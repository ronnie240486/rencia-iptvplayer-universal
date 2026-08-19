package com.meuapp.iptvplayer.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.meuapp.iptvplayer.data.model.EpgListing
import com.meuapp.iptvplayer.ui.epg.EpgTime
import com.meuapp.iptvplayer.ui.epg.EpgReminderReceiver
import java.util.Locale

object ReminderScheduler {
    const val EXTRA_STREAM_ID = "extra_reminder_stream_id"
    const val EXTRA_CHANNEL_NAME = "extra_reminder_channel_name"
    const val EXTRA_STREAM_URL = "extra_reminder_stream_url"
    const val EXTRA_PROGRAM_TITLE = "extra_reminder_program_title"
    const val EXTRA_PROGRAM_START = "extra_reminder_program_start"
    const val EXTRA_PROGRAM_END = "extra_reminder_program_end"

    fun schedule(
        context: Context,
        streamId: Int,
        channelName: String,
        streamUrl: String,
        listing: EpgListing
    ): Boolean {
        val triggerAt = EpgTime.millis(listing.start) ?: return false
        if (triggerAt <= System.currentTimeMillis()) return false

        val intent = Intent(context, EpgReminderReceiver::class.java).apply {
            putExtra(EXTRA_STREAM_ID, streamId)
            putExtra(EXTRA_CHANNEL_NAME, channelName)
            putExtra(EXTRA_STREAM_URL, streamUrl)
            putExtra(EXTRA_PROGRAM_TITLE, listing.titleBase64)
            putExtra(EXTRA_PROGRAM_START, listing.start)
            putExtra(EXTRA_PROGRAM_END, listing.end)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(streamId, listing),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
        ReminderStore.setScheduled(context, streamId, listing, true)
        return true
    }

    fun cancel(context: Context, streamId: Int, listing: EpgListing) {
        val intent = Intent(context, EpgReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode(streamId, listing),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSystemService(AlarmManager::class.java).cancel(pendingIntent)
        pendingIntent.cancel()
        ReminderStore.setScheduled(context, streamId, listing, false)
    }

    private fun requestCode(streamId: Int, listing: EpgListing): Int =
        String.format(Locale.ROOT, "%d_%s_%s", streamId, listing.id, listing.start).hashCode()
}
