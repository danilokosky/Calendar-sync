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
import java.util.Locale

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
            "/chronowear/watch_battery_report" -> {
                try {
                    val prefs = SyncPreferences(applicationContext)
                    prefs.markWatchAppInstalledOnNode(messageEvent.sourceNodeId)
                    val json = org.json.JSONObject(String(messageEvent.data, Charsets.UTF_8))
                    prefs.saveWatchBatteryReport(
                        pct = json.optInt("pct", 82),
                        isCharging = json.optBoolean("isCharging", false),
                        rateLabel = json.optString("rateLabel", ""),
                        historyCsv = json.optString("historyCsv", ""),
                        labelsCsv = json.optString("labelsCsv", ""),
                        levelsCsv = json.optString("levelsCsv", "")
                    )
                    applicationContext.sendBroadcast(
                        Intent(SyncPreferences.ACTION_SYNC_COMPLETED).setPackage(applicationContext.packageName)
                    )
                } catch (_: Exception) {
                }
            }
        }
    }
}

/**
 * Observador de Notificações de Clima no Smartphone (`PhoneWeatherNotificationListenerService`):
 * - Detecta notificações de Apps de Clima escolhidos pelo usuário e envia atualização imediata ao relógio.
 * - Dados de Sono são lidos exclusivamente via API oficial do Android Health Connect (`HealthConnectSleepReader`),
 *   e tarefas via API oficial do Google Tasks, sem depender da barra de notificações.
 */
class PhoneWeatherNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = SyncPreferences(applicationContext)
        if (!prefs.isNotificationsSyncEnabled()) return

        val pkg = sbn.packageName.lowercase(Locale.ROOT)
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
        val combinedLower = "$title $text".lowercase(Locale.ROOT)

        val selectedPkgs = prefs.getSelectedWeatherTriggerPackages().map { it.lowercase(Locale.ROOT) }.toSet()
        val isSelectedTriggerPkg = selectedPkgs.any { sel -> pkg == sel || pkg.contains(sel) }
        if (!isSelectedTriggerPkg) return

        val isGeneralGoogleSearchApp = pkg.contains("googlequicksearchbox")
        val hasWeatherKeywords = combinedLower.contains("chuva") ||
            combinedLower.contains("temperatura") ||
            combinedLower.contains("previsão") ||
            combinedLower.contains("frente fria") ||
            combinedLower.contains("tempestade") ||
            combinedLower.contains("clima") ||
            combinedLower.contains("°c") ||
            combinedLower.contains("°")

        if (!isGeneralGoogleSearchApp || hasWeatherKeywords) {
            val catalogMatch = SyncPreferences.KNOWN_WEATHER_TRIGGER_CATALOG.firstOrNull {
                pkg == it.packageName.lowercase(Locale.ROOT) || pkg.contains(it.packageName.lowercase(Locale.ROOT))
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
                CalendarSyncEngine.fetchPhoneHourlyWeatherIfNeeded(
                    context = applicationContext,
                    forceRefresh = true
                )
                val nowMs = System.currentTimeMillis()
                prefs.recordObservedWeatherNotification(
                    sourceAppLabel = sourceLabel,
                    headline = formattedHeadline,
                    timestampMs = nowMs
                )
                CalendarSyncEngine.performFullSyncToWatch(
                    context = applicationContext,
                    triggerSource = "Trigger App Clima ($sourceLabel)"
                )
            }
        }
    }
}
