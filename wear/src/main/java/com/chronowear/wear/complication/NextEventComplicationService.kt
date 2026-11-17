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
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.text.TextPaint
import android.text.TextUtils
import android.view.Display
import androidx.wear.tiles.TileService
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
import com.chronowear.wear.data.WatchBatterySnapshot
import com.chronowear.wear.data.WeeklyStepsSnapshot
import com.chronowear.wear.tile.UpNextThreeEventsTileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

/**
 * Coordenador central reativo para as duas Complications (Canvas Principal + Cartucho) e Tile:
 * - Compara assinaturas de estado antes de abrir transações Binder (evita acordar Watch Face à toa).
 * - Respeita ciclo de vida (onComplicationActivated / Deactivated) para não atualizar slots inativos.
 * - Adia renderizações quando a tela está totalmente apagada (Display.STATE_OFF) no Canvas Principal
 *   e quando fora do modo interativo no Cartucho Inferior (oculto em AOD na v1.3.0),
 *   descarregando imediatamente via ACTION_SCREEN_ON.
 */
object ChronoWearComplicationUpdater {

    @Volatile
    private var lastDispatchedMainSignature: String? = null

    @Volatile
    private var lastDispatchedPillSignature: String? = null

    @Volatile
    private var lastDispatchedTileSignature: String? = null

    @Volatile
    private var pendingMainDirty: Boolean = false

    @Volatile
    private var pendingPillDirty: Boolean = false

    fun isDeviceInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive ?: true
    }

    fun isDisplayTotallyOff(context: Context): Boolean {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val state = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: return !isDeviceInteractive(context)
        return state == Display.STATE_OFF
    }

    fun markMainSignatureDelivered(signature: String?) {
        if (!signature.isNullOrBlank()) {
            lastDispatchedMainSignature = signature
        }
        pendingMainDirty = false
    }

    fun markPillSignatureDelivered(signature: String?) {
        if (!signature.isNullOrBlank()) {
            lastDispatchedPillSignature = signature
        }
        pendingPillDirty = false
    }

    fun requestUpdateMainSlot(context: Context, force: Boolean = false) {
        try {
            val appCtx = context.applicationContext
            val repo = WatchAutonomousRepository.getInstance(appCtx)
            if (!force && !repo.isMainSlotActiveOrUnknown()) {
                pendingMainDirty = false
                return
            }

            val nowMs = System.currentTimeMillis()
            val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
            val progressWindowMinutes = repo.getProgressWindowMinutes()
            val newSignature = AgendaCardBitmapRenderer.computeMainCardSignature(
                repo = repo,
                upcomingThree = upcomingThree,
                nowMs = nowMs,
                progressWindowMinutes = progressWindowMinutes
            )

            if (!force && newSignature == lastDispatchedMainSignature && !pendingMainDirty) {
                return
            }

            if (!force && isDisplayTotallyOff(appCtx)) {
                pendingMainDirty = true
                return
            }

            pendingMainDirty = false
            lastDispatchedMainSignature = newSignature
            ComplicationDataSourceUpdateRequester.create(
                appCtx,
                ComponentName(appCtx, NextEventComplicationService::class.java)
            ).requestUpdateAll()
        } catch (_: Exception) {
        }
    }

    fun requestUpdatePillSlotIfChanged(context: Context, force: Boolean = false) {
        try {
            val appCtx = context.applicationContext
            val repo = WatchAutonomousRepository.getInstance(appCtx)
            if (!force && !repo.isPillSlotActiveOrUnknown()) {
                pendingPillDirty = false
                return
            }

            val nowMs = System.currentTimeMillis()
            val newSignature = ContextPillBitmapRenderer.computeStateSignature(repo, nowMs)
            val prevSignature = lastDispatchedPillSignature ?: repo.getPillLastSignature()
            if (!force && newSignature == prevSignature && !pendingPillDirty) {
                return
            }

            // O Cartucho (Slot 4) é 100% oculto em modo AOD (alpha="0") na Watch Face 1.3.0.
            // Portanto, qualquer mudança enquanto a tela não está interativa fica pendente até o SCREEN_ON.
            if (!force && !isDeviceInteractive(appCtx)) {
                pendingPillDirty = true
                return
            }

            pendingPillDirty = false
            lastDispatchedPillSignature = newSignature
            ComplicationDataSourceUpdateRequester.create(
                appCtx,
                ComponentName(appCtx, ContextPillComplicationService::class.java)
            ).requestUpdateAll()
        } catch (_: Exception) {
        }
    }

    fun requestUpdateTileIfChanged(context: Context, force: Boolean = false) {
        try {
            val appCtx = context.applicationContext
            val repo = WatchAutonomousRepository.getInstance(appCtx)
            val nowMs = System.currentTimeMillis()
            val newTileSig = repo.computeTileSignature(nowMs)
            if (!force && newTileSig == lastDispatchedTileSignature) {
                return
            }
            lastDispatchedTileSignature = newTileSig
            TileService.getUpdater(appCtx)
                .requestUpdate(UpNextThreeEventsTileService::class.java)
        } catch (_: Exception) {
        }
    }

    fun requestUpdateAllSlots(context: Context, force: Boolean = false) {
        requestUpdateMainSlot(context, force = force)
        requestUpdatePillSlotIfChanged(context, force = force)
    }

    fun flushPendingOnScreenWake(context: Context) {
        val hadPendingMain = pendingMainDirty
        val hadPendingPill = pendingPillDirty
        if (hadPendingMain) {
            requestUpdateMainSlot(context, force = false)
        }
        if (hadPendingPill) {
            requestUpdatePillSlotIfChanged(context, force = false)
        }
        if (!hadPendingMain && !hadPendingPill) {
            requestUpdateAllSlots(context, force = false)
        }
    }
}

/**
 * Motor Multi-Contexto de Renderização em Canvas 2D de Alto DPI (604x272px @2x Retina para 302x136px):
 * - Inclui Cache em Memória por Assinatura de Estado + Objetos Paint/TextPaint/Shader pré-alocados.
 * - Elimina alocações de 657 KB por minuto quando o conteúdo visual do Canvas não mudou.
 */
object AgendaCardBitmapRenderer {

    private const val CARD_WIDTH = 604
    private const val CARD_HEIGHT = 272

