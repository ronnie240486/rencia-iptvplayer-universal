package com.meuapp.iptvplayer.ui.vod

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.RenciaRepository
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.data.model.Category
import com.meuapp.iptvplayer.data.model.VodStream
import com.meuapp.iptvplayer.databinding.ActivityVodBinding
import com.meuapp.iptvplayer.ui.common.CategorySidebarAdapter
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch
import java.text.Normalizer

class VodActivity : AppCompatActivity() {

    companion object {
        private const val CATEGORY_RECENT = "__recent__"
        private const val CATEGORY_FAVORITES = "__favorites__"
    }

    private lateinit var binding: ActivityVodBinding
    private val repository by lazy { XtreamRepository(this) }
    private val renciaRepository = RenciaRepository()
    private lateinit var categoryAdapter: CategorySidebarAdapter
    private lateinit var gridAdapter: VodAdapter
    private var selectedPosterUrl: String? = null
    private var currentCategoryName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = SessionStore.getSavedSession(this)
        if (session == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = getString(R.string.tile_vod)
        binding.toolbar.tvSubtitle.text = "Escolha uma categoria de filmes"
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.setOnClickListener { startActivity(Intent(this, com.meuapp.iptvplayer.ui.search.SearchActivity::class.java)) }

        categoryAdapter = CategorySidebarAdapter(
            barEnabled = AppearancePrefs.isCategoryBarEnabled(this),
            barColorHex = AppearancePrefs.getCategoryBarColor(this)
        ) { category -> onCategorySelected(category) }

        gridAdapter = VodAdapter(
            onClick = { movie -> openDetail(movie) },
            onFocused = { movie ->
                selectedPosterUrl = movie.streamIcon
                binding.backdropView.setPoster(movie.streamIcon, AppearancePrefs.isBackdropPosterEnabled(this))
            }
        )

        binding.rvSidebar.layoutManager = LinearLayoutManager(this)
        binding.rvSidebar.adapter = categoryAdapter
        binding.rvSidebar.visibility = if (AppearancePrefs.isCategoryBarEnabled(this)) View.VISIBLE else View.GONE

        binding.rvGrid.layoutManager = GridLayoutManager(this, 3)
        binding.rvGrid.adapter = gridAdapter

        loadCategories()
    }

    private fun onCategorySelected(category: Category) {
        com.meuapp.iptvplayer.util.AdultContentGuard.guardCategorySelection(this, category) {
            loadMovies(category.categoryId, category.categoryName)
        }
    }

