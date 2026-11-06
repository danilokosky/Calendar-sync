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
import com.chronowear.mobile.sync.SyncPreferences
import com.chronowear.mobile.sync.WearConnectionReport
import com.chronowear.mobile.sync.WearConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MobileSyncConfigActivity : Activity() {

    private enum class BottomTab {
        HOME,
        SETTINGS,
        ABOUT
    }

    private lateinit var prefs: SyncPreferences
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var activeTab: BottomTab = BottomTab.HOME
    private var suppressSwitchEvents = false

    // Containers das 3 abas únicas do rodapé (Home, Settings, About)
    private lateinit var screenHome: LinearLayout
    private lateinit var screenSettings: LinearLayout
    private lateinit var screenAbout: LinearLayout

    // Indicador de conexão Bluetooth + App instalado no relógio
    private lateinit var btStatusBannerHome: TextView
    private lateinit var btStatusBannerSettings: TextView

    // Elementos essenciais da tela Home
    private lateinit var watchBezelSubtext: TextView
    private lateinit var welcomeDeviceStatusTitle: TextView
    private lateinit var welcomeDeviceStatusDetail: TextView
    private lateinit var nextEventSummaryText: TextView

    // Elementos da tela Settings (padrão Amoled Watchfaces)
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

    // Rodapé único com exatamente 3 opções (Home, Settings, About) — sem botão de relógio extra
    private lateinit var navTabHome: LinearLayout
    private lateinit var navTabSettings: LinearLayout
    private lateinit var navTabAbout: LinearLayout
    private lateinit var navIconHome: TextView
    private lateinit var navIconSettings: TextView
    private lateinit var navIconAbout: TextView
    private lateinit var navLabelHome: TextView
    private lateinit var navLabelSettings: TextView
    private lateinit var navLabelAbout: TextView

    private var cachedHorizonEvents: List<LocalCalendarEvent> = emptyList()

    private val syncCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUiState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SyncPreferences(this)
        window.statusBarColor = Color.parseColor("#111315")
        window.navigationBarColor = Color.parseColor("#181C1F")

        buildCleanUi()
        // Nenhuma permissão é solicitada automaticamente na abertura.
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
        startLiveTicker()
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
        radiusDp: Int = 26,
        strokeWidthDp: Int = 1
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(strokeWidthDp), Color.parseColor(strokeHex))
        }
    }

    private fun buildCleanUi() {
        val rootFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#111315"))
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
            setPadding(dp(18), dp(20), dp(18), dp(96))
        }

        // Sem barra redundante de categorias no topo — apenas as 3 telas controladas pelo rodapé
        screenHome = buildScreenHome()
        screenSettings = buildScreenSettings()
        screenAbout = buildScreenAbout()

        mainColumn.addView(screenHome)
        mainColumn.addView(screenSettings)
        mainColumn.addView(screenAbout)

        scrollView.addView(mainColumn)
        rootFrame.addView(scrollView)

        // RODAPÉ ÚNICO COM APENAS AS 3 OPÇÕES: Home, Settings, About (sem ícone de relógio)
        val bottomNavBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#181C1F"))
            setPadding(dp(12), dp(8), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(72),
                Gravity.BOTTOM
            )
        }

        navTabHome = createBottomNavTab("⌂", "Home") {
            switchTab(BottomTab.HOME)
        }.also {
            navIconHome = it.getChildAt(0) as TextView
            navLabelHome = it.getChildAt(1) as TextView
        }

        navTabSettings = createBottomNavTab("⚙", "Settings") {
            switchTab(BottomTab.SETTINGS)
        }.also {
            navIconSettings = it.getChildAt(0) as TextView
            navLabelSettings = it.getChildAt(1) as TextView
        }

        navTabAbout = createBottomNavTab("ⓘ", "About") {
            switchTab(BottomTab.ABOUT)
        }.also {
            navIconAbout = it.getChildAt(0) as TextView
            navLabelAbout = it.getChildAt(1) as TextView
        }

        bottomNavBar.addView(navTabHome)
        bottomNavBar.addView(navTabSettings)
        bottomNavBar.addView(navTabAbout)

        rootFrame.addView(bottomNavBar)
        setContentView(rootFrame)

        switchTab(BottomTab.HOME)
    }

    private fun createBottomNavTab(
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
                setPadding(dp(20), dp(4), dp(20), dp(4))
                setTextColor(Color.parseColor("#E2E8F0"))
            }
            val labelTv = TextView(this@MobileSyncConfigActivity).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, 0)
                setTextColor(Color.parseColor("#94A3B8"))
            }
            addView(iconTv)
            addView(labelTv)
        }
    }

    private fun switchTab(tab: BottomTab) {
        activeTab = tab
        screenHome.visibility = if (tab == BottomTab.HOME) View.VISIBLE else View.GONE
        screenSettings.visibility = if (tab == BottomTab.SETTINGS) View.VISIBLE else View.GONE
        screenAbout.visibility = if (tab == BottomTab.ABOUT) View.VISIBLE else View.GONE

        styleBottomTab(navIconHome, navLabelHome, tab == BottomTab.HOME)
        styleBottomTab(navIconSettings, navLabelSettings, tab == BottomTab.SETTINGS)
        styleBottomTab(navIconAbout, navLabelAbout, tab == BottomTab.ABOUT)

        refreshUiState()
    }

    private fun styleBottomTab(iconView: TextView, labelView: TextView, selected: Boolean) {
        iconView.background = if (selected) {
            pillDrawable("#153843", "#1E4E5D", 18)
        } else {
            pillDrawable("#00000000", "#00000000", 18)
        }
        iconView.setTextColor(
            if (selected) Color.parseColor("#BAE6FD") else Color.parseColor("#CBD5E1")
        )
        labelView.setTextColor(
            if (selected) Color.parseColor("#FFFFFF") else Color.parseColor("#9CA3AF")
        )
        labelView.setTypeface(labelView.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    // =========================================================================
    // ABA 1: HOME (Essencial e Informativa, estilo Amoled Watchfaces)
    // =========================================================================
    private fun buildScreenHome(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            val titleTv = TextView(this@MobileSyncConfigActivity).apply {
                text = "Calendar Sync Complication"
                textSize = 22f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(18))
            }
            addView(titleTv)

            // Mockup Circular do Relógio no topo
            val watchBezel = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#141718"))
                    setStroke(dp(10), Color.parseColor("#2A2F33"))
                }
                setPadding(dp(26), dp(24), dp(26), dp(24))
                layoutParams = LinearLayout.LayoutParams(dp(236), dp(236)).apply {
                    bottomMargin = dp(14)
                }

                val slot1 = LinearLayout(this@MobileSyncConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = pillDrawable("#2B3330", "#3D4844", 18)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(8) }

                    val t1 = TextView(this@MobileSyncConfigActivity).apply {
                        text = "Upcoming Event"
                        textSize = 12f
                        setTextColor(Color.parseColor("#FFFFFF"))
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    val s1 = TextView(this@MobileSyncConfigActivity).apply {
                        text = "Calendar Sync Complication"
                        textSize = 10f
                        setTextColor(Color.parseColor("#CBD5E1"))
                    }
                    addView(t1)
                    addView(s1)
                }
                addView(slot1)

                val slot2 = LinearLayout(this@MobileSyncConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = pillDrawable("#2B3330", "#3D4844", 18)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )

                    val t2 = TextView(this@MobileSyncConfigActivity).apply {
                        text = "Event Timer (1h / 2h)"
                        textSize = 12f
                        setTextColor(Color.parseColor("#FFFFFF"))
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    watchBezelSubtext = TextView(this@MobileSyncConfigActivity).apply {
                        text = "Calendar Sync Complication"
                        textSize = 10f
                        setTextColor(Color.parseColor("#CBD5E1"))
                    }
                    addView(t2)
                    addView(watchBezelSubtext)
                }
                addView(slot2)
            }
            addView(watchBezel)

            // Informativo de conexão: só dá positivo se o Wearable estiver conectado E com o app instalado
            btStatusBannerHome = TextView(this@MobileSyncConfigActivity).apply {
                text = "ᛒ  Verificando conexão com o relógio..."
                textSize = 14f
                setTextColor(Color.parseColor("#E2E8F0"))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(18))
            }
            addView(btStatusBannerHome)

            // Cartão Welcome! limpo e sem botões redundantes
            val welcomeCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1B1D21", "#2A2E35", 24)
                setPadding(dp(22), dp(20), dp(22), dp(20))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                val wTitle = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Welcome!"
                    textSize = 22f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(wTitle)

                val wDesc = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Este app companion envia seus próximos compromissos e contagem regressiva para as Complications do seu relógio Wear OS."
                    textSize = 15f
                    setTextColor(Color.parseColor("#D1D5DB"))
                    setPadding(0, dp(10), 0, dp(18))
                }
                addView(wDesc)

                welcomeDeviceStatusTitle = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Status do aplicativo no relógio:"
                    textSize = 15f
                    setTextColor(Color.parseColor("#E5E7EB"))
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(welcomeDeviceStatusTitle)

                welcomeDeviceStatusDetail = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Verificando dispositivos Wear OS..."
                    textSize = 15f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(6), 0, dp(14))
                }
                addView(welcomeDeviceStatusDetail)

                nextEventSummaryText = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Próximo evento: --"
                    textSize = 13f
                    setTextColor(Color.parseColor("#94A3B8"))
                }
                addView(nextEventSummaryText)
            }
            addView(welcomeCard)
        }
    }

    // =========================================================================
    // ABA 2: SETTINGS (Idêntica à estrutura Amoled Watchfaces)
    // =========================================================================
    private fun buildScreenSettings(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val settingsHeaderTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = "Settings"
                textSize = 23f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(10))
            }
            addView(settingsHeaderTitle)

            btStatusBannerSettings = TextView(this@MobileSyncConfigActivity).apply {
                text = "ᛒ  Verificando conexão..."
                textSize = 14f
                setTextColor(Color.parseColor("#E2E8F0"))
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), dp(16))
            }
            addView(btStatusBannerSettings)

            val mainSettingsCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1B1D21", "#2A2E35", 24)
                setPadding(dp(20), dp(18), dp(20), dp(18))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }
            }

            // 1. Background Service
            val bgServiceRow = createSettingToggleRow(
                title = "Background Service",
                subtitle = "Important - Notifications Access required",
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

            val btnBatteryPermissionPill = createOutlineActionPillButton(
                "⚡  Autorizar 2º Plano Sem Restrição"
            ) {
                requestUnrestrictedBatteryPermissionByUser()
            }
            mainSettingsCard.addView(btnBatteryPermissionPill)

            // 2. Notifications Sync
            val notifSyncRow = createSettingToggleRow(
                title = "Notifications Sync",
                subtitle = "Only for Phone Notifications Complication",
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

            // 3. Media Playback Sync
            val mediaSyncRow = createSettingToggleRow(
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

            // 4. Calendar Events Sync + Synced calendars
            val calSyncRow = createSettingToggleRow(
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

            // 5. Instant Calendar Change Sync
            val autoChangeRow = createSettingToggleRow(
                title = "Instant Calendar Change Sync",
                subtitle = "Relê a agenda automaticamente quando um compromisso é criado ou alterado",
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

            // Configurações de Frequência, Janela da Barra e Horizonte
            val timingCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1B1D21", "#2A2E35", 24)
                setPadding(dp(20), dp(18), dp(20), dp(18))
            }

            val freqLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Frequência de Leitura do Calendário"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            }
            timingCard.addView(freqLabel)

            rowFrequencyChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(14))
            }
            timingCard.addView(rowFrequencyChips)

            val progLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Barra de Progresso (Event Timer)"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            }
            timingCard.addView(progLabel)

            rowProgressWindowChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(14))
            }
            timingCard.addView(rowProgressWindowChips)

            val horLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Horizonte de Eventos Sincronizados"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            }
            timingCard.addView(horLabel)

            rowHorizonDaysChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            timingCard.addView(rowHorizonDaysChips)

            addView(timingCard)
        }
    }

    // =========================================================================
    // ABA 3: ABOUT (Informações essenciais e botão de sincronização manual)
    // =========================================================================
    private fun buildScreenAbout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val aboutHeaderTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = "About"
                textSize = 23f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(16))
            }
            addView(aboutHeaderTitle)

            val card = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1B1D21", "#2A2E35", 24)
                setPadding(dp(22), dp(20), dp(22), dp(20))
            }

            val body = TextView(this@MobileSyncConfigActivity).apply {
                text = "ChronoWear Calendar Sync\n\n" +
                    "• Conexão Verificada: O status só indica 'Connected' quando o relógio está conectado E o aplicativo da versão Wear OS está instalado no relógio.\n\n" +
                    "• Leitura da Agenda: Lê CalendarContract.Instances nos calendários selecionados em 'Synced calendars' na frequência configurada e também instantaneamente quando há alterações na agenda."
                textSize = 14f
                setTextColor(Color.parseColor("#D1D5DB"))
                setPadding(0, 0, 0, dp(16))
            }
            card.addView(body)

            val manualSyncBtn = createOutlineActionPillButton("🔄   Sincronizar Agora com o Relógio") {
                triggerManualSync()
            }
            card.addView(manualSyncBtn)

            addView(card)
        }
    }

    private fun createSettingToggleRow(
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
            background = pillDrawable("#1B1D21", "#4B5563", 24, 1)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun onToggleBackgroundService(enable: Boolean) {
        if (enable) {
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
        } else {
            prefs.setBackgroundServiceEnabled(false)
            CalendarSyncWorker.cancelPeriodicSync(this)
            CalendarSyncForegroundService.stop(this)
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
            requestPermissions(
                arrayOf(Manifest.permission.READ_CALENDAR),
                REQ_CALENDAR_FOR_SYNC
            )
            return
        }

        prefs.setCalendarEventsSyncEnabled(enable)
        if (enable) {
            if (prefs.getSelectedCalendarIds().isEmpty()) {
                openSyncedCalendarsDialog()
            } else {
                triggerManualSync()
            }
        }
        refreshUiState()
    }

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

            if (calendars.isEmpty()) {
                Toast.makeText(
                    this@MobileSyncConfigActivity,
                    "Nenhum calendário encontrado no dispositivo.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val currentSelected = prefs.getSelectedCalendarIds().toMutableSet()
            val labels = calendars.map { cal ->
                "${cal.displayName} (${cal.accountName})"
            }.toTypedArray()
            val checkedItems = BooleanArray(calendars.size) { idx ->
                currentSelected.contains(calendars[idx].id)
            }

            AlertDialog.Builder(this@MobileSyncConfigActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Synced calendars")
                .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                    val calId = calendars[which].id
                    if (isChecked) currentSelected.add(calId) else currentSelected.remove(calId)
                }
                .setPositiveButton("Salvar") { _, _ ->
                    prefs.setSelectedCalendarIds(currentSelected)
                    if (currentSelected.isNotEmpty()) {
                        prefs.setCalendarEventsSyncEnabled(true)
                    }
                    triggerManualSync()
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
                Toast.makeText(this, "Execução sem restrição já autorizada.", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
            startActivity(intent)
        }
    }

    private fun triggerManualSync() {
        if (!CalendarSyncEngine.hasCalendarPermission(this)) {
            switchTab(BottomTab.SETTINGS)
            return
        }
        if (prefs.getSelectedCalendarIds().isEmpty()) {
            openSyncedCalendarsDialog()
            return
        }
        uiScope.launch {
            withContext(Dispatchers.IO) {
                CalendarSyncEngine.performFullSyncToWatch(
                    this@MobileSyncConfigActivity,
                    "Sincronização Manual"
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
                }
            }
            REQ_CALENDAR_FOR_DIALOG -> {
                if (granted) openSyncedCalendarsDialog()
            }
            REQ_NOTIFICATION_SYNC -> {
                if (granted) prefs.setNotificationsSyncEnabled(true)
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
            if (isBgUnrestricted) "Ativo em 2º plano (Sem restrição)"
            else "Ativo • Autorize bateria sem restrição abaixo"
        } else {
            "Important - Notifications Access required"
        }

        subtitleCalendarEventsSync.text = if (hasCalPerm) {
            "Upcoming Event & Event Timer ($selectedCalsCount agenda(s))"
        } else {
            "Upcoming Event & Event Timer Complication"
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
            val connReport: WearConnectionReport = withContext(Dispatchers.IO) {
                CalendarSyncEngine.checkWearConnectionAndAppStatus(this@MobileSyncConfigActivity)
            }

            cachedHorizonEvents = events

            when (connReport.state) {
                WearConnectionState.CONNECTED_WITH_APP_INSTALLED -> {
                    val names = connReport.installedAppNodeNames.joinToString()
                    val positiveBanner = "ᛒ  Connected • $names"
                    btStatusBannerHome.text = positiveBanner
                    btStatusBannerHome.setTextColor(Color.parseColor("#E2E8F0"))
                    btStatusBannerSettings.text = positiveBanner
                    btStatusBannerSettings.setTextColor(Color.parseColor("#E2E8F0"))

                    welcomeDeviceStatusTitle.text = "App is installed on your wear device(s):"
                    welcomeDeviceStatusDetail.text = names
                    welcomeDeviceStatusDetail.setTextColor(Color.parseColor("#4ADE80"))
                }
                WearConnectionState.WEARABLE_CONNECTED_APP_MISSING -> {
                    val wearableNames = connReport.wearableDisplayNames.joinToString()
                    val warnBanner = "⚠  Wearable conectado ($wearableNames) • App NÃO instalado no relógio"
                    btStatusBannerHome.text = warnBanner
                    btStatusBannerHome.setTextColor(Color.parseColor("#FBBF24"))
                    btStatusBannerSettings.text = warnBanner
                    btStatusBannerSettings.setTextColor(Color.parseColor("#FBBF24"))

                    welcomeDeviceStatusTitle.text = "Há um Wearable conectado, mas o app não está instalado no relógio:"
                    welcomeDeviceStatusDetail.text = "$wearableNames (Instale o APK da versão Wear OS no relógio)"
                    welcomeDeviceStatusDetail.setTextColor(Color.parseColor("#FBBF24"))
                }
                WearConnectionState.DISCONNECTED -> {
                    val discBanner = "ᛒ  Disconnected • Nenhum relógio Wear OS conectado"
                    btStatusBannerHome.text = discBanner
                    btStatusBannerHome.setTextColor(Color.parseColor("#9CA3AF"))
                    btStatusBannerSettings.text = discBanner
                    btStatusBannerSettings.setTextColor(Color.parseColor("#9CA3AF"))

                    welcomeDeviceStatusTitle.text = "Status do dispositivo Wear OS:"
                    welcomeDeviceStatusDetail.text = "Nenhum smartwatch conectado ao telefone."
                    welcomeDeviceStatusDetail.setTextColor(Color.parseColor("#9CA3AF"))
                }
            }

            watchBezelSubtext.text = "${events.size} evento(s) sincronizado(s)"
            updateLiveEventSummary()
        }
    }

    private fun renderChipsRows() {
        rowFrequencyChips.removeAllViews()
        val curFreq = prefs.getSyncIntervalMinutes()
        listOf(15L to "15m", 30L to "30m", 60L to "1h", 120L to "2h").forEach { (mins, label) ->
            rowFrequencyChips.addView(
                createSelectableChip(label, curFreq == mins) {
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
        listOf(30 to "30m", 60 to "1h", 120 to "2h", 180 to "3h").forEach { (mins, label) ->
            rowProgressWindowChips.addView(
                createSelectableChip(label, curWin == mins) {
                    prefs.setProgressWindowMinutes(mins)
                    if (prefs.isBackgroundServiceEnabled()) {
                        CalendarSyncWorker.enqueueImmediateSync(this, "Janela ${mins}m")
                    }
                    refreshUiState()
                }
            )
        }

        rowHorizonDaysChips.removeAllViews()
        val curHor = prefs.getHorizonDays()
        listOf(2 to "2d", 3 to "3d", 5 to "5d", 7 to "7d").forEach { (days, label) ->
            rowHorizonDaysChips.addView(
                createSelectableChip(label, curHor == days) {
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
            setTextColor(if (selected) Color.parseColor("#0B191E") else Color.parseColor("#E5E7EB"))
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            background = pillDrawable(
                if (selected) "#BAE6FD" else "#262A30",
                if (selected) "#7DD3FC" else "#373D45",
                18
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                rightMargin = dp(6)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun startLiveTicker() {
        tickerJob?.cancel()
        tickerJob = uiScope.launch {
            while (isActive) {
                updateLiveEventSummary()
                delay(1000L)
            }
        }
    }

    private fun updateLiveEventSummary() {
        val nowMs = System.currentTimeMillis()
        val nextEv = cachedHorizonEvents.firstOrNull { it.startEpochMs > nowMs }
        if (nextEv == null) {
            nextEventSummaryText.text = "Próximo evento: Nenhum compromisso pendente no horizonte."
            return
        }

        val remainingSec = ((nextEv.startEpochMs - nowMs).coerceAtLeast(0L)) / 1000L
        val hours = remainingSec / 3600L
        val mins = (remainingSec % 3600L) / 60L
        val startHHMM = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextEv.startEpochMs))

        nextEventSummaryText.text =
            "Próximo evento: ${nextEv.title} às $startHHMM (em ${hours}h ${mins}m)"
    }

    companion object {
        private const val REQ_CALENDAR_FOR_SYNC = 3001
        private const val REQ_CALENDAR_FOR_DIALOG = 3002
        private const val REQ_NOTIFICATION_FOR_BG_SERVICE = 3003
        private const val REQ_NOTIFICATION_SYNC = 3004
    }
}
