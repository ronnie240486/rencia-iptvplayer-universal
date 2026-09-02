package com.meuapp.iptvplayer.ui.player

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.meuapp.iptvplayer.databinding.ActivityPlayerBinding

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
    }

    private lateinit var binding: ActivityPlayerBinding
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
    }

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
