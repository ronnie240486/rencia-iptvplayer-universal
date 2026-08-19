package com.meuapp.iptvplayer.ui.login

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.RenciaRepository
import com.meuapp.iptvplayer.databinding.ActivityLoginBinding
import com.meuapp.iptvplayer.ui.home.HomeActivity
import com.meuapp.iptvplayer.util.MacAddressProvider
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val renciaRepository = RenciaRepository()
    private var mac: String? = null
    private var formattingMac = false
    private var activationInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val savedMac = SessionStore.getSavedSession(this)?.mac
        val detectedMac = savedMac ?: MacAddressProvider.getFixedMac(this)
        binding.etMac.setText(detectedMac)

        binding.etMac.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!formattingMac) formatMacInput(s?.toString().orEmpty())
            }
        })

        formatMacInput(binding.etMac.text?.toString().orEmpty())
        binding.etMac.setOnClickListener {
            if (mac != null) copyMacToClipboard() else binding.etMac.requestFocus()
        }
        binding.etMac.setOnLongClickListener {
            binding.etMac.showSoftInputOnFocus = true
            binding.etMac.isCursorVisible = true
            false
        }
        binding.btnLogin.setOnClickListener { activateDevice() }

        // Se o MAC já estiver preenchido, valida automaticamente ao abrir o app.
        binding.etMac.post { activateDevice() }
    }

    private fun formatMacInput(raw: String) {
        val compact = raw.filter { it.isLetterOrDigit() }
            .uppercase()
            .take(12)
        val formatted = compact.chunked(2).joinToString(":")
        mac = renciaRepository.normalizeMac(formatted)
        binding.etMac.showSoftInputOnFocus = mac == null

        if (formatted != raw) {
            formattingMac = true
            binding.etMac.setText(formatted)
            binding.etMac.setSelection(formatted.length)
            formattingMac = false
        }
    }

    private fun copyMacToClipboard() {
        val value = mac ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("MAC", value))
        Toast.makeText(this, R.string.mac_copied, Toast.LENGTH_SHORT).show()
    }

    private fun activateDevice() {
        if (activationInProgress) return
        val value = mac
        if (value == null) {
            showError("Digite o MAC no formato AA:BB:CC:DD:EE:FF")
            return
        }

        activationInProgress = true
        setLoading(true)
        lifecycleScope.launch {
            renciaRepository.authenticateMac(value)
                .onSuccess { session ->
                    SessionStore.saveSession(this@LoginActivity, session)
                    startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                    finish()
                }
                .onFailure { error ->
                    activationInProgress = false
                    setLoading(false)
                    showError(error.message ?: getString(R.string.error_login))
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
