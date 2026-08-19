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
    private const val KEY_CLIENT_LOGIN = "client_login"
    private const val KEY_CLIENT_PASSWORD = "client_password"
    private const val KEY_LAYOUT = "assigned_layout"
    private const val KEY_PLAYLIST = "playlist_url"

    fun saveSession(context: Context, session: Session) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MAC, session.mac)
            .putString(KEY_SERVER, session.serverUrl)
            .putString(KEY_USER, session.username)
            .putString(KEY_PASS, session.password)
            .putString(KEY_STATUS, session.status)
            .putString(KEY_EXPIRATION, session.expirationDate)
            .putString(KEY_APP_NAME, session.appName)
            .putString(KEY_CLIENT_LOGIN, session.clientLogin)
            .putString(KEY_CLIENT_PASSWORD, session.clientPassword)
            .putString(KEY_LAYOUT, session.layoutId)
            .putString(KEY_PLAYLIST, session.playlistUrl)
            .apply()
    }

    fun getSavedSession(context: Context): Session? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val pass = prefs.getString(KEY_PASS, null) ?: return null
        return Session(
            mac = prefs.getString(KEY_MAC, null).orEmpty(),
            serverUrl = server,
            username = user,
            password = pass,
            status = prefs.getString(KEY_STATUS, null),
            expirationDate = prefs.getString(KEY_EXPIRATION, null),
            appName = prefs.getString(KEY_APP_NAME, null),
            clientLogin = prefs.getString(KEY_CLIENT_LOGIN, null),
            clientPassword = prefs.getString(KEY_CLIENT_PASSWORD, null),
            layoutId = prefs.getString(KEY_LAYOUT, null),
            playlistUrl = prefs.getString(KEY_PLAYLIST, null)
        )
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
