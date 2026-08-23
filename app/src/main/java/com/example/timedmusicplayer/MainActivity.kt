package com.example.timedmusicplayer

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.timedmusicplayer.adapter.TrackLibraryAdapter
import com.example.timedmusicplayer.artwork.ArtworkRepository
import com.example.timedmusicplayer.databinding.ActivityMainBinding
import com.example.timedmusicplayer.databinding.MiniPlayerOverlayBinding
import com.example.timedmusicplayer.model.Track
import com.example.timedmusicplayer.model.TrackFilter
import com.example.timedmusicplayer.ui.AppViewModelFactory
import com.example.timedmusicplayer.ui.main.MainEvent
import com.example.timedmusicplayer.ui.main.MainUiState
import com.example.timedmusicplayer.ui.main.MainViewModel
import com.example.timedmusicplayer.ui.main.MiniPlayerUiState
import com.example.timedmusicplayer.ui.theme.ThemeColorOption
import com.example.timedmusicplayer.ui.theme.ThemeColorStore
import com.example.timedmusicplayer.emotion.MoodAnalysisStore
import com.example.timedmusicplayer.model.SourceType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlin.math.hypot

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var miniBinding: MiniPlayerOverlayBinding
    private lateinit var adapter: TrackLibraryAdapter
    private lateinit var artworkRepository: ArtworkRepository
    private lateinit var moodAnalysisStore: MoodAnalysisStore
    private lateinit var selectionBackCallback: OnBackPressedCallback
    private lateinit var windowManager: WindowManager
    private lateinit var miniWindowParams: WindowManager.LayoutParams
    private val selectedTracks = linkedMapOf<String, Track>()

    private var activityStarted = false
    private var hasMiniPlayer = false
    private var miniWindowAdded = false
    private var miniExpanded = false
    private var miniDockedToLeft = false
    private var miniWidthAnimator: ValueAnimator? = null
    private var miniSnapAnimator: ValueAnimator? = null
    private var miniCoverAnimator: ObjectAnimator? = null
    private var displayedMiniTrackId: String? = null

    private val viewModel: MainViewModel by viewModels { AppViewModelFactory(application) }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) takePersistableReadPermission(uri)
        viewModel.onLocalFolderSelected(uri)
    }

    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.notification_permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColorStore.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        requestNotificationPermissionIfNeeded()

        artworkRepository = ArtworkRepository(applicationContext)
        moodAnalysisStore = MoodAnalysisStore(applicationContext)
        adapter = TrackLibraryAdapter(::onTrackClicked, ::onTrackLongClicked, ::onTrackMoreClicked)
        setupRecyclerView()
        setupMiniWindow()
        setupActions()
        observeViewModel()
        selectionBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = clearSelection()
        }
        onBackPressedDispatcher.addCallback(this, selectionBackCallback)
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        if (hasMiniPlayer) showMiniWindow()
        viewModel.onStart()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onStop() {
        activityStarted = false
        removeMiniWindow()
        viewModel.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        removeMiniWindow()
        miniWidthAnimator?.cancel()
        miniSnapAnimator?.cancel()
        miniCoverAnimator?.cancel()
        super.onDestroy()
    }

    private fun setupRecyclerView() {
        binding.rvTracks.layoutManager = LinearLayoutManager(this).apply {
            initialPrefetchItemCount = 14
            isItemPrefetchEnabled = true
        }
        binding.rvTracks.adapter = adapter
        binding.rvTracks.setHasFixedSize(true)
        binding.rvTracks.itemAnimator = null
        binding.rvTracks.setItemViewCacheSize(24)
        binding.rvTracks.recycledViewPool.setMaxRecycledViews(0, 60)
    }

    private fun setupMiniWindow() {
        miniBinding = MiniPlayerOverlayBinding.inflate(layoutInflater)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        miniWindowParams = WindowManager.LayoutParams(
            dp(72),
            dp(72),
            WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - width).coerceAtLeast(0)
            y = (screenHeight - dp(176)).coerceAtLeast(dp(24))
        }
        installMiniWindowDragHandler()
    }

    private fun setupActions() {
        binding.btnSelectFolder.setOnClickListener { viewModel.onSelectFolderClicked() }
        binding.btnManageCloud.setOnClickListener { viewModel.onManageCloudClicked() }
        binding.btnResumeLast.setOnClickListener { viewModel.onResumeLastClicked() }
        binding.btnDeleteSelected.setOnClickListener { confirmDeleteSelection() }
        binding.btnCancelSelection.setOnClickListener { clearSelection() }
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            updateFilterUnderline(checkedIds.firstOrNull())
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipLocal -> TrackFilter.LOCAL
                R.id.chipCloud -> TrackFilter.CLOUD
                else -> TrackFilter.ALL
            }
            clearSelection()
            viewModel.onFilterSelected(filter)
        }

        miniBinding.miniPlayerContainer.setOnClickListener { toggleMiniPlayer() }
        miniBinding.ivMiniCover.setOnClickListener { toggleMiniPlayer() }
        miniBinding.miniTrackInfo.setOnClickListener { viewModel.onMiniPlayerClicked() }
        miniBinding.btnMiniPrevious.setOnClickListener { viewModel.onMiniPreviousClicked() }
        miniBinding.btnMiniPlayPause.setOnClickListener { viewModel.onMiniPlayPauseClicked() }
        miniBinding.btnMiniNext.setOnClickListener { viewModel.onMiniNextClicked() }
    }

    private fun installMiniWindowDragHandler() {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var downWindowX = 0
        var downWindowY = 0
        var dragging = false
        miniBinding.ivMiniCover.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    miniSnapAnimator?.cancel()
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = miniWindowParams.x
                    downWindowY = miniWindowParams.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && hypot(dx.toDouble(), dy.toDouble()) >= touchSlop) dragging = true
                    if (dragging) {
                        miniWindowParams.x = clampWindowX(downWindowX + dx.toInt())
                        miniWindowParams.y = clampWindowY(downWindowY + dy.toInt())
                        updateMiniWindowLayout()
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        snapMiniWindowToNearestEdge()
                    } else {
                        miniBinding.ivMiniCover.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) snapMiniWindowToNearestEdge()
                    true
                }
                else -> false
            }
        }
    }

    private fun showMiniWindow() {
        if (!activityStarted || miniWindowAdded || !hasMiniPlayer) return
        val token = window.decorView.windowToken
        if (token == null) {
            window.decorView.post(::showMiniWindow)
            return
        }
        miniWindowParams.token = token
        runCatching { windowManager.addView(miniBinding.root, miniWindowParams) }
            .onSuccess { miniWindowAdded = true }
            .onFailure { miniWindowAdded = false }
    }

    private fun removeMiniWindow() {
        if (!miniWindowAdded) return
        runCatching { windowManager.removeViewImmediate(miniBinding.root) }
        miniWindowAdded = false
    }

    private fun updateMiniWindowLayout() {
        if (miniWindowAdded) runCatching { windowManager.updateViewLayout(miniBinding.root, miniWindowParams) }
    }

    private fun clampWindowX(value: Int): Int {
        val max = (resources.displayMetrics.widthPixels - miniWindowParams.width).coerceAtLeast(0)
        return value.coerceIn(0, max)
    }

    private fun clampWindowY(value: Int): Int {
        val min = dp(24)
        val max = (resources.displayMetrics.heightPixels - miniWindowParams.height - dp(24)).coerceAtLeast(min)
        return value.coerceIn(min, max)
    }

    private fun snapMiniWindowToNearestEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val maxX = (screenWidth - miniWindowParams.width).coerceAtLeast(0)
        val windowCenterX = miniWindowParams.x + miniWindowParams.width / 2
        miniDockedToLeft = windowCenterX <= screenWidth / 2
        val targetX = if (miniDockedToLeft) 0 else maxX
        val startX = miniWindowParams.x
        if (startX == targetX) return

        miniSnapAnimator?.cancel()
        miniSnapAnimator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 220L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                miniWindowParams.x = animation.animatedValue as Int
                updateMiniWindowLayout()
            }
            start()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
                launch { viewModel.pagingData.collect(adapter::submitData) }
                launch { moodAnalysisStore.states.collect(adapter::setMoodStates) }
            }
        }
    }

    private fun render(state: MainUiState) {
        binding.tvLibraryCount.text = state.libraryCountText
        binding.tvEmpty.visibility = if (state.showEmpty) View.VISIBLE else View.GONE
        renderFilter(state.activeFilter)
        renderMiniPlayer(state.miniPlayer)
    }

    private fun renderFilter(filter: TrackFilter) {
        val targetId = when (filter) {
            TrackFilter.ALL -> R.id.chipAll
            TrackFilter.LOCAL -> R.id.chipLocal
            TrackFilter.CLOUD -> R.id.chipCloud
        }
        if (binding.chipGroupFilter.checkedChipId != targetId) binding.chipGroupFilter.check(targetId)
        updateFilterUnderline(targetId)
    }

    private fun updateFilterUnderline(selectedId: Int?) {
        listOf(binding.chipAll, binding.chipLocal, binding.chipCloud).forEach { chip ->
            chip.paintFlags = if (chip.id == selectedId) {
                chip.paintFlags or Paint.UNDERLINE_TEXT_FLAG
            } else {
                chip.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
            chip.invalidate()
        }
    }

    private fun renderMiniPlayer(miniPlayer: MiniPlayerUiState?) {
        hasMiniPlayer = miniPlayer != null
        if (miniPlayer == null) {
            setMiniPlayerExpanded(false, animate = false)
            removeMiniWindow()
            miniCoverAnimator?.pause()
            return
        }
        showMiniWindow()
        miniBinding.miniPlayerContainer.contentDescription = getString(
            if (miniExpanded) R.string.collapse_mini_player else R.string.expand_mini_player
        )
        if (displayedMiniTrackId != miniPlayer.track?.id) {
            displayedMiniTrackId = miniPlayer.track?.id
            artworkRepository.load(miniBinding.ivMiniCover, miniPlayer.track, dp(128))
        }
        updateCoverRotation(miniPlayer.isPlaying)
        miniBinding.tvMiniTitle.text = miniPlayer.title
        miniBinding.tvMiniStatus.text = miniPlayer.status
        miniBinding.btnMiniPlayPause.setImageResource(
            if (miniPlayer.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        miniBinding.btnMiniPrevious.isEnabled = miniPlayer.canSkip
        miniBinding.btnMiniNext.isEnabled = miniPlayer.canSkip
        miniBinding.miniProgressBar.max = miniPlayer.progressMax
        miniBinding.miniProgressBar.progress = miniPlayer.progress
        miniBinding.miniProgressBar.secondaryProgress = miniPlayer.bufferedProgress
        miniBinding.tvMiniCurrentTime.text = miniPlayer.currentTimeText
        miniBinding.tvMiniTotalTime.text = miniPlayer.totalTimeText
    }

    private fun handleEvent(event: MainEvent) {
        when (event) {
            is MainEvent.ShowMessage -> Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            is MainEvent.TracksDeleted -> {
                clearSelection()
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
            }
            is MainEvent.OpenFolderPicker -> folderPickerLauncher.launch(event.initialUri)
            MainEvent.OpenPlayerScreen -> startActivity(Intent(this, PlayerActivity::class.java))
            MainEvent.OpenCloudSourceScreen -> startActivity(Intent(this, CloudSourceActivity::class.java))
        }
    }

    private fun onTrackClicked(track: Track) {
        if (selectedTracks.isNotEmpty()) toggleTrackSelection(track) else viewModel.onTrackSelected(track)
    }

    private fun onTrackLongClicked(track: Track) = toggleTrackSelection(track)

    private fun onTrackMoreClicked(track: Track) {
        if (track.sourceType != SourceType.LOCAL) return
        val labels = arrayOf(
            getString(R.string.mood_tag_none),
            "温暖", "浪漫", "治愈", "忧郁", "激昂", "恢弘"
        )
        val current = moodAnalysisStore.states.value[track.id]?.label
        val selected = labels.indexOf(current).takeIf { it >= 0 } ?: 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.edit_mood_tag_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                moodAnalysisStore.setLabel(track.id, labels[which].takeUnless { which == 0 })
                dialog.dismiss()
            }
            .show()
    }

    private fun toggleTrackSelection(track: Track) {
        if (selectedTracks.remove(track.id) == null) selectedTracks[track.id] = track
        renderSelection()
    }

    private fun renderSelection() {
        val active = selectedTracks.isNotEmpty()
        binding.selectionBar.visibility = if (active) View.VISIBLE else View.GONE
        binding.tvSelectionCount.text = getString(R.string.selected_tracks_count, selectedTracks.size)
        adapter.setSelection(selectedTracks.keys, active)
        selectionBackCallback.isEnabled = active
    }

    private fun clearSelection() {
        if (selectedTracks.isEmpty()) return
        selectedTracks.clear()
        renderSelection()
    }

    private fun confirmDeleteSelection() {
        val tracks = selectedTracks.values.toList()
        if (tracks.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_tracks_title)
            .setMessage(getString(R.string.delete_tracks_message, tracks.size))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.onDeleteTracksConfirmed(tracks) }
            .show()
    }

    private fun showThemeColorDialog() {
        val options = ThemeColorOption.entries
        val labels = options.map { getString(it.labelRes) }.toTypedArray()
        val selectedIndex = options.indexOf(ThemeColorStore.current(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_theme_color)
            .setSingleChoiceItems(labels, selectedIndex, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                val selected = options[position]
                if (selected != ThemeColorStore.current(this)) {
                    ThemeColorStore.select(this, selected)
                    dialog.dismiss()
                    recreate()
                } else {
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.actionThemeSettings) {
            showThemeColorDialog()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun toggleMiniPlayer() = setMiniPlayerExpanded(!miniExpanded, animate = true)

    private fun setMiniPlayerExpanded(expanded: Boolean, animate: Boolean) {
        if (miniExpanded == expanded && animate) return
        miniExpanded = expanded
        val collapsedWidth = dp(72)
        val expandedWidth = (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(collapsedWidth)
        miniWidthAnimator?.cancel()
        val startWidth = miniWindowParams.width
        val startX = miniWindowParams.x
        val targetWidth = if (expanded) expandedWidth else collapsedWidth
        val screenWidth = resources.displayMetrics.widthPixels
        if (expanded) miniDockedToLeft = startX + startWidth / 2 <= screenWidth / 2
        val targetX = if (expanded) {
            ((screenWidth - targetWidth) / 2).coerceAtLeast(0)
        } else if (miniDockedToLeft) {
            0
        } else {
            (screenWidth - targetWidth).coerceAtLeast(0)
        }
        if (expanded) miniBinding.miniExpandedContent.visibility = View.VISIBLE
        miniBinding.miniPlayerContainer.contentDescription = getString(
            if (expanded) R.string.collapse_mini_player else R.string.expand_mini_player
        )
        if (!animate) {
            miniWindowParams.width = targetWidth
            miniWindowParams.x = targetX
            miniBinding.miniPlayerContainer.radius = dp(if (expanded) 20 else 36).toFloat()
            miniBinding.miniExpandedContent.alpha = if (expanded) 1f else 0f
            miniBinding.miniExpandedContent.visibility = if (expanded) View.VISIBLE else View.GONE
            updateMiniWindowLayout()
            return
        }
        miniWidthAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                miniWindowParams.width = (startWidth + (targetWidth - startWidth) * fraction).toInt()
                miniWindowParams.x = (startX + (targetX - startX) * fraction).toInt()
                val radiusFraction = if (expanded) fraction else 1f - fraction
                miniBinding.miniPlayerContainer.radius = dp(36) + (dp(20) - dp(36)) * radiusFraction
                miniBinding.miniExpandedContent.alpha = if (expanded) fraction else 1f - fraction
                updateMiniWindowLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!expanded) miniBinding.miniExpandedContent.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun updateCoverRotation(isPlaying: Boolean) {
        val animator = miniCoverAnimator ?: ObjectAnimator.ofFloat(miniBinding.ivMiniCover, View.ROTATION, 0f, 360f).apply {
            duration = 12_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }.also { miniCoverAnimator = it }
        if (isPlaying) {
            if (!animator.isStarted) animator.start() else if (animator.isPaused) animator.resume()
        } else if (animator.isStarted && !animator.isPaused) {
            animator.pause()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun takePersistableReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }
}
