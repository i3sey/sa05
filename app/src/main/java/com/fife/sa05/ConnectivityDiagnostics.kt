package com.fife.sa05

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory

data class DiagnosticTarget(
    val id: String,
    val label: String,
    val url: String
)

data class DiagnosticResult(
    val target: DiagnosticTarget,
    val delayMs: Long?,
    val error: String = ""
) {
    val reachable: Boolean
        get() = delayMs != null
}

object ConnectivityDiagnosis {
    fun describe(results: List<DiagnosticResult>): String {
        val reachable = results.filter { it.reachable }.map { it.target.id }.toSet()
        return when {
            reachable == setOf("google", "yandex", "telegram") ->
                "Ограничений не обнаружено"
            reachable == setOf("yandex") ->
                "Вероятно действует белый список"
            reachable == setOf("google", "yandex") ->
                "Обычные блокировки: Telegram недоступен"
            reachable.isEmpty() ->
                "Сеть недоступна или соединения блокируются"
            else ->
                "Обнаружена частичная недоступность"
        }
    }
}

class ConnectivityDiagnostics {
    companion object {
        private const val TIMEOUT_MS = 2_500

        val targets = listOf(
            DiagnosticTarget(
                "google",
                "Google",
                "https://www.google.com/generate_204"
            ),
            DiagnosticTarget("yandex", "Яндекс", "https://yandex.ru/"),
            DiagnosticTarget("telegram", "Telegram", "https://telegram.org/")
        )
    }

    suspend fun run(socksPort: Int?): List<DiagnosticResult> = coroutineScope {
        targets.map { target ->
            async(Dispatchers.IO) {
                runCatching { ping(target.url, socksPort) }
                    .fold(
                        onSuccess = { DiagnosticResult(target, it) },
                        onFailure = {
                            DiagnosticResult(
                                target = target,
                                delayMs = null,
                                error = it.message ?: it.javaClass.simpleName
                            )
                        }
                    )
            }
        }.awaitAll()
    }

    private fun ping(url: String, socksPort: Int?): Long {
        val uri = URI(url)
        val port = if (uri.port > 0) uri.port else 443
        val started = System.nanoTime()
        val rawSocket = if (socksPort != null) {
            socksConnect(socksPort, uri.host, port)
        } else {
            Socket().apply {
                connect(InetSocketAddress(uri.host, port), TIMEOUT_MS)
                soTimeout = TIMEOUT_MS
            }
        }
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(rawSocket, uri.host, port, true)
            .apply { soTimeout = TIMEOUT_MS }
        socket.use {
            val path = buildString {
                append(uri.rawPath?.ifBlank { "/" } ?: "/")
                if (!uri.rawQuery.isNullOrBlank()) append('?').append(uri.rawQuery)
            }
            it.getOutputStream().write(
                "HEAD $path HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\nUser-Agent: SA05-Diagnostics\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            it.getOutputStream().flush()
            if (it.getInputStream().read() < 0) throw EOFException("Сервер закрыл соединение")
            return (System.nanoTime() - started) / 1_000_000
        }
    }

    private fun socksConnect(
        socksPort: Int,
        host: String,
        targetPort: Int
    ): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", socksPort), TIMEOUT_MS)
        socket.soTimeout = TIMEOUT_MS
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val greeting = readExactly(input, 2)
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
            socket.close()
            error("SOCKS5 отклонил подключение")
        }
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        output.write(
            byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) +
                hostBytes +
                byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte())
        )
        output.flush()
        val response = readExactly(input, 4)
        if (response[1] != 0x00.toByte()) {
            socket.close()
            error("SOCKS ${response[1].toInt() and 0xff}")
        }
        val addressLength = when (response[3].toInt() and 0xff) {
            1 -> 4
            3 -> input.read().also { if (it < 0) throw EOFException() }
            4 -> 16
            else -> error("Неизвестный SOCKS address type")
        }
        readExactly(input, addressLength + 2)
        return socket
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
        return result
    }
}
