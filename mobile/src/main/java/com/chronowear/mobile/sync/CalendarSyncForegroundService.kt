package com.chronowear.mobile.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import com.chronowear.mobile.ui.MobileSyncConfigActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CalendarSyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var calendarAndTasksObserver: ContentObserver? = null
    private var debounceJob: Job? = null
    private var taskWatchdogJob: Job? = null
    private var lastSyncedTasksSignature: String = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification("Serviço em 2º plano iniciado pelo usuário")
        refreshCalendarAndTasksObservers()
        startTasksDiffWatchdogLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = SyncPreferences(this)
        if (intent?.action == ACTION_STOP_SERVICE || !prefs.isBackgroundServiceEnabled()) {
            unregisterCalendarObserver()
            taskWatchdogJob?.cancel()
            CalendarSyncWorker.cancelPeriodicSync(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        CalendarSyncWorker.schedulePeriodicSync(this, prefs.getSyncIntervalMinutes())
        refreshCalendarAndTasksObservers()
        startTasksDiffWatchdogLoop()

        val triggerReason = intent?.getStringExtra(EXTRA_TRIGGER_REASON) ?: "Serviço em 2º Plano"
        triggerSync(triggerReason)
        return START_STICKY
    }

    private fun triggerSync(reason: String) {
        serviceScope.launch {
            val prefs = SyncPreferences(applicationContext)
            val count = CalendarSyncEngine.performFullSyncToWatch(applicationContext, reason)
            lastSyncedTasksSignature = prefs.getTasksJson()
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val intervalMin = prefs.getSyncIntervalMinutes()
            val intervalLabel = when (intervalMin) {
                60L -> "1h"
                120L -> "2h"
                else -> "${intervalMin}m"
            }
            val autoChangeLabel = if (prefs.isAutoSyncOnCalendarChangeEnabled()) {
                "Tempo real + Periódico ($intervalLabel)"
            } else {
                "Periódico ($intervalLabel)"
            }
            updateNotification(
                "$autoChangeLabel • $count eventos enviados às $timeStr"
            )
        }
    }

    /**
     * Registra ContentObserver em tempo real na Agenda (`CalendarContract.CONTENT_URI`),
     * disparando sincronização com debounce quando um compromisso da agenda é criado, editado ou excluído.
     */
    private fun refreshCalendarAndTasksObservers() {
        val prefs = SyncPreferences(this)
        val shouldObserveCalendar = CalendarSyncEngine.hasCalendarPermission(this) && prefs.isAutoSyncOnCalendarChangeEnabled()

        if (!shouldObserveCalendar) {
            unregisterCalendarObserver()
            return
        }

        if (calendarAndTasksObserver != null) return

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChange(selfChange, null)
            }

            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                super.onChange(selfChange, uri)
                val currentPrefs = SyncPreferences(this@CalendarSyncForegroundService)
                if (!currentPrefs.isAutoSyncOnCalendarChangeEnabled()) return

                val debounceSec = currentPrefs.getChangeDebounceSeconds().coerceAtLeast(1)
                debounceJob?.cancel()
                debounceJob = serviceScope.launch {
                    delay(debounceSec * 1000L)
                    triggerSync("Alteração na Agenda (Tempo Real)")
                }
            }
        }
        calendarAndTasksObserver = observer

        try {
            contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI,
                true,
                observer
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Sincronizador periódico de segurança em segundo plano que respeita o intervalo escolhido
     * pelo usuário (15 min, 30 min, 1 hora ou 2 horas) para economizar bateria, enquanto a Agenda
     * local continua respondendo em tempo real via ContentObserver.
     */
    private fun startTasksDiffWatchdogLoop() {
        taskWatchdogJob?.cancel()
        taskWatchdogJob = serviceScope.launch {
            while (true) {
                val intervalMinutes = SyncPreferences(applicationContext)
                    .getSyncIntervalMinutes()
                    .coerceIn(15L, 120L)
                delay(intervalMinutes * 60_000L)
                try {
                    val prefs = SyncPreferences(applicationContext)
                    if (!prefs.isBackgroundServiceEnabled()) continue
                    if (prefs.isTasksSyncEnabled()) {
                        GoogleTasksApiClient.fetchTasksFromGoogleApi(
                            applicationContext,
                            prefs,
                            System.currentTimeMillis()
                        )
                    }
                    lastSyncedTasksSignature = prefs.getTasksJson()
                    CalendarSyncEngine.performFullSyncToWatch(
                        applicationContext,
                        "Atualização Periódica Geral (${intervalMinutes}m)"
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun unregisterCalendarObserver() {
        calendarAndTasksObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        calendarAndTasksObserver = null
    }

    private fun startForegroundWithNotification(contentText: String) {
        val notification = buildNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = Intent(this, MobileSyncConfigActivity::class.java)
        val pendingOpen = PendingIntent.getActivity(
            this,
            100,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val syncNowIntent = Intent(this, CalendarSyncForegroundService::class.java).apply {
            action = ACTION_SYNC_NOW
            putExtra(EXTRA_TRIGGER_REASON, "Ação da Notificação")
        }
        val pendingSyncNow = PendingIntent.getService(
            this,
            101,
            syncNowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val actionIcon = Icon.createWithResource(this, android.R.drawable.ic_popup_sync)
        val syncAction = Notification.Action.Builder(
            actionIcon,
            "Ler Agenda Agora",
            pendingSyncNow
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ChronoWear • Sincronização em 2º Plano")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(syncAction)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Serviço em Segundo Plano ChronoWear",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Monitora alterações na agenda e sincroniza eventos com o relógio Wear OS"
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        unregisterCalendarObserver()
        debounceJob?.cancel()
        taskWatchdogJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "chronowear_sync_channel"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_SYNC_NOW = "com.chronowear.mobile.ACTION_SYNC_NOW"
        const val ACTION_STOP_SERVICE = "com.chronowear.mobile.ACTION_STOP_SERVICE"
        const val EXTRA_TRIGGER_REASON = "extra_trigger_reason"

        fun startOrUpdate(context: Context, reason: String = "Ativado pelo Usuário") {
            val intent = Intent(context, CalendarSyncForegroundService::class.java).apply {
                action = ACTION_SYNC_NOW
                putExtra(EXTRA_TRIGGER_REASON, reason)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CalendarSyncForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
