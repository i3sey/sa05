package com.fife.sa05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit

class AppUpdateRepository(
    private val context: Context,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val downloadTimeoutMs: Long = DEFAULT_DOWNLOAD_TIMEOUT_MS,
    private val clockMs: () -> Long = {
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
    }
) {
    companion object {
        private const val OWNER = "i3sey"
        private const val REPO = "sa05"
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        private const val DEFAULT_TIMEOUT_MS = 15_000
        private const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1000L

        internal fun parseLatestRelease(body: String): AppRelease {
            val root = JSONObject(body)
            val assets = root.optJSONArray("assets") ?: JSONArray()
            val asset = (0 until assets.length())
                .mapNotNull { index -> assets.optJSONObject(index) }
                .filter { item ->
                    val name = item.optString("name")
                    val contentType = item.optString("content_type")
                    name.endsWith(".apk", ignoreCase = true) ||
                        contentType == "application/vnd.android.package-archive"
                }
                .minByOrNull { it.apkAssetRank() }
                ?: throw IllegalArgumentException("В релизе нет APK-asset")

            val notes = root.optString("body").orEmpty().trim()
            val metadata = parseMetadata(notes)
            val versionName = metadata.versionName
                ?: root.optString("tag_name").removePrefix("v")
            val releaseNotes = metadata.notes.ifBlank { notes }
            return AppRelease(
                tagName = root.optString("tag_name"),
                versionName = versionName.ifBlank { "unknown" },
                versionCode = metadata.versionCode,
                name = root.optString("name").ifBlank { root.optString("tag_name") },
                notes = releaseNotes,
                assetName = asset.optString("name"),
                assetUrl = asset.optString("browser_download_url"),
                htmlUrl = root.optString("html_url"),
                publishedAt = root.optString("published_at")
            )
        }

        internal fun isNewer(
            currentVersionCode: Int,
            currentVersionName: String,
            release: AppRelease
        ): Boolean {
            val releaseVersionCode = release.versionCode
            if (releaseVersionCode != null) return releaseVersionCode > currentVersionCode
            return compareVersionNames(release.versionName, currentVersionName) > 0
        }

        private fun parseMetadata(body: String): ParsedMetadata {
            val lines = body.lineSequence().toList()
            val meta = linkedMapOf<String, String>()
            var index = 0
            while (index < lines.size) {
                val line = lines[index].trimEnd()
                if (line.isBlank()) {
                    index++
                    break
                }
                val separator = line.indexOf(':')
                if (separator <= 0) break
                val key = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                if (key !in setOf("versionName", "versionCode", "asset")) break
                meta[key] = value
                index++
            }
            val looseVersionName = findMetadataValue(body, "versionName")
            val looseVersionCode = findMetadataValue(body, "versionCode")?.toIntOrNull()
            return ParsedMetadata(
                versionName = meta["versionName"] ?: looseVersionName,
                versionCode = meta["versionCode"]?.toIntOrNull() ?: looseVersionCode,
                notes = lines.drop(index)
                    .filterNot { it.isMetadataLine() }
                    .joinToString("\n")
                    .trim()
            )
        }

        private fun findMetadataValue(body: String, key: String): String? {
            val pattern = Regex(
                pattern = """(?im)^\s*(?:[-*]\s*)?(?:\*\*)?\Q$key\E(?:\*\*)?\s*[:=]\s*(\S+)\s*$"""
            )
            return pattern.find(body)?.groupValues?.getOrNull(1)?.trim()
        }

        private fun String.isMetadataLine(): Boolean {
            val trimmed = trim()
            return listOf("versionName", "versionCode", "asset").any { key ->
                trimmed.matches(Regex("""(?:[-*]\s*)?(?:\*\*)?\Q$key\E(?:\*\*)?\s*[:=].*"""))
            }
        }

        private fun compareVersionNames(left: String, right: String): Int {
            val leftParts = left.toVersionParts()
            val rightParts = right.toVersionParts()
            val max = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until max) {
                val leftPart = leftParts.getOrElse(index) { 0 }
                val rightPart = rightParts.getOrElse(index) { 0 }
                if (leftPart != rightPart) return leftPart.compareTo(rightPart)
            }
            return 0
        }

        private fun String.toVersionParts(): List<Int> {
            return removePrefix("v")
                .split(Regex("""[^0-9]+"""))
                .filter { it.isNotBlank() }
                .mapNotNull { it.toIntOrNull() }
        }

        private fun JSONObject.apkAssetRank(): Int {
            val name = optString("name").lowercase()
            return when {
                "release" in name && "debug" !in name -> 0
                "debug" !in name -> 1
                else -> 2
            }
        }

        private data class ParsedMetadata(
            val versionName: String?,
            val versionCode: Int?,
            val notes: String
        )
    }

    suspend fun checkLatestRelease(currentVersionCode: Int, currentVersionName: String): AppUpdateState {
        val connection = connectionFactory(URL(LATEST_RELEASE_URL)).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "SA05-Xray")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IllegalArgumentException("GitHub вернул HTTP $status")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val release = parseLatestRelease(body)
            return if (isNewer(currentVersionCode, currentVersionName, release)) {
                AppUpdateState.Available(release)
            } else {
                AppUpdateState.UpToDate
            }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadRelease(
        release: AppRelease,
        onProgress: (Int) -> Unit = {}
    ): File {
        val targetDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val fileName = safeApkFileName(release)
        val target = File(targetDir, fileName)
        val partial = File(targetDir, "$fileName.part")
        if (partial.exists() && !partial.delete()) {
            throw IOException("Не удалось удалить временный файл обновления")
        }

        var completed = false
        try {
            FileOutputStream(partial).use { output ->
                val connection = connectionFactory(URL(release.assetUrl)).apply {
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = true
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/octet-stream")
                    setRequestProperty("User-Agent", "SA05-Xray")
                }
                try {
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        throw IllegalArgumentException("GitHub вернул HTTP $status при скачивании APK")
                    }
                    val length = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
                    val startedAt = clockMs()
                    var lastProgress = -1
                    fun ensureNotTimedOut() {
                        if (clockMs() - startedAt > downloadTimeoutMs) {
                            throw SocketTimeoutException("Скачивание APK заняло слишком много времени")
                        }
                    }
                    fun emitProgress(progress: Int) {
                        val visibleProgress = progress.coerceIn(0, 99)
                        if (visibleProgress != lastProgress) {
                            lastProgress = visibleProgress
                            onProgress(visibleProgress)
                        }
                    }

                    connection.inputStream.use { input ->
                        val buffer = ByteArray(32 * 1024)
                        var readTotal = 0L
                        while (true) {
                            ensureNotTimedOut()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            ensureNotTimedOut()
                            if (length > 0) {
                                emitProgress(((readTotal * 100) / length).toInt())
                            }
                        }
                        if (length > 0 && readTotal != length) {
                            throw EOFException("APK скачан не полностью: $readTotal из $length байт")
                        }
                    }
                    output.fd.sync()
                } finally {
                    connection.disconnect()
                }
            }

            if (target.exists() && !target.delete()) {
                throw IOException("Не удалось заменить старый APK обновления")
            }
            if (!partial.renameTo(target)) {
                throw IOException("Не удалось сохранить APK обновления")
            }
            completed = true
            onProgress(100)
            return target
        } finally {
            if (!completed && partial.exists()) {
                partial.delete()
            }
        }
    }

    private fun safeApkFileName(release: AppRelease): String {
        val rawName = release.assetName.ifBlank { "${release.tagName}.apk" }
        val fileName = File(rawName).name
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
            .ifBlank { "sa05-update.apk" }
        return if (fileName.endsWith(".apk", ignoreCase = true)) fileName else "$fileName.apk"
    }
}
