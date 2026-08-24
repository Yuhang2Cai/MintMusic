package com.example.timedmusicplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.timedmusicplayer.adapter.CloudSourceAdapter
import com.example.timedmusicplayer.databinding.ActivityCloudSourceBinding
import com.example.timedmusicplayer.domain.model.CloudSource
import com.example.timedmusicplayer.ui.AppViewModelFactory
import com.example.timedmusicplayer.ui.cloud.CloudSourceEvent
import com.example.timedmusicplayer.ui.cloud.CloudSourceUiState
import com.example.timedmusicplayer.ui.cloud.CloudSourceViewModel
import com.example.timedmusicplayer.ui.theme.ThemeColorStore
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class CloudSourceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudSourceBinding
    private lateinit var adapter: CloudSourceAdapter

    private val viewModel: CloudSourceViewModel by viewModels {
        AppViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeColorStore.applyTheme(this)
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
        binding.etSourceName.doAfterTextChanged { viewModel.onNameChanged(it?.toString().orEmpty()) }
        binding.etSourceUrl.doAfterTextChanged { viewModel.onUrlChanged(it?.toString().orEmpty()) }
        binding.etCoverUrl.doAfterTextChanged { viewModel.onCoverUrlChanged(it?.toString().orEmpty()) }
        binding.btnAddSource.setOnClickListener { viewModel.onAddSource() }

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
        if (binding.etSourceName.text?.toString() != state.inputName) binding.etSourceName.setText(state.inputName)
        if (binding.etSourceUrl.text?.toString() != state.inputUrl) binding.etSourceUrl.setText(state.inputUrl)
        if (binding.etCoverUrl.text?.toString() != state.inputCoverUrl) binding.etCoverUrl.setText(state.inputCoverUrl)
        binding.btnAddSource.isEnabled = !state.isSaving
    }

    private fun handleEvent(event: CloudSourceEvent) {
        when (event) {
            is CloudSourceEvent.ShowMessage -> {
                Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
            }

            CloudSourceEvent.OpenPlayerScreen -> {
                startActivity(Intent(this, PlayerActivity::class.java))
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
