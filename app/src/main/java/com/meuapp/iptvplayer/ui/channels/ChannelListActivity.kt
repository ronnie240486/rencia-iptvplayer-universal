package com.meuapp.iptvplayer.ui.channels

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.data.model.EpgListing
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.databinding.ActivityChannelListBinding
import com.meuapp.iptvplayer.ui.common.CategorySidebarAdapter
import com.meuapp.iptvplayer.ui.epg.EpgAdapter
import com.meuapp.iptvplayer.ui.epg.EpgReminderActivity
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
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var epgAdapter: EpgAdapter
    private var miniPlayer: ExoPlayer? = null
    private var selectedChannel: LiveStream? = null
    private var epgMode = false

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
        binding.toolbar.tvSubtitle.text = "Selecione um canal para assistir e ver o EPG"
        binding.toolbar.btnBack.setOnClickListener { finish() }

        miniPlayer = ExoPlayer.Builder(this).build().also { player ->
            binding.miniPlayer.player = player
            binding.miniPlayer.useController = true
        }

        epgAdapter = EpgAdapter(onReminderClick = ::toggleReminder)
        binding.rvEpg.layoutManager = LinearLayoutManager(this)
        binding.rvEpg.adapter = epgAdapter
        binding.tvEpgHeader.text = "EPG — selecione um canal"

        sidebarAdapter = CategorySidebarAdapter(
            barEnabled = AppearancePrefs.isCategoryBarEnabled(this),
            barColorHex = AppearancePrefs.getCategoryBarColor(this)
        ) { category -> loadChannels(category.categoryId, category.categoryName) }
        binding.rvSidebar.layoutManager = LinearLayoutManager(this)
        binding.rvSidebar.adapter = sidebarAdapter

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

        loadCategories()
    }

    private fun loadCategories() {
        val session = SessionStore.getSavedSession(this) ?: return
        setLoading(true)
        lifecycleScope.launch {
            repository.getLiveCategories(session)
                .onSuccess { categories ->
                    sidebarAdapter.submitList(categories)
                    categories.firstOrNull()?.let { loadChannels(it.categoryId, it.categoryName) }
                }
                .onFailure { showError("Não foi possível carregar as categorias") }
            setLoading(false)
        }
    }

    private fun loadChannels(categoryId: String, categoryName: String) {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.toolbar.tvSubtitle.text = "$categoryName · selecione para abrir no mini player"
        setLoading(true)
        lifecycleScope.launch {
            repository.getLiveStreams(session, categoryId)
                .onSuccess { channels ->
                    channelAdapter.submitList(channels)
                    binding.bindingHeaders(categoryName, channels.size)
                    channels.firstOrNull()?.let { selectChannel(it) }
                }
                .onFailure { showError("Não foi possível carregar os canais") }
            setLoading(false)
        }
    }

    private fun selectChannel(channel: LiveStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        selectedChannel = channel
        binding.tvSelectedChannel.text = channel.name
        binding.tvEpgHeader.text = "EPG — ${channel.name}"
        val streamUrl = repository.buildLiveStreamUrl(session, channel.streamId)
        miniPlayer?.setMediaItem(MediaItem.fromUri(streamUrl))
        miniPlayer?.prepare()
        miniPlayer?.playWhenReady = true
        loadEpg(channel.streamId)
    }

    private fun loadEpg(streamId: Int) {
        val session = SessionStore.getSavedSession(this) ?: return
        epgAdapter.setStreamId(streamId)
        setLoading(true)
        lifecycleScope.launch {
            repository.getShortEpg(session, streamId)
                .onSuccess { response -> epgAdapter.submitList(response.listings ?: emptyList()) }
                .onFailure { epgAdapter.submitList(emptyList()) }
            setLoading(false)
        }
    }

    private fun toggleReminder(listing: EpgListing, alreadyScheduled: Boolean) {
        val channel = selectedChannel ?: return
        val session = SessionStore.getSavedSession(this) ?: return
        val streamUrl = repository.buildLiveStreamUrl(session, channel.streamId)
        if (alreadyScheduled) {
            ReminderScheduler.cancel(this, channel.streamId, listing)
            Toast.makeText(this, "Lembrete cancelado", Toast.LENGTH_SHORT).show()
        } else {
            val scheduled = ReminderScheduler.schedule(
                this,
                channel.streamId,
                channel.name,
                streamUrl,
                listing
            )
            Toast.makeText(
                this,
                if (scheduled) "Lembrete ativado para ${EpgReminderActivity.decodeTitle(listing.titleBase64)}" else "Horário do programa inválido",
                Toast.LENGTH_LONG
            ).show()
        }
        epgAdapter.notifyDataSetChanged()
    }

    private fun openFullPlayer(channel: LiveStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, repository.buildLiveStreamUrl(session, channel.streamId))
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
        })
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun ActivityChannelListBinding.bindingHeaders(categoryName: String, count: Int) {
        tvChannelsHeader.text = "$categoryName · $count canais · toque para mini player · pressão longa para tela cheia"
    }

    override fun onDestroy() {
        miniPlayer?.release()
        miniPlayer = null
        super.onDestroy()
    }
}
