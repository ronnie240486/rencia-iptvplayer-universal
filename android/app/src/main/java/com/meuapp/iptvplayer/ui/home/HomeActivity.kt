package com.meuapp.iptvplayer.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.meuapp.iptvplayer.data.api.RenciaRepository
import com.meuapp.iptvplayer.databinding.ActivityHomeBinding
import com.meuapp.iptvplayer.ui.channels.ChannelListActivity
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.ui.multi.MultiScreenActivity
import com.meuapp.iptvplayer.ui.radio.RadioActivity
import com.meuapp.iptvplayer.ui.series.SeriesActivity
import com.meuapp.iptvplayer.ui.settings.AccountActivity
import com.meuapp.iptvplayer.ui.settings.FavoritesActivity
import com.meuapp.iptvplayer.ui.settings.SettingsActivity
import com.meuapp.iptvplayer.ui.vod.VodActivity
import com.meuapp.iptvplayer.util.RemoteLayoutTheme
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

/** Home real do app: fundo fixo (brasão) com 9 botões posicionados por cima
 * em coordenadas exatas (fração da tela), não um painel colorido gerado por
 * código. Confere o acesso (MAC ainda liberado no painel) a cada 5 minutos
 * enquanto o app fica aberto. */
class HomeActivity : AppCompatActivity() {

    companion object {
        private const val ACCESS_CHECK_INTERVAL_MS = 300_000L
    }

