package com.meuapp.iptvplayer.ui.epg

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.data.model.EpgListing
import com.meuapp.iptvplayer.databinding.ActivityEpgGuideBinding
import com.meuapp.iptvplayer.util.AdultContentGuard
import com.meuapp.iptvplayer.util.ReminderScheduler
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

/** Guia de programação completo -- vários canais ao mesmo tempo, com
 * lembrete (sino) em cada linha. Diferente da faixa "agora/depois" da tela
 * de Canais (que só mostra o canal selecionado), aqui mostra vários canais
 * de uma vez, tipo um guia de TV a cabo. */
class EpgGuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEpgGuideBinding
    private val repository by lazy { XtreamRepository(this) }
    private lateinit var guideAdapter: GuideAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = SessionStore.getSavedSession(this)
        if (session == null) {
            finish()
            return
        }

        binding.toolbar.tvTitle.text = getString(R.string.tile_epg)
        binding.toolbar.tvSubtitle.text = "Programação de vários canais"
        binding.toolbar.btnBack.setOnClickListener { finish() }
        binding.toolbar.btnSearch.visibility = View.GONE

        guideAdapter = GuideAdapter(onReminderClick = ::toggleReminder)
        binding.rvGuide.layoutManager = LinearLayoutManager(this)
        binding.rvGuide.adapter = guideAdapter

        loadGuide()
    }

    private fun loadGuide() {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            repository.getLiveCategories(session)
                .onSuccess { categories ->
                    val safeCategories = AdultContentGuard.sortWithAdultLast(categories)
                        .filterNot { AdultContentGuard.isAdultCategory(it.categoryName) }
                    val rows = mutableListOf<GuideProgramRow>()
                    // Limita a poucos canais/categorias pra não fazer
                    // dezenas de chamadas de rede de uma vez só -- pega os
                    // primeiros canais de cada uma das primeiras categorias.
                    for (category in safeCategories.take(6)) {
                        val channels = repository.getLiveStreams(session, category.categoryId).getOrNull().orEmpty()
                        for (channel in channels.take(6)) {
                            val listings: List<EpgListing> = if (channel.directStreamUrl != null) {
                                repository.getEpgFromPlaylist(session, channel.epgChannelId, channel.name).getOrNull().orEmpty().map { p ->
                                    EpgListing(
                                        id = "", titleBase64 = p.title, descriptionBase64 = null,
                                        start = null, end = null,
                                        startTimestamp = p.startMillis / 1000, stopTimestamp = p.stopMillis / 1000
                                    )
                                }
                            } else {
                                repository.getShortEpg(session, channel.streamId).getOrNull()?.listings.orEmpty()
                            }
                            listings.forEach { listing -> rows.add(GuideProgramRow(channel, listing)) }
                        }
                        if (rows.size > 200) break
                    }
                    guideAdapter.submitList(rows)
                    if (rows.isEmpty()) {
                        Toast.makeText(this@EpgGuideActivity, "Nenhuma programação disponível pra essa lista", Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure {
                    Toast.makeText(this@EpgGuideActivity, "Não foi possível carregar o guia: ${it.message}", Toast.LENGTH_LONG).show()
                }
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun toggleReminder(row: GuideProgramRow, alreadyScheduled: Boolean) {
        val session = SessionStore.getSavedSession(this) ?: return
        val streamUrl = row.channel.directStreamUrl ?: repository.buildLiveStreamUrl(session, row.channel.streamId)
        if (alreadyScheduled) {
            ReminderScheduler.cancel(this, row.channel.streamId, row.listing)
            Toast.makeText(this, "Lembrete cancelado", Toast.LENGTH_SHORT).show()
        } else {
            val scheduled = ReminderScheduler.schedule(this, row.channel.streamId, row.channel.name, streamUrl, row.listing)
            Toast.makeText(
                this,
                if (scheduled) "Lembrete salvo" else "Horário do programa inválido",
                Toast.LENGTH_LONG
            ).show()
        }
        guideAdapter.notifyDataSetChanged()
    }
}
