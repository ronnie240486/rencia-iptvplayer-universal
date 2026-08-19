package com.meuapp.iptvplayer.ui.radio

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.RadioRepository
import com.meuapp.iptvplayer.data.model.RadioStation
import com.meuapp.iptvplayer.databinding.ActivityRadioBinding
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.ui.voice.VoiceCommandActivity
import com.meuapp.iptvplayer.util.AppearancePrefs
import kotlinx.coroutines.launch

class RadioActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRadioBinding
    private val repository = RadioRepository()
    private lateinit var adapter: RadioAdapter
    private var loadedCategory: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRadioBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.backdropView.setPoster(null, AppearancePrefs.isBackdropPosterEnabled(this))
        binding.toolbar.tvTitle.text = "RÁDIOS ONLINE"
        binding.toolbar.tvSubtitle.text = "Streams públicos verificados"
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.visibility = View.GONE

        adapter = RadioAdapter { station -> openStation(station) }
        binding.rvStations.layoutManager = GridLayoutManager(this, 4)
        binding.rvStations.adapter = adapter
        binding.etSearch.addTextChangedListener { text -> adapter.filter(text?.toString().orEmpty()) }
        binding.btnVoice.setOnClickListener {
            startActivity(Intent(this, VoiceCommandActivity::class.java))
        }

        val categories = RadioRepository.CATEGORY_TAGS.keys.toList()
        binding.spinnerCategory.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            categories
        )
        binding.spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                categories.getOrNull(position)?.let { loadCategory(it) }
            }
        }
    }

    private fun loadCategory(category: String) {
        if (category == loadedCategory) return
        loadedCategory = category
        setLoading(true)
        binding.tvSummary.text = "Buscando rádios de $category..."
        lifecycleScope.launch {
            repository.getCategory(category)
                .onSuccess { stations ->
                    adapter.submitList(stations)
                    binding.tvSummary.text = "${stations.size} rádios encontradas em $category"
                }
                .onFailure { error ->
                    adapter.submitList(emptyList())
                    binding.tvSummary.text = "Não foi possível carregar $category"
                    Toast.makeText(
                        this@RadioActivity,
                        error.message ?: "Falha ao consultar rádios",
                        Toast.LENGTH_LONG
                    ).show()
                }
            setLoading(false)
        }
    }

    private fun openStation(station: RadioStation) {
        val stream = station.urlResolved?.takeIf { it.isNotBlank() }
        if (stream == null) {
            Toast.makeText(this, "Esta rádio não possui stream reproduzível", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch { repository.markClick(station) }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, stream)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, station.name ?: "Rádio online")
        })
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
