package com.chronowear.mobile.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CalendarChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = SyncPreferences(context)
        CalendarSyncWorker.schedulePeriodicSync(context, prefs.getSyncIntervalMinutes())
        CalendarSyncWorker.enqueueImmediateSync(context)
        if (prefs.isForegroundMonitorEnabled() && CalendarSyncEngine.hasCalendarPermission(context)) {
            try {
                CalendarSyncForegroundService.startOrUpdate(context)
            } catch (_: Exception) {
            }
        }
    }
}
