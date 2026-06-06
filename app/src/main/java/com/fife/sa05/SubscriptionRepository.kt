package com.fife.sa05

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class SubscriptionProfile(
    val id: String,
    val remarks: String,
    val json: String
)

data class SubscriptionState(
    val url: String = "",
    val title: String = "",
    val profiles: List<SubscriptionProfile> = emptyList(),
    val activeProfileId: String = "",
    val updatedAt: Long = 0L,
    val etag: String = "",
    val userInfo: String = "",
    val updateIntervalHours: Int? = null,
    val suggestedBypassApps: Set<String> = emptySet()
) {
    val activeProfile: SubscriptionProfile?
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
}

sealed interface SubscriptionUpdateResult {
    data class Updated(val state: SubscriptionState) : SubscriptionUpdateResult
    data class NotModified(val state: SubscriptionState) : SubscriptionUpdateResult
}

class SubscriptionRepository(private val context: Context) {
    companion object {
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val TIMEOUT_MS = 15_000

        internal fun parseProfiles(body: String): List<SubscriptionProfile> {
            val array = try {
                JSONArray(body)
            } catch (e: Exception) {
                throw IllegalArgumentException("Ответ не является JSON-массивом: ${e.message}")
            }
            if (array.length() == 0) {
                throw IllegalArgumentException("Подписка не содержит профилей")
            }
            return (0 until array.length()).map { index ->
                val profile = array.optJSONObject(index)
                    ?: throw IllegalArgumentException(
                        "Профиль ${index + 1} не является JSON-объектом"
                    )
                val raw = profile.toString(2)
                try {
                    XrayConfig.validate(raw)
                } catch (e: Exception) {
                    throw IllegalArgumentException("Профиль ${index + 1}: ${e.message}")
                }
                val remarks = profile.optString("remarks").ifBlank { "Профиль ${index + 1}" }
                SubscriptionProfile(
                    id = stableId(raw),
                    remarks = remarks,
                    json = raw
                )
            }
        }

        internal fun parseBypassHeader(mode: String?, list: String?): Set<String> {
            if (!mode.equals("bypass", ignoreCase = true) || list.isNullOrBlank()) {
                return emptySet()
            }
            return list.split(',')
                .map { it.trim() }
                .filter { it.matches(Regex("[A-Za-z0-9_.]+")) }
                .toSet()
        }

        private fun stableId(raw: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return bytes.take(12).joinToString("") { "%02x".format(it) }
        }
    }

    fun load(): SubscriptionState = XrayPreferences.subscription(context)

    fun setActiveProfile(id: String): SubscriptionState {
        val current = load()
        require(current.profiles.any { it.id == id }) { "Профиль не найден" }
        return current.copy(activeProfileId = id).also {
            XrayPreferences.saveSubscription(context, it)
            VpnRuntimeState.requestTileRefresh(context)
        }
    }

    fun update(inputUrl: String): SubscriptionUpdateResult {
        val normalizedUrl = validateUrl(inputUrl)
        val previous = load()
        val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SA05-Xray/1.0")
            if (previous.url == normalizedUrl && previous.etag.isNotBlank()) {
                setRequestProperty("If-None-Match", previous.etag)
            }
        }
        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED &&
                previous.url == normalizedUrl &&
                previous.profiles.isNotEmpty()
            ) {
                val refreshed = previous.copy(updatedAt = System.currentTimeMillis())
                XrayPreferences.saveSubscription(context, refreshed)
                return SubscriptionUpdateResult.NotModified(refreshed)
            }
            if (status !in 200..299) {
                throw IllegalArgumentException("Сервер подписки вернул HTTP $status")
            }
            val body = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) {
                        throw IllegalArgumentException("Подписка больше 2 МБ")
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
            val profiles = parseProfiles(body)
            val previousActive = previous.activeProfile
            val activeId = when {
                previous.url != normalizedUrl -> profiles.first().id
                profiles.any { it.id == previous.activeProfileId } -> previous.activeProfileId
                previousActive != null -> profiles.firstOrNull {
                    it.remarks == previousActive.remarks
                }?.id ?: profiles.first().id
                else -> profiles.first().id
            }
            val next = SubscriptionState(
                url = normalizedUrl,
                title = decodeBase64Header(connection.getHeaderField("profile-title")),
                profiles = profiles,
                activeProfileId = activeId,
                updatedAt = System.currentTimeMillis(),
                etag = connection.getHeaderField("ETag").orEmpty(),
                userInfo = connection.getHeaderField("subscription-userinfo").orEmpty(),
                updateIntervalHours = connection
                    .getHeaderField("profile-update-interval")
                    ?.trim()
                    ?.toIntOrNull(),
                suggestedBypassApps = parseBypassHeader(
                    connection.getHeaderField("per-app-proxy-mode"),
                    connection.getHeaderField("per-app-proxy-list")
                )
            )
            XrayPreferences.saveSubscription(context, next)
            return SubscriptionUpdateResult.Updated(next)
        } finally {
            connection.disconnect()
        }
    }

    private fun validateUrl(raw: String): String {
        val value = raw.trim()
        val url = try {
            URL(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Некорректная ссылка подписки")
        }
        if (url.protocol != "https") {
            throw IllegalArgumentException("Подписка должна использовать HTTPS")
        }
        return value
    }

    private fun decodeBase64Header(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val encoded = value.substringAfter("base64:", missingDelimiterValue = "")
        if (encoded.isBlank()) return value
        return try {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

}
