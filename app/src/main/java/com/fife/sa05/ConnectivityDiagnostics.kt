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
import org.json.JSONObject

data class DiagnosticTarget(
    val id: String,
    val label: String,
    val url: String,
    val group: DiagnosticGroup,
    val allowedRedirectHosts: Set<String> = emptySet(),
    val expectedStatus: Int? = null,
    val minimumBodyBytes: Int = 128,
    val informationalOnly: Boolean = false,
    val method: String = "GET",
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String = "",
    val maximumBodyBytes: Int = 4_096
)

enum class DiagnosticGroup {
    CONTROL,
    DPI,
    IP,
    MEDIA
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
                id = "youtube",
                label = "YouTube",
                url = "https://www.youtube.com/",
                group = DiagnosticGroup.MEDIA,
                allowedRedirectHosts = setOf("youtube.com")
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

        val youtubeTarget: DiagnosticTarget
            get() = target("youtube")

        val youtubeAutoTargets = listOf(
            DiagnosticTarget(
                id = "youtube-player",
                label = "YouTube Player API",
                url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                group = DiagnosticGroup.MEDIA,
                expectedStatus = 200,
                minimumBodyBytes = 4_096,
                maximumBodyBytes = 1_048_576,
                method = "POST",
                requestHeaders = mapOf(
                    "Content-Type" to "application/json",
                    "X-YouTube-Client-Name" to "3",
                    "X-YouTube-Client-Version" to "20.10.41"
                ),
                requestBody = """
                    {
                      "videoId":"dQw4w9WgXcQ",
                      "context":{
                        "client":{
                          "clientName":"ANDROID",
                          "clientVersion":"20.10.41",
                          "androidSdkVersion":35,
                          "hl":"en",
                          "gl":"US"
                        }
                      }
                    }
                """.trimIndent()
            ),
            DiagnosticTarget(
                id = "youtube-web",
                label = "YouTube Web",
                url = "https://www.youtube.com/generate_204",
                group = DiagnosticGroup.MEDIA,
                expectedStatus = 204,
                minimumBodyBytes = 0
            ),
            DiagnosticTarget(
                id = "youtube-mobile",
                label = "YouTube API",
                url = "https://youtubei.googleapis.com/generate_204",
                group = DiagnosticGroup.MEDIA,
                expectedStatus = 204,
                minimumBodyBytes = 0
            ),
            DiagnosticTarget(
                id = "youtube-images",
                label = "YouTube Images",
                url = "https://i.ytimg.com/generate_204",
                group = DiagnosticGroup.MEDIA,
                expectedStatus = 204,
                minimumBodyBytes = 0
            ),
            DiagnosticTarget(
                id = "youtube-video",
                label = "YouTube Video",
                url = "https://redirector.googlevideo.com/generate_204",
                group = DiagnosticGroup.MEDIA,
                expectedStatus = 204,
                minimumBodyBytes = 0
            )
        )

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

