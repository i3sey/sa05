package com.fife.sa05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

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
    private const val KEY_ZAPRET_CACHE_MAP = "zapret_cache_map"
    private const val KEY_YOUTUBE_CACHE_NETWORK = "youtube_cache_network"
    private const val KEY_YOUTUBE_CACHE_PRESET = "youtube_cache_preset"
    private const val KEY_YOUTUBE_CACHE_VERSION = "youtube_cache_version"
    private const val KEY_YOUTUBE_CACHE_MAP = "youtube_cache_map"
    private const val KEY_ZAPRET_CUSTOM_ARGUMENTS = "zapret_custom_arguments"
    private const val KEY_TELEGRAM_CF_ENABLED = "telegram_cf_enabled"
    private const val KEY_TELEGRAM_CF_DOMAIN = "telegram_cf_domain"
    private const val KEY_TELEGRAM_SECRET = "telegram_secret"
    private const val KEY_TELEGRAM_APPLIED = "telegram_applied"
    private const val MAX_NETWORK_CACHE_ENTRIES = 16

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

    fun vpnBackend(context: Context): VpnBackend {
        val preferences = prefs(context)
        val stored = preferences.getString(KEY_VPN_BACKEND, null)
        val backend = VpnBackend.fromStoredName(stored)
        if (stored != backend.name) {
            preferences.edit().putString(KEY_VPN_BACKEND, backend.name).apply()
        }
        return backend
    }

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

    fun telegramCfEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TELEGRAM_CF_ENABLED, true)

    fun saveTelegramCfEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TELEGRAM_CF_ENABLED, enabled).apply()
    }

    fun telegramCfDomain(context: Context): String =
        prefs(context).getString(KEY_TELEGRAM_CF_DOMAIN, "").orEmpty()

    fun saveTelegramCfDomain(context: Context, domain: String) {
        prefs(context).edit().putString(KEY_TELEGRAM_CF_DOMAIN, domain.trim()).apply()
    }

    fun telegramSecret(context: Context): String {
        val current = prefs(context).getString(KEY_TELEGRAM_SECRET, "").orEmpty()
        if (TelegramProxyConfig.isValidSecret(current)) return current
        return TelegramProxyConfig.generateSecret().also {
            prefs(context).edit().putString(KEY_TELEGRAM_SECRET, it).commit()
        }
    }

    fun telegramProxyApplied(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TELEGRAM_APPLIED, false)

    fun markTelegramProxyApplied(context: Context) {
        prefs(context).edit().putBoolean(KEY_TELEGRAM_APPLIED, true).apply()
    }

    fun zapretAutoCache(context: Context): ZapretAutoCache? {
        return readZapretAutoCaches(context).lastOrNull()
    }

    fun zapretAutoCache(context: Context, networkKey: String): ZapretAutoCache? {
        return readZapretAutoCaches(context).lastOrNull { it.networkKey == networkKey }
    }

    fun saveZapretAutoCache(context: Context, cache: ZapretAutoCache) {
        val caches = upsertCache(readZapretAutoCaches(context), cache)
        prefs(context).edit()
            .putString(KEY_ZAPRET_CACHE_MAP, encodeAutoCacheMap(caches))
            .remove(KEY_ZAPRET_CACHE_NETWORK)
            .remove(KEY_ZAPRET_CACHE_PRESET)
            .remove(KEY_ZAPRET_CACHE_SCORE)
            .remove(KEY_ZAPRET_CACHE_VERSION)
            .apply()
    }

    fun clearZapretAutoCache(context: Context) {
        prefs(context).edit()
            .remove(KEY_ZAPRET_CACHE_MAP)
            .remove(KEY_ZAPRET_CACHE_NETWORK)
            .remove(KEY_ZAPRET_CACHE_PRESET)
            .remove(KEY_ZAPRET_CACHE_SCORE)
            .remove(KEY_ZAPRET_CACHE_VERSION)
            .apply()
    }

    fun youtubeAutoCache(context: Context): ZapretAutoCache? {
        return readYoutubeAutoCaches(context).lastOrNull()
    }

    fun youtubeAutoCache(context: Context, networkKey: String): ZapretAutoCache? {
        return readYoutubeAutoCaches(context).lastOrNull { it.networkKey == networkKey }
    }

    fun saveYoutubeAutoCache(context: Context, cache: ZapretAutoCache) {
        val caches = upsertCache(readYoutubeAutoCaches(context), cache)
        prefs(context).edit()
            .putString(KEY_YOUTUBE_CACHE_MAP, encodeAutoCacheMap(caches))
            .remove(KEY_YOUTUBE_CACHE_NETWORK)
            .remove(KEY_YOUTUBE_CACHE_PRESET)
            .remove(KEY_YOUTUBE_CACHE_VERSION)
            .apply()
    }

    fun clearYoutubeAutoCache(context: Context) {
        prefs(context).edit()
            .remove(KEY_YOUTUBE_CACHE_MAP)
            .remove(KEY_YOUTUBE_CACHE_NETWORK)
            .remove(KEY_YOUTUBE_CACHE_PRESET)
            .remove(KEY_YOUTUBE_CACHE_VERSION)
            .apply()
    }

    private fun readZapretAutoCaches(context: Context): List<ZapretAutoCache> {
        val preferences = prefs(context)
        val caches = decodeAutoCacheMap(preferences.getString(KEY_ZAPRET_CACHE_MAP, null))
            .toMutableList()
        legacyZapretAutoCache(context)?.let { legacy ->
            if (caches.none { it.networkKey == legacy.networkKey }) caches += legacy
            preferences.edit()
                .putString(KEY_ZAPRET_CACHE_MAP, encodeAutoCacheMap(caches))
                .remove(KEY_ZAPRET_CACHE_NETWORK)
                .remove(KEY_ZAPRET_CACHE_PRESET)
                .remove(KEY_ZAPRET_CACHE_SCORE)
                .remove(KEY_ZAPRET_CACHE_VERSION)
                .apply()
        }
        return caches
    }

    private fun readYoutubeAutoCaches(context: Context): List<ZapretAutoCache> {
        val preferences = prefs(context)
        val caches = decodeAutoCacheMap(preferences.getString(KEY_YOUTUBE_CACHE_MAP, null))
            .toMutableList()
        legacyYoutubeAutoCache(context)?.let { legacy ->
            if (caches.none { it.networkKey == legacy.networkKey }) caches += legacy
            preferences.edit()
                .putString(KEY_YOUTUBE_CACHE_MAP, encodeAutoCacheMap(caches))
                .remove(KEY_YOUTUBE_CACHE_NETWORK)
                .remove(KEY_YOUTUBE_CACHE_PRESET)
                .remove(KEY_YOUTUBE_CACHE_VERSION)
                .apply()
        }
        return caches
    }

    private fun legacyZapretAutoCache(context: Context): ZapretAutoCache? {
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

    private fun legacyYoutubeAutoCache(context: Context): ZapretAutoCache? {
        val preferences = prefs(context)
        val network = preferences.getString(KEY_YOUTUBE_CACHE_NETWORK, "").orEmpty()
        if (network.isBlank()) return null
        return ZapretAutoCache(
            networkKey = network,
            preset = ZapretPreset.fromName(
                preferences.getString(KEY_YOUTUBE_CACHE_PRESET, null)
            ).takeUnless { it == ZapretPreset.AUTO } ?: return null,
            reachableCount = 1,
            algorithmVersion = preferences.getInt(KEY_YOUTUBE_CACHE_VERSION, 0)
        )
    }

    internal fun upsertCache(
        caches: List<ZapretAutoCache>,
        cache: ZapretAutoCache,
        maxEntries: Int = MAX_NETWORK_CACHE_ENTRIES
    ): List<ZapretAutoCache> {
        require(maxEntries > 0)
        return (caches.filter { it.networkKey != cache.networkKey } + cache)
            .takeLast(maxEntries)
    }

    internal fun decodeAutoCacheMap(
        raw: String?,
        maxEntries: Int = MAX_NETWORK_CACHE_ENTRIES
    ): List<ZapretAutoCache> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(raw)
            root.keys().asSequence().mapNotNull { network ->
                val entry = root.optJSONObject(network) ?: return@mapNotNull null
                entry.optInt("order", 0) to ZapretAutoCache(
                    networkKey = network,
                    preset = ZapretPreset.fromName(
                        entry.optString("preset")
                    ).takeUnless { it == ZapretPreset.AUTO } ?: return@mapNotNull null,
                    reachableCount = entry.optInt("reachableCount", 0),
                    algorithmVersion = entry.optInt("algorithmVersion", 0)
                )
            }.sortedBy { it.first }.map { it.second }.toList().takeLast(maxEntries)
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun encodeAutoCacheMap(
        caches: List<ZapretAutoCache>,
        maxEntries: Int = MAX_NETWORK_CACHE_ENTRIES
    ): String {
        val root = JSONObject()
        upsertCache(caches, caches.lastOrNull() ?: return root.toString(), maxEntries)
            .forEachIndexed { index, cache ->
                if (cache.networkKey.isBlank() || cache.preset == ZapretPreset.AUTO) {
                    return@forEachIndexed
                }
                root.put(
                    cache.networkKey,
                    JSONObject()
                        .put("preset", cache.preset.name)
                        .put("reachableCount", cache.reachableCount)
                        .put("algorithmVersion", cache.algorithmVersion)
                        .put("order", index)
                )
            }
        return root.toString()
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

object TelegramProxyConfig {
    const val PORT = 1443
    const val POOL_SIZE = 4

    fun isValidSecret(value: String): Boolean =
        value.length == 32 && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

    fun generateSecret(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun proxyUri(secret: String, webFallback: Boolean = false): String {
        require(isValidSecret(secret)) { "Некорректный секрет Telegram Proxy" }
        val base = if (webFallback) "https://t.me/proxy" else "tg://proxy"
        return "$base?server=127.0.0.1&port=$PORT&secret=dd$secret"
    }
}