    @Volatile
    private var cachedSignature: String? = null

    @Volatile
    private var cachedBitmap: Bitmap? = null

    @Volatile
    private var lastPreparedBucketSec: Long = -1L

    @Volatile
    private var lastPreparedState: PreparedMainCardState? = null

    private var cardBufferA: Bitmap? = null
    private var cardBufferB: Bitmap? = null
    private var useBufferA: Boolean = true
    private val reusableCanvas = Canvas()

    private val cacheLock = Any()

    private val localePtBr = Locale("pt", "BR")
    private val boldTypeface: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val normalTypeface: Typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)

    // Objetos compartilhados reutilizáveis (protegidos estritamente por cacheLock)
    private val cardRect = RectF(2f, 2f, CARD_WIDTH - 2f, CARD_HEIGHT - 2f)
    private val scratchRect = RectF()
    private val scratchPath = Path()

    private val bgCardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A080A0E")
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#252E3A")
    }
    private val sleepEmptyTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1E293B")
    }
    private val goldFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F5C438")
    }
    private val cyanFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#67E8F9")
    }
    private val greenFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4ADE80")
    }
    private val emeraldFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#34D399")
    }
    private val lavenderFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#A78BFA")
    }
    private val darkCutStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        color = Color.parseColor("#1A080A0E")
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val darkCutFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1A080A0E")
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val checkCutStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#1A080A0E")
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }

    private val headerGoldPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5C438")
        textSize = 42f
        typeface = boldTypeface
    }
    private val headerGreenPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ADE80")
        textSize = 42f
        typeface = boldTypeface
    }
    private val headerLavenderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#C4B5FD")
        textSize = 42f
        typeface = boldTypeface
    }
    private val headerTasksPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#34D399")
        textSize = 37f
        typeface = boldTypeface
    }

    // Agenda paints
    private val eventTimePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 44f
        typeface = boldTypeface
    }
    private val eventTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5C438")
        textSize = 44f
        typeface = boldTypeface
    }
    private val eventCountdownPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#67E8F9")
        textSize = 35f
        textAlign = Paint.Align.RIGHT
        typeface = boldTypeface
    }
    private val eventEmptySlotPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#263344")
        textSize = 36f
        typeface = boldTypeface
    }
    private val eventEmptyDayPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 38f
        typeface = normalTypeface
    }

    // Weather paints
    private val weatherRightBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#67E8F9")
        textSize = 32f
        textAlign = Paint.Align.RIGHT
        typeface = boldTypeface
    }
    private val weatherSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = 28f
        typeface = boldTypeface
    }
    private val weatherCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7.5f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#67E8F9")
    }
    private val weatherTempLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 35f
        textAlign = Paint.Align.CENTER
        typeface = boldTypeface
    }
    private val weatherHourLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = 29f
        textAlign = Paint.Align.CENTER
        typeface = boldTypeface
    }

    // Steps paints
    private val stepsRightCountPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 35f
        textAlign = Paint.Align.RIGHT
        typeface = boldTypeface
    }
    private val stepsDayLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        textSize = 26f
        textAlign = Paint.Align.CENTER
        typeface = boldTypeface
    }

    // Solar paints
    private val sunLimeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#65A30D")
    }
    private val sunLimeCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#BEF264")
    }
    private val sunLimeLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#84CC16")
    }
    private val sunBlueGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3B82F6")
        alpha = 150
    }
    private val sunBlueCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#93C5FD")
    }
    private val sunBlueLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#93C5FD")
    }
    private val sunTimeLeftPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f
        typeface = boldTypeface
    }
    private val sunTimeRightPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f
        textAlign = Paint.Align.RIGHT
        typeface = boldTypeface
    }
    private val sunSkyGradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        shader = LinearGradient(
            0f, 74f, 0f, 160f,
            intArrayOf(
                Color.parseColor("#00080A0E"),
                Color.parseColor("#443B5278"),
                Color.parseColor("#886488B8")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
    }
    private val sunDottedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#64748B")
        pathEffect = DashPathEffect(floatArrayOf(3f, 11f), 0f)
    }
    private val sunHorizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.5f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#8AB4F8")
    }
    private val sunSymbolStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#94A3B8")
    }
    private val sunCurvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val sunHaloOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66FDE047")
    }
    private val sunHaloInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CCFACC15")
    }
    private val sunCoreDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FEF9C3")
    }

    // Sleep paints
    private val sleepRightEmptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 32f
        textAlign = Paint.Align.RIGHT
        typeface = boldTypeface
    }
    private val sleepNoDataMainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textSize = 34f
        typeface = boldTypeface
    }
    private val sleepNoDataSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 26f
        typeface = boldTypeface
    }
    private val sleepRightScorePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 35f
        textAlign = Paint.Align.RIGHT
        typeface = boldTypeface
    }
    private val sleepSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = 28f
        typeface = boldTypeface
    }
    private val sleepStageSegPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sleepStageTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 26f
        textAlign = Paint.Align.CENTER
        typeface = boldTypeface
    }
    private val sleepStageValPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 31f
        textAlign = Paint.Align.CENTER
        typeface = boldTypeface
    }
    private val sleepStageColors = intArrayOf(
        Color.parseColor("#F87171"),
        Color.parseColor("#38BDF8"),
        Color.parseColor("#818CF8"),
        Color.parseColor("#4F46E5")
    )
    private val sleepStageNames = arrayOf("Acord.", "REM", "Leve", "Prof.")

    // Tasks paints
    private val tasksRightCounterPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#67E8F9")
        textSize = 29f
        textAlign = Paint.Align.RIGHT
        typeface = boldTypeface
    }
    private val tasksPendingCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.2f
        color = Color.parseColor("#F5C438")
    }
    private val tasksOverdueCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4.5f
        color = Color.parseColor("#EF4444")
    }
    private val tasksEmptyCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#475569")
    }
    private val tasksTimeColPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 35f
        typeface = boldTypeface
    }
    private val tasksTitlePendingPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textSize = 38f
        typeface = boldTypeface
    }
    private val tasksTitleOverduePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F87171")
        textSize = 38f
        typeface = boldTypeface
    }
    private val tasksEmptyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = 34f
        typeface = boldTypeface
    }
    private val tasksSourceBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F5C438")
        textSize = 27f
        textAlign = Paint.Align.RIGHT
        typeface = boldTypeface
    }

    // Battery paints
    private val batStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val batFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val batHeaderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 40f
        typeface = boldTypeface
    }
    private val batRightSubPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = 27f
        textAlign = Paint.Align.RIGHT
        typeface = boldTypeface
    }
    private val batDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#1E293B")
    }
    private val batGridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#162032")
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val batFillAreaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val batLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val batValuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        typeface = boldTypeface
    }
    private val batTimeLabelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = 27f
        textAlign = Paint.Align.CENTER
        typeface = boldTypeface
    }
    private val batAreaShaderEmerald = LinearGradient(
        0f, 104f, 0f, 210f,
        Color.argb(95, 52, 211, 153),
        Color.argb(8, 52, 211, 153),
        Shader.TileMode.CLAMP
    )
    private val batAreaShaderAmber = LinearGradient(
        0f, 104f, 0f, 210f,
        Color.argb(95, 251, 191, 36),
        Color.argb(8, 251, 191, 36),
        Shader.TileMode.CLAMP
    )
    private val batAreaShaderRed = LinearGradient(
        0f, 104f, 0f, 210f,
        Color.argb(95, 248, 113, 113),
        Color.argb(8, 248, 113, 113),
        Shader.TileMode.CLAMP
    )

    private sealed class PreparedMainCardState(val signature: String) {
        class Tasks(signature: String, val snapshot: TasksSnapshot) : PreparedMainCardState(signature)
        class Weather(signature: String, val snapshot: HourlyWeatherSnapshot) : PreparedMainCardState(signature)
        class Steps(signature: String, val snapshot: WeeklyStepsSnapshot) : PreparedMainCardState(signature)
        class Sun(signature: String, val snapshot: SolarSnapshot) : PreparedMainCardState(signature)
        class Sleep(signature: String, val snapshot: SleepSnapshot) : PreparedMainCardState(signature)
        class Battery(signature: String, val snapshot: WatchBatterySnapshot) : PreparedMainCardState(signature)
        class Events(
            signature: String,
            val headerTitle: String,
            val rows: List<Triple<String, String, String>>,
            val progressFraction: Float
        ) : PreparedMainCardState(signature)
    }

    private fun prepareMainCardState(
        repo: WatchAutonomousRepository,
        upcomingThree: List<CachedWearEvent>,
        nowMs: Long,
        progressWindowMinutes: Int
    ): PreparedMainCardState {
        val secBucket = nowMs / 1000L
        val effectiveMode = repo.resolveEffectiveContextMode(nowMs, upcomingThree)
        val existingPrepared = lastPreparedState
        if (lastPreparedBucketSec == secBucket &&
            existingPrepared != null &&
            existingPrepared.signature.startsWith("$effectiveMode|")
        ) {
            return existingPrepared
        }
        val computed: PreparedMainCardState = when (effectiveMode) {
            WatchAutonomousRepository.MODE_TASKS -> {
                val snap = repo.getTasksSnapshot(nowMs)
                val rowsSig = snap.tasks.asSequence()
                    .filter { !it.completed }
                    .take(3)
                    .joinToString(";") { "${it.id}:${it.title}:${it.dueHHMM}:${it.isOverdue}:${it.sourceLabel}" }
                PreparedMainCardState.Tasks(
                    signature = "TASKS|${snap.headerTitle}|${snap.rightBadgeText}|${snap.showTime}|$rowsSig",
                    snapshot = snap
                )
            }
            WatchAutonomousRepository.MODE_WEATHER -> {
                val snap = repo.getHourlyWeatherSnapshot(nowMs)
                val tempsSig = snap.hourlyTempsC.take(5).joinToString(",")
                val hoursSig = snap.hourlyLabels.take(5).joinToString(",")
                val locSig = WatchAutonomousRepository.formatCompactWeatherLocation(snap.locationName)
                PreparedMainCardState.Weather(
                    signature = "WEATHER|${snap.weatherConditionLabel}|${snap.rainProbabilityPct}|$locSig|${snap.updatedTimeHHMM}|$tempsSig|$hoursSig",
                    snapshot = snap
                )
            }
            WatchAutonomousRepository.MODE_STEPS -> {
                val snap = repo.getWeeklyStepsSnapshot()
                PreparedMainCardState.Steps(
                    signature = "STEPS|${snap.todaySteps}|${snap.dailyGoal}|${snap.weekDaysSteps.take(7).joinToString(",")}|${snap.weekDaysLabels.take(7).joinToString(",")}",
                    snapshot = snap
                )
            }
            WatchAutonomousRepository.MODE_SUN -> {
                val snap = repo.getSolarSnapshot(nowMs)
                // Agrupa em janelas de 3 min (~1 px no gráfico de 552px) para cortar redesenhos redundantes em 3x
                val sunStep = snap.currentMinutesOfDay / 3
                PreparedMainCardState.Sun(
                    signature = "SUN|${snap.sunriseHHMM}|${snap.sunsetHHMM}|$sunStep",
                    snapshot = snap
                )
            }
            WatchAutonomousRepository.MODE_SLEEP -> {
                val snap = repo.getSleepSnapshot(nowMs)
                PreparedMainCardState.Sleep(
                    signature = "SLEEP|${snap.hasDataForToday}|${snap.durationMinutes}|${snap.sleepScore}|${snap.showScore}|${snap.bedtimeHHMM}|${snap.wakeHHMM}|${snap.stagesMinutes.take(4).joinToString(",")}",
                    snapshot = snap
                )
            }
            WatchAutonomousRepository.MODE_BATTERY -> {
                val snap = repo.getWatchBatterySnapshot(nowMs)
                PreparedMainCardState.Battery(
                    signature = "BATTERY|${snap.currentPct}|${snap.isCharging}|${snap.rateLabel}|${snap.labels.joinToString(",")}|${snap.levelsPct.joinToString(",")}",
                    snapshot = snap
                )
            }
            else -> {
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

                val progressPct = (progressFraction * 100f).toInt()
                val rowsSig = rows.joinToString(";") { "${it.first}:${it.second}:${it.third}" }
                PreparedMainCardState.Events(
                    signature = "EVENTS|$dayHeader|$progressPct|$rowsSig",
                    headerTitle = dayHeader,
                    rows = rows,
                    progressFraction = progressFraction
                )
            }
        }
        lastPreparedState = computed
        lastPreparedBucketSec = secBucket
        return computed
    }

    fun computeMainCardSignature(
        repo: WatchAutonomousRepository,
        upcomingThree: List<CachedWearEvent>,
        nowMs: Long,
        progressWindowMinutes: Int
    ): String {
        return prepareMainCardState(repo, upcomingThree, nowMs, progressWindowMinutes).signature
    }

    fun getCachedSignature(): String? = cachedSignature

    fun getOrRenderCardBitmap(
        repo: WatchAutonomousRepository,
        upcomingThree: List<CachedWearEvent>,
        nowMs: Long,
        progressWindowMinutes: Int
    ): Bitmap {
        val prepared = prepareMainCardState(repo, upcomingThree, nowMs, progressWindowMinutes)
        synchronized(cacheLock) {
            val existing = cachedBitmap
            if (cachedSignature == prepared.signature && existing != null && !existing.isRecycled) {
                return existing
            }
            val rendered = when (prepared) {
                is PreparedMainCardState.Tasks -> renderTasksChecklistCanvas(prepared.snapshot)
                is PreparedMainCardState.Weather -> renderWeatherForecastCanvas(prepared.snapshot)
                is PreparedMainCardState.Steps -> renderWeeklyStepsCanvas(prepared.snapshot)
                is PreparedMainCardState.Sun -> renderSunriseSunsetCanvas(prepared.snapshot)
                is PreparedMainCardState.Sleep -> renderSleepSummaryCanvas(prepared.snapshot)
                is PreparedMainCardState.Battery -> renderWatchBatteryCanvas(prepared.snapshot)
                is PreparedMainCardState.Events -> renderEventsCardCanvas(
                    headerTitle = prepared.headerTitle,
                    rows = prepared.rows,
                    progressFraction = prepared.progressFraction
                )
            }
            cachedSignature = prepared.signature
            cachedBitmap = rendered
            return rendered
        }
    }

    fun renderPreviewBitmap(): Bitmap {
        val sampleRows = listOf(
            Triple("16:30", "Adolescentes", "8m"),
            Triple("18:00", "Preparação das atividades", ""),
            Triple("07:00", "Projeto Guará", "")
        )
        synchronized(cacheLock) {
            return renderEventsCardCanvas(
                headerTitle = "Hoje",
                rows = sampleRows,
                progressFraction = 0.85f
            )
        }
    }

    private fun createBaseCardBitmap(): Pair<Bitmap, Canvas> {
        val target = if (useBufferA) {
            val b = cardBufferA?.takeIf { !it.isRecycled }
                ?: Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888).also { cardBufferA = it }
            b
        } else {
            val b = cardBufferB?.takeIf { !it.isRecycled }
                ?: Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888).also { cardBufferB = it }
            b
        }
        useBufferA = !useBufferA
        target.eraseColor(Color.TRANSPARENT)
        reusableCanvas.setBitmap(target)
        reusableCanvas.drawRoundRect(cardRect, 40f, 40f, bgCardPaint)
        return target to reusableCanvas
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

        // Ícone de Calendário Dourado Samsung ampliado
        scratchRect.set(30f, 14f, 37f, 26f)
        canvas.drawRoundRect(scratchRect, 2.5f, 2.5f, goldFillPaint)
        scratchRect.set(51f, 14f, 58f, 26f)
        canvas.drawRoundRect(scratchRect, 2.5f, 2.5f, goldFillPaint)
        scratchRect.set(24f, 21f, 64f, 55f)
        canvas.drawRoundRect(scratchRect, 7f, 7f, goldFillPaint)
        canvas.drawLine(31f, 33f, 57f, 33f, darkCutStrokePaint)
        canvas.drawLine(31f, 44f, 50f, 44f, darkCutStrokePaint)

        canvas.drawText(headerTitle, 78f, 51f, headerGoldPaint)

        val row1 = rows.getOrNull(0) ?: Triple("", "Sem compromissos", "")
        if (row1.first.isNotBlank()) {
            canvas.drawText(row1.first, 24f, 105f, eventTimePaint)
            val row1TitleMaxWidth = if (row1.third.isNotBlank()) 290f else 420f
            val row1Ellipsized = TextUtils.ellipsize(
                row1.second,
                eventTitlePaint,
                row1TitleMaxWidth,
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(row1Ellipsized, 150f, 105f, eventTitlePaint)
            if (row1.third.isNotBlank()) {
                canvas.drawText(row1.third, CARD_WIDTH - 24f, 103f, eventCountdownPaint)
            }
        } else {
            val emptyLabel = row1.second.ifBlank { "Sem compromissos" }
            canvas.drawText(emptyLabel, 24f, 105f, eventEmptyDayPaint)
        }

        val barTop = 117f
        val barBottom = 130f
        val barLeft = 24f
        val barRight = CARD_WIDTH - 24f
        scratchRect.set(barLeft, barTop, barRight, barBottom)
        canvas.drawRoundRect(scratchRect, 6.5f, 6.5f, trackPaint)

        if (progressFraction > 0f) {
            val fillWidth = ((barRight - barLeft) * progressFraction.coerceIn(0f, 1f)).coerceAtLeast(13f)
            scratchRect.set(barLeft, barTop, barLeft + fillWidth, barBottom)
            canvas.drawRoundRect(scratchRect, 6.5f, 6.5f, goldFillPaint)
        }

        val row2 = rows.getOrNull(1) ?: Triple("", "", "")
        if (row2.first.isNotBlank()) {
            canvas.drawText(row2.first, 24f, 188f, eventTimePaint)
            val row2Ellipsized = TextUtils.ellipsize(
                row2.second,
                eventTitlePaint,
                430f,
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(row2Ellipsized, 150f, 188f, eventTitlePaint)
        } else {
            canvas.drawText("•", 24f, 188f, eventEmptySlotPaint)
        }

        val row3 = rows.getOrNull(2) ?: Triple("", "", "")
        if (row3.first.isNotBlank()) {
            canvas.drawText(row3.first, 24f, 250f, eventTimePaint)
            val row3Ellipsized = TextUtils.ellipsize(
                row3.second,
                eventTitlePaint,
                430f,
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(row3Ellipsized, 150f, 250f, eventTitlePaint)
        } else {
            canvas.drawText("•", 24f, 250f, eventEmptySlotPaint)
        }

        return bmp
    }

    /**
     * CONTEXTO 2: Clima (Título apenas "Clima", condição climática + chuva e curva com DPI ampliado)
     */
    private fun renderWeatherForecastCanvas(weather: HourlyWeatherSnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        // Ícone de Sol + Nuvem ampliado
        canvas.drawCircle(38f, 34f, 12f, goldFillPaint)
        scratchRect.set(34f, 32f, 68f, 52f)
        canvas.drawRoundRect(scratchRect, 10f, 10f, cyanFillPaint)

        // Título limpo: apenas "Clima"
        canvas.drawText("Clima", 80f, 51f, headerGoldPaint)

        // Badge à direita com condição climática + chuva
        val rightBadgeText = if (weather.rainProbabilityPct > 0 || weather.weatherConditionLabel.contains("Chuva", true)) {
            "${weather.weatherConditionLabel} • Chuva ${weather.rainProbabilityPct}%"
        } else {
            "${weather.weatherConditionLabel} • ${weather.rainProbabilityPct}%"
        }
        canvas.drawText(rightBadgeText, CARD_WIDTH - 24f, 49f, weatherRightBadgePaint)

        val compactLoc = WatchAutonomousRepository.formatCompactWeatherLocation(weather.locationName)
        val locationSubtitle = "📍 $compactLoc • Atualizado ${weather.updatedTimeHHMM}"
        val ellipsizedLocation = TextUtils.ellipsize(
            locationSubtitle,
            weatherSubPaint,
            (CARD_WIDTH - 48).toFloat(),
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(ellipsizedLocation, 24f, 92f, weatherSubPaint)

        // Curva de Mudança de Temperatura (5 pontos nas próximas horas)
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

        scratchPath.reset()
        points.forEachIndexed { idx, (x, y) ->
            if (idx == 0) {
                scratchPath.moveTo(x, y)
            } else {
                val prev = points[idx - 1]
                val ctrlX = (prev.first + x) / 2f
                scratchPath.cubicTo(ctrlX, prev.second, ctrlX, y, x, y)
            }
        }
        canvas.drawPath(scratchPath, weatherCurvePaint)

        points.forEachIndexed { idx, (x, y) ->
            val dotPaint = if (idx == 0) goldFillPaint else cyanFillPaint
            canvas.drawCircle(x, y, 9.5f, dotPaint)
            canvas.drawText("${temps[idx]}°", x, y - 16f, weatherTempLabelPaint)
            val lbl = labels.getOrNull(idx) ?: ""
            canvas.drawText(lbl, x, 254f, weatherHourLabelPaint)
        }

        return bmp
    }

    /**
     * CONTEXTO 3: Passos Semanais & Meta Diária (Alto DPI)
     */
    private fun renderWeeklyStepsCanvas(steps: WeeklyStepsSnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        scratchRect.set(24f, 18f, 60f, 50f)
        canvas.drawRoundRect(scratchRect, 16f, 16f, greenFillPaint)

        canvas.drawText("Passos", 74f, 50f, headerGreenPaint)

        val todayFormatted = String.format(localePtBr, "%,d", steps.todaySteps)
        val goalFormatted = String.format(localePtBr, "%,d", steps.dailyGoal)
        canvas.drawText("$todayFormatted / $goalFormatted", CARD_WIDTH - 24f, 50f, stepsRightCountPaint)

        val barLeft = 24f
        val barRight = CARD_WIDTH - 24f
        scratchRect.set(barLeft, 66f, barRight, 79f)
        canvas.drawRoundRect(scratchRect, 6.5f, 6.5f, trackPaint)
        if (steps.todaySteps > 0) {
            val ratio = (steps.todaySteps.toFloat() / steps.dailyGoal.coerceAtLeast(1).toFloat()).coerceIn(0.04f, 1f)
            scratchRect.set(barLeft, 66f, barLeft + (barRight - barLeft) * ratio, 79f)
            canvas.drawRoundRect(scratchRect, 6.5f, 6.5f, greenFillPaint)
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

        values.forEachIndexed { idx, v ->
            val cx = chartLeft + idx * colSpacing + colSpacing / 2f
            scratchRect.set(cx - 20f, chartTop, cx + 20f, chartBottom)
            canvas.drawRoundRect(scratchRect, 10f, 10f, trackPaint)
            if (v > 0) {
                val h = ((v.toFloat() / maxVal) * (chartBottom - chartTop)).coerceAtLeast(12f)
                scratchRect.set(cx - 20f, chartBottom - h, cx + 20f, chartBottom)
                canvas.drawRoundRect(scratchRect, 10f, 10f, if (idx == todayIdx) goldFillPaint else greenFillPaint)
            }
            stepsDayLabelPaint.color = if (idx == todayIdx) Color.parseColor("#F5C438") else Color.parseColor("#E2E8F0")
            canvas.drawText(labels.getOrNull(idx) ?: "", cx, 250f, stepsDayLabelPaint)
        }

        return bmp
    }

    /**
     * CONTEXTO 4: Nascer e Pôr do Sol (Curva Solar Completa Dia e Noite — Fiel ao Design One UI Watch)
     */
    private fun renderSunriseSunsetCanvas(solar: SolarSnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        // 1. Ícone de Nascer do Sol (Verde-Lima no topo esquerdo) + Horário
        canvas.drawCircle(52f, 42f, 21f, sunLimeGlowPaint)
        canvas.drawCircle(52f, 42f, 14f, sunLimeCorePaint)
        scratchPath.reset()
        scratchPath.apply {
            moveTo(24f, 56f)
            lineTo(42f, 56f)
            quadTo(52f, 44f, 62f, 56f)
            lineTo(80f, 56f)
        }
        canvas.drawPath(scratchPath, sunLimeLinePaint)
        canvas.drawText(solar.sunriseHHMM, 94f, 56f, sunTimeLeftPaint)

        // 2. Horário Pôr do Sol + Ícone de Pôr do Sol (Azul Suave no topo direito)
        canvas.drawText(solar.sunsetHHMM, CARD_WIDTH - 94f, 56f, sunTimeRightPaint)

        val iconRightCx = CARD_WIDTH - 52f
        canvas.drawCircle(iconRightCx, 42f, 21f, sunBlueGlowPaint)
        canvas.drawCircle(iconRightCx, 42f, 14f, sunBlueCorePaint)
        scratchPath.reset()
        scratchPath.apply {
            moveTo(iconRightCx - 28f, 54f)
            lineTo(iconRightCx - 10f, 54f)
            quadTo(iconRightCx, 66f, iconRightCx + 10f, 54f)
            lineTo(iconRightCx + 28f, 54f)
        }
        canvas.drawPath(scratchPath, sunBlueLinePaint)

        // 3. Geometria do Gráfico Solar
        val graphLeft = 26f
        val graphRight = CARD_WIDTH - 26f // 578f
        val sunriseX = 164f
        val noonX = CARD_WIDTH / 2f // 302f
        val sunsetX = CARD_WIDTH - 164f // 440f
        val horizonY = 160f
        val graphTop = 74f
        val graphBottom = 242f

        canvas.drawRect(graphLeft, graphTop, graphRight, horizonY, sunSkyGradientPaint)

        // 4. Três linhas verticais pontilhadas
        for (vx in floatArrayOf(sunriseX, noonX, sunsetX)) {
            scratchPath.reset()
            scratchPath.moveTo(vx, 74f)
            scratchPath.lineTo(vx, 200f)
            canvas.drawPath(scratchPath, sunDottedPaint)
        }

        // 5. Linha Horizontal Azul-Celeste do Horizonte
        canvas.drawLine(graphLeft, horizonY, graphRight, horizonY, sunHorizonPaint)

        // 6. Símbolos inferiores sob as 3 linhas pontilhadas: ↑, ☼, ↓
        canvas.drawLine(sunriseX, 240f, sunriseX, 212f, sunSymbolStrokePaint)
        canvas.drawLine(sunriseX - 10f, 222f, sunriseX, 212f, sunSymbolStrokePaint)
        canvas.drawLine(sunriseX + 10f, 222f, sunriseX, 212f, sunSymbolStrokePaint)

        val sunCenterY = 226f
        canvas.drawCircle(noonX, sunCenterY, 6.5f, sunSymbolStrokePaint)
        for (deg in 0 until 360 step 45) {
            val rad = Math.toRadians(deg.toDouble())
            val x1 = noonX + (11.5f * cos(rad)).toFloat()
            val y1 = sunCenterY + (11.5f * sin(rad)).toFloat()
            val x2 = noonX + (15.5f * cos(rad)).toFloat()
            val y2 = sunCenterY + (15.5f * sin(rad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, sunSymbolStrokePaint)
        }

        canvas.drawLine(sunsetX, 212f, sunsetX, 240f, sunSymbolStrokePaint)
        canvas.drawLine(sunsetX - 10f, 230f, sunsetX, 240f, sunSymbolStrokePaint)
        canvas.drawLine(sunsetX + 10f, 230f, sunsetX, 240f, sunSymbolStrokePaint)

        // 7. Curva Senoidal Completa (Dia e Noite)
        val halfDaySpan = sunsetX - noonX // 138f
        val amplitude = 76f
        scratchPath.reset()
        val steps = 96
        for (i in 0..steps) {
            val x = graphLeft + (graphRight - graphLeft) * (i.toFloat() / steps.toFloat())
            val angle = (Math.PI / 2.0) * ((x - noonX) / halfDaySpan)
            val y = (horizonY - amplitude * cos(angle).toFloat()).coerceIn(80f, graphBottom)
            if (i == 0) scratchPath.moveTo(x, y) else scratchPath.lineTo(x, y)
        }
        canvas.drawPath(scratchPath, sunCurvePaint)

        // 8. Ponto Luminoso do Sol
        val curMins = solar.currentMinutesOfDay.toFloat()
        val riseMins = solar.sunriseMinutesOfDay.toFloat().coerceAtLeast(1f)
        val setMins = solar.sunsetMinutesOfDay.toFloat().coerceAtLeast(riseMins + 60f)

        val sunX = when {
            curMins <= riseMins -> {
                val frac = (curMins / riseMins).coerceIn(0f, 1f)
                graphLeft + frac * (sunriseX - graphLeft)
            }
            curMins <= setMins -> {
                val frac = ((curMins - riseMins) / (setMins - riseMins)).coerceIn(0f, 1f)
                sunriseX + frac * (sunsetX - sunriseX)
            }
            else -> {
                val remDay = (1440f - setMins).coerceAtLeast(1f)
                val frac = ((curMins - setMins) / remDay).coerceIn(0f, 1f)
                sunsetX + frac * (graphRight - sunsetX)
            }
        }
        val sunAngle = (Math.PI / 2.0) * ((sunX - noonX) / halfDaySpan)
        val sunY = (horizonY - amplitude * cos(sunAngle).toFloat()).coerceIn(80f, graphBottom)

        canvas.drawCircle(sunX, sunY, 20f, sunHaloOuterPaint)
        canvas.drawCircle(sunX, sunY, 13f, sunHaloInnerPaint)
        canvas.drawCircle(sunX, sunY, 8.5f, sunCoreDotPaint)

        return bmp
    }

    /**
     * CONTEXTO 5: Resumo de Sono (Hipnograma e Pontuação do Dia)
     */
    private fun renderSleepSummaryCanvas(sleep: SleepSnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        // Ícone de Lua Crescente no topo esquerdo
        canvas.drawCircle(44f, 36f, 17f, lavenderFillPaint)
        canvas.drawCircle(52f, 31f, 14f, darkCutFillPaint)

        canvas.drawText("Sono", 76f, 50f, headerLavenderPaint)

        if (!sleep.hasDataForToday || sleep.durationMinutes <= 0) {
            canvas.drawText("Hoje", CARD_WIDTH - 24f, 50f, sleepRightEmptyPaint)
            canvas.drawText("Não há dados de sono para hoje", 24f, 108f, sleepNoDataMainPaint)
            scratchRect.set(24f, 130f, CARD_WIDTH - 24f, 162f)
            canvas.drawRoundRect(scratchRect, 10f, 10f, sleepEmptyTrackPaint)
            canvas.drawText("Verificado no Samsung Health / Health Connect", 24f, 214f, sleepNoDataSubPaint)
            return bmp
        }

        val hours = sleep.durationMinutes / 60
        val mins = sleep.durationMinutes % 60
        val topRightLabel = if (sleep.showScore && sleep.sleepScore > 0) {
            "${hours}h ${mins}m • ${sleep.sleepScore} pts"
        } else {
            "${hours}h ${mins}m"
        }
        canvas.drawText(topRightLabel, CARD_WIDTH - 24f, 50f, sleepRightScorePaint)

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
        canvas.drawText(subtitleLabel, 24f, 92f, sleepSubPaint)

        // Barra Segmentada de Estágios do Sono (Acordado, REM, Leve, Profundo)
        val stages = sleep.stagesMinutes.take(4).let {
            if (it.size == 4) it else listOf(0, 0, sleep.durationMinutes.coerceAtLeast(1), 0)
        }
        val totalStageMins = stages.sum().coerceAtLeast(1).toFloat()

        val barLeft = 24f
        val barRight = CARD_WIDTH - 24f
        val totalBarWidth = barRight - barLeft
        val barTop = 114f
        val barBottom = 152f

        var curX = barLeft
        stages.forEachIndexed { idx, stageMins ->
            val segW = (stageMins.toFloat() / totalStageMins) * totalBarWidth
            sleepStageSegPaint.color = sleepStageColors[idx]
            val rightEdge = (curX + segW - 4f).coerceAtLeast(curX + 6f)
            scratchRect.set(curX, barTop, rightEdge, barBottom)
            canvas.drawRoundRect(scratchRect, 10f, 10f, sleepStageSegPaint)
            curX += segW
        }

        // Legenda de Estágios na parte inferior
        val colWidth = totalBarWidth / 4f
        stages.forEachIndexed { idx, stageMins ->
            val cx = barLeft + idx * colWidth + colWidth / 2f
            val sh = stageMins / 60
            val sm = stageMins % 60
            val durText = if (sh > 0) "${sh}h${String.format(Locale.US, "%02d", sm)}" else "${sm}m"
            canvas.drawText(sleepStageNames[idx], cx, 198f, sleepStageTitlePaint)
            canvas.drawText(durText, cx, 240f, sleepStageValPaint)
        }

        return bmp
    }

    /**
     * CONTEXTO 6: Tarefas (Samsung Reminder e Google Tasks)
     */
    private fun renderTasksChecklistCanvas(tasksSnap: TasksSnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        // Ícone de Checklist no topo esquerdo
        scratchRect.set(24f, 18f, 62f, 56f)
        canvas.drawRoundRect(scratchRect, 10f, 10f, emeraldFillPaint)
        scratchPath.reset()
        scratchPath.apply {
            moveTo(33f, 38f)
            lineTo(41f, 46f)
            lineTo(54f, 29f)
        }
        canvas.drawPath(scratchPath, checkCutStrokePaint)

        val rightBadgeWidth = tasksRightCounterPaint.measureText(tasksSnap.rightBadgeText)
        val maxHeaderWidth = (CARD_WIDTH - 24f - rightBadgeWidth - 16f - 76f).coerceAtLeast(180f)
        val ellipsizedHeader = TextUtils.ellipsize(
            tasksSnap.headerTitle,
            headerTasksPaint,
            maxHeaderWidth,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(ellipsizedHeader, 76f, 51f, headerTasksPaint)

        val activeTasks = tasksSnap.tasks.filter { !it.completed }
        canvas.drawText(
            tasksSnap.rightBadgeText,
            CARD_WIDTH - 24f,
            49f,
            tasksRightCounterPaint
        )

        val rowYPositions = floatArrayOf(108f, 174f, 240f)
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
                    if (isOverdueRed) tasksOverdueCirclePaint else tasksPendingCirclePaint
                )

                val titlePaint = if (isOverdueRed) tasksTitleOverduePaint else tasksTitlePendingPaint

                if (tasksSnap.showTime && task.dueHHMM.isNotBlank()) {
                    canvas.drawText(task.dueHHMM.take(5), 64f, baseY, tasksTimeColPaint)
                    val ellipsizedTask = TextUtils.ellipsize(
                        task.title,
                        titlePaint,
                        295f,
                        TextUtils.TruncateAt.END
                    ).toString()
                    canvas.drawText(ellipsizedTask, 174f, baseY, titlePaint)
                } else {
                    val ellipsizedTask = TextUtils.ellipsize(
                        task.title,
                        titlePaint,
                        395f,
                        TextUtils.TruncateAt.END
                    ).toString()
                    canvas.drawText(ellipsizedTask, 68f, baseY, titlePaint)
                }

                tasksSourceBadgePaint.color = when {
                    isOverdueRed -> Color.parseColor("#EF4444")
                    task.sourceLabel.contains("Reminder", ignoreCase = true) -> Color.parseColor("#67E8F9")
                    else -> Color.parseColor("#F5C438")
                }
                canvas.drawText(task.sourceLabel.take(8), CARD_WIDTH - 24f, baseY - 2f, tasksSourceBadgePaint)
            } else {
                canvas.drawCircle(38f, centerY, 10f, tasksEmptyCirclePaint)
                if (idx == 0 && displayTasks.isEmpty()) {
                    canvas.drawText("Não há tarefas pendentes", 68f, baseY, tasksEmptyPaint)
                }
            }
        }

        return bmp
    }

    /**
     * CONTEXTO 7: Consumo de Bateria do Relógio
     */
    private fun renderWatchBatteryCanvas(battery: WatchBatterySnapshot): Bitmap {
        val (bmp, canvas) = createBaseCardBitmap()

        val pct = battery.currentPct.coerceIn(0, 100)
        val accentHex = when {
            battery.isCharging -> "#34D399"
            pct <= 15 -> "#F87171"
            pct <= 25 -> "#FBBF24"
            else -> "#34D399"
        }
        val accentColor = Color.parseColor(accentHex)

        // 1. Ícone de Bateria no Topo Esquerdo
        batStrokePaint.color = accentColor
        batFillPaint.color = accentColor
        batHeaderPaint.color = accentColor
        batLinePaint.color = accentColor

        scratchRect.set(26f, 22f, 64f, 46f)
        canvas.drawRoundRect(scratchRect, 5f, 5f, batStrokePaint)
        scratchRect.set(65f, 29f, 70f, 39f)
        canvas.drawRoundRect(scratchRect, 2f, 2f, batFillPaint)
        val fillWidth = (30f * (pct / 100f)).coerceIn(3f, 30f)
        scratchRect.set(30f, 26f, 30f + fillWidth, 42f)
        canvas.drawRoundRect(scratchRect, 2.5f, 2.5f, batFillPaint)

        // 2. Cabeçalho
        val titleText = if (battery.isCharging) "Bateria • ${pct}% ⚡" else "Bateria • ${pct}%"
        canvas.drawText(titleText, 80f, 47f, batHeaderPaint)

        val fittedRate = TextUtils.ellipsize(
            battery.rateLabel,
            batRightSubPaint,
            235f,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(fittedRate, 578f, 45f, batRightSubPaint)

        // Linha divisória sutil abaixo do cabeçalho
        canvas.drawLine(26f, 62f, 578f, 62f, batDividerPaint)

        // 3. Área do Gráfico Progressivo
        val levels = battery.levelsPct.ifEmpty { listOf(pct, pct) }
        val labels = battery.labels.ifEmpty { listOf("Início", "Agora") }
        val count = minOf(levels.size, labels.size).coerceAtLeast(2)

        val chartLeft = 48f
        val chartRight = 556f
        val chartTop = 108f
        val chartBottom = 206f

        canvas.drawLine(chartLeft, chartTop, chartRight, chartTop, batGridPaint)
        canvas.drawLine(chartLeft, (chartTop + chartBottom) * 0.5f, chartRight, (chartTop + chartBottom) * 0.5f, batGridPaint)
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, batGridPaint)

        val minLv = (levels.take(count).minOrNull() ?: 0).coerceIn(0, 100)
        val maxLv = (levels.take(count).maxOrNull() ?: 100).coerceIn(0, 100)
        val floorLv = (minLv - 8).coerceAtLeast(0)
        val ceilLv = (maxLv + 6).coerceAtMost(100).coerceAtLeast(floorLv + 15)
        val rangeLv = (ceilLv - floorLv).coerceAtLeast(1).toFloat()

        val stepX = (chartRight - chartLeft) / (count - 1).coerceAtLeast(1).toFloat()
        val pointsX = FloatArray(count)
        val pointsY = FloatArray(count)

        for (i in 0 until count) {
            pointsX[i] = chartLeft + i * stepX
            val normalized = ((levels[i] - floorLv).toFloat() / rangeLv).coerceIn(0f, 1f)
            pointsY[i] = chartBottom - normalized * (chartBottom - chartTop)
        }

        // Preenchimento em degradê sob a curva
        scratchPath.reset()
        scratchPath.moveTo(pointsX[0], chartBottom)
        for (i in 0 until count) {
            scratchPath.lineTo(pointsX[i], pointsY[i])
        }
        scratchPath.lineTo(pointsX[count - 1], chartBottom)
        scratchPath.close()

        batFillAreaPaint.shader = when {
            battery.isCharging -> batAreaShaderEmerald
            pct <= 15 -> batAreaShaderRed
            pct <= 25 -> batAreaShaderAmber
            else -> batAreaShaderEmerald
        }
        canvas.drawPath(scratchPath, batFillAreaPaint)

        // Linha contínua da curva de consumo
        scratchPath.reset()
        for (i in 0 until count) {
            if (i == 0) scratchPath.moveTo(pointsX[i], pointsY[i])
            else scratchPath.lineTo(pointsX[i], pointsY[i])
        }
        canvas.drawPath(scratchPath, batLinePaint)

        for (i in 0 until count) {
            val isLast = i == count - 1
            canvas.drawCircle(pointsX[i], pointsY[i], if (isLast) 10.5f else 8f, batFillPaint)
            if (!isLast) {
                canvas.drawCircle(pointsX[i], pointsY[i], 3.5f, darkCutFillPaint)
            }
            val valStr = "${levels[i]}%"
            canvas.drawText(valStr, pointsX[i], (pointsY[i] - 15f).coerceAtLeast(92f), batValuePaint)

            batTimeLabelPaint.color = if (isLast) accentColor else Color.parseColor("#CBD5E1")
            canvas.drawText(labels[i], pointsX[i], 248f, batTimeLabelPaint)
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

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        super.onComplicationActivated(complicationInstanceId, type)
        WatchAutonomousRepository.getInstance(applicationContext)
            .markComplicationSlotActivated(WatchAutonomousRepository.SLOT_MAIN, complicationInstanceId)
        AutonomousCountdownScheduler.ensureScreenWakeListenerRegistered(applicationContext)
        AutonomousCountdownScheduler.scheduleNextTransition(applicationContext)
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        super.onComplicationDeactivated(complicationInstanceId)
        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        repo.markComplicationSlotDeactivated(WatchAutonomousRepository.SLOT_MAIN, complicationInstanceId)
        if (!repo.hasAnyActiveComplicationSlot()) {
            AutonomousCountdownScheduler.pauseAllBackgroundSchedules(applicationContext)
        } else {
            AutonomousCountdownScheduler.scheduleNextTransition(applicationContext)
        }
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        return withContext(Dispatchers.Default) {
            val appCtx = applicationContext
            AutonomousCountdownScheduler.ensureScreenWakeListenerRegistered(appCtx)

            val repo = WatchAutonomousRepository.getInstance(appCtx)
            repo.markComplicationSlotActivated(WatchAutonomousRepository.SLOT_MAIN, request.complicationInstanceId)

            val nowMs = System.currentTimeMillis()
            val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
            val progressWindowMinutes = repo.getProgressWindowMinutes()

            AutonomousCountdownScheduler.scheduleNextTransition(appCtx)

            val contentDesc = PlainComplicationText.Builder("ChronoWear").build()
            val cardBitmap = AgendaCardBitmapRenderer.getOrRenderCardBitmap(
                repo = repo,
                upcomingThree = upcomingThree,
                nowMs = nowMs,
                progressWindowMinutes = progressWindowMinutes
            )
            ChronoWearComplicationUpdater.markMainSignatureDelivered(
                AgendaCardBitmapRenderer.getCachedSignature()
            )
            val icon = Icon.createWithBitmap(cardBitmap)
            val tapPendingIntent = buildTapCyclePendingIntent()

            when (request.complicationType) {
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
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val sampleText = PlainComplicationText.Builder("ChronoWear").build()
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
