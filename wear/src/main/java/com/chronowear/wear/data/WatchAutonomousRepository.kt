package com.chronowear.wear.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CachedWearEvent(
    val id: String,
    val calendarId: String,
    val title: String,
    val location: String,
    val startEpochMs: Long,
    val endEpochMs: Long
) {
    fun formatStartHHMM(): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(Date(startEpochMs))
    }

    fun formatRemaining(nowMs: Long): String {
        val diffMs = (startEpochMs - nowMs).coerceAtLeast(0L)
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val mins = totalMinutes % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }
}

class WatchAutonomousRepository private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "chronowear_autonomous.db", null, 1) {

    private val prefs = context.applicationContext.getSharedPreferences(
        "chronowear_wear_prefs",
        Context.MODE_PRIVATE
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE cached_events (
                id TEXT NOT NULL,
                calendar_id TEXT NOT NULL,
                title TEXT NOT NULL,
                location TEXT NOT NULL,
                start_epoch_ms INTEGER NOT NULL,
                end_epoch_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cached_events")
        onCreate(db)
    }

    fun replaceHorizonCache(
        eventsJson: String,
        syncTimestampMs: Long,
        horizonDays: Int,
        progressWindowMinutes: Int,
        cardPngBase64: String? = null
    ) {
        val editor = prefs.edit()
            .putLong("last_sync_ms", syncTimestampMs)
            .putInt("horizon_days", horizonDays)
            .putInt("progress_window_minutes", progressWindowMinutes)
        if (!cardPngBase64.isNullOrBlank()) {
            editor.putString("phone_rendered_card_png_b64", cardPngBase64)
            editor.putLong("phone_rendered_card_ms", syncTimestampMs)
        }
        editor.apply()

        val arr = JSONArray(eventsJson)
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("cached_events", null, null)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cv = ContentValues().apply {
                    put("id", obj.optString("id"))
                    put("calendar_id", obj.optString("calendarId"))
                    put("title", obj.optString("title", "Evento"))
                    put("location", obj.optString("location", ""))
                    put("start_epoch_ms", obj.optLong("startEpochMs"))
                    put("end_epoch_ms", obj.optLong("endEpochMs"))
                }
                db.insert("cached_events", null, cv)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getPhoneRenderedCardBase64IfFresh(nowMs: Long, maxAgeMs: Long = 90_000L): String? {
        val renderedMs = prefs.getLong("phone_rendered_card_ms", 0L)
        if (renderedMs <= 0L || (nowMs - renderedMs) > maxAgeMs) return null
        return prefs.getString("phone_rendered_card_png_b64", null)
    }

    /**
     * Retorna sempre os próximos 3 eventos que ainda vão começar a partir de `nowMs`,
     * funcionando de forma 100% autônoma no relógio mesmo após dias sem Bluetooth.
     */
    fun getNextThreeUpcomingEvents(nowMs: Long): List<CachedWearEvent> {
        val list = mutableListOf<CachedWearEvent>()
        val db = readableDatabase
        db.query(
            "cached_events",
            arrayOf("id", "calendar_id", "title", "location", "start_epoch_ms", "end_epoch_ms"),
            "start_epoch_ms > ?",
            arrayOf(nowMs.toString()),
            null,
            null,
            "start_epoch_ms ASC",
            "3"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    CachedWearEvent(
                        id = cursor.getString(0),
                        calendarId = cursor.getString(1),
                        title = cursor.getString(2),
                        location = cursor.getString(3),
                        startEpochMs = cursor.getLong(4),
                        endEpochMs = cursor.getLong(5)
                    )
                )
            }
        }
        return list
    }

    fun getProgressWindowMinutes(): Int {
        return prefs.getInt("progress_window_minutes", 60)
    }

    fun getHorizonDays(): Int {
        return prefs.getInt("horizon_days", 5)
    }

    fun getLastSyncTimestampMs(): Long {
        return prefs.getLong("last_sync_ms", 0L)
    }

    companion object {
        @Volatile
        private var INSTANCE: WatchAutonomousRepository? = null

        fun getInstance(context: Context): WatchAutonomousRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WatchAutonomousRepository(context).also { INSTANCE = it }
            }
        }
    }
}
