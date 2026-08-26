package com.fife.sa05

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private const val XRAY_PREFERENCES_FILE = "xray"

private val Context.xrayDataStore: DataStore<Preferences> by preferencesDataStore(
    name = XRAY_PREFERENCES_FILE,
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
    produceMigrations = { context -> XrayPreferences.migrations(context) }
)

internal data class XraySettings(
    val config: String,
    val excludedApps: Set<String> = emptySet(),
    val subscription: SubscriptionState = SubscriptionState(),
    val dynamicColor: Boolean = true,
    val advancedModeEnabled: Boolean = false,
    val vpnBackend: VpnBackend = VpnBackend.PROXY_ONLY,
    val zapretPreset: ZapretPreset = ZapretPreset.AUTO,
    val zapretCustomArguments: String = "",
    val telegramCfEnabled: Boolean = true,
    val telegramCfDomain: String = "",
    val telegramSecret: String = "",
    val telegramProxyApplied: Boolean = false,
    val telegramProxyExplainerSeen: Boolean = false,
    val zapretAutoCaches: List<ZapretAutoCache> = emptyList(),
    val youtubeAutoCaches: List<ZapretAutoCache> = emptyList()
)

object XrayPreferences {
    private const val KEY_CONFIG = "config"
    private const val KEY_EXCLUDED = "excluded"
    private const val KEY_SUBSCRIPTION = "subscription"
    private const val KEY_DYNAMIC_COLOR = "dynamic_color"
    private const val KEY_ADVANCED_MODE = "advanced_mode"
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
    private const val KEY_TELEGRAM_EXPLAINER_SEEN = "telegram_explainer_seen"
    private const val MAX_NETWORK_CACHE_ENTRIES = 16

