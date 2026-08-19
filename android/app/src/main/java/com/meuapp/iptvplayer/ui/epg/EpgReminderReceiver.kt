package com.meuapp.iptvplayer.ui.epg

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.meuapp.iptvplayer.R
import com.meuapp.iptvplayer.util.ReminderScheduler

class EpgReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(ReminderScheduler.EXTRA_PROGRAM_TITLE).orEmpty()
        val channel = intent.getStringExtra(ReminderScheduler.EXTRA_CHANNEL_NAME).orEmpty()
        val openIntent = Intent(context, EpgReminderActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtras(intent)
        }
        runCatching { context.startActivity(openIntent) }

        val pending = PendingIntent.getActivity(
            context,
            (channel + title).hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, EpgReminderActivity.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Vai começar agora")
            .setContentText("${channel}: ${EpgReminderActivity.decodeTitle(title)}")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify((channel + title).hashCode(), notification)
        }
    }
}
