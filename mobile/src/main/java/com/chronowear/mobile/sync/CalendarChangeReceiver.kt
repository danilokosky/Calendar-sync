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
        if (!prefs.isBackgroundServiceEnabled() || !CalendarSyncEngine.hasCalendarPermission(context)) {
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
                // Só dispara a leitura imediata se o usuário habilitou "Sincronizar ao alterar compromissos"
                if (prefs.isAutoSyncOnCalendarChangeEnabled()) {
                    CalendarSyncWorker.enqueueImmediateSync(
                        context,
                        "Compromisso Alterado (PROVIDER_CHANGED)"
                    )
                    try {
                        CalendarSyncForegroundService.startOrUpdate(
                            context,
                            "Compromisso Alterado na Agenda"
                        )
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
