package dev.ytchatplayer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.SystemClock
import android.util.Log
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.MediaSession as GeckoMediaSession

/** Owns the player, independent of whether its Activity or PiP window is visible. */
class PlaybackService : Service() {
    inner class LocalBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    lateinit var runtime: GeckoRuntime
        private set
    lateinit var videoSession: GeckoSession
        private set
    var videoUrl: String? = null
        private set
    val videoHistory = mutableListOf<String>()
    var canGoBack = false
    var canGoForward = false
    var paused = true
        private set
    val canControl get() = geckoMedia != null
    var onPlaybackChanged: (() -> Unit)? = null

    private lateinit var androidSession: MediaSession
    private lateinit var notifications: NotificationManager
    private lateinit var openPlayer: PendingIntent
    private var geckoMedia: GeckoMediaSession? = null
    private var notificationVisible = false
    private var foreground = false
    private var title: String? = null
    private var artwork: Bitmap? = null
    private var artworkRequest = 0
    private var durationMs: Long? = null
    private var isLive: Boolean? = null
    private val seekDurationMs get() = durationMs?.takeIf { isLive == false }
    private var positionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN
    private var positionUpdatedAt = 0L
    private var playbackRate = 1f

    override fun onCreate() {
        super.onCreate()
        // Gecko permits one runtime per process; a new service reuses that runtime.
        runtime = GeckoRuntime.getDefault(applicationContext)
        applyBrowserAppearance()
        runtime.settings.remoteDebuggingEnabled = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "メディア再生", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        openPlayer = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).apply {
                action = ACTION_SHOW_PLAYER
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        androidSession = MediaSession(this, "YTFlow").apply {
            setSessionActivity(openPlayer)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = requestPaused(false)
                override fun onPause() = requestPaused(true)
                override fun onStop() = requestPaused(true)
                override fun onSeekTo(pos: Long) {
                    val duration = seekDurationMs ?: return
                    geckoMedia?.seekTo(pos.coerceIn(0L, duration) / 1000.0, false)
                }
            })
        }
        createVideoSession()
    }

    override fun onBind(intent: Intent) = LocalBinder()

    fun applyBrowserAppearance() {
        runtime.settings.setPreferredColorScheme(AppAppearance.read(this).colorScheme)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE || (intent?.action == ACTION_SHOW && notificationVisible)) {
            notificationVisible = true
            startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        } else if (intent?.action == ACTION_SHOW) {
            stopSelf(startId)
        }
        if (intent?.action == ACTION_TOGGLE) requestPaused(!paused)
        // Never restart a fresh browser and autoplay after a process death.
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing the app also terminates Gecko child processes on some devices.
        // Treat it as an explicit exit, not as a resumable paused player.
        stopSelf()
    }

    override fun onDestroy() {
        onPlaybackChanged = null
        artworkRequest++
        videoSession.mediaSessionDelegate = null
        videoSession.close()
        androidSession.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        notifications.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    fun resetVideoSession() {
        hideNotification()
        videoSession.mediaSessionDelegate = null
        videoSession.close()
        geckoMedia = null
        paused = true
        videoUrl = null
        videoHistory.clear()
        canGoBack = false
        canGoForward = false
        clearMetadata()
        val listener = onPlaybackChanged
        createVideoSession()
        onPlaybackChanged = listener
    }

    private fun createVideoSession() {
        videoSession = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .useTrackingProtection(false)
                .build(),
        ).apply {
            permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onContentPermissionRequest(
                    session: GeckoSession,
                    request: GeckoSession.PermissionDelegate.ContentPermission,
                ): GeckoResult<Int> = GeckoResult.fromValue(
                    if (request.permission == 4 || request.permission == 5) {
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    } else GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT,
                )
            }
            mediaSessionDelegate = object : GeckoMediaSession.Delegate {
                override fun onActivated(session: GeckoSession, mediaSession: GeckoMediaSession) {
                    geckoMedia = mediaSession
                    refreshNotification()
                }

                override fun onDeactivated(session: GeckoSession, mediaSession: GeckoMediaSession) {
                    geckoMedia = null
                    playbackChanged(true)
                }

                override fun onPlay(session: GeckoSession, mediaSession: GeckoMediaSession) = playbackChanged(false)
                override fun onPause(session: GeckoSession, mediaSession: GeckoMediaSession) = playbackChanged(true)
                override fun onStop(session: GeckoSession, mediaSession: GeckoMediaSession) = playbackChanged(true)

                override fun onMetadata(session: GeckoSession, mediaSession: GeckoMediaSession, meta: GeckoMediaSession.Metadata) {
                    title = meta.title?.trim()?.takeIf { it.isNotEmpty() }
                    artwork = null
                    val request = ++artworkRequest
                    refreshNotification()
                    meta.artwork?.getBitmap(512)?.accept(
                        { bitmap ->
                            if (session === videoSession && request == artworkRequest) {
                                artwork = bitmap
                                refreshNotification()
                            }
                        },
                        { error -> Log.w(TAG, "Media artwork unavailable", error) },
                    )
                }

                override fun onPositionState(session: GeckoSession, mediaSession: GeckoMediaSession, state: GeckoMediaSession.PositionState) {
                    val previousDuration = durationMs
                    durationMs = state.duration.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() }
                    positionMs = state.position.takeIf { it.isFinite() && it >= 0 }?.let { (it * 1000).toLong() }
                        ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN
                    positionUpdatedAt = SystemClock.elapsedRealtime()
                    playbackRate = state.playbackRate.toFloat()
                    updatePlaybackState()
                    if (previousDuration != durationMs) updateMetadata()
                }
            }
            open(runtime)
        }
        detachUi()
    }

    /** UI delegates must not retain an Activity after its window has gone. */
    fun detachUi() {
        onPlaybackChanged = null
        // Hidden tabs keep audio, but must stop rendering into a destroyed window.
        videoSession.setActive(false)
        videoSession.progressDelegate = null
        videoSession.contentDelegate = null
        videoSession.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.AlertPrompt)
                : GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                savePlaybackAlert(prompt.message.orEmpty())
                return GeckoResult.fromValue(prompt.dismiss())
            }
        }
        videoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(session: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val uri = Uri.parse(request.uri)
                val allowed = uri.scheme == "https" && uri.host in setOf("www.youtube.com", "m.youtube.com", "youtube.com", "accounts.google.com")
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                if (url != null) trackVideoUrl(url)
            }

            override fun onCanGoBack(session: GeckoSession, value: Boolean) { canGoBack = value }
            override fun onCanGoForward(session: GeckoSession, value: Boolean) { canGoForward = value }
        }
    }

    fun savePlaybackAlert(message: String): Boolean {
        if (!message.startsWith("ytcc-save-state:")) return false
        val data = Uri.parse("https://local/?${message.substringAfter(':')}")
        val url = data.getQueryParameter("url") ?: return true
        if (Uri.parse(url).host !in setOf("www.youtube.com", "youtube.com", "m.youtube.com")) return true
        val live = data.getQueryParameter("live")?.toBooleanStrictOrNull()
        if (live != null && isLive != live) {
            isLive = live
            refreshNotification()
        }
        getSharedPreferences("debug_toggles", MODE_PRIVATE).edit()
            .putString("last_video_url", url)
            .putInt("last_video_time", data.getQueryParameter("t")?.toIntOrNull() ?: 0)
            .apply()
        return true
    }

    fun trackVideoUrl(url: String) {
        if (Uri.parse(url).getQueryParameter("v") == null) return
        if (Uri.parse(videoUrl.orEmpty()).getQueryParameter("v") != Uri.parse(url).getQueryParameter("v")) clearMetadata()
        videoUrl = url
        val existing = videoHistory.indexOfLast { it == url }
        if (existing >= 0) {
            while (videoHistory.size > existing + 1) videoHistory.removeAt(videoHistory.lastIndex)
        } else videoHistory.add(url)
    }

    private fun clearMetadata() {
        title = null
        artwork = null
        artworkRequest++
        durationMs = null
        isLive = null
        positionMs = PlaybackState.PLAYBACK_POSITION_UNKNOWN
        positionUpdatedAt = SystemClock.elapsedRealtime()
        playbackRate = 1f
    }

    fun requestPaused(value: Boolean) {
        // Commands never optimistically change the observed playback state.
        if (value) geckoMedia?.pause() else geckoMedia?.play()
    }

    private fun playbackChanged(value: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!paused && positionMs != PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
            positionMs += ((now - positionUpdatedAt) * playbackRate).toLong()
            durationMs?.let { positionMs = positionMs.coerceAtMost(it) }
        }
        positionUpdatedAt = now
        paused = value
        Log.d(TAG, "Gecko playback: ${if (paused) "paused" else "playing"}")
        refreshNotification()
        onPlaybackChanged?.invoke()
    }

    fun showNotification() {
        notificationVisible = true
        if (!foreground) startForegroundService(Intent(this, PlaybackService::class.java).setAction(ACTION_SHOW))
        refreshNotification()
    }

    private fun refreshNotification() {
        androidSession.isActive = notificationVisible && canControl
        updatePlaybackState()
        updateMetadata()
        if (notificationVisible) notifications.notify(NOTIFICATION_ID, createNotification())
    }

    fun hideNotification() {
        notificationVisible = false
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
        }
        notifications.cancel(NOTIFICATION_ID)
        androidSession.isActive = false
    }

    private fun displayTitle(): String = title?.let { "YTFlow: $it" } ?: "YTFlow"

    private fun updateMetadata() {
        androidSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, displayTitle())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "YTFlow")
                .apply {
                    artwork?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
                    seekDurationMs?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
                }.build(),
        )
    }

    private fun updatePlaybackState() {
        androidSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(if (canControl) PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                    (if (seekDurationMs != null) PlaybackState.ACTION_SEEK_TO else 0L) else 0L)
                .setState(if (!canControl) PlaybackState.STATE_NONE else if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING,
                    if (seekDurationMs != null) positionMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (paused) 0f else playbackRate, positionUpdatedAt)
                .build(),
        )
    }

    private fun createNotification(): Notification {
        val action = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
            if (paused) "再生" else "一時停止", toggleIntent(this),
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(artwork)
            .setContentTitle(displayTitle())
            .setContentText("YTFlow")
            .setContentIntent(openPlayer)
            .setShowWhen(false)
            .setOngoing(!paused)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .apply { if (canControl) addAction(action) }
            .setStyle(Notification.MediaStyle().setMediaSession(androidSession.sessionToken).apply {
                if (canControl) setShowActionsInCompactView(0)
            })
            .build()
    }

    companion object {
        private const val TAG = "YTFlowPlayback"
        private const val CHANNEL_ID = "ytchat_media"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_TOGGLE = "dev.ytchatplayer.app.TOGGLE_PLAYBACK"
        private const val ACTION_SHOW = "dev.ytchatplayer.app.SHOW_MEDIA"
        const val ACTION_SHOW_PLAYER = "dev.ytchatplayer.app.SHOW_PLAYER"

        fun toggleIntent(context: android.content.Context): PendingIntent = PendingIntent.getForegroundService(
            context, 2, Intent(context, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
