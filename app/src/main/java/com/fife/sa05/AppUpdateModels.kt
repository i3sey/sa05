package com.fife.sa05

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
)

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
