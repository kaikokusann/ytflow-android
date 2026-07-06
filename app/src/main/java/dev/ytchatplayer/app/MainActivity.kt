package dev.ytchatplayer.app

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.Rational
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import android.app.AlertDialog
import android.content.DialogInterface
import java.net.URLEncoder
import org.mozilla.geckoview.AllowOrDeny

class MainActivity : Activity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var mobileSession: GeckoSession
    private lateinit var videoSession: GeckoSession
    private lateinit var geckoView: GeckoView
    private lateinit var topBar: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var chatOnlyBar: LinearLayout
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var btnFullscreen: ImageButton

    private var yccExtension: WebExtension? = null
    private var lcfExtension: WebExtension? = null

    private var activeSurface = BrowserSurface.MOBILE
    private var mobileUrl: String = HOME_URL
    private var videoUrl: String = TEST_URL
    private var videoTitle: String? = null
    private var mobileMode = BrowserMode.MOBILE
    private var videoMode = BrowserMode.DESKTOP
    private var mobileCanGoBack = false
    private var mobileCanGoForward = false
    private var videoCanGoBack = false
    private var videoCanGoForward = false
    private val videoHistory = mutableListOf<String>()
    private var fullScreen = false
    private var inPictureInPicture = false
    private var pipPlaybackPaused = false
    private var mediaNotificationVisible = false
    private var mediaSession: MediaSession? = null
    private lateinit var notificationManager: NotificationManager
    private var youtubeChatCleanerEnabled = true
    private var liveChatFlusherEnabled = true
    private var chatOnlyModeEnabled = false
    private var pausePlaybackOnPipClose = true
    private var suppressFailedPageStopUntil = 0L
    private var currentOsFps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentActivity = this
        prefs = getSharedPreferences("debug_toggles", MODE_PRIVATE)
        createMediaNotificationSupport()
        requestNotificationPermissionIfNeeded()
        youtubeChatCleanerEnabled = prefs.getBoolean(PREF_YCC_ENABLED, true)
        liveChatFlusherEnabled = prefs.getBoolean(PREF_LCF_ENABLED, true)
        chatOnlyModeEnabled = false
        prefs.edit().putBoolean(PREF_CHAT_ONLY_MODE, false).apply()
        pausePlaybackOnPipClose = prefs.getBoolean(PREF_PAUSE_ON_PIP_CLOSE, true)
        currentOsFps = prefs.getInt(PREF_FPS_LIMIT, 0)
        applyEffectiveOsFps(showToast = false)
        setContentView(createUi())
        createBrowser()
        installBuiltInExtensions()
        loadInitialUrl(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            ACTION_PIP_TOGGLE_PLAYBACK -> {
                handlePipPlaybackAction()
                return
            }
            ACTION_PIP_CLOSE -> {
                handlePipCloseAction()
                return
            }
        }
        loadInitialUrl(intent)
    }

    override fun onDestroy() {
        if (currentActivity === this) currentActivity = null
        hideMediaNotification()
        mediaSession?.release()
        mediaSession = null
        if (::runtime.isInitialized && ::mobileSession.isInitialized && ::videoSession.isInitialized) {
            runtime.webExtensionController.setTabActive(mobileSession, false)
            runtime.webExtensionController.setTabActive(videoSession, false)
            mobileSession.close()
            videoSession.close()
            runtime.shutdown()
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::geckoView.isInitialized) {
            geckoView.requestFocus()
        }
        // Force sync states upon resume to recover from suspended JavaScript state
        setPageAppFullScreenFlag(fullScreen)
        setPageAppPictureInPictureFlag(inPictureInPicture)
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            if (fullScreen) {
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
            }
            // Force a resize event to ensure video player layout recovers from PiP or SystemUI interruptions
            if (::videoSession.isInitialized && activeSurface == BrowserSurface.VIDEO) {
                val script = "window.dispatchEvent(new Event('resize'));"
                videoSession.loadUri("javascript:${Uri.encode(script)}")
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullScreen) {
            exitPageFullScreen()
        } else if (::mobileSession.isInitialized && ::videoSession.isInitialized) {
            handleBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (shouldEnterPictureInPicture()) enterAppPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        setPageAppPictureInPictureFlag(isInPictureInPictureMode)
        if (isInPictureInPictureMode) showOrUpdateMediaNotification()
        updateChromeForPictureInPicture()
    }

    private fun createMediaNotificationSupport() {
        notificationManager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            MEDIA_CHANNEL_ID,
            "メディア再生",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "YouTube再生中の操作"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
        mediaSession = MediaSession(this, "YTChat").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    setVideoPlaybackPaused(false)
                }

                override fun onPause() {
                    setVideoPlaybackPaused(true)
                }

                override fun onStop() {
                    setVideoPlaybackPaused(true)
                }
            })
            isActive = true
        }
        updateMediaSessionState()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun createUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BLACK)
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), statusBarHeight() + dp(7), dp(8), dp(7))
            setBackgroundColor(BLACK)
        }
        val addressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedDrawable(
                color = 0xFF202124.toInt(),
                radiusDp = 24,
                strokeColor = 0xFF3C4043.toInt(),
                strokeWidthDp = 1,
            )
            setPadding(dp(14), 0, dp(4), 0)
        }
        address = EditText(this).apply {
            setSingleLine(true)
            textSize = 15f
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(Color.WHITE)
            setHintTextColor(0xFFBDC1C6.toInt())
            background = null
            hint = "YouTube URL / 検索"
            setPadding(0, 0, dp(8), 0)
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_GO) {
                    openAddressOrSearch(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        addressBar.addView(
            address,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
        )
        addressBar.addView(
            toolbarIconButton("公式アプリで開く", R.drawable.ic_open_in_new) {
                val script = """
                    (() => {
                        let t = 0;
                        try {
                            const video = document.querySelector('video');
                            if (video) {
                                t = Math.floor(video.currentTime);
                                video.pause();
                            }
                            const player = document.querySelector('#movie_player');
                            if (player && typeof player.pauseVideo === 'function') {
                                player.pauseVideo();
                            }
                        } catch (_) {}
                        alert('ytcc-open-in-app:url=' + encodeURIComponent(window.location.href) + '&t=' + t);
                    })()
                """.trimIndent()
                activeSession().loadUri("javascript:${Uri.encode(script)}")
            }.apply {
                background = roundedDrawable(0xFF303134.toInt(), 20)
                setPadding(dp(9), dp(9), dp(9), dp(9))
            },
            LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(4) },
        )
        addressBar.addView(
            toolbarIconButton("開く", R.drawable.ic_arrow_forward) {
                openAddressOrSearch(address.text.toString())
            }.apply {
                background = roundedDrawable(0xFF303134.toInt(), 20)
                setPadding(dp(9), dp(9), dp(9), dp(9))
            },
            LinearLayout.LayoutParams(dp(38), dp(38)),
        )
        topBar.addView(
            addressBar,
            LinearLayout.LayoutParams(0, dp(46), 1f),
        )
        root.addView(topBar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        geckoView = GeckoView(this)
        root.addView(
            geckoView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        status = TextView(this).apply {
            text = "起動中"
            textSize = 12f
            setTextColor(0xFFB0B0B0.toInt())
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(BLACK)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = true
            maxLines = 1
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL
        }

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(8))
            setBackgroundColor(BLACK)
        }
        fun addNavButton(button: View) {
            navBar.addView(
                button,
                LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    setMargins(dp(2), 0, dp(2), 0)
                },
            )
        }

        addNavButton(toolbarIconButton("再生/停止", R.drawable.ic_play_pause) { triggerYouTubePlayPauseShortcut() })
        addNavButton(toolbarIconButton("ホーム", R.drawable.ic_youtube_home) { loadUrl(HOME_URL) })
        addNavButton(toolbarIconButton("登録チャンネル", R.drawable.ic_youtube_subscriptions) {
            loadUrl("https://m.youtube.com/feed/subscriptions")
        })
        addNavButton(toolbarIconButton("履歴", R.drawable.ic_history) {
            loadUrl("https://m.youtube.com/feed/history")
        })
        addNavButton(toolbarIconButton("戻る", R.drawable.ic_arrow_back) { handleBack() })
        addNavButton(toolbarIconButton("進む", R.drawable.ic_arrow_forward) { handleForward() })
        btnFullscreen = toolbarIconButton("全画面表示", R.drawable.ic_fullscreen) { triggerYouTubeFullScreen() }
        addNavButton(btnFullscreen)

        val btnSettings = toolbarButton("⚙", onClick = { showSettingsMenu() })
        addNavButton(btnSettings)

        addNavButton(toolbarIconButton("更新", R.drawable.ic_refresh) { activeSession().reload() })
        root.addView(navBar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(status, LinearLayout.LayoutParams.MATCH_PARENT, dp(30))

        chatOnlyBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(BLACK)
            visibility = View.GONE
        }
        chatOnlyBar.addView(
            toolbarIconButton("再生/停止", R.drawable.ic_play_pause) { triggerYouTubePlayPauseShortcut() },
            LinearLayout.LayoutParams(dp(56), dp(44)).apply {
                marginEnd = dp(8)
            },
        )
        chatOnlyBar.addView(
            toolbarButton("チャット専用を終了", onClick = { setChatOnlyMode(false) }).apply {
                textSize = 14f
                setPadding(dp(18), dp(8), dp(18), dp(8))
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)),
        )
        root.addView(chatOnlyBar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        return root
    }

    private fun showSettingsMenu() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }

        fun createSettingRow(
            title: String,
            isChecked: Boolean,
            onCheckedChange: (Boolean) -> Unit,
            onSettingsClick: () -> Unit
        ): LinearLayout {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(12), 0, dp(12))
            }
            val switch = Switch(this@MainActivity).apply {
                text = title
                textSize = 16f
                this.isChecked = isChecked
                setOnCheckedChangeListener { _, checked -> onCheckedChange(checked) }
            }
            val settingsBtn = Button(this@MainActivity).apply {
                text = "設定"
                textSize = 12f
                setPadding(dp(16), dp(8), dp(16), dp(8))
                minHeight = 0
                minWidth = 0
                setTextColor(Color.parseColor("#1976D2"))
                applyModernRipple(cornerRadiusDp = 16, bgColor = 0x101976D2)
                setOnClickListener { onSettingsClick() }
            }
            row.addView(switch, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(settingsBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
            return row
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("アプリの設定")
            .setView(root)
            .setPositiveButton("閉じる", null)
            .create()

        root.addView(createSettingRow(
            title = "YouTube Chat Cleaner",
            isChecked = youtubeChatCleanerEnabled,
            onCheckedChange = { checked -> setPageFlag(PREF_YCC_ENABLED, "YouTubeChatCleaner", checked) },
            onSettingsClick = {
                dialog.dismiss()
                showExtensionPopup(yccExtension, "popup.html", "YouTubeChatCleaner", showSaveButton = false, reloadAfterSave = false)
            }
        ))

        root.addView(createSettingRow(
            title = "LiveChat Flusher",
            isChecked = liveChatFlusherEnabled,
            onCheckedChange = { checked -> setPageFlag(PREF_LCF_ENABLED, "LiveChat Flusher", checked) },
            onSettingsClick = {
                dialog.dismiss()
                showExtensionPopup(lcfExtension, "options/options.html", "LiveChat Flusher", showSaveButton = true, reloadAfterSave = true)
            }
        ))

        root.addView(createSwitchOnlyRow(
            title = "通常チャット専用モード",
            isChecked = chatOnlyModeEnabled,
            isEnabled = activeSurface == BrowserSurface.VIDEO,
            onCheckedChange = { checked -> setChatOnlyMode(checked) },
        ))

        root.addView(createSwitchOnlyRow(
            title = "PiPを×で閉じたら一時停止",
            isChecked = pausePlaybackOnPipClose,
            onCheckedChange = { checked ->
                pausePlaybackOnPipClose = checked
                prefs.edit().putBoolean(PREF_PAUSE_ON_PIP_CLOSE, checked).apply()
                status.text = "PiP終了時の一時停止: ${if (checked) "ON" else "OFF"}"
            },
        ))

        // 区切り線
        val divider = View(this).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                setMargins(0, dp(12), 0, dp(12))
            }
        }
        root.addView(divider)

        // FPS設定行
        val fpsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val fpsText = TextView(this).apply {
            text = "FPS制限機能 (負担軽減)\n現在: ${if (currentOsFps > 0) "${currentOsFps}fps" else "自動"}"
            textSize = 16f
            setTextColor(Color.BLACK)
        }
        val fpsBtn = Button(this).apply {
            text = "変更"
            textSize = 12f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            minHeight = 0
            minWidth = 0
            setTextColor(Color.parseColor("#1976D2"))
            applyModernRipple(cornerRadiusDp = 16, bgColor = 0x101976D2)
            setOnClickListener {
                dialog.dismiss()
                showOsFpsDialog()
            }
        }
        fpsRow.addView(fpsText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fpsRow.addView(fpsBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        root.addView(fpsRow)

        dialog.show()
    }

    private fun createSwitchOnlyRow(
        title: String,
        isChecked: Boolean,
        isEnabled: Boolean = true,
        onCheckedChange: (Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            alpha = if (isEnabled) 1f else 0.45f
        }
        val switch = Switch(this).apply {
            text = title
            textSize = 16f
            this.isEnabled = isEnabled
            this.isChecked = isChecked
            setOnCheckedChangeListener { _, checked -> onCheckedChange(checked) }
        }
        row.addView(switch, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun showOsFpsDialog() {
        val options = arrayOf("自動 (制限なし)", "120 fps", "60 fps", "30 fps", "15 fps")
        val values = intArrayOf(0, 120, 60, 30, 15)
        val selectedIndex = values.indexOf(currentOsFps).takeIf { it >= 0 } ?: 0
        
        val titleView = TextView(this).apply {
            text = "FPS制限機能\n\n画面の滑らかさを制限することで、スマホの発熱や負担を軽くすることができます。"
            setPadding(dp(24), dp(24), dp(24), dp(8))
            textSize = 16f
        }

        AlertDialog.Builder(this)
            .setCustomTitle(titleView)
            .setSingleChoiceItems(options, selectedIndex) { dialog, which ->
                currentOsFps = values[which]
                prefs.edit().putInt(PREF_FPS_LIMIT, currentOsFps).apply()
                applyEffectiveOsFps(showToast = true)
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun applyOsFps(fps: Int, showToast: Boolean = false) {
        // モバイルセッション（ホームや検索画面）表示中は制限を無効（0）にする
        val targetFps = if (activeSurface == BrowserSurface.MOBILE) 0 else fps
        val params = window.attributes
        params.preferredRefreshRate = targetFps.toFloat()
        window.attributes = params
        if (showToast && fps > 0) {
            Toast.makeText(this, "動画再生時のFPS制限を ${fps} に設定しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyEffectiveOsFps(showToast: Boolean = false) {
        val forcedForChatOnly = chatOnlyModeEnabled && activeSurface == BrowserSurface.VIDEO
        val fps = if (forcedForChatOnly) {
            CHAT_ONLY_FORCED_FPS
        } else {
            currentOsFps
        }
        applyOsFps(fps, showToast = showToast && !forcedForChatOnly)
        if (showToast && forcedForChatOnly) {
            Toast.makeText(
                this,
                "通常チャット専用モード中は ${CHAT_ONLY_FORCED_FPS}fps に固定します",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun toolbarButton(label: String, onClick: () -> Unit, onLongClick: (() -> Boolean)? = null): Button =
        Button(this).apply {
            text = label
            textSize = 10f
            minHeight = 0
            minWidth = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setTextColor(Color.WHITE)
            // 透明ではなく、少し明るいグレーの角丸背景にする
            applyModernRipple(cornerRadiusDp = 12, bgColor = 0xFF2A2A2A.toInt())
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick() }
            }
        }

    private fun toolbarIconButton(label: String, iconRes: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            contentDescription = label
            setImageResource(iconRes)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            setMinimumHeight(0)
            setMinimumWidth(0)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            
            // 透明ではなく、少し明るいグレーの角丸背景にする
            applyModernRipple(cornerRadiusDp = 12, bgColor = 0xFF2A2A2A.toInt())
            
            setOnClickListener { onClick() }
        }

    private fun roundedDrawable(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null,
        strokeWidthDp: Int = 0,
    ): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null && strokeWidthDp > 0) {
                setStroke(dp(strokeWidthDp), strokeColor)
            }
        }

    private fun createBrowser() {
        runtime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .remoteDebuggingEnabled(true)
                .build(),
        )
        mobileSession = createManagedSession(BrowserSurface.MOBILE, BrowserMode.MOBILE)
        videoSession = createManagedSession(BrowserSurface.VIDEO, BrowserMode.DESKTOP)
        mobileSession.open(runtime)
        videoSession.open(runtime)
        geckoView.setSession(mobileSession)
        runtime.webExtensionController.setTabActive(mobileSession, true)
        runtime.webExtensionController.setTabActive(videoSession, false)
    }

    private fun createManagedSession(surface: BrowserSurface, initialMode: BrowserMode): GeckoSession {
        val session = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(
                    if (initialMode == BrowserMode.DESKTOP) {
                        GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    } else {
                        GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                    }
                )
                .viewportMode(
                    if (initialMode == BrowserMode.DESKTOP) {
                        GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                    } else {
                        GeckoSessionSettings.VIEWPORT_MODE_MOBILE
                    }
                )
                .useTrackingProtection(false)
                .build(),
        )
        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                request: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                // 4 = PERMISSION_AUTOPLAY_INAUDIBLE, 5 = PERMISSION_AUTOPLAY_AUDIBLE
                if (request.permission == 4 || request.permission == 5) {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
                }
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT)
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                if (shouldRememberSurfaceUrl(surface, url)) updateSurfaceUrl(surface, url)
                if (surface == activeSurface) {
                    address.setText(urlForAddress(surface, url))
                    status.text = "読み込み中: $url"
                }
                if (surface == BrowserSurface.VIDEO) trackVideoUrl(url)
            }
 
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                if (surface == activeSurface) status.text = "読み込み中: $progress%"
            }
 
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (surface != activeSurface) return
                if (!success && android.os.SystemClock.uptimeMillis() < suppressFailedPageStopUntil) {
                    status.text = "再読み込み中"
                    return
                }
                status.text = if (success) "読み込み完了" else "読み込み失敗"
                if (success && surface == BrowserSurface.VIDEO) {
                    val script = """
                        if (!window.__ytcc_autosave_timer) {
                            window.__ytcc_autosave_timer = setInterval(() => {
                                const video = document.querySelector('#movie_player video') || document.querySelector('video');
                                if (video && !video.paused && video.currentTime > 0) {
                                    try {
                                        const u = new URL(window.location.href);
                                        u.searchParams.delete('t');
                                        alert('ytcc-save-state:t=' + Math.floor(video.currentTime) + '&url=' + encodeURIComponent(u.toString()));
                                    } catch(e) {}
                                }
                            }, 5000);
                        }
                        (() => {
                            const sendTitle = () => {
                                const rawTitle = document.title || '';
                                const title = rawTitle.replace(/\s*-\s*YouTube\s*$/i, '').trim();
                                if (title) {
                                    alert('ytcc-video-title:title=' + encodeURIComponent(title));
                                }
                            };
                            sendTitle();
                            setTimeout(sendTitle, 1200);
                        })();
                    """.trimIndent()
                    session.loadUri("javascript:${Uri.encode(script)}")
                }
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFullScreen(session: GeckoSession, enabled: Boolean) {
                Log.i(TAG, "Page fullscreen request: $enabled")
                if (surface == activeSurface) runOnUiThread { setAppFullScreen(enabled) }
            }
        }
        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val message = prompt.message ?: ""
                if (message.startsWith("ytcc-save-state:")) {
                    val data = message.substringAfter("ytcc-save-state:")
                    val params = data.split("&").associate {
                        val parts = it.split("=")
                        if (parts.size == 2) parts[0] to parts[1] else it to ""
                    }
                    val url = params["url"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    val t = params["t"]?.toIntOrNull() ?: 0
                    if (url != null && isYouTubeUrl(url) && browserModeFor(url) == BrowserMode.DESKTOP) {
                        prefs.edit()
                            .putString(PREF_LAST_VIDEO_URL, url)
                            .putInt(PREF_LAST_VIDEO_TIME, t)
                            .apply()
                    }
                    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                    result.complete(prompt.dismiss())
                    return result
                }
                if (message.startsWith("ytcc-open-in-app:")) {
                    val data = message.substringAfter("ytcc-open-in-app:")
                    val params = data.split("&").associate {
                        val parts = it.split("=")
                        if (parts.size == 2) parts[0] to parts[1] else it to ""
                    }
                    val url = params["url"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                    val t = params["t"]?.toIntOrNull() ?: 0
                    if (url != null && isYouTubeUrl(url)) {
                        val finalUrl = if (t > 0) "$url&t=${t}s" else url
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(finalUrl))
                        try {
                            intent.setPackage("com.google.android.youtube")
                            startActivity(intent)
                        } catch (e: android.content.ActivityNotFoundException) {
                            intent.setPackage(null)
                            runCatching { startActivity(intent) }
                        }
                    }
                    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                    result.complete(prompt.dismiss())
                    return result
                }
                if (message.startsWith("ytcc-video-title:")) {
                    val data = message.substringAfter("ytcc-video-title:")
                    val params = data.split("&").associate {
                        val parts = it.split("=")
                        if (parts.size == 2) parts[0] to parts[1] else it to ""
                    }
                    val title = params["title"]
                        ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                    if (title != null) {
                        videoTitle = title
                        if (mediaNotificationVisible) showOrUpdateMediaNotification()
                    }
                    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                    result.complete(prompt.dismiss())
                    return result
                }
                return super.onAlertPrompt(session, prompt)
            }

            override fun onChoicePrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.ChoicePrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                val choices = prompt.choices
                val items = choices.map { it.label }.toTypedArray()
                val selectedIndex = choices.indexOfFirst { it.selected }.let { if (it == -1) 0 else it }
 
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(prompt.title ?: "選択してください")
                        .setSingleChoiceItems(items, selectedIndex) { dialogInterface, which ->
                            val selectedChoice = choices[which]
                            result.complete(prompt.confirm(selectedChoice))
                            dialogInterface.dismiss()
                        }
                        .setOnCancelListener {
                            result.complete(prompt.dismiss())
                        }
                        .show()
                }
                return result
            }
        }
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                val url = request.uri
                Log.d(TAG, "NavigationDelegate[$surface]: onLoadRequest: $url")
 
                if (isInternalGeckoUrl(url)) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
 
                if (!isAllowedTopLevelUrl(url)) {
                    runOnUiThread {
                        openExternal(url)
                        switchToSurface(BrowserSurface.MOBILE)
                        loadUrlInto(BrowserSurface.MOBILE, HOME_URL)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                val handoffVideoUrl = handoffVideoUrl(url)
                if (handoffVideoUrl != null) {
                    runOnUiThread { openVideoFromMobile(handoffVideoUrl, restoreMobile = false) }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
 
                val normalized = normalizeYouTubeUrl(url)
                val targetSurface = surfaceForUrl(normalized)
                val desiredMode = browserModeFor(normalized)

                if (surface == BrowserSurface.MOBILE && targetSurface == BrowserSurface.VIDEO) {
                    runOnUiThread { openVideoFromMobile(normalized) }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                if (surface == BrowserSurface.VIDEO && targetSurface == BrowserSurface.MOBILE) {
                    runOnUiThread {
                        switchToSurface(BrowserSurface.MOBILE)
                        loadUrlInto(BrowserSurface.MOBILE, normalized)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
 
                if (normalized != url) {
                    runOnUiThread {
                        applyBrowserMode(surface, desiredMode)
                        updateSurfaceUrl(surface, normalized)
                        if (surface == activeSurface) {
                            address.setText(normalized)
                            status.text = "リダイレクト中"
                        }
                        suppressFailedPageStopUntil = android.os.SystemClock.uptimeMillis() + 2500
                        geckoView.postDelayed({ loadUrlInto(surface, normalized) }, 80)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                if (desiredMode != modeFor(surface)) {
                    runOnUiThread {
                        applyBrowserMode(surface, desiredMode)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW)
                }
 
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                val nextUrl = url ?: return
                if (shouldRememberSurfaceUrl(surface, nextUrl)) updateSurfaceUrl(surface, nextUrl)
                if (surface == activeSurface) address.setText(urlForAddress(surface, nextUrl))
                if (surface == BrowserSurface.VIDEO) trackVideoUrl(nextUrl)
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                if (surface == BrowserSurface.MOBILE) mobileCanGoBack = canGoBack else videoCanGoBack = canGoBack
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                if (surface == BrowserSurface.MOBILE) mobileCanGoForward = canGoForward else videoCanGoForward = canGoForward
            }
        }
        return session
    }

    private fun installBuiltInExtensions() {
        installExtension(
            "resource://android/assets/extensions/youtube_router/",
            "youtube-router@local",
            "YouTubeルーター",
            {}
        )
        installExtension(
            "resource://android/assets/extensions/live_chat_flusher/",
            "youtube-live-chat-flusher@local",
            "LiveChat Flusher",
            { ext -> lcfExtension = ext }
        )
        installExtension(
            "resource://android/assets/extensions/youtube_chat_cleaner/",
            "youtube-chat-cleaner@local",
            "YouTubeChatCleaner",
            { ext -> yccExtension = ext }
        )
    }

    private fun installExtension(assetUri: String, id: String, label: String, onSuccess: (WebExtension) -> Unit) {
        runtime.webExtensionController.ensureBuiltIn(assetUri, id).accept(
            { ext ->
                runOnUiThread {
                    status.text = "$label を有効化しました"
                    if (ext != null) {
                        onSuccess(ext)
                    }
                }
            },
            { error ->
                Log.e(TAG, "$label install failed", error)
                val message = "$label の有効化に失敗: ${error?.message ?: "unknown"}"
                runOnUiThread {
                    status.text = message
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    private fun loadInitialUrl(intent: Intent?) {
        val intentUrl = intent?.dataString?.takeIf { it.startsWith("https://") }
        if (intentUrl != null) {
            loadUrl(intentUrl)
            return
        }
        val lastUrl = prefs.getString(PREF_LAST_VIDEO_URL, null)
        val lastTime = prefs.getInt(PREF_LAST_VIDEO_TIME, 0)
        if (lastUrl != null) {
            val urlWithTime = if (lastTime > 0) {
                if (lastUrl.contains("?")) "$lastUrl&t=${lastTime}s" else "$lastUrl?t=${lastTime}s"
            } else lastUrl
            loadUrl(urlWithTime)
        } else {
            loadUrl(HOME_URL)
        }
    }

    private fun openAddressOrSearch(rawInput: String) {
        val input = rawInput.trim()
        if (input.isBlank()) {
            loadUrl(HOME_URL)
            return
        }
        val url = when {
            input.startsWith("https://") || input.startsWith("http://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> {
                val query = URLEncoder.encode(input, "UTF-8")
                "https://m.youtube.com/results?search_query=$query"
            }
        }
        loadUrl(url)
    }

    private fun activeSession(): GeckoSession =
        if (activeSurface == BrowserSurface.VIDEO) videoSession else mobileSession

    private fun currentUrl(): String =
        if (activeSurface == BrowserSurface.VIDEO) videoUrl else mobileUrl

    private fun sessionFor(surface: BrowserSurface): GeckoSession =
        if (surface == BrowserSurface.VIDEO) videoSession else mobileSession

    private fun updateSurfaceUrl(surface: BrowserSurface, url: String) {
        if (surface == BrowserSurface.VIDEO) videoUrl = url else mobileUrl = url
    }

    private fun shouldRememberSurfaceUrl(surface: BrowserSurface, url: String): Boolean =
        surface == BrowserSurface.VIDEO || surfaceForUrl(normalizeYouTubeUrl(url)) == BrowserSurface.MOBILE

    private fun urlForAddress(surface: BrowserSurface, candidateUrl: String): String =
        if (surface == BrowserSurface.MOBILE && !shouldRememberSurfaceUrl(surface, candidateUrl)) {
            mobileUrl
        } else {
            candidateUrl
        }

    private fun loadUrlInto(surface: BrowserSurface, url: String, replaceHistory: Boolean = false) {
        val targetUrl = withAppFlags(url)
        updateSurfaceUrl(surface, url)
        if (surface == activeSurface) address.setText(url)
        val targetSession = sessionFor(surface)
        if (replaceHistory) {
            val loader = GeckoSession.Loader()
                .uri(targetUrl)
                .flags(GeckoSession.LOAD_FLAGS_REPLACE_HISTORY)
            targetSession.load(loader)
        } else {
            targetSession.loadUri(targetUrl)
        }
    }
 
    private fun loadUrl(rawUrl: String) {
        val normalized = normalizeYouTubeUrl(rawUrl)
        val surface = surfaceForUrl(normalized)
        switchToSurface(surface)
        applyBrowserMode(surface, browserModeFor(normalized))
        loadUrlInto(surface, normalized)
    }

    private fun openExtensionSettings(extension: WebExtension?, pagePath: String, label: String) {
        val ext = extension
        if (ext == null) {
            Toast.makeText(this, "$label がまだ読み込まれていません", Toast.LENGTH_SHORT).show()
            return
        }
        val settingsUrl = ext.metaData.baseUrl + pagePath
        Log.i(TAG, "Opening extension settings: $settingsUrl")
        switchToSurface(BrowserSurface.MOBILE)
        loadUrlInto(BrowserSurface.MOBILE, settingsUrl)
    }

    private fun showExtensionPopup(
        extension: WebExtension?,
        pagePath: String,
        label: String,
        showSaveButton: Boolean,
        reloadAfterSave: Boolean,
    ) {
        val ext = extension
        if (ext == null) {
            Toast.makeText(this, "$label がまだ読み込まれていません", Toast.LENGTH_SHORT).show()
            return
        }

        val popupSession = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .build(),
        )
        popupSession.open(runtime)
        val savedInPopup = booleanArrayOf(false)
        popupSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url?.contains("#ytchat-saved") == true) {
                    savedInPopup[0] = true
                }
            }
        }

        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFF202124.toInt())
        }
        val title = TextView(this).apply {
            text = label
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(42), 1f))

        header.addView(
            toolbarButton("×", onClick = {
                if (reloadAfterSave && savedInPopup[0]) {
                    activeSession().reload()
                    status.text = "$label の設定を反映するため再読み込みしました"
                }
                dialog.dismiss()
            }),
            LinearLayout.LayoutParams(dp(46), dp(42)),
        )
        root.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val hint = TextView(this).apply {
            text = "有効・無効の切り替えは、メニューの「⚙」ボタンから行えます"
            textSize = 12f
            setTextColor(0xFF5F6368.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xFFF1F3F4.toInt())
        }
        root.addView(hint, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val popupView = GeckoView(this)
        root.addView(
            popupView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.94f).toInt(),
                (resources.displayMetrics.heightPixels * 0.82f).toInt(),
            )
        }
        dialog.setOnDismissListener {
            runCatching { popupSession.stop() }
            runCatching { popupSession.close() }
        }

        popupView.setSession(popupSession)
        popupSession.loadUri(ext.metaData.baseUrl + pagePath)
        dialog.show()
    }

    private fun applyBrowserMode(surface: BrowserSurface, mode: BrowserMode) {
        if (mode == modeFor(surface)) return
        if (surface == BrowserSurface.VIDEO) videoMode = mode else mobileMode = mode
        val settings = sessionFor(surface).settings
        when (mode) {
            BrowserMode.MOBILE -> {
                settings.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                settings.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            }
            BrowserMode.DESKTOP -> {
                settings.setUserAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                settings.setViewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
            }
        }
        status.text = if (mode == BrowserMode.DESKTOP) "PC版YouTubeで読み込みます" else "モバイル版YouTubeで読み込みます"
    }

    private fun modeFor(surface: BrowserSurface): BrowserMode =
        if (surface == BrowserSurface.VIDEO) videoMode else mobileMode

    private fun switchToSurface(surface: BrowserSurface) {
        if (activeSurface == surface) return
        
        val oldSession = activeSession()
        if (surface == BrowserSurface.MOBILE && chatOnlyModeEnabled) {
            setChatOnlyMode(false)
        }
        if (activeSurface == BrowserSurface.VIDEO && surface == BrowserSurface.MOBILE) {
            pauseVideoPlayback()
            hideMediaNotification()
            // 画面を切り替えても、動画セッションの通信権限は3秒間維持する（履歴送信猶予）
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { runtime.webExtensionController.setTabActive(oldSession, false) }
            }, 3000)
        } else {
            runtime.webExtensionController.setTabActive(oldSession, false)
        }
        
        if (fullScreen) setAppFullScreen(false)
        activeSurface = surface
        applyEffectiveOsFps(showToast = false) // 表示画面が変わったのでFPS制限を再評価・適用
        geckoView.setSession(activeSession())
        runtime.webExtensionController.setTabActive(activeSession(), true)
        address.setText(currentUrl())
        status.text = if (surface == BrowserSurface.VIDEO) "PC動画セッション" else "モバイル一覧セッション"
        if (surface == BrowserSurface.VIDEO) showOrUpdateMediaNotification()
        updateChromeForPictureInPicture()
    }

    private fun openVideoFromMobile(rawUrl: String, restoreMobile: Boolean = true) {
        val normalized = normalizeYouTubeUrl(rawUrl)
        if (restoreMobile) restoreMobileSessionAfterVideoIntercept()
        resetVideoSessionForMobileEntry()
        address.setText(normalized)
        switchToSurface(BrowserSurface.VIDEO)
        applyBrowserMode(BrowserSurface.VIDEO, BrowserMode.DESKTOP)
        trackVideoUrl(normalized)
        loadUrlInto(BrowserSurface.VIDEO, normalized)
        showOrUpdateMediaNotification()
    }

    private fun resetVideoSessionForMobileEntry() {
        if (::videoSession.isInitialized) {
            runCatching { pauseVideoPlayback() }
            runCatching { hideMediaNotification() }
            runCatching { runtime.webExtensionController.setTabActive(videoSession, false) }
            runCatching { videoSession.stop() }
            runCatching { videoSession.close() }
        }
        videoSession = createManagedSession(BrowserSurface.VIDEO, BrowserMode.DESKTOP)
        videoSession.open(runtime)
        videoMode = BrowserMode.DESKTOP
        videoCanGoBack = false
        videoCanGoForward = false
        videoHistory.clear()
    }

    private fun restoreMobileSessionAfterVideoIntercept() {
        val restoreUrl = mobileUrl
        mobileSession.stop()
        geckoView.postDelayed({
            if (mobileCanGoBack) {
                mobileSession.goBack()
            } else {
                loadUrlInto(BrowserSurface.MOBILE, restoreUrl)
            }
        }, 80)
    }

    private fun handleBack() {
        when (activeSurface) {
            BrowserSurface.VIDEO -> {
                if (videoHistory.size > 1 && videoCanGoBack) {
                    videoSession.goBack()
                } else {
                    pauseVideoPlayback()
                    switchToSurface(BrowserSurface.MOBILE)
                }
            }
            BrowserSurface.MOBILE -> {
                if (mobileCanGoBack) mobileSession.goBack()
            }
        }
    }

    private fun handleForward() {
        when (activeSurface) {
            BrowserSurface.VIDEO -> {
                if (videoCanGoForward) videoSession.goForward()
            }
            BrowserSurface.MOBILE -> {
                if (mobileCanGoForward) {
                    mobileSession.goForward()
                } else if (videoHistory.isNotEmpty()) {
                    switchToSurface(BrowserSurface.VIDEO)
                }
            }
        }
    }

    private fun trackVideoUrl(rawUrl: String) {
        val normalized = normalizeYouTubeUrl(rawUrl)
        if (surfaceForUrl(normalized) != BrowserSurface.VIDEO) return
        if (normalized != videoUrl) videoTitle = null
        videoUrl = normalized
        val existingIndex = videoHistory.indexOfLast { it == normalized }
        if (existingIndex >= 0) {
            while (videoHistory.size > existingIndex + 1) videoHistory.removeAt(videoHistory.lastIndex)
        } else if (videoHistory.lastOrNull() != normalized) {
            videoHistory.add(normalized)
        }
    }

    private fun pauseVideoPlayback() {
        if (!::videoSession.isInitialized) return
        pipPlaybackPaused = true
        updateMediaSessionState()
        if (mediaNotificationVisible) showOrUpdateMediaNotification()
        val script = """
            (() => {
              for (const video of document.querySelectorAll('video')) {
                try { video.pause(); } catch (_) {}
              }
              const player = document.querySelector('#movie_player');
              if (player && typeof player.pauseVideo === 'function') {
                try { player.pauseVideo(); } catch (_) {}
              }
            })()
        """.trimIndent()
        videoSession.loadUri("javascript:${Uri.encode(script)}")
    }

    private fun setAppFullScreen(enabled: Boolean) {
        if (fullScreen == enabled) return
        fullScreen = enabled
        setPageAppFullScreenFlag(enabled)
        updateChromeForPictureInPicture()
        requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        window.decorView.systemUiVisibility = if (enabled) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    private fun shouldEnterPictureInPicture(): Boolean =
        activeSurface == BrowserSurface.VIDEO && videoHistory.isNotEmpty() && !inPictureInPicture

    private fun enterAppPictureInPicture() {
        if (!::geckoView.isInitialized) return
        runCatching {
            pipPlaybackPaused = false
            setPageAppPictureInPictureFlag(true)
            enterPictureInPictureMode(createPictureInPictureParams())
        }.onFailure {
            setPageAppPictureInPictureFlag(false)
            Log.w(TAG, "Failed to enter Picture-in-Picture", it)
        }
    }

    private fun createPictureInPictureParams(): PictureInPictureParams {
        val toggleIntent = Intent(this, PipActionReceiver::class.java).apply {
            action = ACTION_PIP_TOGGLE_PLAYBACK
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val icon = if (pipPlaybackPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val label = if (pipPlaybackPaused) "再生" else "一時停止"
        val toggleAction = RemoteAction(
            Icon.createWithResource(this, icon),
            label,
            label,
            togglePendingIntent,
        )
        val closeIntent = Intent(this, PipActionReceiver::class.java).apply {
            action = ACTION_PIP_CLOSE
        }
        val closePendingIntent = PendingIntent.getBroadcast(
            this,
            3,
            closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val closeAction = RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "閉じる",
            "閉じる",
            closePendingIntent,
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(toggleAction))
            .setCloseAction(closeAction)
            .build()
    }

    private fun showOrUpdateMediaNotification() {
        updateMediaSessionState()
        if (!canPostNotifications()) return
        runCatching {
            notificationManager.notify(MEDIA_NOTIFICATION_ID, createMediaNotification())
            mediaNotificationVisible = true
        }.onFailure {
            Log.w(TAG, "Failed to show media notification", it)
        }
    }

    private fun createMediaNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggleIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, PipActionReceiver::class.java).apply {
                action = ACTION_PIP_TOGGLE_PLAYBACK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val actionIcon = if (pipPlaybackPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val actionLabel = if (pipPlaybackPaused) "再生" else "一時停止"
        val action = Notification.Action.Builder(actionIcon, actionLabel, toggleIntent).build()
        return Notification.Builder(this, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(mediaNotificationTitle())
            .setContentText("YTChat")
            .setSubText("YouTube")
            .setContentIntent(openIntent)
            .setShowWhen(false)
            .setOngoing(!pipPlaybackPaused)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0),
            )
            .build()
    }

    private fun updateMediaSessionState() {
        val state = if (pipPlaybackPaused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP,
                )
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        mediaSession?.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, mediaNotificationTitle())
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "YTChat")
                .build(),
        )
    }

    private fun hideMediaNotification() {
        if (::notificationManager.isInitialized) {
            notificationManager.cancel(MEDIA_NOTIFICATION_ID)
        }
        mediaNotificationVisible = false
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
    }

    private fun canPostNotifications(): Boolean {
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun mediaNotificationTitle(): String {
        videoTitle?.takeIf { it.isNotBlank() }?.let { return "YouTubeを再生中: $it" }
        val videoId = runCatching { Uri.parse(videoUrl).getQueryParameter("v") }.getOrNull()
        return if (videoId.isNullOrBlank()) "YouTubeを再生中" else "YouTubeを再生中: $videoId"
    }

    private fun updateChromeForPictureInPicture() {
        val hideChrome = fullScreen || inPictureInPicture
        val hideForChatOnly = chatOnlyModeEnabled && activeSurface == BrowserSurface.VIDEO
        topBar.visibility = if (hideChrome || hideForChatOnly) View.GONE else View.VISIBLE
        status.visibility = if (hideChrome || hideForChatOnly) View.GONE else View.VISIBLE
        navBar.visibility = if (hideChrome || hideForChatOnly) View.GONE else View.VISIBLE
        geckoView.setPadding(0, if (!hideChrome && hideForChatOnly) statusBarHeight() else 0, 0, 0)
        if (::chatOnlyBar.isInitialized) {
            chatOnlyBar.visibility = if (!hideChrome && hideForChatOnly) View.VISIBLE else View.GONE
        }
    }

    private fun enterVideoFullScreen() {
        setAppFullScreen(true)
    }

    private fun triggerYouTubeFullScreen() {
        if (!isYouTubeUrl(currentUrl())) return
        geckoView.requestFocus()
        // 1. 再生トグルの起きない画面下部中央（動画情報や関連動画のあるエリア）を疑似タップしてUser Gestureを成立させる
        simulateNativeTouchAt(geckoView.width / 2f, geckoView.height * 0.7f)

        // 2. タッチイベントの処理後に f キーショートカットを評価
        geckoView.postDelayed({
            val script = """
                (() => {
                  const player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
                  if (player) {
                    player.focus();
                    const ev = new KeyboardEvent('keydown', {
                      key: 'f',
                      code: 'KeyF',
                      keyCode: 70,
                      which: 70,
                      bubbles: true,
                      cancelable: true
                    });
                    player.dispatchEvent(ev);
                  }
                })()
            """.trimIndent()
            activeSession().loadUri("javascript:${Uri.encode(script)}")
        }, 50)
    }

    private fun triggerYouTubePlayPauseShortcut() {
        if (!isYouTubeUrl(currentUrl())) return
        geckoView.requestFocus()
        val script = """
            (() => {
              const player = document.querySelector('#movie_player') || document.querySelector('.html5-video-player');
              const target = player || document.body || document.documentElement;
              if (!target) return;
              if (target.focus) target.focus();
              for (const type of ['keydown', 'keyup']) {
                const ev = new KeyboardEvent(type, {
                  key: 'k',
                  code: 'KeyK',
                  keyCode: 75,
                  which: 75,
                  bubbles: true,
                  cancelable: true
                });
                try { target.dispatchEvent(ev); } catch (_) {}
              }
            })()
        """.trimIndent()
        activeSession().loadUri("javascript:${Uri.encode(script)}")
        status.text = "再生/停止"
    }

    private fun simulateNativeTouchAt(x: Float, y: Float) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val eventTime = android.os.SystemClock.uptimeMillis()
        val downEvent = android.view.MotionEvent.obtain(
            downTime, eventTime,
            android.view.MotionEvent.ACTION_DOWN, x, y, 0
        )
        geckoView.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        val upEvent = android.view.MotionEvent.obtain(
            downTime, eventTime + 20,
            android.view.MotionEvent.ACTION_UP, x, y, 0
        )
        geckoView.dispatchTouchEvent(upEvent)
        upEvent.recycle()
    }

    internal fun handlePipPlaybackAction() {
        setVideoPlaybackPaused(!pipPlaybackPaused)
    }

    internal fun handlePipCloseAction() {
        if (pausePlaybackOnPipClose) {
            setVideoPlaybackPaused(true)
        }
    }

    private fun setVideoPlaybackPaused(paused: Boolean) {
        if (!::videoSession.isInitialized) return
        pipPlaybackPaused = paused
        updateMediaSessionState()
        if (mediaNotificationVisible || activeSurface == BrowserSurface.VIDEO || inPictureInPicture) {
            showOrUpdateMediaNotification()
        }
        if (inPictureInPicture) {
            setPictureInPictureParams(createPictureInPictureParams())
        }
        val command = if (paused) "pause" else "play"
        val script = """
            (() => {
              const video = document.querySelector('#movie_player video') || document.querySelector('video');
              const player = document.querySelector('#movie_player');
              if ('$command' === 'play') {
                if (player && typeof player.playVideo === 'function') {
                  try { player.playVideo(); return; } catch (_) {}
                }
                try { video.play(); } catch (_) {}
                return;
              }
              if (player && typeof player.pauseVideo === 'function') {
                try { player.pauseVideo(); return; } catch (_) {}
              }
              try { video?.pause(); } catch (_) {}
            })()
        """.trimIndent()
        videoSession.loadUri("javascript:${Uri.encode(script)}")
    }

    private fun exitPageFullScreen() {
        activeSession().exitFullScreen()
        setAppFullScreen(false)
    }

    private fun setPageAppFullScreenFlag(enabled: Boolean) {
        if (!isYouTubeUrl(currentUrl())) return
        val script = """
            (() => {
              document.documentElement.classList.${if (enabled) "add" else "remove"}('ytcc-app-fullscreen');
              window.dispatchEvent(new Event('resize'));
            })()
        """.trimIndent()
        activeSession().loadUri("javascript:${Uri.encode(script)}")
    }

    private fun setPageAppPictureInPictureFlag(enabled: Boolean) {
        if (!::videoSession.isInitialized) return
        val script = """
            (() => {
              document.documentElement.classList.${if (enabled) "add" else "remove"}('ytcc-app-pip');
              window.dispatchEvent(new Event('resize'));
            })()
        """.trimIndent()
        videoSession.loadUri("javascript:${Uri.encode(script)}")
    }

    private fun setPageFlag(prefKey: String, label: String, enabled: Boolean) {
        prefs.edit().putBoolean(prefKey, enabled).apply()
        when (prefKey) {
            PREF_YCC_ENABLED -> youtubeChatCleanerEnabled = enabled
            PREF_LCF_ENABLED -> liveChatFlusherEnabled = enabled
        }
        status.text = "$label: ${if (enabled) "ON" else "OFF"}"
        loadUrl(withAppFlags(currentUrl()))
    }

    private fun setChatOnlyMode(enabled: Boolean) {
        if (enabled && activeSurface != BrowserSurface.VIDEO) {
            chatOnlyModeEnabled = false
            prefs.edit().putBoolean(PREF_CHAT_ONLY_MODE, false).apply()
            status.text = "通常チャット専用モードは動画画面でオンにしてください"
            applyEffectiveOsFps(showToast = false)
            updateChromeForPictureInPicture()
            return
        }
        chatOnlyModeEnabled = enabled
        prefs.edit().putBoolean(PREF_CHAT_ONLY_MODE, enabled).apply()
        status.text = "通常チャット専用モード: ${if (enabled) "ON" else "OFF"}"
        applyEffectiveOsFps(showToast = false)
        updateChromeForPictureInPicture()
        applyChatOnlyModeToPage(enabled)
    }

    private fun applyChatOnlyModeToPage(enabled: Boolean) {
        if (!::videoSession.isInitialized) return
        val value = if (enabled) "1" else "0"
        val method = if (enabled) "add" else "remove"
        val script = """
            (() => {
              try { localStorage.setItem('ytcc-app-chat-only-enabled', '$value'); } catch (_) {}
              document.documentElement.classList.$method('ytcc-chat-only');
              if (document.body) document.body.classList.$method('ytcc-chat-only');
              window.dispatchEvent(new Event('ytcc-chat-only-change'));
              window.dispatchEvent(new Event('resize'));
            })()
        """.trimIndent()
        activeSession().loadUri("javascript:${Uri.encode(script)}")
    }

    private fun withAppFlags(rawUrl: String): String {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl
        if (uri.host?.lowercase() !in YOUTUBE_HOSTS) return rawUrl
        val builder = uri.buildUpon().clearQuery()
        for (key in uri.queryParameterNames) {
            if (key.startsWith("ytcc_app_")) continue
            for (value in uri.getQueryParameters(key)) {
                builder.appendQueryParameter(key, value)
            }
        }
        return builder
            .appendQueryParameter("ytcc_app_ycc", if (youtubeChatCleanerEnabled) "1" else "0")
            .appendQueryParameter("ytcc_app_lcf", if (liveChatFlusherEnabled) "1" else "0")
            .appendQueryParameter("ytcc_app_chat_only", if (chatOnlyModeEnabled) "1" else "0")
            .build()
            .toString()
    }

    private fun handoffVideoUrl(rawUrl: String): String? {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in YOUTUBE_HOSTS || uri.path != "/ytcc-open-video") return null
        return uri.getQueryParameter("url")?.takeIf { isYouTubeUrl(it) }
    }

    private fun browserModeFor(rawUrl: String): BrowserMode {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return BrowserMode.MOBILE
        val host = uri.host?.lowercase() ?: return BrowserMode.MOBILE
        if (host == "youtu.be") return BrowserMode.DESKTOP
        if (host !in YOUTUBE_HOSTS) return BrowserMode.MOBILE
        val path = uri.path.orEmpty()
        return if (path == "/watch" || path.startsWith("/live/")) {
            BrowserMode.DESKTOP
        } else {
            BrowserMode.MOBILE
        }
    }

    private fun surfaceForUrl(rawUrl: String): BrowserSurface =
        if (browserModeFor(rawUrl) == BrowserMode.DESKTOP) BrowserSurface.VIDEO else BrowserSurface.MOBILE

    private fun normalizeYouTubeUrl(rawUrl: String): String {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl
        val host = uri.host?.lowercase() ?: return rawUrl
        if (host == "youtu.be") {
            val videoId = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return rawUrl
            val builder = Uri.Builder()
                .scheme("https")
                .authority("www.youtube.com")
                .path("/watch")
                .appendQueryParameter("v", videoId)
            uri.getQueryParameter("list")?.takeIf { it.isNotBlank() }?.let {
                builder.appendQueryParameter("list", it)
            }
            uri.getQueryParameter("t")?.takeIf { it.isNotBlank() }?.let {
                builder.appendQueryParameter("t", it)
            }
            return builder.build().toString()
        }
        if (host !in YOUTUBE_HOSTS) return rawUrl

        val path = uri.path.orEmpty()
        val targetHost = when {
            path == "/watch" || path.startsWith("/live/") -> "www.youtube.com"
            path == "/" ||
                path.startsWith("/results") ||
                path.startsWith("/feed/history") ||
                path.startsWith("/feed/subscriptions") ||
                path.startsWith("/feed/library") ||
                path.startsWith("/playlist") ||
                path.startsWith("/@") ||
                path.startsWith("/channel/") ||
                path.startsWith("/c/") ||
                path.startsWith("/user/") -> "m.youtube.com"
            else -> host
        }

        // モバイル版ドメインに正規化する際、PC版表示を強制する "app=desktop" クエリを除去する
        if (targetHost == "m.youtube.com") {
            val queryNames = runCatching { uri.queryParameterNames }.getOrNull()
            if (queryNames != null && queryNames.contains("app")) {
                val builder = Uri.Builder()
                    .scheme(uri.scheme)
                    .authority(targetHost)
                    .path(uri.path)
                for (key in queryNames) {
                    if (key != "app") {
                        for (value in uri.getQueryParameters(key)) {
                            builder.appendQueryParameter(key, value)
                        }
                    }
                }
                return builder.build().toString()
            }
        }

        return if (targetHost == host) rawUrl else uri.buildUpon().authority(targetHost).build().toString()
    }

    private fun isAllowedTopLevelUrl(rawUrl: String): Boolean {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") return true
        val host = uri.host?.lowercase() ?: return false
        return host in YOUTUBE_HOSTS ||
            host == "accounts.google.com" ||
            host == "myaccount.google.com" ||
            host.endsWith(".google.com") ||
            host == "google.com"
    }

    private fun isYouTubeUrl(rawUrl: String): Boolean {
        val host = runCatching { Uri.parse(rawUrl).host?.lowercase() }.getOrNull() ?: return false
        return host in YOUTUBE_HOSTS
    }

    private fun isInternalGeckoUrl(rawUrl: String): Boolean {
        val scheme = runCatching { Uri.parse(rawUrl).scheme?.lowercase() }.getOrNull()
        return scheme in setOf("about", "resource", "moz-extension", "blob", "data", "javascript")
    }

    private fun openExternal(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            Toast.makeText(this, "外部ブラウザで開けませんでした", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }



    private fun View.applyModernRipple(cornerRadiusDp: Int = 8, bgColor: Int = Color.TRANSPARENT) {
        // rippleColorをシステム属性に頼らず、明示的に「白の半透明(約25%不透明)」に指定し、黒/グレー背景でも確実に見えるようにする
        val rippleColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#40FFFFFF"))
        
        val content = if (bgColor != Color.TRANSPARENT) {
            roundedDrawable(bgColor, cornerRadiusDp)
        } else null
        
        val mask = roundedDrawable(Color.WHITE, cornerRadiusDp)
        
        background = android.graphics.drawable.RippleDrawable(rippleColor, content, mask)
    }

    companion object {
        private const val PREF_FPS_LIMIT = "pref_fps_limit"
        private const val HOME_URL = "https://m.youtube.com/"
        private const val TEST_URL = "https://www.youtube.com/watch?v=6-9qQ0ifz2Y"
        private const val TAG = "YouTubeChatCleaner"
        private const val BLACK = Color.BLACK
        private const val PREF_YCC_ENABLED = "ycc_enabled"
        private const val PREF_LCF_ENABLED = "lcf_enabled"
        private const val PREF_CHAT_ONLY_MODE = "chat_only_mode"
        private const val PREF_PAUSE_ON_PIP_CLOSE = "pause_on_pip_close"
        private const val CHAT_ONLY_FORCED_FPS = 15
        private const val PREF_LAST_VIDEO_URL = "last_video_url"
        private const val PREF_LAST_VIDEO_TIME = "last_video_time"
        private const val MEDIA_CHANNEL_ID = "ytchat_media"
        private const val MEDIA_NOTIFICATION_ID = 1001
        private const val REQUEST_POST_NOTIFICATIONS = 2001
        const val ACTION_PIP_TOGGLE_PLAYBACK = "dev.ytchatplayer.app.PIP_TOGGLE_PLAYBACK"
        const val ACTION_PIP_CLOSE = "dev.ytchatplayer.app.PIP_CLOSE"
        private var currentActivity: MainActivity? = null
        private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")

        fun handlePipAction(intent: Intent?) {
            val action = intent?.action ?: return
            currentActivity?.runOnUiThread {
                when (action) {
                    ACTION_PIP_TOGGLE_PLAYBACK -> currentActivity?.handlePipPlaybackAction()
                    ACTION_PIP_CLOSE -> currentActivity?.handlePipCloseAction()
                }
            }
        }
    }

    private enum class BrowserMode {
        MOBILE,
        DESKTOP,
    }

    private enum class BrowserSurface {
        MOBILE,
        VIDEO,
    }
}

class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MainActivity.handlePipAction(intent)
    }
}
