package com.meuapp.iptvplayer.ui.channels

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.databinding.ItemChannelBinding

class ChannelAdapter(
    private val onClick: (LiveStream) -> Unit,
    private val onLongClick: (LiveStream) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    private val items = mutableListOf<LiveStream>()

    fun submitList(newItems: List<LiveStream>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = items[position]
        holder.binding.tvChannelName.text = channel.name
        holder.binding.ivIcon.load(channel.streamIcon) {
            crossfade(true)
        }
        holder.binding.root.setOnClickListener { onClick(channel) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(channel)
            true
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)
}
