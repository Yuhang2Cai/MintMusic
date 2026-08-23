package com.example.timedmusicplayer.ui.theme

import android.app.Activity
import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.example.timedmusicplayer.R

enum class ThemeColorOption(
    val id: String,
    @StringRes val labelRes: Int,
    @StyleRes val themeRes: Int
) {
    MINT("mint", R.string.theme_mint, R.style.Theme_TimedMusicPlayer),
    BLUE("blue", R.string.theme_blue, R.style.Theme_TimedMusicPlayer_Blue),
    TEAL("teal", R.string.theme_teal, R.style.Theme_TimedMusicPlayer_Teal),
    PURPLE("purple", R.string.theme_purple, R.style.Theme_TimedMusicPlayer_Purple),
    INDIGO("indigo", R.string.theme_indigo, R.style.Theme_TimedMusicPlayer_Indigo),
    PINK("pink", R.string.theme_pink, R.style.Theme_TimedMusicPlayer_Pink),
    ORANGE("orange", R.string.theme_orange, R.style.Theme_TimedMusicPlayer_Orange),
    RED("red", R.string.theme_red, R.style.Theme_TimedMusicPlayer_Red)
}

object ThemeColorStore {
    private const val PREFERENCES_NAME = "appearance_preferences"
    private const val KEY_THEME_COLOR = "theme_color"

    fun applyTheme(activity: Activity) {
        activity.setTheme(current(activity).themeRes)
    }

    fun current(context: Context): ThemeColorOption {
        val storedId = preferences(context).getString(KEY_THEME_COLOR, null)
        return ThemeColorOption.entries.firstOrNull { it.id == storedId } ?: ThemeColorOption.MINT
    }

    fun select(context: Context, option: ThemeColorOption) {
        preferences(context).edit().putString(KEY_THEME_COLOR, option.id).apply()
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
