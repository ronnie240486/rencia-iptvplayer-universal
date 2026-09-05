package com.meuapp.iptvplayer.ui.player

import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private var usingSharedPlayer = false
    private var playerListener: Player.Listener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()

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
            usingSharedPlayer = false
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
                repository.getEpgFromPlaylist(session, epgChannelId, channelName).getOrNull()
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
        // Se é o mesmo canal AO VIVO que já está tocando no mini player,
        // reaproveita o MESMO player (SharedLivePlayer) -- não cria um
        // player novo, não reinicia/rebufferiza nada, só passa a exibir
        // aqui o que já estava tocando.
        val kind = intent.getStringExtra(EXTRA_KIND)
        if (kind == "live" && SharedLivePlayer.isPlayingUrl(url)) {
            usingSharedPlayer = true
            val shared = SharedLivePlayer.getOrCreate(this)
            player = shared
            binding.playerView.player = shared
            binding.progressBar.visibility = View.GONE
            binding.tvPlaybackError.visibility = View.GONE
            binding.btnRetryPlayer.visibility = View.GONE
            attachListener(shared)
            return
        }

        usingSharedPlayer = false
        player?.let { if (it !== SharedLivePlayer.getOrCreate(this)) it.release() }
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
        attachListener(exoPlayer)

        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun attachListener(target: ExoPlayer) {
        playerListener?.let { target.removeListener(it) }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvPlaybackError.visibility = View.GONE
                    }
                    Player.STATE_READY -> {
                        // Não esconde o "carregando" aqui ainda -- STATE_READY
                        // só quer dizer que o player está pronto pra tocar,
                        // mas o áudio costuma começar ANTES do primeiro
                        // quadro de vídeo aparecer de verdade. Esconde só
                        // quando o primeiro quadro realmente é desenhado
                        // (onRenderedFirstFrame), pra não mostrar uma tela
                        // preta com som tocando como se estivesse "pronto".
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

            override fun onRenderedFirstFrame() {
                binding.progressBar.visibility = View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                // Erro no player compartilhado (ao vivo) -- passa a usar
                // um player próprio pra essa troca de link, sem afetar o
                // mini player caso o usuário volte antes disso resolver.
                usingSharedPlayer = false
                tryNextFailoverOrShowError()
            }
        }
        playerListener = listener
        target.addListener(listener)
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
        val currentPlayer = player
        playerListener?.let { currentPlayer?.removeListener(it) }
        if (usingSharedPlayer) {
            // Não libera -- é o player compartilhado com o mini player.
            // IMPORTANTE: solta essa PlayerView da superfície de vídeo
            // antes de sair -- sem isso, o vídeo ficava "grudado" nessa
            // tela mesmo depois de fechada, e o mini player só recebia o
            // áudio de volta (ficava sem imagem nenhuma, só som).
            binding.playerView.player = null
            player = null
            return
        }
        currentPlayer?.release()
        player = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Tela cheia de verdade: some com a barra de status e a barra de
     * navegação do sistema (a "faixa" que ficava aparecendo do lado,
     * tomando espaço da imagem) -- sem isso, o vídeo não usava a tela
     * inteira de verdade. */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
