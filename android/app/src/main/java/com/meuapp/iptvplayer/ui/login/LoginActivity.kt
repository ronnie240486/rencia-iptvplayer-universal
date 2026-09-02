package com.meuapp.iptvplayer.ui.login

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
 * detectado automaticamente e a ativação já dispara sozinha assim que a
 * tela abre; o botão "Ativar aparelho" serve pra tentar de novo. */
class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val renciaRepository = RenciaRepository()
    private var activationInProgress = false
    private var autoActivationStarted = false

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
        binding.btnLogin.setOnClickListener { activateDevice() }

        binding.root.post {
            if (!isFinishing && !autoActivationStarted && detectedMac.isNotBlank()) {
                autoActivationStarted = true
                activateDevice()
            }
        }
    }

    private fun copyMacToClipboard() {
        val mac = binding.etMac.text?.toString().orEmpty()
        if (mac.isBlank()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MAC", mac))
        Toast.makeText(this, "MAC copiado", Toast.LENGTH_SHORT).show()
    }

    private fun activateDevice() {
        if (activationInProgress) return
        val mac = binding.etMac.text?.toString().orEmpty().trim()
        if (renciaRepository.normalizeMac(mac) == null) {
            showError("MAC inválido. O aparelho deve exibir 12 dígitos hexadecimais.")
            return
        }
        activationInProgress = true
        setLoading(true)
        lifecycleScope.launch {
            renciaRepository.authenticateByMac(mac)
                .onSuccess { session ->
                    SessionStore.saveSession(this@LoginActivity, session)
                    RemoteLayoutTheme.save(this@LoginActivity, session.layoutId)
                    startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                    finish()
                }
                .onFailure { error ->
                    activationInProgress = false
                    setLoading(false)
                    showError(error.message ?: "Não foi possível ativar o aparelho.")
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.etMac.isEnabled = !loading
        binding.tvError.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
