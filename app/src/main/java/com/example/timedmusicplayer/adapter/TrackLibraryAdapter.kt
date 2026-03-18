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

class TrackLibraryAdapter(
    private val onItemClick: (Track) -> Unit
) : ListAdapter<Track, TrackLibraryAdapter.TrackViewHolder>(TrackDiffCallback()) {

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track_library, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = getItem(position)
        holder.title.text = track.title
        holder.subtitle.text = buildSubtitle(track)
        holder.sourceTag.text = if (track.sourceType == SourceType.LOCAL) {
            holder.itemView.context.getString(R.string.source_local)
        } else {
            holder.itemView.context.getString(R.string.source_cloud)
        }
        holder.itemView.setOnClickListener {
            onItemClick(track)
        }
    }

    fun submitTracks(items: List<Track>) {
        submitList(items.toList())
    }

    private fun buildSubtitle(track: Track): String {
        return if (track.durationMs > 0) {
            "${track.artist} - ${formatDuration(track.durationMs)}"
        } else {
            track.artist
        }
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).toInt().coerceAtLeast(0)
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format(Locale.getDefault(), "%02d:%02d", min, sec)
    }

    class TrackViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tvTitle)
        val subtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
        val sourceTag: TextView = itemView.findViewById(R.id.tvSourceTag)
    }

    private class TrackDiffCallback : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean {
            return oldItem == newItem
        }
    }
}

