package com.meuapp.iptvplayer.ui.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.databinding.ActivitySimpleInfoBinding
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.SessionStore

class AccountActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySimpleInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySimpleInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = getString(R.string.tile_account)
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.visibility = View.GONE

        val session = SessionStore.getSavedSession(this)
        if (session == null) {
            binding.tvMessage.text = "Nenhum aparelho ativado."
            return
        }

        binding.tvMessage.text = buildString {
            append("MAC: ${session.mac}\n")
            append("Status: ${session.status ?: "-"}\n")
            append("Vencimento: ${session.expirationDate ?: "-"}\n")
            append("Aplicativo: ${session.appName ?: getString(R.string.app_name)}")
        }
    }
}
