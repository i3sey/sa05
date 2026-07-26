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
internal const val LAN_PROXY_PORT = 10809
internal const val LAN_PROXY_USER = "sa05"

internal data class LanProxyEndpoint(val address: String, val interfaceName: String)

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
