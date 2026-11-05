package com.chronowear.wear.data

import android.content.ComponentName
import android.content.Intent
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.chronowear.wear.complication.NextEventComplicationService
import com.chronowear.wear.tile.UpNextThreeEventsTileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class PhoneDataListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

                persistAndNotify(
                    eventsJson = eventsJson,
                    syncTimestampMs = syncTimestampMs,
                    horizonDays = horizonDays,
                    progressWindowMinutes = progressWindowMinutes
                )
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        if (messageEvent.path == "/chronowear/force_sync_payload") {
            try {
                val payloadStr = String(messageEvent.data, Charsets.UTF_8)
                val json = JSONObject(payloadStr)
                val eventsArray = json.optJSONArray("events")?.toString() ?: "[]"
                val syncTimestampMs = json.optLong("sync_timestamp_ms", System.currentTimeMillis())
                val horizonDays = json.optInt("horizon_days", 5)
                val progressWindowMinutes = json.optInt("progress_window_minutes", 60)

                persistAndNotify(
                    eventsJson = eventsArray,
                    syncTimestampMs = syncTimestampMs,
                    horizonDays = horizonDays,
                    progressWindowMinutes = progressWindowMinutes
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun persistAndNotify(
        eventsJson: String,
        syncTimestampMs: Long,
        horizonDays: Int,
        progressWindowMinutes: Int
    ) {
        serviceScope.launch {
            val repo = WatchAutonomousRepository.getInstance(applicationContext)
            repo.replaceHorizonCache(
                eventsJson = eventsJson,
                syncTimestampMs = syncTimestampMs,
                horizonDays = horizonDays,
                progressWindowMinutes = progressWindowMinutes
            )

            AutonomousCountdownScheduler.scheduleNextTransition(applicationContext)

            ComplicationDataSourceUpdateRequester.create(
                applicationContext,
                ComponentName(applicationContext, NextEventComplicationService::class.java)
            ).requestUpdateAll()

            TileService.getUpdater(applicationContext)
                .requestUpdate(UpNextThreeEventsTileService::class.java)

            applicationContext.sendBroadcast(
                Intent(ACTION_WEAR_CACHE_UPDATED).setPackage(applicationContext.packageName)
            )
        }
    }

    companion object {
        const val ACTION_WEAR_CACHE_UPDATED = "com.chronowear.wear.ACTION_WEAR_CACHE_UPDATED"
    }
}
