package com.meuapp.iptvplayer.ui.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.databinding.ActivitySimpleInfoBinding
import com.meuapp.iptvplayer.util.AppearancePrefs

class FavoritesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimpleInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = getString(R.string.tile_favorites)
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.visibility = View.GONE

        // TODO: implementar favoritar canais/filmes/séries (guardar streamId no SessionStore/Room)
        // e listar aqui. Por enquanto, estado vazio.
        binding.tvMessage.text = "Você ainda não tem favoritos.\nToque e segure em um canal, filme ou série para favoritar."
    }
}
