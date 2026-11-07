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
 * Observador de Notificações de Clima e Resumo de Sono no Smartphone (`PhoneWeatherNotificationListenerService`):
 * 1. Detecta notificações de Apps de Clima escolhidos pelo usuário e envia atualização imediata ao relógio.
 * 2. Detecta notificações matinais de Sono do Samsung Health (`com.sec.android.app.shealth`),
 *    Health Connect (`com.google.android.apps.healthdata`), Sleep as Android ou Google Fit,
 *    extraindo o tempo dormido/pontuação e acionando a exposição do contexto de Sono ao acordar.
 */
class PhoneWeatherNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = SyncPreferences(applicationContext)
        val pkg = sbn.packageName.lowercase()
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
        val combinedLower = "$title $text".lowercase()

        // 1) Verifica se é um relatório de Sono (Samsung Health, Health Connect, Sleep as Android, etc.)
        val isSleepAppPkg = pkg.contains("shealth") ||
            pkg.contains("healthdata") ||
            pkg.contains("sleep") ||
            pkg.contains("fitness")
        val hasSleepKeywords = combinedLower.contains("sono") ||
            combinedLower.contains("sleep") ||
            combinedLower.contains("dormiu") ||
            combinedLower.contains("pontuação do sono")

        if (prefs.isSleepSyncEnabled() && isSleepAppPkg && hasSleepKeywords) {
            val hoursRegex = Regex("(\\d{1,2})\\s*h\\s*(\\d{1,2})?\\s*m?")
            val match = hoursRegex.find(combinedLower)
            val parsedMins = if (match != null) {
                val h = match.groupValues[1].toIntOrNull() ?: 7
                val m = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 42
                (h * 60 + m).coerceIn(120, 840)
            } else {
                prefs.getSleepDurationMinutes()
            }

            val scoreRegex = Regex("(\\d{2,3})\\s*(pts|pontos|score)")
            val parsedScore = scoreRegex.find(combinedLower)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: prefs.getSleepScore()

            prefs.saveSleepSnapshot(
                totalDurationMinutes = parsedMins,
                sleepScore = parsedScore,
                bedtimeHHMM = prefs.getSleepBedtimeHHMM(),
                wakeHHMM = prefs.getSleepWakeHHMM(),
                stagesMinutesCsv = prefs.getSleepStagesCsv(),
                wakeTimestampMs = System.currentTimeMillis()
            )

            scope.launch {
                CalendarSyncEngine.performFullSyncToWatch(
                    context = applicationContext,
                    triggerSource = "Resumo de Sono Detectado"
                )
            }
            return
        }

        // 2) Verifica se é um gatilho de Clima escolhido pelo usuário
        if (!prefs.isNotificationsSyncEnabled()) return

        val selectedPkgs = prefs.getSelectedWeatherTriggerPackages().map { it.lowercase() }.toSet()
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