    private fun loadCategories() {
        val session = SessionStore.getSavedSession(this) ?: return
        setLoading(true)
        // Carrega com a sessão atual JÁ, sem esperar a checagem de "a
        // lista mudou?" (que é uma chamada de rede separada e pode demorar
        // até 20s se a rede estiver lenta) -- isso travava a tela inteira
        // até essa checagem terminar.
        lifecycleScope.launch {
            repository.getVodCategories(session)
                .onSuccess { categories ->
                    val allCategories = pinnedCategories() + categories
                    categoryAdapter.submitList(com.meuapp.iptvplayer.util.AdultContentGuard.sortWithAdultLast(allCategories))
                }
                .onFailure {
                    // Sair da tela antes da busca terminar cancela ela
                    // sozinho (Android fazendo isso) -- não é erro de
                    // verdade, não precisa assustar o usuário com aviso.
                    if (it !is kotlinx.coroutines.CancellationException) {
                        binding.toolbar.tvSubtitle.text = "Não foi possível carregar categorias"
                        showError("Não foi possível carregar as categorias de filmes")
                    }
                }
            setLoading(false)
        }
        lifecycleScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(6000) { renciaRepository.refreshSessionIfChanged(session).getOrNull() }?.let { updated ->
                SessionStore.saveSession(this@VodActivity, updated)
                loadCategories()
            }
        }
    }

    /** Duas categorias fixas no topo, feitas de Favoritos e Histórico
     * (guardados localmente no aparelho, não vêm da lista do provedor). */
    private fun pinnedCategories(): List<Category> = listOf(
        Category(categoryId = CATEGORY_RECENT, categoryName = "Recém Assistidos"),
        Category(categoryId = CATEGORY_FAVORITES, categoryName = "Favoritos")
    )

    private fun loadMovies(categoryId: String, categoryName: String) {
        val session = SessionStore.getSavedSession(this) ?: return
        currentCategoryName = categoryName
        binding.toolbar.tvSubtitle.text = "$categoryName · carregando filmes…"
        setLoading(true)
        if (categoryId == CATEGORY_RECENT || categoryId == CATEGORY_FAVORITES) {
            val movies = if (categoryId == CATEGORY_RECENT) {
                com.meuapp.iptvplayer.util.WatchHistoryStore.readAll(this)
                    .filter { it.kind == "vod" }
                    .map { it.toVodStream() }
            } else {
                com.meuapp.iptvplayer.util.FavoritesStore.readAll(this)
                    .filter { it.kind == "vod" }
                    .map { it.toVodStream() }
            }
            gridAdapter.submitList(movies)
            binding.toolbar.tvSubtitle.text = "$categoryName · ${movies.size} filmes"
            setLoading(false)
            return
        }
        lifecycleScope.launch {
            repository.getVodStreams(session, categoryId)
                .onSuccess { movies ->
                    gridAdapter.submitList(movies)
                    movies.firstOrNull()?.let { movie ->
                        selectedPosterUrl = movie.streamIcon
                        binding.backdropView.setPoster(movie.streamIcon, AppearancePrefs.isBackdropPosterEnabled(this@VodActivity))
                    }
                    binding.toolbar.tvSubtitle.text = "$categoryName · ${movies.size} filmes"
                }
                .onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) {
                        gridAdapter.submitList(emptyList())
                        binding.toolbar.tvSubtitle.text = "$categoryName · erro ao carregar"
                        showError("Não foi possível carregar os filmes desta categoria")
                    }
                }
            setLoading(false)
        }
    }

    /** Converte um item de Histórico/Favoritos num VodStream "de
     * mentirinha" só pra reaproveitar a mesma tela de detalhes de sempre. */
    private fun com.meuapp.iptvplayer.util.WatchHistoryItem.toVodStream() = VodStream(
        num = 0, name = title, streamId = 0, streamIcon = posterUrl,
        categoryId = null, rating = null, containerExtension = null, directStreamUrl = streamUrl
    )

    private fun com.meuapp.iptvplayer.util.FavoriteItem.toVodStream() = VodStream(
        num = 0, name = title, streamId = 0, streamIcon = posterUrl,
        categoryId = null, rating = null, containerExtension = null, directStreamUrl = streamUrl
    )

    /** Abre a tela de detalhes do filme (pôster grande, sinopse, botão
     * Assistir) -- mesma experiência da tela de detalhes de série. */
    private fun openDetail(movie: VodStream) {
        startActivity(Intent(this, VodDetailActivity::class.java).apply {
            putExtra(VodDetailActivity.EXTRA_NAME, movie.name)
            putExtra(VodDetailActivity.EXTRA_POSTER, movie.streamIcon)
            putExtra(VodDetailActivity.EXTRA_CATEGORY_NAME, currentCategoryName)
            putExtra(VodDetailActivity.EXTRA_STREAM_ID, movie.streamId)
            putExtra(VodDetailActivity.EXTRA_CONTAINER_EXTENSION, movie.containerExtension)
            putExtra(VodDetailActivity.EXTRA_DIRECT_STREAM_URL, movie.directStreamUrl)
            putExtra(VodDetailActivity.EXTRA_RATING, movie.rating)
        })
    }

    override fun onResume() {
        super.onResume()
        if (::categoryAdapter.isInitialized) {
            val enabled = AppearancePrefs.isCategoryBarEnabled(this)
            binding.rvSidebar.visibility = if (enabled) View.VISIBLE else View.GONE
            categoryAdapter.updateAppearance(enabled, AppearancePrefs.getCategoryBarColor(this))
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
