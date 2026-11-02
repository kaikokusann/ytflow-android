package dev.ytchatplayer.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import org.mozilla.geckoview.GeckoRuntimeSettings

internal enum class AppAppearance(val label: String, val nightMode: Int, val colorScheme: Int) {
    LIGHT("ライト", AppCompatDelegate.MODE_NIGHT_NO, GeckoRuntimeSettings.COLOR_SCHEME_LIGHT),
    DARK("ダーク", AppCompatDelegate.MODE_NIGHT_YES, GeckoRuntimeSettings.COLOR_SCHEME_DARK),
    SYSTEM("システム", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM);

    companion object {
        private const val KEY = "app_appearance"

        fun read(context: Context): AppAppearance {
            val saved = context.getSharedPreferences("debug_toggles", Context.MODE_PRIVATE).getString(KEY, null)
            return entries.firstOrNull { it.name == saved } ?: SYSTEM
        }

        fun save(context: Context, appearance: AppAppearance) {
            context.getSharedPreferences("debug_toggles", Context.MODE_PRIVATE)
                .edit().putString(KEY, appearance.name).apply()
        }
    }
}