    private lateinit var binding: ActivityHomeBinding
    private val renciaRepository = RenciaRepository()
    private val accessHandler = Handler(Looper.getMainLooper())
    private val accessCheckRunnable: Runnable = object : Runnable {
        override fun run() {
            verifyAccessNow()
            accessHandler.postDelayed(this, ACCESS_CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        supportActionBar?.hide()

        val session = SessionStore.getSavedSession(this)
        if (session == null || session.mac.isBlank()) {
            SessionStore.clear(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        RemoteLayoutTheme.save(this, session.layoutId)

        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        bindHomeActions()
        setupContinueWatching()
        binding.dashboardOverlay.post {
            sizeDashboardToScreen()
            // Espera mais um ciclo de layout depois de mudar a altura --
            // sem isso, positionHomeTargets() ainda lia o tamanho ANTIGO
            // do painel (a mudança de altura só é aplicada de verdade no
            // próximo passo de desenho, não na hora).
            binding.dashboardContainer.post { positionHomeTargets() }
        }
    }

    /** O painel principal (fundo + brasão + botões) precisa ocupar a tela
     * inteira, igual sempre foi -- mas agora ele está dentro de um
     * ScrollView (pra "Continuar assistindo" poder ficar revelado ao rolar
     * pra baixo, em vez de um overlay fixo espremido em cima dos botões).
     * Dentro de um ScrollView não dá pra usar match_parent direto, então
     * define a altura em código, do tamanho exato da tela. */
    private fun sizeDashboardToScreen() {
        val screenHeight = resources.displayMetrics.heightPixels
        val params = binding.dashboardContainer.layoutParams
        if (params.height != screenHeight) {
            params.height = screenHeight
            binding.dashboardContainer.layoutParams = params
        }
    }

    override fun onStart() {
        super.onStart()
        verifyAccessNow()
        accessHandler.removeCallbacks(accessCheckRunnable)
        accessHandler.postDelayed(accessCheckRunnable, ACCESS_CHECK_INTERVAL_MS)
    }

    override fun onResume() {
        super.onResume()
        // Atualiza "Continuar assistindo" toda vez que volta pra Home (ex:
        // depois de assistir algo), não só na primeira abertura.
        if (::binding.isInitialized) refreshContinueWatching()
    }

    override fun onStop() {
        accessHandler.removeCallbacks(accessCheckRunnable)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            if (::binding.isInitialized) binding.dashboardOverlay.post {
                sizeDashboardToScreen()
                binding.dashboardContainer.post { positionHomeTargets() }
            }
        }
    }

    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter

    private fun setupContinueWatching() {
        continueWatchingAdapter = ContinueWatchingAdapter { item ->
            startActivity(Intent(this, com.meuapp.iptvplayer.ui.player.PlayerActivity::class.java).apply {
                putExtra(com.meuapp.iptvplayer.ui.player.PlayerActivity.EXTRA_STREAM_URL, item.streamUrl)
                putExtra(com.meuapp.iptvplayer.ui.player.PlayerActivity.EXTRA_CHANNEL_NAME, item.title)
            })
        }
        binding.rvContinueWatching.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        binding.rvContinueWatching.adapter = continueWatchingAdapter
        refreshContinueWatching()
    }

    private fun refreshContinueWatching() {
        val items = com.meuapp.iptvplayer.util.WatchHistoryStore.readAll(this)
        continueWatchingAdapter.submitList(items)
        binding.continueWatchingSection.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun bindHomeActions() {
        bindAction(binding.btnLiveTv, "live")
        bindAction(binding.btnEpg, "epg")
        bindAction(binding.btnVod, "vod")
        bindAction(binding.btnSeries, "series")
        bindAction(binding.btnAccount, "account")
        bindAction(binding.btnMulti, "multi")
        bindAction(binding.btnFavorite, "favorites")
        bindAction(binding.btnRadio, "radio")
        bindAction(binding.btnSettings, "settings")
    }

    private fun bindAction(button: ImageButton, action: String) {
        button.setOnClickListener { dispatchAction(action) }
        button.alpha = 1f
        button.isFocusable = true
        button.isClickable = true
    }

    /** Todas as posições/tamanhos são frações da tela (0..1) medidas na
     * imagem de fundo real -- igual ao app de referência, pra encaixar
     * certinho nos "encaixes" desenhados na imagem. */
    private fun positionHomeTargets() {
        val width = binding.dashboardOverlay.width
        val height = binding.dashboardOverlay.height
        if (width <= 0 || height <= 0) return

        place(binding.homeCrest, 0.34f, -0.02f, 0.32f, 0.62f)
        place(binding.btnLiveTv, 0.172f, 0.477f, 0.114f, 0.247f)
        place(binding.btnEpg, 0.357f, 0.477f, 0.116f, 0.247f)
        place(binding.btnVod, 0.544f, 0.477f, 0.139f, 0.247f)
        place(binding.btnSeries, 0.733f, 0.477f, 0.118f, 0.247f)
        place(binding.btnAccount, 0.172f, 0.785f, 0.1f, 0.139f)
        place(binding.btnMulti, 0.317f, 0.785f, 0.1f, 0.139f)
        place(binding.btnFavorite, 0.462f, 0.785f, 0.1f, 0.139f)
        place(binding.btnRadio, 0.607f, 0.785f, 0.1f, 0.139f)
        place(binding.btnSettings, 0.752f, 0.785f, 0.1f, 0.139f)

        placeLabel(binding.homeLabelLive, 0.172f, 0.477f, 0.114f, 0.247f)
        placeLabel(binding.homeLabelEpg, 0.357f, 0.477f, 0.116f, 0.247f)
        placeLabel(binding.homeLabelVod, 0.544f, 0.477f, 0.139f, 0.247f)
        placeLabel(binding.homeLabelSeries, 0.733f, 0.477f, 0.118f, 0.247f)
        placeLabel(binding.homeLabelAccount, 0.172f, 0.785f, 0.1f, 0.139f)
        placeLabel(binding.homeLabelMulti, 0.317f, 0.785f, 0.1f, 0.139f)
        placeLabel(binding.homeLabelFavorite, 0.462f, 0.785f, 0.1f, 0.139f)
        placeLabel(binding.homeLabelRadio, 0.607f, 0.785f, 0.1f, 0.139f)
        placeLabel(binding.homeLabelSettings, 0.752f, 0.785f, 0.1f, 0.139f)
    }

    private fun place(view: View, x: Float, y: Float, w: Float, h: Float) {
        val width = binding.dashboardOverlay.width
        val height = binding.dashboardOverlay.height
        val params = FrameLayout.LayoutParams(
            (width * w).toInt().coerceAtLeast(1),
            (height * h).toInt().coerceAtLeast(1)
        )
        params.leftMargin = (width * x).toInt()
        params.topMargin = (height * y).toInt()
        view.layoutParams = params
    }

    private fun placeLabel(view: View, x: Float, y: Float, w: Float, h: Float) {
        place(view, x, y, w, h)
        view.isClickable = false
        view.isFocusable = false
    }

    /** Só desloga de verdade quando o painel RESPONDEU e disse claramente
     * que o acesso não é mais permitido -- uma falha de rede passageira
     * (timeout, instabilidade, servidor lento) NÃO deve deslogar ninguém.
     * Sem essa distinção, qualquer soluço de rede jogava o usuário de
     * volta pro login, que reativava sozinho e voltava pra Home, que
     * falhava nessa MESMA checagem de novo -- um vaivém infinito entre as
     * duas telas, rápido o bastante pra travar o app inteiro (parecia
     * "não está respondendo" mesmo em aparelho rápido). */
    private fun isGenuineAccessDenial(message: String?): Boolean {
        val text = message.orEmpty()
        return text.contains("não está mais cadastrado", ignoreCase = true) ||
            text.contains("acesso bloqueado", ignoreCase = true)
    }

    private fun verifyAccessNow() {
        val session = SessionStore.getSavedSession(this) ?: return
        if (session.mac.isBlank()) return
        lifecycleScope.launch {
            renciaRepository.refreshSessionIfChanged(session)
                .onSuccess { updatedSession ->
                    // null = playlist não mudou, está tudo igual, nada a
                    // fazer. Não-null = o painel liberou uma playlist
                    // diferente da que o app estava usando (ex: usuário
                    // trocou de lista) -- salva a nova sessão automaticamente
                    // e os próximos carregamentos (canais/filmes/séries) já
                    // usam o servidor novo, sem precisar reinstalar nem
                    // logar de novo.
                    if (updatedSession != null) {
                        SessionStore.saveSession(this@HomeActivity, updatedSession)
                        Toast.makeText(this@HomeActivity, "Lista atualizada automaticamente", Toast.LENGTH_LONG).show()
                    }
                }
                .onFailure { error ->
                    if (isGenuineAccessDenial(error.message)) {
                        SessionStore.clear(this@HomeActivity)
                        Toast.makeText(this@HomeActivity, "Acesso indisponível para este MAC", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this@HomeActivity, LoginActivity::class.java))
                        finish()
                    }
                    // Qualquer outra falha (rede, timeout, servidor fora do
                    // ar por um instante) -- ignora silenciosamente e tenta
                    // de novo no próximo ciclo, sem deslogar ninguém.
                }
        }
    }

    private fun dispatchAction(action: String) {
        when (action) {
            "live" -> open(ChannelListActivity::class.java)
            "epg" -> open(com.meuapp.iptvplayer.ui.epg.EpgGuideActivity::class.java)
            "vod" -> open(VodActivity::class.java)
            "series" -> open(SeriesActivity::class.java)
            "account" -> open(AccountActivity::class.java)
            "multi" -> open(MultiScreenActivity::class.java)
            "favorites" -> open(FavoritesActivity::class.java)
            "radio" -> open(RadioActivity::class.java)
            "settings" -> open(SettingsActivity::class.java)
        }
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
