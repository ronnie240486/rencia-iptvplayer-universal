package com.meuapp.iptvplayer.ui.search

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.data.model.SeriesItem
import com.meuapp.iptvplayer.data.model.VodStream
import com.meuapp.iptvplayer.databinding.ActivitySearchBinding
import com.meuapp.iptvplayer.ui.channels.ChannelAdapter
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.ui.series.SeriesAdapter
import com.meuapp.iptvplayer.ui.series.SeriesDetailActivity
import com.meuapp.iptvplayer.ui.vod.VodAdapter
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

/** Busca única: procura ao mesmo tempo em canais, filmes e séries, usando
 * a playlist M3U já em cache (rápida, sem baixar nada de novo). */
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val repository by lazy { XtreamRepository(this) }
    private lateinit var liveAdapter: ChannelAdapter
    private lateinit var vodAdapter: VodAdapter
    private lateinit var seriesAdapter: SeriesAdapter
    private val debounceHandler = Handler(Looper.getMainLooper())
    private var pendingSearch: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = SessionStore.getSavedSession(this)
        if (session == null) {
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }

        liveAdapter = ChannelAdapter(
            onClick = { channel -> openLive(channel) },
            onLongClick = { channel -> openLive(channel) }
        )
        binding.rvLive.layoutManager = LinearLayoutManager(this)
        binding.rvLive.adapter = liveAdapter

        vodAdapter = VodAdapter(onClick = { movie -> openVod(movie) })
        binding.rvVod.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvVod.adapter = vodAdapter

        seriesAdapter = SeriesAdapter(
            lifecycleScope = lifecycleScope,
            onClick = { series -> openSeries(series) }
        )
        binding.rvSeries.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvSeries.adapter = seriesAdapter

        binding.etQuery.addTextChangedListener(afterTextChanged = { text ->
            pendingSearch?.let { debounceHandler.removeCallbacks(it) }
            val query = text?.toString().orEmpty()
            val runnable = Runnable { runSearch(query) }
            pendingSearch = runnable
            debounceHandler.postDelayed(runnable, 400)
        })
    }

    private fun runSearch(query: String) {
        val session = SessionStore.getSavedSession(this) ?: return
        if (query.isBlank()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = "Digite pra buscar em canais, filmes e séries ao mesmo tempo"
            binding.sectionLive.visibility = View.GONE
            binding.sectionVod.visibility = View.GONE
            binding.sectionSeries.visibility = View.GONE
            return
        }
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            repository.searchAll(session, query)
                .onSuccess { results ->
                    val hasAny = results.live.isNotEmpty() || results.vod.isNotEmpty() || results.series.isNotEmpty()
                    binding.tvEmptyState.visibility = if (hasAny) View.GONE else View.VISIBLE
                    binding.tvEmptyState.text = "Nenhum resultado para \"$query\""

                    liveAdapter.submitList(results.live)
                    binding.sectionLive.visibility = if (results.live.isEmpty()) View.GONE else View.VISIBLE

                    vodAdapter.submitList(results.vod)
                    binding.sectionVod.visibility = if (results.vod.isEmpty()) View.GONE else View.VISIBLE

                    seriesAdapter.submitList(results.series)
                    binding.sectionSeries.visibility = if (results.series.isEmpty()) View.GONE else View.VISIBLE
                }
                .onFailure { error ->
                    Toast.makeText(this@SearchActivity, error.message ?: "Não foi possível buscar", Toast.LENGTH_LONG).show()
                }
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun openLive(channel: LiveStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.directStreamUrl ?: repository.buildLiveStreamUrl(session, channel.streamId))
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(PlayerActivity.EXTRA_STREAM_ID, channel.streamId)
            putExtra(PlayerActivity.EXTRA_EPG_CHANNEL_ID, channel.epgChannelId)
        })
    }

    private fun openVod(movie: VodStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, movie.directStreamUrl ?: repository.buildVodStreamUrl(session, movie.streamId, movie.containerExtension))
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, movie.name)
        })
    }

    private fun openSeries(series: SeriesItem) {
        startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
            putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, series.seriesId)
            putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, series.name)
            putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, series.cover)
        })
    }
}

private fun android.widget.EditText.addTextChangedListener(afterTextChanged: (CharSequence?) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = afterTextChanged(s)
    })
}
