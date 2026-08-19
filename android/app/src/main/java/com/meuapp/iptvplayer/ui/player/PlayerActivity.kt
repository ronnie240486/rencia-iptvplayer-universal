package com.meuapp.iptvplayer.ui.player

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: return finish()
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        binding.tvChannelName.text = channelName

        initPlayer(streamUrl)
    }

    private fun initPlayer(streamUrl: String) {
        binding.progressBar.visibility = View.VISIBLE

        val exoPlayer = ExoPlayer.Builder(this).build()
        player = exoPlayer
        binding.playerView.player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                binding.progressBar.visibility = View.GONE
                // TODO: mostrar mensagem de erro amigável / tentar reconectar
            }
        })

        val mediaItem = MediaItem.fromUri(streamUrl)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
