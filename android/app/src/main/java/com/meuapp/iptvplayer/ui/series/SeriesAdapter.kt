package com.meuapp.iptvplayer.ui.series

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.meuapp.iptvplayer.data.api.TmdbRepository
import com.meuapp.iptvplayer.data.model.SeriesItem
import com.meuapp.iptvplayer.databinding.ItemPosterBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SeriesAdapter(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onClick: (SeriesItem) -> Unit,
    private val onFocused: (SeriesItem) -> Unit = {},
) : RecyclerView.Adapter<SeriesAdapter.ViewHolder>() {

    private val items = mutableListOf<SeriesItem>()
    private val tmdbRepository = TmdbRepository()

    fun submitList(newItems: List<SeriesItem>) {
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
        holder.binding.tvPosterTitle.text = item.name
        holder.binding.tvPosterSub.text = item.rating?.let { "★ $it" } ?: ""
        // Mostra a capa que já veio do provedor primeiro (rápido, sem
        // esperar rede) -- muitas vezes é só uma logo genérica repetida em
        // todo episódio, então busca o pôster oficial no TMDB em seguida e
        // troca pra ele assim que chegar, se encontrar.
        holder.binding.ivPoster.load(item.cover) { crossfade(true) }
        holder.tmdbJob?.cancel()
        holder.tmdbJob = lifecycleScope.launch {
            val posterUrl = tmdbRepository.findSeriesPosterUrl(item.name)
            if (posterUrl != null && holder.bindingAdapterPosition == position) {
                holder.binding.ivPoster.load(posterUrl) { crossfade(true) }
            }
        }
        holder.binding.root.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused(item) }
        holder.binding.root.setOnClickListener {
            onFocused(item)
            onClick(item)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.tmdbJob?.cancel()
        holder.tmdbJob = null
        super.onViewRecycled(holder)
    }

    override fun getItemCount() = items.size

    class ViewHolder(val binding: ItemPosterBinding) : RecyclerView.ViewHolder(binding.root) {
        var tmdbJob: Job? = null
    }
}
