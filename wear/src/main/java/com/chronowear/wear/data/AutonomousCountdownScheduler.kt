package com.chronowear.wear.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.wear.tiles.TileService
import com.chronowear.wear.complication.ChronoWearComplicationUpdater
import com.chronowear.wear.tile.UpNextThreeEventsTileService
import java.util.concurrent.TimeUnit

/**
 * Agendador Inteligente do Canvas de Contexto no Relógio:
 * 1. Fora da janela crítica: economiza bateria acordando apenas nos marcos de transição
 *    (início da barra de progresso, entrada nos 15 minutos finais e término do evento)
 *    ou quando o usuário liga a tela (ACTION_SCREEN_ON com debounce de 30s).
 * 2. Regra Turbo dos 15 Minutos Finais: quando faltam <= 15 minutos para o evento iniciar,
 *    agenda AlarmManager exato a cada 60 segundos (1 minuto) para redesenhar o Canvas
 *    minuto a minuto (15m, 14m, 13m... 1m) e preencher a barra de progresso suavemente.
 */
object AutonomousCountdownScheduler {

    private const val ACTION_TRANSITION = "com.chronowear.wear.ACTION_AUTONOMOUS_EVENT_TRANSITION"
    private var screenWakeReceiverRegistered = false
    private var lastScreenWakeUpdateMs = 0L

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            val repo = WatchAutonomousRepository.getInstance(context)
            if (!repo.isUpdateOnScreenWakeEnabled()) return

            val nowMs = System.currentTimeMillis()
            // Debounce de 25 segundos para evitar redesenhos redundantes em toques seguidos
            if (nowMs - lastScreenWakeUpdateMs >= 25_000L) {
                lastScreenWakeUpdateMs = nowMs
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

    fun scheduleNextTransition(context: Context) {
        ensureScreenWakeListenerRegistered(context)

        val repo = WatchAutonomousRepository.getInstance(context)
        val nowMs = System.currentTimeMillis()
        val nextEvent = repo.getNextThreeUpcomingEvents(nowMs).firstOrNull() ?: return

        val progressWindowMs = TimeUnit.MINUTES.toMillis(repo.getProgressWindowMinutes().toLong())
        val progressStartEpochMs = nextEvent.startEpochMs - progressWindowMs
        val fifteenMinBeforeMs = nextEvent.startEpochMs - TimeUnit.MINUTES.toMillis(15L)
        val highFreq15MinEnabled = repo.isHighFreq15MinRuleEnabled()

        val nextWakeUpMs = when {
            // 1. REGRA TURBO (Faltam 15 minutos ou menos): atualiza o Canvas a cada 1 minuto!
            highFreq15MinEnabled && nowMs in fifteenMinBeforeMs until nextEvent.startEpochMs -> {
                minOf(nowMs + 60_000L, nextEvent.startEpochMs + 1000L)
            }
            // 2. Antes do início da janela da barra de progresso: acorda no início da barra
            nowMs < progressStartEpochMs -> {
                progressStartEpochMs
            }
            // 3. Dentro da janela da barra, mas ainda faltam mais de 15 minutos: acorda exatamente quando faltar 15 min
            highFreq15MinEnabled && nowMs < fifteenMinBeforeMs -> {
                fifteenMinBeforeMs
            }
            // 4. Evento prestes a começar / iniciando: promove o próximo da fila
            else -> {
                nextEvent.startEpochMs + 1000L
            }
        }

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
        ChronoWearComplicationUpdater.requestUpdateAllSlots(context)

        TileService.getUpdater(context)
            .requestUpdate(UpNextThreeEventsTileService::class.java)

        AutonomousCountdownScheduler.scheduleNextTransition(context)
    }
}
