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
import java.util.concurrent.TimeUnit

/**
 * Agendador Inteligente do Canvas de Contexto + Serviço Stand-Alone Opcional de Clima no Relógio:
 * 1. Fora da janela crítica: economiza bateria acordando apenas nos marcos de transição
 *    (início da barra de progresso, entrada nos 15 minutos finais e término do evento)
 *    ou quando o usuário liga a tela (ACTION_SCREEN_ON com debounce de 25s).
 * 2. Regra Turbo dos 15 Minutos Finais: quando faltam <= 15 minutos para o evento iniciar,
 *    agenda AlarmManager exato a cada 60 segundos (1 minuto) para redesenhar o Canvas.
 * 3. Serviço Stand-Alone de Clima no Relógio (Opcional / Configurável pelo Usuário):
 *    Se ativado pelo usuário (`isWatchStandaloneWeatherEnabled() == true`), o próprio relógio
 *    busca o clima de hora em hora (ou no intervalo configurado), verifica se haverá mudança
 *    de temperatura nas próximas horas e atualiza dinamicamente o Canvas!
 */
object AutonomousCountdownScheduler {

    private const val ACTION_TRANSITION = "com.chronowear.wear.ACTION_AUTONOMOUS_EVENT_TRANSITION"
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

    /**
     * Serviço Stand-Alone de Clima no Relógio (executado somente se o usuário ativar essa função):
     * Busca diretamente do relógio (de hora em hora ou no intervalo configurado) as previsões
     * das próximas horas via Open-Meteo. Se detectar mudança na temperatura/clima nas próximas
     * horas, atualiza imediatamente a Janela de Contexto (Canvas)!
     */
    fun runStandaloneWatchWeatherCheckIfDue(context: Context, forceRefresh: Boolean = false) {
        val appCtx = context.applicationContext
        val repo = WatchAutonomousRepository.getInstance(appCtx)
        if (!forceRefresh && !repo.isWatchStandaloneWeatherEnabled()) return

        val nowMs = System.currentTimeMillis()
        val intervalMs = TimeUnit.MINUTES.toMillis(repo.getWatchStandaloneWeatherIntervalMinutes().toLong())
        val lastFetch = repo.getLastStandaloneWeatherFetchMs()
        if (!forceRefresh && lastFetch > 0L && (nowMs - lastFetch) < intervalMs) return

        ioScope.launch {
            try {
                var lat = -23.5505
                var lon = -46.6333
                if (appCtx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    val loc = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: lm?.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    if (loc != null) {
                        lat = loc.latitude
                        lon = loc.longitude
                    }
                }

                val urlStr =
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&hourly=temperature_2m,precipitation_probability&forecast_days=2&timezone=auto"
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
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
                        val cal = Calendar.getInstance()
                        val currentHour = cal.get(Calendar.HOUR_OF_DAY)
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

                        val shiftDetected = repo.saveStandaloneWeatherAndDetectShift(
                            pickedTemps = pickedTemps,
                            pickedLabels = pickedLabels,
                            maxRainProbPct = maxRain,
                            nowMs = nowMs
                        )

                        // Se detectou mudança de temperatura nas próximas horas ou se já está no modo Clima, redesenha o Canvas!
                        if (shiftDetected || repo.getContextWindowMode() == WatchAutonomousRepository.MODE_WEATHER) {
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

        val standaloneWeatherWakeMs = if (repo.isWatchStandaloneWeatherEnabled()) {
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
                // 1. REGRA TURBO (Faltam 15 minutos ou menos): atualiza o Canvas a cada 1 minuto!
                highFreq15MinEnabled && nowMs in fifteenMinBeforeMs until nextEvent.startEpochMs -> {
                    minOf(nowMs + 60_000L, nextEvent.startEpochMs + 1000L)
                }
                // 2. Antes do início da janela da barra de progresso: acorda no início da barra
                nowMs < progressStartEpochMs -> {
                    progressStartEpochMs
                }
                // 3. Dentro da janela da barra, mas ainda faltam mais de 15 minutos: acorda quando faltar 15 min
                highFreq15MinEnabled && nowMs < fifteenMinBeforeMs -> {
                    fifteenMinBeforeMs
                }
                // 4. Evento prestes a começar / iniciando: promove o próximo da fila
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
        // Se o serviço Stand-Alone de clima no relógio estiver habilitado pelo usuário, verifica mudança de hora em hora
        AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(context, forceRefresh = false)

        ChronoWearComplicationUpdater.requestUpdateAllSlots(context)

        TileService.getUpdater(context)
            .requestUpdate(UpNextThreeEventsTileService::class.java)

        AutonomousCountdownScheduler.scheduleNextTransition(context)
    }
}
