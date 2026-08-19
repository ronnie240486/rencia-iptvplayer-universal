package com.meuapp.iptvplayer.ui.home

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.meuapp.iptvplayer.data.api.RenciaRepository
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

class HomeActivity : AppCompatActivity() {
    companion object { private const val ACCESS_CHECK_INTERVAL_MS = 5 * 60 * 1000L }

    private val renciaRepository = RenciaRepository()
    private val accessHandler = Handler(Looper.getMainLooper())
    private val accessCheckRunnable = object : Runnable {
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
        if (session == null || session.clientLogin.isNullOrBlank() || session.clientPassword.isNullOrBlank()) {
            SessionStore.clear(this)
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        RemoteLayoutTheme.save(this, session.layoutId)
        setContentView(RemoteDashboardFactory.create(this, session.layoutId, ::dispatchAction))
    }

    override fun onStart() {
        super.onStart()
        verifyAccessNow()
        accessHandler.removeCallbacks(accessCheckRunnable)
        accessHandler.postDelayed(accessCheckRunnable, ACCESS_CHECK_INTERVAL_MS)
    }

    override fun onStop() {
        accessHandler.removeCallbacks(accessCheckRunnable)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun verifyAccessNow() {
        val session = SessionStore.getSavedSession(this) ?: return
        val login = session.clientLogin
        val password = session.clientPassword
        if (login.isNullOrBlank() || password.isNullOrBlank()) return
        lifecycleScope.launch {
            renciaRepository.verifyCustomerAccess(login, password)
                .onSuccess { refreshed ->
                    val changedLayout = refreshed.layoutId != session.layoutId
                    SessionStore.saveSession(this@HomeActivity, refreshed)
                    RemoteLayoutTheme.save(this@HomeActivity, refreshed.layoutId)
                    if (changedLayout) recreate()
                }
                .onFailure {
                    SessionStore.clear(this@HomeActivity)
                    Toast.makeText(this@HomeActivity, "Acesso indisponível para esta conta", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@HomeActivity, LoginActivity::class.java))
                    finish()
                }
        }
    }

    private fun dispatchAction(action: String) {
        when (action) {
            RemoteDashboardFactory.LIVE -> open(ChannelListActivity::class.java)
            RemoteDashboardFactory.EPG -> startActivity(Intent(this, ChannelListActivity::class.java).apply {
                putExtra(ChannelListActivity.EXTRA_OPEN_EPG, true)
            })
            RemoteDashboardFactory.VOD -> open(VodActivity::class.java)
            RemoteDashboardFactory.SERIES -> open(SeriesActivity::class.java)
            RemoteDashboardFactory.ACCOUNT -> open(AccountActivity::class.java)
            RemoteDashboardFactory.MULTI -> open(MultiScreenActivity::class.java)
            RemoteDashboardFactory.FAVORITES -> open(FavoritesActivity::class.java)
            RemoteDashboardFactory.RADIO -> open(RadioActivity::class.java)
            RemoteDashboardFactory.SETTINGS -> open(SettingsActivity::class.java)
        }
    }

    private fun open(activity: Class<*>) = startActivity(Intent(this, activity))

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
