package com.chronowear.mobile.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Recebe notificações do sistema Android quando:
 * 1) `Intent.ACTION_PROVIDER_CHANGED` em `content://com.android.calendar`: um compromisso foi criado,
 *    editado ou removido no aplicativo de Agenda do usuário.
 * 2) `Intent.ACTION_BOOT_COMPLETED`: o smartphone foi reiniciado.
 *
 * Só executa se o usuário tiver ativado o Serviço em Segundo Plano e a opção de leitura
 * automática ao alterar compromissos.
 */
class CalendarChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = SyncPreferences(context)
        if (!prefs.isBackgroundServiceEnabled() && !prefs.isTasksSyncEnabled()) {
            return
        }

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                if (prefs.isBackgroundServiceEnabled()) {
                    CalendarSyncWorker.schedulePeriodicSync(context, prefs.getSyncIntervalMinutes())
                    try {
                        CalendarSyncForegroundService.startOrUpdate(context, "Reinicialização do Sistema")
                    } catch (_: Exception) {
                    }
                }
            }

            Intent.ACTION_PROVIDER_CHANGED,
            "com.samsung.android.app.reminder.broadcast.REMINDER_DATA_CHANGED",
            "com.samsung.android.app.reminder.ACTION_REMINDER_DATA_CHANGED",
            "com.samsung.android.calendar.ACTION_EVENT_CHANGED" -> {
                val isReminderAction = intent.action?.contains("reminder", ignoreCase = true) == true ||
                    intent.data?.authority?.contains("reminder", ignoreCase = true) == true
                val label = if (isReminderAction) {
                    "Tarefa Alterada no Samsung Reminder"
                } else {
                    "Agenda / Tarefas Alteradas (PROVIDER_CHANGED)"
                }

                if (prefs.isAutoSyncOnCalendarChangeEnabled() || prefs.isTasksSyncEnabled()) {
                    CalendarSyncWorker.enqueueImmediateSync(context, label)
                    if (prefs.isBackgroundServiceEnabled()) {
                        try {
                            CalendarSyncForegroundService.startOrUpdate(context, label)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }
}
