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
            if (listen !in setOf("127.0.0.1", "localhost", "0.0.0.0")) {
                throw IllegalArgumentException(
                    "SOCKS inbound должен слушать 127.0.0.1, localhost или 0.0.0.0"
                )
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
        throw IllegalArgumentException(
            "Нужен SOCKS inbound на 127.0.0.1, localhost или 0.0.0.0"
        )
    }

    fun quietRuntime(raw: String): String {
        val root = parse(raw)
        listOf("api", "stats", "metrics").forEach { root.remove(it) }
        val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
        log.put("loglevel", "error")
        return root.toString(2)
    }

    fun blockUdp443(raw: String): String {
        val root = parse(raw)
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also {
            root.put("outbounds", it)
        }
        val tags = (0 until outbounds.length()).mapNotNull {
            outbounds.optJSONObject(it)?.optString("tag")?.takeIf(String::isNotBlank)
        }.toMutableSet()
        val blockTag = (0 until outbounds.length()).firstNotNullOfOrNull { index ->
            val outbound = outbounds.optJSONObject(index) ?: return@firstNotNullOfOrNull null
            outbound.optString("tag").takeIf {
                it.isNotBlank() && outbound.optString("protocol") == "blackhole"
            }
        } ?: run {
            var tag = "__sa05_quic_block"
            var suffix = 2
            while (!tags.add(tag)) tag = "__sa05_quic_block-$suffix".also { suffix++ }
            outbounds.put(
                JSONObject()
                    .put("tag", tag)
                    .put("protocol", "blackhole")
            )
            tag
        }
        val routing = root.optJSONObject("routing") ?: JSONObject().also {
            root.put("routing", it)
        }
        val existing = routing.optJSONArray("rules") ?: JSONArray()
        val rules = JSONArray().put(
            JSONObject()
                .put("type", "field")
                .put("network", "udp")
                .put("port", "443")
                .put("outboundTag", blockTag)
        )
        for (index in 0 until existing.length()) rules.put(existing.get(index))
        routing.put("rules", rules)
        return root.toString(2)
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

    /**
     * Fills in Beeline XHTTP padding parameters when a VLESS+XHTTP outbound is
     * missing them.
     *
     * Beeline CDN rejects XHTTP requests on two signals: the long UUID session
     * ID (fixed by the patched core) AND the stock padding (`x_padding=XXXX…`).
     * Without the padding params the CDN answers 403 for the whole tunnel, so
     * every proxied request fails and the browser shows
     * DNS_PROBE_FINISHED_NO_INTERNET even though the TCP handshake to the CDN
     * (what latency probes measure) succeeds. These values are verified against
     * the live edge (generate_204 -> 204). The padding must match the origin's
     * XHTTP inbound; if the subscription already supplies them, they are kept.
     *
     * Idempotent, and a no-op for non-Beeline / non-XHTTP profiles.
     */
    fun applyBeelinePadding(raw: String): String {
        val root = parse(raw)
        val outbounds = root.optJSONArray("outbounds") ?: return root.toString(2)
        var changed = false
        for (index in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(index) ?: continue
            if (outbound.optString("protocol").lowercase() != "vless") continue
            val stream = outbound.optJSONObject("streamSettings") ?: continue
            if (stream.optString("network").lowercase() != "xhttp") continue
            val xhttp = stream.optJSONObject("xhttpSettings")
                ?: JSONObject().also { stream.put("xhttpSettings", it) }
            // Only fill gaps — never override provider-supplied values.
            if (!xhttp.has("xPaddingBytes")) { xhttp.put("xPaddingBytes", "100-500"); changed = true }
            if (!xhttp.has("xPaddingObfsMode")) { xhttp.put("xPaddingObfsMode", true); changed = true }
            if (!xhttp.has("xPaddingPlacement")) { xhttp.put("xPaddingPlacement", "header"); changed = true }
            if (!xhttp.has("xPaddingMethod")) { xhttp.put("xPaddingMethod", "tokenish"); changed = true }
        }
        return if (changed) root.toString(2) else raw
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

    /**
     * Runtime-конфиг для режима БС-туннеля (yctun).
     *
     * Весь TCP-трафик уходит в локальный relayc (соцеты из профиля больше не
     * используются, их правила роутинга заменяются). relayc принимает только
     * SOCKS CONNECT, поэтому:
     *  - DNS обслуживает встроенный модуль Xray через DoH на IP-литерал
     *    (https://8.8.8.8/dns-query) — хостнейм не резолвится, рекурсии через
     *    сам туннель нет; запросы клиентов на udp/tcp 53 уходят в outbound
     *    «dns»;
     *  - остальной UDP (QUIC, игры) уходит в blackhole — TCP-фолбэк
     *    как в остальных режимах приложения;
     *  - приватные подсети идут напрямую (freedom).
     *
     * Провайдерские outbounds остаются в конфиге нетронутыми (pass-through), но
     * недостижимы: дефолтный outbound — туннель, правила провайдера удалены.
     * Служебный блок `sa05_yctun` вырезается перед запуском Xray.
     */
    fun buildYctunConfig(raw: String, relaycPort: Int): ValidatedXrayConfig {
        require(relaycPort in 1..65535)
        val root = parse(raw)
        val validated = validate(raw)
        root.remove(YctunParams.BLOCK_KEY)

        val existingOutbounds = root.optJSONArray("outbounds") ?: JSONArray()
        val tags = (0 until existingOutbounds.length()).mapNotNull {
            existingOutbounds.optJSONObject(it)?.optString("tag")?.takeIf(String::isNotBlank)
        }.toMutableSet()
        fun uniqueTag(base: String): String {
            var value = base
            var suffix = 2
            while (!tags.add(value)) value = "$base-${suffix++}"
            return value
        }
        val tunnelTag = uniqueTag("__sa05_yctun")
        val dnsTag = uniqueTag("__sa05_yctun_dns")
        val directTag = uniqueTag("__sa05_yctun_direct")
        val blockTag = uniqueTag("__sa05_yctun_block")

        fun socksOutbound(tag: String, port: Int) = JSONObject()
            .put("tag", tag)
            .put("protocol", "socks")
            .put(
                "settings",
                JSONObject().put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("address", "127.0.0.1")
                            .put("port", port)
                    )
                )
            )

        val outbounds = JSONArray()
            .put(socksOutbound(tunnelTag, relaycPort)) // дефолтный outbound = туннель
            .put(JSONObject().put("tag", dnsTag).put("protocol", "dns"))
            .put(JSONObject().put("tag", directTag).put("protocol", "freedom"))
            .put(JSONObject().put("tag", blockTag).put("protocol", "blackhole"))
        for (index in 0 until existingOutbounds.length()) {
            outbounds.put(existingOutbounds.get(index))
        }
        root.put("outbounds", outbounds)

        // DNS: только DoH на IP-литерал — upstream-запросы уходят в туннель
        // по дефолтному outbound, без резолва хостнейма самим днс-модулем.
        root.put(
            "dns",
            JSONObject().put(
                "servers",
                JSONArray().put(
                    JSONObject().put("address", "https://8.8.8.8/dns-query")
                )
            )
        )

        val routing = root.optJSONObject("routing") ?: JSONObject().also {
            root.put("routing", it)
        }
        routing.put(
            "rules",
            JSONArray()
                .put(
                    JSONObject()
                        .put("type", "field")
                        .put("ip", JSONArray().put("geoip:private"))
                        .put("outboundTag", directTag)
                )
                .put(
                    JSONObject()
                        .put("type", "field")
                        .put("port", "53")
                        .put("outboundTag", dnsTag)
                )
                .put(
                    JSONObject()
                        .put("type", "field")
                        .put("network", "udp")
                        .put("outboundTag", blockTag)
                )
        )
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
