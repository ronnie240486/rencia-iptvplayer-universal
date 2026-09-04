package com.meuapp.iptvplayer.ui.channels

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.RenciaRepository
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.data.model.Category
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.databinding.ActivityChannelListBinding
import com.meuapp.iptvplayer.ui.common.CategorySidebarAdapter
import com.meuapp.iptvplayer.ui.epg.MiniGuideAdapter
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

/** Tela de Canais ao vivo -- sem abas: mini player + faixa de "agora/depois"
 * (EPG resumido) sempre visíveis, e a lista de canais logo abaixo. Trocar
 * de canal já atualiza a faixa de programação sozinho, sem precisar clicar
 * em nada. */
class ChannelListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_EPG = "extra_open_epg"
    }

    private lateinit var binding: ActivityChannelListBinding
    private val repository by lazy { XtreamRepository(this) }
    private val renciaRepository = RenciaRepository()
    private lateinit var sidebarAdapter: CategorySidebarAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var channelsLayoutManager: GridLayoutManager
    private lateinit var miniGuideAdapter: MiniGuideAdapter
    private var miniPlayer: ExoPlayer? = null
    private var selectedChannel: LiveStream? = null
    private var muted = false
    private var loadedCategories: List<Category> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = SessionStore.getSavedSession(this)
        if (session == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = getString(R.string.tile_live_tv)
        binding.toolbar.tvSubtitle.text = "Escolha uma categoria e selecione um canal"
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.setOnClickListener { startActivity(Intent(this, com.meuapp.iptvplayer.ui.search.SearchActivity::class.java)) }

        setupMiniPlayer()
        binding.btnRetryMiniPlayer.setOnClickListener { selectedChannel?.let { selectChannel(it) } }
        binding.btnMiniPlayerMute.setOnClickListener {
            muted = !muted
            miniPlayer?.volume = if (muted) 0f else 1f
            binding.btnMiniPlayerMute.text = if (muted) "MUDO" else "SOM"
        }

        miniGuideAdapter = MiniGuideAdapter()
        binding.rvMiniGuide.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvMiniGuide.adapter = miniGuideAdapter

        sidebarAdapter = CategorySidebarAdapter(
            barEnabled = AppearancePrefs.isCategoryBarEnabled(this),
            barColorHex = AppearancePrefs.getCategoryBarColor(this)
        ) { category ->
            com.meuapp.iptvplayer.util.AdultContentGuard.guardCategorySelection(this, category) {
                loadChannels(category.categoryId, category.categoryName)
            }
        }
        binding.rvSidebar.layoutManager = LinearLayoutManager(this)
        binding.rvSidebar.adapter = sidebarAdapter
        binding.rvSidebar.visibility = if (AppearancePrefs.isCategoryBarEnabled(this)) View.VISIBLE else View.GONE

        channelAdapter = ChannelAdapter(
            onClick = { channel -> selectChannel(channel) },
            onLongClick = { channel -> openFullPlayer(channel) }
        )
        binding.rvChannels.layoutManager = GridLayoutManager(this, 2).also { channelsLayoutManager = it }
        binding.rvChannels.adapter = channelAdapter

        binding.btnOpenFullPlayer.setOnClickListener {
            selectedChannel?.let { openFullPlayer(it) }
                ?: Toast.makeText(this, "Selecione um canal primeiro", Toast.LENGTH_SHORT).show()
        }

        loadCategories()
    }

    private fun setupMiniPlayer() {
        // Tocar no nome do canal já abre a tela cheia -- em controle
        // remoto de TV Box isso é mais direto do que exigir clique duplo
        // ou pressão longa. (Não faz isso no PlayerView em si, porque ele
        // já usa toque pra mostrar/esconder os próprios controles.)
        binding.tvSelectedChannel.setOnClickListener {
            selectedChannel?.let { openFullPlayer(it) }
        }

        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(1).setContentType(3).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        binding.miniPlayer.player = player
        binding.miniPlayer.useController = true
        binding.miniPlayer.controllerShowTimeoutMs = 4000
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> showMiniState("Carregando transmissão…", retryVisible = false)
                    Player.STATE_READY -> showMiniState("AO VIVO", retryVisible = false)
                    Player.STATE_ENDED -> showMiniState("Transmissão encerrada", retryVisible = true)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                showMiniState("Não foi possível reproduzir este canal", retryVisible = true)
            }
        })
        miniPlayer = player
    }

    private fun showMiniState(message: String, retryVisible: Boolean) {
        binding.tvMiniPlayerState.text = message
        binding.tvMiniPlayerState.visibility = View.VISIBLE
        binding.btnRetryMiniPlayer.visibility = if (retryVisible) View.VISIBLE else View.GONE
    }

    private fun loadCategories() {
        val session = SessionStore.getSavedSession(this) ?: return
        setLoading(true)
        lifecycleScope.launch {
            // Confere na hora se a playlist ligada a esse MAC mudou no
            // painel (ex: trocou de lista) -- sem isso, só descobria depois
            // de até 5 minutos parado na tela Home, e enquanto isso toda
            // categoria vinha vazia porque ainda apontava pro servidor
            // antigo.
            val activeSession = renciaRepository.refreshSessionIfChanged(session)
                .getOrNull()
                ?.also { SessionStore.saveSession(this@ChannelListActivity, it) }
                ?: session
            repository.getLiveCategories(activeSession)
                .onSuccess { categories ->
                    sidebarAdapter.submitList(com.meuapp.iptvplayer.util.AdultContentGuard.sortWithAdultLast(categories))
                    loadedCategories = categories
                    if (categories.isEmpty()) {
                        showError("O provedor respondeu, mas não retornou nenhuma categoria de canal.")
                    }
                }
                .onFailure { showError("Não foi possível carregar as categorias: ${it.message}") }
            setLoading(false)
        }
    }

    private fun loadChannels(categoryId: String, categoryName: String) {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.toolbar.tvSubtitle.text = "$categoryName · selecione para assistir"
        binding.tvChannelsHeader.text = "$categoryName · carregando canais…"
        setLoading(true)
        lifecycleScope.launch {
            repository.getLiveStreams(session, categoryId)
                .onSuccess { channels ->
                    if (channels.isEmpty()) {
                        // Categoria realmente vazia no provedor -- tira da
                        // lista lateral em vez de deixar ali "morta",
                        // confundindo quem for clicar de novo depois.
                        loadedCategories = loadedCategories.filterNot { it.categoryId == categoryId }
                        sidebarAdapter.submitList(loadedCategories, autoSelect = false)
                        displayChannels(channels, categoryName)
                        showError("\"$categoryName\" está vazia no provedor -- removida da lista.")
                    } else {
                        // Sempre a lista COMPLETA da categoria, sem dividir
                        // em subcategorias -- cada canal (com sua
                        // qualidade/link) é uma linha própria, igual o
                        // provedor cadastrou.
                        displayChannels(channels, categoryName)
                    }
                }
                .onFailure { showError("Não foi possível carregar os canais: ${it.message}") }
            setLoading(false)
        }
    }

    private fun displayChannels(channels: List<LiveStream>, categoryName: String) {
        // Sempre lista vertical (uma linha por canal), não grade.
        channelsLayoutManager.spanCount = 1
        channelAdapter.submitList(channels)
        binding.tvChannelsHeader.text = if (channels.isEmpty()) {
            "$categoryName · nenhum canal encontrado"
        } else {
            "$categoryName · ${channels.size} canais · toque para assistir · pressão longa para tela cheia"
        }
        channels.firstOrNull()?.let { selectChannel(it) }
    }

    private fun selectChannel(channel: LiveStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        selectedChannel = channel
        binding.tvSelectedChannel.text = channel.name
        binding.backdropView.setPoster(channel.streamIcon, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.btnRetryMiniPlayer.visibility = View.GONE
        showMiniState("Carregando ${channel.name}…", retryVisible = false)
        val streamUrl = channel.directStreamUrl ?: repository.buildLiveStreamUrl(session, channel.streamId)
        muted = false
        binding.btnMiniPlayerMute.text = "SOM"
        miniPlayer?.volume = 1f
        miniPlayer?.apply {
            stop()
            clearMediaItems()
            setMediaItem(MediaItem.fromUri(streamUrl))
            prepare()
            playWhenReady = true
        }
        loadMiniGuide(channel)
    }

    /** Faixa "agora / depois / depois..." sempre visível embaixo do mini
     * player -- atualiza sozinha assim que troca de canal, sem precisar
     * clicar em EPG nem em nada. Canais vindos da API Xtream usam
     * get_short_epg; canais vindos de uma playlist M3U (sem stream_id de
     * verdade) usam o guia XMLTV referenciado na própria playlist. */
    private fun loadMiniGuide(channel: LiveStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.rvMiniGuide.visibility = View.VISIBLE
        binding.tvMiniGuideEmpty.visibility = View.GONE
        lifecycleScope.launch {
            if (channel.directStreamUrl != null) {
                repository.getEpgFromPlaylist(session, channel.epgChannelId)
                    .onSuccess { programmes ->
                        val listings = programmes.map { p ->
                            com.meuapp.iptvplayer.data.model.EpgListing(
                                id = "",
                                titleBase64 = p.title,
                                descriptionBase64 = null,
                                start = null,
                                end = null,
                                startTimestamp = p.startMillis / 1000,
                                stopTimestamp = p.stopMillis / 1000
                            )
                        }
                        showMiniGuideResult(listings)
                    }
                    .onFailure { showMiniGuideResult(emptyList()) }
                return@launch
            }
            repository.getShortEpg(session, channel.streamId)
                .onSuccess { response -> showMiniGuideResult(response.listings.orEmpty()) }
                .onFailure { showMiniGuideResult(emptyList()) }
        }
    }

    private fun showMiniGuideResult(listings: List<com.meuapp.iptvplayer.data.model.EpgListing>) {
        miniGuideAdapter.submitList(listings)
        binding.rvMiniGuide.visibility = if (listings.isEmpty()) View.GONE else View.VISIBLE
        binding.tvMiniGuideEmpty.visibility = if (listings.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openFullPlayer(channel: LiveStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        val streamUrl = channel.directStreamUrl ?: repository.buildLiveStreamUrl(session, channel.streamId)
        com.meuapp.iptvplayer.util.WatchHistoryStore.record(
            this,
            com.meuapp.iptvplayer.util.WatchHistoryItem(
                kind = "live",
                title = channel.name,
                subtitle = null,
                posterUrl = channel.streamIcon,
                streamUrl = streamUrl,
                watchedAt = System.currentTimeMillis()
            )
        )
        lifecycleScope.launch {
            val failoverUrls = repository.getFailoverUrls(session, channel.categoryId, channel.name, streamUrl)
            startActivity(Intent(this@ChannelListActivity, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
                putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                putExtra(PlayerActivity.EXTRA_STREAM_ID, channel.streamId)
                putExtra(PlayerActivity.EXTRA_EPG_CHANNEL_ID, channel.epgChannelId)
                putExtra(PlayerActivity.EXTRA_KIND, "live")
                putExtra(PlayerActivity.EXTRA_POSTER_URL, channel.streamIcon)
                putStringArrayListExtra(PlayerActivity.EXTRA_FAILOVER_URLS, ArrayList(failoverUrls))
            })
        }
    }

    override fun onResume() {
        super.onResume()
        if (::sidebarAdapter.isInitialized) {
            val enabled = AppearancePrefs.isCategoryBarEnabled(this)
            binding.rvSidebar.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        binding.backdropView.setPoster(selectedChannel?.streamIcon, AppearancePrefs.isBackdropPosterEnabled(this))
        // Quando volta de outra tela (ex: abriu a tela cheia e voltou), o
        // mini player pode "congelar" -- a superfície de vídeo é perdida
        // enquanto a tela fica em segundo plano. Manda tocar de novo pra
        // ele se recuperar sozinho, sem precisar trocar de canal.
        miniPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        // Pausa o mini player quando sai da tela (ex: foi pra tela cheia)
        // -- sem isso, ele continua rodando em segundo plano gastando
        // rede/bateria à toa enquanto o mesmo canal já está tocando na
        // tela cheia por cima.
        miniPlayer?.pause()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        miniPlayer?.release()
        miniPlayer = null
        super.onDestroy()
    }
}
