package com.fife.sa05

import org.json.JSONArray
import org.json.JSONObject

/**
 * Human-facing facts about an active VLESS + XHTTP (Beeline CDN) profile,
 * derived from its Xray JSON for the diagnostics panel (§8).
 *
 * The raw VLESS Encryption key and the user UUID are deliberately absent: this
 * object may be shown in the UI and must never leak secrets (§12). Only the
 * boolean [encryptionEnabled] survives.
 */
data class BeelineProfileInfo(
    val cdnHost: String,
    val path: String,
    val mode: String,
    val paddingBytes: String,
    val paddingPlacement: String,
    val paddingMethod: String,
    val paddingObfs: Boolean,
    val tlsServerName: String,
    val alpn: List<String>,
    val encryptionEnabled: Boolean,
    val sessionIdFormat: String = XrayCore.SESSION_ID_FORMAT
) {
    /** "header/tokenish/100-500" for the padding diagnostics line. */
    val paddingSummary: String
        get() = listOf(paddingPlacement, paddingMethod, paddingBytes)
            .filter { it.isNotBlank() }
            .joinToString("/")
}

private val LOCAL_OUTBOUND_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

/**
 * Returns [BeelineProfileInfo] when the profile's primary remote outbound is a
 * VLESS outbound over the XHTTP transport, or `null` otherwise (so the caller
 * simply hides the panel for ordinary profiles). Never throws on malformed
 * input.
 */
fun beelineProfileInfo(profileJson: String): BeelineProfileInfo? = runCatching {
    val root = JSONObject(profileJson)
    val outbounds = root.optJSONArray("outbounds") ?: return null
    val outbound = (0 until outbounds.length())
        .mapNotNull(outbounds::optJSONObject)
        .firstOrNull { it.optString("protocol").lowercase() !in LOCAL_OUTBOUND_PROTOCOLS }
        ?: return null

    if (outbound.optString("protocol").lowercase() != "vless") return null
    val stream = outbound.optJSONObject("streamSettings") ?: return null
    if (stream.optString("network").lowercase() != "xhttp") return null

    val vnext = outbound.optJSONObject("settings")
        ?.optJSONArray("vnext")
        ?.optJSONObject(0)
    val xhttp = stream.optJSONObject("xhttpSettings") ?: JSONObject()
    val tls = stream.optJSONObject("tlsSettings") ?: JSONObject()

    val encryption = vnext
        ?.optJSONArray("users")
        ?.optJSONObject(0)
        ?.optString("encryption")
        .orEmpty()

    BeelineProfileInfo(
        cdnHost = vnext?.optString("address").orEmpty(),
        path = xhttp.optString("path"),
        mode = xhttp.optString("mode"),
        paddingBytes = xhttp.optString("xPaddingBytes"),
        paddingPlacement = xhttp.optString("xPaddingPlacement"),
        paddingMethod = xhttp.optString("xPaddingMethod"),
        paddingObfs = xhttp.optBoolean("xPaddingObfsMode", false),
        tlsServerName = tls.optString("serverName"),
        alpn = tls.optJSONArray("alpn").toStringList(),
        encryptionEnabled = encryption.isNotBlank() && encryption.lowercase() != "none"
    )
}.getOrNull()

private fun JSONArray?.toStringList(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }
