package com.fife.sa05

enum class VpnBackend(val title: String) {
    XRAY("Xray"),
    ZAPRET("Zapret"),
    TELEGRAM("Telegram")
}

enum class ZapretPreset(
    val title: String,
    val arguments: List<String>
) {
    AUTO("Авто", emptyList()),
    DIRECT("Без обработки", emptyList()),
    DISORDER("Disorder", listOf("--disorder", "1")),
    SNI_DISORDER(
        "SNI disorder",
        listOf("--split", "1+s", "--disorder", "3+s")
    ),
    ADAPTIVE(
        "Адаптивная",
        listOf(
            "--proto", "http,tls",
            "--mod-http", "h,d,r",
            "--auto", "torst,redirect,ssl_err",
            "--timeout", "2",
            "--split", "1+s",
            "--disorder", "3+s"
        )
    ),
    TLS_RECORD(
        "TLS record",
        listOf(
            "--auto", "torst,ssl_err",
            "--timeout", "2",
            "--tlsrec", "3+s"
        )
    ),
    OOB(
        "OOB",
        listOf("--oob", "3+s")
    ),
    DISORDER_OOB(
        "Disorder + OOB",
        listOf("--disoob", "3+s", "--disorder", "7+s")
    ),
    FAKE_REORDER(
        "Fake + reorder",
        listOf("--disorder", "1", "--fake", "-1")
    ),
    FAKE_TTL(
        "Fake TTL",
        listOf("--fake", "-1", "--ttl", "8")
    ),
    COMBINED(
        "Комбинированная",
        listOf(
            "--proto", "http,tls",
            "--split", "1",
            "--disoob", "3+s",
            "--disorder", "7+s",
            "--mod-http", "h,d,r"
        )
    ),
    CUSTOM("Свои параметры", emptyList());

    companion object {
        val selectable = entries.filter { it != DIRECT }
        val testable = listOf(
            DISORDER,
            SNI_DISORDER,
            ADAPTIVE,
            OOB,
            DISORDER_OOB,
            FAKE_REORDER,
            FAKE_TTL,
            TLS_RECORD,
            COMBINED
        )

        fun fromName(value: String?): ZapretPreset =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}

data class ZapretAutoCache(
    val networkKey: String,
    val preset: ZapretPreset,
    val reachableCount: Int,
    val algorithmVersion: Int
)

data class ZapretAutoProgress(
    val running: Boolean = false,
    val preset: String = "",
    val target: String = "",
    val tested: Int = 0,
    val total: Int = ZapretPreset.testable.size,
    val message: String = ""
)

object ZapretArguments {
    private val blocked = setOf(
        "-i", "--ip",
        "-p", "--port",
        "-D", "--daemon",
        "-w", "--pidfile",
        "-E", "--transparent",
        "-I", "--conn-ip"
    )

    fun parse(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        fun flush() {
            if (current.isNotEmpty()) {
                result += current.toString()
                current.clear()
            }
        }
        value.forEach { char ->
            when {
                escaped -> {
                    current.append(char)
                    escaped = false
                }
                char == '\\' -> escaped = true
                quote != null && char == quote -> quote = null
                quote != null -> current.append(char)
                char == '"' || char == '\'' -> quote = char
                char.isWhitespace() -> flush()
                else -> current.append(char)
            }
        }
        require(!escaped && quote == null) { "Незакрытая кавычка или escape-последовательность" }
        flush()
        require(result.none { token ->
            val option = token.substringBefore('=')
            option in blocked ||
                blocked.filter { it.length == 2 }.any {
                    token.startsWith(it) && token.length > it.length
                }
        }) {
            "Нельзя изменять IP, порт или режим процесса"
        }
        return result
    }
}

object ZapretCommand {
    fun build(
        binary: String,
        port: Int,
        preset: ZapretPreset,
        customArguments: String = ""
    ): List<String> {
        require(preset != ZapretPreset.AUTO) { "AUTO must be resolved before launch" }
        val arguments = if (preset == ZapretPreset.CUSTOM) {
            ZapretArguments.parse(customArguments).also {
                require(it.isNotEmpty()) { "Введите параметры ByeDPI" }
            }
        } else {
            preset.arguments
        }
        return listOf(
            binary,
            "--ip", "127.0.0.1",
            "--port", port.toString(),
            "--conn-ip", "0.0.0.0"
        ) + arguments
    }
}

object ZapretBridgeConfig {
    fun build(inboundPort: Int, upstreamPort: Int): String {
        require(inboundPort in 1..65535)
        require(upstreamPort in 1..65535)
        return """
            {
              "log": {"loglevel": "warning"},
              "inbounds": [{
                "tag": "tun",
                "listen": "127.0.0.1",
                "port": $inboundPort,
                "protocol": "socks",
                "settings": {"auth": "noauth", "udp": true}
              }],
              "outbounds": [{
                "tag": "byedpi",
                "protocol": "socks",
                "settings": {
                  "servers": [{
                    "address": "127.0.0.1",
                    "port": $upstreamPort
                  }]
                }
              }, {
                "tag": "direct",
                "protocol": "freedom"
              }, {
                "tag": "block",
                "protocol": "blackhole"
              }],
              "routing": {
                "rules": [{
                  "type": "field",
                  "network": "udp",
                  "port": "443",
                  "outboundTag": "block"
                }, {
                  "type": "field",
                  "network": "udp",
                  "outboundTag": "direct"
                }, {
                  "type": "field",
                  "network": "tcp",
                  "outboundTag": "byedpi"
                }]
              }
            }
        """.trimIndent()
    }
}

object ZapretAutoSelection {
    fun best(scores: List<Pair<ZapretPreset, Int>>): Pair<ZapretPreset, Int> {
        require(scores.isNotEmpty()) { "No tested presets" }
        return scores.maxBy { it.second }
    }
}
