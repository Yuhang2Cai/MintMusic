package com.example.timedmusicplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.timedmusicplayer.adapter.CloudSourceAdapter
import com.example.timedmusicplayer.databinding.ActivityCloudSourceBinding
import com.example.timedmusicplayer.model.CloudSource
import com.example.timedmusicplayer.ui.AppViewModelFactory
import com.example.timedmusicplayer.ui.cloud.CloudSourceEvent
import com.example.timedmusicplayer.ui.cloud.CloudSourceUiState
import com.example.timedmusicplayer.ui.cloud.CloudSourceViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class CloudSourceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudSourceBinding
    private lateinit var adapter: CloudSourceAdapter

    private val viewModel: CloudSourceViewModel by viewModels {
        AppViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloudSourceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = CloudSourceAdapter(
            onItemClick = viewModel::onSourceSelected,
            onEditClick = ::showRenameDialog,
            onDeleteClick = { source -> viewModel.onDeleteSource(source.id) }
        )

        binding.rvCloudSources.layoutManager = LinearLayoutManager(this)
        binding.rvCloudSources.adapter = adapter
        binding.etSourceUrl.setText(viewModel.defaultSourceUrl)
        binding.etSourceName.setText(viewModel.suggestName(viewModel.defaultSourceUrl))
        binding.btnAddSource.setOnClickListener {
            viewModel.onAddSource(
                inputName = binding.etSourceName.text?.toString().orEmpty(),
                url = binding.etSourceUrl.text?.toString().orEmpty()
            )
        }

        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
            }
        }
    }

    private fun render(state: CloudSourceUiState) {
        adapter.submitSources(state.entries)
        binding.tvEmpty.visibility = if (state.isEmpty) View.VISIBLE else View.GONE
    }

    private fun handleEvent(event: CloudSourceEvent) {
        when (event) {
            is CloudSourceEvent.ShowMessage -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }

            is CloudSourceEvent.OpenPlayer -> {
                startActivity(
                    Intent(this, PlayerActivity::class.java).apply {
                        putParcelableArrayListExtra(PlayerActivity.EXTRA_QUEUE, event.queue)
                        putExtra(PlayerActivity.EXTRA_START_INDEX, event.startIndex)
                    }
                )
            }

            CloudSourceEvent.ClearNameInput -> {
                binding.etSourceName.text?.clear()
            }
        }
    }

    private fun showRenameDialog(source: CloudSource) {
        val input = EditText(this).apply {
            setText(source.name)
            setSelection(text?.length ?: 0)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_stream_name))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                viewModel.onRenameSource(source.id, input.text?.toString().orEmpty())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
