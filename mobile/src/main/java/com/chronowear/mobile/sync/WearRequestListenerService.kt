package com.chronowear.mobile.sync

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Escuta mensagens vindas do Smartwatch:
 * 1) Confirmação de que o app Wear OS está instalado (`/chronowear/pong_watch_app`)
 * 2) Solicitação de sincronização iniciada pelo relógio (`/chronowear/request_sync_from_watch`)
 */
class WearRequestListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        when (messageEvent.path) {
            "/chronowear/pong_watch_app" -> {
                val prefs = SyncPreferences(applicationContext)
                prefs.markWatchAppInstalledOnNode(messageEvent.sourceNodeId)
                applicationContext.sendBroadcast(
                    Intent(SyncPreferences.ACTION_SYNC_COMPLETED).setPackage(applicationContext.packageName)
                )
            }
            "/chronowear/request_sync_from_watch" -> {
                val prefs = SyncPreferences(applicationContext)
                prefs.markWatchAppInstalledOnNode(messageEvent.sourceNodeId)
                scope.launch {
                    CalendarSyncEngine.performFullSyncToWatch(
                        applicationContext,
                        "Solicitado pelo Relógio"
                    )
                }
            }
        }
    }
}

/**
 * Observador de Notificações de Clima no Smartphone (`PhoneWeatherNotificationListenerService`):
 * Observa se o telefone recebe uma notificação sobre clima vinda de qualquer **App de Clima de Terceiro**
 * escolhido pelo usuário nas Configurações (`selectedWeatherTriggerPackages`, ex.: App Google,
 * Google Weather, App Clima Samsung, Climatempo, AccuWeather, The Weather Channel, Windy, etc.).
 * Quando detectada:
 * 1. Verifica se o pacote que disparou a notificação está na lista de Apps Trigger selecionados pelo usuário.
 * 2. Chama imediatamente a atualização de previsão horária já implementada (`fetchPhoneHourlyWeatherIfNeeded(forceRefresh = true)`).
 * 3. Registra o alerta com o nome do app de origem e envia o contexto atualizado para o relógio mudar dinamicamente o Canvas!
 */
class PhoneWeatherNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = SyncPreferences(applicationContext)
        if (!prefs.isNotificationsSyncEnabled()) return

        val pkg = sbn.packageName.lowercase()
        val selectedPkgs = prefs.getSelectedWeatherTriggerPackages().map { it.lowercase() }.toSet()
        val isSelectedTriggerPkg = selectedPkgs.any { sel -> pkg == sel || pkg.contains(sel) }
        if (!isSelectedTriggerPkg) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()

        val combinedLower = "$title $text".lowercase()

        val isGeneralGoogleSearchApp = pkg.contains("googlequicksearchbox")
        val hasWeatherKeywords = combinedLower.contains("chuva") ||
            combinedLower.contains("temperatura") ||
            combinedLower.contains("previsão") ||
            combinedLower.contains("frente fria") ||
            combinedLower.contains("tempestade") ||
            combinedLower.contains("clima") ||
            combinedLower.contains("°c") ||
            combinedLower.contains("°")

        // Se for o pacote geral do App Google, exige palavras-chave de clima; se for um app dedicado de clima escolhido pelo usuário, dispara direto
        if (!isGeneralGoogleSearchApp || hasWeatherKeywords) {
            val catalogMatch = SyncPreferences.KNOWN_WEATHER_TRIGGER_CATALOG.firstOrNull {
                pkg == it.packageName.lowercase() || pkg.contains(it.packageName.lowercase())
            }
            val sourceLabel = catalogMatch?.displayName ?: when {
                isGeneralGoogleSearchApp -> "App Google"
                pkg.contains("daemonapp") || pkg.contains("samsung") -> "App Clima"
                else -> try {
                    val ai = applicationContext.packageManager.getApplicationInfo(sbn.packageName, 0)
                    applicationContext.packageManager.getApplicationLabel(ai).toString()
                } catch (_: Exception) {
                    "App Clima"
                }
            }

            val rawSummary = when {
                title.isNotBlank() && text.isNotBlank() -> "$title: $text"
                title.isNotBlank() -> title
                text.isNotBlank() -> text
                else -> "Mudança climática nas próximas horas"
            }
            val formattedHeadline = "$sourceLabel • $rawSummary".take(42)

            scope.launch {
                // 1) Chama a atualização de clima implementada no telefone para garantir temperaturas frescas
                CalendarSyncEngine.fetchPhoneHourlyWeatherIfNeeded(
                    context = applicationContext,
                    forceRefresh = true
                )
                // 2) Registra a notificação com o nome do app escolhido pelo usuário como origem prioritária
                val nowMs = System.currentTimeMillis()
                prefs.recordObservedWeatherNotification(
                    sourceAppLabel = sourceLabel,
                    headline = formattedHeadline,
                    timestampMs = nowMs
                )
                // 3) Envia imediatamente o contexto atualizado ao relógio para mudar o Canvas
                CalendarSyncEngine.performFullSyncToWatch(
                    context = applicationContext,
                    triggerSource = "Trigger App Clima ($sourceLabel)"
                )
            }
        }
    }
}
