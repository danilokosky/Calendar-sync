package com.chronowear.wear.tile

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.chronowear.wear.data.WatchAutonomousRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future

class UpNextThreeEventsTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = serviceScope.future {
        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        val nowMs = System.currentTimeMillis()
        val nextThree = repo.getNextThreeUpcomingEvents(nowMs)

        val columnBuilder = Column.Builder()
            .setWidth(dp(192f))

        val firstEvent = nextThree.firstOrNull()
        val headerLabel = if (firstEvent != null) {
            "PRÓXIMO EM ${firstEvent.formatRemaining(nowMs)} (${firstEvent.formatStartHHMM()})"
        } else {
            "SEM EVENTOS FUTUROS"
        }

        columnBuilder.addContent(
            Text.Builder()
                .setText(headerLabel)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(11f))
                        .setColor(argb(0xFFF59E0B.toInt()))
                        .build()
                )
                .build()
        )

        nextThree.take(3).forEachIndexed { index, ev ->
            columnBuilder.addContent(
                Text.Builder()
                    .setText("${index + 1}. ${ev.formatStartHHMM()} · ${ev.title}")
                    .setMaxLines(1)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(13f))
                            .setColor(argb(0xFFF8FAFC.toInt()))
                            .build()
                    )
                    .build()
            )
        }

        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(columnBuilder.build())
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(layout)
                    .build()
            )
            .build()

        TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(60_000L)
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion("1").build()
        )
}
