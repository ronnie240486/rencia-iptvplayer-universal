package com.meuapp.iptvplayer.ui.channels

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.databinding.ItemChannelBinding

private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LiveStream>() {
    override fun areItemsTheSame(oldItem: LiveStream, newItem: LiveStream): Boolean =
        (oldItem.directStreamUrl ?: oldItem.streamId.toString()) == (newItem.directStreamUrl ?: newItem.streamId.toString())

    override fun areContentsTheSame(oldItem: LiveStream, newItem: LiveStream): Boolean = oldItem == newItem
}

class ChannelAdapter(
    private val onClick: (LiveStream) -> Unit,
    private val onLongClick: (LiveStream) -> Unit
    // Usa ListAdapter (com DiffUtil) em vez de recarregar a lista inteira
    // a cada troca de categoria -- notifyDataSetChanged() forçava
    // reconstruir e redesenhar TODOS os itens visíveis de uma vez, na
    // tela principal, o que podia travar por um instante em categorias
    // com muitos canais. Agora só atualiza o que de fato mudou.
) : ListAdapter<LiveStream, ChannelAdapter.ViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        val holder = ViewHolder(binding)

        // Clique SEMPRE dispara na hora, sem esperar nada -- um
        // GestureDetector "de verdade" pra distinguir toque simples de
        // duplo precisa ESPERAR ~300-400ms antes de confirmar que foi um
        // toque simples (pra saber se não vem um segundo toque logo
        // depois), e isso deixava a seleção de canal lenta (parecia
        // precisar de vários cliques pra funcionar). Aqui não: todo toque
        // já seleciona na hora; se vier um segundo toque rápido (< 400ms),
        // ADICIONALMENTE abre a tela cheia -- sem atraso nenhum no caso
        // comum (um toque só).
        binding.root.setOnClickListener {
            val now = System.currentTimeMillis()
            val channel = holder.channel ?: return@setOnClickListener
            onClick(channel)
            if (now - holder.lastTapAt < 400) {
                onLongClick(channel)
            }
            holder.lastTapAt = now
        }
        // Mantém pressão longa como alternativa também (ex: quem já tinha
        // esse hábito, ou controles remotos que mandam long-press).
        binding.root.setOnLongClickListener {
            holder.channel?.let { onLongClick(it) }
            true
        }
        // Controle remoto de TV Box manda um EVENTO DE TECLA (OK/Enter),
        // não um toque na tela -- detecta duas confirmações seguidas
        // dentro de meio segundo como "clique duplo" também, do mesmo
        // jeito sem atraso no primeiro clique.
        binding.root.setOnKeyListener { _, keyCode, event ->
            val isConfirmKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
            if (!isConfirmKey || event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
            val now = System.currentTimeMillis()
            val channel = holder.channel ?: return@setOnKeyListener true
            onClick(channel)
            if (now - holder.lastKeyConfirmAt < 500) {
                onLongClick(channel)
            }
            holder.lastKeyConfirmAt = now
            true
        }
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = getItem(position)
        holder.channel = channel
        holder.binding.tvChannelName.text = channel.name
        // Sem "crossfade" aqui -- numa lista com muitos itens, cada
        // animação de logo aparecendo suavemente soma um pouquinho de
        // trabalho extra durante rolagem rápida (muitos itens reciclando
        // ao mesmo tempo), o que causava umas travadinhas passageiras ao
        // descer a lista. As logos são pequenas, não faz falta.
        holder.binding.ivIcon.load(channel.streamIcon)
    }

    class ViewHolder(val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        var channel: LiveStream? = null
        var lastTapAt = 0L
        var lastKeyConfirmAt = 0L
    }
}
