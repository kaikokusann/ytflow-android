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
import android.content.res.ColorStateList
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
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
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
    private lateinit var chatSession: GeckoSession
    private lateinit var geckoView: GeckoView
    private lateinit var topBar: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var chatOnlyBar: LinearLayout
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var btnFullscreen: ImageButton
    private lateinit var btnNormalChatFont: Button
    private lateinit var btnNormalChatInterval: Button
    private lateinit var btnNormalChatName: Button
    private lateinit var btnNormalChatIcon: Button

    private var yccExtension: WebExtension? = null
    private var lcfExtension: WebExtension? = null

    private var activeSurface = BrowserSurface.MOBILE
    private var mobileUrl: String = HOME_URL
    private var videoUrl: String = TEST_URL
    private var chatUrl: String = ""
    private var videoTitle: String? = null
    private var mobileMode = BrowserMode.MOBILE
    private var videoMode = BrowserMode.DESKTOP
    private var chatMode = BrowserMode.DESKTOP
    private var mobileCanGoBack = false
    private var mobileCanGoForward = false
    private var videoCanGoBack = false
    private var videoCanGoForward = false
    private var chatCanGoBack = false
    private var chatCanGoForward = false
    private var chatSessionOpen = false
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
    private var normalChatFontScale = DEFAULT_NORMAL_CHAT_FONT_SCALE
    private var normalChatIntervalMs = DEFAULT_NORMAL_CHAT_INTERVAL_MS
    private var normalChatShowName = true
    private var normalChatShowIcon = true

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
        normalChatFontScale = prefs.getInt(PREF_NORMAL_CHAT_FONT_SCALE, DEFAULT_NORMAL_CHAT_FONT_SCALE)
        val savedNormalChatIntervalMs = prefs.getInt(PREF_NORMAL_CHAT_INTERVAL_MS, DEFAULT_NORMAL_CHAT_INTERVAL_MS)
        normalChatIntervalMs = normalizeNormalChatInterval(savedNormalChatIntervalMs)
        if (normalChatIntervalMs != savedNormalChatIntervalMs) {
            prefs.edit().putInt(PREF_NORMAL_CHAT_INTERVAL_MS, normalChatIntervalMs).apply()
        }
        normalChatShowName = prefs.getBoolean(PREF_NORMAL_CHAT_SHOW_NAME, true)
        normalChatShowIcon = prefs.getBoolean(PREF_NORMAL_CHAT_SHOW_ICON, true)
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
        if (::runtime.isInitialized) {
            if (::mobileSession.isInitialized) {
                runCatching { runtime.webExtensionController.setTabActive(mobileSession, false) }
                runCatching { mobileSession.close() }
            }
            if (::videoSession.isInitialized) {
                runCatching { runtime.webExtensionController.setTabActive(videoSession, false) }
                runCatching { videoSession.close() }
            }
            if (::chatSession.isInitialized && chatSessionOpen) {
                runCatching { runtime.webExtensionController.setTabActive(chatSession, false) }
                runCatching { chatSession.close() }
            }
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
        updateKeepScreenOn()
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
        geckoView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
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

        addNavButton(toolbarIconButton("設定", R.drawable.ic_settings) { showSettingsMenu() })

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
            LinearLayout.LayoutParams(dp(48), dp(44)).apply {
                marginEnd = dp(4)
            },
        )
        btnNormalChatFont = toolbarButton(
            label = "",
            onClick = { cycleNormalChatFontScale() },
            onLongClick = {
                showNormalChatFontSizeDialog()
                true
            },
        ).apply {
            textSize = 11f
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        chatOnlyBar.addView(
            btnNormalChatFont,
            LinearLayout.LayoutParams(dp(58), dp(44)).apply { marginEnd = dp(4) },
        )
        btnNormalChatInterval = toolbarButton(
            label = "",
            onClick = { cycleNormalChatInterval() },
            onLongClick = {
                showNormalChatIntervalDialog()
                true
            },
        ).apply {
            textSize = 11f
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        chatOnlyBar.addView(
            btnNormalChatInterval,
            LinearLayout.LayoutParams(dp(58), dp(44)).apply { marginEnd = dp(4) },
        )
        btnNormalChatName = toolbarButton("名", onClick = { setNormalChatShowName(!normalChatShowName) }).apply {
            textSize = 11f
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        chatOnlyBar.addView(
            btnNormalChatName,
            LinearLayout.LayoutParams(dp(46), dp(44)).apply { marginEnd = dp(4) },
        )
        btnNormalChatIcon = toolbarButton("顔", onClick = { setNormalChatShowIcon(!normalChatShowIcon) }).apply {
            textSize = 11f
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        chatOnlyBar.addView(
            btnNormalChatIcon,
            LinearLayout.LayoutParams(dp(46), dp(44)).apply { marginEnd = dp(8) },
        )
        chatOnlyBar.addView(
            toolbarButton("動画へ戻る", onClick = { setChatOnlyMode(false) }).apply {
                textSize = 12f
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10), dp(8), dp(10), dp(8))
            },
            LinearLayout.LayoutParams(0, dp(44), 1f),
        )
        updateNormalChatControls()
        root.addView(chatOnlyBar, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        return root
    }

    private fun showSettingsMenu() {
        val dialog = BottomSheetDialog(this, R.style.YTFlowSettingsBottomSheetDialog)
        val context = dialog.context
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(24))
        }

        root.addView(View(context).apply {
            background = roundedDrawable(Color.parseColor("#5E5E5E"), 2)
        }, LinearLayout.LayoutParams(dp(44), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        })

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val headerText = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        headerText.addView(TextView(context).apply {
            text = "アプリの設定"
            textSize = 22f
            setTextColor(Color.parseColor("#F5F5F5"))
            includeFontPadding = false
        })
        headerText.addView(TextView(context).apply {
            text = "拡張機能と再生まわりの設定"
            textSize = 12f
            setTextColor(Color.parseColor("#A8A8A8"))
        })
        header.addView(headerText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "閉じる"
            minHeight = dp(36)
            minimumHeight = dp(36)
            setOnClickListener { dialog.dismiss() }
        })
        root.addView(header)

        root.addView(materialSwitchCard(
            context = context,
            title = "YT Chat Cleaner",
            summary = "名前、アイコン、チャット幅などを調整",
            isChecked = youtubeChatCleanerEnabled,
            onCheckedChange = { checked -> setPageFlag(PREF_YCC_ENABLED, "YouTubeChatCleaner", checked) },
            actionLabel = "設定",
            onActionClick = {
                dialog.dismiss()
                showExtensionPopup(yccExtension, "popup.html", "YouTubeChatCleaner", showSaveButton = false, reloadAfterSave = false)
            }
        ))

        root.addView(materialSwitchCard(
            context = context,
            title = "LiveChat Flusher",
            summary = "読み込み方式・自動オープンなどを調整",
            isChecked = liveChatFlusherEnabled,
            onCheckedChange = { checked -> setPageFlag(PREF_LCF_ENABLED, "LiveChat Flusher", checked) },
            actionLabel = "設定",
            onActionClick = {
                dialog.dismiss()
                showExtensionPopup(lcfExtension, "options/options.html", "LiveChat Flusher", showSaveButton = true, reloadAfterSave = true)
            }
        ))

        root.addView(materialSwitchCard(
            context = context,
            title = "通常チャット専用モード",
            summary = "動画ページでチャットだけを大きく表示",
            isChecked = chatOnlyModeEnabled,
            isEnabled = activeSurface == BrowserSurface.VIDEO,
            onCheckedChange = { checked ->
                setChatOnlyMode(checked)
                if (checked) dialog.dismiss()
            },
        ))

        root.addView(materialActionCard(
            context = context,
            title = "通常チャット文字サイズ",
            summary = "現在: ${normalChatFontScale}%",
            actionLabel = "変更",
            onClick = {
                dialog.dismiss()
                showNormalChatFontSizeDialog()
            }
        ))

        root.addView(materialActionCard(
            context = context,
            title = "通常チャット更新間隔",
            summary = "現在: ${normalChatIntervalMs}ms",
            actionLabel = "変更",
            onClick = {
                dialog.dismiss()
                showNormalChatIntervalDialog()
            }
        ))

        root.addView(materialSwitchCard(
            context = context,
            title = "通常チャットのユーザー名",
            summary = "チャット行のユーザー名を表示",
            isChecked = normalChatShowName,
            onCheckedChange = { checked -> setNormalChatShowName(checked) },
        ))

        root.addView(materialSwitchCard(
            context = context,
            title = "通常チャットのアイコン",
            summary = "チャット行のユーザーアイコンを表示",
            isChecked = normalChatShowIcon,
            onCheckedChange = { checked -> setNormalChatShowIcon(checked) },
        ))

        root.addView(materialSwitchCard(
            context = context,
            title = "PiPを×で閉じたら一時停止",
            summary = "PiPを閉じる操作で動画も停止",
            isChecked = pausePlaybackOnPipClose,
            onCheckedChange = { checked ->
                pausePlaybackOnPipClose = checked
                prefs.edit().putBoolean(PREF_PAUSE_ON_PIP_CLOSE, checked).apply()
                status.text = "PiP終了時の一時停止: ${if (checked) "ON" else "OFF"}"
            },
        ))

        root.addView(materialActionCard(
            context = context,
            title = "FPS制限機能",
            summary = "現在: ${if (currentOsFps > 0) "${currentOsFps}fps" else "自動"}",
            actionLabel = "変更",
            onClick = {
                dialog.dismiss()
                showOsFpsDialog()
            }
        ))
        root.addView(View(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(20)))

        val scrollView = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(root)
        }
        dialog.setContentView(scrollView)
        dialog.setOnShowListener {
            val availableHeight = resources.displayMetrics.heightPixels - statusBarHeight() - dp(24)
            val preferredHeight = (resources.displayMetrics.heightPixels * 0.78f).toInt()
            val targetHeight = minOf(availableHeight, maxOf(dp(620), preferredHeight))
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                sheet.layoutParams = sheet.layoutParams.apply {
                    height = targetHeight
                }
                sheet.requestLayout()
            }
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun materialSwitchCard(
        context: Context,
        title: String,
        summary: String,
        isChecked: Boolean,
        isEnabled: Boolean = true,
        actionLabel: String? = null,
        onActionClick: (() -> Unit)? = null,
        onCheckedChange: (Boolean) -> Unit,
    ): MaterialCardView =
        settingsCard(context, isEnabled).apply {
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val textColumn = settingTextColumn(context, title, summary)
            val switch = MaterialSwitch(context).apply {
                this.isEnabled = isEnabled
                this.isChecked = isChecked
                setOnCheckedChangeListener { _, checked -> onCheckedChange(checked) }
            }
            row.addView(textColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch)
            content.addView(row)

            if (actionLabel != null && onActionClick != null) {
                content.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = actionLabel
                    minHeight = dp(38)
                    minimumHeight = dp(38)
                    setOnClickListener { onActionClick() }
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(10)
                    gravity = Gravity.END
                })
            }
            addView(content)
            if (isEnabled) {
                setOnClickListener { switch.isChecked = !switch.isChecked }
            }
        }

    private fun materialActionCard(
        context: Context,
        title: String,
        summary: String,
        actionLabel: String,
        onClick: () -> Unit,
    ): MaterialCardView =
        settingsCard(context, true).apply {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            row.addView(settingTextColumn(context, title, summary), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = actionLabel
                minHeight = dp(38)
                minimumHeight = dp(38)
                setOnClickListener { onClick() }
            })
            addView(row)
            setOnClickListener { onClick() }
        }

    private fun settingsCard(context: Context, isEnabled: Boolean): MaterialCardView =
        MaterialCardView(context).apply {
            radius = dp(22).toFloat()
            setCardBackgroundColor(Color.parseColor("#222222"))
            setStrokeColor(Color.parseColor("#343434"))
            strokeWidth = dp(1)
            alpha = if (isEnabled) 1f else 0.45f
            useCompatPadding = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        }

    private fun settingTextColumn(context: Context, title: String, summary: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                setTextColor(Color.parseColor("#F5F5F5"))
                includeFontPadding = false
            })
            addView(TextView(context).apply {
                text = summary
                textSize = 12f
                setTextColor(Color.parseColor("#A8A8A8"))
                setPadding(0, dp(5), dp(12), 0)
            })
    }

    private fun showOsFpsDialog() {
        val options = arrayOf("自動 (制限なし)", "120 fps", "60 fps", "30 fps", "15 fps")
        val values = intArrayOf(0, 120, 60, 30, 15)
        val dialog = BottomSheetDialog(this, R.style.YTFlowSettingsBottomSheetDialog)
        val context = dialog.context
        val controlTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.parseColor("#FF0033"), Color.parseColor("#9A949D")),
        )
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(24))
        }
        root.addView(View(context).apply {
            background = roundedDrawable(Color.parseColor("#5E5E5E"), 2)
        }, LinearLayout.LayoutParams(dp(44), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(18)
        })
        root.addView(TextView(context).apply {
            text = "FPS制限機能"
            textSize = 22f
            setTextColor(Color.parseColor("#F5F5F5"))
            includeFontPadding = false
        })
        root.addView(TextView(context).apply {
            text = "画面の滑らかさを制限して負担を軽くします。"
            textSize = 12f
            setTextColor(Color.parseColor("#A8A8A8"))
            setPadding(0, dp(6), 0, dp(12))
        })
        options.forEachIndexed { index, label ->
            root.addView(MaterialRadioButton(context).apply {
                text = label
                textSize = 16f
                setTextColor(Color.parseColor("#F5F5F5"))
                buttonTintList = controlTint
                isChecked = values[index] == currentOsFps
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    currentOsFps = values[index]
                    prefs.edit().putInt(PREF_FPS_LIMIT, currentOsFps).apply()
                    applyEffectiveOsFps(showToast = true)
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        }
        root.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "キャンセル"
            minHeight = dp(40)
            minimumHeight = dp(40)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            gravity = Gravity.END
        })

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun showNormalChatFontSizeDialog() {
        val options = arrayOf("小さめ 100%", "標準 140%", "大きめ 180%", "かなり大きめ 220%", "特大 260%", "最大 300%")
        val values = NORMAL_CHAT_FONT_SCALES
        val dialog = BottomSheetDialog(this, R.style.YTFlowSettingsBottomSheetDialog)
        val context = dialog.context
        val controlTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.parseColor("#FF0033"), Color.parseColor("#9A949D")),
        )
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(24))
        }
        root.addView(View(context).apply {
            background = roundedDrawable(Color.parseColor("#5E5E5E"), 2)
        }, LinearLayout.LayoutParams(dp(44), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(18)
        })
        root.addView(TextView(context).apply {
            text = "通常チャット文字サイズ"
            textSize = 22f
            setTextColor(Color.parseColor("#F5F5F5"))
            includeFontPadding = false
        })
        root.addView(TextView(context).apply {
            text = "通常チャット専用モードの文字サイズを変更します。"
            textSize = 12f
            setTextColor(Color.parseColor("#A8A8A8"))
            setPadding(0, dp(6), 0, dp(12))
        })
        options.forEachIndexed { index, label ->
            root.addView(MaterialRadioButton(context).apply {
                text = label
                textSize = 16f
                setTextColor(Color.parseColor("#F5F5F5"))
                buttonTintList = controlTint
                isChecked = values[index] == normalChatFontScale
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    setNormalChatFontScale(values[index])
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        }
        root.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "キャンセル"
            minHeight = dp(40)
            minimumHeight = dp(40)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            gravity = Gravity.END
        })

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun showNormalChatIntervalDialog() {
        val options = arrayOf("高速 50ms", "標準 100ms", "ゆっくり 150ms", "かなりゆっくり 250ms", "確認用 500ms")
        val values = NORMAL_CHAT_INTERVALS_MS
        val dialog = BottomSheetDialog(this, R.style.YTFlowSettingsBottomSheetDialog)
        val context = dialog.context
        val controlTint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.parseColor("#FF0033"), Color.parseColor("#9A949D")),
        )
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(24))
        }
        root.addView(View(context).apply {
            background = roundedDrawable(Color.parseColor("#5E5E5E"), 2)
        }, LinearLayout.LayoutParams(dp(44), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(18)
        })
        root.addView(TextView(context).apply {
            text = "通常チャット更新間隔"
            textSize = 22f
            setTextColor(Color.parseColor("#F5F5F5"))
            includeFontPadding = false
        })
        root.addView(TextView(context).apply {
            text = "返ってきたチャットを何msごとに表示するかを変更します。"
            textSize = 12f
            setTextColor(Color.parseColor("#A8A8A8"))
            setPadding(0, dp(6), 0, dp(12))
        })
        options.forEachIndexed { index, label ->
            root.addView(MaterialRadioButton(context).apply {
                text = label
                textSize = 16f
                setTextColor(Color.parseColor("#F5F5F5"))
                buttonTintList = controlTint
                isChecked = values[index] == normalChatIntervalMs
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                setOnClickListener {
                    setNormalChatInterval(values[index])
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)))
        }
        root.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "キャンセル"
            minHeight = dp(40)
            minimumHeight = dp(40)
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            gravity = Gravity.END
        })

        dialog.setContentView(root)
        dialog.setOnShowListener {
            dialog.behavior.skipCollapsed = true
            dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        dialog.show()
    }

    private fun cycleNormalChatFontScale() {
        val currentIndex = NORMAL_CHAT_FONT_SCALES.indexOf(normalChatFontScale)
        val nextIndex = if (currentIndex >= 0) {
            (currentIndex + 1) % NORMAL_CHAT_FONT_SCALES.size
        } else {
            NORMAL_CHAT_FONT_SCALES.indexOfFirst { it >= normalChatFontScale }.takeIf { it >= 0 } ?: 0
        }
        setNormalChatFontScale(NORMAL_CHAT_FONT_SCALES[nextIndex])
    }

    private fun cycleNormalChatInterval() {
        val currentIndex = NORMAL_CHAT_INTERVALS_MS.indexOf(normalChatIntervalMs)
        val nextIndex = if (currentIndex >= 0) {
            (currentIndex + 1) % NORMAL_CHAT_INTERVALS_MS.size
        } else {
            NORMAL_CHAT_INTERVALS_MS.indexOfFirst { it >= normalChatIntervalMs }.takeIf { it >= 0 } ?: 0
        }
        setNormalChatInterval(NORMAL_CHAT_INTERVALS_MS[nextIndex])
    }

    private fun setNormalChatFontScale(scale: Int) {
        normalChatFontScale = scale.coerceIn(100, 300)
        prefs.edit().putInt(PREF_NORMAL_CHAT_FONT_SCALE, normalChatFontScale).apply()
        applyNormalChatSettings()
        status.text = "通常チャット文字サイズ: ${normalChatFontScale}%"
    }

    private fun setNormalChatInterval(intervalMs: Int) {
        normalChatIntervalMs = normalizeNormalChatInterval(intervalMs)
        prefs.edit().putInt(PREF_NORMAL_CHAT_INTERVAL_MS, normalChatIntervalMs).apply()
        applyNormalChatSettings()
        status.text = "通常チャット更新間隔: ${normalChatIntervalMs}ms"
    }

    private fun normalizeNormalChatInterval(intervalMs: Int): Int =
        intervalMs.coerceIn(MIN_NORMAL_CHAT_INTERVAL_MS, 1000)

    private fun setNormalChatShowName(show: Boolean) {
        normalChatShowName = show
        prefs.edit().putBoolean(PREF_NORMAL_CHAT_SHOW_NAME, show).apply()
        applyNormalChatSettings()
        status.text = "通常チャットのユーザー名: ${if (show) "ON" else "OFF"}"
    }

    private fun setNormalChatShowIcon(show: Boolean) {
        normalChatShowIcon = show
        prefs.edit().putBoolean(PREF_NORMAL_CHAT_SHOW_ICON, show).apply()
        applyNormalChatSettings()
        status.text = "通常チャットのアイコン: ${if (show) "ON" else "OFF"}"
    }

    private fun applyNormalChatSettings() {
        updateNormalChatControls()
        applyNormalChatModeToPage(false)
        applyNormalChatSettingsToChatSession()
    }

    private fun updateNormalChatControls() {
        if (::btnNormalChatFont.isInitialized) {
            btnNormalChatFont.text = "字${normalChatFontScale}"
        }
        if (::btnNormalChatInterval.isInitialized) {
            btnNormalChatInterval.text = "間${normalChatIntervalMs}"
        }
        if (::btnNormalChatName.isInitialized) {
            btnNormalChatName.text = if (normalChatShowName) "名ON" else "名OFF"
            btnNormalChatName.setTextColor(if (normalChatShowName) Color.WHITE else Color.parseColor("#9AA0A6"))
        }
        if (::btnNormalChatIcon.isInitialized) {
            btnNormalChatIcon.text = if (normalChatShowIcon) "顔ON" else "顔OFF"
            btnNormalChatIcon.setTextColor(if (normalChatShowIcon) Color.WHITE else Color.parseColor("#9AA0A6"))
        }
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
        val forcedForChatOnly = chatOnlyModeEnabled && activeSurface == BrowserSurface.CHAT
        val fps = if (forcedForChatOnly) {
            CHAT_ONLY_FORCED_FPS
        } else {
            currentOsFps
        }
        applyOsFps(fps, showToast = showToast && !forcedForChatOnly)
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
                    applyNormalChatModeToPage(false)
                    applyNormalChatSettingsToChatSession()
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
                    val params = parseAlertParams(data)
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
                if (message.startsWith("ytcc-open-chat:")) {
                    val data = message.substringAfter("ytcc-open-chat:")
                    val params = parseAlertParams(data)
                    val error = params["error"]
                    if (error != null) {
                        status.text = "チャットURLを取得できませんでした"
                        Toast.makeText(this@MainActivity, "チャットを開けませんでした", Toast.LENGTH_SHORT).show()
                        setChatOnlyMode(false)
                    } else {
                        val url = params["url"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        val offsetMs = params["offsetMs"]?.toLongOrNull()
                        if (url != null && isYouTubeChatUrl(url)) {
                            openChatOnlySurface(url, offsetMs)
                        } else {
                            status.text = "チャットURLが不正です"
                            Toast.makeText(this@MainActivity, "チャットURLが不正です", Toast.LENGTH_SHORT).show()
                            setChatOnlyMode(false)
                        }
                    }
                    val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
                    result.complete(prompt.dismiss())
                    return result
                }
                if (message.startsWith("ytcc-open-in-app:")) {
                    val data = message.substringAfter("ytcc-open-in-app:")
                    val params = parseAlertParams(data)
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
                    val params = parseAlertParams(data)
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

                if (surface != BrowserSurface.CHAT && targetSurface == BrowserSurface.CHAT) {
                    runOnUiThread {
                        switchToSurface(BrowserSurface.CHAT)
                        applyBrowserMode(BrowserSurface.CHAT, BrowserMode.DESKTOP)
                        loadUrlInto(BrowserSurface.CHAT, normalized)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                if (surface == BrowserSurface.VIDEO && targetSurface == BrowserSurface.MOBILE) {
                    runOnUiThread {
                        switchToSurface(BrowserSurface.MOBILE)
                        loadUrlInto(BrowserSurface.MOBILE, normalized)
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }

                if (surface == BrowserSurface.CHAT && targetSurface != BrowserSurface.CHAT) {
                    runOnUiThread {
                        if (chatOnlyModeEnabled) setChatOnlyMode(false)
                        switchToSurface(targetSurface)
                        loadUrlInto(targetSurface, normalized)
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
                when (surface) {
                    BrowserSurface.MOBILE -> mobileCanGoBack = canGoBack
                    BrowserSurface.VIDEO -> videoCanGoBack = canGoBack
                    BrowserSurface.CHAT -> chatCanGoBack = canGoBack
                }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                when (surface) {
                    BrowserSurface.MOBILE -> mobileCanGoForward = canGoForward
                    BrowserSurface.VIDEO -> videoCanGoForward = canGoForward
                    BrowserSurface.CHAT -> chatCanGoForward = canGoForward
                }
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
        sessionFor(activeSurface)

    private fun currentUrl(): String =
        when (activeSurface) {
            BrowserSurface.MOBILE -> mobileUrl
            BrowserSurface.VIDEO -> videoUrl
            BrowserSurface.CHAT -> chatUrl.ifBlank { videoUrl }
        }

    private fun sessionFor(surface: BrowserSurface): GeckoSession =
        when (surface) {
            BrowserSurface.MOBILE -> mobileSession
            BrowserSurface.VIDEO -> videoSession
            BrowserSurface.CHAT -> ensureChatSession()
        }

    private fun updateSurfaceUrl(surface: BrowserSurface, url: String) {
        when (surface) {
            BrowserSurface.MOBILE -> mobileUrl = url
            BrowserSurface.VIDEO -> videoUrl = url
            BrowserSurface.CHAT -> chatUrl = url
        }
    }

    private fun shouldRememberSurfaceUrl(surface: BrowserSurface, url: String): Boolean =
        when (surface) {
            BrowserSurface.MOBILE -> surfaceForUrl(normalizeYouTubeUrl(url)) == BrowserSurface.MOBILE
            BrowserSurface.VIDEO -> true
            BrowserSurface.CHAT -> isYouTubeChatUrl(url)
        }

    private fun urlForAddress(surface: BrowserSurface, candidateUrl: String): String =
        if (surface == BrowserSurface.MOBILE && !shouldRememberSurfaceUrl(surface, candidateUrl)) {
            mobileUrl
        } else {
            candidateUrl
        }

    private fun loadUrlInto(surface: BrowserSurface, url: String, replaceHistory: Boolean = false) {
        if (surface == BrowserSurface.CHAT) ensureChatSession()
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
        when (surface) {
            BrowserSurface.MOBILE -> mobileMode = mode
            BrowserSurface.VIDEO -> videoMode = mode
            BrowserSurface.CHAT -> chatMode = mode
        }
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
        when (surface) {
            BrowserSurface.MOBILE -> mobileMode
            BrowserSurface.VIDEO -> videoMode
            BrowserSurface.CHAT -> chatMode
        }

    private fun switchToSurface(surface: BrowserSurface) {
        if (activeSurface == surface) return

        val previousSurface = activeSurface
        if (surface == BrowserSurface.MOBILE && chatOnlyModeEnabled) {
            setChatOnlyMode(false)
        }
        if (surface == BrowserSurface.CHAT) ensureChatSession()

        val oldSession = activeSession()
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
        updateRequestedOrientation()
        applyEffectiveOsFps(showToast = false) // 表示画面が変わったのでFPS制限を再評価・適用
        geckoView.setSession(activeSession())
        if (surface == BrowserSurface.CHAT || previousSurface == BrowserSurface.CHAT) {
            geckoView.requestNewSurface()
        }
        runtime.webExtensionController.setTabActive(activeSession(), true)
        address.setText(currentUrl())
        status.text = when (surface) {
            BrowserSurface.MOBILE -> "モバイル一覧セッション"
            BrowserSurface.VIDEO -> "PC動画セッション"
            BrowserSurface.CHAT -> "通常チャット専用セッション"
        }
        if (surface == BrowserSurface.VIDEO || surface == BrowserSurface.CHAT) showOrUpdateMediaNotification()
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
            BrowserSurface.CHAT -> {
                setChatOnlyMode(false)
            }
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
            BrowserSurface.CHAT -> {
                if (chatCanGoForward) chatSession.goForward()
            }
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
        updateRequestedOrientation()
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

    private fun updateRequestedOrientation() {
        requestedOrientation = when {
            chatOnlyModeEnabled && activeSurface == BrowserSurface.CHAT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            fullScreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        val hideForChatOnly = chatOnlyModeEnabled && activeSurface == BrowserSurface.CHAT
        topBar.visibility = if (hideChrome || hideForChatOnly) View.GONE else View.VISIBLE
        status.visibility = if (hideChrome || hideForChatOnly) View.GONE else View.VISIBLE
        navBar.visibility = if (hideChrome || hideForChatOnly) View.GONE else View.VISIBLE
        geckoView.setPadding(0, if (!hideChrome && hideForChatOnly) statusBarHeight() else 0, 0, 0)
        if (::chatOnlyBar.isInitialized) {
            chatOnlyBar.visibility = if (!hideChrome && hideForChatOnly) View.VISIBLE else View.GONE
        }
        updateKeepScreenOn()
    }

    private fun updateKeepScreenOn() {
        if (chatOnlyModeEnabled && activeSurface == BrowserSurface.CHAT) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun enterVideoFullScreen() {
        setAppFullScreen(true)
    }

    private fun triggerYouTubeFullScreen() {
        if (activeSurface != BrowserSurface.VIDEO || !isYouTubeUrl(currentUrl())) return
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
        val targetSession = if (activeSurface == BrowserSurface.CHAT) videoSession else activeSession()
        val targetUrl = if (activeSurface == BrowserSurface.CHAT) videoUrl else currentUrl()
        if (!isYouTubeUrl(targetUrl)) return
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
        targetSession.loadUri("javascript:${Uri.encode(script)}")
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
            applyNormalChatModeToPage(false)
            status.text = "通常チャット専用モードは動画画面でオンにしてください"
            applyEffectiveOsFps(showToast = false)
            updateChromeForPictureInPicture()
            return
        }

        if (enabled) {
            chatOnlyModeEnabled = true
            prefs.edit().putBoolean(PREF_CHAT_ONLY_MODE, true).apply()
            status.text = "通常チャットURLを取得中"
            applyNormalChatModeToPage(false)
            requestChatOnlyUrlFromVideoPage()
            applyEffectiveOsFps(showToast = false)
            updateChromeForPictureInPicture()
            return
        }

        chatOnlyModeEnabled = enabled
        prefs.edit().putBoolean(PREF_CHAT_ONLY_MODE, false).apply()
        status.text = "通常チャット専用モード: OFF"
        applyNormalChatModeToPage(false)
        if (activeSurface == BrowserSurface.CHAT) {
            switchToSurface(BrowserSurface.VIDEO)
        }
        stopChatOnlySession()
        applyEffectiveOsFps(showToast = false)
        updateChromeForPictureInPicture()
    }

    private fun requestChatOnlyUrlFromVideoPage() {
        if (!::videoSession.isInitialized) return
        val script = """
            (() => {
              if (window.__ytcc_chat_only_request_timer) {
                clearInterval(window.__ytcc_chat_only_request_timer);
                window.__ytcc_chat_only_request_timer = null;
              }

              const chatPaths = new Set(['/live_chat', '/live_chat_replay']);
              const normalizeChatUrl = rawUrl => {
                const url = new URL(rawUrl, location.href);
                url.hostname = 'www.youtube.com';
                if (!chatPaths.has(url.pathname)) return null;
                url.searchParams.set('is_popout', '1');
                return url.toString();
              };

              const videoIdFromPage = () => {
                try {
                  const url = new URL(location.href);
                  const fromQuery = url.searchParams.get('v');
                  if (fromQuery) return fromQuery;
                  if (url.pathname.startsWith('/live/')) {
                    return url.pathname.split('/').filter(Boolean).pop() || '';
                  }
                } catch (_) {}
                return '';
              };

              const extractJsonAfter = (text, marker) => {
                const markerIndex = text.indexOf(marker);
                if (markerIndex < 0) return null;
                const start = text.indexOf('{', markerIndex + marker.length);
                if (start < 0) return null;
                let depth = 0;
                let inString = false;
                let escape = false;
                for (let i = start; i < text.length; i++) {
                  const ch = text[i];
                  if (inString) {
                    if (escape) {
                      escape = false;
                    } else if (ch === '\\') {
                      escape = true;
                    } else if (ch === '"') {
                      inString = false;
                    }
                    continue;
                  }
                  if (ch === '"') {
                    inString = true;
                  } else if (ch === '{') {
                    depth += 1;
                  } else if (ch === '}') {
                    depth -= 1;
                    if (depth === 0) return text.slice(start, i + 1);
                  }
                }
                return null;
              };

              const initialDataFromScripts = () => {
                if (window.ytInitialData) return window.ytInitialData;
                for (const script of document.scripts) {
                  const text = script.textContent || '';
                  if (!text.includes('ytInitialData')) continue;
                  const json = extractJsonAfter(text, 'ytInitialData');
                  if (!json) continue;
                  try {
                    return JSON.parse(json);
                  } catch (_) {}
                }
                return null;
              };

              const chatUrlFromInitialData = () => {
                const data = initialDataFromScripts();
                const liveChatRenderer =
                  data?.contents?.twoColumnWatchNextResults?.conversationBar?.liveChatRenderer;
                const continuation =
                  liveChatRenderer?.continuations?.[0]?.reloadContinuationData?.continuation;
                if (!continuation) return '';
                const path = liveChatRenderer?.isReplay ? '/live_chat_replay' : '/live_chat';
                // YouTubeのcontinuationは既にURL向けにエスケープされた形で入っている。
                // URLSearchParamsを通すと %3D が %253D になり、replay chatが空になる。
                return `https://www.youtube.com${'$'}{path}?continuation=${'$'}{continuation}&is_popout=1`;
              };

              const headerText = () => {
                const selectors = [
                  'yt-live-chat-header-renderer',
                  'ytd-live-chat-frame',
                  '#chat',
                  '#panels'
                ];
                return selectors
                  .map(selector => document.querySelector(selector)?.textContent || '')
                  .join(' ')
                  .replace(/\s+/g, ' ')
                  .trim();
              };

              const findChatUrl = () => {
                const selectors = [
                  'iframe#chatframe',
                  'ytd-live-chat-frame iframe',
                  'iframe[src*="/live_chat"]',
                  'iframe[src*="/live_chat_replay"]'
                ];
                for (const selector of selectors) {
                  const frame = document.querySelector(selector);
                  const src = frame?.getAttribute('src') || frame?.src;
                  if (!src) continue;
                  const normalized = normalizeChatUrl(src);
                  if (normalized) return normalized;
                }

                const fromInitialData = chatUrlFromInitialData();
                if (fromInitialData) return fromInitialData;

                const text = headerText();
                const appearsArchive = /リプレイ|replay/i.test(text);
                const videoId = videoIdFromPage();
                if (videoId && !appearsArchive) {
                  return `https://www.youtube.com/live_chat?is_popout=1&v=${'$'}{encodeURIComponent(videoId)}`;
                }
                return '';
              };

              const playbackOffsetMs = () => {
                try {
                  const video = document.querySelector('video');
                  if (video && Number.isFinite(video.currentTime) && video.currentTime > 0) {
                    return Math.max(0, Math.floor(video.currentTime * 1000));
                  }
                  const rawTime = new URL(location.href).searchParams.get('t') || '';
                  const directSeconds = rawTime.match(/^(\d+)s?$/i);
                  if (directSeconds) return Number.parseInt(directSeconds[1], 10) * 1000;
                  const parts = rawTime.match(/(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?/i);
                  if (parts && (parts[1] || parts[2] || parts[3])) {
                    const seconds =
                      (Number.parseInt(parts[1] || '0', 10) * 3600) +
                      (Number.parseInt(parts[2] || '0', 10) * 60) +
                      Number.parseInt(parts[3] || '0', 10);
                    return seconds * 1000;
                  }
                  return 0;
                } catch (_) {
                  return 0;
                }
              };

              const send = (url, source) => {
                alert(
                  'ytcc-open-chat:url=' + encodeURIComponent(url) +
                  '&offsetMs=' + encodeURIComponent(String(playbackOffsetMs())) +
                  '&source=' + encodeURIComponent(source)
                );
              };

              const trySend = source => {
                const url = findChatUrl();
                if (!url) return false;
                send(url, source);
                return true;
              };

              if (trySend('immediate')) return;

              let attempts = 0;
              window.__ytcc_chat_only_request_timer = setInterval(() => {
                attempts += 1;
                if (trySend('retry')) {
                  clearInterval(window.__ytcc_chat_only_request_timer);
                  window.__ytcc_chat_only_request_timer = null;
                  return;
                }
                if (attempts >= 24) {
                  clearInterval(window.__ytcc_chat_only_request_timer);
                  window.__ytcc_chat_only_request_timer = null;
                  alert('ytcc-open-chat:error=no-chat-url');
                }
              }, 250);
            })()
        """.trimIndent()
        videoSession.loadUri("javascript:${Uri.encode(script)}")
    }

    private fun openChatOnlySurface(rawChatUrl: String, playbackOffsetMs: Long? = null) {
        val normalized = normalizeChatOnlyUrl(rawChatUrl, playbackOffsetMs)
        if (!isYouTubeChatUrl(normalized)) {
            status.text = "チャットURLが不正です"
            setChatOnlyMode(false)
            return
        }

        Log.i(TAG, "Opening chat-only URL: $normalized")
        chatOnlyModeEnabled = true
        prefs.edit().putBoolean(PREF_CHAT_ONLY_MODE, true).apply()
        applyNormalChatModeToPage(false)
        resetChatSessionForChatOnly()
        switchToSurface(BrowserSurface.CHAT)
        applyBrowserMode(BrowserSurface.CHAT, BrowserMode.DESKTOP)
        loadUrlInto(BrowserSurface.CHAT, normalized)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (activeSurface == BrowserSurface.CHAT) geckoView.requestNewSurface()
        }, 500)
        applyEffectiveOsFps(showToast = false)
        updateChromeForPictureInPicture()
        status.text = "通常チャット専用モード: ON"
    }

    private fun resetChatSessionForChatOnly() {
        if (::chatSession.isInitialized && chatSessionOpen) {
            runCatching { runtime.webExtensionController.setTabActive(chatSession, false) }
            runCatching { chatSession.stop() }
            runCatching { chatSession.close() }
        }
        chatSession = createManagedSession(BrowserSurface.CHAT, BrowserMode.DESKTOP)
        chatSession.open(runtime)
        chatSessionOpen = true
        chatMode = BrowserMode.DESKTOP
        chatCanGoBack = false
        chatCanGoForward = false
    }

    private fun stopChatOnlySession() {
        if (!::runtime.isInitialized || !::chatSession.isInitialized || !chatSessionOpen) {
            chatUrl = ""
            return
        }
        runCatching { runtime.webExtensionController.setTabActive(chatSession, false) }
        runCatching { chatSession.stop() }
        runCatching { chatSession.close() }
        chatSessionOpen = false
        chatMode = BrowserMode.DESKTOP
        chatCanGoBack = false
        chatCanGoForward = false
        chatUrl = ""
    }

    private fun ensureChatSession(): GeckoSession {
        if (!::chatSession.isInitialized || !chatSessionOpen) {
            chatSession = createManagedSession(BrowserSurface.CHAT, BrowserMode.DESKTOP)
            chatSession.open(runtime)
            chatSessionOpen = true
            chatMode = BrowserMode.DESKTOP
            chatCanGoBack = false
            chatCanGoForward = false
            runCatching { runtime.webExtensionController.setTabActive(chatSession, false) }
        }
        return chatSession
    }

    private fun applyNormalChatModeToPage(enabled: Boolean) {
        if (!::videoSession.isInitialized) return
        videoSession.loadUri("javascript:${Uri.encode(normalChatSettingsScript(enabled))}")
    }

    private fun applyNormalChatSettingsToChatSession() {
        if (!::chatSession.isInitialized || !chatSessionOpen) return
        chatSession.loadUri("javascript:${Uri.encode(normalChatSettingsScript(true))}")
    }

    private fun normalChatSettingsScript(enabled: Boolean): String {
        val value = if (enabled) "1" else "0"
        val showNameValue = if (normalChatShowName) "1" else "0"
        val showIconValue = if (normalChatShowIcon) "1" else "0"
        return """
            (() => {
              const root = document.documentElement;
              try { root.setAttribute('data-ytlcf-app-normal-chat-enabled', '$value'); } catch (_) {}
              try { root.setAttribute('data-ytlcf-app-normal-chat-font-scale', '$normalChatFontScale'); } catch (_) {}
              try { root.setAttribute('data-ytlcf-app-normal-chat-interval-ms', '$normalChatIntervalMs'); } catch (_) {}
              try { root.setAttribute('data-ytlcf-app-normal-chat-show-name', '$showNameValue'); } catch (_) {}
              try { root.setAttribute('data-ytlcf-app-normal-chat-show-photo', '$showIconValue'); } catch (_) {}
              try { localStorage.removeItem('ytlcf-app-normal-chat-enabled'); } catch (_) {}
              try { localStorage.removeItem('ytcc-app-chat-only-enabled'); } catch (_) {}
              try { localStorage.setItem('ytlcf-app-normal-chat-font-scale', '$normalChatFontScale'); } catch (_) {}
              try { localStorage.setItem('ytlcf-app-normal-chat-interval-ms', '$normalChatIntervalMs'); } catch (_) {}
              try { localStorage.setItem('ytlcf-app-normal-chat-show-name', '$showNameValue'); } catch (_) {}
              try { localStorage.setItem('ytlcf-app-normal-chat-show-photo', '$showIconValue'); } catch (_) {}
              if ('$value' !== '1') {
                root.classList.remove('ytlcf-app-normal-chat-active');
                const legacyTargets = document.querySelectorAll([
                  'video',
                  '.video-stream',
                  '.html5-video-player',
                  '.html5-video-container',
                  '#movie_player',
                  '#player',
                  '#player-container',
                  '#player-container-outer',
                  'ytd-player',
                  'ytd-miniplayer',
                  '[class*="miniplayer"]',
                  '[class*="MiniPlayer"]'
                ].join(','));
                for (const element of legacyTargets) {
                  const style = element.style;
                  const looksLegacyHidden =
                    style.getPropertyValue('left') === '-10000px' ||
                    style.getPropertyValue('top') === '-10000px' ||
                    style.getPropertyValue('width') === '1px' ||
                    style.getPropertyValue('height') === '1px' ||
                    style.getPropertyValue('transform').includes('-10000px');
                  if (!looksLegacyHidden) continue;
                  for (const name of [
                    'display',
                    'visibility',
                    'opacity',
                    'pointer-events',
                    'position',
                    'left',
                    'top',
                    'width',
                    'height',
                    'transform'
                  ]) {
                    style.removeProperty(name);
                  }
                  if (element instanceof HTMLVideoElement) {
                    element.removeAttribute('width');
                    element.removeAttribute('height');
                  }
                }
              }
              window.dispatchEvent(new Event('ytlcf-normal-chat-change'));
              window.postMessage({ type: 'ytlcf-normal-chat-change' }, '*');
              window.dispatchEvent(new Event('resize'));
            })()
        """.trimIndent()
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
        val flaggedUri = builder
            .appendQueryParameter("ytcc_app_ycc", if (youtubeChatCleanerEnabled) "1" else "0")
            .appendQueryParameter("ytcc_app_lcf", if (liveChatFlusherEnabled) "1" else "0")
            .appendQueryParameter("ytcc_app_chat_only", if (chatOnlyModeEnabled) "1" else "0")
            .appendQueryParameter("ytcc_app_normal_chat_font_scale", normalChatFontScale.toString())
            .appendQueryParameter("ytcc_app_normal_chat_interval_ms", normalChatIntervalMs.toString())
            .appendQueryParameter("ytcc_app_normal_chat_show_name", if (normalChatShowName) "1" else "0")
            .appendQueryParameter("ytcc_app_normal_chat_show_photo", if (normalChatShowIcon) "1" else "0")
            .build()
        if (!chatOnlyModeEnabled || !isYouTubeChatUrl(rawUrl)) return flaggedUri.toString()
        val chatOffsetMs = runCatching { uri.getQueryParameter("ytcc_chat_offset_ms") }.getOrNull()
        val fragmentParams = mutableListOf(
            "ytcc_app_chat_only=1",
            "ytcc_app_normal_chat_font_scale=$normalChatFontScale",
            "ytcc_app_normal_chat_interval_ms=$normalChatIntervalMs",
            "ytcc_app_normal_chat_show_name=${if (normalChatShowName) "1" else "0"}",
            "ytcc_app_normal_chat_show_photo=${if (normalChatShowIcon) "1" else "0"}",
        )
        if (!chatOffsetMs.isNullOrBlank()) {
            fragmentParams.add("ytcc_chat_offset_ms=$chatOffsetMs")
        }
        return flaggedUri
            .buildUpon()
            .encodedFragment(fragmentParams.joinToString("&"))
            .build()
            .toString()
    }

    private fun parseAlertParams(data: String): Map<String, String> =
        data.split("&")
            .filter { it.isNotBlank() }
            .associate { entry ->
                val separator = entry.indexOf('=')
                if (separator >= 0) {
                    entry.substring(0, separator) to entry.substring(separator + 1)
                } else {
                    entry to ""
                }
            }

    private fun handoffVideoUrl(rawUrl: String): String? {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in YOUTUBE_HOSTS || uri.path != "/ytcc-open-video") return null
        return uri.getQueryParameter("url")?.takeIf { isYouTubeUrl(it) }
    }

    private fun normalizeChatOnlyUrl(rawUrl: String, playbackOffsetMs: Long? = null): String {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return rawUrl
        val host = uri.host?.lowercase() ?: return rawUrl
        if (host !in YOUTUBE_HOSTS) return rawUrl
        if (!isYouTubeChatUrl(rawUrl)) return rawUrl
        val path = uri.path.orEmpty()
        val builder = uri.buildUpon().authority("www.youtube.com").clearQuery()
        for (key in uri.queryParameterNames) {
            if (key == "is_popout" || key == "ytcc_chat_offset_ms") continue
            for (value in uri.getQueryParameters(key)) {
                builder.appendQueryParameter(key, value)
            }
        }
        builder.appendQueryParameter("is_popout", "1")
        playbackOffsetMs
            ?.takeIf { it > 0 && path == "/live_chat_replay" }
            ?.let { builder.appendQueryParameter("ytcc_chat_offset_ms", it.toString()) }
        return builder.build().toString()
    }

    private fun browserModeFor(rawUrl: String): BrowserMode {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return BrowserMode.MOBILE
        val host = uri.host?.lowercase() ?: return BrowserMode.MOBILE
        if (host == "youtu.be") return BrowserMode.DESKTOP
        if (host !in YOUTUBE_HOSTS) return BrowserMode.MOBILE
        val path = uri.path.orEmpty()
        return if (path == "/watch" || path.startsWith("/live/") || isYouTubeChatUrl(rawUrl)) {
            BrowserMode.DESKTOP
        } else {
            BrowserMode.MOBILE
        }
    }

    private fun surfaceForUrl(rawUrl: String): BrowserSurface =
        when {
            isYouTubeChatUrl(rawUrl) -> BrowserSurface.CHAT
            browserModeFor(rawUrl) == BrowserMode.DESKTOP -> BrowserSurface.VIDEO
            else -> BrowserSurface.MOBILE
        }

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
            path == "/watch" || path.startsWith("/live/") || isYouTubeChatUrl(rawUrl) -> "www.youtube.com"
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

    private fun isYouTubeChatUrl(rawUrl: String): Boolean {
        val uri = runCatching { Uri.parse(rawUrl) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        return host in YOUTUBE_HOSTS && (path == "/live_chat" || path == "/live_chat_replay")
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
        private const val PREF_NORMAL_CHAT_FONT_SCALE = "normal_chat_font_scale"
        private const val PREF_NORMAL_CHAT_INTERVAL_MS = "normal_chat_interval_ms"
        private const val PREF_NORMAL_CHAT_SHOW_NAME = "normal_chat_show_name"
        private const val PREF_NORMAL_CHAT_SHOW_ICON = "normal_chat_show_icon"
        private const val DEFAULT_NORMAL_CHAT_FONT_SCALE = 180
        private const val DEFAULT_NORMAL_CHAT_INTERVAL_MS = 100
        private val NORMAL_CHAT_FONT_SCALES = intArrayOf(100, 140, 180, 220, 260, 300)
        private const val MIN_NORMAL_CHAT_INTERVAL_MS = 50
        private val NORMAL_CHAT_INTERVALS_MS = intArrayOf(50, 100, 150, 250, 500)
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
        CHAT,
    }
}

class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        MainActivity.handlePipAction(intent)
    }
}
