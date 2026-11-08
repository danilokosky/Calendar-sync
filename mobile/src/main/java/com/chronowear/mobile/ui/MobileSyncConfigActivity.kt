package com.chronowear.mobile.ui

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.DragEvent
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.chronowear.mobile.sync.CalendarSyncEngine
import com.chronowear.mobile.sync.CalendarSyncForegroundService
import com.chronowear.mobile.sync.LocalCalendarEvent
import com.chronowear.mobile.sync.LocalCalendarItem
import com.chronowear.mobile.sync.MobileTaskItem
import com.chronowear.mobile.sync.SyncPreferences
import com.chronowear.mobile.sync.WearConnectionReport
import com.chronowear.mobile.sync.WearConnectionState
import com.chronowear.mobile.sync.WeatherTriggerAppOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class MobileSyncConfigActivity : Activity() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: SyncPreferences

    private val clockHandler = Handler(Looper.getMainLooper())
    private var currentTabIndex = 0
    private var isUpdatingToggleProgrammatically = false
    private var suppressSyncBroadcastUntilMs = 0L

    // Pull-to-Refresh com Flecha Circular Animada
    private lateinit var pullScrollView: PullToRefreshScrollView
    private lateinit var pullHeaderContainer: LinearLayout
    private lateinit var pullSpinnerView: PullRefreshArrowSpinnerView
    private lateinit var pullHeaderText: TextView
    private var isPullRefreshing = false

    // Containers de abas
    private lateinit var tabHomeContainer: LinearLayout
    private lateinit var tabSettingsContainer: FrameLayout
    private lateinit var tabAboutContainer: LinearLayout

    // Barra inferior estilo Samsung Health (compacta com cápsula/círculo interno)
    private lateinit var navTabItemLayouts: List<LinearLayout>
    private lateinit var navCircleBadgeViews: List<View>
    private lateinit var navLabelViews: List<TextView>

    // Topo da Aba Início: Watchface Idêntica ao Relógio + Status de Conexão + Permissões
    private lateinit var virtualWatchView: VirtualWatchCanvasView
    private lateinit var watchPreviewModeLabel: TextView
    private lateinit var connectionDotView: View
    private lateinit var connectionHeadlineText: TextView
    private lateinit var connectionDetailText: TextView
    private lateinit var bluetoothBadgeText: TextView
    private lateinit var watchAppBadgeText: TextView
    private lateinit var permissionsAlertContainer: LinearLayout
    private lateinit var permissionsListContainer: LinearLayout
    private lateinit var widgetsGridContainer: LinearLayout

    // Camadas finas escuras de proteção (quando faltam permissões ou app Wear não está instalado)
    private lateinit var watchPreviewLockOverlay: FrameLayout
    private lateinit var watchPreviewLockText: TextView
    private lateinit var widgetsLockOverlay: FrameLayout
    private lateinit var widgetsLockText: TextView
    private lateinit var settingsLockOverlay: FrameLayout
    private lateinit var settingsLockText: TextView

    // Aba Configurações: Controles e Switches
    private lateinit var switchCalendarEvents: Switch
    private lateinit var switchTasksSync: Switch
    private lateinit var switchAutoFallbackTasks: Switch
    private lateinit var switchNotifyOnCanvasChange: Switch
    private lateinit var switchNotifyAgenda15Min: Switch
    private lateinit var switchUnifiedWeatherSolar: Switch
    private lateinit var switchWatchStandaloneWeather: Switch
    private lateinit var switchSleepSync: Switch
    private lateinit var switchHighFreq15Min: Switch
    private lateinit var switchUpdateOnWake: Switch
    private lateinit var switchDaemonService: Switch

    // Mapas de Chips (atualizados in-place sem removeAllViews para nunca travar toques)
    private val canvasModeChipButtons = mutableMapOf<String, Button>()
    private val primaryContextChipButtons = mutableMapOf<String, Button>()
    private val holdMinutesChipButtons = mutableMapOf<Int, Button>()
    private val horizonChipButtons = mutableMapOf<Int, Button>()
    private val progressWindowChipButtons = mutableMapOf<Int, Button>()
    private val phoneWeatherIntervalChipButtons = mutableMapOf<Int, Button>()
    private val watchWeatherIntervalChipButtons = mutableMapOf<Int, Button>()

    private lateinit var calendarsCheckboxContainer: LinearLayout
    private lateinit var weatherTriggerAppsSummaryText: TextView

    // Dados em memória para desenho imediato na Watchface e nos Tiles
    private var cachedEvents: List<LocalCalendarEvent> = emptyList()
    private var cachedTasks: List<MobileTaskItem> = emptyList()
    private var lastConnectionReport: WearConnectionReport? = null
    private var selectedVirtualPreviewOverride: String? = null
    private var discoveredWeatherTriggerApps: List<WeatherTriggerAppOption> = emptyList()

    private val syncCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (System.currentTimeMillis() < suppressSyncBroadcastUntilMs) {
                virtualWatchView.invalidate()
                refreshWidgetsGridInPlace()
                return
            }
            refreshUiState(checkRemoteConnection = false)
        }
    }

    private val liveClockRunnable = object : Runnable {
        override fun run() {
            if (::virtualWatchView.isInitialized) {
                virtualWatchView.invalidate()
            }
            refreshWidgetsLiveCanvasOnly()
            clockHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SyncPreferences(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = Color.parseColor("#050507")
            window.navigationBarColor = Color.parseColor("#0A0D12")
        }

        setContentView(buildRootLayout())

        val filter = IntentFilter(SyncPreferences.ACTION_SYNC_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncCompletedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(syncCompletedReceiver, filter)
        }

        ensureDefaultCalendarsSelectedIfPermitted()
        refreshUiState(checkRemoteConnection = true)
    }

    override fun onResume() {
        super.onResume()
        clockHandler.removeCallbacks(liveClockRunnable)
        clockHandler.post(liveClockRunnable)
        ensureDefaultCalendarsSelectedIfPermitted()
        refreshUiState(checkRemoteConnection = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshUiState(checkRemoteConnection = true)
        }
    }

    override fun onPause() {
        clockHandler.removeCallbacks(liveClockRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        clockHandler.removeCallbacks(liveClockRunnable)
        try {
            unregisterReceiver(syncCompletedReceiver)
        } catch (_: Exception) {
        }
        uiScope.cancel()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        ensureDefaultCalendarsSelectedIfPermitted()
        refreshUiState(checkRemoteConnection = true)
        if (countMissingPermissions() == 0) {
            triggerBackgroundSync("Permissões liberadas")
        }
    }

    private fun ensureDefaultCalendarsSelectedIfPermitted() {
        if (!CalendarSyncEngine.hasCalendarPermission(this)) return
        if (prefs.getSelectedCalendarIds().isEmpty()) {
            val allIds = CalendarSyncEngine.loadDeviceCalendars(this).map { it.id }.toSet()
            if (allIds.isNotEmpty()) {
                prefs.setSelectedCalendarIds(allIds)
            }
        }
    }

    // =========================================================================================
    // CONSTRUÇÃO DA INTERFACE (INÍCIO, CONFIGURAÇÕES, SOBRE) COM PULL-TO-REFRESH ANIMADO
    // =========================================================================================

    private fun buildRootLayout(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#050507"))
        }

        pullScrollView = PullToRefreshScrollView(this) {
            performPullToRefresh()
        }.apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_ALWAYS
        }

        val pageColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(104))
        }

        // Indicador Puxar para Atualizar com Flecha Circular Animada que gira conforme puxa
        pullHeaderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedRect("#0B111A", "#1E293B", 16)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }

        pullSpinnerView = PullRefreshArrowSpinnerView(this).apply {
            val lp = LinearLayout.LayoutParams(dp(24), dp(24))
            lp.rightMargin = dp(10)
            layoutParams = lp
        }
        pullHeaderContainer.addView(pullSpinnerView)

        pullHeaderText = TextView(this).apply {
            text = "Puxe a página para baixo para verificar conexão e atualizar"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }
        pullHeaderContainer.addView(pullHeaderText)

        pullScrollView.onPullProgressChanged = { pullPx, thresholdPx ->
            val ratio = (pullPx / thresholdPx).coerceIn(0f, 1.25f)
            pullSpinnerView.setPullProgress(ratio)
            if (!isPullRefreshing) {
                when {
                    pullPx >= thresholdPx -> {
                        pullHeaderText.text = "Solte agora para verificar conexão, permissões e sincronizar..."
                        pullHeaderText.setTextColor(Color.parseColor("#F5C438"))
                    }
                    pullPx > dp(6) -> {
                        val pct = ((pullPx * 100f) / thresholdPx).toInt().coerceIn(1, 99)
                        pullHeaderText.text = "Puxando para atualizar ($pct%)..."
                        pullHeaderText.setTextColor(Color.parseColor("#67E8F9"))
                    }
                    else -> {
                        updateIdlePullHeaderMessage()
                    }
                }
            }
        }
        pageColumn.addView(pullHeaderContainer)

        // Cabeçalho essencial (sem botão Atualizar redundante, pois o Puxar para Atualizar já faz isso)
        pageColumn.addView(buildCompactTopBar())

        // Abas
        tabHomeContainer = buildHomeTab()
        tabSettingsContainer = buildSettingsTab()
        tabAboutContainer = buildAboutTab()

        pageColumn.addView(tabHomeContainer)
        pageColumn.addView(tabSettingsContainer)
        pageColumn.addView(tabAboutContainer)

        pullScrollView.addView(pageColumn)
        root.addView(
            pullScrollView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        root.addView(
            buildBottomNavigationBar(),
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(14)
            }
        )

        switchTabAnimated(0, initial = true)
        return root
    }

    private fun buildCompactTopBar(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }

        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(
            TextView(this).apply {
                text = "ChronoWear Companion"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            }
        )
        titleCol.addView(
            TextView(this).apply {
                text = "v1.2.1 • Sincronização Inteligente Wear OS"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }
        )
        row.addView(titleCol)
        return row
    }

    // =========================================================================================
    // ABA 1: INÍCIO (WATCHFACE IDÊNTICA AO RELÓGIO + STATUS + MINI TUTORIAL / PERMISSÕES + TILES)
    // =========================================================================================

    private fun buildHomeTab(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Card Superior: Cópia Idêntica da Watchface do Relógio + Status de Conexão
        val watchCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedRect("#0B0F17", "#1B2434", 24)
            setPadding(dp(14), dp(16), dp(14), dp(16))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            layoutParams = lp
        }

        // Wrapper da Watchface (com camada fina escura quando bloqueado por falta de permissão ou app Wear)
        val watchPreviewFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val watchPreviewInnerCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        watchPreviewModeLabel = TextView(this).apply {
            text = "MOSTRADOR DO RELÓGIO • SINCRONIZADO"
            setTextColor(Color.parseColor("#F5C438"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
        }
        watchPreviewInnerCol.addView(watchPreviewModeLabel)

        virtualWatchView = VirtualWatchCanvasView(this).apply {
            val lp = LinearLayout.LayoutParams(dp(264), dp(264))
            lp.topMargin = dp(10)
            lp.bottomMargin = dp(8)
            layoutParams = lp
            setOnClickListener {
                if (isAppUnlockedForCustomization()) {
                    cycleVirtualPreviewContext()
                }
            }
        }
        watchPreviewInnerCol.addView(virtualWatchView)

        watchPreviewInnerCol.addView(
            TextView(this).apply {
                text = "Cópia idêntica da Watch Face Format (WFF) do relógio • Toque para alternar o Canvas"
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                gravity = Gravity.CENTER
            }
        )

        watchPreviewFrame.addView(watchPreviewInnerCol)

        watchPreviewLockOverlay = FrameLayout(this).apply {
            background = roundedRect("#BF05070B", "#263348", 18)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        watchPreviewLockText = TextView(this).apply {
            text = "🔒 Conclua as permissões e instale o app no relógio abaixo para interagir"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedRect("#E60B111A", "#334155", 14)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
        }
        watchPreviewLockOverlay.addView(watchPreviewLockText)
        watchPreviewFrame.addView(watchPreviewLockOverlay)
        watchCard.addView(watchPreviewFrame)

        // Faixa de Status Preciso (SEMPRE APARENTE E DESBLOQUEADA)
        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect("#101724", "#1E293B", 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(12)
            layoutParams = lp
            setOnClickListener {
                performPullToRefresh()
            }
        }

        val statusTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        connectionDotView = View(this).apply {
            background = ovalDrawable("#F59E0B")
            val lp = LinearLayout.LayoutParams(dp(10), dp(10))
            lp.rightMargin = dp(8)
            layoutParams = lp
        }
        statusTopRow.addView(connectionDotView)

        connectionHeadlineText = TextView(this).apply {
            text = "Verificando conexão com o relógio..."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        statusTopRow.addView(connectionHeadlineText)
        statusBox.addView(statusTopRow)

        connectionDetailText = TextView(this).apply {
            text = "Sincronizando estado do Bluetooth e nó Wear OS..."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(3)
            layoutParams = lp
        }
        statusBox.addView(connectionDetailText)

        val badgesRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        bluetoothBadgeText = TextView(this).apply {
            text = "Bluetooth: --"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = roundedRect("#0B111A", "#263244", 10)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.rightMargin = dp(6)
            layoutParams = lp
            gravity = Gravity.CENTER
        }
        watchAppBadgeText = TextView(this).apply {
            text = "App Wear: --"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = roundedRect("#0B111A", "#263244", 10)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            gravity = Gravity.CENTER
        }
        badgesRow.addView(bluetoothBadgeText)
        badgesRow.addView(watchAppBadgeText)
        statusBox.addView(badgesRow)

        watchCard.addView(statusBox)
        col.addView(watchCard)

        // 2. Quadro Informativo de Permissões Pendentes + Mini Tutorial + Link www.laskosky.com.br (SEMPRE APARENTE)
        permissionsAlertContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect("#19120B", "#F59E0B", 18)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(14)
            layoutParams = lp
        }
        permissionsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        permissionsAlertContainer.addView(permissionsListContainer)
        col.addView(permissionsAlertContainer)

        // 3. Seção dos Blocos de Status (Tiles 2x2) com Camada Fina Escura de Proteção quando bloqueado
        val widgetsProtectedFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val widgetsSectionCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val widgetsHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }
        widgetsHeaderRow.addView(
            TextView(this).apply {
                text = "QUADROS DE STATUS (TILES)"
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                letterSpacing = 0.06f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        widgetsHeaderRow.addView(
            TextView(this).apply {
                text = "◀ ▶ reordena • ⤢/⤡ expande ou reduz"
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            }
        )
        widgetsSectionCol.addView(widgetsHeaderRow)

        widgetsGridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        widgetsSectionCol.addView(widgetsGridContainer)
        widgetsProtectedFrame.addView(widgetsSectionCol)

        widgetsLockOverlay = FrameLayout(this).apply {
            background = roundedRect("#C405070B", "#263348", 20)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        widgetsLockText = TextView(this).apply {
            text = "🔒 Tiles bloqueados: conclua o passo a passo no quadro acima (liberar permissões e instalar o app no relógio)"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedRect("#E60B111A", "#F59E0B", 16)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(dp(16), dp(28), dp(16), dp(16))
            }
        }
        widgetsLockOverlay.addView(widgetsLockText)
        widgetsProtectedFrame.addView(widgetsLockOverlay)

        col.addView(widgetsProtectedFrame)

        return col
    }

    // =========================================================================================
    // ABA 2: CONFIGURAÇÕES (PROTEGIDA POR CAMADA FINA ESCURA ATÉ LIBERAR PERMISSÕES E APP WEAR)
    // =========================================================================================

    private fun buildSettingsTab(): FrameLayout {
        val rootFrame = FrameLayout(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Card 1: Modo do Canvas e Prioridade Automática
        val modeCard = createSectionCard(
            "MODO DO CANVAS NO RELÓGIO",
            "Escolha se o mostrador gira no Automático Inteligente ou fica fixo em um contexto"
        )

        val modeOptions = listOf(
            SyncPreferences.MODE_AUTO to "Automático",
            SyncPreferences.MODE_EVENTS to "Agenda",
            SyncPreferences.MODE_TASKS to "Tarefas",
            SyncPreferences.MODE_WEATHER to "Clima",
            SyncPreferences.MODE_SUN to "Sol",
            SyncPreferences.MODE_SLEEP to "Sono",
            SyncPreferences.MODE_STEPS to "Passos"
        )
        modeCard.addView(
            buildStaticChipGrid(
                options = modeOptions,
                buttonStore = canvasModeChipButtons
            ) { selectedKey ->
                suppressSyncBroadcastUntilMs = System.currentTimeMillis() + 1800L
                prefs.setContextWindowMode(selectedKey)
                selectedVirtualPreviewOverride = if (selectedKey == SyncPreferences.MODE_AUTO) null else selectedKey
                updateAllChipSelectionsInPlace()
                virtualWatchView.invalidate()
                refreshWidgetsGridInPlace()
                triggerBackgroundSync("Modo Canvas: $selectedKey")
            }
        )

        modeCard.addView(
            createSubLabel("Foco Principal quando em Automático:")
        )
        val primaryOptions = listOf(
            SyncPreferences.MODE_EVENTS to "Foco: Agenda",
            SyncPreferences.MODE_TASKS to "Foco: Tarefas",
            SyncPreferences.MODE_WEATHER to "Foco: Clima",
            SyncPreferences.MODE_SUN to "Foco: Sol",
            SyncPreferences.MODE_SLEEP to "Foco: Sono",
            SyncPreferences.MODE_STEPS to "Foco: Passos"
        )
        modeCard.addView(
            buildStaticChipGrid(
                options = primaryOptions,
                buttonStore = primaryContextChipButtons
            ) { selectedPrimary ->
                suppressSyncBroadcastUntilMs = System.currentTimeMillis() + 1800L
                prefs.setAutoPrimaryContext(selectedPrimary)
                selectedVirtualPreviewOverride = null
                updateAllChipSelectionsInPlace()
                virtualWatchView.invalidate()
                refreshWidgetsGridInPlace()
                triggerBackgroundSync("Foco Automático: $selectedPrimary")
            }
        )

        modeCard.addView(
            createSubLabel("Tempo de retenção de alertas temporários antes de voltar ao Foco:")
        )
        val holdOptions = listOf(5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min")
        modeCard.addView(
            buildStaticChipGrid(
                options = holdOptions,
                buttonStore = holdMinutesChipButtons
            ) { mins ->
                suppressSyncBroadcastUntilMs = System.currentTimeMillis() + 1800L
                prefs.setUnifiedCanvasHoldMinutes(mins)
                updateAllChipSelectionsInPlace()
                triggerBackgroundSync("Retenção Canvas: ${mins}m")
            }
        )

        switchAutoFallbackTasks = createResponsiveSettingToggleRow(
            parent = modeCard,
            title = "Priorizar Tarefas se Agenda de Hoje estiver vazia",
            subtitle = "No modo Automático com foco em Agenda, exibe Tarefas ativas do dia quando não houver eventos hoje"
        ) { checked ->
            prefs.setAutoFallbackAgendaToTasksEnabled(checked)
            virtualWatchView.invalidate()
            triggerBackgroundSync("Fallback Agenda->Tarefas")
        }

        col.addView(modeCard)

        // Card 2: Notificações do Relógio e Alerta de 15 Minutos
        val notifCard = createSectionCard(
            "NOTIFICAÇÕES NO RELÓGIO",
            "Controle os avisos vibratórios na troca de Canvas e antes dos compromissos"
        )

        switchNotifyOnCanvasChange = createResponsiveSettingToggleRow(
            parent = notifCard,
            title = "Notificar no relógio ao atualizar o Canvas",
            subtitle = "Gera aviso discreto no Wear OS quando o quadro central mudar de contexto"
        ) { checked ->
            prefs.setNotifyWatchOnCanvasChangeEnabled(checked)
            triggerBackgroundSync("Notificar troca de Canvas")
        }

        switchNotifyAgenda15Min = createResponsiveSettingToggleRow(
            parent = notifCard,
            title = "Notificar 15 min antes do compromisso",
            subtitle = "Troca para a Agenda faltando 15 min e envia notificação opcional no relógio"
        ) { checked ->
            prefs.setNotifyAgenda15MinEnabled(checked)
            triggerBackgroundSync("Alerta 15 min Agenda")
        }

        switchHighFreq15Min = createResponsiveSettingToggleRow(
            parent = notifCard,
            title = "Contagem regressiva de alta precisão (≤ 15 min)",
            subtitle = "Atualiza a barra e o contador minuto a minuto nos 15 min finais"
        ) { checked ->
            prefs.setHighFreq15MinRuleEnabled(checked)
            triggerBackgroundSync("Regra 15 min precisão")
        }

        col.addView(notifCard)

        // Card 3: Sincronização de Agenda e Tarefas
        val agendaTasksCard = createSectionCard(
            "AGENDA & TAREFAS (GOOGLE TASKS / REMINDER)",
            "As tarefas ativas (Hoje ou Sem horário) sincronizam automaticamente junto com a Agenda"
        )

        switchCalendarEvents = createResponsiveSettingToggleRow(
            parent = agendaTasksCard,
            title = "Sincronizar Compromissos da Agenda",
            subtitle = "Lê os calendários selecionados e mantém horizonte offline no relógio"
        ) { checked ->
            prefs.setCalendarEventsSyncEnabled(checked)
            if (checked && !CalendarSyncEngine.hasCalendarPermission(this)) {
                requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 101)
            }
            refreshPermissionsBanner()
            triggerBackgroundSync("Toggle Agenda")
        }

        switchTasksSync = createResponsiveSettingToggleRow(
            parent = agendaTasksCard,
            title = "Sincronizar Quadro de Tarefas",
            subtitle = "Exibe apenas tarefas não concluídas programadas para Hoje ou Sem horário"
        ) { checked ->
            prefs.setTasksSyncEnabled(checked)
            virtualWatchView.invalidate()
            refreshWidgetsGridInPlace()
            triggerBackgroundSync("Toggle Tarefas")
        }

        val manageTasksBtn = createActionButton("＋ Adicionar / Gerenciar Tarefas de Hoje") {
            showManageTasksDialog()
        }
        agendaTasksCard.addView(manageTasksBtn)

        agendaTasksCard.addView(createSubLabel("Horizonte de sincronização de eventos:"))
        val horizonOpts = listOf(1 to "24 horas", 2 to "2 dias", 3 to "3 dias", 7 to "7 dias")
        agendaTasksCard.addView(
            buildStaticChipGrid(
                options = horizonOpts,
                buttonStore = horizonChipButtons
            ) { days ->
                suppressSyncBroadcastUntilMs = System.currentTimeMillis() + 1800L
                prefs.setHorizonDays(days)
                updateAllChipSelectionsInPlace()
                triggerBackgroundSync("Horizonte: ${days}d")
            }
        )

        agendaTasksCard.addView(createSubLabel("Janela da barra de progresso (0% → 100%):"))
        val windowOpts = listOf(30 to "30 min", 60 to "60 min", 120 to "2 horas")
        agendaTasksCard.addView(
            buildStaticChipGrid(
                options = windowOpts,
                buttonStore = progressWindowChipButtons
            ) { mins ->
                suppressSyncBroadcastUntilMs = System.currentTimeMillis() + 1800L
                prefs.setProgressWindowMinutes(mins)
                updateAllChipSelectionsInPlace()
                virtualWatchView.invalidate()
                refreshWidgetsGridInPlace()
                triggerBackgroundSync("Janela Progresso: ${mins}m")
            }
        )

        agendaTasksCard.addView(createSubLabel("Calendários sincronizados no aparelho:"))
        calendarsCheckboxContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        agendaTasksCard.addView(calendarsCheckboxContainer)

        col.addView(agendaTasksCard)

        // Card 4: Localização Unificada (Clima + Nascer/Pôr do Sol)
        val weatherSunCard = createSectionCard(
            "CLIMA & SOL (LOCALIZAÇÃO UNIFICADA)",
            "Uma única atualização fixa de localização alimenta o Clima (com UF oficial) e o Pôr do Sol"
        )

        switchUnifiedWeatherSolar = createResponsiveSettingToggleRow(
            parent = weatherSunCard,
            title = "Atualização Unificada de Clima e Pôr do Sol",
            subtitle = "Persiste a última coordenada real para nunca cair em localização genérica"
        ) { checked ->
            prefs.setPhoneWeatherAutoCheckEnabled(checked)
            if (checked && !CalendarSyncEngine.hasLocationPermission(this)) {
                requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ),
                    103
                )
            }
            refreshPermissionsBanner()
            triggerBackgroundSync("Toggle Clima+Sol Unificado")
        }

        weatherSunCard.addView(createSubLabel("Intervalo de atualização no Smartphone:"))
        val phoneWeatherIntervals = listOf(30 to "30 min", 60 to "1 hora", 120 to "2 horas", 180 to "3 horas")
        weatherSunCard.addView(
            buildStaticChipGrid(
                options = phoneWeatherIntervals,
                buttonStore = phoneWeatherIntervalChipButtons
            ) { mins ->
                suppressSyncBroadcastUntilMs = System.currentTimeMillis() + 1800L
                prefs.setPhoneWeatherCheckIntervalMinutes(mins)
                updateAllChipSelectionsInPlace()
                triggerBackgroundSync("Intervalo Clima Phone: ${mins}m")
            }
        )

        switchWatchStandaloneWeather = createResponsiveSettingToggleRow(
            parent = weatherSunCard,
            title = "Atualização Autônoma no Relógio (Wi-Fi / LTE / GPS)",
            subtitle = "Permite ao relógio buscar Clima e calcular Pôr do Sol mesmo longe do telefone"
        ) { checked ->
            prefs.setWatchStandaloneWeatherEnabled(checked)
            prefs.setWatchStandaloneSolarEnabled(checked)
            triggerBackgroundSync("Toggle Clima/Sol Autônomo Watch")
        }

        weatherSunCard.addView(createSubLabel("Intervalo autônomo no Relógio:"))
        val watchWeatherIntervals = listOf(30 to "30 min", 60 to "1 hora", 120 to "2 horas")
        weatherSunCard.addView(
            buildStaticChipGrid(
                options = watchWeatherIntervals,
                buttonStore = watchWeatherIntervalChipButtons
            ) { mins ->
                suppressSyncBroadcastUntilMs = System.currentTimeMillis() + 1800L
                prefs.setWatchStandaloneWeatherIntervalMinutes(mins)
                updateAllChipSelectionsInPlace()
                triggerBackgroundSync("Intervalo Clima Watch: ${mins}m")
            }
        )

        weatherTriggerAppsSummaryText = TextView(this).apply {
            text = "Apps gatilho de notificação de clima: carregando..."
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        weatherSunCard.addView(weatherTriggerAppsSummaryText)

        weatherSunCard.addView(
            createActionButton("Selecionar Apps Gatilho de Clima") {
                showWeatherTriggerAppsDialog()
            }
        )

        col.addView(weatherSunCard)

        // Card 5: Sono do Dia e Serviço em Segundo Plano
        val sleepSystemCard = createSectionCard(
            "SONO DO DIA & SISTEMA",
            "Sincroniza o sono de hoje automaticamente e valida quando não há dados registrados"
        )

        switchSleepSync = createResponsiveSettingToggleRow(
            parent = sleepSystemCard,
            title = "Sincronizar Sono automaticamente no Relógio",
            subtitle = "Exibe hipnograma de hoje ou informa 'Não há dados de sono para hoje' se vazio"
        ) { checked ->
            prefs.setSleepSyncEnabled(checked)
            virtualWatchView.invalidate()
            refreshWidgetsGridInPlace()
            triggerBackgroundSync("Toggle Sono")
        }

        val toggleSleepTodayStateBtn = createActionButton("Alternar Estado de Dados de Sono de Hoje (Com Dados / Sem Dados)") {
            val currentlyHas = prefs.hasVerifiedSleepDataForToday()
            prefs.setSleepHasDataForToday(!currentlyHas)
            virtualWatchView.invalidate()
            refreshWidgetsGridInPlace()
            triggerBackgroundSync("Verificação Sono de Hoje")
        }
        sleepSystemCard.addView(toggleSleepTodayStateBtn)

        switchUpdateOnWake = createResponsiveSettingToggleRow(
            parent = sleepSystemCard,
            title = "Atualizar mostrador ao ligar a tela do relógio",
            subtitle = "Recalcula timers e expira eventos passados instantaneamente no pulso"
        ) { checked ->
            prefs.setUpdateOnScreenWakeEnabled(checked)
            triggerBackgroundSync("Toggle Update On Wake")
        }

        switchDaemonService = createResponsiveSettingToggleRow(
            parent = sleepSystemCard,
            title = "Serviço Contínuo em Segundo Plano",
            subtitle = "Mantém observador ativo de calendário, tarefas, sono e clima"
        ) { checked ->
            prefs.setBackgroundServiceEnabled(checked)
            if (checked) {
                CalendarSyncForegroundService.startOrUpdate(this, "Ativado nas Configurações")
            } else {
                CalendarSyncForegroundService.stop(this)
            }
        }

        col.addView(sleepSystemCard)
        rootFrame.addView(col)

        // Camada fina escura de proteção sobre todas as configurações quando bloqueado
        settingsLockOverlay = FrameLayout(this).apply {
            background = roundedRect("#C405070B", "#263348", 20)
            isClickable = true
            isFocusable = true
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        settingsLockText = TextView(this).apply {
            text = "🔒 Opções de personalização bloqueadas.\nVolte à aba Início para liberar as permissões pendentes e instalar o app ChronoWear no relógio."
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedRect("#EB0B111A", "#F59E0B", 18)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                setMargins(dp(16), dp(24), dp(16), dp(16))
            }
            setOnClickListener {
                switchTabAnimated(0)
            }
        }
        settingsLockOverlay.addView(settingsLockText)
        rootFrame.addView(settingsLockOverlay)

        return rootFrame
    }

    // =========================================================================================
    // ABA 3: SOBRE (MODELO REPRESENTATIVO E ARQUITETURA)
    // =========================================================================================

    private fun buildAboutTab(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val aboutCard = createSectionCard(
            "SOBRE O CHRONOWEAR v1.2.1",
            "Modelo Representativo e Arquitetura Híbrida Smartphone + Wear OS"
        )

        val blocks = listOf(
            "1. Watch Face Format (WFF) & Complication Nativa" to
                "O mostrador do relógio utiliza um único slot central 302×136dp (SMALL_IMAGE / PHOTO_IMAGE) que alterna dinamicamente entre Agenda, Tarefas, Clima, Sol, Sono e Passos.",
            "2. Progressão Offline Autônoma da Agenda" to
                "O relógio armazena o horizonte de eventos em banco SQLite local e acorda a cada minuto para atualizar o cronômetro regressivo e avançar automaticamente quando um compromisso passa, sem depender de nova conexão com o smartphone até o fim do dia.",
            "3. Filtro Estrito Hoje vs Amanhã & Fallback para Tarefas" to
                "Eventos de outros dias nunca aparecem sob o título 'Hoje'. Quando não há compromissos restantes hoje e o modo Automático está focado na Agenda, o Canvas prioriza automaticamente suas Tarefas ativas do dia.",
            "4. Localização Unificada Clima + Pôr do Sol com UF Oficial" to
                "As coordenadas válidas de GPS/Rede são persistidas localmente para evitar regressão para localizações genéricas em segundo plano, abreviando o estado sempre pela sigla oficial (ex.: MA, PR, SP)."
        )

        for ((heading, body) in blocks) {
            val itemBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedRect("#0B111A", "#1E293B", 14)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(8)
                layoutParams = lp
            }
            itemBox.addView(
                TextView(this).apply {
                    text = heading
                    setTextColor(Color.parseColor("#F5C438"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                }
            )
            itemBox.addView(
                TextView(this).apply {
                    text = body
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = dp(4)
                    layoutParams = lp
                }
            )
            aboutCard.addView(itemBox)
        }

        col.addView(aboutCard)
        return col
    }

    // =========================================================================================
    // VALIDAÇÃO REALISTA DE PERMISSÕES, CONEXÃO E PULL-TO-REFRESH (SEM FALSO POSITIVO)
    // =========================================================================================

    private fun countMissingPermissions(): Int {
        var count = 0
        if (!CalendarSyncEngine.hasCalendarPermission(this)) count++
        if (!CalendarSyncEngine.hasLocationPermission(this)) count++
        if (!CalendarSyncEngine.hasNotificationPermission(this)) count++
        if (!CalendarSyncEngine.isNotificationListenerEnabled(this)) count++
        if (!CalendarSyncEngine.isIgnoringBatteryOptimizations(this)) count++
        return count
    }

    private fun isWatchAppDetected(): Boolean {
        val report = lastConnectionReport ?: return false
        return report.bluetoothEnabled &&
            report.state == WearConnectionState.CONNECTED_WITH_APP_INSTALLED
    }

    private fun isAppUnlockedForCustomization(): Boolean {
        return countMissingPermissions() == 0 && isWatchAppDetected()
    }

    private fun updateIdlePullHeaderMessage() {
        val missingPerms = countMissingPermissions()
        val report = lastConnectionReport
        when {
            missingPerms > 0 -> {
                pullHeaderText.text = "⚠ Pendente: libere as $missingPerms permissões abaixo (Puxe p/ revalidar)"
                pullHeaderText.setTextColor(Color.parseColor("#F59E0B"))
            }
            report != null && !report.bluetoothEnabled -> {
                pullHeaderText.text = "⚠ Bluetooth desligado • Ative o Bluetooth e puxe para atualizar"
                pullHeaderText.setTextColor(Color.parseColor("#FCA5A5"))
            }
            report != null && report.state != WearConnectionState.CONNECTED_WITH_APP_INSTALLED -> {
                pullHeaderText.text = "⚠ App do relógio não detectado • Instale o app e puxe para atualizar"
                pullHeaderText.setTextColor(Color.parseColor("#FCD34D"))
            }
            else -> {
                pullHeaderText.text = "Puxe a página para baixo para verificar conexão e atualizar"
                pullHeaderText.setTextColor(Color.parseColor("#94A3B8"))
            }
        }
    }

    private fun performPullToRefresh() {
        if (isPullRefreshing) return
        isPullRefreshing = true
        pullSpinnerView.startSpinning()
        pullHeaderText.text = "Verificando permissões, Bluetooth e App no Relógio..."
        pullHeaderText.setTextColor(Color.parseColor("#F5C438"))

        uiScope.launch {
            val missingPerms = countMissingPermissions()
            val btOn = CalendarSyncEngine.isBluetoothEnabled(this@MobileSyncConfigActivity)
            val report = if (!btOn) {
                WearConnectionReport(
                    state = WearConnectionState.DISCONNECTED,
                    wearableDisplayNames = emptyList(),
                    installedAppNodeNames = emptyList(),
                    bluetoothEnabled = false
                )
            } else {
                withContext(Dispatchers.IO) {
                    CalendarSyncEngine.checkWearConnectionAndAppStatus(this@MobileSyncConfigActivity)
                }
            }

            applyConnectionReportToUi(report)

            val hhmm = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            when {
                missingPerms > 0 -> {
                    // NUNCA afirma que os dados foram atualizados quando faltam permissões!
                    pullHeaderText.text = "⚠ Dados não atualizados ($hhmm): libere as $missingPerms permissões pendentes abaixo"
                    pullHeaderText.setTextColor(Color.parseColor("#F59E0B"))
                }
                !report.bluetoothEnabled -> {
                    pullHeaderText.text = "⚠ Não sincronizado ($hhmm): Bluetooth desligado no smartphone"
                    pullHeaderText.setTextColor(Color.parseColor("#FCA5A5"))
                }
                report.state != WearConnectionState.CONNECTED_WITH_APP_INSTALLED -> {
                    pullHeaderText.text = "⚠ Não sincronizado ($hhmm): app ChronoWear não instalado no relógio"
                    pullHeaderText.setTextColor(Color.parseColor("#FCD34D"))
                }
                else -> {
                    withContext(Dispatchers.IO) {
                        CalendarSyncEngine.fetchPhoneHourlyWeatherIfNeeded(
                            this@MobileSyncConfigActivity,
                            forceRefresh = true
                        )
                        CalendarSyncEngine.performFullSyncToWatch(
                            this@MobileSyncConfigActivity,
                            "Puxar para Atualizar"
                        )
                    }
                    pullHeaderText.text = "✓ Sincronizado com o relógio às $hhmm • Puxe para atualizar"
                    pullHeaderText.setTextColor(Color.parseColor("#34D399"))
                }
            }

            isPullRefreshing = false
            pullSpinnerView.stopSpinning()
            refreshUiState(checkRemoteConnection = false)
        }
    }

    private fun triggerBackgroundSync(reason: String) {
        if (!isAppUnlockedForCustomization()) return
        uiScope.launch(Dispatchers.IO) {
            CalendarSyncEngine.performFullSyncToWatch(this@MobileSyncConfigActivity, reason)
        }
    }

    private fun refreshUiState(checkRemoteConnection: Boolean) {
        val nowMs = System.currentTimeMillis()
        cachedEvents = if (CalendarSyncEngine.hasCalendarPermission(this) && prefs.isCalendarEventsSyncEnabled()) {
            CalendarSyncEngine.queryHorizonEvents(this)
        } else {
            emptyList()
        }
        cachedTasks = prefs.getActiveUncompletedTasks(nowMs)

        // Atualiza Switches sem disparar listeners recursivos
        isUpdatingToggleProgrammatically = true
        try {
            switchCalendarEvents.isChecked = prefs.isCalendarEventsSyncEnabled()
            switchTasksSync.isChecked = prefs.isTasksSyncEnabled()
            switchAutoFallbackTasks.isChecked = prefs.isAutoFallbackAgendaToTasksEnabled()
            switchNotifyOnCanvasChange.isChecked = prefs.isNotifyWatchOnCanvasChangeEnabled()
            switchNotifyAgenda15Min.isChecked = prefs.isNotifyAgenda15MinEnabled()
            switchUnifiedWeatherSolar.isChecked = prefs.isPhoneWeatherAutoCheckEnabled()
            switchWatchStandaloneWeather.isChecked = prefs.isWatchStandaloneWeatherEnabled()
            switchSleepSync.isChecked = prefs.isSleepSyncEnabled()
            switchHighFreq15Min.isChecked = prefs.isHighFreq15MinRuleEnabled()
            switchUpdateOnWake.isChecked = prefs.isUpdateOnScreenWakeEnabled()
            switchDaemonService.isChecked = prefs.isBackgroundServiceEnabled()
        } finally {
            isUpdatingToggleProgrammatically = false
        }

        updateAllChipSelectionsInPlace()
        refreshPermissionsBanner()
        refreshCalendarsList()
        refreshWeatherTriggerAppsSummary()
        refreshWidgetsGridInPlace()
        virtualWatchView.invalidate()

        val btOn = CalendarSyncEngine.isBluetoothEnabled(this)
        if (!btOn) {
            applyConnectionReportToUi(
                WearConnectionReport(
                    state = WearConnectionState.DISCONNECTED,
                    wearableDisplayNames = emptyList(),
                    installedAppNodeNames = emptyList(),
                    bluetoothEnabled = false
                )
            )
        } else if (checkRemoteConnection) {
            uiScope.launch {
                val report = withContext(Dispatchers.IO) {
                    CalendarSyncEngine.checkWearConnectionAndAppStatus(this@MobileSyncConfigActivity)
                }
                applyConnectionReportToUi(report)
            }
        } else if (lastConnectionReport != null) {
            applyConnectionReportToUi(lastConnectionReport!!.copy(bluetoothEnabled = btOn))
        } else {
            refreshProtectionLockOverlays()
        }
    }

    private fun applyConnectionReportToUi(report: WearConnectionReport) {
        lastConnectionReport = report
        if (!report.bluetoothEnabled) {
            connectionDotView.background = ovalDrawable("#EF4444")
            connectionHeadlineText.text = "Bluetooth Desativado no Smartphone"
            connectionDetailText.text = "Ative o Bluetooth para restabelecer a comunicação com o Galaxy Watch / Wear OS."
            bluetoothBadgeText.text = "Bluetooth: Desligado"
            bluetoothBadgeText.setTextColor(Color.parseColor("#FCA5A5"))
            watchAppBadgeText.text = "App Wear: Inacessível"
            watchAppBadgeText.setTextColor(Color.parseColor("#94A3B8"))
        } else {
            bluetoothBadgeText.text = "Bluetooth: Ativo"
            bluetoothBadgeText.setTextColor(Color.parseColor("#34D399"))

            when (report.state) {
                WearConnectionState.CONNECTED_WITH_APP_INSTALLED -> {
                    val nodeNames = report.installedAppNodeNames.joinToString().ifBlank { "Wear OS" }
                    connectionDotView.background = ovalDrawable("#10B981")
                    connectionHeadlineText.text = "Conectado • $nodeNames"
                    connectionDetailText.text = "Bluetooth ativo e app ChronoWear Wear OS v1.2.1 respondendo em tempo real."
                    watchAppBadgeText.text = "App Wear: Instalado"
                    watchAppBadgeText.setTextColor(Color.parseColor("#34D399"))
                }
                WearConnectionState.WEARABLE_CONNECTED_APP_MISSING -> {
                    val nodeNames = report.wearableDisplayNames.joinToString().ifBlank { "Relógio" }
                    connectionDotView.background = ovalDrawable("#F59E0B")
                    connectionHeadlineText.text = "Relógio Pareado ($nodeNames) • App Wear Ausente"
                    connectionDetailText.text = "O Bluetooth está conectado ao relógio, mas o app ChronoWear não foi detectado no relógio."
                    watchAppBadgeText.text = "App Wear: Não detectado"
                    watchAppBadgeText.setTextColor(Color.parseColor("#FCD34D"))
                }
                WearConnectionState.DISCONNECTED -> {
                    connectionDotView.background = ovalDrawable("#EF4444")
                    connectionHeadlineText.text = "Relógio Desconectado"
                    connectionDetailText.text = "Bluetooth ligado no telefone, mas nenhum relógio Wear OS está conectado no alcance."
                    watchAppBadgeText.text = "App Wear: Offline"
                    watchAppBadgeText.setTextColor(Color.parseColor("#FCA5A5"))
                }
            }
        }

        // Reconstroi o quadro de permissões + aviso de instalação do app no relógio e atualiza as camadas de bloqueio
        refreshPermissionsBanner()
        refreshProtectionLockOverlays()
        if (!isPullRefreshing) {
            updateIdlePullHeaderMessage()
        }
    }

    /**
     * Aplica ou remove a camada fina escura sobre todos os controles e textos de personalização:
     * - Enquanto faltar qualquer permissão obrigatória OU enquanto o app do relógio não for detectado,
     *   mantém a proteção escura sobre os controles, deixando aparente apenas o status de conexão e
     *   o quadro de permissões / instalação do app no relógio.
     */
    private fun refreshProtectionLockOverlays() {
        val missingPerms = countMissingPermissions()
        val watchDetected = isWatchAppDetected()
        val locked = missingPerms > 0 || !watchDetected

        val reasonMessage = when {
            missingPerms > 0 && !watchDetected ->
                "🔒 Libere as $missingPerms permissões pendentes no Passo 1 e instale o app no relógio no Passo 2 para desbloquear as opções"
            missingPerms > 0 ->
                "🔒 Passo 1 pendente: clique nos botões 'Conceder' no tutorial acima ($missingPerms restantes) para liberar o acesso"
            else ->
                "🔒 Passo 2 pendente: app não detectado no relógio. Instale o app ChronoWear no relógio pelo botão acima (www.laskosky.com.br) para desbloquear as opções"
        }

        watchPreviewLockText.text = reasonMessage
        widgetsLockText.text = reasonMessage
        settingsLockText.text = "$reasonMessage\n(Toque aqui para voltar ao Início)"

        val vis = if (locked) View.VISIBLE else View.GONE
        watchPreviewLockOverlay.visibility = vis
        widgetsLockOverlay.visibility = vis
        settingsLockOverlay.visibility = vis
    }

    // =========================================================================================
    // QUADRO DE PERMISSÕES PENDENTES + MINI TUTORIAL + LINK WWW.LASKOSKY.COM.BR
    // =========================================================================================

    private data class MissingPermItem(
        val title: String,
        val reason: String,
        val actionLabel: String,
        val onGrantClick: () -> Unit
    )

    private fun refreshPermissionsBanner() {
        if (!::permissionsListContainer.isInitialized) return
        permissionsListContainer.removeAllViews()
        val missing = mutableListOf<MissingPermItem>()

        if (!CalendarSyncEngine.hasCalendarPermission(this)) {
            missing.add(
                MissingPermItem(
                    title = "Acesso ao Calendário (Agenda)",
                    reason = "Necessário para ler os próximos compromissos de Hoje e Amanhã.",
                    actionLabel = "Conceder",
                    onGrantClick = {
                        requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 101)
                    }
                )
            )
        }

        if (!CalendarSyncEngine.hasLocationPermission(this)) {
            missing.add(
                MissingPermItem(
                    title = "Localização Unificada (Clima & Pôr do Sol)",
                    reason = "Necessário para obter a cidade/UF exata e calcular o pôr do sol local.",
                    actionLabel = "Conceder",
                    onGrantClick = {
                        requestPermissions(
                            arrayOf(
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ),
                            103
                        )
                    }
                )
            )
        }

        if (!CalendarSyncEngine.hasNotificationPermission(this)) {
            missing.add(
                MissingPermItem(
                    title = "Permissão de Notificações",
                    reason = "Necessária para exibir status de sincronização em segundo plano.",
                    actionLabel = "Permitir",
                    onGrantClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
                        }
                    }
                )
            )
        }

        if (!CalendarSyncEngine.isNotificationListenerEnabled(this)) {
            missing.add(
                MissingPermItem(
                    title = "Leitor de Gatilhos de Clima",
                    reason = "Necessário para detectar alertas meteorológicos e lembretes.",
                    actionLabel = "Ativar",
                    onGrantClick = {
                        try {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                )
            )
        }

        if (!CalendarSyncEngine.isIgnoringBatteryOptimizations(this)) {
            missing.add(
                MissingPermItem(
                    title = "Sincronização Sem Restrição de Bateria",
                    reason = "Evita que o sistema congele o envio de agenda e sono ao relógio.",
                    actionLabel = "Liberar",
                    onGrantClick = {
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (_: Exception) {
                        }
                    }
                )
            )
        }

        val watchDetected = isWatchAppDetected()

        if (missing.isEmpty() && watchDetected) {
            permissionsAlertContainer.background = roundedRect("#091914", "#10B981", 16)
            permissionsListContainer.addView(
                TextView(this).apply {
                    text = "✓ Configuração Completa: Todas as permissões foram concedidas e o app ChronoWear está ativo no relógio. Personalização desbloqueada!"
                    setTextColor(Color.parseColor("#34D399"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                }
            )
            return
        }

        permissionsAlertContainer.background = roundedRect("#19120B", "#F59E0B", 18)

        // Cabeçalho do Mini Tutorial de Liberação Obrigatória
        val tutorialBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect("#24190C", "#F5C438", 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        tutorialBox.addView(
            TextView(this).apply {
                text = if (missing.isNotEmpty()) {
                    "🧭 MINI TUTORIAL (PASSO 1 DE 2) • LIBERAÇÃO DE PERMISSÕES (${missing.size} PENDENTES)"
                } else {
                    "🧭 MINI TUTORIAL (PASSO 2 DE 2) • INSTALAÇÃO DO APP NO RELÓGIO"
                }
                setTextColor(Color.parseColor("#F5C438"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            }
        )
        tutorialBox.addView(
            TextView(this).apply {
                text = if (missing.isNotEmpty()) {
                    "As demais funções e personalizações estão protegidas. Clique apenas nos botões amarelos de liberação abaixo e, em seguida, certifique-se de ter o app instalado no relógio para desbloquear o aplicativo."
                } else {
                    "Permissões do smartphone concluídas! Porém o app ChronoWear ainda não foi detectado no relógio. As opções de personalização só serão desbloqueadas quando o app estiver instalado e conectado no relógio."
                }
                setTextColor(Color.parseColor("#F8FAFC"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(4)
                layoutParams = lp
            }
        )
        permissionsListContainer.addView(tutorialBox)

        // Bloco Informativo de Instalação do App no Relógio + Link www.laskosky.com.br (sempre visível dentro do quadro quando o app do relógio não for detectado)
        if (!watchDetected) {
            val installWatchBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedRect("#101926", "#38BDF8", 14)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = if (missing.isNotEmpty()) dp(10) else 0
                layoutParams = lp
            }

            installWatchBox.addView(
                TextView(this).apply {
                    text = "⌚ É NECESSÁRIO INSTALAR O APP CHRONOWEAR NO RELÓGIO"
                    setTextColor(Color.parseColor("#67E8F9"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                }
            )
            installWatchBox.addView(
                TextView(this).apply {
                    text = "Para sincronizar dados reais e desbloquear a personalização dos tiles e mostrador, instale o app complementar no seu Galaxy Watch / Wear OS."
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    lp.topMargin = dp(3)
                    lp.bottomMargin = dp(8)
                    layoutParams = lp
                }
            )

            val linkBtn = Button(this).apply {
                text = "Baixar App do Relógio (www.laskosky.com.br)"
                isAllCaps = false
                setTextColor(Color.parseColor("#050507"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                background = roundedRect("#38BDF8", "#67E8F9", 999)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                minHeight = dp(38)
                minimumHeight = dp(38)
                setOnClickListener {
                    try {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.laskosky.com.br"))
                        )
                    } catch (_: Exception) {
                    }
                }
            }
            installWatchBox.addView(linkBtn)
            permissionsListContainer.addView(installWatchBox)
        }

        // Lista de Permissões Pendentes
        for (item in missing) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundedRect("#120E0A", "#2E2418", 12)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(6)
                layoutParams = lp
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            textCol.addView(
                TextView(this).apply {
                    text = item.title
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                }
            )
            textCol.addView(
                TextView(this).apply {
                    text = item.reason
                    setTextColor(Color.parseColor("#CBD5E1"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                }
            )
            row.addView(textCol)

            val btn = Button(this).apply {
                text = item.actionLabel
                isAllCaps = false
                setTextColor(Color.parseColor("#050507"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                background = roundedRect("#F5C438", "#FDE047", 999)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                minHeight = dp(36)
                minimumHeight = dp(36)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.leftMargin = dp(8)
                layoutParams = lp
                setOnClickListener { item.onGrantClick() }
            }
            row.addView(btn)
            permissionsListContainer.addView(row)
        }
    }

    // =========================================================================================
    // BLOCOS DE TILES 2X2 (BOTÕES ERGONÔMICOS GRANDES E EXPANSÃO/REDUÇÃO CONFIÁVEL)
    // =========================================================================================

    private val activeWidgetCanvasViews = mutableListOf<WidgetBlockCanvasView>()

    private fun refreshWidgetsLiveCanvasOnly() {
        for (v in activeWidgetCanvasViews) {
            v.invalidate()
        }
    }

    private fun refreshWidgetsGridInPlace() {
        if (!::widgetsGridContainer.isInitialized) return
        widgetsGridContainer.removeAllViews()
        activeWidgetCanvasViews.clear()

        val order = prefs.getHomeWidgetsOrder()
        var index = 0
        while (index < order.size) {
            val widgetId = order[index]
            val isExpanded = prefs.isWidgetExpandedFullRow(widgetId)

            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 2f
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(10)
                layoutParams = lp
            }

            if (isExpanded) {
                rowLayout.addView(
                    buildSingleWidgetBlockCard(widgetId, isFullWidth = true)
                )
                index += 1
            } else {
                val nextId = order.getOrNull(index + 1)
                val nextExpanded = nextId != null && prefs.isWidgetExpandedFullRow(nextId)
                if (nextId != null && !nextExpanded) {
                    rowLayout.addView(
                        buildSingleWidgetBlockCard(widgetId, isFullWidth = false, isLeftColumn = true)
                    )
                    rowLayout.addView(
                        buildSingleWidgetBlockCard(nextId, isFullWidth = false, isLeftColumn = false)
                    )
                    index += 2
                } else {
                    rowLayout.addView(
                        buildSingleWidgetBlockCard(widgetId, isFullWidth = false, isLeftColumn = true)
                    )
                    val spacer = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(164), 1f).apply {
                            leftMargin = dp(5)
                        }
                    }
                    rowLayout.addView(spacer)
                    index += 1
                }
            }

            widgetsGridContainer.addView(rowLayout)
        }
    }

    private fun buildSingleWidgetBlockCard(
        widgetId: String,
        isFullWidth: Boolean,
        isLeftColumn: Boolean = true
    ): View {
        val activePreviewMode = resolveEffectivePreviewMode()
        val isHighlighted = activePreviewMode == widgetId

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect(
                bgHex = "#0B0F17",
                borderHex = if (isHighlighted) "#F5C438" else "#1E293B",
                radiusDp = 20,
                strokeWidthDp = if (isHighlighted) 2 else 1
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = LinearLayout.LayoutParams(0, dp(164), if (isFullWidth) 2f else 1f)
            if (!isFullWidth && isLeftColumn) {
                lp.rightMargin = dp(5)
            } else if (!isFullWidth && !isLeftColumn) {
                lp.leftMargin = dp(5)
            }
            layoutParams = lp
            tag = widgetId
        }

        // Cabeçalho do Tile com Botões Ergonômicos Grandes (36x30dp) e Ícones Vetoriais Nítidos
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(4)
            layoutParams = lp
        }

        val titleText = TextView(this).apply {
            text = when (widgetId) {
                SyncPreferences.MODE_EVENTS -> "📅 Agenda"
                SyncPreferences.MODE_TASKS -> "✓ Tarefas"
                SyncPreferences.MODE_WEATHER -> "⛅ Clima"
                SyncPreferences.MODE_SUN -> "☀ Sol"
                SyncPreferences.MODE_SLEEP -> "🌙 Sono"
                SyncPreferences.MODE_STEPS -> "👟 Passos"
                else -> widgetId
            }
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleText)

        if (widgetId == SyncPreferences.MODE_TASKS) {
            headerRow.addView(
                createErgonomicTileIconButton(TileIconType.PLUS, false) {
                    showManageTasksDialog()
                }
            )
        } else if (widgetId == SyncPreferences.MODE_SLEEP) {
            headerRow.addView(
                createErgonomicTileIconButton(TileIconType.SWAP, false) {
                    val hasNow = prefs.hasVerifiedSleepDataForToday()
                    prefs.setSleepHasDataForToday(!hasNow)
                    virtualWatchView.invalidate()
                    refreshWidgetsGridInPlace()
                    triggerBackgroundSync("Alternar Sono Hoje")
                }
            )
        }

        // Botão Mover para trás (◀)
        headerRow.addView(
            createErgonomicTileIconButton(TileIconType.MOVE_LEFT, false) {
                moveWidgetInOrder(widgetId, -1)
            }
        )
        // Botão Mover para frente (▶)
        headerRow.addView(
            createErgonomicTileIconButton(TileIconType.MOVE_RIGHT, false) {
                moveWidgetInOrder(widgetId, +1)
            }
        )
        // Botão Expandir (⤢) / Reduzir (⤡) Linha Inteira — alterna de forma determinística
        headerRow.addView(
            createErgonomicTileIconButton(
                if (isFullWidth) TileIconType.COLLAPSE else TileIconType.EXPAND,
                isFullWidth
            ) {
                prefs.setWidgetExpandedFullRow(widgetId, !isFullWidth)
                refreshWidgetsGridInPlace()
            }
        )

        card.addView(headerRow)

        // Área desenhada do Bloco
        val blockCanvas = WidgetBlockCanvasView(this, widgetId).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setOnClickListener {
                selectedVirtualPreviewOverride = widgetId
                if (prefs.getContextWindowMode() == SyncPreferences.MODE_AUTO) {
                    prefs.setAutoPrimaryContext(widgetId)
                } else {
                    prefs.setContextWindowMode(widgetId)
                }
                updateAllChipSelectionsInPlace()
                virtualWatchView.invalidate()
                refreshWidgetsGridInPlace()
                triggerBackgroundSync("Foco Imediato no Relógio: $widgetId")
            }
            setOnLongClickListener {
                val clip = ClipData.newPlainText("widget_id", widgetId)
                val shadow = View.DragShadowBuilder(card)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    card.startDragAndDrop(clip, shadow, widgetId, 0)
                } else {
                    @Suppress("DEPRECATION")
                    card.startDrag(clip, shadow, widgetId, 0)
                }
                true
            }
        }
        activeWidgetCanvasViews.add(blockCanvas)
        card.addView(blockCanvas)

        card.setOnDragListener { v, event ->
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_ENTERED -> {
                    v.alpha = 0.72f
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    v.alpha = 1.0f
                    true
                }
                DragEvent.ACTION_DROP -> {
                    v.alpha = 1.0f
                    val draggedId = event.localState as? String
                    val targetId = v.tag as? String
                    if (draggedId != null && targetId != null && draggedId != targetId) {
                        swapWidgetsInOrder(draggedId, targetId)
                    }
                    true
                }
                else -> true
            }
        }

        return card
    }

    private enum class TileIconType {
        MOVE_LEFT,
        MOVE_RIGHT,
        EXPAND,
        COLLAPSE,
        PLUS,
        SWAP
    }

    private fun createErgonomicTileIconButton(
        iconType: TileIconType,
        isActiveHighlight: Boolean,
        onClick: () -> Unit
    ): View {
        return TileHeaderIconButtonView(this, iconType, isActiveHighlight).apply {
            isClickable = true
            isFocusable = true
            val lp = LinearLayout.LayoutParams(dp(36), dp(30))
            lp.leftMargin = dp(5)
            layoutParams = lp
            setOnClickListener {
                onClick()
            }
        }
    }

    /**
     * Botão ergonômico de 36x30dp com ícones vetoriais grandes e de alto contraste desenhados em Canvas,
     * garantindo perfeita visibilidade e área de toque generosa tanto para aumentar (Expandir) quanto para diminuir (Reduzir).
     */
    private inner class TileHeaderIconButtonView(
        context: Context,
        private val iconType: TileIconType,
        private val isActiveHighlight: Boolean
    ) : View(context) {
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            rect.set(dp(1).toFloat(), dp(1).toFloat(), w - dp(1), h - dp(1))

            bgPaint.style = Paint.Style.FILL
            bgPaint.color = if (isActiveHighlight) {
                Color.parseColor("#282110")
            } else {
                Color.parseColor("#162032")
            }
            canvas.drawRoundRect(rect, dp(9).toFloat(), dp(9).toFloat(), bgPaint)

            borderPaint.style = Paint.Style.STROKE
            borderPaint.strokeWidth = dp(1.3f).toFloat()
            borderPaint.color = if (isActiveHighlight) {
                Color.parseColor("#F5C438")
            } else {
                Color.parseColor("#334766")
            }
            canvas.drawRoundRect(rect, dp(9).toFloat(), dp(9).toFloat(), borderPaint)

            val cx = w / 2f
            val cy = h / 2f

            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = dp(2.3f).toFloat()
            iconPaint.strokeCap = Paint.Cap.ROUND
            iconPaint.strokeJoin = Paint.Join.ROUND
            iconPaint.color = when (iconType) {
                TileIconType.EXPAND -> Color.parseColor("#F5C438")
                TileIconType.COLLAPSE -> Color.parseColor("#67E8F9")
                TileIconType.PLUS -> Color.parseColor("#34D399")
                TileIconType.SWAP -> Color.parseColor("#C4B5FD")
                else -> Color.parseColor("#F8FAFC")
            }

            when (iconType) {
                TileIconType.MOVE_LEFT -> {
                    val s = dp(5.5f).toFloat()
                    path.reset()
                    path.moveTo(cx + s * 0.55f, cy - s)
                    path.lineTo(cx - s * 0.65f, cy)
                    path.lineTo(cx + s * 0.55f, cy + s)
                    canvas.drawPath(path, iconPaint)
                }
                TileIconType.MOVE_RIGHT -> {
                    val s = dp(5.5f).toFloat()
                    path.reset()
                    path.moveTo(cx - s * 0.55f, cy - s)
                    path.lineTo(cx + s * 0.65f, cy)
                    path.lineTo(cx - s * 0.55f, cy + s)
                    canvas.drawPath(path, iconPaint)
                }
                TileIconType.EXPAND -> {
                    // Duas setas grandes apontando para fora (⤢) para aumentar o tile
                    val d = dp(6.2f).toFloat()
                    val a = dp(4.2f).toFloat()
                    // Linha diagonal de canto inferior-esquerdo ao superior-direito
                    canvas.drawLine(cx - d, cy + d, cx + d, cy - d, iconPaint)
                    // Ponta superior-direita
                    canvas.drawLine(cx + d - a, cy - d, cx + d, cy - d, iconPaint)
                    canvas.drawLine(cx + d, cy - d + a, cx + d, cy - d, iconPaint)
                    // Ponta inferior-esquerda
                    canvas.drawLine(cx - d + a, cy + d, cx - d, cy + d, iconPaint)
                    canvas.drawLine(cx - d, cy + d - a, cx - d, cy + d, iconPaint)
                }
                TileIconType.COLLAPSE -> {
                    // Duas setas grandes apontando para o centro (⤡) para diminuir o tile de volta para 1 coluna
                    val d = dp(6.8f).toFloat()
                    val inner = dp(1.8f).toFloat()
                    val a = dp(4.0f).toFloat()
                    // Seta vinda do topo-direito apontando para o centro
                    canvas.drawLine(cx + d, cy - d, cx + inner, cy - inner, iconPaint)
                    canvas.drawLine(cx + inner, cy - inner - a, cx + inner, cy - inner, iconPaint)
                    canvas.drawLine(cx + inner + a, cy - inner, cx + inner, cy - inner, iconPaint)
                    // Seta vinda de baixo-esquerdo apontando para o centro
                    canvas.drawLine(cx - d, cy + d, cx - inner, cy + inner, iconPaint)
                    canvas.drawLine(cx - inner, cy + inner + a, cx - inner, cy + inner, iconPaint)
                    canvas.drawLine(cx - inner - a, cy + inner, cx - inner, cy + inner, iconPaint)
                }
                TileIconType.PLUS -> {
                    val s = dp(5.5f).toFloat()
                    canvas.drawLine(cx - s, cy, cx + s, cy, iconPaint)
                    canvas.drawLine(cx, cy - s, cx, cy + s, iconPaint)
                }
                TileIconType.SWAP -> {
                    val s = dp(5.5f).toFloat()
                    val y1 = cy - dp(2.8f)
                    val y2 = cy + dp(2.8f)
                    canvas.drawLine(cx - s, y1, cx + s, y1, iconPaint)
                    canvas.drawLine(cx + s - dp(3), y1 - dp(2.5f), cx + s, y1, iconPaint)
                    canvas.drawLine(cx + s, y2, cx - s, y2, iconPaint)
                    canvas.drawLine(cx - s + dp(3), y2 + dp(2.5f), cx - s, y2, iconPaint)
                }
            }
        }
    }

    private fun moveWidgetInOrder(widgetId: String, delta: Int) {
        val current = prefs.getHomeWidgetsOrder().toMutableList()
        val idx = current.indexOf(widgetId)
        if (idx < 0) return
        val newIdx = (idx + delta).coerceIn(0, current.size - 1)
        if (newIdx == idx) return
        current.removeAt(idx)
        current.add(newIdx, widgetId)
        prefs.setHomeWidgetsOrder(current)
        refreshWidgetsGridInPlace()
    }

    private fun swapWidgetsInOrder(draggedId: String, targetId: String) {
        val current = prefs.getHomeWidgetsOrder().toMutableList()
        val fromIdx = current.indexOf(draggedId)
        val toIdx = current.indexOf(targetId)
        if (fromIdx < 0 || toIdx < 0) return
        current.removeAt(fromIdx)
        current.add(toIdx, draggedId)
        prefs.setHomeWidgetsOrder(current)
        refreshWidgetsGridInPlace()
    }

    // =========================================================================================
    // RESOLUÇÃO DO CONTEXTO ATUAL PARA A WATCHFACE VIRTUAL
    // =========================================================================================

    private fun resolveEffectivePreviewMode(): String {
        val override = selectedVirtualPreviewOverride
        if (override != null && override != SyncPreferences.MODE_AUTO) {
            return override
        }
        val configuredMode = prefs.getContextWindowMode()
        if (configuredMode != SyncPreferences.MODE_AUTO) {
            return configuredMode
        }
        val primary = prefs.getAutoPrimaryContext()
        if (primary == SyncPreferences.MODE_EVENTS && prefs.isAutoFallbackAgendaToTasksEnabled()) {
            val nowMs = System.currentTimeMillis()
            val todayEvents = cachedEvents.filter {
                it.startEpochMs > nowMs - 15 * 60_000L &&
                    CalendarSyncEngine.isSameCalendarDay(nowMs, it.startEpochMs)
            }
            if (todayEvents.isEmpty() && prefs.isTasksSyncEnabled() && cachedTasks.isNotEmpty()) {
                return SyncPreferences.MODE_TASKS
            }
        }
        return primary
    }

    private fun cycleVirtualPreviewContext() {
        val cycleOrder = listOf(
            SyncPreferences.MODE_EVENTS,
            SyncPreferences.MODE_TASKS,
            SyncPreferences.MODE_WEATHER,
            SyncPreferences.MODE_SUN,
            SyncPreferences.MODE_SLEEP,
            SyncPreferences.MODE_STEPS
        )
        val current = resolveEffectivePreviewMode()
        val nextIdx = (cycleOrder.indexOf(current) + 1) % cycleOrder.size
        val nextMode = cycleOrder[nextIdx]
        selectedVirtualPreviewOverride = nextMode
        if (prefs.getContextWindowMode() == SyncPreferences.MODE_AUTO) {
            prefs.setAutoPrimaryContext(nextMode)
        } else {
            prefs.setContextWindowMode(nextMode)
        }
        updateAllChipSelectionsInPlace()
        virtualWatchView.invalidate()
        refreshWidgetsGridInPlace()
        triggerBackgroundSync("Alternar Foco Watchface: $nextMode")
    }

    // =========================================================================================
    // VIEW CUSTOMIZADA 0: FLECHA CIRCULAR QUE GIRA CONFORME PUXA A PÁGINA (PULL-TO-REFRESH)
    // =========================================================================================

    private inner class PullRefreshArrowSpinnerView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrowPath = Path()
        private val rect = RectF()
        private var pullProgress = 0f
        private var spinAngle = 0f
        private var spinAnimator: ValueAnimator? = null

        fun setPullProgress(progress: Float) {
            pullProgress = progress.coerceIn(0f, 1.3f)
            invalidate()
        }

        fun startSpinning() {
            if (spinAnimator?.isRunning == true) return
            spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 700L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    spinAngle = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopSpinning() {
            spinAnimator?.cancel()
            spinAnimator = null
            spinAngle = 0f
            pullProgress = 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) / 2f - dp(3)

            // Trilho circular de fundo
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.2f).toFloat()
            paint.color = Color.parseColor("#1E293B")
            canvas.drawCircle(cx, cy, r, paint)

            val isSpinning = spinAnimator?.isRunning == true
            val effectiveProgress = if (isSpinning) 0.82f else pullProgress.coerceIn(0.15f, 1f)
            val sweep = 60f + effectiveProgress * 235f
            val startDeg = if (isSpinning) {
                spinAngle - 90f
            } else {
                -90f + pullProgress * 310f
            }

            rect.set(cx - r, cy - r, cx + r, cy + r)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = if (pullProgress >= 1f || isSpinning) {
                Color.parseColor("#F5C438")
            } else {
                Color.parseColor("#67E8F9")
            }
            canvas.drawArc(rect, startDeg, sweep, false, paint)

            // Ponta da flecha circular na extremidade do arco
            val endRad = Math.toRadians((startDeg + sweep).toDouble())
            val tipX = (cx + r * cos(endRad)).toFloat()
            val tipY = (cy + r * sin(endRad)).toFloat()

            canvas.save()
            canvas.translate(tipX, tipY)
            canvas.rotate(startDeg + sweep + 95f)
            paint.style = Paint.Style.FILL
            arrowPath.reset()
            val aw = dp(4.2f).toFloat()
            arrowPath.moveTo(0f, -aw)
            arrowPath.lineTo(aw * 0.95f, aw * 0.8f)
            arrowPath.lineTo(-aw * 0.95f, aw * 0.8f)
            arrowPath.close()
            canvas.drawPath(arrowPath, paint)
            canvas.restore()
        }
    }

    // =========================================================================================
    // VIEW CUSTOMIZADA 1: CÓPIA IDÊNTICA DA WATCHFACE DO RELÓGIO (450x450 COORDINATE SYSTEM)
    // =========================================================================================

    private inner class VirtualWatchCanvasView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = minOf(width, height).toFloat()
            if (size <= 0f) return

            // Escala exata de 450x450 do watchface.xml para o tamanho da View
            val scale = size / 450f
            canvas.save()
            canvas.translate((width - size) / 2f, (height - size) / 2f)
            canvas.scale(scale, scale)

            // 1. Mostrador circular AMOLED preto + borda externa (#18202C)
            paint.style = Paint.Style.FILL
            paint.color = Color.BLACK
            canvas.drawCircle(225f, 225f, 221f, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 8f
            paint.color = Color.parseColor("#18202C")
            canvas.drawCircle(225f, 225f, 221f, paint)

            // 2. SLOT 7: Arco Lateral Esquerdo Bicolor (idêntico ao watchface.xml + bolinha laranja única)
            rect.set(18f, 18f, 432f, 432f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 18f
            paint.strokeCap = Paint.Cap.ROUND
            // Metade inferior esquerda (160°..180° em coordenadas Android Canvas, onde 180° é 9h)
            paint.color = Color.parseColor("#2E3B38")
            canvas.drawArc(rect, 160f, 20f, false, paint)
            // Metade superior esquerda (180°..200°)
            paint.color = Color.parseColor("#453D20")
            canvas.drawArc(rect, 180f, 20f, false, paint)

            // Bolinha laranja única no centro esquerdo (x=18, y=225)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#F97316")
            canvas.drawCircle(18f, 225f, 8f, paint)

            // Ícone superior esquerdo inclinado (+28°) e contador inferior esquerdo (-28°)
            canvas.save()
            canvas.translate(37f, 118f)
            canvas.rotate(28f)
            textPaint.color = Color.parseColor("#F5C438")
            textPaint.textSize = 18f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            canvas.drawText("⏳", 0f, 6f, textPaint)
            canvas.restore()

            canvas.save()
            canvas.translate(35f, 316f)
            canvas.rotate(-28f)
            textPaint.color = Color.parseColor("#88D4DC")
            textPaint.textSize = 17f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText("0", 0f, 6f, textPaint)
            canvas.restore()

            // 3. SLOT 8: Arco Lateral Direito (trilho #25302B + progresso verde #86EFAC)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 18f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.parseColor("#25302B")
            canvas.drawArc(rect, -20f, 40f, false, paint)
            paint.color = Color.parseColor("#86EFAC")
            canvas.drawArc(rect, -20f, 26f, false, paint)

            // Ícone superior direito inclinado (-28°) e contador inferior direito (+28°)
            canvas.save()
            canvas.translate(413f, 118f)
            canvas.rotate(-28f)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#F5C438")
            canvas.drawCircle(0f, 0f, 11f, paint)
            textPaint.color = Color.BLACK
            textPaint.textSize = 14f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            canvas.drawText("✓", 0f, 5f, textPaint)
            canvas.restore()

            canvas.save()
            canvas.translate(415f, 316f)
            canvas.rotate(28f)
            textPaint.color = Color.parseColor("#E2E8F0")
            textPaint.textSize = 17f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText("0", 0f, 6f, textPaint)
            canvas.restore()

            // 4. TOPO SAMSUNG (Dia da Semana Dourado #F5C438 + Dia do Mês Branco + Relógio 70sp HH:mm:ss)
            val cal = Calendar.getInstance()
            val weekdayNames = arrayOf("DOM.", "SEG.", "TER.", "QUA.", "QUI.", "SEX.", "SÁB.")
            val wd = weekdayNames[(cal.get(Calendar.DAY_OF_WEEK) - 1).coerceIn(0, 6)]
            val dayNum = String.format(Locale.getDefault(), "%02d", cal.get(Calendar.DAY_OF_MONTH))
            val hhmmss = String.format(
                Locale.getDefault(),
                "%02d:%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND)
            )

            textPaint.textSize = 24f
            textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = Color.parseColor("#F5C438")
            canvas.drawText(wd, 222f, 36f, textPaint)

            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.WHITE
            canvas.drawText(dayNum, 232f, 36f, textPaint)

            textPaint.textSize = 62f
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.color = Color.WHITE
            canvas.drawText(hhmmss, 225f, 94f, textPaint)

            // 5. JANELA DE CONTEXTO CENTRAL (x=74, y=110, w=302, h=136) — Cópia idêntica do Canvas 604x272 @2x
            val previewMode = resolveEffectivePreviewMode()
            val cfgMode = prefs.getContextWindowMode()
            val modeNamePt = when (previewMode) {
                SyncPreferences.MODE_EVENTS -> "Agenda"
                SyncPreferences.MODE_TASKS -> "Tarefas"
                SyncPreferences.MODE_WEATHER -> "Clima"
                SyncPreferences.MODE_SUN -> "Sol"
                SyncPreferences.MODE_SLEEP -> "Sono"
                SyncPreferences.MODE_STEPS -> "Passos"
                else -> "Agenda"
            }
            watchPreviewModeLabel.text = if (cfgMode == SyncPreferences.MODE_AUTO) {
                "MODO AUTOMÁTICO • EXIBINDO AGORA: ${modeNamePt.uppercase(Locale.getDefault())}"
            } else {
                "MODO FIXO CONFIGURADO: ${modeNamePt.uppercase(Locale.getDefault())}"
            }

            rect.set(74f, 110f, 376f, 246f)
            drawContextCanvasInsideRect(canvas, rect, previewMode)

            // 6. CARTUCHO INFERIOR DUPLO DE COMPLICATIONS (x=84, y=268, w=282, h=92 — Slots 4 e 5 do watchface.xml)
            rect.set(84f, 268f, 366f, 360f)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#161D1C")
            canvas.drawRoundRect(rect, 46f, 46f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.color = Color.parseColor("#283533")
            canvas.drawRoundRect(rect, 46f, 46f, paint)

            // Slot 4: Cápsula Interna Esquerda (x=92, y=275, w=127, h=78) -> Passos
            rect.set(92f, 275f, 219f, 353f)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#263230")
            canvas.drawRoundRect(rect, 39f, 39f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f
            paint.color = Color.parseColor("#455955")
            canvas.drawRoundRect(rect, 39f, 39f, paint)

            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            textPaint.textSize = 15f
            textPaint.color = Color.parseColor("#88D4DC")
            canvas.drawText("PASSOS", 155.5f, 304f, textPaint)
            textPaint.textSize = 21f
            textPaint.color = Color.WHITE
            canvas.drawText("8.435", 155.5f, 334f, textPaint)

            // Slot 5: Cápsula Interna Direita (x=231, y=275, w=127, h=78) -> Pôr do Sol
            rect.set(231f, 275f, 358f, 353f)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#263230")
            canvas.drawRoundRect(rect, 39f, 39f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.8f
            paint.color = Color.parseColor("#455955")
            canvas.drawRoundRect(rect, 39f, 39f, paint)

            textPaint.textSize = 15f
            textPaint.color = Color.parseColor("#F5C438")
            canvas.drawText("PÔR DO SOL", 294.5f, 304f, textPaint)
            textPaint.textSize = 21f
            textPaint.color = Color.WHITE
            canvas.drawText(prefs.getSolarSunsetHHMM(), 294.5f, 334f, textPaint)

            // 7. BARRA INFERIOR DE BATERIA ESTILO TUBO DE ENSAIO (watchface.xml linhas 138-246)
            rect.set(18f, 18f, 432f, 432f)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 22f
            paint.color = Color.parseColor("#22261A")
            canvas.drawArc(rect, 74f, 32f, false, paint)

            paint.strokeWidth = 17f
            paint.color = Color.parseColor("#3F4430")
            canvas.drawArc(rect, 74f, 32f, false, paint)

            // 9 cortes radiais no tubo de fundo
            paint.strokeWidth = 3.2f
            paint.color = Color.parseColor("#14170F")
            val cuts = arrayOf(
                floatArrayOf(182f, 415f, 177f, 439f),
                floatArrayOf(192f, 417f, 189f, 441f),
                floatArrayOf(203f, 419f, 201f, 443f),
                floatArrayOf(214f, 420f, 213f, 444f),
                floatArrayOf(225f, 420f, 225f, 444f),
                floatArrayOf(236f, 420f, 237f, 444f),
                floatArrayOf(247f, 419f, 249f, 443f),
                floatArrayOf(258f, 417f, 261f, 441f),
                floatArrayOf(268f, 415f, 273f, 439f)
            )
            for (c in cuts) {
                canvas.drawLine(c[0], c[1], c[2], c[3], paint)
            }

            // Líquido translúcido ciano à frente
            paint.strokeWidth = 11.5f
            paint.color = Color.parseColor("#CC88D4DC")
            canvas.drawArc(rect, 84f, 22f, false, paint)

            // Ícone de bateria dourado inclinado à esquerda (+26°)
            canvas.save()
            canvas.translate(130f, 408f)
            canvas.rotate(26f)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#F5C438")
            canvas.drawRoundRect(RectF(-4f, -14f, 4f, -10f), 1.5f, 1.5f, paint)
            canvas.drawRoundRect(RectF(-8f, -10f, 8f, 12f), 3.5f, 3.5f, paint)
            canvas.restore()

            // Percentual 62% inclinado à direita (-28°)
            canvas.save()
            canvas.translate(324f, 406f)
            canvas.rotate(-28f)
            textPaint.color = Color.parseColor("#88D4DC")
            textPaint.textSize = 21f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText("62%", 0f, 6f, textPaint)
            canvas.restore()

            canvas.restore()
        }
    }

    // =========================================================================================
    // VIEW CUSTOMIZADA 2: DESENHO DE CADA TILE (WIDGET 2X2 OU LINHA INTEIRA)
    // =========================================================================================

    private inner class WidgetBlockCanvasView(
        context: Context,
        private val mode: String
    ) : View(context) {
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            rect.set(0f, dp(2).toFloat(), width.toFloat(), height.toFloat())
            drawContextCanvasInsideRect(canvas, rect, mode)
        }
    }

    /**
     * Renderiza em escala vetorial o cartão 604x272 (@2x de 302x136) IDÊNTICO ao NextEventComplicationService.kt
     * tanto dentro da Watchface Virtual quanto dentro dos Tiles!
     */
    private fun drawContextCanvasInsideRect(
        canvas: Canvas,
        bounds: RectF,
        mode: String
    ) {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        val cardW = 604f
        val cardH = 272f

        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(bounds.width() / cardW, bounds.height() / cardH)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()
        val nowMs = System.currentTimeMillis()
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

        // Base escura arredondada (604x272, raio 38)
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#080A0E")
        canvas.drawRoundRect(RectF(0f, 0f, cardW, cardH), 38f, 38f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        paint.color = Color.parseColor("#1B2430")
        canvas.drawRoundRect(RectF(1.2f, 1.2f, cardW - 1.2f, cardH - 1.2f), 38f, 38f, paint)

        when (mode) {
            SyncPreferences.MODE_EVENTS -> {
                val (dayHeader, dayEvents) = CalendarSyncEngine.resolveDayHeaderAndEvents(cachedEvents, nowMs)
                val ev0 = dayEvents.getOrNull(0)
                val ev1 = dayEvents.getOrNull(1)
                val ev2 = dayEvents.getOrNull(2)

                // Ícone de Calendário + Título Hoje/Amanhã
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#F5C438")
                canvas.drawRoundRect(RectF(24f, 18f, 58f, 52f), 8f, 8f, paint)

                textPaint.color = Color.parseColor("#F5C438")
                textPaint.textSize = 40f
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(dayHeader, 72f, 49f, textPaint)

                val countdownLabel = if (ev0 != null) {
                    val diffMin = TimeUnit.MILLISECONDS.toMinutes((ev0.startEpochMs - nowMs).coerceAtLeast(0L))
                    val h = diffMin / 60
                    val m = diffMin % 60
                    if (h > 0) "em ${h}h ${m}m" else "em ${m}m"
                } else {
                    "Livre"
                }
                textPaint.color = Color.parseColor("#67E8F9")
                textPaint.textSize = 32f
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(countdownLabel, cardW - 24f, 49f, textPaint)

                // Linha 1 + Barra de progresso
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.textSize = 36f
                if (ev0 != null) {
                    textPaint.color = Color.WHITE
                    canvas.drawText(sdf.format(Date(ev0.startEpochMs)), 24f, 102f, textPaint)
                    textPaint.color = Color.parseColor("#F5C438")
                    val ellipsized = TextUtils.ellipsize(
                        ev0.title,
                        textPaint,
                        420f,
                        TextUtils.TruncateAt.END
                    ).toString()
                    canvas.drawText(ellipsized, 134f, 102f, textPaint)
                } else {
                    textPaint.color = Color.parseColor("#94A3B8")
                    canvas.drawText("Não há compromissos", 24f, 102f, textPaint)
                }

                // Barra 0% -> 100%
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#252E3A")
                canvas.drawRoundRect(RectF(24f, 114f, cardW - 24f, 126f), 6f, 6f, paint)

                val winMs = TimeUnit.MINUTES.toMillis(prefs.getProgressWindowMinutes().coerceAtLeast(1).toLong())
                val frac = if (ev0 == null) 0f else {
                    val rem = ev0.startEpochMs - nowMs
                    when {
                        rem <= 0L -> 1f
                        rem >= winMs -> 0.12f
                        else -> ((winMs - rem).toFloat() / winMs.toFloat()).coerceIn(0.12f, 1f)
                    }
                }
                if (frac > 0f) {
                    paint.color = Color.parseColor("#F5C438")
                    canvas.drawRoundRect(
                        RectF(24f, 114f, 24f + (cardW - 48f) * frac, 126f),
                        6f,
                        6f,
                        paint
                    )
                }

                // Linhas 2 e 3
                drawCardSecondaryEventRow(canvas, textPaint, 182f, ev1, sdf)
                drawCardSecondaryEventRow(canvas, textPaint, 238f, ev2, sdf)
            }

            SyncPreferences.MODE_TASKS -> {
                // Regra estrita de 3 níveis:
                // 1) Hoje (com hora HH:mm) -> 2) Amanhã (com hora HH:mm) -> 3) Sem data + Atrasadas em vermelho (ambas sem data/hora)
                val resolvedTasks = prefs.resolveTasksBucketForCanvas(nowMs)
                val activeTasks = resolvedTasks.displayTasks
                val showTime = resolvedTasks.showTime

                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#34D399")
                canvas.drawRoundRect(RectF(24f, 18f, 62f, 56f), 10f, 10f, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4.5f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = Color.parseColor("#080A0E")
                path.reset()
                path.moveTo(33f, 38f)
                path.lineTo(41f, 46f)
                path.lineTo(54f, 29f)
                canvas.drawPath(path, paint)

                textPaint.color = Color.parseColor("#34D399")
                textPaint.textSize = 36f
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(resolvedTasks.headerTitle, 76f, 50f, textPaint)

                textPaint.color = Color.parseColor("#67E8F9")
                textPaint.textSize = 28f
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(resolvedTasks.rightBadgeText, cardW - 24f, 48f, textPaint)

                val rowYs = listOf(108f, 174f, 240f)
                for (i in rowYs.indices) {
                    val baseY = rowYs[i]
                    val task = activeTasks.getOrNull(i)
                    val cy = baseY - 13f
                    paint.style = Paint.Style.STROKE
                    if (task != null) {
                        val isOverdueRed = task.isOverdue || task.scheduleCategory.equals("OVERDUE", ignoreCase = true)
                        paint.strokeWidth = 4.2f
                        paint.color = if (isOverdueRed) Color.parseColor("#EF4444") else Color.parseColor("#F5C438")
                        canvas.drawCircle(38f, cy, 12.5f, paint)

                        if (showTime) {
                            textPaint.textAlign = Paint.Align.LEFT
                            textPaint.color = Color.WHITE
                            textPaint.textSize = 33f
                            val hhmmRegex = Regex("^\\d{1,2}:\\d{2}$")
                            val rawTime = task.dueHHMM.trim()
                            val due = if (hhmmRegex.matches(rawTime)) rawTime.take(5) else "--:--"
                            canvas.drawText(due, 64f, baseY, textPaint)

                            textPaint.color = if (isOverdueRed) Color.parseColor("#F87171") else Color.parseColor("#F8FAFC")
                            textPaint.textSize = 35f
                            val ellipsized = TextUtils.ellipsize(
                                task.title,
                                textPaint,
                                295f,
                                TextUtils.TruncateAt.END
                            ).toString()
                            canvas.drawText(ellipsized, 174f, baseY, textPaint)
                        } else {
                            // Nível 3 (Sem data ou Em atraso): NÃO exibe horário nem data, apenas o check de não feito + título (em vermelho se atrasada)
                            textPaint.textAlign = Paint.Align.LEFT
                            textPaint.color = if (isOverdueRed) Color.parseColor("#F87171") else Color.parseColor("#F8FAFC")
                            textPaint.textSize = 35f
                            val ellipsized = TextUtils.ellipsize(
                                task.title,
                                textPaint,
                                405f,
                                TextUtils.TruncateAt.END
                            ).toString()
                            canvas.drawText(ellipsized, 68f, baseY, textPaint)
                        }

                        textPaint.textAlign = Paint.Align.RIGHT
                        textPaint.textSize = 26f
                        textPaint.color = if (isOverdueRed) Color.parseColor("#EF4444") else Color.parseColor("#67E8F9")
                        canvas.drawText(task.sourceLabel.take(8), cardW - 24f, baseY - 2f, textPaint)
                    } else {
                        paint.strokeWidth = 3.5f
                        paint.color = Color.parseColor("#475569")
                        canvas.drawCircle(38f, cy, 10f, paint)

                        textPaint.textAlign = Paint.Align.LEFT
                        textPaint.color = Color.parseColor("#64748B")
                        textPaint.textSize = 33f
                        val emptyMsg = if (i == 0 && activeTasks.isEmpty()) "Sem tarefas pendentes" else "Livre"
                        canvas.drawText(emptyMsg, 68f, baseY, textPaint)
                    }
                }
            }

            SyncPreferences.MODE_WEATHER -> {
                val temps = prefs.getPhoneWeatherTempsCsv().split(",").mapNotNull { it.trim().toIntOrNull() }
                    .ifEmpty { listOf(28, 30, 29, 26, 25) }
                val hours = prefs.getPhoneWeatherHoursCsv().split(",").map { it.trim() }
                    .ifEmpty { listOf("Agora", "13h", "15h", "17h", "20h") }
                val cond = prefs.getPhoneWeatherConditionLabel()
                val loc = prefs.getPhoneWeatherLocationName()
                val rain = prefs.getPhoneWeatherRainProbPct()
                val updated = prefs.getPhoneWeatherUpdatedHHMM()

                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.parseColor("#F5C438")
                textPaint.textSize = 40f
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("Clima", 24f, 48f, textPaint)

                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = Color.parseColor("#67E8F9")
                textPaint.textSize = 30f
                canvas.drawText("$cond • 💧$rain%", cardW - 24f, 46f, textPaint)

                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.parseColor("#E2E8F0")
                textPaint.textSize = 27f
                canvas.drawText("📍 $loc • Atualizado $updated", 24f, 86f, textPaint)

                val chartTop = 128f
                val chartBottom = 216f
                val leftX = 48f
                val rightX = cardW - 48f
                val minT = (temps.minOrNull() ?: 20) - 1
                val maxT = (temps.maxOrNull() ?: 32) + 1
                val span = (maxT - minT).coerceAtLeast(1)
                val stepX = (rightX - leftX) / (temps.size - 1).coerceAtLeast(1)

                path.reset()
                for (i in temps.indices) {
                    val x = leftX + i * stepX
                    val norm = (temps[i] - minT).toFloat() / span.toFloat()
                    val y = chartBottom - norm * (chartBottom - chartTop)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 6f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.parseColor("#67E8F9")
                canvas.drawPath(path, paint)

                for (i in temps.indices) {
                    val x = leftX + i * stepX
                    val norm = (temps[i] - minT).toFloat() / span.toFloat()
                    val y = chartBottom - norm * (chartBottom - chartTop)
                    paint.style = Paint.Style.FILL
                    paint.color = if (i == 0) Color.parseColor("#F5C438") else Color.parseColor("#67E8F9")
                    canvas.drawCircle(x, y, 7.5f, paint)

                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.color = Color.WHITE
                    textPaint.textSize = 27f
                    canvas.drawText("${temps[i]}°", x, y - 12f, textPaint)

                    textPaint.color = Color.parseColor("#CBD5E1")
                    textPaint.textSize = 24f
                    canvas.drawText(hours.getOrNull(i) ?: "", x, 254f, textPaint)
                }
            }

            SyncPreferences.MODE_SUN -> {
                // CÓPIA IDÊNTICA AO RELÓGIO (NextEventComplicationService.renderSunriseSunsetCanvas)
                // Inclui curva abaixo da linha do horizonte representando o período noturno sem sol!
                val riseStr = prefs.getSolarSunriseHHMM()
                val setStr = prefs.getSolarSunsetHHMM()

                // 1. Ícone Verde-Lima Nascer do Sol + Horário
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#65A30D")
                canvas.drawCircle(52f, 42f, 21f, paint)
                paint.color = Color.parseColor("#BEF264")
                canvas.drawCircle(52f, 42f, 14f, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.parseColor("#84CC16")
                path.reset()
                path.moveTo(24f, 56f)
                path.lineTo(42f, 56f)
                path.quadTo(52f, 44f, 62f, 56f)
                path.lineTo(80f, 56f)
                canvas.drawPath(path, paint)

                textPaint.color = Color.WHITE
                textPaint.textSize = 44f
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(riseStr, 94f, 56f, textPaint)

                // 2. Horário Pôr do Sol + Ícone Azul Suave à Direita
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(setStr, cardW - 94f, 56f, textPaint)

                val iconRightCx = cardW - 52f
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#963B82F6")
                canvas.drawCircle(iconRightCx, 42f, 21f, paint)
                paint.color = Color.parseColor("#93C5FD")
                canvas.drawCircle(iconRightCx, 42f, 14f, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 5f
                paint.color = Color.parseColor("#93C5FD")
                path.reset()
                path.moveTo(iconRightCx - 28f, 54f)
                path.lineTo(iconRightCx - 10f, 54f)
                path.quadTo(iconRightCx, 66f, iconRightCx + 10f, 54f)
                path.lineTo(iconRightCx + 28f, 54f)
                canvas.drawPath(path, paint)

                // 3. Geometria do Gráfico Solar (Dia acima de horizonY = 160f, Noite abaixo de horizonY = 160f até 238f)
                val graphLeft = 26f
                val graphRight = cardW - 26f // 578f
                val sunriseX = 164f
                val noonX = cardW / 2f // 302f
                val sunsetX = cardW - 164f // 440f
                val horizonY = 160f
                val graphTop = 74f
                val graphBottom = 242f

                val skyGradPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
                canvas.drawRect(graphLeft, graphTop, graphRight, horizonY, skyGradPaint)

                // 4. Três linhas verticais pontilhadas (Nascer, Meio-Dia, Pôr do Sol)
                val dottedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                    strokeCap = Paint.Cap.ROUND
                    color = Color.parseColor("#64748B")
                    pathEffect = DashPathEffect(floatArrayOf(3f, 11f), 0f)
                }
                for (vx in listOf(sunriseX, noonX, sunsetX)) {
                    path.reset()
                    path.moveTo(vx, 74f)
                    path.lineTo(vx, 200f)
                    canvas.drawPath(path, dottedPaint)
                }

                // 5. Linha Horizontal Azul-Celeste do Horizonte (horizonY = 160f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 6.5f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.parseColor("#8AB4F8")
                canvas.drawLine(graphLeft, horizonY, graphRight, horizonY, paint)

                // 6. Símbolos inferiores sob as 3 linhas pontilhadas: ↑, ☼ e ↓
                paint.strokeWidth = 4.5f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = Color.parseColor("#94A3B8")
                // Seta ↑ em sunriseX
                canvas.drawLine(sunriseX, 240f, sunriseX, 212f, paint)
                canvas.drawLine(sunriseX - 10f, 222f, sunriseX, 212f, paint)
                canvas.drawLine(sunriseX + 10f, 222f, sunriseX, 212f, paint)
                // Sol ☼ em noonX
                val sunCy = 226f
                canvas.drawCircle(noonX, sunCy, 6.5f, paint)
                for (deg in 0 until 360 step 45) {
                    val rad = Math.toRadians(deg.toDouble())
                    canvas.drawLine(
                        noonX + (11.5f * cos(rad)).toFloat(),
                        sunCy + (11.5f * sin(rad)).toFloat(),
                        noonX + (15.5f * cos(rad)).toFloat(),
                        sunCy + (15.5f * sin(rad)).toFloat(),
                        paint
                    )
                }
                // Seta ↓ em sunsetX
                canvas.drawLine(sunsetX, 212f, sunsetX, 240f, paint)
                canvas.drawLine(sunsetX - 10f, 230f, sunsetX, 240f, paint)
                canvas.drawLine(sunsetX + 10f, 230f, sunsetX, 240f, paint)

                // 7. Curva Cosseno Completa (Dia acima de 160f + Noite abaixo de 160f até 236f!)
                val halfDaySpan = sunsetX - noonX // 138f
                val amplitude = 76f
                path.reset()
                val steps = 96
                for (i in 0..steps) {
                    val x = graphLeft + (graphRight - graphLeft) * (i.toFloat() / steps.toFloat())
                    val angle = (Math.PI / 2.0) * ((x - noonX) / halfDaySpan)
                    val y = (horizonY - amplitude * cos(angle).toFloat()).coerceIn(80f, graphBottom)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 7f
                paint.strokeCap = Paint.Cap.ROUND
                paint.color = Color.WHITE
                canvas.drawPath(path, paint)

                // 8. Posição do Sol ao longo das 24h (inclusive abaixo do horizonte à noite)
                val calNow = Calendar.getInstance()
                val curMins = (calNow.get(Calendar.HOUR_OF_DAY) * 60 + calNow.get(Calendar.MINUTE)).toFloat()
                val riseMins = parseHHMMToMinutes(riseStr, 327).toFloat().coerceAtLeast(1f)
                val setMins = parseHHMMToMinutes(setStr, 1060).toFloat().coerceAtLeast(riseMins + 60f)

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

                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#66FDE047")
                canvas.drawCircle(sunX, sunY, 20f, paint)
                paint.color = Color.parseColor("#CCFACC15")
                canvas.drawCircle(sunX, sunY, 13f, paint)
                paint.color = Color.parseColor("#FEF9C3")
                canvas.drawCircle(sunX, sunY, 8.5f, paint)
            }

            SyncPreferences.MODE_SLEEP -> {
                val hasSleepToday = prefs.hasVerifiedSleepDataForToday(nowMs)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#A78BFA")
                canvas.drawCircle(44f, 36f, 17f, paint)
                paint.color = Color.parseColor("#080A0E")
                canvas.drawCircle(52f, 31f, 14f, paint)

                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.parseColor("#C4B5FD")
                textPaint.textSize = 40f
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("Sono", 76f, 50f, textPaint)

                if (!hasSleepToday) {
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.color = Color.parseColor("#94A3B8")
                    textPaint.textSize = 30f
                    canvas.drawText("Hoje", cardW - 24f, 50f, textPaint)

                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.color = Color.parseColor("#F8FAFC")
                    textPaint.textSize = 34f
                    canvas.drawText("Não há dados de sono para hoje", 24f, 112f, textPaint)

                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#1E293B")
                    canvas.drawRoundRect(RectF(24f, 136f, cardW - 24f, 168f), 10f, 10f, paint)

                    textPaint.color = Color.parseColor("#94A3B8")
                    textPaint.textSize = 26f
                    canvas.drawText("Verificado no Samsung Health / Health Connect", 24f, 218f, textPaint)
                } else {
                    val durMin = prefs.getSleepDurationMinutes()
                    val h = durMin / 60
                    val m = durMin % 60
                    val score = prefs.getSleepScore()

                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.color = Color.WHITE
                    textPaint.textSize = 34f
                    canvas.drawText("${h}h ${m}m • $score pts", cardW - 24f, 50f, textPaint)

                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.color = Color.parseColor("#CBD5E1")
                    textPaint.textSize = 28f
                    canvas.drawText(
                        "${prefs.getSleepBedtimeHHMM()} → ${prefs.getSleepWakeHHMM()} • Excelente",
                        24f,
                        92f,
                        textPaint
                    )

                    val stages = prefs.getSleepStagesCsv().split(",").mapNotNull { it.trim().toIntOrNull() }
                        .take(4).let { if (it.size == 4) it else listOf(18, 95, 244, 105) }
                    val total = stages.sum().coerceAtLeast(1).toFloat()
                    val colors = listOf("#F87171", "#38BDF8", "#818CF8", "#4F46E5")
                    val names = listOf("Acord.", "REM", "Leve", "Prof.")

                    val barLeft = 24f
                    val totalW = cardW - 48f
                    var curX = barLeft
                    for (i in stages.indices) {
                        val segW = (stages[i] / total) * totalW
                        paint.style = Paint.Style.FILL
                        paint.color = Color.parseColor(colors[i])
                        canvas.drawRoundRect(
                            RectF(curX, 114f, (curX + segW - 4f).coerceAtLeast(curX + 6f), 152f),
                            10f,
                            10f,
                            paint
                        )
                        curX += segW
                    }

                    val colW = totalW / 4f
                    textPaint.textAlign = Paint.Align.CENTER
                    for (i in stages.indices) {
                        val cx = barLeft + i * colW + colW / 2f
                        val sh = stages[i] / 60
                        val sm = stages[i] % 60
                        val dTxt = if (sh > 0) "${sh}h${String.format("%02d", sm)}" else "${sm}m"
                        textPaint.color = Color.parseColor("#94A3B8")
                        textPaint.textSize = 25f
                        canvas.drawText(names[i], cx, 198f, textPaint)
                        textPaint.color = Color.WHITE
                        textPaint.textSize = 30f
                        canvas.drawText(dTxt, cx, 238f, textPaint)
                    }
                }
            }

            SyncPreferences.MODE_STEPS -> {
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.parseColor("#F5C438")
                textPaint.textSize = 40f
                textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                canvas.drawText("Passos", 24f, 48f, textPaint)

                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = Color.parseColor("#67E8F9")
                textPaint.textSize = 30f
                canvas.drawText("8.435 hoje • 50.4k sem.", cardW - 24f, 48f, textPaint)

                val bars = listOf(0.64f, 0.82f, 0.73f, 0.91f, 0.84f, 0.58f, 0.51f)
                val labels = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")
                val slotW = (cardW - 48f) / bars.size
                val chartTop = 96f
                val chartBottom = 216f

                for (i in bars.indices) {
                    val cx = 24f + i * slotW + slotW / 2f
                    val h = bars[i] * (chartBottom - chartTop)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#1C2632")
                    canvas.drawRoundRect(RectF(cx - 20f, chartTop, cx + 20f, chartBottom), 10f, 10f, paint)
                    paint.color = if (i == 4) Color.parseColor("#F5C438") else Color.parseColor("#22C55E")
                    canvas.drawRoundRect(RectF(cx - 20f, chartBottom - h, cx + 20f, chartBottom), 10f, 10f, paint)

                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.textSize = 24f
                    textPaint.color = if (i == 4) Color.parseColor("#F5C438") else Color.parseColor("#CBD5E1")
                    canvas.drawText(labels[i], cx, 250f, textPaint)
                }
            }
        }

        canvas.restore()
    }

    private fun parseHHMMToMinutes(hhmm: String, fallback: Int): Int {
        val parts = hhmm.trim().split(":")
        if (parts.size != 2) return fallback
        val h = parts[0].toIntOrNull() ?: return fallback
        val m = parts[1].toIntOrNull() ?: return fallback
        return h * 60 + m
    }

    private fun drawCardSecondaryEventRow(
        canvas: Canvas,
        textPaint: TextPaint,
        y: Float,
        ev: LocalCalendarEvent?,
        sdf: SimpleDateFormat
    ) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = 34f
        if (ev != null) {
            textPaint.color = Color.WHITE
            canvas.drawText(sdf.format(Date(ev.startEpochMs)), 24f, y, textPaint)
            textPaint.color = Color.parseColor("#F5C438")
            val ellipsized = TextUtils.ellipsize(
                ev.title,
                textPaint,
                430f,
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(ellipsized, 134f, y, textPaint)
        } else {
            textPaint.color = Color.parseColor("#64748B")
            canvas.drawText("--:--", 24f, y, textPaint)
            canvas.drawText("Livre", 134f, y, textPaint)
        }
    }

    // =========================================================================================
    // COMPONENTES DE CONTROLE NATIVOS (SWITCHES E CHIPS QUE NUNCA TRAVAM)
    // =========================================================================================

    private fun createResponsiveSettingToggleRow(
        parent: LinearLayout,
        title: String,
        subtitle: String,
        onToggle: (Boolean) -> Unit
    ): Switch {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRect("#0B111A", "#1E293B", 14)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(
            TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            }
        )
        textCol.addView(
            TextView(this).apply {
                text = subtitle
                setTextColor(Color.parseColor("#94A3B8"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = dp(2)
                layoutParams = lp
            }
        )
        row.addView(textCol)

        val sw = Switch(this).apply {
            showText = false
            isClickable = true
            isFocusable = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val states = arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                )
                thumbTintList = ColorStateList(
                    states,
                    intArrayOf(Color.parseColor("#F5C438"), Color.parseColor("#CBD5E1"))
                )
                trackTintList = ColorStateList(
                    states,
                    intArrayOf(Color.parseColor("#66F5C438"), Color.parseColor("#334155"))
                )
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = dp(10)
            layoutParams = lp
            setOnCheckedChangeListener { _, isChecked ->
                if (isUpdatingToggleProgrammatically) return@setOnCheckedChangeListener
                suppressSyncBroadcastUntilMs = System.currentTimeMillis() + 1800L
                onToggle(isChecked)
            }
        }
        row.addView(sw)

        row.setOnClickListener {
            sw.toggle()
        }

        parent.addView(row)
        return sw
    }

    private fun <T> buildStaticChipGrid(
        options: List<Pair<T, String>>,
        buttonStore: MutableMap<T, Button>,
        onSelect: (T) -> Unit
    ): LinearLayout {
        buttonStore.clear()
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(6)
            layoutParams = lp
        }

        val perRow = 3
        var currentRow: LinearLayout? = null
        for ((idx, pair) in options.withIndex()) {
            if (idx % perRow == 0) {
                currentRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    if (idx > 0) lp.topMargin = dp(6)
                    layoutParams = lp
                }
                wrapper.addView(currentRow)
            }

            val (key, label) = pair
            val btn = Button(this).apply {
                text = label
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                minHeight = dp(36)
                minimumHeight = dp(36)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (idx % perRow != 0) lp.leftMargin = dp(6)
                layoutParams = lp
                setOnClickListener {
                    onSelect(key)
                }
            }
            buttonStore[key] = btn
            currentRow?.addView(btn)
        }
        return wrapper
    }

    private fun updateAllChipSelectionsInPlace() {
        val currentMode = prefs.getContextWindowMode()
        for ((k, btn) in canvasModeChipButtons) {
            styleChipButton(btn, k == currentMode)
        }
        val currentPrimary = prefs.getAutoPrimaryContext()
        for ((k, btn) in primaryContextChipButtons) {
            styleChipButton(btn, k == currentPrimary)
        }
        val currentHold = prefs.getUnifiedCanvasHoldMinutes()
        for ((k, btn) in holdMinutesChipButtons) {
            styleChipButton(btn, k == currentHold)
        }
        val currentHorizon = prefs.getHorizonDays()
        for ((k, btn) in horizonChipButtons) {
            styleChipButton(btn, k == currentHorizon)
        }
        val currentProgWin = prefs.getProgressWindowMinutes()
        for ((k, btn) in progressWindowChipButtons) {
            styleChipButton(btn, k == currentProgWin)
        }
        val currentPhoneW = prefs.getPhoneWeatherCheckIntervalMinutes()
        for ((k, btn) in phoneWeatherIntervalChipButtons) {
            styleChipButton(btn, k == currentPhoneW)
        }
        val currentWatchW = prefs.getWatchStandaloneWeatherIntervalMinutes()
        for ((k, btn) in watchWeatherIntervalChipButtons) {
            styleChipButton(btn, k == currentWatchW)
        }
    }

    private fun styleChipButton(btn: Button, selected: Boolean) {
        if (selected) {
            btn.setTextColor(Color.parseColor("#050507"))
            btn.background = roundedRect("#F5C438", "#FDE047", 12)
        } else {
            btn.setTextColor(Color.parseColor("#CBD5E1"))
            btn.background = roundedRect("#0B111A", "#263348", 12)
        }
    }

    // =========================================================================================
    // DIÁLOGOS DE GERENCIAMENTO DE TAREFAS E APPS DE CLIMA
    // =========================================================================================

    private fun showManageTasksDialog() {
        val nowMs = System.currentTimeMillis()
        val allUncompleted = prefs.getAllUncompletedTasks(nowMs)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(8))
        }

        container.addView(
            TextView(this).apply {
                text = "Hierarquia do Canvas:\n1º Hoje (c/ hora) → 2º Amanhã (c/ hora) → 3º Sem data + Atrasadas em vermelho (sem data/hora)."
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            }
        )

        for (task in allUncompleted) {
            val catTag = when (task.scheduleCategory.uppercase(Locale.getDefault())) {
                "TODAY" -> "Hoje • ${task.dueHHMM}"
                "TOMORROW" -> "Amanhã • ${task.dueHHMM}"
                "OVERDUE" -> "ATRASADA (Sem data/hora)"
                else -> "Sem data"
            }
            val cb = CheckBox(this).apply {
                text = "${task.title} [$catTag]"
                setTextColor(
                    if (task.isOverdue || task.scheduleCategory == "OVERDUE") {
                        Color.parseColor("#F87171")
                    } else {
                        Color.WHITE
                    }
                )
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        prefs.markTaskCompletedAndRemoveFromCanvas(task.id)
                        cachedTasks = prefs.getActiveUncompletedTasks(System.currentTimeMillis())
                        virtualWatchView.invalidate()
                        refreshWidgetsGridInPlace()
                        triggerBackgroundSync("Tarefa concluída")
                    }
                }
            }
            container.addView(cb)
        }

        var selectedCategory = "TODAY"
        val categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }
        val catButtons = mutableMapOf<String, Button>()
        val cats = listOf(
            "TODAY" to "Hoje",
            "TOMORROW" to "Amanhã",
            "UNDATED" to "Sem data",
            "OVERDUE" to "Atrasada"
        )
        for ((idx, pair) in cats.withIndex()) {
            val (catKey, catLabel) = pair
            val btn = Button(this).apply {
                text = catLabel
                isAllCaps = false
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                val lp = LinearLayout.LayoutParams(0, dp(34), 1f)
                if (idx > 0) lp.leftMargin = dp(4)
                layoutParams = lp
                setOnClickListener {
                    selectedCategory = catKey
                    for ((k, b) in catButtons) {
                        styleChipButton(b, k == selectedCategory)
                    }
                }
            }
            catButtons[catKey] = btn
            categoryRow.addView(btn)
        }
        for ((k, b) in catButtons) {
            styleChipButton(b, k == selectedCategory)
        }
        container.addView(categoryRow)

        val inputTitle = EditText(this).apply {
            hint = "Nova tarefa (ex.: Enviar relatório)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val inputTime = EditText(this).apply {
            hint = "Horário HH:mm (apenas para Hoje ou Amanhã, ex.: 16:30)"
            inputType = InputType.TYPE_CLASS_DATETIME
        }
        container.addView(inputTitle)
        container.addView(inputTime)

        AlertDialog.Builder(this)
            .setTitle("Gerenciar Tarefas (Hoje / Amanhã / Sem Data / Atraso)")
            .setView(container)
            .setPositiveButton("Adicionar Tarefa") { _, _ ->
                val title = inputTitle.text?.toString()?.trim().orEmpty()
                val rawTime = inputTime.text?.toString()?.trim().orEmpty()
                if (title.isNotBlank()) {
                    val hasTime = selectedCategory == "TODAY" || selectedCategory == "TOMORROW"
                    val timeLabel = if (hasTime) rawTime.ifBlank { "14:00" } else ""
                    val updated = prefs.getAllUncompletedTasks(System.currentTimeMillis()).toMutableList()
                    updated.add(
                        0,
                        MobileTaskItem(
                            id = "t_${System.currentTimeMillis()}",
                            title = title,
                            dueHHMM = timeLabel,
                            sourceLabel = "Tasks",
                            completed = false,
                            isTodayOrTimeless = true,
                            scheduleCategory = selectedCategory,
                            isOverdue = selectedCategory == "OVERDUE"
                        )
                    )
                    prefs.saveMobileTaskItems(updated)
                    cachedTasks = prefs.getActiveUncompletedTasks(System.currentTimeMillis())
                    virtualWatchView.invalidate()
                    refreshWidgetsGridInPlace()
                    triggerBackgroundSync("Nova tarefa adicionada")
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun refreshCalendarsList() {
        if (!::calendarsCheckboxContainer.isInitialized) return
        calendarsCheckboxContainer.removeAllViews()
        if (!CalendarSyncEngine.hasCalendarPermission(this)) {
            calendarsCheckboxContainer.addView(
                TextView(this).apply {
                    text = "Conceda a permissão de Calendário para listar suas contas."
                    setTextColor(Color.parseColor("#F59E0B"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                }
            )
            return
        }

        val calendars: List<LocalCalendarItem> = CalendarSyncEngine.loadDeviceCalendars(this)
        val selected = prefs.getSelectedCalendarIds().toMutableSet()
        for (cal in calendars) {
            val cb = CheckBox(this).apply {
                text = "${cal.displayName} (${cal.accountName}) • ${cal.upcomingEventsCount}"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                isChecked = selected.contains(cal.id)
                setOnCheckedChangeListener { _, isChecked ->
                    suppressSyncBroadcastUntilMs = System.currentTimeMillis() + 1800L
                    val currentSet = prefs.getSelectedCalendarIds().toMutableSet()
                    if (isChecked) currentSet.add(cal.id) else currentSet.remove(cal.id)
                    prefs.setSelectedCalendarIds(currentSet)
                    cachedEvents = CalendarSyncEngine.queryHorizonEvents(this@MobileSyncConfigActivity)
                    virtualWatchView.invalidate()
                    refreshWidgetsGridInPlace()
                    triggerBackgroundSync("Seleção de Calendário")
                }
            }
            calendarsCheckboxContainer.addView(cb)
        }
    }

    private fun refreshWeatherTriggerAppsSummary() {
        if (!::weatherTriggerAppsSummaryText.isInitialized) return
        val selectedCount = prefs.getSelectedWeatherTriggerPackages().size
        weatherTriggerAppsSummaryText.text = "$selectedCount apps monitorados para gatilho de notificação de clima."
    }

    private fun showWeatherTriggerAppsDialog() {
        uiScope.launch {
            if (discoveredWeatherTriggerApps.isEmpty()) {
                discoveredWeatherTriggerApps = withContext(Dispatchers.IO) {
                    CalendarSyncEngine.discoverInstalledWeatherAppsByCategory(this@MobileSyncConfigActivity)
                }
            }
            val selected = prefs.getSelectedWeatherTriggerPackages().toMutableSet()
            val labels = discoveredWeatherTriggerApps.map {
                val status = if (it.isInstalledOnDevice) "Instalado" else "Catálogo"
                "${it.displayName} ($status)"
            }.toTypedArray()
            val checked = discoveredWeatherTriggerApps.map {
                selected.contains(it.packageName.lowercase())
            }.toBooleanArray()

            AlertDialog.Builder(this@MobileSyncConfigActivity)
                .setTitle("Apps Gatilho de Clima")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val pkg = discoveredWeatherTriggerApps[which].packageName.lowercase()
                    if (isChecked) selected.add(pkg) else selected.remove(pkg)
                }
                .setPositiveButton("Salvar") { _, _ ->
                    prefs.setSelectedWeatherTriggerPackages(selected)
                    refreshWeatherTriggerAppsSummary()
                    triggerBackgroundSync("Apps Gatilho Clima")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    // =========================================================================================
    // NAVEGAÇÃO INFERIOR ESTILO SAMSUNG HEALTH (COMPACTA COM CÍRCULO/CÁPSULA DENTRO)
    // =========================================================================================

    private fun buildBottomNavigationBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedRect("#1B221E", "#313E36", 999)
            setPadding(dp(6), dp(5), dp(6), dp(5))
        }

        val tabs = listOf(
            Triple("Início", "●", 0),
            Triple("Configurações", "⚙", 1),
            Triple("Sobre", "i", 2)
        )
        val itemLayouts = mutableListOf<LinearLayout>()
        val circleBadges = mutableListOf<View>()
        val labels = mutableListOf<TextView>()

        for ((title, symbol, idx) in tabs) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isClickable = true
                isFocusable = true
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (idx > 0) lp.leftMargin = dp(4)
                layoutParams = lp
                setOnClickListener {
                    switchTabAnimated(idx)
                }
            }

            // Círculo interno dentro da cápsula (estilo Samsung Health)
            val circleBadge = TextView(this).apply {
                text = symbol
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                gravity = Gravity.CENTER
                val lp = LinearLayout.LayoutParams(dp(20), dp(20))
                lp.rightMargin = dp(6)
                layoutParams = lp
            }

            val lbl = TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            }

            itemLayouts.add(item)
            circleBadges.add(circleBadge)
            labels.add(lbl)

            item.addView(circleBadge)
            item.addView(lbl)
            bar.addView(item)
        }

        navTabItemLayouts = itemLayouts
        navCircleBadgeViews = circleBadges
        navLabelViews = labels
        return bar
    }

    private fun switchTabAnimated(targetIndex: Int, initial: Boolean = false) {
        currentTabIndex = targetIndex
        val containers = listOf(tabHomeContainer, tabSettingsContainer, tabAboutContainer)
        for ((idx, view) in containers.withIndex()) {
            if (idx == targetIndex) {
                view.visibility = View.VISIBLE
                if (!initial) {
                    view.alpha = 0f
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 160L
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { view.alpha = it.animatedValue as Float }
                        start()
                    }
                }
            } else {
                view.visibility = View.GONE
            }
        }

        for (i in navTabItemLayouts.indices) {
            val active = i == targetIndex
            val tabItem = navTabItemLayouts[i]
            val badge = navCircleBadgeViews[i] as TextView
            val label = navLabelViews[i]

            if (active) {
                tabItem.background = roundedRect("#344239", "#4B5F52", 999)
                badge.background = ovalDrawable("#F5C438")
                badge.setTextColor(Color.parseColor("#050507"))
                label.setTextColor(Color.WHITE)
            } else {
                tabItem.background = null
                badge.background = ovalDrawable("#26312A")
                badge.setTextColor(Color.parseColor("#94A3B8"))
                label.setTextColor(Color.parseColor("#94A3B8"))
            }
        }

        if (!initial) {
            refreshUiState(checkRemoteConnection = true)
        }
    }

    private fun createSectionCard(title: String, subtitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect("#0B0F17", "#1B2434", 20)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(12)
            layoutParams = lp

            addView(
                TextView(this@MobileSyncConfigActivity).apply {
                    text = title
                    setTextColor(Color.parseColor("#F5C438"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                    letterSpacing = 0.06f
                }
            )
            addView(
                TextView(this@MobileSyncConfigActivity).apply {
                    text = subtitle
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    val subLp = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    subLp.topMargin = dp(3)
                    subLp.bottomMargin = dp(6)
                    layoutParams = subLp
                }
            )
        }
    }

    private fun createSubLabel(textStr: String): TextView {
        return TextView(this).apply {
            text = textStr
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
            layoutParams = lp
        }
    }

    private fun createActionButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.parseColor("#67E8F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            background = roundedRect("#0F172A", "#38BDF8", 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun roundedRect(
        bgHex: String,
        borderHex: String,
        radiusDp: Int,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(bgHex))
            setStroke(dp(strokeWidthDp), Color.parseColor(borderHex))
        }
    }

    private fun ovalDrawable(colorHex: String): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(colorHex))
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun dp(value: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            resources.displayMetrics
        ).toInt()
    }

    // =========================================================================================
    // SCROLLVIEW COM INTERCEPTAÇÃO DE PUXAR PARA ATUALIZAR E ANIMAÇÃO DE ROTAÇÃO DA FLECHA
    // =========================================================================================

    private inner class PullToRefreshScrollView(
        context: Context,
        private val onTriggerRefresh: () -> Unit
    ) : ScrollView(context) {

        var onPullProgressChanged: ((pullPx: Float, thresholdPx: Float) -> Unit)? = null
        private var startY = -1f
        private var currentPullPx = 0f
        private var isDraggingPull = false
        private val triggerThresholdPx = dp(76).toFloat()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = if (scrollY == 0) ev.rawY else -1f
                    currentPullPx = 0f
                    isDraggingPull = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scrollY == 0 && startY >= 0f) {
                        val dy = ev.rawY - startY
                        if (dy > touchSlop * 1.2f) {
                            isDraggingPull = true
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDraggingPull = false
                    startY = -1f
                }
            }
            return super.onInterceptTouchEvent(ev)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (scrollY == 0) {
                        startY = ev.rawY
                    } else {
                        startY = -1f
                    }
                    currentPullPx = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scrollY == 0) {
                        if (startY < 0f) startY = ev.rawY
                        val dy = ev.rawY - startY
                        if (dy > touchSlop) {
                            currentPullPx = (dy - touchSlop) * 0.55f
                            getChildAt(0)?.translationY = currentPullPx.coerceAtMost(triggerThresholdPx * 1.35f)
                            onPullProgressChanged?.invoke(currentPullPx, triggerThresholdPx)
                        }
                    } else {
                        startY = -1f
                        currentPullPx = 0f
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (currentPullPx >= triggerThresholdPx) {
                        onTriggerRefresh.invoke()
                    }
                    if (currentPullPx > 0f) {
                        getChildAt(0)?.animate()?.translationY(0f)?.setDuration(180L)?.start()
                        onPullProgressChanged?.invoke(0f, triggerThresholdPx)
                    }
                    isDraggingPull = false
                    startY = -1f
                    currentPullPx = 0f
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}
