package com.chronowear.mobile.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class CalendarSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = SyncPreferences(applicationContext)
        if (!prefs.isBackgroundServiceEnabled() && inputData.getString("source") != "manual") {
            return Result.success()
        }

        return try {
            val source = inputData.getString("source")
                ?: "Leitura Periódica (${prefs.getSyncIntervalMinutes()} min)"
            CalendarSyncEngine.performFullSyncToWatch(applicationContext, source)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ChronoWearPeriodicCalendarSync"

        fun schedulePeriodicSync(context: Context, intervalMinutes: Long) {
            val prefs = SyncPreferences(context)
            if (!prefs.isBackgroundServiceEnabled()) {
                cancelPeriodicSync(context)
                return
            }

            val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
                intervalMinutes.coerceAtLeast(15L),
                TimeUnit.MINUTES
            )
                .setInputData(workDataOf("source" to "Leitura Periódica (${intervalMinutes}m)"))
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancelPeriodicSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        fun enqueueImmediateSync(context: Context, reason: String = "Alteração na Agenda") {
            val request = OneTimeWorkRequestBuilder<CalendarSyncWorker>()
                .setInputData(workDataOf("source" to reason))
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
