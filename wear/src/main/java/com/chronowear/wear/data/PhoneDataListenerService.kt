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
                val weatherTempsCsv = dataMap.getString("weather_temps_csv")
                val weatherHoursCsv = dataMap.getString("weather_hours_csv")
                val weatherRainProb = if (dataMap.containsKey("weather_rain_prob")) {
                    dataMap.getInt("weather_rain_prob", 65)
                } else null
                val weatherAlertSource = dataMap.getString("weather_alert_source")
                val weatherAlertHeadline = dataMap.getString("weather_alert_headline")
                val weatherAlertTsMs = if (dataMap.containsKey("weather_alert_timestamp_ms")) {
                    dataMap.getLong("weather_alert_timestamp_ms", 0L)
                } else null
                val watchStandaloneWeather = if (dataMap.containsKey("watch_standalone_weather_enabled")) {
                    dataMap.getBoolean("watch_standalone_weather_enabled", false)
                } else null
                val watchStandaloneInterval = if (dataMap.containsKey("watch_standalone_weather_interval_min")) {
                    dataMap.getInt("watch_standalone_weather_interval_min", 60)
                } else null
                val selectedTriggerPkgsCsv = dataMap.getString("selected_weather_trigger_packages_csv")

                persistAndNotify(
                    eventsJson = eventsJson,
                    syncTimestampMs = syncTimestampMs,
                    horizonDays = horizonDays,
                    progressWindowMinutes = progressWindowMinutes,
                    cardPngBase64 = cardPngB64,
                    contextMode = contextMode,
                    highFreq15MinRule = highFreq15Min,
                    updateOnScreenWake = updateOnWake,
                    weatherTempsCsv = weatherTempsCsv,
                    weatherHoursCsv = weatherHoursCsv,
                    weatherRainProb = weatherRainProb,
                    weatherAlertSource = weatherAlertSource,
                    weatherAlertHeadline = weatherAlertHeadline,
                    weatherAlertTimestampMs = weatherAlertTsMs,
                    watchStandaloneWeatherEnabled = watchStandaloneWeather,
                    watchStandaloneWeatherIntervalMin = watchStandaloneInterval,
                    selectedWeatherTriggerPackagesCsv = selectedTriggerPkgsCsv
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
                            "installed:1.1.1-wear".toByteArray(Charsets.UTF_8)
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
                    val weatherTempsCsv = json.optString("weather_temps_csv", "").takeIf { it.isNotBlank() }
                    val weatherHoursCsv = json.optString("weather_hours_csv", "").takeIf { it.isNotBlank() }
                    val weatherRainProb = if (json.has("weather_rain_prob")) {
                        json.optInt("weather_rain_prob", 65)
                    } else null
                    val weatherAlertSource = json.optString("weather_alert_source", "").takeIf { it.isNotBlank() }
                    val weatherAlertHeadline = json.optString("weather_alert_headline", "").takeIf { it.isNotBlank() }
                    val weatherAlertTsMs = if (json.has("weather_alert_timestamp_ms")) {
                        json.optLong("weather_alert_timestamp_ms", 0L)
                    } else null
                    val watchStandaloneWeather = if (json.has("watch_standalone_weather_enabled")) {
                        json.optBoolean("watch_standalone_weather_enabled", false)
                    } else null
                    val watchStandaloneInterval = if (json.has("watch_standalone_weather_interval_min")) {
                        json.optInt("watch_standalone_weather_interval_min", 60)
                    } else null
                    val selectedTriggerPkgsCsv = json.optString("selected_weather_trigger_packages_csv", "")
                        .takeIf { it.isNotBlank() }

                    persistAndNotify(
                        eventsJson = eventsArray,
                        syncTimestampMs = syncTimestampMs,
                        horizonDays = horizonDays,
                        progressWindowMinutes = progressWindowMinutes,
                        cardPngBase64 = cardPngB64,
                        contextMode = contextMode,
                        highFreq15MinRule = highFreq15Min,
                        updateOnScreenWake = updateOnWake,
                        weatherTempsCsv = weatherTempsCsv,
                        weatherHoursCsv = weatherHoursCsv,
                        weatherRainProb = weatherRainProb,
                        weatherAlertSource = weatherAlertSource,
                        weatherAlertHeadline = weatherAlertHeadline,
                        weatherAlertTimestampMs = weatherAlertTsMs,
                        watchStandaloneWeatherEnabled = watchStandaloneWeather,
                        watchStandaloneWeatherIntervalMin = watchStandaloneInterval,
                        selectedWeatherTriggerPackagesCsv = selectedTriggerPkgsCsv
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
        updateOnScreenWake: Boolean? = null,
        weatherTempsCsv: String? = null,
        weatherHoursCsv: String? = null,
        weatherRainProb: Int? = null,
        weatherAlertSource: String? = null,
        weatherAlertHeadline: String? = null,
        weatherAlertTimestampMs: Long? = null,
        watchStandaloneWeatherEnabled: Boolean? = null,
        watchStandaloneWeatherIntervalMin: Int? = null,
        selectedWeatherTriggerPackagesCsv: String? = null
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
                updateOnScreenWake = updateOnScreenWake,
                weatherTempsCsv = weatherTempsCsv,
                weatherHoursCsv = weatherHoursCsv,
                weatherRainProb = weatherRainProb,
                weatherAlertSource = weatherAlertSource,
                weatherAlertHeadline = weatherAlertHeadline,
                weatherAlertTimestampMs = weatherAlertTimestampMs,
                watchStandaloneWeatherEnabled = watchStandaloneWeatherEnabled,
                watchStandaloneWeatherIntervalMin = watchStandaloneWeatherIntervalMin,
                selectedWeatherTriggerPackagesCsv = selectedWeatherTriggerPackagesCsv
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
 * Leitor de Notificações no Relógio (`WearContextNotificationListenerService`):
 * Observa notificações sobre clima que chegam ao relógio vindas dos Apps de Clima selecionados
 * pelo usuário nas configurações (`getSelectedWeatherTriggerPackages()`, ex: App Google, Samsung Clima,
 * Climatempo, AccuWeather, The Weather Channel).
 * Quando detectada:
 * - Identifica e informa a origem do App escolhido.
 * - Aciona a atualização do clima (e se o Stand-Alone estiver ativo, atualiza localmente também).
 * - Redesenha a Janela de Contexto (Canvas) para exibir o gráfico de mudança de temperatura!
 */
class WearContextNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName.lowercase()
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
        val combined = "$title $text".lowercase()

        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        val selectedPkgs = repo.getSelectedWeatherTriggerPackages()
        val isUserSelectedWeatherTrigger = selectedPkgs.any { selected ->
            pkg == selected || pkg.contains(selected) || selected.contains(pkg)
        }

        val hasWeatherKeywords = combined.contains("chuva") ||
            combined.contains("temperatura") ||
            combined.contains("previsão") ||
            combined.contains("frente fria") ||
            combined.contains("tempestade") ||
            combined.contains("clima") ||
            combined.contains("°c") ||
            combined.contains("°")

        if (isUserSelectedWeatherTrigger && hasWeatherKeywords) {
            val sourceLabel = when {
                pkg.contains("google") -> "App Google"
                pkg.contains("daemonapp") || pkg.contains("samsung") -> "App Clima"
                pkg.contains("climatempo") -> "Climatempo"
                pkg.contains("accuweather") -> "AccuWeather"
                else -> "App Clima"
            }

            val rawSummary = when {
                title.isNotBlank() && text.isNotBlank() -> "$title: $text"
                title.isNotBlank() -> title
                text.isNotBlank() -> text
                else -> "Mudança climática detectada"
            }
            val headline = "$sourceLabel • $rawSummary".take(42)

            repo.recordWatchWeatherNotificationAlert(
                sourceAppLabel = sourceLabel,
                headline = headline,
                tempsCsv = null,
                hoursCsv = null,
                rainProbPct = null,
                nowMs = System.currentTimeMillis()
            )

            // Se o serviço Stand-Alone estiver ativo no relógio, aproveita a trigger para atualizar a curva
            if (repo.isWatchStandaloneWeatherEnabled()) {
                AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(
                    applicationContext,
                    forceRefresh = true
                )
            }

            ChronoWearComplicationUpdater.requestUpdateAllSlots(applicationContext)
            applicationContext.sendBroadcast(
                Intent(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
                    .setPackage(applicationContext.packageName)
            )
        }
    }
}
