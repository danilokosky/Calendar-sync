package com.chronowear.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.chronowear.wear.data.AutonomousCountdownScheduler
import com.chronowear.wear.data.AutonomousTransitionReceiver
import com.chronowear.wear.data.HourlyWeatherSnapshot
import com.chronowear.wear.data.WatchAutonomousRepository
import java.util.Locale

/**
 * Renderizador 2D de Alto DPI para o Cartucho Inferior em Pílula (604x232px @2x Retina para 302x116px).
 * - Compartilha 100% do mesmo provedor de dados (WatchAutonomousRepository), sem chamadas extras de rede.
 * - Usa Assinatura de Estado + Cache em Memória para evitar redesenhos quando os dados não mudarem.
 * - Etapa 1: exibe o contexto de Clima/Temperatura no estilo One UI Watch (com fundos e ícones por condição e dia/noite).
 * - Arquitetura pronta para expandir novos contextos e alternância por toque no Cartucho.
 */
object ContextPillBitmapRenderer {

    private const val PILL_WIDTH = 604
    private const val PILL_HEIGHT = 232

    @Volatile
    private var cachedSignature: String? = null

    @Volatile
    private var cachedBitmap: Bitmap? = null

    private val cacheLock = Any()

    enum class WeatherVisualKind {
        STORM,
        HEAVY_RAIN,
        DRIZZLE_RAIN,
        CLOUDY_DAY,
        CLEAR_DAY,
        PARTLY_NIGHT,
        CLEAR_NIGHT,
        OVERCAST_FOG,
        SNOW
    }

    data class WeatherPillState(
        val mode: String,
        val tempC: Int,
        val conditionLabel: String,
        val cityShort: String,
        val isNight: Boolean,
        val visualKind: WeatherVisualKind,
        val signature: String
    )

    private fun extractCityShort(locationName: String): String {
        val raw = locationName.trim()
        if (raw.isBlank()) return "São Paulo"
        val withoutUf = raw.substringBefore(",").substringBefore(" - ").trim()
        return withoutUf.ifBlank { raw }
    }

    private fun resolveVisualKind(conditionLabel: String, isNight: Boolean): WeatherVisualKind {
        val lower = conditionLabel.lowercase(Locale.ROOT)
        return when {
            lower.contains("tempest") || lower.contains("trovoad") -> WeatherVisualKind.STORM
            lower.contains("chuva forte") -> WeatherVisualKind.HEAVY_RAIN
            lower.contains("garoa") || lower.contains("chuva") -> WeatherVisualKind.DRIZZLE_RAIN
            lower.contains("neve") -> WeatherVisualKind.SNOW
            lower.contains("neblina") || lower.contains("nublado") && !lower.contains("parc") -> {
                if (isNight) WeatherVisualKind.PARTLY_NIGHT else WeatherVisualKind.OVERCAST_FOG
            }
            lower.contains("parc") -> {
                if (isNight) WeatherVisualKind.PARTLY_NIGHT else WeatherVisualKind.CLOUDY_DAY
            }
            else -> {
                if (isNight) WeatherVisualKind.CLEAR_NIGHT else WeatherVisualKind.CLEAR_DAY
            }
        }
    }

    fun resolveWeatherPillState(
        repo: WatchAutonomousRepository,
        nowMs: Long
    ): WeatherPillState {
        val pillMode = repo.getPillContextMode(nowMs)
        val weather: HourlyWeatherSnapshot = repo.getHourlyWeatherSnapshot(nowMs)
        val solar = repo.getSolarSnapshot(nowMs)

        val tempNow = weather.hourlyTempsC.firstOrNull() ?: 25
        val condition = weather.weatherConditionLabel.ifBlank { "Céu limpo" }
        val cityShort = extractCityShort(weather.locationName)
        val isNight = solar.currentMinutesOfDay < solar.sunriseMinutesOfDay ||
            solar.currentMinutesOfDay >= solar.sunsetMinutesOfDay
        val kind = resolveVisualKind(condition, isNight)
        val sig = "$pillMode|$tempNow|${kind.name}|$cityShort|$isNight"

        return WeatherPillState(
            mode = pillMode,
            tempC = tempNow,
            conditionLabel = condition,
            cityShort = cityShort,
            isNight = isNight,
            visualKind = kind,
            signature = sig
        )
    }

