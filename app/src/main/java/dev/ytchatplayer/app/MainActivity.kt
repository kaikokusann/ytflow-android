package dev.ytchatplayer.app

import android.Manifest
import android.app.Dialog
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.TextUtils
import android.util.Rational
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.ListPopupWindow
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import android.app.AlertDialog
import android.content.DialogInterface
import java.net.URLEncoder
import org.mozilla.geckoview.AllowOrDeny

class MainActivity : AppCompatActivity() {
    private lateinit var playback: PlaybackService
    private var serviceBound = false
    private var restoringActivity = false
    private val playbackConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            playback = (binder as PlaybackService.LocalBinder).service
            playback.applyBrowserAppearance()
            createBrowser()
            playback.onPlaybackChanged = {
                if (inPictureInPicture) setPictureInPictureParams(createPictureInPictureParams())
            }
            installBuiltInExtensions()
            val retainedUrl = playback.videoUrl
            if ((intent.data == null || restoringActivity) && retainedUrl != null) {
                videoUrl = retainedUrl
                videoCanGoBack = playback.canGoBack
                videoCanGoForward = playback.canGoForward
                loadUrlInto(BrowserSurface.MOBILE, HOME_URL)
                switchToSurface(BrowserSurface.VIDEO)
            } else {
                loadInitialUrl(intent)
                if (activeSurface != BrowserSurface.MOBILE) {
                    loadUrlInto(BrowserSurface.MOBILE, HOME_URL)
                }
            }
            setBrowserControlsEnabled(true)
        }

        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    private lateinit var runtime: GeckoRuntime
    private lateinit var mobileSession: GeckoSession
    private lateinit var videoSession: GeckoSession
    private lateinit var chatSession: GeckoSession
    private lateinit var geckoView: GeckoView
    private lateinit var topBar: LinearLayout
    private lateinit var addressBar: LinearLayout
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
    private var activeSettingsDialog: BottomSheetDialog? = null
    private var activeSettingsScrollView: NestedScrollView? = null
    private var activeSettingsChoicePopup: ListPopupWindow? = null
    private var refreshSettingsContent: (() -> Unit)? = null
    private var activeExtensionDialog: Dialog? = null
    private var lastNightMode = Configuration.UI_MODE_NIGHT_UNDEFINED

    private var yccExtension: WebExtension? = null
    private var lcfExtension: WebExtension? = null

    private var activeSurface = BrowserSurface.MOBILE
    private var mobileUrl: String = HOME_URL
    private var videoUrl: String = TEST_URL
    private var chatUrl: String = ""
    private var mobileMode = BrowserMode.MOBILE
    private var videoMode = BrowserMode.DESKTOP
    private var chatMode = BrowserMode.DESKTOP
    private var mobileCanGoBack = false
    private var mobileCanGoForward = false
    private var videoCanGoBack = false
    private var videoCanGoForward = false
    private var chatCanGoForward = false
    private var chatSessionOpen = false
    private val videoHistory get() = playback.videoHistory
    private var fullScreen = false
    private var inPictureInPicture = false
    private var pipDismissPending = false
    private var activityStarted = false
    private val videoPlaybackPaused get() = !::playback.isInitialized || playback.paused
    private var youtubeChatCleanerEnabled = true
    private var liveChatFlusherEnabled = true
    private var dvrChanEnabled = true
    private var chatOnlyModeEnabled = false
    private var pausePlaybackOnPipClose = true
    private var suppressFailedPageStopUntil = 0L
    private var currentOsFps = 0
    private var lcfDanmakuFontSizePx = DEFAULT_LCF_DANMAKU_FONT_SIZE_PX
    private var lcfDanmakuShowIcon = false
    private var normalChatFontScale = DEFAULT_NORMAL_CHAT_FONT_SCALE
    private var normalChatIntervalMs = DEFAULT_NORMAL_CHAT_INTERVAL_MS
    private var normalChatShowName = true
    private var normalChatShowIcon = true

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppAppearance.read(this).nightMode)
        super.onCreate(savedInstanceState)
        lastNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        restoringActivity = savedInstanceState != null
        prefs = getSharedPreferences("debug_toggles", MODE_PRIVATE)
        requestNotificationPermissionIfNeeded()
        youtubeChatCleanerEnabled = prefs.getBoolean(PREF_YCC_ENABLED, true)
        liveChatFlusherEnabled = prefs.getBoolean(PREF_LCF_ENABLED, true)
        dvrChanEnabled = prefs.getBoolean(PREF_DVR_ENABLED, true)
        chatOnlyModeEnabled = false
        pausePlaybackOnPipClose = prefs.getBoolean(PREF_PAUSE_ON_PIP_CLOSE, true)
        currentOsFps = prefs.getInt(PREF_FPS_LIMIT, 0)
        lcfDanmakuFontSizePx = normalizeLcfDanmakuFontSize(
            prefs.getInt(PREF_LCF_DANMAKU_FONT_SIZE_PX, DEFAULT_LCF_DANMAKU_FONT_SIZE_PX)
        )
        lcfDanmakuShowIcon = prefs.getBoolean(PREF_LCF_DANMAKU_SHOW_ICON, false)
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
        updateChromeAppearance()
        setBrowserControlsEnabled(false)
        serviceBound = bindService(Intent(this, PlaybackService::class.java), playbackConnection, BIND_AUTO_CREATE)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restoringActivity = false
        if (::playback.isInitialized && intent.data != null) loadInitialUrl(intent)
    }

    override fun onDestroy() {
        activeSettingsDialog?.dismiss()
        activeExtensionDialog?.dismiss()
        if (::playback.isInitialized) {
            playback.detachUi()
            geckoView.releaseSession()
        }
        if (::runtime.isInitialized) {
            if (::mobileSession.isInitialized) {
                runCatching { runtime.webExtensionController.setTabActive(mobileSession, false) }
                runCatching { mobileSession.close() }
            }
            if (::chatSession.isInitialized && chatSessionOpen) {
                runCatching { runtime.webExtensionController.setTabActive(chatSession, false) }
                runCatching { chatSession.close() }
            }
        }
        if (serviceBound) unbindService(playbackConnection)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
    }

    override fun onResume() {
        super.onResume()
        pipDismissPending = false
        if (!isInPictureInPictureMode) {
            activeSettingsDialog?.show()
            activeExtensionDialog?.show()
        }
        if (::geckoView.isInitialized) {
            geckoView.requestFocus()
        }
        // Force sync states upon resume to recover from suspended JavaScript state
        if (::mobileSession.isInitialized) setPageAppFullScreenFlag(fullScreen)
        setPageAppPictureInPictureFlag(inPictureInPicture)
        updateKeepScreenOn()
    }

    override fun onStop() {
        super.onStop()
        activityStarted = false
        pauseAfterPipDismissal()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateSystemBarAppearance(window)
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
        videoSession.compositorController.onPipModeChanged(isInPictureInPictureMode)
        if (isInPictureInPictureMode) pipDismissPending = true
        if (isInPictureInPictureMode) {
            activeSettingsChoicePopup?.dismiss()
            activeSettingsDialog?.hide()
            activeExtensionDialog?.hide()
        }
        pauseAfterPipDismissal()
        setPageAppPictureInPictureFlag(isInPictureInPictureMode)
        if (isInPictureInPictureMode) showOrUpdateMediaNotification()
        updateChromeForPictureInPicture()
    }

    private fun pauseAfterPipDismissal() {
        // onStop and the PiP callback can arrive in either order.
        if (!pipDismissPending || activityStarted || inPictureInPicture) return
        pipDismissPending = false
        if (pausePlaybackOnPipClose) requestVideoPlaybackPaused(true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        activeSettingsChoicePopup?.dismiss()
        val nightMode = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (nightMode != lastNightMode) {
            lastNightMode = nightMode
            theme.rebase()
            theme.applyStyle(R.style.AppTheme, true)
            if (::topBar.isInitialized) updateChromeAppearance()
            refreshSettingsContent?.invoke()
            if (::playback.isInitialized) playback.applyBrowserAppearance()
        }
        activeExtensionDialog?.let { configureExtensionDialog(it) }
        activeSettingsDialog?.let { dialog ->
            activeSettingsScrollView?.post {
                if (activeSettingsDialog === dialog) configureSettingsBottomSheet(dialog)
            }
        }
    }


    private fun requestNotificationPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST_NOTIFICATIONS)
    }

    private fun setBrowserControlsEnabled(enabled: Boolean) {
        fun setEnabled(view: View) {
            view.isEnabled = enabled
            if (view is ViewGroup) for (i in 0 until view.childCount) setEnabled(view.getChildAt(i))
        }
        setEnabled(topBar)
        setEnabled(navBar)
    }

    private fun themeColor(context: Context, attribute: Int): Int =
        MaterialColors.getColor(context, attribute, "YTFlow")

    private fun updateChromeAppearance() {
        val surface = themeColor(this, com.google.android.material.R.attr.colorSurface)
        val container = themeColor(this, com.google.android.material.R.attr.colorSurfaceContainer)
        val text = themeColor(this, com.google.android.material.R.attr.colorOnSurface)
        val secondary = themeColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
        (topBar.parent as View).setBackgroundColor(surface)
        for (bar in listOf(topBar, navBar, chatOnlyBar)) bar.setBackgroundColor(surface)
        addressBar.background = roundedDrawable(container, 24,
            themeColor(this, com.google.android.material.R.attr.colorOutlineVariant), 1)
        address.setTextColor(text)
        address.setHintTextColor(secondary)
        status.setBackgroundColor(surface)
        status.setTextColor(secondary)
        fun recolorButtons(view: View) {
            when (view) {
                is ImageButton -> {
                    view.setColorFilter(text)
                    view.applyModernRipple(8, container)
                }
                is Button -> {
                    view.setTextColor(text)
                    view.applyModernRipple(8, container)
                }
                is ViewGroup -> for (index in 0 until view.childCount) recolorButtons(view.getChildAt(index))
            }
        }
        listOf(topBar, navBar, chatOnlyBar).forEach { recolorButtons(it) }
        updateNormalChatControls()
        updateSystemBarAppearance(window)
    }

    private fun updateSystemBarAppearance(target: android.view.Window) {
        val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        androidx.core.view.WindowCompat.getInsetsController(target, target.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
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
        addressBar = LinearLayout(this).apply {
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

    private fun showSettingsMenu() = showSettingsSheet("アプリの設定") { context, content, dialog ->
        content.addView(settingsSection(context, "外観"))
        content.addView(settingTextColumn(context, "表示テーマ", ""))
        content.addView(com.google.android.material.button.MaterialButtonToggleGroup(context).apply {
            isSingleSelection = true
            isSelectionRequired = true
            val selected = AppAppearance.read(this@MainActivity)
            AppAppearance.entries.forEach { appearance ->
                addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    id = View.generateViewId()
                    text = appearance.label
                    textSize = 14f
                    cornerRadius = dp(8)
                    strokeWidth = 0
                    backgroundTintList = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(context.getColor(R.color.settings_accent), context.getColor(R.color.settings_card_background)))
                    setTextColor(ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(Color.WHITE, context.getColor(R.color.settings_text_primary))))
                    minimumWidth = 0
                    minWidth = 0
                    insetTop = 0
                    insetBottom = 0
                    setPadding(dp(4), 0, dp(4), 0)
                    tag = appearance
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            check((0 until childCount).map { getChildAt(it) }.first { it.tag == selected }.id)
            addOnButtonCheckedListener { group, checkedId, checked ->
                if (checked) {
                    val appearance = group.findViewById<View>(checkedId).tag as AppAppearance
                    AppAppearance.save(this@MainActivity, appearance)
                    if (::playback.isInitialized) playback.applyBrowserAppearance()
                    AppCompatDelegate.setDefaultNightMode(appearance.nightMode)
                }
            }
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6); bottomMargin = dp(16) })

        content.addView(settingsSection(context, "拡張機能"))
        content.addView(settingsSwitchRow(context, "YT Chat Cleaner", "名前・アイコン・チャット幅",
            youtubeChatCleanerEnabled,
            onSettings = {
                dialog.dismiss()
                showExtensionPopup(yccExtension, "popup.html", "YT Chat Cleaner", reloadAfterSave = false)
            },
            onCheckedChange = { setPageFlag(PREF_YCC_ENABLED, "YouTubeChatCleaner", it) }))
        content.addView(settingsSwitchRow(context, "LiveChat Flusher", "読み込み方法・自動オープン",
            liveChatFlusherEnabled,
            onSettings = {
                dialog.dismiss()
                showExtensionPopup(lcfExtension, "options/options.html", "LiveChat Flusher", reloadAfterSave = true)
            },
            onCheckedChange = { setPageFlag(PREF_LCF_ENABLED, "LiveChat Flusher", it) }))
        content.addView(settingsSwitchRow(context, "DVR-chan", "ライブ配信の巻き戻し",
            dvrChanEnabled, onCheckedChange = { setPageFlag(PREF_DVR_ENABLED, "DVR-chan", it) }))

        content.addView(settingsSection(context, "再生とチャット"))
        content.addView(settingsChoiceRow(context, "弾幕文字サイズ",
            LCF_DANMAKU_FONT_SIZES_PX.map { "${it}px" }, LCF_DANMAKU_FONT_SIZES_PX.indexOf(lcfDanmakuFontSizePx)) {
            setLcfDanmakuFontSize(LCF_DANMAKU_FONT_SIZES_PX[it])
        })
        content.addView(settingsSwitchRow(context, "弾幕アイコン", "",
            lcfDanmakuShowIcon, onCheckedChange = { setLcfDanmakuShowIcon(it) }))
        content.addView(settingsSwitchRow(context, "通常チャット専用モード", "",
            chatOnlyModeEnabled, isEnabled = activeSurface == BrowserSurface.VIDEO,
            onCheckedChange = {
                setChatOnlyMode(it)
                if (it) dialog.dismiss()
            }))
        content.addView(settingsSwitchRow(context, "PiPを閉じたら一時停止", "",
            pausePlaybackOnPipClose, onCheckedChange = {
                pausePlaybackOnPipClose = it
                prefs.edit().putBoolean(PREF_PAUSE_ON_PIP_CLOSE, it).apply()
            }))
        val fpsValues = intArrayOf(0, 120, 60, 30, 15)
        content.addView(settingsChoiceRow(context, "フレームレート",
            listOf("制限なし", "120fps", "60fps", "30fps", "15fps"), fpsValues.indexOf(currentOsFps)) {
            currentOsFps = fpsValues[it]
            prefs.edit().putInt(PREF_FPS_LIMIT, currentOsFps).apply()
            applyEffectiveOsFps(showToast = true)
        })

        val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        val extensionVersions = listOf(
            "LiveChat Flusher" to "live_chat_flusher",
            "YT Chat Cleaner" to "youtube_chat_cleaner",
            "DVR-chan" to "dvr_chan",
        ).map { (name, folder) ->
            val manifest = assets.open("extensions/$folder/manifest.json").bufferedReader().use { it.readText() }
            "$name  ${org.json.JSONObject(manifest).getString("version")}"
        }
        content.addView(settingTextColumn(context, "バージョン情報",
            (listOf("YTFlow  ${packageInfo.versionName} (${packageInfo.longVersionCode})") + extensionVersions).joinToString("\n"),
        ).apply { setPadding(0, dp(24), 0, dp(8)) })
    }

    private fun showSettingsSheet(
        title: String,
        fillContent: (Context, LinearLayout, BottomSheetDialog) -> Unit,
    ) {
        val dialog = BottomSheetDialog(this, R.style.YTFlowSettingsBottomSheetDialog)
        val context = dialog.context
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val scrollView = NestedScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        fun render() {
            val scrollY = scrollView.scrollY
            context.theme.rebase()
            context.theme.applyStyle(R.style.YTFlowSettingsBottomSheetDialog, true)
            root.removeAllViews()
            root.addView(View(context).apply {
                background = roundedDrawable(themeColor(context, com.google.android.material.R.attr.colorOutline), 2)
            }, LinearLayout.LayoutParams(dp(32), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(8)
            })
            val header = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = title
                    textSize = 22f
                    gravity = Gravity.CENTER_VERTICAL
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface))
                }, LinearLayout.LayoutParams(0, dp(56), 1f))
                addView(settingsIconButton(context, "閉じる", R.drawable.ic_close) { dialog.dismiss() },
                    LinearLayout.LayoutParams(dp(48), dp(48)))
            }
            root.addView(header)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(24))
            }
            fillContent(context, content, dialog)
            scrollView.removeAllViews()
            scrollView.addView(content)
            root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))
            scrollView.post { scrollView.scrollTo(0, scrollY) }
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.backgroundTintList =
                ColorStateList.valueOf(themeColor(context, com.google.android.material.R.attr.colorSurface))
        }
        render()
        dialog.setContentView(root)
        dialog.setOnShowListener {
            activeSettingsDialog = dialog
            activeSettingsScrollView = scrollView
            refreshSettingsContent = { render() }
            configureSettingsBottomSheet(dialog)
        }
        dialog.setOnDismissListener {
            if (activeSettingsDialog === dialog) {
                activeSettingsChoicePopup?.dismiss()
                activeSettingsDialog = null
                activeSettingsScrollView = null
                refreshSettingsContent = null
            }
        }
        dialog.show()
    }

    private fun configureSettingsBottomSheet(dialog: BottomSheetDialog) {
        dialog.window?.let { updateSystemBarAppearance(it) }
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val width = metrics.bounds.width() - insets.left - insets.right
        val height = metrics.bounds.height() - insets.top - insets.bottom
        dialog.behavior.maxWidth = minOf(width, dp(600))
        dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.layoutParams = sheet.layoutParams.apply {
                this.height = (height * if (width > height) 0.94f else 0.88f).toInt()
            }
            sheet.requestLayout()
        }
        dialog.behavior.skipCollapsed = true
        dialog.behavior.setDraggableOnNestedScroll(false)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    private fun settingsSection(context: Context, title: String) = TextView(context).apply {
        text = title
        textSize = 13f
        setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(8))
    }

    private fun settingsRow(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun settingsDivider(context: Context, row: View): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(-1, -2))
            addView(View(context).apply {
                setBackgroundColor(themeColor(context, com.google.android.material.R.attr.colorOutlineVariant))
            }, LinearLayout.LayoutParams(-1, dp(1)))
        }

    private fun settingsSwitchRow(
        context: Context,
        title: String,
        summary: String,
        checked: Boolean,
        isEnabled: Boolean = true,
        onSettings: (() -> Unit)? = null,
        onCheckedChange: (Boolean) -> Unit,
    ): View {
        val row = settingsRow(context)
        if (summary.isNotEmpty()) row.minimumHeight = dp(64)
        val switch = MaterialSwitch(context).apply {
            this.isEnabled = isEnabled
            isChecked = checked
            contentDescription = title
            minimumHeight = dp(48)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(context.getColor(R.color.settings_accent), context.getColor(R.color.settings_switch_off)))
            trackDecorationTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setOnCheckedChangeListener { _, value -> onCheckedChange(value) }
        }
        row.addView(settingTextColumn(context, title, summary),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
        if (onSettings != null) {
            row.addView(settingsIconButton(context, "$title の設定", R.drawable.ic_settings_outline, onSettings),
                LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        row.addView(switch, LinearLayout.LayoutParams(-2, dp(48)))
        row.alpha = if (isEnabled) 1f else 0.45f
        row.setOnClickListener { if (isEnabled) switch.isChecked = !switch.isChecked }
        return settingsDivider(context, row)
    }

    private fun settingsChoiceRow(
        context: Context,
        title: String,
        labels: List<String>,
        selected: Int,
        onSelected: (Int) -> Unit,
    ): View {
        val row = settingsRow(context)
        row.addView(settingTextColumn(context, title, ""), LinearLayout.LayoutParams(0, -2, 1f))
        var selectedIndex = selected.coerceIn(labels.indices)
        val value = TextView(context).apply {
            text = labels[selectedIndex]
            textSize = 14f
            setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        val choiceControl = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            addView(value)
        }
        fun showChoices() {
            activeSettingsChoicePopup?.dismiss()
            val popup = ListPopupWindow(context).apply {
                anchorView = choiceControl
                setDropDownGravity(Gravity.END)
                width = minOf(row.width, dp(240))
                isModal = true
                inputMethodMode = ListPopupWindow.INPUT_METHOD_NOT_NEEDED
                setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_single_choice, labels))
                setOnItemClickListener { _, _, index, _ ->
                    onSelected(index)
                    selectedIndex = index
                    value.text = labels[index]
                    dismiss()
                }
            }
            popup.setOnDismissListener {
                if (activeSettingsChoicePopup === popup) activeSettingsChoicePopup = null
            }
            activeSettingsChoicePopup = popup
            popup.show()
            popup.listView?.apply {
                choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
                setItemChecked(selectedIndex, true)
                setSelection(selectedIndex)
            }
        }
        choiceControl.addView(settingsIconButton(context, "$title を変更", R.drawable.ic_expand_more) { showChoices() },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        choiceControl.setOnClickListener { showChoices() }
        row.addView(choiceControl)
        row.setOnClickListener { showChoices() }
        return settingsDivider(context, row)
    }

    private fun settingsIconButton(context: Context, label: String, icon: Int, action: () -> Unit) =
        androidx.appcompat.widget.AppCompatImageButton(context).apply {
            contentDescription = label
            tooltipText = label
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            val background = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true)
            setBackgroundResource(background.resourceId)
            setOnClickListener { action() }
        }

    private fun settingTextColumn(context: Context, title: String, summary: String) =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = title
                textSize = 16f
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface))
            })
            if (summary.isNotEmpty()) addView(TextView(context).apply {
                text = summary
                textSize = 12f
                setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(4), 0, 0)
            })
        }

    private fun showSettingsChoice(title: String, labels: List<String>, selected: Int, onSelected: (Int) -> Unit) =
        showSettingsSheet(title) { context, content, dialog ->
            labels.forEachIndexed { index, label ->
                content.addView(MaterialRadioButton(context).apply {
                    text = label
                    textSize = 16f
                    isChecked = index == selected
                    setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface))
                    setOnClickListener {
                        onSelected(index)
                        dialog.dismiss()
                    }
                }, LinearLayout.LayoutParams(-1, dp(52)))
            }
        }

    private fun showNormalChatFontSizeDialog() {
        val values = NORMAL_CHAT_FONT_SCALES
        showSettingsChoice("通常チャット文字サイズ", values.map { "${it}%" }, values.indexOf(normalChatFontScale)) {
            setNormalChatFontScale(values[it])
        }
    }

    private fun showNormalChatIntervalDialog() {
        val values = NORMAL_CHAT_INTERVALS_MS
        showSettingsChoice("通常チャット更新間隔", values.map { "${it}ms" }, values.indexOf(normalChatIntervalMs)) {
            setNormalChatInterval(values[it])
        }
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

    private fun setLcfDanmakuFontSize(sizePx: Int) {
        lcfDanmakuFontSizePx = normalizeLcfDanmakuFontSize(sizePx)
        prefs.edit().putInt(PREF_LCF_DANMAKU_FONT_SIZE_PX, lcfDanmakuFontSizePx).apply()
        applyLiveChatFlusherAppSettings()
        status.text = "弾幕文字サイズ: ${lcfDanmakuFontSizePx}px"
    }

    private fun setLcfDanmakuShowIcon(show: Boolean) {
        lcfDanmakuShowIcon = show
        prefs.edit().putBoolean(PREF_LCF_DANMAKU_SHOW_ICON, show).apply()
        applyLiveChatFlusherAppSettings()
        status.text = "弾幕アイコン: ${if (show) "ON" else "OFF"}"
    }

    private fun normalizeLcfDanmakuFontSize(sizePx: Int): Int =
        sizePx.coerceIn(LCF_DANMAKU_FONT_SIZES_PX.first(), LCF_DANMAKU_FONT_SIZES_PX.last())

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
            btnNormalChatName.setTextColor(themeColor(this, if (normalChatShowName)
                com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        if (::btnNormalChatIcon.isInitialized) {
            btnNormalChatIcon.text = if (normalChatShowIcon) "顔ON" else "顔OFF"
            btnNormalChatIcon.setTextColor(themeColor(this, if (normalChatShowIcon)
                com.google.android.material.R.attr.colorOnSurface else com.google.android.material.R.attr.colorOnSurfaceVariant))
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
            setTextColor(themeColor(context, com.google.android.material.R.attr.colorOnSurface))
            applyModernRipple(8, themeColor(context, com.google.android.material.R.attr.colorSurfaceContainer))
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener { onLongClick() }
            }
        }

    private fun toolbarIconButton(label: String, iconRes: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            contentDescription = label
            setImageResource(iconRes)
            setColorFilter(themeColor(context, com.google.android.material.R.attr.colorOnSurface))
            scaleType = ImageView.ScaleType.CENTER
            setMinimumHeight(0)
            setMinimumWidth(0)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            
            applyModernRipple(8, themeColor(context, com.google.android.material.R.attr.colorSurfaceContainer))
            
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
        runtime = playback.runtime
        mobileSession = createManagedSession(BrowserSurface.MOBILE, BrowserMode.MOBILE)
        videoSession = createManagedSession(BrowserSurface.VIDEO, BrowserMode.DESKTOP, playback.videoSession)
        mobileSession.open(runtime)
        geckoView.setSession(mobileSession)
        runtime.webExtensionController.setTabActive(mobileSession, true)
        runtime.webExtensionController.setTabActive(videoSession, false)
    }

    private fun createManagedSession(surface: BrowserSurface, initialMode: BrowserMode, existing: GeckoSession? = null): GeckoSession {
        val session = existing ?: GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(
                    if (initialMode == BrowserMode.DESKTOP) {
                        GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
                    } else {
                        GeckoSessionSettings.USER_AGENT_MODE_MOBILE
                    }
                )
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
                .useTrackingProtection(false)
                .build(),
        )
        if (surface != BrowserSurface.VIDEO) session.permissionDelegate = object : GeckoSession.PermissionDelegate {
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
                                        const response = document.querySelector('#movie_player')?.getPlayerResponse();
                                        const live = response?.videoDetails?.videoId === u.searchParams.get('v')
                                            ? '&live=' + (response.microformat?.playerMicroformatRenderer?.liveBroadcastDetails?.isLiveNow === true)
                                            : '';
                                        alert('ytcc-save-state:t=' + Math.floor(video.currentTime) + '&url=' + encodeURIComponent(u.toString()) + live);
                                    } catch(e) {}
                                }
                            }, 5000);
                        }
                    """.trimIndent()
                    session.loadUri("javascript:${Uri.encode(script)}")
                    applyLiveChatFlusherAppSettings()
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
                    playback.savePlaybackAlert(message)
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
                    BrowserSurface.VIDEO -> {
                        videoCanGoBack = canGoBack
                        playback.canGoBack = canGoBack
                    }
                    BrowserSurface.CHAT -> Unit
                }
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                when (surface) {
                    BrowserSurface.MOBILE -> mobileCanGoForward = canGoForward
                    BrowserSurface.VIDEO -> {
                        videoCanGoForward = canGoForward
                        playback.canGoForward = canGoForward
                    }
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
            "resource://android/assets/extensions/dvr_chan/",
            "dvr-chan@local",
            "DVR-chan",
            {}
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

        val dialog = Dialog(this, R.style.YTFlowExtensionSettingsDialog)
        val context = dialog.context
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = MaterialShapeDrawable(ShapeAppearanceModel.builder()
                .setAllCornerSizes(resources.getDimension(R.dimen.settings_corner_radius)).build())
            clipToOutline = true
            // Keep the web surface clear of the rounded bottom corners.
            setPadding(0, 0, 0, resources.getDimensionPixelSize(R.dimen.settings_corner_radius))
        }
        val header = MaterialToolbar(context).apply {
            id = R.id.extension_settings_toolbar
            title = label
            menu.add("閉じる").apply {
                setIcon(R.drawable.ic_close)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
                contentDescription = "閉じる"
            }
            setOnMenuItemClickListener {
                dialog.dismiss()
                true
            }
        }
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        val popupView = GeckoView(context)
        root.addView(
            popupView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnShowListener {
            activeExtensionDialog = dialog
            configureExtensionDialog(dialog)
        }
        dialog.setOnDismissListener {
            if (!isFinishing && !isChangingConfigurations && reloadAfterSave && savedInPopup[0]) {
                activeSession().reload()
                status.text = "$label の設定を反映するため再読み込みしました"
            }
            if (activeExtensionDialog === dialog) activeExtensionDialog = null
            runCatching { popupSession.stop() }
            runCatching { popupSession.close() }
        }

        popupView.setSession(popupSession)
        popupSession.loadUri(ext.metaData.baseUrl + pagePath)
        dialog.show()
    }

    private fun configureExtensionDialog(dialog: Dialog) {
        dialog.window?.let { updateSystemBarAppearance(it) }
        dialog.context.theme.rebase()
        dialog.context.theme.applyStyle(R.style.YTFlowExtensionSettingsDialog, true)
        val surface = MaterialColors.getColor(dialog.context, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        val onSurface = MaterialColors.getColor(dialog.context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        dialog.findViewById<MaterialToolbar>(R.id.extension_settings_toolbar)?.let { toolbar ->
            toolbar.setBackgroundColor(surface)
            toolbar.setTitleTextColor(onSurface)
            toolbar.menu.getItem(0).icon?.setTint(onSurface)
            ((toolbar.parent as View).background as MaterialShapeDrawable).fillColor = ColorStateList.valueOf(surface)
        }
        val metrics = windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
        val width = metrics.bounds.width() - insets.left - insets.right
        val height = metrics.bounds.height() - insets.top - insets.bottom
        dialog.window?.setLayout(minOf(width - dp(24), dp(960)), (height * 0.9f).toInt())
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

        if (surface == BrowserSurface.MOBILE && chatOnlyModeEnabled) {
            setChatOnlyMode(false)
        }
        if (surface == BrowserSurface.CHAT) ensureChatSession()

        val oldSession = activeSession()
        if (activeSurface == BrowserSurface.VIDEO && surface == BrowserSurface.MOBILE) {
            pauseVideoPlayback()
            hideMediaNotification()
        }
        runtime.webExtensionController.setTabActive(oldSession, false)
        
        if (fullScreen) setAppFullScreen(false)
        activeSurface = surface
        updateRequestedOrientation()
        applyEffectiveOsFps(showToast = false) // 表示画面が変わったのでFPS制限を再評価・適用
        geckoView.setSession(activeSession())
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
        playback.resetVideoSession()
        videoSession = createManagedSession(BrowserSurface.VIDEO, BrowserMode.DESKTOP, playback.videoSession)
        videoMode = BrowserMode.DESKTOP
        videoCanGoBack = false
        videoCanGoForward = false
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
        videoUrl = normalized
        playback.trackVideoUrl(normalized)
    }

    private fun pauseVideoPlayback() {
        requestVideoPlaybackPaused(true)
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
        ::playback.isInitialized && playback.canControl && !playback.paused &&
            activeSurface == BrowserSurface.VIDEO && videoHistory.isNotEmpty() && !inPictureInPicture

    private fun enterAppPictureInPicture() {
        if (!::geckoView.isInitialized) return
        runCatching {
            setPageAppPictureInPictureFlag(true)
            if (!enterPictureInPictureMode(createPictureInPictureParams())) {
                setPageAppPictureInPictureFlag(false)
                Log.w(TAG, "Picture-in-Picture request declined by Android")
            }
        }.onFailure {
            setPageAppPictureInPictureFlag(false)
            Log.w(TAG, "Failed to enter Picture-in-Picture", it)
        }
    }

    private fun createPictureInPictureParams(): PictureInPictureParams {
        val icon = if (videoPlaybackPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val label = if (videoPlaybackPaused) "再生" else "一時停止"
        val toggleAction = RemoteAction(
            Icon.createWithResource(this, icon),
            label,
            label,
            PlaybackService.toggleIntent(this),
        ).apply { isEnabled = playback.canControl }
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(listOf(toggleAction))
            .build()
    }

    private fun showOrUpdateMediaNotification() = playback.showNotification()

    private fun hideMediaNotification() = playback.hideNotification()

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

    private fun triggerYouTubeFullScreen() {
        if (activeSurface != BrowserSurface.VIDEO || !isYouTubeUrl(currentUrl())) return
        geckoView.requestFocus()
        status.text = "全画面: Fキー"
        dispatchNativeKey(android.view.KeyEvent.KEYCODE_F)
    }

    private fun dispatchNativeKey(keyCode: Int) {
        val downTime = android.os.SystemClock.uptimeMillis()
        val downEvent = android.view.KeyEvent(
            downTime,
            downTime,
            android.view.KeyEvent.ACTION_DOWN,
            keyCode,
            0
        )
        val upEvent = android.view.KeyEvent(
            downTime,
            android.os.SystemClock.uptimeMillis(),
            android.view.KeyEvent.ACTION_UP,
            keyCode,
            0
        )
        geckoView.dispatchKeyEvent(downEvent)
        geckoView.dispatchKeyEvent(upEvent)
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

    private fun requestVideoPlaybackPaused(paused: Boolean) = playback.requestPaused(paused)

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
            PREF_DVR_ENABLED -> dvrChanEnabled = enabled
        }
        status.text = "$label: ${if (enabled) "ON" else "OFF"}"
        loadUrl(withAppFlags(currentUrl()))
    }

    private fun setChatOnlyMode(enabled: Boolean) {
        if (enabled && activeSurface != BrowserSurface.VIDEO) {
            chatOnlyModeEnabled = false
            status.text = "通常チャット専用モードは動画画面でオンにしてください"
            applyEffectiveOsFps(showToast = false)
            updateChromeForPictureInPicture()
            return
        }

        if (enabled) {
            chatOnlyModeEnabled = true
            status.text = "通常チャットURLを取得中"
            requestChatOnlyUrlFromVideoPage()
            applyEffectiveOsFps(showToast = false)
            updateChromeForPictureInPicture()
            return
        }

        chatOnlyModeEnabled = enabled
        status.text = "通常チャット専用モード: OFF"
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
        resetChatSessionForChatOnly()
        switchToSurface(BrowserSurface.CHAT)
        applyBrowserMode(BrowserSurface.CHAT, BrowserMode.DESKTOP)
        loadUrlInto(BrowserSurface.CHAT, normalized)
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
        chatCanGoForward = false
        chatUrl = ""
    }

    private fun ensureChatSession(): GeckoSession {
        if (!::chatSession.isInitialized || !chatSessionOpen) {
            chatSession = createManagedSession(BrowserSurface.CHAT, BrowserMode.DESKTOP)
            chatSession.open(runtime)
            chatSessionOpen = true
            chatMode = BrowserMode.DESKTOP
            chatCanGoForward = false
            runCatching { runtime.webExtensionController.setTabActive(chatSession, false) }
        }
        return chatSession
    }

    private fun applyNormalChatSettingsToChatSession() {
        if (!::chatSession.isInitialized || !chatSessionOpen) return
        chatSession.loadUri("javascript:${Uri.encode(normalChatSettingsScript())}")
    }

    private fun applyLiveChatFlusherAppSettings() {
        if (!::videoSession.isInitialized) return
        val showPhotoValue = if (lcfDanmakuShowIcon) "true" else "false"
        val showPhotoFlag = if (lcfDanmakuShowIcon) "1" else "0"
        val script = """
            (() => {
              const root = document.documentElement;
              const detail = {
                fontSizePx: $lcfDanmakuFontSizePx,
                showPhoto: $showPhotoValue
              };
              try { root.setAttribute('data-ytcc-app-lcf-font-size-px', '${lcfDanmakuFontSizePx}'); } catch (_) {}
              try { root.setAttribute('data-ytcc-app-lcf-show-photo', '$showPhotoFlag'); } catch (_) {}
              try { localStorage.setItem('ytcc-app-lcf-font-size-px', '${lcfDanmakuFontSizePx}'); } catch (_) {}
              try { localStorage.setItem('ytcc-app-lcf-show-photo', '$showPhotoFlag'); } catch (_) {}
              window.dispatchEvent(new CustomEvent('ytcc-app-lcf-settings', { detail }));
            })()
        """.trimIndent()
        videoSession.loadUri("javascript:${Uri.encode(script)}")
    }

    private fun normalChatSettingsScript(): String {
        val showNameValue = if (normalChatShowName) "1" else "0"
        val showIconValue = if (normalChatShowIcon) "1" else "0"
        return """
            (() => {
              const root = document.documentElement;
              root.setAttribute('data-ytlcf-app-normal-chat-font-scale', '$normalChatFontScale');
              root.setAttribute('data-ytlcf-app-normal-chat-interval-ms', '$normalChatIntervalMs');
              root.setAttribute('data-ytlcf-app-normal-chat-show-name', '$showNameValue');
              root.setAttribute('data-ytlcf-app-normal-chat-show-photo', '$showIconValue');
              try { localStorage.setItem('ytlcf-app-normal-chat-font-scale', '$normalChatFontScale'); } catch (_) {}
              try { localStorage.setItem('ytlcf-app-normal-chat-interval-ms', '$normalChatIntervalMs'); } catch (_) {}
              try { localStorage.setItem('ytlcf-app-normal-chat-show-name', '$showNameValue'); } catch (_) {}
              try { localStorage.setItem('ytlcf-app-normal-chat-show-photo', '$showIconValue'); } catch (_) {}
              window.dispatchEvent(new Event('ytlcf-normal-chat-change'));
              window.postMessage({ type: 'ytlcf-normal-chat-change' }, '*');
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
            .appendQueryParameter("ytcc_app_dvr", if (dvrChanEnabled) "1" else "0")
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
        val rippleColor = ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(
            themeColor(context, com.google.android.material.R.attr.colorOnSurface), 40))
        
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
        private const val PREF_DVR_ENABLED = "dvr_enabled"
        private const val PREF_PAUSE_ON_PIP_CLOSE = "pause_on_pip_close"
        private const val PREF_LCF_DANMAKU_FONT_SIZE_PX = "lcf_danmaku_font_size_px"
        private const val PREF_LCF_DANMAKU_SHOW_ICON = "lcf_danmaku_show_icon"
        private const val PREF_NORMAL_CHAT_FONT_SCALE = "normal_chat_font_scale"
        private const val PREF_NORMAL_CHAT_INTERVAL_MS = "normal_chat_interval_ms"
        private const val PREF_NORMAL_CHAT_SHOW_NAME = "normal_chat_show_name"
        private const val PREF_NORMAL_CHAT_SHOW_ICON = "normal_chat_show_icon"
        private const val DEFAULT_LCF_DANMAKU_FONT_SIZE_PX = 32
        private val LCF_DANMAKU_FONT_SIZES_PX = intArrayOf(12, 14, 16, 18, 20, 22, 24, 26, 28, 30, 32, 36, 40, 44, 48)
        private const val DEFAULT_NORMAL_CHAT_FONT_SCALE = 180
        private const val DEFAULT_NORMAL_CHAT_INTERVAL_MS = 100
        private val NORMAL_CHAT_FONT_SCALES = intArrayOf(100, 140, 180, 220, 260, 300)
        private const val MIN_NORMAL_CHAT_INTERVAL_MS = 50
        private val NORMAL_CHAT_INTERVALS_MS = intArrayOf(50, 100, 150, 250, 500)
        private const val CHAT_ONLY_FORCED_FPS = 15
        private const val PREF_LAST_VIDEO_URL = "last_video_url"
        private const val PREF_LAST_VIDEO_TIME = "last_video_time"
        private const val REQUEST_POST_NOTIFICATIONS = 2001
        private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
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
