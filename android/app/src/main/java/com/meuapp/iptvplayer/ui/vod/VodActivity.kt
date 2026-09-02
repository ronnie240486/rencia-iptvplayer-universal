package com.meuapp.iptvplayer.ui.vod

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch
import java.text.Normalizer

class VodActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVodBinding
    private val repository = XtreamRepository()
    private val renciaRepository = RenciaRepository()
    private lateinit var categoryAdapter: CategorySidebarAdapter
    private lateinit var gridAdapter: VodAdapter
    private var selectedPosterUrl: String? = null

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

        categoryAdapter = CategorySidebarAdapter(
            barEnabled = AppearancePrefs.isCategoryBarEnabled(this),
            barColorHex = AppearancePrefs.getCategoryBarColor(this)
        ) { category -> onCategorySelected(category) }

        gridAdapter = VodAdapter(
            onClick = { movie -> openPlayer(movie) },
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
        lifecycleScope.launch {
            val activeSession = renciaRepository.refreshSessionIfChanged(session)
                .getOrNull()
                ?.also { SessionStore.saveSession(this@VodActivity, it) }
                ?: session
            repository.getVodCategories(activeSession)
                .onSuccess { categories -> categoryAdapter.submitList(com.meuapp.iptvplayer.util.AdultContentGuard.sortWithAdultLast(categories)) }
                .onFailure {
                    binding.toolbar.tvSubtitle.text = "Não foi possível carregar categorias"
                    showError("Não foi possível carregar as categorias de filmes")
                }
            setLoading(false)
        }
    }

    private fun loadMovies(categoryId: String, categoryName: String) {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.toolbar.tvSubtitle.text = "$categoryName · carregando filmes…"
        setLoading(true)
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
                    gridAdapter.submitList(emptyList())
                    binding.toolbar.tvSubtitle.text = "$categoryName · erro ao carregar"
                    showError("Não foi possível carregar os filmes desta categoria")
                }
            setLoading(false)
        }
    }

    private fun openPlayer(movie: VodStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        val streamUrl = movie.directStreamUrl ?: repository.buildVodStreamUrl(session, movie.streamId, movie.containerExtension)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, movie.name)
        }
        startActivity(intent)
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
