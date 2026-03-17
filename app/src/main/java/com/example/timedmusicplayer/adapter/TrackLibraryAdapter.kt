package com.example.timedmusicplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.model.SourceType
import com.example.timedmusicplayer.model.Track
import java.util.Locale

/**
 * 音乐库列表适配器：基于 DiffUtil 与稳定 ID，提升滚动流畅度。
 */
class TrackLibraryAdapter(
    private val onItemClick: (Int) -> Unit
) : ListAdapter<Track, TrackLibraryAdapter.TrackViewHolder>(TrackDiffCallback()) {

    init {
        setHasStableIds(true)
    }

    // 函数： getItemId
    // 说明：读取并返回当前数据或状态快照。
    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    // 函数： onCreateViewHolder
    // 说明：封装 onCreateViewHolder 相关业务流程，负责参数校验、状态流转与异常兜底。
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
    // 属性： view
    // 说明：运行期状态变量，承载 view 相关上下文信息。
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track_library, parent, false)
        return TrackViewHolder(view, onItemClick)
    }

    // 函数： onBindViewHolder
    // 说明：封装 onBindViewHolder 相关业务流程，负责参数校验、状态流转与异常兜底。
    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
    // 属性： track
    // 说明：运行期状态变量，承载 track 相关上下文信息。
        val track = getItem(position)
        holder.title.text = track.title
        holder.subtitle.text = buildSubtitle(track)
        holder.sourceTag.text = if (track.sourceType == SourceType.LOCAL) {
            holder.itemView.context.getString(R.string.source_local)
        } else {
            holder.itemView.context.getString(R.string.source_cloud)
        }
    }

    // 函数： submitTracks
    // 说明：封装 submitTracks 相关业务流程，负责参数校验、状态流转与异常兜底。
    fun submitTracks(items: List<Track>) {
        submitList(items.toList())
    }

// 函数： buildSubtitle
// 说明：封装 buildSubtitle 相关业务流程，负责参数校验、状态流转与异常兜底。
private fun buildSubtitle(track: Track): String {
        return if (track.durationMs > 0) {
            "${track.artist} 闂?${formatDuration(track.durationMs)}"
        } else {
            track.artist
        }
    }

    // 函数： formatDuration
    // 说明：将原始数据转换为便于展示的文本格式。
    private fun formatDuration(durationMs: Long): String {
    // 属性： totalSec
    // 说明：运行期状态变量，承载 totalSec 相关上下文信息。
        val totalSec = (durationMs / 1000L).toInt().coerceAtLeast(0)
    // 属性： min
    // 说明：运行期状态变量，承载 min 相关上下文信息。
        val min = totalSec / 60
    // 属性： sec
    // 说明：运行期状态变量，承载 sec 相关上下文信息。
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }

    class TrackViewHolder(
        itemView: View,
        onItemClick: (Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
    // 属性： title
    // 说明：运行期状态变量，承载 title 相关上下文信息。
        val title: TextView = itemView.findViewById(R.id.tvTitle)
    // 属性： subtitle
    // 说明：运行期状态变量，承载 subtitle 相关上下文信息。
        val subtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
    // 属性： sourceTag
    // 说明：运行期状态变量，承载 sourceTag 相关上下文信息。
        val sourceTag: TextView = itemView.findViewById(R.id.tvSourceTag)

        init {
            itemView.setOnClickListener {
    // 属性： position
    // 说明：进度与定位相关变量，用于计算展示和控制边界。
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onItemClick(position)
                }
            }
        }
    }

    private class TrackDiffCallback : DiffUtil.ItemCallback<Track>() {
        // 函数： areItemsTheSame
        // 说明：封装 areItemsTheSame 相关业务流程，负责参数校验、状态流转与异常兜底。
        override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean {
            return oldItem.id == newItem.id
        }

        // 函数： areContentsTheSame
        // 说明：封装 areContentsTheSame 相关业务流程，负责参数校验、状态流转与异常兜底。
        override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean {
            return oldItem == newItem
        }
    }
}