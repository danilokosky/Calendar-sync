package com.chronowear.wear.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.chronowear.wear.complication.NextEventComplicationService
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class WearMainActivity : Activity() {

    private lateinit var repo: WatchAutonomousRepository
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tickerJob: Job? = null

    private lateinit var modeStatusText: TextView
    private lateinit var countdownHeaderText: TextView
    private lateinit var countdownTimerText: TextView
    private lateinit var progressTrackView: LinearLayout
    private lateinit var progressFillView: View
    private lateinit var progressLabelText: TextView
    private lateinit var topThreeEventsContainer: LinearLayout

    private var upcomingThree: List<CachedWearEvent> = emptyList()

    private val cacheUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadFromLocalCache()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = WatchAutonomousRepository.getInstance(this)
        buildWatchUi()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(PhoneDataListenerService.ACTION_WEAR_CACHE_UPDATED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cacheUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cacheUpdateReceiver, filter)
        }
        reloadFromLocalCache()
        startSecondBySecondTicker()
        requestSyncFromPhoneSilently()
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

    private fun roundedBg(fillHex: String, strokeHex: String, radiusDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(Color.parseColor(fillHex))
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun buildWatchUi() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#070A12"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(26), dp(18), dp(36))
        }

        modeStatusText = TextView(this).apply {
            text = "CHRONOWEAR • CACHE AUTÔNOMO"
            textSize = 10f
            setTextColor(Color.parseColor("#38BDF8"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        container.addView(modeStatusText)

        // Bloco de Contagem Regressiva e Barra de Progresso
        val topCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#111827", "#1E293B", 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
                bottomMargin = dp(10)
            }
            layoutParams = lp
        }

        countdownHeaderText = TextView(this).apply {
            text = "Próximo Evento"
            textSize = 11f
            setTextColor(Color.parseColor("#CBD5E1"))
            gravity = Gravity.CENTER
        }
        topCard.addView(countdownHeaderText)

        countdownTimerText = TextView(this).apply {
            text = "--:--:--"
            textSize = 18f
            setTextColor(Color.parseColor("#F59E0B"))
            typeface = Typeface.MONOSPACE
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        }
        topCard.addView(countdownTimerText)

        progressTrackView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = roundedBg("#1E293B", "#334155", 6)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(8)
            )
        }
        progressFillView = View(this).apply {
            background = roundedBg("#F59E0B", "#F59E0B", 6)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
        }
        progressTrackView.addView(progressFillView)
        topCard.addView(progressTrackView)

        progressLabelText = TextView(this).apply {
            text = "Barra: 0%"
            textSize = 10f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        topCard.addView(progressLabelText)
        container.addView(topCard)

        // Janela dos Próximos 3 Eventos
        val sectionTitle = TextView(this).apply {
            text = "PRÓXIMOS 3 EVENTOS"
            textSize = 10f
            setTextColor(Color.parseColor("#F59E0B"))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        container.addView(sectionTitle)

        topThreeEventsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(topThreeEventsContainer)

        // Botão para solicitar sincronização ao smartphone ou atualizar Complications
        val syncButton = Button(this).apply {
            text = "Sincronizar / Atualizar"
            textSize = 11f
            setTextColor(Color.parseColor("#090D16"))
            background = roundedBg("#F59E0B", "#F59E0B", 14)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            layoutParams = lp
            setOnClickListener {
                Toast.makeText(
                    this@WearMainActivity,
                    "Solicitando agenda ao smartphone...",
                    Toast.LENGTH_SHORT
                ).show()
                requestSyncFromPhoneSilently()
                forceUpdateComplicationsAndTile()
            }
        }
        container.addView(syncButton)

        scrollView.addView(container)
        setContentView(scrollView)
    }

    private fun reloadFromLocalCache() {
        val nowMs = System.currentTimeMillis()
        upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        val lastSyncMs = repo.getLastSyncTimestampMs()
        val syncTimeStr = if (lastSyncMs > 0L) {
            SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(lastSyncMs))
        } else {
            "Aguardando 1º sync"
        }

        modeStatusText.text = "CACHE ${repo.getHorizonDays()}D • SYNC $syncTimeStr"

        topThreeEventsContainer.removeAllViews()
        if (upcomingThree.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Sem eventos futuros no cache local.\nAbra o app no Smartphone e toque em Sincronizar."
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(8), dp(6), dp(8))
            }
            topThreeEventsContainer.addView(emptyTv)
        } else {
            upcomingThree.forEachIndexed { idx, ev ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = roundedBg("#111827", "#1E293B", 12)
                    setPadding(dp(10), dp(8), dp(10), dp(8))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                    layoutParams = lp
                }

                val timeLine = TextView(this).apply {
                    text = "#${idx + 1} • ${ev.formatStartHHMM()}"
                    textSize = 10f
                    setTextColor(Color.parseColor("#38BDF8"))
                    typeface = Typeface.MONOSPACE
                }
                val titleLine = TextView(this).apply {
                    text = ev.title
                    textSize = 12f
                    setTextColor(Color.parseColor("#F8FAFC"))
                    setTypeface(typeface, Typeface.BOLD)
                    maxLines = 2
                }
                card.addView(timeLine)
                card.addView(titleLine)
                topThreeEventsContainer.addView(card)
            }
        }

        updateCountdownTick()
    }

    private fun startSecondBySecondTicker() {
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
        if (first == null) {
            countdownHeaderText.text = "Agenda livre"
            countdownTimerText.text = "--:--:--"
            progressLabelText.text = "Janela ${repo.getProgressWindowMinutes()}m • 0%"
            progressFillView.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT)
            return
        }

        val remainingMs = (first.startEpochMs - nowMs).coerceAtLeast(0L)
        val totalSec = remainingMs / 1000L
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L

        countdownHeaderText.text = "Início às ${first.formatStartHHMM()}"
        countdownTimerText.text = String.format(Locale.getDefault(), "%02dh %02dm %02ds", hours, mins, secs)

        val windowMs = TimeUnit.MINUTES.toMillis(repo.getProgressWindowMinutes().toLong()).toFloat()
        val ratio = if (remainingMs >= windowMs) {
            0f
        } else {
            (1f - (remainingMs.toFloat() / windowMs)).coerceIn(0f, 1f)
        }
        val pct = (ratio * 100f).toInt()
        progressLabelText.text = "Janela ${repo.getProgressWindowMinutes()}m • $pct%"

        val trackW = progressTrackView.width
        if (trackW > 0) {
            val fillW = (trackW * ratio).toInt().coerceAtLeast(if (ratio > 0f) dp(4) else 0)
            progressFillView.layoutParams = LinearLayout.LayoutParams(
                fillW,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun requestSyncFromPhoneSilently() {
        uiScope.launch(Dispatchers.IO) {
            try {
                val nodes = Wearable.getNodeClient(this@WearMainActivity).connectedNodes.await()
                for (node in nodes) {
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
        ComplicationDataSourceUpdateRequester.create(
            this,
            ComponentName(this, NextEventComplicationService::class.java)
        ).requestUpdateAll()

        TileService.getUpdater(this)
            .requestUpdate(UpNextThreeEventsTileService::class.java)
    }
}
