package com.meuapp.iptvplayer.ui.multi

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.meuapp.iptvplayer.data.api.Session
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.databinding.ActivityMultiScreenBinding
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class MultiScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMultiScreenBinding
    private val repository by lazy { XtreamRepository(this) }
    private val players = arrayOfNulls<ExoPlayer>(4)
    private lateinit var playerViews: Array<PlayerView>
    private lateinit var nameLabels: Array<TextView>
    private var session: Session? = null
    private var channels: List<LiveStream> = emptyList()
    private val selectedChannels = arrayOfNulls<LiveStream>(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMultiScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionStore.getSavedSession(this)
        if (session == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        playerViews = arrayOf(binding.playerOne, binding.playerTwo, binding.playerThree, binding.playerFour)
        nameLabels = arrayOf(binding.tvOneName, binding.tvTwoName, binding.tvThreeName, binding.tvFourName)
        playerViews.forEachIndexed { index, view ->
            view.setOnClickListener { showChannelPicker(index) }
            nameLabels[index].setOnClickListener { showChannelPicker(index) }
        }
        loadChannels()
    }

    private fun loadChannels() {
        val currentSession = session ?: return
        lifecycleScope.launch {
            repository.getLiveStreams(currentSession, null)
                .onSuccess { loaded ->
                    channels = loaded
                    if (channels.isEmpty()) {
                        Toast.makeText(this@MultiScreenActivity, "Nenhum canal disponível", Toast.LENGTH_LONG).show()
                        return@onSuccess
                    }
                    channels.take(4).forEachIndexed { index, channel ->
                        selectedChannels[index] = channel
                        bindChannel(index, channel)
                    }
                    for (index in channels.size.coerceAtMost(4) until 4) {
                        nameLabels[index].text = "Toque para escolher um canal"
                    }
                }
                .onFailure {
                    Toast.makeText(
                        this@MultiScreenActivity,
                        "Não foi possível carregar os canais",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun bindChannel(index: Int, channel: LiveStream) {
        val currentSession = session ?: return
        players[index]?.release()
        players[index] = null
        nameLabels[index].text = channel.name

        val player = ExoPlayer.Builder(this).build()
        players[index] = player
        playerViews[index].player = player
        player.setMediaItem(MediaItem.fromUri(repository.buildLiveStreamUrl(currentSession, channel.streamId)))
        player.prepare()
        player.playWhenReady = true
    }

    private fun showChannelPicker(index: Int) {
        if (channels.isEmpty()) return
        val labels = channels.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Canal da tela ${index + 1}")
            .setItems(labels) { _, selected ->
                channels.getOrNull(selected)?.let {
                    selectedChannels[index] = it
                    bindChannel(index, it)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onStop() {
        players.forEachIndexed { index, player ->
            player?.release()
            players[index] = null
            playerViews.getOrNull(index)?.player = null
        }
        super.onStop()
    }
}
