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
    private lateinit var sidebarAdapter: CategorySidebarAdapter
    private lateinit var gridAdapter: SeriesAdapter

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

        gridAdapter = SeriesAdapter { series ->
            startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, series.seriesId)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, series.name)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, series.cover)
            })
        }

        binding.rvSidebar.layoutManager = LinearLayoutManager(this)
        binding.rvSidebar.adapter = sidebarAdapter

        binding.rvGrid.layoutManager = GridLayoutManager(this, 3)
        binding.rvGrid.adapter = gridAdapter

        loadCategories()
    }

    private fun loadCategories() {
        val session = SessionStore.getSavedSession(this) ?: return
        setLoading(true)
        lifecycleScope.launch {
            val result = repository.getSeriesCategories(session)
            setLoading(false)
            result.onSuccess { sidebarAdapter.submitList(it) }
        }
    }

    private fun loadSeries(categoryId: String, categoryName: String) {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.toolbar.tvSubtitle.text = categoryName
        setLoading(true)
        lifecycleScope.launch {
            val result = repository.getSeries(session, categoryId)
            setLoading(false)
            result.onSuccess { series ->
                gridAdapter.submitList(series)
                binding.toolbar.tvSubtitle.text = "$categoryName · ${series.size} séries"
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
