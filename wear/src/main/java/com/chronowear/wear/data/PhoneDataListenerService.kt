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
                val autoPrimaryContext = dataMap.getString("auto_primary_context")
                val unifiedCanvasHoldMin = if (dataMap.containsKey("unified_canvas_hold_min")) {
                    dataMap.getInt("unified_canvas_hold_min", 30)
                } else null
                val notifyOnCanvasChange = if (dataMap.containsKey("notify_on_canvas_change")) {
                    dataMap.getBoolean("notify_on_canvas_change", true)
                } else null
                val notifyAgenda15Min = if (dataMap.containsKey("notify_agenda_15min")) {
                    dataMap.getBoolean("notify_agenda_15min", true)
                } else null
                val autoAgendaExpMin = if (dataMap.containsKey("auto_agenda_exposure_min")) {
                    dataMap.getInt("auto_agenda_exposure_min", 30)
                } else null
                val autoWeatherExpMin = if (dataMap.containsKey("auto_weather_exposure_min")) {
                    dataMap.getInt("auto_weather_exposure_min", 30)
                } else null
                val autoSunExpMin = if (dataMap.containsKey("auto_sun_exposure_min")) {
                    dataMap.getInt("auto_sun_exposure_min", 30)
                } else null
                val autoSleepExpMin = if (dataMap.containsKey("auto_sleep_exposure_min")) {
                    dataMap.getInt("auto_sleep_exposure_min", 30)
                } else null
                val highFreq15Min = if (dataMap.containsKey("high_freq_15min_rule")) {
                    dataMap.getBoolean("high_freq_15min_rule", true)
                } else null
                val updateOnWake = if (dataMap.containsKey("update_on_screen_wake")) {
                    dataMap.getBoolean("update_on_screen_wake", true)
                } else null
                val weatherTempsCsv = dataMap.getString("weather_temps_csv")
                val weatherHoursCsv = dataMap.getString("weather_hours_csv")
                val weatherRainProb = if (dataMap.containsKey("weather_rain_prob")) {
                    dataMap.getInt("weather_rain_prob", 20)
                } else null
                val weatherConditionLabel = dataMap.getString("weather_condition_label")
                val weatherLocationName = dataMap.getString("weather_location_name")
                val weatherUpdatedHHMM = dataMap.getString("weather_updated_hhmm")
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
                val solarSunriseHHMM = dataMap.getString("solar_sunrise_hhmm")
                val solarSunsetHHMM = dataMap.getString("solar_sunset_hhmm")
                val watchStandaloneSolar = if (dataMap.containsKey("watch_standalone_solar_enabled")) {
                    dataMap.getBoolean("watch_standalone_solar_enabled", false)
                } else null
                val sleepSyncEnabled = if (dataMap.containsKey("sleep_sync_enabled")) {
                    dataMap.getBoolean("sleep_sync_enabled", true)
                } else null
                val sleepDurationMin = when {
                    dataMap.containsKey("sleep_duration_min") -> dataMap.getInt("sleep_duration_min", 462)
                    dataMap.containsKey("sleep_duration_minutes") -> dataMap.getInt("sleep_duration_minutes", 462)
                    else -> null
                }
                val sleepScore = if (dataMap.containsKey("sleep_score")) {
                    dataMap.getInt("sleep_score", 86)
                } else null
                val sleepBedtimeHHMM = dataMap.getString("sleep_bedtime_hhmm")
                val sleepWakeHHMM = dataMap.getString("sleep_wake_hhmm")
                val sleepStagesCsv = dataMap.getString("sleep_stages_csv")
                val sleepWakeTsMs = when {
                    dataMap.containsKey("sleep_wake_ts_ms") -> dataMap.getLong("sleep_wake_ts_ms", 0L)
                    dataMap.containsKey("sleep_wake_timestamp_ms") -> dataMap.getLong("sleep_wake_timestamp_ms", 0L)
                    else -> null
                }

                persistAndNotify(
                    eventsJson = eventsJson,
                    syncTimestampMs = syncTimestampMs,
                    horizonDays = horizonDays,
                    progressWindowMinutes = progressWindowMinutes,
                    cardPngBase64 = cardPngB64,
                    contextMode = contextMode,
                    autoPrimaryContext = autoPrimaryContext,
                    unifiedCanvasHoldMin = unifiedCanvasHoldMin,
                    notifyOnCanvasChange = notifyOnCanvasChange,
                    notifyAgenda15Min = notifyAgenda15Min,
                    autoAgendaExposureMin = autoAgendaExpMin,
                    autoWeatherExposureMin = autoWeatherExpMin,
                    autoSunExposureMin = autoSunExpMin,
                    autoSleepExposureMin = autoSleepExpMin,
                    highFreq15MinRule = highFreq15Min,
                    updateOnScreenWake = updateOnWake,
                    weatherTempsCsv = weatherTempsCsv,
                    weatherHoursCsv = weatherHoursCsv,
                    weatherRainProb = weatherRainProb,
                    weatherConditionLabel = weatherConditionLabel,
                    weatherLocationName = weatherLocationName,
                    weatherUpdatedHHMM = weatherUpdatedHHMM,
                    weatherAlertSource = weatherAlertSource,
                    weatherAlertHeadline = weatherAlertHeadline,
                    weatherAlertTimestampMs = weatherAlertTsMs,
                    watchStandaloneWeatherEnabled = watchStandaloneWeather,
                    watchStandaloneWeatherIntervalMin = watchStandaloneInterval,
                    selectedWeatherTriggerPackagesCsv = selectedTriggerPkgsCsv,
                    solarSunriseHHMM = solarSunriseHHMM,
                    solarSunsetHHMM = solarSunsetHHMM,
                    watchStandaloneSolarEnabled = watchStandaloneSolar,
                    sleepSyncEnabled = sleepSyncEnabled,
                    sleepDurationMinutes = sleepDurationMin,
                    sleepScore = sleepScore,
                    sleepBedtimeHHMM = sleepBedtimeHHMM,
                    sleepWakeHHMM = sleepWakeHHMM,
                    sleepStagesCsv = sleepStagesCsv,
                    sleepWakeTimestampMs = sleepWakeTsMs
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
                            "installed:1.2.1-wear".toByteArray(Charsets.UTF_8)
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
                    val autoPrimaryContext = json.optString("auto_primary_context", "").takeIf { it.isNotBlank() }
                    val unifiedCanvasHoldMin = if (json.has("unified_canvas_hold_min")) {
                        json.optInt("unified_canvas_hold_min", 30)
                    } else null
                    val notifyOnCanvasChange = if (json.has("notify_on_canvas_change")) {
                        json.optBoolean("notify_on_canvas_change", true)
                    } else null
                    val notifyAgenda15Min = if (json.has("notify_agenda_15min")) {
                        json.optBoolean("notify_agenda_15min", true)
                    } else null
                    val autoAgendaExpMin = if (json.has("auto_agenda_exposure_min")) {
                        json.optInt("auto_agenda_exposure_min", 30)
                    } else null
                    val autoWeatherExpMin = if (json.has("auto_weather_exposure_min")) {
                        json.optInt("auto_weather_exposure_min", 30)
                    } else null
                    val autoSunExpMin = if (json.has("auto_sun_exposure_min")) {
                        json.optInt("auto_sun_exposure_min", 30)
                    } else null
                    val autoSleepExpMin = if (json.has("auto_sleep_exposure_min")) {
                        json.optInt("auto_sleep_exposure_min", 30)
                    } else null
                    val highFreq15Min = if (json.has("high_freq_15min_rule")) {
                        json.optBoolean("high_freq_15min_rule", true)
                    } else null
                    val updateOnWake = if (json.has("update_on_screen_wake")) {
                        json.optBoolean("update_on_screen_wake", true)
                    } else null
                    val weatherTempsCsv = json.optString("weather_temps_csv", "").takeIf { it.isNotBlank() }
                    val weatherHoursCsv = json.optString("weather_hours_csv", "").takeIf { it.isNotBlank() }
                    val weatherRainProb = if (json.has("weather_rain_prob")) {
                        json.optInt("weather_rain_prob", 20)
                    } else null
                    val weatherConditionLabel = json.optString("weather_condition_label", "").takeIf { it.isNotBlank() }
                    val weatherLocationName = json.optString("weather_location_name", "").takeIf { it.isNotBlank() }
                    val weatherUpdatedHHMM = json.optString("weather_updated_hhmm", "").takeIf { it.isNotBlank() }
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
                    val solarSunriseHHMM = json.optString("solar_sunrise_hhmm", "").takeIf { it.isNotBlank() }
                    val solarSunsetHHMM = json.optString("solar_sunset_hhmm", "").takeIf { it.isNotBlank() }
                    val watchStandaloneSolar = if (json.has("watch_standalone_solar_enabled")) {
                        json.optBoolean("watch_standalone_solar_enabled", false)
                    } else null
                    val sleepSyncEnabled = if (json.has("sleep_sync_enabled")) {
                        json.optBoolean("sleep_sync_enabled", true)
                    } else null
                    val sleepDurationMin = when {
                        json.has("sleep_duration_min") -> json.optInt("sleep_duration_min", 462)
                        json.has("sleep_duration_minutes") -> json.optInt("sleep_duration_minutes", 462)
                        else -> null
                    }
                    val sleepScore = if (json.has("sleep_score")) {
                        json.optInt("sleep_score", 86)
                    } else null
                    val sleepBedtimeHHMM = json.optString("sleep_bedtime_hhmm", "").takeIf { it.isNotBlank() }
                    val sleepWakeHHMM = json.optString("sleep_wake_hhmm", "").takeIf { it.isNotBlank() }
                    val sleepStagesCsv = json.optString("sleep_stages_csv", "").takeIf { it.isNotBlank() }
                    val sleepWakeTsMs = when {
                        json.has("sleep_wake_ts_ms") -> json.optLong("sleep_wake_ts_ms", 0L)
                        json.has("sleep_wake_timestamp_ms") -> json.optLong("sleep_wake_timestamp_ms", 0L)
                        else -> null
                    }

                    persistAndNotify(
                        eventsJson = eventsArray,
                        syncTimestampMs = syncTimestampMs,
                        horizonDays = horizonDays,
                        progressWindowMinutes = progressWindowMinutes,
                        cardPngBase64 = cardPngB64,
                        contextMode = contextMode,
                        autoPrimaryContext = autoPrimaryContext,
                        unifiedCanvasHoldMin = unifiedCanvasHoldMin,
                        notifyOnCanvasChange = notifyOnCanvasChange,
                        notifyAgenda15Min = notifyAgenda15Min,
                        autoAgendaExposureMin = autoAgendaExpMin,
                        autoWeatherExposureMin = autoWeatherExpMin,
                        autoSunExposureMin = autoSunExpMin,
                        autoSleepExposureMin = autoSleepExpMin,
                        highFreq15MinRule = highFreq15Min,
                        updateOnScreenWake = updateOnWake,
                        weatherTempsCsv = weatherTempsCsv,
                        weatherHoursCsv = weatherHoursCsv,
                        weatherRainProb = weatherRainProb,
                        weatherConditionLabel = weatherConditionLabel,
                        weatherLocationName = weatherLocationName,
                        weatherUpdatedHHMM = weatherUpdatedHHMM,
                        weatherAlertSource = weatherAlertSource,
                        weatherAlertHeadline = weatherAlertHeadline,
                        weatherAlertTimestampMs = weatherAlertTsMs,
                        watchStandaloneWeatherEnabled = watchStandaloneWeather,
                        watchStandaloneWeatherIntervalMin = watchStandaloneInterval,
                        selectedWeatherTriggerPackagesCsv = selectedTriggerPkgsCsv,
                        solarSunriseHHMM = solarSunriseHHMM,
                        solarSunsetHHMM = solarSunsetHHMM,
                        watchStandaloneSolarEnabled = watchStandaloneSolar,
                        sleepSyncEnabled = sleepSyncEnabled,
                        sleepDurationMinutes = sleepDurationMin,
                        sleepScore = sleepScore,
                        sleepBedtimeHHMM = sleepBedtimeHHMM,
                        sleepWakeHHMM = sleepWakeHHMM,
                        sleepStagesCsv = sleepStagesCsv,
                        sleepWakeTimestampMs = sleepWakeTsMs
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
        autoPrimaryContext: String? = null,
        unifiedCanvasHoldMin: Int? = null,
        notifyOnCanvasChange: Boolean? = null,
        notifyAgenda15Min: Boolean? = null,
        autoAgendaExposureMin: Int? = null,
        autoWeatherExposureMin: Int? = null,
        autoSunExposureMin: Int? = null,
        autoSleepExposureMin: Int? = null,
        highFreq15MinRule: Boolean? = null,
        updateOnScreenWake: Boolean? = null,
        weatherTempsCsv: String? = null,
        weatherHoursCsv: String? = null,
        weatherRainProb: Int? = null,
        weatherConditionLabel: String? = null,
        weatherLocationName: String? = null,
        weatherUpdatedHHMM: String? = null,
        weatherAlertSource: String? = null,
        weatherAlertHeadline: String? = null,
        weatherAlertTimestampMs: Long? = null,
        watchStandaloneWeatherEnabled: Boolean? = null,
        watchStandaloneWeatherIntervalMin: Int? = null,
        selectedWeatherTriggerPackagesCsv: String? = null,
        solarSunriseHHMM: String? = null,
        solarSunsetHHMM: String? = null,
        watchStandaloneSolarEnabled: Boolean? = null,
        sleepSyncEnabled: Boolean? = null,
        sleepDurationMinutes: Int? = null,
        sleepScore: Int? = null,
        sleepBedtimeHHMM: String? = null,
        sleepWakeHHMM: String? = null,
        sleepStagesCsv: String? = null,
        sleepWakeTimestampMs: Long? = null
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
                autoPrimaryContext = autoPrimaryContext,
                unifiedCanvasHoldMin = unifiedCanvasHoldMin,
                notifyOnCanvasChange = notifyOnCanvasChange,
                notifyAgenda15Min = notifyAgenda15Min,
                autoAgendaExposureMin = autoAgendaExposureMin,
                autoWeatherExposureMin = autoWeatherExposureMin,
                autoSunExposureMin = autoSunExposureMin,
                autoSleepExposureMin = autoSleepExposureMin,
                highFreq15MinRule = highFreq15MinRule,
                updateOnScreenWake = updateOnScreenWake,
                weatherTempsCsv = weatherTempsCsv,
                weatherHoursCsv = weatherHoursCsv,
                weatherRainProb = weatherRainProb,
                weatherConditionLabel = weatherConditionLabel,
                weatherLocationName = weatherLocationName,
                weatherUpdatedHHMM = weatherUpdatedHHMM,
                weatherAlertSource = weatherAlertSource,
                weatherAlertHeadline = weatherAlertHeadline,
                weatherAlertTimestampMs = weatherAlertTimestampMs,
                watchStandaloneWeatherEnabled = watchStandaloneWeatherEnabled,
                watchStandaloneWeatherIntervalMin = watchStandaloneWeatherIntervalMin,
                selectedWeatherTriggerPackagesCsv = selectedWeatherTriggerPackagesCsv,
                solarSunriseHHMM = solarSunriseHHMM,
                solarSunsetHHMM = solarSunsetHHMM,
                watchStandaloneSolarEnabled = watchStandaloneSolarEnabled,
                sleepSyncEnabled = sleepSyncEnabled,
                sleepDurationMinutes = sleepDurationMinutes,
                sleepScore = sleepScore,
                sleepBedtimeHHMM = sleepBedtimeHHMM,
                sleepWakeHHMM = sleepWakeHHMM,
                sleepStagesCsv = sleepStagesCsv,
                sleepWakeTimestampMs = sleepWakeTimestampMs
            )

            AutonomousCountdownScheduler.notifyWatchOnForcedCanvasChangeIfNeeded(applicationContext)
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
                else -> "Clima"
            }

            val rawSummary = when {
                title.isNotBlank() && text.isNotBlank() -> "$title: $text"
                title.isNotBlank() -> title
                text.isNotBlank() -> text
                else -> "Mudança climática detectada"
            }
            val headline = "$sourceLabel • $rawSummary".take(44)

            repo.recordWatchWeatherNotificationAlert(
                sourceAppLabel = sourceLabel,
                headline = headline,
                tempsCsv = null,
                hoursCsv = null,
                rainProbPct = null,
                nowMs = System.currentTimeMillis()
            )

            if (repo.isWatchStandaloneWeatherEnabled()) {
                AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(
                    applicationContext,
                    forceRefresh = true
                )
            }

            AutonomousCountdownScheduler.notifyWatchOnForcedCanvasChangeIfNeeded(
                applicationContext,
                headline
            )
            AutonomousCountdownScheduler.scheduleNextTransition(applicationContext)
            ChronoWearComplicationUpdater.requestUpdateAllSlots(applicationContext)
            applicationContext.sendBroadcast(
                Intent(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
                    .setPackage(applicationContext.packageName)
            )
            return
        }

        val isSleepNotification = (
            pkg.contains("shealth") ||
                pkg.contains("health") ||
                pkg.contains("sleep") ||
                pkg.contains("fitness")
            ) && (
            combined.contains("sono") ||
                combined.contains("sleep") ||
                combined.contains("pontuação do sono") ||
                combined.contains("dormiu")
            )

        if (isSleepNotification && repo.isSleepSyncEnabled()) {
            repo.recordWatchSleepNotification(nowMs = System.currentTimeMillis())
            AutonomousCountdownScheduler.notifyWatchOnForcedCanvasChangeIfNeeded(applicationContext)
            AutonomousCountdownScheduler.scheduleNextTransition(applicationContext)
            ChronoWearComplicationUpdater.requestUpdateAllSlots(applicationContext)
            applicationContext.sendBroadcast(
                Intent(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
                    .setPackage(applicationContext.packageName)
            )
        }
    }
}
