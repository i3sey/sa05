package com.fife.sa05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object XrayPreferences {
    private const val FILE = "xray"
    private const val KEY_CONFIG = "config"
    private const val KEY_EXCLUDED = "excluded"
    private const val KEY_SUBSCRIPTION = "subscription"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_VPN_BACKEND = "vpn_backend"
    private const val KEY_ZAPRET_PRESET = "zapret_preset"
    private const val KEY_ZAPRET_CACHE_NETWORK = "zapret_cache_network"
    private const val KEY_ZAPRET_CACHE_PRESET = "zapret_cache_preset"
    private const val KEY_ZAPRET_CACHE_SCORE = "zapret_cache_score"
    private const val KEY_ZAPRET_CACHE_VERSION = "zapret_cache_version"
    private const val KEY_ZAPRET_CUSTOM_ARGUMENTS = "zapret_custom_arguments"

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
        subscription(context).activeProfile?.json
            ?: prefs(context).getString(KEY_CONFIG, defaultConfig)
            ?: defaultConfig

    fun excludedApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_EXCLUDED, emptySet())?.toSet() ?: emptySet()

    fun saveConfig(context: Context, value: String) {
        prefs(context).edit().putString(KEY_CONFIG, value).apply()
    }

    fun saveExcludedApps(context: Context, value: Set<String>) {
        prefs(context).edit().putStringSet(KEY_EXCLUDED, value).apply()
    }

    fun dynamicColor(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DYNAMIC_COLOR, true)

    fun saveDynamicColor(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }

    fun vpnBackend(context: Context): VpnBackend =
        runCatching {
            VpnBackend.valueOf(
                prefs(context).getString(KEY_VPN_BACKEND, VpnBackend.XRAY.name)
                    ?: VpnBackend.XRAY.name
            )
        }.getOrDefault(VpnBackend.XRAY)

    fun saveVpnBackend(context: Context, backend: VpnBackend) {
        prefs(context).edit().putString(KEY_VPN_BACKEND, backend.name).apply()
        VpnRuntimeState.requestTileRefresh(context)
    }

    fun zapretPreset(context: Context): ZapretPreset =
        ZapretPreset.fromName(prefs(context).getString(KEY_ZAPRET_PRESET, null))

    fun saveZapretPreset(context: Context, preset: ZapretPreset) {
        prefs(context).edit().putString(KEY_ZAPRET_PRESET, preset.name).apply()
        VpnRuntimeState.requestTileRefresh(context)
    }

    fun zapretCustomArguments(context: Context): String =
        prefs(context).getString(KEY_ZAPRET_CUSTOM_ARGUMENTS, "").orEmpty()

    fun saveZapretCustomArguments(context: Context, value: String) {
        ZapretArguments.parse(value)
        prefs(context).edit().putString(KEY_ZAPRET_CUSTOM_ARGUMENTS, value.trim()).apply()
    }

    fun zapretAutoCache(context: Context): ZapretAutoCache? {
        val preferences = prefs(context)
        val network = preferences.getString(KEY_ZAPRET_CACHE_NETWORK, "").orEmpty()
        if (network.isBlank()) return null
        return ZapretAutoCache(
            networkKey = network,
            preset = ZapretPreset.fromName(
                preferences.getString(KEY_ZAPRET_CACHE_PRESET, null)
            ).takeUnless { it == ZapretPreset.AUTO } ?: return null,
            reachableCount = preferences.getInt(KEY_ZAPRET_CACHE_SCORE, 0),
            algorithmVersion = preferences.getInt(KEY_ZAPRET_CACHE_VERSION, 0)
        )
    }

    fun saveZapretAutoCache(context: Context, cache: ZapretAutoCache) {
        prefs(context).edit()
            .putString(KEY_ZAPRET_CACHE_NETWORK, cache.networkKey)
            .putString(KEY_ZAPRET_CACHE_PRESET, cache.preset.name)
            .putInt(KEY_ZAPRET_CACHE_SCORE, cache.reachableCount)
            .putInt(KEY_ZAPRET_CACHE_VERSION, cache.algorithmVersion)
            .apply()
    }

    fun clearZapretAutoCache(context: Context) {
        prefs(context).edit()
            .remove(KEY_ZAPRET_CACHE_NETWORK)
            .remove(KEY_ZAPRET_CACHE_PRESET)
            .remove(KEY_ZAPRET_CACHE_SCORE)
            .remove(KEY_ZAPRET_CACHE_VERSION)
            .apply()
    }

    fun subscription(context: Context): SubscriptionState {
        val raw = prefs(context).getString(KEY_SUBSCRIPTION, null) ?: return SubscriptionState()
        return try {
            val root = JSONObject(raw)
            val profilesJson = root.optJSONArray("profiles") ?: JSONArray()
            val profiles = (0 until profilesJson.length()).mapNotNull { index ->
                profilesJson.optJSONObject(index)?.let {
                    SubscriptionProfile(
                        id = it.optString("id"),
                        remarks = it.optString("remarks"),
                        json = it.optString("json")
                    )
                }
            }
            val bypassJson = root.optJSONArray("suggestedBypassApps") ?: JSONArray()
            SubscriptionState(
                url = root.optString("url"),
                title = root.optString("title"),
                profiles = profiles,
                activeProfileId = root.optString("activeProfileId"),
                updatedAt = root.optLong("updatedAt"),
                etag = root.optString("etag"),
                userInfo = root.optString("userInfo"),
                updateIntervalHours = root.optInt("updateIntervalHours", -1)
                    .takeIf { it >= 0 },
                suggestedBypassApps = (0 until bypassJson.length())
                    .mapNotNull { bypassJson.optString(it).takeIf(String::isNotBlank) }
                    .toSet()
            )
        } catch (_: Exception) {
            SubscriptionState()
        }
    }

    fun saveSubscription(context: Context, state: SubscriptionState) {
        val profiles = JSONArray()
        state.profiles.forEach {
            profiles.put(
                JSONObject()
                    .put("id", it.id)
                    .put("remarks", it.remarks)
                    .put("json", it.json)
            )
        }
        val bypass = JSONArray()
        state.suggestedBypassApps.sorted().forEach(bypass::put)
        val root = JSONObject()
            .put("url", state.url)
            .put("title", state.title)
            .put("profiles", profiles)
            .put("activeProfileId", state.activeProfileId)
            .put("updatedAt", state.updatedAt)
            .put("etag", state.etag)
            .put("userInfo", state.userInfo)
            .put("updateIntervalHours", state.updateIntervalHours ?: -1)
            .put("suggestedBypassApps", bypass)
        check(prefs(context).edit().putString(KEY_SUBSCRIPTION, root.toString()).commit()) {
            "Не удалось сохранить подписку"
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
