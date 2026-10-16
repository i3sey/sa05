package com.fife.sa05

import android.content.Context

object AppUpdatePreferences {
    private const val PREFS_NAME = "sa05_app_update"
    private const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
    private const val KEY_LAST_AVAILABLE_RELEASE = "last_available_release"

    fun getLastNotifiedVersion(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_NOTIFIED_VERSION, null)
    }

    fun setLastNotifiedVersion(context: Context, version: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_NOTIFIED_VERSION, version)
            .apply()
    }

    fun clearLastNotifiedVersion(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_NOTIFIED_VERSION)
            .apply()
    }

    fun getLastAvailableRelease(context: Context): AppRelease? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_AVAILABLE_RELEASE, null) ?: return null
        return AppRelease.fromJson(raw)
    }

    fun saveLastAvailableRelease(context: Context, release: AppRelease) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_AVAILABLE_RELEASE, release.toJson())
            .apply()
    }

    fun clearLastAvailableRelease(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_AVAILABLE_RELEASE)
            .apply()
    }
}
