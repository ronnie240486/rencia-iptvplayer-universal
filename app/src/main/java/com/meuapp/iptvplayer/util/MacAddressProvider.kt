package com.meuapp.iptvplayer.util

import android.content.Context
import android.net.wifi.WifiManager
import android.provider.Settings
import java.net.NetworkInterface
import java.security.MessageDigest

object MacAddressProvider {

    fun getFixedMac(context: Context): String {
        getWifiMac(context)?.let { return it }
        return createStableMac(context)
    }

    private fun getWifiMac(context: Context): String? {
        val wifiMac = runCatching {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.macAddress
        }.getOrNull()
        normalize(wifiMac)?.let { return it }

        val names = listOf("wlan0", "wifi0", "eth0")
        for (name in names) {
            val candidate = runCatching {
                NetworkInterface.getByName(name)?.hardwareAddress
                    ?.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
            }.getOrNull()
            normalize(candidate)?.let { return it }
        }

        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.mapNotNull { networkInterface ->
                    networkInterface.hardwareAddress
                        ?.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
                }
                ?.mapNotNull(::normalize)
                ?.firstOrNull()
        }.getOrNull()
    }

    private fun createStableMac(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty().ifBlank { "supremus-device" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(androidId.toByteArray(Charsets.UTF_8))
            .copyOf(6)

        // MAC local/unicast: não conflita com endereços físicos de fábrica.
        digest[0] = ((digest[0].toInt() and 0xFC) or 0x02).toByte()
        return digest.joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    private fun normalize(raw: String?): String? {
        val compact = raw.orEmpty().filter { it.isLetterOrDigit() }.uppercase()
        if (compact.length != 12 || compact.any { it !in "0123456789ABCDEF" }) return null
        if (compact == "020000000000" || compact == "000000000000") return null
        return compact.chunked(2).joinToString(":")
    }
}
