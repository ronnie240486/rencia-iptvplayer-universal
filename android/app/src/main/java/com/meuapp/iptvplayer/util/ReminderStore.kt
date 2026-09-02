package com.meuapp.iptvplayer.util

import android.content.Context
import com.meuapp.iptvplayer.data.model.EpgListing

object ReminderStore {
    private const val PREFS = "supremus_epg_reminders"

    fun key(streamId: Int, listing: EpgListing): String =
        "${streamId}_${listing.id}_${listing.startValue()}"

    fun isScheduled(context: Context, streamId: Int, listing: EpgListing): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(streamId, listing), false)

    fun setScheduled(context: Context, streamId: Int, listing: EpgListing, scheduled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(streamId, listing), scheduled)
            .apply()
    }
}
