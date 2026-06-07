package com.fife.sa05

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class ValidatedXrayConfig(val runtimeJson: String, val socksPort: Int)

data class XrayHost(
    val id: String,
    val outboundIndex: Int,
    val endpointIndex: Int,
    val tag: String,
    val protocol: String,
    val address: String,
    val port: Int
)

data class XrayPingConfig(
    val runtimeJson: String,
    val probeUrl: String,
    val timeoutMs: Int
)

object XrayConfig {
    private val youtubeDomains = listOf(
        "geosite:youtube",
        "domain:youtube.com",
        "domain:youtu.be",
        "domain:youtube-nocookie.com",
        "domain:youtubekids.com",
        "domain:googlevideo.com",
        "domain:ytimg.com",
        "domain:googleusercontent.com",
        "domain:ggpht.com",
        "domain:youtubei.googleapis.com",
        "domain:youtubeembeddedplayer.googleapis.com",
        "domain:jnn-pa.googleapis.com",
        "domain:wide-youtube.l.google.com",
        "domain:youtube-ui.l.google.com",
        "domain:yt-video-upload.l.google.com"
    )

    fun validate(raw: String): ValidatedXrayConfig {
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("JSON не разобран: ${e.message}")
        }
        val inbounds = root.optJSONArray("inbounds")
            ?: throw IllegalArgumentException("Нет массива inbounds")
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            if (inbound.optString("protocol") != "socks") continue
            val listen = inbound.optString("listen", "127.0.0.1")
            if (listen != "127.0.0.1" && listen != "localhost") {
                throw IllegalArgumentException("SOCKS inbound должен слушать 127.0.0.1")
            }
            val port = inbound.optInt("port", -1)
            if (port !in 1..65535) {
                throw IllegalArgumentException("У SOCKS inbound некорректный port")
            }
            if (!inbound.optJSONObject("settings")?.optBoolean("udp", false).orFalse()) {
                throw IllegalArgumentException("Для VPN нужен settings.udp=true у SOCKS inbound")
            }
            return ValidatedXrayConfig(root.toString(2), port)
        }
        throw IllegalArgumentException("Нужен SOCKS inbound на 127.0.0.1")
    }

    fun extractHosts(raw: String): List<XrayHost> {
        val root = parse(raw)
        val outbounds = root.optJSONArray("outbounds") ?: return emptyList()
        val result = mutableListOf<XrayHost>()
        for (outboundIndex in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(outboundIndex) ?: continue
            val protocol = outbound.optString("protocol")
            if (protocol in setOf("freedom", "blackhole", "dns", "loopback")) continue
            val tag = outbound.optString("tag").ifBlank { "outbound-$outboundIndex" }
            val settings = outbound.optJSONObject("settings") ?: continue
            val endpoints = when {
                settings.optJSONArray("vnext") != null -> settings.optJSONArray("vnext")
                settings.optJSONArray("servers") != null -> settings.optJSONArray("servers")
                protocol == "hysteria" && settings.optInt("version") == 2 -> JSONArray()
                    .put(
                        JSONObject()
                            .put("address", settings.optString("address"))
                            .put("port", settings.optInt("port"))
                    )
                else -> null
            } ?: continue
            for (endpointIndex in 0 until endpoints.length()) {
                val endpoint = endpoints.optJSONObject(endpointIndex) ?: continue
                val address = endpoint.optString("address")
                val port = endpoint.optInt("port", -1)
                if (address.isBlank() || port !in 1..65535) continue
                result += XrayHost(
                    id = "$outboundIndex:$endpointIndex:$address:$port",
                    outboundIndex = outboundIndex,
                    endpointIndex = endpointIndex,
                    tag = tag,
                    protocol = protocol,
                    address = address,
                    port = port
                )
            }
        }
        return result
    }

    fun buildFullAutoConfig(raw: String, byeDpiPort: Int): ValidatedXrayConfig {
        require(byeDpiPort in 1..65535)
        val root = parse(raw)
        val validated = validate(raw)
        val inbounds = root.getJSONArray("inbounds")
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            if (inbound.optString("protocol") != "socks" ||
                inbound.optInt("port") != validated.socksPort
            ) continue
            val sniffing = inbound.optJSONObject("sniffing") ?: JSONObject().also {
                inbound.put("sniffing", it)
            }
            sniffing.put("enabled", true)
            // Keep the original IP for ByeDPI while using the sniffed host only for routing.
            sniffing.put("routeOnly", true)
            val overrides = sniffing.optJSONArray("destOverride") ?: JSONArray()
            val values = (0 until overrides.length())
                .map { overrides.optString(it) }
                .filter(String::isNotBlank)
                .toMutableSet()
            values += listOf("http", "tls", "quic")
            sniffing.put("destOverride", JSONArray(values.toList()))
            break
        }

        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also {
            root.put("outbounds", it)
        }
        val tags = (0 until outbounds.length()).mapNotNull {
            outbounds.optJSONObject(it)?.optString("tag")?.takeIf(String::isNotBlank)
        }.toMutableSet()
        fun uniqueTag(base: String): String {
            var value = base
            var suffix = 2
            while (!tags.add(value)) value = "$base-${suffix++}"
            return value
        }
        val byeDpiTag = uniqueTag("__sa05_youtube_byedpi")
        val blockTag = uniqueTag("__sa05_youtube_quic_block")
        outbounds.put(
            JSONObject()
                .put("tag", byeDpiTag)
                .put("protocol", "socks")
                .put(
                    "settings",
                    JSONObject().put(
                        "servers",
                        JSONArray().put(
                            JSONObject()
                                .put("address", "127.0.0.1")
                                .put("port", byeDpiPort)
                        )
                    )
                )
        )
        outbounds.put(
            JSONObject()
                .put("tag", blockTag)
                .put("protocol", "blackhole")
        )

        val routing = root.optJSONObject("routing") ?: JSONObject().also {
            root.put("routing", it)
        }
        val existingRules = routing.optJSONArray("rules") ?: JSONArray()
        val rules = JSONArray()
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("network", "udp")
                    .put("port", "443")
                    .put("outboundTag", blockTag)
            )
            .put(
                JSONObject()
                    .put("type", "field")
                    .put("network", "tcp")
                    .put("domain", JSONArray(youtubeDomains))
                    .put("outboundTag", byeDpiTag)
            )
        for (index in 0 until existingRules.length()) rules.put(existingRules.get(index))
        routing.put("rules", rules)
        return ValidatedXrayConfig(root.toString(2), validated.socksPort)
    }

    fun buildPingConfig(raw: String, host: XrayHost, socksPort: Int): XrayPingConfig {
        val root = parse(raw)
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("Нет массива outbounds")
        val selected = outbounds.optJSONObject(host.outboundIndex)
            ?: throw IllegalArgumentException("Outbound больше не существует")
        val settings = selected.optJSONObject("settings")
            ?: throw IllegalArgumentException("У outbound нет settings")
        val endpointsKey = when {
            settings.optJSONArray("vnext") != null -> "vnext"
            settings.optJSONArray("servers") != null -> "servers"
            selected.optString("protocol") == "hysteria" &&
                settings.optInt("version") == 2 -> null
            else -> throw IllegalArgumentException("Не найден список серверов outbound")
        }
        if (endpointsKey != null) {
            val endpoints = settings.optJSONArray(endpointsKey)
                ?: throw IllegalArgumentException("Не найден endpoint outbound")
            val endpoint = endpoints.optJSONObject(host.endpointIndex)
                ?: throw IllegalArgumentException("Endpoint больше не существует")
            settings.put(endpointsKey, JSONArray().put(JSONObject(endpoint.toString())))
        }

        val targetTag = selected.optString("tag").ifBlank {
            "__ping_target".also { selected.put("tag", it) }
        }
        root.put(
            "inbounds",
            JSONArray().put(
                JSONObject()
                    .put("tag", "__ping_in")
                    .put("listen", "127.0.0.1")
                    .put("port", socksPort)
                    .put("protocol", "socks")
                    .put("settings", JSONObject().put("udp", true).put("auth", "noauth"))
            )
        )
        root.put(
            "routing",
            JSONObject()
                .put("domainStrategy", "AsIs")
                .put(
                    "rules",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "field")
                            .put("inboundTag", JSONArray().put("__ping_in"))
                            .put("outboundTag", targetTag)
                    )
                )
        )
        root.remove("observatory")
        root.remove("burstObservatory")
        root.remove("api")
        root.remove("metrics")

        val probeUrl = probeUrl(parse(raw))
        return XrayPingConfig(
            runtimeJson = root.toString(2),
            probeUrl = probeUrl,
            timeoutMs = probeTimeoutMs(parse(raw))
        )
    }

    private fun parse(raw: String): JSONObject = try {
        JSONObject(raw)
    } catch (e: Exception) {
        throw IllegalArgumentException("JSON не разобран: ${e.message}")
    }

    private fun probeUrl(root: JSONObject): String {
        val burst = root.optJSONObject("burstObservatory")
            ?.optJSONObject("pingConfig")
            ?.optString("destination")
            .orEmpty()
        val background = root.optJSONObject("observatory")
            ?.optString("probeUrl")
            .orEmpty()
        val value = burst.ifBlank { background }.ifBlank {
            "https://www.gstatic.com/generate_204"
        }
        val uri = try {
            URI(value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Некорректный URL для пинга")
        }
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("Пинг поддерживает только HTTP/HTTPS URL")
        }
        return value
    }

    private fun probeTimeoutMs(root: JSONObject): Int {
        val value = root.optJSONObject("burstObservatory")
            ?.optJSONObject("pingConfig")
            ?.optString("timeout")
            .orEmpty()
        if (value.isBlank()) return 8_000
        val match = Regex("""^(\d+)(ms|s|m)$""").matchEntire(value) ?: return 8_000
        val amount = match.groupValues[1].toLongOrNull() ?: return 8_000
        val multiplier = when (match.groupValues[2]) {
            "ms" -> 1L
            "s" -> 1_000L
            "m" -> 60_000L
            else -> 1L
        }
        return (amount * multiplier).coerceIn(1_000, 30_000).toInt()
    }

    private fun Boolean?.orFalse() = this ?: false
}
