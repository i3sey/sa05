package com.fife.sa05

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL

object SubscriptionDeepLink {
    fun parse(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val outer = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!outer.scheme.equals("sa05", ignoreCase = true) ||
            !outer.host.equals("add", ignoreCase = true)
        ) {
            return null
        }
        val encoded = outer.rawPath?.removePrefix("/")?.takeIf { it.isNotBlank() }
            ?: return null
        val decoded = decodePercentEncoded(encoded) ?: return null
        val url = runCatching { URL(decoded) }.getOrNull() ?: return null
        return decoded.takeIf {
            url.protocol.equals("https", ignoreCase = true) && url.host.isNotBlank()
        }
    }

    private fun decodePercentEncoded(value: String): String? {
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] != '%') {
                result.append(value[index++])
                continue
            }
            val bytes = ByteArrayOutputStream()
            while (index < value.length && value[index] == '%') {
                if (index + 2 >= value.length) return null
                val byte = value.substring(index + 1, index + 3).toIntOrNull(16) ?: return null
                bytes.write(byte)
                index += 3
            }
            result.append(bytes.toByteArray().toString(Charsets.UTF_8))
        }
        return result.toString()
    }
}