    fun computeStateSignature(repo: WatchAutonomousRepository, nowMs: Long): String {
        return when (repo.getPillContextMode(nowMs)) {
            WatchAutonomousRepository.MODE_WEATHER -> resolveWeatherPillState(repo, nowMs).signature
            else -> resolveWeatherPillState(repo, nowMs).signature
        }
    }

    fun getOrRenderPillBitmap(
        repo: WatchAutonomousRepository,
        nowMs: Long
    ): Bitmap {
        val state = resolveWeatherPillState(repo, nowMs)
        synchronized(cacheLock) {
            val existing = cachedBitmap
            if (cachedSignature == state.signature && existing != null && !existing.isRecycled) {
                return existing
            }
            val rendered = when (state.mode) {
                WatchAutonomousRepository.MODE_WEATHER -> renderWeatherPillCanvas(state)
                // Expansão futura de outros contextos no Cartucho:
                else -> renderWeatherPillCanvas(state)
            }
            cachedSignature = state.signature
            cachedBitmap = rendered
            repo.savePillLastSignature(state.signature)
            return rendered
        }
    }

    fun renderPreviewBitmap(): Bitmap {
        val sample = WeatherPillState(
            mode = WatchAutonomousRepository.MODE_WEATHER,
            tempC = 27,
            conditionLabel = "Parc. nublado",
            cityShort = "Estreito",
            isNight = false,
            visualKind = WeatherVisualKind.CLOUDY_DAY,
            signature = "PREVIEW"
        )
        return renderWeatherPillCanvas(sample)
    }

    private fun renderWeatherPillCanvas(state: WeatherPillState): Bitmap {
        val bmp = Bitmap.createBitmap(PILL_WIDTH, PILL_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val pillRect = RectF(0f, 0f, PILL_WIDTH.toFloat(), PILL_HEIGHT.toFloat())
        val radius = PILL_HEIGHT / 2f

        // 25% de opacidade (0x40) na cor de fundo para harmonizar com o fundo do relógio e o canvas principal
        val gradientColors = when (state.visualKind) {
            WeatherVisualKind.STORM -> intArrayOf(
                Color.parseColor("#403E4B6E"),
                Color.parseColor("#40354060"),
                Color.parseColor("#402B3552")
            )
            WeatherVisualKind.HEAVY_RAIN,
            WeatherVisualKind.DRIZZLE_RAIN -> intArrayOf(
                Color.parseColor("#403F757C"),
                Color.parseColor("#403C7E84"),
                Color.parseColor("#40346B72")
            )
            WeatherVisualKind.CLEAR_NIGHT,
            WeatherVisualKind.PARTLY_NIGHT -> intArrayOf(
                Color.parseColor("#40152452"),
                Color.parseColor("#40192C61"),
                Color.parseColor("#401F3673")
            )
            WeatherVisualKind.OVERCAST_FOG,
            WeatherVisualKind.SNOW -> intArrayOf(
                Color.parseColor("#404A5F78"),
                Color.parseColor("#403E526A"),
                Color.parseColor("#4032445A")
            )
            WeatherVisualKind.CLOUDY_DAY,
            WeatherVisualKind.CLEAR_DAY -> intArrayOf(
                Color.parseColor("#403A6FBA"),
                Color.parseColor("#40467CC6"),
                Color.parseColor("#405187CE")
            )
        }

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f,
                0f,
                PILL_WIDTH.toFloat(),
                PILL_HEIGHT.toFloat(),
                gradientColors,
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(pillRect, radius, radius, bgPaint)

        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#24FFFFFF")
        }
        canvas.drawRoundRect(
            RectF(1f, 1f, PILL_WIDTH - 1f, PILL_HEIGHT - 1f),
            radius - 1f,
            radius - 1f,
            rimPaint
        )

        // Circunferência esquerda perfeita: centro (116, 116), raio 116 (0..232)
        // Ícone climático preenchido e contido dentro desse círculo limite
        val iconCx = 116f
        val iconCy = 116f
        when (state.visualKind) {
            WeatherVisualKind.STORM -> drawStormIcon(canvas, iconCx, iconCy)
            WeatherVisualKind.HEAVY_RAIN -> drawHeavyRainIcon(canvas, iconCx, iconCy)
            WeatherVisualKind.DRIZZLE_RAIN -> drawDrizzleStreaksIcon(canvas, iconCx, iconCy)
            WeatherVisualKind.CLOUDY_DAY -> drawSunBehindCloudIcon(canvas, iconCx, iconCy)
            WeatherVisualKind.CLEAR_DAY -> drawSunIcon(canvas, iconCx, iconCy)
            WeatherVisualKind.PARTLY_NIGHT -> drawNightMoonCloudsIcon(canvas, iconCx, iconCy, withClouds = true)
            WeatherVisualKind.CLEAR_NIGHT -> drawNightMoonCloudsIcon(canvas, iconCx, iconCy, withClouds = false)
            WeatherVisualKind.OVERCAST_FOG -> drawOvercastCloudIcon(canvas, iconCx, iconCy)
            WeatherVisualKind.SNOW -> drawSnowIcon(canvas, iconCx, iconCy)
        }

        // Informações justificadas à esquerda logo após o círculo esquerdo (x=216..376),
        // mantendo livre toda a circunferência direita (complication circular em x=388..588).
        val textStartX = 216f
        val maxContentRightX = 376f

        val tempPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 74f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        canvas.drawText("${state.tempC}°", textStartX, 115f, tempPaint)

        drawLocationPinIcon(canvas, textStartX + 10f, 152f, scale = 0.92f)

        val cityStartX = textStartX + 28f
        val availableCityWidth = (maxContentRightX - cityStartX).coerceAtLeast(80f)
        val cityPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#DDE6F3")
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        while (cityPaint.textSize > 24f && cityPaint.measureText(state.cityShort) > availableCityWidth) {
            cityPaint.textSize -= 1f
        }
        val ellipsizedCity = TextUtils.ellipsize(
            state.cityShort,
            cityPaint,
            availableCityWidth,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(ellipsizedCity, cityStartX, 162f, cityPaint)

        return bmp
    }

    private fun drawCloudShape(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        scale: Float,
        mainColor: Int,
        shadowLobeColor: Int
    ) {
        val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = mainColor
        }
        val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = shadowLobeColor
        }

