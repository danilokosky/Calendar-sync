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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import com.chronowear.mobile.ui.MobileSyncConfigActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CalendarSyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var calendarObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundWithNotification("Iniciando monitoramento de agenda e tarefas...")
        registerCalendarObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        triggerImmediateSync()
        return START_STICKY
    }

    private fun triggerImmediateSync() {
        serviceScope.launch {
            val count = CalendarSyncEngine.performFullSyncToWatch(applicationContext)
            val prefs = SyncPreferences(applicationContext)
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            updateNotification(
                "Ativo (${prefs.getHorizonDays()}d) • $count eventos enviados às $timeStr"
            )
        }
    }

    private fun registerCalendarObserver() {
        if (!CalendarSyncEngine.hasCalendarPermission(this)) return
        if (calendarObserver != null) return

        calendarObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                triggerImmediateSync()
            }
        }

        try {
            contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI,
                true,
                calendarObserver!!
            )
        } catch (_: SecurityException) {
        }
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
        }
        val pendingSyncNow = PendingIntent.getService(
            this,
            101,
            syncNowIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("ChronoWear • Sincronização Wear OS")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_popup_sync,
                    "Sincronizar Agora",
                    pendingSyncNow
                ).build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sincronização de Calendário Wear OS",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantém a agenda e contagem regressiva sincronizadas com o relógio"
        }
        nm.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        calendarObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
            } catch (_: Exception) {
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "chronowear_sync_channel"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_SYNC_NOW = "com.chronowear.mobile.ACTION_SYNC_NOW"
        const val ACTION_STOP_SERVICE = "com.chronowear.mobile.ACTION_STOP_SERVICE"

        fun startOrUpdate(context: Context) {
            val intent = Intent(context, CalendarSyncForegroundService::class.java).apply {
                action = ACTION_SYNC_NOW
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
