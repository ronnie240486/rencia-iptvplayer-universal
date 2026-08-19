package com.meuapp.iptvplayer.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.RenciaRepository
import com.meuapp.iptvplayer.databinding.ActivityLoginBinding
import com.meuapp.iptvplayer.ui.home.HomeActivity
import com.meuapp.iptvplayer.util.RemoteLayoutTheme
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val renciaRepository = RenciaRepository()
    private var loginInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val session = SessionStore.getSavedSession(this)
        binding.etLogin.setText(session?.clientLogin.orEmpty())
        binding.etPassword.setText(session?.clientPassword.orEmpty())
        binding.btnLogin.setOnClickListener { authenticate() }
    }

    private fun authenticate() {
        if (loginInProgress) return
        val login = binding.etLogin.text?.toString().orEmpty().trim()
        val password = binding.etPassword.text?.toString().orEmpty()
        if (login.isBlank() || password.isBlank()) {
            showError(getString(R.string.error_credentials_required))
            return
        }
        loginInProgress = true
        setLoading(true)
        lifecycleScope.launch {
            renciaRepository.authenticateCustomer(login, password)
                .onSuccess { session ->
                    SessionStore.saveSession(this@LoginActivity, session)
                    RemoteLayoutTheme.save(this@LoginActivity, session.layoutId)
                    startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                    finish()
                }
                .onFailure { error ->
                    loginInProgress = false
                    setLoading(false)
                    showError(error.message ?: getString(R.string.error_login))
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !loading
        binding.etLogin.isEnabled = !loading
        binding.etPassword.isEnabled = !loading
        binding.tvError.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
