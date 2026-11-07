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
        return prefs.getBoolean(KEY_CALENDAR_EVENTS_SYNC_ENABLED, false)
    }

    fun setCalendarEventsSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CALENDAR_EVENTS_SYNC_ENABLED, enabled).commit()
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
        return prefs.getLong(KEY_SYNC_INTERVAL_MIN, 15L)
    }

    fun setSyncIntervalMinutes(minutes: Long) {
        prefs.edit().putLong(KEY_SYNC_INTERVAL_MIN, minutes).commit()
    }

    fun isAutoSyncOnCalendarChangeEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SYNC_ON_CHANGE, true)
    }

    fun setAutoSyncOnCalendarChangeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC_ON_CHANGE, enabled).commit()
    }

    fun getChangeDebounceSeconds(): Int {
        return prefs.getInt(KEY_CHANGE_DEBOUNCE_SEC, 5)
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

    /**
     * REGRAS UNIFICADAS DE PRIORIDADE E RETORNO AO CANVAS PADRÃO:
     * - Canvas Padrão Principal (EVENTS, WEATHER, SUN, STEPS, SLEEP)
     * - Tempo Unificado de Permanência de Canvas Não-Padrão (5, 15, 30, 45, 60 min):
     *   Define por quanto tempo qualquer Canvas que não seja o padrão principal (seja aberto por
     *   Toque/Tap na Watch Face ou forçado por mudança de Clima, Agenda, Sol ou Sono) fica fixo
     *   no relógio antes de retornar automaticamente à aparência padrão (Canvas Padrão Principal).
     * - Notificação no Relógio quando uma mudança de contexto forçar a troca do Canvas.
     */
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

    fun getAutoAgendaExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun setAutoAgendaExposureMinutes(minutes: Int) = setUnifiedCanvasHoldMinutes(minutes)

    fun getAutoWeatherExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun setAutoWeatherExposureMinutes(minutes: Int) = setUnifiedCanvasHoldMinutes(minutes)

    fun getAutoSunExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun setAutoSunExposureMinutes(minutes: Int) = setUnifiedCanvasHoldMinutes(minutes)

    fun getAutoSleepExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun setAutoSleepExposureMinutes(minutes: Int) = setUnifiedCanvasHoldMinutes(minutes)

    /**
     * DADOS DE SONO (Health Connect / Samsung Health / Monitoramento Noturno)
     */
    fun isSleepSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_SLEEP_SYNC_ENABLED, true)
    }

    fun setSleepSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SLEEP_SYNC_ENABLED, enabled).commit()
    }

    fun saveSleepSnapshot(
        totalDurationMinutes: Int,
        sleepScore: Int,
        bedtimeHHMM: String,
        wakeHHMM: String,
        stagesMinutesCsv: String,
        wakeTimestampMs: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putInt(KEY_SLEEP_DURATION_MIN, totalDurationMinutes.coerceIn(60, 960))
            .putInt(KEY_SLEEP_SCORE, sleepScore.coerceIn(1, 100))
            .putString(KEY_SLEEP_BEDTIME_HHMM, bedtimeHHMM)
            .putString(KEY_SLEEP_WAKE_HHMM, wakeHHMM)
            .putString(KEY_SLEEP_STAGES_CSV, stagesMinutesCsv)
            .putLong(KEY_SLEEP_WAKE_TS_MS, wakeTimestampMs)
            .apply()
    }

    fun getSleepDurationMinutes(): Int {
        return prefs.getInt(KEY_SLEEP_DURATION_MIN, 462) // 7h 42m
    }

    fun getSleepScore(): Int {
        return prefs.getInt(KEY_SLEEP_SCORE, 86)
    }

    fun getSleepBedtimeHHMM(): String {
        return prefs.getString(KEY_SLEEP_BEDTIME_HHMM, "23:18") ?: "23:18"
    }

    fun getSleepWakeHHMM(): String {
        return prefs.getString(KEY_SLEEP_WAKE_HHMM, "07:00") ?: "07:00"
    }

    fun getSleepStagesCsv(): String {
        // Acordado, REM, Leve, Profundo (em minutos)
        return prefs.getString(KEY_SLEEP_STAGES_CSV, "18,110,229,105") ?: "18,110,229,105"
    }

    fun getSleepWakeTimestampMs(): Long {
        return prefs.getLong(KEY_SLEEP_WAKE_TS_MS, 0L)
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

    /**
     * GATILHO 2: Apps de Clima de Terceiros escolhidos pelo usuário nas configurações
     * que servem como Trigger de Notificação.
     */
    fun getSelectedWeatherTriggerPackages(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_WEATHER_TRIGGER_PKGS, DEFAULT_WEATHER_TRIGGER_PACKAGES)
            ?: DEFAULT_WEATHER_TRIGGER_PACKAGES
    }

    fun setSelectedWeatherTriggerPackages(packages: Set<String>) {
        prefs.edit().putStringSet(KEY_SELECTED_WEATHER_TRIGGER_PKGS, HashSet(packages)).commit()
    }

    /**
     * GATILHO 3 UNIFICADO (RELÓGIO STAND-ALONE): Serviço Stand-Alone Opcional de Clima + Sol
     * no Relógio (de hora em hora, usando a mesma localização).
     */
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

    /**
     * NASCER E PÔR DO SOL (SOLAR) — Unificado com a atualização de localização do Clima.
     */
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
        val lastT = pickedTemps.lastOrNull() ?: firstT

        val shiftAcrossHours = (maxT - minT) >= 3
        val shiftFromPreviousFetch = prevFirstTemp != null && abs(firstT - prevFirstTemp) >= 2
        val rainAlert = rainProbPct >= 45
        val weatherConditionChanged = conditionShiftDetected ||
            (prevCondition.isNotBlank() && !prevCondition.equals(weatherConditionLabel, ignoreCase = true))

        val shiftDetected = isPhoneWeatherAutoCheckEnabled() &&
            (shiftAcrossHours || shiftFromPreviousFetch || rainAlert || weatherConditionChanged)

        val resolvedLoc = formatCompactWeatherLocation(locationName.ifBlank { getPhoneWeatherLocationName() })
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
        if (locationName.isNotBlank()) {
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
        private const val KEY_CONTEXT_WINDOW_MODE = "context_window_mode"
        private const val KEY_AUTO_PRIMARY_CONTEXT = "auto_primary_context"
        private const val KEY_UNIFIED_CANVAS_HOLD_MIN = "unified_canvas_hold_min"
        private const val KEY_NOTIFY_ON_CANVAS_CHANGE = "notify_on_canvas_change"
        private const val KEY_NOTIFY_AGENDA_15MIN = "notify_agenda_15min"
        private const val KEY_AUTO_AGENDA_EXPOSURE_MIN = "auto_agenda_exposure_min"
        private const val KEY_AUTO_WEATHER_EXPOSURE_MIN = "auto_weather_exposure_min"
        private const val KEY_AUTO_SUN_EXPOSURE_MIN = "auto_sun_exposure_min"
        private const val KEY_AUTO_SLEEP_EXPOSURE_MIN = "auto_sleep_exposure_min"
        private const val KEY_SLEEP_SYNC_ENABLED = "sleep_sync_enabled"
        private const val KEY_SLEEP_DURATION_MIN = "sleep_duration_min"
        private const val KEY_SLEEP_SCORE = "sleep_score"
        private const val KEY_SLEEP_BEDTIME_HHMM = "sleep_bedtime_hhmm"
        private const val KEY_SLEEP_WAKE_HHMM = "sleep_wake_hhmm"
        private const val KEY_SLEEP_STAGES_CSV = "sleep_stages_csv"
        private const val KEY_SLEEP_WAKE_TS_MS = "sleep_wake_ts_ms"
        private const val KEY_HIGH_FREQ_15MIN_RULE = "high_freq_15min_rule"
        private const val KEY_UPDATE_ON_SCREEN_WAKE = "update_on_screen_wake"
        private const val KEY_PHONE_WEATHER_AUTO_CHECK_ENABLED = "phone_weather_auto_check_enabled"
        private const val KEY_PHONE_WEATHER_CHECK_INTERVAL_MIN = "phone_weather_check_interval_min"
        private const val KEY_SELECTED_WEATHER_TRIGGER_PKGS = "selected_weather_trigger_pkgs"
        private const val KEY_WATCH_STANDALONE_WEATHER_ENABLED = "watch_standalone_weather_enabled"
        private const val KEY_WATCH_STANDALONE_WEATHER_INTERVAL_MIN = "watch_standalone_weather_interval_min"
        private const val KEY_PHONE_SOLAR_AUTO_SYNC_ENABLED = "phone_solar_auto_sync_enabled"
        private const val KEY_WATCH_STANDALONE_SOLAR_ENABLED = "watch_standalone_solar_enabled"
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

        private val BRAZIL_STATE_UF_MAP = mapOf(
            "acre" to "AC",
            "alagoas" to "AL",
            "amapa" to "AP",
            "amazonas" to "AM",
            "bahia" to "BA",
            "ceara" to "CE",
            "distrito federal" to "DF",
            "espirito santo" to "ES",
            "goias" to "GO",
            "maranhao" to "MA",
            "mato grosso" to "MT",
            "mato grosso do sul" to "MS",
            "minas gerais" to "MG",
            "para" to "PA",
            "paraiba" to "PB",
            "parana" to "PR",
            "pernambuco" to "PE",
            "piaui" to "PI",
            "rio de janeiro" to "RJ",
            "rio grande do norte" to "RN",
            "rio grande do sul" to "RS",
            "rondonia" to "RO",
            "roraima" to "RR",
            "santa catarina" to "SC",
            "sao paulo" to "SP",
            "sergipe" to "SE",
            "tocantins" to "TO"
        )

        private fun stripAccents(input: String): String {
            return java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        }

        /**
         * Formata o nome do local para o subtítulo do Canvas de Clima:
         * 1. Converte o nome do estado para a sigla UF de 2 letras (ex: "Maranhão" -> "MA", "Paraná" -> "PR").
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

            val ufSigla = if (statePart.isNotBlank()) {
                val cleanState = stripAccents(statePart)
                    .lowercase(java.util.Locale.ROOT)
                    .replace(Regex("^estado d[eao]\\s+"), "")
                    .trim()
                when {
                    BRAZIL_STATE_UF_MAP.containsKey(cleanState) -> BRAZIL_STATE_UF_MAP[cleanState]!!
                    statePart.trim().length == 2 -> statePart.trim().uppercase(java.util.Locale.ROOT)
                    else -> {
                        val stateWords = cleanState.split(Regex("\\s+")).filter {
                            it.isNotBlank() && it !in setOf("de", "da", "do", "das", "dos")
                        }
                        if (stateWords.size >= 2) {
                            "${stateWords[0].first()}${stateWords[1].first()}".uppercase(java.util.Locale.ROOT)
                        } else {
                            statePart.trim().take(2).uppercase(java.util.Locale.ROOT)
                        }
                    }
                }
            } else {
                ""
            }

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