    suspend fun probeYoutubePlaybackSocks(
        socksPort: Int,
        resolveForSocks: Boolean
    ): DiagnosticResult = withContext(Dispatchers.IO) {
        val playerTarget = youtubeAutoTargets.first()
        val started = System.nanoTime()
        try {
            val player = request(
                playerTarget,
                URI(playerTarget.url),
                0,
                socksPort,
                resolveForSocks
            )
            val playerResult = classifyResponse(
                playerTarget,
                player.statusCode,
                player.body.size,
                player.finalUrl,
                (System.nanoTime() - started) / 1_000_000
            )
            if (!playerResult.reachable) return@withContext playerResult
            val root = JSONObject(player.body.toString(Charsets.UTF_8))
            val streaming = root.optJSONObject("streamingData")
                ?: return@withContext playerResult.copy(
                    status = DiagnosticStatus.FAILED,
                    error = "Player API не вернул streamingData"
                )
            val formats = streaming.optJSONArray("adaptiveFormats")
                ?: streaming.optJSONArray("formats")
                ?: return@withContext playerResult.copy(
                    status = DiagnosticStatus.FAILED,
                    error = "Player API не вернул форматы видео"
                )
            val mediaUrl = (0 until formats.length()).firstNotNullOfOrNull { index ->
                formats.optJSONObject(index)?.optString("url")?.takeIf(String::isNotBlank)
            } ?: return@withContext playerResult.copy(
                status = DiagnosticStatus.FAILED,
                error = "Player API не вернул прямой URL видео"
            )
            val mediaTarget = DiagnosticTarget(
                id = "youtube-media",
                label = "YouTube Media",
                url = mediaUrl,
                group = DiagnosticGroup.MEDIA,
                minimumBodyBytes = 16_384,
                maximumBodyBytes = 65_536,
                requestHeaders = mapOf("Range" to "bytes=0-65535")
            )
            val mediaStarted = System.nanoTime()
            val media = request(
                mediaTarget,
                URI(mediaUrl),
                0,
                socksPort,
                resolveForSocks
            )
            classifyResponse(
                mediaTarget,
                media.statusCode,
                media.body.size,
                media.finalUrl,
                (System.nanoTime() - mediaStarted) / 1_000_000
            )
        } catch (error: Throwable) {
            DiagnosticResult(
                target = DiagnosticTarget(
                    id = "youtube-media",
                    label = "YouTube Media",
                    url = playerTarget.url,
                    group = DiagnosticGroup.MEDIA
                ),
                status = DiagnosticStatus.FAILED,
                error = diagnosticError(error)
            )
        }
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
                bodyBytes = response.body.size,
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
        val body: ByteArray,
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
            val body = target.requestBody.toByteArray(Charsets.UTF_8)
            val extraHeaders = buildString {
                target.requestHeaders.forEach { (name, value) ->
                    append(name).append(": ").append(value).append("\r\n")
                }
                if (body.isNotEmpty()) {
                    append("Content-Length: ").append(body.size).append("\r\n")
                }
            }
            val request = (
                "${target.method} $path HTTP/1.1\r\n" +
                    "Host: ${uri.host}\r\n" +
                    "Connection: close\r\n" +
                    "Accept: text/html,application/xhtml+xml,*/*;q=0.8\r\n" +
                    "Accept-Encoding: identity\r\n" +
                    "User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36\r\n" +
                    extraHeaders +
                    "\r\n"
                ).toByteArray(Charsets.US_ASCII)
            it.getOutputStream().write(request)
            if (body.isNotEmpty()) it.getOutputStream().write(body)
            it.getOutputStream().flush()
            val input = BufferedInputStream(it.getInputStream())
            val head = readResponseHead(input)
            if (head.statusCode in 300..399 && redirects < MAX_REDIRECTS) {
                val location = head.headers["location"]
                    ?: return HttpResponse(
                        head.statusCode,
                        head.headers,
                        byteArrayOf(),
                        uri.toString()
                    )
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
                body = readResponseBody(input, head.headers, target.maximumBodyBytes),
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

    private fun readBodySample(input: InputStream, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        while (output.size() < maximumBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun readResponseBody(
        input: InputStream,
        headers: Map<String, String>,
        maximumBytes: Int
    ): ByteArray {
        if (!headers["transfer-encoding"].orEmpty().contains("chunked", true)) {
            return readBodySample(input, maximumBytes)
        }
        val output = ByteArrayOutputStream()
        while (output.size() < maximumBytes) {
            val size = readAsciiLine(input)
                .substringBefore(';')
                .trim()
                .toIntOrNull(16)
                ?: error("Некорректный chunked HTTP-ответ")
            if (size == 0) break
            val chunk = readExactly(input, size)
            output.write(chunk, 0, minOf(chunk.size, maximumBytes - output.size()))
            readAsciiLine(input)
        }
        return output.toByteArray()
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
