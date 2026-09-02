package com.meuapp.iptvplayer.ui.player

import android.os.Bundle
import android.util.Base64
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.databinding.ActivityPlayerBinding
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_STREAM_ID = "extra_stream_id"
        const val EXTRA_EPG_CHANNEL_ID = "extra_epg_channel_id"
    }

    private lateinit var binding: ActivityPlayerBinding
    private val repository = XtreamRepository()
    private var player: ExoPlayer? = null
    private var streamUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL).orEmpty()
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME).orEmpty()
        if (streamUrl.isBlank()) {
            finish()
            return
        }
        binding.tvChannelName.text = channelName
        binding.btnRetryPlayer.setOnClickListener { initPlayer(streamUrl) }

        initPlayer(streamUrl)
        loadNowPlaying()
    }

    /** Mostra o programa que está passando agora nesse canal, num rótulo
     * no canto da tela cheia -- igual a faixa de programação já mostrada
     * no mini player, mas resumida (só "agora"). */
    private fun loadNowPlaying() {
        val session = SessionStore.getSavedSession(this) ?: return
        val streamId = intent.getIntExtra(EXTRA_STREAM_ID, 0)
        val epgChannelId = intent.getStringExtra(EXTRA_EPG_CHANNEL_ID)
        lifecycleScope.launch {
            val title = if (streamId != 0) {
                repository.getShortEpg(session, streamId).getOrNull()
                    ?.listings?.firstOrNull()?.let { decodeTitle(it.titleValue()) }
            } else {
                repository.getEpgFromPlaylist(session, epgChannelId).getOrNull()
                    ?.firstOrNull()?.title
            }
            if (!title.isNullOrBlank()) {
                binding.tvNowPlaying.text = "Agora: $title"
                binding.tvNowPlaying.visibility = View.VISIBLE
            }
        }
    }

    private fun decodeTitle(value: String): String = runCatching {
        val decoded = String(Base64.decode(value.trim(), Base64.DEFAULT), Charsets.UTF_8).trim()
        decoded.ifBlank { value }
    }.getOrDefault(value)

    private fun initPlayer(url: String) {
        player?.release()
        binding.tvPlaybackError.visibility = View.GONE
        binding.btnRetryPlayer.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        exoPlayer.setAudioAttributes(
            AudioAttributes.Builder().setUsage(1).setContentType(3).build(),
            true
        )
        exoPlayer.setHandleAudioBecomingNoisy(true)
        binding.playerView.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvPlaybackError.visibility = View.GONE
                    }
                    Player.STATE_READY -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvPlaybackError.visibility = View.GONE
                        binding.btnRetryPlayer.visibility = View.GONE
                    }
                    Player.STATE_ENDED -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvPlaybackError.text = "Transmissão encerrada"
                        binding.tvPlaybackError.visibility = View.VISIBLE
                        binding.btnRetryPlayer.visibility = View.VISIBLE
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.progressBar.visibility = View.GONE
                binding.tvPlaybackError.text = "Não foi possível reproduzir este canal"
                binding.tvPlaybackError.visibility = View.VISIBLE
                binding.btnRetryPlayer.visibility = View.VISIBLE
            }
        })

        exoPlayer.setMediaItem(MediaItem.fromUri(url))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
