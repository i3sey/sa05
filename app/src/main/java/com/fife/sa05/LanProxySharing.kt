package com.fife.sa05

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sharing the tunnel over the local network turns the phone into an open gateway for anything
 * that can reach it, so every decision here is made the cautious way:
 *
 * - authentication is mandatory and the password is generated, never chosen or blank;
 * - the inbound binds to one specific local address rather than `0.0.0.0`, so it is not exposed
 *   on the cellular interface at the same time;
 * - the feature only offers itself when a local network is actually present.
 */
/**
 * Starting point for the sharing port. It is only a preference: [pickLanProxyPort] moves off it
 * when a profile already uses it.
 *
 * The first choice here was 10809, which is exactly the port providers hand to their HTTP
 * inbound. Both ends bound anyway — Go sets SO_REUSEADDR and a specific address outranks
 * 0.0.0.0 — so it appeared to work while actually depending on luck.
 */
internal const val LAN_PROXY_BASE_PORT = 10890
internal const val LAN_PROXY_USER = "sa05"

/** Loopback forms a config may legitimately bind to; anything else is reachable off-device. */
private val LOOPBACK_LISTEN = setOf("127.0.0.1", "localhost", "::1")

internal data class LanProxyEndpoint(val address: String, val interfaceName: String)

/** Ports already claimed by a config's inbounds, so sharing can avoid them. */
internal fun inboundPorts(raw: String): Set<Int> = runCatching {
    val inbounds = JSONObject(raw).optJSONArray("inbounds") ?: return emptySet()
    (0 until inbounds.length())
        .mapNotNull { inbounds.optJSONObject(it)?.optInt("port")?.takeIf { p -> p in 1..65535 } }
        .toSet()
}.getOrDefault(emptySet())

internal fun pickLanProxyPort(
    taken: Set<Int>,
    base: Int = LAN_PROXY_BASE_PORT
): Int {
    var port = base
    while (port in taken && port < 65535) port++
    return port
}

internal data class HardenedConfig(
    val json: String,
    /** Inbounds that were reachable from the network and have been pulled back to loopback. */
    val reboundPorts: List<Int>
)

/**
 * Pulls every inbound that listens beyond loopback back to `127.0.0.1` in a runtime config copy.
 *
 * Providers routinely ship `"listen": "0.0.0.0"`, which turns the phone into an open,
 * unauthenticated SOCKS and HTTP proxy for everyone on the same Wi-Fi for as long as the VPN
 * runs — verified from a second machine on the LAN, no credentials needed. Nothing is lost by
 * moving them: tun2socks, the ping engine and the diagnostics all dial `127.0.0.1`, and sharing
 * to other devices is what the password-protected inbound exists for.
 *
 * The saved profile is untouched; this only rewrites the copy handed to the Xray process.
 */
internal fun hardenInbounds(raw: String): HardenedConfig {
    val root = runCatching { JSONObject(raw) }.getOrNull() ?: return HardenedConfig(raw, emptyList())
    val inbounds = root.optJSONArray("inbounds") ?: return HardenedConfig(raw, emptyList())
    val rebound = mutableListOf<Int>()
    for (index in 0 until inbounds.length()) {
        val inbound = inbounds.optJSONObject(index) ?: continue
        // Sharing binds a LAN address on purpose and carries its own authentication.
        if (inbound.optString("tag").startsWith("__sa05_lan_share")) continue
        val listen = inbound.optString("listen")
        if (listen.isBlank() || listen in LOOPBACK_LISTEN) continue
        inbound.put("listen", "127.0.0.1")
        inbound.optInt("port").takeIf { it in 1..65535 }?.let(rebound::add)
    }
    return HardenedConfig(root.toString(), rebound)
}

/** A generated password: 16 chars from an unambiguous alphabet, so it can be typed by hand. */
internal fun generateLanProxyPassword(random: SecureRandom = SecureRandom()): String {
    val alphabet = "abcdefghijkmnpqrstuvwxyz23456789"
    return (1..16)
        .map { alphabet[random.nextInt(alphabet.length)] }
        .joinToString("")
}

internal fun lanProxyShareUri(
    address: String,
    port: Int,
    user: String,
    password: String
): String = "socks5://$user:$password@$address:$port"

/**
 * Site-local IPv4 addresses of up interfaces, excluding loopback and the app's own TUN.
 *
 * The TUN address is excluded because handing it out would point clients at the tunnel's inside,
 * which nothing on the LAN can reach.
 */
internal fun lanEndpoints(
    interfaces: List<NetworkInterface> =
        runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
): List<LanProxyEndpoint> = interfaces
    .asSequence()
    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
    .flatMap { networkInterface ->
        networkInterface.inetAddresses.asSequence().mapNotNull { address ->
            if (address !is Inet4Address) return@mapNotNull null
            val host = address.hostAddress ?: return@mapNotNull null
            if (!address.isSiteLocalAddress) return@mapNotNull null
            if (host == TUN_IPV4_ADDRESS || host == "10.10.10.2") return@mapNotNull null
            LanProxyEndpoint(address = host, interfaceName = networkInterface.name.orEmpty())
        }
    }
    .distinctBy { it.address }
    .toList()

/**
 * Adds a password-protected SOCKS inbound bound to [listenAddress] to a runtime config copy.
 *
 * Mirrors how Full Auto augments the runtime JSON: the saved profile is never touched.
 */
internal fun addLanProxyInbound(
    raw: String,
    listenAddress: String,
    port: Int,
    user: String,
    password: String
): String {
    require(port in 1..65535) { "Некорректный порт: $port" }
    require(listenAddress.isNotBlank()) { "Не указан адрес для раздачи" }
    require(password.isNotBlank()) { "Раздача без пароля запрещена" }
    require(listenAddress != "0.0.0.0") {
        "Раздача должна слушать конкретный адрес локальной сети, а не 0.0.0.0"
    }

    val root = JSONObject(raw)
    val inbounds = root.optJSONArray("inbounds") ?: JSONArray().also { root.put("inbounds", it) }
    val taken = (0 until inbounds.length())
        .mapNotNull { inbounds.optJSONObject(it)?.optString("tag")?.takeIf(String::isNotBlank) }
        .toMutableSet()
    var tag = "__sa05_lan_share"
    var suffix = 2
    while (!taken.add(tag)) tag = "__sa05_lan_share-${suffix++}"

    inbounds.put(
        JSONObject()
            .put("tag", tag)
            .put("protocol", "socks")
            .put("listen", listenAddress)
            .put("port", port)
            .put(
                "settings",
                JSONObject()
                    .put("auth", "password")
                    .put("udp", true)
                    .put(
                        "accounts",
                        JSONArray().put(
                            JSONObject().put("user", user).put("pass", password)
                        )
                    )
            )
            .put(
                "sniffing",
                JSONObject()
                    .put("enabled", true)
                    .put("destOverride", JSONArray(listOf("http", "tls", "quic")))
            )
    )
    return root.toString()
}