        // Base arredondada
        val baseRect = RectF(
            cx - 56f * scale,
            cy - 4f * scale,
            cx + 58f * scale,
            cy + 40f * scale
        )
        canvas.drawRoundRect(baseRect, 22f * scale, 22f * scale, mainPaint)
        // Cúpula principal esquerda
        canvas.drawCircle(cx - 14f * scale, cy - 12f * scale, 34f * scale, mainPaint)
        // Cúpula secundária direita (com sombra suave estilo One UI)
        canvas.drawCircle(cx + 26f * scale, cy + 4f * scale, 26f * scale, shadePaint)
        // Suaviza junção inferior
        canvas.drawRoundRect(
            RectF(cx - 56f * scale, cy + 8f * scale, cx + 58f * scale, cy + 40f * scale),
            16f * scale,
            16f * scale,
            mainPaint
        )
        canvas.drawCircle(cx + 28f * scale, cy + 16f * scale, 24f * scale, shadePaint)
    }

    private fun drawStormIcon(canvas: Canvas, cx: Float, cy: Float) {
        val s = 1.18f
        drawCloudShape(
            canvas = canvas,
            cx = cx,
            cy = cy - 18f * s,
            scale = s,
            mainColor = Color.parseColor("#FF7A8089"),
            shadowLobeColor = Color.parseColor("#FF666C75")
        )

        // Raio amarelo brilhante
        val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFFAE042")
        }
        val boltPath = Path().apply {
            moveTo(cx - 18f * s, cy + 5f * s)
            lineTo(cx - 2f * s, cy + 5f * s)
            lineTo(cx - 10f * s, cy + 28f * s)
            lineTo(cx + 4f * s, cy + 28f * s)
            lineTo(cx - 26f * s, cy + 70f * s)
            lineTo(cx - 16f * s, cy + 37f * s)
            lineTo(cx - 28f * s, cy + 37f * s)
            close()
        }
        canvas.drawPath(boltPath, boltPaint)

        // Gotas brancas diagonais
        val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8.5f * s
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#FFF8FAFC")
        }
        canvas.drawLine(cx + 18f * s, cy + 38f * s, cx + 8f * s, cy + 55f * s, dropPaint)
        canvas.drawLine(cx + 42f * s, cy + 38f * s, cx + 32f * s, cy + 55f * s, dropPaint)
    }

    private fun drawHeavyRainIcon(canvas: Canvas, cx: Float, cy: Float) {
        val s = 1.24f
        drawCloudShape(
            canvas = canvas,
            cx = cx,
            cy = cy - 16f * s,
            scale = s,
            mainColor = Color.parseColor("#FFCBD5E1"),
            shadowLobeColor = Color.parseColor("#FFB4C0D0")
        )
        val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f * s
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#FF93E5FF")
        }
        canvas.drawLine(cx - 24f * s, cy + 34f * s, cx - 34f * s, cy + 54f * s, dropPaint)
        canvas.drawLine(cx + 2f * s, cy + 34f * s, cx - 8f * s, cy + 54f * s, dropPaint)
        canvas.drawLine(cx + 28f * s, cy + 34f * s, cx + 18f * s, cy + 54f * s, dropPaint)
    }

    private fun drawDrizzleStreaksIcon(canvas: Canvas, cx: Float, cy: Float) {
        val s = 1.42f
        val streakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 10.5f * s
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
        }
        // 3 traços diagonais com pontos no estilo One UI (teal)
        canvas.drawLine(cx - 20f * s, cy - 22f * s, cx - 40f * s, cy - 2f * s, streakPaint)
        canvas.drawCircle(cx - 56f * s, cy + 14f * s, 5.5f * s, dotPaint)

        canvas.drawLine(cx + 14f * s, cy - 22f * s, cx - 10f * s, cy + 2f * s, streakPaint)

        canvas.drawLine(cx + 30f * s, cy + 2f * s, cx + 2f * s, cy + 30f * s, streakPaint)
        canvas.drawCircle(cx + 44f * s, cy - 12f * s, 5.5f * s, dotPaint)
    }

    private fun drawSunBehindCloudIcon(canvas: Canvas, cx: Float, cy: Float) {
        val s = 1.28f
        val sunRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFE09F24")
        }
        val sunCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFF8CA47")
        }
        canvas.drawCircle(cx + 22f * s, cy - 22f * s, 29f * s, sunRimPaint)
        canvas.drawCircle(cx + 22f * s, cy - 22f * s, 24f * s, sunCorePaint)

        drawCloudShape(
            canvas = canvas,
            cx = cx - 4f * s,
            cy = cy + 6f * s,
            scale = s,
            mainColor = Color.WHITE,
            shadowLobeColor = Color.parseColor("#FFEBEEF3")
        )
    }

    private fun drawSunIcon(canvas: Canvas, cx: Float, cy: Float) {
        val s = 1.45f
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#44FACC15")
        }
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFE69D1F")
        }
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFFBD04C")
        }
        canvas.drawCircle(cx, cy, 48f * s, glowPaint)
        canvas.drawCircle(cx, cy, 38f * s, rimPaint)
        canvas.drawCircle(cx, cy, 32f * s, corePaint)
    }

    private fun drawNightMoonCloudsIcon(canvas: Canvas, cx: Float, cy: Float, withClouds: Boolean) {
        val s = 1.30f
        // Desenha lua crescente em layer isolado para o recorte DST_OUT não apagar o degradê de fundo
        val saveCount = canvas.saveLayer(cx - 104f, cy - 104f, cx + 104f, cy + 104f, null)
        val moonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFC6DAE9")
        }
        val cutPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        val moonCx = if (withClouds) cx - 4f * s else cx
        val moonCy = if (withClouds) cy - 6f * s else cy
        canvas.drawCircle(moonCx, moonCy, 42f * s, moonPaint)
        canvas.drawCircle(moonCx + 22f * s, moonCy - 18f * s, 36f * s, cutPaint)
        canvas.restoreToCount(saveCount)

        if (withClouds) {
            val cloudWhite = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.WHITE
            }
            val cloudSoft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.parseColor("#FFE2E8F0")
            }
            // Nuvem pequena à esquerda
            canvas.drawRoundRect(
                RectF(cx - 58f * s, cy - 2f * s, cx - 22f * s, cy + 22f * s),
                12f * s,
                12f * s,
                cloudWhite
            )
            canvas.drawCircle(cx - 42f * s, cy - 4f * s, 15f * s, cloudWhite)

            // Nuvem média à direita inferior
            canvas.drawRoundRect(
                RectF(cx - 4f * s, cy + 12f * s, cx + 52f * s, cy + 44f * s),
                16f * s,
                16f * s,
                cloudWhite
            )
            canvas.drawCircle(cx + 16f * s, cy + 12f * s, 20f * s, cloudWhite)
            canvas.drawCircle(cx + 38f * s, cy + 26f * s, 14f * s, cloudSoft)
        }
    }

    private fun drawOvercastCloudIcon(canvas: Canvas, cx: Float, cy: Float) {
        val s = 1.24f
        drawCloudShape(
            canvas = canvas,
            cx = cx - 12f * s,
            cy = cy - 10f * s,
            scale = 0.85f * s,
            mainColor = Color.parseColor("#FF94A3B8"),
            shadowLobeColor = Color.parseColor("#FF7C8B9F")
        )
        drawCloudShape(
            canvas = canvas,
            cx = cx + 6f * s,
            cy = cy + 12f * s,
            scale = 0.95f * s,
            mainColor = Color.WHITE,
            shadowLobeColor = Color.parseColor("#FFE2E8F0")
        )
    }

    private fun drawSnowIcon(canvas: Canvas, cx: Float, cy: Float) {
        val s = 1.24f
        drawCloudShape(
            canvas = canvas,
            cx = cx,
            cy = cy - 16f * s,
            scale = s,
            mainColor = Color.WHITE,
            shadowLobeColor = Color.parseColor("#FFE2E8F0")
        )
        val flakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#FFE0F2FE")
        }
        canvas.drawCircle(cx - 26f * s, cy + 44f * s, 6f * s, flakePaint)
        canvas.drawCircle(cx + 2f * s, cy + 52f * s, 6f * s, flakePaint)
        canvas.drawCircle(cx + 30f * s, cy + 42f * s, 6f * s, flakePaint)
    }

    private fun drawLocationPinIcon(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        scale: Float = 1.0f
    ) {
        val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * scale
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor("#FFD2DCEB")
        }
        val r = 10f * scale
        val pinPath = Path().apply {
            arcTo(
                RectF(
                    centerX - r,
                    centerY - r - 3f * scale,
                    centerX + r,
                    centerY + r - 3f * scale
                ),
                155f,
                230f,
                false
            )
            lineTo(centerX, centerY + 13f * scale)
            close()
        }
        canvas.drawPath(pinPath, pinPaint)
        canvas.drawCircle(centerX, centerY - 3f * scale, 3.6f * scale, pinPaint)
    }
}

class ContextPillComplicationService : SuspendingComplicationDataSourceService() {

    private fun buildTapCyclePillPendingIntent(): PendingIntent {
        val tapIntent = Intent(applicationContext, AutonomousTransitionReceiver::class.java).apply {
            action = AutonomousCountdownScheduler.ACTION_TAP_CYCLE_PILL_CONTEXT
        }
        return PendingIntent.getBroadcast(
            applicationContext,
            3002,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        AutonomousCountdownScheduler.ensureScreenWakeListenerRegistered(applicationContext)

        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        val nowMs = System.currentTimeMillis()
        val pillBitmap = ContextPillBitmapRenderer.getOrRenderPillBitmap(repo, nowMs)
        val icon = Icon.createWithBitmap(pillBitmap)
        val tapPendingIntent = buildTapCyclePillPendingIntent()
        val contentDesc = PlainComplicationText.Builder("Clima ChronoWear").build()

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
        val sampleText = PlainComplicationText.Builder("Clima ChronoWear").build()
        val icon = Icon.createWithBitmap(ContextPillBitmapRenderer.renderPreviewBitmap())

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
