package com.chronowear.wear.complication

import android.content.ComponentName
import android.content.Context
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.chronowear.wear.data.WatchAutonomousRepository
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Utilitário centralizado para atualizar simultaneamente o Slot de Contagem Regressiva
 * e os 3 Slots individuais dos Próximos Eventos na Watch Face.
 */
object ChronoWearComplicationUpdater {
    fun requestUpdateAllSlots(context: Context) {
        val services = listOf(
            NextEventComplicationService::class.java,
            FirstEventComplicationService::class.java,
            SecondEventComplicationService::class.java,
            ThirdEventComplicationService::class.java
        )
        for (cls in services) {
            try {
                ComplicationDataSourceUpdateRequester.create(
                    context,
                    ComponentName(context, cls)
                ).requestUpdateAll()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * SLOT 1 (TOPO): Contagem Regressiva + Barra de Progresso (RANGED_VALUE) + Resumo do Próximo Evento.
 */
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
            TimeDifferenceStyle.SHORT_DUAL_UNIT,
            CountDownTimeReference(Instant.ofEpochMilli(primaryEvent.startEpochMs))
        ).build()

        val primarySummary = "${primaryEvent.formatStartHHMM()} • ${primaryEvent.title}"
        val threeEventsInline = upcomingThree.mapIndexed { idx, ev ->
            "${idx + 1}. ${ev.formatStartHHMM()} ${ev.title}"
        }.joinToString("  |  ")

        val windowMs = TimeUnit.MINUTES.toMillis(progressWindowMinutes.toLong()).toFloat()
        val remainingMs = (primaryEvent.startEpochMs - nowMs).coerceAtLeast(0L).toFloat()

        // Quando está dentro da janela configurada (ex: 1h ou 2h antes), preenche de 0% a 100%.
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
                    contentDescription = PlainComplicationText.Builder(primarySummary).build()
                )
                    .setTitle(countdownText)
                    .setText(PlainComplicationText.Builder(primarySummary).build())
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(threeEventsInline).build(),
                    contentDescription = PlainComplicationText.Builder(threeEventsInline).build()
                )
                    .setTitle(countdownText)
                    .build()
            }

            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = countdownText,
                    contentDescription = PlainComplicationText.Builder(primarySummary).build()
                )
                    .setTitle(PlainComplicationText.Builder(primaryEvent.formatStartHHMM()).build())
                    .build()
            }

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val sampleText = PlainComplicationText.Builder("16:30 • Adolescentes").build()
        val sampleTitle = PlainComplicationText.Builder("36m").build()

        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 65f,
                min = 0f,
                max = 100f,
                contentDescription = sampleText
            ).setTitle(sampleTitle).setText(sampleText).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = sampleText,
                contentDescription = sampleText
            ).setTitle(sampleTitle).build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = sampleTitle,
                contentDescription = sampleText
            ).setTitle(PlainComplicationText.Builder("16:30").build()).build()

            else -> null
        }
    }

    private fun buildEmptyComplication(type: ComplicationType): ComplicationData? {
        val emptyText = PlainComplicationText.Builder("Agenda livre").build()
        val emptyTitle = PlainComplicationText.Builder("--").build()
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 0f,
                min = 0f,
                max = 100f,
                contentDescription = emptyText
            ).setTitle(emptyTitle).setText(emptyText).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = emptyText,
                contentDescription = emptyText
            ).setTitle(emptyTitle).build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = emptyTitle,
                contentDescription = emptyText
            ).setTitle(emptyText).build()

            else -> null
        }
    }
}

/**
 * Classe base para fornecer cada um dos 3 próximos compromissos individualmente
 * (TITLE = Horário ex. "16:30", TEXT = Nome do Evento ex. "Adolescentes").
 * Assim a Watch Face renderiza 3 cartões horizontais separados, sem colar um texto no outro.
 */
abstract class BaseIndexedEventComplicationService(
    private val eventIndex: Int,
    private val previewTime: String,
    private val previewTitle: String
) : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        val nowMs = System.currentTimeMillis()
        val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        val targetEvent = upcomingThree.getOrNull(eventIndex)

        val timeStr = targetEvent?.formatStartHHMM() ?: "--:--"
        val titleStr = targetEvent?.title ?: "Sem compromisso"

        val timeText = PlainComplicationText.Builder(timeStr).build()
        val eventTitleText = PlainComplicationText.Builder(titleStr).build()
        val descText = PlainComplicationText.Builder("$timeStr $titleStr").build()

        return when (request.complicationType) {
            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = eventTitleText,
                    contentDescription = descText
                )
                    .setTitle(timeText)
                    .build()
            }

            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = eventTitleText,
                    contentDescription = descText
                )
                    .setTitle(timeText)
                    .build()
            }

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val t = PlainComplicationText.Builder(previewTime).build()
        val n = PlainComplicationText.Builder(previewTitle).build()
        return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = n,
                contentDescription = n
            ).setTitle(t).build()

            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = n,
                contentDescription = n
            ).setTitle(t).build()

            else -> null
        }
    }
}

class FirstEventComplicationService : BaseIndexedEventComplicationService(
    eventIndex = 0,
    previewTime = "16:30",
    previewTitle = "Adolescentes"
)

class SecondEventComplicationService : BaseIndexedEventComplicationService(
    eventIndex = 1,
    previewTime = "18:00",
    previewTitle = "Preparação das atividades"
)

class ThirdEventComplicationService : BaseIndexedEventComplicationService(
    eventIndex = 2,
    previewTime = "07:00",
    previewTitle = "Projeto Guará"
)
