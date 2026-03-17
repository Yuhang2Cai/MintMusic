package com.example.timedmusicplayer.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.timedmusicplayer.R
import com.example.timedmusicplayer.model.CloudSource

/**
 * 云端音源列表适配器，提供播放、重命名、删除回调。
 */
class CloudSourceAdapter(
    private val entries: MutableList<CloudSource>,
    private val onItemClick: (Int) -> Unit,
    private val onEditClick: (Int) -> Unit,
    private val onDeleteClick: (Int) -> Unit
) : RecyclerView.Adapter<CloudSourceAdapter.SourceViewHolder>() {

    // 函数： onCreateViewHolder
    // 说明：封装 onCreateViewHolder 相关业务流程，负责参数校验、状态流转与异常兜底。
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
    // 属性： view
    // 说明：运行期状态变量，承载 view 相关上下文信息。
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cloud_source, parent, false)
        return SourceViewHolder(view)
    }

    // 函数： onBindViewHolder
    // 说明：封装 onBindViewHolder 相关业务流程，负责参数校验、状态流转与异常兜底。
    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
    // 属性： item
    // 说明：运行期状态变量，承载 item 相关上下文信息。
        val item = entries[position]
        holder.title.text = item.name
        holder.subtitle.text = item.url

        holder.itemView.setOnClickListener {
    // 属性： adapterPos
    // 说明：列表适配器实例，负责数据到条目视图的绑定。
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onItemClick(adapterPos)
            }
        }

        holder.editButton.setOnClickListener {
    // 属性： adapterPos
    // 说明：列表适配器实例，负责数据到条目视图的绑定。
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onEditClick(adapterPos)
            }
        }

        holder.deleteButton.setOnClickListener {
    // 属性： adapterPos
    // 说明：列表适配器实例，负责数据到条目视图的绑定。
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onDeleteClick(adapterPos)
            }
        }
    }

    // 函数： getItemCount
    // 说明：读取并返回当前数据或状态快照。
    override fun getItemCount(): Int = entries.size

    class SourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    // 属性： title
    // 说明：运行期状态变量，承载 title 相关上下文信息。
        val title: TextView = itemView.findViewById(R.id.tvTitle)
    // 属性： subtitle
    // 说明：运行期状态变量，承载 subtitle 相关上下文信息。
        val subtitle: TextView = itemView.findViewById(R.id.tvSubtitle)
    // 属性： editButton
    // 说明：运行期状态变量，承载 editButton 相关上下文信息。
        val editButton: ImageButton = itemView.findViewById(R.id.btnEdit)
    // 属性： deleteButton
    // 说明：运行期状态变量，承载 deleteButton 相关上下文信息。
        val deleteButton: ImageButton = itemView.findViewById(R.id.btnDelete)
    }
}