package com.fife.sa05

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext

class XrayPingEngine(private val context: Context) {
    private data class PingRun(
        @Volatile var process: Process? = null,
        @Volatile var socket: Socket? = null
    )

    @Volatile
    private var activeRun: PingRun? = null

    fun cancel() {
        val run = synchronized(this) {
            activeRun.also { activeRun = null }
        }
        closeRun(run)
    }

    suspend fun ping(rawConfig: String, host: XrayHost): Long = withContext(Dispatchers.IO) {
        cancel()
        val run = PingRun()
        synchronized(this@XrayPingEngine) {
            activeRun = run
        }
        val socksPort = freePort()
        val pingConfig = XrayConfig.buildPingConfig(rawConfig, host, socksPort)
        val configFile = File(context.cacheDir, "xray-ping-${System.nanoTime()}.json")
        var process: Process? = null
        try {
            copyGeoAssets()
            configFile.writeText(pingConfig.runtimeJson)
            val binary = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
            check(binary.exists()) { "libxray.so не найден" }
            process = ProcessBuilder(
                binary.absolutePath,
                "run",
                "-config",
                configFile.absolutePath
            )
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply { environment()["XRAY_LOCATION_ASSET"] = context.filesDir.absolutePath }
                .start()
            registerProcess(run, process)
            val runningProcess = process
            Thread {
                try {
                    runningProcess.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { Log.d("XrayPing", it) }
                    }
                } catch (_: InterruptedIOException) {
                    // Expected when cancel() or finally closes the Xray process.
                } catch (e: Exception) {
                    if (isAlive(runningProcess)) {
                        Log.w("XrayPing", "Не удалось прочитать лог Xray", e)
                    }
                }
            }.apply {
                name = "XrayPingLog"
                isDaemon = true
            }.start()

            try {
                withTimeout(pingConfig.timeoutMs.toLong()) {
                    waitForPort(runningProcess, socksPort)
                    httpPing(run, socksPort, pingConfig.probeUrl, pingConfig.timeoutMs)
                }
            } catch (_: TimeoutCancellationException) {
                error("Таймаут ${pingConfig.timeoutMs / 1000} с")
            }
        } finally {
            synchronized(this@XrayPingEngine) {
                if (activeRun === run) activeRun = null
            }
            closeRun(run)
            configFile.delete()
        }
    }

    private suspend fun waitForPort(process: Process, port: Int) {
        repeat(100) {
            coroutineContext.ensureActive()
            if (!isAlive(process)) error("Xray завершился при запуске пинга")
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 50) }
                return
            } catch (_: Exception) {
                delay(40)
            }
        }
        error("Xray не открыл тестовый SOCKS-порт")
    }

    private fun httpPing(
        run: PingRun,
        socksPort: Int,
        url: String,
        timeoutMs: Int
    ): Long {
        val uri = URI(url)
        val targetPort = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
        val started = System.nanoTime()
        val rawSocket = socksConnect(socksPort, uri.host, targetPort, timeoutMs)
        registerSocket(run, rawSocket)
        val socket = if (uri.scheme == "https") {
            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(rawSocket, uri.host, targetPort, true)
                .apply { soTimeout = timeoutMs }
        } else {
            rawSocket
        }
        registerSocket(run, socket)
        socket.use {
            val path = buildString {
                append(uri.rawPath?.ifBlank { "/" } ?: "/")
                if (!uri.rawQuery.isNullOrBlank()) append('?').append(uri.rawQuery)
            }
            it.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: ${uri.host}\r\nConnection: close\r\nUser-Agent: SA05-Xray-Ping\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            it.getOutputStream().flush()
            if (it.getInputStream().read() < 0) throw EOFException("HTTP-сервер закрыл соединение")
            return (System.nanoTime() - started) / 1_000_000
        }
    }

    private fun registerProcess(run: PingRun, process: Process) {
        synchronized(this) {
            if (activeRun === run) {
                run.process = process
                return
            }
        }
        process.destroy()
        error("Пинг отменён")
    }

    private fun registerSocket(run: PingRun, socket: Socket) {
        synchronized(this) {
            if (activeRun === run) {
                run.socket = socket
                return
            }
        }
        socket.close()
        error("Пинг отменён")
    }

    private fun closeRun(run: PingRun?) {
        if (run == null) return
        try {
            run.socket?.close()
        } catch (_: Exception) {
        }
        try {
            run.process?.inputStream?.close()
        } catch (_: Exception) {
        }
        try {
            run.process?.destroy()
        } catch (_: Exception) {
        }
        run.socket = null
        run.process = null
    }

    private fun socksConnect(port: Int, host: String, targetPort: Int, timeoutMs: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), timeoutMs)
        socket.soTimeout = timeoutMs
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val greeting = readExactly(input, 2)
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
            socket.close()
            error("SOCKS5 handshake отклонён")
        }
        val hostBytes = host.toByteArray(Charsets.UTF_8)
        require(hostBytes.size <= 255) { "Слишком длинное имя хоста" }
        output.write(
            byteArrayOf(0x05, 0x01, 0x00, 0x03, hostBytes.size.toByte()) +
                hostBytes +
                byteArrayOf((targetPort shr 8).toByte(), targetPort.toByte())
        )
        output.flush()
        val response = readExactly(input, 4)
        if (response[1] != 0x00.toByte()) {
            socket.close()
            error("Outbound отклонил соединение: SOCKS ${response[1].toInt() and 0xff}")
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

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun isAlive(process: Process): Boolean = try {
        process.exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }

    private fun copyGeoAssets() {
        listOf("geoip.dat", "geosite.dat").forEach { name ->
            val output = File(context.filesDir, name)
            if (output.exists() && output.length() > 0) return@forEach
            context.assets.open(name).use { input ->
                output.outputStream().use { input.copyTo(it) }
            }
        }
    }
}
