package com.chronowear.wear.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.wear.tiles.TileService
import com.chronowear.wear.complication.ChronoWearComplicationUpdater
import com.chronowear.wear.tile.UpNextThreeEventsTileService
import java.util.concurrent.TimeUnit

object AutonomousCountdownScheduler {

    private const val ACTION_TRANSITION = "com.chronowear.wear.ACTION_AUTONOMOUS_EVENT_TRANSITION"

    fun scheduleNextTransition(context: Context) {
        val repo = WatchAutonomousRepository.getInstance(context)
        val nowMs = System.currentTimeMillis()
        val nextEvent = repo.getNextThreeUpcomingEvents(nowMs).firstOrNull() ?: return

        val progressWindowMs = TimeUnit.MINUTES.toMillis(repo.getProgressWindowMinutes().toLong())
        val progressStartEpochMs = nextEvent.startEpochMs - progressWindowMs

        val nextWakeUpMs = when {
            nowMs < progressStartEpochMs -> progressStartEpochMs
            else -> nextEvent.startEpochMs + 1000L
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
