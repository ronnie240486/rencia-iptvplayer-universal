package com.meuapp.iptvplayer.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.meuapp.iptvplayer.data.api.Session
import com.meuapp.iptvplayer.data.api.XtreamRepository
import com.meuapp.iptvplayer.data.model.LiveStream
import com.meuapp.iptvplayer.databinding.ActivityVoiceCommandBinding
import com.meuapp.iptvplayer.ui.login.LoginActivity
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.util.SessionStore
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale

class VoiceCommandActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_RECORD_AUDIO = 5201
    }

    private lateinit var binding: ActivityVoiceCommandBinding
    private val repository by lazy { XtreamRepository(this) }
    private var speechRecognizer: SpeechRecognizer? = null
    private var session: Session? = null
    private var channels: List<LiveStream> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceCommandBinding.inflate(layoutInflater)
        setContentView(binding.root)
        session = SessionStore.getSavedSession(this)

        if (session == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding.btnTryAgain.setOnClickListener { beginListening() }
        binding.progressBar.visibility = View.VISIBLE
        loadChannelsAndListen()
    }

    private fun loadChannelsAndListen() {
        val currentSession = session ?: return
        lifecycleScope.launch {
            repository.getLiveStreams(currentSession, null)
                .onSuccess {
                    channels = it
                    beginListening()
                }
                .onFailure {
                    binding.progressBar.visibility = View.GONE
                    binding.tvStatus.text = "Não foi possível carregar os canais para o comando de voz."
                    binding.btnTryAgain.visibility = View.VISIBLE
                }
        }
    }

    private fun beginListening() {
        if (channels.isEmpty()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.text = "O reconhecimento de voz não está disponível neste aparelho."
            binding.btnTryAgain.visibility = View.VISIBLE
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->
            recognizer.setRecognitionListener(listener)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Diga o nome do canal")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
            binding.progressBar.visibility = View.VISIBLE
            binding.btnTryAgain.visibility = View.GONE
            binding.tvStatus.text = "Estou ouvindo... diga o nome do canal"
            recognizer.startListening(intent)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            binding.tvStatus.text = "Pode falar agora..."
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() {
            binding.tvStatus.text = "Procurando o canal..."
        }
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onError(error: Int) {
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.text = "Não entendi. Toque para falar novamente."
            binding.btnTryAgain.visibility = View.VISIBLE
        }
        override fun onResults(results: Bundle?) {
            binding.progressBar.visibility = View.GONE
            val phrases = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val channel = findChannel(phrases)
            if (channel == null) {
                binding.tvStatus.text = "Canal não encontrado: ${phrases.firstOrNull().orEmpty()}"
                binding.btnTryAgain.visibility = View.VISIBLE
            } else {
                openChannel(channel)
            }
        }
    }

    private fun findChannel(phrases: List<String>): LiveStream? {
        val normalizedNames = channels.map { channel -> channel to normalize(channel.name) }
        for (phrase in phrases) {
            val query = normalize(phrase)
            normalizedNames.firstOrNull { (_, name) -> name == query }?.let { return it.first }
            normalizedNames.firstOrNull { (_, name) -> name.contains(query) || query.contains(name) }?.let { return it.first }
        }
        return null
    }

    private fun normalize(value: String): String {
        val noAccents = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return noAccents
            .replace(Regex("\\b(abrir|abra|tocar|toque|canal|executar|reproduzir)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun openChannel(channel: LiveStream) {
        val currentSession = session ?: return
        Toast.makeText(this, "Abrindo ${channel.name}", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, repository.buildLiveStreamUrl(currentSession, channel.streamId))
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
        })
        finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            beginListening()
        } else {
            binding.progressBar.visibility = View.GONE
            binding.tvStatus.text = "Permissão de microfone necessária para falar o nome do canal."
            binding.btnTryAgain.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }
}
