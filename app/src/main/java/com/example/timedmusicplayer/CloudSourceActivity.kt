package com.example.timedmusicplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.timedmusicplayer.adapter.CloudSourceAdapter
import com.example.timedmusicplayer.data.MusicRepository
import com.example.timedmusicplayer.databinding.ActivityCloudSourceBinding
import com.example.timedmusicplayer.model.CloudSource
import com.example.timedmusicplayer.model.TrackFilter

/**
 * 云端音源管理页：支持新增、编辑、删除与播放在线流。
 */
class CloudSourceActivity : AppCompatActivity() {
    // 属性： binding
    // 说明：当前界面的 ViewBinding 引用，用于类型安全地访问布局控件。
    private lateinit var binding: ActivityCloudSourceBinding
    // 属性： repository
    // 说明：数据仓库入口，统一提供本地与云端数据访问能力。
    private val repository by lazy { MusicRepository.getInstance(this) }

    // 属性： entries
    // 说明：运行期状态变量，承载 entries 相关上下文信息。
    private val entries = mutableListOf<CloudSource>()
    // 属性： adapter
    // 说明：列表适配器实例，负责数据到条目视图的绑定。
    private lateinit var adapter: CloudSourceAdapter

    // 函数： onCreate
    // 说明：生命周期初始化入口，完成依赖注入、组件初始化与初始状态设置。
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCloudSourceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = CloudSourceAdapter(
            entries = entries,
            onItemClick = { index -> openPlayer(index) },
            onEditClick = { index -> editSourceName(index) },
            onDeleteClick = { index -> deleteSource(index) }
        )

        binding.rvCloudSources.layoutManager = LinearLayoutManager(this)
        binding.rvCloudSources.adapter = adapter

        binding.etSourceUrl.setText(DEFAULT_STREAM_URL)
        binding.etSourceName.setText(deriveName(DEFAULT_STREAM_URL))

        binding.btnAddSource.setOnClickListener {
            addSource()
        }

        loadSources()
    }

    // 函数： onSupportNavigateUp
    // 说明：处理顶部返回按钮事件，统一页面回退行为。
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

// 函数： loadSources
// 说明：加载并整理数据，必要时命中缓存或触发刷新。
private fun loadSources() {
        entries.clear()
        entries.addAll(repository.getCloudSources())
        adapter.notifyDataSetChanged()

        binding.tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
    }

// 函数： addSource
// 说明：封装 addSource 相关业务流程，负责参数校验、状态流转与异常兜底。
private fun addSource() {
    // 属性： url
    // 说明：资源定位地址，指向本地文件或在线媒体流。
        val url = binding.etSourceUrl.text?.toString()?.trim().orEmpty()
    // 属性： inputName
    // 说明：运行期状态变量，承载 inputName 相关上下文信息。
        val inputName = binding.etSourceName.text?.toString()?.trim().orEmpty()

        if (url.isBlank()) {
            Toast.makeText(this, getString(R.string.no_stream_url), Toast.LENGTH_SHORT).show()
            return
        }

        if (!isValidUrl(url)) {
            Toast.makeText(this, getString(R.string.invalid_stream_url), Toast.LENGTH_SHORT).show()
            return
        }

        if (repository.hasDuplicateCloudUrl(url)) {
            Toast.makeText(this, getString(R.string.duplicate_stream_url), Toast.LENGTH_SHORT).show()
            return
        }

    // 属性： finalName
    // 说明：运行期状态变量，承载 finalName 相关上下文信息。
        val finalName = if (inputName.isBlank()) deriveName(url) else inputName
        repository.addCloudSource(finalName, url)

        binding.etSourceName.text?.clear()
        loadSources()
    }

    // 函数： editSourceName
    // 说明：封装 editSourceName 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun editSourceName(index: Int) {
    // 属性： item
    // 说明：运行期状态变量，承载 item 相关上下文信息。
        val item = entries.getOrNull(index) ?: return
    // 属性： input
    // 说明：运行期状态变量，承载 input 相关上下文信息。
        val input = EditText(this).apply {
            setText(item.name)
            setSelection(text.length)
        }

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.edit_stream_name))
            .setView(input)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
    // 属性： newName
    // 说明：运行期状态变量，承载 newName 相关上下文信息。
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isBlank()) {
                    Toast.makeText(this, getString(R.string.stream_name_empty), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                repository.renameCloudSource(item.id, newName)
                loadSources()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // 函数： deleteSource
    // 说明：封装 deleteSource 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun deleteSource(index: Int) {
    // 属性： item
    // 说明：运行期状态变量，承载 item 相关上下文信息。
        val item = entries.getOrNull(index) ?: return
        repository.deleteCloudSource(item.id)
        loadSources()
    }

// 函数： openPlayer
// 说明：执行页面跳转或打开目标能力入口。
private fun openPlayer(index: Int) {
    // 属性： source
    // 说明：运行期状态变量，承载 source 相关上下文信息。
        val source = entries.getOrNull(index) ?: return
    // 属性： cloudTracks
    // 说明：当前曲目集合或播放队列，用于驱动列表与切歌逻辑。
        val cloudTracks = repository.getTracks(TrackFilter.CLOUD)
        if (cloudTracks.isEmpty()) {
            return
        }

    // 属性： targetTrackId
    // 说明：运行期状态变量，承载 targetTrackId 相关上下文信息。
        val targetTrackId = "cloud:${source.id}"
    // 属性： targetIndex
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
        val targetIndex = cloudTracks.indexOfFirst { it.id == targetTrackId }
        if (targetIndex == -1) {
            return
        }

        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                putParcelableArrayListExtra(PlayerActivity.EXTRA_QUEUE, ArrayList(cloudTracks))
                putExtra(PlayerActivity.EXTRA_START_INDEX, targetIndex)
            }
        )
    }

    // 函数： isValidUrl
    // 说明：封装 isValidUrl 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun isValidUrl(url: String): Boolean {
        return (url.startsWith("http://") || url.startsWith("https://")) &&
            Patterns.WEB_URL.matcher(url).matches()
    }

    // 函数： deriveName
    // 说明：封装 deriveName 相关业务流程，负责参数校验、状态流转与异常兜底。
    private fun deriveName(url: String): String {
    // 属性： host
    // 说明：运行期状态变量，承载 host 相关上下文信息。
        val host = runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
        return when {
            host.isNotBlank() -> host
            url.length <= 30 -> url
            else -> "${url.take(30)}..."
        }
    }

    companion object {
        private const val DEFAULT_STREAM_URL = "http://ice1.somafm.com/groovesalad-128-mp3"
    }
}