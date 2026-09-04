package com.meuapp.iptvplayer.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.meuapp.iptvplayer.databinding.ItemPosterHorizontalBinding
import com.meuapp.iptvplayer.util.WatchHistoryItem

class ContinueWatchingAdapter(
    private val onClick: (WatchHistoryItem) -> Unit
) : RecyclerView.Adapter<ContinueWatchingAdapter.ViewHolder>() {

    private val items = mutableListOf<WatchHistoryItem>()

    fun submitList(newItems: List<WatchHistoryItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPosterHorizontalBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvPosterTitle.text = item.title
        holder.binding.tvPosterSub.text = item.subtitle ?: when (item.kind) {
            "live" -> "Canal ao vivo"
            "vod" -> "Filme"
            else -> ""
        }
        holder.binding.ivPoster.load(item.posterUrl) { crossfade(true) }
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = items.size

    class ViewHolder(val binding: ItemPosterHorizontalBinding) : RecyclerView.ViewHolder(binding.root)
}
