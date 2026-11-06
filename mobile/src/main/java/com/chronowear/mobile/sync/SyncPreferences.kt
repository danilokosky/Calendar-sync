package com.chronowear.mobile.sync

import android.content.Context
import android.content.SharedPreferences

data class SyncLogEntry(
    val timestampMs: Long,
    val eventsSentCount: Int,
    val calendarsCount: Int,
    val horizonDays: Int,
    val triggerSource: String,
    val status: String
)

class SyncPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("chronowear_mobile_prefs", Context.MODE_PRIVATE)

    fun getSelectedCalendarIds(): Set<String> {
        return prefs.getStringSet(KEY_CALENDARS, emptySet()) ?: emptySet()
    }

    fun setSelectedCalendarIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_CALENDARS, HashSet(ids)).apply()
    }

    fun getHorizonDays(): Int {
        return prefs.getInt(KEY_HORIZON_DAYS, 5)
    }

    fun setHorizonDays(days: Int) {
        prefs.edit().putInt(KEY_HORIZON_DAYS, days).apply()
    }

    fun getProgressWindowMinutes(): Int {
        return prefs.getInt(KEY_PROGRESS_WINDOW_MIN, 60)
    }

    fun setProgressWindowMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_PROGRESS_WINDOW_MIN, minutes).apply()
    }

    fun isBackgroundServiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_BACKGROUND_SERVICE_ENABLED, false)
    }

    fun setBackgroundServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_SERVICE_ENABLED, enabled).apply()
    }

    fun isCalendarEventsSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_CALENDAR_EVENTS_SYNC_ENABLED, false)
    }

    fun setCalendarEventsSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_EVENTS_SYNC_ENABLED, enabled).apply()
    }

    fun isNotificationsSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATIONS_SYNC_ENABLED, false)
    }

    fun setNotificationsSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_SYNC_ENABLED, enabled).apply()
    }

    fun isMediaPlaybackSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_MEDIA_PLAYBACK_SYNC_ENABLED, false)
    }

    fun setMediaPlaybackSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_PLAYBACK_SYNC_ENABLED, enabled).apply()
    }

    fun getSyncIntervalMinutes(): Long {
        return prefs.getLong(KEY_SYNC_INTERVAL_MIN, 15L)
    }

    fun setSyncIntervalMinutes(minutes: Long) {
        prefs.edit().putLong(KEY_SYNC_INTERVAL_MIN, minutes).apply()
    }

    fun isAutoSyncOnCalendarChangeEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SYNC_ON_CHANGE, true)
    }

    fun setAutoSyncOnCalendarChangeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ON_CHANGE, enabled).apply()
    }

    fun getChangeDebounceSeconds(): Int {
        return prefs.getInt(KEY_CHANGE_DEBOUNCE_SEC, 5)
    }

    fun setChangeDebounceSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_CHANGE_DEBOUNCE_SEC, seconds).apply()
    }

    fun markWatchAppInstalledOnNode(nodeId: String) {
        val current = (prefs.getStringSet(KEY_VERIFIED_WATCH_APP_NODES, emptySet()) ?: emptySet()).toMutableSet()
        current.add(nodeId)
        prefs.edit()
            .putStringSet(KEY_VERIFIED_WATCH_APP_NODES, current)
            .putLong("pong_ts_$nodeId", System.currentTimeMillis())
            .apply()
    }

    fun isWatchAppVerifiedOnNode(nodeId: String): Boolean {
        val set = prefs.getStringSet(KEY_VERIFIED_WATCH_APP_NODES, emptySet()) ?: emptySet()
        if (!set.contains(nodeId)) return false
        val ts = prefs.getLong("pong_ts_$nodeId", 0L)
        return (System.currentTimeMillis() - ts) < 12 * 60 * 60 * 1000L
    }

    fun recordLastSync(
        timestampMs: Long,
        eventsCount: Int,
        calendarsCount: Int,
        triggerSource: String,
        status: String
    ) {
        prefs.edit()
            .putLong(KEY_LAST_SYNC_MS, timestampMs)
            .putInt(KEY_LAST_SYNC_COUNT, eventsCount)
            .putInt(KEY_LAST_CAL_COUNT, calendarsCount)
            .putString(KEY_LAST_TRIGGER_SOURCE, triggerSource)
            .putString(KEY_LAST_SYNC_STATUS, status)
            .apply()
    }

    fun getLastSyncSummary(): SyncLogEntry {
        return SyncLogEntry(
            timestampMs = prefs.getLong(KEY_LAST_SYNC_MS, 0L),
            eventsSentCount = prefs.getInt(KEY_LAST_SYNC_COUNT, 0),
            calendarsCount = prefs.getInt(KEY_LAST_CAL_COUNT, 0),
            horizonDays = getHorizonDays(),
            triggerSource = prefs.getString(KEY_LAST_TRIGGER_SOURCE, "Nenhum") ?: "Nenhum",
            status = prefs.getString(KEY_LAST_SYNC_STATUS, "Aguardando ativação pelo usuário") ?: ""
        )
    }

    companion object {
        const val ACTION_SYNC_COMPLETED = "com.chronowear.mobile.ACTION_SYNC_COMPLETED"
        private const val KEY_CALENDARS = "selected_calendar_ids"
        private const val KEY_HORIZON_DAYS = "horizon_days"
        private const val KEY_PROGRESS_WINDOW_MIN = "progress_window_minutes"
        private const val KEY_BACKGROUND_SERVICE_ENABLED = "background_service_enabled"
        private const val KEY_CALENDAR_EVENTS_SYNC_ENABLED = "calendar_events_sync_enabled"
        private const val KEY_NOTIFICATIONS_SYNC_ENABLED = "notifications_sync_enabled"
        private const val KEY_MEDIA_PLAYBACK_SYNC_ENABLED = "media_playback_sync_enabled"
        private const val KEY_SYNC_INTERVAL_MIN = "sync_interval_minutes"
        private const val KEY_AUTO_SYNC_ON_CHANGE = "auto_sync_on_calendar_change"
        private const val KEY_CHANGE_DEBOUNCE_SEC = "change_debounce_seconds"
        private const val KEY_VERIFIED_WATCH_APP_NODES = "verified_watch_app_nodes"
        private const val KEY_LAST_SYNC_MS = "last_sync_ms"
        private const val KEY_LAST_SYNC_COUNT = "last_sync_count"
        private const val KEY_LAST_CAL_COUNT = "last_cal_count"
        private const val KEY_LAST_TRIGGER_SOURCE = "last_trigger_source"
        private const val KEY_LAST_SYNC_STATUS = "last_sync_status"
    }
}
