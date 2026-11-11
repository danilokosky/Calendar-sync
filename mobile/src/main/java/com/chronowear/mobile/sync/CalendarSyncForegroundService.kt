package com.chronowear.mobile.sync

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.concurrent.atomic.AtomicBoolean

class CalendarSyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var calendarAndTasksObserver: ContentObserver? = null
    private var debounceJob: Job? = null
    private var taskWatchdogJob: Job? = null
    private var systemEventsReceiverRegistered = false
    private val isSyncRunning = AtomicBoolean(false)
    @Volatile
    private var lastSyncedTasksSignature: String = ""

    private val systemEventsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val act = intent?.action ?: return
            when (act) {
                Intent.ACTION_TIME_TICK -> {
                    evaluatePeriodicAndTasksSyncIfNeeded("Tick Relógio (1m)")
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    evaluatePeriodicAndTasksSyncIfNeeded("Tela do Smartphone Ativa")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification("Serviço em 2º plano iniciado pelo usuário")
        lastSyncedTasksSignature = SyncPreferences(this).getTasksJson()
        registerSystemEventsReceiverIfNeeded()
        refreshCalendarAndTasksObservers()
        startTasksDiffWatchdogLoop()
        scheduleNextPeriodicAlarm()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = SyncPreferences(this)
        if (intent?.action == ACTION_STOP_SERVICE || !prefs.isBackgroundServiceEnabled()) {
            unregisterCalendarObserver()
            unregisterSystemEventsReceiver()
            cancelPeriodicAlarm()
            taskWatchdogJob?.cancel()
            CalendarSyncWorker.cancelPeriodicSync(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        CalendarSyncWorker.schedulePeriodicSync(
            this,
            prefs.getSyncIntervalMinutes(),
            forceUpdate = false
        )
        registerSystemEventsReceiverIfNeeded()
        refreshCalendarAndTasksObservers()
        if (taskWatchdogJob == null || taskWatchdogJob?.isActive != true) {
            startTasksDiffWatchdogLoop()
        }
        scheduleNextPeriodicAlarm()

        when (intent?.action) {
            ACTION_ENSURE_RUNNING -> {
                evaluatePeriodicAndTasksSyncIfNeeded("Verificação em 2º Plano")
            }
            ACTION_PERIODIC_ALARM -> {
                triggerSync("Atualização Periódica (${prefs.getSyncIntervalMinutes()}m)")
            }
            else -> {
                val triggerReason = intent?.getStringExtra(EXTRA_TRIGGER_REASON) ?: "Serviço em 2º Plano"
                triggerSync(triggerReason)
            }
        }
        return START_STICKY
    }

    private fun triggerSync(reason: String) {
        serviceScope.launch {
            if (!isSyncRunning.compareAndSet(false, true)) return@launch
            try {
                val prefs = SyncPreferences(applicationContext)
                val count = CalendarSyncEngine.performFullSyncToWatch(applicationContext, reason)
                lastSyncedTasksSignature = prefs.getTasksJson()
                scheduleNextPeriodicAlarm()
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
            } finally {
                isSyncRunning.set(false)
            }
        }
    }

    /**
     * Avalia usando o relógio real de parede (`System.currentTimeMillis()`, que não congela em Doze)
     * se a única atualização periódica universal configurada pelo usuário (15m, 30m, 1h, 2h) já venceu.
     */
    private fun evaluatePeriodicAndTasksSyncIfNeeded(@Suppress("UNUSED_PARAMETER") sourceLabel: String) {
        serviceScope.launch {
            if (isSyncRunning.get()) return@launch
            val prefs = SyncPreferences(applicationContext)
            if (!prefs.isBackgroundServiceEnabled()) return@launch

            val nowMs = System.currentTimeMillis()
            val intervalMinutes = prefs.getSyncIntervalMinutes().coerceIn(15L, 120L)
            val intervalMs = intervalMinutes * 60_000L
            val lastFullSyncMs = prefs.getLastSyncTimestampMs()

            if (lastFullSyncMs <= 0L || (nowMs - lastFullSyncMs) >= intervalMs) {
                triggerSync("Atualização Periódica (${intervalMinutes}m)")
            }
        }
    }

    private fun scheduleNextPeriodicAlarm() {
        try {
            val prefs = SyncPreferences(applicationContext)
            if (!prefs.isBackgroundServiceEnabled()) return
            val nowMs = System.currentTimeMillis()
            val intervalMinutes = prefs.getSyncIntervalMinutes().coerceIn(15L, 120L)
            val intervalMs = intervalMinutes * 60_000L
            val lastSyncMs = prefs.getLastSyncTimestampMs()
            val triggerAtMs = if (lastSyncMs > 0L) {
                (lastSyncMs + intervalMs).coerceAtLeast(nowMs + 5_000L)
            } else {
                nowMs + intervalMs
            }
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val alarmIntent = Intent(this, CalendarSyncForegroundService::class.java).apply {
                action = ACTION_PERIODIC_ALARM
            }
            val pi = PendingIntent.getService(
                this,
                102,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } catch (_: Exception) {
        }
    }

    private fun cancelPeriodicAlarm() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val alarmIntent = Intent(this, CalendarSyncForegroundService::class.java).apply {
                action = ACTION_PERIODIC_ALARM
            }
            val pi = PendingIntent.getService(
                this,
                102,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        } catch (_: Exception) {
        }
    }

    private fun registerSystemEventsReceiverIfNeeded() {
        if (systemEventsReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            registerReceiver(systemEventsReceiver, filter)
            systemEventsReceiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterSystemEventsReceiver() {
        if (!systemEventsReceiverRegistered) return
        try {
            unregisterReceiver(systemEventsReceiver)
        } catch (_: Exception) {
        }
        systemEventsReceiverRegistered = false
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
     * Loop de verificação contínua baseado em relógio real (`System.currentTimeMillis()`).
     */
    private fun startTasksDiffWatchdogLoop() {
        taskWatchdogJob?.cancel()
        taskWatchdogJob = serviceScope.launch {
            while (true) {
                delay(60_000L)
                evaluatePeriodicAndTasksSyncIfNeeded("Monitor em 2º Plano")
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
        unregisterSystemEventsReceiver()
        cancelPeriodicAlarm()
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
        const val ACTION_ENSURE_RUNNING = "com.chronowear.mobile.ACTION_ENSURE_RUNNING"
        const val ACTION_PERIODIC_ALARM = "com.chronowear.mobile.ACTION_PERIODIC_ALARM"
        const val ACTION_STOP_SERVICE = "com.chronowear.mobile.ACTION_STOP_SERVICE"
        const val EXTRA_TRIGGER_REASON = "extra_trigger_reason"

        fun startOrUpdate(context: Context, reason: String = "Ativado pelo Usuário") {
            val intent = Intent(context, CalendarSyncForegroundService::class.java).apply {
                action = ACTION_SYNC_NOW
                putExtra(EXTRA_TRIGGER_REASON, reason)
            }
            context.startForegroundService(intent)
        }

        fun ensureRunning(context: Context) {
            val prefs = SyncPreferences(context)
            if (!prefs.isBackgroundServiceEnabled()) return
            try {
                val intent = Intent(context, CalendarSyncForegroundService::class.java).apply {
                    action = ACTION_ENSURE_RUNNING
                }
                context.startForegroundService(intent)
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CalendarSyncForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }
}
