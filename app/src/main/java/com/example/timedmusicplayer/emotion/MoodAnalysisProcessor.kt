package com.example.timedmusicplayer.emotion

import android.content.Context

/** Application operation used by background scheduling adapters. */
class MoodAnalysisProcessor(context: Context) {
    private val analyzer = MoodAnalyzer(context)
    private val store = MoodAnalysisStore(context, observeChanges = false)

    fun analyze(trackId: String?, uri: String, title: String, mimeType: String): MoodAnalysisOutput {
        return runCatching { analyzer.analyze(uri, title, mimeType) }
            .onSuccess { output -> trackId?.let { store.markCompleted(it, output.moods) } }
            .onFailure { trackId?.let(store::markFailed) }
            .getOrThrow()
    }
}
