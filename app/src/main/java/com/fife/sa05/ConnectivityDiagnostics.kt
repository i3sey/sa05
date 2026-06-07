package com.fife.sa05

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

data class DiagnosticTarget(
    val id: String,
    val label: String,
    val url: String,
    val group: DiagnosticGroup,
    val allowedRedirectHosts: Set<String> = emptySet(),
    val expectedStatus: Int? = null,
    val minimumBodyBytes: Int = 128,
    val informationalOnly: Boolean = false
)

enum class DiagnosticGroup {
    CONTROL,
    DPI,
    IP
}

enum class DiagnosticStatus {
    SUCCESS,
    FAILED,
    INCONCLUSIVE
}

data class DiagnosticResult(
    val target: DiagnosticTarget,
    val status: DiagnosticStatus,
    val delayMs: Long? = null,
    val statusCode: Int? = null,
    val bodyBytes: Int = 0,
    val finalUrl: String = target.url,
    val error: String = ""
) {
    val reachable: Boolean
        get() = status == DiagnosticStatus.SUCCESS
}

object ConnectivityDiagnosis {
    fun describe(results: List<DiagnosticResult>): String {
        val successful = results.filter { it.reachable }.map { it.target.id }.toSet()
        val controlAvailable = ConnectivityDiagnostics.controlWorks(results)
        val dpiCount = ConnectivityDiagnostics.bypassScore(results)
        return when {
            !controlAvailable && successful == setOf("yandex") ->
                "Вероятно, сеть работает по белому списку"
            !controlAvailable ->
                "Полный интернет-доступ не подтверждён"
            ConnectivityDiagnostics.bypassWorks(results) && "telegram" in successful ->
                "Backend открыл проверенные сайты; доступ приложения проверьте кнопкой «Открыть»"
            ConnectivityDiagnostics.bypassWorks(results) ->
                "DPI-обход подтверждён, Telegram недоступен отдельно"
            else ->
                "Обход DPI не подтверждён: $dpiCount из ${ConnectivityDiagnostics.dpiTargetIds.size}"
        }
    }
}

class ConnectivityDiagnostics {
    companion object {
        private const val TIMEOUT_MS = 3_500
        private const val MAX_REDIRECTS = 3
        private const val MAX_BODY_BYTES = 4_096
        const val REQUIRED_DPI_SUCCESSES = 2

        val targets = listOf(
            DiagnosticTarget(
                id = "google",
                label = "Google",
                url = "https://www.google.com/generate_204",
                group = DiagnosticGroup.CONTROL,
                expectedStatus = 204,
                minimumBodyBytes = 0
            ),
            DiagnosticTarget(
                id = "yandex",
                label = "Ya.ru",
                url = "https://ya.ru/",
                group = DiagnosticGroup.CONTROL,
                allowedRedirectHosts = setOf("dzen.ru", "www.dzen.ru")
            ),
            DiagnosticTarget(
                id = "rutracker",
                label = "RuTracker",
                url = "https://rutracker.org/",
                group = DiagnosticGroup.DPI,
                informationalOnly = true
            ),
            DiagnosticTarget(
                id = "rule34",
                label = "Rule34",
                url = "https://rule34.xxx/",
                group = DiagnosticGroup.DPI
            ),
            DiagnosticTarget(
                id = "kinozal",
                label = "Kinozal",
                url = "https://kinozal.tv/",
                group = DiagnosticGroup.DPI
            ),
            DiagnosticTarget(
                id = "nnmclub",
                label = "NNMClub",
                url = "https://nnmclub.to/",
                group = DiagnosticGroup.DPI
            ),
            DiagnosticTarget(
                id = "telegram",
                label = "Telegram",
                url = "https://telegram.org/",
                group = DiagnosticGroup.IP
            )
        )

        val autoTargets = targets.filter {
            it.group == DiagnosticGroup.CONTROL ||
                (it.group == DiagnosticGroup.DPI && !it.informationalOnly)
        }

        val dpiTargetIds = targets
            .filter { it.group == DiagnosticGroup.DPI && !it.informationalOnly }
            .mapTo(linkedSetOf()) { it.id }

        fun target(id: String): DiagnosticTarget =
            targets.firstOrNull { it.id == id } ?: error("Неизвестная цель: $id")

        fun bypassScore(results: List<DiagnosticResult>): Int =
            results.count { it.reachable && it.target.id in dpiTargetIds }

        fun controlWorks(results: List<DiagnosticResult>): Boolean =
            results.any { it.reachable && it.target.group == DiagnosticGroup.CONTROL }

        fun bypassWorks(results: List<DiagnosticResult>): Boolean =
            controlWorks(results) && bypassScore(results) >= REQUIRED_DPI_SUCCESSES
    }

