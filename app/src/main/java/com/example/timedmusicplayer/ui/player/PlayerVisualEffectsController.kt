package com.example.timedmusicplayer.ui.player

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.artwork.ArtworkRepository
import com.example.timedmusicplayer.databinding.ActivityPlayerEditorialBinding
import com.example.timedmusicplayer.playback.AudioVisualizerController

/** Owns artwork, cover animation and audio-spectrum rendering for the player view. */
class PlayerVisualEffectsController(
    private val activity: AppCompatActivity,
    private val binding: ActivityPlayerEditorialBinding,
    private val requestAudioPermission: () -> Unit
) {
    private val artwork = ArtworkRepository(activity.applicationContext)
    private val visualizer = AudioVisualizerController { fft, samplingRate ->
        binding.spectrumView.post { binding.spectrumView.updateFft(fft, samplingRate) }
    }
    private val coverAnimator = ObjectAnimator.ofFloat(binding.ivCover, View.ROTATION, 0f, 360f).apply {
        duration = 12_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
    }
    private var latestState = PlayerUiState()
    private var displayedCoverId: String? = null
    private var started = false
    private var permissionRequested = false

    init {
        binding.ivCover.setImageResource(R.drawable.cover_placeholder)
    }

    fun onStart() {
        started = true
        renderVisualizer(latestState)
    }

    fun onStop() {
        started = false
        visualizer.release()
        binding.spectrumView.setActive(false)
        pauseCoverRotation()
    }

    fun onDestroy() {
        visualizer.release()
        coverAnimator.cancel()
    }

    fun onAudioPermissionResult(granted: Boolean) {
        if (granted) renderVisualizer(latestState) else binding.spectrumView.setActive(false)
    }

    fun render(state: PlayerUiState) {
        latestState = state
        if (state.currentTrack?.id != displayedCoverId) {
            displayedCoverId = state.currentTrack?.id
            artwork.load(binding.ivCover, state.currentTrack, 512)
        }
        if (state.isPlaying) startCoverRotation() else pauseCoverRotation()
        renderVisualizer(state)
    }

    private fun renderVisualizer(state: PlayerUiState) {
        val shouldVisualize = started && state.isPlaying && state.audioSessionId != C.AUDIO_SESSION_ID_UNSET
        binding.spectrumView.setActive(shouldVisualize)
        if (!shouldVisualize) {
            visualizer.release()
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            visualizer.attach(state.audioSessionId)
        } else if (!permissionRequested) {
            permissionRequested = true
            requestAudioPermission()
        }
    }

    private fun startCoverRotation() {
        if (coverAnimator.isPaused) coverAnimator.resume()
        else if (!coverAnimator.isStarted) coverAnimator.start()
    }

    private fun pauseCoverRotation() {
        if (coverAnimator.isStarted && !coverAnimator.isPaused) coverAnimator.pause()
    }
}
