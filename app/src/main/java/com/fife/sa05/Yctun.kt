package com.fife.sa05

import org.json.JSONObject

/**
 * Параметры режима «CDN-туннель» (yctun).
 *
 * Профиль подписки может нести опциональный верхнеуровневый блок
 * `sa05_yctun` с ключами туннеля. Xray такой ключ игнорирует; приложение
 * вырезает его перед записью runtime-конфига и запускает relayc (нативный
 * `librelayc.so`) как локальный SOCKS-прокси:
 *
 *   tun2socks -> Xray (socks inbound) -> yctun outbound -> relayc :10812
 *   -> HTTPS GET dom.sa05.eu.cc (Yandex Cloud CDN) -> relayd на VPS -> интернет
 *
 * Блок опционален: обычные профили работают как раньше. Если провайдер
 * не может пронести блок в теле (Remnawave вырезает неизвестные ключи
 * шаблона при сохранении), параметры можно отдать заголовком подписки
 * `x-sa05-yctun` — см. [SubscriptionRepository.decodeYctunHeader].
 * Режим [VpnBackend.YCTUN] не запускается без параметров из любого источника.
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
    fun relaycConfig(listen: String): String = JSONObject()
        .put("base_url", baseUrl)
        .put("psk", psk)
        .put("server_pub", serverPub)
        .put("listen", listen)
        .put("stream", stream)
        .put("streams", streams)
        .put("workers", workers)
        .put("chunk", chunk)
        .put("poll_ms", pollMs)
        .toString(2)

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