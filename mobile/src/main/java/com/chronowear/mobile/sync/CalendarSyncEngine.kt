package com.chronowear.mobile.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.LocationManager
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

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

    fun resolveDeviceCoordinates(context: Context): Pair<Double, Double> {
        var lat = -23.5505
        var lon = -46.6333
        try {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val loc = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                if (loc != null) {
                    lat = loc.latitude
                    lon = loc.longitude
                }
            }
        } catch (_: Exception) {
        }
        return lat to lon
    }

    /**
     * Calcula astronomicamente os horários de Nascer e Pôr do Sol (NOAA Solar Calculator)
     * baseado na latitude, longitude e fuso horário do dispositivo, servindo tanto offline
     * quanto como complemento da API.
     */
    fun calculateAstronomicalSunriseSunset(lat: Double, lon: Double): Pair<String, String> {
        return try {
            val cal = Calendar.getInstance()
            val dayOfYear = cal.get(Calendar.DAY_OF_YEAR)
            val tzOffsetHours = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000.0

            val gamma = 2.0 * Math.PI / 365.0 * (dayOfYear - 1)
            val eqTime = 229.18 * (
                0.000075 +
                    0.001868 * cos(gamma) -
                    0.032077 * sin(gamma) -
                    0.014615 * cos(2 * gamma) -
                    0.040849 * sin(2 * gamma)
                )
            val decl = 0.006918 -
                0.399912 * cos(gamma) +
                0.070257 * sin(gamma) -
                0.006758 * cos(2 * gamma) +
                0.000907 * sin(2 * gamma) -
                0.002697 * cos(3 * gamma) +
                0.00148 * sin(3 * gamma)

            val latRad = Math.toRadians(lat)
            val zenith = Math.toRadians(90.833)
            val cosHa = (cos(zenith) / (cos(latRad) * cos(decl)) - tan(latRad) * tan(decl))
                .coerceIn(-1.0, 1.0)
            val haDeg = Math.toDegrees(acos(cosHa))

            val sunriseMinutesUtc = 720.0 - 4.0 * (lon + haDeg) - eqTime
            val sunsetMinutesUtc = 720.0 - 4.0 * (lon - haDeg) - eqTime

            fun formatMins(utcMins: Double): String {
                val localMins = ((utcMins + tzOffsetHours * 60.0) % 1440.0 + 1440.0) % 1440.0
                val h = floor(localMins / 60.0).toInt() % 24
                val m = floor(localMins % 60.0).toInt()
                return String.format(Locale.getDefault(), "%02d:%02d", h, m)
            }

            formatMins(sunriseMinutesUtc) to formatMins(sunsetMinutesUtc)
        } catch (_: Exception) {
            "05:27" to "17:40"
        }
    }

    fun mapWmoCodeToPortugueseLabel(wmoCode: Int, rainProbPct: Int): String {
        return when {
            wmoCode in 95..99 -> "Tempestade"
            wmoCode in 80..82 || wmoCode in 65..67 -> "Chuva forte"
            wmoCode in 61..64 || rainProbPct >= 65 -> "Chuva"
            wmoCode in 51..57 || rainProbPct in 45..64 -> "Garoa"
            wmoCode in 71..77 || wmoCode in 85..86 -> "Neve"
            wmoCode in 45..48 -> "Neblina"
            wmoCode == 3 -> "Nublado"
            wmoCode == 2 -> "Parc. nublado"
            else -> "Céu limpo"
        }
    }

    /**
     * Inspeciona os aplicativos instalados no Smartphone e os separa por categoria relacionada
     * a Clima, Meteorologia, Radar e Provedores do Sistema para que o usuário escolha quais
     * servirão de Trigger de Notificação.
     */
    fun discoverInstalledWeatherAppsByCategory(context: Context): List<WeatherTriggerAppOption> {
        val pm = context.packageManager
        val installedMap = mutableMapOf<String, ApplicationInfo>()

        try {
            val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in allApps) {
                installedMap[app.packageName.lowercase()] = app
            }
        } catch (_: Exception) {
        }

        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
            for (ri in resolveInfos) {
                val ai = ri.activityInfo?.applicationInfo
                if (ai != null) {
                    installedMap[ai.packageName.lowercase()] = ai
                }
            }
        } catch (_: Exception) {
        }

        val results = mutableListOf<WeatherTriggerAppOption>()
        val addedPackages = mutableSetOf<String>()

        // 1. Verifica os apps do catálogo oficial e marca se estão instalados no telefone
        for (item in SyncPreferences.KNOWN_WEATHER_TRIGGER_CATALOG) {
            val key = item.packageName.lowercase()
            val installedApp = installedMap[key]
            val label = if (installedApp != null) {
                try {
                    pm.getApplicationLabel(installedApp).toString().takeIf { it.isNotBlank() } ?: item.displayName
                } catch (_: Exception) {
                    item.displayName
                }
            } else {
                item.displayName
            }
            results.add(
                item.copy(
                    displayName = label,
                    isInstalledOnDevice = installedApp != null
                )
            )
            addedPackages.add(key)
        }

        // 2. Varre todos os outros aplicativos instalados no telefone procurando áreas afins de Clima/Meteorologia
        val weatherKeywords = listOf(
            "weather", "clima", "meteo", "forecast", "tempo", "chuva", "rain",
            "windy", "wind", "storm", "radar", "inmet", "accuweather", "climatempo",
            "daemonapp", "weawow", "overdrop", "breezy", "ambient", "solar", "sun"
        )

        for ((pkgLower, appInfo) in installedMap) {
            if (addedPackages.contains(pkgLower)) continue
            if (pkgLower.startsWith("com.chronowear")) continue

            val appLabel = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                ""
            }
            val labelLower = appLabel.lowercase()

            val matchesWeatherDomain = weatherKeywords.any { kw ->
                pkgLower.contains(kw) || labelLower.contains(kw)
            }

            if (matchesWeatherDomain) {
                val categoryName = when {
                    pkgLower.contains("radar") || pkgLower.contains("wind") || pkgLower.contains("storm") ->
                        "Clima e Radar"
                    pkgLower.contains("sun") || pkgLower.contains("solar") ->
                        "Astronomia e Sol"
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ->
                        "Provedores do Sistema"
                    else ->
                        "Clima e Meteorologia"
                }

                results.add(
                    WeatherTriggerAppOption(
                        packageName = appInfo.packageName,
                        displayName = appLabel.ifBlank { appInfo.packageName },
                        categoryName = categoryName,
                        categoryDescription = "Instalado no telefone (${appInfo.packageName})",
                        isInstalledOnDevice = true
                    )
                )
                addedPackages.add(pkgLower)
            }
        }

        // Ordena priorizando os instalados no aparelho e agrupando por categoria
        return results.sortedWith(
            compareByDescending<WeatherTriggerAppOption> { it.isInstalledOnDevice }
                .thenBy { it.categoryName }
                .thenBy { it.displayName }
        )
    }

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
     * Atualiza os horários de Nascer e Pôr do Sol baseados na localização do Smartphone
     * (calcula astronomicamente de imediato e refina via Open-Meteo quando disponível).
     */
    fun refreshPhoneSolarTimes(context: Context) {
        val prefs = SyncPreferences(context)
        if (!prefs.isPhoneSolarAutoSyncEnabled()) return
        val (lat, lon) = resolveDeviceCoordinates(context)
        val (astroSunrise, astroSunset) = calculateAstronomicalSunriseSunset(lat, lon)
        prefs.saveSolarTimes(astroSunrise, astroSunset)
    }

    /**
     * Busca as previsões horárias reais pelo Smartphone (temperatura, probabilidade de chuva,
     * código meteorológico WMO e horários de nascer/pôr do sol), detectando tanto mudanças de
     * temperatura quanto mudanças climáticas (chuva, garoa, tempestade, neblina).
     */
    fun fetchPhoneHourlyWeatherIfNeeded(context: Context, forceRefresh: Boolean = false): Boolean {
        val prefs = SyncPreferences(context)
        val (lat, lon) = resolveDeviceCoordinates(context)

        // Garante que o cálculo solar baseado na localização esteja sempre atualizado
        if (prefs.isPhoneSolarAutoSyncEnabled()) {
            val (astroRise, astroSet) = calculateAstronomicalSunriseSunset(lat, lon)
            prefs.saveSolarTimes(astroRise, astroSet)
        }

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
            val urlStr =
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&hourly=temperature_2m,precipitation_probability,weather_code" +
                    "&daily=sunrise,sunset&forecast_days=2&timezone=auto"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4500
                readTimeout = 4500
            }

            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)

                // Extrai Nascer e Pôr do Sol exatos da localização
                if (prefs.isPhoneSolarAutoSyncEnabled()) {
                    val daily = root.optJSONObject("daily")
                    val sunriseArr = daily?.optJSONArray("sunrise")
                    val sunsetArr = daily?.optJSONArray("sunset")
                    val rawRise = sunriseArr?.optString(0, "") ?: ""
                    val rawSet = sunsetArr?.optString(0, "") ?: ""
                    val riseHHMM = rawRise.substringAfter("T", "").take(5)
                    val setHHMM = rawSet.substringAfter("T", "").take(5)
                    if (riseHHMM.length == 5 && setHHMM.length == 5) {
                        prefs.saveSolarTimes(riseHHMM, setHHMM)
                    }
                }

                val hourly = root.optJSONObject("hourly")
                val timesArr = hourly?.optJSONArray("time")
                val tempsArr = hourly?.optJSONArray("temperature_2m")
                val rainArr = hourly?.optJSONArray("precipitation_probability")
                val codeArr = hourly?.optJSONArray("weather_code")

                if (timesArr != null && tempsArr != null && timesArr.length() >= 24) {
                    val cal = Calendar.getInstance()
                    val currentHour = cal.get(Calendar.HOUR_OF_DAY)
                    val offsets = listOf(0, 2, 4, 6, 8)
                    val pickedTemps = mutableListOf<Int>()
                    val pickedLabels = mutableListOf<String>()
                    val pickedCodes = mutableListOf<Int>()
                    var maxRain = 0

                    for ((idx, offset) in offsets.withIndex()) {
                        val arrayIdx = (currentHour + offset).coerceAtMost(timesArr.length() - 1)
                        val tempVal = Math.round(tempsArr.optDouble(arrayIdx, 25.0)).toInt()
                        val rainVal = rainArr?.optInt(arrayIdx, 0) ?: 0
                        val codeVal = codeArr?.optInt(arrayIdx, 0) ?: 0
                        if (rainVal > maxRain) maxRain = rainVal
                        pickedTemps.add(tempVal)
                        pickedCodes.add(codeVal)
                        if (idx == 0) {
                            pickedLabels.add("Agora")
                        } else {
                            val h = (currentHour + offset) % 24
                            pickedLabels.add(String.format(Locale.getDefault(), "%02dh", h))
                        }
                    }

                    val dominantWmoCode = pickedCodes.maxByOrNull { code ->
                        when {
                            code >= 95 -> 100
                            code >= 61 -> 80
                            code >= 51 -> 60
                            code >= 45 -> 40
                            code >= 2 -> 20
                            else -> 0
                        }
                    } ?: 0

                    val firstCondition = mapWmoCodeToPortugueseLabel(pickedCodes.firstOrNull() ?: 0, 0)
                    val dominantCondition = mapWmoCodeToPortugueseLabel(dominantWmoCode, maxRain)
                    val conditionShiftAcrossHours = firstCondition != dominantCondition

                    shiftDetected = prefs.savePhoneFetchedWeatherAndDetectShift(
                        pickedTemps = pickedTemps,
                        pickedLabels = pickedLabels,
                        rainProbPct = maxRain,
                        weatherConditionLabel = dominantCondition,
                        conditionShiftDetected = conditionShiftAcrossHours,
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

        // Atualiza previsões horárias de clima e horários solares pelo telefone
        fetchPhoneHourlyWeatherIfNeeded(context, forceRefresh = false)

        val hasCalPerm = hasCalendarPermission(context)
        val selectedCalendarsCount = prefs.getSelectedCalendarIds().size
        val events = if (hasCalPerm && selectedCalendarsCount > 0) {
            queryHorizonEvents(context)
        } else {
            emptyList()
        }

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
        val autoPrimaryContext = prefs.getAutoPrimaryContext()
        val autoAgendaExposureMin = prefs.getAutoAgendaExposureMinutes()
        val autoWeatherExposureMin = prefs.getAutoWeatherExposureMinutes()
        val autoSunExposureMin = prefs.getAutoSunExposureMinutes()
        val autoSleepExposureMin = prefs.getAutoSleepExposureMinutes()
        val sleepSyncEnabled = prefs.isSleepSyncEnabled()
        val sleepDurationMin = prefs.getSleepDurationMinutes()
        val sleepScore = prefs.getSleepScore()
        val sleepBedtimeHHMM = prefs.getSleepBedtimeHHMM()
        val sleepWakeHHMM = prefs.getSleepWakeHHMM()
        val sleepStagesCsv = prefs.getSleepStagesCsv()
        val sleepWakeTsMs = prefs.getSleepWakeTimestampMs()
        val highFreq15Min = prefs.isHighFreq15MinRuleEnabled()
        val updateOnWake = prefs.isUpdateOnScreenWakeEnabled()

        val weatherTempsCsv = prefs.getPhoneWeatherTempsCsv()
        val weatherHoursCsv = prefs.getPhoneWeatherHoursCsv()
        val weatherRainProb = prefs.getPhoneWeatherRainProbPct()
        val weatherConditionLabel = prefs.getPhoneWeatherConditionLabel()
        val weatherAlertSource = prefs.getWeatherAlertSource()
        val weatherAlertHeadline = prefs.getWeatherAlertHeadline()
        val weatherAlertTsMs = prefs.getWeatherAlertTimestampMs()
        val watchStandaloneWeatherEnabled = prefs.isWatchStandaloneWeatherEnabled()
        val watchStandaloneWeatherIntervalMin = prefs.getWatchStandaloneWeatherIntervalMinutes()
        val selectedWeatherTriggerPackagesCsv = prefs.getSelectedWeatherTriggerPackages().joinToString(",")
        val solarSunriseHHMM = prefs.getSolarSunriseHHMM()
        val solarSunsetHHMM = prefs.getSolarSunsetHHMM()
        val watchStandaloneSolarEnabled = prefs.isWatchStandaloneSolarEnabled()

        val payloadJson = JSONObject().apply {
            put("events", jsonArray)
            put("sync_timestamp_ms", nowMs)
            put("horizon_days", horizonDays)
            put("progress_window_minutes", progressWindowMinutes)
            put("context_window_mode", contextMode)
            put("auto_primary_context", autoPrimaryContext)
            put("auto_agenda_exposure_min", autoAgendaExposureMin)
            put("auto_weather_exposure_min", autoWeatherExposureMin)
            put("auto_sun_exposure_min", autoSunExposureMin)
            put("auto_sleep_exposure_min", autoSleepExposureMin)
            put("sleep_sync_enabled", sleepSyncEnabled)
            put("sleep_duration_min", sleepDurationMin)
            put("sleep_score", sleepScore)
            put("sleep_bedtime_hhmm", sleepBedtimeHHMM)
            put("sleep_wake_hhmm", sleepWakeHHMM)
            put("sleep_stages_csv", sleepStagesCsv)
            put("sleep_wake_ts_ms", sleepWakeTsMs)
            put("high_freq_15min_rule", highFreq15Min)
            put("update_on_screen_wake", updateOnWake)
            put("weather_temps_csv", weatherTempsCsv)
            put("weather_hours_csv", weatherHoursCsv)
            put("weather_rain_prob", weatherRainProb)
            put("weather_condition_label", weatherConditionLabel)
            put("weather_alert_source", weatherAlertSource)
            put("weather_alert_headline", weatherAlertHeadline)
            put("weather_alert_timestamp_ms", weatherAlertTsMs)
            put("watch_standalone_weather_enabled", watchStandaloneWeatherEnabled)
            put("watch_standalone_weather_interval_min", watchStandaloneWeatherIntervalMin)
            put("selected_weather_trigger_packages_csv", selectedWeatherTriggerPackagesCsv)
            put("solar_sunrise_hhmm", solarSunriseHHMM)
            put("solar_sunset_hhmm", solarSunsetHHMM)
            put("watch_standalone_solar_enabled", watchStandaloneSolarEnabled)
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
                dataMap.putString("auto_primary_context", autoPrimaryContext)
                dataMap.putInt("auto_agenda_exposure_min", autoAgendaExposureMin)
                dataMap.putInt("auto_weather_exposure_min", autoWeatherExposureMin)
                dataMap.putInt("auto_sun_exposure_min", autoSunExposureMin)
                dataMap.putInt("auto_sleep_exposure_min", autoSleepExposureMin)
                dataMap.putBoolean("sleep_sync_enabled", sleepSyncEnabled)
                dataMap.putInt("sleep_duration_min", sleepDurationMin)
                dataMap.putInt("sleep_score", sleepScore)
                dataMap.putString("sleep_bedtime_hhmm", sleepBedtimeHHMM)
                dataMap.putString("sleep_wake_hhmm", sleepWakeHHMM)
                dataMap.putString("sleep_stages_csv", sleepStagesCsv)
                dataMap.putLong("sleep_wake_ts_ms", sleepWakeTsMs)
                dataMap.putBoolean("high_freq_15min_rule", highFreq15Min)
                dataMap.putBoolean("update_on_screen_wake", updateOnWake)
                dataMap.putString("weather_temps_csv", weatherTempsCsv)
                dataMap.putString("weather_hours_csv", weatherHoursCsv)
                dataMap.putInt("weather_rain_prob", weatherRainProb)
                dataMap.putString("weather_condition_label", weatherConditionLabel)
                dataMap.putString("weather_alert_source", weatherAlertSource)
                dataMap.putString("weather_alert_headline", weatherAlertHeadline)
                dataMap.putLong("weather_alert_timestamp_ms", weatherAlertTsMs)
                dataMap.putBoolean("watch_standalone_weather_enabled", watchStandaloneWeatherEnabled)
                dataMap.putInt("watch_standalone_weather_interval_min", watchStandaloneWeatherIntervalMin)
                dataMap.putString("selected_weather_trigger_packages_csv", selectedWeatherTriggerPackagesCsv)
                dataMap.putString("solar_sunrise_hhmm", solarSunriseHHMM)
                dataMap.putString("solar_sunset_hhmm", solarSunsetHHMM)
                dataMap.putBoolean("watch_standalone_solar_enabled", watchStandaloneSolarEnabled)
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
                "Configurações e pacote salvos localmente (${events.size} compromissos)"
            )
        }

        context.sendBroadcast(
            Intent(SyncPreferences.ACTION_SYNC_COMPLETED).setPackage(context.packageName)
        )
        return events.size
    }

    /**
     * Pré-renderiza no processador do Smartphone o Bitmap transparente 604x272px (@2x Retina para 302x136px)
     * com tipografia ampliada e barra de progresso 0% -> 100% fiel à janela configurada pelo usuário.
     */
    private fun renderAgendaCardPngBase64OnPhone(
        events: List<LocalCalendarEvent>,
        nowMs: Long,
        progressWindowMinutes: Int
    ): String {
        val upcomingThree = events.filter { it.startEpochMs > nowMs }.take(3)
        val primary = upcomingThree.firstOrNull()
        val windowMs = TimeUnit.MINUTES.toMillis(progressWindowMinutes.coerceAtLeast(1).toLong())
        val progressFraction = if (primary == null) {
            0f
        } else {
            val remMs = primary.startEpochMs - nowMs
            when {
                remMs <= 0L -> 1f
                remMs >= windowMs -> 0f
                else -> ((windowMs - remMs).toFloat() / windowMs.toFloat()).coerceIn(0f, 1f)
            }
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
            strokeWidth = 2.6f
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
            strokeWidth = 4.5f
            color = Color.parseColor("#080A0E")
        }
        canvas.drawRoundRect(RectF(30f, 14f, 37f, 26f), 2.5f, 2.5f, goldPaint)
        canvas.drawRoundRect(RectF(51f, 14f, 58f, 26f), 2.5f, 2.5f, goldPaint)
        canvas.drawRoundRect(RectF(24f, 21f, 64f, 55f), 7f, 7f, goldPaint)
        canvas.drawLine(31f, 33f, 57f, 33f, darkCutPaint)
        canvas.drawLine(31f, 44f, 50f, 44f, darkCutPaint)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Hoje", 78f, 51f, headerPaint)

        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val countdownPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#67E8F9")
            textSize = 35f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val ev0 = upcomingThree.getOrNull(0)
        canvas.drawText(if (ev0 != null) sdf.format(Date(ev0.startEpochMs)) else "--:--", 24f, 105f, timePaint)
        val t0 = TextUtils.ellipsize(ev0?.title ?: "Livre", titlePaint, 290f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(t0, 150f, 105f, titlePaint)
        canvas.drawText(if (ev0 != null) formatRemaining(ev0.startEpochMs) else "--", w - 24f, 103f, countdownPaint)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#252E3A")
        }
        val barLeft = 24f
        val barRight = w - 24f
        canvas.drawRoundRect(RectF(barLeft, 117f, barRight, 130f), 6.5f, 6.5f, trackPaint)
        if (progressFraction > 0f) {
            val fillWidth = ((barRight - barLeft) * progressFraction).coerceAtLeast(10f)
            canvas.drawRoundRect(RectF(barLeft, 117f, barLeft + fillWidth, 130f), 6.5f, 6.5f, goldPaint)
        }

        val ev1 = upcomingThree.getOrNull(1)
        canvas.drawText(if (ev1 != null) sdf.format(Date(ev1.startEpochMs)) else "--:--", 24f, 188f, timePaint)
        val t1 = TextUtils.ellipsize(ev1?.title ?: "Livre", titlePaint, 430f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(t1, 150f, 188f, titlePaint)

        val ev2 = upcomingThree.getOrNull(2)
        canvas.drawText(if (ev2 != null) sdf.format(Date(ev2.startEpochMs)) else "--:--", 24f, 250f, timePaint)
        val t2 = TextUtils.ellipsize(ev2?.title ?: "Livre", titlePaint, 430f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(t2, 150f, 250f, titlePaint)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
