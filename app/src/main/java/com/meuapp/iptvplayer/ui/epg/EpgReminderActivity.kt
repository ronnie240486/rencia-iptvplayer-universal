package com.meuapp.iptvplayer.ui.epg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Base64
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.meuapp.iptvplayer.databinding.ActivityEpgReminderBinding
import com.meuapp.iptvplayer.ui.player.PlayerActivity
import com.meuapp.iptvplayer.util.ReminderScheduler

class EpgReminderActivity : AppCompatActivity() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "supremus_epg_reminders"

        fun decodeTitle(value: String): String = runCatching {
            String(Base64.decode(value, Base64.DEFAULT)).ifBlank { value }
        }.getOrDefault(value)
    }

    private lateinit var binding: ActivityEpgReminderBinding
    private var countdown: CountDownTimer? = null
    private var streamUrl: String = ""
    private var channelName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        createNotificationChannel()
        binding = ActivityEpgReminderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val encodedTitle = intent.getStringExtra(ReminderScheduler.EXTRA_PROGRAM_TITLE).orEmpty()
        val start = intent.getStringExtra(ReminderScheduler.EXTRA_PROGRAM_START).orEmpty()
        val end = intent.getStringExtra(ReminderScheduler.EXTRA_PROGRAM_END).orEmpty()
        streamUrl = intent.getStringExtra(ReminderScheduler.EXTRA_STREAM_URL).orEmpty()
        channelName = intent.getStringExtra(ReminderScheduler.EXTRA_CHANNEL_NAME).orEmpty()

        binding.tvProgramTitle.text = decodeTitle(encodedTitle)
        binding.tvChannelName.text = channelName
        binding.tvSchedule.text = "${EpgTime.format(start)} - ${EpgTime.format(end)}"
        binding.btnOpenNow.setOnClickListener { openNow() }
        binding.btnCancel.setOnClickListener { finish() }
        startCountdown()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Lembretes do EPG",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Avisos dos programas marcados com sino"
                enableVibration(true)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = object : CountDownTimer(10_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = (millisUntilFinished / 1_000L).toInt().coerceAtLeast(0)
                binding.tvClock.text = String.format("00:%02d", seconds)
                binding.progressCountdown.progress = seconds
                binding.tvCountdownHint.text = "Abrindo automaticamente em $seconds segundos"
            }

            override fun onFinish() {
                binding.tvClock.text = "00:00"
                openNow()
            }
        }.start()
    }

    private fun openNow() {
        countdown?.cancel()
        if (streamUrl.isBlank()) {
            finish()
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_STREAM_URL, streamUrl)
            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channelName)
        })
        finish()
    }

    override fun onDestroy() {
        countdown?.cancel()
        countdown = null
        super.onDestroy()
    }
}
