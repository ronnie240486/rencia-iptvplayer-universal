package com.meuapp.iptvplayer.ui.settings

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.meuapp.iptvplayer.databinding.ItemPosterBinding
import com.meuapp.iptvplayer.util.FavoriteItem

class FavoritesAdapter(
    private val onClick: (FavoriteItem) -> Unit,
    private val onLongClick: (FavoriteItem) -> Unit
) : RecyclerView.Adapter<FavoritesAdapter.ViewHolder>() {

    private val items = mutableListOf<FavoriteItem>()

    fun submitList(newItems: List<FavoriteItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvPosterTitle.text = item.title
        holder.binding.tvPosterSub.text = when (item.kind) {
            "live" -> "Canal"
            "vod" -> "Filme"
            else -> "Série"
        }
        holder.binding.ivPoster.load(item.posterUrl ?: item.seriesCover) { crossfade(true) }
        holder.binding.root.setOnClickListener { onClick(item) }
        holder.binding.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(val binding: ItemPosterBinding) : RecyclerView.ViewHolder(binding.root)
}
