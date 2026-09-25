package com.fife.sa05

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.sync.withLock
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
    val suggestedBypassApps: Set<String> = emptySet(),
    /** Параметры yctun из заголовка `x-sa05-yctun` (JSON), если провайдер не кладёт блок в профиль. */
    val yctunJson: String = ""
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

        /**
         * Какой профиль остаётся выбранным после ответа подписки.
         * Список провайдера не содержит псевдо-сервер «БС-туннель», поэтому его
         * id сохраняется отдельно и не сбрасывается на первый обычный профиль.
         */
        internal fun resolveRefreshedActiveProfileId(
            sameSubscription: Boolean,
            activeProfileId: String,
            activeRemarks: String?,
            downloaded: List<SubscriptionProfile>
        ): String {
            if (!sameSubscription) return downloaded.first().id
            if (activeProfileId == BsProfile.ID || activeProfileId == BsProfile.LEGACY_ID) {
                return activeProfileId
            }
            if (downloaded.any { it.id == activeProfileId }) return activeProfileId
            if (!activeRemarks.isNullOrBlank()) {
                return downloaded.firstOrNull { it.remarks == activeRemarks }?.id
                    ?: downloaded.first().id
            }
            return downloaded.first().id
        }

        internal fun decodeYctunHeader(value: String?): String {
            if (value.isNullOrBlank()) return ""
            val encoded = value.trim().substringAfter("base64:", missingDelimiterValue = "")
            return if (encoded.isBlank()) {
                value.trim()
            } else {
                try {
                    String(
                        Base64.decode(encoded, Base64.DEFAULT),
                        Charsets.UTF_8
                    )
                } catch (_: Exception) {
                    ""
                }
            }
        }

        private fun stableId(raw: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return bytes.take(12).joinToString("") { "%02x".format(it) }
        }
    }

    suspend fun load(): SubscriptionState = XrayPreferences.snapshot(context).subscription

    suspend fun setActiveProfile(id: String): SubscriptionState =
        SubscriptionMutationLock.mutex.withLock {
            val current = load()
            require(current.profiles.any { it.id == id }) { "Профиль не найден" }
            val next = current.copy(activeProfileId = id)
            XrayPreferences.saveSubscription(context, next)
            VpnRuntimeState.requestTileRefresh(context)
            next
        }

    suspend fun update(inputUrl: String): SubscriptionUpdateResult {
        val normalizedUrl = validateUrl(inputUrl)
        val baseline = load()
        val fetched = fetch(normalizedUrl, baseline)
        val downloaded = fetched.body?.let { parseProfiles(it) }
        return SubscriptionMutationLock.mutex.withLock {
            commit(normalizedUrl, baseline, fetched, downloaded)
        }
    }

    private fun fetch(normalizedUrl: String, baseline: SubscriptionState): FetchedSubscription {
        val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SA05-Xray/1.0")
            if (baseline.url == normalizedUrl && baseline.etag.isNotBlank()) {
                setRequestProperty("If-None-Match", baseline.etag)
            }
        }
        try {
            val status = connection.responseCode
            val headers = FetchedHeaders(
                title = decodeBase64Header(connection.getHeaderField("profile-title")),
                userInfo = connection.getHeaderField("subscription-userinfo").orEmpty(),
                updateIntervalHours = connection
                    .getHeaderField("profile-update-interval")
                    ?.trim()
                    ?.toIntOrNull(),
                suggestedBypassApps = parseBypassHeader(
                    connection.getHeaderField("per-app-proxy-mode"),
                    connection.getHeaderField("per-app-proxy-list")
                ),
                yctunJson = decodeYctunHeader(connection.getHeaderField("x-sa05-yctun")),
                etag = connection.getHeaderField("ETag").orEmpty()
            )
            if (status == HttpURLConnection.HTTP_NOT_MODIFIED &&
                baseline.url == normalizedUrl &&
                baseline.profiles.isNotEmpty()
            ) {
                return FetchedSubscription(notModified = true, headers = headers, body = null)
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
            return FetchedSubscription(notModified = false, headers = headers, body = body)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun commit(
        normalizedUrl: String,
        baseline: SubscriptionState,
        fetched: FetchedSubscription,
        downloaded: List<SubscriptionProfile>?
    ): SubscriptionUpdateResult {
        val current = load()
        if (current.url.isNotBlank() && current.url != normalizedUrl) {
            return SubscriptionUpdateResult.NotModified(current)
        }
        if (fetched.notModified) {
            // Более новый ответ уже записан, пока этот 304 ждал сеть.
            if (current.etag.isNotBlank() && current.etag != baseline.etag) {
                return SubscriptionUpdateResult.NotModified(current)
            }
            val headers = fetched.headers
            // 304 приходит со свежими заголовками: обновляем производные
            // поля (включая x-sa05-yctun), не перекачивая тело.
            val refreshed = current.copy(
                title = headers.title.ifBlank { current.title },
                userInfo = headers.userInfo.ifBlank { current.userInfo },
                updateIntervalHours = headers.updateIntervalHours ?: current.updateIntervalHours,
                suggestedBypassApps = headers.suggestedBypassApps.ifEmpty {
                    current.suggestedBypassApps
                },
                yctunJson = headers.yctunJson.ifBlank { current.yctunJson }
            ).withBsProfile()
            if (!subscriptionMetadataChanged(current, refreshed)) {
                return SubscriptionUpdateResult.NotModified(current)
            }
            val saved = refreshed.copy(updatedAt = System.currentTimeMillis())
            XrayPreferences.saveSubscription(context, saved)
            return SubscriptionUpdateResult.NotModified(saved)
        }
        val profiles = checkNotNull(downloaded)
        val activeId = resolveRefreshedActiveProfileId(
            sameSubscription = current.url == normalizedUrl,
            activeProfileId = current.activeProfileId,
            activeRemarks = current.activeProfile?.remarks,
            downloaded = profiles
        )
        val headers = fetched.headers
        val next = SubscriptionState(
            url = normalizedUrl,
            title = headers.title,
            profiles = profiles,
            activeProfileId = activeId,
            updatedAt = System.currentTimeMillis(),
            etag = headers.etag,
            userInfo = headers.userInfo,
            updateIntervalHours = headers.updateIntervalHours,
            suggestedBypassApps = headers.suggestedBypassApps,
            yctunJson = headers.yctunJson
        ).withBsProfile()
        XrayPreferences.saveSubscription(context, next)
        return SubscriptionUpdateResult.Updated(next)
    }

    private data class FetchedHeaders(
        val title: String,
        val userInfo: String,
        val updateIntervalHours: Int?,
        val suggestedBypassApps: Set<String>,
        val yctunJson: String,
        val etag: String
    )

    private data class FetchedSubscription(
        val notModified: Boolean,
        val headers: FetchedHeaders,
        val body: String?
    )

    private fun subscriptionMetadataChanged(
        previous: SubscriptionState,
        refreshed: SubscriptionState
    ): Boolean =
        previous.title != refreshed.title ||
            previous.userInfo != refreshed.userInfo ||
            previous.updateIntervalHours != refreshed.updateIntervalHours ||
            previous.suggestedBypassApps != refreshed.suggestedBypassApps ||
            previous.yctunJson != refreshed.yctunJson ||
            previous.activeProfileId != refreshed.activeProfileId ||
            previous.profiles != refreshed.profiles

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
