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
    // Clique duplo (dois toques/OK rápidos seguidos) também abre tela
    // cheia -- em TV Box com controle remoto, "pressão longa" nem sempre
    // funciona bem, então isso serve de alternativa mais confiável.
    private var lastClickPosition = -1
    private var lastClickAt = 0L

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
        holder.binding.root.setOnClickListener {
            val now = System.currentTimeMillis()
            if (lastClickPosition == position && now - lastClickAt < 400) {
                lastClickPosition = -1
                onLongClick(channel)
            } else {
                lastClickPosition = position
                lastClickAt = now
                onClick(channel)
            }
        }
        holder.binding.root.setOnLongClickListener {
            onLongClick(channel)
            true
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root)
}
