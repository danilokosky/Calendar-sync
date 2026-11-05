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

    private lateinit var prefs: SyncPreferences
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null

    private lateinit var permissionBannerCard: LinearLayout
    private lateinit var wearNodesStatusText: TextView
    private lateinit var lastSyncDetailsText: TextView
    private lateinit var liveCountdownTitleText: TextView
    private lateinit var liveCountdownClockText: TextView
    private lateinit var liveProgressPercentText: TextView
    private lateinit var liveProgressFillView: View
    private lateinit var calendarsListContainer: LinearLayout
    private lateinit var upcomingEventsContainer: LinearLayout
    private lateinit var progressButtonsContainer: LinearLayout
    private lateinit var horizonButtonsContainer: LinearLayout

    private var cachedHorizonEvents: List<LocalCalendarEvent> = emptyList()

    private val syncCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshDashboardData()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SyncPreferences(this)
        window.statusBarColor = Color.parseColor("#090D16")
        window.navigationBarColor = Color.parseColor("#090D16")

        buildFullDashboardUi()
        requestRequiredPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(SyncPreferences.ACTION_SYNC_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(syncCompletedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(syncCompletedReceiver, filter)
        }
        refreshDashboardData()
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

    private fun makeCardBackground(
        fillHex: String = "#111827",
        strokeHex: String = "#1E293B",
        cornerDp: Int = 18
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(cornerDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun buildFullDashboardUi() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(36))
        }

        // CABEÇALHO DO APP
        val kicker = TextView(this).apply {
            text = "CHRONOWEAR • COMPANION ANDROID + WEAR OS"
            textSize = 11f
            setTextColor(Color.parseColor("#F59E0B"))
            typeface = Typeface.MONOSPACE
        }
        root.addView(kicker)

        val title = TextView(this).apply {
            text = "Sincronizador de Agenda & Complications"
            textSize = 22f
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Envia os próximos eventos para o relógio com barra de progresso automática (1h/2h) e cache autônomo offline."
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(16))
        }
        root.addView(subtitle)

        // BANNER DE PERMISSÕES (Aparece apenas quando READ_CALENDAR não foi concedido)
        permissionBannerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBackground("#2A1215", "#EF4444", 16)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            layoutParams = lp

            val warnTitle = TextView(this@MobileSyncConfigActivity).apply {
                text = "Permissão de Calendário Necessária"
                textSize = 16f
                setTextColor(Color.parseColor("#FCA5A5"))
                setTypeface(typeface, Typeface.BOLD)
            }
            addView(warnTitle)

            val warnDesc = TextView(this@MobileSyncConfigActivity).apply {
                text = "Para listar seus calendários e sincronizar seus eventos com o Smartwatch em segundo plano, conceda acesso à Agenda."
                textSize = 13f
                setTextColor(Color.parseColor("#FECACA"))
                setPadding(0, dp(6), 0, dp(12))
            }
            addView(warnDesc)

            val btnRow = LinearLayout(this@MobileSyncConfigActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            val grantBtn = Button(this@MobileSyncConfigActivity).apply {
                text = "Solicitar Permissão"
                setTextColor(Color.parseColor("#090D16"))
                background = makeCardBackground("#F59E0B", "#F59E0B", 10)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener {
                    requestRequiredPermissionsIfNeeded(forcePrompt = true)
                }
            }
            btnRow.addView(grantBtn)

            val settingsBtn = Button(this@MobileSyncConfigActivity).apply {
                text = "Abrir Ajustes do App"
                setTextColor(Color.parseColor("#F8FAFC"))
                background = makeCardBackground("#1E293B", "#334155", 10)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                val blp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(10) }
                layoutParams = blp
                setOnClickListener {
                    val intent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null)
                    )
                    startActivity(intent)
                }
            }
            btnRow.addView(settingsBtn)
            addView(btnRow)
        }
        root.addView(permissionBannerCard)

        // CARD 1: STATUS DA CONEXÃO WEAR OS & SERVIÇO EM SEGUNDO PLANO
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBackground("#111827", "#1E293B", 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            layoutParams = lp
        }

        val statusHeader = TextView(this).apply {
            text = "STATUS DO SERVIÇO & RELÓGIO WEAR OS"
            textSize = 11f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.MONOSPACE
        }
        statusCard.addView(statusHeader)

        wearNodesStatusText = TextView(this).apply {
            text = "Verificando conexão com Smartwatch..."
            textSize = 15f
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(4))
        }
        statusCard.addView(wearNodesStatusText)

        lastSyncDetailsText = TextView(this).apply {
            text = "Último envio: aguardando..."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(12))
        }
        statusCard.addView(lastSyncDetailsText)

        val fgServiceSwitch = Switch(this).apply {
            text = "Serviço Contínuo em 2º Plano (Monitorar Agenda ao Vivo)"
            textSize = 13f
            setTextColor(Color.parseColor("#E2E8F0"))
            isChecked = prefs.isForegroundMonitorEnabled()
            setPadding(0, dp(4), 0, dp(12))
            setOnCheckedChangeListener { _, isChecked ->
                prefs.setForegroundMonitorEnabled(isChecked)
                if (isChecked && CalendarSyncEngine.hasCalendarPermission(this@MobileSyncConfigActivity)) {
                    CalendarSyncForegroundService.startOrUpdate(this@MobileSyncConfigActivity)
                } else if (!isChecked) {
                    CalendarSyncForegroundService.stop(this@MobileSyncConfigActivity)
                }
            }
        }
        statusCard.addView(fgServiceSwitch)

        val syncNowBtn = Button(this).apply {
            text = "Sincronizar Agora com o Smartwatch"
            setTextColor(Color.parseColor("#090D16"))
            setTypeface(typeface, Typeface.BOLD)
            background = makeCardBackground("#F59E0B", "#F59E0B", 12)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                triggerManualSyncWithFeedback()
            }
        }
        statusCard.addView(syncNowBtn)
        root.addView(statusCard)

        // CARD 2: PRÉVIA AO VIVO DO TEMPORIZADOR E BARRA DE PROGRESSO (COMO NO RELÓGIO)
        val previewCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBackground("#0F172A", "#334155", 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            layoutParams = lp
        }

        val previewKicker = TextView(this).apply {
            text = "TEMPORIZADOR & BARRA RANGED_VALUE EM TEMPO REAL"
            textSize = 11f
            setTextColor(Color.parseColor("#F59E0B"))
            typeface = Typeface.MONOSPACE
        }
        previewCard.addView(previewKicker)

        liveCountdownTitleText = TextView(this).apply {
            text = "Calculando próximo evento..."
            textSize = 16f
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(2))
        }
        previewCard.addView(liveCountdownTitleText)

        liveCountdownClockText = TextView(this).apply {
            text = "--:--:--"
            textSize = 24f
            setTextColor(Color.parseColor("#F59E0B"))
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, dp(10))
        }
        previewCard.addView(liveCountdownClockText)

        // Barra de progresso visual
        val progressTrack = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeCardBackground("#1E293B", "#334155", 8)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(14)
            )
        }
        liveProgressFillView = View(this).apply {
            background = makeCardBackground("#F59E0B", "#F59E0B", 8)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        progressTrack.addView(liveProgressFillView)
        previewCard.addView(progressTrack)

        liveProgressPercentText = TextView(this).apply {
            text = "Janela de progresso: 0%"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
        }
        previewCard.addView(liveProgressPercentText)
        root.addView(previewCard)

        // CARD 3: CONFIGURAÇÃO DA BARRA DE PROGRESSO (30m, 1h, 2h, 3h) E HORIZONTE AUTÔNOMO (2d, 3d, 5d, 7d)
        val configCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBackground("#111827", "#1E293B", 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            layoutParams = lp
        }

        val configTitle = TextView(this).apply {
            text = "Janela da Barra de Progresso (Antes do Evento)"
            textSize = 14f
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
        }
        configCard.addView(configTitle)

        progressButtonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(16))
        }
        configCard.addView(progressButtonsContainer)

        val horizonTitle = TextView(this).apply {
            text = "Horizonte de Cache Autônomo Offline no Relógio"
            textSize = 14f
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
        }
        configCard.addView(horizonTitle)

        horizonButtonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        configCard.addView(horizonButtonsContainer)
        root.addView(configCard)

        // CARD 4: SELEÇÃO DE CALENDÁRIOS DO APARELHO
        val calCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBackground("#111827", "#1E293B", 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            layoutParams = lp
        }

        val calHeader = TextView(this).apply {
            text = "Calendários Sincronizados do Smartphone"
            textSize = 15f
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        calCard.addView(calHeader)

        calendarsListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        calCard.addView(calendarsListContainer)
        root.addView(calCard)

        // CARD 5: PRÓXIMOS EVENTOS ENCONTRADOS NA AGENDA
        val eventsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeCardBackground("#111827", "#1E293B", 18)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val eventsHeader = TextView(this).apply {
            text = "Próximos Eventos no Horizonte (Enviados ao Relógio)"
            textSize = 15f
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
        eventsCard.addView(eventsHeader)

        upcomingEventsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        eventsCard.addView(upcomingEventsContainer)
        root.addView(eventsCard)

        scroll.addView(root)
        setContentView(scroll)

        renderOptionChips()
    }

    private fun renderOptionChips() {
        progressButtonsContainer.removeAllViews()
        val currentWin = prefs.getProgressWindowMinutes()
        listOf(30 to "30 min", 60 to "1 hora", 120 to "2 horas", 180 to "3 horas").forEach { (mins, label) ->
            val selected = (currentWin == mins)
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                setTextColor(if (selected) Color.parseColor("#090D16") else Color.parseColor("#CBD5E1"))
                background = makeCardBackground(
                    if (selected) "#F59E0B" else "#090D16",
                    if (selected) "#F59E0B" else "#334155",
                    10
                )
                val lp = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    rightMargin = dp(6)
                }
                layoutParams = lp
                setOnClickListener {
                    prefs.setProgressWindowMinutes(mins)
                    renderOptionChips()
                    triggerManualSyncWithFeedback()
                }
            }
            progressButtonsContainer.addView(btn)
        }

        horizonButtonsContainer.removeAllViews()
        val currentHor = prefs.getHorizonDays()
        listOf(2 to "2 dias", 3 to "3 dias", 5 to "5 dias", 7 to "7 dias").forEach { (days, label) ->
            val selected = (currentHor == days)
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                setTextColor(if (selected) Color.parseColor("#090D16") else Color.parseColor("#CBD5E1"))
                background = makeCardBackground(
                    if (selected) "#38BDF8" else "#090D16",
                    if (selected) "#38BDF8" else "#334155",
                    10
                )
                val lp = LinearLayout.LayoutParams(0, dp(38), 1f).apply {
                    rightMargin = dp(6)
                }
                layoutParams = lp
                setOnClickListener {
                    prefs.setHorizonDays(days)
                    renderOptionChips()
                    triggerManualSyncWithFeedback()
                }
            }
            horizonButtonsContainer.addView(btn)
        }
    }

    private fun requestRequiredPermissionsIfNeeded(forcePrompt: Boolean = false) {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_CALENDAR)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), 1001)
        } else if (forcePrompt) {
            Toast.makeText(this, "Permissão já concedida!", Toast.LENGTH_SHORT).show()
            onPermissionsReady()
        } else {
            onPermissionsReady()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            onPermissionsReady()
        }
    }

    private fun onPermissionsReady() {
        val hasCal = CalendarSyncEngine.hasCalendarPermission(this)
        permissionBannerCard.visibility = if (hasCal) View.GONE else View.VISIBLE

        if (hasCal) {
            CalendarSyncWorker.schedulePeriodicSync(this, prefs.getSyncIntervalMinutes())
            if (prefs.isForegroundMonitorEnabled()) {
                try {
                    CalendarSyncForegroundService.startOrUpdate(this)
                } catch (_: Exception) {
                }
            }
            refreshDashboardData()
        }
    }

    private fun triggerManualSyncWithFeedback() {
        if (!CalendarSyncEngine.hasCalendarPermission(this)) {
            requestRequiredPermissionsIfNeeded(forcePrompt = true)
            return
        }
        Toast.makeText(this, "Sincronizando agenda com o Smartwatch...", Toast.LENGTH_SHORT).show()
        uiScope.launch {
            withContext(Dispatchers.IO) {
                CalendarSyncEngine.performFullSyncToWatch(this@MobileSyncConfigActivity)
            }
            refreshDashboardData()
        }
    }

    private fun refreshDashboardData() {
        val hasCal = CalendarSyncEngine.hasCalendarPermission(this)
        permissionBannerCard.visibility = if (hasCal) View.GONE else View.VISIBLE

        uiScope.launch {
            val calendars = withContext(Dispatchers.IO) {
                CalendarSyncEngine.loadDeviceCalendars(this@MobileSyncConfigActivity)
            }
            val events = withContext(Dispatchers.IO) {
                CalendarSyncEngine.queryHorizonEvents(this@MobileSyncConfigActivity)
            }
            val connectedNodes = withContext(Dispatchers.IO) {
                try {
                    Wearable.getNodeClient(this@MobileSyncConfigActivity).connectedNodes.await()
                } catch (_: Exception) {
                    emptyList()
                }
            }

            cachedHorizonEvents = events
            val summary = prefs.getLastSyncSummary()

            wearNodesStatusText.text = if (connectedNodes.isNotEmpty()) {
                "Conectado a: ${connectedNodes.joinToString { it.displayName }}"
            } else {
                "Nenhum relógio conectado no momento (Fila Data Layer pronta)"
            }

            val timeFormatted = if (summary.timestampMs > 0L) {
                SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(summary.timestampMs))
            } else {
                "nunca"
            }
            lastSyncDetailsText.text =
                "Último envio: $timeFormatted • ${events.size} eventos no horizonte de ${prefs.getHorizonDays()} dias\nStatus: ${summary.status}"

            populateCalendarsRows(calendars)
            populateUpcomingEventsRows(events)
            updateLiveCountdownTick()
        }
    }

    private fun populateCalendarsRows(calendars: List<LocalCalendarItem>) {
        calendarsListContainer.removeAllViews()
        if (calendars.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = if (CalendarSyncEngine.hasCalendarPermission(this@MobileSyncConfigActivity)) {
                    "Nenhum calendário encontrado na conta do aparelho."
                } else {
                    "Conceda a permissão de Calendário acima para carregar suas agendas."
                }
                textSize = 13f
                setTextColor(Color.parseColor("#94A3B8"))
            }
            calendarsListContainer.addView(emptyTv)
            return
        }

        val selectedIds = prefs.getSelectedCalendarIds().toMutableSet()
        calendars.forEach { cal ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))
            }

            val colorDot = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(cal.colorInt)
                }
                layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    rightMargin = dp(10)
                }
            }
            row.addView(colorDot)

            val labelCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameTv = TextView(this).apply {
                text = cal.displayName
                textSize = 14f
                setTextColor(Color.parseColor("#F1F5F9"))
                setTypeface(typeface, Typeface.BOLD)
            }
            val subTv = TextView(this).apply {
                text = "${cal.accountName} • ${cal.upcomingEventsCount} eventos nos próximos ${prefs.getHorizonDays()}d"
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
            }
            labelCol.addView(nameTv)
            labelCol.addView(subTv)
            row.addView(labelCol)

            val sw = Switch(this).apply {
                isChecked = selectedIds.contains(cal.id)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedIds.add(cal.id) else selectedIds.remove(cal.id)
                    prefs.setSelectedCalendarIds(selectedIds)
                    triggerManualSyncWithFeedback()
                }
            }
            row.addView(sw)
            calendarsListContainer.addView(row)
        }
    }

    private fun populateUpcomingEventsRows(events: List<LocalCalendarEvent>) {
        upcomingEventsContainer.removeAllViews()
        if (events.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Nenhum evento futuro encontrado nos calendários selecionados para os próximos ${prefs.getHorizonDays()} dias."
                textSize = 13f
                setTextColor(Color.parseColor("#94A3B8"))
            }
            upcomingEventsContainer.addView(emptyTv)
            return
        }

        val sdf = SimpleDateFormat("EEE dd/MM • HH:mm", Locale.getDefault())
        events.take(8).forEachIndexed { index, ev ->
            val itemBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = makeCardBackground("#090D16", "#1E293B", 12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                layoutParams = lp
            }

            val topLine = TextView(this).apply {
                text = "#${index + 1} • ${sdf.format(Date(ev.startEpochMs))} (${ev.calendarName})"
                textSize = 11f
                setTextColor(Color.parseColor("#F59E0B"))
                typeface = Typeface.MONOSPACE
            }
            val titleLine = TextView(this).apply {
                text = ev.title
                textSize = 14f
                setTextColor(Color.parseColor("#F8FAFC"))
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
            }
            itemBox.addView(topLine)
            itemBox.addView(titleLine)
            upcomingEventsContainer.addView(itemBox)
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
            liveCountdownTitleText.text = "Sem próximo evento na fila"
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
        liveCountdownTitleText.text = "Próximo (${startStr}): ${nextEv.title}"
        liveCountdownClockText.text = if (days > 0) {
            String.format(Locale.getDefault(), "Faltam %dd %02dh %02dm %02ds", days, hours, mins, secs)
        } else {
            String.format(Locale.getDefault(), "Faltam %02dh %02dm %02ds", hours, mins, secs)
        }

        val windowMs = TimeUnit.MINUTES.toMillis(prefs.getProgressWindowMinutes().toLong()).toFloat()
        val ratio = if (remainingMs >= windowMs) {
            0f
        } else {
            ((1f - (remainingMs.toFloat() / windowMs))).coerceIn(0f, 1f)
        }
        val pct = (ratio * 100f).toInt()
        liveProgressPercentText.text =
            "Barra no Relógio: $pct% preenchida (Inicia ${prefs.getProgressWindowMinutes()} min antes)"

        val parentWidth = (liveProgressFillView.parent as? View)?.width ?: 0
        if (parentWidth > 0) {
            val fillPx = (parentWidth * ratio).toInt().coerceAtLeast(if (ratio > 0f) dp(6) else 0)
            liveProgressFillView.layoutParams = LinearLayout.LayoutParams(
                fillPx,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
    }
}
