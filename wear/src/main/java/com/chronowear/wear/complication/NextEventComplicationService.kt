package com.chronowear.wear.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.text.TextPaint
import android.text.TextUtils
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
import com.chronowear.wear.data.AutonomousTransitionReceiver
import com.chronowear.wear.data.CachedWearEvent
import com.chronowear.wear.data.HourlyWeatherSnapshot
import com.chronowear.wear.data.SleepSnapshot
import com.chronowear.wear.data.SolarSnapshot
import com.chronowear.wear.data.TasksSnapshot
import com.chronowear.wear.data.WatchAutonomousRepository
import com.chronowear.wear.data.WeeklyStepsSnapshot
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

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
 * Motor Multi-Contexto de Renderização em Canvas 2D de Alto DPI (604x272px @2x Retina para 302x136px):
 * Renderiza com tipografia ampliada e alta legibilidade:
 * 1. EVENTS: Próximos 3 Eventos + Contagem Regressiva + Barra de Progresso (0% a 100% fiel à janela escolhida)
 * 2. WEATHER: Título limpo "Clima", condição climática (Chuva, Tempestade, Garoa, Nublado, Céu limpo)
 *    e curva de mudança de temperatura nas próximas horas com números maiores
 * 3. STEPS: Gráfico de Passos Semanais (7 dias) + Meta Diária com barras e rótulos ampliados
 * 4. SUN: Curva Solar Completa (Dia e Noite abaixo da linha do horizonte, fiel ao design One UI Watch)
 * 5. SLEEP: Resumo de Sono (Hipnograma de Estágios REM/Leve/Profundo, duração e pontuação)
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
            WatchAutonomousRepository.MODE_TASKS -> {
                renderTasksChecklistCanvas(repo.getTasksSnapshot(nowMs))
            }
            WatchAutonomousRepository.MODE_WEATHER -> {
                renderWeatherForecastCanvas(repo.getHourlyWeatherSnapshot(nowMs))
            }
            WatchAutonomousRepository.MODE_STEPS -> {
                renderWeeklyStepsCanvas(repo.getWeeklyStepsSnapshot())
            }
            WatchAutonomousRepository.MODE_SUN -> {
                renderSunriseSunsetCanvas(repo.getSolarSnapshot(nowMs))
            }
            WatchAutonomousRepository.MODE_SLEEP -> {
                renderSleepSummaryCanvas(repo.getSleepSnapshot(nowMs))
            }
            else -> {
                // Regra estrita: se o título for "Hoje", exibe APENAS compromissos de hoje.
                // Somente quando não houver mais compromissos para hoje muda para "Amanhã" (se houver)
                // ou informa "Não há compromissos".
                val (dayHeader, dayFilteredEvents) = repo.resolveDayHeaderAndEventsForWatch(nowMs)
                val primary = dayFilteredEvents.firstOrNull()
                val windowMs = TimeUnit.MINUTES.toMillis(progressWindowMinutes.toLong()).toFloat()

                val progressFraction = if (primary == null || windowMs <= 0f) {
                    0f
                } else {
                    val remMs = (primary.startEpochMs - nowMs).coerceAtLeast(0L).toFloat()
                    if (remMs >= windowMs) {
                        0f
                    } else {
                        ((windowMs - remMs) / windowMs).coerceIn(0f, 1f)
                    }
                }

                val rows = if (dayFilteredEvents.isEmpty()) {
                    listOf(
                        Triple("", "Sem compromissos", ""),
                        Triple("", "", ""),
                        Triple("", "", "")
                    )
                } else {
                    listOf(
                        Triple(
                            dayFilteredEvents.getOrNull(0)?.formatStartHHMM() ?: "",
                            dayFilteredEvents.getOrNull(0)?.title ?: "",
                            dayFilteredEvents.getOrNull(0)?.formatRemaining(nowMs) ?: ""
                        ),
                        Triple(
                            dayFilteredEvents.getOrNull(1)?.formatStartHHMM() ?: "",
                            dayFilteredEvents.getOrNull(1)?.title ?: "",
                            ""
                        ),
                        Triple(
                            dayFilteredEvents.getOrNull(2)?.formatStartHHMM() ?: "",
                            dayFilteredEvents.getOrNull(2)?.title ?: "",
                            ""
                        )
                    )
                }

                renderEventsCardCanvas(
                    headerTitle = dayHeader,
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
            strokeWidth = 2.6f
            color = Color.parseColor("#161D26")
        }

        val cardRect = RectF(2f, 2f, CARD_WIDTH - 2f, CARD_HEIGHT - 2f)
        canvas.drawRoundRect(cardRect, 40f, 40f, bgPaint)
        canvas.drawRoundRect(cardRect, 40f, 40f, borderPaint)
        return bmp to canvas
    }

    /**
     * CONTEXTO 1: Próximos 3 Eventos + Contagem Regressiva + Barra de Progresso (0% a 100%)
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
            strokeWidth = 4.5f
            color = Color.parseColor("#080A0E")
        }

        // Ícone de Calendário Dourado Samsung ampliado
        canvas.drawRoundRect(RectF(30f, 14f, 37f, 26f), 2.5f, 2.5f, goldPaint)
        canvas.drawRoundRect(RectF(51f, 14f, 58f, 26f), 2.5f, 2.5f, goldPaint)
        canvas.drawRoundRect(RectF(24f, 21f, 64f, 55f), 7f, 7f, goldPaint)
        canvas.drawLine(31f, 33f, 57f, 33f, darkCutPaint)
        canvas.drawLine(31f, 44f, 50f, 44f, darkCutPaint)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(headerTitle, 78f, 51f, headerPaint)

        val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 44f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val countdownPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#67E8F9")
            textSize = 35f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val emptySlotPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#263344")
            textSize = 36f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val emptyDayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val row1 = rows.getOrNull(0) ?: Triple("", "Sem compromissos", "")
        if (row1.first.isNotBlank()) {
            canvas.drawText(row1.first, 24f, 105f, timePaint)
            val row1TitleMaxWidth = if (row1.third.isNotBlank()) 290f else 420f
            val row1Ellipsized = TextUtils.ellipsize(
                row1.second,
                titlePaint,
                row1TitleMaxWidth,
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(row1Ellipsized, 150f, 105f, titlePaint)
            if (row1.third.isNotBlank()) {
                canvas.drawText(row1.third, CARD_WIDTH - 24f, 103f, countdownPaint)
            }
        } else {
            val emptyLabel = row1.second.ifBlank { "Sem compromissos" }
            canvas.drawText(emptyLabel, 24f, 105f, emptyDayPaint)
        }

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#252E3A")
        }
        val barTop = 117f
        val barBottom = 130f
        val barLeft = 24f
        val barRight = CARD_WIDTH - 24f
        canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barBottom), 6.5f, 6.5f, trackPaint)

        if (progressFraction > 0f) {
            val fillWidth = ((barRight - barLeft) * progressFraction.coerceIn(0f, 1f)).coerceAtLeast(13f)
            canvas.drawRoundRect(
                RectF(barLeft, barTop, barLeft + fillWidth, barBottom),
                6.5f,
                6.5f,
                goldPaint
            )
        }

        val row2 = rows.getOrNull(1) ?: Triple("", "", "")
        if (row2.first.isNotBlank()) {
            canvas.drawText(row2.first, 24f, 188f, timePaint)
            val row2Ellipsized = TextUtils.ellipsize(
                row2.second,
                titlePaint,
                430f,
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(row2Ellipsized, 150f, 188f, titlePaint)
        } else {
            canvas.drawText("•", 24f, 188f, emptySlotPaint)
        }

        val row3 = rows.getOrNull(2) ?: Triple("", "", "")
        if (row3.first.isNotBlank()) {
            canvas.drawText(row3.first, 24f, 250f, timePaint)
            val row3Ellipsized = TextUtils.ellipsize(
                row3.second,
                titlePaint,
                430f,
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(row3Ellipsized, 150f, 250f, titlePaint)
        } else {
            canvas.drawText("•", 24f, 250f, emptySlotPaint)
        }

        return bmp
    }

    /**
     * CONTEXTO 2: Clima (Título apenas "Clima", condição climática + chuva e curva com DPI ampliado)
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

        // Ícone de Sol + Nuvem ampliado
        canvas.drawCircle(38f, 34f, 12f, goldPaint)
        canvas.drawRoundRect(RectF(34f, 32f, 68f, 52f), 10f, 10f, cyanPaint)

        // Título limpo: apenas "Clima"
        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Clima", 80f, 51f, headerPaint)

        // Badge à direita com condição climática + chuva
        val rightConditionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#67E8F9")
            textSize = 32f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val rightBadgeText = if (weather.rainProbabilityPct > 0 || weather.weatherConditionLabel.contains("Chuva", true)) {
            "${weather.weatherConditionLabel} • Chuva ${weather.rainProbabilityPct}%"
        } else {
            "${weather.weatherConditionLabel} • ${weather.rainProbabilityPct}%"
        }
        canvas.drawText(rightBadgeText, CARD_WIDTH - 24f, 49f, rightConditionPaint)

        // Subtítulo com a localização compacta da previsão (Cidade abreviada se extensa + sigla UF de 2 letras)
        // e horário da última atualização para que o usuário saiba se o clima está atualizado e no local onde se situa
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val compactLoc = WatchAutonomousRepository.formatCompactWeatherLocation(weather.locationName)
        val locationSubtitle = "📍 $compactLoc • Atualizado ${weather.updatedTimeHHMM}"
        val ellipsizedLocation = TextUtils.ellipsize(
            locationSubtitle,
            subPaint,
            (CARD_WIDTH - 48).toFloat(),
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(ellipsizedLocation, 24f, 92f, subPaint)

        // Curva de Mudança de Temperatura (5 pontos nas próximas horas) com fontes maiores
        val temps = weather.hourlyTempsC.take(5).ifEmpty { listOf(28, 28, 27, 27, 27) }
        val labels = weather.hourlyLabels.take(5).ifEmpty { listOf("Agora", "23h", "01h", "03h", "05h") }
        val minT = (temps.minOrNull() ?: 15) - 2
        val maxT = (temps.maxOrNull() ?: 30) + 2
        val rangeT = (maxT - minT).coerceAtLeast(1).toFloat()

        val chartLeft = 60f
        val chartRight = CARD_WIDTH - 60f
        val chartTop = 148f
        val chartBottom = 208f
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
            strokeWidth = 7.5f
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#67E8F9")
        }
        canvas.drawPath(curvePath, linePaint)

        val tempTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 35f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val hourTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 29f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        points.forEachIndexed { idx, (x, y) ->
            val dotPaint = if (idx == 0) goldPaint else cyanPaint
            canvas.drawCircle(x, y, 9.5f, dotPaint)
            canvas.drawText("${temps[idx]}°", x, y - 16f, tempTextPaint)
            val lbl = labels.getOrNull(idx) ?: ""
            canvas.drawText(lbl, x, 254f, hourTextPaint)
        }

        return bmp
    }

    /**
     * CONTEXTO 3: Passos Semanais & Meta Diária (Alto DPI)
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

        canvas.drawRoundRect(RectF(24f, 18f, 60f, 50f), 16f, 16f, greenPaint)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4ADE80")
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Passos", 74f, 50f, headerPaint)

        val rightCountPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 35f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val todayFormatted = String.format(java.util.Locale("pt", "BR"), "%,d", steps.todaySteps)
        val goalFormatted = String.format(java.util.Locale("pt", "BR"), "%,d", steps.dailyGoal)
        canvas.drawText("$todayFormatted / $goalFormatted", CARD_WIDTH - 24f, 50f, rightCountPaint)

        val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#252E3A")
        }
        val barLeft = 24f
        val barRight = CARD_WIDTH - 24f
        canvas.drawRoundRect(RectF(barLeft, 66f, barRight, 79f), 6.5f, 6.5f, trackPaint)
        if (steps.todaySteps > 0) {
            val ratio = (steps.todaySteps.toFloat() / steps.dailyGoal.coerceAtLeast(1).toFloat()).coerceIn(0.04f, 1f)
            canvas.drawRoundRect(
                RectF(barLeft, 66f, barLeft + (barRight - barLeft) * ratio, 79f),
                6.5f,
                6.5f,
                greenPaint
            )
        }

        val values = steps.weekDaysSteps.take(7)
        val labels = steps.weekDaysLabels.take(7)
        val maxVal = (values.maxOrNull() ?: steps.dailyGoal).coerceAtLeast(steps.dailyGoal).coerceAtLeast(1).toFloat()
        val todayIdx = values.lastIndex

        val chartLeft = 42f
        val chartRight = CARD_WIDTH - 42f
        val chartTop = 100f
        val chartBottom = 212f
        val colSpacing = (chartRight - chartLeft) / 7f

        val dayLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            textSize = 26f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        values.forEachIndexed { idx, v ->
            val cx = chartLeft + idx * colSpacing + colSpacing / 2f
            canvas.drawRoundRect(RectF(cx - 20f, chartTop, cx + 20f, chartBottom), 10f, 10f, trackPaint)
            if (v > 0) {
                val h = ((v.toFloat() / maxVal) * (chartBottom - chartTop)).coerceAtLeast(12f)
                val barRect = RectF(cx - 20f, chartBottom - h, cx + 20f, chartBottom)
                canvas.drawRoundRect(barRect, 10f, 10f, if (idx == todayIdx) goldPaint else greenPaint)
            }
            dayLabelPaint.color = if (idx == todayIdx) Color.parseColor("#F5C438") else Color.parseColor("#E2E8F0")
            canvas.drawText(labels.getOrNull(idx) ?: "", cx, 250f, dayLabelPaint)
        }

        return bmp
    }

    /**
     * CONTEXTO 4: Nascer e Pôr do Sol (Curva Solar Completa Dia e Noite — Fiel ao Design One UI Watch)
     * - A curva branca cruza a linha azul do horizonte exatamente nas linhas pontilhadas de
     *   Nascer do Sol (sunriseX = 164f) e Pôr do Sol (sunsetX = 440f).
     * - Durante a noite (antes do nascer [26f..164f] e após o pôr do sol [440f..578f]), a curva
     *   desce ABAIXO da linha do horizonte (até y = 236f) e o ponto luminoso do sol acompanha
     *   o trajeto noturno para mostrar quanto falta para o sol cruzar a linha do horizonte.
     */
    private fun renderSunriseSunsetCanvas(solar: SolarSnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        // 1. Ícone de Nascer do Sol (Verde-Lima no topo esquerdo) + Horário 05:27
        val limeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#65A30D")
        }
        val limeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#BEF264")
        }
        val limeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#84CC16")
        }
        canvas.drawCircle(52f, 42f, 21f, limeGlowPaint)
        canvas.drawCircle(52f, 42f, 14f, limeCorePaint)
        val riseHorizonPath = Path().apply {
            moveTo(24f, 56f)
            lineTo(42f, 56f)
            quadTo(52f, 44f, 62f, 56f)
            lineTo(80f, 56f)
        }
        canvas.drawPath(riseHorizonPath, limeLinePaint)

        val timeTopPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(solar.sunriseHHMM, 94f, 56f, timeTopPaint)

        // 2. Horário Pôr do Sol (17:40) + Ícone de Pôr do Sol (Azul Suave no topo direito)
        val sunsetRightTimePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(solar.sunsetHHMM, CARD_WIDTH - 94f, 56f, sunsetRightTimePaint)

        val blueGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#3B82F6")
            alpha = 150
        }
        val blueCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#93C5FD")
        }
        val blueLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#93C5FD")
        }
        val iconRightCx = CARD_WIDTH - 52f
        canvas.drawCircle(iconRightCx, 42f, 21f, blueGlowPaint)
        canvas.drawCircle(iconRightCx, 42f, 14f, blueCorePaint)
        val setHorizonPath = Path().apply {
            moveTo(iconRightCx - 28f, 54f)
            lineTo(iconRightCx - 10f, 54f)
            quadTo(iconRightCx, 66f, iconRightCx + 10f, 54f)
            lineTo(iconRightCx + 28f, 54f)
        }
        canvas.drawPath(setHorizonPath, blueLinePaint)

        // 3. Geometria do Gráfico Solar (Dia acima de horizonY = 160f, Noite abaixo de horizonY = 160f)
        val graphLeft = 26f
        val graphRight = CARD_WIDTH - 26f // 578f
        val sunriseX = 164f
        val noonX = CARD_WIDTH / 2f // 302f
        val sunsetX = CARD_WIDTH - 164f // 440f
        val horizonY = 160f
        val graphTop = 74f
        val graphBottom = 242f

        val skyGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f,
                graphTop,
                0f,
                horizonY,
                intArrayOf(
                    Color.parseColor("#00080A0E"),
                    Color.parseColor("#443B5278"),
                    Color.parseColor("#886488B8")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(graphLeft, graphTop, graphRight, horizonY, skyGradientPaint)

        // 4. Três linhas verticais pontilhadas (Nascer X=164, Meio-Dia Solar X=302, Pôr do Sol X=440)
        val dottedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 5f
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#64748B")
            pathEffect = DashPathEffect(floatArrayOf(3f, 11f), 0f)
        }

        for (vx in listOf(sunriseX, noonX, sunsetX)) {
            val vPath = Path().apply {
                moveTo(vx, 74f)
                lineTo(vx, 200f)
            }
            canvas.drawPath(vPath, dottedPaint)
        }

        // 5. Linha Horizontal Azul-Celeste do Horizonte (em horizonY = 160f)
        val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6.5f
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#8AB4F8")
        }
        canvas.drawLine(graphLeft, horizonY, graphRight, horizonY, horizonPaint)

        // 6. Símbolos inferiores sob as 3 linhas pontilhadas: Seta Subindo (↑), Sol (☼) e Seta Descendo (↓)
        val symbolStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#94A3B8")
        }
        // Seta ↑ em sunriseX
        canvas.drawLine(sunriseX, 240f, sunriseX, 212f, symbolStrokePaint)
        canvas.drawLine(sunriseX - 10f, 222f, sunriseX, 212f, symbolStrokePaint)
        canvas.drawLine(sunriseX + 10f, 222f, sunriseX, 212f, symbolStrokePaint)

        // Sol ☼ em noonX
        val sunCenterY = 226f
        canvas.drawCircle(noonX, sunCenterY, 6.5f, symbolStrokePaint)
        for (deg in 0 until 360 step 45) {
            val rad = Math.toRadians(deg.toDouble())
            val x1 = noonX + (11.5f * cos(rad)).toFloat()
            val y1 = sunCenterY + (11.5f * sin(rad)).toFloat()
            val x2 = noonX + (15.5f * cos(rad)).toFloat()
            val y2 = sunCenterY + (15.5f * sin(rad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, symbolStrokePaint)
        }

        // Seta ↓ em sunsetX
        canvas.drawLine(sunsetX, 212f, sunsetX, 240f, symbolStrokePaint)
        canvas.drawLine(sunsetX - 10f, 230f, sunsetX, 240f, symbolStrokePaint)
        canvas.drawLine(sunsetX + 10f, 230f, sunsetX, 240f, symbolStrokePaint)

        // 7. Curva Senoidal Completa (Dia e Noite):
        // halfDaySpan = sunsetX - noonX = 138px.
        // - Em x = sunriseX (164): angle = -PI/2 -> cos = 0 -> y = horizonY (160)
        // - Em x = noonX (302):    angle = 0     -> cos = +1 -> y = 160 - 76 = 84 (Pico do Dia)
        // - Em x = sunsetX (440):  angle = +PI/2 -> cos = 0 -> y = horizonY (160)
        // - Em x < sunriseX (26..164) e x > sunsetX (440..578): cos < 0 -> y > horizonY (até 236, Noite abaixo do horizonte!)
        val halfDaySpan = sunsetX - noonX // 138f
        val amplitude = 76f
        val solarCurvePath = Path()
        val steps = 96
        for (i in 0..steps) {
            val x = graphLeft + (graphRight - graphLeft) * (i.toFloat() / steps.toFloat())
            val angle = (Math.PI / 2.0) * ((x - noonX) / halfDaySpan)
            val y = (horizonY - amplitude * cos(angle).toFloat()).coerceIn(80f, graphBottom)
            if (i == 0) solarCurvePath.moveTo(x, y) else solarCurvePath.lineTo(x, y)
        }

        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        canvas.drawPath(solarCurvePath, curvePaint)

        // 8. Ponto Luminoso do Sol na Posição da Hora Atual (inclusive abaixo do horizonte à noite!)
        val curMins = solar.currentMinutesOfDay.toFloat()
        val riseMins = solar.sunriseMinutesOfDay.toFloat().coerceAtLeast(1f)
        val setMins = solar.sunsetMinutesOfDay.toFloat().coerceAtLeast(riseMins + 60f)

        val sunX = when {
            curMins <= riseMins -> {
                // Madrugada (00:00 até Nascer do Sol): percorre de graphLeft (26) até sunriseX (164) abaixo do horizonte
                val frac = (curMins / riseMins).coerceIn(0f, 1f)
                graphLeft + frac * (sunriseX - graphLeft)
            }
            curMins <= setMins -> {
                // Dia (Nascer até Pôr do Sol): percorre de sunriseX (164) até sunsetX (440) acima do horizonte
                val frac = ((curMins - riseMins) / (setMins - riseMins)).coerceIn(0f, 1f)
                sunriseX + frac * (sunsetX - sunriseX)
            }
            else -> {
                // Noite (Pôr do Sol até 24:00): percorre de sunsetX (440) até graphRight (578) abaixo do horizonte
                val remDay = (1440f - setMins).coerceAtLeast(1f)
                val frac = ((curMins - setMins) / remDay).coerceIn(0f, 1f)
                sunsetX + frac * (graphRight - sunsetX)
            }
        }
        val sunAngle = (Math.PI / 2.0) * ((sunX - noonX) / halfDaySpan)
        val sunY = (horizonY - amplitude * cos(sunAngle).toFloat()).coerceIn(80f, graphBottom)

        val sunHaloOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#66FDE047")
        }
        val sunHaloInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#CCFACC15")
        }
        val sunCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FEF9C3")
        }
        canvas.drawCircle(sunX, sunY, 20f, sunHaloOuter)
        canvas.drawCircle(sunX, sunY, 13f, sunHaloInner)
        canvas.drawCircle(sunX, sunY, 8.5f, sunCore)

        return bmp
    }

    /**
     * CONTEXTO 5: Resumo de Sono (Health Connect / Samsung Health — Hipnograma e Pontuação do Dia)
     * - Caso o app confirme que não há dados de sono registrados para hoje (!sleep.hasDataForToday),
     *   informa claramente "Não há dados de sono para hoje".
     */
    private fun renderSleepSummaryCanvas(sleep: SleepSnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        val lavenderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#A78BFA")
        }
        val darkCutoutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#080A0E")
        }

        // Ícone de Lua Crescente no topo esquerdo
        canvas.drawCircle(44f, 36f, 17f, lavenderPaint)
        canvas.drawCircle(52f, 31f, 14f, darkCutoutPaint)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C4B5FD")
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("Sono", 76f, 50f, headerPaint)

        if (!sleep.hasDataForToday || sleep.durationMinutes <= 0) {
            val rightEmptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#94A3B8")
                textSize = 32f
                textAlign = Paint.Align.RIGHT
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("Hoje", CARD_WIDTH - 24f, 50f, rightEmptyPaint)

            val mainNoDataPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#F8FAFC")
                textSize = 34f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("Não há dados de sono para hoje", 24f, 108f, mainNoDataPaint)

            val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#1E293B")
            }
            canvas.drawRoundRect(RectF(24f, 130f, CARD_WIDTH - 24f, 162f), 10f, 10f, trackPaint)

            val subNoDataPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#94A3B8")
                textSize = 26f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("Verificado no Samsung Health / Health Connect", 24f, 214f, subNoDataPaint)
            return bmp
        }

        val hours = sleep.durationMinutes / 60
        val mins = sleep.durationMinutes % 60
        val rightScorePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 35f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val topRightLabel = if (sleep.showScore && sleep.sleepScore > 0) {
            "${hours}h ${mins}m • ${sleep.sleepScore} pts"
        } else {
            "${hours}h ${mins}m"
        }
        canvas.drawText(topRightLabel, CARD_WIDTH - 24f, 50f, rightScorePaint)

        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CBD5E1")
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val qualityLabel = when {
            sleep.sleepScore >= 85 -> "Excelente"
            sleep.sleepScore >= 70 -> "Bom"
            else -> "Regular"
        }
        val subtitleLabel = if (sleep.showScore && sleep.sleepScore > 0) {
            "${sleep.bedtimeHHMM} → ${sleep.wakeHHMM} • $qualityLabel"
        } else {
            "${sleep.bedtimeHHMM} → ${sleep.wakeHHMM}"
        }
        canvas.drawText(subtitleLabel, 24f, 92f, subPaint)

        // Barra Segmentada de Estágios do Sono (Acordado, REM, Leve, Profundo)
        val stages = sleep.stagesMinutes.take(4).let {
            if (it.size == 4) it else listOf(0, 0, sleep.durationMinutes.coerceAtLeast(1), 0)
        }
        val totalStageMins = stages.sum().coerceAtLeast(1).toFloat()
        val stageColors = listOf(
            Color.parseColor("#F87171"), // Acordado
            Color.parseColor("#38BDF8"), // REM
            Color.parseColor("#818CF8"), // Leve
            Color.parseColor("#4F46E5")  // Profundo
        )
        val stageNames = listOf("Acord.", "REM", "Leve", "Prof.")

        val barLeft = 24f
        val barRight = CARD_WIDTH - 24f
        val totalBarWidth = barRight - barLeft
        val barTop = 114f
        val barBottom = 152f

        var curX = barLeft
        stages.forEachIndexed { idx, stageMins ->
            val segW = (stageMins.toFloat() / totalStageMins) * totalBarWidth
            val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = stageColors[idx]
            }
            val rightEdge = (curX + segW - 4f).coerceAtLeast(curX + 6f)
            canvas.drawRoundRect(RectF(curX, barTop, rightEdge, barBottom), 10f, 10f, segPaint)
            curX += segW
        }

        // Legenda de Estágios na parte inferior com alto DPI
        val colWidth = totalBarWidth / 4f
        val stageTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 26f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val stageValPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 31f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        stages.forEachIndexed { idx, stageMins ->
            val cx = barLeft + idx * colWidth + colWidth / 2f
            val sh = stageMins / 60
            val sm = stageMins % 60
            val durText = if (sh > 0) "${sh}h${String.format("%02d", sm)}" else "${sm}m"
            canvas.drawText(stageNames[idx], cx, 198f, stageTitlePaint)
            canvas.drawText(durText, cx, 240f, stageValPaint)
        }

        return bmp
    }

    /**
     * CONTEXTO 6: Tarefas (Samsung Reminder e Google Tasks — Hierarquia Estrita):
     * 1. Somente atividades agendadas para Hoje (com horário HH:mm e check de não feito).
     * 2. Se não houver para Hoje, atividades agendadas para Amanhã (com horário HH:mm e check de não feito).
     * 3. Se não houver para Amanhã, atividades Sem Data Definida e Atrasadas (SEM data e SEM hora, apenas check de não feito,
     *    sendo as atrasadas exibidas em VERMELHO).
     */
    private fun renderTasksChecklistCanvas(tasksSnap: TasksSnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        val emeraldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#34D399")
        }
        val checkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Color.parseColor("#080A0E")
        }

        // Ícone de Checklist no topo esquerdo
        canvas.drawRoundRect(RectF(24f, 18f, 62f, 56f), 10f, 10f, emeraldPaint)
        val headerCheckPath = Path().apply {
            moveTo(33f, 38f)
            lineTo(41f, 46f)
            lineTo(54f, 29f)
        }
        canvas.drawPath(headerCheckPath, checkStrokePaint)

        val rightCounterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#67E8F9")
            textSize = 29f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val rightBadgeWidth = rightCounterPaint.measureText(tasksSnap.rightBadgeText)

        val headerTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#34D399")
            textSize = 37f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val maxHeaderWidth = (CARD_WIDTH - 24f - rightBadgeWidth - 16f - 76f).coerceAtLeast(180f)
        val ellipsizedHeader = TextUtils.ellipsize(
            tasksSnap.headerTitle,
            headerTitlePaint,
            maxHeaderWidth,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(ellipsizedHeader, 76f, 51f, headerTitlePaint)

        val activeTasks = tasksSnap.tasks.filter { !it.completed }
        canvas.drawText(
            tasksSnap.rightBadgeText,
            CARD_WIDTH - 24f,
            49f,
            rightCounterPaint
        )

        val pendingCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.2f
            color = Color.parseColor("#F5C438")
        }
        val overdueCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4.5f
            color = Color.parseColor("#EF4444")
        }
        val emptyCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
            color = Color.parseColor("#475569")
        }
        val timeColPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 35f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val taskTitlePendingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F8FAFC")
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val taskTitleOverduePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F87171")
            textSize = 38f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val taskEmptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val sourceBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F5C438")
            textSize = 27f
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        val rowYPositions = listOf(108f, 174f, 240f)
        val displayTasks = activeTasks.take(3)

        rowYPositions.forEachIndexed { idx, baseY ->
            val task = displayTasks.getOrNull(idx)
            val centerY = baseY - 13f
            if (task != null) {
                val isOverdueRed = task.isOverdue || task.scheduleCategory.equals("OVERDUE", ignoreCase = true)
                canvas.drawCircle(
                    38f,
                    centerY,
                    12.5f,
                    if (isOverdueRed) overdueCirclePaint else pendingCirclePaint
                )

                val titlePaint = if (isOverdueRed) taskTitleOverduePaint else taskTitlePendingPaint

                if (tasksSnap.showTime && task.dueHHMM.isNotBlank()) {
                    canvas.drawText(task.dueHHMM.take(5), 64f, baseY, timeColPaint)
                    val ellipsizedTask = TextUtils.ellipsize(
                        task.title,
                        titlePaint,
                        295f,
                        TextUtils.TruncateAt.END
                    ).toString()
                    canvas.drawText(ellipsizedTask, 174f, baseY, titlePaint)
                } else {
                    // Sem data definida ou Atrasada: NÃO apresenta data nem hora, apenas o check de não feito + título
                    val ellipsizedTask = TextUtils.ellipsize(
                        task.title,
                        titlePaint,
                        395f,
                        TextUtils.TruncateAt.END
                    ).toString()
                    canvas.drawText(ellipsizedTask, 68f, baseY, titlePaint)
                }

                sourceBadgePaint.color = when {
                    isOverdueRed -> Color.parseColor("#EF4444")
                    task.sourceLabel.contains("Reminder", ignoreCase = true) -> Color.parseColor("#67E8F9")
                    else -> Color.parseColor("#F5C438")
                }
                canvas.drawText(task.sourceLabel.take(8), CARD_WIDTH - 24f, baseY - 2f, sourceBadgePaint)
            } else {
                canvas.drawCircle(38f, centerY, 10f, emptyCirclePaint)
                val emptyMsg = if (idx == 0 && displayTasks.isEmpty()) "Não há tarefas pendentes" else "Livre"
                canvas.drawText(emptyMsg, 68f, baseY, taskEmptyPaint)
            }
        }

        return bmp
    }
}

