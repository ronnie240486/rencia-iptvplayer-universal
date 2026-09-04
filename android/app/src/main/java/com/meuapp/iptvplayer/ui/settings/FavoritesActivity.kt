package com.meuapp.iptvplayer.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.databinding.ActivityFavoritesBinding
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.ui.series.SeriesDetailActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.FavoriteItem
import com.meuapp.iptvplayer.util.FavoritesStore

class FavoritesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFavoritesBinding
    private lateinit var adapter: FavoritesAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFavoritesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = getString(R.string.tile_favorites)
        binding.toolbar.tvSubtitle.text = "Toque para assistir · pressão longa para remover"
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.visibility = View.GONE

        adapter = FavoritesAdapter(
            onClick = { item -> openItem(item) },
            onLongClick = { item -> confirmRemove(item) }
        )
        binding.rvFavorites.layoutManager = GridLayoutManager(this, 4)
        binding.rvFavorites.adapter = adapter

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val items = FavoritesStore.readAll(this)
        adapter.submitList(items)
        binding.tvEmptyState.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        binding.rvFavorites.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openItem(item: FavoriteItem) {
        if (item.kind == "series" && item.seriesId != null) {
            startActivity(Intent(this, SeriesDetailActivity::class.java).apply {
                putExtra(SeriesDetailActivity.EXTRA_SERIES_ID, item.seriesId)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_NAME, item.title)
                putExtra(SeriesDetailActivity.EXTRA_SERIES_COVER, item.seriesCover)
            })
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, item.title)
            putExtra(PlayerActivity.EXTRA_KIND, item.kind)
            putExtra(PlayerActivity.EXTRA_POSTER_URL, item.posterUrl)
        })
    }

    private fun confirmRemove(item: FavoriteItem) {
        AlertDialog.Builder(this)
            .setTitle("Remover favorito")
            .setMessage("Remover \"${item.title}\" dos favoritos?")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Remover") { _, _ ->
                FavoritesStore.toggle(this, item)
                refresh()
                Toast.makeText(this, "Removido dos favoritos", Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
