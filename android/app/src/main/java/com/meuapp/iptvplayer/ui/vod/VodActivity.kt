package com.meuapp.iptvplayer.ui.vod

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.data.model.VodStream
import com.meuapp.iptvplayer.databinding.ActivityVodBinding
import com.meuapp.iptvplayer.ui.common.CategorySidebarAdapter
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class VodActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVodBinding
    private val repository = XtreamRepository()
    private lateinit var sidebarAdapter: CategorySidebarAdapter
    private lateinit var gridAdapter: VodAdapter

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
        binding.toolbar.btnBack.setOnClickListener { finish() }

        sidebarAdapter = CategorySidebarAdapter(
            barEnabled = AppearancePrefs.isCategoryBarEnabled(this),
            barColorHex = AppearancePrefs.getCategoryBarColor(this)
        ) { category -> loadMovies(category.categoryId, category.categoryName) }

        gridAdapter = VodAdapter { movie -> openPlayer(movie) }

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
            val result = repository.getVodCategories(session)
            setLoading(false)
            result.onSuccess { sidebarAdapter.submitList(it) }
        }
    }

    private fun loadMovies(categoryId: String, categoryName: String) {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.toolbar.tvSubtitle.text = categoryName
        setLoading(true)
        lifecycleScope.launch {
            val result = repository.getVodStreams(session, categoryId)
            setLoading(false)
            result.onSuccess { movies ->
                gridAdapter.submitList(movies)
                binding.toolbar.tvSubtitle.text = "$categoryName · ${movies.size} filmes"
            }
        }
    }

    private fun openPlayer(movie: VodStream) {
        val session = SessionStore.getSavedSession(this) ?: return
        val streamUrl = repository.buildVodStreamUrl(session, movie.streamId, movie.containerExtension)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, movie.name)
        }
        startActivity(intent)
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
