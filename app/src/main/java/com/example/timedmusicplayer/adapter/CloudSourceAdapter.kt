package com.example.timedmusicplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.model.CloudSource

class CloudSourceAdapter(
    private val onItemClick: (CloudSource) -> Unit,
    private val onEditClick: (CloudSource) -> Unit,
    private val onDeleteClick: (CloudSource) -> Unit
) : ListAdapter<CloudSource, CloudSourceAdapter.SourceViewHolder>(CloudSourceDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cloud_source, parent, false)
        return SourceViewHolder(view)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        val item = getItem(position)
        holder.title.text = item.name
        holder.subtitle.text = item.url
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.editButton.setOnClickListener { onEditClick(item) }
        holder.deleteButton.setOnClickListener { onDeleteClick(item) }
    }

    fun submitSources(items: List<CloudSource>) {
        submitList(items.toList())
    }

    class SourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.tvTitle)
        val subtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
        val editButton: ImageButton = itemView.findViewById(R.id.btnEdit)
        val deleteButton: ImageButton = itemView.findViewById(R.id.btnDelete)
    }

    private class CloudSourceDiffCallback : DiffUtil.ItemCallback<CloudSource>() {
        override fun areItemsTheSame(oldItem: CloudSource, newItem: CloudSource): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CloudSource, newItem: CloudSource): Boolean {
            return oldItem == newItem
        }
    }
}
