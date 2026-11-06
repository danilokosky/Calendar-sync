package com.chronowear.wear.complication

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.text.TextPaint
import android.text.TextUtils
import android.util.Base64
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.chronowear.wear.data.AutonomousCountdownScheduler
import com.chronowear.wear.data.CachedWearEvent
import com.chronowear.wear.data.HourlyWeatherSnapshot
import com.chronowear.wear.data.WatchAutonomousRepository
import com.chronowear.wear.data.WeeklyStepsSnapshot
import java.util.concurrent.TimeUnit

/**
 * Utilitário centralizado para atualizar a ÚNICA Complication de Janela de Contexto (Canvas Bitmap)
 * na Watch Face (sem complications baseadas em texto).
 */
object ChronoWearComplicationUpdater {
    fun requestUpdateAllSlots(context: Context) {
        try {
            ComplicationDataSourceUpdateRequester.create(
                context,
                ComponentName(context, NextEventComplicationService::class.java)
            ).requestUpdateAll()
        } catch (_: Exception) {
        }
    }
}

/**
 * Motor Multi-Contexto de Renderização em Canvas 2D (604x272px @2x Retina para o quadro 302x136px):
 * Renderiza com fundo externo transparente qualquer contexto escolhido pelo usuário no App
 * (ou trocado automaticamente por notificações do relógio no modo Dinâmico):
 * 1. EVENTS: Próximos 3 Eventos + Contagem Regressiva + Barra de Progresso (com Turbo 1 min nos 15m finais)
 * 2. WEATHER: Curva de Mudança de Temperatura nas Próximas Horas + Alerta de Notificação do Relógio
 * 3. STEPS: Gráfico de Passos Semanais (7 dias) + Meta Diária
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
        val effectiveMode = repo.resolveEffectiveContextMode(nowMs, upcomingThree)

        return when (effectiveMode) {
            WatchAutonomousRepository.MODE_WEATHER -> {
                renderWeatherForecastCanvas(repo.getHourlyWeatherSnapshot(nowMs))
            }
            WatchAutonomousRepository.MODE_STEPS -> {
                renderWeeklyStepsCanvas(repo.getWeeklyStepsSnapshot())
            }
            else -> {
                val primary = upcomingThree.firstOrNull()
                val windowMs = TimeUnit.MINUTES.toMillis(progressWindowMinutes.toLong())
                val isInsideActiveProgressWindow = primary != null &&
                    (primary.startEpochMs - nowMs) in 1..windowMs

                // Se estiver no modo EVENTS fora da janela ativa e houver PNG fresco do telefone, reutiliza
                if (!isInsideActiveProgressWindow && repo.getContextWindowMode() == WatchAutonomousRepository.MODE_EVENTS) {
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

                val isTurbo15MinActive = primary != null &&
                    repo.isHighFreq15MinRuleEnabled() &&
                    (primary.startEpochMs - nowMs) in 1..TimeUnit.MINUTES.toMillis(15L)

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

                renderEventsCardCanvas(
                    headerTitle = if (isTurbo15MinActive) "Hoje • Turbo 1m" else "Hoje",
                    rows = rows,
                    progressFraction = progressFraction
                )
            }
        }
    }

    fun renderPreviewBitmap(): Bitmap {
        val sampleRows = listOf(
            Triple("16:30", "Adolescentes", "8m"),
            Triple("18:00", "Preparação das atividades", ""),
            Triple("07:00", "Projeto Guará", "")
        )
        return renderEventsCardCanvas(
            headerTitle = "Hoje",
            rows = sampleRows,
            progressFraction = 0.85f
        )
    }

    private fun createBaseCardBitmap(): Pair<Bitmap, Canvas> {
        val bmp = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
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

        val cardRect = RectF(2f, 2f, CARD_WIDTH - 2f, CARD_HEIGHT - 2f)
        canvas.drawRoundRect(cardRect, 40f, 40f, bgPaint)
        canvas.drawRoundRect(cardRect, 40f, 40f, borderPaint)
        return bmp to canvas
    }

    /**
     * CONTEXTO 1: Próximos 3 Eventos + Contagem Regressiva + Barra Fininha
     */
    private fun renderEventsCardCanvas(
        headerTitle: String,
        rows: List<Triple<String, String, String>>,
        progressFraction: Float
    ): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

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

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(headerTitle, 74f, 48f, headerPaint)

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

        val row2 = rows.getOrNull(1) ?: Triple("--:--", "Livre", "")
        canvas.drawText(row2.first, 24f, 182f, timePaint)
        val row2Ellipsized = TextUtils.ellipsize(
            row2.second,
            titlePaint,
            440f,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(row2Ellipsized, 136f, 182f, titlePaint)

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

    /**
     * CONTEXTO 2: Curva de Mudança de Temperatura nas Próximas Horas (Acionado por Notificação do Relógio ou escolha no App)
     */
    private fun renderWeatherForecastCanvas(weather: HourlyWeatherSnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#67E8F9")
        }
        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#F5C438")
        }

        // Ícone de Sol + Nuvem no topo esquerdo
        canvas.drawCircle(36f, 32f, 10f, goldPaint)
        canvas.drawRoundRect(RectF(32f, 30f, 62f, 48f), 9f, 9f, cyanPaint)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 33f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Clima • Próximas Horas", 74f, 46f, headerPaint)

        val rainBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#67E8F9")
            textSize = 27f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Chuva ${weather.rainProbabilityPct}%", CARD_WIDTH - 24f, 45f, rainBadgePaint)

        // Subtítulo do Alerta (ex: lido da notificação do relógio)
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
        val ellipsizedAlert = TextUtils.ellipsize(
            weather.alertHeadline,
            subPaint,
            (CARD_WIDTH - 48).toFloat(),
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(ellipsizedAlert, 24f, 84f, subPaint)

        // Desenha a Curva de Mudança de Temperatura (5 pontos nas próximas horas)
        val temps = weather.hourlyTempsC.take(5).ifEmpty { listOf(28, 26, 22, 19, 18) }
        val labels = weather.hourlyLabels.take(5).ifEmpty { listOf("Agora", "+1h", "+2h", "+4h", "+6h") }
        val minT = (temps.minOrNull() ?: 15) - 2
        val maxT = (temps.maxOrNull() ?: 30) + 2
        val rangeT = (maxT - minT).coerceAtLeast(1).toFloat()

        val chartLeft = 56f
        val chartRight = CARD_WIDTH - 56f
        val chartTop = 132f
        val chartBottom = 206f
        val stepX = (chartRight - chartLeft) / (temps.size - 1).coerceAtLeast(1)

        val points = temps.mapIndexed { idx, temp ->
            val x = chartLeft + idx * stepX
            val norm = (temp - minT).toFloat() / rangeT
            val y = chartBottom - norm * (chartBottom - chartTop)
            x to y
        }

        val curvePath = Path()
        points.forEachIndexed { idx, (x, y) ->
            if (idx == 0) {
                curvePath.moveTo(x, y)
            } else {
                val prev = points[idx - 1]
                val ctrlX = (prev.first + x) / 2f
                curvePath.cubicTo(ctrlX, prev.second, ctrlX, y, x, y)
            }
        }

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#67E8F9")
        }
        canvas.drawPath(curvePath, linePaint)

        val tempTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 27f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val hourTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 23f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        points.forEachIndexed { idx, (x, y) ->
            val dotPaint = if (idx == 0) goldPaint else cyanPaint
            canvas.drawCircle(x, y, 7.5f, dotPaint)
            canvas.drawText("${temps[idx]}°", x, y - 14f, tempTextPaint)
            val lbl = labels.getOrNull(idx) ?: ""
            canvas.drawText(lbl, x, 248f, hourTextPaint)
        }

        return bmp
    }

    /**
     * CONTEXTO 3: Passos Semanais & Meta Diária
     */
    private fun renderWeeklyStepsCanvas(steps: WeeklyStepsSnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#4ADE80")
        }
        val goldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#F5C438")
        }

        canvas.drawRoundRect(RectF(26f, 20f, 58f, 48f), 14f, 14f, greenPaint)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4ADE80")
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Passos Semanais", 74f, 46f, headerPaint)

        val rightCountPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 30f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("${steps.todaySteps} / ${steps.dailyGoal}", CARD_WIDTH - 24f, 46f, rightCountPaint)

        // Barra da meta de hoje
        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#252E3A")
        }
        val barLeft = 24f
        val barRight = CARD_WIDTH - 24f
        canvas.drawRoundRect(RectF(barLeft, 66f, barRight, 76f), 5f, 5f, trackPaint)
        val ratio = (steps.todaySteps.toFloat() / steps.dailyGoal.coerceAtLeast(1).toFloat()).coerceIn(0.05f, 1f)
        canvas.drawRoundRect(
            RectF(barLeft, 66f, barLeft + (barRight - barLeft) * ratio, 76f),
            5f,
            5f,
            greenPaint
        )

        // Colunas dos 7 dias da semana
        val values = steps.weekDaysSteps.take(7)
        val labels = steps.weekDaysLabels.take(7)
        val maxVal = (values.maxOrNull() ?: 10000).coerceAtLeast(steps.dailyGoal).toFloat()

        val chartLeft = 46f
        val chartRight = CARD_WIDTH - 46f
        val chartTop = 104f
        val chartBottom = 212f
        val colSpacing = (chartRight - chartLeft) / 7f

        val dayLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 23f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        values.forEachIndexed { idx, v ->
            val cx = chartLeft + idx * colSpacing + colSpacing / 2f
            val h = ((v.toFloat() / maxVal) * (chartBottom - chartTop)).coerceAtLeast(12f)
            val barRect = RectF(cx - 16f, chartBottom - h, cx + 16f, chartBottom)
            canvas.drawRoundRect(RectF(cx - 16f, chartTop, cx + 16f, chartBottom), 8f, 8f, trackPaint)
            canvas.drawRoundRect(barRect, 8f, 8f, if (idx == 4) goldPaint else greenPaint)
            canvas.drawText(labels.getOrNull(idx) ?: "", cx, 246f, dayLabelPaint)
        }

        return bmp
    }
}

