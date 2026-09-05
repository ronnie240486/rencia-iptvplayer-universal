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
import com.meuapp.iptvplayer.ui.player.SharedLivePlayer
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
    private var miniPlayerListener: Player.Listener? = null
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
        // Guarda mais views recicladas prontas durante rolagem rápida --
        // sem isso, rolar rápido pra baixo numa categoria grande recriava
        // views demais de uma vez, causando umas travadinhas passageiras.
        binding.rvChannels.setItemViewCacheSize(24)
        binding.rvChannels.setHasFixedSize(true)

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

        // Player COMPARTILHADO com a tela cheia -- assim, abrir/fechar a
        // tela cheia do mesmo canal não reinicia/rebufferiza nada, só
        // troca qual tela está mostrando o vídeo.
        val player = SharedLivePlayer.getOrCreate(this)
        binding.miniPlayer.player = player
        binding.miniPlayer.useController = true
        binding.miniPlayer.controllerShowTimeoutMs = 4000
        // Guarda o listener numa variável -- SEM isso, toda vez que essa
        // tela é recriada (voltar da Home, app recriado pelo sistema...) um
        // listener NOVO era adicionado no player COMPARTILHADO, sem nunca
        // remover os antigos. Isso ia se acumulando (um listener a mais a
        // cada vez que a tela reabre), cada um segurando uma referência
        // pra uma tela já destruída -- com uso prolongado, isso enche de
        // trabalho inútil toda troca de estado do vídeo e contribuía pros
        // travamentos aparecerem em vários lugares diferentes.
        miniPlayerListener?.let { player.removeListener(it) }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> showMiniState("Carregando transmissão…", retryVisible = false)
                    // Não mostra "AO VIVO" aqui ainda -- STATE_READY só
                    // quer dizer que o player está pronto, mas o ÁUDIO
                    // geralmente começa ANTES do primeiro quadro de vídeo
                    // aparecer de verdade. Só mostra "AO VIVO" quando o
                    // primeiro quadro é desenhado (onRenderedFirstFrame),
                    // pra não dar a impressão de "som tocando com tela
                    // preta" como se já estivesse tudo pronto.
                    Player.STATE_ENDED -> showMiniState("Transmissão encerrada", retryVisible = true)
                }
            }

            override fun onRenderedFirstFrame() {
                showMiniState("AO VIVO", retryVisible = false)
            }

            override fun onPlayerError(error: PlaybackException) {
                showMiniState("Não foi possível reproduzir este canal", retryVisible = true)
            }
        }
        miniPlayerListener = listener
        player.addListener(listener)
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
        // Carrega as categorias JÁ, com a sessão atual -- não espera a
        // checagem de "a lista mudou no painel?" terminar primeiro. Essa
        // checagem faz uma chamada de rede separada que pode demorar até
        // 20s se a rede estiver lenta, e isso estava travando a tela
        // inteira (spinner infinito) até ela terminar, mesmo quando a
        // sessão atual já era perfeitamente válida pra carregar os canais.
        lifecycleScope.launch {
            repository.getLiveCategories(session)
                .onSuccess { categories ->
                    sidebarAdapter.submitList(com.meuapp.iptvplayer.util.AdultContentGuard.sortWithAdultLast(categories))
                    loadedCategories = categories
                    if (categories.isEmpty()) {
                        showError("O provedor respondeu, mas não retornou nenhuma categoria de canal.")
                    }
                }
                .onFailure { showErrorUnlessCancelled("Não foi possível carregar as categorias", it) }
            setLoading(false)
        }
        // Confere se a playlist mudou no painel (ex: trocou de lista) EM
        // PARALELO, numa corrotina separada -- só recarrega a tela se de
        // fato mudar algo, sem segurar o carregamento inicial esperando
        // essa checagem terminar.
        lifecycleScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(6000) { renciaRepository.refreshSessionIfChanged(session).getOrNull() }?.let { updated ->
                SessionStore.saveSession(this@ChannelListActivity, updated)
                loadCategories()
            }
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
                .onFailure { showErrorUnlessCancelled("Não foi possível carregar os canais", it) }
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
        val streamUrl = channel.directStreamUrl ?: repository.buildLiveStreamUrl(session, channel.streamId)
        // Se já é esse mesmo canal tocando (ex: voltou da tela cheia),
        // playUrl não faz nada -- continua exatamente de onde estava, sem
        // reiniciar. Só troca de verdade se for um canal diferente.
        if (!SharedLivePlayer.isPlayingUrl(streamUrl)) {
            showMiniState("Carregando ${channel.name}…", retryVisible = false)
            muted = false
            binding.btnMiniPlayerMute.text = "SOM"
            SharedLivePlayer.playUrl(this, streamUrl)
            miniPlayer?.volume = 1f
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
        binding.tvMiniGuideEmpty.text = "Esta lista não fornece programação (EPG) para este canal"
        lifecycleScope.launch {
            if (channel.directStreamUrl != null) {
                // Limite de tempo pra essa busca nunca ficar "pendurada"
                // sem mostrar nada (nem a faixa, nem o aviso) -- se
                // demorar demais (rede lenta tentando as 3 fontes de
                // guia), desiste e mostra o aviso genérico em vez de
                // deixar a área de programação em branco pra sempre.
                val result = kotlinx.coroutines.withTimeoutOrNull(15_000) {
                    repository.getEpgFromPlaylist(session, channel.epgChannelId, channel.name)
                }
                if (result == null) {
                    binding.tvMiniGuideEmpty.text = "Sem programação: a busca demorou demais e foi cancelada"
                    showMiniGuideResult(emptyList())
                    return@launch
                }
                result
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
                        if (listings.isEmpty()) diagnoseEpgEmpty(session, channel) else showMiniGuideResult(listings)
                    }
                    .onFailure { diagnoseEpgEmpty(session, channel) }
                return@launch
            }
            repository.getShortEpg(session, channel.streamId)
                .onSuccess { response -> showMiniGuideResult(response.listings.orEmpty()) }
                .onFailure { showMiniGuideResult(emptyList()) }
        }
    }

    /** Descobre e mostra EXATAMENTE por que não tem programação pra esse
     * canal, em vez de só "não disponível" -- ajuda a saber se é porque a
     * lista não declara guia nenhum, ou se declara mas esse canal
     * específico não bate com nenhum ID do guia. */
    private suspend fun diagnoseEpgEmpty(session: com.meuapp.iptvplayer.data.api.Session, channel: LiveStream) {
        val diag = repository.diagnoseEpg(session, channel.epgChannelId, channel.name)
        val searchInfo = "(procurando por tvg-id=\"${diag.searchedTvgId ?: "nenhum"}\" ou nome=\"${diag.searchedNormalizedName ?: "nenhum"}\")"
        val reason = when {
            !diag.hasTvgId -> "este canal não tem um tvg-id na playlist (a lista não diz qual é o ID de programação dele)"
            !diag.hasEpgUrlDeclared -> "nenhuma das 3 fontes de guia (playlist, xmltv.php do painel, guia universal) tem dado nenhum pra este canal $searchInfo"
            diag.guideChannelCount == 0 -> "o guia declarado na lista não retornou nenhum canal (pode estar fora do ar ou vazio)"
            !diag.hasMatchForThisChannel -> "o guia tem ${diag.guideChannelCount} canais, mas nenhum bate com este $searchInfo"
            else -> "sem programação futura cadastrada pra este canal agora"
        }
        binding.tvMiniGuideEmpty.text = "Sem programação: $reason"
        showMiniGuideResult(emptyList())
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
        // Reconecta o player compartilhado ao mini player (pode ter sido
        // "emprestado" pra tela cheia enquanto essa tela ficou em segundo
        // plano) -- sem travar/reiniciar nada, é o mesmo player, só volta
        // a aparecer aqui.
        miniPlayer?.let { binding.miniPlayer.player = it }
        miniPlayer?.play()
    }

    override fun onPause() {
        super.onPause()
        // Não pausa mais o player aqui -- agora ele é COMPARTILHADO com a
        // tela cheia (SharedLivePlayer). Se pausasse ao sair pra tela
        // cheia, o vídeo pararia bem na hora da troca -- o objetivo é
        // exatamente o contrário: continuar tocando sem interrupção.
        // MAS solta essa PlayerView da superfície de vídeo (sem pausar o
        // player em si) -- se as duas telas (mini player e tela cheia)
        // ficassem "grudadas" no mesmo player ao mesmo tempo, só uma
        // conseguia mostrar a imagem de verdade; a outra ficava só com
        // áudio, sem vídeo nenhum.
        binding.miniPlayer.player = null
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Sair da tela ANTES de uma busca terminar cancela ela sozinho (é o
     * Android fazendo isso, não um erro de verdade) -- sem esse filtro, o
     * usuário via um aviso assustador ("Job was cancelled") só por ter
     * saído da tela rápido demais, às vezes até aparecendo bem depois, na
     * tela seguinte. */
    private fun showErrorUnlessCancelled(prefix: String, error: Throwable) {
        if (error is kotlinx.coroutines.CancellationException) return
        showError("$prefix: ${error.message}")
    }

    override fun onDestroy() {
        // SEMPRE remove o listener antes de destruir essa tela -- mesmo
        // quando não é pra encerrar o player de vez (ex: o sistema recriou
        // essa Activity), sem isso o listener antigo (com referência pra
        // essa tela já destruída) ficava acumulando pra sempre no player
        // compartilhado, um a mais a cada vez que essa tela reabria.
        miniPlayerListener?.let { miniPlayer?.removeListener(it) }
        // Só encerra o player de verdade quando está saindo de Live TV
        // (voltou pra Home) -- não ao só abrir/fechar a tela cheia por
        // cima (isso não passa por onDestroy, só onPause/onResume).
        if (isFinishing) {
            SharedLivePlayer.release()
        }
        miniPlayer = null
        miniPlayerListener = null
        super.onDestroy()
    }
}
