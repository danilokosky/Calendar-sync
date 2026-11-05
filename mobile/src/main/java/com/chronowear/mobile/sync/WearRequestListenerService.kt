package com.chronowear.mobile.sync

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Escuta solicitações vindas diretamente do Smartwatch (ex.: quando o usuário toca em
 * "Pedir Sincronização ao Telefone" no relógio) e responde imediatamente com os eventos do horizonte.
 */
class WearRequestListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path == "/chronowear/request_sync_from_watch") {
            scope.launch {
                CalendarSyncEngine.performFullSyncToWatch(applicationContext)
            }
        }
    }
}
