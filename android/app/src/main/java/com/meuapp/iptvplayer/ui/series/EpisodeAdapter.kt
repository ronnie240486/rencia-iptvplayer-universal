package com.meuapp.iptvplayer.ui.series

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.meuapp.iptvplayer.data.model.SeriesEpisode
import com.meuapp.iptvplayer.databinding.ItemEpisodeBinding

class EpisodeAdapter(
    private val onClick: (SeriesEpisode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    private val items = mutableListOf<SeriesEpisode>()

    fun submitList(newItems: List<SeriesEpisode>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpisodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvEpisodeNumber.text = "E${item.episodeNumber ?: (position + 1)}"
        holder.binding.tvEpisodeTitle.text = item.title?.takeIf { it.isNotBlank() } ?: "Episódio ${position + 1}"
        val rating = item.info?.rating?.takeIf { it.isNotBlank() }
        holder.binding.tvEpisodeMeta.text = rating?.let { "★ $it" } ?: "Toque para reproduzir"
        holder.binding.ivEpisode.load(item.info?.image) {
            crossfade(true)
        }
        holder.binding.root.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemEpisodeBinding) : RecyclerView.ViewHolder(binding.root)
}
