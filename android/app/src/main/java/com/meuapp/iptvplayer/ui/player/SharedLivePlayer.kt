package com.meuapp.iptvplayer.ui.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/** Um único ExoPlayer compartilhado entre o mini player (tela de Canais) e
 * a tela cheia -- assim, tocar em "tela cheia" só troca a tela que MOSTRA o
 * vídeo, sem criar um player novo nem reiniciar/rebufferizar o canal. Ao
 * voltar da tela cheia, o mini player pega esse mesmo player de volta,
 * continuando exatamente de onde estava (sem travar/reiniciar). */
object SharedLivePlayer {
    private var player: ExoPlayer? = null
    private var currentUrl: String? = null

    fun getOrCreate(context: Context): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(context.applicationContext).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(1).setContentType(3).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        player = created
        return created
    }

    /** Só troca o canal de verdade (para, limpa, prepara de novo) se for
     * uma URL DIFERENTE da que já está tocando -- reabrir a tela cheia do
     * MESMO canal que já estava no mini player não reinicia nada. */
    fun playUrl(context: Context, url: String) {
        if (currentUrl == url && player != null) return
        val p = getOrCreate(context)
        currentUrl = url
        p.stop()
        p.clearMediaItems()
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
    }

    fun currentUrl(): String? = currentUrl

    fun isPlayingUrl(url: String): Boolean = currentUrl == url && player != null

    /** Encerra de vez o player compartilhado -- só deve ser chamado quando
     * o usuário sai de Live TV de verdade (não ao só abrir/fechar a tela
     * cheia por cima). */
    fun release() {
        player?.release()
        player = null
        currentUrl = null
    }
}
