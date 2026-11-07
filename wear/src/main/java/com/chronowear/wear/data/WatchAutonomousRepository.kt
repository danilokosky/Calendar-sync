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
    val stagesMinutes: List<Int>
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
        sleepWakeTimestampMs: Long? = null
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
        if (watchStandaloneWeatherEnabled != null) {
            editor.putBoolean("watch_standalone_weather_enabled", watchStandaloneWeatherEnabled)
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
        if (!weatherLocationName.isNullOrBlank()) {
            editor.putString("weather_location_name", weatherLocationName)
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
        if (watchStandaloneSolarEnabled != null) {
            editor.putBoolean("watch_standalone_solar_enabled", watchStandaloneSolarEnabled)
        }
        if (sleepSyncEnabled != null) {
            editor.putBoolean("sleep_sync_enabled", sleepSyncEnabled)
        }
        if (sleepDurationMinutes != null && sleepDurationMinutes > 0) {
            editor.putInt("sleep_duration_minutes", sleepDurationMinutes.coerceIn(60, 960))
        }
        if (sleepScore != null && sleepScore > 0) {
            editor.putInt("sleep_score", sleepScore.coerceIn(1, 100))
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
        if (sleepWakeTimestampMs != null && sleepWakeTimestampMs > 0L) {
            val currentWakeTs = prefs.getLong("sleep_wake_timestamp_ms", 0L)
            if (sleepWakeTimestampMs > currentWakeTs) {
                editor.putLong("sleep_wake_timestamp_ms", sleepWakeTimestampMs)
                // Novo registro de despertar/sono tem prioridade máxima: cancela toque manual
                editor.remove("tap_override_context_mode")
                editor.putLong("tap_override_timestamp_ms", 0L)
            }
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
        val renderedMs = prefs.getLong("phone_rendered_card_ms", 0L)
        if (renderedMs <= 0L || (nowMs - renderedMs) > maxAgeMs) return null
        return prefs.getString("phone_rendered_card_png_b64", null)
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

    fun getWeatherLocationName(): String {
        return prefs.getString("weather_location_name", "São Paulo, SP") ?: "São Paulo, SP"
    }

    fun setWeatherLocationName(locationName: String) {
        if (locationName.isNotBlank()) {
            prefs.edit().putString("weather_location_name", locationName.trim()).apply()
        }
    }

    fun getAutoAgendaExposureMinutes(): Int = 15

    fun getAutoWeatherExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun getAutoSunExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun getAutoSleepExposureMinutes(): Int = getUnifiedCanvasHoldMinutes()

    fun isSleepSyncEnabled(): Boolean {
        return prefs.getBoolean("sleep_sync_enabled", true)
    }

    fun getSleepSnapshot(): SleepSnapshot {
        val dur = prefs.getInt("sleep_duration_minutes", 462)
        val score = prefs.getInt("sleep_score", 86)
        val bed = prefs.getString("sleep_bedtime_hhmm", "23:18") ?: "23:18"
        val wake = prefs.getString("sleep_wake_hhmm", "07:00") ?: "07:00"
        val stagesStr = prefs.getString("sleep_stages_csv", "18,95,244,105") ?: "18,95,244,105"
        val stages = stagesStr.split(",").mapNotNull { it.trim().toIntOrNull() }
            .ifEmpty { listOf(18, 95, 244, 105) }
        return SleepSnapshot(
            durationMinutes = dur,
            sleepScore = score,
            bedtimeHHMM = bed,
            wakeHHMM = wake,
            stagesMinutes = stages
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
            .putLong("sleep_wake_timestamp_ms", nowMs)
            // Prioridade máxima: cancela toque manual no Canvas
            .remove("tap_override_context_mode")
            .putLong("tap_override_timestamp_ms", 0L)
            .apply()
    }

    fun hasActiveSleepWakeExposure(nowMs: Long, exposureMinutes: Int = getUnifiedCanvasHoldMinutes()): Boolean {
        if (!isSleepSyncEnabled()) return false
        val wakeTs = prefs.getLong("sleep_wake_timestamp_ms", 0L)
        val windowMs = TimeUnit.MINUTES.toMillis(exposureMinutes.toLong())
        if (wakeTs > 0L && (nowMs - wakeTs) in 0..windowMs) {
            return true
        }
        val sleep = getSleepSnapshot()
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
        return prefs.getBoolean("watch_standalone_weather_enabled", false)
    }

    fun setWatchStandaloneWeatherEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("watch_standalone_weather_enabled", enabled).commit()
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
        return prefs.getBoolean("watch_standalone_solar_enabled", false)
    }

    fun setWatchStandaloneSolarEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("watch_standalone_solar_enabled", enabled).commit()
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

        if (!locationName.isNullOrBlank()) {
            editor.putString("weather_location_name", locationName.trim())
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
        val locationName = prefs.getString("weather_location_name", "São Paulo, SP") ?: "São Paulo, SP"
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
     * 2. Mudança de Clima/Chuva: assume o Canvas pelo Tempo Fixo Unificado.
     * 3. Nascer e Pôr do Sol: assume o Canvas na janela solar.
     * 4. Dormir ou Acordar: assume o Canvas com o Resumo de Sono.
     * Retorna Pair(modoForcado, motivoParaNotificacao) ou null se nenhum gatilho prioritário estiver ativo.
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

        // 2. Mudança de Clima (Temperatura / Chuva / Alerta de App) — Prioridade Máxima se recente
        val weatherAlertTs = prefs.getLong("weather_alert_timestamp_ms", 0L)
        if (weatherAlertTs > 0L && (nowMs - weatherAlertTs) in 0..unifiedHoldMs) {
            val snap = getHourlyWeatherSnapshot(nowMs)
            return MODE_WEATHER to "${snap.weatherConditionLabel} (${snap.locationName}) • ${snap.alertHeadline}"
        }

        // 3. Nascer e Pôr do Sol (dentro da janela unificada ao redor do evento solar)
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

        // 4. Dormir ou Acordar (Resumo de Sono)
        if (hasActiveSleepWakeExposure(nowMs, unifiedHoldMin)) {
            val sleep = getSleepSnapshot()
            val h = sleep.durationMinutes / 60
            val m = sleep.durationMinutes % 60
            return MODE_SLEEP to "Sono ${h}h ${m}m (${sleep.sleepScore} pts) • ${sleep.bedtimeHHMM} → ${sleep.wakeHHMM}"
        }

        return null
    }

    /**
     * Resolve qual contexto deve ser desenhado no Canvas agora:
     * 1. Mudanças Reais de Contexto (Clima, Agenda, Pôr/Nascer do Sol, Acordar/Dormir) têm
     *    PRIORIDADE MÁXIMA quando ocorrem: se não houver toque manual mais recente que o gatilho,
     *    assumem o Canvas pelo Tempo Unificado (getUnifiedCanvasHoldMinutes()) e depois retornam
     *    ao Canvas Padrão Principal.
     * 2. Toque Manual no Canvas (Tap-to-Cycle):
     *    Se o usuário tocou no Canvas na Watch Face para inspecionar o próximo Canvas disponível,
     *    ele permanece fixo pelo Tempo Unificado (getUnifiedCanvasHoldMinutes()) e depois retorna
     *    automaticamente à aparência padrão (Canvas Padrão Principal).
     * 3. Canvas Padrão Principal:
     *    Retorna getAutoPrimaryContext() selecionado nas configurações.
     */
    fun resolveEffectiveContextMode(nowMs: Long, upcomingThree: List<CachedWearEvent>): String {
        val unifiedHoldMin = getUnifiedCanvasHoldMinutes().toLong()
        val tapOverride = getActiveTapOverrideMode(nowMs, unifiedHoldMin)
        val tapTs = prefs.getLong("tap_override_timestamp_ms", 0L)

        // Verifica se há mudança prioritária de contexto (Clima, Agenda, Sol, Sono)
        val priorityPair = evaluatePriorityContextOverride(nowMs, upcomingThree)
        if (priorityPair != null) {
            val (priorityMode, _) = priorityPair
            val weatherTs = prefs.getLong("weather_alert_timestamp_ms", 0L)
            val sleepTs = prefs.getLong("sleep_wake_timestamp_ms", 0L)
            val newestTriggerTs = maxOf(weatherTs, sleepTs)

            // Se não há toque manual ativo OU se a mudança de contexto aconteceu após o toque,
            // a mudança de contexto tem prioridade máxima!
            if (tapOverride == null || newestTriggerTs >= tapTs) {
                return priorityMode
            }
        }

        // Se o usuário tocou no Canvas na Watch Face dentro do tempo unificado configurado
        if (tapOverride != null) {
            return tapOverride
        }

        // Caso contrário, retorna ao Canvas Padrão Principal configurado pelo usuário
        val primaryContext = getAutoPrimaryContext()
        return if (primaryContext == MODE_EVENTS && upcomingThree.isEmpty()) {
            MODE_WEATHER
        } else {
            primaryContext
        }
    }

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
        const val MODE_DYNAMIC = "DYNAMIC"
        const val MODE_EVENTS = "EVENTS"
        const val MODE_WEATHER = "WEATHER"
        const val MODE_STEPS = "STEPS"
        const val MODE_SUN = "SUN"
        const val MODE_SLEEP = "SLEEP"

        const val DEFAULT_WEATHER_TRIGGER_PACKAGES_CSV =
            "com.google.android.googlequicksearchbox,com.google.android.apps.weather,com.sec.android.daemonapp,com.samsung.android.watch.weather,br.com.climatempo.meteorologia,com.accuweather.android,com.weather.Weather"

        @Volatile
        private var INSTANCE: WatchAutonomousRepository? = null

        fun getInstance(context: Context): WatchAutonomousRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WatchAutonomousRepository(context).also { INSTANCE = it }
            }
        }
    }
}
