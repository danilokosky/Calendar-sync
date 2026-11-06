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
 * Utilitário centralizado para atualizar simultaneamente o Slot Principal (Evento #1 + Barra + Timer)
 * e os Slots dos próximos eventos (Evento #2 e Evento #3) na Watch Face.
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
 * SLOT 1 (EVENTO PRINCIPAL #1 DA LISTA CENTRAL):
 * Exibe o Evento #1 ("16:30  Adolescentes") alinhado como primeiro item do quadro central,
 * acoplado ao tempo restante (TITLE = "17h" / "36m") e à barra de progresso fina (RANGED_VALUE).
 * Como ele já é o primeiro item da lista central, não é repetido abaixo.
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

        val primaryLine = "${primaryEvent.formatStartHHMM()}  ${primaryEvent.title}"

        val windowMs = TimeUnit.MINUTES.toMillis(progressWindowMinutes.toLong()).toFloat()
        val remainingMs = (primaryEvent.startEpochMs - nowMs).coerceAtLeast(0L).toFloat()

        // Preenche de 0% a 100% quando entra na janela configurada (ex: 1h ou 2h antes do início)
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
                    contentDescription = PlainComplicationText.Builder(primaryLine).build()
                )
                    .setTitle(countdownText)
                    .setText(PlainComplicationText.Builder(primaryLine).build())
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(primaryLine).build(),
                    contentDescription = PlainComplicationText.Builder(primaryLine).build()
                )
                    .setTitle(countdownText)
                    .build()
            }

            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(primaryEvent.title).build(),
                    contentDescription = PlainComplicationText.Builder(primaryLine).build()
                )
                    .setTitle(countdownText)
                    .build()
            }

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val sampleText = PlainComplicationText.Builder("16:30  Adolescentes").build()
        val sampleTitle = PlainComplicationText.Builder("17h").build()

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
                text = PlainComplicationText.Builder("Adolescentes").build(),
                contentDescription = sampleText
            ).setTitle(sampleTitle).build()

            else -> null
        }
    }

    private fun buildEmptyComplication(type: ComplicationType): ComplicationData? {
        val emptyText = PlainComplicationText.Builder("Sem eventos hoje").build()
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
 * Classe base para fornecer cada compromisso subsequente da lista padronizada
 * (TITLE = Horário ex. "18:00", TEXT = Nome do Evento ex. "Preparação das atividades").
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
        val titleStr = targetEvent?.title ?: "Livre"

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