    suspend fun runDirect(
        targetsToTest: List<DiagnosticTarget> = targets,
        onResult: suspend (DiagnosticResult) -> Unit = {}
    ): List<DiagnosticResult> {
        val results = mutableListOf<DiagnosticResult>()
        for (target in targetsToTest) {
            coroutineContext.ensureActive()
            val result = withContext(Dispatchers.IO) { probeDirect(target) }
            results += result
            onResult(result)
        }
        return results
    }

    suspend fun runSocks(
        socksPort: Int,
        resolveForSocks: Boolean,
        targetsToTest: List<DiagnosticTarget> = targets,
        onResult: suspend (DiagnosticResult) -> Unit = {}
    ): List<DiagnosticResult> {
        val results = mutableListOf<DiagnosticResult>()
        for (target in targetsToTest) {
            coroutineContext.ensureActive()
            val result = withContext(Dispatchers.IO) {
                probe(target, socksPort, resolveForSocks)
            }
            results += result
            onResult(result)
        }
        return results
    }

    fun probeDirect(target: DiagnosticTarget): DiagnosticResult {
        return probe(target, null, false)
    }

    private fun probe(
        target: DiagnosticTarget,
        socksPort: Int?,
        resolveForSocks: Boolean
    ): DiagnosticResult {
        val started = System.nanoTime()
        return try {
            val response = request(
                target,
                URI(target.url),
                0,
                socksPort,
                resolveForSocks
            )
            classifyResponse(
                target = target,
                statusCode = response.statusCode,
                bodyBytes = response.bodyBytes,
                finalUrl = response.finalUrl,
                delayMs = (System.nanoTime() - started) / 1_000_000
            )
        } catch (error: Throwable) {
            DiagnosticResult(
                target = target,
                status = DiagnosticStatus.FAILED,
                error = diagnosticError(error)
            )
        }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val bodyBytes: Int,
        val finalUrl: String
    )

    private fun request(
        target: DiagnosticTarget,
        uri: URI,
        redirects: Int,
        socksPort: Int?,
        resolveForSocks: Boolean
    ): HttpResponse {
        val port = if (uri.port > 0) uri.port else 443
        val rawSocket = if (socksPort != null) {
            socksConnect(socksPort, uri.host, port, resolveForSocks)
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
            val request = (
                "GET $path HTTP/1.1\r\n" +
                    "Host: ${uri.host}\r\n" +
                    "Connection: close\r\n" +
                    "Accept: text/html,application/xhtml+xml,*/*;q=0.8\r\n" +
                    "Accept-Encoding: identity\r\n" +
                    "User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36\r\n\r\n"
                ).toByteArray(Charsets.US_ASCII)
            it.getOutputStream().write(request)
            it.getOutputStream().flush()
            val input = BufferedInputStream(it.getInputStream())
            val head = readResponseHead(input)
            if (head.statusCode in 300..399 && redirects < MAX_REDIRECTS) {
                val location = head.headers["location"]
                    ?: return HttpResponse(head.statusCode, head.headers, 0, uri.toString())
                val redirected = uri.resolve(location)
                validateRedirect(target, uri, redirected)
                return request(
                    target,
                    redirected,
                    redirects + 1,
                    socksPort,
                    resolveForSocks
                )
            }
            return HttpResponse(
                statusCode = head.statusCode,
                headers = head.headers,
                bodyBytes = readBodySample(input),
                finalUrl = uri.toString()
            )
        }
    }

