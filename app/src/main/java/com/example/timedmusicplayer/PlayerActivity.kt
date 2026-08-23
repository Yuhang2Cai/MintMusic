package com.example.timedmusicplayer

import android.Manifest
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.timedmusicplayer.databinding.ActivityPlayerEditorialBinding
import com.example.timedmusicplayer.ui.AppViewModelFactory
import com.example.timedmusicplayer.ui.player.MoodResultUi
import com.example.timedmusicplayer.ui.player.PlayerEvent
import com.example.timedmusicplayer.ui.player.PlayerLyricsViewController
import com.example.timedmusicplayer.ui.player.PlayerUiState
import com.example.timedmusicplayer.ui.player.PlayerViewModel
import com.example.timedmusicplayer.ui.player.PlayerVisualEffectsController
import com.example.timedmusicplayer.ui.player.SleepTimerOptionUi
import com.example.timedmusicplayer.ui.theme.ThemeColorStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.chip.Chip
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerEditorialBinding
    private var isSeekBarTracking = false
    private lateinit var lyricsController: PlayerLyricsViewController
    private lateinit var visualEffects: PlayerVisualEffectsController
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (::visualEffects.isInitialized) visualEffects.onAudioPermissionResult(granted)
        if (!granted) {
            Toast.makeText(
                this,
                R.string.audio_visualizer_permission_denied,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val viewModel: PlayerViewModel by viewModels {
        AppViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColorStore.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerEditorialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        visualEffects = PlayerVisualEffectsController(this, binding) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        setupControls()
        lyricsController = PlayerLyricsViewController(this, binding, viewModel::onLyricsPageSelected)
        observeViewModel()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::lyricsController.isInitialized) lyricsController.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.player_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_generate_lyrics -> {
            // Close the overflow popup before the ViewModel requests a modal dialog.
            binding.root.post { viewModel.onGenerateLyricsClicked() }
            true
        }
        R.id.action_analyze_mood -> {
            // Let AppCompat close the overflow popup before showing a modal.
            // Otherwise the popup remains visibly layered behind the dialog.
            binding.root.post { viewModel.onAnalyzeMoodClicked() }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onStart() {
        super.onStart()
        visualEffects.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        visualEffects.onStop()
        super.onStop()
        viewModel.onStop()
    }

    override fun onDestroy() {
        visualEffects.onDestroy()
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
        binding.btnSleepTimer.setOnClickListener {
            viewModel.onSleepTimerClicked()
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.onSeekPreview(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekBarTracking = true
                viewModel.onSeekStarted()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val targetProgress = seekBar?.progress ?: 0
                isSeekBarTracking = false
                viewModel.onSeekCompleted(targetProgress)
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
                launch { viewModel.events.collect(::handleEvent) }
                launch {
                    viewModel.seekPreviewText.collect { preview ->
                        if (preview != null) binding.tvCurrentTime.text = preview
                    }
                }
            }
        }
    }

    private fun handleEvent(event: PlayerEvent) {
        when (event) {
            is PlayerEvent.ShowMessage -> Toast.makeText(
                this,
                event.message,
                if (event.long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
            is PlayerEvent.ShowMoodResult -> showMoodResult(event.result)
            is PlayerEvent.ShowSleepTimerOptions -> showSleepTimerDialog(event.options)
            PlayerEvent.ConfirmLyricsGeneration -> showGenerateLyricsDialog()
            PlayerEvent.ConfirmMoodAnalysis -> showAnalyzeMoodDialog()
        }
    }

    private fun render(state: PlayerUiState) {
        renderMoodAnalysis(state)
        binding.tvTrackTitle.text = state.title
        binding.tvTrackArtist.text = state.subtitle.orEmpty()
        binding.tvStatus.text = state.statusText
        binding.btnPlaybackMode.text = state.playbackModeText
        binding.btnSleepTimer.text = state.sleepTimerText
        binding.loadingContainer.visibility = if (state.showLoading) View.VISIBLE else View.GONE
        binding.tvCurrentTime.text = state.currentTimeText
        binding.tvTotalTime.text = state.totalTimeText
        binding.tvBufferedInfo.text = state.bufferedInfoText
        binding.tvBufferedInfo.visibility = if (state.showBufferedInfo) View.VISIBLE else View.GONE
        binding.seekBar.isEnabled = state.canSeek
        binding.seekBar.alpha = if (state.canSeek) 1f else 0.45f
        if (binding.seekBar.max != state.seekMax) {
            binding.seekBar.max = state.seekMax
        }
        if (!isSeekBarTracking && binding.seekBar.progress != state.seekProgress) {
            binding.seekBar.progress = state.seekProgress
        }
        if (binding.seekBar.secondaryProgress != state.bufferedProgress) {
            binding.seekBar.secondaryProgress = state.bufferedProgress
        }
        binding.btnPlayPause.setImageResource(
            if (state.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        )
        binding.btnPrevious.isEnabled = state.canSkip
        binding.btnNext.isEnabled = state.canSkip

        lyricsController.render(state)
        visualEffects.render(state)
    }

    private fun showGenerateLyricsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.lyrics_lookup_title)
            .setMessage(R.string.lyrics_lookup_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.generate_lyrics) { _, _ -> viewModel.onGenerateLyricsConfirmed() }
            .show()
    }

    private fun showAnalyzeMoodDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.music_mood_title)
            .setMessage(R.string.music_mood_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.music_mood_start) { _, _ -> viewModel.onAnalyzeMoodConfirmed() }
            .show()
    }

    private fun showMoodResult(result: MoodResultUi) {
        val content = layoutInflater.inflate(R.layout.dialog_mood_result, null)
        content.findViewById<android.widget.TextView>(R.id.tvMoodDialogTrack).text = result.trackTitle
        content.findViewById<android.widget.TextView>(R.id.tvMoodValence).text = result.valenceText
        content.findViewById<android.widget.TextView>(R.id.tvMoodArousal).text = result.arousalText
        val chips = content.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipMoodResult)
        result.labels.forEach { label ->
            chips.addView(Chip(this).apply { text = label; isClickable = false; isCheckable = false; setChipBackgroundColorResource(R.color.app_surface); setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.app_text_primary)) })
        }
        MaterialAlertDialogBuilder(this)
            .setView(content)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun renderMoodAnalysis(state: PlayerUiState) {
        binding.topMoodAnalysisContainer.visibility = if (state.showContentStatus) View.VISIBLE else View.GONE
        binding.topMoodIndicator.visibility = if (state.isContentProcessing) View.VISIBLE else View.GONE
        binding.tvTopMoodLabel.text = state.contentStatusText
        binding.tvTopMoodLabel.setBackgroundResource(
            if (state.isContentProcessing) android.R.color.transparent else R.drawable.bg_mood_tag
        )
        binding.tvPlayerMoodTag.visibility = View.GONE
    }

    private fun showSleepTimerDialog(options: List<SleepTimerOptionUi>) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer_title)
            .setItems(options.map(SleepTimerOptionUi::label).toTypedArray()) { _, index ->
                viewModel.onSleepTimerOptionSelected(options[index])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

}
