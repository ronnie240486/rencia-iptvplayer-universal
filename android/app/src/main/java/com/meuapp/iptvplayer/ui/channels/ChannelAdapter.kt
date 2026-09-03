package com.meuapp.iptvplayer.ui.channels

import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
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
        val holder = ViewHolder(binding)

        // GestureDetector nativo do Android -- mais confiável que contar
        // milissegundos na mão pra detectar clique duplo por TOQUE (que
        // tinha ficado sem efeito no celular). onDoubleTap abre tela
        // cheia, um toque só seleciona o canal no mini player. Lê o canal
        // ATUAL do holder (não uma variável "presa" de quando o listener
        // foi criado), pra funcionar certo mesmo com reciclagem de views
        // do RecyclerView.
        val gestureDetector = GestureDetector(parent.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                holder.channel?.let { onClick(it) }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                holder.channel?.let { onLongClick(it) }
                return true
            }
        })
        binding.root.setOnTouchListener { view, event ->
            gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) view.performClick()
            true
        }
        // Mantém pressão longa como alternativa também (ex: quem já tinha
        // esse hábito, ou controles remotos que mandam long-press).
        binding.root.setOnLongClickListener {
            holder.channel?.let { onLongClick(it) }
            true
        }
        // Controle remoto de TV Box manda um EVENTO DE TECLA (OK/Enter),
        // não um toque na tela -- o GestureDetector acima NUNCA recebe
        // esses eventos, então clique duplo com o controle não funcionava.
        // Detecta duas confirmações (OK/Enter/DPAD_CENTER) seguidas dentro
        // de meio segundo como "clique duplo" também.
        binding.root.setOnKeyListener { _, keyCode, event ->
            val isConfirmKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            if (!isConfirmKey || event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            val now = System.currentTimeMillis()
            if (now - holder.lastKeyConfirmAt < 500) {
                holder.lastKeyConfirmAt = 0L
                holder.channel?.let { onLongClick(it) }
            } else {
                holder.lastKeyConfirmAt = now
                holder.channel?.let { onClick(it) }
            }
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = items[position]
        holder.channel = channel
        holder.binding.tvChannelName.text = channel.name
        holder.binding.ivIcon.load(channel.streamIcon) {
            crossfade(true)
        }
    }

    override fun getItemCount() = items.size

    class ViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        var channel: LiveStream? = null
        var lastKeyConfirmAt = 0L
    }
}
