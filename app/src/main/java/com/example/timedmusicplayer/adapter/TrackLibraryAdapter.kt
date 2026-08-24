package com.example.timedmusicplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.artwork.ArtworkRepository
import com.example.timedmusicplayer.domain.model.SourceType
import com.example.timedmusicplayer.domain.model.Track
import com.example.timedmusicplayer.emotion.MoodAnalysisState
import java.util.Locale

class TrackLibraryAdapter(
    private val onItemClick: (Track) -> Unit,
    private val onItemLongClick: (Track) -> Unit,
    private val onMoreClick: (Track) -> Unit
) : PagingDataAdapter<Track, TrackLibraryAdapter.TrackViewHolder>(TrackDiffCallback()) {

    private var artworkRepository: ArtworkRepository? = null
    private var selectedIds: Set<String> = emptySet()
    private var selectionMode = false
    private var moodStates: Map<String, MoodAnalysisState> = emptyMap()

    fun setMoodStates(states: Map<String, MoodAnalysisState>) {
        if (moodStates == states) return
        moodStates = states
        notifyDataSetChanged()
    }

    fun setSelection(selectedIds: Set<String>, selectionMode: Boolean) {
        if (this.selectedIds == selectedIds && this.selectionMode == selectionMode) return
        this.selectedIds = selectedIds.toSet()
        this.selectionMode = selectionMode
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track_library, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val track = getItem(position) ?: return
        holder.title.text = track.title
        val artwork = artworkRepository ?: ArtworkRepository(holder.itemView.context.applicationContext).also { artworkRepository = it }
        artwork.load(holder.cover, track, 128)
        holder.subtitle.text = buildSubtitle(track)
        holder.sourceTag.text = if (track.sourceType == SourceType.LOCAL) {
            holder.itemView.context.getString(R.string.source_local)
        } else {
            holder.itemView.context.getString(R.string.source_cloud)
        }
        val mood = moodStates[track.id]
        holder.moodTag.text = mood?.label
        holder.moodTag.visibility = if (!mood?.label.isNullOrBlank()) View.VISIBLE else View.GONE
        holder.analyzing.visibility = if (mood?.isAnalyzing == true) View.VISIBLE else View.GONE
        holder.itemView.isActivated = track.id in selectedIds
        holder.selection.visibility = if (selectionMode) View.VISIBLE else View.GONE
        holder.selection.isChecked = track.id in selectedIds
        holder.more.visibility = if (selectionMode) View.GONE else View.VISIBLE
        holder.itemView.setOnClickListener {
            onItemClick(track)
        }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(track)
            true
        }
        holder.more.setOnClickListener { onMoreClick(track) }
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
        val cover: ImageView = itemView.findViewById(R.id.ivCover)
        val subtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
        val sourceTag: TextView = itemView.findViewById(R.id.tvSourceTag)
        val moodTag: TextView = itemView.findViewById(R.id.tvMoodTag)
        val analyzing: View = itemView.findViewById(R.id.analyzingContainer)
        val more: ImageView = itemView.findViewById(R.id.btnTrackMore)
        val selection: android.widget.CheckBox = itemView.findViewById(R.id.cbTrackSelection)
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

