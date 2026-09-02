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
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.data.model.Category
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.databinding.ActivityChannelListBinding
import com.meuapp.iptvplayer.ui.common.CategorySidebarAdapter
import com.meuapp.iptvplayer.ui.epg.EpgReminderActivity
import com.meuapp.iptvplayer.ui.epg.GuideAdapter
import com.meuapp.iptvplayer.ui.epg.GuideProgramRow
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.ReminderScheduler
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class ChannelListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_EPG = "extra_open_epg"
    }

    private lateinit var binding: ActivityChannelListBinding
    private val repository = XtreamRepository()
    private lateinit var sidebarAdapter: CategorySidebarAdapter
    private lateinit var subcategoryAdapter: CategorySidebarAdapter
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var guideAdapter: GuideAdapter
    private lateinit var miniGuideAdapter: MiniGuideAdapter
    private var miniPlayer: ExoPlayer? = null
    private var selectedChannel: LiveStream? = null
    private var epgMode = false
    private var muted = false
    private var loadedChannels: List<LiveStream> = emptyList()
    // Cada painel Xtream organiza os "canais" de um jeito diferente -- em
    // alguns, uma categoria já é granular o bastante; em outros, uma
    // categoria "ESPORTES" esconde 40 canais numerados dentro (ESPN 1, ESPN
    // 2, Premiere 1...). Quando isso acontece, agrupamos automaticamente
    // por nome/qualidade e criamos essa sub-navegação.
    private val subcategoryGroups = mutableMapOf<String, List<LiveStream>>()

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

        epgMode = intent.getBooleanExtra(EXTRA_OPEN_EPG, false)
        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = getString(if (epgMode) R.string.tile_epg else R.string.tile_live_tv)
        binding.toolbar.tvSubtitle.text = "Escolha uma categoria e selecione um canal"
        binding.toolbar.btnBack.setOnClickListener { finish() }

        setupMiniPlayer()
        setupTabs()

        guideAdapter = GuideAdapter(onReminderClick = ::toggleGuideReminder)
        binding.rvEpg.layoutManager = LinearLayoutManager(this)
        binding.rvEpg.adapter = guideAdapter

        miniGuideAdapter = MiniGuideAdapter()
        binding.rvMiniGuide.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvMiniGuide.adapter = miniGuideAdapter
        binding.tvEpgHeader.text = "EPG — programação dos canais"

        subcategoryAdapter = CategorySidebarAdapter(
            barEnabled = AppearancePrefs.isCategoryBarEnabled(this),
            barColorHex = AppearancePrefs.getCategoryBarColor(this)
        ) { category -> loadChannels(category.categoryId, category.categoryName) }
        binding.rvSubcategories.layoutManager = LinearLayoutManager(this)
        binding.rvSubcategories.adapter = subcategoryAdapter

        sidebarAdapter = CategorySidebarAdapter(
            barEnabled = AppearancePrefs.isCategoryBarEnabled(this),
            barColorHex = AppearancePrefs.getCategoryBarColor(this)
        ) { category -> loadChannels(category.categoryId, category.categoryName) }
        binding.rvSidebar.layoutManager = LinearLayoutManager(this)
        binding.rvSidebar.adapter = sidebarAdapter
        binding.rvSidebar.visibility = if (AppearancePrefs.isCategoryBarEnabled(this)) View.VISIBLE else View.GONE

        channelAdapter = ChannelAdapter(
            onClick = { channel -> selectChannel(channel) },
            onLongClick = { channel -> openFullPlayer(channel) }
        )
        binding.rvChannels.layoutManager = GridLayoutManager(this, 2)
        binding.rvChannels.adapter = channelAdapter

        binding.btnOpenFullPlayer.setOnClickListener {
            selectedChannel?.let { openFullPlayer(it) }
                ?: Toast.makeText(this, "Selecione um canal primeiro", Toast.LENGTH_SHORT).show()
        }

        switchContentTab(showEpg = epgMode)
        loadCategories()
    }

    private fun setupTabs() {
        binding.tabChannels.setOnClickListener { switchContentTab(showEpg = false) }
        binding.tabEpg.setOnClickListener { switchContentTab(showEpg = true) }
        binding.btnRetryMiniPlayer.setOnClickListener { selectedChannel?.let { selectChannel(it) } }
        binding.btnMiniPlayerMute.setOnClickListener {
            muted = !muted
            miniPlayer?.volume = if (muted) 0f else 1f
            binding.btnMiniPlayerMute.text = if (muted) "MUDO" else "SOM"
        }
    }

    private fun switchContentTab(showEpg: Boolean) {
        epgMode = showEpg
        binding.miniPlayerPanel.visibility = if (showEpg) View.GONE else View.VISIBLE
        binding.rvMiniGuide.visibility = if (showEpg || miniGuideAdapter.itemCount == 0) View.GONE else View.VISIBLE
        if (showEpg) {
            miniPlayer?.stop()
            binding.tvSelectedChannel.text = "EPG — programação"
        }
        binding.rvSidebar.visibility = if (!showEpg && AppearancePrefs.isCategoryBarEnabled(this)) View.VISIBLE else View.GONE
        binding.rvSubcategories.visibility = View.GONE
        binding.channelsPanel.visibility = if (showEpg) View.GONE else View.VISIBLE
        binding.epgPanel.visibility = if (showEpg) View.VISIBLE else View.GONE
        if (showEpg) loadGuide()
        binding.tabChannels.setBackgroundResource(if (showEpg) 0 else R.drawable.bg_tile_highlight)
        binding.tabEpg.setBackgroundResource(if (showEpg) R.drawable.bg_tile_highlight else 0)
        binding.tabChannels.setTextColor(getColor(if (showEpg) R.color.text_secondary else R.color.white))
        binding.tabEpg.setTextColor(getColor(if (showEpg) R.color.white else R.color.text_secondary))
    }

    private fun setupMiniPlayer() {
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
            repository.getLiveCategories(session)
                .onSuccess { categories ->
                    sidebarAdapter.submitList(categories)
                    if (categories.isEmpty()) {
                        showError("O provedor respondeu, mas não retornou nenhuma categoria de canal.")
                    }
                }
                .onFailure { showError("Não foi possível carregar as categorias: ${it.message}") }
            setLoading(false)
        }
    }

    private fun loadChannels(categoryId: String, categoryName: String) {
        // Sub-categoria já resolvida localmente (agrupada a partir de uma
        // categoria maior) -- não precisa ir à rede de novo.
        subcategoryGroups[categoryId]?.let { channels ->
            binding.rvSubcategories.visibility = View.VISIBLE
            displayChannels(channels, categoryName)
            return
        }
        val session = SessionStore.getSavedSession(this) ?: return
        binding.toolbar.tvSubtitle.text = "$categoryName · selecione para assistir"
        binding.tvChannelsHeader.text = "$categoryName · carregando canais…"
        setLoading(true)
        lifecycleScope.launch {
            repository.getLiveStreams(session, categoryId)
                .onSuccess { channels ->
                    if (channels.size > 30) {
                        showSubcategories(categoryName, channels)
                    } else {
                        binding.rvSubcategories.visibility = View.GONE
                        displayChannels(channels, categoryName)
                    }
                }
                .onFailure { showError("Não foi possível carregar os canais: ${it.message}") }
            setLoading(false)
        }
    }

    /** Categoria grande demais (ex: "ESPORTES" com 40 canais numerados) --
     * agrupa em sub-categorias por nome, pra não jogar tudo numa lista só. */
    private fun showSubcategories(parentName: String, channels: List<LiveStream>) {
        val sorted = channels.sortedWith(compareBy<LiveStream> { it.num }.thenBy { it.name.lowercase() })
        subcategoryGroups.clear()
        val parentLower = parentName.lowercase()
        val categories = sorted.mapIndexed { index, channel ->
            val label = channel.name.trim().ifBlank { "$parentName ${channel.num}" }
            val key = "channel:$parentLower:$index:${channel.streamId}"
            subcategoryGroups[key] = listOf(channel)
            Category(key, label)
        }.ifEmpty {
            val key = "channel:$parentLower:empty"
            subcategoryGroups[key] = emptyList()
            listOf(Category(key, "$parentName — nenhum canal"))
        }
        // Não dispara seleção automática aqui -- já mostramos TODOS os
        // canais da categoria pai abaixo; selecionar a 1ª sub-categoria
        // sozinha reduziria a lista pra só 1 canal sem o usuário pedir.
        subcategoryAdapter.submitList(categories, autoSelect = false)
        binding.rvSubcategories.visibility = View.VISIBLE
        displayChannels(sorted, parentName)
    }

    private fun displayChannels(channels: List<LiveStream>, categoryName: String) {
        loadedChannels = channels
        channelAdapter.submitList(channels)
        binding.tvChannelsHeader.text = if (channels.isEmpty()) {
            "$categoryName · nenhum canal encontrado"
        } else {
            "$categoryName · ${channels.size} canais · toque para mini player · pressão longa para tela cheia"
        }
        channels.firstOrNull()?.let { selectChannel(it) }
    }

    private fun selectChannel(channel: LiveStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        selectedChannel = channel
        binding.tvSelectedChannel.text = channel.name
        binding.tvEpgHeader.text = "EPG — ${channel.name}"
        binding.backdropView.setPoster(channel.streamIcon, AppearancePrefs.isBackdropPosterEnabled(this))
        if (epgMode) {
            loadGuide()
            return
        }
        binding.btnRetryMiniPlayer.visibility = View.GONE
        showMiniState("Carregando ${channel.name}…", retryVisible = false)
        val streamUrl = repository.buildLiveStreamUrl(session, channel.streamId)
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

    /** Faixa compacta com os próximos programas do canal que está tocando
     * no mini player agora -- só o suficiente pra dar uma prévia (5-6
     * programas), sem precisar abrir a aba EPG completa. */
    private fun loadMiniGuide(channel: LiveStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        lifecycleScope.launch {
            repository.getShortEpg(session, channel.streamId)
                .onSuccess { response ->
                    val listings = response.listings.orEmpty()
                    miniGuideAdapter.submitList(listings)
                    binding.rvMiniGuide.visibility = if (listings.isEmpty()) View.GONE else View.VISIBLE
                }
                .onFailure {
                    miniGuideAdapter.submitList(emptyList())
                    binding.rvMiniGuide.visibility = View.GONE
                }
        }
    }

    /** Guia de programação de VÁRIOS canais ao mesmo tempo (aba EPG) --
     * limitado aos primeiros 15 canais carregados pra não sobrecarregar de
     * requisições o painel de uma vez só. */
    private fun loadGuide() {
        val session = SessionStore.getSavedSession(this) ?: return
        val channels = loadedChannels.take(15)
        if (channels.isEmpty()) {
            guideAdapter.submitList(emptyList())
            binding.tvEpgHeader.text = "EPG — nenhum canal carregado"
            return
        }
        setLoading(true)
        lifecycleScope.launch {
            val rows = mutableListOf<GuideProgramRow>()
            channels.forEach { channel ->
                repository.getShortEpg(session, channel.streamId).onSuccess { response ->
                    response.listings?.forEach { listing -> rows.add(GuideProgramRow(channel, listing)) }
                }
            }
            guideAdapter.submitList(rows)
            binding.tvEpgHeader.text = "EPG — programação dos canais"
            setLoading(false)
        }
    }

    private fun toggleGuideReminder(row: GuideProgramRow, alreadyScheduled: Boolean) {
        val session = SessionStore.getSavedSession(this) ?: return
        val streamUrl = repository.buildLiveStreamUrl(session, row.channel.streamId)
        if (alreadyScheduled) {
            ReminderScheduler.cancel(this, row.channel.streamId, row.listing)
            Toast.makeText(this, "Lembrete cancelado", Toast.LENGTH_SHORT).show()
        } else {
            val scheduled = ReminderScheduler.schedule(this, row.channel.streamId, row.channel.name, streamUrl, row.listing)
            Toast.makeText(
                this,
                if (scheduled) "Lembrete salvo para ${EpgReminderActivity.decodeTitle(row.listing.titleValue())}" else "Horário do programa inválido",
                Toast.LENGTH_LONG
            ).show()
        }
        guideAdapter.notifyDataSetChanged()
    }

    private fun openFullPlayer(channel: LiveStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, repository.buildLiveStreamUrl(session, channel.streamId))
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
        })
    }

    override fun onResume() {
        super.onResume()
        if (::sidebarAdapter.isInitialized) {
            val enabled = AppearancePrefs.isCategoryBarEnabled(this)
            binding.rvSidebar.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        binding.backdropView.setPoster(selectedChannel?.streamIcon, AppearancePrefs.isBackdropPosterEnabled(this))
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
