package com.fife.sa05

import org.json.JSONObject

data class AppRelease(
    val tagName: String,
    val versionName: String,
    val versionCode: Int?,
    val name: String,
    val notes: String,
    val assetName: String,
    val assetUrl: String,
    val htmlUrl: String,
    val publishedAt: String
) {
    fun toJson(): String {
        val root = JSONObject()
        root.put("tag_name", tagName)
        root.put("version_name", versionName)
        if (versionCode != null) {
            root.put("version_code", versionCode)
        }
        root.put("name", name)
        root.put("notes", notes)
        root.put("asset_name", assetName)
        root.put("asset_url", assetUrl)
        root.put("html_url", htmlUrl)
        root.put("published_at", publishedAt)
        return root.toString()
    }

    companion object {
        fun fromJson(jsonStr: String): AppRelease? {
            return runCatching {
                val root = JSONObject(jsonStr)
                AppRelease(
                    tagName = root.getString("tag_name"),
                    versionName = root.getString("version_name"),
                    versionCode = if (root.has("version_code") && !root.isNull("version_code")) {
                        root.getInt("version_code")
                    } else {
                        null
                    },
                    name = root.optString("name", ""),
                    notes = root.optString("notes", ""),
                    assetName = root.getString("asset_name"),
                    assetUrl = root.getString("asset_url"),
                    htmlUrl = root.optString("html_url", ""),
                    publishedAt = root.optString("published_at", "")
                )
            }.getOrNull()
        }
    }
}

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data object UpToDate : AppUpdateState
    data class Available(
        val release: AppRelease,
        val downloadedPath: String? = null,
        val downloadProgress: Int? = null
    ) : AppUpdateState
    data class Error(val message: String) : AppUpdateState
}
