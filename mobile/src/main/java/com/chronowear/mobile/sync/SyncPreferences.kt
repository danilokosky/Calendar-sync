package com.chronowear.mobile.sync

import android.content.Context
import android.content.SharedPreferences

data class SyncLogEntry(
    val timestampMs: Long,
    val eventsSentCount: Int,
    val calendarsCount: Int,
    val horizonDays: Int,
    val status: String
)

class SyncPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("chronowear_mobile_prefs", Context.MODE_PRIVATE)

    fun hasInitializedCalendars(): Boolean {
        return prefs.getBoolean(KEY_INIT_CALENDARS, false)
    }

    fun markCalendarsInitialized() {
        prefs.edit().putBoolean(KEY_INIT_CALENDARS, true).apply()
    }

    fun getSelectedCalendarIds(): Set<String> {
        return prefs.getStringSet(KEY_CALENDARS, emptySet()) ?: emptySet()
    }

    fun setSelectedCalendarIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_CALENDARS, HashSet(ids)).apply()
    }

    fun getHorizonDays(): Int {
        return prefs.getInt(KEY_HORIZON_DAYS, 5) // 2, 3, 5 ou 7 dias
    }

    fun setHorizonDays(days: Int) {
        prefs.edit().putInt(KEY_HORIZON_DAYS, days).apply()
    }

    fun getProgressWindowMinutes(): Int {
        return prefs.getInt(KEY_PROGRESS_WINDOW_MIN, 60) // 30, 60 (1h), 120 (2h) ou 180 (3h)
    }

    fun setProgressWindowMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_PROGRESS_WINDOW_MIN, minutes).apply()
    }

    fun getSyncIntervalMinutes(): Long {
        return prefs.getLong(KEY_SYNC_INTERVAL_MIN, 15L)
    }

    fun setSyncIntervalMinutes(minutes: Long) {
        prefs.edit().putLong(KEY_SYNC_INTERVAL_MIN, minutes).apply()
    }

    fun isForegroundMonitorEnabled(): Boolean {
        return prefs.getBoolean(KEY_FOREGROUND_SERVICE, true)
    }

    fun setForegroundMonitorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FOREGROUND_SERVICE, enabled).apply()
    }

    fun recordLastSync(timestampMs: Long, eventsCount: Int, calendarsCount: Int, status: String) {
        prefs.edit()
            .putLong(KEY_LAST_SYNC_MS, timestampMs)
            .putInt(KEY_LAST_SYNC_COUNT, eventsCount)
            .putInt(KEY_LAST_CAL_COUNT, calendarsCount)
            .putString(KEY_LAST_SYNC_STATUS, status)
            .apply()
    }

    fun getLastSyncSummary(): SyncLogEntry {
        return SyncLogEntry(
            timestampMs = prefs.getLong(KEY_LAST_SYNC_MS, 0L),
            eventsSentCount = prefs.getInt(KEY_LAST_SYNC_COUNT, 0),
            calendarsCount = prefs.getInt(KEY_LAST_CAL_COUNT, 0),
            horizonDays = getHorizonDays(),
            status = prefs.getString(KEY_LAST_SYNC_STATUS, "Aguardando primeira sincronização") ?: ""
        )
    }

    companion object {
        const val ACTION_SYNC_COMPLETED = "com.chronowear.mobile.ACTION_SYNC_COMPLETED"
        private const val KEY_INIT_CALENDARS = "initialized_calendars"
        private const val KEY_CALENDARS = "selected_calendar_ids"
        private const val KEY_HORIZON_DAYS = "horizon_days"
        private const val KEY_PROGRESS_WINDOW_MIN = "progress_window_minutes"
        private const val KEY_SYNC_INTERVAL_MIN = "sync_interval_minutes"
        private const val KEY_FOREGROUND_SERVICE = "foreground_service_enabled"
        private const val KEY_LAST_SYNC_MS = "last_sync_ms"
        private const val KEY_LAST_SYNC_COUNT = "last_sync_count"
        private const val KEY_LAST_CAL_COUNT = "last_cal_count"
        private const val KEY_LAST_SYNC_STATUS = "last_sync_status"
    }
}
