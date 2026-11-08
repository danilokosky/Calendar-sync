package com.chronowear.wear.data

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import androidx.wear.tiles.TileService
import com.chronowear.wear.R
import com.chronowear.wear.complication.ChronoWearComplicationUpdater
import com.chronowear.wear.tile.UpNextThreeEventsTileService
import com.chronowear.wear.ui.WearMainActivity
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
    private const val CHANNEL_CANVAS_SHIFT_ID = "chronowear_canvas_shift_channel"
    private const val NOTIF_ID_CANVAS_SHIFT = 4102
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var screenWakeReceiverRegistered = false
    private var lastScreenWakeUpdateMs = 0L

    /**
     * Exibe uma notificação no Relógio quando o app recebe ou detecta uma mudança de contexto
     * de prioridade máxima (Clima, Agenda faltando 15m, Nascer/Pôr do Sol, Dormir/Acordar)
     * que força a mudança do Canvas.
     */
    fun notifyWatchOnForcedCanvasChangeIfNeeded(
        context: Context,
        explicitReasonOverride: String? = null
    ) {
        val appCtx = context.applicationContext
        val repo = WatchAutonomousRepository.getInstance(appCtx)
        if (!repo.isNotifyOnCanvasChangeEnabled()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (appCtx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val nowMs = System.currentTimeMillis()
        val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        val priorityPair = repo.evaluatePriorityContextOverride(nowMs, upcomingThree) ?: return
        val (forcedMode, autoReason) = priorityPair

        // Para compromissos da Agenda faltando 15 minutos, respeita a opção dedicada de ativar/desativar a notificação
        if (forcedMode == WatchAutonomousRepository.MODE_EVENTS && !repo.isNotifyAgenda15MinEnabled()) {
            return
        }

        val nextEventId = upcomingThree.firstOrNull()?.id ?: ""
        val notificationDedupKey = if (forcedMode == WatchAutonomousRepository.MODE_EVENTS) {
            "EVENTS_15M_$nextEventId"
        } else {
            forcedMode
        }

        val lastMode = repo.getLastNotifiedForcedContext()
        val lastTs = repo.getLastNotifiedForcedContextTimestampMs()
        // Evita repetir a mesma notificação para o mesmo compromisso de 15m ou mesmo gatilho em menos de 10 minutos
        if (notificationDedupKey == lastMode &&
            (forcedMode == WatchAutonomousRepository.MODE_EVENTS || (nowMs - lastTs) < TimeUnit.MINUTES.toMillis(10L)) &&
            explicitReasonOverride == null
        ) {
            return
        }
        repo.recordNotifiedForcedContext(notificationDedupKey, nowMs)

        val modeTitle = when (forcedMode) {
            WatchAutonomousRepository.MODE_WEATHER -> "Canvas • Clima"
            WatchAutonomousRepository.MODE_EVENTS -> "Canvas • Agenda (15 min)"
            WatchAutonomousRepository.MODE_TASKS -> "Canvas • Tarefas"
            WatchAutonomousRepository.MODE_SUN -> "Canvas • Nascer e Pôr do Sol"
            WatchAutonomousRepository.MODE_SLEEP -> "Canvas • Sono"
            WatchAutonomousRepository.MODE_STEPS -> "Canvas • Passos"
            else -> "Canvas Atualizado"
        }
        val holdMins = if (forcedMode == WatchAutonomousRepository.MODE_EVENTS) {
            15
        } else {
            repo.getUnifiedCanvasHoldMinutes()
        }
        val primaryLabel = when (repo.getAutoPrimaryContext()) {
            WatchAutonomousRepository.MODE_EVENTS -> "Agenda"
            WatchAutonomousRepository.MODE_TASKS -> "Tarefas"
            WatchAutonomousRepository.MODE_WEATHER -> "Clima"
            WatchAutonomousRepository.MODE_SUN -> "Sol"
            WatchAutonomousRepository.MODE_STEPS -> "Passos"
            WatchAutonomousRepository.MODE_SLEEP -> "Sono"
            else -> "Padrão"
        }
        val bodyText = (explicitReasonOverride ?: autoReason) +
            " • Fixo por ${holdMins}m (retorna p/ $primaryLabel)"

        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_CANVAS_SHIFT_ID,
                    "Mudança Prioritária do Canvas",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Avulso quando Clima, Agenda (15m), Sol ou Sono assumem o Canvas do relógio"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }

            val openIntent = Intent(appCtx, WearMainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPi = PendingIntent.getActivity(
                appCtx,
                4103,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(appCtx, CHANNEL_CANVAS_SHIFT_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(appCtx)
            }

            val notification = builder
                .setSmallIcon(R.drawable.ic_launcher_chronowear)
                .setContentTitle(modeTitle)
                .setContentText(bodyText)
                .setStyle(Notification.BigTextStyle().bigText(bodyText))
                .setAutoCancel(true)
                .setContentIntent(contentPi)
                .build()

            nm.notify(NOTIF_ID_CANVAS_SHIFT, notification)
        } catch (_: Exception) {
        }
    }

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val repo = WatchAutonomousRepository.getInstance(context)
            val nowMs = System.currentTimeMillis()

            if (action == Intent.ACTION_TIME_TICK) {
                runStandaloneWatchWeatherCheckIfDue(context, forceRefresh = false)
                notifyWatchOnForcedCanvasChangeIfNeeded(context)
                ChronoWearComplicationUpdater.requestUpdateAllSlots(context)
                scheduleNextTransition(context)
                return
            }

            if (action != Intent.ACTION_SCREEN_ON) return
            if (!repo.isUpdateOnScreenWakeEnabled()) return

            if (nowMs - lastScreenWakeUpdateMs >= 15_000L) {
                lastScreenWakeUpdateMs = nowMs
                runStandaloneWatchWeatherCheckIfDue(context, forceRefresh = false)
                ChronoWearComplicationUpdater.requestUpdateAllSlots(context)
                scheduleNextTransition(context)
            }
        }
    }

    fun ensureScreenWakeListenerRegistered(context: Context) {
        if (screenWakeReceiverRegistered) return
        try {
            val appCtx = context.applicationContext
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_TIME_TICK)
            }
            appCtx.registerReceiver(screenOnReceiver, filter)
            screenWakeReceiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun resolveWatchCoordinates(context: Context): Pair<Double, Double> {
        val repo = WatchAutonomousRepository.getInstance(context)
        try {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                val providers = listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
                val bestLoc = providers.mapNotNull { provider ->
                    try {
                        lm?.getLastKnownLocation(provider)
                    } catch (_: Exception) {
                        null
                    }
                }.maxByOrNull { it.time }

                if (bestLoc != null && (bestLoc.latitude != 0.0 || bestLoc.longitude != 0.0)) {
                    repo.saveWeatherCoordinates(bestLoc.latitude, bestLoc.longitude)
                    return bestLoc.latitude to bestLoc.longitude
                }
            }
        } catch (_: Exception) {
        }
        return repo.getSavedWeatherCoordinates() ?: (-23.5505 to -46.6333)
    }

    private fun resolveWatchLocationName(context: Context, lat: Double, lon: Double): String {
        val repo = WatchAutonomousRepository.getInstance(context)
        try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
            val addr = addresses?.firstOrNull()
            if (addr != null) {
                val city = addr.locality ?: addr.subAdminArea ?: addr.featureName ?: ""
                val state = addr.adminArea ?: ""
                if (city.isNotBlank()) {
                    val formatted = WatchAutonomousRepository.formatCompactWeatherLocation(city, state)
                    repo.setWeatherLocationName(formatted)
                    return formatted
                }
            }
        } catch (_: Exception) {
        }
        return repo.getWeatherLocationName()
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
     * Serviço Stand-Alone Unificado de Clima + Nascer/Pôr do Sol no Relógio:
     * Quando atualiza a localização para o Clima, deduz e atualiza conjuntamente os horários
     * de Nascer e Pôr do Sol em uma única operação fixa.
     */
    fun runStandaloneWatchWeatherCheckIfDue(context: Context, forceRefresh: Boolean = false) {
        val appCtx = context.applicationContext
        val repo = WatchAutonomousRepository.getInstance(appCtx)
        if (!forceRefresh && !repo.isWatchStandaloneWeatherEnabled()) return

        val (lat, lon) = resolveWatchCoordinates(appCtx)
        // Sempre recalcula astronomicamente os horários de Nascer e Pôr do Sol com base na mesma localização do Clima
        val (astroRise, astroSet) = calculateAstronomicalSunriseSunset(lat, lon)
        repo.saveSolarTimes(astroRise, astroSet)

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

                    // Atualiza conjuntamente o Nascer e Pôr do Sol a partir da mesma resposta da localização do Clima
                    val daily = root.optJSONObject("daily")
                    val riseRaw = daily?.optJSONArray("sunrise")?.optString(0, "") ?: ""
                    val setRaw = daily?.optJSONArray("sunset")?.optString(0, "") ?: ""
                    val riseHHMM = riseRaw.substringAfter("T", "").take(5)
                    val setHHMM = setRaw.substringAfter("T", "").take(5)
                    if (riseHHMM.length == 5 && setHHMM.length == 5) {
                        repo.saveSolarTimes(riseHHMM, setHHMM)
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
                        val resolvedLocationName = resolveWatchLocationName(appCtx, lat, lon)

                        val shiftDetected = repo.saveStandaloneWeatherAndDetectShift(
                            pickedTemps = pickedTemps,
                            pickedLabels = pickedLabels,
                            maxRainProbPct = maxRain,
                            weatherConditionLabel = dominantCondition,
                            locationName = resolvedLocationName,
                            conditionShiftDetected = conditionShiftAcrossHours,
                            nowMs = nowMs
                        )

                        if (shiftDetected) {
                            notifyWatchOnForcedCanvasChangeIfNeeded(appCtx)
                        }

                        if (forceRefresh ||
                            shiftDetected ||
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

        val tapOverrideReturnMs = repo.getTapOverrideExpiryEpochMs(nowMs)

        // Sempre que houver compromissos futuros sincronizados no cache do relógio,
        // desperta a cada 1 minuto (e exatamente na transição de início do compromisso)
        // para avançar a contagem regressiva e substituir compromissos encerrados sem depender de nova sincronização!
        val eventWakeUpMs = if (nextEvent == null) {
            Long.MAX_VALUE
        } else {
            val fifteenMinBeforeMs = nextEvent.startEpochMs - TimeUnit.MINUTES.toMillis(15L)
            val nextMinuteTickMs = nowMs + 60_000L
            val exactEventBoundaryMs = (nextEvent.startEpochMs + 500L).coerceAtLeast(nowMs + 1000L)

            if (nowMs < fifteenMinBeforeMs && fifteenMinBeforeMs < nextMinuteTickMs) {
                fifteenMinBeforeMs
            } else {
                minOf(nextMinuteTickMs, exactEventBoundaryMs)
            }
        }

        val nextWakeUpMs = minOf(eventWakeUpMs, minOf(standaloneWeatherWakeMs, tapOverrideReturnMs))
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
            // Toque direto na imagem do Canvas na Watch Face: avança imediatamente para o próximo Canvas
            // e agenda o retorno automático ao Canvas Padrão Principal após o Tempo Unificado configurado!
            val repo = WatchAutonomousRepository.getInstance(context)
            val nowMs = System.currentTimeMillis()
            val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
            repo.cycleNextVisualCanvasOnTap(nowMs, upcomingThree)
            ChronoWearComplicationUpdater.requestUpdateAllSlots(context)
            AutonomousCountdownScheduler.scheduleNextTransition(context)
            context.sendBroadcast(
                Intent(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
                    .setPackage(context.packageName)
            )
            return
        }

        AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(context, forceRefresh = false)
        AutonomousCountdownScheduler.notifyWatchOnForcedCanvasChangeIfNeeded(context)

        ChronoWearComplicationUpdater.requestUpdateAllSlots(context)

        TileService.getUpdater(context)
            .requestUpdate(UpNextThreeEventsTileService::class.java)

        AutonomousCountdownScheduler.scheduleNextTransition(context)
    }
}
