package com.chronowear.mobile.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class WearConnectionState {
    DISCONNECTED,
    WEARABLE_CONNECTED_APP_MISSING,
    CONNECTED_WITH_APP_INSTALLED
}

data class WearConnectionReport(
    val state: WearConnectionState,
    val wearableDisplayNames: List<String>,
    val installedAppNodeNames: List<String>
)

data class LocalCalendarItem(
    val id: String,
    val displayName: String,
    val accountName: String,
    val ownerAccount: String,
    val colorInt: Int,
    val upcomingEventsCount: Int
)

data class LocalCalendarEvent(
    val id: String,
    val calendarId: String,
    val calendarName: String,
    val title: String,
    val location: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val allDay: Boolean,
    val colorInt: Int
)

object CalendarSyncEngine {

    const val DATA_LAYER_PATH = "/chronowear/calendar_horizon_payload"
    const val MESSAGE_FORCE_SYNC_PATH = "/chronowear/force_sync_payload"
    const val MESSAGE_PING_WATCH_APP_PATH = "/chronowear/ping_watch_app"
    const val CAPABILITY_WATCH_APP_INSTALLED = "chronowear_wear_app_installed"

    fun hasCalendarPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Verifica se há um relógio Wear OS conectado ao smartphone E se o aplicativo ChronoWear
     * está efetivamente instalado no relógio (via CapabilityClient + handshake ping/pong).
     */
    suspend fun checkWearConnectionAndAppStatus(context: Context): WearConnectionReport {
        val prefs = SyncPreferences(context)
        val connectedNodes = try {
            Wearable.getNodeClient(context).connectedNodes.await()
        } catch (_: Exception) {
            emptyList()
        }

        if (connectedNodes.isEmpty()) {
            return WearConnectionReport(
                state = WearConnectionState.DISCONNECTED,
                wearableDisplayNames = emptyList(),
                installedAppNodeNames = emptyList()
            )
        }

        val capableNodeIds = try {
            val capInfo = Wearable.getCapabilityClient(context)
                .getCapability(CAPABILITY_WATCH_APP_INSTALLED, CapabilityClient.FILTER_REACHABLE)
                .await()
            capInfo.nodes.map { it.id }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        // Dispara ping leve para cada nó conectado para confirmar presença do app no relógio
        for (node in connectedNodes) {
            try {
                Wearable.getMessageClient(context).sendMessage(
                    node.id,
                    MESSAGE_PING_WATCH_APP_PATH,
                    ByteArray(0)
                ).await()
            } catch (_: Exception) {
            }
        }

        val installedNodes = connectedNodes.filter { node ->
            capableNodeIds.contains(node.id) || prefs.isWatchAppVerifiedOnNode(node.id)
        }

        return if (installedNodes.isNotEmpty()) {
            WearConnectionReport(
                state = WearConnectionState.CONNECTED_WITH_APP_INSTALLED,
                wearableDisplayNames = connectedNodes.map { it.displayName },
                installedAppNodeNames = installedNodes.map { it.displayName }
            )
        } else {
            WearConnectionReport(
                state = WearConnectionState.WEARABLE_CONNECTED_APP_MISSING,
                wearableDisplayNames = connectedNodes.map { it.displayName },
                installedAppNodeNames = emptyList()
            )
        }
    }

    fun loadDeviceCalendars(context: Context): List<LocalCalendarItem> {
        if (!hasCalendarPermission(context)) return emptyList()

        val prefs = SyncPreferences(context)
        val result = mutableListOf<LocalCalendarItem>()
        val nowMs = System.currentTimeMillis()
        val endMs = nowMs + TimeUnit.DAYS.toMillis(prefs.getHorizonDays().toLong())

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_COLOR
        )

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(0).toString()
                val name = cursor.getString(1) ?: "Calendário"
                val account = cursor.getString(2) ?: "Conta Local"
                val owner = cursor.getString(3) ?: account
                val color = if (!cursor.isNull(4)) cursor.getInt(4) else 0xFFF59E0B.toInt()
                val count = countEventsForCalendar(context, calId, nowMs, endMs)
                result.add(LocalCalendarItem(calId, name, account, owner, color, count))
            }
        }

        return result
    }

    private fun countEventsForCalendar(
        context: Context,
        calendarId: String,
        startMs: Long,
        endMs: Long
    ): Int {
        var count = 0
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)

        val projection = arrayOf(
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.END,
            CalendarContract.Instances.STATUS
        )

        context.contentResolver.query(
            builder.build(),
            projection,
            "${CalendarContract.Instances.CALENDAR_ID} = ? AND ${CalendarContract.Instances.STATUS} != ?",
            arrayOf(calendarId, CalendarContract.Instances.STATUS_CANCELED.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val evEnd = cursor.getLong(1)
                if (evEnd > startMs) count++
            }
        }
        return count
    }

    fun queryHorizonEvents(context: Context): List<LocalCalendarEvent> {
        if (!hasCalendarPermission(context)) return emptyList()

        val prefs = SyncPreferences(context)
        val selectedIds = prefs.getSelectedCalendarIds()
        if (selectedIds.isEmpty()) return emptyList()

        val calendars = loadDeviceCalendars(context)
        val calMap = calendars.associateBy { it.id }

        val nowMs = System.currentTimeMillis()
        val endMs = nowMs + TimeUnit.DAYS.toMillis(prefs.getHorizonDays().toLong())

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, nowMs)
        ContentUris.appendId(builder, endMs)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS
        )

        val events = mutableListOf<LocalCalendarEvent>()

        context.contentResolver.query(
            builder.build(),
            projection,
            "${CalendarContract.Instances.STATUS} != ?",
            arrayOf(CalendarContract.Instances.STATUS_CANCELED.toString()),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(1).toString()
                if (!selectedIds.contains(calId)) continue

                val beginMs = cursor.getLong(4)
                val endEventMs = cursor.getLong(5)
                if (endEventMs <= nowMs) continue

                val allDay = cursor.getInt(6) == 1
                val calMeta = calMap[calId]
                events.add(
                    LocalCalendarEvent(
                        id = cursor.getLong(0).toString(),
                        calendarId = calId,
                        calendarName = calMeta?.displayName ?: "Calendário",
                        title = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: "Compromisso sem título",
                        location = cursor.getString(3) ?: "",
                        startEpochMs = beginMs,
                        endEpochMs = endEventMs,
                        allDay = allDay,
                        colorInt = calMeta?.colorInt ?: 0xFFF59E0B.toInt()
                    )
                )
            }
        }

        return events.sortedBy { it.startEpochMs }
    }

    /**
     * Busca as previsões horárias reais pelo Smartphone (via API gratuita Open-Meteo usando a
     * última localização aproximada do telefone), detecta automaticamente se houve mudança
     * relevante de temperatura/chuva nas próximas horas (`savePhoneFetchedWeatherAndDetectShift`)
     * e salva no SyncPreferences para notificar o relógio e alternar dinamicamente o Canvas.
     */
    fun fetchPhoneHourlyWeatherIfNeeded(context: Context, forceRefresh: Boolean = false): Boolean {
        val prefs = SyncPreferences(context)
        if (!forceRefresh && !prefs.isPhoneWeatherAutoCheckEnabled()) {
            return false
        }
        val nowMs = System.currentTimeMillis()
        val lastFetch = prefs.getPhoneWeatherLastFetchMs()
        val checkIntervalMin = prefs.getPhoneWeatherCheckIntervalMinutes()
        if (!forceRefresh && lastFetch > 0L && (nowMs - lastFetch) < TimeUnit.MINUTES.toMillis(checkIntervalMin.toLong())) {
            return false
        }

        var shiftDetected = false
        try {
            var lat = -23.5505
            var lon = -46.6333
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
                val loc = lm?.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER)
                    ?: lm?.getLastKnownLocation(android.location.LocationManager.PASSIVE_PROVIDER)
                if (loc != null) {
                    lat = loc.latitude
                    lon = loc.longitude
                }
            }

            val urlStr =
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&hourly=temperature_2m,precipitation_probability&forecast_days=2&timezone=auto"
            val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val hourly = root.optJSONObject("hourly")
                val timesArr = hourly?.optJSONArray("time")
                val tempsArr = hourly?.optJSONArray("temperature_2m")
                val rainArr = hourly?.optJSONArray("precipitation_probability")

                if (timesArr != null && tempsArr != null && timesArr.length() >= 24) {
                    val cal = java.util.Calendar.getInstance()
                    val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                    val offsets = listOf(0, 2, 4, 6, 8)
                    val pickedTemps = mutableListOf<Int>()
                    val pickedLabels = mutableListOf<String>()
                    var maxRain = 0

                    for ((idx, offset) in offsets.withIndex()) {
                        val arrayIdx = (currentHour + offset).coerceAtMost(timesArr.length() - 1)
                        val tempVal = Math.round(tempsArr.optDouble(arrayIdx, 25.0)).toInt()
                        val rainVal = rainArr?.optInt(arrayIdx, 0) ?: 0
                        if (rainVal > maxRain) maxRain = rainVal
                        pickedTemps.add(tempVal)
                        if (idx == 0) {
                            pickedLabels.add("Agora")
                        } else {
                            val h = (currentHour + offset) % 24
                            pickedLabels.add(String.format(Locale.getDefault(), "%02dh", h))
                        }
                    }

                    shiftDetected = prefs.savePhoneFetchedWeatherAndDetectShift(
                        pickedTemps = pickedTemps,
                        pickedLabels = pickedLabels,
                        rainProbPct = maxRain,
                        fetchTimestampMs = nowMs
                    )
                }
            }
            conn.disconnect()
        } catch (_: Exception) {
        }
        return shiftDetected
    }

    suspend fun performFullSyncToWatch(
        context: Context,
        triggerSource: String = "Sincronização Manual"
    ): Int {
        val prefs = SyncPreferences(context)
        val nowMs = System.currentTimeMillis()

        // Atualiza as previsões horárias pelo telefone e detecta mudanças de temperatura
        fetchPhoneHourlyWeatherIfNeeded(context, forceRefresh = false)

        if (!hasCalendarPermission(context)) {
            prefs.recordLastSync(
                nowMs,
                0,
                0,
                triggerSource,
                "Aguardando permissão de leitura do calendário pelo usuário"
            )
            return 0
        }

        val selectedCalendarsCount = prefs.getSelectedCalendarIds().size
        if (selectedCalendarsCount == 0) {
            prefs.recordLastSync(
                nowMs,
                0,
                0,
                triggerSource,
                "Nenhum calendário selecionado em Synced calendars"
            )
            context.sendBroadcast(
                Intent(SyncPreferences.ACTION_SYNC_COMPLETED).setPackage(context.packageName)
            )
            return 0
        }

        val events = queryHorizonEvents(context)
        val horizonDays = prefs.getHorizonDays()
        val progressWindowMinutes = prefs.getProgressWindowMinutes()

        val jsonArray = JSONArray()
        events.forEach { ev ->
            val obj = JSONObject().apply {
                put("id", ev.id)
                put("calendarId", ev.calendarId)
                put("calendarName", ev.calendarName)
                put("title", ev.title)
                put("location", ev.location)
                put("startEpochMs", ev.startEpochMs)
                put("endEpochMs", ev.endEpochMs)
                put("colorInt", ev.colorInt)
            }
            jsonArray.put(obj)
        }

        val preRenderedCardPngB64 = try {
            renderAgendaCardPngBase64OnPhone(events, nowMs, progressWindowMinutes)
        } catch (_: Exception) {
            ""
        }

        val contextMode = prefs.getContextWindowMode()
        val highFreq15Min = prefs.isHighFreq15MinRuleEnabled()
        val updateOnWake = prefs.isUpdateOnScreenWakeEnabled()

        val weatherTempsCsv = prefs.getPhoneWeatherTempsCsv()
        val weatherHoursCsv = prefs.getPhoneWeatherHoursCsv()
        val weatherRainProb = prefs.getPhoneWeatherRainProbPct()
        val weatherAlertSource = prefs.getWeatherAlertSource()
        val weatherAlertHeadline = prefs.getWeatherAlertHeadline()
        val weatherAlertTsMs = prefs.getWeatherAlertTimestampMs()
        val watchStandaloneWeatherEnabled = prefs.isWatchStandaloneWeatherEnabled()
        val watchStandaloneWeatherIntervalMin = prefs.getWatchStandaloneWeatherIntervalMinutes()
        val selectedWeatherTriggerPackagesCsv = prefs.getSelectedWeatherTriggerPackages().joinToString(",")

        val payloadJson = JSONObject().apply {
            put("events", jsonArray)
            put("sync_timestamp_ms", nowMs)
            put("horizon_days", horizonDays)
            put("progress_window_minutes", progressWindowMinutes)
            put("context_window_mode", contextMode)
            put("high_freq_15min_rule", highFreq15Min)
            put("update_on_screen_wake", updateOnWake)
            put("weather_temps_csv", weatherTempsCsv)
            put("weather_hours_csv", weatherHoursCsv)
            put("weather_rain_prob", weatherRainProb)
            put("weather_alert_source", weatherAlertSource)
            put("weather_alert_headline", weatherAlertHeadline)
            put("weather_alert_timestamp_ms", weatherAlertTsMs)
            put("watch_standalone_weather_enabled", watchStandaloneWeatherEnabled)
            put("watch_standalone_weather_interval_min", watchStandaloneWeatherIntervalMin)
            put("selected_weather_trigger_packages_csv", selectedWeatherTriggerPackagesCsv)
            if (preRenderedCardPngB64.isNotBlank()) {
                put("card_png_b64", preRenderedCardPngB64)
            }
        }.toString()

        try {
            val connReport = checkWearConnectionAndAppStatus(context)
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()

            val putDataMapReq = PutDataMapRequest.create(DATA_LAYER_PATH).apply {
                dataMap.putString("events_json", jsonArray.toString())
                dataMap.putLong("sync_timestamp_ms", nowMs)
                dataMap.putInt("horizon_days", horizonDays)
                dataMap.putInt("progress_window_minutes", progressWindowMinutes)
                dataMap.putString("context_window_mode", contextMode)
                dataMap.putBoolean("high_freq_15min_rule", highFreq15Min)
                dataMap.putBoolean("update_on_screen_wake", updateOnWake)
                dataMap.putString("weather_temps_csv", weatherTempsCsv)
                dataMap.putString("weather_hours_csv", weatherHoursCsv)
                dataMap.putInt("weather_rain_prob", weatherRainProb)
                dataMap.putString("weather_alert_source", weatherAlertSource)
                dataMap.putString("weather_alert_headline", weatherAlertHeadline)
                dataMap.putLong("weather_alert_timestamp_ms", weatherAlertTsMs)
                dataMap.putBoolean("watch_standalone_weather_enabled", watchStandaloneWeatherEnabled)
                dataMap.putInt("watch_standalone_weather_interval_min", watchStandaloneWeatherIntervalMin)
                dataMap.putString("selected_weather_trigger_packages_csv", selectedWeatherTriggerPackagesCsv)
                if (preRenderedCardPngB64.isNotBlank()) {
                    dataMap.putString("card_png_b64", preRenderedCardPngB64)
                }
            }
            Wearable.getDataClient(context)
                .putDataItem(putDataMapReq.asPutDataRequest().setUrgent())
                .await()

            for (node in nodes) {
                Wearable.getMessageClient(context).sendMessage(
                    node.id,
                    MESSAGE_FORCE_SYNC_PATH,
                    payloadJson.toByteArray(Charsets.UTF_8)
                ).await()
            }

            val nodeStatus = when (connReport.state) {
                WearConnectionState.CONNECTED_WITH_APP_INSTALLED ->
                    "Sincronizado com ${connReport.installedAppNodeNames.joinToString()}"
                WearConnectionState.WEARABLE_CONNECTED_APP_MISSING ->
                    "Wearable conectado (${connReport.wearableDisplayNames.joinToString()}), mas o app não está instalado no relógio"
                WearConnectionState.DISCONNECTED ->
                    "Nenhum relógio conectado"
            }
            prefs.recordLastSync(nowMs, events.size, selectedCalendarsCount, triggerSource, nodeStatus)
        } catch (_: Exception) {
            prefs.recordLastSync(
                nowMs,
                events.size,
                selectedCalendarsCount,
                triggerSource,
                "Pacote pronto localmente (${events.size} compromissos)"
            )
        }

        context.sendBroadcast(
            Intent(SyncPreferences.ACTION_SYNC_COMPLETED).setPackage(context.packageName)
        )
        return events.size
    }

    /**
     * Pré-renderiza no processador do Smartphone o Bitmap transparente 604x272px (@2x Retina para 302x136px)
     * com os 3 próximos eventos, contagem regressiva e barra de progresso para poupar a CPU do relógio.
     */
    private fun renderAgendaCardPngBase64OnPhone(
        events: List<LocalCalendarEvent>,
        nowMs: Long,
        progressWindowMinutes: Int
    ): String {
        val upcomingThree = events.filter { it.startEpochMs > nowMs }.take(3)
        val primary = upcomingThree.firstOrNull()
        val windowMs = TimeUnit.MINUTES.toMillis(progressWindowMinutes.toLong()).toFloat()
        val progressFraction = if (primary == null) {
            0.05f
        } else {
            val rem = (primary.startEpochMs - nowMs).coerceAtLeast(0L).toFloat()
            if (rem >= windowMs) 0.06f else (1f - (rem / windowMs)).coerceIn(0.06f, 1f)
        }

        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        fun formatRemaining(startMs: Long): String {
            val diffMs = (startMs - nowMs).coerceAtLeast(0L)
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

        val w = 604
        val h = 272
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#080A0E")
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            color = Color.parseColor("#161D26")
        }
        val cardRect = RectF(2f, 2f, w - 2f, h - 2f)
        canvas.drawRoundRect(cardRect, 40f, 40f, bgPaint)
        canvas.drawRoundRect(cardRect, 40f, 40f, borderPaint)

        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#F5C438")
        }
        val darkCutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#080A0E")
        }
        canvas.drawRoundRect(RectF(30f, 16f, 36f, 26f), 2f, 2f, goldPaint)
        canvas.drawRoundRect(RectF(48f, 16f, 54f, 26f), 2f, 2f, goldPaint)
        canvas.drawRoundRect(RectF(24f, 22f, 60f, 52f), 6f, 6f, goldPaint)
        canvas.drawLine(30f, 32f, 54f, 32f, darkCutPaint)
        canvas.drawLine(30f, 42f, 48f, 42f, darkCutPaint)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Hoje", 74f, 48f, headerPaint)

        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val countdownPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#67E8F9")
            textSize = 29f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val ev0 = upcomingThree.getOrNull(0)
        canvas.drawText(if (ev0 != null) sdf.format(Date(ev0.startEpochMs)) else "--:--", 24f, 102f, timePaint)
        val t0 = TextUtils.ellipsize(ev0?.title ?: "Livre", titlePaint, 300f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(t0, 136f, 102f, titlePaint)
        canvas.drawText(if (ev0 != null) formatRemaining(ev0.startEpochMs) else "--", w - 24f, 100f, countdownPaint)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#252E3A")
        }
        val barLeft = 24f
        val barRight = w - 24f
        canvas.drawRoundRect(RectF(barLeft, 116f, barRight, 126f), 5f, 5f, trackPaint)
        val fillWidth = (barRight - barLeft) * progressFraction
        canvas.drawRoundRect(RectF(barLeft, 116f, barLeft + fillWidth, 126f), 5f, 5f, goldPaint)

        val ev1 = upcomingThree.getOrNull(1)
        canvas.drawText(if (ev1 != null) sdf.format(Date(ev1.startEpochMs)) else "--:--", 24f, 182f, timePaint)
        val t1 = TextUtils.ellipsize(ev1?.title ?: "Livre", titlePaint, 440f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(t1, 136f, 182f, titlePaint)

        val ev2 = upcomingThree.getOrNull(2)
        canvas.drawText(if (ev2 != null) sdf.format(Date(ev2.startEpochMs)) else "--:--", 24f, 242f, timePaint)
        val t2 = TextUtils.ellipsize(ev2?.title ?: "Livre", titlePaint, 440f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(t2, 136f, 242f, titlePaint)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
