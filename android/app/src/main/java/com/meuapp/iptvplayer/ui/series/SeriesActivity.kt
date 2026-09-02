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

    private lateinit var binding: ActivitySeriesBinding
    private val repository = XtreamRepository()
    private val renciaRepository = RenciaRepository()
    private lateinit var sidebarAdapter: CategorySidebarAdapter
    private lateinit var gridAdapter: SeriesAdapter
    private var selectedPosterUrl: String? = null

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

        sidebarAdapter = CategorySidebarAdapter(
            barEnabled = AppearancePrefs.isCategoryBarEnabled(this),
            barColorHex = AppearancePrefs.getCategoryBarColor(this)
        ) { category -> loadSeries(category.categoryId, category.categoryName) }

        gridAdapter = SeriesAdapter(
            lifecycleScope = lifecycleScope,
            onClick = { series ->
                startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, series.seriesId)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, series.name)
                    putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, series.cover)
                })
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
        lifecycleScope.launch {
            val activeSession = renciaRepository.refreshSessionIfChanged(session)
                .getOrNull()
                ?.also { SessionStore.saveSession(this@SeriesActivity, it) }
                ?: session
            repository.getSeriesCategories(activeSession)
                .onSuccess { sidebarAdapter.submitList(it) }
                .onFailure {
                    binding.toolbar.tvSubtitle.text = "Não foi possível carregar categorias"
                    showError("Não foi possível carregar as categorias de séries")
                }
            setLoading(false)
        }
    }

    private fun loadSeries(categoryId: String, categoryName: String) {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.toolbar.tvSubtitle.text = "$categoryName · carregando séries…"
        setLoading(true)
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
                    gridAdapter.submitList(emptyList())
                    binding.toolbar.tvSubtitle.text = "$categoryName · erro ao carregar"
                    showError("Não foi possível carregar as séries desta categoria")
                }
            setLoading(false)
        }
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
