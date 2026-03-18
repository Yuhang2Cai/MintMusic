package com.example.timedmusicplayer

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.timedmusicplayer.databinding.ActivityPlayerBinding
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.ui.AppViewModelFactory
import com.example.timedmusicplayer.ui.player.PlayerUiState
import com.example.timedmusicplayer.ui.player.PlayerViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var coverRotationAnimator: ObjectAnimator? = null

    private val viewModel: PlayerViewModel by viewModels {
        AppViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        initCoverRotation()
        applyCoverArt()
        setupControls()
        observeViewModel()
        readIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStop()
        pauseCoverRotation()
    }

    override fun onDestroy() {
        coverRotationAnimator?.cancel()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setupControls() {
        binding.btnPrevious.setOnClickListener {
            viewModel.onPreviousClicked()
        }
        binding.btnNext.setOnClickListener {
            viewModel.onNextClicked()
        }
        binding.btnPlayPause.setOnClickListener {
            viewModel.onPlayPauseClicked()
        }
        binding.btnPlaybackMode.setOnClickListener {
            viewModel.onPlaybackModeClicked()
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    viewModel.onSeekPreview(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                viewModel.onSeekStarted()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                viewModel.onSeekCompleted(seekBar?.progress ?: 0)
            }
        })
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        render(state)
                    }
                }
            }
        }
    }

    private fun readIntent(intent: Intent?) {
        if (intent == null) {
            return
        }
        val queue = intent.getParcelableArrayListExtra<Track>(EXTRA_QUEUE)
        if (!queue.isNullOrEmpty()) {
            val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)
            viewModel.onQueueReceived(queue, startIndex)
        }
    }

    private fun render(state: PlayerUiState) {
        binding.tvTrackTitle.text = state.title
        binding.toolbar.subtitle = state.subtitle
        binding.tvStatus.text = state.statusText
        binding.btnPlaybackMode.text = state.playbackModeText
        binding.loadingContainer.visibility = if (state.showLoading) View.VISIBLE else View.GONE
        binding.tvCurrentTime.text = state.currentTimeText
        binding.tvTotalTime.text = state.totalTimeText
        binding.tvBufferedInfo.text = state.bufferedInfoText
        binding.tvBufferedInfo.visibility = if (state.showBufferedInfo) View.VISIBLE else View.GONE
        binding.seekBar.max = state.seekMax
        binding.seekBar.progress = state.seekProgress
        binding.seekBar.secondaryProgress = state.bufferedProgress
        binding.btnPlayPause.setImageResource(
            if (state.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        )
        binding.btnPrevious.isEnabled = state.canSkip
        binding.btnNext.isEnabled = state.canSkip

        if (state.isPlaying) {
            startCoverRotation()
        } else {
            pauseCoverRotation()
        }
    }

    private fun initCoverRotation() {
        if (coverRotationAnimator != null) {
            return
        }
        coverRotationAnimator = ObjectAnimator.ofFloat(binding.ivCover, View.ROTATION, 0f, 360f).apply {
            duration = 12_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }
    }

    private fun startCoverRotation() {
        val animator = coverRotationAnimator ?: return
        if (animator.isPaused) {
            animator.resume()
        } else if (!animator.isStarted) {
            animator.start()
        }
    }

    private fun pauseCoverRotation() {
        val animator = coverRotationAnimator ?: return
        if (animator.isRunning) {
            animator.pause()
        }
    }

    private fun applyCoverArt() {
        val customCoverResId = resources.getIdentifier(CUSTOM_COVER_RES_NAME, "drawable", packageName)
        if (customCoverResId != 0) {
            binding.ivCover.setImageResource(customCoverResId)
        } else {
            binding.ivCover.setImageResource(R.drawable.cover_placeholder)
        }
    }

    companion object {
        const val EXTRA_QUEUE = "extra_queue"
        const val EXTRA_START_INDEX = "extra_start_index"

        private const val CUSTOM_COVER_RES_NAME = "jay_cover"
    }
}
