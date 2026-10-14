package org.openbased.android.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.openbased.android.OpenBasedApp
import org.openbased.android.api.ApiException
import java.io.IOException
import java.util.concurrent.TimeUnit

private val ONLINE = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

/** Brings the local catalogue up to date. */
class CatalogSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OpenBasedApp).container
        if (!container.auth.signedIn.value) return Result.success()
        return try {
            container.catalog.sync()
            Result.success()
        } catch (e: ApiException) {
            if (e.status >= 500) Result.retry() else Result.failure()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    companion object {
        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "catalog-sync", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<CatalogSyncWorker>().setConstraints(ONLINE).build(),
            )
        }

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "catalog-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<CatalogSyncWorker>(6, TimeUnit.HOURS).setConstraints(ONLINE).build(),
            )
        }
    }
}

/** Sends queued playback progress once the device is online. */
class ProgressSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OpenBasedApp).container
        if (!container.auth.signedIn.value) return Result.success()
        return try {
            container.progress.flush()
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "progress-sync", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ProgressSyncWorker>()
                    .setConstraints(ONLINE)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
