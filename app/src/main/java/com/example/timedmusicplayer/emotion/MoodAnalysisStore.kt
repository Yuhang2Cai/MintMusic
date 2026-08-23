package com.example.timedmusicplayer.emotion

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

data class MoodAnalysisState(
    val isAnalyzing: Boolean = false,
    val label: String? = null
)

/** Persists the compact mood label displayed beside a local track. */
class MoodAnalysisStore(context: Context, observeChanges: Boolean = true) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    // MainActivity, PlayerActivity and WorkManager create separate store objects.
    // They must nevertheless update one shared UI state in this app process.
    private val mutableStates = synchronized(Shared.lock) {
        Shared.flow ?: MutableStateFlow(readStates()).also { Shared.flow = it }
    }
    val states: StateFlow<Map<String, MoodAnalysisState>> = mutableStates

    init {
        if (observeChanges) {
            preferences.registerOnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_STATES) mutableStates.value = readStates()
            }
        }
    }

    fun markAnalyzing(trackId: String) = update(trackId, MoodAnalysisState(isAnalyzing = true))

    fun markCompleted(trackId: String, moods: List<String>) {
        val previous = mutableStates.value[trackId]
        update(trackId, MoodAnalysisState(label = previous?.label ?: moods.firstNotNullOfOrNull(::toChineseLabel)))
    }

    fun markFailed(trackId: String) {
        val previous = mutableStates.value[trackId]
        update(trackId, previous?.copy(isAnalyzing = false) ?: MoodAnalysisState())
    }

    fun setLabel(trackId: String, label: String?) {
        val previous = mutableStates.value[trackId]
        update(trackId, MoodAnalysisState(isAnalyzing = previous?.isAnalyzing == true, label = label))
    }

    private fun update(trackId: String, state: MoodAnalysisState) {
        val updated = mutableStates.value.toMutableMap()
        if (!state.isAnalyzing && state.label.isNullOrBlank()) updated.remove(trackId) else updated[trackId] = state
        preferences.edit().putString(KEY_STATES, JSONObject().apply {
            updated.forEach { (id, value) ->
                put(id, JSONObject().apply {
                    put("analyzing", value.isAnalyzing)
                    put("label", value.label)
                })
            }
        }.toString()).apply()
        mutableStates.value = updated
    }

    private fun readStates(): Map<String, MoodAnalysisState> = runCatching {
        val json = JSONObject(preferences.getString(KEY_STATES, "{}") ?: "{}")
        buildMap {
            json.keys().forEach { id ->
                json.optJSONObject(id)?.let { item ->
                    put(id, MoodAnalysisState(item.optBoolean("analyzing"), item.optString("label").ifBlank { null }))
                }
            }
        }
    }.getOrDefault(emptyMap())

    private fun toChineseLabel(value: String): String? = when (value.lowercase()) {
        "warm", "hopeful" -> "温暖"
        "romantic", "love" -> "浪漫"
        "healing", "ballad", "emotional" -> "治愈"
        "melancholic", "sad", "dark" -> "忧郁"
        "motivational", "powerful", "adventure" -> "激昂"
        "dramatic", "epic" -> "恢弘"
        else -> null
    }

    private companion object {
        const val PREFS_NAME = "mood_analysis"
        const val KEY_STATES = "track_states"

        object Shared {
            val lock = Any()
            var flow: MutableStateFlow<Map<String, MoodAnalysisState>>? = null
        }
    }
}