class NextEventComplicationService : SuspendingComplicationDataSourceService() {

    private fun buildTapCyclePendingIntent(): PendingIntent {
        val tapIntent = Intent(applicationContext, AutonomousTransitionReceiver::class.java).apply {
            action = AutonomousCountdownScheduler.ACTION_TAP_CYCLE_CONTEXT
        }
        return PendingIntent.getBroadcast(
            applicationContext,
            3001,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        AutonomousCountdownScheduler.ensureScreenWakeListenerRegistered(applicationContext)

        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        val nowMs = System.currentTimeMillis()
        val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        val progressWindowMinutes = repo.getProgressWindowMinutes()

        AutonomousCountdownScheduler.scheduleNextTransition(applicationContext)

        val contentDesc = PlainComplicationText.Builder("ChronoWear Janela de Contexto (Toque p/ Alternar)").build()

        val cardBitmap = AgendaCardBitmapRenderer.getOrRenderCardBitmap(
            repo = repo,
            upcomingThree = upcomingThree,
            nowMs = nowMs,
            progressWindowMinutes = progressWindowMinutes
        )
        val icon = Icon.createWithBitmap(cardBitmap)
        val tapPendingIntent = buildTapCyclePendingIntent()

        return when (request.complicationType) {
            ComplicationType.SMALL_IMAGE -> {
                val smallImage = SmallImage.Builder(icon, SmallImageType.PHOTO).build()
                SmallImageComplicationData.Builder(smallImage, contentDesc)
                    .setTapAction(tapPendingIntent)
                    .build()
            }
            ComplicationType.PHOTO_IMAGE -> {
                PhotoImageComplicationData.Builder(icon, contentDesc)
                    .setTapAction(tapPendingIntent)
                    .build()
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
