package com.chronowear.mobile.sync

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.abs

data class SyncLogEntry(
    val timestampMs: Long,
    val eventsSentCount: Int,
    val calendarsCount: Int,
    val horizonDays: Int,
    val triggerSource: String,
    val status: String
)

data class WeatherTriggerAppOption(
    val packageName: String,
    val displayName: String,
    val categoryName: String,
    val categoryDescription: String,
    val isInstalledOnDevice: Boolean = false
)

data class MobileTaskItem(
    val id: String,
    val title: String,
    val dueHHMM: String,
    val sourceLabel: String,
    val completed: Boolean = false,
    val isTodayOrTimeless: Boolean = true,
    val scheduleCategory: String = "TODAY", // "TODAY", "TOMORROW", "UNDATED", "OVERDUE"
    val isOverdue: Boolean = false
)

data class MobileResolvedTasksSnapshot(
    val headerTitle: String,
    val rightBadgeText: String,
    val showTime: Boolean,
    val bucket: String,
    val displayTasks: List<MobileTaskItem>,
    val visibleTasks: List<MobileTaskItem> = displayTasks
)

class SyncPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("chronowear_mobile_prefs", Context.MODE_PRIVATE)

    fun getSelectedCalendarIds(): Set<String> {
        return prefs.getStringSet(KEY_CALENDARS, emptySet()) ?: emptySet()
    }

    fun setSelectedCalendarIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_CALENDARS, HashSet(ids)).commit()
    }

    fun getHorizonDays(): Int {
        return prefs.getInt(KEY_HORIZON_DAYS, 5)
    }

    fun setHorizonDays(days: Int) {
        prefs.edit().putInt(KEY_HORIZON_DAYS, days).commit()
    }

    fun getProgressWindowMinutes(): Int {
        return prefs.getInt(KEY_PROGRESS_WINDOW_MIN, 60)
    }

    fun setProgressWindowMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_PROGRESS_WINDOW_MIN, minutes).commit()
    }

    fun isBackgroundServiceEnabled(): Boolean {
        return prefs.getBoolean(KEY_BACKGROUND_SERVICE_ENABLED, false)
    }

    fun setBackgroundServiceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_SERVICE_ENABLED, enabled).commit()
    }

    fun isCalendarEventsSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_CALENDAR_EVENTS_SYNC_ENABLED_V2, true)
    }

    fun setCalendarEventsSyncEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_CALENDAR_EVENTS_SYNC_ENABLED_V2, enabled)
            .putBoolean(KEY_CALENDAR_EVENTS_SYNC_ENABLED, enabled)
            .commit()
    }

    fun isNotificationsSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFICATIONS_SYNC_ENABLED, true)
    }

    fun setNotificationsSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS_SYNC_ENABLED, enabled).commit()
    }

    fun isMediaPlaybackSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_MEDIA_PLAYBACK_SYNC_ENABLED, false)
    }

    fun setMediaPlaybackSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MEDIA_PLAYBACK_SYNC_ENABLED, enabled).commit()
    }

    fun getSyncIntervalMinutes(): Long {
        val raw = prefs.getLong(KEY_SYNC_INTERVAL_MIN, 60L)
        return when (raw) {
            15L, 30L, 60L, 120L -> raw
            else -> 60L
        }
    }

    fun setSyncIntervalMinutes(minutes: Long) {
        val valid = when (minutes) {
            15L, 30L, 60L, 120L -> minutes
            else -> minutes.coerceIn(15L, 120L)
        }
        prefs.edit().putLong(KEY_SYNC_INTERVAL_MIN, valid).commit()
    }

    fun isAutoSyncOnCalendarChangeEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SYNC_ON_CHANGE, true)
    }

    fun setAutoSyncOnCalendarChangeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ON_CHANGE, enabled).commit()
    }

    fun getChangeDebounceSeconds(): Int {
        return prefs.getInt(KEY_CHANGE_DEBOUNCE_SEC, 2)
    }

    fun setChangeDebounceSeconds(seconds: Int) {
        prefs.edit().putInt(KEY_CHANGE_DEBOUNCE_SEC, seconds).commit()
    }

    fun getContextWindowMode(): String {
        return prefs.getString(KEY_CONTEXT_WINDOW_MODE, "DYNAMIC") ?: "DYNAMIC"
    }

    fun setContextWindowMode(mode: String) {
        prefs.edit().putString(KEY_CONTEXT_WINDOW_MODE, mode).commit()
    }

    fun getAutoPrimaryContext(): String {
        return prefs.getString(KEY_AUTO_PRIMARY_CONTEXT, "EVENTS") ?: "EVENTS"
    }

    fun setAutoPrimaryContext(mode: String) {
        prefs.edit().putString(KEY_AUTO_PRIMARY_CONTEXT, mode).commit()
    }

    fun getUnifiedCanvasHoldMinutes(): Int {
        return prefs.getInt(KEY_UNIFIED_CANVAS_HOLD_MIN, 30).coerceIn(5, 180)
    }

    fun setUnifiedCanvasHoldMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(5, 180)
        prefs.edit()
            .putInt(KEY_UNIFIED_CANVAS_HOLD_MIN, clamped)
            .putInt(KEY_AUTO_AGENDA_EXPOSURE_MIN, clamped)
            .putInt(KEY_AUTO_WEATHER_EXPOSURE_MIN, clamped)
            .putInt(KEY_AUTO_SUN_EXPOSURE_MIN, clamped)
            .putInt(KEY_AUTO_SLEEP_EXPOSURE_MIN, clamped)
            .commit()
    }

    fun isNotifyWatchOnCanvasChangeEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFY_ON_CANVAS_CHANGE, true)
    }

    fun setNotifyWatchOnCanvasChangeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_ON_CANVAS_CHANGE, enabled).commit()
    }

    fun isNotifyAgenda15MinEnabled(): Boolean {
        return prefs.getBoolean(KEY_NOTIFY_AGENDA_15MIN, true)
    }

    fun setNotifyAgenda15MinEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFY_AGENDA_15MIN, enabled).commit()
    }

    fun isAutoFallbackAgendaToTasksEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_FALLBACK_AGENDA_TO_TASKS, true)
    }

    fun setAutoFallbackAgendaToTasksEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FALLBACK_AGENDA_TO_TASKS, enabled).commit()
    }

    /**
     * GATILHOS DO MODO AUTOMÁTICO (Centralizados — Agenda & Tarefas, Passos, Sol, Sono, Clima)
     */
    fun isAutoTriggerAgendaTasksEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_TRIGGER_AGENDA_TASKS_ENABLED, true)
    }

    fun setAutoTriggerAgendaTasksEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_AUTO_TRIGGER_AGENDA_TASKS_ENABLED, enabled)
            .putBoolean(KEY_NOTIFY_AGENDA_15MIN, enabled)
            .commit()
    }

    fun getAutoTriggerAgendaTasksLeadMin(): Int {
        val v = prefs.getInt(KEY_AUTO_TRIGGER_AGENDA_TASKS_LEAD_MIN, 15)
        return if (v in setOf(5, 10, 15)) v else 15
    }

    fun setAutoTriggerAgendaTasksLeadMin(minutes: Int) {
        val valid = if (minutes in setOf(5, 10, 15)) minutes else 15
        prefs.edit().putInt(KEY_AUTO_TRIGGER_AGENDA_TASKS_LEAD_MIN, valid).commit()
    }

    fun isAutoTriggerStepsEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_TRIGGER_STEPS_ENABLED, true)
    }

    fun setAutoTriggerStepsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TRIGGER_STEPS_ENABLED, enabled).commit()
    }

    fun getAutoTriggerStepsMilestone(): String {
        val v = prefs.getString(KEY_AUTO_TRIGGER_STEPS_MILESTONE, "100") ?: "100"
        return if (v in setOf("80", "100", "BOTH")) v else "100"
    }

    fun setAutoTriggerStepsMilestone(milestone: String) {
        val valid = if (milestone in setOf("80", "100", "BOTH")) milestone else "100"
        prefs.edit().putString(KEY_AUTO_TRIGGER_STEPS_MILESTONE, valid).commit()
    }

    fun isAutoTriggerSunEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_TRIGGER_SUN_ENABLED, true)
    }

    fun setAutoTriggerSunEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TRIGGER_SUN_ENABLED, enabled).commit()
    }

    fun getAutoTriggerSunLeadMin(): Int {
        val v = prefs.getInt(KEY_AUTO_TRIGGER_SUN_LEAD_MIN, 15)
        return if (v in setOf(5, 15, 30)) v else 15
    }

    fun setAutoTriggerSunLeadMin(minutes: Int) {
        val valid = if (minutes in setOf(5, 15, 30)) minutes else 15
        prefs.edit().putInt(KEY_AUTO_TRIGGER_SUN_LEAD_MIN, valid).commit()
    }

    fun isAutoTriggerSleepEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_TRIGGER_SLEEP_ENABLED, true)
    }

    fun setAutoTriggerSleepEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TRIGGER_SLEEP_ENABLED, enabled).commit()
    }

    fun getAutoTriggerSleepMode(): String {
        val v = prefs.getString(KEY_AUTO_TRIGGER_SLEEP_MODE, "BOTH") ?: "BOTH"
        return if (v in setOf("WAKE", "BEDTIME", "BOTH")) v else "BOTH"
    }

    fun setAutoTriggerSleepMode(mode: String) {
        val valid = if (mode in setOf("WAKE", "BEDTIME", "BOTH")) mode else "BOTH"
        prefs.edit().putString(KEY_AUTO_TRIGGER_SLEEP_MODE, valid).commit()
    }

    fun isAutoTriggerWeatherEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_TRIGGER_WEATHER_ENABLED, true)
    }

    fun setAutoTriggerWeatherEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TRIGGER_WEATHER_ENABLED, enabled).commit()
    }

    fun getAutoTriggerWeatherMode(): String {
        val v = prefs.getString(KEY_AUTO_TRIGGER_WEATHER_MODE, "RAIN_CHANGE") ?: "RAIN_CHANGE"
        return if (v in setOf("RAIN_CHANGE", "APP_ALERT", "EVERY_UPDATE")) v else "RAIN_CHANGE"
    }

    fun setAutoTriggerWeatherMode(mode: String) {
        val valid = if (mode in setOf("RAIN_CHANGE", "APP_ALERT", "EVERY_UPDATE")) mode else "RAIN_CHANGE"
        prefs.edit().putString(KEY_AUTO_TRIGGER_WEATHER_MODE, valid).commit()
    }

    fun hasRequestedInitialPermissionsOnce(): Boolean {
        return prefs.getBoolean(KEY_INITIAL_PERMISSIONS_REQUESTED, false)
    }

    fun markInitialPermissionsRequested() {
        prefs.edit().putBoolean(KEY_INITIAL_PERMISSIONS_REQUESTED, true).apply()
    }

    fun getAutoAgendaExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun setAutoAgendaExposureMinutes(minutes: Int) = setUnifiedCanvasHoldMinutes(minutes)

    fun getAutoWeatherExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun setAutoWeatherExposureMinutes(minutes: Int) = setUnifiedCanvasHoldMinutes(minutes)

    fun getAutoSunExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun setAutoSunExposureMinutes(minutes: Int) = setUnifiedCanvasHoldMinutes(minutes)

    fun getAutoSleepExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun setAutoSleepExposureMinutes(minutes: Int) = setUnifiedCanvasHoldMinutes(minutes)

    /**
     * WIDGETS DA TELA INICIAL (ORDEM ARRASTÁVEL E EXPANSÃO DE LINHA INTEIRA)
     */
    fun getHomeWidgetsOrder(): List<String> {
        val defaultOrder = listOf("EVENTS", "TASKS", "WEATHER", "SUN", "SLEEP", "STEPS")
        val raw = prefs.getString(KEY_HOME_WIDGETS_ORDER_CSV, "") ?: ""
        if (raw.isBlank()) return defaultOrder
        val parsed = raw.split(",").map { it.trim().uppercase() }.filter { it in defaultOrder }.distinct()
        val missing = defaultOrder.filter { it !in parsed }
        return parsed + missing
    }

    fun setHomeWidgetsOrder(order: List<String>) {
        val clean = order.map { it.trim().uppercase() }.distinct().joinToString(",")
        prefs.edit().putString(KEY_HOME_WIDGETS_ORDER_CSV, clean).commit()
    }

    fun getExpandedWidgetIds(): Set<String> {
        val raw = prefs.getString(KEY_HOME_WIDGETS_EXPANDED_CSV, "") ?: ""
        if (raw.isBlank() || raw == "__NONE__") return emptySet()
        return raw.split(",")
            .map { it.trim().uppercase(java.util.Locale.ROOT) }
            .filter { it.isNotBlank() && it != "__NONE__" }
            .toSet()
    }

    fun isWidgetExpandedFullRow(widgetId: String): Boolean {
        return getExpandedWidgetIds().contains(widgetId.trim().uppercase(java.util.Locale.ROOT))
    }

    fun setWidgetExpandedFullRow(widgetId: String, expanded: Boolean) {
        val id = widgetId.trim().uppercase(java.util.Locale.ROOT)
        val current = getExpandedWidgetIds().toMutableSet()
        if (expanded) {
            current.add(id)
        } else {
            current.remove(id)
        }
        val serialized = if (current.isEmpty()) "__NONE__" else current.joinToString(",")
        prefs.edit().putString(KEY_HOME_WIDGETS_EXPANDED_CSV, serialized).commit()
    }

    fun toggleWidgetExpandedFullRow(widgetId: String): Boolean {
        val currentlyExpanded = isWidgetExpandedFullRow(widgetId)
        val nextExpanded = !currentlyExpanded
        setWidgetExpandedFullRow(widgetId, nextExpanded)
        return nextExpanded
    }

    /**
     * DADOS DE SONO DO DIA (Lidos exclusivamente via Android Health Connect — Samsung Health, Fitbit/Pixel, Google Fit)
     */
    fun isSleepSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_SLEEP_SYNC_ENABLED, true)
    }

    fun setSleepSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SLEEP_SYNC_ENABLED, enabled).commit()
    }

    fun isSleepScoreVisible(): Boolean {
        return prefs.getBoolean(KEY_SLEEP_SHOW_SCORE, true)
    }

    fun setSleepScoreVisible(visible: Boolean) {
        prefs.edit().putBoolean(KEY_SLEEP_SHOW_SCORE, visible).commit()
    }

    fun isHealthConnectSleepPermissionGranted(): Boolean {
        return prefs.getBoolean(KEY_HEALTH_CONNECT_SLEEP_PERM_GRANTED, false)
    }

    fun setHealthConnectSleepPermissionGranted(granted: Boolean) {
        prefs.edit().putBoolean(KEY_HEALTH_CONNECT_SLEEP_PERM_GRANTED, granted).commit()
    }

    fun isHealthConnectStepsPermissionGranted(): Boolean {
        return prefs.getBoolean(KEY_HEALTH_CONNECT_STEPS_PERM_GRANTED, false)
    }

    fun setHealthConnectStepsPermissionGranted(granted: Boolean) {
        prefs.edit().putBoolean(KEY_HEALTH_CONNECT_STEPS_PERM_GRANTED, granted).commit()
    }

    fun saveStepsSnapshot(
        todaySteps: Int,
        dailyGoal: Int = getStepsDailyGoal(),
        weekStepsCsv: String,
        weekLabelsCsv: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putInt(KEY_STEPS_TODAY, todaySteps.coerceAtLeast(0))
            .putInt(KEY_STEPS_GOAL, dailyGoal.coerceAtLeast(1000))
            .putString(KEY_STEPS_WEEK_CSV, weekStepsCsv)
            .putString(KEY_STEPS_WEEK_LABELS_CSV, weekLabelsCsv)
            .putLong(KEY_STEPS_LAST_SYNC_MS, nowMs)
            .commit()
    }

    fun clearStepsData() {
        prefs.edit()
            .putInt(KEY_STEPS_TODAY, 0)
            .putString(KEY_STEPS_WEEK_CSV, "0,0,0,0,0,0,0")
            .putLong(KEY_STEPS_LAST_SYNC_MS, 0L)
            .commit()
    }

    fun getStepsToday(): Int {
        return prefs.getInt(KEY_STEPS_TODAY, 0).coerceAtLeast(0)
    }

    fun getStepsDailyGoal(): Int {
        return prefs.getInt(KEY_STEPS_GOAL, 10000).coerceAtLeast(1000)
    }

    fun getStepsWeekCsv(): String {
        return prefs.getString(KEY_STEPS_WEEK_CSV, "0,0,0,0,0,0,0") ?: "0,0,0,0,0,0,0"
    }

    fun getStepsWeekList(): List<Int> {
        val parsed = getStepsWeekCsv().split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (parsed.size == 7) parsed else listOf(0, 0, 0, 0, 0, 0, getStepsToday())
    }

    fun getStepsWeekLabelsCsv(): String {
        return prefs.getString(KEY_STEPS_WEEK_LABELS_CSV, "Seg,Ter,Qua,Qui,Sex,Sáb,Dom")
            ?: "Seg,Ter,Qua,Qui,Sex,Sáb,Dom"
    }

    fun getStepsWeekLabelsList(): List<String> {
        val parsed = getStepsWeekLabelsCsv().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parsed.size == 7) parsed else listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
    }

    private fun isSameDayEpoch(epochA: Long, epochB: Long): Boolean {
        val calA = java.util.Calendar.getInstance().apply { timeInMillis = epochA }
        val calB = java.util.Calendar.getInstance().apply { timeInMillis = epochB }
        return calA.get(java.util.Calendar.YEAR) == calB.get(java.util.Calendar.YEAR) &&
            calA.get(java.util.Calendar.DAY_OF_YEAR) == calB.get(java.util.Calendar.DAY_OF_YEAR)
    }

    fun hasVerifiedSleepDataForToday(nowMs: Long = System.currentTimeMillis()): Boolean {
        val explicitToday = prefs.getBoolean(KEY_SLEEP_HAS_DATA_TODAY, false)
        if (!explicitToday) return false
        val wakeTs = prefs.getLong(KEY_SLEEP_WAKE_TS_MS, 0L)
        val dur = prefs.getInt(KEY_SLEEP_DURATION_MIN, 0)
        if (dur <= 0 || wakeTs <= 0L) return false
        return isSameDayEpoch(nowMs, wakeTs)
    }

    fun clearSleepDataForToday() {
        prefs.edit()
            .putBoolean(KEY_SLEEP_HAS_DATA_TODAY, false)
            .putInt(KEY_SLEEP_DURATION_MIN, 0)
            .putInt(KEY_SLEEP_SCORE, 0)
            .putString(KEY_SLEEP_BEDTIME_HHMM, "--:--")
            .putString(KEY_SLEEP_WAKE_HHMM, "--:--")
            .putString(KEY_SLEEP_STAGES_CSV, "0,0,0,0")
            .putLong(KEY_SLEEP_WAKE_TS_MS, 0L)
            .commit()
    }

    fun setSleepHasDataForToday(hasData: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (!hasData) {
            clearSleepDataForToday()
            return
        }
        prefs.edit()
            .putBoolean(KEY_SLEEP_HAS_DATA_TODAY, true)
            .putLong(KEY_SLEEP_WAKE_TS_MS, nowMs)
            .commit()
    }

    fun saveSleepSnapshot(
        totalDurationMinutes: Int,
        sleepScore: Int,
        bedtimeHHMM: String,
        wakeHHMM: String,
        stagesMinutesCsv: String,
        wakeTimestampMs: Long = System.currentTimeMillis(),
        hasDataToday: Boolean = true
    ) {
        prefs.edit()
            .putBoolean(KEY_SLEEP_HAS_DATA_TODAY, hasDataToday && totalDurationMinutes > 0)
            .putInt(KEY_SLEEP_DURATION_MIN, totalDurationMinutes.coerceIn(0, 960))
            .putInt(KEY_SLEEP_SCORE, sleepScore.coerceIn(0, 100))
            .putString(KEY_SLEEP_BEDTIME_HHMM, bedtimeHHMM)
            .putString(KEY_SLEEP_WAKE_HHMM, wakeHHMM)
            .putString(KEY_SLEEP_STAGES_CSV, stagesMinutesCsv)
            .putLong(KEY_SLEEP_WAKE_TS_MS, wakeTimestampMs)
            .commit()
    }

    fun getSleepDurationMinutes(nowMs: Long = System.currentTimeMillis()): Int {
        if (!hasVerifiedSleepDataForToday(nowMs)) return 0
        return prefs.getInt(KEY_SLEEP_DURATION_MIN, 0)
    }

    fun getSleepScore(nowMs: Long = System.currentTimeMillis()): Int {
        if (!hasVerifiedSleepDataForToday(nowMs)) return 0
        return prefs.getInt(KEY_SLEEP_SCORE, 0)
    }

    fun getSleepBedtimeHHMM(): String {
        return prefs.getString(KEY_SLEEP_BEDTIME_HHMM, "--:--") ?: "--:--"
    }

    fun getSleepWakeHHMM(): String {
        return prefs.getString(KEY_SLEEP_WAKE_HHMM, "--:--") ?: "--:--"
    }

    fun getSleepStagesCsv(): String {
        return prefs.getString(KEY_SLEEP_STAGES_CSV, "0,0,0,0") ?: "0,0,0,0"
    }

    fun getSleepWakeTimestampMs(): Long {
        return prefs.getLong(KEY_SLEEP_WAKE_TS_MS, 0L)
    }

    /**
     * QUADRO DE TAREFAS (Google Tasks REST API) — Hierarquia Estrita:
     * 1. Somente atividades agendadas para HOJE ("TODAY") — apresenta check de não feito + horário (HH:mm) + título.
     * 2. Se NÃO há atividades agendadas para hoje, apresenta as atividades agendadas para AMANHÃ ("TOMORROW") — check de não feito + horário (HH:mm) + título.
     * 3. Se NÃO houver atividades para hoje nem para amanhã, apresenta as atividades SEM DATA DEFINIDA ("UNDATED")
     *    e as atividades EM ATRASO ("OVERDUE") com no máximo 30 dias (1 mês):
     *    - Sem data definida: NÃO apresenta data nem hora, apenas o check de não feito + título.
     *    - Em atraso (<= 30 dias): NÃO apresenta data nem hora, apenas o check de não feito + título em VERMELHO.
     */
    fun isTasksSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_TASKS_SYNC_ENABLED, true)
    }

    fun setTasksSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TASKS_SYNC_ENABLED, enabled).commit()
    }

    fun getGoogleTasksAccountEmail(): String {
        return prefs.getString(KEY_GOOGLE_TASKS_ACCOUNT_EMAIL, "") ?: ""
    }

    fun setGoogleTasksAccountEmail(email: String) {
        prefs.edit().putString(KEY_GOOGLE_TASKS_ACCOUNT_EMAIL, email.trim()).commit()
    }

    fun getGoogleTasksAccessToken(): String {
        return prefs.getString(KEY_GOOGLE_TASKS_ACCESS_TOKEN, "") ?: ""
    }

    fun setGoogleTasksAccessToken(token: String) {
        prefs.edit().putString(KEY_GOOGLE_TASKS_ACCESS_TOKEN, token.trim()).commit()
    }

    fun getGoogleTasksLastSyncMs(): Long {
        return prefs.getLong(KEY_GOOGLE_TASKS_LAST_SYNC_MS, 0L)
    }

    fun setGoogleTasksLastSyncMs(tsMs: Long) {
        prefs.edit().putLong(KEY_GOOGLE_TASKS_LAST_SYNC_MS, tsMs).commit()
    }

    fun getGoogleTasksSyncStatus(): String {
        return prefs.getString(KEY_GOOGLE_TASKS_SYNC_STATUS, "Conecte sua conta Google Tasks")
            ?: "Conecte sua conta Google Tasks"
    }

    fun setGoogleTasksSyncStatus(status: String) {
        prefs.edit().putString(KEY_GOOGLE_TASKS_SYNC_STATUS, status).commit()
    }

    fun getTasksJson(): String {
        val raw = prefs.getString(KEY_TASKS_JSON, "") ?: ""
        return raw.ifBlank { "[]" }
    }

    private fun parseTaskItemsFromJsonString(jsonStr: String, nowMs: Long): List<MobileTaskItem> {
        val list = mutableListOf<MobileTaskItem>()
        val sdfIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
        val todayIso = sdfIso.format(java.util.Date(nowMs))
        val tomorrowIso = sdfIso.format(java.util.Date(nowMs + 24L * 60L * 60L * 1000L))
        val thirtyDaysAgoIso = sdfIso.format(java.util.Date(nowMs - 30L * 24L * 60L * 60L * 1000L))
        val timeRegex = Regex("(\\d{1,2}:\\d{2})")
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val title = obj.optString("title", "").trim()
                if (title.isBlank()) continue
                val done = obj.optBoolean("completed", false)
                if (done) continue

                val explicitCat = obj.optString("scheduleCategory", "").trim().uppercase(java.util.Locale.ROOT)
                val dueDateIso = obj.optString("dueDateIso", "").trim()
                // Regra estrita: tarefas com mais de 1 mês (30 dias) no passado nunca são sincronizadas
                if (dueDateIso.isNotBlank() && dueDateIso < thirtyDaysAgoIso) continue

                val rawDue = obj.optString("dueHHMM", "").trim()
                val rawLower = rawDue.lowercase(java.util.Locale.ROOT)
                val explicitOverdue = obj.optBoolean("isOverdue", false)

                val resolvedCategory = when {
                    dueDateIso.isNotBlank() && dueDateIso < todayIso -> "OVERDUE"
                    dueDateIso == todayIso -> "TODAY"
                    dueDateIso == tomorrowIso -> "TOMORROW"
                    explicitCat in setOf("TODAY", "TOMORROW", "UNDATED", "OVERDUE") -> explicitCat
                    explicitOverdue ||
                        rawLower.contains("atras") ||
                        rawLower.contains("ontem") ||
                        rawLower.contains("vencid") -> "OVERDUE"
                    rawLower.contains("amanh") -> "TOMORROW"
                    dueDateIso.isBlank() && (
                        rawDue.isBlank() ||
                            rawLower == "sem data" ||
                            rawLower == "sem horário" ||
                            rawLower == "sem horario" ||
                            rawLower == "hoje"
                        ) -> "UNDATED"
                    dueDateIso.isBlank() -> {
                        if (timeRegex.containsMatchIn(rawDue)) "TODAY" else "UNDATED"
                    }
                    else -> continue
                }

                val matchedTime = timeRegex.find(rawDue)?.groupValues?.getOrNull(1) ?: ""
                val formattedTime = when (resolvedCategory) {
                    "TODAY", "TOMORROW" -> matchedTime.padStart(5, '0').ifBlank { "09:00" }
                    else -> ""
                }

                list.add(
                    MobileTaskItem(
                        id = obj.optString("id", "t_$i"),
                        title = title,
                        dueHHMM = formattedTime,
                        sourceLabel = "Tasks",
                        completed = false,
                        isTodayOrTimeless = true,
                        scheduleCategory = resolvedCategory,
                        isOverdue = resolvedCategory == "OVERDUE" || explicitOverdue
                    )
                )
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun getAllUncompletedTasks(nowMs: Long = System.currentTimeMillis()): List<MobileTaskItem> {
        if (!isTasksSyncEnabled()) return emptyList()
        return parseTaskItemsFromJsonString(getTasksJson(), nowMs)
    }

    fun resolveTasksBucketForCanvas(nowMs: Long = System.currentTimeMillis()): MobileResolvedTasksSnapshot {
        val allUncompleted = getAllUncompletedTasks(nowMs)

        val todayTasks = allUncompleted.filter { it.scheduleCategory == "TODAY" }
        if (todayTasks.isNotEmpty()) {
            return MobileResolvedTasksSnapshot(
                headerTitle = "Tarefas • Hoje",
                rightBadgeText = "${todayTasks.size} hoje",
                showTime = true,
                bucket = "TODAY",
                displayTasks = todayTasks
            )
        }

        val tomorrowTasks = allUncompleted.filter { it.scheduleCategory == "TOMORROW" }
        if (tomorrowTasks.isNotEmpty()) {
            return MobileResolvedTasksSnapshot(
                headerTitle = "Tarefas • Amanhã",
                rightBadgeText = "${tomorrowTasks.size} amanhã",
                showTime = true,
                bucket = "TOMORROW",
                displayTasks = tomorrowTasks
            )
        }

        val undatedOrOverdue = allUncompleted
            .filter { it.scheduleCategory == "OVERDUE" || it.scheduleCategory == "UNDATED" }
            .sortedByDescending { it.isOverdue || it.scheduleCategory == "OVERDUE" }

        if (undatedOrOverdue.isNotEmpty()) {
            return MobileResolvedTasksSnapshot(
                headerTitle = "Tarefas • Sem data",
                rightBadgeText = "${undatedOrOverdue.size} pend.",
                showTime = false,
                bucket = "UNDATED_OVERDUE",
                displayTasks = undatedOrOverdue
            )
        }

        return MobileResolvedTasksSnapshot(
            headerTitle = "Tarefas • Hoje",
            rightBadgeText = "0 ativas",
            showTime = false,
            bucket = "EMPTY",
            displayTasks = emptyList()
        )
    }

    fun getActiveUncompletedTasks(nowMs: Long = System.currentTimeMillis()): List<MobileTaskItem> {
        return resolveTasksBucketForCanvas(nowMs).displayTasks
    }

    fun getMobileTaskItems(nowMs: Long = System.currentTimeMillis()): List<MobileTaskItem> {
        return getAllUncompletedTasks(nowMs)
    }

    fun getTasksFilteredJsonForWatch(nowMs: Long = System.currentTimeMillis()): String {
        val allUncompleted = getAllUncompletedTasks(nowMs)
        val arr = org.json.JSONArray()
        allUncompleted.forEach { t ->
            arr.put(
                org.json.JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("dueHHMM", t.dueHHMM)
                    put("sourceLabel", "Tasks")
                    put("completed", false)
                    put("isTodayOrTimeless", true)
                    put("scheduleCategory", t.scheduleCategory)
                    put("isOverdue", t.isOverdue)
                }
            )
        }
        return arr.toString()
    }

    fun markTaskCompletedAndRemoveFromCanvas(taskId: String) {
        try {
            val arr = org.json.JSONArray(getTasksJson())
            val nextArr = org.json.JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("id", "") == taskId) {
                    obj.put("completed", true)
                }
                if (!obj.optBoolean("completed", false)) {
                    nextArr.put(obj)
                }
            }
            prefs.edit().putString(KEY_TASKS_JSON, nextArr.toString()).commit()
        } catch (_: Exception) {
        }
    }

    fun saveTasksJson(tasksJson: String, alertTimestampMs: Long = 0L) {
        val editor = prefs.edit().putString(KEY_TASKS_JSON, tasksJson.ifBlank { "[]" })
        if (alertTimestampMs > 0L) {
            editor.putLong(KEY_TASKS_ALERT_TS_MS, alertTimestampMs)
        }
        editor.commit()
    }

    fun saveMobileTaskItems(items: List<MobileTaskItem>, alertTimestampMs: Long = 0L) {
        val arr = org.json.JSONArray()
        for (t in items) {
            if (t.completed) continue
            arr.put(
                org.json.JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("dueHHMM", t.dueHHMM)
                    put("sourceLabel", "Tasks")
                    put("completed", false)
                    put("isTodayOrTimeless", true)
                    put("scheduleCategory", t.scheduleCategory.ifBlank { "TODAY" })
                    put("isOverdue", t.isOverdue || t.scheduleCategory == "OVERDUE")
                }
            )
        }
        saveTasksJson(arr.toString(), alertTimestampMs)
    }

    fun getTasksAlertTimestampMs(): Long {
        return prefs.getLong(KEY_TASKS_ALERT_TS_MS, 0L)
    }

    fun getTasksSummaryLabel(): String {
        val resolved = resolveTasksBucketForCanvas()
        return if (resolved.displayTasks.isNotEmpty()) {
            "${resolved.headerTitle} (${resolved.rightBadgeText}) • ${resolved.displayTasks.first().title}"
        } else {
            "Não há tarefas pendentes"
        }
    }

    fun isHighFreq15MinRuleEnabled(): Boolean {
        return prefs.getBoolean(KEY_HIGH_FREQ_15MIN_RULE, true)
    }

    fun setHighFreq15MinRuleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HIGH_FREQ_15MIN_RULE, enabled).commit()
    }

    fun isUpdateOnScreenWakeEnabled(): Boolean {
        return prefs.getBoolean(KEY_UPDATE_ON_SCREEN_WAKE, true)
    }

    fun setUpdateOnScreenWakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_UPDATE_ON_SCREEN_WAKE, enabled).commit()
    }

    /**
     * GATILHO 1 UNIFICADO (TELEFONE): Busca Periódica Autônoma de Clima + Nascer/Pôr do Sol
     * pela mesma Localização do Smartphone (quando um atualiza, o outro atualiza junto).
     */
    fun isPhoneWeatherAutoCheckEnabled(): Boolean {
        return prefs.getBoolean(
            KEY_PHONE_WEATHER_AUTO_CHECK_ENABLED,
            prefs.getBoolean(KEY_PHONE_SOLAR_AUTO_SYNC_ENABLED, true)
        )
    }

    fun setPhoneWeatherAutoCheckEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_PHONE_WEATHER_AUTO_CHECK_ENABLED, enabled)
            .putBoolean(KEY_PHONE_SOLAR_AUTO_SYNC_ENABLED, enabled)
            .commit()
    }

    fun getPhoneWeatherCheckIntervalMinutes(): Int {
        return prefs.getInt(KEY_PHONE_WEATHER_CHECK_INTERVAL_MIN, 60).coerceAtLeast(15)
    }

    fun setPhoneWeatherCheckIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_PHONE_WEATHER_CHECK_INTERVAL_MIN, minutes.coerceAtLeast(15)).commit()
    }

    fun getSelectedWeatherTriggerPackages(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_WEATHER_TRIGGER_PKGS, DEFAULT_WEATHER_TRIGGER_PACKAGES)
            ?: DEFAULT_WEATHER_TRIGGER_PACKAGES
    }

    fun setSelectedWeatherTriggerPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_WEATHER_TRIGGER_PKGS, HashSet(packages)).commit()
    }

    fun isWatchStandaloneWeatherEnabled(): Boolean {
        return prefs.getBoolean(
            KEY_WATCH_STANDALONE_WEATHER_ENABLED,
            prefs.getBoolean(KEY_WATCH_STANDALONE_SOLAR_ENABLED, false)
        )
    }

    fun setWatchStandaloneWeatherEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_WATCH_STANDALONE_WEATHER_ENABLED, enabled)
            .putBoolean(KEY_WATCH_STANDALONE_SOLAR_ENABLED, enabled)
            .commit()
    }

    fun getWatchStandaloneWeatherIntervalMinutes(): Int {
        return prefs.getInt(KEY_WATCH_STANDALONE_WEATHER_INTERVAL_MIN, 60).coerceAtLeast(15)
    }

    fun setWatchStandaloneWeatherIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_WATCH_STANDALONE_WEATHER_INTERVAL_MIN, minutes.coerceAtLeast(15)).commit()
    }

    fun isPhoneSolarAutoSyncEnabled(): Boolean {
        return isPhoneWeatherAutoCheckEnabled()
    }

    fun setPhoneSolarAutoSyncEnabled(enabled: Boolean) {
        setPhoneWeatherAutoCheckEnabled(enabled)
    }

    fun isWatchStandaloneSolarEnabled(): Boolean {
        return isWatchStandaloneWeatherEnabled()
    }

    fun setWatchStandaloneSolarEnabled(enabled: Boolean) {
        setWatchStandaloneWeatherEnabled(enabled)
    }

    fun saveLastKnownCoordinates(lat: Double, lon: Double) {
        if (lat != 0.0 || lon != 0.0) {
            prefs.edit()
                .putString(KEY_WEATHER_SAVED_LAT, lat.toString())
                .putString(KEY_WEATHER_SAVED_LON, lon.toString())
                .apply()
        }
    }

    fun getLastKnownCoordinates(): Pair<Double, Double>? {
        val latStr = prefs.getString(KEY_WEATHER_SAVED_LAT, null) ?: return null
        val lonStr = prefs.getString(KEY_WEATHER_SAVED_LON, null) ?: return null
        val lat = latStr.toDoubleOrNull() ?: return null
        val lon = lonStr.toDoubleOrNull() ?: return null
        return lat to lon
    }

    fun saveSolarTimes(sunriseHHMM: String, sunsetHHMM: String) {
        prefs.edit()
            .putString(KEY_SOLAR_SUNRISE_HHMM, sunriseHHMM)
            .putString(KEY_SOLAR_SUNSET_HHMM, sunsetHHMM)
            .apply()
    }

    fun getSolarSunriseHHMM(): String {
        return prefs.getString(KEY_SOLAR_SUNRISE_HHMM, "05:27") ?: "05:27"
    }

    fun getSolarSunsetHHMM(): String {
        return prefs.getString(KEY_SOLAR_SUNSET_HHMM, "17:40") ?: "17:40"
    }

    fun savePhoneFetchedWeatherAndDetectShift(
        pickedTemps: List<Int>,
        pickedLabels: List<String>,
        rainProbPct: Int,
        weatherConditionLabel: String = "Parcialmente nublado",
        locationName: String = "",
        conditionShiftDetected: Boolean = false,
        fetchTimestampMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prevTempsStr = prefs.getString(KEY_WEATHER_TEMPS_CSV, "") ?: ""
        val prevFirstTemp = prevTempsStr.split(",").firstOrNull()?.trim()?.toIntOrNull()
        val prevCondition = prefs.getString(KEY_WEATHER_CONDITION_LABEL, "") ?: ""

        val tempsCsv = pickedTemps.joinToString(",")
        val hoursCsv = pickedLabels.joinToString(",")

        val minT = pickedTemps.minOrNull() ?: 22
        val maxT = pickedTemps.maxOrNull() ?: 22
        val firstT = pickedTemps.firstOrNull() ?: 22

        val shiftAcrossHours = (maxT - minT) >= 3
        val shiftFromPreviousFetch = prevFirstTemp != null && abs(firstT - prevFirstTemp) >= 2
        val rainAlert = rainProbPct >= 45
        val weatherConditionChanged = conditionShiftDetected ||
            (prevCondition.isNotBlank() && !prevCondition.equals(weatherConditionLabel, ignoreCase = true))

        val shiftDetected = isPhoneWeatherAutoCheckEnabled() &&
            (shiftAcrossHours || shiftFromPreviousFetch || rainAlert || weatherConditionChanged)

        val validCandidateLoc = locationName.trim().takeIf {
            it.isNotBlank() && !it.equals("Local, BR", ignoreCase = true)
        } ?: getPhoneWeatherLocationName()
        val resolvedLoc = formatCompactWeatherLocation(validCandidateLoc)
        val updatedHHMM = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(fetchTimestampMs))

        val editor = prefs.edit()
            .putString(KEY_WEATHER_TEMPS_CSV, tempsCsv)
            .putString(KEY_WEATHER_HOURS_CSV, hoursCsv)
            .putInt(KEY_WEATHER_RAIN_PROB, rainProbPct.coerceIn(0, 100))
            .putString(KEY_WEATHER_CONDITION_LABEL, weatherConditionLabel)
            .putString(KEY_WEATHER_LOCATION_NAME, resolvedLoc)
            .putString(KEY_WEATHER_UPDATED_HHMM, updatedHHMM)
            .putLong(KEY_WEATHER_LAST_FETCH_MS, fetchTimestampMs)

        if (shiftDetected) {
            val headline = "$resolvedLoc • Atualizado $updatedHHMM"
            editor.putString(KEY_WEATHER_ALERT_SOURCE, "Clima")
            editor.putString(KEY_WEATHER_ALERT_HEADLINE, headline)
            editor.putLong(KEY_WEATHER_ALERT_TS_MS, fetchTimestampMs)
        }
        editor.apply()
        return shiftDetected
    }

    fun getPhoneWeatherTempsCsv(): String {
        return prefs.getString(KEY_WEATHER_TEMPS_CSV, "28,28,27,27,27") ?: "28,28,27,27,27"
    }

    fun getPhoneWeatherHoursCsv(): String {
        return prefs.getString(KEY_WEATHER_HOURS_CSV, "Agora,23h,01h,03h,05h") ?: "Agora,23h,01h,03h,05h"
    }

    fun getPhoneWeatherRainProbPct(): Int {
        return prefs.getInt(KEY_WEATHER_RAIN_PROB, 20)
    }

    fun getPhoneWeatherConditionLabel(): String {
        return prefs.getString(KEY_WEATHER_CONDITION_LABEL, "Céu limpo") ?: "Céu limpo"
    }

    fun getPhoneWeatherLocationName(): String {
        val raw = prefs.getString(KEY_WEATHER_LOCATION_NAME, "São Paulo, SP") ?: "São Paulo, SP"
        return formatCompactWeatherLocation(raw)
    }

    fun setPhoneWeatherLocationName(locationName: String) {
        if (locationName.isNotBlank() && !locationName.equals("Local, BR", ignoreCase = true)) {
            prefs.edit().putString(KEY_WEATHER_LOCATION_NAME, formatCompactWeatherLocation(locationName)).apply()
        }
    }

    fun getPhoneWeatherUpdatedHHMM(): String {
        val saved = prefs.getString(KEY_WEATHER_UPDATED_HHMM, "") ?: ""
        if (saved.isNotBlank()) return saved
        val lastFetch = getPhoneWeatherLastFetchMs()
        return if (lastFetch > 0L) {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(lastFetch))
        } else {
            "Agora"
        }
    }

    fun getPhoneWeatherLastFetchMs(): Long {
        return prefs.getLong(KEY_WEATHER_LAST_FETCH_MS, 0L)
    }

    fun recordObservedWeatherNotification(
        sourceAppLabel: String,
        headline: String,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putString(KEY_WEATHER_ALERT_SOURCE, sourceAppLabel)
            .putString(KEY_WEATHER_ALERT_HEADLINE, headline)
            .putLong(KEY_WEATHER_ALERT_TS_MS, timestampMs)
            .apply()
    }

    fun getWeatherAlertSource(): String {
        return prefs.getString(KEY_WEATHER_ALERT_SOURCE, "Clima") ?: "Clima"
    }

    fun getWeatherAlertHeadline(): String {
        return prefs.getString(KEY_WEATHER_ALERT_HEADLINE, "Previsão para as próximas horas")
            ?: "Previsão para as próximas horas"
    }

    fun getWeatherAlertTimestampMs(): Long {
        return prefs.getLong(KEY_WEATHER_ALERT_TS_MS, 0L)
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
        const val MODE_AUTO = "DYNAMIC"
        const val MODE_EVENTS = "EVENTS"
        const val MODE_TASKS = "TASKS"
        const val MODE_WEATHER = "WEATHER"
        const val MODE_SUN = "SUN"
        const val MODE_SLEEP = "SLEEP"
        const val MODE_STEPS = "STEPS"
        private const val KEY_CALENDARS = "selected_calendar_ids"
        private const val KEY_HORIZON_DAYS = "horizon_days"
        private const val KEY_PROGRESS_WINDOW_MIN = "progress_window_minutes"
        private const val KEY_BACKGROUND_SERVICE_ENABLED = "background_service_enabled"
        private const val KEY_CALENDAR_EVENTS_SYNC_ENABLED = "calendar_events_sync_enabled"
        private const val KEY_CALENDAR_EVENTS_SYNC_ENABLED_V2 = "calendar_events_sync_enabled_v2"
        private const val KEY_NOTIFICATIONS_SYNC_ENABLED = "notifications_sync_enabled"
        private const val KEY_MEDIA_PLAYBACK_SYNC_ENABLED = "media_playback_sync_enabled"
        private const val KEY_SYNC_INTERVAL_MIN = "sync_interval_minutes"
        private const val KEY_AUTO_SYNC_ON_CHANGE = "auto_sync_on_calendar_change"
        private const val KEY_CHANGE_DEBOUNCE_SEC = "change_debounce_seconds"
        private const val KEY_CONTEXT_WINDOW_MODE = "context_window_mode"
        private const val KEY_AUTO_PRIMARY_CONTEXT = "auto_primary_context"
        private const val KEY_UNIFIED_CANVAS_HOLD_MIN = "unified_canvas_hold_min"
        private const val KEY_NOTIFY_ON_CANVAS_CHANGE = "notify_on_canvas_change"
        private const val KEY_NOTIFY_AGENDA_15MIN = "notify_agenda_15min"
        private const val KEY_AUTO_FALLBACK_AGENDA_TO_TASKS = "auto_fallback_agenda_to_tasks"
        private const val KEY_AUTO_TRIGGER_AGENDA_TASKS_ENABLED = "auto_trigger_agenda_tasks_enabled"
        private const val KEY_AUTO_TRIGGER_AGENDA_TASKS_LEAD_MIN = "auto_trigger_agenda_tasks_lead_min"
        private const val KEY_AUTO_TRIGGER_STEPS_ENABLED = "auto_trigger_steps_enabled"
        private const val KEY_AUTO_TRIGGER_STEPS_MILESTONE = "auto_trigger_steps_milestone"
        private const val KEY_AUTO_TRIGGER_SUN_ENABLED = "auto_trigger_sun_enabled"
        private const val KEY_AUTO_TRIGGER_SUN_LEAD_MIN = "auto_trigger_sun_lead_min"
        private const val KEY_AUTO_TRIGGER_SLEEP_ENABLED = "auto_trigger_sleep_enabled"
        private const val KEY_AUTO_TRIGGER_SLEEP_MODE = "auto_trigger_sleep_mode"
        private const val KEY_AUTO_TRIGGER_WEATHER_ENABLED = "auto_trigger_weather_enabled"
        private const val KEY_AUTO_TRIGGER_WEATHER_MODE = "auto_trigger_weather_mode"
        private const val KEY_INITIAL_PERMISSIONS_REQUESTED = "initial_permissions_requested"
        private const val KEY_HOME_WIDGETS_ORDER_CSV = "home_widgets_order_csv"
        private const val KEY_HOME_WIDGETS_EXPANDED_CSV = "home_widgets_expanded_csv"
        private const val KEY_AUTO_AGENDA_EXPOSURE_MIN = "auto_agenda_exposure_min"
        private const val KEY_AUTO_WEATHER_EXPOSURE_MIN = "auto_weather_exposure_min"
        private const val KEY_AUTO_SUN_EXPOSURE_MIN = "auto_sun_exposure_min"
        private const val KEY_AUTO_SLEEP_EXPOSURE_MIN = "auto_sleep_exposure_min"
        private const val KEY_SLEEP_SYNC_ENABLED = "sleep_sync_enabled"
        private const val KEY_SLEEP_SHOW_SCORE = "sleep_show_score"
        private const val KEY_HEALTH_CONNECT_SLEEP_PERM_GRANTED = "health_connect_sleep_perm_granted"
        private const val KEY_HEALTH_CONNECT_STEPS_PERM_GRANTED = "health_connect_steps_perm_granted"
        private const val KEY_STEPS_TODAY = "steps_today_v2"
        private const val KEY_STEPS_GOAL = "steps_goal_v2"
        private const val KEY_STEPS_WEEK_CSV = "steps_week_csv_v2"
        private const val KEY_STEPS_WEEK_LABELS_CSV = "steps_week_labels_csv_v2"
        private const val KEY_STEPS_LAST_SYNC_MS = "steps_last_sync_ms_v2"
        private const val KEY_SLEEP_HAS_DATA_TODAY = "sleep_has_data_today_v2"
        private const val KEY_SLEEP_DURATION_MIN = "sleep_duration_min_v2"
        private const val KEY_SLEEP_SCORE = "sleep_score_v2"
        private const val KEY_SLEEP_BEDTIME_HHMM = "sleep_bedtime_hhmm_v2"
        private const val KEY_SLEEP_WAKE_HHMM = "sleep_wake_hhmm_v2"
        private const val KEY_SLEEP_STAGES_CSV = "sleep_stages_csv_v2"
        private const val KEY_SLEEP_WAKE_TS_MS = "sleep_wake_ts_ms_v2"
        private const val KEY_TASKS_SYNC_ENABLED = "tasks_sync_enabled"
        private const val KEY_GOOGLE_TASKS_ACCOUNT_EMAIL = "google_tasks_account_email"
        private const val KEY_GOOGLE_TASKS_ACCESS_TOKEN = "google_tasks_access_token"
        private const val KEY_GOOGLE_TASKS_LAST_SYNC_MS = "google_tasks_last_sync_ms"
        private const val KEY_GOOGLE_TASKS_SYNC_STATUS = "google_tasks_sync_status"
        private const val KEY_TASKS_JSON = "tasks_json"
        private const val KEY_TASKS_ALERT_TS_MS = "tasks_alert_ts_ms"
        private const val KEY_HIGH_FREQ_15MIN_RULE = "high_freq_15min_rule"
        const val DEFAULT_TASKS_JSON = """[
            {"id":"t1","title":"Enviar relatório trimestral","dueHHMM":"14:00","sourceLabel":"Tasks","completed":false,"isTodayOrTimeless":true,"scheduleCategory":"TODAY","isOverdue":false},
            {"id":"t2","title":"Reunião de revisão da sprint","dueHHMM":"16:30","sourceLabel":"Tasks","completed":false,"isTodayOrTimeless":true,"scheduleCategory":"TODAY","isOverdue":false},
            {"id":"t3","title":"Apresentar protótipo Wear OS","dueHHMM":"10:00","sourceLabel":"Tasks","completed":false,"isTodayOrTimeless":true,"scheduleCategory":"TOMORROW","isOverdue":false},
            {"id":"t4","title":"Pagar fatura do cartão corporativo","dueHHMM":"","sourceLabel":"Tasks","completed":false,"isTodayOrTimeless":true,"scheduleCategory":"OVERDUE","isOverdue":true},
            {"id":"t5","title":"Organizar documentos da contabilidade","dueHHMM":"","sourceLabel":"Tasks","completed":false,"isTodayOrTimeless":true,"scheduleCategory":"UNDATED","isOverdue":false}
        ]"""
        private const val KEY_UPDATE_ON_SCREEN_WAKE = "update_on_screen_wake"
        private const val KEY_PHONE_WEATHER_AUTO_CHECK_ENABLED = "phone_weather_auto_check_enabled"
        private const val KEY_PHONE_WEATHER_CHECK_INTERVAL_MIN = "phone_weather_check_interval_min"
        private const val KEY_SELECTED_WEATHER_TRIGGER_PKGS = "selected_weather_trigger_pkgs"
        private const val KEY_WATCH_STANDALONE_WEATHER_ENABLED = "watch_standalone_weather_enabled"
        private const val KEY_WATCH_STANDALONE_WEATHER_INTERVAL_MIN = "watch_standalone_weather_interval_min"
        private const val KEY_PHONE_SOLAR_AUTO_SYNC_ENABLED = "phone_solar_auto_sync_enabled"
        private const val KEY_WATCH_STANDALONE_SOLAR_ENABLED = "watch_standalone_solar_enabled"
        private const val KEY_WEATHER_SAVED_LAT = "weather_saved_lat"
        private const val KEY_WEATHER_SAVED_LON = "weather_saved_lon"
        private const val KEY_SOLAR_SUNRISE_HHMM = "solar_sunrise_hhmm"
        private const val KEY_SOLAR_SUNSET_HHMM = "solar_sunset_hhmm"
        private const val KEY_WEATHER_TEMPS_CSV = "weather_temps_csv"
        private const val KEY_WEATHER_HOURS_CSV = "weather_hours_csv"
        private const val KEY_WEATHER_RAIN_PROB = "weather_rain_prob"
        private const val KEY_WEATHER_CONDITION_LABEL = "weather_condition_label"
        private const val KEY_WEATHER_LOCATION_NAME = "weather_location_name"
        private const val KEY_WEATHER_UPDATED_HHMM = "weather_updated_hhmm"
        private const val KEY_WEATHER_LAST_FETCH_MS = "weather_last_fetch_ms"
        private const val KEY_WEATHER_ALERT_SOURCE = "weather_alert_source"
        private const val KEY_WEATHER_ALERT_HEADLINE = "weather_alert_headline"
        private const val KEY_WEATHER_ALERT_TS_MS = "weather_alert_ts_ms"
        private const val KEY_VERIFIED_WATCH_APP_NODES = "verified_watch_app_nodes"
        private const val KEY_LAST_SYNC_MS = "last_sync_ms"
        private const val KEY_LAST_SYNC_COUNT = "last_sync_count"
        private const val KEY_LAST_CAL_COUNT = "last_cal_count"
        private const val KEY_LAST_TRIGGER_SOURCE = "last_trigger_source"
        private const val KEY_LAST_SYNC_STATUS = "last_sync_status"

        val DEFAULT_WEATHER_TRIGGER_PACKAGES = setOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.weather",
            "com.sec.android.daemonapp",
            "br.com.climatempo.meteorologia",
            "com.accuweather.android",
            "com.weather.Weather"
        )

        private val OFFICIAL_IBGE_UFS = setOf(
            "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO",
            "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI",
            "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO"
        )

        private val BRAZIL_STATE_UF_ORDERED = listOf(
            "mato grosso do sul" to "MS",
            "rio grande do norte" to "RN",
            "rio grande do sul" to "RS",
            "distrito federal" to "DF",
            "espirito santo" to "ES",
            "santa catarina" to "SC",
            "rio de janeiro" to "RJ",
            "minas gerais" to "MG",
            "mato grosso" to "MT",
            "sao paulo" to "SP",
            "maranhao" to "MA",
            "pernambuco" to "PE",
            "tocantins" to "TO",
            "amazonas" to "AM",
            "rondonia" to "RO",
            "roraima" to "RR",
            "alagoas" to "AL",
            "paraiba" to "PB",
            "sergipe" to "SE",
            "parana" to "PR",
            "amapa" to "AP",
            "bahia" to "BA",
            "ceara" to "CE",
            "goias" to "GO",
            "piaui" to "PI",
            "acre" to "AC",
            "para" to "PA"
        )

        private fun stripAccents(input: String): String {
            return java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        }

        /**
         * Resolve exclusivamente a sigla oficial IBGE de 2 letras de cada estado brasileiro
         * (ex: São Paulo -> SP, Paraná -> PR, Maranhão -> MA), sem jamais usar abreviação lógica
         * das 2 primeiras letras ("SA" ou "PA" para Paraná).
         */
        fun resolveOfficialBrazilStateUf(rawState: String, fallbackCity: String = ""): String {
            val trimmed = rawState.trim()
            val upper = trimmed.uppercase(java.util.Locale.ROOT)
            if (upper in OFFICIAL_IBGE_UFS) return upper

            val cleaned = stripAccents(trimmed)
                .lowercase(java.util.Locale.ROOT)
                .replace(Regex("^(state of|estado d[eao]|uf|unidade federativa|provincia d[eao])\\s+"), "")
                .replace(Regex("\\s+(state|uf|brasil|brazil)$"), "")
                .trim()

            for ((stateName, uf) in BRAZIL_STATE_UF_ORDERED) {
                if (cleaned == stateName || cleaned.contains(stateName)) {
                    return uf
                }
            }

            if (fallbackCity.isNotBlank()) {
                val cleanCity = stripAccents(fallbackCity).lowercase(java.util.Locale.ROOT).trim()
                for ((stateName, uf) in BRAZIL_STATE_UF_ORDERED) {
                    if (cleanCity == stateName) return uf
                }
            }

            return if (upper.length == 2 && upper.all { it.isLetter() }) upper else ""
        }

        /**
         * Formata o nome do local para o subtítulo do Canvas de Clima:
         * 1. Converte o nome do estado para a sigla oficial IBGE de 2 letras (ex: "São Paulo" -> "SP", "Maranhão" -> "MA", "Paraná" -> "PR").
         * 2. Se o nome da cidade for extenso (> 12 caracteres) e ocupar mais da metade do quadro,
         *    mantém a primeira palavra inteira e metade da segunda com reticências (ex: "São Bernardo do Campo" -> "São Bern..."),
         *    ou abrevia palavras únicas muito longas (ex: "Pindamonhangaba" -> "Pindamonh...").
         */
        fun formatCompactWeatherLocation(rawCityOrCombined: String, rawState: String = ""): String {
            val cleaned = rawCityOrCombined.trim()
            if (cleaned.isBlank()) return "Local, BR"

            val cityPart: String
            val statePart: String
            if (rawState.isNotBlank()) {
                cityPart = cleaned
                statePart = rawState.trim()
            } else if (cleaned.contains(",")) {
                cityPart = cleaned.substringBeforeLast(",").trim()
                statePart = cleaned.substringAfterLast(",").trim()
            } else if (cleaned.contains(" - ")) {
                cityPart = cleaned.substringBeforeLast(" - ").trim()
                statePart = cleaned.substringAfterLast(" - ").trim()
            } else {
                cityPart = cleaned
                statePart = ""
            }

            val ufSigla = resolveOfficialBrazilStateUf(statePart, cityPart)

            val cleanCity = cityPart.trim().removeSuffix("...")
            val compactCity = if (cityPart.trim().endsWith("...") && cityPart.trim().length <= 16) {
                cityPart.trim()
            } else if (cleanCity.length > 12) {
                val words = cleanCity.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.size >= 2) {
                    val firstWord = words[0]
                    val secondIsConnector = words[1].lowercase(java.util.Locale.ROOT) in setOf("de", "da", "do", "das", "dos")
                    if (secondIsConnector && words.size >= 3) {
                        val thirdWord = words[2]
                        val halfThird = thirdWord.take(((thirdWord.length + 1) / 2).coerceAtLeast(2))
                        "$firstWord ${words[1]} ${halfThird}..."
                    } else {
                        val secondWord = words[1]
                        val halfSecond = secondWord.take(((secondWord.length + 1) / 2).coerceAtLeast(2))
                        "$firstWord ${halfSecond}..."
                    }
                } else {
                    "${cleanCity.take(9)}..."
                }
            } else {
                cleanCity
            }

            return if (ufSigla.isNotBlank()) {
                "$compactCity, $ufSigla"
            } else {
                compactCity
            }
        }

        val KNOWN_WEATHER_TRIGGER_CATALOG = listOf(
            WeatherTriggerAppOption(
                packageName = "com.google.android.googlequicksearchbox",
                displayName = "App Google",
                categoryName = "Provedores do Sistema",
                categoryDescription = "Alertas meteorológicos do Google"
            ),
            WeatherTriggerAppOption(
                packageName = "com.google.android.apps.weather",
                displayName = "Google Clima / Weather",
                categoryName = "Provedores do Sistema",
                categoryDescription = "App dedicado de Clima do Google"
            ),
            WeatherTriggerAppOption(
                packageName = "com.sec.android.daemonapp",
                displayName = "Samsung Clima (One UI Weather)",
                categoryName = "Provedores do Sistema",
                categoryDescription = "Provedor oficial de Clima da Samsung"
            ),
            WeatherTriggerAppOption(
                packageName = "br.com.climatempo.meteorologia",
                displayName = "Climatempo",
                categoryName = "Clima e Meteorologia",
                categoryDescription = "Alertas de chuva, raios e frente fria"
            ),
            WeatherTriggerAppOption(
                packageName = "com.accuweather.android",
                displayName = "AccuWeather",
                categoryName = "Clima e Meteorologia",
                categoryDescription = "Alertas de precipitação e variação térmica"
            ),
            WeatherTriggerAppOption(
                packageName = "com.weather.Weather",
                categoryName = "Clima e Meteorologia",
                displayName = "The Weather Channel",
                categoryDescription = "Notificações de mudança brusca de tempo"
            ),
            WeatherTriggerAppOption(
                packageName = "com.windyty.android",
                displayName = "Windy.com",
                categoryName = "Clima e Radar",
                categoryDescription = "Alertas de vento, frente fria e chuva"
            ),
            WeatherTriggerAppOption(
                packageName = "com.weawow",
                displayName = "Weawow Clima",
                categoryName = "Clima e Meteorologia",
                categoryDescription = "Previsão detalhada e alertas de chuva"
            ),
            WeatherTriggerAppOption(
                packageName = "widget.dd.com.overdrop.free",
                displayName = "Overdrop Weather",
                categoryName = "Clima e Meteorologia",
                categoryDescription = "Alertas de condição severa e precipitação"
            )
        )
    }
}
