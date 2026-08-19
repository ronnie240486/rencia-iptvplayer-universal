package com.meuapp.iptvplayer.util

import android.content.Context
import com.meuapp.iptvplayer.data.api.Session

object SessionStore {
    private const val PREFS = "iptv_session_prefs"
    private const val KEY_MAC = "device_mac"
    private const val KEY_SERVER = "server_url"
    private const val KEY_USER = "username"
    private const val KEY_PASS = "password"
    private const val KEY_STATUS = "access_status"
    private const val KEY_EXPIRATION = "expiration_date"
    private const val KEY_APP_NAME = "assigned_app"

    fun saveSession(context: Context, session: Session) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAC, session.mac)
            .putString(KEY_SERVER, session.serverUrl)
            .putString(KEY_USER, session.username)
            .putString(KEY_PASS, session.password)
            .putString(KEY_STATUS, session.status)
            .putString(KEY_EXPIRATION, session.expirationDate)
            .putString(KEY_APP_NAME, session.appName)
            .apply()
    }

    fun getSavedSession(context: Context): Session? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mac = prefs.getString(KEY_MAC, null) ?: return null
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val pass = prefs.getString(KEY_PASS, null) ?: return null
        return Session(
            mac = mac,
            serverUrl = server,
            username = user,
            password = pass,
            status = prefs.getString(KEY_STATUS, null),
            expirationDate = prefs.getString(KEY_EXPIRATION, null),
            appName = prefs.getString(KEY_APP_NAME, null)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
