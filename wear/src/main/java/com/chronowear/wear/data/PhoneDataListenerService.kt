package com.chronowear.wear.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.wear.tiles.TileService
import com.chronowear.wear.complication.ChronoWearComplicationUpdater
import com.chronowear.wear.tile.UpNextThreeEventsTileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
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
            try {
                pullLatestDataItemIfNewer(applicationContext)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val extracted = mutableListOf<Pair<SyncPayloadFields, String?>>()
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == DATA_LAYER_PATH
            ) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val replyNodeId = event.dataItem.uri.host?.takeIf { it.isNotBlank() }
                extracted.add(extractFromDataMap(dataMap) to replyNodeId)
            }
        }
        if (extracted.isEmpty()) return

        runBlocking {
            withTimeoutOrNull(12_000L) {
                for ((payload, replyNodeId) in extracted) {
                    persistAndNotifySync(
                        appCtx = applicationContext,
                        payload = payload,
                        replyNodeId = replyNodeId,
                        skipIfSameTimestampApplied = true
                    )
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        when (messageEvent.path) {
            "/chronowear/ping_watch_app" -> {
                runBlocking {
                    withTimeoutOrNull(8_000L) {
                        try {
                            Wearable.getCapabilityClient(applicationContext)
                                .addLocalCapability(CAPABILITY_WATCH_APP_INSTALLED)
                                .await()
                        } catch (_: Exception) {
                        }
                        try {
                            val pongPayload = "installed:1.2.1-wear|${Build.MODEL}".toByteArray(Charsets.UTF_8)
                            Wearable.getMessageClient(applicationContext).sendMessage(
                                messageEvent.sourceNodeId,
                                "/chronowear/pong_watch_app",
                                pongPayload
                            ).await()
                        } catch (_: Exception) {
                        }
                        pullLatestDataItemIfNewer(
                            context = applicationContext,
                            replyNodeId = messageEvent.sourceNodeId,
                            forceAckIfAlreadyApplied = false
                        )
                        sendWatchBatteryReportToPhone(applicationContext, messageEvent.sourceNodeId)
                    }
                }
            }

            "/chronowear/wake_and_pull_data" -> {
                runBlocking {
                    withTimeoutOrNull(12_000L) {
                        val expectedTs = String(messageEvent.data, Charsets.UTF_8)
                            .trim()
                            .toLongOrNull() ?: 0L
                        val pulled = pullLatestDataItemIfNewer(
                            context = applicationContext,
                            replyNodeId = messageEvent.sourceNodeId,
                            forceAckIfAlreadyApplied = true
                        )
                        val repoTs = WatchAutonomousRepository.getInstance(applicationContext)
                            .getLastSyncTimestampMs()
                        if (!pulled && expectedTs > repoTs) {
                            try {
                                Wearable.getMessageClient(applicationContext).sendMessage(
                                    messageEvent.sourceNodeId,
                                    "/chronowear/request_sync_from_watch",
                                    "pull_miss:$expectedTs".toByteArray(Charsets.UTF_8)
                                ).await()
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            }

            "/chronowear/force_sync_payload" -> {
                try {
                    val payloadStr = String(messageEvent.data, Charsets.UTF_8)
                    val json = JSONObject(payloadStr)
                    val payload = extractFromJson(json)
                    runBlocking {
                        withTimeoutOrNull(12_000L) {
                            persistAndNotifySync(
                                appCtx = applicationContext,
                                payload = payload,
                                replyNodeId = messageEvent.sourceNodeId,
                                skipIfSameTimestampApplied = false
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    data class SyncPayloadFields(
        val eventsJson: String,
        val eventsCount: Int,
        val syncTimestampMs: Long,
        val syncIntervalMinutes: Int? = null,
        val horizonDays: Int,
        val progressWindowMinutes: Int,
        val cardPngBase64: String? = null,
        val contextMode: String? = null,
        val autoPrimaryContext: String? = null,
        val unifiedCanvasHoldMin: Int? = null,
        val notifyOnCanvasChange: Boolean? = null,
        val notifyAgenda15Min: Boolean? = null,
        val autoAgendaExposureMin: Int? = null,
        val autoWeatherExposureMin: Int? = null,
        val autoSunExposureMin: Int? = null,
        val autoSleepExposureMin: Int? = null,
        val highFreq15MinRule: Boolean? = null,
        val updateOnScreenWake: Boolean? = null,
        val updateOnCanvasTap: Boolean? = null,
        val realtimeSyncHighTierMin: Int? = null,
        val realtimeSyncLowTierMin: Int? = null,
        val weatherTempsCsv: String? = null,
        val weatherHoursCsv: String? = null,
        val weatherRainProb: Int? = null,
        val weatherConditionLabel: String? = null,
        val weatherLocationName: String? = null,
        val weatherUpdatedHHMM: String? = null,
        val weatherAlertSource: String? = null,
        val weatherAlertHeadline: String? = null,
        val weatherAlertTimestampMs: Long? = null,
        val watchStandaloneWeatherEnabled: Boolean? = null,
        val watchStandaloneWeatherIntervalMin: Int? = null,
        val selectedWeatherTriggerPackagesCsv: String? = null,
        val solarSunriseHHMM: String? = null,
        val solarSunsetHHMM: String? = null,
        val watchStandaloneSolarEnabled: Boolean? = null,
        val sleepSyncEnabled: Boolean? = null,
        val sleepShowScore: Boolean? = null,
        val sleepDurationMinutes: Int? = null,
        val sleepScore: Int? = null,
        val sleepBedtimeHHMM: String? = null,
        val sleepWakeHHMM: String? = null,
        val sleepStagesCsv: String? = null,
        val sleepWakeTimestampMs: Long? = null,
        val sleepHasDataToday: Boolean? = null,
        val stepsToday: Int? = null,
        val stepsGoal: Int? = null,
        val stepsWeekCsv: String? = null,
        val stepsWeekLabelsCsv: String? = null,
        val tasksSyncEnabled: Boolean? = null,
        val tasksJson: String? = null,
        val tasksAlertTimestampMs: Long? = null,
        val autoFallbackAgendaToTasks: Boolean? = null,
        val autoTriggerAgendaTasksEnabled: Boolean? = null,
        val autoTriggerAgendaTasksLeadMin: Int? = null,
        val autoTriggerStepsEnabled: Boolean? = null,
        val autoTriggerStepsMilestone: String? = null,
        val autoTriggerSunEnabled: Boolean? = null,
        val autoTriggerSunLeadMin: Int? = null,
        val autoTriggerSleepEnabled: Boolean? = null,
        val autoTriggerSleepMode: String? = null,
        val autoTriggerWeatherEnabled: Boolean? = null,
        val autoTriggerWeatherMode: String? = null,
        val autoTriggerBatteryEnabled: Boolean? = null,
        val autoTriggerBatteryMode: String? = null,
        val batteryGraphWindowHours: Int? = null,
        val weatherLat: Double? = null,
        val weatherLon: Double? = null
    )

    companion object {
        const val ACTION_WEAR_CACHE_UPDATED = "com.chronowear.wear.ACTION_WEAR_CACHE_UPDATED"
        const val CAPABILITY_WATCH_APP_INSTALLED = "chronowear_wear_app_installed"
        const val DATA_LAYER_PATH = "/chronowear/calendar_horizon_payload"

        private fun safeJsonArrayLength(jsonStr: String): Int {
            return try {
                JSONArray(jsonStr).length()
            } catch (_: Exception) {
                0
            }
        }

        fun extractFromDataMap(dataMap: DataMap): SyncPayloadFields {
            val eventsJson = dataMap.getString("events_json") ?: "[]"
            return SyncPayloadFields(
                eventsJson = eventsJson,
                eventsCount = safeJsonArrayLength(eventsJson),
                syncTimestampMs = dataMap.getLong("sync_timestamp_ms"),
                syncIntervalMinutes = if (dataMap.containsKey("sync_interval_minutes")) {
                    dataMap.getInt("sync_interval_minutes", 15)
                } else null,
                horizonDays = dataMap.getInt("horizon_days", 5),
                progressWindowMinutes = dataMap.getInt("progress_window_minutes", 60),
                cardPngBase64 = dataMap.getString("card_png_b64"),
                contextMode = dataMap.getString("context_window_mode"),
                autoPrimaryContext = dataMap.getString("auto_primary_context"),
                unifiedCanvasHoldMin = if (dataMap.containsKey("unified_canvas_hold_min")) {
                    dataMap.getInt("unified_canvas_hold_min", 30)
                } else null,
                notifyOnCanvasChange = if (dataMap.containsKey("notify_on_canvas_change")) {
                    dataMap.getBoolean("notify_on_canvas_change", true)
                } else null,
                notifyAgenda15Min = if (dataMap.containsKey("notify_agenda_15min")) {
                    dataMap.getBoolean("notify_agenda_15min", true)
                } else null,
                autoAgendaExposureMin = if (dataMap.containsKey("auto_agenda_exposure_min")) {
                    dataMap.getInt("auto_agenda_exposure_min", 30)
                } else null,
                autoWeatherExposureMin = if (dataMap.containsKey("auto_weather_exposure_min")) {
                    dataMap.getInt("auto_weather_exposure_min", 30)
                } else null,
                autoSunExposureMin = if (dataMap.containsKey("auto_sun_exposure_min")) {
                    dataMap.getInt("auto_sun_exposure_min", 30)
                } else null,
                autoSleepExposureMin = if (dataMap.containsKey("auto_sleep_exposure_min")) {
                    dataMap.getInt("auto_sleep_exposure_min", 30)
                } else null,
                highFreq15MinRule = if (dataMap.containsKey("high_freq_15min_rule")) {
                    dataMap.getBoolean("high_freq_15min_rule", true)
                } else null,
                updateOnScreenWake = if (dataMap.containsKey("update_on_screen_wake")) {
                    dataMap.getBoolean("update_on_screen_wake", true)
                } else null,
                updateOnCanvasTap = if (dataMap.containsKey("update_on_canvas_tap")) {
                    dataMap.getBoolean("update_on_canvas_tap", true)
                } else null,
                realtimeSyncHighTierMin = if (dataMap.containsKey("realtime_sync_high_tier_min")) {
                    dataMap.getInt("realtime_sync_high_tier_min", 2)
                } else null,
                realtimeSyncLowTierMin = if (dataMap.containsKey("realtime_sync_low_tier_min")) {
                    dataMap.getInt("realtime_sync_low_tier_min", 10)
                } else null,
                weatherTempsCsv = dataMap.getString("weather_temps_csv"),
                weatherHoursCsv = dataMap.getString("weather_hours_csv"),
                weatherRainProb = if (dataMap.containsKey("weather_rain_prob")) {
                    dataMap.getInt("weather_rain_prob", 20)
                } else null,
                weatherConditionLabel = dataMap.getString("weather_condition_label"),
                weatherLocationName = dataMap.getString("weather_location_name"),
                weatherUpdatedHHMM = dataMap.getString("weather_updated_hhmm"),
                weatherAlertSource = dataMap.getString("weather_alert_source"),
                weatherAlertHeadline = dataMap.getString("weather_alert_headline"),
                weatherAlertTimestampMs = if (dataMap.containsKey("weather_alert_timestamp_ms")) {
                    dataMap.getLong("weather_alert_timestamp_ms", 0L)
                } else null,
                watchStandaloneWeatherEnabled = if (dataMap.containsKey("watch_standalone_weather_enabled")) {
                    dataMap.getBoolean("watch_standalone_weather_enabled", false)
                } else null,
                watchStandaloneWeatherIntervalMin = if (dataMap.containsKey("watch_standalone_weather_interval_min")) {
                    dataMap.getInt("watch_standalone_weather_interval_min", 60)
                } else null,
                selectedWeatherTriggerPackagesCsv = dataMap.getString("selected_weather_trigger_packages_csv"),
                solarSunriseHHMM = dataMap.getString("solar_sunrise_hhmm"),
                solarSunsetHHMM = dataMap.getString("solar_sunset_hhmm"),
                watchStandaloneSolarEnabled = if (dataMap.containsKey("watch_standalone_solar_enabled")) {
                    dataMap.getBoolean("watch_standalone_solar_enabled", false)
                } else null,
                sleepSyncEnabled = if (dataMap.containsKey("sleep_sync_enabled")) {
                    dataMap.getBoolean("sleep_sync_enabled", true)
                } else null,
                sleepShowScore = if (dataMap.containsKey("sleep_show_score")) {
                    dataMap.getBoolean("sleep_show_score", true)
                } else null,
                sleepDurationMinutes = when {
                    dataMap.containsKey("sleep_duration_min") -> dataMap.getInt("sleep_duration_min", 0)
                    dataMap.containsKey("sleep_duration_minutes") -> dataMap.getInt("sleep_duration_minutes", 0)
                    else -> null
                },
                sleepScore = if (dataMap.containsKey("sleep_score")) {
                    dataMap.getInt("sleep_score", 0)
                } else null,
                sleepBedtimeHHMM = dataMap.getString("sleep_bedtime_hhmm"),
                sleepWakeHHMM = dataMap.getString("sleep_wake_hhmm"),
                sleepStagesCsv = dataMap.getString("sleep_stages_csv"),
                sleepWakeTimestampMs = when {
                    dataMap.containsKey("sleep_wake_ts_ms") -> dataMap.getLong("sleep_wake_ts_ms", 0L)
                    dataMap.containsKey("sleep_wake_timestamp_ms") -> dataMap.getLong("sleep_wake_timestamp_ms", 0L)
                    else -> null
                },
                sleepHasDataToday = if (dataMap.containsKey("sleep_has_data_today")) {
                    dataMap.getBoolean("sleep_has_data_today", false)
                } else null,
                stepsToday = if (dataMap.containsKey("steps_today")) {
                    dataMap.getInt("steps_today", 0)
                } else null,
                stepsGoal = if (dataMap.containsKey("steps_goal")) {
                    dataMap.getInt("steps_goal", 10000)
                } else null,
                stepsWeekCsv = dataMap.getString("steps_week_csv"),
                stepsWeekLabelsCsv = dataMap.getString("steps_week_labels_csv"),
                tasksSyncEnabled = if (dataMap.containsKey("tasks_sync_enabled")) {
                    dataMap.getBoolean("tasks_sync_enabled", true)
                } else null,
                tasksJson = dataMap.getString("tasks_json"),
                tasksAlertTimestampMs = if (dataMap.containsKey("tasks_alert_timestamp_ms")) {
                    dataMap.getLong("tasks_alert_timestamp_ms", 0L)
                } else null,
                autoFallbackAgendaToTasks = if (dataMap.containsKey("auto_fallback_agenda_to_tasks")) {
                    dataMap.getBoolean("auto_fallback_agenda_to_tasks", true)
                } else null,
                autoTriggerAgendaTasksEnabled = if (dataMap.containsKey("auto_trigger_agenda_tasks_enabled")) {
                    dataMap.getBoolean("auto_trigger_agenda_tasks_enabled", true)
                } else null,
                autoTriggerAgendaTasksLeadMin = if (dataMap.containsKey("auto_trigger_agenda_tasks_lead_min")) {
                    dataMap.getInt("auto_trigger_agenda_tasks_lead_min", 15)
                } else null,
                autoTriggerStepsEnabled = if (dataMap.containsKey("auto_trigger_steps_enabled")) {
                    dataMap.getBoolean("auto_trigger_steps_enabled", true)
                } else null,
                autoTriggerStepsMilestone = dataMap.getString("auto_trigger_steps_milestone"),
                autoTriggerSunEnabled = if (dataMap.containsKey("auto_trigger_sun_enabled")) {
                    dataMap.getBoolean("auto_trigger_sun_enabled", true)
                } else null,
                autoTriggerSunLeadMin = if (dataMap.containsKey("auto_trigger_sun_lead_min")) {
                    dataMap.getInt("auto_trigger_sun_lead_min", 15)
                } else null,
                autoTriggerSleepEnabled = if (dataMap.containsKey("auto_trigger_sleep_enabled")) {
                    dataMap.getBoolean("auto_trigger_sleep_enabled", true)
                } else null,
                autoTriggerSleepMode = dataMap.getString("auto_trigger_sleep_mode"),
                autoTriggerWeatherEnabled = if (dataMap.containsKey("auto_trigger_weather_enabled")) {
                    dataMap.getBoolean("auto_trigger_weather_enabled", true)
                } else null,
                autoTriggerWeatherMode = dataMap.getString("auto_trigger_weather_mode"),
                autoTriggerBatteryEnabled = if (dataMap.containsKey("auto_trigger_battery_enabled")) {
                    dataMap.getBoolean("auto_trigger_battery_enabled", true)
                } else null,
                autoTriggerBatteryMode = dataMap.getString("auto_trigger_battery_mode"),
                batteryGraphWindowHours = if (dataMap.containsKey("battery_graph_window_hours")) {
                    dataMap.getInt("battery_graph_window_hours", 12)
                } else null,
                weatherLat = if (dataMap.containsKey("weather_lat")) {
                    dataMap.getDouble("weather_lat", 0.0)
                } else null,
                weatherLon = if (dataMap.containsKey("weather_lon")) {
                    dataMap.getDouble("weather_lon", 0.0)
                } else null
            )
        }

        fun extractFromJson(json: JSONObject): SyncPayloadFields {
            val eventsArr = json.optJSONArray("events")
            val eventsArrayStr = eventsArr?.toString() ?: "[]"
            return SyncPayloadFields(
                eventsJson = eventsArrayStr,
                eventsCount = eventsArr?.length() ?: 0,
                syncTimestampMs = json.optLong("sync_timestamp_ms", System.currentTimeMillis()),
                syncIntervalMinutes = if (json.has("sync_interval_minutes")) {
                    json.optInt("sync_interval_minutes", 15)
                } else null,
                horizonDays = json.optInt("horizon_days", 5),
                progressWindowMinutes = json.optInt("progress_window_minutes", 60),
                cardPngBase64 = json.optString("card_png_b64", "").takeIf { it.isNotBlank() },
                contextMode = json.optString("context_window_mode", "").takeIf { it.isNotBlank() },
                autoPrimaryContext = json.optString("auto_primary_context", "").takeIf { it.isNotBlank() },
                unifiedCanvasHoldMin = if (json.has("unified_canvas_hold_min")) {
                    json.optInt("unified_canvas_hold_min", 30)
                } else null,
                notifyOnCanvasChange = if (json.has("notify_on_canvas_change")) {
                    json.optBoolean("notify_on_canvas_change", true)
                } else null,
                notifyAgenda15Min = if (json.has("notify_agenda_15min")) {
                    json.optBoolean("notify_agenda_15min", true)
                } else null,
                autoAgendaExposureMin = if (json.has("auto_agenda_exposure_min")) {
                    json.optInt("auto_agenda_exposure_min", 30)
                } else null,
                autoWeatherExposureMin = if (json.has("auto_weather_exposure_min")) {
                    json.optInt("auto_weather_exposure_min", 30)
                } else null,
                autoSunExposureMin = if (json.has("auto_sun_exposure_min")) {
                    json.optInt("auto_sun_exposure_min", 30)
                } else null,
                autoSleepExposureMin = if (json.has("auto_sleep_exposure_min")) {
                    json.optInt("auto_sleep_exposure_min", 30)
                } else null,
                highFreq15MinRule = if (json.has("high_freq_15min_rule")) {
                    json.optBoolean("high_freq_15min_rule", true)
                } else null,
                updateOnScreenWake = if (json.has("update_on_screen_wake")) {
                    json.optBoolean("update_on_screen_wake", true)
                } else null,
                updateOnCanvasTap = if (json.has("update_on_canvas_tap")) {
                    json.optBoolean("update_on_canvas_tap", true)
                } else null,
                realtimeSyncHighTierMin = if (json.has("realtime_sync_high_tier_min")) {
                    json.optInt("realtime_sync_high_tier_min", 2)
                } else null,
                realtimeSyncLowTierMin = if (json.has("realtime_sync_low_tier_min")) {
                    json.optInt("realtime_sync_low_tier_min", 10)
                } else null,
                weatherTempsCsv = json.optString("weather_temps_csv", "").takeIf { it.isNotBlank() },
                weatherHoursCsv = json.optString("weather_hours_csv", "").takeIf { it.isNotBlank() },
                weatherRainProb = if (json.has("weather_rain_prob")) {
                    json.optInt("weather_rain_prob", 20)
                } else null,
                weatherConditionLabel = json.optString("weather_condition_label", "").takeIf { it.isNotBlank() },
                weatherLocationName = json.optString("weather_location_name", "").takeIf { it.isNotBlank() },
                weatherUpdatedHHMM = json.optString("weather_updated_hhmm", "").takeIf { it.isNotBlank() },
                weatherAlertSource = json.optString("weather_alert_source", "").takeIf { it.isNotBlank() },
                weatherAlertHeadline = json.optString("weather_alert_headline", "").takeIf { it.isNotBlank() },
                weatherAlertTimestampMs = if (json.has("weather_alert_timestamp_ms")) {
                    json.optLong("weather_alert_timestamp_ms", 0L)
                } else null,
                watchStandaloneWeatherEnabled = if (json.has("watch_standalone_weather_enabled")) {
                    json.optBoolean("watch_standalone_weather_enabled", false)
                } else null,
                watchStandaloneWeatherIntervalMin = if (json.has("watch_standalone_weather_interval_min")) {
                    json.optInt("watch_standalone_weather_interval_min", 60)
                } else null,
                selectedWeatherTriggerPackagesCsv = json.optString("selected_weather_trigger_packages_csv", "")
                    .takeIf { it.isNotBlank() },
                solarSunriseHHMM = json.optString("solar_sunrise_hhmm", "").takeIf { it.isNotBlank() },
                solarSunsetHHMM = json.optString("solar_sunset_hhmm", "").takeIf { it.isNotBlank() },
                watchStandaloneSolarEnabled = if (json.has("watch_standalone_solar_enabled")) {
                    json.optBoolean("watch_standalone_solar_enabled", false)
                } else null,
                sleepSyncEnabled = if (json.has("sleep_sync_enabled")) {
                    json.optBoolean("sleep_sync_enabled", true)
                } else null,
                sleepShowScore = if (json.has("sleep_show_score")) {
                    json.optBoolean("sleep_show_score", true)
                } else null,
                sleepDurationMinutes = when {
                    json.has("sleep_duration_min") -> json.optInt("sleep_duration_min", 0)
                    json.has("sleep_duration_minutes") -> json.optInt("sleep_duration_minutes", 0)
                    else -> null
                },
                sleepScore = if (json.has("sleep_score")) {
                    json.optInt("sleep_score", 0)
                } else null,
                sleepBedtimeHHMM = json.optString("sleep_bedtime_hhmm", "").takeIf { it.isNotBlank() },
                sleepWakeHHMM = json.optString("sleep_wake_hhmm", "").takeIf { it.isNotBlank() },
                sleepStagesCsv = json.optString("sleep_stages_csv", "").takeIf { it.isNotBlank() },
                sleepWakeTimestampMs = when {
                    json.has("sleep_wake_ts_ms") -> json.optLong("sleep_wake_ts_ms", 0L)
                    json.has("sleep_wake_timestamp_ms") -> json.optLong("sleep_wake_timestamp_ms", 0L)
                    else -> null
                },
                sleepHasDataToday = if (json.has("sleep_has_data_today")) {
                    json.optBoolean("sleep_has_data_today", false)
                } else null,
                stepsToday = if (json.has("steps_today")) {
                    json.optInt("steps_today", 0)
                } else null,
                stepsGoal = if (json.has("steps_goal")) {
                    json.optInt("steps_goal", 10000)
                } else null,
                stepsWeekCsv = json.optString("steps_week_csv", "").takeIf { it.isNotBlank() },
                stepsWeekLabelsCsv = json.optString("steps_week_labels_csv", "").takeIf { it.isNotBlank() },
                tasksSyncEnabled = if (json.has("tasks_sync_enabled")) {
                    json.optBoolean("tasks_sync_enabled", true)
                } else null,
                tasksJson = json.optString("tasks_json", "").takeIf { it.isNotBlank() },
                tasksAlertTimestampMs = if (json.has("tasks_alert_timestamp_ms")) {
                    json.optLong("tasks_alert_timestamp_ms", 0L)
                } else null,
                autoFallbackAgendaToTasks = if (json.has("auto_fallback_agenda_to_tasks")) {
                    json.optBoolean("auto_fallback_agenda_to_tasks", true)
                } else null,
                autoTriggerAgendaTasksEnabled = if (json.has("auto_trigger_agenda_tasks_enabled")) {
                    json.optBoolean("auto_trigger_agenda_tasks_enabled", true)
                } else null,
                autoTriggerAgendaTasksLeadMin = if (json.has("auto_trigger_agenda_tasks_lead_min")) {
                    json.optInt("auto_trigger_agenda_tasks_lead_min", 15)
                } else null,
                autoTriggerStepsEnabled = if (json.has("auto_trigger_steps_enabled")) {
                    json.optBoolean("auto_trigger_steps_enabled", true)
                } else null,
                autoTriggerStepsMilestone = json.optString("auto_trigger_steps_milestone", "").takeIf { it.isNotBlank() },
                autoTriggerSunEnabled = if (json.has("auto_trigger_sun_enabled")) {
                    json.optBoolean("auto_trigger_sun_enabled", true)
                } else null,
                autoTriggerSunLeadMin = if (json.has("auto_trigger_sun_lead_min")) {
                    json.optInt("auto_trigger_sun_lead_min", 15)
                } else null,
                autoTriggerSleepEnabled = if (json.has("auto_trigger_sleep_enabled")) {
                    json.optBoolean("auto_trigger_sleep_enabled", true)
                } else null,
                autoTriggerSleepMode = json.optString("auto_trigger_sleep_mode", "").takeIf { it.isNotBlank() },
                autoTriggerWeatherEnabled = if (json.has("auto_trigger_weather_enabled")) {
                    json.optBoolean("auto_trigger_weather_enabled", true)
                } else null,
                autoTriggerWeatherMode = json.optString("auto_trigger_weather_mode", "").takeIf { it.isNotBlank() },
                autoTriggerBatteryEnabled = if (json.has("auto_trigger_battery_enabled")) {
                    json.optBoolean("auto_trigger_battery_enabled", true)
                } else null,
                autoTriggerBatteryMode = json.optString("auto_trigger_battery_mode", "").takeIf { it.isNotBlank() },
                batteryGraphWindowHours = if (json.has("battery_graph_window_hours")) {
                    json.optInt("battery_graph_window_hours", 12)
                } else null,
                weatherLat = if (json.has("weather_lat")) {
                    json.optDouble("weather_lat", 0.0)
                } else null,
                weatherLon = if (json.has("weather_lon")) {
                    json.optDouble("weather_lon", 0.0)
                } else null
            )
        }

        /**
         * Busca ativamente o DataItem mais recente armazenado no DataClient do Play Services.
         * Essencial em cenários com 2 relógios ou quando o segundo relógio estava dormindo/trocado
         * no momento em que o smartphone gravou o DataItem ou quando o MessageClient falhou.
         */
        suspend fun pullLatestDataItemIfNewer(
            context: Context,
            replyNodeId: String? = null,
            forceAckIfAlreadyApplied: Boolean = false
        ): Boolean {
            val appCtx = context.applicationContext

            var bestPayload: SyncPayloadFields? = null
            var bestNodeHost: String? = null

            val buffer = try {
                Wearable.getDataClient(appCtx).dataItems.await()
            } catch (_: Exception) {
                null
            } ?: return false

            try {
                for (item in buffer) {
                    if (item.uri.path == DATA_LAYER_PATH) {
                        val dm = DataMapItem.fromDataItem(item).dataMap
                        val parsed = extractFromDataMap(dm)
                        if (bestPayload == null || parsed.syncTimestampMs > bestPayload.syncTimestampMs) {
                            bestPayload = parsed
                            bestNodeHost = item.uri.host?.takeIf { it.isNotBlank() }
                        }
                    }
                }
            } finally {
                buffer.release()
            }

            val chosen = bestPayload ?: return false
            if (chosen.syncTimestampMs <= 0L) return false

            val repo = WatchAutonomousRepository.getInstance(appCtx)
            val currentSyncTs = repo.getLastSyncTimestampMs()
            val targetReplyNode = replyNodeId ?: bestNodeHost

            if (chosen.syncTimestampMs > currentSyncTs) {
                persistAndNotifySync(
                    appCtx = appCtx,
                    payload = chosen,
                    replyNodeId = targetReplyNode,
                    skipIfSameTimestampApplied = false
                )
                return true
            } else if (forceAckIfAlreadyApplied) {
                sendSyncAckToPhone(
                    appCtx = appCtx,
                    syncTimestampMs = chosen.syncTimestampMs,
                    eventsCount = chosen.eventsCount,
                    targetNodeId = targetReplyNode
                )
            }
            return false
        }

        suspend fun persistAndNotifySync(
            appCtx: Context,
            payload: SyncPayloadFields,
            replyNodeId: String? = null,
            skipIfSameTimestampApplied: Boolean = true
        ) {
            val repo = WatchAutonomousRepository.getInstance(appCtx)
            val alreadyAppliedTs = repo.getLastSyncTimestampMs()
            if (skipIfSameTimestampApplied &&
                payload.syncTimestampMs > 0L &&
                payload.syncTimestampMs == alreadyAppliedTs
            ) {
                sendSyncAckToPhone(
                    appCtx = appCtx,
                    syncTimestampMs = payload.syncTimestampMs,
                    eventsCount = payload.eventsCount,
                    targetNodeId = replyNodeId
                )
                return
            }

            repo.replaceHorizonCache(
                eventsJson = payload.eventsJson,
                syncTimestampMs = payload.syncTimestampMs,
                syncIntervalMinutes = payload.syncIntervalMinutes,
                horizonDays = payload.horizonDays,
                progressWindowMinutes = payload.progressWindowMinutes,
                cardPngBase64 = payload.cardPngBase64,
                contextMode = payload.contextMode,
                autoPrimaryContext = payload.autoPrimaryContext,
                unifiedCanvasHoldMin = payload.unifiedCanvasHoldMin,
                notifyOnCanvasChange = payload.notifyOnCanvasChange,
                notifyAgenda15Min = payload.notifyAgenda15Min,
                autoAgendaExposureMin = payload.autoAgendaExposureMin,
                autoWeatherExposureMin = payload.autoWeatherExposureMin,
                autoSunExposureMin = payload.autoSunExposureMin,
                autoSleepExposureMin = payload.autoSleepExposureMin,
                highFreq15MinRule = payload.highFreq15MinRule,
                updateOnScreenWake = payload.updateOnScreenWake,
                updateOnCanvasTap = payload.updateOnCanvasTap,
                realtimeSyncHighTierMin = payload.realtimeSyncHighTierMin,
                realtimeSyncLowTierMin = payload.realtimeSyncLowTierMin,
                weatherTempsCsv = payload.weatherTempsCsv,
                weatherHoursCsv = payload.weatherHoursCsv,
                weatherRainProb = payload.weatherRainProb,
                weatherConditionLabel = payload.weatherConditionLabel,
                weatherLocationName = payload.weatherLocationName,
                weatherUpdatedHHMM = payload.weatherUpdatedHHMM,
                weatherAlertSource = payload.weatherAlertSource,
                weatherAlertHeadline = payload.weatherAlertHeadline,
                weatherAlertTimestampMs = payload.weatherAlertTimestampMs,
                watchStandaloneWeatherEnabled = payload.watchStandaloneWeatherEnabled,
                watchStandaloneWeatherIntervalMin = payload.watchStandaloneWeatherIntervalMin,
                selectedWeatherTriggerPackagesCsv = payload.selectedWeatherTriggerPackagesCsv,
                solarSunriseHHMM = payload.solarSunriseHHMM,
                solarSunsetHHMM = payload.solarSunsetHHMM,
                watchStandaloneSolarEnabled = payload.watchStandaloneSolarEnabled,
                sleepSyncEnabled = payload.sleepSyncEnabled,
                sleepShowScore = payload.sleepShowScore,
                sleepDurationMinutes = payload.sleepDurationMinutes,
                sleepScore = payload.sleepScore,
                sleepBedtimeHHMM = payload.sleepBedtimeHHMM,
                sleepWakeHHMM = payload.sleepWakeHHMM,
                sleepStagesCsv = payload.sleepStagesCsv,
                sleepWakeTimestampMs = payload.sleepWakeTimestampMs,
                sleepHasDataToday = payload.sleepHasDataToday,
                stepsToday = payload.stepsToday,
                stepsGoal = payload.stepsGoal,
                stepsWeekCsv = payload.stepsWeekCsv,
                stepsWeekLabelsCsv = payload.stepsWeekLabelsCsv,
                tasksSyncEnabled = payload.tasksSyncEnabled,
                tasksJson = payload.tasksJson,
                tasksAlertTimestampMs = payload.tasksAlertTimestampMs,
                autoFallbackAgendaToTasks = payload.autoFallbackAgendaToTasks,
                autoTriggerAgendaTasksEnabled = payload.autoTriggerAgendaTasksEnabled,
                autoTriggerAgendaTasksLeadMin = payload.autoTriggerAgendaTasksLeadMin,
                autoTriggerStepsEnabled = payload.autoTriggerStepsEnabled,
                autoTriggerStepsMilestone = payload.autoTriggerStepsMilestone,
                autoTriggerSunEnabled = payload.autoTriggerSunEnabled,
                autoTriggerSunLeadMin = payload.autoTriggerSunLeadMin,
                autoTriggerSleepEnabled = payload.autoTriggerSleepEnabled,
                autoTriggerSleepMode = payload.autoTriggerSleepMode,
                autoTriggerWeatherEnabled = payload.autoTriggerWeatherEnabled,
                autoTriggerWeatherMode = payload.autoTriggerWeatherMode,
                autoTriggerBatteryEnabled = payload.autoTriggerBatteryEnabled,
                autoTriggerBatteryMode = payload.autoTriggerBatteryMode,
                batteryGraphWindowHours = payload.batteryGraphWindowHours,
                weatherLat = payload.weatherLat,
                weatherLon = payload.weatherLon
            )

            sendSyncAckToPhone(
                appCtx = appCtx,
                syncTimestampMs = payload.syncTimestampMs,
                eventsCount = payload.eventsCount,
                targetNodeId = replyNodeId
            )

            sendWatchBatteryReportToPhone(appCtx, replyNodeId)

            val weatherAlertTs = payload.weatherAlertTimestampMs
            if (weatherAlertTs != null &&
                weatherAlertTs > 0L &&
                (System.currentTimeMillis() - weatherAlertTs) < 120_000L &&
                repo.isWatchStandaloneWeatherEnabled()
            ) {
                AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(
                    appCtx,
                    forceRefresh = true
                )
            }

            AutonomousCountdownScheduler.notifyWatchOnForcedCanvasChangeIfNeeded(appCtx)
            AutonomousCountdownScheduler.scheduleNextTransition(appCtx)

            ChronoWearComplicationUpdater.requestUpdateAllSlots(appCtx)

            try {
                TileService.getUpdater(appCtx)
                    .requestUpdate(UpNextThreeEventsTileService::class.java)
            } catch (_: Exception) {
            }

            appCtx.sendBroadcast(
                Intent(ACTION_WEAR_CACHE_UPDATED).setPackage(appCtx.packageName)
            )
        }

        private suspend fun sendSyncAckToPhone(
            appCtx: Context,
            syncTimestampMs: Long,
            eventsCount: Int,
            targetNodeId: String? = null
        ) {
            try {
                val ackBytes = JSONObject().apply {
                    put("sync_timestamp_ms", syncTimestampMs)
                    put("events_count", eventsCount)
                    put("watch_model", Build.MODEL ?: "Wear OS")
                }.toString().toByteArray(Charsets.UTF_8)

                val msgClient = Wearable.getMessageClient(appCtx)
                if (!targetNodeId.isNullOrBlank()) {
                    msgClient.sendMessage(targetNodeId, "/chronowear/sync_ack", ackBytes).await()
                } else {
                    val nodes = Wearable.getNodeClient(appCtx).connectedNodes.await()
                    for (node in nodes) {
                        msgClient.sendMessage(node.id, "/chronowear/sync_ack", ackBytes).await()
                    }
                }
            } catch (_: Exception) {
            }
        }

        private suspend fun sendWatchBatteryReportToPhone(
            appCtx: Context,
            targetNodeId: String? = null
        ) {
            try {
                val repo = WatchAutonomousRepository.getInstance(appCtx)
                val snap = repo.getWatchBatterySnapshot(System.currentTimeMillis())
                val payload = JSONObject().apply {
                    put("pct", snap.currentPct)
                    put("isCharging", snap.isCharging)
                    put("rateLabel", snap.rateLabel)
                    put("historyCsv", snap.historyCsv)
                    put("labelsCsv", snap.labels.joinToString(","))
                    put("levelsCsv", snap.levelsPct.joinToString(","))
                }.toString().toByteArray(Charsets.UTF_8)

                val msgClient = Wearable.getMessageClient(appCtx)
                if (!targetNodeId.isNullOrBlank()) {
                    msgClient.sendMessage(targetNodeId, "/chronowear/watch_battery_report", payload).await()
                } else {
                    val nodes = Wearable.getNodeClient(appCtx).connectedNodes.await()
                    for (node in nodes) {
                        msgClient.sendMessage(node.id, "/chronowear/watch_battery_report", payload).await()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
