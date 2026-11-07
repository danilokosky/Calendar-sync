package com.chronowear.wear.data

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.wear.tiles.TileService
import com.chronowear.wear.complication.ChronoWearComplicationUpdater
import com.chronowear.wear.tile.UpNextThreeEventsTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

object AutonomousCountdownScheduler {

    const val ACTION_TRANSITION = "com.chronowear.wear.ACTION_AUTONOMOUS_EVENT_TRANSITION"
    const val ACTION_TAP_CYCLE_CONTEXT = "com.chronowear.wear.ACTION_TAP_CYCLE_CONTEXT"
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var screenWakeReceiverRegistered = false
    private var lastScreenWakeUpdateMs = 0L

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            val repo = WatchAutonomousRepository.getInstance(context)
            if (!repo.isUpdateOnScreenWakeEnabled()) return

            val nowMs = System.currentTimeMillis()
            if (nowMs - lastScreenWakeUpdateMs >= 25_000L) {
                lastScreenWakeUpdateMs = nowMs
                runStandaloneWatchWeatherCheckIfDue(context, forceRefresh = false)
                ChronoWearComplicationUpdater.requestUpdateAllSlots(context)
            }
        }
    }

    fun ensureScreenWakeListenerRegistered(context: Context) {
        if (screenWakeReceiverRegistered) return
        try {
            val appCtx = context.applicationContext
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            appCtx.registerReceiver(screenOnReceiver, filter)
            screenWakeReceiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun resolveWatchCoordinates(context: Context): Pair<Double, Double> {
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

    private fun mapWmoCodeToPortugueseLabel(wmoCode: Int, rainProbPct: Int): String {
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
     * Serviços Stand-Alone de Clima e Solar no Relógio:
     * Busca diretamente do relógio (de hora em hora ou no intervalo configurado) as previsões
     * de temperatura, chuva, condição meteorológica e horários de nascer/pôr do sol.
     */
    fun runStandaloneWatchWeatherCheckIfDue(context: Context, forceRefresh: Boolean = false) {
        val appCtx = context.applicationContext
        val repo = WatchAutonomousRepository.getInstance(appCtx)
        val (lat, lon) = resolveWatchCoordinates(appCtx)

        if (repo.isWatchStandaloneSolarEnabled()) {
            val (astroRise, astroSet) = calculateAstronomicalSunriseSunset(lat, lon)
            repo.saveSolarTimes(astroRise, astroSet)
        }

        if (!forceRefresh && !repo.isWatchStandaloneWeatherEnabled()) return

        val nowMs = System.currentTimeMillis()
        val intervalMs = TimeUnit.MINUTES.toMillis(repo.getWatchStandaloneWeatherIntervalMinutes().toLong())
        val lastFetch = repo.getLastStandaloneWeatherFetchMs()
        if (!forceRefresh && lastFetch > 0L && (nowMs - lastFetch) < intervalMs) return

        ioScope.launch {
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

                    if (repo.isWatchStandaloneSolarEnabled()) {
                        val daily = root.optJSONObject("daily")
                        val riseRaw = daily?.optJSONArray("sunrise")?.optString(0, "") ?: ""
                        val setRaw = daily?.optJSONArray("sunset")?.optString(0, "") ?: ""
                        val riseHHMM = riseRaw.substringAfter("T", "").take(5)
                        val setHHMM = setRaw.substringAfter("T", "").take(5)
                        if (riseHHMM.length == 5 && setHHMM.length == 5) {
                            repo.saveSolarTimes(riseHHMM, setHHMM)
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

                        val shiftDetected = repo.saveStandaloneWeatherAndDetectShift(
                            pickedTemps = pickedTemps,
                            pickedLabels = pickedLabels,
                            maxRainProbPct = maxRain,
                            weatherConditionLabel = dominantCondition,
                            conditionShiftDetected = conditionShiftAcrossHours,
                            nowMs = nowMs
                        )

                        if (shiftDetected ||
                            repo.getContextWindowMode() == WatchAutonomousRepository.MODE_WEATHER ||
                            repo.getContextWindowMode() == WatchAutonomousRepository.MODE_SUN
                        ) {
                            ChronoWearComplicationUpdater.requestUpdateAllSlots(appCtx)
                            appCtx.sendBroadcast(
                                Intent(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
                                    .setPackage(appCtx.packageName)
                            )
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    fun scheduleNextTransition(context: Context) {
        ensureScreenWakeListenerRegistered(context)

        val repo = WatchAutonomousRepository.getInstance(context)
        val nowMs = System.currentTimeMillis()
        val nextEvent = repo.getNextThreeUpcomingEvents(nowMs).firstOrNull()

        val standaloneWeatherWakeMs = if (repo.isWatchStandaloneWeatherEnabled() || repo.isWatchStandaloneSolarEnabled()) {
            nowMs + TimeUnit.MINUTES.toMillis(repo.getWatchStandaloneWeatherIntervalMinutes().toLong())
        } else {
            Long.MAX_VALUE
        }

        val eventWakeUpMs = if (nextEvent == null) {
            Long.MAX_VALUE
        } else {
            val progressWindowMs = TimeUnit.MINUTES.toMillis(repo.getProgressWindowMinutes().toLong())
            val progressStartEpochMs = nextEvent.startEpochMs - progressWindowMs
            val fifteenMinBeforeMs = nextEvent.startEpochMs - TimeUnit.MINUTES.toMillis(15L)
            val highFreq15MinEnabled = repo.isHighFreq15MinRuleEnabled()

            when {
                // Dentro da janela da barra de progresso (30m, 1h, 2h ou 3h antes) ou nos 15m finais:
                // desperta a cada 1 minuto para fazer a barra crescer de 0% a 100% até a hora do evento
                nowMs in progressStartEpochMs until nextEvent.startEpochMs -> {
                    minOf(nowMs + 60_000L, nextEvent.startEpochMs + 1000L)
                }
                highFreq15MinEnabled && nowMs in fifteenMinBeforeMs until nextEvent.startEpochMs -> {
                    minOf(nowMs + 60_000L, nextEvent.startEpochMs + 1000L)
                }
                nowMs < progressStartEpochMs -> {
                    progressStartEpochMs
                }
                else -> {
                    nextEvent.startEpochMs + 1000L
                }
            }
        }

        val nextWakeUpMs = minOf(eventWakeUpMs, standaloneWeatherWakeMs)
        if (nextWakeUpMs == Long.MAX_VALUE) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, AutonomousTransitionReceiver::class.java).apply {
            action = ACTION_TRANSITION
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextWakeUpMs,
                pendingIntent
            )
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextWakeUpMs,
                pendingIntent
            )
        }
    }
}

class AutonomousTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AutonomousCountdownScheduler.ACTION_TAP_CYCLE_CONTEXT) {
            // Toque direto na imagem do Canvas na Watch Face: avança imediatamente para o próximo Canvas!
            val repo = WatchAutonomousRepository.getInstance(context)
            val nowMs = System.currentTimeMillis()
            val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
            repo.cycleNextVisualCanvasOnTap(nowMs, upcomingThree)
            ChronoWearComplicationUpdater.requestUpdateAllSlots(context)
            context.sendBroadcast(
                Intent(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
                    .setPackage(context.packageName)
            )
            return
        }

        AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(context, forceRefresh = false)

        ChronoWearComplicationUpdater.requestUpdateAllSlots(context)

        TileService.getUpdater(context)
            .requestUpdate(UpNextThreeEventsTileService::class.java)

        AutonomousCountdownScheduler.scheduleNextTransition(context)
    }
}
