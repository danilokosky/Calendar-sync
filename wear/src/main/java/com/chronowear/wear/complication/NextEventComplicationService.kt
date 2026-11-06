package com.chronowear.wear.complication

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.chronowear.wear.data.CachedWearEvent
import com.chronowear.wear.data.WatchAutonomousRepository
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Utilitário centralizado para atualizar o Painel Central de Agenda (Imagem Renderizada + Fallback)
 * e os Slots auxiliares na Watch Face.
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
 * Motor Híbrido de Renderização do Painel Central (604x272px @2x Retina para 302x136px):
 * - Quando conectado ao Smartphone e fora da janela ativa da barra, reutiliza o PNG pré-renderizado
 *   pelo celular (economia máxima de CPU no relógio).
 * - Quando o Smartphone está desconectado (Modo Autônomo Offline) ou a barra de progresso está
 *   em contagem regressiva ativa, renderiza localmente via Canvas 2D nativo em < 2ms com fundo
 *   transparente e liberdade total de fontes, cores, ícone e barra de progresso.
 */
object AgendaCardBitmapRenderer {

    private const val CARD_WIDTH = 604
    private const val CARD_HEIGHT = 272

    fun getOrRenderCardBitmap(
        repo: WatchAutonomousRepository,
        upcomingThree: List<CachedWearEvent>,
        nowMs: Long,
        progressWindowMinutes: Int
    ): Bitmap {
        val primary = upcomingThree.firstOrNull()
        val windowMs = TimeUnit.MINUTES.toMillis(progressWindowMinutes.toLong())
        val isInsideActiveProgressWindow = primary != null &&
            (primary.startEpochMs - nowMs) in 1..windowMs

        // Se o celular enviou um PNG pré-renderizado recente e não estamos no meio da animação minuto-a-minuto da barra
        if (!isInsideActiveProgressWindow) {
            val phoneB64 = repo.getPhoneRenderedCardBase64IfFresh(nowMs)
            if (!phoneB64.isNullOrBlank()) {
                try {
                    val bytes = Base64.decode(phoneB64, Base64.DEFAULT)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (decoded != null) return decoded
                } catch (_: Exception) {
                }
            }
        }

        val progressFraction = if (primary == null) {
            0f
        } else {
            val rem = (primary.startEpochMs - nowMs).coerceAtLeast(0L).toFloat()
            if (rem >= windowMs.toFloat()) 0.06f
            else (1f - (rem / windowMs.toFloat())).coerceIn(0.06f, 1f)
        }

        val rows = listOf(
             Triple(
                upcomingThree.getOrNull(0)?.formatStartHHMM() ?: "--:--",
                upcomingThree.getOrNull(0)?.title ?: "Livre",
                upcomingThree.getOrNull(0)?.formatRemaining(nowMs) ?: "--"
            ),
            Triple(
                upcomingThree.getOrNull(1)?.formatStartHHMM() ?: "--:--",
                upcomingThree.getOrNull(1)?.title ?: "Livre",
                ""
            ),
            Triple(
                upcomingThree.getOrNull(2)?.formatStartHHMM() ?: "--:--",
                upcomingThree.getOrNull(2)?.title ?: "Livre",
                ""
            )
        )

        return renderCardCanvas(
            headerTitle = "Hoje",
            rows = rows,
            progressFraction = progressFraction
        )
    }

    fun renderPreviewBitmap(): Bitmap {
        val sampleRows = listOf(
            Triple("16:30", "Adolescentes", "2h28min"),
            Triple("18:00", "Preparação das atividades", ""),
            Triple("07:00", "Projeto Guará", "")
        )
        return renderCardCanvas(
            headerTitle = "Hoje",
            rows = sampleRows,
            progressFraction = 0.35f
        )
    }

    private fun renderCardCanvas(
        headerTitle: String,
        rows: List<Triple<String, String, String>>,
        progressFraction: Float
    ): Bitmap {
        val bmp = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Fundo externo 100% transparente para respeitar os cantos arredondados na Watch Face
        canvas.drawColor(Color.TRANSPARENT)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#080A0E")
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.4f
            color = Color.parseColor("#161D26")
        }

