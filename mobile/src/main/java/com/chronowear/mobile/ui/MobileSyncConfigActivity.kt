package com.chronowear.mobile.ui

import android.Manifest
import android.animation.LayoutTransition
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
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import com.chronowear.mobile.sync.WeatherTriggerAppOption
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
import java.util.concurrent.TimeUnit

class MobileSyncConfigActivity : Activity() {

    private enum class BottomTab(val index: Int) {
        HOME(0),
        SETTINGS(1),
        ABOUT(2)
    }

    private lateinit var prefs: SyncPreferences
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null
    private var activeTab: BottomTab = BottomTab.HOME
    private var suppressSwitchEvents = false

    // Containers das 3 abas controladas pelo rodapé flutuante estilo Samsung Health
    private lateinit var screenHome: LinearLayout
    private lateinit var screenSettings: LinearLayout
    private lateinit var screenAbout: LinearLayout

    // Indicadores de conexão Bluetooth + App instalado no relógio
    private lateinit var btStatusBannerHome: TextView
    private lateinit var btStatusBannerSettings: TextView

    // Elementos da tela Início (estilo Samsung Health + Amoled Watchfaces)
    private lateinit var watchBezelSubtext: TextView
    private lateinit var welcomeDeviceStatusTitle: TextView
    private lateinit var welcomeDeviceStatusDetail: TextView
    private lateinit var energyBigCountdownText: TextView
    private lateinit var energyNextEventSubtitle: TextView
    private lateinit var energyProgressFillView: View
    private lateinit var energyProgressCaption: TextView

    // Elementos da tela Settings
    private lateinit var switchBackgroundService: Switch
    private lateinit var subtitleBackgroundService: TextView
    private lateinit var switchPhoneWeatherAutoCheck: Switch
    private lateinit var switchNotificationsSync: Switch
    private lateinit var btnWeatherTriggerAppsPill: Button
    private lateinit var switchWatchStandaloneWeather: Switch
    private lateinit var switchMediaPlaybackSync: Switch
    private lateinit var switchCalendarEventsSync: Switch
    private lateinit var subtitleCalendarEventsSync: TextView
    private lateinit var btnSyncedCalendarsPill: Button
    private lateinit var switchAutoReadOnChange: Switch
    private lateinit var switchHighFreq15MinRule: Switch
    private lateinit var switchUpdateOnScreenWake: Switch
    private lateinit var rowContextModeChips: LinearLayout
    private lateinit var rowPhoneWeatherIntervalChips: LinearLayout
    private lateinit var rowFrequencyChips: LinearLayout
    private lateinit var rowProgressWindowChips: LinearLayout
    private lateinit var rowHorizonDaysChips: LinearLayout

    // Pílula Flutuante do Rodapé estilo Samsung Health (apenas 3 opções, sem botão de relógio extra)
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
        window.statusBarColor = Color.parseColor("#352E18")
        window.navigationBarColor = Color.parseColor("#0B0E0C")

        buildSamsungHealthUi()
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

