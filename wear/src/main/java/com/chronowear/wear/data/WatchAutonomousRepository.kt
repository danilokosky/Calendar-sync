package com.chronowear.wear.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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

data class HourlyWeatherSnapshot(
    val alertSource: String,
    val alertHeadline: String,
    val weatherConditionLabel: String,
    val locationName: String,
    val updatedTimeHHMM: String,
    val hourlyLabels: List<String>,
    val hourlyTempsC: List<Int>,
    val rainProbabilityPct: Int,
    val triggeredByWatchNotification: Boolean
)

data class WeeklyStepsSnapshot(
    val todaySteps: Int,
    val dailyGoal: Int,
    val weekDaysLabels: List<String>,
    val weekDaysSteps: List<Int>
)

data class SolarSnapshot(
    val sunriseHHMM: String,
    val sunsetHHMM: String,
    val currentMinutesOfDay: Int,
    val sunriseMinutesOfDay: Int,
    val sunsetMinutesOfDay: Int
)

data class SleepSnapshot(
    val durationMinutes: Int,
    val sleepScore: Int,
    val bedtimeHHMM: String,
    val wakeHHMM: String,
    val stagesMinutes: List<Int>,
    val hasDataForToday: Boolean = true
)

data class CachedWearTask(
    val id: String,
    val title: String,
    val dueHHMM: String,
    val sourceLabel: String,
    val completed: Boolean = false,
    val isTodayOrTimeless: Boolean = true
)