/**
 * COMPLICATION ÚNICA DO CHRONOWEAR: JANELA DE CONTEXTO (CANVAS BITMAP).
 * No seletor de personalização da Watch Face aparece apenas esta Complication.
 * O conteúdo que ela desenha (Próximos Eventos, Clima nas Próximas Horas, Passos Semanais ou
 * Dinâmico guiado pelas notificações do relógio e pela regra de 1 min nos 15 min finais)
 * é configurado diretamente no App!
 */
class NextEventComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        AutonomousCountdownScheduler.ensureScreenWakeListenerRegistered(applicationContext)

        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        val nowMs = System.currentTimeMillis()
        val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        val progressWindowMinutes = repo.getProgressWindowMinutes()

        // Garante que o agendador (incluindo a regra de 1 minuto quando faltam <= 15 min) esteja programado
        AutonomousCountdownScheduler.scheduleNextTransition(applicationContext)

        val contentDesc = PlainComplicationText.Builder("ChronoWear Janela de Contexto").build()

        val cardBitmap = AgendaCardBitmapRenderer.getOrRenderCardBitmap(
            repo = repo,
            upcomingThree = upcomingThree,
            nowMs = nowMs,
            progressWindowMinutes = progressWindowMinutes
        )
        val icon = Icon.createWithBitmap(cardBitmap)

        return when (request.complicationType) {
            ComplicationType.SMALL_IMAGE -> {
                val smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build()
                SmallImageComplicationData.Builder(smallImage, contentDesc).build()
            }
            ComplicationType.PHOTO_IMAGE -> {
                PhotoImageComplicationData.Builder(icon, contentDesc).build()
            }
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val sampleText = PlainComplicationText.Builder("ChronoWear Janela de Contexto").build()
        val icon = Icon.createWithBitmap(AgendaCardBitmapRenderer.renderPreviewBitmap())

        return when (type) {
            ComplicationType.SMALL_IMAGE -> {
                val smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build()
                SmallImageComplicationData.Builder(smallImage, sampleText).build()
            }
            ComplicationType.PHOTO_IMAGE -> {
                PhotoImageComplicationData.Builder(icon, sampleText).build()
            }
            else -> null
        }
    }
}
