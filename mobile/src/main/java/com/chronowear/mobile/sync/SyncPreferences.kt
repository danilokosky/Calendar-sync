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
     * GATILHO 1: Busca Periódica Autônoma pelo Próprio App do Telefone (independente de apps de terceiros).
     */
    fun isPhoneWeatherAutoCheckEnabled(): Boolean {
        return prefs.getBoolean(KEY_PHONE_WEATHER_AUTO_CHECK_ENABLED, true)
    }

    fun setPhoneWeatherAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE_WEATHER_AUTO_CHECK_ENABLED, enabled).commit()
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
     * GATILHO 3: Serviço Stand-Alone Opcional de Clima no Relógio (de hora em hora).
     */
    fun isWatchStandaloneWeatherEnabled(): Boolean {
        return prefs.getBoolean(KEY_WATCH_STANDALONE_WEATHER_ENABLED, false)
    }

    fun setWatchStandaloneWeatherEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WATCH_STANDALONE_WEATHER_ENABLED, enabled).commit()
    }

    fun getWatchStandaloneWeatherIntervalMinutes(): Int {
        return prefs.getInt(KEY_WATCH_STANDALONE_WEATHER_INTERVAL_MIN, 60).coerceAtLeast(15)
    }

    fun setWatchStandaloneWeatherIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_WATCH_STANDALONE_WEATHER_INTERVAL_MIN, minutes.coerceAtLeast(15)).commit()
    }

    /**
     * NASCER E PÔR DO SOL (SOLAR) — Direto do Smartphone e Stand-Alone no Relógio.
     */
    fun isPhoneSolarAutoSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_PHONE_SOLAR_AUTO_SYNC_ENABLED, true)
    }

    fun setPhoneSolarAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHONE_SOLAR_AUTO_SYNC_ENABLED, enabled).commit()
    }

    fun isWatchStandaloneSolarEnabled(): Boolean {
        return prefs.getBoolean(KEY_WATCH_STANDALONE_SOLAR_ENABLED, false)
    }

    fun setWatchStandaloneSolarEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WATCH_STANDALONE_SOLAR_ENABLED, enabled).commit()
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

    /**
     * Salva a previsão horária buscada pelo próprio app do telefone e avalia automaticamente
     * se há uma mudança de temperatura nas próximas horas OU mudança climática (chuva, tempestade,
     * garoa, neblina ou transição de condição meteorológica WMO).
     * Se detectar mudança, registra o alerta dinâmico para notificar o relógio a mudar o Canvas!
     */
    fun savePhoneFetchedWeatherAndDetectShift(
        pickedTemps: List<Int>,
        pickedLabels: List<String>,
        rainProbPct: Int,
        weatherConditionLabel: String = "Parcialmente nublado",
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

        val editor = prefs.edit()
            .putString(KEY_WEATHER_TEMPS_CSV, tempsCsv)
            .putString(KEY_WEATHER_HOURS_CSV, hoursCsv)
            .putInt(KEY_WEATHER_RAIN_PROB, rainProbPct.coerceIn(0, 100))
            .putString(KEY_WEATHER_CONDITION_LABEL, weatherConditionLabel)
            .putLong(KEY_WEATHER_LAST_FETCH_MS, fetchTimestampMs)

        if (shiftDetected) {
            val headline = when {
                rainAlert && shiftAcrossHours ->
                    "$weatherConditionLabel (${rainProbPct}%) • ${firstT}° → ${lastT}°C"
                weatherConditionChanged ->
                    "$weatherConditionLabel • ${firstT}° → ${lastT}°C (Chuva ${rainProbPct}%)"
                rainAlert ->
                    "Previsão de $weatherConditionLabel (${rainProbPct}%) nas próx. horas"
                shiftAcrossHours ->
                    "$weatherConditionLabel • Variação ${firstT}° → ${lastT}°C"
                else ->
                    "$weatherConditionLabel • Temperatura atual ${firstT}°C"
            }
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

    fun getPhoneWeatherLastFetchMs(): Long {
        return prefs.getLong(KEY_WEATHER_LAST_FETCH_MS, 0L)
    }

    /**
     * Registra quando o telefone observa uma notificação sobre clima vinda de um App de terceiro
     * escolhido pelo usuário nas configurações.
     */
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
