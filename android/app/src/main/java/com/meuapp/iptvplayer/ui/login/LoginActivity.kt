package com.meuapp.iptvplayer.ui.login

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.meuapp.iptvplayer.data.api.RenciaRepository
import com.meuapp.iptvplayer.databinding.ActivityLoginBinding
import com.meuapp.iptvplayer.ui.home.HomeActivity
import com.meuapp.iptvplayer.util.MacAddressProvider
import com.meuapp.iptvplayer.util.RemoteLayoutTheme
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

/** Ativação por MAC do aparelho -- não é login de usuário/senha. O MAC é
 * detectado automaticamente e a tela fica tentando ativar sozinha, em
 * segundo plano, de tempos em tempos -- assim, assim que o usuário liberar
 * o MAC no painel (em outra aba/aparelho), o app entra sozinho, sem
 * precisar tocar em nada aqui. O botão "Ativar aparelho" só força uma
 * tentativa imediata, não é obrigatório usá-lo. */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val RETRY_INTERVAL_MS = 6_000L
    }

    private lateinit var binding: ActivityLoginBinding
    private val renciaRepository = RenciaRepository()
    private val retryHandler = Handler(Looper.getMainLooper())
    private var activationInProgress = false
    private var pollingActive = false

    private val retryRunnable = Runnable {
        if (pollingActive) activateDevice(silent = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

    override fun onStart() {
        super.onStart()
        pollingActive = true
        // Primeira tentativa imediata; se não der certo, o próprio
        // activateDevice reagenda a próxima automaticamente.
        activateDevice(silent = true)
    }

    override fun onStop() {
        pollingActive = false
        retryHandler.removeCallbacks(retryRunnable)
        super.onStop()
    }

    private fun copyMacToClipboard() {
        val mac = binding.etMac.text?.toString().orEmpty()
        if (mac.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MAC", mac))
        Toast.makeText(this, "MAC copiado", Toast.LENGTH_SHORT).show()
    }

    /** @param silent quando true (tentativa automática em segundo plano),
     * não mostra Toast de erro nem trava a tela -- só atualiza o texto de
     * status e agenda a próxima tentativa. Quando false (usuário tocou no
     * botão), mostra erro na hora se falhar. */
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
                    startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                    finish()
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
                    // Continua tentando sozinho em segundo plano, mesmo que
                    // essa tentativa (manual ou automática) tenha falhado --
                    // é assim que o app "entra sozinho" quando o MAC for
                    // liberado no painel sem o usuário precisar tocar em
                    // nada.
                    if (pollingActive) {
                        retryHandler.removeCallbacks(retryRunnable)
                        retryHandler.postDelayed(retryRunnable, RETRY_INTERVAL_MS)
                    }
                }
        }
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
