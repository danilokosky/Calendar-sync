package com.chronowear.mobile.sync

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CalendarSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val prefs = SyncPreferences(applicationContext)
            val selectedCalendarIds = prefs.getSelectedCalendarIds()
            val horizonDays = prefs.getHorizonDays() // 2, 3, 5 ou 7 dias
            val progressWindowMinutes = prefs.getProgressWindowMinutes() // 60m (1h) ou 120m (2h)

            val nowMs = System.currentTimeMillis()
            val endHorizonMs = nowMs + TimeUnit.DAYS.toMillis(horizonDays.toLong())

            val eventsJsonArray = queryUpcomingEvents(
                selectedCalendarIds = selectedCalendarIds,
                startMs = nowMs,
                endMs = endHorizonMs
            )

            val putDataMapReq = PutDataMapRequest.create(DATA_LAYER_PATH).apply {
                dataMap.putString("events_json", eventsJsonArray.toString())
                dataMap.putLong("sync_timestamp_ms", nowMs)
                dataMap.putInt("horizon_days", horizonDays)
                dataMap.putInt("progress_window_minutes", progressWindowMinutes)
            }

            val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
            Wearable.getDataClient(applicationContext).putDataItem(putDataReq).await()

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun queryUpcomingEvents(
        selectedCalendarIds: Set<String>,
        startMs: Long,
        endMs: Long
    ): JSONArray {
        val result = JSONArray()
        if (selectedCalendarIds.isEmpty()) return result

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END
        )

        val sortOrder = "${CalendarContract.Instances.BEGIN} ASC"

        applicationContext.contentResolver.query(
            builder.build(),
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(1).toString()
                if (!selectedCalendarIds.contains(calId)) continue

                val beginMs = cursor.getLong(4)
                val endEventMs = cursor.getLong(5)
                if (endEventMs <= startMs) continue

                val item = JSONObject().apply {
                    put("id", cursor.getLong(0).toString())
                    put("calendarId", calId)
                    put("title", cursor.getString(2) ?: "Sem título")
                    put("location", cursor.getString(3) ?: "")
                    put("startEpochMs", beginMs)
                    put("endEpochMs", endEventMs)
                }
                result.put(item)
            }
        }
        return result
    }

    companion object {
        const val DATA_LAYER_PATH = "/chronowear/calendar_horizon_payload"
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
