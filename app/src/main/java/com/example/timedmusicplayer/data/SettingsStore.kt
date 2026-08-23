package com.example.timedmusicplayer.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore by preferencesDataStore("mint_settings")

class SettingsStore(private val context: Context) {
    suspend fun folderUri(): String? = read(KEY_FOLDER_URI)
    suspend fun setFolderUri(value: String) = write(KEY_FOLDER_URI, value)
    suspend fun playbackMode(default: String): String = read(KEY_PLAYBACK_MODE) ?: default
    suspend fun setPlaybackMode(value: String) = write(KEY_PLAYBACK_MODE, value)
    suspend fun selectedFilter(default: String): String = read(KEY_FILTER) ?: default
    suspend fun setSelectedFilter(value: String) = write(KEY_FILTER, value)
    suspend fun autoResume(): Boolean = context.settingsDataStore.data.first()[KEY_AUTO_RESUME] ?: true
    suspend fun weakNetworkMaxRetries(): Int = context.settingsDataStore.data.first()[KEY_WEAK_RETRIES] ?: 4
    suspend fun migrationVersion(): Int = context.settingsDataStore.data.first()[KEY_MIGRATION_VERSION] ?: 0
    suspend fun setMigrationVersion(value: Int) { context.settingsDataStore.edit { it[KEY_MIGRATION_VERSION] = value } }
    private suspend fun read(key: Preferences.Key<String>): String? = context.settingsDataStore.data.first()[key]
    private suspend fun write(key: Preferences.Key<String>, value: String) { context.settingsDataStore.edit { it[key] = value } }
    companion object {
        private val KEY_FOLDER_URI = stringPreferencesKey("folder_uri")
        private val KEY_PLAYBACK_MODE = stringPreferencesKey("playback_mode")
        private val KEY_FILTER = stringPreferencesKey("library_filter")
        private val KEY_AUTO_RESUME = booleanPreferencesKey("auto_resume")
        private val KEY_WEAK_RETRIES = intPreferencesKey("weak_network_max_retries")
        private val KEY_MIGRATION_VERSION = intPreferencesKey("migration_version")
    }
}
