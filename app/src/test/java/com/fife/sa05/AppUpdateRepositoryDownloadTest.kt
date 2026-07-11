package com.fife.sa05

import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateRepositoryDownloadTest {
    @Test
    fun stalledFinalByteFailsInsteadOfStayingAtAlmostReady() {
        val cacheRoot = Files.createTempDirectory("sa05-update-stall-").toFile()
        val sentAlmostAll = CountDownLatch(1)
        val releaseSocket = CountDownLatch(1)
        val server = serveOnce { socket ->
            socket.readHttpRequest()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: keep-alive\r\n\r\n".toByteArray())
                write(ByteArray(999) { 0x41 })
                flush()
            }
            sentAlmostAll.countDown()
            releaseSocket.await(5, TimeUnit.SECONDS)
        }
        try {
            val repository = repository(cacheRoot, timeoutMs = 200, downloadTimeoutMs = 1_000)
            val progress = mutableListOf<Int>()
            val elapsedMs = measureElapsedMs {
                assertThrows(SocketTimeoutException::class.java) {
                    runBlocking { repository.downloadRelease(release(server)) { progress += it } }
                }
            }

            assertTrue("Server did not send the 99% prefix", sentAlmostAll.await(1, TimeUnit.SECONDS))
            assertTrue("Download did not fail promptly: ${elapsedMs}ms", elapsedMs < 4_000)
            assertTrue("Progress should reach the visible 99% state", 99 in progress)
            assertFalse(File(cacheRoot, "updates/sa05.apk").exists())
            assertFalse(File(cacheRoot, "updates/sa05.apk.part").exists())
        } finally {
            releaseSocket.countDown()
            server.close()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun failedDownloadDoesNotReplacePreviousApk() {
        val cacheRoot = Files.createTempDirectory("sa05-update-existing-").toFile()
        val updates = File(cacheRoot, "updates").apply { mkdirs() }
        val existingApk = File(updates, "sa05.apk").apply { writeText("previous") }
        val server = serveOnce { socket ->
            socket.readHttpRequest()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\n".toByteArray())
                write(ByteArray(5) { 0x42 })
                flush()
            }
        }
        try {
            val repository = repository(cacheRoot, timeoutMs = 500, downloadTimeoutMs = 2_000)

            assertThrows(Exception::class.java) {
                runBlocking { repository.downloadRelease(release(server)) }
            }

            assertEquals("previous", existingApk.readText())
            assertFalse(File(updates, "sa05.apk.part").exists())
        } finally {
            server.close()
            cacheRoot.deleteRecursively()
        }
    }

    @Test
    fun successfulDownloadRenamesPartialAndReportsHundredOnlyAfterSave() {
        val cacheRoot = Files.createTempDirectory("sa05-update-success-").toFile()
        val server = serveOnce { socket ->
            socket.readHttpRequest()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 1000\r\nConnection: close\r\n\r\n".toByteArray())
                write(ByteArray(1000) { 0x43 })
                flush()
            }
        }
        try {
            val repository = repository(cacheRoot, timeoutMs = 500, downloadTimeoutMs = 2_000)
            val progress = mutableListOf<Int>()

            val file = runBlocking { repository.downloadRelease(release(server)) { progress += it } }

            assertEquals("sa05.apk", file.name)
            assertEquals(1000L, file.length())
            assertFalse(File(cacheRoot, "updates/sa05.apk.part").exists())
            assertEquals(100, progress.last())
            assertTrue(progress.dropLast(1).all { it < 100 })
        } finally {
            server.close()
            cacheRoot.deleteRecursively()
        }
    }

    private fun repository(
        cacheRoot: File,
        timeoutMs: Int,
        downloadTimeoutMs: Long
    ) = AppUpdateRepository(
        context = object : ContextWrapper(null) {
            override fun getCacheDir(): File = cacheRoot
        },
        timeoutMs = timeoutMs,
        downloadTimeoutMs = downloadTimeoutMs
    )

    private fun release(server: ServerSocket) = AppRelease(
        tagName = "v-test",
        versionName = "test",
        versionCode = null,
        name = "test",
        notes = "",
        assetName = "sa05.apk",
        assetUrl = "http://127.0.0.1:${server.localPort}/sa05.apk",
        htmlUrl = "",
        publishedAt = ""
    )

    private fun serveOnce(handler: (Socket) -> Unit): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            try {
                server.accept().use(handler)
            } catch (_: IOException) {
                // Test cleanup can close the socket before accept/read completes.
            }
        }.apply {
            isDaemon = true
            start()
        }
        return server
    }

    private fun Socket.readHttpRequest() {
        val reader = getInputStream().bufferedReader()
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) return
        }
    }

    private fun measureElapsedMs(block: () -> Unit): Long {
        val started = System.nanoTime()
        block()
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    }
}
