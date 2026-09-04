package com.meuapp.iptvplayer.ui.vod

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.meuapp.iptvplayer.data.model.VodStream
import com.meuapp.iptvplayer.databinding.ItemPosterBinding
import com.meuapp.iptvplayer.databinding.ItemPosterHorizontalBinding

class VodAdapter(
    private val onClick: (VodStream) -> Unit,
    private val onFocused: (VodStream) -> Unit = {},
    // true = card com largura fixa (fileira horizontal, ex: resultado de
    // busca) -- o card normal (largura "match_parent") só funciona numa
    // grade, numa fileira horizontal ele tenta ocupar a tela inteira.
    private val horizontal: Boolean = false,
) : RecyclerView.Adapter<VodAdapter.ViewHolder>() {

    private val items = mutableListOf<VodStream>()

    fun submitList(newItems: List<VodStream>) {
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
        holder.ivPoster.load(item.streamIcon) { crossfade(true) }
        holder.itemView.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) onFocused(item) }
        holder.itemView.setOnClickListener {
            onFocused(item)
            onClick(item)
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(
        view: View,
        val ivPoster: ImageView,
        val tvPosterTitle: TextView,
        val tvPosterSub: TextView
    ) : RecyclerView.ViewHolder(view)
}
