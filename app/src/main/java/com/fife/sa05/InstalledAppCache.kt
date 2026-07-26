package com.fife.sa05

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Enumerating every installed application is slow enough to be visible on a cold start, and the
 * result only changes when a package is installed or removed. Cache it in memory and refresh on
 * those broadcasts instead of rebuilding the list every time the screen is created.
 */
internal object InstalledAppCache {
    @Volatile
    private var cache: List<InstalledApp> = emptyList()

    fun cached(): List<InstalledApp> = cache

    /** Emits the cached list immediately, then again whenever packages change. */
    fun observe(context: Context): Flow<List<InstalledApp>> = callbackFlow {
        val appContext = context.applicationContext

        suspend fun publish() {
            val apps = withContext(Dispatchers.IO) { load(appContext) }
            cache = apps
            trySend(apps)
        }

        if (cache.isNotEmpty()) trySend(cache)
        launch { publish() }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                launch { publish() }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        appContext.registerReceiver(receiver, filter)
        awaitClose { runCatching { appContext.unregisterReceiver(receiver) } }
    }.flowOn(Dispatchers.Default)

    private fun load(context: Context): List<InstalledApp> {
        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val installed = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        return installed.asSequence()
            .filter { it.packageName != context.packageName }
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                InstalledApp(
                    label = packageManager.getApplicationLabel(it).toString(),
                    packageName = it.packageName
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}
