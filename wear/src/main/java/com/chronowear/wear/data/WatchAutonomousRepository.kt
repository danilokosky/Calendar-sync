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

data class HourlyWeatherSnapshot(
    val alertHeadline: String,
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
        highFreq15MinRule: Boolean? = null,
        updateOnScreenWake: Boolean? = null
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
        if (highFreq15MinRule != null) {
            editor.putBoolean("high_freq_15min_rule", highFreq15MinRule)
        }
        if (updateOnScreenWake != null) {
            editor.putBoolean("update_on_screen_wake", updateOnScreenWake)
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
     * Modo da Janela de Contexto escolhido no App (Smartphone ou Relógio):
     * - "DYNAMIC": Muda sozinho (Agenda por padrão, Clima quando chega notificação de mudança climática, Turbo Agenda nos 15m finais)
     * - "EVENTS": Sempre mostra os Próximos 3 Eventos + Barra
     * - "WEATHER": Sempre mostra o Gráfico de Mudança de Temperatura nas Próximas Horas
     * - "STEPS": Sempre mostra o Gráfico de Passos Semanais & Meta Diária
     */
    fun getContextWindowMode(): String {
        return prefs.getString("context_window_mode", MODE_DYNAMIC) ?: MODE_DYNAMIC
    }

    fun setContextWindowMode(mode: String) {
        prefs.edit().putString("context_window_mode", mode).apply()
    }

    /**
     * Regra de atualização de alta frequência: atualiza o Canvas a cada 1 minuto
     * quando faltar 15 minutos ou menos para o próximo evento iniciar.
     */
    fun isHighFreq15MinRuleEnabled(): Boolean {
        return prefs.getBoolean("high_freq_15min_rule", true)
    }

    fun setHighFreq15MinRuleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("high_freq_15min_rule", enabled).apply()
    }

    /**
     * Atualiza o Canvas sempre que o usuário acorda a tela do relógio (ACTION_SCREEN_ON).
     */
    fun isUpdateOnScreenWakeEnabled(): Boolean {
        return prefs.getBoolean("update_on_screen_wake", true)
    }

    fun setUpdateOnScreenWakeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("update_on_screen_wake", enabled).apply()
    }

    /**
     * Registra uma mudança climática / alerta meteorológico interceptado diretamente
     * nas notificações do relógio pelo `WearContextNotificationListenerService`.
     */
    fun recordWatchWeatherNotificationAlert(
        headline: String,
        tempsCsv: String = "28,26,22,19,18",
        hoursCsv: String = "Agora,+1h,+2h,+4h,+6h",
        rainProbPct: Int = 75,
        nowMs: Long = System.currentTimeMillis()
    ) {
        prefs.edit()
            .putString("weather_alert_headline", headline)
            .putString("weather_temps_csv", tempsCsv)
            .putString("weather_hours_csv", hoursCsv)
            .putInt("weather_rain_prob", rainProbPct.coerceIn(0, 100))
            .putLong("weather_alert_timestamp_ms", nowMs)
            .apply()
    }

    fun hasActiveWeatherNotificationOverride(nowMs: Long, windowMinutes: Long = 25L): Boolean {
        val alertTs = prefs.getLong("weather_alert_timestamp_ms", 0L)
        if (alertTs <= 0L) return false
        return (nowMs - alertTs) in 0..TimeUnit.MINUTES.toMillis(windowMinutes)
    }

    fun getHourlyWeatherSnapshot(nowMs: Long): HourlyWeatherSnapshot {
        val headline = prefs.getString("weather_alert_headline", "Queda térmica • Chuva em 40m")
            ?: "Queda térmica • Chuva em 40m"
        val tempsStr = prefs.getString("weather_temps_csv", "28,26,22,19,18") ?: "28,26,22,19,18"
        val hoursStr = prefs.getString("weather_hours_csv", "Agora,16h,18h,20h,22h") ?: "Agora,16h,18h,20h,22h"
        val rainProb = prefs.getInt("weather_rain_prob", 75)

        val temps = tempsStr.split(",").mapNotNull { it.trim().toIntOrNull() }
            .ifEmpty { listOf(28, 26, 22, 19, 18) }
        val labels = hoursStr.split(",").map { it.trim() }
            .ifEmpty { listOf("Agora", "16h", "18h", "20h", "22h") }

        return HourlyWeatherSnapshot(
            alertHeadline = headline,
            hourlyLabels = labels,
            hourlyTempsC = temps,
            rainProbabilityPct = rainProb,
            triggeredByWatchNotification = hasActiveWeatherNotificationOverride(nowMs)
        )
    }

    fun getWeeklyStepsSnapshot(): WeeklyStepsSnapshot {
        val today = prefs.getInt("steps_today", 7840)
        val goal = prefs.getInt("steps_goal", 10000)
        val csv = prefs.getString("steps_week_csv", "6400,8200,7100,9500,7840,5300,4900")
            ?: "6400,8200,7100,9500,7840,5300,4900"
        val stepsList = csv.split(",").mapNotNull { it.trim().toIntOrNull() }
            .ifEmpty { listOf(6400, 8200, 7100, 9500, 7840, 5300, 4900) }
        return WeeklyStepsSnapshot(
            todaySteps = today,
            dailyGoal = goal,
            weekDaysLabels = listOf("S", "T", "Q", "Q", "S", "S", "D"),
            weekDaysSteps = stepsList
        )
    }

    /**
     * Resolve qual contexto deve ser desenhado no Canvas agora:
     * - Se o usuário fixou EVENTS, WEATHER ou STEPS, respeita a escolha.
     * - Se escolheu DYNAMIC:
     *   1. Faltando <= 15 min para um compromisso -> prioriza EVENTS (com atualização a cada 1 min!)
     *   2. Se o relógio recebeu uma notificação recente de Mudança Climática -> exibe WEATHER temporariamente!
     *   3. Caso contrário -> exibe EVENTS (ou WEATHER se a agenda estiver vazia).
     */
    fun resolveEffectiveContextMode(nowMs: Long, upcomingThree: List<CachedWearEvent>): String {
        val configured = getContextWindowMode()
        if (configured != MODE_DYNAMIC) return configured

        val nextEvent = upcomingThree.firstOrNull()
        val fifteenMinMs = TimeUnit.MINUTES.toMillis(15L)
        if (nextEvent != null && (nextEvent.startEpochMs - nowMs) in 1..fifteenMinMs) {
            return MODE_EVENTS
        }
        if (hasActiveWeatherNotificationOverride(nowMs)) {
            return MODE_WEATHER
        }
        return if (upcomingThree.isNotEmpty()) MODE_EVENTS else MODE_WEATHER
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
        const val MODE_DYNAMIC = "DYNAMIC"
        const val MODE_EVENTS = "EVENTS"
        const val MODE_WEATHER = "WEATHER"
        const val MODE_STEPS = "STEPS"

        @Volatile
        private var INSTANCE: WatchAutonomousRepository? = null

        fun getInstance(context: Context): WatchAutonomousRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WatchAutonomousRepository(context).also { INSTANCE = it }
            }
        }
    }
}
