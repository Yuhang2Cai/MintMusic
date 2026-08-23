package com.example.timedmusicplayer

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import com.example.timedmusicplayer.databinding.ActivityPlayerEditorialBinding
import com.example.timedmusicplayer.artwork.ArtworkRepository
import com.example.timedmusicplayer.playback.AudioVisualizerController
import com.example.timedmusicplayer.lyrics.LyricLine
import com.example.timedmusicplayer.lyrics.LyricPageView
import com.example.timedmusicplayer.ui.AppViewModelFactory
import com.example.timedmusicplayer.ui.player.MoodResultUi
import com.example.timedmusicplayer.ui.player.PlayerEvent
import com.example.timedmusicplayer.ui.player.PlayerUiState
import com.example.timedmusicplayer.ui.player.PlayerViewModel
import com.example.timedmusicplayer.ui.theme.ThemeColorStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.chip.Chip
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerEditorialBinding
    private var coverRotationAnimator: ObjectAnimator? = null
    private val artwork by lazy { ArtworkRepository(applicationContext) }
    private var displayedCoverId: String? = null
    private var latestState = PlayerUiState()
    private var audioPermissionRequested = false
    private var activityStarted = false
    private var isSeekBarTracking = false
    private lateinit var lyricPage: LyricPageView
    private var lyricLines: List<LyricLine> = emptyList()
    private var displayedLyricTrackId: String? = null
    private var showingLyrics = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private val visualizerController by lazy {
        AudioVisualizerController { fft, samplingRate ->
            binding.spectrumView.post {
                binding.spectrumView.updateFft(fft, samplingRate)
            }
        }
    }
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            updateVisualizer(latestState)
        } else {
            binding.spectrumView.setActive(false)
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

        initCoverRotation()
        applyCoverArt()
        setupControls()
        setupLyricPage()
        binding.dotCover.setOnClickListener { hideLyrics() }
        binding.dotLyrics.setOnClickListener { showLyrics() }
        lyricPage.onPageSelected = { lyricsSelected ->
            if (lyricsSelected) showLyrics() else hideLyrics()
        }
        observeViewModel()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.x
                touchDownY = ev.y
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - touchDownX
                val dy = ev.y - touchDownY
                if (kotlin.math.abs(dx) >= 120f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    if (dx < 0 && lyricLines.isNotEmpty()) showLyrics()
                    else if (dx > 0 && showingLyrics) hideLyrics()
                }
            }
        }
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
        activityStarted = true
        viewModel.onStart()
    }

    override fun onStop() {
        activityStarted = false
        visualizerController.release()
        binding.spectrumView.setActive(false)
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
        binding.btnSleepTimer.setOnClickListener {
            showSleepTimerDialog()
        }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvCurrentTime.text = viewModel.onSeekPreview(progress)
                }
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
            PlayerEvent.ConfirmLyricsGeneration -> showGenerateLyricsDialog()
            PlayerEvent.ConfirmMoodAnalysis -> showAnalyzeMoodDialog()
        }
    }

    private fun render(state: PlayerUiState) {
        latestState = state
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

        if (state.currentTrack?.id != displayedCoverId) {
            displayedCoverId = state.currentTrack?.id
            artwork.load(binding.ivCover, state.currentTrack, 512)
        }
        renderLyrics(state)
        lyricPage.updatePosition(state.positionMs)

        if (state.isPlaying) {
            startCoverRotation()
        } else {
            pauseCoverRotation()
        }

        updateVisualizer(state)
    }

    private fun setupLyricPage() {
        lyricPage = LyricPageView(this).apply { visibility = View.GONE }
        addContentView(
            lyricPage,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { topMargin = (64 * resources.displayMetrics.density).toInt() }
        )
    }

    private fun renderLyrics(state: PlayerUiState) {
        if (displayedLyricTrackId == state.lyricTrackId && lyricLines == state.lyrics) return
        displayedLyricTrackId = state.lyricTrackId
        lyricLines = state.lyrics
        lyricPage.setLyrics(state.title, lyricLines)
        updatePageIndicator()
        if (lyricLines.isEmpty()) hideLyrics(immediate = true)
    }

    private fun showLyrics() {
        if (lyricLines.isEmpty() || showingLyrics) return
        showingLyrics = true
        lyricPage.translationX = -lyricPage.width.toFloat()
        lyricPage.visibility = View.VISIBLE
        lyricPage.animate().translationX(0f).setDuration(220).start()
        updatePageIndicator()
    }

    private fun hideLyrics(immediate: Boolean = false) {
        if (!showingLyrics && lyricPage.visibility != View.VISIBLE) return
        showingLyrics = false
        if (immediate) {
            lyricPage.visibility = View.GONE
            lyricPage.translationX = 0f
        } else lyricPage.animate().translationX(-lyricPage.width.toFloat()).setDuration(220)
            .withEndAction { lyricPage.visibility = View.GONE; lyricPage.translationX = 0f }.start()
        updatePageIndicator()
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
        fun score(value: Double) = if (value.isNaN()) "—" else String.format(java.util.Locale.getDefault(), "%.2f", value)
        val content = layoutInflater.inflate(R.layout.dialog_mood_result, null)
        content.findViewById<android.widget.TextView>(R.id.tvMoodDialogTrack).text = result.trackTitle
        content.findViewById<android.widget.TextView>(R.id.tvMoodValence).text = score(result.valence)
        content.findViewById<android.widget.TextView>(R.id.tvMoodArousal).text = score(result.arousal)
        val chips = content.findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipMoodResult)
        result.labels.ifEmpty { listOf(getString(R.string.music_mood_no_label)) }.forEach { label ->
            chips.addView(Chip(this).apply { text = label; isClickable = false; isCheckable = false; setChipBackgroundColorResource(R.color.app_surface); setTextColor(ContextCompat.getColor(this@PlayerActivity, R.color.app_text_primary)) })
        }
        MaterialAlertDialogBuilder(this)
            .setView(content)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun renderMoodAnalysis(state: PlayerUiState) {
        val processing = state.isLyricsLoading || state.isMoodAnalyzing
        binding.topMoodAnalysisContainer.visibility = if (processing || !state.moodLabel.isNullOrBlank()) View.VISIBLE else View.GONE
        binding.topMoodIndicator.visibility = if (processing) View.VISIBLE else View.GONE
        binding.tvTopMoodLabel.text = when {
            state.isLyricsLoading -> getString(R.string.lyrics_matching)
            state.isMoodAnalyzing -> getString(R.string.music_mood_analyzing)
            else -> state.moodLabel.orEmpty()
        }
        binding.tvTopMoodLabel.setBackgroundResource(if (processing) android.R.color.transparent else R.drawable.bg_mood_tag)
        binding.tvPlayerMoodTag.visibility = View.GONE
    }

    private fun updatePageIndicator() {
        val hasLyrics = lyricLines.isNotEmpty()
        binding.coverPageIndicator.visibility = if (hasLyrics) View.VISIBLE else View.GONE
        binding.dotCover.setBackgroundResource(
            if (showingLyrics) R.drawable.bg_page_dot_inactive else R.drawable.bg_page_dot_active
        )
        binding.dotLyrics.setBackgroundResource(
            if (showingLyrics) R.drawable.bg_page_dot_active else R.drawable.bg_page_dot_inactive
        )
        binding.dotCover.layoutParams.width = dp(if (showingLyrics) 7 else 18)
        binding.dotLyrics.layoutParams.width = dp(if (showingLyrics) 18 else 7)
        binding.dotCover.requestLayout()
        binding.dotLyrics.requestLayout()
        lyricPage.setPageIndicatorVisible(hasLyrics, showingLyrics)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun showSleepTimerDialog() {
        val labels = TIMER_MINUTES
            .map { minutes -> getString(R.string.sleep_timer_minutes, minutes) }
            .toMutableList()
        val hasActiveTimer = latestState.sleepTimerRemainingMs > 0L
        if (hasActiveTimer) {
            labels += getString(R.string.sleep_timer_cancel)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sleep_timer_title)
            .setItems(labels.toTypedArray()) { _, index ->
                if (hasActiveTimer && index == labels.lastIndex) {
                    viewModel.onSleepTimerCancelled()
                } else {
                    viewModel.onSleepTimerSelected(TIMER_MINUTES[index])
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateVisualizer(state: PlayerUiState) {
        val shouldVisualize = activityStarted &&
            state.isPlaying &&
            state.audioSessionId != C.AUDIO_SESSION_ID_UNSET
        binding.spectrumView.setActive(shouldVisualize)
        if (!shouldVisualize) {
            visualizerController.release()
            return
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            visualizerController.attach(state.audioSessionId)
        } else if (!audioPermissionRequested) {
            audioPermissionRequested = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun applyCoverArt() {
        binding.ivCover.setImageResource(R.drawable.cover_placeholder)
    }

    private fun initCoverRotation() {
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
        if (animator.isStarted && !animator.isPaused) {
            animator.pause()
        }
    }

    private companion object {
        val TIMER_MINUTES = intArrayOf(15, 30, 45, 60)
    }

}