data class TasksSnapshot(
    val pendingCount: Int,
    val completedCount: Int,
    val tasks: List<CachedWearTask>,
    val triggeredByTaskAlert: Boolean
)

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
        cardPngBase64: String? = null,
        contextMode: String? = null,
        autoPrimaryContext: String? = null,
        unifiedCanvasHoldMin: Int? = null,
        notifyOnCanvasChange: Boolean? = null,
        notifyAgenda15Min: Boolean? = null,
        autoAgendaExposureMin: Int? = null,
        autoWeatherExposureMin: Int? = null,
        autoSunExposureMin: Int? = null,
        autoSleepExposureMin: Int? = null,
        highFreq15MinRule: Boolean? = null,
        updateOnScreenWake: Boolean? = null,
        weatherTempsCsv: String? = null,
        weatherHoursCsv: String? = null,
        weatherRainProb: Int? = null,
        weatherConditionLabel: String? = null,
        weatherLocationName: String? = null,
        weatherUpdatedHHMM: String? = null,
        weatherAlertSource: String? = null,
        weatherAlertHeadline: String? = null,
        weatherAlertTimestampMs: Long? = null,
        watchStandaloneWeatherEnabled: Boolean? = null,
        watchStandaloneWeatherIntervalMin: Int? = null,
        selectedWeatherTriggerPackagesCsv: String? = null,
        solarSunriseHHMM: String? = null,
        solarSunsetHHMM: String? = null,
        watchStandaloneSolarEnabled: Boolean? = null,
        sleepSyncEnabled: Boolean? = null,
        sleepDurationMinutes: Int? = null,
        sleepScore: Int? = null,
        sleepBedtimeHHMM: String? = null,
        sleepWakeHHMM: String? = null,
        sleepStagesCsv: String? = null,
        sleepWakeTimestampMs: Long? = null,
        sleepHasDataToday: Boolean? = null,
        tasksSyncEnabled: Boolean? = null,
        tasksJson: String? = null,
        tasksAlertTimestampMs: Long? = null,
        autoFallbackAgendaToTasks: Boolean? = null,
        weatherLat: Double? = null,
        weatherLon: Double? = null
    ) {
        val editor = prefs.edit()
            .putLong("last_sync_ms", syncTimestampMs)
            .putInt("horizon_days", horizonDays)
            .putInt("progress_window_minutes", progressWindowMinutes)
        if (!cardPngBase64.isNullOrBlank()) {
            editor.putString("phone_rendered_card_png_b64", cardPngBase64)
            editor.putLong("phone_rendered_card_ms", syncTimestampMs)
        }
        if (!contextMode.isNullOrBlank()) {
            editor.putString("context_window_mode", contextMode)
        }
        if (!autoPrimaryContext.isNullOrBlank()) {
            editor.putString("auto_primary_context", autoPrimaryContext)
        }
        val effectiveUnifiedMin = unifiedCanvasHoldMin
            ?: autoWeatherExposureMin
            ?: autoAgendaExposureMin
            ?: autoSunExposureMin
            ?: autoSleepExposureMin
        if (effectiveUnifiedMin != null && effectiveUnifiedMin >= 5) {
            val clamped = effectiveUnifiedMin.coerceIn(5, 180)
            editor.putInt("unified_canvas_hold_min", clamped)
            editor.putInt("auto_agenda_exposure_min", clamped)
            editor.putInt("auto_weather_exposure_min", clamped)
            editor.putInt("auto_sun_exposure_min", clamped)
            editor.putInt("auto_sleep_exposure_min", clamped)
        }
        if (notifyOnCanvasChange != null) {
            editor.putBoolean("notify_on_canvas_change", notifyOnCanvasChange)
        }
        if (notifyAgenda15Min != null) {
            editor.putBoolean("notify_agenda_15min", notifyAgenda15Min)
        }
        if (highFreq15MinRule != null) {
            editor.putBoolean("high_freq_15min_rule", highFreq15MinRule)
        }
        if (updateOnScreenWake != null) {
            editor.putBoolean("update_on_screen_wake", updateOnScreenWake)
        }
        val unifiedWatchStandaloneLocation = watchStandaloneWeatherEnabled ?: watchStandaloneSolarEnabled
        if (unifiedWatchStandaloneLocation != null) {
            editor.putBoolean("watch_standalone_weather_enabled", unifiedWatchStandaloneLocation)
            editor.putBoolean("watch_standalone_solar_enabled", unifiedWatchStandaloneLocation)
        }
        if (watchStandaloneWeatherIntervalMin != null && watchStandaloneWeatherIntervalMin >= 15) {
            editor.putInt("watch_standalone_weather_interval_min", watchStandaloneWeatherIntervalMin)
        }
        if (!selectedWeatherTriggerPackagesCsv.isNullOrBlank()) {
            editor.putString("selected_weather_trigger_packages_csv", selectedWeatherTriggerPackagesCsv)
        }
        if (!weatherTempsCsv.isNullOrBlank()) {
            editor.putString("weather_temps_csv", weatherTempsCsv)
        }
        if (!weatherHoursCsv.isNullOrBlank()) {
            editor.putString("weather_hours_csv", weatherHoursCsv)
        }
        if (weatherRainProb != null) {
            editor.putInt("weather_rain_prob", weatherRainProb.coerceIn(0, 100))
        }
        if (!weatherConditionLabel.isNullOrBlank()) {
            editor.putString("weather_condition_label", weatherConditionLabel)
        }
        if (weatherLat != null && weatherLon != null && (weatherLat != 0.0 || weatherLon != 0.0)) {
            editor.putString("weather_saved_lat", weatherLat.toString())
            editor.putString("weather_saved_lon", weatherLon.toString())
        }
        if (!weatherLocationName.isNullOrBlank() &&
            !weatherLocationName.equals("Local, BR", ignoreCase = true)
        ) {
            editor.putString("weather_location_name", formatCompactWeatherLocation(weatherLocationName))
        }
        if (!weatherUpdatedHHMM.isNullOrBlank()) {
            editor.putString("weather_updated_hhmm", weatherUpdatedHHMM)
        }
        if (!weatherAlertSource.isNullOrBlank()) {
            editor.putString("weather_alert_source", weatherAlertSource)
        }
        if (!weatherAlertHeadline.isNullOrBlank()) {
            editor.putString("weather_alert_headline", weatherAlertHeadline)
        }
        if (weatherAlertTimestampMs != null && weatherAlertTimestampMs > 0L) {
            val currentTs = prefs.getLong("weather_alert_timestamp_ms", 0L)
            if (weatherAlertTimestampMs > currentTs) {
                editor.putLong("weather_alert_timestamp_ms", weatherAlertTimestampMs)
                // Mudança climática tem prioridade máxima: cancela toque manual no Canvas
                editor.remove("tap_override_context_mode")
                editor.putLong("tap_override_timestamp_ms", 0L)
            }
        }
        if (!solarSunriseHHMM.isNullOrBlank()) {
            editor.putString("solar_sunrise_hhmm", solarSunriseHHMM)
        }
        if (!solarSunsetHHMM.isNullOrBlank()) {
            editor.putString("solar_sunset_hhmm", solarSunsetHHMM)
        }
        if (sleepSyncEnabled != null) {
            editor.putBoolean("sleep_sync_enabled", sleepSyncEnabled)
        }
        if (sleepHasDataToday != null) {
            editor.putBoolean("sleep_has_data_today", sleepHasDataToday)
        }
        if (sleepDurationMinutes != null) {
            editor.putInt("sleep_duration_minutes", sleepDurationMinutes.coerceIn(0, 960))
        }
        if (sleepScore != null) {
            editor.putInt("sleep_score", sleepScore.coerceIn(0, 100))
        }
        if (!sleepBedtimeHHMM.isNullOrBlank()) {
            editor.putString("sleep_bedtime_hhmm", sleepBedtimeHHMM)
        }
        if (!sleepWakeHHMM.isNullOrBlank()) {
            editor.putString("sleep_wake_hhmm", sleepWakeHHMM)
        }
        if (!sleepStagesCsv.isNullOrBlank()) {
            editor.putString("sleep_stages_csv", sleepStagesCsv)
        }
        if (sleepWakeTimestampMs != null) {
            val currentWakeTs = prefs.getLong("sleep_wake_timestamp_ms", 0L)
            editor.putLong("sleep_wake_timestamp_ms", sleepWakeTimestampMs)
            if (sleepWakeTimestampMs > currentWakeTs && sleepWakeTimestampMs > 0L) {
                // Novo registro de despertar/sono tem prioridade máxima: cancela toque manual
                editor.remove("tap_override_context_mode")
                editor.putLong("tap_override_timestamp_ms", 0L)
            }
        }
        if (tasksSyncEnabled != null) {
            editor.putBoolean("tasks_sync_enabled", tasksSyncEnabled)
        }
        if (!tasksJson.isNullOrBlank()) {
            editor.putString("cached_tasks_json", tasksJson)
        }
        if (tasksAlertTimestampMs != null && tasksAlertTimestampMs > 0L) {
            val currentTaskTs = prefs.getLong("tasks_alert_timestamp_ms", 0L)
            if (tasksAlertTimestampMs > currentTaskTs) {
                editor.putLong("tasks_alert_timestamp_ms", tasksAlertTimestampMs)
                // Alerta de Tarefa (Samsung Reminder / Google Tasks) tem prioridade máxima
                editor.remove("tap_override_context_mode")
                editor.putLong("tap_override_timestamp_ms", 0L)
            }
        }
        if (autoFallbackAgendaToTasks != null) {
            editor.putBoolean("auto_fallback_agenda_to_tasks", autoFallbackAgendaToTasks)
        }
        editor.commit()

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
        // Desativado: o relógio sempre renderiza o Canvas dinamicamente a partir do cache local
        // para que o temporizador, a barra de progresso e a lista de compromissos avancem a cada minuto
        // mesmo sem sincronização recente com o smartphone.
        return null
    }

    fun getContextWindowMode(): String {
        return prefs.getString("context_window_mode", MODE_DYNAMIC) ?: MODE_DYNAMIC
    }

    fun setContextWindowMode(mode: String) {
        val editor = prefs.edit().putString("context_window_mode", mode)
        if (mode != MODE_DYNAMIC) {
            editor.putString("auto_primary_context", mode)
        }
        editor.remove("tap_override_context_mode")
        editor.putLong("tap_override_timestamp_ms", 0L)
        editor.commit()
    }

    /**
     * Canvas Padrão Principal: aparência padrão para a qual o relógio sempre retorna
     * após expirar o tempo unificado de permanência de um Canvas temporário ou de um toque (Tap).
     */
    fun getAutoPrimaryContext(): String {
        val cfg = getContextWindowMode()
        if (cfg != MODE_DYNAMIC) return cfg
        return prefs.getString("auto_primary_context", MODE_EVENTS) ?: MODE_EVENTS
    }

    /**
     * Tempo Unificado (em minutos) em que qualquer Canvas que não seja o Padrão Principal
     * (aberto por Toque no Canvas da Watch Face ou disparado por Clima, Agenda, Sol ou Sono)
     * permanece fixo antes de retornar automaticamente ao Canvas Padrão Principal.
     */
    fun getUnifiedCanvasHoldMinutes(): Int {
        return prefs.getInt("unified_canvas_hold_min", 30).coerceIn(5, 180)
    }

    fun setUnifiedCanvasHoldMinutes(minutes: Int) {
        val clamped = minutes.coerceIn(5, 180)
        prefs.edit()
            .putInt("unified_canvas_hold_min", clamped)
            .putInt("auto_agenda_exposure_min", clamped)
            .putInt("auto_weather_exposure_min", clamped)
            .putInt("auto_sun_exposure_min", clamped)
            .putInt("auto_sleep_exposure_min", clamped)
            .commit()
    }

    fun isNotifyOnCanvasChangeEnabled(): Boolean {
        return prefs.getBoolean("notify_on_canvas_change", true)
    }

    fun setNotifyOnCanvasChangeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notify_on_canvas_change", enabled).commit()
    }

    fun isNotifyAgenda15MinEnabled(): Boolean {
        return prefs.getBoolean("notify_agenda_15min", true)
    }

    fun setNotifyAgenda15MinEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("notify_agenda_15min", enabled).commit()
    }

    fun getAutoAgendaExposureMinutes(): Int = 15

    fun getAutoWeatherExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun getAutoSunExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun getAutoSleepExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun isSleepSyncEnabled(): Boolean {
        return prefs.getBoolean("sleep_sync_enabled", true)
    }

    /**
     * Retorna o resumo de sono garantindo que os dados pertencem realmente ao dia de hoje.
     * Caso não haja sessão de sono verificada para hoje, hasDataForToday = false para que o
     * Canvas informe claramente "Não há dados de sono para hoje".
     */
    fun getSleepSnapshot(nowMs: Long = System.currentTimeMillis()): SleepSnapshot {
        val dur = prefs.getInt("sleep_duration_minutes", 462)
        val score = prefs.getInt("sleep_score", 86)
        val bed = prefs.getString("sleep_bedtime_hhmm", "23:18") ?: "23:18"
        val wake = prefs.getString("sleep_wake_hhmm", "07:00") ?: "07:00"
        val stagesStr = prefs.getString("sleep_stages_csv", "18,95,244,105") ?: "18,95,244,105"
        val stages = stagesStr.split(",").mapNotNull { it.trim().toIntOrNull() }
            .ifEmpty { listOf(18, 95, 244, 105) }

        val wakeTs = prefs.getLong("sleep_wake_timestamp_ms", 0L)
        val explicitFlag = prefs.getBoolean("sleep_has_data_today", true)
        val verifiedForToday = explicitFlag && dur > 0 && (wakeTs <= 0L || isSameCalendarDay(nowMs, wakeTs))

        return SleepSnapshot(
            durationMinutes = if (verifiedForToday) dur else 0,
            sleepScore = if (verifiedForToday) score else 0,
            bedtimeHHMM = bed,
            wakeHHMM = wake,
            stagesMinutes = stages,
            hasDataForToday = verifiedForToday
        )
    }

    fun recordWatchSleepNotification(
        durationMinutes: Int = 462,
        sleepScore: Int = 86,
        nowMs: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putInt("sleep_duration_minutes", durationMinutes.coerceIn(60, 960))
            .putInt("sleep_score", sleepScore.coerceIn(1, 100))
            .putBoolean("sleep_has_data_today", true)
            .putLong("sleep_wake_timestamp_ms", nowMs)
            // Prioridade máxima: cancela toque manual no Canvas
            .remove("tap_override_context_mode")
            .putLong("tap_override_timestamp_ms", 0L)
            .apply()
    }

    fun hasActiveSleepWakeExposure(nowMs: Long, exposureMinutes: Int = getUnifiedCanvasHoldMinutes()): Boolean {
        if (!isSleepSyncEnabled()) return false
        val sleep = getSleepSnapshot(nowMs)
        if (!sleep.hasDataForToday) return false

        val wakeTs = prefs.getLong("sleep_wake_timestamp_ms", 0L)
        val windowMs = TimeUnit.MINUTES.toMillis(exposureMinutes.toLong())
        if (wakeTs > 0L && (nowMs - wakeTs) in 0..windowMs) {
            return true
        }
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val curMinsOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val wakeParts = sleep.wakeHHMM.split(":")
        if (wakeParts.size == 2) {
            val wh = wakeParts[0].trim().toIntOrNull() ?: 7
            val wm = wakeParts[1].trim().toIntOrNull() ?: 0
            val wakeMinsOfDay = wh * 60 + wm
            if (curMinsOfDay in wakeMinsOfDay..(wakeMinsOfDay + exposureMinutes)) {
                return true
            }
        }
        val bedParts = sleep.bedtimeHHMM.split(":")
        if (bedParts.size == 2) {
            val bh = bedParts[0].trim().toIntOrNull() ?: 23
            val bm = bedParts[1].trim().toIntOrNull() ?: 18
            val bedMinsOfDay = bh * 60 + bm
            if (curMinsOfDay in bedMinsOfDay..(bedMinsOfDay + exposureMinutes)) {
                return true
            }
        }
        return false
    }

    fun isHighFreq15MinRuleEnabled(): Boolean {
        return prefs.getBoolean("high_freq_15min_rule", true)
    }

    fun setHighFreq15MinRuleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("high_freq_15min_rule", enabled).commit()
    }

    fun isUpdateOnScreenWakeEnabled(): Boolean {
        return prefs.getBoolean("update_on_screen_wake", true)
    }

    fun setUpdateOnScreenWakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("update_on_screen_wake", enabled).commit()
    }

    fun isWatchStandaloneWeatherEnabled(): Boolean {
        return prefs.getBoolean(
            "watch_standalone_weather_enabled",
            prefs.getBoolean("watch_standalone_solar_enabled", false)
        )
    }

    fun setWatchStandaloneWeatherEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean("watch_standalone_weather_enabled", enabled)
            .putBoolean("watch_standalone_solar_enabled", enabled)
            .commit()
    }

    fun getWatchStandaloneWeatherIntervalMinutes(): Int {
        return prefs.getInt("watch_standalone_weather_interval_min", 60).coerceAtLeast(15)
    }

    fun setWatchStandaloneWeatherIntervalMinutes(minutes: Int) {
        prefs.edit().putInt("watch_standalone_weather_interval_min", minutes.coerceAtLeast(15)).commit()
    }

    fun getLastStandaloneWeatherFetchMs(): Long {
        return prefs.getLong("watch_standalone_last_fetch_ms", 0L)
    }

    fun setLastStandaloneWeatherFetchMs(timestampMs: Long) {
        prefs.edit().putLong("watch_standalone_last_fetch_ms", timestampMs).apply()
    }

    fun isWatchStandaloneSolarEnabled(): Boolean {
        return isWatchStandaloneWeatherEnabled()
    }

    fun setWatchStandaloneSolarEnabled(enabled: Boolean) {
        setWatchStandaloneWeatherEnabled(enabled)
    }

    fun saveWeatherCoordinates(lat: Double, lon: Double) {
        if (lat != 0.0 || lon != 0.0) {
            prefs.edit()
                .putString("weather_saved_lat", lat.toString())
                .putString("weather_saved_lon", lon.toString())
                .apply()
        }
    }

    fun getSavedWeatherCoordinates(): Pair<Double, Double>? {
        val latStr = prefs.getString("weather_saved_lat", null) ?: return null
        val lonStr = prefs.getString("weather_saved_lon", null) ?: return null
        val lat = latStr.toDoubleOrNull() ?: return null
        val lon = lonStr.toDoubleOrNull() ?: return null
        return lat to lon
    }

    fun getWeatherLocationName(): String {
        val raw = prefs.getString("weather_location_name", "São Paulo, SP") ?: "São Paulo, SP"
        return formatCompactWeatherLocation(raw)
    }

    fun setWeatherLocationName(locationName: String) {
        if (locationName.isNotBlank() && !locationName.equals("Local, BR", ignoreCase = true)) {
            prefs.edit()
                .putString("weather_location_name", formatCompactWeatherLocation(locationName))
                .apply()
        }
    }

    fun saveSolarTimes(sunriseHHMM: String, sunsetHHMM: String) {
        prefs.edit()
            .putString("solar_sunrise_hhmm", sunriseHHMM)
            .putString("solar_sunset_hhmm", sunsetHHMM)
            .apply()
    }

    fun getSolarSnapshot(nowMs: Long): SolarSnapshot {
        val riseStr = prefs.getString("solar_sunrise_hhmm", "05:27") ?: "05:27"
        val setStr = prefs.getString("solar_sunset_hhmm", "17:40") ?: "17:40"

        fun parseHHMMToMins(hhmm: String, defaultMins: Int): Int {
            val parts = hhmm.split(":")
            if (parts.size != 2) return defaultMins
            val h = parts[0].trim().toIntOrNull() ?: return defaultMins
            val m = parts[1].trim().toIntOrNull() ?: return defaultMins
            return (h * 60 + m).coerceIn(0, 1439)
        }

        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val curMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return SolarSnapshot(
            sunriseHHMM = riseStr,
            sunsetHHMM = setStr,
            currentMinutesOfDay = curMins,
            sunriseMinutesOfDay = parseHHMMToMins(riseStr, 5 * 60 + 27),
            sunsetMinutesOfDay = parseHHMMToMins(setStr, 17 * 60 + 40)
        )
    }

    fun getSelectedWeatherTriggerPackages(): Set<String> {
        val csv = prefs.getString(
            "selected_weather_trigger_packages_csv",
            DEFAULT_WEATHER_TRIGGER_PACKAGES_CSV
        ) ?: DEFAULT_WEATHER_TRIGGER_PACKAGES_CSV
        return csv.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
    }

    /**
     * Avalia se a curva horária buscada pelo relógio apresenta mudança significativa de
     * temperatura OU mudança climática (chuva, garoa, tempestade, neblina).
     */
    fun saveStandaloneWeatherAndDetectShift(
        pickedTemps: List<Int>,
        pickedLabels: List<String>,
        maxRainProbPct: Int,
        weatherConditionLabel: String = "Céu limpo",
        locationName: String? = null,
        conditionShiftDetected: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prevTempsStr = prefs.getString("weather_temps_csv", "") ?: ""
        val prevFirstTemp = prevTempsStr.split(",").firstOrNull()?.trim()?.toIntOrNull()
        val prevCondition = prefs.getString("weather_condition_label", "") ?: ""

        val tempsCsv = pickedTemps.joinToString(",")
        val hoursCsv = pickedLabels.joinToString(",")

        val minT = pickedTemps.minOrNull() ?: 22
        val maxT = pickedTemps.maxOrNull() ?: 22
        val firstT = pickedTemps.firstOrNull() ?: 22
        val lastT = pickedTemps.lastOrNull() ?: firstT

        val shiftAcrossHours = (maxT - minT) >= 3
        val shiftFromPreviousFetch = prevFirstTemp != null && abs(firstT - prevFirstTemp) >= 2
        val rainAlert = maxRainProbPct >= 45
        val weatherConditionChanged = conditionShiftDetected ||
            (prevCondition.isNotBlank() && !prevCondition.equals(weatherConditionLabel, ignoreCase = true))

        val shiftDetected = shiftAcrossHours || shiftFromPreviousFetch || rainAlert || weatherConditionChanged

        val updatedHHMM = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs))
        val editor = prefs.edit()
            .putString("weather_temps_csv", tempsCsv)
            .putString("weather_hours_csv", hoursCsv)
            .putInt("weather_rain_prob", maxRainProbPct.coerceIn(0, 100))
            .putString("weather_condition_label", weatherConditionLabel)
            .putString("weather_updated_hhmm", updatedHHMM)
            .putLong("watch_standalone_last_fetch_ms", nowMs)

        if (!locationName.isNullOrBlank() && !locationName.equals("Local, BR", ignoreCase = true)) {
            editor.putString("weather_location_name", formatCompactWeatherLocation(locationName))
        }

        if (shiftDetected) {
            val headline = when {
                rainAlert && shiftAcrossHours ->
                    "$weatherConditionLabel (${maxRainProbPct}%) • ${firstT}° → ${lastT}°C"
                weatherConditionChanged ->
                    "$weatherConditionLabel • ${firstT}° → ${lastT}°C (Chuva ${maxRainProbPct}%)"
                rainAlert ->
                    "Previsão de $weatherConditionLabel (${maxRainProbPct}%)"
                shiftAcrossHours ->
                    "$weatherConditionLabel • Variação ${firstT}° → ${lastT}°C"
                else ->
                    "$weatherConditionLabel • Temperatura ${firstT}°C"
            }
            editor.putString("weather_alert_source", "Clima")
            editor.putString("weather_alert_headline", headline)
            editor.putLong("weather_alert_timestamp_ms", nowMs)
            // Mudança climática tem prioridade máxima sobre toque manual
            editor.remove("tap_override_context_mode")
            editor.putLong("tap_override_timestamp_ms", 0L)
        }
        editor.apply()
        return shiftDetected
    }

    fun recordWatchWeatherNotificationAlert(
        sourceAppLabel: String = "Clima",
        headline: String,
        tempsCsv: String? = null,
        hoursCsv: String? = null,
        rainProbPct: Int? = null,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val updatedHHMM = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs))
        val editor = prefs.edit()
            .putString("weather_alert_source", sourceAppLabel)
            .putString("weather_alert_headline", headline)
            .putString("weather_updated_hhmm", updatedHHMM)
            .putLong("weather_alert_timestamp_ms", nowMs)
            // Prioridade máxima: cancela toque manual
            .remove("tap_override_context_mode")
            .putLong("tap_override_timestamp_ms", 0L)

        if (!tempsCsv.isNullOrBlank()) {
            editor.putString("weather_temps_csv", tempsCsv)
        }
        if (!hoursCsv.isNullOrBlank()) {
            editor.putString("weather_hours_csv", hoursCsv)
        }
        if (rainProbPct != null) {
            editor.putInt("weather_rain_prob", rainProbPct.coerceIn(0, 100))
        }
        editor.apply()
    }

    fun hasActiveWeatherNotificationOverride(
        nowMs: Long,
        windowMinutes: Long = getUnifiedCanvasHoldMinutes().toLong()
    ): Boolean {
        val alertTs = prefs.getLong("weather_alert_timestamp_ms", 0L)
        if (alertTs <= 0L) return false
        return (nowMs - alertTs) in 0..TimeUnit.MINUTES.toMillis(windowMinutes)
    }

    fun getHourlyWeatherSnapshot(nowMs: Long): HourlyWeatherSnapshot {
        val source = prefs.getString("weather_alert_source", "Clima") ?: "Clima"
        val headline = prefs.getString("weather_alert_headline", "Previsão para as próximas horas")
            ?: "Previsão para as próximas horas"
        val conditionLabel = prefs.getString("weather_condition_label", "Céu limpo") ?: "Céu limpo"
        val rawLocation = prefs.getString("weather_location_name", "São Paulo, SP") ?: "São Paulo, SP"
        val locationName = formatCompactWeatherLocation(rawLocation)
        val fallbackTs = prefs.getLong("watch_standalone_last_fetch_ms", 0L)
            .takeIf { it > 0L }
            ?: prefs.getLong("last_sync_ms", nowMs).takeIf { it > 0L }
            ?: nowMs
        val fallbackHHMM = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(fallbackTs))
        val updatedTimeHHMM = prefs.getString("weather_updated_hhmm", fallbackHHMM) ?: fallbackHHMM
        val tempsStr = prefs.getString("weather_temps_csv", "28,28,27,27,27") ?: "28,28,27,27,27"
        val hoursStr = prefs.getString("weather_hours_csv", "Agora,23h,01h,03h,05h") ?: "Agora,23h,01h,03h,05h"
        val rainProb = prefs.getInt("weather_rain_prob", 20)

        val temps = tempsStr.split(",").mapNotNull { it.trim().toIntOrNull() }
            .ifEmpty { listOf(28, 28, 27, 27, 27) }
        val labels = hoursStr.split(",").map { it.trim() }
            .ifEmpty { listOf("Agora", "23h", "01h", "03h", "05h") }

        return HourlyWeatherSnapshot(
            alertSource = source,
            alertHeadline = headline,
            weatherConditionLabel = conditionLabel,
            locationName = locationName,
            updatedTimeHHMM = updatedTimeHHMM,
            hourlyLabels = labels,
            hourlyTempsC = temps,
            rainProbabilityPct = rainProb,
            triggeredByWatchNotification = hasActiveWeatherNotificationOverride(nowMs)
        )
    }

    fun getWeeklyStepsSnapshot(): WeeklyStepsSnapshot {
        val today = prefs.getInt("steps_today", 9567)
        val goal = prefs.getInt("steps_goal", 10000)
        val csv = prefs.getString("steps_week_csv", "6400,8200,7100,9500,9567,5300,4900")
            ?: "6400,8200,7100,9500,9567,5300,4900"
        val stepsList = csv.split(",").mapNotNull { it.trim().toIntOrNull() }
            .ifEmpty { listOf(6400, 8200, 7100, 9500, 9567, 5300, 4900) }
        return WeeklyStepsSnapshot(
            todaySteps = today,
            dailyGoal = goal,
            weekDaysLabels = listOf("S", "T", "Q", "Q", "S", "S", "D"),
            weekDaysSteps = stepsList
        )
    }

    fun isTasksSyncEnabled(): Boolean {
        return prefs.getBoolean("tasks_sync_enabled", true)
    }

    fun setTasksSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tasks_sync_enabled", enabled).commit()
    }

    fun isAutoFallbackAgendaToTasksEnabled(): Boolean {
        return prefs.getBoolean("auto_fallback_agenda_to_tasks", true)
    }

    fun setAutoFallbackAgendaToTasksEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_fallback_agenda_to_tasks", enabled).commit()
    }

    fun recordWatchTaskNotification(
        title: String,
        sourceLabel: String = "Reminder",
        dueHHMM: String = "",
        nowMs: Long = System.currentTimeMillis()
    ) {
        val currentTasks = getTasksSnapshot(nowMs).tasks.toMutableList()
        val formattedDue = dueHHMM.trim().ifBlank { "Hoje" }
        val cleanTitle = title.trim().ifBlank { "Lembrete pendente" }
        currentTasks.removeAll { it.title.equals(cleanTitle, ignoreCase = true) }
        currentTasks.add(
            0,
            CachedWearTask(
                id = "task-${nowMs}",
                title = cleanTitle,
                dueHHMM = formattedDue,
                sourceLabel = sourceLabel,
                completed = false,
                isTodayOrTimeless = true
            )
        )
        val arr = JSONArray()
        currentTasks.filter { !it.completed && it.isTodayOrTimeless }.take(6).forEach { t ->
            val obj = org.json.JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("dueHHMM", t.dueHHMM)
                put("sourceLabel", t.sourceLabel)
                put("completed", false)
                put("isTodayOrTimeless", true)
            }
            arr.put(obj)
        }
        prefs.edit()
            .putString("cached_tasks_json", arr.toString())
            .putLong("tasks_alert_timestamp_ms", nowMs)
            .remove("tap_override_context_mode")
            .putLong("tap_override_timestamp_ms", 0L)
            .apply()
    }

    fun hasActiveTaskAlertOverride(
        nowMs: Long,
        windowMinutes: Long = getUnifiedCanvasHoldMinutes().toLong()
    ): Boolean {
        if (!isTasksSyncEnabled()) return false
        val alertTs = prefs.getLong("tasks_alert_timestamp_ms", 0L)
        if (alertTs <= 0L) return false
        return (nowMs - alertTs) in 0..TimeUnit.MINUTES.toMillis(windowMinutes)
    }

    /**
     * Retorna apenas as tarefas NÃO CONCLUÍDAS (completed == false) que sejam para HOJE
     * ou SEM HORÁRIO/DATA ("Hoje" / "Sem horário" / ""). Tarefas concluídas saem imediatamente
     * do Canvas para não ocupar espaço, e tarefas agendadas para outros dias são ignoradas.
     */
    fun getTasksSnapshot(nowMs: Long = System.currentTimeMillis()): TasksSnapshot {
        val defaultTasks = listOf(
            CachedWearTask(
                id = "t-1",
                title = "Enviar relatório Sprint",
                dueHHMM = "14:00",
                sourceLabel = "Tasks",
                completed = false,
                isTodayOrTimeless = true
            ),
            CachedWearTask(
                id = "t-2",
                title = "Retornar cliente One UI",
                dueHHMM = "16:30",
                sourceLabel = "Reminder",
                completed = false,
                isTodayOrTimeless = true
            ),
            CachedWearTask(
                id = "t-3",
                title = "Revisar PR Watch Face",
                dueHHMM = "Hoje",
                sourceLabel = "Tasks",
                completed = false,
                isTodayOrTimeless = true
            )
        )
        val rawJson = prefs.getString("cached_tasks_json", "") ?: ""
        val parsed = mutableListOf<CachedWearTask>()
        var totalCompletedCount = 0
        var hadExplicitJson = false
        if (rawJson.isNotBlank()) {
            try {
                val arr = JSONArray(rawJson)
                hadExplicitJson = true
                val todayDateIso = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(nowMs))
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title", "").trim()
                    if (title.isBlank()) continue
                    val isDone = obj.optBoolean("completed", false)
                    if (isDone) {
                        totalCompletedCount++
                        continue // Tarefas concluídas saem do Canvas para não ocupar espaço
                    }
                    val dueDateIso = obj.optString("dueDateIso", "").trim()
                    val isTodayOrTimelessFlag = obj.optBoolean("isTodayOrTimeless", true)
                    val isEligibleDate = isTodayOrTimelessFlag &&
                        (dueDateIso.isBlank() || dueDateIso == todayDateIso)
                    if (!isEligibleDate) continue

                    val rawDue = obj.optString("dueHHMM", "").trim()
                    val normalizedDue = if (rawDue.isBlank() || rawDue.equals("Sem horário", ignoreCase = true)) {
                        "Hoje"
                    } else {
                        rawDue
                    }
                    parsed.add(
                        CachedWearTask(
                            id = obj.optString("id", "t-$i"),
                            title = title,
                            dueHHMM = normalizedDue,
                            sourceLabel = obj.optString("sourceLabel", "Tasks"),
                            completed = false,
                            isTodayOrTimeless = true
                        )
                    )
                }
            } catch (_: Exception) {
            }
        }
        val activeList = if (hadExplicitJson) parsed else defaultTasks
        return TasksSnapshot(
            pendingCount = activeList.size,
            completedCount = totalCompletedCount,
            tasks = activeList,
            triggeredByTaskAlert = hasActiveTaskAlertOverride(nowMs)
        )
    }

    /**
     * Chamado quando o usuário toca diretamente na imagem do Canvas na Watch Face (Complication Tap Action).
     * - NÃO fica em definitivo: o próximo Canvas permanece fixo pelo Tempo Unificado configurado
     *   (getUnifiedCanvasHoldMinutes(), ex: 15m, 30m, 45m, 1h) e depois retorna automaticamente
     *   ao Canvas Padrão Principal selecionado nas configurações.
     * - Se o toque chegar de volta ao próprio Canvas Padrão Principal, limpa o override temporário.
     */
    fun cycleNextVisualCanvasOnTap(nowMs: Long, upcomingThree: List<CachedWearEvent>): String {
        val visualOrder = listOf(
            MODE_EVENTS,
            MODE_TASKS,
            MODE_WEATHER,
            MODE_SUN,
            MODE_STEPS,
            MODE_SLEEP
        )
        val currentVisual = resolveEffectiveContextMode(nowMs, upcomingThree)
        val currentIdx = visualOrder.indexOf(currentVisual).takeIf { it >= 0 } ?: 0
        val nextVisual = visualOrder[(currentIdx + 1) % visualOrder.size]
        val defaultPrimary = getAutoPrimaryContext()

        if (nextVisual == defaultPrimary) {
            clearTapOverride()
        } else {
            prefs.edit()
                .putString("tap_override_context_mode", nextVisual)
                .putLong("tap_override_timestamp_ms", nowMs)
                .commit()
        }
        return nextVisual
    }

    fun clearTapOverride() {
        prefs.edit()
            .remove("tap_override_context_mode")
            .putLong("tap_override_timestamp_ms", 0L)
            .commit()
    }

    fun getTapOverrideExpiryEpochMs(nowMs: Long): Long {
        val ts = prefs.getLong("tap_override_timestamp_ms", 0L)
        if (ts <= 0L) return Long.MAX_VALUE
        val holdMs = TimeUnit.MINUTES.toMillis(getUnifiedCanvasHoldMinutes().toLong())
        val expiry = ts + holdMs
        return if (expiry > nowMs) expiry else Long.MAX_VALUE
    }

    private fun getActiveTapOverrideMode(
        nowMs: Long,
        holdMinutes: Long = getUnifiedCanvasHoldMinutes().toLong()
    ): String? {
        val ts = prefs.getLong("tap_override_timestamp_ms", 0L)
        if (ts <= 0L) return null
        val elapsed = nowMs - ts
        if (elapsed !in 0..TimeUnit.MINUTES.toMillis(holdMinutes)) return null
        return prefs.getString("tap_override_context_mode", null)?.takeIf { it.isNotBlank() }
    }

    fun getLastNotifiedForcedContext(): String {
        return prefs.getString("last_notified_forced_context", "") ?: ""
    }

    fun getLastNotifiedForcedContextTimestampMs(): Long {
        return prefs.getLong("last_notified_forced_context_ts_ms", 0L)
    }

    fun recordNotifiedForcedContext(contextMode: String, nowMs: Long) {
        prefs.edit()
            .putString("last_notified_forced_context", contextMode)
            .putLong("last_notified_forced_context_ts_ms", nowMs)
            .apply()
    }

    /**
     * Avalia se há uma mudança de contexto de PRIORIDADE MÁXIMA ativa agora:
     * 1. Compromisso da Agenda faltando 15 minutos ou menos (<= 15m): o Canvas troca para Agenda
     *    e (se ativado pelo usuário) gera notificação no relógio.
     * 2. Lembrete / Tarefa ativa do Samsung Reminder ou Google Tasks: assume o Canvas pelo Tempo Fixo Unificado.
     * 3. Mudança de Clima/Chuva: assume o Canvas pelo Tempo Fixo Unificado.
     * 4. Nascer e Pôr do Sol: assume o Canvas na janela solar.
     * 5. Dormir ou Acordar: assume o Canvas com o Resumo de Sono (se houver dados de sono para hoje).
     */
    fun evaluatePriorityContextOverride(
        nowMs: Long,
        upcomingThree: List<CachedWearEvent>
    ): Pair<String, String>? {
        val unifiedHoldMin = getUnifiedCanvasHoldMinutes()
        val unifiedHoldMs = TimeUnit.MINUTES.toMillis(unifiedHoldMin.toLong())
        val nextEvent = upcomingThree.firstOrNull()

        // 1. Compromisso da Agenda faltando 15 minutos (<= 15m) — Troca para o Canvas de Agenda com Prioridade Máxima
        if (nextEvent != null) {
            val remMs = nextEvent.startEpochMs - nowMs
            val fifteenMinMs = TimeUnit.MINUTES.toMillis(15L)
            if (remMs in 1..fifteenMinMs) {
                return MODE_EVENTS to "Faltam ${nextEvent.formatRemaining(nowMs)} para ${nextEvent.title} (${nextEvent.formatStartHHMM()})"
            }
        }

        // 2. Tarefa / Lembrete acionado (Samsung Reminder ou Google Tasks) — Prioridade Máxima se recente
        val taskAlertTs = prefs.getLong("tasks_alert_timestamp_ms", 0L)
        if (isTasksSyncEnabled() && taskAlertTs > 0L && (nowMs - taskAlertTs) in 0..unifiedHoldMs) {
            val tasksSnap = getTasksSnapshot(nowMs)
            if (tasksSnap.pendingCount > 0) {
                val firstPending = tasksSnap.tasks.firstOrNull()
                val detail = if (firstPending != null) {
                    "${firstPending.sourceLabel}: ${firstPending.title} (${firstPending.dueHHMM})"
                } else {
                    "${tasksSnap.pendingCount} tarefa(s) pendente(s)"
                }
                return MODE_TASKS to detail
            }
        }

        // 3. Mudança de Clima (Temperatura / Chuva / Alerta de App) — Prioridade Máxima se recente
        val weatherAlertTs = prefs.getLong("weather_alert_timestamp_ms", 0L)
        if (weatherAlertTs > 0L && (nowMs - weatherAlertTs) in 0..unifiedHoldMs) {
            val snap = getHourlyWeatherSnapshot(nowMs)
            return MODE_WEATHER to "${snap.weatherConditionLabel} (${snap.locationName}) • ${snap.alertHeadline}"
        }

        // 4. Nascer e Pôr do Sol (dentro da janela unificada ao redor do evento solar)
        val solar = getSolarSnapshot(nowMs)
        val halfSunWindowMins = (unifiedHoldMin / 2).coerceAtLeast(5)
        val nearSunrise = abs(solar.currentMinutesOfDay - solar.sunriseMinutesOfDay) <= halfSunWindowMins
        val nearSunset = abs(solar.currentMinutesOfDay - solar.sunsetMinutesOfDay) <= halfSunWindowMins
        if (nearSunrise) {
            return MODE_SUN to "Nascer do Sol às ${solar.sunriseHHMM} (Exposição ${unifiedHoldMin}m)"
        }
        if (nearSunset) {
            return MODE_SUN to "Pôr do Sol às ${solar.sunsetHHMM} (Exposição ${unifiedHoldMin}m)"
        }

        // 5. Dormir ou Acordar (Resumo de Sono — somente se houver dados de sono confirmados para hoje)
        if (hasActiveSleepWakeExposure(nowMs, unifiedHoldMin)) {
            val sleep = getSleepSnapshot(nowMs)
            val h = sleep.durationMinutes / 60
            val m = sleep.durationMinutes % 60
            return MODE_SLEEP to "Sono ${h}h ${m}m (${sleep.sleepScore} pts) • ${sleep.bedtimeHHMM} → ${sleep.wakeHHMM}"
        }

        return null
    }

    fun resolveEffectiveContextMode(nowMs: Long, upcomingThree: List<CachedWearEvent>): String {
        val unifiedHoldMin = getUnifiedCanvasHoldMinutes().toLong()
        val tapOverride = getActiveTapOverrideMode(nowMs, unifiedHoldMin)
        val tapTs = prefs.getLong("tap_override_timestamp_ms", 0L)

        // Verifica se há mudança prioritária de contexto (Clima, Agenda, Tarefas, Sol, Sono)
        val priorityPair = evaluatePriorityContextOverride(nowMs, upcomingThree)
        if (priorityPair != null) {
            val (priorityMode, _) = priorityPair
            val weatherTs = prefs.getLong("weather_alert_timestamp_ms", 0L)
            val sleepTs = prefs.getLong("sleep_wake_timestamp_ms", 0L)
            val tasksTs = prefs.getLong("tasks_alert_timestamp_ms", 0L)
            val newestTriggerTs = maxOf(weatherTs, sleepTs, tasksTs)

            if (tapOverride == null || newestTriggerTs >= tapTs) {
                return priorityMode
            }
        }

        // Se o usuário tocou no Canvas na Watch Face dentro do tempo unificado configurado
        if (tapOverride != null) {
            return tapOverride
        }

        val primaryContext = getAutoPrimaryContext()
        if (primaryContext == MODE_EVENTS) {
            val allFuture = getAllUpcomingHorizonEvents(nowMs, 15)
            val hasEventToday = allFuture.any { isSameCalendarDay(nowMs, it.startEpochMs) }
            if (!hasEventToday &&
                isAutoFallbackAgendaToTasksEnabled() &&
                isTasksSyncEnabled() &&
                getTasksSnapshot(nowMs).pendingCount > 0
            ) {
                return MODE_TASKS
            }
            return MODE_EVENTS
        }
        return primaryContext
    }

    /**
     * Busca os próximos compromissos futuros (start_epoch_ms > nowMs) no cache SQLite.
     * Conforme o horário passa, compromissos cujo horário de início já passou deixam automaticamente
     * de ser retornados, fazendo a fila avançar dinamicamente no relógio sem depender de nova sincronização.
     */
    fun getAllUpcomingHorizonEvents(nowMs: Long, limit: Int = 20): List<CachedWearEvent> {
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
            limit.toString()
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

    /**
     * Regra Estrita de Separação por Dia no Canvas da Agenda:
     * - Enquanto houver compromissos para HOJE (isSameCalendarDay(nowMs, ev.startEpochMs)),
     *   o título é "Hoje" e SOMENTE os compromissos de hoje aparecem na lista (nunca mistura eventos de outros dias sob "Hoje").
     * - Somente quando NÃO houver mais nenhum compromisso para hoje, verifica se há compromissos
     *   para AMANHÃ (calendarDaysDiff == 1). Se houver, muda o título para "Amanhã" e lista apenas os de amanhã.
     * - Se não houver compromissos para hoje nem para amanhã, mantém "Hoje" com lista vazia ("Não há compromissos").
     */
    fun resolveDayHeaderAndEventsForWatch(nowMs: Long): Pair<String, List<CachedWearEvent>> {
        val allFuture = getAllUpcomingHorizonEvents(nowMs, 24)
        val todayEvents = allFuture.filter { isSameCalendarDay(nowMs, it.startEpochMs) }
        if (todayEvents.isNotEmpty()) {
            return "Hoje" to todayEvents.take(3)
        }
        val tomorrowEvents = allFuture.filter { calendarDaysDiff(nowMs, it.startEpochMs) == 1 }
        if (tomorrowEvents.isNotEmpty()) {
            return "Amanhã" to tomorrowEvents.take(3)
        }
        return "Hoje" to emptyList()
    }

    fun getNextThreeUpcomingEvents(nowMs: Long): List<CachedWearEvent> {
        val (_, dayFiltered) = resolveDayHeaderAndEventsForWatch(nowMs)
        return dayFiltered
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
        const val MODE_DYNAMIC = "DYNAMIC"
        const val MODE_EVENTS = "EVENTS"
        const val MODE_TASKS = "TASKS"
        const val MODE_WEATHER = "WEATHER"
        const val MODE_STEPS = "STEPS"
        const val MODE_SUN = "SUN"
        const val MODE_SLEEP = "SLEEP"

        const val DEFAULT_WEATHER_TRIGGER_PACKAGES_CSV =
            "com.google.android.googlequicksearchbox,com.google.android.apps.weather,com.sec.android.daemonapp,com.samsung.android.watch.weather,br.com.climatempo.meteorologia,com.accuweather.android,com.weather.Weather"

        private val OFFICIAL_IBGE_UFS = setOf(
            "AC", "AL", "AP", "AM", "BA", "CE", "DF", "ES", "GO",
            "MA", "MT", "MS", "MG", "PA", "PB", "PR", "PE", "PI",
            "RJ", "RN", "RS", "RO", "RR", "SC", "SP", "SE", "TO"
        )

        // Ordenado dos nomes compostos mais longos para os mais curtos para evitar colisões
        // (ex: "mato grosso do sul" -> MS antes de "mato grosso" -> MT; "parana" -> PR e "paraiba" -> PB antes de "para" -> PA)
        private val ORDERED_BRAZIL_STATE_UF_LIST = listOf(
            "mato grosso do sul" to "MS",
            "rio grande do norte" to "RN",
            "rio grande do sul" to "RS",
            "distrito federal" to "DF",
            "espirito santo" to "ES",
            "santa catarina" to "SC",
            "rio de janeiro" to "RJ",
            "minas gerais" to "MG",
            "mato grosso" to "MT",
            "pernambuco" to "PE",
            "sao paulo" to "SP",
            "tocantins" to "TO",
            "maranhao" to "MA",
            "amazonas" to "AM",
            "rondonia" to "RO",
            "paraiba" to "PB",
            "alagoas" to "AL",
            "roraima" to "RR",
            "sergipe" to "SE",
            "parana" to "PR",
            "ceara" to "CE",
            "goias" to "GO",
            "piaui" to "PI",
            "bahia" to "BA",
            "amapa" to "AP",
            "acre" to "AC",
            "para" to "PA"
        )

        private val KNOWN_CITY_OFFICIAL_UF_MAP = mapOf(
            "sao paulo" to "SP",
            "sao bernardo do campo" to "SP",
            "santo andre" to "SP",
            "sao caetano do sul" to "SP",
            "campinas" to "SP",
            "guarulhos" to "SP",
            "santos" to "SP",
            "ribeirao preto" to "SP",
            "sorocaba" to "SP",
            "sao jose dos campos" to "SP",
            "pindamonhangaba" to "SP",
            "pinheiros" to "SP",
            "sao luis" to "MA",
            "barreirinhas" to "MA",
            "imperatriz" to "MA",
            "curitiba" to "PR",
            "londrina" to "PR",
            "maringa" to "PR",
            "foz do iguacu" to "PR",
            "cascavel" to "PR",
            "ponta grossa" to "PR",
            "rio de janeiro" to "RJ",
            "niteroi" to "RJ",
            "belo horizonte" to "MG",
            "uberlandia" to "MG",
            "governador valadares" to "MG",
            "juiz de fora" to "MG",
            "porto alegre" to "RS",
            "caxias do sul" to "RS",
            "florianopolis" to "SC",
            "joinville" to "SC",
            "blumenau" to "SC",
            "salvador" to "BA",
            "recife" to "PE",
            "fortaleza" to "CE",
            "brasilia" to "DF",
            "goiania" to "GO",
            "belem" to "PA",
            "manaus" to "AM",
            "vitoria" to "ES",
            "natal" to "RN",
            "joao pessoa" to "PB",
            "maceio" to "AL",
            "aracaju" to "SE",
            "teresina" to "PI",
            "campo grande" to "MS",
            "cuiaba" to "MT",
            "palmas" to "TO",
            "porto velho" to "RO",
            "rio branco" to "AC",
            "macapa" to "AP",
            "boa vista" to "RR"
        )

        private fun stripAccents(input: String): String {
            return java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        }

        fun isSameCalendarDay(epochA: Long, epochB: Long): Boolean {
            val calA = Calendar.getInstance().apply { timeInMillis = epochA }
            val calB = Calendar.getInstance().apply { timeInMillis = epochB }
            return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
                calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
        }

        fun calendarDaysDiff(fromEpochMs: Long, toEpochMs: Long): Int {
            val startFrom = Calendar.getInstance().apply {
                timeInMillis = fromEpochMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val startTo = Calendar.getInstance().apply {
                timeInMillis = toEpochMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            return Math.round((startTo - startFrom) / 86_400_000.0).toInt()
        }

        /**
         * Resolve exclusivamente a sigla UF oficial de 2 letras do IBGE para cada estado brasileiro
         * (ex: São Paulo -> SP, Maranhão -> MA, Paraná -> PR, Pará -> PA, Mato Grosso do Sul -> MS),
         * sem nunca usar abreviações lógicas como "SA" ou "PA" para Paraná.
         */
        fun resolveOfficialBrazilStateUf(rawState: String, rawCity: String = ""): String {
            val cleanState = stripAccents(rawState)
                .lowercase(Locale.ROOT)
                .replace(Regex("\\b(state of|estado d[eao]|provincia d[eao]|brasil|brazil|br)\\b"), " ")
                .replace(Regex("[^a-z\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleanState.length == 2) {
                val upper = cleanState.uppercase(Locale.ROOT)
                if (upper in OFFICIAL_IBGE_UFS) return upper
            }

            if (cleanState.isNotBlank()) {
                for ((stateName, uf) in ORDERED_BRAZIL_STATE_UF_LIST) {
                    if (cleanState == stateName || Regex("\\b${Regex.escape(stateName)}\\b").containsMatchIn(cleanState)) {
                        return uf
                    }
                }
                val tokens = cleanState.split(" ").map { it.uppercase(Locale.ROOT) }
                val directToken = tokens.firstOrNull { it in OFFICIAL_IBGE_UFS }
                if (directToken != null) return directToken
            }

            val cleanCity = stripAccents(rawCity)
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (cleanCity.isNotBlank()) {
                KNOWN_CITY_OFFICIAL_UF_MAP[cleanCity]?.let { return it }
                for ((stateName, uf) in ORDERED_BRAZIL_STATE_UF_LIST) {
                    if (cleanCity == stateName) return uf
                }
            }

            return ""
        }

        /**
         * Formata o nome do local para o subtítulo do Canvas de Clima:
         * 1. Converte o nome do estado para a sigla UF OFICIAL de 2 letras do IBGE
         *    (ex: "São Paulo" -> "SP", "Maranhão" -> "MA", "Paraná" -> "PR").
         * 2. Se o nome da cidade for extenso (> 12 caracteres) e ocupar mais da metade do quadro,
         *    mantém a primeira palavra inteira e metade da segunda com reticências (ex: "São Bernardo do Campo" -> "São Bern..."),
         *    ou abrevia palavras únicas muito longas (ex: "Pindamonhangaba" -> "Pindamonh...").
         */
        fun formatCompactWeatherLocation(rawCityOrCombined: String, rawState: String = ""): String {
            val cleaned = rawCityOrCombined.trim()
            if (cleaned.isBlank()) return "São Paulo, SP"

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
                    val secondIsConnector = words[1].lowercase(Locale.ROOT) in setOf("de", "da", "do", "das", "dos")
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

        @Volatile
        private var INSTANCE: WatchAutonomousRepository? = null

        fun getInstance(context: Context): WatchAutonomousRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WatchAutonomousRepository(context).also { INSTANCE = it }
            }
        }
    }
}
