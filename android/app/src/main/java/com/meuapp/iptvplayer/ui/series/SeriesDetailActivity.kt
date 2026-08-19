package com.meuapp.iptvplayer.ui.series

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.data.model.SeriesEpisode
import com.meuapp.iptvplayer.data.model.SeriesInfoResponse
import com.meuapp.iptvplayer.data.model.SeriesSeason
import com.meuapp.iptvplayer.databinding.ActivitySeriesDetailBinding
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class SeriesDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SERIES_ID = "extra_series_id"
        const val EXTRA_SERIES_NAME = "extra_series_name"
        const val EXTRA_SERIES_COVER = "extra_series_cover"
    }

    private lateinit var binding: ActivitySeriesDetailBinding
    private val repository = XtreamRepository()
    private lateinit var episodeAdapter: EpisodeAdapter
    private var detail: SeriesInfoResponse? = null
    private var seasons = emptyList<SeriesSeason>()
    private var seasonKeys = emptyList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = SessionStore.getSavedSession(this)
        val seriesId = intent.getIntExtra(EXTRA_SERIES_ID, -1)
        if (session == null || seriesId < 0) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val name = intent.getStringExtra(EXTRA_SERIES_NAME).orEmpty()
        val cover = intent.getStringExtra(EXTRA_SERIES_COVER)
        binding.detailToolbar.tvTitle.text = name
        binding.detailToolbar.btnBack.setOnClickListener { finish() }
        binding.backdropView.setPoster(cover, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.ivCover.load(cover) { crossfade(true) }

        episodeAdapter = EpisodeAdapter { episode -> openEpisode(session, episode) }
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodes.adapter = episodeAdapter
        binding.spinnerSeason.isEnabled = false

        loadDetails(seriesId)
    }

    private fun loadDetails(seriesId: Int) {
        val session = SessionStore.getSavedSession(this) ?: return
        setLoading(true)
        lifecycleScope.launch {
            repository.getSeriesInfo(session, seriesId)
                .onSuccess { response ->
                    detail = response
                    renderDetails(response)
                }
                .onFailure { error ->
                    Toast.makeText(
                        this@SeriesDetailActivity,
                        error.message ?: "Não foi possível carregar os episódios",
                        Toast.LENGTH_LONG
                    ).show()
                }
            setLoading(false)
        }
    }

    private fun renderDetails(response: SeriesInfoResponse) {
        response.info?.let { info ->
            binding.detailToolbar.tvTitle.text = info.name ?: binding.detailToolbar.tvTitle.text
            binding.ivCover.load(info.cover) { crossfade(true) }
            binding.backdropView.setPoster(info.cover, AppearancePrefs.isBackdropPosterEnabled(this))
            binding.tvPlot.text = info.plot.orEmpty()
            binding.tvMeta.text = listOfNotNull(
                info.genre?.takeIf { it.isNotBlank() },
                info.releaseDate?.takeIf { it.isNotBlank() },
                info.rating?.takeIf { it.isNotBlank() }?.let { "★ $it" }
            ).joinToString(" • ")
        }

        val episodesBySeason = response.episodes.orEmpty()
        seasons = response.seasons.orEmpty().ifEmpty {
            episodesBySeason.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                .map { key -> SeriesSeason(null, "Temporada $key", key.toIntOrNull(), episodesBySeason[key]?.size, null) }
        }
        seasonKeys = seasons.mapIndexed { index, season ->
            season.seasonNumber?.toString() ?: (index + 1).toString()
        }
        if (seasonKeys.isEmpty()) {
            binding.spinnerSeason.visibility = View.GONE
            episodeAdapter.submitList(emptyList())
            return
        }

        val labels = seasons.mapIndexed { index, season ->
            val title = season.name?.takeIf { it.isNotBlank() } ?: "Temporada ${seasonKeys[index]}"
            val count = season.episodeCount ?: episodesBySeason[seasonKeys[index]]?.size ?: 0
            "$title ($count episódios)"
        }
        binding.spinnerSeason.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        binding.spinnerSeason.isEnabled = true
        binding.spinnerSeason.setSelection(0)
        binding.spinnerSeason.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val key = seasonKeys.getOrNull(position) ?: return
                episodeAdapter.submitList(
                    episodesBySeason[key].orEmpty().sortedBy { it.episodeNumber ?: Int.MAX_VALUE }
                )
            }
        })
    }

    private fun openEpisode(session: com.meuapp.iptvplayer.data.api.Session, episode: SeriesEpisode) {
        val url = repository.buildSeriesStreamUrl(session, episode.id, episode.containerExtension)
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, episode.title ?: "Episódio")
        })
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
