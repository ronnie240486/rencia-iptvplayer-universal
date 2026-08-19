package com.meuapp.iptvplayer.ui.home

import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.data.api.RenciaRepository
import com.meuapp.iptvplayer.databinding.ActivityHomeBinding
import com.meuapp.iptvplayer.ui.channels.ChannelListActivity
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.ui.multi.MultiScreenActivity
import com.meuapp.iptvplayer.ui.radio.RadioActivity
import com.meuapp.iptvplayer.ui.series.SeriesActivity
import com.meuapp.iptvplayer.ui.voice.VoiceCommandActivity
import com.meuapp.iptvplayer.ui.settings.AccountActivity
import com.meuapp.iptvplayer.ui.settings.FavoritesActivity
import com.meuapp.iptvplayer.ui.settings.SettingsActivity
import com.meuapp.iptvplayer.ui.vod.VodActivity
import com.meuapp.iptvplayer.util.SessionStore
import com.meuapp.iptvplayer.util.RemoteLayoutTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class HomeActivity : AppCompatActivity() {

    companion object {
        private const val ACCESS_CHECK_INTERVAL_MS = 5 * 60 * 1000L
    }

    private lateinit var binding: ActivityHomeBinding
    private val renciaRepository = RenciaRepository()
    private val accessHandler = Handler(Looper.getMainLooper())
    private val accessCheckRunnable = object : Runnable {
        override fun run() {
            verifyAccessNow()
            accessHandler.postDelayed(this, ACCESS_CHECK_INTERVAL_MS)
        }
    }
    private var lastOverlayWidth = 0
    private var lastOverlayHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()
        supportActionBar?.hide()
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (SessionStore.getSavedSession(this) == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val accent = Color.parseColor(RemoteLayoutTheme.accent(RemoteLayoutTheme.current(this)))
        binding.homeBackground.setColorFilter(accent, PorterDuff.Mode.SRC_ATOP)

        bindAction(binding.btnLiveTv) { open(ChannelListActivity::class.java) }
        bindAction(binding.btnEpg) { openEpgPicker() }
        bindAction(binding.btnVod) { open(VodActivity::class.java) }
        bindAction(binding.btnSeries) { open(SeriesActivity::class.java) }
        bindAction(binding.btnAccount) { open(AccountActivity::class.java) }
        bindAction(binding.btnMulti) { open(MultiScreenActivity::class.java) }
        bindAction(binding.btnCatchUp) { openEpgPicker() }
        bindAction(binding.btnFavorite) { open(FavoritesActivity::class.java) }
        bindAction(binding.btnRadio) { open(RadioActivity::class.java) }
        binding.btnRadio.setOnLongClickListener {
            open(VoiceCommandActivity::class.java)
            true
        }
        bindAction(binding.btnSettings) { open(SettingsActivity::class.java) }

        configureRemoteNavigation()
        binding.dashboardOverlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            positionDashboard()
        }
        binding.dashboardOverlay.post { positionDashboard() }
        binding.btnLiveTv.requestFocus()
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
                .onFailure {
                    SessionStore.clear(this@HomeActivity)
                    Toast.makeText(
                        this@HomeActivity,
                        "Acesso indisponível para esta conta",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(Intent(this@HomeActivity, LoginActivity::class.java))
                    finish()
                }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun positionDashboard() {
        val overlay = binding.dashboardOverlay
        val width = overlay.width
        val height = overlay.height
        if (width <= 0 || height <= 0) return
        if (width == lastOverlayWidth && height == lastOverlayHeight) return
        lastOverlayWidth = width
        lastOverlayHeight = height

        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            // Limites dos cartões da arte portrait 1440x2560.
            place(binding.btnLiveTv, 0.097, 0.388, 0.466, 0.522)
            place(binding.btnEpg, 0.535, 0.388, 0.902, 0.522)
            place(binding.btnVod, 0.097, 0.548, 0.466, 0.681)
            place(binding.btnSeries, 0.535, 0.548, 0.902, 0.681)
            place(binding.btnAccount, 0.098, 0.718, 0.333, 0.771)
            place(binding.btnMulti, 0.379, 0.718, 0.619, 0.771)
            place(binding.btnCatchUp, 0.666, 0.718, 0.900, 0.771)
            place(binding.btnFavorite, 0.098, 0.811, 0.333, 0.866)
            place(binding.btnRadio, 0.379, 0.811, 0.619, 0.866)
            place(binding.btnSettings, 0.666, 0.811, 0.900, 0.866)
        } else {
            // Limites dos cartões da arte landscape 2560x1440.
            place(binding.btnLiveTv, 0.156, 0.477, 0.270, 0.724)
            place(binding.btnEpg, 0.342, 0.477, 0.459, 0.724)
            place(binding.btnVod, 0.539, 0.477, 0.653, 0.724)
            place(binding.btnSeries, 0.737, 0.477, 0.851, 0.724)
            place(binding.btnAccount, 0.132, 0.785, 0.219, 0.924)
            place(binding.btnMulti, 0.260, 0.785, 0.340, 0.924)
            place(binding.btnCatchUp, 0.384, 0.785, 0.467, 0.924)
            place(binding.btnFavorite, 0.518, 0.785, 0.601, 0.924)
            place(binding.btnRadio, 0.646, 0.785, 0.732, 0.924)
            place(binding.btnSettings, 0.775, 0.785, 0.862, 0.924)
        }
    }

    private fun place(view: View, left: Double, top: Double, right: Double, bottom: Double) {
        val parent = binding.dashboardOverlay
        val params = (view.layoutParams as FrameLayout.LayoutParams).apply {
            width = ((right - left) * parent.width).roundToInt().coerceAtLeast(1)
            height = ((bottom - top) * parent.height).roundToInt().coerceAtLeast(1)
            leftMargin = (left * parent.width).roundToInt()
            topMargin = (top * parent.height).roundToInt()
        }
        view.layoutParams = params
    }

    private fun bindAction(view: View, action: () -> Unit) {
        view.setOnClickListener {
            view.requestFocus()
            action()
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_UP &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                view.performClick()
                true
            } else {
                false
            }
        }
    }

    private fun configureRemoteNavigation() {
        linkHorizontal(binding.btnLiveTv, binding.btnEpg)
        linkHorizontal(binding.btnEpg, binding.btnVod)
        linkHorizontal(binding.btnVod, binding.btnSeries)

        linkHorizontal(binding.btnAccount, binding.btnMulti)
        linkHorizontal(binding.btnMulti, binding.btnCatchUp)
        linkHorizontal(binding.btnCatchUp, binding.btnFavorite)
        linkHorizontal(binding.btnFavorite, binding.btnRadio)
        linkHorizontal(binding.btnRadio, binding.btnSettings)

        binding.btnLiveTv.setNextFocusDownId(R.id.btnAccount)
        binding.btnEpg.setNextFocusDownId(R.id.btnMulti)
        binding.btnVod.setNextFocusDownId(R.id.btnCatchUp)
        binding.btnSeries.setNextFocusDownId(R.id.btnFavorite)
        binding.btnAccount.setNextFocusUpId(R.id.btnLiveTv)
        binding.btnMulti.setNextFocusUpId(R.id.btnEpg)
        binding.btnCatchUp.setNextFocusUpId(R.id.btnVod)
        binding.btnFavorite.setNextFocusUpId(R.id.btnSeries)
        binding.btnRadio.setNextFocusUpId(R.id.btnSeries)
        binding.btnSettings.setNextFocusUpId(R.id.btnSeries)
    }

    private fun linkHorizontal(left: View, right: View) {
        left.nextFocusRightId = right.id
        right.nextFocusLeftId = left.id
    }

    private fun open(activity: Class<*>) {
        startActivity(Intent(this, activity))
    }

    private fun openEpgPicker() {
        startActivity(Intent(this, ChannelListActivity::class.java).apply {
            putExtra(ChannelListActivity.EXTRA_OPEN_EPG, true)
        })
    }

    private fun showComingSoon(feature: String) {
        Toast.makeText(this, "$feature em breve", Toast.LENGTH_SHORT).show()
    }
}
