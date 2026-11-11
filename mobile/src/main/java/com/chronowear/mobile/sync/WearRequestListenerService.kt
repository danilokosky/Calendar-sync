package com.chronowear.mobile.sync

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
import java.util.concurrent.ConcurrentHashMap

/**
 * Escuta mensagens vindas do Smartwatch:
 * 1) Confirmação de que o app Wear OS está instalado (`/chronowear/pong_watch_app`)
 * 2) Solicitação de sincronização iniciada pelo relógio (`/chronowear/request_sync_from_watch`)
 * 3) Relatório periódico de bateria do relógio (`/chronowear/watch_battery_report`)
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
 * Observador de Notificações Exclusivo no Smartphone (`PhoneWeatherNotificationListenerService`):
 * - Serve unicamente como gatilho ao identificar que um aplicativo de Clima confiável emitiu uma notificação.
 * - NÃO extrai nem lê qualquer texto ou conteúdo interno da notificação.
 * - Ao identificar a emissão da notificação pelo pacote do app de Clima, aciona imediatamente a busca interna
 *   de clima do próprio ChronoWear e sincroniza com o relógio para exibir o Canvas de Clima e emitir a notificação no Wear OS.
 */
class PhoneWeatherNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = SyncPreferences(applicationContext)
        if (!prefs.isNotificationsSyncEnabled() || !prefs.isAutoTriggerWeatherEnabled()) return

        val pkg = sbn.packageName.lowercase(Locale.ROOT)
        val selectedPkgs = prefs.getSelectedWeatherTriggerPackages().map { it.lowercase(Locale.ROOT) }.toSet()

        val isSamsungWeatherSelected = selectedPkgs.any {
            it.contains("daemonapp") || it.contains("samsung.android.weather")
        }
        val isMatchedWeatherApp = selectedPkgs.any { sel -> pkg == sel || pkg.contains(sel) } ||
            (isSamsungWeatherSelected && (pkg == "com.sec.android.daemonapp" || pkg == "com.samsung.android.weather"))

        if (!isMatchedWeatherApp) return

        val nowMs = System.currentTimeMillis()
        val lastPkgTriggerMs = lastTriggerByPackageMs[pkg] ?: 0L
        // Evita disparo duplicado quando o mesmo app posta resumo de grupo + notificação filha no mesmo instante
        if ((nowMs - lastPkgTriggerMs) < 60_000L) return
        lastTriggerByPackageMs[pkg] = nowMs

        val catalogMatch = SyncPreferences.KNOWN_WEATHER_TRIGGER_CATALOG.firstOrNull {
            pkg == it.packageName.lowercase(Locale.ROOT) || pkg.contains(it.packageName.lowercase(Locale.ROOT))
        }
        val sourceLabel = catalogMatch?.displayName ?: when {
            pkg.contains("daemonapp") || pkg.contains("samsung.android.weather") -> "Samsung Clima"
            pkg.contains("google.android.apps.weather") -> "Google Clima"
            else -> try {
                val ai = applicationContext.packageManager.getApplicationInfo(sbn.packageName, 0)
                applicationContext.packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                "App Clima"
            }
        }

        scope.launch {
            // 1. Busca a atualização de clima independente pelo provedor interno do nosso aplicativo
            CalendarSyncEngine.fetchPhoneHourlyWeatherIfNeeded(
                context = applicationContext,
                forceRefresh = true
            )
            // 2. Registra o gatilho de atualização para virar o Canvas para Clima e notificar no relógio
            val triggerTs = System.currentTimeMillis()
            val locName = prefs.getPhoneWeatherLocationName()
            val updatedHHMM = prefs.getPhoneWeatherUpdatedHHMM()
            val internalHeadline = "$locName • Atualizado $updatedHHMM"
            prefs.recordObservedWeatherNotification(
                sourceAppLabel = sourceLabel,
                headline = internalHeadline,
                timestampMs = triggerTs
            )
            // 3. Sincroniza imediatamente com o relógio para mudar o Canvas e emitir a notificação no Wear OS
            CalendarSyncEngine.performFullSyncToWatch(
                context = applicationContext,
                triggerSource = "Gatilho App de Clima ($sourceLabel)"
            )
        }
    }

    companion object {
        private val lastTriggerByPackageMs = ConcurrentHashMap<String, Long>()
    }
}
