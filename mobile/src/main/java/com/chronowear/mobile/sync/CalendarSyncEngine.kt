package com.chronowear.mobile.sync

import android.Manifest
import android.bluetooth.BluetoothManager
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
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
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
    val installedAppNodeNames: List<String>,
    val bluetoothEnabled: Boolean = true
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

    fun isBluetoothEnabled(context: Context): Boolean {
        return try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bm?.adapter ?: return true
            adapter.isEnabled
        } catch (_: Exception) {
            true
        }
    }

    fun hasCalendarPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasLocationPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
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

    fun isNotificationListenerEnabled(context: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
            flat.contains(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isGoogleTasksInstalled(context: Context): Boolean {
        return isPackageInstalledOnPhone(context, "com.google.android.apps.tasks")
    }

    fun isSamsungReminderInstalled(context: Context): Boolean {
        return isPackageInstalledOnPhone(context, "com.samsung.android.app.reminder") ||
            isPackageInstalledOnPhone(context, "com.sec.android.app.reminder")
    }

    private fun isPackageInstalledOnPhone(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: Exception) {
            try {
                val allApps = context.packageManager.getInstalledApplications(0)
                allApps.any { it.packageName.equals(packageName, ignoreCase = true) }
            } catch (_: Exception) {
                false
            }
        }
    }

    fun resolveDeviceCoordinates(context: Context): Pair<Double, Double> {
        val prefs = SyncPreferences(context)
        try {
            if (hasLocationPermission(context)) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                if (lm != null) {
                    val providers = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                    var bestLoc: Location? = null
                    for (provider in providers) {
                        val candidate = try {
                            lm.getLastKnownLocation(provider)
                        } catch (_: Exception) {
                            null
                        }
                        if (candidate != null && (bestLoc == null || candidate.time > bestLoc.time)) {
                            bestLoc = candidate
                        }
                    }
                    if (bestLoc != null) {
                        prefs.saveLastKnownCoordinates(bestLoc.latitude, bestLoc.longitude)
                        return bestLoc.latitude to bestLoc.longitude
                    }
                }
            }
        } catch (_: Exception) {
        }
        return prefs.getLastKnownCoordinates() ?: (-23.5505 to -46.6333)
    }

    @Suppress("DEPRECATION")
    fun resolveDeviceLocationName(context: Context, lat: Double, lon: Double): String {
        val prefs = SyncPreferences(context)
        try {
            val geocoder = android.location.Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            val addr = addresses?.firstOrNull()
            if (addr != null) {
                val city = addr.subAdminArea ?: addr.locality ?: addr.subLocality ?: ""
                val state = addr.adminArea ?: ""
                if (city.isNotBlank()) {
                    val formatted = SyncPreferences.formatCompactWeatherLocation(city, state)
                    prefs.setPhoneWeatherLocationName(formatted)
                    return formatted
                }
            }
        } catch (_: Exception) {
        }
        return prefs.getPhoneWeatherLocationName()
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
        val btEnabled = isBluetoothEnabled(context)
        if (!btEnabled) {
            return WearConnectionReport(
                state = WearConnectionState.DISCONNECTED,
                wearableDisplayNames = emptyList(),
                installedAppNodeNames = emptyList(),
                bluetoothEnabled = false
            )
        }

        val connectedNodes = try {
            Wearable.getNodeClient(context).connectedNodes.await()
        } catch (_: Exception) {
            emptyList()
        }

        if (connectedNodes.isEmpty()) {
            return WearConnectionReport(
                state = WearConnectionState.DISCONNECTED,
                wearableDisplayNames = emptyList(),
                installedAppNodeNames = emptyList(),
                bluetoothEnabled = true
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
                installedAppNodeNames = installedNodes.map { it.displayName },
                bluetoothEnabled = true
            )
        } else {
            WearConnectionReport(
                state = WearConnectionState.WEARABLE_CONNECTED_APP_MISSING,
                wearableDisplayNames = connectedNodes.map { it.displayName },
                installedAppNodeNames = emptyList(),
                bluetoothEnabled = true
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
            null,
            null,
            "${CalendarContract.Calendars.VISIBLE} DESC, ${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
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

                val rawBeginMs = cursor.getLong(4)
                val rawEndMs = cursor.getLong(5)
                val allDay = cursor.getInt(6) == 1

                // Eventos de dia inteiro no Android CalendarContract vêm em 00:00 UTC.
                // Em UTC-3, 00:00 UTC de amanhã é 21:00 de hoje! Normalizamos para o dia local correto.
                val beginMs: Long
                val endEventMs: Long
                if (allDay) {
                    val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        timeInMillis = rawBeginMs
                    }
                    val localCal = Calendar.getInstance().apply {
                        set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                        set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                        set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                        set(Calendar.HOUR_OF_DAY, 9)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    beginMs = localCal.timeInMillis
                    localCal.set(Calendar.HOUR_OF_DAY, 23)
                    localCal.set(Calendar.MINUTE, 59)
                    endEventMs = localCal.timeInMillis
                } else {
                    beginMs = rawBeginMs
                    endEventMs = rawEndMs
                }

                if (endEventMs <= nowMs) continue
                if (!allDay && beginMs <= nowMs - 15 * 60_000L) continue

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
     * Atualiza os horários de Nascer e Pôr do Sol baseados na mesma localização do Clima no Smartphone.
     */
    fun refreshPhoneSolarTimes(context: Context) {
        val prefs = SyncPreferences(context)
        if (!prefs.isPhoneWeatherAutoCheckEnabled()) return
        val (lat, lon) = resolveDeviceCoordinates(context)
        val (astroSunrise, astroSunset) = calculateAstronomicalSunriseSunset(lat, lon)
        prefs.saveSolarTimes(astroSunrise, astroSunset)
    }

    /**
     * Atualização Unificada de Localização (Clima + Nascer e Pôr do Sol):
     * Quando o Clima é atualizado pelo Smartphone, os horários de Nascer e Pôr do Sol são
     * calculados e atualizados conjuntamente com base na mesma localização.
     */
    fun fetchPhoneHourlyWeatherIfNeeded(context: Context, forceRefresh: Boolean = false): Boolean {
        val prefs = SyncPreferences(context)
        if (!forceRefresh && !prefs.isPhoneWeatherAutoCheckEnabled()) {
            return false
        }

        val (lat, lon) = resolveDeviceCoordinates(context)
        // Sempre atualiza os horários solares em conjunto com a localização do clima
        val (astroRise, astroSet) = calculateAstronomicalSunriseSunset(lat, lon)
        prefs.saveSolarTimes(astroRise, astroSet)

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

                // Extrai Nascer e Pôr do Sol exatos da mesma localização do Clima
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
                    val resolvedLocationName = resolveDeviceLocationName(context, lat, lon)

                    shiftDetected = prefs.savePhoneFetchedWeatherAndDetectShift(
                        pickedTemps = pickedTemps,
                        pickedLabels = pickedLabels,
                        rainProbPct = maxRain,
                        weatherConditionLabel = dominantCondition,
                        locationName = resolvedLocationName,
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

    /**
     * Lê tarefas/lembretes reais do dispositivo (agenda oficial do telefone via CalendarContract +
     * Samsung Reminder + Google Tasks + notificações ativas), respeitando os switches individuais
     * de Samsung Reminder e Google Tasks, e salva em SyncPreferences para atualização imediata
     * tanto no app do telefone quanto no push único para o relógio:
     * - TODAY (agendadas para hoje, com hora HH:mm)
     * - TOMORROW (agendadas para amanhã, com hora HH:mm)
     * - UNDATED (sem data/hora definida)
     * - OVERDUE (em atraso, sem data e hora, exibidas em vermelho)
     */
    fun buildMergedTasksJsonForWatch(context: Context, prefs: SyncPreferences, nowMs: Long): String {
        if (!prefs.isTasksSyncEnabled()) {
            prefs.saveTasksJson("[]")
            return "[]"
        }

        val syncSamsungReminder = prefs.isSamsungReminderSyncEnabled()
        val syncGoogleTasks = prefs.isGoogleTasksSyncEnabled()
        if (!syncSamsungReminder && !syncGoogleTasks) {
            prefs.saveTasksJson("[]")
            return "[]"
        }

        val collectedTasks = mutableListOf<MobileTaskItem>()
        val selectedIds = prefs.getSelectedCalendarIds()
        val sdfHHMM = SimpleDateFormat("HH:mm", Locale.getDefault())

        // 1. Tarefas/lembretes capturados de notificações recentes no smartphone
        val notifTasks = prefs.getObservedNotificationTasks(nowMs)
        for (nt in notifTasks) {
            val isRem = nt.sourceLabel.equals("Reminder", ignoreCase = true)
            if (isRem && !syncSamsungReminder) continue
            if (!isRem && !syncGoogleTasks) continue
            collectedTasks.add(nt)
        }

        fun normalizeBeginMs(rawBeginMs: Long, allDay: Boolean): Long {
            if (rawBeginMs <= 0L) return 0L
            if (!allDay) return rawBeginMs
            val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                timeInMillis = rawBeginMs
            }
            return Calendar.getInstance().apply {
                set(Calendar.YEAR, utcCal.get(Calendar.YEAR))
                set(Calendar.MONTH, utcCal.get(Calendar.MONTH))
                set(Calendar.DAY_OF_MONTH, utcCal.get(Calendar.DAY_OF_MONTH))
                set(Calendar.HOUR_OF_DAY, 9)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }

        fun classifyAndAddCalendarTask(
            rawId: String,
            rawTitle: String,
            rawBeginMs: Long,
            rawEndMs: Long,
            allDay: Boolean,
            calId: String,
            calName: String,
            accName: String,
            ownerAcc: String,
            customPkg: String,
            desc: String
        ) {
            val cleanTitle = rawTitle.trim()
                .removePrefix("[Lembrete]")
                .removePrefix("[lembrete]")
                .removePrefix("[Reminder]")
                .removePrefix("[reminder]")
                .removePrefix("[Tarefa]")
                .removePrefix("[tarefa]")
                .removePrefix("[Task]")
                .removePrefix("[task]")
                .trim()
            if (cleanTitle.isBlank()) return

            val titleLower = rawTitle.lowercase(Locale.ROOT)
            val combinedMeta = "$calName $accName $ownerAcc $customPkg $desc".lowercase(Locale.ROOT)

            val isReminderMeta = combinedMeta.contains("reminder") ||
                combinedMeta.contains("lembrete") ||
                titleLower.contains("[lembrete]") ||
                titleLower.contains("[reminder]")

            val isGoogleTasksMeta = combinedMeta.contains("task") ||
                combinedMeta.contains("tarefa") ||
                combinedMeta.contains("todo") ||
                combinedMeta.contains("to-do") ||
                combinedMeta.contains("afazer") ||
                titleLower.contains("[tarefa]") ||
                titleLower.contains("[task]")

            // No Samsung Calendar / CalendarContract, lembretes sincronizados da agenda oficial muitas vezes
            // possuem duração zero (endMs <= beginMs ou endMs == 0) ou pacote Samsung/Local
            val isPointInTimeReminderInSelectedCal = (selectedIds.isEmpty() || selectedIds.contains(calId)) &&
                !allDay &&
                rawBeginMs > 0L &&
                (rawEndMs <= 0L || rawEndMs <= rawBeginMs + 5 * 60_000L)

            val sourceLabel: String = when {
                isReminderMeta || isPointInTimeReminderInSelectedCal -> {
                    if (!syncSamsungReminder) return
                    "Reminder"
                }
                isGoogleTasksMeta -> {
                    if (!syncGoogleTasks) return
                    "Tasks"
                }
                else -> return
            }

            val effectiveBeginMs = normalizeBeginMs(rawBeginMs, allDay)
            val category: String
            val dueTime: String

            if (effectiveBeginMs <= 0L || combinedMeta.contains("sem data") || combinedMeta.contains("undated")) {
                category = "UNDATED"
                dueTime = ""
            } else {
                val dayDiff = calendarDaysDiff(nowMs, effectiveBeginMs)
                when {
                    dayDiff < 0 -> {
                        category = "OVERDUE"
                        dueTime = ""
                    }
                    dayDiff == 0 -> {
                        category = "TODAY"
                        dueTime = if (!allDay) sdfHHMM.format(Date(effectiveBeginMs)) else "09:00"
                    }
                    dayDiff == 1 -> {
                        category = "TOMORROW"
                        dueTime = if (!allDay) sdfHHMM.format(Date(effectiveBeginMs)) else "09:00"
                    }
                    else -> return
                }
            }

            val existingIdx = collectedTasks.indexOfFirst {
                it.title.equals(cleanTitle, ignoreCase = true)
            }
            val item = MobileTaskItem(
                id = "cal_task_$rawId",
                title = cleanTitle,
                dueHHMM = dueTime,
                sourceLabel = sourceLabel,
                completed = false,
                isTodayOrTimeless = true,
                scheduleCategory = category,
                isOverdue = category == "OVERDUE"
            )
            if (existingIdx >= 0) {
                // Atualiza com os dados mais recentes da agenda oficial
                collectedTasks[existingIdx] = item
            } else {
                collectedTasks.add(item)
            }
        }

        // 2. Consulta CalendarContract.Instances (compromissos/tarefas de -30 dias até +7 dias)
        if (hasCalendarPermission(context)) {
            try {
                val startRange = nowMs - TimeUnit.DAYS.toMillis(30)
                val endRange = nowMs + TimeUnit.DAYS.toMillis(7)
                val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
                ContentUris.appendId(builder, startRange)
                ContentUris.appendId(builder, endRange)
                val projection = arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                    CalendarContract.Instances.OWNER_ACCOUNT,
                    CalendarContract.Instances.CUSTOM_APP_PACKAGE,
                    CalendarContract.Instances.DESCRIPTION,
                    CalendarContract.Instances.STATUS
                )
                context.contentResolver.query(
                    builder.build(),
                    projection,
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val status = if (!cursor.isNull(11)) cursor.getInt(11) else 0
                        if (status == CalendarContract.Instances.STATUS_CANCELED) continue

                        classifyAndAddCalendarTask(
                            rawId = cursor.getLong(0).toString(),
                            calId = cursor.getLong(1).toString(),
                            rawTitle = cursor.getString(2) ?: "",
                            rawBeginMs = cursor.getLong(3),
                            rawEndMs = cursor.getLong(4),
                            allDay = cursor.getInt(5) == 1,
                            calName = cursor.getString(6) ?: "",
                            accName = cursor.getString(7) ?: "",
                            ownerAcc = cursor.getString(8) ?: "",
                            customPkg = cursor.getString(9) ?: "",
                            desc = cursor.getString(10) ?: ""
                        )
                    }
                }
            } catch (_: Exception) {
            }

            // 3. Consulta complementar em CalendarContract.Events para lembretes/tarefas sem DTEND ou sem data (DTSTART = 0)
            try {
                val evProjection = arrayOf(
                    CalendarContract.Events._ID,
                    CalendarContract.Events.CALENDAR_ID,
                    CalendarContract.Events.TITLE,
                    CalendarContract.Events.DTSTART,
                    CalendarContract.Events.DTEND,
                    CalendarContract.Events.ALL_DAY,
                    CalendarContract.Events.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Events.ACCOUNT_NAME,
                    CalendarContract.Events.OWNER_ACCOUNT,
                    CalendarContract.Events.CUSTOM_APP_PACKAGE,
                    CalendarContract.Events.DESCRIPTION,
                    CalendarContract.Events.STATUS,
                    CalendarContract.Events.DELETED
                )
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    evProjection,
                    "${CalendarContract.Events.DELETED} = 0",
                    null,
                    "${CalendarContract.Events.DTSTART} ASC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val status = if (!cursor.isNull(11)) cursor.getInt(11) else 0
                        if (status == CalendarContract.Events.STATUS_CANCELED) continue

                        classifyAndAddCalendarTask(
                            rawId = cursor.getLong(0).toString(),
                            calId = cursor.getLong(1).toString(),
                            rawTitle = cursor.getString(2) ?: "",
                            rawBeginMs = if (!cursor.isNull(3)) cursor.getLong(3) else 0L,
                            rawEndMs = if (!cursor.isNull(4)) cursor.getLong(4) else 0L,
                            allDay = !cursor.isNull(5) && cursor.getInt(5) == 1,
                            calName = cursor.getString(6) ?: "",
                            accName = cursor.getString(7) ?: "",
                            ownerAcc = cursor.getString(8) ?: "",
                            customPkg = cursor.getString(9) ?: "",
                            desc = cursor.getString(10) ?: ""
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }

        val sortedTasks = collectedTasks.sortedWith(
            compareBy<MobileTaskItem> {
                when (it.scheduleCategory) {
                    "TODAY" -> 0
                    "TOMORROW" -> 1
                    "OVERDUE" -> 2
                    else -> 3
                }
            }.thenBy { it.dueHHMM.ifBlank { "99:99" } }
        )

        val arr = JSONArray()
        sortedTasks.forEach { t ->
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("dueHHMM", if (t.scheduleCategory == "TODAY" || t.scheduleCategory == "TOMORROW") t.dueHHMM else "")
                    put("sourceLabel", t.sourceLabel)
                    put("completed", false)
                    put("isTodayOrTimeless", true)
                    put("scheduleCategory", t.scheduleCategory)
                    put("isOverdue", t.isOverdue || t.scheduleCategory == "OVERDUE")
                }
            )
        }
        val jsonResult = arr.toString()
        prefs.saveTasksJson(jsonResult)
        return jsonResult
    }

    suspend fun performFullSyncToWatch(
        context: Context,
        triggerSource: String = "Sincronização Manual"
    ): Int {
        val prefs = SyncPreferences(context)
        val nowMs = System.currentTimeMillis()

        // Usa imediatamente as coordenadas salvas em cache para não atrasar o envio ao relógio
        val cachedCoords = prefs.getLastKnownCoordinates()
        val weatherLat = cachedCoords?.first ?: -23.5505
        val weatherLon = cachedCoords?.second ?: -46.6333

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
        val unifiedCanvasHoldMin = prefs.getUnifiedCanvasHoldMinutes()
        val notifyOnCanvasChange = prefs.isNotifyWatchOnCanvasChangeEnabled()
        val notifyAgenda15Min = prefs.isNotifyAgenda15MinEnabled()
        val autoFallbackAgendaToTasks = prefs.isAutoFallbackAgendaToTasksEnabled()
        val autoAgendaExposureMin = prefs.getAutoAgendaExposureMinutes()
        val autoWeatherExposureMin = prefs.getAutoWeatherExposureMinutes()
        val autoSunExposureMin = prefs.getAutoSunExposureMinutes()
        val autoSleepExposureMin = prefs.getAutoSleepExposureMinutes()
        val sleepSyncEnabled = prefs.isSleepSyncEnabled()
        val sleepHasDataToday = prefs.hasVerifiedSleepDataForToday(nowMs)
        val sleepDurationMin = prefs.getSleepDurationMinutes()
        val sleepScore = prefs.getSleepScore()
        val sleepBedtimeHHMM = prefs.getSleepBedtimeHHMM()
        val sleepWakeHHMM = prefs.getSleepWakeHHMM()
        val sleepStagesCsv = prefs.getSleepStagesCsv()
        val sleepWakeTsMs = prefs.getSleepWakeTimestampMs()
        val tasksSyncEnabled = prefs.isTasksSyncEnabled()
        // Sincroniza imediatamente as tarefas classificadas (Hoje c/ hora -> Amanhã c/ hora -> Sem data e Atrasadas em vermelho)
        val tasksJson = buildMergedTasksJsonForWatch(context, prefs, nowMs)
        val tasksAlertTsMs = prefs.getTasksAlertTimestampMs()
        val highFreq15Min = prefs.isHighFreq15MinRuleEnabled()
        val updateOnWake = prefs.isUpdateOnScreenWakeEnabled()

        val weatherTempsCsv = prefs.getPhoneWeatherTempsCsv()
        val weatherHoursCsv = prefs.getPhoneWeatherHoursCsv()
        val weatherRainProb = prefs.getPhoneWeatherRainProbPct()
        val weatherConditionLabel = prefs.getPhoneWeatherConditionLabel()
        val weatherLocationName = prefs.getPhoneWeatherLocationName()
        val weatherUpdatedHHMM = prefs.getPhoneWeatherUpdatedHHMM()
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
            put("unified_canvas_hold_min", unifiedCanvasHoldMin)
            put("notify_on_canvas_change", notifyOnCanvasChange)
            put("notify_agenda_15min", notifyAgenda15Min)
            put("auto_fallback_agenda_to_tasks", autoFallbackAgendaToTasks)
            put("auto_agenda_exposure_min", autoAgendaExposureMin)
            put("auto_weather_exposure_min", autoWeatherExposureMin)
            put("auto_sun_exposure_min", autoSunExposureMin)
            put("auto_sleep_exposure_min", autoSleepExposureMin)
            put("sleep_sync_enabled", sleepSyncEnabled)
            put("sleep_has_data_today", sleepHasDataToday)
            put("sleep_duration_min", sleepDurationMin)
            put("sleep_score", sleepScore)
            put("sleep_bedtime_hhmm", sleepBedtimeHHMM)
            put("sleep_wake_hhmm", sleepWakeHHMM)
            put("sleep_stages_csv", sleepStagesCsv)
            put("sleep_wake_ts_ms", sleepWakeTsMs)
            put("tasks_sync_enabled", tasksSyncEnabled)
            put("tasks_json", tasksJson)
            put("tasks_alert_timestamp_ms", tasksAlertTsMs)
            put("high_freq_15min_rule", highFreq15Min)
            put("update_on_screen_wake", updateOnWake)
            put("weather_temps_csv", weatherTempsCsv)
            put("weather_hours_csv", weatherHoursCsv)
            put("weather_rain_prob", weatherRainProb)
            put("weather_condition_label", weatherConditionLabel)
            put("weather_location_name", weatherLocationName)
            put("weather_updated_hhmm", weatherUpdatedHHMM)
            put("weather_lat", weatherLat)
            put("weather_lon", weatherLon)
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
            // 1. ENVIO IMEDIATO AO RELÓGIO (MessageClient em tempo real + DataClient Urgente, sem bloquear na rede!)
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)
            for (node in nodes) {
                try {
                    Wearable.getMessageClient(context).sendMessage(
                        node.id,
                        MESSAGE_FORCE_SYNC_PATH,
                        payloadBytes
                    ).await()
                } catch (_: Exception) {
                }
            }

            val putDataMapReq = PutDataMapRequest.create(DATA_LAYER_PATH).apply {
                dataMap.putString("events_json", jsonArray.toString())
                dataMap.putLong("sync_timestamp_ms", nowMs)
                dataMap.putInt("horizon_days", horizonDays)
                dataMap.putInt("progress_window_minutes", progressWindowMinutes)
                dataMap.putString("context_window_mode", contextMode)
                dataMap.putString("auto_primary_context", autoPrimaryContext)
                dataMap.putInt("unified_canvas_hold_min", unifiedCanvasHoldMin)
                dataMap.putBoolean("notify_on_canvas_change", notifyOnCanvasChange)
                dataMap.putBoolean("notify_agenda_15min", notifyAgenda15Min)
                dataMap.putBoolean("auto_fallback_agenda_to_tasks", autoFallbackAgendaToTasks)
                dataMap.putInt("auto_agenda_exposure_min", autoAgendaExposureMin)
                dataMap.putInt("auto_weather_exposure_min", autoWeatherExposureMin)
                dataMap.putInt("auto_sun_exposure_min", autoSunExposureMin)
                dataMap.putInt("auto_sleep_exposure_min", autoSleepExposureMin)
                dataMap.putBoolean("sleep_sync_enabled", sleepSyncEnabled)
                dataMap.putBoolean("sleep_has_data_today", sleepHasDataToday)
                dataMap.putInt("sleep_duration_min", sleepDurationMin)
                dataMap.putInt("sleep_score", sleepScore)
                dataMap.putString("sleep_bedtime_hhmm", sleepBedtimeHHMM)
                dataMap.putString("sleep_wake_hhmm", sleepWakeHHMM)
                dataMap.putString("sleep_stages_csv", sleepStagesCsv)
                dataMap.putLong("sleep_wake_ts_ms", sleepWakeTsMs)
                dataMap.putBoolean("tasks_sync_enabled", tasksSyncEnabled)
                dataMap.putString("tasks_json", tasksJson)
                dataMap.putLong("tasks_alert_timestamp_ms", tasksAlertTsMs)
                dataMap.putBoolean("high_freq_15min_rule", highFreq15Min)
                dataMap.putBoolean("update_on_screen_wake", updateOnWake)
                dataMap.putString("weather_temps_csv", weatherTempsCsv)
                dataMap.putString("weather_hours_csv", weatherHoursCsv)
                dataMap.putInt("weather_rain_prob", weatherRainProb)
                dataMap.putString("weather_condition_label", weatherConditionLabel)
                dataMap.putString("weather_location_name", weatherLocationName)
                dataMap.putString("weather_updated_hhmm", weatherUpdatedHHMM)
                dataMap.putDouble("weather_lat", weatherLat)
                dataMap.putDouble("weather_lon", weatherLon)
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

            val connReport = checkWearConnectionAndAppStatus(context)
            val nodeStatus = when (connReport.state) {
                WearConnectionState.CONNECTED_WITH_APP_INSTALLED ->
                    "Sincronizado com ${connReport.installedAppNodeNames.joinToString()}"
                WearConnectionState.WEARABLE_CONNECTED_APP_MISSING ->
                    "Wearable conectado (${connReport.wearableDisplayNames.joinToString()}), mas o app não está instalado no relógio"
                WearConnectionState.DISCONNECTED ->
                    if (!connReport.bluetoothEnabled) "Bluetooth desativado no smartphone" else "Nenhum relógio conectado"
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

        // Notifica a interface do smartphone imediatamente
        context.sendBroadcast(
            Intent(SyncPreferences.ACTION_SYNC_COMPLETED).setPackage(context.packageName)
        )

        // 2. Somente APÓS o relógio já ter sido atualizado instantaneamente, verifica em segundo plano se o clima precisa ser renovado
        try {
            fetchPhoneHourlyWeatherIfNeeded(context, forceRefresh = false)
        } catch (_: Exception) {
        }

        return events.size
    }

    fun isSameCalendarDay(epochA: Long, epochB: Long): Boolean {
        val calA = Calendar.getInstance().apply { timeInMillis = epochA }
        val calB = Calendar.getInstance().apply { timeInMillis = epochB }
        return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
            calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
    }

    fun calendarDaysDiff(nowMs: Long, targetMs: Long): Int {
        val startOfNow = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val startOfTarget = Calendar.getInstance().apply {
            timeInMillis = targetMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return TimeUnit.MILLISECONDS.toDays(startOfTarget - startOfNow).toInt()
    }

    /**
     * Resolve de forma estrita os compromissos de "Hoje" ou de "Amanhã" (sem misturar dias).
     */
    fun resolveDayHeaderAndEvents(
        events: List<LocalCalendarEvent>,
        nowMs: Long
    ): Pair<String, List<LocalCalendarEvent>> {
        val allFuture = events.filter { it.startEpochMs > nowMs - 15 * 60_000L && it.endEpochMs > nowMs }
        val todayEvents = allFuture.filter { isSameCalendarDay(nowMs, it.startEpochMs) }
        if (todayEvents.isNotEmpty()) {
            return "Hoje" to todayEvents.take(3)
        }
        val tomorrowEvents = allFuture.filter { calendarDaysDiff(nowMs, it.startEpochMs) == 1 }
        if (tomorrowEvents.isNotEmpty()) {
            return "Amanhã" to tomorrowEvents.take(3)
        }
        if (allFuture.isNotEmpty()) {
            val firstNext = allFuture.first()
            val header = SimpleDateFormat("EEE, dd", Locale("pt", "BR"))
                .format(Date(firstNext.startEpochMs))
                .replace(".", "")
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("pt", "BR")) else it.toString() }
            return header to allFuture.filter { isSameCalendarDay(firstNext.startEpochMs, it.startEpochMs) }.take(3)
        }
        return "Hoje" to emptyList()
    }

    /**
     * Pré-renderiza no processador do Smartphone o Bitmap transparente 604x272px (@2x Retina para 302x136px)
     * com cabeçalho dinâmico ("Hoje", "Amanhã" ou próximo dia com agenda) e barra de progresso 0% -> 100%.
     */
    private fun renderAgendaCardPngBase64OnPhone(
        events: List<LocalCalendarEvent>,
        nowMs: Long,
        progressWindowMinutes: Int
    ): String {
        val allFuture = events.filter { it.startEpochMs > nowMs }
        val todayEvents = allFuture.filter { isSameCalendarDay(nowMs, it.startEpochMs) }

        val dayHeader: String
        val dayEvents: List<LocalCalendarEvent>
        if (todayEvents.isNotEmpty()) {
            dayHeader = "Hoje"
            dayEvents = todayEvents.take(3)
        } else if (allFuture.isNotEmpty()) {
            val firstNext = allFuture.first()
            val diffDays = calendarDaysDiff(nowMs, firstNext.startEpochMs)
            dayHeader = when (diffDays) {
                1 -> "Amanhã"
                else -> SimpleDateFormat("EEE, dd", Locale("pt", "BR"))
                    .format(Date(firstNext.startEpochMs))
                    .replace(".", "")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("pt", "BR")) else it.toString() }
            }
            dayEvents = allFuture.filter { isSameCalendarDay(firstNext.startEpochMs, it.startEpochMs) }.take(3)
        } else {
            dayHeader = "Hoje"
            dayEvents = emptyList()
        }

        val primary = dayEvents.firstOrNull()
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
        canvas.drawText(dayHeader, 78f, 51f, headerPaint)

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

        val ev0 = dayEvents.getOrNull(0)
        canvas.drawText(if (ev0 != null) sdf.format(Date(ev0.startEpochMs)) else "--:--", 24f, 105f, timePaint)
        val t0 = TextUtils.ellipsize(ev0?.title ?: "Não há eventos", titlePaint, 290f, TextUtils.TruncateAt.END).toString()
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

        val ev1 = dayEvents.getOrNull(1)
        canvas.drawText(if (ev1 != null) sdf.format(Date(ev1.startEpochMs)) else "--:--", 24f, 188f, timePaint)
        val t1 = TextUtils.ellipsize(ev1?.title ?: if (ev0 != null) "Livre" else "Agenda livre", titlePaint, 430f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(t1, 150f, 188f, titlePaint)

        val ev2 = dayEvents.getOrNull(2)
        canvas.drawText(if (ev2 != null) sdf.format(Date(ev2.startEpochMs)) else "--:--", 24f, 250f, timePaint)
        val t2 = TextUtils.ellipsize(ev2?.title ?: if (ev0 != null) "Livre" else "Sem compromissos", titlePaint, 430f, TextUtils.TruncateAt.END).toString()
        canvas.drawText(t2, 150f, 250f, titlePaint)

        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
