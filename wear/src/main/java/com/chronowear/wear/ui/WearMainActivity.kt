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
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.wear.tiles.TileService
import com.chronowear.wear.complication.ChronoWearComplicationUpdater
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

    private var upcomingThree: List<CachedWearEvent> = emptyList()

    private val cacheUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            reloadEssentialState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = WatchAutonomousRepository.getInstance(this)
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

    private fun buildEssentialWatchUi() {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#000000"))
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }

        val appTitle = TextView(this).apply {
            text = "ChronoWear"
            textSize = 15f
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
            setPadding(0, dp(2), 0, dp(10))
        }
        container.addView(connectionStatusText)

        val eventCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = pillBg("#141917", "#26312C", 18)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
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
            textSize = 15f
            setTextColor(Color.parseColor("#BAE6FD"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(2))
        }
        eventCard.addView(nextEventCountdownText)

        complicationStatusText = TextView(this).apply {
            text = "Complications ativas"
            textSize = 10f
            setTextColor(Color.parseColor("#9CA3AF"))
            gravity = Gravity.CENTER
        }
        eventCard.addView(complicationStatusText)

        container.addView(eventCard)

        val syncBtn = Button(this).apply {
            text = "Sincronizar"
            isAllCaps = false
            textSize = 12f
            setTextColor(Color.parseColor("#0B191E"))
            setTypeface(typeface, Typeface.BOLD)
            background = pillBg("#BAE6FD", "#7DD3FC", 20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            )
            setPadding(dp(20), 0, dp(20), 0)
            setOnClickListener {
                Toast.makeText(this@WearMainActivity, "Sincronizando...", Toast.LENGTH_SHORT).show()
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
                connectionStatusText.text = "ᛒ Conectado • ${nodes.first().displayName}"
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
        if (first == null) {
            nextEventTitleText.text = "Agenda livre"
            nextEventCountdownText.text = "Sem próximo evento"
            complicationStatusText.text = "Complication pronta (${repo.getHorizonDays()}d)"
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
        complicationStatusText.text = "${upcomingThree.size} próx. evento(s) na Complication"
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
                        "installed:1.0.0-wear".toByteArray(Charsets.UTF_8)
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
