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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object SubscriptionRefreshPolicy {
    fun intervalHours(subscription: SubscriptionState): Long? {
        val interval = subscription.updateIntervalHours ?: return null
        return interval.toLong().takeIf {
            it > 0L && subscription.url.startsWith("https://") && subscription.profiles.isNotEmpty()
        }
    }

    fun shouldRetry(error: Throwable): Boolean = error is IOException
}

internal object SubscriptionRefreshRunner {
    private val mutex = Mutex()

    suspend fun refresh(context: Context, url: String): SubscriptionUpdateResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            SubscriptionRepository(context.applicationContext).update(url)
        }
    }
}

internal object SubscriptionRefreshScheduler {
    private const val UNIQUE_WORK_NAME = "subscription-periodic-refresh"
    private const val WORK_TAG = "subscription-refresh"

    fun sync(context: Context, subscription: SubscriptionState) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val intervalHours = SubscriptionRefreshPolicy.intervalHours(subscription)
        if (intervalHours == null) {
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
            intervalHours,
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
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

class SubscriptionRefreshWorker(
    appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val current = SubscriptionRepository(applicationContext).load()
        if (SubscriptionRefreshPolicy.intervalHours(current) == null) return Result.success()
        return try {
            val update = SubscriptionRefreshRunner.refresh(applicationContext, current.url)
            val refreshed = when (update) {
                is SubscriptionUpdateResult.Updated -> update.state
                is SubscriptionUpdateResult.NotModified -> update.state
            }
            SubscriptionRefreshScheduler.sync(applicationContext, refreshed)
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("SubscriptionRefresh", "Background refresh failed", error)
            if (SubscriptionRefreshPolicy.shouldRetry(error)) Result.retry() else Result.success()
        }
    }
}
