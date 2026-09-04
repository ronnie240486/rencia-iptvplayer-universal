package com.meuapp.iptvplayer.ui.player

import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.databinding.ActivityPlayerBinding
import com.meuapp.iptvplayer.util.FavoriteItem
import com.meuapp.iptvplayer.util.FavoritesStore
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_STREAM_ID = "extra_stream_id"
        const val EXTRA_EPG_CHANNEL_ID = "extra_epg_channel_id"
        const val EXTRA_KIND = "extra_kind" // "live" | "vod" -- controla se favoritar aparece
        const val EXTRA_POSTER_URL = "extra_poster_url"
        // Outros links (qualidades/backup) do MESMO canal -- se o
        // principal falhar, tenta esses automaticamente antes de desistir.
        const val EXTRA_FAILOVER_URLS = "extra_failover_urls"
    }

    private lateinit var binding: ActivityPlayerBinding
    private val repository by lazy { XtreamRepository(this) }
    private var player: ExoPlayer? = null
    private var streamUrl: String = ""
    private var channelName: String = ""
    private var failoverUrls: List<String> = emptyList()
    private var failoverIndex = -1 // -1 = tentando o principal ainda
    private var isFavorite = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
        failoverUrls = intent.getStringArrayListExtra(EXTRA_FAILOVER_URLS).orEmpty()
            .filter { it.isNotBlank() && it != streamUrl }
        if (streamUrl.isBlank()) {
            finish()
            return
        }
        binding.tvChannelName.text = channelName
        binding.btnRetryPlayer.setOnClickListener {
            failoverIndex = -1
            initPlayer(streamUrl)
        }

        setupFavoriteButton()
        initPlayer(streamUrl)
        loadNowPlaying()
    }

    private fun setupFavoriteButton() {
        val kind = intent.getStringExtra(EXTRA_KIND)
        if (kind.isNullOrBlank()) {
            binding.btnFavorite.visibility = View.GONE
            return
        }
        isFavorite = FavoritesStore.isFavorite(this, kind, streamUrl)
        updateFavoriteIcon()
        binding.btnFavorite.setOnClickListener {
            val posterUrl = intent.getStringExtra(EXTRA_POSTER_URL)
            isFavorite = FavoritesStore.toggle(
                this,
                FavoriteItem(
                    kind = kind,
                    title = channelName,
                    posterUrl = posterUrl,
                    streamUrl = streamUrl,
                    addedAt = System.currentTimeMillis()
                )
            )
            updateFavoriteIcon()
            Toast.makeText(this, if (isFavorite) "Adicionado aos favoritos" else "Removido dos favoritos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateFavoriteIcon() {
        binding.btnFavorite.alpha = if (isFavorite) 1f else 0.5f
        binding.btnFavorite.setColorFilter(
            getColor(if (isFavorite) R.color.accent else android.R.color.white)
        )
    }

    /** Mostra o programa que está passando agora nesse canal, num rótulo
     * no canto da tela cheia -- igual a faixa de programação já mostrada
     * no mini player, mas resumida (só "agora"). */
    private fun loadNowPlaying() {
        val session = SessionStore.getSavedSession(this) ?: return
        val streamId = intent.getIntExtra(EXTRA_STREAM_ID, 0)
        val epgChannelId = intent.getStringExtra(EXTRA_EPG_CHANNEL_ID)
        lifecycleScope.launch {
            val title = if (streamId != 0) {
                repository.getShortEpg(session, streamId).getOrNull()
                    ?.listings?.firstOrNull()?.let { decodeTitle(it.titleValue()) }
            } else {
                repository.getEpgFromPlaylist(session, epgChannelId).getOrNull()
                    ?.firstOrNull()?.title
            }
            if (!title.isNullOrBlank()) {
                binding.tvNowPlaying.text = "Agora: $title"
                binding.tvNowPlaying.visibility = View.VISIBLE
            }
        }
    }

    private fun decodeTitle(value: String): String = runCatching {
        val decoded = String(Base64.decode(value.trim(), Base64.DEFAULT), Charsets.UTF_8).trim()
        decoded.ifBlank { value }
    }.getOrDefault(value)

    private fun initPlayer(url: String) {
        player?.release()
        binding.tvPlaybackError.visibility = View.GONE
        binding.btnRetryPlayer.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        exoPlayer.setAudioAttributes(
            AudioAttributes.Builder().setUsage(1).setContentType(3).build(),
            true
        )
        exoPlayer.setHandleAudioBecomingNoisy(true)
        binding.playerView.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvPlaybackError.visibility = View.GONE
                    }
                    Player.STATE_READY -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvPlaybackError.visibility = View.GONE
                        binding.btnRetryPlayer.visibility = View.GONE
                    }
                    Player.STATE_ENDED -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvPlaybackError.text = "Transmissão encerrada"
                        binding.tvPlaybackError.visibility = View.VISIBLE
                        binding.btnRetryPlayer.visibility = View.VISIBLE
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                tryNextFailoverOrShowError()
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /** Esse canal falhou -- se tiver outro link (outra qualidade/backup)
     * cadastrado pro mesmo canal, tenta ele sozinho, sem o usuário
     * precisar fazer nada. Só mostra o erro/botão de recarregar depois de
     * esgotar todas as opções conhecidas. */
    private fun tryNextFailoverOrShowError() {
        val nextIndex = failoverIndex + 1
        if (nextIndex < failoverUrls.size) {
            failoverIndex = nextIndex
            val nextUrl = failoverUrls[nextIndex]
            Toast.makeText(this, "Esse link falhou, tentando outra opção…", Toast.LENGTH_SHORT).show()
            initPlayer(nextUrl)
            return
        }
        binding.progressBar.visibility = View.GONE
        binding.tvPlaybackError.text = if (failoverUrls.isEmpty()) {
            "Não foi possível reproduzir este canal"
        } else {
            "Nenhuma das opções deste canal funcionou no momento"
        }
        binding.tvPlaybackError.visibility = View.VISIBLE
        binding.btnRetryPlayer.visibility = View.VISIBLE
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
