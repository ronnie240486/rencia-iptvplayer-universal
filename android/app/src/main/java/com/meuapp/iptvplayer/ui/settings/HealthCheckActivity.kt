package com.meuapp.iptvplayer.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.databinding.ActivityHealthCheckBinding
import com.meuapp.iptvplayer.databinding.ItemHealthResultBinding
import com.meuapp.iptvplayer.util.AppearancePrefs
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

/** Testa a conexão de vários canais da lista de uma vez (sem precisar
 * clicar um por um) e mostra quais estão com problema. */
class HealthCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHealthCheckBinding
    private val repository by lazy { XtreamRepository(this) }
    private lateinit var resultsAdapter: ResultsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHealthCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = "Verificar lista"
        binding.toolbar.tvSubtitle.text = "Testa a conexão dos canais"
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.visibility = View.GONE

        resultsAdapter = ResultsAdapter()
        binding.rvResults.layoutManager = LinearLayoutManager(this)
        binding.rvResults.adapter = resultsAdapter

        binding.btnStart.setOnClickListener { runCheck() }
    }

    private fun runCheck() {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.btnStart.isEnabled = false
        binding.tvStatus.text = "Iniciando verificação…"
        binding.progressBar.progress = 0
        binding.tvResultsHeader.visibility = View.GONE
        resultsAdapter.submitList(emptyList())

        lifecycleScope.launch {
            repository.healthCheck(session, limit = 150) { checked, total ->
                runOnUiThread {
                    val percent = if (total > 0) (checked * 100 / total) else 0
                    binding.progressBar.progress = percent
                    binding.tvStatus.text = "Testando $checked de $total canais…"
                }
            }.onSuccess { results ->
                val broken = results.filterNot { it.ok }
                binding.tvStatus.text = if (results.isEmpty()) {
                    "Nenhum canal disponível pra testar (essa lista pode não ter M3U)."
                } else {
                    "${results.size} canais testados · ${results.size - broken.size} ok · ${broken.size} com problema"
                }
                resultsAdapter.submitList(broken)
                binding.tvResultsHeader.visibility = if (broken.isEmpty()) View.GONE else View.VISIBLE
            }.onFailure { error ->
                binding.tvStatus.text = "Não foi possível verificar: ${error.message}"
            }
            binding.btnStart.isEnabled = true
            binding.btnStart.text = "Verificar de novo"
        }
    }

    private class ResultsAdapter : RecyclerView.Adapter<ResultsAdapter.ViewHolder>() {
        private val items = mutableListOf<XtreamRepository.HealthCheckResult>()

        fun submitList(newItems: List<XtreamRepository.HealthCheckResult>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemHealthResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.tvName.text = item.name
            holder.binding.tvCategory.text = "${item.category} · sem resposta do servidor"
        }

        override fun getItemCount() = items.size

        class ViewHolder(val binding: ItemHealthResultBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
