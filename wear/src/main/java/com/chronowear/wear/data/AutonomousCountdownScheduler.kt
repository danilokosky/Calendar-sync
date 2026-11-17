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
import com.chronowear.wear.R
import com.chronowear.wear.complication.ChronoWearComplicationUpdater
import com.chronowear.wear.ui.WearMainActivity
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
    const val ACTION_TAP_CYCLE_PILL_CONTEXT = "com.chronowear.wear.ACTION_TAP_CYCLE_PILL_CONTEXT"
    private const val CHANNEL_CANVAS_SHIFT_ID = "chronowear_canvas_shift_channel"
    private const val NOTIF_ID_CANVAS_SHIFT = 4102
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receiverLock = Any()
    private var screenWakeReceiverRegistered = false
    private var lastScreenWakeUpdateMs = 0L
    @Volatile
    private var lastPhoneSyncRequestMs = 0L
    @Volatile
    private var weatherFetchInFlight = false
    @Volatile
    private var lastScheduledAlarmWakeMs = -1L

    /**
     * Solicita imediatamente ao smartphone conectado uma sincronização completa
     * (`/chronowear/request_sync_from_watch`) ao tocar para alternar qualquer Canvas
     * ou ao acender a tela do relógio (quando as respectivas opções estiverem ativadas).
     */
    fun requestPhoneSyncNow(context: Context, minThrottleMs: Long = 5_000L) {
        val nowMs = System.currentTimeMillis()
        if ((nowMs - lastPhoneSyncRequestMs) < minThrottleMs) return
        lastPhoneSyncRequestMs = nowMs
        val appCtx = context.applicationContext

        ioScope.launch {
            try {
                PhoneDataListenerService.pullLatestDataItemIfNewer(appCtx)
            } catch (_: Exception) {
            }
            try {
                val nodes = Wearable.getNodeClient(appCtx).connectedNodes.await()
                val msgClient = Wearable.getMessageClient(appCtx)
                val payload = "sync_req:$nowMs".toByteArray(Charsets.UTF_8)
                for (node in nodes) {
                    try {
                        msgClient.sendMessage(
                            node.id,
                            "/chronowear/request_sync_from_watch",
                            payload
                        ).await()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Avalia a sincronização em tempo real (disparada ao tocar no Canvas ou ao ligar a tela do relógio)
     * aplicando os 2 Tiers de prioridade para economia inteligente de bateria:
     * - Tier Alta Prioridade (Agenda, Tarefas e Clima): janela padrão de 2 minutos
     * - Tier Baixa Prioridade (Sol, Sono, Passos e Bateria): janela padrão de 10 minutos
     * Somente acorda o smartphone via `/chronowear/request_sync_from_watch` se o tempo desde a última
     * sincronização for maior ou igual à janela do Tier do Canvas ativo no visor.
     */
    fun requestPhoneSyncForActiveCanvasIfDue(context: Context) {
        val nowMs = System.currentTimeMillis()
        val appCtx = context.applicationContext
        val repo = WatchAutonomousRepository.getInstance(appCtx)
        val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        val activeCanvasMode = repo.resolveEffectiveContextMode(nowMs, upcomingThree)
        val tierMinutes = repo.getRealtimeSyncTierMinutesForMode(activeCanvasMode)
        val requiredWindowMs = TimeUnit.MINUTES.toMillis(tierMinutes.toLong())
        val lastSyncOrReqMs = maxOf(repo.getLastSyncTimestampMs(), lastPhoneSyncRequestMs)

        if (lastSyncOrReqMs > 0L && (nowMs - lastSyncOrReqMs) < requiredWindowMs) {
            return
        }

        requestPhoneSyncNow(appCtx, minThrottleMs = 5_000L)
        if (activeCanvasMode == WatchAutonomousRepository.MODE_WEATHER ||
            activeCanvasMode == WatchAutonomousRepository.MODE_SUN ||
            repo.isWatchStandaloneWeatherEnabled()
        ) {
            runStandaloneWatchWeatherCheckIfDue(appCtx, forceRefresh = false)
        }
    }

    /**
     * Solicita ao smartphone conectado uma sincronização completa (`/chronowear/request_sync_from_watch`)
     * respeitando estritamente o único intervalo periódico universal configurado pelo usuário
     * (15 min, 30 min, 1 hora ou 2 horas), fazendo o relógio acordar o telefone quando o ciclo vencer.
     */
    fun requestPhoneSyncIfDue(context: Context) {
        val nowMs = System.currentTimeMillis()
        val appCtx = context.applicationContext
        val repo = WatchAutonomousRepository.getInstance(appCtx)
        val universalIntervalMs = TimeUnit.MINUTES.toMillis(repo.getUniversalSyncIntervalMinutes().toLong())
        val lastSyncMs = repo.getLastSyncTimestampMs()
        if (lastSyncMs > 0L && (nowMs - lastSyncMs) < universalIntervalMs) return
        requestPhoneSyncNow(context, minThrottleMs = 30_000L)
    }

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
        val calNow = Calendar.getInstance().apply { timeInMillis = nowMs }
        val dayKey = "${calNow.get(Calendar.YEAR)}_${calNow.get(Calendar.DAY_OF_YEAR)}"
        val curMinsOfDay = calNow.get(Calendar.HOUR_OF_DAY) * 60 + calNow.get(Calendar.MINUTE)

        val notificationDedupKey = when (forcedMode) {
            WatchAutonomousRepository.MODE_EVENTS -> "EVENTS_15M_$nextEventId"
            WatchAutonomousRepository.MODE_WEATHER -> {
                val weatherTs = if (repo.getAutoTriggerWeatherMode() == "EVERY_UPDATE") {
                    maxOf(repo.getWeatherAlertTimestampMs(), repo.getLastStandaloneWeatherFetchMs())
                } else {
                    repo.getWeatherAlertTimestampMs()
                }
                "WEATHER_$weatherTs"
            }
            WatchAutonomousRepository.MODE_TASKS -> {
                val tasksSnap = repo.getTasksSnapshot(nowMs)
                val firstTaskId = tasksSnap.tasks.firstOrNull()?.id ?: ""
                "TASKS_${dayKey}_${firstTaskId}_${repo.getTasksAlertTimestampMs()}"
            }
            WatchAutonomousRepository.MODE_SUN -> {
                val sunPhase = if (curMinsOfDay < 720) "SUNRISE" else "SUNSET"
                "SUN_${dayKey}_$sunPhase"
            }
            WatchAutonomousRepository.MODE_SLEEP -> {
                val sleepPhase = if (curMinsOfDay in 240..840) "WAKE" else "BEDTIME"
                "SLEEP_${dayKey}_$sleepPhase"
            }
            WatchAutonomousRepository.MODE_STEPS -> "STEPS_${dayKey}_${repo.getStepsMilestoneAlertTimestampMs()}"
            WatchAutonomousRepository.MODE_BATTERY -> "BATTERY_${repo.getBatteryAlertTimestampMs()}"
            else -> "${forcedMode}_$dayKey"
        }

        val lastMode = repo.getLastNotifiedForcedContext()
        // Dispara exatamente 1 notificação por mudança real de gatilho (ex: cada novo alerta de clima,
        // compromisso, marco de passos ou evento solar), sem repetir a cada 10 minutos durante a retenção.
        if (notificationDedupKey == lastMode && explicitReasonOverride == null) {
            return
        }
        repo.recordNotifiedForcedContext(notificationDedupKey, nowMs)

        val modeTitle = when (forcedMode) {
            WatchAutonomousRepository.MODE_WEATHER -> "Clima"
            WatchAutonomousRepository.MODE_EVENTS -> "Agenda"
            WatchAutonomousRepository.MODE_TASKS -> "Tarefas"
            WatchAutonomousRepository.MODE_SUN -> "Sol"
            WatchAutonomousRepository.MODE_SLEEP -> "Sono"
            WatchAutonomousRepository.MODE_STEPS -> "Passos"
            WatchAutonomousRepository.MODE_BATTERY -> "Bateria"
            else -> "ChronoWear"
        }
        val bodyText = explicitReasonOverride ?: autoReason

        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_CANVAS_SHIFT_ID,
                    "Alertas do Canvas",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Alertas de Agenda, Clima, Sol, Sono, Passos e Bateria"
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
            if (!repo.hasAnyActiveComplicationSlot()) {
                pauseAllBackgroundSchedules(context)
                return
            }
            val nowMs = System.currentTimeMillis()

            if (action == Intent.ACTION_TIME_TICK) {
                if (ChronoWearComplicationUpdater.isDisplayTotallyOff(context)) {
                    return
                }
                repo.recordWatchBatterySampleOnPeriodicUpdate(nowMs)
                runStandaloneWatchWeatherCheckIfDue(context, forceRefresh = false)
                requestPhoneSyncIfDue(context)
                notifyWatchOnForcedCanvasChangeIfNeeded(context)
                ChronoWearComplicationUpdater.requestUpdateAllSlots(context)
                ChronoWearComplicationUpdater.requestUpdateTileIfChanged(context)
                scheduleNextTransition(context)
                return
            }

            if (action != Intent.ACTION_SCREEN_ON) return
            ChronoWearComplicationUpdater.flushPendingOnScreenWake(context)
            ChronoWearComplicationUpdater.requestUpdateTileIfChanged(context)
            if (!repo.isUpdateOnScreenWakeEnabled()) return

            if (nowMs - lastScreenWakeUpdateMs >= 15_000L) {
                lastScreenWakeUpdateMs = nowMs
                repo.recordWatchBatterySampleOnPeriodicUpdate(nowMs)
                requestPhoneSyncForActiveCanvasIfDue(context)
                scheduleNextTransition(context)
            }
        }
    }

    fun ensureScreenWakeListenerRegistered(context: Context) {
        synchronized(receiverLock) {
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
    }

    fun clearLastScheduledWakeTimestamp() {
        lastScheduledAlarmWakeMs = -1L
    }

    fun cancelPendingTransitionAlarm(context: Context) {
        lastScheduledAlarmWakeMs = -1L
        try {
            val appCtx = context.applicationContext
            val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(appCtx, AutonomousTransitionReceiver::class.java).apply {
                action = ACTION_TRANSITION
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appCtx,
                2001,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        } catch (_: Exception) {
        }
    }

    fun pauseAllBackgroundSchedules(context: Context) {
        cancelPendingTransitionAlarm(context)
        synchronized(receiverLock) {
            if (!screenWakeReceiverRegistered) return
            try {
                context.applicationContext.unregisterReceiver(screenOnReceiver)
            } catch (_: Exception) {
            } finally {
                screenWakeReceiverRegistered = false
            }
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

        val nowMs = System.currentTimeMillis()
        val todayDayKey = WatchAutonomousRepository.formatIsoDateLocal(nowMs)
        val needsDailySolarAstro = repo.isWatchStandaloneSolarEnabled() &&
            repo.getLastSolarAstroDayKey() != todayDayKey

        val intervalMs = TimeUnit.MINUTES.toMillis(repo.getWatchStandaloneWeatherIntervalMinutes().toLong())
        val lastFetch = repo.getLastStandaloneWeatherFetchMs()
        val weatherDue = forceRefresh || lastFetch <= 0L || (nowMs - lastFetch) >= intervalMs

        if (!weatherDue && !needsDailySolarAstro) return

        val (lat, lon) = resolveWatchCoordinates(appCtx)
        if (needsDailySolarAstro || forceRefresh) {
            val (astroRise, astroSet) = calculateAstronomicalSunriseSunset(lat, lon)
            repo.saveSolarTimes(astroRise, astroSet)
            repo.saveLastSolarAstroDayKey(todayDayKey)
        }

        if (!weatherDue) return
        if (!forceRefresh && weatherFetchInFlight) return
        weatherFetchInFlight = true

        ioScope.launch {
            var conn: HttpURLConnection? = null
            try {
                val urlStr =
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&hourly=temperature_2m,precipitation_probability,weather_code" +
                        "&daily=sunrise,sunset&forecast_days=2&timezone=auto"
                conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
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

                        val dominantCondition = mapWmoCodeToPortugueseLabel(dominantWmoCode, maxRain)
                        val resolvedLocationName = resolveWatchLocationName(appCtx, lat, lon)

                        val shiftDetected = repo.saveStandaloneWeatherAndDetectShift(
                            pickedTemps = pickedTemps,
                            pickedLabels = pickedLabels,
                            maxRainProbPct = maxRain,
                            weatherConditionLabel = dominantCondition,
                            locationName = resolvedLocationName,
                            conditionShiftDetected = false,
                            nowMs = nowMs
                        )

                        if (shiftDetected || forceRefresh) {
                            notifyWatchOnForcedCanvasChangeIfNeeded(appCtx)
                        }

                        ChronoWearComplicationUpdater.requestUpdatePillSlotIfChanged(appCtx)
                        ChronoWearComplicationUpdater.requestUpdateMainSlot(
                            appCtx,
                            force = forceRefresh || shiftDetected
                        )

                        if (forceRefresh ||
                            shiftDetected ||
                            repo.getContextWindowMode() == WatchAutonomousRepository.MODE_WEATHER ||
                            repo.getContextWindowMode() == WatchAutonomousRepository.MODE_SUN
                        ) {
                            appCtx.sendBroadcast(
                                Intent(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
                                    .setPackage(appCtx.packageName)
                            )
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
                weatherFetchInFlight = false
            }
        }
    }

    private fun computeNextSolarTriggerBoundaryMs(repo: WatchAutonomousRepository, nowMs: Long): Long {
        if (!repo.isAutoTriggerSunEnabled()) return Long.MAX_VALUE
        val solar = repo.getSolarSnapshot(nowMs)
        val sunLeadMin = repo.getAutoTriggerSunLeadMin()
        val holdMin = repo.getUnifiedCanvasHoldMinutes()

        val dayStartMs = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        val boundaries = longArrayOf(
            dayStartMs + TimeUnit.MINUTES.toMillis((solar.sunriseMinutesOfDay - sunLeadMin).toLong()),
            dayStartMs + TimeUnit.MINUTES.toMillis((solar.sunriseMinutesOfDay - sunLeadMin + holdMin + 1).toLong()),
            dayStartMs + TimeUnit.MINUTES.toMillis((solar.sunsetMinutesOfDay - sunLeadMin).toLong()),
            dayStartMs + TimeUnit.MINUTES.toMillis((solar.sunsetMinutesOfDay - sunLeadMin + holdMin + 1).toLong()),
            dayStartMs + TimeUnit.MINUTES.toMillis((1440 + solar.sunriseMinutesOfDay - sunLeadMin).toLong())
        )
        var best = Long.MAX_VALUE
        for (b in boundaries) {
            if (b > nowMs + 1000L && b < best) {
                best = b
            }
        }
        return best
    }

    fun scheduleNextTransition(context: Context) {
        val appCtx = context.applicationContext
        val repo = WatchAutonomousRepository.getInstance(appCtx)
        if (!repo.hasAnyActiveComplicationSlot()) {
            cancelPendingTransitionAlarm(appCtx)
            return
        }

        ensureScreenWakeListenerRegistered(appCtx)

        val nowMs = System.currentTimeMillis()
        val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        val nextEvent = upcomingThree.firstOrNull()

        val standaloneWeatherWakeMs = if (repo.isWatchStandaloneWeatherEnabled() || repo.isWatchStandaloneSolarEnabled()) {
            val weatherIntervalMs = TimeUnit.MINUTES.toMillis(repo.getWatchStandaloneWeatherIntervalMinutes().toLong())
            val lastFetch = repo.getLastStandaloneWeatherFetchMs()
            if (lastFetch > 0L) {
                (lastFetch + weatherIntervalMs).coerceAtLeast(nowMs + 60_000L)
            } else {
                nowMs + weatherIntervalMs
            }
        } else {
            Long.MAX_VALUE
        }

        val universalIntervalMs = TimeUnit.MINUTES.toMillis(repo.getUniversalSyncIntervalMinutes().toLong())
        val lastSyncMs = repo.getLastSyncTimestampMs()
        val universalPeriodicWakeMs = if (lastSyncMs > 0L) {
            (lastSyncMs + universalIntervalMs).coerceAtLeast(nowMs + 30_000L)
        } else {
            nowMs + universalIntervalMs
        }

        val tapOverrideReturnMs = repo.getTapOverrideExpiryEpochMs(nowMs)
        val pillTapOverrideReturnMs = repo.getPillTapOverrideExpiryEpochMs(nowMs)
        val solarTriggerBoundaryMs = computeNextSolarTriggerBoundaryMs(repo, nowMs)

        // Agenda inteligente:
        // - Sempre agenda transições exatas de fronteira (entrada na antecedência de alerta, início, fim da tolerância de 15m).
        // - Só desperta a cada virada exata de minuto quando a tela NÃO está totalmente apagada (interativo ou AOD visível)
        //   E o Canvas Principal está exibindo Agenda (evitando acordar a CPU em Deep Doze quando a tela está OFF ou em Clima/Sono/Passos).
        val effectiveMode = repo.resolveEffectiveContextMode(nowMs, upcomingThree)
        val displayTotallyOff = ChronoWearComplicationUpdater.isDisplayTotallyOff(appCtx)
        val nextMinuteAlignedMs = ((nowMs / 60_000L) + 1L) * 60_000L + 250L

        val eventWakeUpMs = if (nextEvent == null) {
            Long.MAX_VALUE
        } else {
            val leadMin = repo.getAutoTriggerAgendaTasksLeadMin().toLong()
            val leadEntryMs = nextEvent.startEpochMs - TimeUnit.MINUTES.toMillis(leadMin)
            val startBoundaryMs = nextEvent.startEpochMs + 500L
            val graceEndBoundaryMs = minOf(
                nextEvent.startEpochMs + TimeUnit.MINUTES.toMillis(15L) + 500L,
                nextEvent.endEpochMs + 500L
            )

            var earliestBoundary = Long.MAX_VALUE
            if (leadEntryMs > nowMs + 500L) earliestBoundary = minOf(earliestBoundary, leadEntryMs)
            if (startBoundaryMs > nowMs + 500L) earliestBoundary = minOf(earliestBoundary, startBoundaryMs)
            if (graceEndBoundaryMs > nowMs + 500L) earliestBoundary = minOf(earliestBoundary, graceEndBoundaryMs)

            val shouldTickPerMinute = !displayTotallyOff &&
                effectiveMode == WatchAutonomousRepository.MODE_EVENTS &&
                (nextEvent.startEpochMs - nowMs) <= TimeUnit.HOURS.toMillis(24L)

            if (shouldTickPerMinute) {
                minOf(nextMinuteAlignedMs, earliestBoundary)
            } else {
                earliestBoundary
            }
        }

        val nextWakeUpMs = minOf(
            minOf(eventWakeUpMs, universalPeriodicWakeMs),
            minOf(
                minOf(standaloneWeatherWakeMs, solarTriggerBoundaryMs),
                minOf(tapOverrideReturnMs, pillTapOverrideReturnMs)
            )
        )
        if (nextWakeUpMs == Long.MAX_VALUE) return

        val prevWake = lastScheduledAlarmWakeMs
        if (prevWake > nowMs && kotlin.math.abs(nextWakeUpMs - prevWake) < 400L) {
            return
        }
        lastScheduledAlarmWakeMs = nextWakeUpMs

        val alarmManager = appCtx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(appCtx, AutonomousTransitionReceiver::class.java).apply {
            action = ACTION_TRANSITION
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appCtx,
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
        val action = intent.action
        if (action == AutonomousCountdownScheduler.ACTION_TRANSITION) {
            AutonomousCountdownScheduler.clearLastScheduledWakeTimestamp()
        }
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AutonomousCountdownScheduler.ensureScreenWakeListenerRegistered(context)
            AutonomousCountdownScheduler.scheduleNextTransition(context)
            ChronoWearComplicationUpdater.requestUpdateAllSlots(context, force = true)
            ChronoWearComplicationUpdater.requestUpdateTileIfChanged(context, force = true)
            return
        }

        if (action == AutonomousCountdownScheduler.ACTION_TAP_CYCLE_CONTEXT) {
            // Toque direto na imagem do Canvas na Watch Face: avança imediatamente para o próximo Canvas
            // e agenda o retorno automático ao Canvas Padrão Principal após o Tempo Unificado configurado!
            val repo = WatchAutonomousRepository.getInstance(context)
            val nowMs = System.currentTimeMillis()
            val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
            repo.cycleNextVisualCanvasOnTap(nowMs, upcomingThree)
            if (repo.isUpdateOnCanvasTapEnabled()) {
                AutonomousCountdownScheduler.requestPhoneSyncForActiveCanvasIfDue(context)
            }
            ChronoWearComplicationUpdater.requestUpdateMainSlot(context, force = true)
            AutonomousCountdownScheduler.scheduleNextTransition(context)
            context.sendBroadcast(
                Intent(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
                    .setPackage(context.packageName)
            )
            return
        }

        if (action == AutonomousCountdownScheduler.ACTION_TAP_CYCLE_PILL_CONTEXT) {
            // Toque direto no Cartucho Inferior (2º Canvas): alterna entre Clima e Tarefas (Google Tasks)
            val repo = WatchAutonomousRepository.getInstance(context)
            val nowMs = System.currentTimeMillis()
            val nextPillMode = repo.cycleNextPillCanvasOnTap(nowMs)
            if (repo.isUpdateOnCanvasTapEnabled()) {
                if (nextPillMode == WatchAutonomousRepository.MODE_WEATHER) {
                    AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(context, forceRefresh = false)
                }
                AutonomousCountdownScheduler.requestPhoneSyncForActiveCanvasIfDue(context)
            }
            ChronoWearComplicationUpdater.requestUpdatePillSlotIfChanged(context, force = true)
            AutonomousCountdownScheduler.scheduleNextTransition(context)
            context.sendBroadcast(
                Intent(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
                    .setPackage(context.packageName)
            )
            return
        }

        WatchAutonomousRepository.getInstance(context).recordWatchBatterySampleOnPeriodicUpdate()
        AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(context, forceRefresh = false)
        AutonomousCountdownScheduler.requestPhoneSyncIfDue(context)
        AutonomousCountdownScheduler.notifyWatchOnForcedCanvasChangeIfNeeded(context)

        ChronoWearComplicationUpdater.requestUpdateAllSlots(context)
        ChronoWearComplicationUpdater.requestUpdateTileIfChanged(context)
        AutonomousCountdownScheduler.scheduleNextTransition(context)
    }
}
