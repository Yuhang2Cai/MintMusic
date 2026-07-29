package com.example.timedmusicplayer.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.settingsDataStore by preferencesDataStore("mint_settings")

class SettingsStore(private val context: Context) {
    fun folderUri(): String? = read(KEY_FOLDER_URI)
    fun setFolderUri(value: String) = write(KEY_FOLDER_URI, value)
    fun playbackMode(default: String): String = read(KEY_PLAYBACK_MODE) ?: default
    fun setPlaybackMode(value: String) = write(KEY_PLAYBACK_MODE, value)
    fun selectedFilter(default: String): String = read(KEY_FILTER) ?: default
    fun setSelectedFilter(value: String) = write(KEY_FILTER, value)
    fun autoResume(): Boolean = runBlocking { context.settingsDataStore.data.first()[KEY_AUTO_RESUME] ?: true }
    fun weakNetworkMaxRetries(): Int = runBlocking { context.settingsDataStore.data.first()[KEY_WEAK_RETRIES] ?: 4 }
    fun migrationVersion(): Int = runBlocking { context.settingsDataStore.data.first()[KEY_MIGRATION_VERSION] ?: 0 }
    fun setMigrationVersion(value: Int) = runBlocking { context.settingsDataStore.edit { it[KEY_MIGRATION_VERSION] = value } }
    private fun read(key: Preferences.Key<String>): String? = runBlocking { context.settingsDataStore.data.first()[key] }
    private fun write(key: Preferences.Key<String>, value: String) = runBlocking { context.settingsDataStore.edit { it[key] = value } }
    companion object {
        private val KEY_FOLDER_URI = stringPreferencesKey("folder_uri")
        private val KEY_PLAYBACK_MODE = stringPreferencesKey("playback_mode")
        private val KEY_FILTER = stringPreferencesKey("library_filter")
        private val KEY_AUTO_RESUME = booleanPreferencesKey("auto_resume")
        private val KEY_WEAK_RETRIES = intPreferencesKey("weak_network_max_retries")
        private val KEY_MIGRATION_VERSION = intPreferencesKey("migration_version")
    }
}
