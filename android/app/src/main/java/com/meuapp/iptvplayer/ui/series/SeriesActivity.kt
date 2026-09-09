package com.meuapp.iptvplayer.ui.series

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.RenciaRepository
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.databinding.ActivitySeriesBinding
import com.meuapp.iptvplayer.ui.common.CategorySidebarAdapter
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class SeriesActivity : AppCompatActivity() {

    companion object {
        private const val CATEGORY_RECENT = "__recent__"
        private const val CATEGORY_FAVORITES = "__favorites__"
    }

    private lateinit var binding: ActivitySeriesBinding
    private val repository by lazy { XtreamRepository(this) }
    private val renciaRepository = RenciaRepository()
    private lateinit var sidebarAdapter: CategorySidebarAdapter
    private lateinit var gridAdapter: SeriesAdapter
    private var selectedPosterUrl: String? = null
    // "Recém Assistidos" guarda EPISÓDIOS específicos (não a série toda) --
    // esse mapa liga o seriesId "de mentirinha" desses itens ao link de
    // reprodução de verdade, pra tocar direto em vez de abrir a tela de
    // temporadas/episódios (que não faz sentido pra um episódio avulso).
    private val recentEpisodePlayback = mutableMapOf<Int, com.meuapp.iptvplayer.util.WatchHistoryItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = SessionStore.getSavedSession(this)
        if (session == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = getString(R.string.tile_series)
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.setOnClickListener { startActivity(Intent(this, com.meuapp.iptvplayer.ui.search.SearchActivity::class.java)) }

        sidebarAdapter = CategorySidebarAdapter(
            barEnabled = AppearancePrefs.isCategoryBarEnabled(this),
            barColorHex = AppearancePrefs.getCategoryBarColor(this)
        ) { category ->
            com.meuapp.iptvplayer.util.AdultContentGuard.guardCategorySelection(this, category) {
                loadSeries(category.categoryId, category.categoryName)
            }
        }

        gridAdapter = SeriesAdapter(
            lifecycleScope = lifecycleScope,
            onClick = { series ->
                // Item de "Recém Assistidos" (episódio avulso) -- toca
                // direto em vez de abrir a tela de temporadas/episódios.
                val recentItem = recentEpisodePlayback[series.seriesId]
                if (recentItem != null) {
                    playDirect(recentItem)
                } else {
                    startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, series.seriesId)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, series.name)
                        putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, series.cover)
                    })
                }
            },
            onFocused = { series ->
                selectedPosterUrl = series.cover
                binding.backdropView.setPoster(series.cover, AppearancePrefs.isBackdropPosterEnabled(this))
            }
        )

        binding.rvSidebar.layoutManager = LinearLayoutManager(this)
        binding.rvSidebar.adapter = sidebarAdapter
        binding.rvSidebar.visibility = if (AppearancePrefs.isCategoryBarEnabled(this)) View.VISIBLE else View.GONE

        binding.rvGrid.layoutManager = GridLayoutManager(this, 3)
        binding.rvGrid.adapter = gridAdapter

        loadCategories()
    }

    private fun loadCategories() {
        val session = SessionStore.getSavedSession(this) ?: return
        setLoading(true)
        // Carrega com a sessão atual JÁ, sem esperar a checagem de "a
        // lista mudou?" (que é uma chamada de rede separada e pode demorar
        // até 20s se a rede estiver lenta) -- isso travava a tela inteira
        // até essa checagem terminar.
        lifecycleScope.launch {
            repository.getSeriesCategories(session)
                .onSuccess { categories ->
                    val allCategories = pinnedCategories() + categories
                    sidebarAdapter.submitList(com.meuapp.iptvplayer.util.AdultContentGuard.sortWithAdultLast(allCategories))
                }
                .onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) {
                        binding.toolbar.tvSubtitle.text = "Não foi possível carregar categorias"
                        showError("Não foi possível carregar as categorias de séries")
                    }
                }
            setLoading(false)
        }
        lifecycleScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(6000) { renciaRepository.refreshSessionIfChanged(session).getOrNull() }?.let { updated ->
                SessionStore.saveSession(this@SeriesActivity, updated)
                loadCategories()
            }
        }
    }

    /** Duas categorias fixas no topo, feitas de Favoritos e Histórico
     * (guardados localmente no aparelho, não vêm da lista do provedor). */
    private fun pinnedCategories(): List<com.meuapp.iptvplayer.data.model.Category> = listOf(
        com.meuapp.iptvplayer.data.model.Category(categoryId = CATEGORY_RECENT, categoryName = "Recém Assistidos"),
        com.meuapp.iptvplayer.data.model.Category(categoryId = CATEGORY_FAVORITES, categoryName = "Favoritos")
    )

    private fun loadSeries(categoryId: String, categoryName: String) {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.toolbar.tvSubtitle.text = "$categoryName · carregando séries…"
        setLoading(true)
        recentEpisodePlayback.clear()
        if (categoryId == CATEGORY_FAVORITES) {
            val favorites = com.meuapp.iptvplayer.util.FavoritesStore.readAll(this)
                .filter { it.kind == "series" && it.seriesId != null }
                .map { fav ->
                    com.meuapp.iptvplayer.data.model.SeriesItem(
                        num = 0, name = fav.title, seriesId = fav.seriesId!!,
                        cover = fav.seriesCover ?: fav.posterUrl, categoryId = categoryName,
                        rating = null, lastModified = null
                    )
                }
            gridAdapter.submitList(favorites)
            binding.toolbar.tvSubtitle.text = "$categoryName · ${favorites.size} séries"
            setLoading(false)
            return
        }
        if (categoryId == CATEGORY_RECENT) {
            // Cada item aqui é um EPISÓDIO específico -- usa um seriesId
            // "de mentirinha" (negativo, nunca colide com um de verdade)
            // só pra reaproveitar a mesma grade/adapter de sempre.
            val history = com.meuapp.iptvplayer.util.WatchHistoryStore.readAll(this).filter { it.kind == "series" }
            val items = history.mapIndexed { index, item ->
                val fakeId = -(index + 1)
                recentEpisodePlayback[fakeId] = item
                com.meuapp.iptvplayer.data.model.SeriesItem(
                    num = 0, name = item.title, seriesId = fakeId,
                    cover = item.posterUrl, categoryId = categoryName,
                    rating = null, lastModified = null
                )
            }
            gridAdapter.submitList(items)
            binding.toolbar.tvSubtitle.text = "$categoryName · ${items.size} episódios"
            setLoading(false)
            return
        }
        lifecycleScope.launch {
            repository.getSeries(session, categoryId)
                .onSuccess { series ->
                    gridAdapter.submitList(series)
                    series.firstOrNull()?.let {
                        selectedPosterUrl = it.cover
                        binding.backdropView.setPoster(it.cover, AppearancePrefs.isBackdropPosterEnabled(this@SeriesActivity))
                    }
                    binding.toolbar.tvSubtitle.text = "$categoryName · ${series.size} séries"
                }
                .onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) {
                        gridAdapter.submitList(emptyList())
                        binding.toolbar.tvSubtitle.text = "$categoryName · erro ao carregar"
                        showError("Não foi possível carregar as séries desta categoria")
                    }
                }
            setLoading(false)
        }
    }

    /** Toca direto um episódio de "Recém Assistidos" -- já tem o link de
     * reprodução pronto, não precisa passar pela tela de temporadas. */
    private fun playDirect(item: com.meuapp.iptvplayer.util.WatchHistoryItem) {
        startActivity(Intent(this, com.meuapp.iptvplayer.ui.player.PlayerActivity::class.java).apply {
            putExtra(com.meuapp.iptvplayer.ui.player.PlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
            putExtra(com.meuapp.iptvplayer.ui.player.PlayerActivity.EXTRA_CHANNEL_NAME, item.subtitle ?: item.title)
        })
    }

    override fun onResume() {
        super.onResume()
        if (::sidebarAdapter.isInitialized) {
            val enabled = AppearancePrefs.isCategoryBarEnabled(this)
            binding.rvSidebar.visibility = if (enabled) View.VISIBLE else View.GONE
            sidebarAdapter.updateAppearance(enabled, AppearancePrefs.getCategoryBarColor(this))
        }
        binding.backdropView.setPoster(selectedPosterUrl, AppearancePrefs.isBackdropPosterEnabled(this))
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
