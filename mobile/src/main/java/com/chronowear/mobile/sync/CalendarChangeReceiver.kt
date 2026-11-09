package com.chronowear.mobile.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Recebe notificações do sistema Android quando:
 * 1) `Intent.ACTION_PROVIDER_CHANGED` em `content://com.android.calendar`: um compromisso foi criado,
 *    editado ou removido no aplicativo de Agenda do usuário.
 * 2) `Intent.ACTION_BOOT_COMPLETED`: o smartphone foi reiniciado.
 */
class CalendarChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = SyncPreferences(context)
        if (!prefs.isBackgroundServiceEnabled()) {
            return
        }

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                CalendarSyncWorker.schedulePeriodicSync(context, prefs.getSyncIntervalMinutes())
                try {
                    CalendarSyncForegroundService.startOrUpdate(context, "Reinicialização do Sistema")
                } catch (_: Exception) {
                }
            }

            Intent.ACTION_PROVIDER_CHANGED -> {
                if (prefs.isAutoSyncOnCalendarChangeEnabled()) {
                    val label = "Agenda Alterada (PROVIDER_CHANGED)"
                    CalendarSyncWorker.enqueueImmediateSync(context, label)
                    try {
                        CalendarSyncForegroundService.startOrUpdate(context, label)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
