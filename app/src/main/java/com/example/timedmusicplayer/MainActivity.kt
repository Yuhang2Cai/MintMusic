package com.example.timedmusicplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.timedmusicplayer.adapter.TrackLibraryAdapter
import com.example.timedmusicplayer.databinding.ActivityMainBinding
import com.example.timedmusicplayer.model.TrackFilter
import com.example.timedmusicplayer.ui.AppViewModelFactory
import com.example.timedmusicplayer.ui.main.MainEvent
import com.example.timedmusicplayer.ui.main.MainUiState
import com.example.timedmusicplayer.ui.main.MainViewModel
import com.example.timedmusicplayer.ui.main.MiniPlayerUiState
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: TrackLibraryAdapter

    private val viewModel: MainViewModel by viewModels {
        AppViewModelFactory(application)
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            takePersistableReadPermission(uri)
        }
        viewModel.onLocalFolderSelected(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        adapter = TrackLibraryAdapter(onItemClick = viewModel::onTrackSelected)
        setupRecyclerView()
        setupActions()
        observeViewModel()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStop()
    }

    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(this).apply {
            initialPrefetchItemCount = 14
            isItemPrefetchEnabled = true
        }
        binding.rvTracks.layoutManager = layoutManager
        binding.rvTracks.adapter = adapter
        binding.rvTracks.setHasFixedSize(true)
        binding.rvTracks.itemAnimator = null
        binding.rvTracks.setItemViewCacheSize(24)
        binding.rvTracks.recycledViewPool.setMaxRecycledViews(0, 60)
    }

    private fun setupActions() {
        binding.btnSelectFolder.setOnClickListener {
            viewModel.onSelectFolderClicked()
        }
        binding.btnManageCloud.setOnClickListener {
            viewModel.onManageCloudClicked()
        }
        binding.btnResumeLast.setOnClickListener {
            viewModel.onResumeLastClicked()
        }
        binding.miniPlayerContainer.setOnClickListener {
            viewModel.onMiniPlayerClicked()
        }
        binding.btnMiniPrevious.setOnClickListener {
            viewModel.onMiniPreviousClicked()
        }
        binding.btnMiniPlayPause.setOnClickListener {
            viewModel.onMiniPlayPauseClicked()
        }
        binding.btnMiniNext.setOnClickListener {
            viewModel.onMiniNextClicked()
        }
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipLocal -> TrackFilter.LOCAL
                R.id.chipCloud -> TrackFilter.CLOUD
                else -> TrackFilter.ALL
            }
            viewModel.onFilterSelected(filter)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        render(state)
                    }
                }
                launch {
                    viewModel.events.collect { event ->
                        handleEvent(event)
                    }
                }
                launch {
                    viewModel.pagingData.collect { adapter.submitData(it) }
                }
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
        if (binding.chipGroupFilter.checkedChipId != targetId) {
            binding.chipGroupFilter.check(targetId)
        }
    }

    private fun renderMiniPlayer(miniPlayer: MiniPlayerUiState?) {
        if (miniPlayer == null) {
            binding.miniPlayerContainer.visibility = View.GONE
            return
        }

        binding.miniPlayerContainer.visibility = View.VISIBLE
        binding.tvMiniTitle.text = miniPlayer.title
        binding.tvMiniStatus.text = miniPlayer.status
        binding.btnMiniPlayPause.setImageResource(
            if (miniPlayer.isPlaying) {
                android.R.drawable.ic_media_pause
            } else {
                android.R.drawable.ic_media_play
            }
        )
        binding.btnMiniPrevious.isEnabled = miniPlayer.canSkip
        binding.btnMiniNext.isEnabled = miniPlayer.canSkip
        binding.miniProgressBar.max = miniPlayer.progressMax
        binding.miniProgressBar.progress = miniPlayer.progress
        binding.miniProgressBar.secondaryProgress = miniPlayer.bufferedProgress
        binding.tvMiniCurrentTime.text = miniPlayer.currentTimeText
        binding.tvMiniTotalTime.text = miniPlayer.totalTimeText
    }

    private fun handleEvent(event: MainEvent) {
        when (event) {
            is MainEvent.ShowMessage -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }

            is MainEvent.OpenFolderPicker -> {
                folderPickerLauncher.launch(event.initialUri)
            }

            is MainEvent.OpenPlayer -> {
                startActivity(
                    Intent(this, PlayerActivity::class.java).apply {
                        putParcelableArrayListExtra(PlayerActivity.EXTRA_QUEUE, event.queue)
                        putExtra(PlayerActivity.EXTRA_START_INDEX, event.startIndex)
                    }
                )
            }

            MainEvent.OpenPlayerScreen -> {
                startActivity(Intent(this, PlayerActivity::class.java))
            }

            MainEvent.OpenCloudSourceScreen -> {
                startActivity(Intent(this, CloudSourceActivity::class.java))
            }
        }
    }

    private fun takePersistableReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }
}
