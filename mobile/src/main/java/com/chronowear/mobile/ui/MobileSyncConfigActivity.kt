package com.chronowear.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.chronowear.mobile.sync.CalendarSyncEngine
import com.chronowear.mobile.sync.CalendarSyncForegroundService
import com.chronowear.mobile.sync.CalendarSyncWorker
import com.chronowear.mobile.sync.LocalCalendarEvent
import com.chronowear.mobile.sync.LocalCalendarItem
import com.chronowear.mobile.sync.SyncPreferences
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MobileSyncConfigActivity : Activity() {

    private enum class AppTab {
        PAINEL,
        CALENDARIOS,
        CONFIGURACOES
    }

    private lateinit var prefs: SyncPreferences
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var currentTab: AppTab = AppTab.PAINEL

    // Containers principais das 3 abas
    private lateinit var tabPainelView: LinearLayout
    private lateinit var tabCalendariosView: LinearLayout
    private lateinit var tabConfiguracoesView: LinearLayout

    // Botões da barra de navegação inferior
    private lateinit var navBtnPainel: Button
    private lateinit var navBtnCalendarios: Button
    private lateinit var navBtnConfiguracoes: Button

    // Elementos da Aba 1: Painel & Relógio
    private lateinit var wearConnectionStatusText: TextView
    private lateinit var serviceStateBadgeText: TextView
    private lateinit var lastSyncSummaryText: TextView
    private lateinit var liveCountdownTitleText: TextView
    private lateinit var liveCountdownClockText: TextView
    private lateinit var liveProgressPercentText: TextView
    private lateinit var liveProgressFillView: View
    private lateinit var upcomingEventsContainer: LinearLayout

    // Elementos da Aba 2: Calendários
    private lateinit var calendarsSelectionSummaryText: TextView
    private lateinit var calendarsListContainer: LinearLayout

    // Elementos da Aba 3: Configurações (Permissões, Serviço 2º Plano, Leitura e Frequência)
    private lateinit var permCalendarStatusText: TextView
    private lateinit var permCalendarBtn: Button
    private lateinit var permNotificationStatusText: TextView
    private lateinit var permNotificationBtn: Button
    private lateinit var permBatteryStatusText: TextView
    private lateinit var permBatteryBtn: Button
    private lateinit var masterBackgroundSwitch: Switch
    private lateinit var autoChangeSyncSwitch: Switch
    private lateinit var frequencyButtonsRow: LinearLayout
    private lateinit var debounceButtonsRow: LinearLayout
    private lateinit var progressButtonsRow: LinearLayout
    private lateinit var horizonButtonsRow: LinearLayout

    private var cachedHorizonEvents: List<LocalCalendarEvent> = emptyList()
    private var suppressSwitchCallback = false

    private val syncCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshAllTabsData()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SyncPreferences(this)
        window.statusBarColor = Color.parseColor("#090D16")
        window.navigationBarColor = Color.parseColor("#0F172A")

        buildProfessionalTabbedInterface()
        // IMPORTANTE: Nenhuma janela de permissão é disparada sozinha no onCreate!
        // O usuário concede cada permissão explicitamente quando clicar nos botões da aba Configurações.
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SyncPreferences.ACTION_SYNC_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncCompletedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(syncCompletedReceiver, filter)
        }
        refreshAllTabsData()
        startLiveCountdownTicker()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(syncCompletedReceiver)
        } catch (_: Exception) {
        }
        tickerJob?.cancel()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun cardDrawable(
        fillHex: String = "#111827",
        strokeHex: String = "#1E293B",
        cornerDp: Int = 16
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun buildProfessionalTabbedInterface() {
        val mainShell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#090D16"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // TOP APP BAR FIXA
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        val brandColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val brandTitle = TextView(this).apply {
            text = "ChronoWear Studio"
            textSize = 18f
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
        }
        val brandSub = TextView(this).apply {
            text = "Sincronizador Android & Wear OS"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
        }
        brandColumn.addView(brandTitle)
        brandColumn.addView(brandSub)
        topBar.addView(brandColumn)

        serviceStateBadgeText = TextView(this).apply {
            text = "SERVIÇO PARADO"
            textSize = 10f
            setTextColor(Color.parseColor("#F59E0B"))
            typeface = Typeface.MONOSPACE
            background = cardDrawable("#1E293B", "#334155", 8)
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        topBar.addView(serviceStateBadgeText)
        mainShell.addView(topBar)

        // ÁREA CENTRAL SCROLLÁVEL
        val scrollArea = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val pagesWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(28))
        }

        tabPainelView = buildTabPainel()
        tabCalendariosView = buildTabCalendarios()
        tabConfiguracoesView = buildTabConfiguracoes()

        pagesWrapper.addView(tabPainelView)
        pagesWrapper.addView(tabCalendariosView)
        pagesWrapper.addView(tabConfiguracoesView)
        scrollArea.addView(pagesWrapper)
        mainShell.addView(scrollArea)

        // BARRA DE NAVEGAÇÃO INFERIOR (3 ABAS PROFISSIONAIS)
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(dp(12), dp(8), dp(12), dp(10))
        }

        navBtnPainel = createBottomNavButton("1. Painel") { switchTab(AppTab.PAINEL) }
        navBtnCalendarios = createBottomNavButton("2. Calendários") { switchTab(AppTab.CALENDARIOS) }
        navBtnConfiguracoes = createBottomNavButton("3. Configurações") { switchTab(AppTab.CONFIGURACOES) }

        bottomNav.addView(navBtnPainel)
        bottomNav.addView(navBtnCalendarios)
        bottomNav.addView(navBtnConfiguracoes)
        mainShell.addView(bottomNav)

        setContentView(mainShell)
        switchTab(AppTab.PAINEL)
    }

    private fun createBottomNavButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun switchTab(tab: AppTab) {
        currentTab = tab
        tabPainelView.visibility = if (tab == AppTab.PAINEL) View.VISIBLE else View.GONE
        tabCalendariosView.visibility = if (tab == AppTab.CALENDARIOS) View.VISIBLE else View.GONE
        tabConfiguracoesView.visibility = if (tab == AppTab.CONFIGURACOES) View.VISIBLE else View.GONE

        styleNavButton(navBtnPainel, tab == AppTab.PAINEL)
        styleNavButton(navBtnCalendarios, tab == AppTab.CALENDARIOS)
        styleNavButton(navBtnConfiguracoes, tab == AppTab.CONFIGURACOES)

        refreshAllTabsData()
    }

    private fun styleNavButton(button: Button, active: Boolean) {
        button.setTextColor(if (active) Color.parseColor("#090D16") else Color.parseColor("#CBD5E1"))
        button.setTypeface(button.typeface, if (active) Typeface.BOLD else Typeface.NORMAL)
        button.background = cardDrawable(
            if (active) "#F59E0B" else "#1E293B",
            if (active) "#F59E0B" else "#334155",
            10
        )
    }

    // =========================================================================
    // ABA 1: PAINEL & PRÉVIA DO RELÓGIO
    // =========================================================================
    private fun buildTabPainel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // Card de Conexão e Sincronização Manual
            val syncCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable("#111827", "#1E293B", 16)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }

            val syncKicker = TextView(this@MobileSyncConfigActivity).apply {
                text = "CONEXÃO COM SMARTWATCH & ÚLTIMA LEITURA"
                textSize = 11f
                setTextColor(Color.parseColor("#38BDF8"))
                typeface = Typeface.MONOSPACE
            }
            syncCard.addView(syncKicker)

            wearConnectionStatusText = TextView(this@MobileSyncConfigActivity).apply {
                text = "Verificando nós Wear OS..."
                textSize = 15f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(4))
            }
            syncCard.addView(wearConnectionStatusText)

            lastSyncSummaryText = TextView(this@MobileSyncConfigActivity).apply {
                text = "Nenhuma leitura realizada ainda."
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, dp(12))
            }
            syncCard.addView(lastSyncSummaryText)

            val btnRow = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val syncNowBtn = Button(this@MobileSyncConfigActivity).apply {
                text = "Ler Agenda e Enviar Agora"
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.parseColor("#090D16"))
                setTypeface(typeface, Typeface.BOLD)
                background = cardDrawable("#F59E0B", "#F59E0B", 10)
                layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply {
                    rightMargin = dp(6)
                }
                setOnClickListener {
                    handleManualSyncClick()
                }
            }
            btnRow.addView(syncNowBtn)

            val openSettingsBtn = Button(this@MobileSyncConfigActivity).apply {
                text = "Configurações"
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.parseColor("#E2E8F0"))
                background = cardDrawable("#1E293B", "#334155", 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(42)
                )
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener {
                    switchTab(AppTab.CONFIGURACOES)
                }
            }
            btnRow.addView(openSettingsBtn)
            syncCard.addView(btnRow)
            addView(syncCard)

            // Card do Temporizador Regressivo e Barra RangedValue
            val watchPreviewCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable("#0F172A", "#334155", 16)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }

            val previewKicker = TextView(this@MobileSyncConfigActivity).apply {
                text = "CONTAGEM REGRESSIVA & BARRA DE PROGRESSO (COMPLICATION)"
                textSize = 10f
                setTextColor(Color.parseColor("#F59E0B"))
                typeface = Typeface.MONOSPACE
            }
            watchPreviewCard.addView(previewKicker)

            liveCountdownTitleText = TextView(this@MobileSyncConfigActivity).apply {
                text = "Sem próximo compromisso"
                textSize = 15f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(2))
            }
            watchPreviewCard.addView(liveCountdownTitleText)

            liveCountdownClockText = TextView(this@MobileSyncConfigActivity).apply {
                text = "--:--:--"
                textSize = 22f
                setTextColor(Color.parseColor("#F59E0B"))
                typeface = Typeface.MONOSPACE
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(2), 0, dp(10))
            }
            watchPreviewCard.addView(liveCountdownClockText)

            val progressTrack = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                background = cardDrawable("#1E293B", "#334155", 8)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(12)
                )
            }
            liveProgressFillView = View(this@MobileSyncConfigActivity).apply {
                background = cardDrawable("#F59E0B", "#F59E0B", 8)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
            }
            progressTrack.addView(liveProgressFillView)
            watchPreviewCard.addView(progressTrack)

            liveProgressPercentText = TextView(this@MobileSyncConfigActivity).apply {
                text = "Barra RangedValue: 0%"
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(6), 0, 0)
            }
            watchPreviewCard.addView(liveProgressPercentText)
            addView(watchPreviewCard)

            // Card dos Próximos Compromissos Lidos da Agenda
            val eventsCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable("#111827", "#1E293B", 16)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

            val eventsTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = "Próximos Compromissos Sincronizados com o Relógio"
                textSize = 15f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            }
            eventsCard.addView(eventsTitle)

            upcomingEventsContainer = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            eventsCard.addView(upcomingEventsContainer)
            addView(eventsCard)
        }
    }

    // =========================================================================
    // ABA 2: SELEÇÃO DE CALENDÁRIOS PELO USUÁRIO
    // =========================================================================
    private fun buildTabCalendarios(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val headerCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable("#111827", "#1E293B", 16)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }

            val calKicker = TextView(this@MobileSyncConfigActivity).apply {
                text = "ESCOLHA QUAIS CALENDÁRIOS SINCRONIZAR"
                textSize = 11f
                setTextColor(Color.parseColor("#F59E0B"))
                typeface = Typeface.MONOSPACE
            }
            headerCard.addView(calKicker)

            val calTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = "Calendários da Conta do Dispositivo"
                textSize = 17f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
            }
            headerCard.addView(calTitle)

            calendarsSelectionSummaryText = TextView(this@MobileSyncConfigActivity).apply {
                text = "Ative abaixo apenas os calendários cujos compromissos devem ir para o relógio."
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, dp(12))
            }
            headerCard.addView(calendarsSelectionSummaryText)

            val actionsRow = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val selectAllBtn = Button(this@MobileSyncConfigActivity).apply {
                text = "Marcar Todos"
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.parseColor("#F8FAFC"))
                background = cardDrawable("#1E293B", "#334155", 10)
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    rightMargin = dp(6)
                }
                setOnClickListener {
                    if (!CalendarSyncEngine.hasCalendarPermission(this@MobileSyncConfigActivity)) {
                        Toast.makeText(
                            this@MobileSyncConfigActivity,
                            "Conceda primeiro a permissão de Calendário na aba Configurações.",
                            Toast.LENGTH_SHORT
                        ).show()
                        switchTab(AppTab.CONFIGURACOES)
                        return@setOnClickListener
                    }
                    uiScope.launch {
                        val allCals = withContext(Dispatchers.IO) {
                            CalendarSyncEngine.loadDeviceCalendars(this@MobileSyncConfigActivity)
                        }
                        prefs.setSelectedCalendarIds(allCals.map { it.id }.toSet())
                        if (prefs.isBackgroundServiceEnabled()) {
                            withContext(Dispatchers.IO) {
                                CalendarSyncEngine.performFullSyncToWatch(
                                    this@MobileSyncConfigActivity,
                                    "Seleção de Calendários"
                                )
                            }
                        }
                        refreshAllTabsData()
                    }
                }
            }
            actionsRow.addView(selectAllBtn)

            val clearAllBtn = Button(this@MobileSyncConfigActivity).apply {
                text = "Desmarcar Todos"
                isAllCaps = false
                textSize = 12f
                setTextColor(Color.parseColor("#CBD5E1"))
                background = cardDrawable("#090D16", "#334155", 10)
                layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f)
                setOnClickListener {
                    prefs.setSelectedCalendarIds(emptySet())
                    refreshAllTabsData()
                }
            }
            actionsRow.addView(clearAllBtn)
            headerCard.addView(actionsRow)
            addView(headerCard)

            val listCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable("#111827", "#1E293B", 16)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }

            calendarsListContainer = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            listCard.addView(calendarsListContainer)
            addView(listCard)
        }
    }

    // =========================================================================
    // ABA 3: CONFIGURAÇÕES (PERMISSÕES PELO USUÁRIO, SERVIÇO 2º PLANO, LEITURA E FREQUÊNCIA)
    // =========================================================================
    private fun buildTabConfiguracoes(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            // SEÇÃO A: PERMISSÕES CONCEDIDAS APENAS PELO USUÁRIO
            val permsCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable("#111827", "#1E293B", 16)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }

            val permsKicker = TextView(this@MobileSyncConfigActivity).apply {
                text = "1. CONTROLE DE PERMISSÕES (ACIONADAS PELO USUÁRIO)"
                textSize = 11f
                setTextColor(Color.parseColor("#F59E0B"))
                typeface = Typeface.MONOSPACE
            }
            permsCard.addView(permsKicker)

            val permsDesc = TextView(this@MobileSyncConfigActivity).apply {
                text = "Nenhuma permissão é solicitada sem sua ação. Conceda abaixo cada permissão necessária para a leitura do calendário e execução em segundo plano."
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(4), 0, dp(12))
            }
            permsCard.addView(permsDesc)

            // Permissão 1: Ler o Calendário (READ_CALENDAR)
            val calPermRow = createPermissionItemRow(
                title = "Permissão para Ler o Calendário (READ_CALENDAR)",
                subtitle = "Autoriza consultar seus calendários e compromissos futuros no dispositivo.",
                onStatusViewCreated = { permCalendarStatusText = it },
                onButtonCreated = { btn ->
                    permCalendarBtn = btn
                    btn.setOnClickListener {
                        if (CalendarSyncEngine.hasCalendarPermission(this@MobileSyncConfigActivity)) {
                            openSystemAppDetailsSettings()
                        } else {
                            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CALENDAR_ONLY)
                        }
                    }
                }
            )
            permsCard.addView(calPermRow)

            // Permissão 2: Notificações do Serviço em Segundo Plano (POST_NOTIFICATIONS)
            val notifPermRow = createPermissionItemRow(
                title = "Permissão de Notificação do Serviço (POST_NOTIFICATIONS)",
                subtitle = "Exibe a notificação persistente do serviço de sincronização em 2º plano.",
                onStatusViewCreated = { permNotificationStatusText = it },
                onButtonCreated = { btn ->
                    permNotificationBtn = btn
                    btn.setOnClickListener {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !CalendarSyncEngine.hasNotificationPermission(this@MobileSyncConfigActivity)
                        ) {
                            requestPermissions(
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                REQ_NOTIFICATIONS_ONLY
                            )
                        } else {
                            openSystemAppDetailsSettings()
                        }
                    }
                }
            )
            permsCard.addView(notifPermRow)

            // Permissão 3: Execução em Segundo Plano sem Restrição de Bateria
            val batteryPermRow = createPermissionItemRow(
                title = "Permissão para Serviço em Segundo Plano (Sem Restrição de Bateria)",
                subtitle = "Impede que o Android encerre o serviço de sincronização quando a tela apaga.",
                onStatusViewCreated = { permBatteryStatusText = it },
                onButtonCreated = { btn ->
                    permBatteryBtn = btn
                    btn.setOnClickListener {
                        requestUnrestrictedBackgroundExecution()
                    }
                }
            )
            permsCard.addView(batteryPermRow)
            addView(permsCard)

            // SEÇÃO B: SWITCH DE ATIVAÇÃO DO SERVIÇO EM SEGUNDO PLANO & MODO DE LEITURA
            val serviceConfigCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable("#111827", "#1E293B", 16)
                setPadding(dp(16), dp(16), dp(16), dp(16))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(14) }
            }

            val serviceKicker = TextView(this@MobileSyncConfigActivity).apply {
                text = "2. SERVIÇO EM SEGUNDO PLANO & DETECÇÃO DE ALTERAÇÕES"
                textSize = 11f
                setTextColor(Color.parseColor("#38BDF8"))
                typeface = Typeface.MONOSPACE
            }
            serviceConfigCard.addView(serviceKicker)

            masterBackgroundSwitch = Switch(this@MobileSyncConfigActivity).apply {
                text = "Ativar Serviço de Sincronização em Segundo Plano"
                textSize = 14f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(10), 0, dp(8))
                setOnCheckedChangeListener { _, isChecked ->
                    if (suppressSwitchCallback) return@setOnCheckedChangeListener
                    handleMasterServiceToggle(isChecked)
                }
            }
            serviceConfigCard.addView(masterBackgroundSwitch)

            val masterExplain = TextView(this@MobileSyncConfigActivity).apply {
                text = "Quando ativado, o ChronoWear mantém o Foreground Service + WorkManager ativos para ler sua agenda periodicamente e enviar os próximos eventos ao relógio."
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, dp(12))
            }
            serviceConfigCard.addView(masterExplain)

            autoChangeSyncSwitch = Switch(this@MobileSyncConfigActivity).apply {
                text = "Ler compromissos automaticamente quando houver alteração na Agenda"
                textSize = 13f
                setTextColor(Color.parseColor("#E2E8F0"))
                setPadding(0, dp(6), 0, dp(6))
                setOnCheckedChangeListener { _, isChecked ->
                    if (suppressSwitchCallback) return@setOnCheckedChangeListener
                    prefs.setAutoSyncOnCalendarChangeEnabled(isChecked)
                    if (prefs.isBackgroundServiceEnabled() &&
                        CalendarSyncEngine.hasCalendarPermission(this@MobileSyncConfigActivity)
                    ) {
                        CalendarSyncForegroundService.startOrUpdate(
                            this@MobileSyncConfigActivity,
                            "Configuração de Escuta de Alterações"
                        )
                    }
                    refreshAllTabsData()
                }
            }
            serviceConfigCard.addView(autoChangeSyncSwitch)

            val autoChangeExplain = TextView(this@MobileSyncConfigActivity).apply {
                text = "Como funciona: o app registra um ContentObserver em CalendarContract.Events e escuta o broadcast ACTION_PROVIDER_CHANGED. Sempre que você criar, editar ou apagar um compromisso no Google Agenda/Samsung Calendar, ele relê os compromissos e atualiza o relógio sozinho."
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, 0, 0, dp(12))
            }
            serviceConfigCard.addView(autoChangeExplain)

            val debounceLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Tempo de Espera após Alteração no Compromisso (Debounce):"
                textSize = 12f
                setTextColor(Color.parseColor("#CBD5E1"))
                setTypeface(typeface, Typeface.BOLD)
            }
            serviceConfigCard.addView(debounceLabel)

            debounceButtonsRow = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(14))
            }
            serviceConfigCard.addView(debounceButtonsRow)

            val freqLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Frequência da Leitura Periódica em Segundo Plano (WorkManager):"
                textSize = 12f
                setTextColor(Color.parseColor("#CBD5E1"))
                setTypeface(typeface, Typeface.BOLD)
            }
            serviceConfigCard.addView(freqLabel)

            frequencyButtonsRow = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
            }
            serviceConfigCard.addView(frequencyButtonsRow)
            addView(serviceConfigCard)

            // SEÇÃO C: PARÂMETROS DO RELÓGIO (BARRA DE PROGRESSO E HORIZONTE AUTÔNOMO)
            val watchParamsCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable("#111827", "#1E293B", 16)
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }

            val watchParamsKicker = TextView(this@MobileSyncConfigActivity).apply {
                text = "3. JANELA DE PROGRESSO & HORIZONTE AUTÔNOMO NO RELÓGIO"
                textSize = 11f
                setTextColor(Color.parseColor("#10B981"))
                typeface = Typeface.MONOSPACE
            }
            watchParamsCard.addView(watchParamsKicker)

            val progTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = "Antecedência da Barra de Progresso na Watch Face:"
                textSize = 13f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(4))
            }
            watchParamsCard.addView(progTitle)

            progressButtonsRow = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(14))
            }
            watchParamsCard.addView(progressButtonsRow)

            val horTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = "Horizonte de Dias Sincronizados (Modo Autônomo sem Bluetooth):"
                textSize = 13f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            }
            watchParamsCard.addView(horTitle)

            horizonButtonsRow = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            watchParamsCard.addView(horizonButtonsRow)
            addView(watchParamsCard)
        }
    }

    private fun createPermissionItemRow(
        title: String,
        subtitle: String,
        onStatusViewCreated: (TextView) -> Unit,
        onButtonCreated: (Button) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardDrawable("#090D16", "#1E293B", 12)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }

            val titleTv = TextView(this@MobileSyncConfigActivity).apply {
                text = title
                textSize = 13f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
            }
            addView(titleTv)

            val subTv = TextView(this@MobileSyncConfigActivity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(2), 0, dp(8))
            }
            addView(subTv)

            val bottomRow = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val statusTv = TextView(this@MobileSyncConfigActivity).apply {
                text = "Pendente"
                textSize = 11f
                typeface = Typeface.MONOSPACE
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            onStatusViewCreated(statusTv)
            bottomRow.addView(statusTv)

            val actionBtn = Button(this@MobileSyncConfigActivity).apply {
                text = "Conceder"
                isAllCaps = false
                textSize = 11f
                setPadding(dp(12), dp(6), dp(12), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(34)
                )
            }
            onButtonCreated(actionBtn)
            bottomRow.addView(actionBtn)
            addView(bottomRow)
        }
    }

    private fun handleMasterServiceToggle(enable: Boolean) {
        if (enable && !CalendarSyncEngine.hasCalendarPermission(this)) {
            suppressSwitchCallback = true
            masterBackgroundSwitch.isChecked = false
            suppressSwitchCallback = false
            Toast.makeText(
                this,
                "Conceda primeiro a Permissão para Ler o Calendário no botão acima.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        prefs.setBackgroundServiceEnabled(enable)
        if (enable) {
            CalendarSyncWorker.schedulePeriodicSync(this, prefs.getSyncIntervalMinutes())
            try {
                CalendarSyncForegroundService.startOrUpdate(this, "Serviço Ativado pelo Usuário")
            } catch (_: Exception) {
            }
            Toast.makeText(
                this,
                "Serviço em 2º plano ativado!",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            CalendarSyncWorker.cancelPeriodicSync(this)
            CalendarSyncForegroundService.stop(this)
            Toast.makeText(
                this,
                "Serviço em 2º plano desativado pelo usuário.",
                Toast.LENGTH_SHORT
            ).show()
        }
        refreshAllTabsData()
    }

    private fun handleManualSyncClick() {
        if (!CalendarSyncEngine.hasCalendarPermission(this)) {
            Toast.makeText(
                this,
                "Conceda a permissão para ler o calendário na aba Configurações.",
                Toast.LENGTH_LONG
            ).show()
            switchTab(AppTab.CONFIGURACOES)
            return
        }
        if (prefs.getSelectedCalendarIds().isEmpty()) {
            Toast.makeText(
                this,
                "Selecione pelo menos 1 calendário na aba Calendários.",
                Toast.LENGTH_LONG
            ).show()
            switchTab(AppTab.CALENDARIOS)
            return
        }

        Toast.makeText(this, "Lendo compromissos e enviando ao relógio...", Toast.LENGTH_SHORT).show()
        uiScope.launch {
            withContext(Dispatchers.IO) {
                CalendarSyncEngine.performFullSyncToWatch(
                    this@MobileSyncConfigActivity,
                    "Botão Manual do Usuário"
                )
            }
            refreshAllTabsData()
        }
    }

    private fun requestUnrestrictedBackgroundExecution() {
        try {
            if (!CalendarSyncEngine.isIgnoringBatteryOptimizations(this)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                openSystemAppDetailsSettings()
            }
        } catch (_: Exception) {
            openSystemAppDetailsSettings()
        }
    }

    private fun openSystemAppDetailsSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        )
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (requestCode == REQ_CALENDAR_ONLY) {
            Toast.makeText(
                this,
                if (granted) "Permissão de Calendário concedida! Agora escolha os calendários na aba 2."
                else "Permissão de Calendário negada.",
                Toast.LENGTH_LONG
            ).show()
        } else if (requestCode == REQ_NOTIFICATIONS_ONLY) {
            Toast.makeText(
                this,
                if (granted) "Permissão de Notificação concedida!"
                else "Permissão de Notificação negada.",
                Toast.LENGTH_SHORT
            ).show()
        }
        refreshAllTabsData()
    }

    private fun refreshAllTabsData() {
        val hasCalPerm = CalendarSyncEngine.hasCalendarPermission(this)
        val hasNotifPerm = CalendarSyncEngine.hasNotificationPermission(this)
        val isUnrestrictedBg = CalendarSyncEngine.isIgnoringBatteryOptimizations(this)
        val serviceActive = prefs.isBackgroundServiceEnabled() && hasCalPerm

        // Badge superior
        serviceStateBadgeText.text = if (serviceActive) "2º PLANO ATIVO" else "2º PLANO INATIVO"
        serviceStateBadgeText.setTextColor(
            if (serviceActive) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        )

        // Atualiza status na Aba 3 (Configurações)
        permCalendarStatusText.text = if (hasCalPerm) "CONCEDIDA PELO USUÁRIO" else "NÃO CONCEDIDA"
        permCalendarStatusText.setTextColor(
            if (hasCalPerm) Color.parseColor("#10B981") else Color.parseColor("#F87171")
        )
        permCalendarBtn.text = if (hasCalPerm) "Gerenciar" else "Conceder Permissão"
        permCalendarBtn.setTextColor(
            if (hasCalPerm) Color.parseColor("#E2E8F0") else Color.parseColor("#090D16")
        )
        permCalendarBtn.background = cardDrawable(
            if (hasCalPerm) "#1E293B" else "#F59E0B",
            if (hasCalPerm) "#334155" else "#F59E0B",
            8
        )

        permNotificationStatusText.text = if (hasNotifPerm) "CONCEDIDA" else "NÃO CONCEDIDA"
        permNotificationStatusText.setTextColor(
            if (hasNotifPerm) Color.parseColor("#10B981") else Color.parseColor("#F59E0B")
        )
        permNotificationBtn.text = if (hasNotifPerm) "Ajustes" else "Permitir Notificação"
        permNotificationBtn.setTextColor(
            if (hasNotifPerm) Color.parseColor("#E2E8F0") else Color.parseColor("#090D16")
        )
        permNotificationBtn.background = cardDrawable(
            if (hasNotifPerm) "#1E293B" else "#F59E0B",
            if (hasNotifPerm) "#334155" else "#F59E0B",
            8
        )

        permBatteryStatusText.text = if (isUnrestrictedBg) "SEM RESTRIÇÕES (ATIVO)" else "OTIMIZADO PELO SISTEMA"
        permBatteryStatusText.setTextColor(
            if (isUnrestrictedBg) Color.parseColor("#10B981") else Color.parseColor("#38BDF8")
        )
        permBatteryBtn.text = if (isUnrestrictedBg) "Configurado" else "Autorizar 2º Plano"
        permBatteryBtn.setTextColor(
            if (isUnrestrictedBg) Color.parseColor("#E2E8F0") else Color.parseColor("#090D16")
        )
        permBatteryBtn.background = cardDrawable(
            if (isUnrestrictedBg) "#1E293B" else "#38BDF8",
            if (isUnrestrictedBg) "#334155" else "#38BDF8",
            8
        )

        suppressSwitchCallback = true
        masterBackgroundSwitch.isChecked = prefs.isBackgroundServiceEnabled()
        autoChangeSyncSwitch.isChecked = prefs.isAutoSyncOnCalendarChangeEnabled()
        suppressSwitchCallback = false

        renderSettingsChips()

        // Carrega calendários, eventos e nós conectados em background
        uiScope.launch {
            val calendars = withContext(Dispatchers.IO) {
                CalendarSyncEngine.loadDeviceCalendars(this@MobileSyncConfigActivity)
            }
            val events = withContext(Dispatchers.IO) {
                CalendarSyncEngine.queryHorizonEvents(this@MobileSyncConfigActivity)
            }
            val nodes = withContext(Dispatchers.IO) {
                try {
                    Wearable.getNodeClient(this@MobileSyncConfigActivity).connectedNodes.await()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            cachedHorizonEvents = events
            val summary = prefs.getLastSyncSummary()

            wearConnectionStatusText.text = if (nodes.isNotEmpty()) {
                "Smartwatch Conectado: ${nodes.joinToString { it.displayName }}"
            } else {
                "Nenhum Smartwatch Bluetooth conectado no momento"
            }

            val timeFormatted = if (summary.timestampMs > 0L) {
                SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(summary.timestampMs))
            } else {
                "Nenhuma sincronização disparada"
            }

            val readModeDesc = if (prefs.isAutoSyncOnCalendarChangeEnabled()) {
                "Periódica (${prefs.getSyncIntervalMinutes()}m) + Ao alterar compromissos (${prefs.getChangeDebounceSeconds()}s)"
            } else {
                "Somente periódica a cada ${prefs.getSyncIntervalMinutes()} min"
            }

            lastSyncSummaryText.text =
                "Última leitura: $timeFormatted\nGatilho: ${summary.triggerSource}\nModo de leitura: $readModeDesc\nResultado: ${summary.status}"

            populateCalendarsTab(calendars)
            populateEventsPreview(events)
            updateLiveCountdownTick()
        }
    }

    private fun renderSettingsChips() {
        // Chips de Debounce (Ao alterar compromissos)
        debounceButtonsRow.removeAllViews()
        val curDebounce = prefs.getChangeDebounceSeconds()
        listOf(2 to "Imediato (2s)", 5 to "5 seg", 15 to "15 seg", 30 to "30 seg").forEach { (sec, label) ->
            val active = (curDebounce == sec)
            debounceButtonsRow.addView(
                createChipButton(label, active, "#38BDF8") {
                    prefs.setChangeDebounceSeconds(sec)
                    renderSettingsChips()
                    refreshAllTabsData()
                }
            )
        }

        // Chips de Frequência de Leitura Periódica
        frequencyButtonsRow.removeAllViews()
        val curFreq = prefs.getSyncIntervalMinutes()
        listOf(15L to "15 min", 30L to "30 min", 60L to "1 hora", 120L to "2 horas").forEach { (mins, label) ->
            val active = (curFreq == mins)
            frequencyButtonsRow.addView(
                createChipButton(label, active, "#F59E0B") {
                    prefs.setSyncIntervalMinutes(mins)
                    if (prefs.isBackgroundServiceEnabled()) {
                        CalendarSyncWorker.schedulePeriodicSync(this, mins)
                    }
                    renderSettingsChips()
                    refreshAllTabsData()
                }
            )
        }

        // Chips da Janela de Progresso
        progressButtonsRow.removeAllViews()
        val curProg = prefs.getProgressWindowMinutes()
        listOf(30 to "30 min", 60 to "1 hora", 120 to "2 horas", 180 to "3 horas").forEach { (mins, label) ->
            val active = (curProg == mins)
            progressButtonsRow.addView(
                createChipButton(label, active, "#F59E0B") {
                    prefs.setProgressWindowMinutes(mins)
                    renderSettingsChips()
                    if (prefs.isBackgroundServiceEnabled()) {
                        CalendarSyncWorker.enqueueImmediateSync(this, "Ajuste da Barra (${mins}m)")
                    }
                    refreshAllTabsData()
                }
            )
        }

        // Chips do Horizonte Autônomo
        horizonButtonsRow.removeAllViews()
        val curHor = prefs.getHorizonDays()
        listOf(2 to "2 dias", 3 to "3 dias", 5 to "5 dias", 7 to "7 dias").forEach { (days, label) ->
            val active = (curHor == days)
            horizonButtonsRow.addView(
                createChipButton(label, active, "#10B981") {
                    prefs.setHorizonDays(days)
                    renderSettingsChips()
                    if (prefs.isBackgroundServiceEnabled()) {
                        CalendarSyncWorker.enqueueImmediateSync(this, "Ajuste de Horizonte (${days}d)")
                    }
                    refreshAllTabsData()
                }
            )
        }
    }

    private fun createChipButton(
        label: String,
        selected: Boolean,
        accentHex: String,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(if (selected) Color.parseColor("#090D16") else Color.parseColor("#CBD5E1"))
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            background = cardDrawable(
                if (selected) accentHex else "#090D16",
                if (selected) accentHex else "#334155",
                8
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                rightMargin = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun populateCalendarsTab(calendars: List<LocalCalendarItem>) {
        calendarsListContainer.removeAllViews()
        val selectedIds = prefs.getSelectedCalendarIds().toMutableSet()

        calendarsSelectionSummaryText.text =
            "${selectedIds.size} de ${calendars.size} calendário(s) marcados pelo usuário para sincronização."

        if (!CalendarSyncEngine.hasCalendarPermission(this)) {
            val noPermTv = TextView(this).apply {
                text = "A permissão para ler o calendário ainda não foi concedida.\nToque na aba '3. Configurações' abaixo e clique em 'Conceder Permissão'."
                textSize = 13f
                setTextColor(Color.parseColor("#FCA5A5"))
                setPadding(0, dp(8), 0, dp(8))
            }
            calendarsListContainer.addView(noPermTv)
            return
        }

        if (calendars.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Nenhum calendário visível encontrado nas contas deste smartphone."
                textSize = 13f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(8), 0, dp(8))
            }
            calendarsListContainer.addView(emptyTv)
            return
        }

        calendars.forEach { cal ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardDrawable("#090D16", "#1E293B", 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val colorDot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(cal.colorInt)
                }
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                    rightMargin = dp(12)
                }
            }
            row.addView(colorDot)

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameTv = TextView(this).apply {
                text = cal.displayName
                textSize = 14f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
            }
            val subTv = TextView(this).apply {
                text = "${cal.accountName} • ${cal.upcomingEventsCount} compromisso(s) nos próximos ${prefs.getHorizonDays()}d"
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
            }
            textCol.addView(nameTv)
            textCol.addView(subTv)
            row.addView(textCol)

            val toggle = Switch(this).apply {
                isChecked = selectedIds.contains(cal.id)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedIds.add(cal.id) else selectedIds.remove(cal.id)
                    prefs.setSelectedCalendarIds(selectedIds)
                    if (prefs.isBackgroundServiceEnabled()) {
                        CalendarSyncWorker.enqueueImmediateSync(
                            this@MobileSyncConfigActivity,
                            "Alteração de Calendário Selecionado"
                        )
                    }
                    refreshAllTabsData()
                }
            }
            row.addView(toggle)
            calendarsListContainer.addView(row)
        }
    }

    private fun populateEventsPreview(events: List<LocalCalendarEvent>) {
        upcomingEventsContainer.removeAllViews()
        if (!CalendarSyncEngine.hasCalendarPermission(this)) {
            val tv = TextView(this).apply {
                text = "Conceda a permissão de leitura do calendário na aba '3. Configurações' para visualizar seus compromissos."
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
            }
            upcomingEventsContainer.addView(tv)
            return
        }

        if (prefs.getSelectedCalendarIds().isEmpty()) {
            val tv = TextView(this).apply {
                text = "Nenhum calendário selecionado. Vá até a aba '2. Calendários' e escolha quais agendas sincronizar."
                textSize = 12f
                setTextColor(Color.parseColor("#F59E0B"))
            }
            upcomingEventsContainer.addView(tv)
            return
        }

        if (events.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Nenhum compromisso encontrado nos calendários escolhidos para os próximos ${prefs.getHorizonDays()} dias."
                textSize = 12f
                setTextColor(Color.parseColor("#94A3B8"))
            }
            upcomingEventsContainer.addView(tv)
            return
        }

        val sdf = SimpleDateFormat("EEE dd/MM • HH:mm", Locale.getDefault())
        events.take(6).forEachIndexed { idx, ev ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = cardDrawable("#090D16", "#1E293B", 10)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val meta = TextView(this).apply {
                text = "#${idx + 1} • ${sdf.format(Date(ev.startEpochMs))} • ${ev.calendarName}"
                textSize = 11f
                setTextColor(Color.parseColor("#38BDF8"))
                typeface = Typeface.MONOSPACE
            }
            val title = TextView(this).apply {
                text = ev.title
                textSize = 14f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
            }
            box.addView(meta)
            box.addView(title)
            upcomingEventsContainer.addView(box)
        }
    }

    private fun startLiveCountdownTicker() {
        tickerJob?.cancel()
        tickerJob = uiScope.launch {
            while (isActive) {
                updateLiveCountdownTick()
                delay(1000L)
            }
        }
    }

    private fun updateLiveCountdownTick() {
        val nowMs = System.currentTimeMillis()
        val nextEv = cachedHorizonEvents.firstOrNull { it.startEpochMs > nowMs }

        if (nextEv == null) {
            liveCountdownTitleText.text = "Nenhum próximo compromisso ativo"
            liveCountdownClockText.text = "--:--:--"
            liveProgressPercentText.text = "Barra RangedValue: 0% (Janela ${prefs.getProgressWindowMinutes()}m)"
            liveProgressFillView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
            return
        }

        val remainingMs = (nextEv.startEpochMs - nowMs).coerceAtLeast(0L)
        val totalSec = remainingMs / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L

        val startStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextEv.startEpochMs))
        liveCountdownTitleText.text = "Próximo às $startStr: ${nextEv.title}"
        liveCountdownClockText.text = if (days > 0) {
            String.format(Locale.getDefault(), "Faltam %dd %02dh %02dm %02ds", days, hours, mins, secs)
        } else {
            String.format(Locale.getDefault(), "Faltam %02dh %02dm %02ds", hours, mins, secs)
        }

        val windowMs = TimeUnit.MINUTES.toMillis(prefs.getProgressWindowMinutes().toLong()).toFloat()
        val ratio = if (remainingMs >= windowMs) {
            0f
        } else {
            (1f - (remainingMs.toFloat() / windowMs)).coerceIn(0f, 1f)
        }
        val pct = (ratio * 100f).toInt()
        liveProgressPercentText.text =
            "Barra RangedValue: $pct% preenchida (Inicia ${prefs.getProgressWindowMinutes()} min antes)"

        val parentWidth = (liveProgressFillView.parent as? View)?.width ?: 0
        if (parentWidth > 0) {
            val fillPx = (parentWidth * ratio).toInt().coerceAtLeast(if (ratio > 0f) dp(6) else 0)
            liveProgressFillView.layoutParams = LinearLayout.LayoutParams(
                fillPx,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    companion object {
        private const val REQ_CALENDAR_ONLY = 2001
        private const val REQ_NOTIFICATIONS_ONLY = 2002
    }
}
