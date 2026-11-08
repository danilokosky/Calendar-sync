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

    // Pull-to-Refresh
    private lateinit var pullScrollView: PullToRefreshScrollView
    private lateinit var pullHeaderContainer: LinearLayout
    private lateinit var pullHeaderText: TextView
    private var isPullRefreshing = false

    // Containers de abas
    private lateinit var tabHomeContainer: LinearLayout
    private lateinit var tabSettingsContainer: LinearLayout
    private lateinit var tabAboutContainer: LinearLayout
    private lateinit var navDotViews: List<View>
    private lateinit var navLabelViews: List<TextView>

    // Topo da Aba Início: Representação Virtual do Relógio + Status de Conexão + Permissões
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

    // Aba Ajustes: Controles e Switches
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

    // Dados em memória para desenho imediato na Representação Virtual e nos Widgets
    private var cachedEvents: List<LocalCalendarEvent> = emptyList()
    private var cachedTasks: List<MobileTaskItem> = emptyList()
    private var lastConnectionReport: WearConnectionReport? = null
    private var selectedVirtualPreviewOverride: String? = null
    private var discoveredWeatherTriggerApps: List<WeatherTriggerAppOption> = emptyList()

    private val syncCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (System.currentTimeMillis() < suppressSyncBroadcastUntilMs) {
                // Evita reconstruir/resetar controles enquanto o usuário está tocando nos switches
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
        // Atualiza imediatamente o status Bluetooth + App Wear + Permissões ao voltar ao foco
        refreshUiState(checkRemoteConnection = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Sensível ao foco da janela (ex.: após voltar do painel Bluetooth ou Permissões)
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
        triggerBackgroundSync("Permissão atualizada")
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
    // CONSTRUÇÃO DA INTERFACE (INÍCIO, AJUSTES, SOBRE) COM PULL-TO-REFRESH
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
            setPadding(dp(16), dp(14), dp(16), dp(108))
        }

        // Indicador Puxar para Atualizar (Pull-to-Refresh)
        pullHeaderContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedRect("#0B111A", "#1E293B", 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10)
            layoutParams = lp
        }
        pullHeaderText = TextView(this).apply {
            text = "↓ Puxe a página para baixo para atualizar Bluetooth, Relógio e Dados"
            setTextColor(Color.parseColor("#67E8F9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
        }
        pullHeaderContainer.addView(pullHeaderText)
        pullScrollView.onPullProgressChanged = { pullPx, thresholdPx ->
            if (!isPullRefreshing) {
                if (pullPx >= thresholdPx) {
                    pullHeaderText.text = "↻ Solte agora para atualizar conexão e sincronizar..."
                    pullHeaderText.setTextColor(Color.parseColor("#F5C438"))
                } else if (pullPx > dp(8)) {
                    pullHeaderText.text = "↓ Continue puxando para atualizar (${((pullPx * 100f) / thresholdPx).toInt().coerceAtMost(100)}%)"
                    pullHeaderText.setTextColor(Color.parseColor("#67E8F9"))
                } else {
                    pullHeaderText.text = "↓ Puxe a página para baixo para atualizar status e relógio"
                    pullHeaderText.setTextColor(Color.parseColor("#64748B"))
                }
            }
        }
        pageColumn.addView(pullHeaderContainer)

        // Cabeçalho compacto essencial
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
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

        val syncNowBtn = Button(this).apply {
            text = "↻ Atualizar"
            isAllCaps = false
            setTextColor(Color.parseColor("#050507"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            background = roundedRect("#F5C438", "#FDE047", 999)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            minHeight = dp(36)
            minimumHeight = dp(36)
            setOnClickListener {
                performPullToRefresh()
            }
        }
        row.addView(syncNowBtn)
        return row
    }

    // =========================================================================================
    // ABA 1: INÍCIO (REPRESENTAÇÃO VIRTUAL DO RELÓGIO + STATUS + PERMISSÕES + WIDGETS 2X2)
    // =========================================================================================

    private fun buildHomeTab(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 1. Card da Representação Virtual Circular + Status Preciso de Conexão
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

        watchPreviewModeLabel = TextView(this).apply {
            text = "REPRESENTAÇÃO VIRTUAL DA CONFIGURAÇÃO ATUAL"
            setTextColor(Color.parseColor("#F5C438"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
        }
        watchCard.addView(watchPreviewModeLabel)

        virtualWatchView = VirtualWatchCanvasView(this).apply {
            val lp = LinearLayout.LayoutParams(dp(236), dp(236))
            lp.topMargin = dp(10)
            lp.bottomMargin = dp(10)
            layoutParams = lp
            setOnClickListener {
                // Ao tocar no relógio virtual, alterna entre visualizar o modo configurado ou inspecionar cada contexto
                cycleVirtualPreviewContext()
            }
        }
        watchCard.addView(virtualWatchView)

        watchCard.addView(
            TextView(this).apply {
                text = "Toque no mostrador virtual ou em qualquer bloco abaixo para inspecionar o Canvas"
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                gravity = Gravity.CENTER
            }
        )

        // Faixa de Status Preciso (Bluetooth + App Wear OS)
        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedRect("#101724", "#1E293B", 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(10)
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

        // 2. Quadro Informativo de Permissões Pendentes (logo abaixo da representação do relógio)
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

        // 3. Cabeçalho dos Blocos de Status (Widgets 2x2 Expansíveis e Reordenáveis)
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
                text = "QUADROS DE STATUS (WIDGETS)"
                setTextColor(Color.parseColor("#E2E8F0"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                letterSpacing = 0.06f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        widgetsHeaderRow.addView(
            TextView(this).apply {
                text = "Segure e arraste para reordenar • ⤢ expande linha"
                setTextColor(Color.parseColor("#64748B"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            }
        )
        col.addView(widgetsHeaderRow)

        widgetsGridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        col.addView(widgetsGridContainer)

        return col
    }

    // =========================================================================================
    // ABA 2: AJUSTES (SWITCHES NATIVOS RESPONSIVOS + CHIPS IN-PLACE)
    // =========================================================================================

    private fun buildSettingsTab(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
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
                selectedVirtualPreviewOverride = selectedKey
                updateAllChipSelectionsInPlace()
                virtualWatchView.invalidate()
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
                updateAllChipSelectionsInPlace()
                virtualWatchView.invalidate()
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

        return col
    }

    // =========================================================================================
    // ABA 3: SOBRE (MODELO REPRESENTATIVO E ARQUITETURA MOVIDOS PARA CÁ)
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
            "1. Representação Virtual & Complication Nativa" to
                "O mostrador do relógio utiliza um único slot central 302×136dp (SHORT_TEXT + SMALL_IMAGE) que alterna dinamicamente entre Agenda, Tarefas, Clima, Sol, Sono e Passos.",
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
    // ATUALIZAÇÃO DE ESTADO, PULL-TO-REFRESH E SINCRONIZAÇÃO
    // =========================================================================================

    private fun performPullToRefresh() {
        if (isPullRefreshing) return
        isPullRefreshing = true
        pullHeaderText.text = "⟳ Atualizando Bluetooth, App do Relógio, Clima e Agenda..."
        pullHeaderText.setTextColor(Color.parseColor("#F5C438"))

        uiScope.launch {
            withContext(Dispatchers.IO) {
                CalendarSyncEngine.fetchPhoneHourlyWeatherIfNeeded(this@MobileSyncConfigActivity, forceRefresh = true)
                CalendarSyncEngine.performFullSyncToWatch(this@MobileSyncConfigActivity, "Puxar para Atualizar")
            }
            isPullRefreshing = false
            val hhmm = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            pullHeaderText.text = "✓ Atualizado às $hhmm • Puxe para baixo para atualizar novamente"
            pullHeaderText.setTextColor(Color.parseColor("#34D399"))
            refreshUiState(checkRemoteConnection = true)
        }
    }

    private fun triggerBackgroundSync(reason: String) {
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

        // Atualiza status imediato de Bluetooth antes mesmo da chamada assíncrona Wear
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
            return
        }

        bluetoothBadgeText.text = "Bluetooth: Ativo"
        bluetoothBadgeText.setTextColor(Color.parseColor("#34D399"))

        when (report.state) {
            WearConnectionState.CONNECTED_WITH_APP_INSTALLED -> {
                val nodeNames = report.installedAppNodeNames.joinToString().ifBlank { "Wear OS" }
                connectionDotView.background = ovalDrawable("#10B981")
                connectionHeadlineText.text = "Conectado • $nodeNames"
                connectionDetailText.text = "Bluetooth ativo e app ChronoWear Wear OS v1.2.1 respondendo em tempo real."
                watchAppBadgeText.text = "App Wear: Conectado"
                watchAppBadgeText.setTextColor(Color.parseColor("#34D399"))
            }
            WearConnectionState.WEARABLE_CONNECTED_APP_MISSING -> {
                val nodeNames = report.wearableDisplayNames.joinToString().ifBlank { "Relógio" }
                connectionDotView.background = ovalDrawable("#F59E0B")
                connectionHeadlineText.text = "Relógio Pareado ($nodeNames) • App Wear Ausente"
                connectionDetailText.text = "O Bluetooth está conectado ao relógio, mas o APK do módulo :wear ainda não respondeu."
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

    // =========================================================================================
    // QUADRO INFORMATIVO DE PERMISSÕES PENDENTES (ABAIXO DA REPRESENTAÇÃO DO RELÓGIO)
    // =========================================================================================

    private data class MissingPermItem(
        val title: String,
        val reason: String,
        val actionLabel: String,
        val onGrantClick: () -> Unit
    )

    private fun refreshPermissionsBanner() {
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
                    reason = "Opcional para detectar alertas meteorológicos do Google/Samsung Clima.",
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

        if (missing.isEmpty()) {
            permissionsAlertContainer.background = roundedRect("#091914", "#10B981", 16)
            permissionsListContainer.addView(
                TextView(this).apply {
                    text = "✓ Todas as permissões essenciais de Calendário, Localização (Clima/Sol) e Segundo Plano estão ativas."
                    setTextColor(Color.parseColor("#34D399"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
            )
            return
        }

        permissionsAlertContainer.background = roundedRect("#19120B", "#F59E0B", 18)
        permissionsListContainer.addView(
            TextView(this).apply {
                text = "⚠ PERMISSÕES PENDENTES (${missing.size})"
                setTextColor(Color.parseColor("#F5C438"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = dp(6)
                layoutParams = lp
            }
        )

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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                background = roundedRect("#F5C438", "#FDE047", 999)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                minHeight = dp(32)
                minimumHeight = dp(32)
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
    // BLOCOS DE WIDGETS 2X2 (EXPANSÍVEIS PARA LINHA INTEIRA E REORDENÁVEIS POR DRAG & DROP)
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
                    // Espaço vazio na segunda coluna quando o próximo bloco está expandido
                    val spacer = View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(156), 1f)
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
            val lp = LinearLayout.LayoutParams(0, dp(156), if (isFullWidth) 2f else 1f)
            if (!isFullWidth && isLeftColumn) {
                lp.rightMargin = dp(5)
            } else if (!isFullWidth && !isLeftColumn) {
                lp.leftMargin = dp(5)
            }
            layoutParams = lp
            tag = widgetId
        }

        // Cabeçalho do Bloco (Título + Botões de Ação / Reordenação / Expansão)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleText)

        // Ação rápida contextual (ex.: Gerenciar tarefas no bloco Tarefas, ou alternar Sono hoje)
        if (widgetId == SyncPreferences.MODE_TASKS) {
            headerRow.addView(
                createMiniIconButton("＋") {
                    showManageTasksDialog()
                }
            )
        } else if (widgetId == SyncPreferences.MODE_SLEEP) {
            headerRow.addView(
                createMiniIconButton("⇄") {
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
            createMiniIconButton("◀") {
                moveWidgetInOrder(widgetId, -1)
            }
        )
        // Botão Mover para frente (▶)
        headerRow.addView(
            createMiniIconButton("▶") {
                moveWidgetInOrder(widgetId, +1)
            }
        )
        // Botão Expandir / Recolher Linha Inteira (⤢ / ⤡)
        headerRow.addView(
            createMiniIconButton(if (isFullWidth) "⤡" else "⤢") {
                prefs.toggleWidgetExpandedFullRow(widgetId)
                refreshWidgetsGridInPlace()
            }
        )

        card.addView(headerRow)

        // Área desenhada do Bloco (mesma altura, adaptando-se a 1 coluna ou linha inteira)
        val blockCanvas = WidgetBlockCanvasView(this, widgetId).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        activeWidgetCanvasViews.add(blockCanvas)
        card.addView(blockCanvas)

        // Toque simples no bloco: seleciona para visualização imediata no Relógio Virtual do topo
        val clickAction = View.OnClickListener {
            selectedVirtualPreviewOverride = widgetId
            virtualWatchView.invalidate()
            refreshWidgetsGridInPlace()
        }
        card.setOnClickListener(clickAction)
        blockCanvas.setOnClickListener(clickAction)

        // Arrastar e Soltar (Drag and Drop) ao segurar o bloco
        val longClickDrag = View.OnLongClickListener {
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
        card.setOnLongClickListener(longClickDrag)
        blockCanvas.setOnLongClickListener(longClickDrag)

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

    private fun createMiniIconButton(symbol: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = symbol
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            background = roundedRect("#131C2B", "#263348", 8)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.leftMargin = dp(4)
            layoutParams = lp
            setOnClickListener { onClick() }
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
    // RESOLUÇÃO DO CONTEXTO ATUAL PARA A REPRESENTAÇÃO VIRTUAL DO RELÓGIO
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
        selectedVirtualPreviewOverride = cycleOrder[nextIdx]
        virtualWatchView.invalidate()
        refreshWidgetsGridInPlace()
    }

    // =========================================================================================
    // VIEW CUSTOMIZADA 1: REPRESENTAÇÃO VIRTUAL CIRCULAR DO RELÓGIO (GERADA PELO SMARTPHONE)
    // =========================================================================================

    private inner class VirtualWatchCanvasView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val radius = (minOf(w, h) / 2f) - dp(4)

            // 1. Corpo externo e mostrador AMOLED
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#040609")
            canvas.drawCircle(cx, cy, radius, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3).toFloat()
            paint.color = Color.parseColor("#222B38")
            canvas.drawCircle(cx, cy, radius, paint)

            // 2. Marcações sutis do bezel
            for (i in 0 until 60) {
                val angleRad = Math.toRadians((i * 6 - 90).toDouble())
                val isMajor = i % 5 == 0
                val rOuter = radius - dp(5)
                val rInner = rOuter - if (isMajor) dp(6) else dp(3)
                paint.strokeWidth = if (isMajor) dp(1.6f).toFloat() else dp(0.9f).toFloat()
                paint.color = if (isMajor) Color.parseColor("#64748B") else Color.parseColor("#1E293B")
                canvas.drawLine(
                    (cx + cos(angleRad) * rInner).toFloat(),
                    (cy + sin(angleRad) * rInner).toFloat(),
                    (cx + cos(angleRad) * rOuter).toFloat(),
                    (cy + sin(angleRad) * rOuter).toFloat(),
                    paint
                )
            }

            // 3. Arcos laterais de complicação (Esquerda: Temperatura, Direita: Chuva/Meta)
            val arcRadius = radius - dp(12)
            rect.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(4).toFloat()
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = Color.parseColor("#1E293B")
            canvas.drawArc(rect, 142f, 76f, false, paint)
            canvas.drawArc(rect, 322f, 76f, false, paint)

            paint.color = Color.parseColor("#F5C438")
            canvas.drawArc(rect, 142f, 52f, false, paint)
            paint.color = Color.parseColor("#38BDF8")
            canvas.drawArc(rect, 322f, 44f, false, paint)

            // 4. Relógio digital superior (HH:mm:ss e Data)
            val cal = Calendar.getInstance()
            val hhmm = String.format(Locale.getDefault(), "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            val ss = String.format(Locale.getDefault(), ":%02d", cal.get(Calendar.SECOND))
            val dateLabel = SimpleDateFormat("EEE, dd MMM", Locale("pt", "BR")).format(cal.time).uppercase(Locale("pt", "BR"))

            textPaint.color = Color.parseColor("#F5C438")
            textPaint.textSize = dp(9.5f).toFloat()
            textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText(dateLabel, cx, cy - radius * 0.56f, textPaint)

            textPaint.color = Color.WHITE
            textPaint.textSize = dp(24).toFloat()
            textPaint.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            canvas.drawText(hhmm, cx - dp(10), cy - radius * 0.31f, textPaint)

            textPaint.color = Color.parseColor("#F5C438")
            textPaint.textSize = dp(12).toFloat()
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(ss, cx + dp(25), cy - radius * 0.31f, textPaint)

            // 5. Slot Central do Canvas (302x136 proporcional dentro do relógio virtual)
            val cardW = radius * 1.48f
            val cardH = radius * 0.68f
            val cardLeft = cx - cardW / 2f
            val cardTop = cy - cardH * 0.34f
            rect.set(cardLeft, cardTop, cardLeft + cardW, cardTop + cardH)

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

            drawContextCanvasInsideRect(canvas, rect, previewMode, isCompactWatchSlot = true)

            // 6. Pílula inferior de bateria e status
            val pillW = radius * 0.68f
            val pillH = dp(16).toFloat()
            rect.set(cx - pillW / 2f, cy + radius * 0.62f, cx + pillW / 2f, cy + radius * 0.62f + pillH)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#111827")
            canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = Color.parseColor("#334155")
            canvas.drawRoundRect(rect, pillH / 2f, pillH / 2f, paint)

            textPaint.color = Color.parseColor("#E2E8F0")
            textPaint.textSize = dp(8.5f).toFloat()
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            val firstTemp = prefs.getPhoneWeatherTempsCsv().split(",").firstOrNull()?.trim() ?: "28"
            canvas.drawText("⚡ 86%  •  ${firstTemp}°C", cx, rect.centerY() + dp(3), textPaint)
        }
    }

    // =========================================================================================
    // VIEW CUSTOMIZADA 2: DESENHO DE CADA BLOCO DE STATUS (WIDGET 2X2 OU LINHA INTEIRA)
    // =========================================================================================

    private inner class WidgetBlockCanvasView(
        context: Context,
        private val mode: String
    ) : View(context) {
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            rect.set(0f, dp(4).toFloat(), width.toFloat(), height.toFloat())
            drawContextCanvasInsideRect(canvas, rect, mode, isCompactWatchSlot = false)
        }
    }

    /**
     * Desenha fielmente o conteúdo gráfico de cada contexto tanto dentro do Slot Central
     * da Representação Virtual do Relógio quanto dentro de cada Bloco de Status (Widget).
     */
    private fun drawContextCanvasInsideRect(
        canvas: Canvas,
        bounds: RectF,
        mode: String,
        isCompactWatchSlot: Boolean
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val path = Path()

        // Fundo do Canvas
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#080B11")
        val corner = if (isCompactWatchSlot) dp(14).toFloat() else dp(12).toFloat()
        canvas.drawRoundRect(bounds, corner, corner, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = Color.parseColor("#1E293B")
        canvas.drawRoundRect(bounds, corner, corner, paint)

        val padX = bounds.left + dp(8)
        val rightX = bounds.right - dp(8)
        val topY = bounds.top + dp(6)
        val availW = bounds.width() - dp(16)
        val nowMs = System.currentTimeMillis()
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

        when (mode) {
            SyncPreferences.MODE_EVENTS -> {
                val (dayHeader, dayEvents) = CalendarSyncEngine.resolveDayHeaderAndEvents(cachedEvents, nowMs)
                val ev0 = dayEvents.getOrNull(0)

                // Cabeçalho Hoje / Amanhã + Cronômetro regressivo à direita
                textPaint.color = Color.parseColor("#F5C438")
                textPaint.textSize = dp(if (isCompactWatchSlot) 10.5f else 11.5f).toFloat()
                textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText(dayHeader, padX, topY + dp(10), textPaint)

                val countdownLabel = if (ev0 != null) {
                    val diffMin = TimeUnit.MILLISECONDS.toMinutes((ev0.startEpochMs - nowMs).coerceAtLeast(0L))
                    val h = diffMin / 60
                    val m = diffMin % 60
                    if (h > 0) "em ${h}h ${m}m" else "em ${m}m"
                } else {
                    "Livre"
                }
                textPaint.color = Color.parseColor("#67E8F9")
                textPaint.textAlign = Paint.Align.RIGHT
                canvas.drawText(countdownLabel, rightX, topY + dp(10), textPaint)

                // Linha 1: Próximo compromisso + Barra de Progresso
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.WHITE
                textPaint.textSize = dp(if (isCompactWatchSlot) 10f else 11f).toFloat()
                val row1Y = topY + dp(25)
                if (ev0 != null) {
                    val tStr = sdf.format(Date(ev0.startEpochMs))
                    canvas.drawText(tStr, padX, row1Y, textPaint)
                    textPaint.color = Color.parseColor("#F5C438")
                    val ellipsized = TextUtils.ellipsize(
                        ev0.title,
                        textPaint,
                        (availW - dp(38)).coerceAtLeast(40f),
                        TextUtils.TruncateAt.END
                    ).toString()
                    canvas.drawText(ellipsized, padX + dp(34), row1Y, textPaint)
                } else {
                    textPaint.color = Color.parseColor("#94A3B8")
                    canvas.drawText("Não há compromissos", padX, row1Y, textPaint)
                }

                // Barra de progresso 0% -> 100%
                val barTop = row1Y + dp(5)
                val barBottom = barTop + dp(4)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#1E293B")
                canvas.drawRoundRect(RectF(padX, barTop, rightX, barBottom), dp(2).toFloat(), dp(2).toFloat(), paint)

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
                        RectF(padX, barTop, padX + availW * frac, barBottom),
                        dp(2).toFloat(),
                        dp(2).toFloat(),
                        paint
                    )
                }

                // Linhas 2 e 3: 2º e 3º compromissos em ordem
                val ev1 = dayEvents.getOrNull(1)
                val ev2 = dayEvents.getOrNull(2)
                val row2Y = barBottom + dp(14)
                val row3Y = row2Y + dp(15)

                drawSecondaryEventLine(canvas, textPaint, padX, availW, row2Y, ev1, sdf, isCompactWatchSlot)
                drawSecondaryEventLine(canvas, textPaint, padX, availW, row3Y, ev2, sdf, isCompactWatchSlot)
            }

            SyncPreferences.MODE_TASKS -> {
                val activeTasks = cachedTasks
                textPaint.color = Color.parseColor("#38BDF8")
                textPaint.textSize = dp(if (isCompactWatchSlot) 10.5f else 11.5f).toFloat()
                textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("Tarefas • Hoje", padX, topY + dp(10), textPaint)

                textPaint.color = Color.parseColor("#94A3B8")
                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.textSize = dp(9.5f).toFloat()
                canvas.drawText("${activeTasks.size} ativas", rightX, topY + dp(10), textPaint)

                if (activeTasks.isEmpty()) {
                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.color = Color.parseColor("#94A3B8")
                    textPaint.textSize = dp(10.5f).toFloat()
                    canvas.drawText("Não há tarefas para hoje", padX, topY + dp(34), textPaint)
                } else {
                    val maxRows = 3
                    for (i in 0 until minOf(maxRows, activeTasks.size)) {
                        val task = activeTasks[i]
                        val y = topY + dp(26 + i * 18)
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = dp(1.3f).toFloat()
                        paint.color = Color.parseColor("#38BDF8")
                        canvas.drawRoundRect(
                            RectF(padX, y - dp(8), padX + dp(9), y + dp(1)),
                            dp(2).toFloat(),
                            dp(2).toFloat(),
                            paint
                        )

                        textPaint.textAlign = Paint.Align.LEFT
                        textPaint.color = Color.WHITE
                        textPaint.textSize = dp(if (isCompactWatchSlot) 9.5f else 10.5f).toFloat()
                        val badge = task.dueHHMM.ifBlank { "Hoje" }
                        val title = TextUtils.ellipsize(
                            task.title,
                            textPaint,
                            (availW - dp(48)).coerceAtLeast(36f),
                            TextUtils.TruncateAt.END
                        ).toString()
                        canvas.drawText(title, padX + dp(14), y, textPaint)

                        textPaint.textAlign = Paint.Align.RIGHT
                        textPaint.color = Color.parseColor("#67E8F9")
                        textPaint.textSize = dp(9f).toFloat()
                        canvas.drawText(badge, rightX, y, textPaint)
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

                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.parseColor("#F5C438")
                textPaint.textSize = dp(if (isCompactWatchSlot) 10.5f else 11.5f).toFloat()
                textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                canvas.drawText("${temps.first()}° • $cond", padX, topY + dp(10), textPaint)

                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = Color.parseColor("#38BDF8")
                textPaint.textSize = dp(9.5f).toFloat()
                canvas.drawText("💧${rain}%", rightX, topY + dp(10), textPaint)

                // Subtítulo com Localização compacta e UF Oficial
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.parseColor("#94A3B8")
                textPaint.textSize = dp(9.5f).toFloat()
                canvas.drawText("📍 $loc", padX, topY + dp(22), textPaint)

                // Curva de 5 pontos de temperatura
                val chartTop = topY + dp(34)
                val chartBottom = bounds.bottom - dp(16)
                val minT = (temps.minOrNull() ?: 20) - 1
                val maxT = (temps.maxOrNull() ?: 32) + 1
                val span = (maxT - minT).coerceAtLeast(1)

                path.reset()
                val stepX = availW / (temps.size - 1).coerceAtLeast(1)
                for (i in temps.indices) {
                    val x = padX + i * stepX
                    val norm = (temps[i] - minT).toFloat() / span.toFloat()
                    val y = chartBottom - norm * (chartBottom - chartTop)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Color.parseColor("#F5C438")
                canvas.drawPath(path, paint)

                for (i in temps.indices) {
                    val x = padX + i * stepX
                    val norm = (temps[i] - minT).toFloat() / span.toFloat()
                    val y = chartBottom - norm * (chartBottom - chartTop)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#F5C438")
                    canvas.drawCircle(x, y, dp(2.5f).toFloat(), paint)

                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.color = Color.WHITE
                    textPaint.textSize = dp(8.5f).toFloat()
                    canvas.drawText("${temps[i]}°", x, y - dp(4), textPaint)

                    textPaint.color = Color.parseColor("#64748B")
                    textPaint.textSize = dp(7.5f).toFloat()
                    canvas.drawText(hours.getOrNull(i) ?: "", x, bounds.bottom - dp(4), textPaint)
                }
            }

            SyncPreferences.MODE_SUN -> {
                val rise = prefs.getSolarSunriseHHMM()
                val set = prefs.getSolarSunsetHHMM()
                val loc = prefs.getPhoneWeatherLocationName()

                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.parseColor("#F5C438")
                textPaint.textSize = dp(10.5f).toFloat()
                textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                canvas.drawText("☀ Sol • $loc", padX, topY + dp(10), textPaint)

                textPaint.color = Color.parseColor("#E2E8F0")
                textPaint.textSize = dp(9.5f).toFloat()
                canvas.drawText("Nascer $rise   •   Pôr $set", padX, topY + dp(23), textPaint)

                // Arco solar senoidal
                val horizonY = bounds.bottom - dp(16)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1).toFloat()
                paint.color = Color.parseColor("#334155")
                canvas.drawLine(padX, horizonY, rightX, horizonY, paint)

                path.reset()
                val steps = 24
                for (i in 0..steps) {
                    val t = i.toFloat() / steps.toFloat()
                    val x = padX + t * availW
                    val y = horizonY - sin(t * Math.PI).toFloat() * dp(24)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                paint.strokeWidth = dp(2).toFloat()
                paint.color = Color.parseColor("#F59E0B")
                canvas.drawPath(path, paint)

                val cal = Calendar.getInstance()
                val minsNow = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val sunFrac = ((minsNow - 330f) / (720f)).coerceIn(0.05f, 0.95f)
                val sunX = padX + sunFrac * availW
                val sunY = horizonY - sin(sunFrac * Math.PI).toFloat() * dp(24)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#FDE047")
                canvas.drawCircle(sunX, sunY, dp(4).toFloat(), paint)
            }

            SyncPreferences.MODE_SLEEP -> {
                val hasSleepToday = prefs.hasVerifiedSleepDataForToday(nowMs)
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.parseColor("#A78BFA")
                textPaint.textSize = dp(10.5f).toFloat()
                textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                canvas.drawText("🌙 Sono de Hoje", padX, topY + dp(10), textPaint)

                if (!hasSleepToday) {
                    textPaint.color = Color.parseColor("#CBD5E1")
                    textPaint.textSize = dp(10f).toFloat()
                    canvas.drawText("Não há dados de sono para hoje", padX, topY + dp(32), textPaint)
                    textPaint.color = Color.parseColor("#64748B")
                    textPaint.textSize = dp(8.5f).toFloat()
                    canvas.drawText("Sincronizado e verificado pelo app", padX, topY + dp(46), textPaint)
                } else {
                    val durMin = prefs.getSleepDurationMinutes()
                    val h = durMin / 60
                    val m = durMin % 60
                    val score = prefs.getSleepScore()
                    textPaint.textAlign = Paint.Align.RIGHT
                    textPaint.color = Color.parseColor("#C4B5FD")
                    textPaint.textSize = dp(10f).toFloat()
                    canvas.drawText("${h}h ${m}m • Score $score", rightX, topY + dp(10), textPaint)

                    textPaint.textAlign = Paint.Align.LEFT
                    textPaint.color = Color.parseColor("#94A3B8")
                    textPaint.textSize = dp(9f).toFloat()
                    canvas.drawText(
                        "${prefs.getSleepBedtimeHHMM()} → ${prefs.getSleepWakeHHMM()}",
                        padX,
                        topY + dp(23),
                        textPaint
                    )

                    val barTop = topY + dp(32)
                    val barBottom = barTop + dp(12)
                    val stages = prefs.getSleepStagesCsv().split(",").mapNotNull { it.trim().toFloatOrNull() }
                        .ifEmpty { listOf(12f, 52f, 21f, 15f) }
                    val total = stages.sum().coerceAtLeast(1f)
                    val colors = listOf("#F87171", "#60A5FA", "#818CF8", "#34D399")
                    var curX = padX
                    for (i in stages.indices) {
                        val segW = (stages[i] / total) * availW
                        paint.style = Paint.Style.FILL
                        paint.color = Color.parseColor(colors.getOrElse(i) { "#818CF8" })
                        canvas.drawRoundRect(
                            RectF(curX, barTop, (curX + segW - dp(2)).coerceAtLeast(curX + 2f), barBottom),
                            dp(3).toFloat(),
                            dp(3).toFloat(),
                            paint
                        )
                        curX += segW
                    }
                }
            }

            SyncPreferences.MODE_STEPS -> {
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.color = Color.parseColor("#34D399")
                textPaint.textSize = dp(10.5f).toFloat()
                textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
                canvas.drawText("👟 6.420 passos", padX, topY + dp(10), textPaint)

                textPaint.textAlign = Paint.Align.RIGHT
                textPaint.color = Color.parseColor("#94A3B8")
                textPaint.textSize = dp(9f).toFloat()
                canvas.drawText("Meta 8.000 (80%)", rightX, topY + dp(10), textPaint)

                val bars = listOf(0.55f, 0.78f, 0.92f, 0.64f, 0.88f, 1.0f, 0.80f)
                val days = listOf("D", "S", "T", "Q", "Q", "S", "S")
                val barSlotW = availW / bars.size
                val maxBarH = dp(28).toFloat()
                val baseBarY = bounds.bottom - dp(14)

                for (i in bars.indices) {
                    val bx = padX + i * barSlotW + barSlotW * 0.2f
                    val bw = barSlotW * 0.6f
                    val bh = bars[i] * maxBarH
                    paint.style = Paint.Style.FILL
                    paint.color = if (i == bars.lastIndex) Color.parseColor("#34D399") else Color.parseColor("#1E293B")
                    canvas.drawRoundRect(
                        RectF(bx, baseBarY - bh, bx + bw, baseBarY),
                        dp(3).toFloat(),
                        dp(3).toFloat(),
                        paint
                    )
                    textPaint.textAlign = Paint.Align.CENTER
                    textPaint.color = Color.parseColor("#64748B")
                    textPaint.textSize = dp(7.5f).toFloat()
                    canvas.drawText(days[i], bx + bw / 2f, bounds.bottom - dp(3), textPaint)
                }
            }
        }
    }

    private fun drawSecondaryEventLine(
        canvas: Canvas,
        textPaint: TextPaint,
        padX: Float,
        availW: Float,
        y: Float,
        ev: LocalCalendarEvent?,
        sdf: SimpleDateFormat,
        isCompact: Boolean
    ) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = dp(if (isCompact) 9f else 9.8f).toFloat()
        if (ev != null) {
            textPaint.color = Color.parseColor("#CBD5E1")
            canvas.drawText(sdf.format(Date(ev.startEpochMs)), padX, y, textPaint)
            textPaint.color = Color.parseColor("#F5C438")
            val title = TextUtils.ellipsize(
                ev.title,
                textPaint,
                (availW - dp(36)).coerceAtLeast(36f),
                TextUtils.TruncateAt.END
            ).toString()
            canvas.drawText(title, padX + dp(34), y, textPaint)
        } else {
            textPaint.color = Color.parseColor("#475569")
            canvas.drawText("--:--  Livre", padX, y, textPaint)
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

        // Tocar em qualquer parte da linha alterna o Switch imediatamente
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
    // DIÁLOGOS DE GERENCIAMENTO DE TAREFAS (HOJE / SEM HORÁRIO) E APPS DE CLIMA
    // =========================================================================================

    private fun showManageTasksDialog() {
        val activeTasks = prefs.getActiveUncompletedTasks()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(8))
        }

        container.addView(
            TextView(this).apply {
                text = "Marque uma tarefa para concluí-la (ela sai imediatamente do Canvas) ou adicione uma nova tarefa para Hoje:"
                setTextColor(Color.parseColor("#CBD5E1"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
        )

        for (task in activeTasks) {
            val cb = CheckBox(this).apply {
                text = "${task.title} (${task.dueHHMM.ifBlank { "Sem horário" }})"
                setTextColor(Color.WHITE)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        prefs.markTaskCompletedAndRemoveFromCanvas(task.id)
                        cachedTasks = prefs.getActiveUncompletedTasks()
                        virtualWatchView.invalidate()
                        refreshWidgetsGridInPlace()
                        triggerBackgroundSync("Tarefa concluída")
                    }
                }
            }
            container.addView(cb)
        }

        val inputTitle = EditText(this).apply {
            hint = "Nova tarefa (ex.: Enviar relatório)"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val inputTime = EditText(this).apply {
            hint = "Horário opcional (ex.: 16:30 ou deixe vazio para 'Hoje')"
            inputType = InputType.TYPE_CLASS_DATETIME
        }
        container.addView(inputTitle)
        container.addView(inputTime)

        AlertDialog.Builder(this)
            .setTitle("Tarefas Ativas (Hoje / Sem Horário)")
            .setView(container)
            .setPositiveButton("Adicionar Tarefa") { _, _ ->
                val title = inputTitle.text?.toString()?.trim().orEmpty()
                val time = inputTime.text?.toString()?.trim().orEmpty().ifBlank { "Hoje" }
                if (title.isNotBlank()) {
                    val updated = prefs.getActiveUncompletedTasks().toMutableList()
                    updated.add(
                        MobileTaskItem(
                            id = "t_${System.currentTimeMillis()}",
                            title = title,
                            dueHHMM = time,
                            sourceLabel = "Tasks",
                            completed = false,
                            isTodayOrTimeless = true
                        )
                    )
                    prefs.saveMobileTaskItems(updated)
                    cachedTasks = prefs.getActiveUncompletedTasks()
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
    // NAVEGAÇÃO INFERIOR E UTILITÁRIOS DE ESTILO
    // =========================================================================================

    private fun buildBottomNavigationBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = roundedRect("#0A0E17", "#1E293B", 999)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val lp = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            lp.setMargins(dp(20), 0, dp(20), dp(14))
            layoutParams = lp
        }

        val tabs = listOf("Início", "Ajustes", "Sobre")
        val dots = mutableListOf<View>()
        val labels = mutableListOf<TextView>()

        for ((idx, title) in tabs.withIndex()) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    switchTabAnimated(idx)
                }
            }
            val dot = View(this).apply {
                background = ovalDrawable("#F5C438")
                val lp = LinearLayout.LayoutParams(dp(7), dp(7))
                lp.rightMargin = dp(6)
                layoutParams = lp
            }
            val lbl = TextView(this).apply {
                text = title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                typeface = Typeface.create("sans-serif-bold", Typeface.BOLD)
            }
            dots.add(dot)
            labels.add(lbl)
            item.addView(dot)
            item.addView(lbl)
            bar.addView(item)
        }

        navDotViews = dots
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
                        duration = 180L
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { view.alpha = it.animatedValue as Float }
                        start()
                    }
                }
            } else {
                view.visibility = View.GONE
            }
        }

        for (i in navDotViews.indices) {
            val active = i == targetIndex
            navDotViews[i].visibility = if (active) View.VISIBLE else View.GONE
            navLabelViews[i].setTextColor(
                if (active) Color.parseColor("#F5C438") else Color.parseColor("#94A3B8")
            )
        }

        // Sempre que trocar de aba, revalida o status preciso de Bluetooth e App do Relógio
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
    // SCROLLVIEW COM SUPORTE NATIVO A PUXAR PARA ATUALIZAR (PULL-TO-REFRESH)
    // =========================================================================================

    private inner class PullToRefreshScrollView(
        context: Context,
        private val onTriggerRefresh: () -> Unit
    ) : ScrollView(context) {

        var onPullProgressChanged: ((pullPx: Float, thresholdPx: Float) -> Unit)? = null
        private var startY = -1f
        private var currentPullPx = 0f
        private val triggerThresholdPx = dp(76).toFloat()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

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
                    startY = -1f
                    currentPullPx = 0f
                }
            }
            return super.onTouchEvent(ev)
        }
    }
}
