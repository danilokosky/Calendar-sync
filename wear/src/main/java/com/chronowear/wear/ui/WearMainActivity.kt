package com.chronowear.wear.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.wear.tiles.TileService
import com.chronowear.wear.complication.ChronoWearComplicationUpdater
import com.chronowear.wear.data.AutonomousCountdownScheduler
import com.chronowear.wear.data.CachedWearEvent
import com.chronowear.wear.data.PhoneDataListenerService
import com.chronowear.wear.data.WatchAutonomousRepository
import com.chronowear.wear.tile.UpNextThreeEventsTileService
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
import java.util.Locale

class WearMainActivity : Activity() {

    private lateinit var repo: WatchAutonomousRepository
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null

    private lateinit var connectionStatusText: TextView
    private lateinit var nextEventTitleText: TextView
    private lateinit var nextEventCountdownText: TextView
    private lateinit var complicationStatusText: TextView
    private lateinit var contextModeButton: Button
    private lateinit var notifyCanvasChangeButton: Button
    private lateinit var notifyAgenda15MinButton: Button
    private lateinit var turboRuleButton: Button
    private lateinit var standaloneWeatherButton: Button
    private lateinit var standaloneSolarButton: Button

    private var upcomingThree: List<CachedWearEvent> = emptyList()

    private val cacheUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadEssentialState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = WatchAutonomousRepository.getInstance(this)
        AutonomousCountdownScheduler.ensureScreenWakeListenerRegistered(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
        buildEssentialWatchUi()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cacheUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cacheUpdateReceiver, filter)
        }
        reloadEssentialState()
        startTicker()
        announcePresenceAndRequestSync()
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(cacheUpdateReceiver)
        } catch (_: Exception) {
        }
        tickerJob?.cancel()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun pillBg(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun formatContextModeLabel(mode: String): String {
        return when (mode) {
            WatchAutonomousRepository.MODE_DYNAMIC -> "Contexto: Auto (Dinâmico)"
            WatchAutonomousRepository.MODE_EVENTS -> "Contexto: Agenda"
            WatchAutonomousRepository.MODE_WEATHER -> "Contexto: Clima"
            WatchAutonomousRepository.MODE_STEPS -> "Contexto: Passos"
            WatchAutonomousRepository.MODE_SUN -> "Contexto: Nascer/Pôr do Sol"
            WatchAutonomousRepository.MODE_SLEEP -> "Contexto: Sono"
            else -> "Contexto: Auto (Dinâmico)"
        }
    }

    private fun cycleContextWindowMode() {
        val order = listOf(
            WatchAutonomousRepository.MODE_DYNAMIC,
            WatchAutonomousRepository.MODE_EVENTS,
            WatchAutonomousRepository.MODE_WEATHER,
            WatchAutonomousRepository.MODE_STEPS,
            WatchAutonomousRepository.MODE_SUN,
            WatchAutonomousRepository.MODE_SLEEP
        )
        val cur = repo.getContextWindowMode()
        val next = order[(order.indexOf(cur) + 1) % order.size]
        repo.setContextWindowMode(next)
        contextModeButton.text = formatContextModeLabel(next)
        forceUpdateComplicationsAndTile()
    }

    private fun buildEssentialWatchUi() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#000000"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }

        val appTitle = TextView(this).apply {
            text = "ChronoWear Canvas"
            textSize = 14.5f
            setTextColor(Color.parseColor("#FFFFFF"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        container.addView(appTitle)

        connectionStatusText = TextView(this).apply {
            text = "Verificando conexão..."
            textSize = 11f
            setTextColor(Color.parseColor("#4ADE80"))
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        }
        container.addView(connectionStatusText)

        val eventCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pillBg("#141917", "#26312C", 18)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        nextEventTitleText = TextView(this).apply {
            text = "Sem eventos sincronizados"
            textSize = 12f
            setTextColor(Color.parseColor("#F8FAFC"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
        }
        eventCard.addView(nextEventTitleText)

        nextEventCountdownText = TextView(this).apply {
            text = "--:--"
            textSize = 14.5f
            setTextColor(Color.parseColor("#BAE6FD"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(3), 0, dp(2))
        }
        eventCard.addView(nextEventCountdownText)

        complicationStatusText = TextView(this).apply {
            text = "Janela de Contexto ativa"
            textSize = 10f
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
        }
        eventCard.addView(complicationStatusText)

        container.addView(eventCard)

        contextModeButton = Button(this).apply {
            text = formatContextModeLabel(repo.getContextWindowMode())
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.parseColor("#F5C438"))
            setTypeface(typeface, Typeface.BOLD)
            background = pillBg("#1C2420", "#384942", 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(34)
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                cycleContextWindowMode()
            }
        }
        container.addView(contextModeButton)

        notifyCanvasChangeButton = Button(this).apply {
            val enabled = repo.isNotifyOnCanvasChangeEnabled()
            text = if (enabled) "Notif. Atualiz. Canvas: ON" else "Notif. Atualiz. Canvas: OFF"
            isAllCaps = false
            textSize = 10.5f
            setTextColor(Color.parseColor(if (enabled) "#4ADE80" else "#94A3B8"))
            background = pillBg("#161D1B", "#2B3935", 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                val next = !repo.isNotifyOnCanvasChangeEnabled()
                repo.setNotifyOnCanvasChangeEnabled(next)
                text = if (next) "Notif. Atualiz. Canvas: ON" else "Notif. Atualiz. Canvas: OFF"
                setTextColor(Color.parseColor(if (next) "#4ADE80" else "#94A3B8"))
                Toast.makeText(
                    this@WearMainActivity,
                    if (next) "Notificações do Canvas ativadas no relógio" else "Notificações do Canvas desativadas",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        container.addView(notifyCanvasChangeButton)

        notifyAgenda15MinButton = Button(this).apply {
            val enabled = repo.isNotifyAgenda15MinEnabled()
            text = if (enabled) "Notif. Agenda (15m): ON" else "Notif. Agenda (15m): OFF"
            isAllCaps = false
            textSize = 10.5f
            setTextColor(Color.parseColor(if (enabled) "#4ADE80" else "#94A3B8"))
            background = pillBg("#161D1B", "#2B3935", 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                val next = !repo.isNotifyAgenda15MinEnabled()
                repo.setNotifyAgenda15MinEnabled(next)
                text = if (next) "Notif. Agenda (15m): ON" else "Notif. Agenda (15m): OFF"
                setTextColor(Color.parseColor(if (next) "#4ADE80" else "#94A3B8"))
                Toast.makeText(
                    this@WearMainActivity,
                    if (next) "Notificação de 15 min antes do compromisso: ATIVADA" else "Notificação de 15 min: DESATIVADA (apenas troca o Canvas)",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        container.addView(notifyAgenda15MinButton)

        turboRuleButton = Button(this).apply {
            text = if (repo.isHighFreq15MinRuleEnabled()) "Turbo 1m (15m finais): ATIVO" else "Turbo 1m (15m finais): OFF"
            isAllCaps = false
            textSize = 10.5f
            setTextColor(Color.parseColor("#E2E8F0"))
            background = pillBg("#161D1B", "#2B3935", 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                val next = !repo.isHighFreq15MinRuleEnabled()
                repo.setHighFreq15MinRuleEnabled(next)
                text = if (next) "Turbo 1m (15m finais): ATIVO" else "Turbo 1m (15m finais): OFF"
                AutonomousCountdownScheduler.scheduleNextTransition(this@WearMainActivity)
                forceUpdateComplicationsAndTile()
            }
        }
        container.addView(turboRuleButton)

        standaloneWeatherButton = Button(this).apply {
            val enabled = repo.isWatchStandaloneWeatherEnabled()
            text = if (enabled) "Clima Stand-Alone (1h): ON" else "Clima Stand-Alone (1h): OFF"
            isAllCaps = false
            textSize = 10.5f
            setTextColor(Color.parseColor(if (enabled) "#4ADE80" else "#94A3B8"))
            background = pillBg("#161D1B", "#2B3935", 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                val next = !repo.isWatchStandaloneWeatherEnabled()
                repo.setWatchStandaloneWeatherEnabled(next)
                text = if (next) "Clima Stand-Alone (1h): ON" else "Clima Stand-Alone (1h): OFF"
                setTextColor(Color.parseColor(if (next) "#4ADE80" else "#94A3B8"))
                if (next) {
                    AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(
                        this@WearMainActivity,
                        forceRefresh = true
                    )
                }
                AutonomousCountdownScheduler.scheduleNextTransition(this@WearMainActivity)
                forceUpdateComplicationsAndTile()
            }
        }
        container.addView(standaloneWeatherButton)

        standaloneSolarButton = Button(this).apply {
            val enabled = repo.isWatchStandaloneSolarEnabled()
            text = if (enabled) "Solar Stand-Alone: ON" else "Solar Stand-Alone: OFF"
            isAllCaps = false
            textSize = 10.5f
            setTextColor(Color.parseColor(if (enabled) "#4ADE80" else "#94A3B8"))
            background = pillBg("#161D1B", "#2B3935", 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            ).apply { bottomMargin = dp(6) }
            setOnClickListener {
                val next = !repo.isWatchStandaloneSolarEnabled()
                repo.setWatchStandaloneSolarEnabled(next)
                text = if (next) "Solar Stand-Alone: ON" else "Solar Stand-Alone: OFF"
                setTextColor(Color.parseColor(if (next) "#4ADE80" else "#94A3B8"))
                if (next) {
                    AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(
                        this@WearMainActivity,
                        forceRefresh = true
                    )
                }
                AutonomousCountdownScheduler.scheduleNextTransition(this@WearMainActivity)
                forceUpdateComplicationsAndTile()
            }
        }
        container.addView(standaloneSolarButton)

        val notifAccessBtn = Button(this).apply {
            text = "Acesso Notificações (Clima)"
            isAllCaps = false
            textSize = 10.5f
            setTextColor(Color.parseColor("#BAE6FD"))
            background = pillBg("#141C22", "#283844", 18)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(32)
            ).apply { bottomMargin = dp(8) }
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(
                        this@WearMainActivity,
                        "Ative em Config. > Apps > Acesso Especial > Acesso a Notificações",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        container.addView(notifAccessBtn)

        val syncBtn = Button(this).apply {
            text = "Atualizar Clima e Canvas"
            isAllCaps = false
            textSize = 11.5f
            setTextColor(Color.parseColor("#0B191E"))
            setTypeface(typeface, Typeface.BOLD)
            background = pillBg("#BAE6FD", "#7DD3FC", 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(34)
            )
            setPadding(dp(18), 0, dp(18), 0)
            setOnClickListener {
                Toast.makeText(this@WearMainActivity, "Atualizando Clima, Local e Canvas...", Toast.LENGTH_SHORT).show()
                AutonomousCountdownScheduler.runStandaloneWatchWeatherCheckIfDue(
                    this@WearMainActivity,
                    forceRefresh = true
                )
                announcePresenceAndRequestSync()
                forceUpdateComplicationsAndTile()
            }
        }
        container.addView(syncBtn)

        scrollView.addView(container)
        setContentView(scrollView)
    }

    private fun reloadEssentialState() {
        val nowMs = System.currentTimeMillis()
        upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        contextModeButton.text = formatContextModeLabel(repo.getContextWindowMode())
        val notifCanvasOn = repo.isNotifyOnCanvasChangeEnabled()
        notifyCanvasChangeButton.text = if (notifCanvasOn) {
            "Notif. Atualiz. Canvas: ON"
        } else {
            "Notif. Atualiz. Canvas: OFF"
        }
        notifyCanvasChangeButton.setTextColor(
            Color.parseColor(if (notifCanvasOn) "#4ADE80" else "#94A3B8")
        )
        val notifAgenda15On = repo.isNotifyAgenda15MinEnabled()
        notifyAgenda15MinButton.text = if (notifAgenda15On) {
            "Notif. Agenda (15m): ON"
        } else {
            "Notif. Agenda (15m): OFF"
        }
        notifyAgenda15MinButton.setTextColor(
            Color.parseColor(if (notifAgenda15On) "#4ADE80" else "#94A3B8")
        )
        turboRuleButton.text = if (repo.isHighFreq15MinRuleEnabled()) {
            "Turbo 1m (15m finais): ATIVO"
        } else {
            "Turbo 1m (15m finais): OFF"
        }
        val standAloneOn = repo.isWatchStandaloneWeatherEnabled()
        standaloneWeatherButton.text = if (standAloneOn) {
            "Clima Stand-Alone (1h): ON"
        } else {
            "Clima Stand-Alone (1h): OFF"
        }
        standaloneWeatherButton.setTextColor(
            Color.parseColor(if (standAloneOn) "#4ADE80" else "#94A3B8")
        )
        val solarStandAloneOn = repo.isWatchStandaloneSolarEnabled()
        standaloneSolarButton.text = if (solarStandAloneOn) {
            "Solar Stand-Alone: ON"
        } else {
            "Solar Stand-Alone: OFF"
        }
        standaloneSolarButton.setTextColor(
            Color.parseColor(if (solarStandAloneOn) "#4ADE80" else "#94A3B8")
        )
        updateCountdownTick()

        uiScope.launch {
            val nodes = withContext(Dispatchers.IO) {
                try {
                    Wearable.getNodeClient(this@WearMainActivity).connectedNodes.await()
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (nodes.isNotEmpty()) {
                connectionStatusText.text = "Conectado • ${nodes.first().displayName}"
                connectionStatusText.setTextColor(Color.parseColor("#4ADE80"))
            } else {
                connectionStatusText.text = "Offline • Cache (${repo.getHorizonDays()}d)"
                connectionStatusText.setTextColor(Color.parseColor("#FBBF24"))
            }
        }
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = uiScope.launch {
            while (isActive) {
                updateCountdownTick()
                delay(1000L)
            }
        }
    }

    private fun updateCountdownTick() {
        val nowMs = System.currentTimeMillis()
        val first = upcomingThree.firstOrNull { it.startEpochMs > nowMs }
        val effectiveContext = repo.resolveEffectiveContextMode(nowMs, upcomingThree)
        val weatherSnap = repo.getHourlyWeatherSnapshot(nowMs)
        if (first == null) {
            nextEventTitleText.text = "Agenda livre"
            nextEventCountdownText.text = "Sem próximo evento"
            complicationStatusText.text = "Canvas: $effectiveContext • 📍 ${weatherSnap.locationName} (${weatherSnap.updatedTimeHHMM})"
            return
        }

        val remainingMs = (first.startEpochMs - nowMs).coerceAtLeast(0L)
        val totalSec = remainingMs / 1000L
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L

        nextEventTitleText.text = "${first.formatStartHHMM()} • ${first.title}"
        nextEventCountdownText.text = if (hours > 0) {
            String.format(Locale.getDefault(), "Em %02dh %02dm", hours, mins)
        } else {
            String.format(Locale.getDefault(), "Em %02dm %02ds", mins, secs)
        }
        complicationStatusText.text = "Canvas: $effectiveContext • 📍 ${weatherSnap.locationName} (${weatherSnap.updatedTimeHHMM})"
    }

    private fun announcePresenceAndRequestSync() {
        uiScope.launch(Dispatchers.IO) {
            try {
                Wearable.getCapabilityClient(this@WearMainActivity)
                    .addLocalCapability(PhoneDataListenerService.CAPABILITY_WATCH_APP_INSTALLED)
                    .await()
            } catch (_: Exception) {
            }
            try {
                val nodes = Wearable.getNodeClient(this@WearMainActivity).connectedNodes.await()
                for (node in nodes) {
                    Wearable.getMessageClient(this@WearMainActivity).sendMessage(
                        node.id,
                        "/chronowear/pong_watch_app",
                        "installed:1.1.2-wear".toByteArray(Charsets.UTF_8)
                    ).await()
                    Wearable.getMessageClient(this@WearMainActivity).sendMessage(
                        node.id,
                        "/chronowear/request_sync_from_watch",
                        ByteArray(0)
                    ).await()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun forceUpdateComplicationsAndTile() {
        ChronoWearComplicationUpdater.requestUpdateAllSlots(this)

        TileService.getUpdater(this)
            .requestUpdate(UpNextThreeEventsTileService::class.java)
    }
}