    private fun samsungHealthHeaderGradient(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor("#3E351B"), // Topo oliva-dourado suave do Samsung Health
                Color.parseColor("#17231B"), // Transição verde escuro profundo
                Color.parseColor("#0B0E0C")  // Fundo preto carbono One UI
            )
        )
    }

    private fun animatePressFeedback(view: View, onEnd: () -> Unit = {}) {
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(85L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(160L)
                    .setInterpolator(OvershootInterpolator(1.6f))
                    .start()
                onEnd()
            }
            .start()
    }

    private fun buildSamsungHealthUi() {
        val rootFrame = FrameLayout(this).apply {
            background = samsungHealthHeaderGradient()
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
            setPadding(dp(18), dp(22), dp(18), dp(112))
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
            }
        }

        screenHome = buildScreenHome()
        screenSettings = buildScreenSettings()
        screenAbout = buildScreenAbout()

        mainColumn.addView(screenHome)
        mainColumn.addView(screenSettings)
        mainColumn.addView(screenAbout)

        scrollView.addView(mainColumn)
        rootFrame.addView(scrollView)

        // =========================================================================
        // RODAPÉ FLUTUANTE EM PÍLULA IGUAL AO SAMSUNG HEALTH (Apenas 3 opções!)
        // =========================================================================
        val floatingFooterContainer = FrameLayout(this).apply {
            setPadding(dp(26), dp(8), dp(26), dp(18))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val samsungHealthFloatingPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable("#242B26", "#39443C", 36, 1)
            elevation = dp(16).toFloat()
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutTransition = LayoutTransition().apply {
                enableTransitionType(LayoutTransition.CHANGING)
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(66)
            )
        }

        navTabHome = createSamsungHealthBottomItem("⌂", "Início") {
            switchTabAnimated(BottomTab.HOME)
        }.also {
            navIconHome = it.getChildAt(0) as TextView
            navLabelHome = it.getChildAt(1) as TextView
        }

        navTabSettings = createSamsungHealthBottomItem("⚙", "Settings") {
            switchTabAnimated(BottomTab.SETTINGS)
        }.also {
            navIconSettings = it.getChildAt(0) as TextView
            navLabelSettings = it.getChildAt(1) as TextView
        }

        navTabAbout = createSamsungHealthBottomItem("ⓘ", "About") {
            switchTabAnimated(BottomTab.ABOUT)
        }.also {
            navIconAbout = it.getChildAt(0) as TextView
            navLabelAbout = it.getChildAt(1) as TextView
        }

        samsungHealthFloatingPill.addView(navTabHome)
        samsungHealthFloatingPill.addView(navTabSettings)
        samsungHealthFloatingPill.addView(navTabAbout)

        floatingFooterContainer.addView(samsungHealthFloatingPill)
        rootFrame.addView(floatingFooterContainer)
        setContentView(rootFrame)

        applyTabImmediate(BottomTab.HOME)
    }

    private fun createSamsungHealthBottomItem(
        iconSymbol: String,
        title: String,
        onClick: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                animatePressFeedback(this) { onClick() }
            }

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
                setPadding(0, dp(1), 0, 0)
                setTextColor(Color.parseColor("#CBD5E1"))
            }
            addView(iconTv)
            addView(labelTv)
        }
    }

    private fun applyTabImmediate(tab: BottomTab) {
        activeTab = tab
        screenHome.visibility = if (tab == BottomTab.HOME) View.VISIBLE else View.GONE
        screenSettings.visibility = if (tab == BottomTab.SETTINGS) View.VISIBLE else View.GONE
        screenAbout.visibility = if (tab == BottomTab.ABOUT) View.VISIBLE else View.GONE

        styleSamsungHealthBottomTab(navTabHome, navIconHome, navLabelHome, tab == BottomTab.HOME)
        styleSamsungHealthBottomTab(navTabSettings, navIconSettings, navLabelSettings, tab == BottomTab.SETTINGS)
        styleSamsungHealthBottomTab(navTabAbout, navIconAbout, navLabelAbout, tab == BottomTab.ABOUT)

        refreshUiState()
    }

    /**
     * Troca de aba com animação fluida (deslizamento horizontal proporcional + fade + pílula elástica).
     */
    private fun switchTabAnimated(targetTab: BottomTab) {
        if (targetTab == activeTab) return

        val direction = if (targetTab.index > activeTab.index) 1 else -1
        activeTab = targetTab

        val views = mapOf(
            BottomTab.HOME to screenHome,
            BottomTab.SETTINGS to screenSettings,
            BottomTab.ABOUT to screenAbout
        )

        views.forEach { (tab, view) ->
            if (tab == targetTab) {
                view.visibility = View.VISIBLE
                view.alpha = 0f
                view.translationX = dp(28 * direction).toFloat()
                view.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(260L)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .start()
            } else {
                view.visibility = View.GONE
            }
        }

        styleSamsungHealthBottomTab(navTabHome, navIconHome, navLabelHome, targetTab == BottomTab.HOME)
        styleSamsungHealthBottomTab(navTabSettings, navIconSettings, navLabelSettings, targetTab == BottomTab.SETTINGS)
        styleSamsungHealthBottomTab(navTabAbout, navIconAbout, navLabelAbout, targetTab == BottomTab.ABOUT)

        refreshUiState()
    }

    private fun styleSamsungHealthBottomTab(
        tabContainer: LinearLayout,
        iconView: TextView,
        labelView: TextView,
        selected: Boolean
    ) {
        if (selected) {
            // Pílula interna preenchida que abraça o ícone + texto igual ao botão "Início" do Samsung Health
            tabContainer.background = pillDrawable("#434E46", "#57655B", 30, 1)
            iconView.setTextColor(Color.parseColor("#FFFFFF"))
            labelView.setTextColor(Color.parseColor("#FFFFFF"))
            labelView.setTypeface(labelView.typeface, Typeface.BOLD)
            tabContainer.scaleX = 0.92f
            tabContainer.scaleY = 0.92f
            tabContainer.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220L)
                .setInterpolator(OvershootInterpolator(1.8f))
                .start()
        } else {
            tabContainer.background = pillDrawable("#00000000", "#00000000", 30, 0)
            iconView.setTextColor(Color.parseColor("#CBD5E1"))
            labelView.setTextColor(Color.parseColor("#9CA3AF"))
            labelView.setTypeface(labelView.typeface, Typeface.NORMAL)
        }
    }

    private fun createOrangeDotBadge(): View {
        return View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#F97316"))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
    }

    // =========================================================================
    // ABA 1: INÍCIO (Estética Samsung Health + Status Amoled Watchfaces)
    // =========================================================================
    private fun buildScreenHome(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL

            val headerRow = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }
            }

            val titleTv = TextView(this@MobileSyncConfigActivity).apply {
                text = "ChronoWear Health Sync"
                textSize = 23f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            headerRow.addView(titleTv)
            headerRow.addView(createOrangeDotBadge())
            addView(headerRow)

            // Mockup Circular do Relógio no topo mostrando o novo layout de 4 Slots
            val watchBezel = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor("#0D1210"))
                    setStroke(dp(10), Color.parseColor("#28332C"))
                }
                setPadding(dp(24), dp(20), dp(24), dp(20))
                layoutParams = LinearLayout.LayoutParams(dp(236), dp(236)).apply {
                    bottomMargin = dp(12)
                }

                val slotTimer = LinearLayout(this@MobileSyncConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = pillDrawable("#1E2822", "#34463B", 16)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }

                    val t1 = TextView(this@MobileSyncConfigActivity).apply {
                        text = "🖼 Janela de Contexto (Canvas)"
                        textSize = 11f
                        setTextColor(Color.parseColor("#FBBF24"))
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    watchBezelSubtext = TextView(this@MobileSyncConfigActivity).apply {
                        text = "Complication Única SMALL_IMAGE"
                        textSize = 9.5f
                        setTextColor(Color.parseColor("#CBD5E1"))
                    }
                    addView(t1)
                    addView(watchBezelSubtext)
                }
                addView(slotTimer)

                val slotEvents = LinearLayout(this@MobileSyncConfigActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = pillDrawable("#18211C", "#2B3A31", 16)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )

                    val t2 = TextView(this@MobileSyncConfigActivity).apply {
                        text = "⚡ Dinâmico • Eventos • Clima • Passos"
                        textSize = 10.5f
                        setTextColor(Color.parseColor("#FFFFFF"))
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    val s2 = TextView(this@MobileSyncConfigActivity).apply {
                        text = "Turbo 1m nos 15 min finais + Alerta Clima"
                        textSize = 9.5f
                        setTextColor(Color.parseColor("#A7F3D0"))
                    }
                    addView(t2)
                    addView(s2)
                }
                addView(slotEvents)
            }
            addView(watchBezel)

            // Status Bluetooth verificado (só dá positivo se o app estiver instalado no relógio)
            btStatusBannerHome = TextView(this@MobileSyncConfigActivity).apply {
                text = "ᛒ  Verificando conexão com o relógio..."
                textSize = 13.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(16))
            }
            addView(btStatusBannerHome)

            // Cartão Principal estilo "Sua alimentação sob controle / Welcome!" do Samsung Health
            val welcomeCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1D2620", "#2F3D33", 26)
                setPadding(dp(22), dp(20), dp(22), dp(20))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }

                val wTitle = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Sua agenda sob controle"
                    textSize = 20f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(wTitle)

                val wDesc = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Sincronize seus compromissos em segundo plano para exibir o contador regressivo e os próximos 3 eventos separados no seu smartwatch."
                    textSize = 14f
                    setTextColor(Color.parseColor("#D1D5DB"))
                    setPadding(0, dp(8), 0, dp(14))
                }
                addView(wDesc)

                welcomeDeviceStatusTitle = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Status do aplicativo no relógio:"
                    textSize = 13f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(welcomeDeviceStatusTitle)

                welcomeDeviceStatusDetail = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Verificando dispositivos Wear OS..."
                    textSize = 14.5f
                    setTextColor(Color.parseColor("#4ADE80"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(4), 0, 0)
                }
                addView(welcomeDeviceStatusDetail)
            }
            addView(welcomeCard)

            // Cartão estilo "Pontuação energia" do Samsung Health para o Próximo Evento & Barra
            val energyScoreCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#141816", "#252C28", 26)
                setPadding(dp(22), dp(18), dp(22), dp(20))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                val topRow = LinearLayout(this@MobileSyncConfigActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val eHeader = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Temporizador do próximo evento"
                    textSize = 14f
                    setTextColor(Color.parseColor("#E5E7EB"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                topRow.addView(eHeader)
                topRow.addView(createOrangeDotBadge())
                addView(topRow)

                energyBigCountdownText = TextView(this@MobileSyncConfigActivity).apply {
                    text = "--:--"
                    textSize = 36f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(6), 0, dp(2))
                }
                addView(energyBigCountdownText)

                energyNextEventSubtitle = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Ative Calendar Events Sync em Settings"
                    textSize = 14f
                    setTextColor(Color.parseColor("#60A5FA"))
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, 0, 0, dp(12))
                }
                addView(energyNextEventSubtitle)

                val barTrack = LinearLayout(this@MobileSyncConfigActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    background = pillDrawable("#262D29", "#36403A", 10)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(14)
                    )
                }
                energyProgressFillView = View(this@MobileSyncConfigActivity).apply {
                    background = pillDrawable("#4ADE80", "#4ADE80", 10)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
                }
                barTrack.addView(energyProgressFillView)
                addView(barTrack)

                energyProgressCaption = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Barra de progresso no relógio: 0%"
                    textSize = 11.5f
                    setTextColor(Color.parseColor("#9CA3AF"))
                    setPadding(0, dp(8), 0, 0)
                }
                addView(energyProgressCaption)
            }
            addView(energyScoreCard)
        }
    }

    // =========================================================================
    // ABA 2: SETTINGS (Design Samsung Health + Funcionalidades Amoled Watchfaces)
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
                setPadding(0, dp(2), 0, dp(8))
            }
            addView(settingsHeaderTitle)

            btStatusBannerSettings = TextView(this@MobileSyncConfigActivity).apply {
                text = "ᛒ  Verificando conexão..."
                textSize = 13.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), dp(14))
            }
            addView(btStatusBannerSettings)

            val mainSettingsCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#141816", "#262E29", 26)
                setPadding(dp(20), dp(18), dp(20), dp(18))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }
            }

            val bgServiceRow = createSettingToggleRow(
                title = "Background Service",
                subtitle = "Important - Notifications Access required",
                onSubtitleCreated = { subtitleBackgroundService = it },
                onSwitchCreated = { sw ->
                    switchBackgroundService = sw
                    sw.setOnCheckedChangeListener { buttonView, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        animatePressFeedback(buttonView)
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

            val phoneAutoWeatherRow = createSettingToggleRow(
                title = "Busca Periódica de Clima pelo App (Auto)",
                subtitle = "Mesmo sem notificação externa, nosso app busca o clima de hora em hora e notifica o relógio se houver mudança de temperatura",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchPhoneWeatherAutoCheck = sw
                    sw.setOnCheckedChangeListener { buttonView, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        animatePressFeedback(buttonView)
                        prefs.setPhoneWeatherAutoCheckEnabled(isChecked)
                        triggerManualSync()
                    }
                }
            )
            mainSettingsCard.addView(phoneAutoWeatherRow)

            val notifSyncRow = createSettingToggleRow(
                title = "Trigger por Notificação de Apps de Clima",
                subtitle = "Quando o app de clima escolhido disparar uma notificação, atualiza a previsão e muda o Canvas no relógio",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchNotificationsSync = sw
                    sw.setOnCheckedChangeListener { buttonView, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        animatePressFeedback(buttonView)
                        onToggleNotificationsSync(isChecked)
                    }
                }
            )
            mainSettingsCard.addView(notifSyncRow)

            btnWeatherTriggerAppsPill = createOutlineActionPillButton(
                "🌦  Escolher Apps de Clima (Trigger)"
            ) {
                openWeatherTriggerAppsDialog()
            }
            mainSettingsCard.addView(btnWeatherTriggerAppsPill)

            val btnNotifAccessPill = createOutlineActionPillButton(
                "🔔  Autorizar Leitura de Notificações"
            ) {
                try {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                } catch (_: Exception) {
                }
            }
            mainSettingsCard.addView(btnNotifAccessPill)

            val watchStandaloneWeatherRow = createSettingToggleRow(
                title = "Serviço Stand-Alone de Clima no Relógio (1h)",
                subtitle = "Opcional: permite que o próprio relógio busque o clima de hora em hora sozinho (desligado por padrão)",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchWatchStandaloneWeather = sw
                    sw.setOnCheckedChangeListener { buttonView, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        animatePressFeedback(buttonView)
                        prefs.setWatchStandaloneWeatherEnabled(isChecked)
                        triggerManualSync()
                    }
                }
            )
            mainSettingsCard.addView(watchStandaloneWeatherRow)

            val btnWeatherFetchPill = createOutlineActionPillButton(
                "🌤  Atualizar Previsão pelo Telefone Agora"
            ) {
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 3009)
                } else {
                    uiScope.launch {
                        withContext(Dispatchers.IO) {
                            CalendarSyncEngine.fetchPhoneHourlyWeatherIfNeeded(
                                this@MobileSyncConfigActivity,
                                forceRefresh = true
                            )
                            CalendarSyncEngine.performFullSyncToWatch(
                                this@MobileSyncConfigActivity,
                                "Previsão do Clima pelo Telefone"
                            )
                        }
                        Toast.makeText(
                            this@MobileSyncConfigActivity,
                            "Previsão horária buscada pelo telefone e enviada ao relógio!",
                            Toast.LENGTH_SHORT
                        ).show()
                        refreshUiState()
                    }
                }
            }
            mainSettingsCard.addView(btnWeatherFetchPill)

            val mediaSyncRow = createSettingToggleRow(
                title = "Media Playback Sync",
                subtitle = "Now Playing Complication support",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchMediaPlaybackSync = sw
                    sw.setOnCheckedChangeListener { buttonView, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        animatePressFeedback(buttonView)
                        prefs.setMediaPlaybackSyncEnabled(isChecked)
                    }
                }
            )
            mainSettingsCard.addView(mediaSyncRow)

            val calSyncRow = createSettingToggleRow(
                title = "Calendar Events Sync",
                subtitle = "Upcoming Event & Event Timer Complication",
                onSubtitleCreated = { subtitleCalendarEventsSync = it },
                onSwitchCreated = { sw ->
                    switchCalendarEventsSync = sw
                    sw.setOnCheckedChangeListener { buttonView, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        animatePressFeedback(buttonView)
                        onToggleCalendarEventsSync(isChecked)
                    }
                }
            )
            mainSettingsCard.addView(calSyncRow)

            btnSyncedCalendarsPill = createOutlineActionPillButton("📅   Synced calendars") {
                openSyncedCalendarsDialog()
            }
            mainSettingsCard.addView(btnSyncedCalendarsPill)

            val autoChangeRow = createSettingToggleRow(
                title = "Instant Calendar Change Sync",
                subtitle = "Relê a agenda automaticamente quando um compromisso é criado ou alterado",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchAutoReadOnChange = sw
                    sw.setOnCheckedChangeListener { buttonView, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        animatePressFeedback(buttonView)
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

            val turbo15mRow = createSettingToggleRow(
                title = "Regra Turbo 15 min (Atualizar a cada 1 min)",
                subtitle = "Quando faltar ≤ 15 min para iniciar o evento, atualiza o Canvas minuto a minuto",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchHighFreq15MinRule = sw
                    sw.setOnCheckedChangeListener { buttonView, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        animatePressFeedback(buttonView)
                        prefs.setHighFreq15MinRuleEnabled(isChecked)
                        triggerManualSync()
                    }
                }
            )
            mainSettingsCard.addView(turbo15mRow)

            val screenWakeRow = createSettingToggleRow(
                title = "Atualizar Canvas ao Acordar a Tela",
                subtitle = "Redesenha o quadro ao ligar a tela do relógio (com proteção de 25s)",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchUpdateOnScreenWake = sw
                    sw.setOnCheckedChangeListener { buttonView, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        animatePressFeedback(buttonView)
                        prefs.setUpdateOnScreenWakeEnabled(isChecked)
                        triggerManualSync()
                    }
                }
            )
            mainSettingsCard.addView(screenWakeRow)

            addView(mainSettingsCard)

            val timingCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#141816", "#262E29", 26)
                setPadding(dp(20), dp(18), dp(20), dp(18))
            }

            val ctxLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Função da Janela de Contexto (Canvas Bitmap)"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            }
            timingCard.addView(ctxLabel)

            rowContextModeChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(14))
            }
            timingCard.addView(rowContextModeChips)

            val weatherCheckLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Intervalo de Busca Automática de Clima (Telefone)"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(8))
            }
            timingCard.addView(weatherCheckLabel)

            rowPhoneWeatherIntervalChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(14))
            }
            timingCard.addView(rowPhoneWeatherIntervalChips)

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
    // ABA 3: ABOUT (Informações essenciais e sincronização manual)
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
                setPadding(0, dp(2), 0, dp(16))
            }
            addView(aboutHeaderTitle)

            val card = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#141816", "#262E29", 26)
                setPadding(dp(22), dp(20), dp(22), dp(20))
            }

            val body = TextView(this@MobileSyncConfigActivity).apply {
                text = "ChronoWear Health & Calendar Sync (v1.1.1)\n\n" +
                    "• 3 Gatilhos Inteligentes de Clima para o Canvas:\n" +
                    "  1) Busca Periódica pelo Nosso App (Telefone): De hora em hora (ou no intervalo configurado), o próprio ChronoWear busca a previsão e, se detectar mudança relevante de temperatura nas próximas horas, notifica o relógio para mudar dinamicamente o Canvas.\n" +
                    "  2) Trigger por App de Terceiro Escolhido: Você escolhe quais apps da categoria de clima (App Google, Google Weather, App Clima Samsung, Climatempo, AccuWeather, The Weather Channel, Windy) disparam a atualização imediata e enviam o contexto ao relógio.\n" +
                    "  3) Serviço Stand-Alone no Relógio (1h em 1h): Opcional e configurável caso queira que o próprio relógio busque sozinho o clima de hora em hora quando desconectado do celular."
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
            background = pillDrawable("#1D2420", "#455249", 24, 1)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(44)
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            }
            setOnClickListener {
                animatePressFeedback(this) { onClick() }
            }
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

    /**
     * Permite ao usuário escolher quais aplicativos da categoria de Clima (App Google, Google Weather,
     * App Clima Samsung, Climatempo, AccuWeather, The Weather Channel, Windy e outros apps de clima
     * instalados no aparelho) funcionarão como trigger de notificação para atualizar o clima e
     * enviar o contexto ao relógio.
     */
    private fun openWeatherTriggerAppsDialog() {
        val catalog = SyncPreferences.KNOWN_WEATHER_TRIGGER_CATALOG.toMutableList()
        val knownPkgs = catalog.map { it.packageName.lowercase() }.toMutableSet()

        try {
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                val pkgLower = app.packageName.lowercase()
                if (!knownPkgs.contains(pkgLower) && (
                        pkgLower.contains("weather") ||
                            pkgLower.contains("clima") ||
                            pkgLower.contains("meteo") ||
                            pkgLower.contains("forecast")
                        )
                ) {
                    val label = packageManager.getApplicationLabel(app).toString()
                    catalog.add(
                        WeatherTriggerAppOption(
                            packageName = app.packageName,
                            displayName = label,
                            categoryDescription = "App de Clima instalado (${app.packageName})"
                        )
                    )
                    knownPkgs.add(pkgLower)
                }
            }
        } catch (_: Exception) {
        }

        val currentSelected = prefs.getSelectedWeatherTriggerPackages().toMutableSet()
        val labels = catalog.map { opt ->
            "${opt.displayName} • ${opt.categoryDescription}"
        }.toTypedArray()
        val checkedItems = BooleanArray(catalog.size) { idx ->
            currentSelected.contains(catalog[idx].packageName)
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Apps de Clima (Trigger de Notificação)")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                val pkg = catalog[which].packageName
                if (isChecked) currentSelected.add(pkg) else currentSelected.remove(pkg)
            }
            .setPositiveButton("Salvar") { _, _ ->
                prefs.setSelectedWeatherTriggerPackages(currentSelected)
                if (currentSelected.isNotEmpty()) {
                    prefs.setNotificationsSyncEnabled(true)
                }
                triggerManualSync()
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
            switchTabAnimated(BottomTab.SETTINGS)
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
        val selectedWeatherAppsCount = prefs.getSelectedWeatherTriggerPackages().size

        suppressSwitchEvents = true
        switchBackgroundService.isChecked = prefs.isBackgroundServiceEnabled()
        switchPhoneWeatherAutoCheck.isChecked = prefs.isPhoneWeatherAutoCheckEnabled()
        switchNotificationsSync.isChecked = prefs.isNotificationsSyncEnabled()
        switchWatchStandaloneWeather.isChecked = prefs.isWatchStandaloneWeatherEnabled()
        switchMediaPlaybackSync.isChecked = prefs.isMediaPlaybackSyncEnabled()
        switchCalendarEventsSync.isChecked = prefs.isCalendarEventsSyncEnabled() && hasCalPerm
        switchAutoReadOnChange.isChecked = prefs.isAutoSyncOnCalendarChangeEnabled()
        switchHighFreq15MinRule.isChecked = prefs.isHighFreq15MinRuleEnabled()
        switchUpdateOnScreenWake.isChecked = prefs.isUpdateOnScreenWakeEnabled()
        suppressSwitchEvents = false

        btnWeatherTriggerAppsPill.text =
            "🌦  Escolher Apps de Clima ($selectedWeatherAppsCount selecionado(s))"

        subtitleBackgroundService.text = if (prefs.isBackgroundServiceEnabled()) {
            if (isBgUnrestricted) "Ativo em 2º plano (Sem restrição)"
            else "Ativo • Autorize bateria sem restrição abaixo"
        } else {
            "Important - Notifications Access required"
        }

        subtitleCalendarEventsSync.text = if (hasCalPerm) {
            "Janela de Contexto (Canvas) • $selectedCalsCount agenda(s)"
        } else {
            "Janela de Contexto (Canvas Bitmap)"
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

            watchBezelSubtext.text = "Modo ${prefs.getContextWindowMode()} • ${events.size} evento(s)"
            updateLiveEventSummary()
        }
    }

    private fun renderChipsRows() {
        rowContextModeChips.removeAllViews()
        val curCtx = prefs.getContextWindowMode()
        listOf(
            "DYNAMIC" to "Auto",
            "EVENTS" to "Agenda",
            "WEATHER" to "Clima",
            "STEPS" to "Passos"
        ).forEach { (mode, label) ->
            rowContextModeChips.addView(
                createSelectableChip(label, curCtx == mode) {
                    prefs.setContextWindowMode(mode)
                    triggerManualSync()
                }
            )
        }

        rowPhoneWeatherIntervalChips.removeAllViews()
        val curWeatherInt = prefs.getPhoneWeatherCheckIntervalMinutes()
        listOf(30 to "30m", 60 to "1h", 120 to "2h", 180 to "3h").forEach { (mins, label) ->
            rowPhoneWeatherIntervalChips.addView(
                createSelectableChip(label, curWeatherInt == mins) {
                    prefs.setPhoneWeatherCheckIntervalMinutes(mins)
                    triggerManualSync()
                }
            )
        }

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
                if (selected) "#4ADE80" else "#232A26",
                if (selected) "#34D399" else "#35403A",
                18
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                rightMargin = dp(6)
            }
            setOnClickListener {
                animatePressFeedback(this) { onClick() }
            }
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
            energyBigCountdownText.text = "Livre"
            energyNextEventSubtitle.text = "Sem próximo compromisso no horizonte"
            energyProgressCaption.text =
                "Barra RangedValue: 0% • Janela: ${prefs.getProgressWindowMinutes()}m antes"
            energyProgressFillView.layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
            return
        }

        val remainingMs = (nextEv.startEpochMs - nowMs).coerceAtLeast(0L)
        val remainingSec = remainingMs / 1000L
        val days = remainingSec / 86400L
        val hours = (remainingSec % 86400L) / 3600L
        val mins = (remainingSec % 3600L) / 60L
        val secs = remainingSec % 60L

        energyBigCountdownText.text = when {
            days > 0 -> String.format(Locale.getDefault(), "%dd %02dh %02dm", days, hours, mins)
            hours > 0 -> String.format(Locale.getDefault(), "%02dh %02dm", hours, mins)
            else -> String.format(Locale.getDefault(), "%02dm %02ds", mins, secs)
        }

        val startHHMM = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextEv.startEpochMs))
        energyNextEventSubtitle.text = "$startHHMM • ${nextEv.title}"

        val windowMs = TimeUnit.MINUTES.toMillis(prefs.getProgressWindowMinutes().toLong()).toFloat()
        val ratio = if (remainingMs >= windowMs) {
            0f
        } else {
            (1f - (remainingMs.toFloat() / windowMs)).coerceIn(0f, 1f)
        }
        val pct = (ratio * 100f).toInt()
        energyProgressCaption.text =
            "Barra no Relógio: $pct% (Inicia ${prefs.getProgressWindowMinutes()} min antes)"

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
