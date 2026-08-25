package com.fife.sa05

import org.json.JSONArray
import org.json.JSONObject

/**
 * Параметры режима «CDN-туннель» (yctun).
 *
 * Credentials приходят блоком `sa05_yctun` в профиле или заголовком
 * `x-sa05-yctun`. Клиент добавляет псевдо-сервер [CdnProfile] в список
 * подписки; выбор этого сервера запускает relayc + rewrite Xray:
 *
 *   tun2socks -> Xray (socks inbound) -> yctun outbound -> relayc :10812
 *   -> HTTPS GET CDN -> relayd на VPS -> интернет
 */
data class YctunParams(
    val baseUrl: String,
    val psk: String,
    val serverPub: String,
    val stream: Boolean = true,
    val streams: Int = 4,
    val workers: Int = 6,
    val chunk: Int = 12288,
    val pollMs: Int = 400
) {
    /** Конфиг для relayc (JSON-файл, `librelayc.so -config ...`). */
    fun relaycConfig(listen: String): String = toBlock()
        .put("listen", listen)
        .toString(2)

    fun toBlock(): JSONObject = JSONObject()
        .put("base_url", baseUrl)
        .put("psk", psk)
        .put("server_pub", serverPub)
        .put("stream", stream)
        .put("streams", streams)
        .put("workers", workers)
        .put("chunk", chunk)
        .put("poll_ms", pollMs)

    companion object {
        const val BLOCK_KEY = "sa05_yctun"

        /** Возвращает параметры туннеля из JSON профиля или null, если блока нет. */
        fun parse(profileJson: String): YctunParams? {
            val root = try {
                JSONObject(profileJson)
            } catch (e: Exception) {
                throw IllegalArgumentException("JSON профиля не разобран: ${e.message}")
            }
            val block = root.optJSONObject(BLOCK_KEY) ?: return null
            return parseBlock(block)
        }

        /**
         * Параметры из заголовка подписки `x-sa05-yctun` (JSON), см.
         * [SubscriptionRepository.decodeYctunHeader]. Используется провайдерами,
         * которые не могут положить блок в тело профиля (Remnawave вырезает
         * неизвестные ключи шаблона при сохранении).
         */
        fun parseSubscription(subscription: SubscriptionState): YctunParams? {
            if (subscription.yctunJson.isBlank()) return null
            val block = try {
                JSONObject(subscription.yctunJson)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "$BLOCK_KEY в заголовке x-sa05-yctun не разобран: ${e.message}"
                )
            }
            return parseBlock(block)
        }

        /** Блок из профиля имеет приоритет, заголовок подписки — фолбэк. */
        fun resolve(profileJson: String, subscription: SubscriptionState): YctunParams? =
            parse(profileJson) ?: parseSubscription(subscription)

        private fun parseBlock(block: JSONObject): YctunParams {
            val baseUrl = requireText(block, "base_url")
            val psk = requireText(block, "psk")
            val serverPub = requireText(block, "server_pub")
            validateBaseUrl(baseUrl)
            validatePsk(psk)
            validateServerPub(serverPub)
            return YctunParams(
                baseUrl = baseUrl,
                psk = psk,
                serverPub = serverPub,
                stream = block.optBoolean("stream", true),
                streams = positiveInt(block, "streams", 4),
                workers = positiveInt(block, "workers", 6),
                chunk = positiveInt(block, "chunk", 12288),
                pollMs = positiveInt(block, "poll_ms", 400)
            )
        }

        private fun requireText(block: JSONObject, key: String): String =
            block.optString(key).trim().takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException(
                    "$BLOCK_KEY: обязательно поле «$key»"
                )

        private fun validateBaseUrl(value: String) {
            val url = try {
                java.net.URI(value)
            } catch (_: Exception) {
                throw IllegalArgumentException("$BLOCK_KEY: base_url не URL")
            }
            if (url.scheme != "https" || url.host.isNullOrBlank()) {
                throw IllegalArgumentException(
                    "$BLOCK_KEY: base_url должен быть https-адресом CDN-ресурса"
                )
            }
        }

        private fun validatePsk(value: String) {
            if (value.length < 32 || !value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                throw IllegalArgumentException(
                    "$BLOCK_KEY: psk должен быть hex (минимум 16 байт)"
                )
            }
        }

        private fun validateServerPub(value: String) {
            if (value.length != 64 || !value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
                throw IllegalArgumentException(
                    "$BLOCK_KEY: server_pub должен быть hex (32 байта)"
                )
            }
        }

        private fun positiveInt(block: JSONObject, key: String, fallback: Int): Int {
            val value = block.optInt(key, fallback)
            if (value <= 0) {
                throw IllegalArgumentException("$BLOCK_KEY: «$key» должен быть положительным")
            }
            return value
        }
    }
}

/**
 * Клиентский псевдо-сервер «CDN-туннель»: не приходит с провайдера,
 * а вставляется в [SubscriptionState.profiles] при наличии credentials.
 */
object CdnProfile {
    const val ID = "__sa05_cdn"
    const val REMARKS = "CDN-туннель"
    const val KIND_KEY = "sa05_kind"
    const val KIND_VALUE = "yctun"
    private const val SOCKS_PORT = 10808

    fun isCdn(profile: SubscriptionProfile?): Boolean {
        profile ?: return false
        if (profile.id == ID) return true
        return try {
            JSONObject(profile.json).optString(KIND_KEY) == KIND_VALUE
        } catch (_: Exception) {
            false
        }
    }

    fun build(params: YctunParams): SubscriptionProfile {
        val json = JSONObject()
            .put("remarks", REMARKS)
            .put(KIND_KEY, KIND_VALUE)
            .put(YctunParams.BLOCK_KEY, params.toBlock())
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "socks")
                        .put("listen", "127.0.0.1")
                        .put("port", SOCKS_PORT)
                        .put("protocol", "socks")
                        .put(
                            "settings",
                            JSONObject()
                                .put("udp", true)
                                .put("auth", "noauth")
                        )
                )
            )
            .put(
                "outbounds",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "direct")
                        .put("protocol", "freedom")
                )
            )
            .toString(2)
        XrayConfig.validate(json)
        return SubscriptionProfile(id = ID, remarks = REMARKS, json = json)
    }
}

/** Добавляет или убирает псевдо-сервер CDN в зависимости от credentials. */
fun SubscriptionState.withCdnProfile(): SubscriptionState {
    val without = profiles.filterNot { CdnProfile.isCdn(it) }
    val params = runCatching { YctunParams.parseSubscription(this) }.getOrNull()
        ?: without.firstNotNullOfOrNull {
            runCatching { YctunParams.parse(it.json) }.getOrNull()
        }
    val nextProfiles = if (params != null) {
        without + CdnProfile.build(params)
    } else {
        without
    }
    val activeId = when {
        nextProfiles.any { it.id == activeProfileId } -> activeProfileId
        else -> nextProfiles.firstOrNull()?.id.orEmpty()
    }
    return copy(profiles = nextProfiles, activeProfileId = activeId)
}