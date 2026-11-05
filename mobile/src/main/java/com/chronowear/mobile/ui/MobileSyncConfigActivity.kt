package com.chronowear.mobile.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.FrameLayout
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

    private enum class BottomTab {
        INICIO,
        CONFIGURACOES,
        SOBRE
    }

    private lateinit var prefs: SyncPreferences
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var activeTab: BottomTab = BottomTab.INICIO
    private var suppressSwitchEvents = false

    // Containers principais das 3 telas (Início, Configurações, Sobre)
    private lateinit var screenInicio: LinearLayout
    private lateinit var screenConfiguracoes: LinearLayout
    private lateinit var screenSobre: LinearLayout

    // Pílulas de filtro no topo (estilo Samsung Health)
    private lateinit var topCategoryPillsRow: LinearLayout

    // Cabeçalho Bluetooth (estilo Amoled Watchfaces: 蓝牙 Connected • Galaxy Watch Ultra)
    private lateinit var btConnectedBannerInicio: TextView
    private lateinit var btConnectedBannerConfig: TextView

    // Elementos da Tela Início
    private lateinit var watchBezelPreviewSubtext: TextView
    private lateinit var installedWearDeviceNameText: TextView
    private lateinit var energyCountdownBigNumber: TextView
    private lateinit var energyEventSubtitle: TextView
    private lateinit var energyProgressFillView: View
    private lateinit var energyProgressCaption: TextView
    private lateinit var upcomingThreeListContainer: LinearLayout

    // Elementos da Tela Configurações (estilo Amoled Watchfaces)
    private lateinit var switchBackgroundService: Switch
    private lateinit var subtitleBackgroundService: TextView
    private lateinit var switchNotificationsSync: Switch
    private lateinit var switchMediaPlaybackSync: Switch
    private lateinit var switchCalendarEventsSync: Switch
    private lateinit var subtitleCalendarEventsSync: TextView
    private lateinit var btnSyncedCalendarsPill: Button
    private lateinit var switchAutoReadOnChange: Switch
    private lateinit var rowFrequencyChips: LinearLayout
    private lateinit var rowProgressWindowChips: LinearLayout
    private lateinit var rowHorizonDaysChips: LinearLayout

    // Botões da Dock Flutuante Inferior (estilo Samsung Health)
    private lateinit var navTabInicio: LinearLayout
    private lateinit var navTabConfig: LinearLayout
    private lateinit var navTabSobre: LinearLayout
    private lateinit var navLabelInicio: TextView
    private lateinit var navLabelConfig: TextView
    private lateinit var navLabelSobre: TextView

    private var cachedHorizonEvents: List<LocalCalendarEvent> = emptyList()
    private var cachedDeviceCalendars: List<LocalCalendarItem> = emptyList()

    private val syncCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUiState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SyncPreferences(this)
        window.statusBarColor = Color.parseColor("#182218")
        window.navigationBarColor = Color.parseColor("#0B0E0C")

        buildSamsungHealthAndAmoledWatchfacesUi()
        // Nenhuma permissão é disparada automaticamente no onCreate.
        // O usuário concede cada permissão ao acionar os Switches em Settings.
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SyncPreferences.ACTION_SYNC_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncCompletedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(syncCompletedReceiver, filter)
        }
        refreshUiState()
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

    private fun pillDrawable(
        fillHex: String,
        strokeHex: String = fillHex,
        radiusDp: Int = 28,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(strokeWidthDp), Color.parseColor(strokeHex))
        }
    }

    private fun headerGradientDrawable(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor("#3E351B"), // Tom dourado/oliva suave do topo do Samsung Health
                Color.parseColor("#16231B"), // Verde profundo
                Color.parseColor("#0B0E0C")  // Preto carbono One UI
            )
        )
    }

    private fun buildSamsungHealthAndAmoledWatchfacesUi() {
        val rootFrame = FrameLayout(this).apply {
            background = headerGradientDrawable()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val mainColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(116)) // Espaço inferior para a pílula flutuante
        }

        // TOPO ESTILO SAMSUNG HEALTH: Título + Barra de Pílulas de Categoria
        val topHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), dp(14))
        }

        val appTitleText = TextView(this).apply {
            text = "ChronoWear Health & Sync"
            textSize = 23f
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topHeaderRow.addView(appTitleText)

        val quickSyncBadge = TextView(this).apply {
            text = "Wear OS"
            textSize = 11f
            setTextColor(Color.parseColor("#A7F3D0"))
            setTypeface(typeface, Typeface.BOLD)
            background = pillDrawable("#1E3A2F", "#34D399", 20)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { triggerManualSyncFromFab() }
        }
        topHeaderRow.addView(quickSyncBadge)
        mainColumn.addView(topHeaderRow)

        // Pílula de ícones/atalhos horizontal (igual à barra abaixo de "Samsung Health" no print 1)
        topCategoryPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable("#262D26", "#384238", 30)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(18) }
        }
        mainColumn.addView(topCategoryPillsRow)

        // 3 Telas Principais
        screenInicio = buildScreenInicio()
        screenConfiguracoes = buildScreenConfiguracoes()
        screenSobre = buildScreenSobre()

        mainColumn.addView(screenInicio)
        mainColumn.addView(screenConfiguracoes)
        mainColumn.addView(screenSobre)

        scrollView.addView(mainColumn)
        rootFrame.addView(scrollView)

        // DOCK FLUTUANTE INFERIOR ESTILO SAMSUNG HEALTH (Pílula arredondada + Botão Circular lateral)
        val floatingBottomWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(18))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val pillNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable("#232A25", "#37413A", 34)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                rightMargin = dp(12)
            }
        }

        navTabInicio = createFloatingNavItem("⌂", "Início") {
            switchTab(BottomTab.INICIO)
        }.also { navLabelInicio = it.getChildAt(1) as TextView }

        navTabConfig = createFloatingNavItem("⚙", "Settings") {
            switchTab(BottomTab.CONFIGURACOES)
        }.also { navLabelConfig = it.getChildAt(1) as TextView }

        navTabSobre = createFloatingNavItem("ⓘ", "About") {
            switchTab(BottomTab.SOBRE)
        }.also { navLabelSobre = it.getChildAt(1) as TextView }

        pillNavBar.addView(navTabInicio)
        pillNavBar.addView(navTabConfig)
        pillNavBar.addView(navTabSobre)
        floatingBottomWrapper.addView(pillNavBar)

        // Botão circular de ação rápida à direita (como no Samsung Health e no Amoled Watchfaces)
        val roundSyncFab = Button(this).apply {
            text = "⌚"
            textSize = 18f
            setTextColor(Color.parseColor("#0B191E"))
            background = pillDrawable("#BAE6FD", "#7DD3FC", 32)
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
            setOnClickListener {
                triggerManualSyncFromFab()
            }
        }
        floatingBottomWrapper.addView(roundSyncFab)

        rootFrame.addView(floatingBottomWrapper)
        setContentView(rootFrame)

        switchTab(BottomTab.INICIO)
    }

    private fun renderTopCategoryStrip() {
        topCategoryPillsRow.removeAllViews()
        val items = listOf(
            Triple("⊞ Visão", BottomTab.INICIO, activeTab == BottomTab.INICIO),
            Triple("⚙ Settings", BottomTab.CONFIGURACOES, activeTab == BottomTab.CONFIGURACOES),
            Triple("📅 Agendas", BottomTab.CONFIGURACOES, false),
            Triple("⌚ Relógio", BottomTab.SOBRE, activeTab == BottomTab.SOBRE)
        )

        items.forEachIndexed { idx, (label, targetTab, isHighlighted) ->
            val pillBtn = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(
                    if (isHighlighted) Color.parseColor("#FFFFFF") else Color.parseColor("#9CA3AF")
                )
                setTypeface(typeface, if (isHighlighted) Typeface.BOLD else Typeface.NORMAL)
                background = if (isHighlighted) {
                    pillDrawable("#525B52", "#6B7280", 24)
                } else {
                    pillDrawable("#00000000", "#00000000", 24)
                }
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    if (idx == 2) {
                        openSyncedCalendarsDialog()
                    } else {
                        switchTab(targetTab)
                    }
                }
            }
            topCategoryPillsRow.addView(pillBtn)
        }
    }

    private fun createFloatingNavItem(
        iconSymbol: String,
        title: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { onClick() }

            val iconTv = TextView(this@MobileSyncConfigActivity).apply {
                text = iconSymbol
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#F8FAFC"))
            }
            val labelTv = TextView(this@MobileSyncConfigActivity).apply {
                text = title
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#CBD5E1"))
            }
            addView(iconTv)
            addView(labelTv)
        }
    }

    private fun switchTab(tab: BottomTab) {
        activeTab = tab
        screenInicio.visibility = if (tab == BottomTab.INICIO) View.VISIBLE else View.GONE
        screenConfiguracoes.visibility = if (tab == BottomTab.CONFIGURACOES) View.VISIBLE else View.GONE
        screenSobre.visibility = if (tab == BottomTab.SOBRE) View.VISIBLE else View.GONE

        styleBottomNavItem(navTabInicio, navLabelInicio, tab == BottomTab.INICIO)
        styleBottomNavItem(navTabConfig, navLabelConfig, tab == BottomTab.CONFIGURACOES)
        styleBottomNavItem(navTabSobre, navLabelSobre, tab == BottomTab.SOBRE)

        renderTopCategoryStrip()
        refreshUiState()
    }

    private fun styleBottomNavItem(container: LinearLayout, label: TextView, selected: Boolean) {
        container.background = if (selected) {
            pillDrawable("#3F4A41", "#526054", 26)
        } else {
            pillDrawable("#00000000", "#00000000", 26)
        }
        label.setTextColor(
            if (selected) Color.parseColor("#FFFFFF") else Color.parseColor("#94A3B8")
        )
        label.setTypeface(label.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    // =========================================================================
    // TELA 1: INÍCIO (Mockup Circular do Relógio + Welcome Card + Card estilo "Pontuação Energia")
    // =========================================================================
    private fun buildScreenInicio(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            // 1. Mockup Circular do Smartwatch (como na print 2: Phone Battery Complication)
            val watchOuterBezel = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#111513"))
                    setStroke(dp(10), Color.parseColor("#2A322D"))
                }
                setPadding(dp(26), dp(24), dp(26), dp(24))
                layoutParams = LinearLayout.LayoutParams(dp(248), dp(248)).apply {
                    bottomMargin = dp(14)
                }

                val pill1 = LinearLayout(this@MobileSyncConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = pillDrawable("#26312A", "#3B493F", 18)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }

                    val t1 = TextView(this@MobileSyncConfigActivity).apply {
                        text = "⌚ Event Timer & Progress"
                        textSize = 12f
                        setTextColor(Color.parseColor("#F8FAFC"))
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    val s1 = TextView(this@MobileSyncConfigActivity).apply {
                        text = "RangedValue Complication (1h / 2h)"
                        textSize = 10f
                        setTextColor(Color.parseColor("#A7F3D0"))
                    }
                    addView(t1)
                    addView(s1)
                }
                addView(pill1)

                val pill2 = LinearLayout(this@MobileSyncConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = pillDrawable("#2D3831", "#47554B", 18)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )

                    val t2 = TextView(this@MobileSyncConfigActivity).apply {
                        text = "📅 Next 3 Calendar Events"
                        textSize = 12f
                        setTextColor(Color.parseColor("#FFFFFF"))
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    watchBezelPreviewSubtext = TextView(this@MobileSyncConfigActivity).apply {
                        text = "Upcoming Event & Autonomous Cache"
                        textSize = 10f
                        setTextColor(Color.parseColor("#CBD5E1"))
                    }
                    addView(t2)
                    addView(watchBezelPreviewSubtext)
                }
                addView(pill2)
            }
            addView(watchOuterBezel)

            // 2. Linha de Status Bluetooth (Exatamente como nas prints 2 e 3: "Connected • Galaxy Watch Ultra")
            btConnectedBannerInicio = TextView(this@MobileSyncConfigActivity).apply {
                text = "ᛒ  Verificando conexão com Smartwatch..."
                textSize = 14f
                setTextColor(Color.parseColor("#E2E8F0"))
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(16))
            }
            addView(btConnectedBannerInicio)

            // 3. Card "Sua agenda sob controle / Welcome!" (Fusão Samsung Health + Amoled Watchfaces)
            val welcomeCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1D2620", "#2E3B32", 26)
                setPadding(dp(22), dp(20), dp(22), dp(20))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }

                val wTitle = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Sua agenda sob controle!"
                    textSize = 21f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(wTitle)

                val wDesc = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Este app companion envia seus próximos compromissos, temporizador regressivo e barra de progresso (1h/2h) para o seu relógio Wear OS."
                    textSize = 14f
                    setTextColor(Color.parseColor("#D1D5DB"))
                    setPadding(0, dp(8), 0, dp(14))
                }
                addView(wDesc)

                val wInstalledLabel = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Dispositivo Wear conectado / Cache autônomo:"
                    textSize = 13f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(wInstalledLabel)

                installedWearDeviceNameText = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Buscando nós Wear OS..."
                    textSize = 15f
                    setTextColor(Color.parseColor("#BAE6FD"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(4), 0, 0)
                }
                addView(installedWearDeviceNameText)
            }
            addView(welcomeCard)

            // 4. Card estilo "Pontuação energia" do Samsung Health para o Temporizador & Barra de Progresso
            val energyScoreCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#141715", "#262B28", 26)
                setPadding(dp(22), dp(18), dp(22), dp(20))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }

                val eHeader = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Temporizador do Próximo Evento"
                    textSize = 14f
                    setTextColor(Color.parseColor("#E5E7EB"))
                }
                addView(eHeader)

                energyCountdownBigNumber = TextView(this@MobileSyncConfigActivity).apply {
                    text = "--:--"
                    textSize = 36f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(6), 0, dp(2))
                }
                addView(energyCountdownBigNumber)

                energyEventSubtitle = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Ative Calendar Events Sync em Settings"
                    textSize = 14f
                    setTextColor(Color.parseColor("#60A5FA"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(12))
                }
                addView(energyEventSubtitle)

                // Trilho de cápsula da barra de progresso
                val barTrack = LinearLayout(this@MobileSyncConfigActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = pillDrawable("#272C29", "#37413A", 12)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(16)
                    )
                }
                energyProgressFillView = View(this@MobileSyncConfigActivity).apply {
                    background = pillDrawable("#4ADE80", "#4ADE80", 12)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
                }
                barTrack.addView(energyProgressFillView)
                addView(barTrack)

                energyProgressCaption = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Barra RangedValue: 0%"
                    textSize = 11f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setPadding(0, dp(8), 0, 0)
                }
                addView(energyProgressCaption)
            }
            addView(energyScoreCard)

            // 5. Card dos Próximos 3 Eventos enviados para a Janela Grande da Watch Face
            val topThreeCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#141715", "#262B28", 26)
                setPadding(dp(20), dp(18), dp(20), dp(18))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                val topThreeHeader = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Próximos 3 Eventos (Janela Grande Watch Face)"
                    textSize = 14f
                    setTextColor(Color.parseColor("#E5E7EB"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(10))
                }
                addView(topThreeHeader)

                upcomingThreeListContainer = LinearLayout(this@MobileSyncConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
                addView(upcomingThreeListContainer)
            }
            addView(topThreeCard)
        }
    }

    // =========================================================================
    // TELA 2: SETTINGS (Idêntica à print 3 do Amoled Watchfaces + Leitura e Frequência)
    // =========================================================================
    private fun buildScreenConfiguracoes(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val settingsHeaderTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = "Settings"
                textSize = 24f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(8))
            }
            addView(settingsHeaderTitle)

            btConnectedBannerConfig = TextView(this@MobileSyncConfigActivity).apply {
                text = "ᛒ  Connected • Smartwatch"
                textSize = 14f
                setTextColor(Color.parseColor("#E2E8F0"))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(16))
            }
            addView(btConnectedBannerConfig)

            // Container Principal Arredondado (igual ao card único de Settings da print 3)
            val mainSettingsCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1A1C1E", "#2C3034", 26)
                setPadding(dp(20), dp(20), dp(20), dp(20))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }
            }

            // ITEM 1: Background Service (Important - Unristricted Battery & Service Permission required)
            val bgServiceRow = createAmoledSettingToggleRow(
                title = "Background Service",
                subtitle = "Important - Background & Notifications Access required",
                onSubtitleCreated = { subtitleBackgroundService = it },
                onSwitchCreated = { sw ->
                    switchBackgroundService = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        onToggleBackgroundService(isChecked)
                    }
                }
            )
            mainSettingsCard.addView(bgServiceRow)

            // Botão pílula para autorizar execução irrestrita em 2º plano pelo usuário
            val btnBatteryPermissionPill = createOutlineActionPillButton(
                "⚡  Autorizar Permissão de 2º Plano (Sem Restrição)"
            ) {
                requestUnrestrictedBatteryPermissionByUser()
            }
            mainSettingsCard.addView(btnBatteryPermissionPill)

            // ITEM 2: Notifications Sync
            val notifSyncRow = createAmoledSettingToggleRow(
                title = "Notifications Sync",
                subtitle = "Only for Phone Notifications & Complication Alerts",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchNotificationsSync = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        onToggleNotificationsSync(isChecked)
                    }
                }
            )
            mainSettingsCard.addView(notifSyncRow)

            // ITEM 3: Media Playback Sync
            val mediaSyncRow = createAmoledSettingToggleRow(
                title = "Media Playback Sync",
                subtitle = "Now Playing Complication support",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchMediaPlaybackSync = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        prefs.setMediaPlaybackSyncEnabled(isChecked)
                    }
                }
            )
            mainSettingsCard.addView(mediaSyncRow)

            // ITEM 4: Calendar Events Sync (Upcoming Event & Event Timer Complication) + Botão "📅 Synced calendars"
            val calSyncRow = createAmoledSettingToggleRow(
                title = "Calendar Events Sync",
                subtitle = "Upcoming Event & Event Timer Complication",
                onSubtitleCreated = { subtitleCalendarEventsSync = it },
                onSwitchCreated = { sw ->
                    switchCalendarEventsSync = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        onToggleCalendarEventsSync(isChecked)
                    }
                }
            )
            mainSettingsCard.addView(calSyncRow)

            btnSyncedCalendarsPill = createOutlineActionPillButton("📅   Synced calendars") {
                openSyncedCalendarsDialog()
            }
            mainSettingsCard.addView(btnSyncedCalendarsPill)

            // ITEM 5: Alteração nos Compromissos (Ler automaticamente ao criar/editar/apagar)
            val autoChangeRow = createAmoledSettingToggleRow(
                title = "Instant Calendar Change Sync",
                subtitle = "Lê os compromissos automaticamente sempre que houver alteração na Agenda (ContentObserver)",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchAutoReadOnChange = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        prefs.setAutoSyncOnCalendarChangeEnabled(isChecked)
                        if (prefs.isBackgroundServiceEnabled() &&
                            CalendarSyncEngine.hasCalendarPermission(this@MobileSyncConfigActivity)
                        ) {
                            CalendarSyncForegroundService.startOrUpdate(
                                this@MobileSyncConfigActivity,
                                "Alteração de Escuta Instantânea"
                            )
                        }
                        refreshUiState()
                    }
                }
            )
            mainSettingsCard.addView(autoChangeRow)

            addView(mainSettingsCard)

            // Card complementar de Frequência de Leitura, Barra de Progresso (1h/2h) e Horizonte Autônomo (2d a 7d)
            val advancedCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1A1C1E", "#2C3034", 26)
                setPadding(dp(20), dp(18), dp(20), dp(20))
            }

            val freqLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Frequência de Leitura Periódica do Calendário"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
            }
            advancedCard.addView(freqLabel)

            val freqSub = TextView(this@MobileSyncConfigActivity).apply {
                text = "Intervalo do WorkManager além da leitura instantânea por alteração:"
                textSize = 12f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, dp(2), 0, dp(8))
            }
            advancedCard.addView(freqSub)

            rowFrequencyChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(16))
            }
            advancedCard.addView(rowFrequencyChips)

            val progLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Janela da Barra de Progresso (Event Timer)"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
            }
            advancedCard.addView(progLabel)

            val progSub = TextView(this@MobileSyncConfigActivity).apply {
                text = "Antecedência com que a barra da Complication começa a encher:"
                textSize = 12f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, dp(2), 0, dp(8))
            }
            advancedCard.addView(progSub)

            rowProgressWindowChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(16))
            }
            advancedCard.addView(rowProgressWindowChips)

            val horLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Horizonte Autônomo Offline no Relógio"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
            }
            advancedCard.addView(horLabel)

            val horSub = TextView(this@MobileSyncConfigActivity).apply {
                text = "Quantidade de dias futuros salvos no relógio caso fique sem conexão:"
                textSize = 12f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, dp(2), 0, dp(8))
            }
            advancedCard.addView(horSub)

            rowHorizonDaysChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            advancedCard.addView(rowHorizonDaysChips)

            addView(advancedCard)
        }
    }

    // =========================================================================
    // TELA 3: SOBRE / DIAGNÓSTICO DE LEITURA DA AGENDA
    // =========================================================================
    private fun buildScreenSobre(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val card = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1A1C1E", "#2C3034", 26)
                setPadding(dp(22), dp(20), dp(22), dp(20))
            }

            val t = TextView(this@MobileSyncConfigActivity).apply {
                text = "Como Funciona a Leitura e Sincronização"
                textSize = 18f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            }
            card.addView(t)

            val body = TextView(this@MobileSyncConfigActivity).apply {
                text = "• Permissões 100% controladas pelo usuário: o app só solicita acesso ao Calendário ou Notificações quando você ativa a chave correspondente em Settings.\n\n" +
                    "• Como a leitura do calendário é feita: consultamos a tabela CalendarContract.Instances dentro do horizonte configurado (2, 3, 5 ou 7 dias), expandindo eventos recorrentes automaticamente e filtrando apenas os calendários escolhidos no botão 'Synced calendars'.\n\n" +
                    "• Se houver alteração nos compromissos: com 'Instant Calendar Change Sync' ligado, um ContentObserver e o CalendarChangeReceiver detectam qualquer novo compromisso, mudança de horário ou exclusão na agenda e disparam a leitura imediatamente.\n\n" +
                    "• Modo Autônomo no Smartwatch: o relógio armazena o pacote de dias futuros em banco SQLite local e avança para o próximo evento sozinho usando AlarmManager mesmo desconectado do Bluetooth."
                textSize = 13f
                setTextColor(Color.parseColor("#D1D5DB"))
            }
            card.addView(body)
            addView(card)
        }
    }

    private fun createAmoledSettingToggleRow(
        title: String,
        subtitle: String,
        onSubtitleCreated: (TextView) -> Unit,
        onSwitchCreated: (Switch) -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(12))

            val textCol = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(12)
                }
            }

            val titleTv = TextView(this@MobileSyncConfigActivity).apply {
                text = title
                textSize = 17f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
            }
            val subTv = TextView(this@MobileSyncConfigActivity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, dp(2), 0, 0)
            }
            onSubtitleCreated(subTv)
            textCol.addView(titleTv)
            textCol.addView(subTv)
            addView(textCol)

            val sw = Switch(this@MobileSyncConfigActivity)
            onSwitchCreated(sw)
            addView(sw)
        }
    }

    private fun createOutlineActionPillButton(
        label: String,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.parseColor("#E2E8F0"))
            setTypeface(typeface, Typeface.BOLD)
            background = pillDrawable("#1A1C1E", "#4B5563", 26, 1)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(46)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(12)
            }
            setOnClickListener { onClick() }
        }
    }

    // =========================================================================
    // LÓGICA DE PERMISSÕES ACIONADAS PELO USUÁRIO E SELETOR "SYNCED CALENDARS"
    // =========================================================================
    private fun onToggleBackgroundService(enable: Boolean) {
        if (enable) {
            // Se Android 13+, solicita permissão de notificação para o Foreground Service se ainda não tiver
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !CalendarSyncEngine.hasNotificationPermission(this)
            ) {
                requestPermissions(
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIFICATION_FOR_BG_SERVICE
                )
            }
            prefs.setBackgroundServiceEnabled(true)
            CalendarSyncWorker.schedulePeriodicSync(this, prefs.getSyncIntervalMinutes())
            try {
                CalendarSyncForegroundService.startOrUpdate(this, "Background Service Ativado")
            } catch (_: Exception) {
            }
            Toast.makeText(this, "Background Service ativado!", Toast.LENGTH_SHORT).show()
        } else {
            prefs.setBackgroundServiceEnabled(false)
            CalendarSyncWorker.cancelPeriodicSync(this)
            CalendarSyncForegroundService.stop(this)
            Toast.makeText(this, "Background Service desativado.", Toast.LENGTH_SHORT).show()
        }
        refreshUiState()
    }

    private fun onToggleNotificationsSync(enable: Boolean) {
        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !CalendarSyncEngine.hasNotificationPermission(this)
        ) {
            suppressSwitchEvents = true
            switchNotificationsSync.isChecked = false
            suppressSwitchEvents = false
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATION_SYNC
            )
            return
        }
        prefs.setNotificationsSyncEnabled(enable)
        refreshUiState()
    }

    private fun onToggleCalendarEventsSync(enable: Boolean) {
        if (enable && !CalendarSyncEngine.hasCalendarPermission(this)) {
            suppressSwitchEvents = true
            switchCalendarEventsSync.isChecked = false
            suppressSwitchEvents = false
            // Solicita READ_CALENDAR somente porque o usuário acionou "Calendar Events Sync"
            requestPermissions(
                arrayOf(Manifest.permission.READ_CALENDAR),
                REQ_CALENDAR_FOR_SYNC
            )
            return
        }

        prefs.setCalendarEventsSyncEnabled(enable)
        if (enable) {
            if (prefs.getSelectedCalendarIds().isEmpty()) {
                // Abre imediatamente o seletor de calendários para o usuário escolher quais sincronizar
                openSyncedCalendarsDialog()
            } else {
                triggerManualSyncFromFab()
            }
        }
        refreshUiState()
    }

    /**
     * Abre o diálogo nativo multi-seleção "Synced calendars" (exatamente como no Amoled Watchfaces)
     * listando todos os calendários do aparelho para o usuário marcar quais deseja sincronizar.
     */
    private fun openSyncedCalendarsDialog() {
        if (!CalendarSyncEngine.hasCalendarPermission(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CALENDAR),
                REQ_CALENDAR_FOR_DIALOG
            )
            return
        }

        uiScope.launch {
            val calendars = withContext(Dispatchers.IO) {
                CalendarSyncEngine.loadDeviceCalendars(this@MobileSyncConfigActivity)
            }
            cachedDeviceCalendars = calendars

            if (calendars.isEmpty()) {
                Toast.makeText(
                    this@MobileSyncConfigActivity,
                    "Nenhum calendário encontrado nas contas do aparelho.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val currentSelected = prefs.getSelectedCalendarIds().toMutableSet()
            val labels = calendars.map { cal ->
                "${cal.displayName} (${cal.accountName}) • ${cal.upcomingEventsCount} ev."
            }.toTypedArray()
            val checkedItems = BooleanArray(calendars.size) { idx ->
                currentSelected.contains(calendars[idx].id)
            }

            AlertDialog.Builder(this@MobileSyncConfigActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Synced calendars")
                .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                    val calId = calendars[which].id
                    if (isChecked) {
                        currentSelected.add(calId)
                    } else {
                        currentSelected.remove(calId)
                    }
                }
                .setPositiveButton("Salvar e Sincronizar") { _, _ ->
                    prefs.setSelectedCalendarIds(currentSelected)
                    if (currentSelected.isNotEmpty()) {
                        prefs.setCalendarEventsSyncEnabled(true)
                    }
                    triggerManualSyncFromFab()
                }
                .setNeutralButton("Marcar Todos") { _, _ ->
                    val allIds = calendars.map { it.id }.toSet()
                    prefs.setSelectedCalendarIds(allIds)
                    prefs.setCalendarEventsSyncEnabled(true)
                    triggerManualSyncFromFab()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun requestUnrestrictedBatteryPermissionByUser() {
        try {
            if (!CalendarSyncEngine.isIgnoringBatteryOptimizations(this)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(
                    this,
                    "Execução em 2º plano sem restrição já está autorizada!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (_: Exception) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            startActivity(intent)
        }
    }

    private fun triggerManualSyncFromFab() {
        if (!CalendarSyncEngine.hasCalendarPermission(this)) {
            Toast.makeText(
                this,
                "Ative 'Calendar Events Sync' em Settings para autorizar a leitura do calendário.",
                Toast.LENGTH_LONG
            ).show()
            switchTab(BottomTab.CONFIGURACOES)
            return
        }

        if (prefs.getSelectedCalendarIds().isEmpty()) {
            openSyncedCalendarsDialog()
            return
        }

        Toast.makeText(this, "Lendo compromissos e enviando ao relógio...", Toast.LENGTH_SHORT).show()
        uiScope.launch {
            withContext(Dispatchers.IO) {
                CalendarSyncEngine.performFullSyncToWatch(
                    this@MobileSyncConfigActivity,
                    "Sincronização Manual (Botão Relógio)"
                )
            }
            refreshUiState()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED

        when (requestCode) {
            REQ_CALENDAR_FOR_SYNC -> {
                if (granted) {
                    prefs.setCalendarEventsSyncEnabled(true)
                    openSyncedCalendarsDialog()
                } else {
                    Toast.makeText(this, "Permissão de Calendário negada pelo usuário.", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_CALENDAR_FOR_DIALOG -> {
                if (granted) {
                    openSyncedCalendarsDialog()
                }
            }
            REQ_NOTIFICATION_SYNC -> {
                if (granted) {
                    prefs.setNotificationsSyncEnabled(true)
                }
            }
        }
        refreshUiState()
    }

    private fun refreshUiState() {
        val hasCalPerm = CalendarSyncEngine.hasCalendarPermission(this)
        val isBgUnrestricted = CalendarSyncEngine.isIgnoringBatteryOptimizations(this)
        val selectedCalsCount = prefs.getSelectedCalendarIds().size

        suppressSwitchEvents = true
        switchBackgroundService.isChecked = prefs.isBackgroundServiceEnabled()
        switchNotificationsSync.isChecked = prefs.isNotificationsSyncEnabled()
        switchMediaPlaybackSync.isChecked = prefs.isMediaPlaybackSyncEnabled()
        switchCalendarEventsSync.isChecked = prefs.isCalendarEventsSyncEnabled() && hasCalPerm
        switchAutoReadOnChange.isChecked = prefs.isAutoSyncOnCalendarChangeEnabled()
        suppressSwitchEvents = false

        subtitleBackgroundService.text = if (prefs.isBackgroundServiceEnabled()) {
            if (isBgUnrestricted) "Ativo em 2º plano (Sem restrição de bateria)"
            else "Ativo • Recomendado autorizar bateria irrestrita abaixo"
        } else {
            "Important - Notifications & Background Access required"
        }

        subtitleCalendarEventsSync.text = if (hasCalPerm) {
            "Upcoming Event & Event Timer ($selectedCalsCount calendário(s) sincronizado(s))"
        } else {
            "Upcoming Event & Event Timer Complication (Requer Permissão)"
        }

        btnSyncedCalendarsPill.text = if (selectedCalsCount > 0) {
            "📅   Synced calendars ($selectedCalsCount)"
        } else {
            "📅   Synced calendars"
        }

        renderChipsRows()

        uiScope.launch {
            val events = withContext(Dispatchers.IO) {
                if (hasCalPerm) CalendarSyncEngine.queryHorizonEvents(this@MobileSyncConfigActivity)
                else emptyList()
            }
            val nodes = withContext(Dispatchers.IO) {
                try {
                    Wearable.getNodeClient(this@MobileSyncConfigActivity).connectedNodes.await()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            cachedHorizonEvents = events

            val connectedLabel = if (nodes.isNotEmpty()) {
                "ᛒ  Connected • ${nodes.joinToString { it.displayName }}"
            } else {
                "ᛒ  Standby • Galaxy Watch / Wear OS (Cache Autônomo ${prefs.getHorizonDays()}d)"
            }
            btConnectedBannerInicio.text = connectedLabel
            btConnectedBannerConfig.text = connectedLabel

            installedWearDeviceNameText.text = if (nodes.isNotEmpty()) {
                nodes.joinToString("\n") { "${it.displayName} (${it.id.take(4).uppercase()})" }
            } else {
                "Aguardando conexão Bluetooth • Cache de ${prefs.getHorizonDays()} dias configurado"
            }

            watchBezelPreviewSubtext.text =
                "${events.size} eventos no horizonte (${prefs.getHorizonDays()}d)"

            populateTopThreeCards(events)
            updateLiveCountdownAndBar()
        }
    }

    private fun renderChipsRows() {
        rowFrequencyChips.removeAllViews()
        val curFreq = prefs.getSyncIntervalMinutes()
        listOf(15L to "15m", 30L to "30m", 60L to "1h", 120L to "2h").forEach { (mins, label) ->
            val selected = (curFreq == mins)
            rowFrequencyChips.addView(
                createSelectableChip(label, selected) {
                    prefs.setSyncIntervalMinutes(mins)
                    if (prefs.isBackgroundServiceEnabled()) {
                        CalendarSyncWorker.schedulePeriodicSync(this, mins)
                    }
                    refreshUiState()
                }
            )
        }

        rowProgressWindowChips.removeAllViews()
        val curWin = prefs.getProgressWindowMinutes()
        listOf(30 to "30m", 60 to "1 hora", 120 to "2 horas", 180 to "3 horas").forEach { (mins, label) ->
            val selected = (curWin == mins)
            rowProgressWindowChips.addView(
                createSelectableChip(label, selected) {
                    prefs.setProgressWindowMinutes(mins)
                    if (prefs.isBackgroundServiceEnabled()) {
                        CalendarSyncWorker.enqueueImmediateSync(this, "Janela Barra ${mins}m")
                    }
                    refreshUiState()
                }
            )
        }

        rowHorizonDaysChips.removeAllViews()
        val curHor = prefs.getHorizonDays()
        listOf(2 to "2 dias", 3 to "3 dias", 5 to "5 dias", 7 to "7 dias").forEach { (days, label) ->
            val selected = (curHor == days)
            rowHorizonDaysChips.addView(
                createSelectableChip(label, selected) {
                    prefs.setHorizonDays(days)
                    if (prefs.isBackgroundServiceEnabled()) {
                        CalendarSyncWorker.enqueueImmediateSync(this, "Horizonte ${days}d")
                    }
                    refreshUiState()
                }
            )
        }
    }

    private fun createSelectableChip(
        label: String,
        selected: Boolean,
        onClick: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(if (selected) Color.parseColor("#111827") else Color.parseColor("#E5E7EB"))
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            background = pillDrawable(
                if (selected) "#BAE6FD" else "#262B28",
                if (selected) "#7DD3FC" else "#37413A",
                20
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                rightMargin = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun populateTopThreeCards(events: List<LocalCalendarEvent>) {
        upcomingThreeListContainer.removeAllViews()
        if (!CalendarSyncEngine.hasCalendarPermission(this) || !prefs.isCalendarEventsSyncEnabled()) {
            val tv = TextView(this).apply {
                text = "Vá em 'Settings' -> ative 'Calendar Events Sync' e escolha seus calendários em 'Synced calendars'."
                textSize = 13f
                setTextColor(Color.parseColor("#9CA3AF"))
            }
            upcomingThreeListContainer.addView(tv)
            return
        }

        if (events.isEmpty()) {
            val tv = TextView(this).apply {
                text = "Nenhum evento encontrado nos próximos ${prefs.getHorizonDays()} dias para os calendários selecionados."
                textSize = 13f
                setTextColor(Color.parseColor("#9CA3AF"))
            }
            upcomingThreeListContainer.addView(tv)
            return
        }

        val sdf = SimpleDateFormat("EEE dd/MM • HH:mm", Locale.getDefault())
        events.take(3).forEachIndexed { idx, ev ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1E2420", "#2E3832", 18)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }

            val top = TextView(this).apply {
                text = "#${idx + 1} • ${sdf.format(Date(ev.startEpochMs))} (${ev.calendarName})"
                textSize = 11f
                setTextColor(Color.parseColor("#4ADE80"))
                typeface = Typeface.MONOSPACE
            }
            val title = TextView(this).apply {
                text = ev.title
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
            }
            row.addView(top)
            row.addView(title)
            upcomingThreeListContainer.addView(row)
        }
    }

    private fun startLiveCountdownTicker() {
        tickerJob?.cancel()
        tickerJob = uiScope.launch {
            while (isActive) {
                updateLiveCountdownAndBar()
                delay(1000L)
            }
        }
    }

    private fun updateLiveCountdownAndBar() {
        val nowMs = System.currentTimeMillis()
        val nextEv = cachedHorizonEvents.firstOrNull { it.startEpochMs > nowMs }

        if (nextEv == null) {
            energyCountdownBigNumber.text = "Livre"
            energyEventSubtitle.text = "Sem próximo compromisso no horizonte"
            energyProgressCaption.text =
                "Barra RangedValue: 0% • Janela configurada: ${prefs.getProgressWindowMinutes()}m antes"
            energyProgressFillView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
            return
        }

        val remainingMs = (nextEv.startEpochMs - nowMs).coerceAtLeast(0L)
        val totalSec = remainingMs / 1000L
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L

        energyCountdownBigNumber.text = when {
            days > 0 -> String.format(Locale.getDefault(), "%dd %02dh %02dm", days, hours, mins)
            hours > 0 -> String.format(Locale.getDefault(), "%02dh %02dm %02ds", hours, mins, secs)
            else -> String.format(Locale.getDefault(), "%02dm %02ds", mins, secs)
        }

        val startHHMM = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextEv.startEpochMs))
        energyEventSubtitle.text = "Início às $startHHMM • ${nextEv.title}"

        val windowMs = TimeUnit.MINUTES.toMillis(prefs.getProgressWindowMinutes().toLong()).toFloat()
        val ratio = if (remainingMs >= windowMs) {
            0f
        } else {
            (1f - (remainingMs.toFloat() / windowMs)).coerceIn(0f, 1f)
        }
        val pct = (ratio * 100f).toInt()
        energyProgressCaption.text =
            "Barra de Progresso no Relógio: $pct% (Inicia ${prefs.getProgressWindowMinutes()} min antes)"

        val parentW = (energyProgressFillView.parent as? View)?.width ?: 0
        if (parentW > 0) {
            val fillW = (parentW * ratio).toInt().coerceAtLeast(if (ratio > 0f) dp(8) else 0)
            energyProgressFillView.layoutParams = LinearLayout.LayoutParams(
                fillW,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    companion object {
        private const val REQ_CALENDAR_FOR_SYNC = 3001
        private const val REQ_CALENDAR_FOR_DIALOG = 3002
        private const val REQ_NOTIFICATION_FOR_BG_SERVICE = 3003
        private const val REQ_NOTIFICATION_SYNC = 3004
    }
}