    private data class ResponseHead(
        val statusCode: Int,
        val headers: Map<String, String>
    )

    private fun readResponseHead(input: InputStream): ResponseHead {
        val lines = mutableListOf<String>()
        for (index in 0 until 64) {
            val line = readAsciiLine(input)
            if (line.isEmpty()) break
            lines += line
        }
        val statusLine = lines.firstOrNull() ?: throw EOFException("Пустой HTTP-ответ")
        val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            ?: error("Некорректный HTTP-ответ")
        return ResponseHead(
            statusCode,
            lines.drop(1).mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else {
                    line.substring(0, separator).trim().lowercase() to
                        line.substring(separator + 1).trim()
                }
            }.toMap()
        )
    }

    private fun readBodySample(input: InputStream): Int {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        while (output.size() < MAX_BODY_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BODY_BYTES - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.size()
    }

    internal fun classifyResponse(
        target: DiagnosticTarget,
        statusCode: Int,
        bodyBytes: Int,
        finalUrl: String = target.url,
        delayMs: Long
    ): DiagnosticResult {
        if (target.id == "rutracker" && statusCode >= 500) {
            return DiagnosticResult(
                target = target,
                status = DiagnosticStatus.INCONCLUSIVE,
                delayMs = delayMs,
                statusCode = statusCode,
                bodyBytes = bodyBytes,
                finalUrl = finalUrl,
                error = "Ошибка сайта HTTP $statusCode"
            )
        }
        val expected = target.expectedStatus
        val statusValid = if (expected != null) {
            statusCode == expected
        } else {
            statusCode in 200..299
        }
        val bodyValid = bodyBytes >= target.minimumBodyBytes
        return if (statusValid && bodyValid) {
            DiagnosticResult(
                target = target,
                status = DiagnosticStatus.SUCCESS,
                delayMs = delayMs,
                statusCode = statusCode,
                bodyBytes = bodyBytes,
                finalUrl = finalUrl
            )
        } else {
            DiagnosticResult(
                target = target,
                status = DiagnosticStatus.FAILED,
                delayMs = delayMs,
                statusCode = statusCode,
                bodyBytes = bodyBytes,
                finalUrl = finalUrl,
                error = when {
                    !statusValid -> "HTTP $statusCode"
                    else -> "Пустой или неполный ответ"
                }
            )
        }
    }

    private fun validateRedirect(target: DiagnosticTarget, source: URI, redirected: URI) {
        val host = redirected.host.orEmpty().lowercase()
        val sourceHost = source.host.orEmpty().lowercase()
        val sameDomain = host == sourceHost ||
            host.endsWith(".$sourceHost") ||
            sourceHost.endsWith(".$host")
        val explicitlyAllowed = target.allowedRedirectHosts.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
        if (!sameDomain && !explicitlyAllowed) {
            error("Перенаправление на ${redirected.host ?: "другой сайт"}")
        }
    }

    private fun readAsciiLine(input: InputStream): String {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size < 8_192) {
            val value = input.read()
            if (value < 0) {
                if (bytes.isEmpty()) throw EOFException("Сервер закрыл соединение")
                break
            }
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun diagnosticError(error: Throwable): String = when (error) {
        is java.net.SocketTimeoutException -> "Таймаут"
        is java.net.UnknownHostException -> "Ошибка DNS"
        is javax.net.ssl.SSLException -> "Ошибка TLS"
        is java.net.ConnectException -> "Нет соединения"
        is java.net.SocketException -> "Соединение сброшено"
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun socksConnect(
        socksPort: Int,
        host: String,
        targetPort: Int,
        resolveHost: Boolean
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
        val address = if (resolveHost) {
            InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
                ?: error("Нет IPv4-адреса для $host")
        } else {
            null
        }
        val destination = if (address != null) {
            byteArrayOf(0x05, 0x01, 0x00, 0x01) + address.address
        } else {
            val hostBytes = host.toByteArray(Charsets.UTF_8)
            byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) + hostBytes
        }
        output.write(
            destination +
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
