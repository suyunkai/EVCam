package com.kooo.evcam.v2.ui.playback

import android.view.LayoutInflater
import android.view.ViewGroup
import android.graphics.Bitmap
import androidx.recyclerview.widget.RecyclerView
import com.kooo.evcam.databinding.ItemV2VideoBinding

class V2VideoPlaybackAdapter(
    private val onItemClick: (V2VideoGroup) -> Unit
) : RecyclerView.Adapter<V2VideoPlaybackAdapter.VH>() {
    private val groups = mutableListOf<V2VideoGroup>()

    class VH(val binding: ItemV2VideoBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemV2VideoBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val group = groups[position]
        holder.binding.videoDate.text = group.displayYear
        holder.binding.videoTime.text = group.displayDate
        holder.binding.videoSize.text = group.displayTime
        if (group.thumbnail != null) {
            holder.binding.videoThumbnail.setImageBitmap(group.thumbnail)
            holder.binding.videoThumbnail.alpha = 1f
        } else {
            holder.binding.videoThumbnail.setImageDrawable(null)
            holder.binding.videoThumbnail.alpha = 0.35f
        }
        holder.binding.root.setOnClickListener { onItemClick(group) }
    }
    override fun getItemCount() = groups.size

    fun clear() {
        val oldSize = groups.size
        groups.clear()
        if (oldSize > 0) notifyItemRangeRemoved(0, oldSize)
    }

    fun addOrUpdate(group: V2VideoGroup): Int {
        val existing = groups.indexOfFirst { it.timestamp == group.timestamp }
        if (existing >= 0) {
            groups[existing] = group
            notifyItemChanged(existing)
            return existing
        }
        val insertAt = groups.indexOfFirst { it.timestamp < group.timestamp }.let { if (it < 0) groups.size else it }
        groups.add(insertAt, group)
        notifyItemInserted(insertAt)
        return insertAt
    }

    fun updateThumbnail(timestamp: String, thumbnail: Bitmap) {
        val index = groups.indexOfFirst { it.timestamp == timestamp }
        if (index < 0) return
        groups[index] = groups[index].copy(thumbnail = thumbnail)
        notifyItemChanged(index)
    }

    fun firstOrNull(): V2VideoGroup? = groups.firstOrNull()
}
