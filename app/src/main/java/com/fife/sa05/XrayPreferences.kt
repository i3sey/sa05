package com.fife.sa05

import android.content.Context

object XrayPreferences {
    private const val FILE = "xray"
    private const val KEY_CONFIG = "config"
    private const val KEY_EXCLUDED = "excluded"

    private val defaultConfig = """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [
            {
              "tag": "socks",
              "listen": "127.0.0.1",
              "port": 10808,
              "protocol": "socks",
              "settings": { "udp": true, "auth": "noauth" },
              "sniffing": {
                "enabled": true,
                "destOverride": ["http", "tls", "quic"]
              }
            }
          ],
          "outbounds": [
            { "tag": "direct", "protocol": "freedom" },
            { "tag": "block", "protocol": "blackhole" }
          ]
        }
    """.trimIndent()

    fun config(context: Context): String =
        prefs(context).getString(KEY_CONFIG, defaultConfig) ?: defaultConfig

    fun excludedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXCLUDED, emptySet())?.toSet() ?: emptySet()

    fun saveConfig(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CONFIG, value).apply()
    }

    fun saveExcludedApps(context: Context, value: Set<String>) {
        prefs(context).edit().putStringSet(KEY_EXCLUDED, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
