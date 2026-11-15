package com.chronowear.mobile.sync

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import android.provider.Settings
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
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
    const val MESSAGE_WAKE_AND_PULL_PATH = "/chronowear/wake_and_pull_data"
    const val MESSAGE_PING_WATCH_APP_PATH = "/chronowear/ping_watch_app"
    const val CAPABILITY_WATCH_APP_INSTALLED = "chronowear_wear_app_installed"
    private const val MAX_MESSAGE_PAYLOAD_BYTES = 85_000

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

        val reachableCapNodeIds = try {
            Wearable.getCapabilityClient(context)
                .getCapability(CAPABILITY_WATCH_APP_INSTALLED, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes.map { it.id }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        val allCapNodeIds = try {
            Wearable.getCapabilityClient(context)
                .getCapability(CAPABILITY_WATCH_APP_INSTALLED, CapabilityClient.FILTER_ALL)
                .await()
                .nodes.map { it.id }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val pingStartedAtMs = System.currentTimeMillis()
        val pingSucceededNodeIds = HashSet<String>()
        for (node in connectedNodes) {
            try {
                Wearable.getMessageClient(context).sendMessage(
                    node.id,
                    MESSAGE_PING_WATCH_APP_PATH,
                    ByteArray(0)
                ).await()
                pingSucceededNodeIds.add(node.id)
            } catch (_: Exception) {
            }
        }

        fun resolveInstalledNodes(currentReachableIds: Set<String>): List<com.google.android.gms.wearable.Node> {
            return connectedNodes.filter { node ->
                val freshPong = prefs.getWatchPongTimestampMs(node.id) >= pingStartedAtMs
                val recentlyVerified = prefs.isWatchAppVerifiedOnNode(node.id, 12 * 60 * 60 * 1000L)
                currentReachableIds.contains(node.id) ||
                    freshPong ||
                    (allCapNodeIds.contains(node.id) && pingSucceededNodeIds.contains(node.id)) ||
                    (recentlyVerified && pingSucceededNodeIds.contains(node.id))
            }
        }

        var installedNodes = resolveInstalledNodes(reachableCapNodeIds)

        val anyConnectedMissingFreshVerify = connectedNodes.any { node ->
            !reachableCapNodeIds.contains(node.id) &&
                prefs.getWatchPongTimestampMs(node.id) < pingStartedAtMs
        }
        if (installedNodes.isEmpty() || (anyConnectedMissingFreshVerify && pingSucceededNodeIds.isNotEmpty())) {
            repeat(6) {
                delay(150L)
                val updatedReachableIds = try {
                    Wearable.getCapabilityClient(context)
                        .getCapability(CAPABILITY_WATCH_APP_INSTALLED, CapabilityClient.FILTER_REACHABLE)
                        .await()
                        .nodes.map { it.id }.toSet()
                } catch (_: Exception) {
                    reachableCapNodeIds
                }
                installedNodes = resolveInstalledNodes(updatedReachableIds)
                if (installedNodes.size == connectedNodes.size) return@repeat
            }
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

    private fun normalizeEventWindow(
        rawBeginMs: Long,
        rawEndMs: Long,
        allDay: Boolean
    ): Pair<Long, Long> {
        if (!allDay) {
            val safeEnd = if (rawEndMs > rawBeginMs) rawEndMs else rawBeginMs + TimeUnit.MINUTES.toMillis(60)
            return rawBeginMs to safeEnd
        }
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
        val beginMs = localCal.timeInMillis
        localCal.set(Calendar.HOUR_OF_DAY, 23)
        localCal.set(Calendar.MINUTE, 59)
        localCal.set(Calendar.SECOND, 59)
        return beginMs to localCal.timeInMillis
    }

    private fun parseDurationMillis(duration: String?): Long? {
        if (duration.isNullOrBlank()) return null
        val regex = Regex("^P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?$")
        val match = regex.matchEntire(duration.trim()) ?: return null
        val days = match.groupValues[1].toLongOrNull() ?: 0L
        val hours = match.groupValues[2].toLongOrNull() ?: 0L
        val mins = match.groupValues[3].toLongOrNull() ?: 0L
        val secs = match.groupValues[4].toLongOrNull() ?: 0L
        val total = TimeUnit.DAYS.toMillis(days) +
            TimeUnit.HOURS.toMillis(hours) +
            TimeUnit.MINUTES.toMillis(mins) +
            TimeUnit.SECONDS.toMillis(secs)
        return total.takeIf { it > 0L }
    }

    private fun countEventsForCalendar(
        context: Context,
        calendarId: String,
        startMs: Long,
        endMs: Long
    ): Int {
        var count = 0
        val seenEventIds = HashSet<String>()
        val queryStartMs = startMs - TimeUnit.HOURS.toMillis(24)
        val queryEndMs = endMs + TimeUnit.HOURS.toMillis(24)

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, queryStartMs)
        ContentUris.appendId(builder, queryEndMs)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )

        context.contentResolver.query(
            builder.build(),
            projection,
            "${CalendarContract.Instances.CALENDAR_ID} = ? AND (${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ?)",
            arrayOf(calendarId, CalendarContract.Instances.STATUS_CANCELED.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0).toString()
                val rawBeginMs = cursor.getLong(1)
                val rawEndMs = cursor.getLong(2)
                val allDay = cursor.getInt(3) == 1
                val (beginMs, endEventMs) = normalizeEventWindow(rawBeginMs, rawEndMs, allDay)
                if (beginMs > endMs || endEventMs <= startMs) continue
                if (!allDay && beginMs <= startMs - 15 * 60_000L) continue
                seenEventIds.add(eventId)
                count++
            }
        }

        // Fallback seguro para eventos únicos recém-criados (ex.: Samsung Calendar local) que ainda
        // não foram expandidos na tabela Instances no momento da consulta.
        val eventsProjection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY
        )
        val eventsSelection = "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
            "(${CalendarContract.Events.DELETED} IS NULL OR ${CalendarContract.Events.DELETED} = 0) AND " +
            "(${CalendarContract.Events.STATUS} IS NULL OR ${CalendarContract.Events.STATUS} != ?) AND " +
            "(${CalendarContract.Events.RRULE} IS NULL OR ${CalendarContract.Events.RRULE} = '') AND " +
            "(${CalendarContract.Events.RDATE} IS NULL OR ${CalendarContract.Events.RDATE} = '') AND " +
            "${CalendarContract.Events.ORIGINAL_ID} IS NULL AND " +
            "${CalendarContract.Events.DTSTART} <= ? AND " +
            "(${CalendarContract.Events.DTEND} IS NULL OR ${CalendarContract.Events.DTEND} >= ?)"

        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                eventsProjection,
                eventsSelection,
                arrayOf(
                    calendarId,
                    CalendarContract.Events.STATUS_CANCELED.toString(),
                    queryEndMs.toString(),
                    queryStartMs.toString()
                ),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val eventId = cursor.getLong(0).toString()
                    if (seenEventIds.contains(eventId)) continue
                    val rawBeginMs = cursor.getLong(1)
                    val rawEndCol = if (cursor.isNull(2)) 0L else cursor.getLong(2)
                    val durationMs = parseDurationMillis(cursor.getString(3))
                    val rawEndMs = if (rawEndCol > rawBeginMs) {
                        rawEndCol
                    } else {
                        rawBeginMs + (durationMs ?: TimeUnit.MINUTES.toMillis(60))
                    }
                    val allDay = cursor.getInt(4) == 1
                    val (beginMs, endEventMs) = normalizeEventWindow(rawBeginMs, rawEndMs, allDay)
                    if (beginMs > endMs || endEventMs <= startMs) continue
                    if (!allDay && beginMs <= startMs - 15 * 60_000L) continue
                    seenEventIds.add(eventId)
                    count++
                }
            }
        } catch (_: Exception) {
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
        val queryStartMs = nowMs - TimeUnit.HOURS.toMillis(24)
        val queryEndMs = endMs + TimeUnit.HOURS.toMillis(24)

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, queryStartMs)
        ContentUris.appendId(builder, queryEndMs)

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
        val seenEventIds = HashSet<String>()

        context.contentResolver.query(
            builder.build(),
            projection,
            "(${CalendarContract.Instances.STATUS} IS NULL OR ${CalendarContract.Instances.STATUS} != ?)",
            arrayOf(CalendarContract.Instances.STATUS_CANCELED.toString()),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(1).toString()
                if (!selectedIds.contains(calId)) continue

                val eventId = cursor.getLong(0).toString()
                val rawBeginMs = cursor.getLong(4)
                val rawEndMs = cursor.getLong(5)
                val allDay = cursor.getInt(6) == 1

                val (beginMs, endEventMs) = normalizeEventWindow(rawBeginMs, rawEndMs, allDay)

                if (beginMs > endMs || endEventMs <= nowMs) continue
                if (!allDay && beginMs <= nowMs - 15 * 60_000L) continue

                seenEventIds.add(eventId)
                val calMeta = calMap[calId]
                events.add(
                    LocalCalendarEvent(
                        id = eventId,
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

        // Complemento direto em CalendarContract.Events para eventos únicos recém-adicionados que ainda
        // não apareceram em Instances (ou possuam STATUS nulo antes do sync em nuvem).
        val eventsProjection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DURATION,
            CalendarContract.Events.ALL_DAY
        )
        val eventsSelection =
            "(${CalendarContract.Events.DELETED} IS NULL OR ${CalendarContract.Events.DELETED} = 0) AND " +
                "(${CalendarContract.Events.STATUS} IS NULL OR ${CalendarContract.Events.STATUS} != ?) AND " +
                "(${CalendarContract.Events.RRULE} IS NULL OR ${CalendarContract.Events.RRULE} = '') AND " +
                "(${CalendarContract.Events.RDATE} IS NULL OR ${CalendarContract.Events.RDATE} = '') AND " +
                "${CalendarContract.Events.ORIGINAL_ID} IS NULL AND " +
                "${CalendarContract.Events.DTSTART} <= ? AND " +
                "(${CalendarContract.Events.DTEND} IS NULL OR ${CalendarContract.Events.DTEND} >= ?)"

        try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                eventsProjection,
                eventsSelection,
                arrayOf(
                    CalendarContract.Events.STATUS_CANCELED.toString(),
                    queryEndMs.toString(),
                    queryStartMs.toString()
                ),
                "${CalendarContract.Events.DTSTART} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val calId = cursor.getLong(1).toString()
                    if (!selectedIds.contains(calId)) continue

                    val eventId = cursor.getLong(0).toString()
                    if (seenEventIds.contains(eventId)) continue

                    val rawBeginMs = cursor.getLong(4)
                    val rawEndCol = if (cursor.isNull(5)) 0L else cursor.getLong(5)
                    val durationMs = parseDurationMillis(cursor.getString(6))
                    val rawEndMs = if (rawEndCol > rawBeginMs) {
                        rawEndCol
                    } else {
                        rawBeginMs + (durationMs ?: TimeUnit.MINUTES.toMillis(60))
                    }
                    val allDay = cursor.getInt(7) == 1

                    val (beginMs, endEventMs) = normalizeEventWindow(rawBeginMs, rawEndMs, allDay)
                    if (beginMs > endMs || endEventMs <= nowMs) continue
                    if (!allDay && beginMs <= nowMs - 15 * 60_000L) continue

                    seenEventIds.add(eventId)
                    val calMeta = calMap[calId]
                    events.add(
                        LocalCalendarEvent(
                            id = eventId,
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
        } catch (_: Exception) {
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

                    val dominantCondition = mapWmoCodeToPortugueseLabel(dominantWmoCode, maxRain)
                    val resolvedLocationName = resolveDeviceLocationName(context, lat, lon)

                    shiftDetected = prefs.savePhoneFetchedWeatherAndDetectShift(
                        pickedTemps = pickedTemps,
                        pickedLabels = pickedLabels,
                        rainProbPct = maxRain,
                        weatherConditionLabel = dominantCondition,
                        locationName = resolvedLocationName,
                        conditionShiftDetected = false,
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
     * Sincroniza o quadro de tarefas exclusivamente via Google Tasks REST API v1
     * (`https://tasks.googleapis.com/tasks/v1/...`), sem depender de permissões de
     * Acessibilidade, notificações da barra de status ou provedores locais do aparelho.
     * Respeita o limite de 30 dias para tarefas em atraso e a hierarquia de 3 níveis:
     * 1º TODAY (agendadas para hoje, com hora HH:mm)
     * 2º TOMORROW (agendadas para amanhã, com hora HH:mm)
     * 3º UNDATED / OVERDUE (sem data/hora ou em atraso de no máximo 30 dias, atrasadas em vermelho)
     */
    fun buildMergedTasksJsonForWatch(context: Context, prefs: SyncPreferences, nowMs: Long): String {
        if (!prefs.isTasksSyncEnabled()) {
            prefs.saveTasksJson("[]")
            return "[]"
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return prefs.getTasksJson()
        }
        return GoogleTasksApiClient.fetchTasksFromGoogleApi(context, prefs, nowMs)
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

        // Atualiza os dados reais de sono via Android Health Connect antes de montar o pacote para o relógio
        try {
            HealthConnectSleepReader.refreshPermissionAndReadTodaySleep(context, nowMs)
        } catch (_: Exception) {
        }

        // Verifica se a atualização periódica de clima está vencida antes de montar o payload para o relógio
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            try {
                fetchPhoneHourlyWeatherIfNeeded(context, forceRefresh = false)
            } catch (_: Exception) {
            }
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
        val sleepShowScore = prefs.isSleepScoreVisible()
        val sleepHasDataToday = prefs.hasVerifiedSleepDataForToday(nowMs)
        val sleepDurationMin = prefs.getSleepDurationMinutes(nowMs)
        val sleepScore = prefs.getSleepScore(nowMs)
        val sleepBedtimeHHMM = prefs.getSleepBedtimeHHMM()
        val sleepWakeHHMM = prefs.getSleepWakeHHMM()
        val sleepStagesCsv = prefs.getSleepStagesCsv()
        val sleepWakeTsMs = prefs.getSleepWakeTimestampMs()
        val stepsToday = prefs.getStepsToday()
        val stepsGoal = prefs.getStepsDailyGoal()
        val stepsWeekCsv = prefs.getStepsWeekCsv()
        val stepsWeekLabelsCsv = prefs.getStepsWeekLabelsCsv()
        val tasksSyncEnabled = prefs.isTasksSyncEnabled()
        // Sincroniza imediatamente as tarefas classificadas (Hoje c/ hora -> Amanhã c/ hora -> Sem data e Atrasadas em vermelho)
        val tasksJson = buildMergedTasksJsonForWatch(context, prefs, nowMs)
        val tasksAlertTsMs = prefs.getTasksAlertTimestampMs()
        val highFreq15Min = prefs.isHighFreq15MinRuleEnabled()
        val updateOnWake = prefs.isUpdateOnScreenWakeEnabled()
        val updateOnTap = prefs.isUpdateOnCanvasTapEnabled()
        val realtimeHighTierMin = prefs.getRealtimeSyncHighTierMinutes()
        val realtimeLowTierMin = prefs.getRealtimeSyncLowTierMinutes()

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

        val autoTriggerAgendaTasksEnabled = prefs.isAutoTriggerAgendaTasksEnabled()
        val autoTriggerAgendaTasksLeadMin = prefs.getAutoTriggerAgendaTasksLeadMin()
        val autoTriggerStepsEnabled = prefs.isAutoTriggerStepsEnabled()
        val autoTriggerStepsMilestone = prefs.getAutoTriggerStepsMilestone()
        val autoTriggerSunEnabled = prefs.isAutoTriggerSunEnabled()
        val autoTriggerSunLeadMin = prefs.getAutoTriggerSunLeadMin()
        val autoTriggerSleepEnabled = prefs.isAutoTriggerSleepEnabled()
        val autoTriggerSleepMode = prefs.getAutoTriggerSleepMode()
        val autoTriggerWeatherEnabled = prefs.isAutoTriggerWeatherEnabled()
        val autoTriggerWeatherMode = prefs.getAutoTriggerWeatherMode()
        val autoTriggerBatteryEnabled = prefs.isAutoTriggerBatteryEnabled()
        val autoTriggerBatteryMode = prefs.getAutoTriggerBatteryMode()
        val batteryGraphWindowHours = prefs.getBatteryGraphWindowHours()
        val syncIntervalMinutes = prefs.getSyncIntervalMinutes().toInt().coerceIn(15, 120)

        val payloadJson = JSONObject().apply {
            put("events", jsonArray)
            put("sync_timestamp_ms", nowMs)
            put("sync_interval_minutes", syncIntervalMinutes)
            put("horizon_days", horizonDays)
            put("progress_window_minutes", progressWindowMinutes)
            put("context_window_mode", contextMode)
            put("auto_primary_context", autoPrimaryContext)
            put("unified_canvas_hold_min", unifiedCanvasHoldMin)
            put("notify_on_canvas_change", notifyOnCanvasChange)
            put("notify_agenda_15min", notifyAgenda15Min)
            put("auto_fallback_agenda_to_tasks", autoFallbackAgendaToTasks)
            put("auto_trigger_agenda_tasks_enabled", autoTriggerAgendaTasksEnabled)
            put("auto_trigger_agenda_tasks_lead_min", autoTriggerAgendaTasksLeadMin)
            put("auto_trigger_steps_enabled", autoTriggerStepsEnabled)
            put("auto_trigger_steps_milestone", autoTriggerStepsMilestone)
            put("auto_trigger_sun_enabled", autoTriggerSunEnabled)
            put("auto_trigger_sun_lead_min", autoTriggerSunLeadMin)
            put("auto_trigger_sleep_enabled", autoTriggerSleepEnabled)
            put("auto_trigger_sleep_mode", autoTriggerSleepMode)
            put("auto_trigger_weather_enabled", autoTriggerWeatherEnabled)
            put("auto_trigger_weather_mode", autoTriggerWeatherMode)
            put("auto_trigger_battery_enabled", autoTriggerBatteryEnabled)
            put("auto_trigger_battery_mode", autoTriggerBatteryMode)
            put("battery_graph_window_hours", batteryGraphWindowHours)
            put("auto_agenda_exposure_min", autoAgendaExposureMin)
            put("auto_weather_exposure_min", autoWeatherExposureMin)
            put("auto_sun_exposure_min", autoSunExposureMin)
            put("auto_sleep_exposure_min", autoSleepExposureMin)
            put("sleep_sync_enabled", sleepSyncEnabled)
            put("sleep_show_score", sleepShowScore)
            put("sleep_has_data_today", sleepHasDataToday)
            put("sleep_duration_min", sleepDurationMin)
            put("sleep_score", sleepScore)
            put("sleep_bedtime_hhmm", sleepBedtimeHHMM)
            put("sleep_wake_hhmm", sleepWakeHHMM)
            put("sleep_stages_csv", sleepStagesCsv)
            put("sleep_wake_ts_ms", sleepWakeTsMs)
            put("steps_today", stepsToday)
            put("steps_goal", stepsGoal)
            put("steps_week_csv", stepsWeekCsv)
            put("steps_week_labels_csv", stepsWeekLabelsCsv)
            put("tasks_sync_enabled", tasksSyncEnabled)
            put("tasks_json", tasksJson)
            put("tasks_alert_timestamp_ms", tasksAlertTsMs)
            put("high_freq_15min_rule", highFreq15Min)
            put("update_on_screen_wake", updateOnWake)
            put("update_on_canvas_tap", updateOnTap)
            put("realtime_sync_high_tier_min", realtimeHighTierMin)
            put("realtime_sync_low_tier_min", realtimeLowTierMin)
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
        }.toString()

        try {
            val putDataMapReq = PutDataMapRequest.create(DATA_LAYER_PATH).apply {
                dataMap.putString("events_json", jsonArray.toString())
                dataMap.putLong("sync_timestamp_ms", nowMs)
                dataMap.putInt("sync_interval_minutes", syncIntervalMinutes)
                dataMap.putInt("horizon_days", horizonDays)
                dataMap.putInt("progress_window_minutes", progressWindowMinutes)
                dataMap.putString("context_window_mode", contextMode)
                dataMap.putString("auto_primary_context", autoPrimaryContext)
                dataMap.putInt("unified_canvas_hold_min", unifiedCanvasHoldMin)
                dataMap.putBoolean("notify_on_canvas_change", notifyOnCanvasChange)
                dataMap.putBoolean("notify_agenda_15min", notifyAgenda15Min)
                dataMap.putBoolean("auto_fallback_agenda_to_tasks", autoFallbackAgendaToTasks)
                dataMap.putBoolean("auto_trigger_agenda_tasks_enabled", autoTriggerAgendaTasksEnabled)
                dataMap.putInt("auto_trigger_agenda_tasks_lead_min", autoTriggerAgendaTasksLeadMin)
                dataMap.putBoolean("auto_trigger_steps_enabled", autoTriggerStepsEnabled)
                dataMap.putString("auto_trigger_steps_milestone", autoTriggerStepsMilestone)
                dataMap.putBoolean("auto_trigger_sun_enabled", autoTriggerSunEnabled)
                dataMap.putInt("auto_trigger_sun_lead_min", autoTriggerSunLeadMin)
                dataMap.putBoolean("auto_trigger_sleep_enabled", autoTriggerSleepEnabled)
                dataMap.putString("auto_trigger_sleep_mode", autoTriggerSleepMode)
                dataMap.putBoolean("auto_trigger_weather_enabled", autoTriggerWeatherEnabled)
                dataMap.putString("auto_trigger_weather_mode", autoTriggerWeatherMode)
                dataMap.putBoolean("auto_trigger_battery_enabled", autoTriggerBatteryEnabled)
                dataMap.putString("auto_trigger_battery_mode", autoTriggerBatteryMode)
                dataMap.putInt("battery_graph_window_hours", batteryGraphWindowHours)
                dataMap.putInt("auto_agenda_exposure_min", autoAgendaExposureMin)
                dataMap.putInt("auto_weather_exposure_min", autoWeatherExposureMin)
                dataMap.putInt("auto_sun_exposure_min", autoSunExposureMin)
                dataMap.putInt("auto_sleep_exposure_min", autoSleepExposureMin)
                dataMap.putBoolean("sleep_sync_enabled", sleepSyncEnabled)
                dataMap.putBoolean("sleep_show_score", sleepShowScore)
                dataMap.putBoolean("sleep_has_data_today", sleepHasDataToday)
                dataMap.putInt("sleep_duration_min", sleepDurationMin)
                dataMap.putInt("sleep_score", sleepScore)
                dataMap.putString("sleep_bedtime_hhmm", sleepBedtimeHHMM)
                dataMap.putString("sleep_wake_hhmm", sleepWakeHHMM)
                dataMap.putString("sleep_stages_csv", sleepStagesCsv)
                dataMap.putLong("sleep_wake_ts_ms", sleepWakeTsMs)
                dataMap.putInt("steps_today", stepsToday)
                dataMap.putInt("steps_goal", stepsGoal)
                dataMap.putString("steps_week_csv", stepsWeekCsv)
                dataMap.putString("steps_week_labels_csv", stepsWeekLabelsCsv)
                dataMap.putBoolean("tasks_sync_enabled", tasksSyncEnabled)
                dataMap.putString("tasks_json", tasksJson)
                dataMap.putLong("tasks_alert_timestamp_ms", tasksAlertTsMs)
                dataMap.putBoolean("high_freq_15min_rule", highFreq15Min)
                dataMap.putBoolean("update_on_screen_wake", updateOnWake)
                dataMap.putBoolean("update_on_canvas_tap", updateOnTap)
                dataMap.putInt("realtime_sync_high_tier_min", realtimeHighTierMin)
                dataMap.putInt("realtime_sync_low_tier_min", realtimeLowTierMin)
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
            }
            Wearable.getDataClient(context)
                .putDataItem(putDataMapReq.asPutDataRequest().setUrgent())
                .await()

            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            val payloadBytes = payloadJson.toByteArray(Charsets.UTF_8)
            val msgClient = Wearable.getMessageClient(context)
            val wakeBytes = nowMs.toString().toByteArray(Charsets.UTF_8)

            val sentNodes = HashSet<String>()
            val failedNodes = LinkedHashMap<String, String>()

            for (node in nodes) {
                var deliveredDirect = false
                if (payloadBytes.size <= MAX_MESSAGE_PAYLOAD_BYTES) {
                    try {
                        msgClient.sendMessage(
                            node.id,
                            MESSAGE_FORCE_SYNC_PATH,
                            payloadBytes
                        ).await()
                        sentNodes.add(node.id)
                        deliveredDirect = true
                    } catch (e: Exception) {
                        failedNodes[node.id] = e.javaClass.simpleName
                    }
                }
                if (!deliveredDirect) {
                    try {
                        msgClient.sendMessage(
                            node.id,
                            MESSAGE_WAKE_AND_PULL_PATH,
                            wakeBytes
                        ).await()
                        sentNodes.add(node.id)
                        failedNodes.remove(node.id)
                    } catch (e: Exception) {
                        if (!failedNodes.containsKey(node.id)) {
                            failedNodes[node.id] = e.javaClass.simpleName
                        }
                    }
                }
            }

            if (nodes.isNotEmpty() && sentNodes.isNotEmpty()) {
                repeat(8) {
                    val allAcked = nodes.all { node ->
                        prefs.getLastSyncAckTimestampMs(node.id) >= nowMs
                    }
                    if (allAcked) return@repeat
                    delay(150L)
                }
            }

            val ackedNames = mutableListOf<String>()
            val pendingAckNames = mutableListOf<String>()
            val failedNames = mutableListOf<String>()
            for (node in nodes) {
                val label = prefs.getWatchModelForNode(node.id).takeIf { it.isNotBlank() } ?: node.displayName
                when {
                    prefs.getLastSyncAckTimestampMs(node.id) >= nowMs -> ackedNames.add(label)
                    sentNodes.contains(node.id) -> pendingAckNames.add(label)
                    else -> failedNames.add(label)
                }
            }

            val connReport = if (nodes.isEmpty() || (ackedNames.isEmpty() && pendingAckNames.isEmpty())) {
                checkWearConnectionAndAppStatus(context)
            } else {
                null
            }
            val allConfirmed = nodes.isNotEmpty() && ackedNames.size == nodes.size
            prefs.setLastSyncDeliveryConfirmed(allConfirmed || (ackedNames.isNotEmpty() && failedNames.isEmpty()))

            val nodeStatus = when {
                nodes.isEmpty() -> {
                    if (connReport != null && !connReport.bluetoothEnabled) "Bluetooth desativado no smartphone"
                    else "Nenhum relógio conectado"
                }
                ackedNames.isNotEmpty() && pendingAckNames.isEmpty() && failedNames.isEmpty() ->
                    "Confirmado por ${ackedNames.joinToString()}"
                ackedNames.isNotEmpty() ->
                    "Confirmado: ${ackedNames.joinToString()} • Pendente/Falha: ${(pendingAckNames + failedNames).joinToString()}"
                pendingAckNames.isNotEmpty() && failedNames.isEmpty() ->
                    "Enviado para ${pendingAckNames.joinToString()} (aguardando confirmação do app no relógio)"
                connReport?.state == WearConnectionState.WEARABLE_CONNECTED_APP_MISSING ->
                    "Wearable conectado (${connReport.wearableDisplayNames.joinToString()}), mas o app não respondeu no relógio"
                else ->
                    "Falha ao enviar para ${(failedNames + pendingAckNames).joinToString()}"
            }
            prefs.recordLastSync(nowMs, events.size, selectedCalendarsCount, triggerSource, nodeStatus)
        } catch (_: Exception) {
            prefs.setLastSyncDeliveryConfirmed(false)
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
        val allFuture = events.filter {
            it.endEpochMs > nowMs && (it.allDay || it.startEpochMs > nowMs - 15 * 60_000L)
        }
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
}
