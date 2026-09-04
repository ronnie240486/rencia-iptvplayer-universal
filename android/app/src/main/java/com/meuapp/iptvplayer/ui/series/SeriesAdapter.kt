package com.meuapp.iptvplayer.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.meuapp.iptvplayer.data.api.TmdbRepository
import com.meuapp.iptvplayer.data.model.SeriesItem
import com.meuapp.iptvplayer.databinding.ItemPosterBinding
import com.meuapp.iptvplayer.databinding.ItemPosterHorizontalBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SeriesAdapter(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val onClick: (SeriesItem) -> Unit,
    private val onFocused: (SeriesItem) -> Unit = {},
    // true = card com largura fixa (fileira horizontal, ex: resultado de
    // busca) -- o card normal (largura "match_parent") só funciona numa
    // grade, numa fileira horizontal ele tenta ocupar a tela inteira.
    private val horizontal: Boolean = false,
) : RecyclerView.Adapter<SeriesAdapter.ViewHolder>() {

    private val items = mutableListOf<SeriesItem>()
    private val tmdbRepository = TmdbRepository()

    fun submitList(newItems: List<SeriesItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (horizontal) {
            val binding = ItemPosterHorizontalBinding.inflate(inflater, parent, false)
            ViewHolder(binding.root, binding.ivPoster, binding.tvPosterTitle, binding.tvPosterSub)
        } else {
            val binding = ItemPosterBinding.inflate(inflater, parent, false)
            ViewHolder(binding.root, binding.ivPoster, binding.tvPosterTitle, binding.tvPosterSub)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvPosterTitle.text = item.name
        holder.tvPosterSub.text = item.rating?.let { "★ $it" } ?: ""
        // Mostra a capa que já veio do provedor primeiro (rápido, sem
        // esperar rede) -- muitas vezes é só uma logo genérica repetida em
        // todo episódio, então busca o pôster oficial no TMDB em seguida e
        // troca pra ele assim que chegar, se encontrar.
        holder.ivPoster.load(item.cover) { crossfade(true) }
        holder.tmdbJob?.cancel()
        holder.tmdbJob = lifecycleScope.launch {
            val posterUrl = tmdbRepository.findSeriesPosterUrl(item.name)
            if (posterUrl != null && holder.bindingAdapterPosition == position) {
                holder.ivPoster.load(posterUrl) { crossfade(true) }
            }
        }
        holder.itemView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused(item) }
        holder.itemView.setOnClickListener {
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

    class ViewHolder(
        view: View,
        val ivPoster: ImageView,
        val tvPosterTitle: TextView,
        val tvPosterSub: TextView
    ) : RecyclerView.ViewHolder(view) {
        var tmdbJob: Job? = null
    }
}