    internal val defaultConfig = """
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

    private val configKey = stringPreferencesKey(KEY_CONFIG)
    private val excludedKey = stringSetPreferencesKey(KEY_EXCLUDED)
    private val subscriptionKey = stringPreferencesKey(KEY_SUBSCRIPTION)
    private val dynamicColorKey = booleanPreferencesKey(KEY_DYNAMIC_COLOR)
    private val advancedModeKey = booleanPreferencesKey(KEY_ADVANCED_MODE)
    private val vpnBackendKey = stringPreferencesKey(KEY_VPN_BACKEND)
    private val zapretPresetKey = stringPreferencesKey(KEY_ZAPRET_PRESET)
    private val zapretCustomArgumentsKey = stringPreferencesKey(KEY_ZAPRET_CUSTOM_ARGUMENTS)
    private val telegramCfEnabledKey = booleanPreferencesKey(KEY_TELEGRAM_CF_ENABLED)
    private val telegramCfDomainKey = stringPreferencesKey(KEY_TELEGRAM_CF_DOMAIN)
    private val telegramSecretKey = stringPreferencesKey(KEY_TELEGRAM_SECRET)
    private val telegramAppliedKey = booleanPreferencesKey(KEY_TELEGRAM_APPLIED)
    private val telegramExplainerSeenKey = booleanPreferencesKey(KEY_TELEGRAM_EXPLAINER_SEEN)
    private val zapretCacheMapKey = stringPreferencesKey(KEY_ZAPRET_CACHE_MAP)
    private val youtubeCacheMapKey = stringPreferencesKey(KEY_YOUTUBE_CACHE_MAP)
    private val zapretCacheNetworkKey = stringPreferencesKey(KEY_ZAPRET_CACHE_NETWORK)
    private val zapretCachePresetKey = stringPreferencesKey(KEY_ZAPRET_CACHE_PRESET)
    private val zapretCacheScoreKey = intPreferencesKey(KEY_ZAPRET_CACHE_SCORE)
    private val zapretCacheVersionKey = intPreferencesKey(KEY_ZAPRET_CACHE_VERSION)
    private val youtubeCacheNetworkKey = stringPreferencesKey(KEY_YOUTUBE_CACHE_NETWORK)
    private val youtubeCachePresetKey = stringPreferencesKey(KEY_YOUTUBE_CACHE_PRESET)
    private val youtubeCacheVersionKey = intPreferencesKey(KEY_YOUTUBE_CACHE_VERSION)

    internal fun migrations(
        context: Context,
        sharedPreferencesName: String = XRAY_PREFERENCES_FILE
    ): List<DataMigration<Preferences>> = listOf(
        SharedPreferencesMigration(context, sharedPreferencesName)
    )

    internal fun decodeSettings(preferences: Preferences): XraySettings {
        var subscription = decodeSubscription(preferences[subscriptionKey]).withBsProfile()
        val storedBackend = preferences[vpnBackendKey]
        // Раньше CDN был режимом Advanced: переносим выбор на псевдо-сервер.
        if (storedBackend == "YCTUN" && subscription.profiles.any { BsProfile.isBs(it) }) {
            subscription = subscription.copy(activeProfileId = BsProfile.ID)
        }
        return XraySettings(
            config = subscription.activeProfile?.json
                ?: preferences[configKey]
                ?: defaultConfig,
            excludedApps = preferences[excludedKey].orEmpty(),
            subscription = subscription,
            dynamicColor = preferences[dynamicColorKey] ?: true,
            advancedModeEnabled = preferences[advancedModeKey] ?: false,
            vpnBackend = VpnBackend.fromStoredName(storedBackend),
            zapretPreset = ZapretPreset.fromName(preferences[zapretPresetKey]),
            zapretCustomArguments = preferences[zapretCustomArgumentsKey].orEmpty(),
            telegramCfEnabled = preferences[telegramCfEnabledKey] ?: true,
            telegramCfDomain = preferences[telegramCfDomainKey].orEmpty(),
            telegramSecret = preferences[telegramSecretKey].orEmpty(),
            telegramProxyApplied = preferences[telegramAppliedKey] ?: false,
            telegramProxyExplainerSeen = preferences[telegramExplainerSeenKey] ?: false,
            zapretAutoCaches = migratedZapretAutoCaches(preferences),
            youtubeAutoCaches = migratedYoutubeAutoCaches(preferences)
        )
    }

    internal fun settings(context: Context): Flow<XraySettings> =
        context.applicationContext.xrayDataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map(::decodeSettings)
            .flowOn(Dispatchers.Default)
            .distinctUntilChanged()

    internal suspend fun snapshot(context: Context): XraySettings = settings(context).first()

    suspend fun saveConfig(context: Context, value: String) {
        dataStore(context).edit { it[configKey] = value }
    }

    suspend fun saveExcludedApps(context: Context, value: Set<String>) {
        dataStore(context).edit { it[excludedKey] = value }
    }

    suspend fun saveDynamicColor(context: Context, enabled: Boolean) {
        dataStore(context).edit { it[dynamicColorKey] = enabled }
    }

    suspend fun saveAdvancedModeEnabled(context: Context, enabled: Boolean) {
        dataStore(context).edit { it[advancedModeKey] = enabled }
        VpnRuntimeState.requestTileRefresh(context)
    }

    suspend fun saveVpnBackend(context: Context, backend: VpnBackend) {
        dataStore(context).edit { it[vpnBackendKey] = backend.name }
        VpnRuntimeState.requestTileRefresh(context)
    }

    suspend fun saveZapretPreset(context: Context, preset: ZapretPreset) {
        dataStore(context).edit { it[zapretPresetKey] = preset.name }
        VpnRuntimeState.requestTileRefresh(context)
    }

    suspend fun saveZapretCustomArguments(context: Context, value: String) {
        ZapretArguments.parse(value)
        dataStore(context).edit { it[zapretCustomArgumentsKey] = value.trim() }
    }

    suspend fun saveTelegramCfEnabled(context: Context, enabled: Boolean) {
        dataStore(context).edit { it[telegramCfEnabledKey] = enabled }
    }

    suspend fun saveTelegramCfDomain(context: Context, domain: String) {
        dataStore(context).edit { it[telegramCfDomainKey] = domain.trim() }
    }

    suspend fun telegramSecret(context: Context): String {
        var result = ""
        dataStore(context).edit { preferences ->
            val current = preferences[telegramSecretKey].orEmpty()
            result = if (TelegramProxyConfig.isValidSecret(current)) {
                current
            } else {
                TelegramProxyConfig.generateSecret().also {
                    preferences[telegramSecretKey] = it
                }
            }
        }
        return result
    }

    suspend fun markTelegramProxyApplied(context: Context) {
        dataStore(context).edit { it[telegramAppliedKey] = true }
    }

    suspend fun markTelegramProxyExplainerSeen(context: Context) {
        dataStore(context).edit { it[telegramExplainerSeenKey] = true }
    }

    suspend fun zapretAutoCache(context: Context, networkKey: String): ZapretAutoCache? =
        snapshot(context).zapretAutoCaches.lastOrNull { it.networkKey == networkKey }

    suspend fun saveZapretAutoCache(context: Context, cache: ZapretAutoCache) {
        dataStore(context).edit { preferences ->
            val caches = upsertCache(migratedZapretAutoCaches(preferences), cache)
            preferences[zapretCacheMapKey] = encodeAutoCacheMap(caches)
            clearLegacyZapretCache(preferences)
        }
    }

    suspend fun clearZapretAutoCache(context: Context) {
        dataStore(context).edit { preferences ->
            preferences.remove(zapretCacheMapKey)
            clearLegacyZapretCache(preferences)
        }
    }

    suspend fun youtubeAutoCache(context: Context, networkKey: String): ZapretAutoCache? =
        snapshot(context).youtubeAutoCaches.lastOrNull { it.networkKey == networkKey }

    suspend fun saveYoutubeAutoCache(context: Context, cache: ZapretAutoCache) {
        dataStore(context).edit { preferences ->
            val caches = upsertCache(migratedYoutubeAutoCaches(preferences), cache)
            preferences[youtubeCacheMapKey] = encodeAutoCacheMap(caches)
            clearLegacyYoutubeCache(preferences)
        }
    }

    suspend fun clearYoutubeAutoCache(context: Context) {
        dataStore(context).edit { preferences ->
            preferences.remove(youtubeCacheMapKey)
            clearLegacyYoutubeCache(preferences)
        }
    }

    private fun migratedZapretAutoCaches(preferences: Preferences): List<ZapretAutoCache> {
        val caches = decodeAutoCacheMap(preferences[zapretCacheMapKey]).toMutableList()
        legacyZapretAutoCache(preferences)?.let { legacy ->
            if (caches.none { it.networkKey == legacy.networkKey }) caches += legacy
        }
        return caches
    }

    private fun migratedYoutubeAutoCaches(preferences: Preferences): List<ZapretAutoCache> {
        val caches = decodeAutoCacheMap(preferences[youtubeCacheMapKey]).toMutableList()
        legacyYoutubeAutoCache(preferences)?.let { legacy ->
            if (caches.none { it.networkKey == legacy.networkKey }) caches += legacy
        }
        return caches
    }

    private fun legacyZapretAutoCache(preferences: Preferences): ZapretAutoCache? {
        val network = preferences[zapretCacheNetworkKey].orEmpty()
        if (network.isBlank()) return null
        return ZapretAutoCache(
            networkKey = network,
            preset = ZapretPreset.fromName(preferences[zapretCachePresetKey])
                .takeUnless { it == ZapretPreset.AUTO } ?: return null,
            reachableCount = preferences[zapretCacheScoreKey] ?: 0,
            algorithmVersion = preferences[zapretCacheVersionKey] ?: 0
        )
    }

    private fun legacyYoutubeAutoCache(preferences: Preferences): ZapretAutoCache? {
        val network = preferences[youtubeCacheNetworkKey].orEmpty()
        if (network.isBlank()) return null
        return ZapretAutoCache(
            networkKey = network,
            preset = ZapretPreset.fromName(preferences[youtubeCachePresetKey])
                .takeUnless { it == ZapretPreset.AUTO } ?: return null,
            reachableCount = 1,
            algorithmVersion = preferences[youtubeCacheVersionKey] ?: 0
        )
    }

    private fun clearLegacyZapretCache(preferences: MutablePreferences) {
        preferences.remove(zapretCacheNetworkKey)
        preferences.remove(zapretCachePresetKey)
        preferences.remove(zapretCacheScoreKey)
        preferences.remove(zapretCacheVersionKey)
    }

    private fun clearLegacyYoutubeCache(preferences: MutablePreferences) {
        preferences.remove(youtubeCacheNetworkKey)
        preferences.remove(youtubeCachePresetKey)
        preferences.remove(youtubeCacheVersionKey)
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

    internal fun decodeSubscription(raw: String?): SubscriptionState {
        raw ?: return SubscriptionState()
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
                    .toSet(),
                yctunJson = root.optString("yctunJson")
            )
        } catch (_: Exception) {
            SubscriptionState()
        }
    }

    suspend fun saveSubscription(context: Context, state: SubscriptionState) {
        dataStore(context).edit { it[subscriptionKey] = encodeSubscription(state) }
    }

    internal fun encodeSubscription(state: SubscriptionState): String {
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
        return JSONObject()
            .put("url", state.url)
            .put("title", state.title)
            .put("profiles", profiles)
            .put("activeProfileId", state.activeProfileId)
            .put("updatedAt", state.updatedAt)
            .put("etag", state.etag)
            .put("userInfo", state.userInfo)
            .put("updateIntervalHours", state.updateIntervalHours ?: -1)
            .put("suggestedBypassApps", bypass)
            .put("yctunJson", state.yctunJson)
            .toString()
    }

    private fun dataStore(context: Context): DataStore<Preferences> =
        context.applicationContext.xrayDataStore
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
