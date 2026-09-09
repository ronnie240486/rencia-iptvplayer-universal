package com.meuapp.iptvplayer.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.databinding.ActivityCategoryOrderBinding
import com.meuapp.iptvplayer.ui.login.LoginActivity
import android.content.Intent
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.CategoryOrderStore
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

/** Deixa o usuário arrastar as categorias de Canais pra colocar na ordem
 * que quiser -- pedido explícito: alguns provedores organizam a playlist
 * de um jeito que não é o que a pessoa prefere ver, então em vez de tentar
 * adivinhar uma ordem "certa", dá o controle direto pra ela. */
class CategoryOrderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCategoryOrderBinding
    private val repository by lazy { XtreamRepository(this) }
    private lateinit var adapter: CategoryOrderAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryOrderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = "Posições das categorias"
        binding.toolbar.tvSubtitle.text = "Canais"
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.visibility = View.GONE

        val session = SessionStore.getSavedSession(this)
        if (session == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        adapter = CategoryOrderAdapter { newOrder ->
            // Salva sozinho a cada arraste solto -- sem botão "Salvar"
            // separado pra não esquecer de apertar.
            CategoryOrderStore.saveLiveOrder(this, newOrder)
        }
        binding.rvCategories.layoutManager = LinearLayoutManager(this)
        binding.rvCategories.adapter = adapter
        adapter.attachTo(binding.rvCategories)

        binding.btnResetOrder.setOnClickListener {
            CategoryOrderStore.clearLiveOrder(this)
            Toast.makeText(this, "Ordem original restaurada", Toast.LENGTH_SHORT).show()
            loadCategories(session)
        }

        loadCategories(session)
    }

    private fun loadCategories(session: com.meuapp.iptvplayer.data.api.Session) {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            repository.getLiveCategories(session)
                .onSuccess { categories ->
                    // A ordem "natural" que vem daqui já reflete a
                    // playlist -- por cima disso, aplica a ordem
                    // customizada salva (se tiver alguma).
                    val ordered = CategoryOrderStore.applyLiveOrder(this@CategoryOrderActivity, categories)
                    adapter.submitList(ordered.map { it.categoryName })
                }
                .onFailure {
                    Toast.makeText(this@CategoryOrderActivity, "Não foi possível carregar as categorias", Toast.LENGTH_SHORT).show()
                }
            binding.progressBar.visibility = View.GONE
        }
    }
}
