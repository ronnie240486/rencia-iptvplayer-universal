package com.meuapp.iptvplayer.ui.vod

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.Session
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.databinding.ActivityVodDetailBinding
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.FavoriteItem
import com.meuapp.iptvplayer.util.FavoritesStore
import com.meuapp.iptvplayer.util.SessionStore
import com.meuapp.iptvplayer.util.WatchHistoryItem
import com.meuapp.iptvplayer.util.WatchHistoryStore
import kotlinx.coroutines.launch

/** Tela de detalhes de UM filme -- mesmo estilo da tela de detalhes de
 * série (pôster grande, sinopse, botão de favoritar), só que sem lista de
 * episódios (filme é uma coisa só) e com um botão "ASSISTIR" no lugar. */
class VodDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_POSTER = "extra_poster"
        const val EXTRA_CATEGORY_NAME = "extra_category_name"
        const val EXTRA_STREAM_ID = "extra_stream_id"
        const val EXTRA_CONTAINER_EXTENSION = "extra_container_extension"
        const val EXTRA_DIRECT_STREAM_URL = "extra_direct_stream_url"
        const val EXTRA_RATING = "extra_rating"
    }

    private lateinit var binding: ActivityVodDetailBinding
    private val repository by lazy { XtreamRepository(this) }
    private val tmdbRepository = com.meuapp.iptvplayer.data.api.TmdbRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = SessionStore.getSavedSession(this)
        if (session == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val poster = intent.getStringExtra(EXTRA_POSTER)
        val categoryName = intent.getStringExtra(EXTRA_CATEGORY_NAME).orEmpty()
        val rating = intent.getStringExtra(EXTRA_RATING)
        val streamUrl = resolveStreamUrl(session)

        binding.detailToolbar.tvTitle.text = name
        binding.detailToolbar.btnBack.setOnClickListener { finish() }
        binding.backdropView.setPoster(poster, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.ivCover.load(poster) { crossfade(true) }
        binding.tvMeta.text = listOfNotNull(
            categoryName.takeIf { it.isNotBlank() },
            rating?.takeIf { it.isNotBlank() }?.let { "★ $it" }
        ).joinToString(" • ")

        setupFavoriteButton(name, poster, streamUrl)
        loadSynopsis(name)

        binding.btnPlay.setOnClickListener { play(session, name, poster, streamUrl) }
    }

    private fun resolveStreamUrl(session: Session): String {
        val direct = intent.getStringExtra(EXTRA_DIRECT_STREAM_URL)
        if (!direct.isNullOrBlank()) return direct
        val streamId = intent.getIntExtra(EXTRA_STREAM_ID, -1)
        val containerExtension = intent.getStringExtra(EXTRA_CONTAINER_EXTENSION)
        return repository.buildVodStreamUrl(session, streamId, containerExtension)
    }

    /** Listas M3U não trazem sinopse nenhuma -- busca no TMDB pelo NOME do
     * filme (mesma fonte já usada pra capa/sinopse de série). Se não achar
     * nada, deixa em branco (sem caixa de erro nem nada -- só não mostra
     * sinopse pra esse título específico). */
    private fun loadSynopsis(name: String) {
        if (name.isBlank()) return
        lifecycleScope.launch {
            val overview = runCatching { tmdbRepository.findMovieOverview(name) }.getOrNull()
            if (!overview.isNullOrBlank()) {
                binding.tvPlot.text = overview
            }
        }
    }

    private fun setupFavoriteButton(name: String, poster: String?, streamUrl: String) {
        val btn = binding.detailToolbar.btnSearch
        btn.setImageResource(R.drawable.ic_star)
        var isFavorite = FavoritesStore.isFavorite(this, "vod", streamUrl)
        fun updateIcon() {
            btn.alpha = if (isFavorite) 1f else 0.5f
            btn.setColorFilter(getColor(if (isFavorite) R.color.accent else android.R.color.white))
        }
        updateIcon()
        btn.setOnClickListener {
            isFavorite = FavoritesStore.toggle(
                this,
                FavoriteItem(
                    kind = "vod",
                    title = name,
                    posterUrl = poster,
                    streamUrl = streamUrl,
                    addedAt = System.currentTimeMillis()
                )
            )
            updateIcon()
            Toast.makeText(this, if (isFavorite) "Adicionado aos favoritos" else "Removido dos favoritos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun play(session: Session, name: String, poster: String?, streamUrl: String) {
        WatchHistoryStore.record(
            this,
            WatchHistoryItem(
                kind = "vod",
                title = name,
                subtitle = null,
                posterUrl = poster,
                streamUrl = streamUrl,
                watchedAt = System.currentTimeMillis()
            )
        )
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, name)
            putExtra(PlayerActivity.EXTRA_KIND, "vod")
            putExtra(PlayerActivity.EXTRA_POSTER_URL, poster)
        })
    }
}
