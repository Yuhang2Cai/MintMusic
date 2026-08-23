package com.example.timedmusicplayer.ui.theme

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persistence boundary for appearance settings used by presentation models. */
class AppearanceRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun selectedTheme(): ThemeColorOption = withContext(Dispatchers.IO) {
        ThemeColorStore.current(appContext)
    }

    suspend fun selectTheme(option: ThemeColorOption) = withContext(Dispatchers.IO) {
        ThemeColorStore.select(appContext, option)
    }
}