        // Quadro arredondado único (604x272 @2x = 302x136 @1x, cornerRadius = 40px @2x = 20px @1x)
        val cardRect = RectF(2f, 2f, CARD_WIDTH - 2f, CARD_HEIGHT - 2f)
        canvas.drawRoundRect(cardRect, 40f, 40f, bgPaint)
        canvas.drawRoundRect(cardRect, 40f, 40f, borderPaint)

        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#F5C438")
        }
        val darkCutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#080A0E")
        }

        // Ícone de Calendário Dourado Samsung no topo esquerdo
        canvas.drawRoundRect(RectF(30f, 16f, 36f, 26f), 2f, 2f, goldPaint)
        canvas.drawRoundRect(RectF(48f, 16f, 54f, 26f), 2f, 2f, goldPaint)
        canvas.drawRoundRect(RectF(24f, 22f, 60f, 52f), 6f, 6f, goldPaint)
        canvas.drawLine(30f, 32f, 54f, 32f, darkCutPaint)
        canvas.drawLine(30f, 42f, 48f, 42f, darkCutPaint)

        // Cabeçalho "Hoje" (36px = 18sp BOLD Dourado)
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(headerTitle, 74f, 48f, headerPaint)

        // Fontes das 3 linhas de eventos (38px = 19sp BOLD)
        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val countdownPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#67E8F9")
            textSize = 29f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        // LINHA 1: Evento Principal #1 + Contagem Regressiva à direita
        val row1 = rows.getOrNull(0) ?: Triple("--:--", "Livre", "--")
        canvas.drawText(row1.first, 24f, 102f, timePaint)

        val row1TitleMaxWidth = if (row1.third.isNotBlank()) 300f else 430f
        val row1Ellipsized = TextUtils.ellipsize(
            row1.second,
            titlePaint,
            row1TitleMaxWidth,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(row1Ellipsized, 136f, 102f, titlePaint)

        if (row1.third.isNotBlank()) {
            canvas.drawText(row1.third, CARD_WIDTH - 24f, 100f, countdownPaint)
        }

        // Barra de Progresso Fininha acoplada abaixo do Evento Principal #1
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#252E3A")
        }
        val barTop = 116f
        val barBottom = 126f
        val barLeft = 24f
        val barRight = CARD_WIDTH - 24f
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 5f, 5f, trackPaint)

        val fillWidth = ((barRight - barLeft) * progressFraction.coerceIn(0.05f, 1f))
        canvas.drawRoundRect(
            RectF(barLeft, barTop, barLeft + fillWidth, barBottom),
            5f,
            5f,
            goldPaint
        )

        // LINHA 2: Próximo Evento #2
        val row2 = rows.getOrNull(1) ?: Triple("--:--", "Livre", "")
        canvas.drawText(row2.first, 24f, 182f, timePaint)
        val row2Ellipsized = TextUtils.ellipsize(
            row2.second,
            titlePaint,
            440f,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(row2Ellipsized, 136f, 182f, titlePaint)

        // LINHA 3: Próximo Evento #3
        val row3 = rows.getOrNull(2) ?: Triple("--:--", "Livre", "")
        canvas.drawText(row3.first, 24f, 242f, timePaint)
        val row3Ellipsized = TextUtils.ellipsize(
            row3.second,
            titlePaint,
            440f,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(row3Ellipsized, 136f, 242f, titlePaint)

        return bmp
    }
}

/**
 * SLOT CENTRAL ÚNICO (PAINEL RENDERIZADO DOS 3 EVENTOS + BARRA DE PROGRESSO):
 * Substitui as 4 complications amontoadas por 1 única complication que fornece uma imagem
 * transparente de alta definição (604x272px @2x para o quadro 302x136px) via SMALL_IMAGE / PHOTO_IMAGE,
 * mantendo também fallback para RANGED_VALUE, LONG_TEXT e SHORT_TEXT.
 */
class NextEventComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        val nowMs = System.currentTimeMillis()

        val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        val primaryEvent = upcomingThree.firstOrNull()
        val progressWindowMinutes = repo.getProgressWindowMinutes()

        val primaryLine = if (primaryEvent != null) {
            "${primaryEvent.formatStartHHMM()} ${primaryEvent.title}"
        } else {
            "Agenda Livre"
        }
        val contentDesc = PlainComplicationText.Builder(primaryLine).build()

        // Modos de Imagem Renderizada do Quadro Completo (Substitui as 4 complications separadas!)
        if (request.complicationType == ComplicationType.SMALL_IMAGE ||
            request.complicationType == ComplicationType.PHOTO_IMAGE
        ) {
            val cardBitmap = AgendaCardBitmapRenderer.getOrRenderCardBitmap(
                repo = repo,
                upcomingThree = upcomingThree,
                nowMs = nowMs,
                progressWindowMinutes = progressWindowMinutes
            )
            val icon = Icon.createWithBitmap(cardBitmap)

            return if (request.complicationType == ComplicationType.SMALL_IMAGE) {
                val smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build()
                SmallImageComplicationData.Builder(smallImage, contentDesc).build()
            } else {
                PhotoImageComplicationData.Builder(icon, contentDesc).build()
            }
        }

        if (primaryEvent == null) {
            return buildEmptyComplication(request.complicationType)
        }

        val countdownText = TimeDifferenceComplicationText.Builder(
            TimeDifferenceStyle.SHORT_DUAL_UNIT,
            CountDownTimeReference(Instant.ofEpochMilli(primaryEvent.startEpochMs))
        ).build()

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
                    contentDescription = contentDesc
                )
                    .setTitle(countdownText)
                    .setText(contentDesc)
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = contentDesc,
                    contentDescription = contentDesc
                )
                    .setTitle(countdownText)
                    .build()
            }

            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(primaryEvent.title).build(),
                    contentDescription = contentDesc
                )
                    .setTitle(countdownText)
                    .build()
            }

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val sampleText = PlainComplicationText.Builder("16:30 Adolescentes").build()
        val sampleTitle = PlainComplicationText.Builder("2h28min").build()

        return when (type) {
            ComplicationType.SMALL_IMAGE -> {
                val icon = Icon.createWithBitmap(AgendaCardBitmapRenderer.renderPreviewBitmap())
                val smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build()
                SmallImageComplicationData.Builder(smallImage, sampleText).build()
            }

            ComplicationType.PHOTO_IMAGE -> {
                val icon = Icon.createWithBitmap(AgendaCardBitmapRenderer.renderPreviewBitmap())
                PhotoImageComplicationData.Builder(icon, sampleText).build()
            }

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
 * Mantidos para retrocompatibilidade caso o usuário queira usar slots individuais em outras Watch Faces.
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
