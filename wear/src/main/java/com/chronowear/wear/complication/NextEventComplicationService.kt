package com.chronowear.wear.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.chronowear.wear.data.WatchAutonomousRepository
import java.time.Instant
import java.util.concurrent.TimeUnit

class NextEventComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        val nowMs = System.currentTimeMillis()

        val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        val primaryEvent = upcomingThree.firstOrNull()
        val progressWindowMinutes = repo.getProgressWindowMinutes()

        if (primaryEvent == null) {
            return buildEmptyComplication(request.complicationType)
        }

        val countdownText = TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_DUAL_UNIT
        )
            .setCountDownTimeReference(Instant.ofEpochMilli(primaryEvent.startEpochMs))
            .build()

        val threeEventsSummary = upcomingThree.mapIndexed { idx, ev ->
            "${idx + 1}. ${ev.formatStartHHMM()} ${ev.title}"
        }.joinToString("\n")

        val windowMs = TimeUnit.MINUTES.toMillis(progressWindowMinutes.toLong()).toFloat()
        val remainingMs = (primaryEvent.startEpochMs - nowMs).coerceAtLeast(0L).toFloat()

        val progressPercentage = if (remainingMs >= windowMs) {
            0f
        } else {
            ((1f - (remainingMs / windowMs)) * 100f).coerceIn(0f, 100f)
        }

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = progressPercentage,
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder(threeEventsSummary).build()
                )
                    .setTitle(countdownText)
                    .setText(PlainComplicationText.Builder("${primaryEvent.formatStartHHMM()} ${primaryEvent.title}").build())
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(threeEventsSummary).build(),
                    contentDescription = PlainComplicationText.Builder(threeEventsSummary).build()
                )
                    .setTitle(countdownText)
                    .build()
            }

            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = countdownText,
                    contentDescription = PlainComplicationText.Builder(primaryEvent.title).build()
                )
                    .setTitle(PlainComplicationText.Builder(primaryEvent.formatStartHHMM()).build())
                    .build()
            }

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val sampleThree = PlainComplicationText.Builder(
            "1. 10:00 Daily Standup\n2. 13:30 Revisão WFF\n3. 16:00 Deploy APK"
        ).build()
        val sampleTitle = PlainComplicationText.Builder("24m (10:00)").build()

        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 60f,
                min = 0f,
                max = 100f,
                contentDescription = sampleThree
            ).setTitle(sampleTitle).setText(sampleThree).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = sampleThree,
                contentDescription = sampleThree
            ).setTitle(sampleTitle).build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = sampleTitle,
                contentDescription = sampleThree
            ).build()

            else -> null
        }
    }

    private fun buildEmptyComplication(type: ComplicationType): ComplicationData? {
        val emptyText = PlainComplicationText.Builder("Sem eventos futuros").build()
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 0f,
                min = 0f,
                max = 100f,
                contentDescription = emptyText
            ).setText(emptyText).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = emptyText,
                contentDescription = emptyText
            ).build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder("--").build(),
                contentDescription = emptyText
            ).build()

            else -> null
        }
    }
}
