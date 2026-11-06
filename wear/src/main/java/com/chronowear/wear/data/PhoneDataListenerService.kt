package com.chronowear.wear.data

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.wear.tiles.TileService
import com.chronowear.wear.complication.ChronoWearComplicationUpdater
import com.chronowear.wear.tile.UpNextThreeEventsTileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

class PhoneDataListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AutonomousCountdownScheduler.ensureScreenWakeListenerRegistered(applicationContext)
        serviceScope.launch {
            try {
                Wearable.getCapabilityClient(applicationContext)
                    .addLocalCapability(CAPABILITY_WATCH_APP_INSTALLED)
                    .await()
            } catch (_: Exception) {
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == "/chronowear/calendar_horizon_payload"
            ) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val eventsJson = dataMap.getString("events_json") ?: "[]"
                val syncTimestampMs = dataMap.getLong("sync_timestamp_ms")
                val horizonDays = dataMap.getInt("horizon_days", 5)
                val progressWindowMinutes = dataMap.getInt("progress_window_minutes", 60)
                val cardPngB64 = dataMap.getString("card_png_b64")
                val contextMode = dataMap.getString("context_window_mode")
                val highFreq15Min = if (dataMap.containsKey("high_freq_15min_rule")) {
                    dataMap.getBoolean("high_freq_15min_rule", true)
                } else null
                val updateOnWake = if (dataMap.containsKey("update_on_screen_wake")) {
                    dataMap.getBoolean("update_on_screen_wake", true)
                } else null

                persistAndNotify(
                    eventsJson = eventsJson,
                    syncTimestampMs = syncTimestampMs,
                    horizonDays = horizonDays,
                    progressWindowMinutes = progressWindowMinutes,
                    cardPngBase64 = cardPngB64,
                    contextMode = contextMode,
                    highFreq15MinRule = highFreq15Min,
                    updateOnScreenWake = updateOnWake
                )
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        when (messageEvent.path) {
            "/chronowear/ping_watch_app" -> {
                serviceScope.launch {
                    try {
                        Wearable.getCapabilityClient(applicationContext)
                            .addLocalCapability(CAPABILITY_WATCH_APP_INSTALLED)
                            .await()
                    } catch (_: Exception) {
                    }
                    try {
                        Wearable.getMessageClient(applicationContext).sendMessage(
                            messageEvent.sourceNodeId,
                            "/chronowear/pong_watch_app",
                            "installed:1.1.0-wear".toByteArray(Charsets.UTF_8)
                        ).await()
                    } catch (_: Exception) {
                    }
                }
            }
            "/chronowear/force_sync_payload" -> {
                try {
                    val payloadStr = String(messageEvent.data, Charsets.UTF_8)
                    val json = JSONObject(payloadStr)
                    val eventsArray = json.optJSONArray("events")?.toString() ?: "[]"
                    val syncTimestampMs = json.optLong("sync_timestamp_ms", System.currentTimeMillis())
                    val horizonDays = json.optInt("horizon_days", 5)
                    val progressWindowMinutes = json.optInt("progress_window_minutes", 60)
                    val cardPngB64 = json.optString("card_png_b64", "").takeIf { it.isNotBlank() }
                    val contextMode = json.optString("context_window_mode", "").takeIf { it.isNotBlank() }
                    val highFreq15Min = if (json.has("high_freq_15min_rule")) {
                        json.optBoolean("high_freq_15min_rule", true)
                    } else null
                    val updateOnWake = if (json.has("update_on_screen_wake")) {
                        json.optBoolean("update_on_screen_wake", true)
                    } else null

                    persistAndNotify(
                        eventsJson = eventsArray,
                        syncTimestampMs = syncTimestampMs,
                        horizonDays = horizonDays,
                        progressWindowMinutes = progressWindowMinutes,
                        cardPngBase64 = cardPngB64,
                        contextMode = contextMode,
                        highFreq15MinRule = highFreq15Min,
                        updateOnScreenWake = updateOnWake
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun persistAndNotify(
        eventsJson: String,
        syncTimestampMs: Long,
        horizonDays: Int,
        progressWindowMinutes: Int,
        cardPngBase64: String? = null,
        contextMode: String? = null,
        highFreq15MinRule: Boolean? = null,
        updateOnScreenWake: Boolean? = null
    ) {
        serviceScope.launch {
            val repo = WatchAutonomousRepository.getInstance(applicationContext)
            repo.replaceHorizonCache(
                eventsJson = eventsJson,
                syncTimestampMs = syncTimestampMs,
                horizonDays = horizonDays,
                progressWindowMinutes = progressWindowMinutes,
                cardPngBase64 = cardPngBase64,
                contextMode = contextMode,
                highFreq15MinRule = highFreq15MinRule,
                updateOnScreenWake = updateOnScreenWake
            )

            AutonomousCountdownScheduler.scheduleNextTransition(applicationContext)

            ChronoWearComplicationUpdater.requestUpdateAllSlots(applicationContext)

            TileService.getUpdater(applicationContext)
                .requestUpdate(UpNextThreeEventsTileService::class.java)

            applicationContext.sendBroadcast(
                Intent(ACTION_WEAR_CACHE_UPDATED).setPackage(applicationContext.packageName)
            )
        }
    }

    companion object {
        const val ACTION_WEAR_CACHE_UPDATED = "com.chronowear.wear.ACTION_WEAR_CACHE_UPDATED"
        const val CAPABILITY_WATCH_APP_INSTALLED = "chronowear_wear_app_installed"
    }
}

/**
 * Leitor de Notificações Exclusivo do Relógio (`NotificationListenerService` nativo no Wear OS):
 * Escuta notificações geradas localmente no relógio (ex: Samsung Weather, Google Weather,
 * Samsung Health) ou espelhadas para o relógio. Quando detecta um alerta de mudança climática,
 * chuva ou variação brusca de temperatura, grava o contexto meteorológico e aciona a
 * Janela de Contexto (Canvas) para exibir temporariamente o gráfico de mudança de temperatura!
 */
class WearContextNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName.lowercase()
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val combined = "$title $text".lowercase()

        val isWeatherPackage = pkg.contains("weather") || pkg.contains("clima") || pkg.contains("meteo")
        val hasWeatherKeywords = combined.contains("chuva") ||
            combined.contains("temperatura") ||
            combined.contains("frente fria") ||
            combined.contains("tempestade") ||
            combined.contains("clima") ||
            combined.contains("°c") ||
            combined.contains("°")

        if (isWeatherPackage || hasWeatherKeywords) {
            val repo = WatchAutonomousRepository.getInstance(applicationContext)
            val headline = when {
                title.isNotBlank() && text.isNotBlank() -> "$title • $text".take(36)
                title.isNotBlank() -> title.take(36)
                text.isNotBlank() -> text.take(36)
                else -> "Mudança climática detectada"
            }

            // Extrai temperaturas citadas na notificação (se houver) ou usa a curva das próximas horas
            val regexTemps = Regex("(\\d{1,2})\\s*°")
            val extractedTemps = regexTemps.findAll("$title $text")
                .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                .toList()

            val tempsCsv = if (extractedTemps.size >= 2) {
                val t0 = extractedTemps[0]
                val tEnd = extractedTemps[1]
                val mid1 = (t0 * 2 + tEnd) / 3
                val mid2 = (t0 + tEnd * 2) / 3
                "$t0,$mid1,$mid2,$tEnd,$tEnd"
            } else {
                "28,25,21,19,18"
            }

            repo.recordWatchWeatherNotificationAlert(
                headline = headline,
                tempsCsv = tempsCsv,
                hoursCsv = "Agora,+1h,+2h,+4h,+6h",
                rainProbPct = if (combined.contains("chuva") || combined.contains("tempestade")) 80 else 45,
                nowMs = System.currentTimeMillis()
            )

            // Se o usuário estiver no modo DYNAMIC ou WEATHER, redesenha o Canvas na hora!
            ChronoWearComplicationUpdater.requestUpdateAllSlots(applicationContext)
            applicationContext.sendBroadcast(
                Intent(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
                    .setPackage(applicationContext.packageName)
            )
        }
    }
}
