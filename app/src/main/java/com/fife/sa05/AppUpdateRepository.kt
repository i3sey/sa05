package com.fife.sa05

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AppUpdateRepository(private val context: Context) {
    companion object {
        private const val OWNER = "i3sey"
        private const val REPO = "sa05"
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
        private const val TIMEOUT_MS = 15_000

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
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
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
        val target = File(targetDir, release.assetName.ifBlank { "${release.tagName}.apk" })
        if (target.exists()) target.delete()
        target.outputStream().use { output ->
            val connection = (URL(release.assetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", "SA05-Xray")
            }
            try {
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw IllegalArgumentException("APK download failed: HTTP $status")
                }
                val length = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var readTotal = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        readTotal += read
                        if (length > 0) {
                            onProgress(((readTotal * 100) / length).toInt().coerceIn(0, 100))
                        }
                        read = input.read(buffer)
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        onProgress(100)
        return target
    }
}
