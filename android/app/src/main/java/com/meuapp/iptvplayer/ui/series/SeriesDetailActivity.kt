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
    private val repository by lazy { XtreamRepository(this) }
    private val tmdbRepository = com.meuapp.iptvplayer.data.api.TmdbRepository()
    private lateinit var episodeAdapter: EpisodeAdapter
    private var detail: SeriesInfoResponse? = null
    private var seasons = emptyList<SeriesSeason>()
    private var seasonKeys = emptyList<String>()
    // Sinopse que veio pronta (API Xtream, quando existir) -- listas M3U
    // não têm esse dado, então fica null nesse caso.
    private var apiPlot: String? = null
    // Sinopse/ID buscados no TMDB pelo NOME da série -- único jeito de
    // mostrar sinopse pra conteúdo vindo de M3U, que não traz isso.
    private var tmdbOverview: String? = null
    private var tmdbSeriesId: Int? = null
    // Temporada selecionada no momento -- precisa saber isso (+ o número
    // do episódio) pra buscar a sinopse de UM episódio específico no TMDB.
    private var currentSeasonNumber: Int? = null

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
        loadTmdbDetails(name)
        setupFavoriteButton(seriesId, name, cover)

        episodeAdapter = EpisodeAdapter { episode -> openEpisode(session, episode) }
        binding.rvEpisodes.layoutManager = LinearLayoutManager(this)
        binding.rvEpisodes.adapter = episodeAdapter
        binding.spinnerSeason.isEnabled = false

        loadDetails(seriesId)
    }

    /** Busca no TMDB (pelo NOME) o pôster oficial + a sinopse da série --
     * é a única fonte possível de sinopse pra série vinda de M3U, que não
     * traz esse dado. Mesma chamada já usada pra buscar o pôster, agora
     * também aproveitada pra sinopse (e guarda o ID, usado depois pra
     * buscar sinopse de episódio específico). */
    private fun loadTmdbDetails(name: String) {
        if (name.isBlank()) return
        lifecycleScope.launch {
            tmdbRepository.findSeriesDetails(name)?.let { details ->
                details.posterUrl?.let { posterUrl ->
                    binding.ivCover.load(posterUrl) { crossfade(true) }
                    binding.backdropView.setPoster(posterUrl, AppearancePrefs.isBackdropPosterEnabled(this@SeriesDetailActivity))
                }
                tmdbOverview = details.overview
                tmdbSeriesId = details.tmdbId
                refreshPlotDisplay()
            }
        }
    }

    /** Mostra a sinopse que tiver disponível -- prioriza a que já vem
     * pronta (API Xtream), e só usa a do TMDB quando a lista é M3U (que
     * nunca traz sinopse nenhuma). Chamada depois de CADA uma das duas
     * buscas (API + TMDB) terminar, pra não perder o resultado de
     * qualquer uma das duas por causa da ordem em que terminam. */
    private fun refreshPlotDisplay() {
        binding.tvPlot.text = apiPlot?.takeIf { it.isNotBlank() } ?: tmdbOverview.orEmpty()
    }

    /** O botão de busca do topo não faz sentido aqui (já estamos dentro de
     * uma série) -- reaproveita ele como botão de favoritar a série. */
    private fun setupFavoriteButton(seriesId: Int, name: String, cover: String?) {
        val btn = binding.detailToolbar.btnSearch
        btn.setImageResource(R.drawable.ic_star)
        var isFavorite = com.meuapp.iptvplayer.util.FavoritesStore.isFavorite(this, "series", "series:$seriesId", seriesId)
        fun updateIcon() {
            btn.alpha = if (isFavorite) 1f else 0.5f
            btn.setColorFilter(getColor(if (isFavorite) R.color.accent else android.R.color.white))
        }
        updateIcon()
        btn.setOnClickListener {
            isFavorite = com.meuapp.iptvplayer.util.FavoritesStore.toggle(
                this,
                com.meuapp.iptvplayer.util.FavoriteItem(
                    kind = "series",
                    title = name,
                    posterUrl = cover,
                    streamUrl = "series:$seriesId",
                    seriesId = seriesId,
                    seriesCover = cover,
                    addedAt = System.currentTimeMillis()
                )
            )
            updateIcon()
            Toast.makeText(this, if (isFavorite) "Adicionado aos favoritos" else "Removido dos favoritos", Toast.LENGTH_SHORT).show()
        }
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
            apiPlot = info.plot
            refreshPlotDisplay()
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
                currentSeasonNumber = key.toIntOrNull()
                episodeAdapter.submitList(
                    episodesBySeason[key].orEmpty().sortedBy { it.episodeNumber ?: Int.MAX_VALUE }
                )
            }
        })
    }

    /** Antes de tocar o episódio, busca a sinopse dele no TMDB (se der pra
     * saber qual é -- precisa do ID da série + temporada + número do
     * episódio) e mostra pro usuário confirmar, igual a maioria dos apps
     * de streaming faz. Se não achar sinopse nenhuma (série não
     * encontrada no TMDB, ou episódio isolado sem número certo), toca
     * direto sem incomodar com uma caixa vazia. */
    private fun openEpisode(session: com.meuapp.iptvplayer.data.api.Session, episode: SeriesEpisode) {
        val seriesTmdbId = tmdbSeriesId
        val season = currentSeasonNumber
        val episodeNumber = episode.episodeNumber
        if (seriesTmdbId == null || season == null || episodeNumber == null) {
            playEpisode(session, episode)
            return
        }
        lifecycleScope.launch {
            val overview = runCatching { tmdbRepository.findEpisodeOverview(seriesTmdbId, season, episodeNumber) }.getOrNull()
            if (overview.isNullOrBlank()) {
                playEpisode(session, episode)
            } else {
                val title = episode.title?.takeIf { it.isNotBlank() } ?: "Episódio $episodeNumber"
                androidx.appcompat.app.AlertDialog.Builder(this@SeriesDetailActivity)
                    .setTitle(title)
                    .setMessage(overview)
                    .setPositiveButton("Assistir") { _, _ -> playEpisode(session, episode) }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }
    }

    private fun playEpisode(session: com.meuapp.iptvplayer.data.api.Session, episode: SeriesEpisode) {
        val url = episode.directStreamUrl ?: repository.buildSeriesStreamUrl(session, episode.id, episode.containerExtension)
        val seriesName = intent.getStringExtra(EXTRA_SERIES_NAME).orEmpty()
        val cover = intent.getStringExtra(EXTRA_SERIES_COVER)
        com.meuapp.iptvplayer.util.WatchHistoryStore.record(
            this,
            com.meuapp.iptvplayer.util.WatchHistoryItem(
                kind = "series",
                title = seriesName.ifBlank { episode.title ?: "Episódio" },
                subtitle = episode.title,
                posterUrl = cover,
                streamUrl = url,
                watchedAt = System.currentTimeMillis()
            )
        )
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, url)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, episode.title ?: "Episódio")
        })
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
