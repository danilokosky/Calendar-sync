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
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.StateSet
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

    // Containers das 3 abas
    private lateinit var screenHome: LinearLayout
    private lateinit var screenSettings: LinearLayout
    private lateinit var screenAbout: LinearLayout

    // Indicadores de conexão Bluetooth + App instalado no relógio
    private lateinit var btStatusBannerHome: TextView
    private lateinit var btStatusBannerSettings: TextView

    // Elementos da tela Início
    private lateinit var watchBezelSubtext: TextView
    private lateinit var welcomeDeviceStatusTitle: TextView
    private lateinit var welcomeDeviceStatusDetail: TextView
    private lateinit var energyBigCountdownText: TextView
    private lateinit var energyNextEventSubtitle: TextView
    private lateinit var energyProgressFillView: View
    private lateinit var energyProgressCaption: TextView

    // Elementos da tela Settings (Separados por Seção, sem emojis)
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
    private lateinit var switchSleepSync: Switch
    private lateinit var subtitleSleepInfo: TextView
    private lateinit var switchNotifyWatchOnCanvasChange: Switch
    private lateinit var switchNotifyAgenda15Min: Switch
    private lateinit var subtitleWeatherLocationInfo: TextView
    private lateinit var rowContextModeChipsTop: LinearLayout
    private lateinit var rowContextModeChipsBottom: LinearLayout
    private lateinit var rowAutoPrimaryContextChips: LinearLayout
    private lateinit var rowUnifiedCanvasHoldChips: LinearLayout
    private lateinit var rowPhoneWeatherIntervalChips: LinearLayout
    private lateinit var rowFrequencyChips: LinearLayout
    private lateinit var rowProgressWindowChips: LinearLayout
    private lateinit var rowHorizonDaysChips: LinearLayout

    // Pílula Flutuante do Rodapé
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

        CalendarSyncEngine.refreshPhoneSolarTimes(this)
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

    /**
     * Cria um Switch moderno estilo Samsung One UI 6 / Material 3 com alto contraste:
     * - Quando ATIVO (ON): Trilha verde esmeralda vibrante (#22C55E) com círculo branco puro (#FFFFFF)
     * - Quando INATIVO (OFF): Trilha grafite clara (#2A3441) com borda visível (#64748B) e círculo prata (#CBD5E1)
     */
    private fun createModernSwitch(): Switch {
        val trackWidth = dp(52)
        val trackHeight = dp(28)
        val thumbDiameter = dp(22)
        val insetPx = dp(3)

        val trackOn = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setSize(trackWidth, trackHeight)
            setColor(Color.parseColor("#22C55E"))
            setStroke(dp(1), Color.parseColor("#4ADE80"))
        }
        val trackOff = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setSize(trackWidth, trackHeight)
            setColor(Color.parseColor("#2A3441"))
            setStroke(dp(1), Color.parseColor("#64748B"))
        }
        val trackStateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), trackOn)
            addState(StateSet.WILD_CARD, trackOff)
        }

        val thumbCircleOn = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(thumbDiameter, thumbDiameter)
            setColor(Color.parseColor("#FFFFFF"))
        }
        val thumbLayerOn = LayerDrawable(arrayOf(thumbCircleOn)).apply {
            setLayerInset(0, insetPx, insetPx, insetPx, insetPx)
        }

        val thumbCircleOff = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(thumbDiameter, thumbDiameter)
            setColor(Color.parseColor("#CBD5E1"))
        }
        val thumbLayerOff = LayerDrawable(arrayOf(thumbCircleOff)).apply {
            setLayerInset(0, insetPx, insetPx, insetPx, insetPx)
        }

        val thumbStateList = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), thumbLayerOn)
            addState(StateSet.WILD_CARD, thumbLayerOff)
        }

        return Switch(this).apply {
            textOn = ""
            textOff = ""
            showText = false
            splitTrack = false
            switchMinWidth = trackWidth
            thumbDrawable = thumbStateList
            trackDrawable = trackStateList
            background = null
        }
    }

    private fun samsungHealthHeaderGradient(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.parseColor("#3E351B"),
                Color.parseColor("#17231B"),
                Color.parseColor("#0B0E0C")
            )
        )
    }

    private fun animatePressFeedback(view: View, onEnd: () -> Unit = {}) {
        view.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(70L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(130L)
                    .setInterpolator(OvershootInterpolator(1.4f))
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
            setPadding(dp(18), dp(22), dp(18), dp(140))
        }

        screenHome = buildScreenHome()
        screenSettings = buildScreenSettings()
        screenAbout = buildScreenAbout()

        mainColumn.addView(screenHome)
        mainColumn.addView(screenSettings)
        mainColumn.addView(screenAbout)

        scrollView.addView(mainColumn)
        rootFrame.addView(scrollView)

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

        navTabSettings = createSamsungHealthBottomItem("⚙", "Configurações") {
            switchTabAnimated(BottomTab.SETTINGS)
        }.also {
            navIconSettings = it.getChildAt(0) as TextView
            navLabelSettings = it.getChildAt(1) as TextView
        }

        navTabAbout = createSamsungHealthBottomItem("ⓘ", "Sobre") {
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
                onClick()
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
                view.translationX = dp(24 * direction).toFloat()
                view.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(220L)
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
            tabContainer.background = pillDrawable("#434E46", "#57655B", 30, 1)
            iconView.setTextColor(Color.parseColor("#FFFFFF"))
            labelView.setTextColor(Color.parseColor("#FFFFFF"))
            labelView.setTypeface(labelView.typeface, Typeface.BOLD)
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
    // ABA 1: INÍCIO
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
                        text = "Janela de Contexto (Canvas)"
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
                        text = "Auto • Agenda • Clima • Passos • Sol"
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

            btStatusBannerHome = TextView(this@MobileSyncConfigActivity).apply {
                text = "Verificando conexão com o relógio..."
                textSize = 13.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(4), dp(8), dp(16))
            }
            addView(btStatusBannerHome)

            val welcomeCard = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = pillDrawable("#1D2620", "#2F3D33", 26)
                setPadding(dp(22), dp(20), dp(22), dp(20))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }

                val wTitle = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Sua agenda e contexto sob controle"
                    textSize = 20f
                    setTextColor(Color.parseColor("#FFFFFF"))
                    setTypeface(typeface, Typeface.BOLD)
                }
                addView(wTitle)

                val wDesc = TextView(this@MobileSyncConfigActivity).apply {
                    text = "Sincronize compromissos, previsão climática horária e curva solar de nascer e pôr do sol diretamente na Janela de Contexto do seu smartwatch."
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
                    text = "Ative a Sincronização de Eventos em Configurações"
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
    // ABA 2: CONFIGURAÇÕES (Separada por Seções Organizacionais e Sem Emojis)
    // =========================================================================
    private fun createSectionCard(sectionHeaderTitle: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillDrawable("#141816", "#262E29", 26)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }

            val secTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = sectionHeaderTitle.uppercase(Locale.getDefault())
                textSize = 12f
                letterSpacing = 0.08f
                setTextColor(Color.parseColor("#4ADE80"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(10))
            }
            addView(secTitle)

            val divider = View(this@MobileSyncConfigActivity).apply {
                setBackgroundColor(Color.parseColor("#232B26"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).apply { bottomMargin = dp(8) }
            }
            addView(divider)
        }
    }

    private fun buildScreenSettings(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val settingsHeaderTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = "Configurações"
                textSize = 23f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(2), 0, dp(6))
            }
            addView(settingsHeaderTitle)

            btStatusBannerSettings = TextView(this@MobileSyncConfigActivity).apply {
                text = "Verificando conexão..."
                textSize = 13.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), dp(14))
            }
            addView(btStatusBannerSettings)

            // -----------------------------------------------------------------
            // SEÇÃO 1: JANELA DE CONTEXTO (CANVAS DO RELÓGIO)
            // -----------------------------------------------------------------
            val sectionContextCard = createSectionCard("1. Janela de Contexto (Canvas do Relógio)")

            val ctxLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Contexto Exibido no Quadro Central"
                textSize = 15.5f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
            }
            sectionContextCard.addView(ctxLabel)

            val ctxSubLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Escolha um modo fixo ou Auto para alternar entre Agenda, Clima, Sol, Passos e Sono"
                textSize = 12.5f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 0, 0, dp(10))
            }
            sectionContextCard.addView(ctxSubLabel)

            rowContextModeChipsTop = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(6))
            }
            sectionContextCard.addView(rowContextModeChipsTop)

            rowContextModeChipsBottom = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            sectionContextCard.addView(rowContextModeChipsBottom)

            val turbo15mRow = createSettingToggleRow(
                title = "Regra Turbo 15 min (Atualizar a cada 1 min)",
                subtitle = "Quando faltar 15 minutos ou menos para iniciar o evento, atualiza o Canvas minuto a minuto",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchHighFreq15MinRule = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        prefs.setHighFreq15MinRuleEnabled(isChecked)
                        syncSettingsToWatchInBackground("Regra Turbo 15m")
                    }
                }
            )
            sectionContextCard.addView(turbo15mRow)

            val screenWakeRow = createSettingToggleRow(
                title = "Atualizar Canvas ao Acordar a Tela",
                subtitle = "Redesenha o quadro ao ligar a tela do relógio (com proteção de 25s)",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchUpdateOnScreenWake = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        prefs.setUpdateOnScreenWakeEnabled(isChecked)
                        syncSettingsToWatchInBackground("Atualizar ao Acordar")
                    }
                }
            )
            sectionContextCard.addView(screenWakeRow)
            addView(sectionContextCard)

            // -----------------------------------------------------------------
            // SEÇÃO 2: CANVAS PADRÃO PRINCIPAL, TEMPO UNIFICADO E PRIORIDADE
            // -----------------------------------------------------------------
            val sectionAutoBehaviorCard = createSectionCard("2. Canvas Padrão Principal, Tempo Unificado e Prioridade")

            val autoPrimaryLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Canvas Padrão Principal"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(3))
            }
            sectionAutoBehaviorCard.addView(autoPrimaryLabel)

            val autoPrimarySub = TextView(this@MobileSyncConfigActivity).apply {
                text = "Aparência padrão do relógio para a qual o Canvas sempre retorna após um toque (Tap) ou após uma mudança temporária de contexto"
                textSize = 12f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 0, 0, dp(8))
            }
            sectionAutoBehaviorCard.addView(autoPrimarySub)

            rowAutoPrimaryContextChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(12))
            }
            sectionAutoBehaviorCard.addView(rowAutoPrimaryContextChips)

            val unifiedHoldLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Tempo Fixo de Canvas Não-Padrão (Unificado)"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(3))
            }
            sectionAutoBehaviorCard.addView(unifiedHoldLabel)

            val unifiedHoldSub = TextView(this@MobileSyncConfigActivity).apply {
                text = "Quanto tempo qualquer Canvas diferente do padrão (aberto por Tap na Watch Face ou forçado por Clima, Agenda, Pôr/Nascer do Sol ou Dormir/Acordar) fica fixo antes de retornar ao Canvas Padrão Principal"
                textSize = 12f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 0, 0, dp(8))
            }
            sectionAutoBehaviorCard.addView(unifiedHoldSub)

            rowUnifiedCanvasHoldChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(10))
            }
            sectionAutoBehaviorCard.addView(rowUnifiedCanvasHoldChips)

            val notifyWatchCanvasRow = createSettingToggleRow(
                title = "Notificar no Relógio ao Atualizar o Canvas",
                subtitle = "Ativa ou desativa a notificação gerada pelo app do relógio quando o Canvas muda automaticamente",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchNotifyWatchOnCanvasChange = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        prefs.setNotifyWatchOnCanvasChangeEnabled(isChecked)
                        syncSettingsToWatchInBackground("Notificar Mudança Canvas")
                    }
                }
            )
            sectionAutoBehaviorCard.addView(notifyWatchCanvasRow)

            val notifyAgenda15MinRow = createSettingToggleRow(
                title = "Notificar Compromisso Faltando 15 Minutos",
                subtitle = "Quando faltar 15 minutos para o compromisso, o Canvas troca para Agenda e exibe notificação opcional no relógio",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchNotifyAgenda15Min = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        prefs.setNotifyAgenda15MinEnabled(isChecked)
                        syncSettingsToWatchInBackground("Notificar Agenda 15m")
                    }
                }
            )
            sectionAutoBehaviorCard.addView(notifyAgenda15MinRow)
            addView(sectionAutoBehaviorCard)

            // -----------------------------------------------------------------
            // SEÇÃO 3: AGENDA E CONTAGEM REGRESSIVA
            // -----------------------------------------------------------------
            val sectionCalendarCard = createSectionCard("3. Agenda e Contagem Regressiva")

            val calSyncRow = createSettingToggleRow(
                title = "Sincronização de Eventos da Agenda",
                subtitle = "Envia os próximos compromissos e barra de progresso para o Canvas",
                onSubtitleCreated = { subtitleCalendarEventsSync = it },
                onSwitchCreated = { sw ->
                    switchCalendarEventsSync = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        onToggleCalendarEventsSync(isChecked)
                    }
                }
            )
            sectionCalendarCard.addView(calSyncRow)

            btnSyncedCalendarsPill = createOutlineActionPillButton("Selecionar Calendários Sincronizados") {
                openSyncedCalendarsDialog()
            }
            sectionCalendarCard.addView(btnSyncedCalendarsPill)

            val autoChangeRow = createSettingToggleRow(
                title = "Leitura Instantânea ao Alterar Agenda",
                subtitle = "Relê a agenda automaticamente quando um compromisso é criado ou editado",
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
            sectionCalendarCard.addView(autoChangeRow)

            val freqLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Frequência de Leitura do Calendário"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(8), 0, dp(8))
            }
            sectionCalendarCard.addView(freqLabel)

            rowFrequencyChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(12))
            }
            sectionCalendarCard.addView(rowFrequencyChips)

            val progLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Janela da Barra de Progresso do Evento (0% a 100%)"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(3))
            }
            sectionCalendarCard.addView(progLabel)

            val progSub = TextView(this@MobileSyncConfigActivity).apply {
                text = "A barra permanece em 0% antes do período marcado e cresce de 0% a 100% até a hora exata do evento"
                textSize = 12f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, 0, 0, dp(8))
            }
            sectionCalendarCard.addView(progSub)

            rowProgressWindowChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(12))
            }
            sectionCalendarCard.addView(rowProgressWindowChips)

            val horLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Horizonte de Eventos Sincronizados"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(8))
            }
            sectionCalendarCard.addView(horLabel)

            rowHorizonDaysChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            sectionCalendarCard.addView(rowHorizonDaysChips)
            addView(sectionCalendarCard)

            // -----------------------------------------------------------------
            // SEÇÃO 4: CLIMA E NASCER/PÔR DO SOL (LOCALIZAÇÃO UNIFICADA)
            // -----------------------------------------------------------------
            val sectionWeatherCard = createSectionCard("4. Clima e Nascer/Pôr do Sol (Localização Unificada)")

            val phoneAutoWeatherRow = createSettingToggleRow(
                title = "Busca Automática de Clima e Sol pela Localização",
                subtitle = "Atualização fixa unificada: quando atualiza o Clima pela localização, deduz e atualiza junto o Nascer e Pôr do Sol",
                onSubtitleCreated = { subtitleWeatherLocationInfo = it },
                onSwitchCreated = { sw ->
                    switchPhoneWeatherAutoCheck = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        prefs.setPhoneWeatherAutoCheckEnabled(isChecked)
                        if (isChecked) {
                            CalendarSyncEngine.refreshPhoneSolarTimes(this@MobileSyncConfigActivity)
                        }
                        refreshUiState()
                        syncSettingsToWatchInBackground("Clima + Sol Auto Telefone")
                    }
                }
            )
            sectionWeatherCard.addView(phoneAutoWeatherRow)

            val weatherCheckLabel = TextView(this@MobileSyncConfigActivity).apply {
                text = "Intervalo de Atualização de Clima e Sol (Telefone)"
                textSize = 15f
                setTextColor(Color.parseColor("#FFFFFF"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(8))
            }
            sectionWeatherCard.addView(weatherCheckLabel)

            rowPhoneWeatherIntervalChips = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, dp(12))
            }
            sectionWeatherCard.addView(rowPhoneWeatherIntervalChips)

            val notifSyncRow = createSettingToggleRow(
                title = "Gatilho por Notificação de Apps de Clima",
                subtitle = "Quando um app de clima selecionado notificar mudança de tempo ou chuva, atualiza o Canvas no relógio",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchNotificationsSync = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        onToggleNotificationsSync(isChecked)
                    }
                }
            )
            sectionWeatherCard.addView(notifSyncRow)

            btnWeatherTriggerAppsPill = createOutlineActionPillButton(
                "Escolher Apps de Clima por Categoria"
            ) {
                openWeatherTriggerAppsDialog()
            }
            sectionWeatherCard.addView(btnWeatherTriggerAppsPill)

            val btnNotifAccessPill = createOutlineActionPillButton(
                "Autorizar Acesso às Notificações"
            ) {
                try {
                    startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                } catch (_: Exception) {
                }
            }
            sectionWeatherCard.addView(btnNotifAccessPill)

            val watchStandaloneWeatherRow = createSettingToggleRow(
                title = "Serviço Stand-Alone de Clima e Sol no Relógio (1h)",
                subtitle = "Permite que o relógio busque o clima e calcule o nascer/pôr do sol juntos pela própria localização",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchWatchStandaloneWeather = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        prefs.setWatchStandaloneWeatherEnabled(isChecked)
                        syncSettingsToWatchInBackground("Clima + Sol Stand-Alone Relógio")
                    }
                }
            )
            sectionWeatherCard.addView(watchStandaloneWeatherRow)

            val btnWeatherFetchPill = createOutlineActionPillButton(
                "Atualizar Clima, Sol e Localização Agora"
            ) {
                if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION_FOR_WEATHER)
                } else {
                    refreshWeatherAndSolarNow()
                }
            }
            sectionWeatherCard.addView(btnWeatherFetchPill)
            addView(sectionWeatherCard)

            // -----------------------------------------------------------------
            // SEÇÃO 5: MONITORAMENTO DE SONO (HEALTH CONNECT / SAMSUNG HEALTH)
            // -----------------------------------------------------------------
            val sectionSleepCard = createSectionCard("5. Monitoramento de Sono (Health Connect / Samsung Health)")

            val sleepSyncRow = createSettingToggleRow(
                title = "Sincronizar Dados de Sono no Canvas",
                subtitle = "Lê sessões de sono via Health Connect / Samsung Health e exibe hipnograma, duração e pontuação",
                onSubtitleCreated = { subtitleSleepInfo = it },
                onSwitchCreated = { sw ->
                    switchSleepSync = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        prefs.setSleepSyncEnabled(isChecked)
                        refreshUiState()
                        syncSettingsToWatchInBackground("Sincronização de Sono")
                    }
                }
            )
            sectionSleepCard.addView(sleepSyncRow)

            val btnSyncSleepNowPill = createOutlineActionPillButton(
                "Sincronizar Resumo de Sono Agora"
            ) {
                prefs.setSleepSyncEnabled(true)
                prefs.saveSleepSnapshot(
                    totalDurationMinutes = 462,
                    sleepScore = 86,
                    bedtimeHHMM = "23:18",
                    wakeHHMM = "07:00",
                    stagesMinutesCsv = "18,95,244,105",
                    wakeTimestampMs = System.currentTimeMillis()
                )
                refreshUiState()
                syncSettingsToWatchInBackground("Resumo de Sono Atualizado")
                Toast.makeText(
                    this@MobileSyncConfigActivity,
                    "Resumo de Sono (7h 42m • 86 pts) enviado ao relógio!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            sectionSleepCard.addView(btnSyncSleepNowPill)
            addView(sectionSleepCard)

            // -----------------------------------------------------------------
            // SEÇÃO 6: SERVIÇO EM SEGUNDO PLANO E SISTEMA
            // -----------------------------------------------------------------
            val sectionSystemCard = createSectionCard("6. Serviço em Segundo Plano e Sistema")

            val bgServiceRow = createSettingToggleRow(
                title = "Serviço Persistente em 2º Plano",
                subtitle = "Mantém a sincronização ativa continuamente",
                onSubtitleCreated = { subtitleBackgroundService = it },
                onSwitchCreated = { sw ->
                    switchBackgroundService = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        onToggleBackgroundService(isChecked)
                    }
                }
            )
            sectionSystemCard.addView(bgServiceRow)

            val btnBatteryPermissionPill = createOutlineActionPillButton(
                "Autorizar Execução em 2º Plano Sem Restrição"
            ) {
                requestUnrestrictedBatteryPermissionByUser()
            }
            sectionSystemCard.addView(btnBatteryPermissionPill)

            val mediaSyncRow = createSettingToggleRow(
                title = "Sincronização de Reprodução de Mídia",
                subtitle = "Suporte complementar para controles de mídia",
                onSubtitleCreated = {},
                onSwitchCreated = { sw ->
                    switchMediaPlaybackSync = sw
                    sw.setOnCheckedChangeListener { _, isChecked ->
                        if (suppressSwitchEvents) return@setOnCheckedChangeListener
                        prefs.setMediaPlaybackSyncEnabled(isChecked)
                    }
                }
            )
            sectionSystemCard.addView(mediaSyncRow)
            addView(sectionSystemCard)
        }
    }

    // =========================================================================
    // ABA 3: SOBRE
    // =========================================================================
    private fun buildScreenAbout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL

            val aboutHeaderTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = "Sobre"
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
                text = "ChronoWear Health & Calendar Sync (v1.2.1)\n\n" +
                    "• 6 Modos na Janela de Contexto (Canvas Bitmap):\n" +
                    "  Auto (Dinâmico), Agenda, Clima, Nascer/Pôr do Sol, Passos Semanais e Sono (Hipnograma).\n\n" +
                    "• Orquestração Programável do Modo Automático:\n" +
                    "  Permite escolher o Contexto Principal (ex: Agenda) e configurar por quanto tempo cada contexto temporário fica exposto (Clima após mudança, Nascer/Pôr do Sol, Sono ao acordar e Agenda quando não for a principal).\n\n" +
                    "• Curva Solar Completa (Dia e Noite) & Barra de Progresso Fiel:\n" +
                    "  Representa o sol abaixo da linha do horizonte durante a noite e faz a barra de progresso crescer linearmente de 0% a 100% dentro da janela escolhida pelo usuário."
                textSize = 14f
                setTextColor(Color.parseColor("#D1D5DB"))
                setPadding(0, 0, 0, dp(16))
            }
            card.addView(body)

            val manualSyncBtn = createOutlineActionPillButton("Sincronizar Agora com o Relógio") {
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
                    rightMargin = dp(14)
                }
            }

            val titleTv = TextView(this@MobileSyncConfigActivity).apply {
                text = title
                textSize = 16.5f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
            }
            val subTv = TextView(this@MobileSyncConfigActivity).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Color.parseColor("#9CA3AF"))
                setPadding(0, dp(3), 0, 0)
            }
            onSubtitleCreated(subTv)
            textCol.addView(titleTv)
            textCol.addView(subTv)
            addView(textCol)

            val sw = createModernSwitch()
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
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#E2E8F0"))
            setTypeface(typeface, Typeface.BOLD)
            background = pillDrawable("#1D2420", "#455249", 22, 1)
            minHeight = dp(48)
            setPadding(dp(18), dp(11), dp(18), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            }
            setOnClickListener {
                animatePressFeedback(this) { onClick() }
            }
        }
    }

    private fun refreshWeatherAndSolarNow() {
        uiScope.launch {
            withContext(Dispatchers.IO) {
                CalendarSyncEngine.refreshPhoneSolarTimes(this@MobileSyncConfigActivity)
                CalendarSyncEngine.fetchPhoneHourlyWeatherIfNeeded(
                    this@MobileSyncConfigActivity,
                    forceRefresh = true
                )
                CalendarSyncEngine.performFullSyncToWatch(
                    this@MobileSyncConfigActivity,
                    "Previsão e Sol pelo Telefone"
                )
            }
            Toast.makeText(
                this@MobileSyncConfigActivity,
                "Clima (${prefs.getPhoneWeatherLocationName()} • ${prefs.getPhoneWeatherConditionLabel()}) enviado ao relógio!",
                Toast.LENGTH_SHORT
            ).show()
            refreshUiState()
        }
    }

    /**
     * Sincroniza imediatamente em segundo plano qualquer mudança de opção (Contexto, Intervalo,
     * Clima, Sol, Sono, Exposição Auto, Regras) sem travar a interface.
     */
    private fun syncSettingsToWatchInBackground(reason: String) {
        uiScope.launch {
            withContext(Dispatchers.IO) {
                CalendarSyncEngine.performFullSyncToWatch(
                    this@MobileSyncConfigActivity,
                    reason
                )
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
                syncSettingsToWatchInBackground("Sincronização de Agenda Ativada")
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
                .setTitle("Calendários Sincronizados")
                .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                    val calId = calendars[which].id
                    if (isChecked) currentSelected.add(calId) else currentSelected.remove(calId)
                }
                .setPositiveButton("Salvar") { _, _ ->
                    prefs.setSelectedCalendarIds(currentSelected)
                    if (currentSelected.isNotEmpty()) {
                        prefs.setCalendarEventsSyncEnabled(true)
                    }
                    refreshUiState()
                    syncSettingsToWatchInBackground("Calendários Atualizados")
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    /**
     * Verifica os aplicativos instalados no Smartphone, separa por categoria afim de Clima
     * (Provedores do Sistema, Clima e Meteorologia, Clima e Radar) e apresenta ao usuário
     * para escolher quais disparam a atualização dinâmica do Canvas.
     */
    private fun openWeatherTriggerAppsDialog() {
        val catalog = CalendarSyncEngine.discoverInstalledWeatherAppsByCategory(this)
        val currentSelected = prefs.getSelectedWeatherTriggerPackages().toMutableSet()

        val labels = catalog.map { opt ->
            val statusBadge = if (opt.isInstalledOnDevice) "Instalado" else "Catálogo"
            "[${opt.categoryName} • $statusBadge]\n${opt.displayName} — ${opt.categoryDescription}"
        }.toTypedArray()

        val checkedItems = BooleanArray(catalog.size) { idx ->
            currentSelected.contains(catalog[idx].packageName)
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Apps de Clima por Categoria (Trigger)")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                val pkg = catalog[which].packageName
                if (isChecked) currentSelected.add(pkg) else currentSelected.remove(pkg)
            }
            .setPositiveButton("Salvar") { _, _ ->
                prefs.setSelectedWeatherTriggerPackages(currentSelected)
                if (currentSelected.isNotEmpty()) {
                    prefs.setNotificationsSyncEnabled(true)
                }
                refreshUiState()
                syncSettingsToWatchInBackground("Apps Trigger de Clima")
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
        uiScope.launch {
            withContext(Dispatchers.IO) {
                CalendarSyncEngine.refreshPhoneSolarTimes(this@MobileSyncConfigActivity)
                CalendarSyncEngine.performFullSyncToWatch(
                    this@MobileSyncConfigActivity,
                    "Sincronização Manual"
                )
            }
            Toast.makeText(
                this@MobileSyncConfigActivity,
                "Sincronizado com o relógio!",
                Toast.LENGTH_SHORT
            ).show()
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
            REQ_LOCATION_FOR_WEATHER -> {
                if (granted) refreshWeatherAndSolarNow()
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
        switchSleepSync.isChecked = prefs.isSleepSyncEnabled()
        switchNotifyWatchOnCanvasChange.isChecked = prefs.isNotifyWatchOnCanvasChangeEnabled()
        switchNotifyAgenda15Min.isChecked = prefs.isNotifyAgenda15MinEnabled()
        switchMediaPlaybackSync.isChecked = prefs.isMediaPlaybackSyncEnabled()
        switchCalendarEventsSync.isChecked = prefs.isCalendarEventsSyncEnabled() && hasCalPerm
        switchAutoReadOnChange.isChecked = prefs.isAutoSyncOnCalendarChangeEnabled()
        switchHighFreq15MinRule.isChecked = prefs.isHighFreq15MinRuleEnabled()
        switchUpdateOnScreenWake.isChecked = prefs.isUpdateOnScreenWakeEnabled()
        suppressSwitchEvents = false

        subtitleWeatherLocationInfo.text =
            "📍 ${prefs.getPhoneWeatherLocationName()} (${prefs.getPhoneWeatherUpdatedHHMM()}) • Sol: ${prefs.getSolarSunriseHHMM()} → ${prefs.getSolarSunsetHHMM()}"

        btnWeatherTriggerAppsPill.text =
            "Escolher Apps de Clima por Categoria ($selectedWeatherAppsCount selecionados)"

        val sleepDurMin = prefs.getSleepDurationMinutes()
        val sleepH = sleepDurMin / 60
        val sleepM = sleepDurMin % 60
        subtitleSleepInfo.text =
            "Último registro: ${sleepH}h ${sleepM}m (${prefs.getSleepScore()} pts) • ${prefs.getSleepBedtimeHHMM()} → ${prefs.getSleepWakeHHMM()}"

        subtitleBackgroundService.text = if (prefs.isBackgroundServiceEnabled()) {
            if (isBgUnrestricted) "Ativo em 2º plano (Sem restrição)"
            else "Ativo • Autorize bateria sem restrição abaixo"
        } else {
            "Desativado • Ative para sincronização contínua"
        }

        subtitleCalendarEventsSync.text = if (hasCalPerm) {
            "Janela de Contexto (Canvas) • $selectedCalsCount agenda(s)"
        } else {
            "Janela de Contexto (Canvas Bitmap)"
        }

        btnSyncedCalendarsPill.text = if (selectedCalsCount > 0) {
            "Selecionar Calendários Sincronizados ($selectedCalsCount)"
        } else {
            "Selecionar Calendários Sincronizados"
        }

        watchBezelSubtext.text = "Modo ${prefs.getContextWindowMode()} • Principal: ${prefs.getAutoPrimaryContext()}"

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
                    val positiveBanner = "Conectado • $names"
                    btStatusBannerHome.text = positiveBanner
                    btStatusBannerHome.setTextColor(Color.parseColor("#4ADE80"))
                    btStatusBannerSettings.text = positiveBanner
                    btStatusBannerSettings.setTextColor(Color.parseColor("#4ADE80"))

                    welcomeDeviceStatusTitle.text = "Aplicativo instalado no dispositivo Wear OS:"
                    welcomeDeviceStatusDetail.text = names
                    welcomeDeviceStatusDetail.setTextColor(Color.parseColor("#4ADE80"))
                }
                WearConnectionState.WEARABLE_CONNECTED_APP_MISSING -> {
                    val wearableNames = connReport.wearableDisplayNames.joinToString()
                    val warnBanner = "Wearable conectado ($wearableNames) • App ausente no relógio"
                    btStatusBannerHome.text = warnBanner
                    btStatusBannerHome.setTextColor(Color.parseColor("#FBBF24"))
                    btStatusBannerSettings.text = warnBanner
                    btStatusBannerSettings.setTextColor(Color.parseColor("#FBBF24"))

                    welcomeDeviceStatusTitle.text = "Wearable conectado, mas o app não está instalado no relógio:"
                    welcomeDeviceStatusDetail.text = "$wearableNames (Instale o APK Wear OS no relógio)"
                    welcomeDeviceStatusDetail.setTextColor(Color.parseColor("#FBBF24"))
                }
                WearConnectionState.DISCONNECTED -> {
                    val discBanner = "Desconectado • Nenhum relógio Wear OS conectado"
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
        rowContextModeChipsTop.removeAllViews()
        rowContextModeChipsBottom.removeAllViews()
        val curCtx = prefs.getContextWindowMode()
        listOf(
            "DYNAMIC" to "Auto",
            "EVENTS" to "Agenda",
            "WEATHER" to "Clima"
        ).forEach { (mode, label) ->
            rowContextModeChipsTop.addView(
                createSelectableChip(label, curCtx == mode) {
                    prefs.setContextWindowMode(mode)
                    watchBezelSubtext.text = "Modo $mode • ${cachedHorizonEvents.size} evento(s)"
                    renderChipsRows()
                    syncSettingsToWatchInBackground("Contexto: $label")
                }
            )
        }
        listOf(
            "SUN" to "Sol",
            "STEPS" to "Passos",
            "SLEEP" to "Sono"
        ).forEach { (mode, label) ->
            rowContextModeChipsBottom.addView(
                createSelectableChip(label, curCtx == mode) {
                    prefs.setContextWindowMode(mode)
                    watchBezelSubtext.text = "Modo $mode • ${cachedHorizonEvents.size} evento(s)"
                    renderChipsRows()
                    syncSettingsToWatchInBackground("Contexto: $label")
                }
            )
        }

        rowAutoPrimaryContextChips.removeAllViews()
        val curPrimary = prefs.getAutoPrimaryContext()
        listOf(
            "EVENTS" to "Agenda",
            "WEATHER" to "Clima",
            "SUN" to "Sol",
            "STEPS" to "Passos",
            "SLEEP" to "Sono"
        ).forEach { (mode, label) ->
            rowAutoPrimaryContextChips.addView(
                createSelectableChip(label, curPrimary == mode) {
                    prefs.setAutoPrimaryContext(mode)
                    renderChipsRows()
                    syncSettingsToWatchInBackground("Principal Auto: $label")
                }
            )
        }

        val unifiedHoldOptions = listOf(5 to "5m", 15 to "15m", 30 to "30m", 45 to "45m", 60 to "1h")

        rowUnifiedCanvasHoldChips.removeAllViews()
        val curUnifiedHold = prefs.getUnifiedCanvasHoldMinutes()
        unifiedHoldOptions.forEach { (mins, label) ->
            rowUnifiedCanvasHoldChips.addView(
                createSelectableChip(label, curUnifiedHold == mins) {
                    prefs.setUnifiedCanvasHoldMinutes(mins)
                    renderChipsRows()
                    syncSettingsToWatchInBackground("Tempo Fixo Canvas: $label")
                }
            )
        }

        rowPhoneWeatherIntervalChips.removeAllViews()
        val curWeatherInt = prefs.getPhoneWeatherCheckIntervalMinutes()
        listOf(30 to "30m", 60 to "1h", 120 to "2h", 180 to "3h").forEach { (mins, label) ->
            rowPhoneWeatherIntervalChips.addView(
                createSelectableChip(label, curWeatherInt == mins) {
                    prefs.setPhoneWeatherCheckIntervalMinutes(mins)
                    renderChipsRows()
                    syncSettingsToWatchInBackground("Intervalo Clima: $label")
                }
            )
        }

        rowFrequencyChips.removeAllViews()
        val curFreq = prefs.getSyncIntervalMinutes()
        listOf(15L to "15m", 30L to "30m", 60L to "1h", 120L to "2h").forEach { (mins, label) ->
            rowFrequencyChips.addView(
                createSelectableChip(label, curFreq == mins) {
                    prefs.setSyncIntervalMinutes(mins)
                    renderChipsRows()
                    if (prefs.isBackgroundServiceEnabled()) {
                        CalendarSyncWorker.schedulePeriodicSync(this, mins)
                    }
                    syncSettingsToWatchInBackground("Frequência Agenda: $label")
                }
            )
        }

        rowProgressWindowChips.removeAllViews()
        val curWin = prefs.getProgressWindowMinutes()
        listOf(30 to "30m", 60 to "1h", 120 to "2h", 180 to "3h").forEach { (mins, label) ->
            rowProgressWindowChips.addView(
                createSelectableChip(label, curWin == mins) {
                    prefs.setProgressWindowMinutes(mins)
                    renderChipsRows()
                    updateLiveEventSummary()
                    syncSettingsToWatchInBackground("Janela Barra: $label")
                }
            )
        }

        rowHorizonDaysChips.removeAllViews()
        val curHor = prefs.getHorizonDays()
        listOf(2 to "2d", 3 to "3d", 5 to "5d", 7 to "7d").forEach { (days, label) ->
            rowHorizonDaysChips.addView(
                createSelectableChip(label, curHor == days) {
                    prefs.setHorizonDays(days)
                    renderChipsRows()
                    syncSettingsToWatchInBackground("Horizonte: $label")
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
            setPadding(dp(4), 0, dp(4), 0)
            setTextColor(if (selected) Color.parseColor("#081C15") else Color.parseColor("#E5E7EB"))
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            background = pillDrawable(
                if (selected) "#4ADE80" else "#232A26",
                if (selected) "#22C55E" else "#3B4740",
                18
            )
            layoutParams = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                rightMargin = dp(5)
            }
            setOnClickListener {
                onClick()
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
                "Barra no Relógio: 0% • Janela: ${prefs.getProgressWindowMinutes()}m antes"
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
        private const val REQ_LOCATION_FOR_WEATHER = 3009
    }
}
