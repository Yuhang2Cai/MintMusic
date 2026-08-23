package com.example.timedmusicplayer.playback

import android.media.audiofx.Visualizer
import androidx.media3.common.C

class AudioVisualizerController(
    private val onFftData: (fft: ByteArray, samplingRateMilliHertz: Int) -> Unit
) {
    private var visualizer: Visualizer? = null
    private var attachedSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    fun attach(audioSessionId: Int): Boolean {
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return false
        if (audioSessionId == attachedSessionId && visualizer != null) return true

        release()
        return runCatching {
            Visualizer(audioSessionId).apply {
                val captureRange = Visualizer.getCaptureSizeRange()
                captureSize = DEFAULT_CAPTURE_SIZE.coerceIn(captureRange[0], captureRange[1])
                setDataCaptureListener(
                    object : Visualizer.OnDataCaptureListener {
                        override fun onWaveFormDataCapture(
                            visualizer: Visualizer?,
                            waveform: ByteArray?,
                            samplingRate: Int
                        ) = Unit

                        override fun onFftDataCapture(
                            visualizer: Visualizer?,
                            fft: ByteArray?,
                            samplingRate: Int
                        ) {
                            fft?.let { onFftData(it.copyOf(), samplingRate) }
                        }
                    },
                    Visualizer.getMaxCaptureRate(),
                    false,
                    true
                )
                enabled = true
            }.also {
                visualizer = it
                attachedSessionId = audioSessionId
            }
            true
        }.getOrDefault(false)
    }

    fun release() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        attachedSessionId = C.AUDIO_SESSION_ID_UNSET
    }

    private companion object {
        // More bins are needed for logarithmic frequency bands to remain distinct.
        const val DEFAULT_CAPTURE_SIZE = 1024
    }
}
