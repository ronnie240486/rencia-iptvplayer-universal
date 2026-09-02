package com.meuapp.iptvplayer.ui.login

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.meuapp.iptvplayer.data.api.RenciaRepository
import com.meuapp.iptvplayer.data.api.Session
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.databinding.ActivityLoginBinding
import com.meuapp.iptvplayer.ui.home.HomeActivity
import com.meuapp.iptvplayer.util.MacAddressProvider
import com.meuapp.iptvplayer.util.RemoteLayoutTheme
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

/** Ativação por MAC do aparelho -- não é login de usuário/senha. O MAC é
 * detectado automaticamente e a tela fica tentando ativar sozinha, em
 * segundo plano, de tempos em tempos. Depois de ativar, a lista completa
 * (playlist M3U) já é baixada aqui mesmo, com barra de progresso e
 * cronômetro, pra quando o usuário chegar na Home tudo já estar pronto e
 * as telas abrirem na hora, sem esperar de novo. */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val RETRY_INTERVAL_MS = 6_000L
        private const val TIMER_TICK_MS = 250L
    }

    private lateinit var binding: ActivityLoginBinding
    private val renciaRepository = RenciaRepository()
    private val xtreamRepository = XtreamRepository()
    private val retryHandler = Handler(Looper.getMainLooper())
    private val timerHandler = Handler(Looper.getMainLooper())
    private var activationInProgress = false
    private var pollingActive = false
    private var loadingStartMillis = 0L
    private var crestAnimator: ObjectAnimator? = null

    private val retryRunnable = Runnable {
        if (pollingActive) activateDevice(silent = true)
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            val elapsedSeconds = (System.currentTimeMillis() - loadingStartMillis) / 1000
            binding.tvLoadingTimer.text = "%02d:%02d".format(elapsedSeconds / 60, elapsedSeconds % 60)
            timerHandler.postDelayed(this, TIMER_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startCrestPulse()

        val session = SessionStore.getSavedSession(this)
        val detectedMac = session?.mac?.takeIf { it.isNotBlank() } ?: MacAddressProvider.getFixedMac(this)

        binding.etMac.setText(detectedMac)
        binding.etMac.showSoftInputOnFocus = false
        binding.etMac.isCursorVisible = false
        binding.etMac.isLongClickable = false
        binding.etMac.setOnClickListener { copyMacToClipboard() }
        binding.etMac.setOnLongClickListener { copyMacToClipboard(); true }
        binding.btnLogin.setOnClickListener { activateDevice(silent = false) }
    }

    /** Pulsar suave no brasão -- só pra tela não parecer estática enquanto
     * o usuário espera a ativação. */
    private fun startCrestPulse() {
        crestAnimator?.cancel()
        crestAnimator = ObjectAnimator.ofFloat(binding.ivLogo, "scaleX", 1f, 1.06f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        ObjectAnimator.ofFloat(binding.ivLogo, "scaleY", 1f, 1.06f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    override fun onStart() {
        super.onStart()
        pollingActive = true
        activateDevice(silent = true)
    }

    override fun onStop() {
        pollingActive = false
        retryHandler.removeCallbacks(retryRunnable)
        timerHandler.removeCallbacks(timerRunnable)
        super.onStop()
    }

    override fun onDestroy() {
        crestAnimator?.cancel()
        super.onDestroy()
    }

    private fun copyMacToClipboard() {
        val mac = binding.etMac.text?.toString().orEmpty()
        if (mac.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MAC", mac))
        Toast.makeText(this, "MAC copiado", Toast.LENGTH_SHORT).show()
    }

    private fun activateDevice(silent: Boolean) {
        if (activationInProgress) return
        val mac = binding.etMac.text?.toString().orEmpty().trim()
        if (renciaRepository.normalizeMac(mac) == null) {
            showStatus("MAC inválido. O aparelho deve exibir 12 dígitos hexadecimais.", isError = true)
            return
        }
        activationInProgress = true
        setLoading(true, silent)
        if (!silent) {
            showStatus("Verificando autorização no painel...", isError = false)
        }
        lifecycleScope.launch {
            renciaRepository.authenticateByMac(mac)
                .onSuccess { session ->
                    pollingActive = false
                    retryHandler.removeCallbacks(retryRunnable)
                    SessionStore.saveSession(this@LoginActivity, session)
                    RemoteLayoutTheme.save(this@LoginActivity, session.layoutId)
                    preloadContentThenContinue(session)
                }
                .onFailure { error ->
                    activationInProgress = false
                    setLoading(false, silent)
                    val message = error.message ?: "Não foi possível ativar o aparelho."
                    showStatus(
                        if (silent) "Aguardando liberação do MAC no painel... ($message)" else message,
                        isError = !silent
                    )
                    if (!silent) Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show()
                    if (pollingActive) {
                        retryHandler.removeCallbacks(retryRunnable)
                        retryHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
                    }
                }
        }
    }

    /** MAC ativado -- antes de ir pra Home, baixa a lista completa aqui
     * mesmo (com barra de progresso de verdade quando é uma playlist M3U,
     * já que dá pra medir bytes baixados/total; senão, um progresso
     * simulado enquanto confere as categorias pela API). */
    private fun preloadContentThenContinue(session: Session) {
        binding.macSection.visibility = View.GONE
        binding.loadingSection.visibility = View.VISIBLE
        loadingStartMillis = System.currentTimeMillis()
        timerHandler.removeCallbacks(timerRunnable)
        timerHandler.post(timerRunnable)
        updateProgress(0)

        lifecycleScope.launch {
            if (!session.playlistUrl.isNullOrBlank()) {
                binding.tvLoadingStatus.text = "Baixando lista completa…"
                xtreamRepository.preloadPlaylistWithProgress(session) { bytesRead, totalBytes ->
                    val percent = if (totalBytes > 0) {
                        ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        // Servidor não informou o tamanho total -- sobe
                        // devagar até 90% enquanto ainda está lendo, e só
                        // fecha em 100% quando terminar de verdade.
                        (bytesRead / 5000L).toInt().coerceIn(0, 90)
                    }
                    runOnUiThread { updateProgress(percent) }
                }
            } else {
                binding.tvLoadingStatus.text = "Carregando categorias…"
                updateProgress(20)
                xtreamRepository.getLiveCategories(session)
                updateProgress(60)
                xtreamRepository.getVodCategories(session)
                updateProgress(90)
            }
            updateProgress(100)
            binding.tvLoadingStatus.text = "Pronto!"
            timerHandler.removeCallbacks(timerRunnable)
            startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
            finish()
        }
    }

    private fun updateProgress(percent: Int) {
        binding.tvLoadingPercent.text = "$percent%"
        binding.progressLoading.progress = percent
    }

    private fun setLoading(loading: Boolean, silent: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.etMac.isEnabled = !loading
        if (!silent) binding.tvError.visibility = View.GONE
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
        binding.tvError.setTextColor(
            getColor(if (isError) android.R.color.holo_red_light else com.meuapp.iptvplayer.R.color.text_secondary)
        )
    }
}
