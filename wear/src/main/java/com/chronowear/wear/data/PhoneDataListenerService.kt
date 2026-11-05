package com.chronowear.wear.data

import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.chronowear.wear.complication.NextEventComplicationService
import com.chronowear.wear.tile.UpNextThreeEventsTileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
                }
            }
        }
    }
}
