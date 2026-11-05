package com.chronowear.mobile.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class CalendarSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            CalendarSyncEngine.performFullSyncToWatch(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ChronoWearPeriodicCalendarSync"

        fun schedulePeriodicSync(context: Context, intervalMinutes: Long = 15L) {
            val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
                intervalMinutes.coerceAtLeast(15L),
                TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun enqueueImmediateSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
