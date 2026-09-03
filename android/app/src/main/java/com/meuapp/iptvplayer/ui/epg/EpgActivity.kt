package com.meuapp.iptvplayer.ui.epg

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.databinding.ActivityEpgBinding
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch

class EpgActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STREAM_ID = "extra_stream_id"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
    }

    private lateinit var binding: ActivityEpgBinding
    private val repository by lazy { XtreamRepository(this) }
    private val adapter = EpgAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEpgBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val streamId = intent.getIntExtra(EXTRA_STREAM_ID, -1)
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: ""
        binding.tvChannelName.text = channelName

        binding.rvEpg.layoutManager = LinearLayoutManager(this)
        binding.rvEpg.adapter = adapter

        if (streamId != -1) loadEpg(streamId)
    }

    private fun loadEpg(streamId: Int) {
        val session = SessionStore.getSavedSession(this) ?: return
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = repository.getShortEpg(session, streamId)
            binding.progressBar.visibility = View.GONE
            result.onSuccess { response ->
                adapter.submitList(response.listings ?: emptyList())
            }
        }
    }
}
