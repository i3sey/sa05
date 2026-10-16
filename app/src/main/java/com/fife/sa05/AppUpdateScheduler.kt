package com.fife.sa05

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppUpdateScheduler {
    private const val UNIQUE_WORK_NAME = "app-update-periodic-check"
    private const val WORK_TAG = "app-update-check"
    const val CHECK_INTERVAL_HOURS = 12L

    fun sync(context: Context) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(
            CHECK_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15L,
                TimeUnit.MINUTES
            )
            .addTag(WORK_TAG)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}

class AppUpdateWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        return try {
            val repository = AppUpdateRepository(applicationContext)
            val state = withContext(Dispatchers.IO) {
                repository.checkLatestRelease(
                    BuildConfig.VERSION_CODE,
                    BuildConfig.VERSION_NAME
                )
            }
            when (state) {
                is AppUpdateState.Available -> {
                    AppUpdateNotification.notifyIfNew(applicationContext, state.release)
                }
                AppUpdateState.UpToDate -> {
                    AppUpdateNotification.onUpToDate(applicationContext)
                }
                else -> Unit
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            Log.w("AppUpdateWorker", "Background app update network error", error)
            Result.retry()
        } catch (error: Exception) {
            Log.w("AppUpdateWorker", "Background app update check failed", error)
            Result.success()
        }
    }
}
