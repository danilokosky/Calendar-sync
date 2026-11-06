package com.chronowear.mobile.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.CalendarContract
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class WearConnectionState {
    DISCONNECTED,
    WEARABLE_CONNECTED_APP_MISSING,
    CONNECTED_WITH_APP_INSTALLED
}

data class WearConnectionReport(
    val state: WearConnectionState,
    val wearableDisplayNames: List<String>,
    val installedAppNodeNames: List<String>
)

data class LocalCalendarItem(
    val id: String,
    val displayName: String,
    val accountName: String,
    val ownerAccount: String,
    val colorInt: Int,
    val upcomingEventsCount: Int
)

data class LocalCalendarEvent(
    val id: String,
    val calendarId: String,
    val calendarName: String,
    val title: String,
    val location: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val allDay: Boolean,
    val colorInt: Int
)

object CalendarSyncEngine {

    const val DATA_LAYER_PATH = "/chronowear/calendar_horizon_payload"
    const val MESSAGE_FORCE_SYNC_PATH = "/chronowear/force_sync_payload"
    const val MESSAGE_PING_WATCH_APP_PATH = "/chronowear/ping_watch_app"
    const val CAPABILITY_WATCH_APP_INSTALLED = "chronowear_wear_app_installed"

    fun hasCalendarPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Verifica se há um relógio Wear OS conectado ao smartphone E se o aplicativo ChronoWear
     * está efetivamente instalado no relógio (via CapabilityClient + handshake ping/pong).
     */
    suspend fun checkWearConnectionAndAppStatus(context: Context): WearConnectionReport {
        val prefs = SyncPreferences(context)
        val connectedNodes = try {
            Wearable.getNodeClient(context).connectedNodes.await()
        } catch (_: Exception) {
            emptyList()
        }

        if (connectedNodes.isEmpty()) {
            return WearConnectionReport(
                state = WearConnectionState.DISCONNECTED,
                wearableDisplayNames = emptyList(),
                installedAppNodeNames = emptyList()
            )
        }

        val capableNodeIds = try {
            val capInfo = Wearable.getCapabilityClient(context)
                .getCapability(CAPABILITY_WATCH_APP_INSTALLED, CapabilityClient.FILTER_REACHABLE)
                .await()
            capInfo.nodes.map { it.id }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        // Dispara ping leve para cada nó conectado para confirmar presença do app no relógio
        for (node in connectedNodes) {
            try {
                Wearable.getMessageClient(context).sendMessage(
                    node.id,
                    MESSAGE_PING_WATCH_APP_PATH,
                    ByteArray(0)
                ).await()
            } catch (_: Exception) {
            }
        }

        val installedNodes = connectedNodes.filter { node ->
            capableNodeIds.contains(node.id) || prefs.isWatchAppVerifiedOnNode(node.id)
        }

        return if (installedNodes.isNotEmpty()) {
            WearConnectionReport(
                state = WearConnectionState.CONNECTED_WITH_APP_INSTALLED,
                wearableDisplayNames = connectedNodes.map { it.displayName },
                installedAppNodeNames = installedNodes.map { it.displayName }
            )
        } else {
            WearConnectionReport(
                state = WearConnectionState.WEARABLE_CONNECTED_APP_MISSING,
                wearableDisplayNames = connectedNodes.map { it.displayName },
                installedAppNodeNames = emptyList()
            )
        }
    }

    fun loadDeviceCalendars(context: Context): List<LocalCalendarItem> {
        if (!hasCalendarPermission(context)) return emptyList()

        val prefs = SyncPreferences(context)
        val result = mutableListOf<LocalCalendarItem>()
        val nowMs = System.currentTimeMillis()
        val endMs = nowMs + TimeUnit.DAYS.toMillis(prefs.getHorizonDays().toLong())

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.CALENDAR_COLOR
        )

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(0).toString()
                val name = cursor.getString(1) ?: "Calendário"
                val account = cursor.getString(2) ?: "Conta Local"
                val owner = cursor.getString(3) ?: account
                val color = if (!cursor.isNull(4)) cursor.getInt(4) else 0xFFF59E0B.toInt()
                val count = countEventsForCalendar(context, calId, nowMs, endMs)
                result.add(LocalCalendarItem(calId, name, account, owner, color, count))
            }
        }

        return result
    }

    private fun countEventsForCalendar(
        context: Context,
        calendarId: String,
        startMs: Long,
        endMs: Long
    ): Int {
        var count = 0
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)

        val projection = arrayOf(
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.END,
            CalendarContract.Instances.STATUS
        )

        context.contentResolver.query(
            builder.build(),
            projection,
            "${CalendarContract.Instances.CALENDAR_ID} = ? AND ${CalendarContract.Instances.STATUS} != ?",
            arrayOf(calendarId, CalendarContract.Instances.STATUS_CANCELED.toString()),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val evEnd = cursor.getLong(1)
                if (evEnd > startMs) count++
            }
        }
        return count
    }

    fun queryHorizonEvents(context: Context): List<LocalCalendarEvent> {
        if (!hasCalendarPermission(context)) return emptyList()

        val prefs = SyncPreferences(context)
        val selectedIds = prefs.getSelectedCalendarIds()
        if (selectedIds.isEmpty()) return emptyList()

        val calendars = loadDeviceCalendars(context)
        val calMap = calendars.associateBy { it.id }

        val nowMs = System.currentTimeMillis()
        val endMs = nowMs + TimeUnit.DAYS.toMillis(prefs.getHorizonDays().toLong())

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, nowMs)
        ContentUris.appendId(builder, endMs)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.STATUS
        )

        val events = mutableListOf<LocalCalendarEvent>()

        context.contentResolver.query(
            builder.build(),
            projection,
            "${CalendarContract.Instances.STATUS} != ?",
            arrayOf(CalendarContract.Instances.STATUS_CANCELED.toString()),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(1).toString()
                if (!selectedIds.contains(calId)) continue

                val beginMs = cursor.getLong(4)
                val endEventMs = cursor.getLong(5)
                if (endEventMs <= nowMs) continue

                val allDay = cursor.getInt(6) == 1
                val calMeta = calMap[calId]
                events.add(
                    LocalCalendarEvent(
                        id = cursor.getLong(0).toString(),
                        calendarId = calId,
                        calendarName = calMeta?.displayName ?: "Calendário",
                        title = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: "Compromisso sem título",
                        location = cursor.getString(3) ?: "",
                        startEpochMs = beginMs,
                        endEpochMs = endEventMs,
                        allDay = allDay,
                        colorInt = calMeta?.colorInt ?: 0xFFF59E0B.toInt()
                    )
                )
            }
        }

        return events.sortedBy { it.startEpochMs }
    }

    suspend fun performFullSyncToWatch(
        context: Context,
        triggerSource: String = "Sincronização Manual"
    ): Int {
        val prefs = SyncPreferences(context)
        val nowMs = System.currentTimeMillis()

        if (!hasCalendarPermission(context)) {
            prefs.recordLastSync(
                nowMs,
                0,
                0,
                triggerSource,
                "Aguardando permissão de leitura do calendário pelo usuário"
            )
            return 0
        }

        val selectedCalendarsCount = prefs.getSelectedCalendarIds().size
        if (selectedCalendarsCount == 0) {
            prefs.recordLastSync(
                nowMs,
                0,
                0,
                triggerSource,
                "Nenhum calendário selecionado em Synced calendars"
            )
            context.sendBroadcast(
                Intent(SyncPreferences.ACTION_SYNC_COMPLETED).setPackage(context.packageName)
            )
            return 0
        }

        val events = queryHorizonEvents(context)
        val horizonDays = prefs.getHorizonDays()
        val progressWindowMinutes = prefs.getProgressWindowMinutes()

        val jsonArray = JSONArray()
        events.forEach { ev ->
            val obj = JSONObject().apply {
                put("id", ev.id)
                put("calendarId", ev.calendarId)
                put("calendarName", ev.calendarName)
                put("title", ev.title)
                put("location", ev.location)
                put("startEpochMs", ev.startEpochMs)
                put("endEpochMs", ev.endEpochMs)
                put("colorInt", ev.colorInt)
            }
            jsonArray.put(obj)
        }

        val payloadJson = JSONObject().apply {
            put("events", jsonArray)
            put("sync_timestamp_ms", nowMs)
            put("horizon_days", horizonDays)
            put("progress_window_minutes", progressWindowMinutes)
        }.toString()

        try {
            val connReport = checkWearConnectionAndAppStatus(context)
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()

            val putDataMapReq = PutDataMapRequest.create(DATA_LAYER_PATH).apply {
                dataMap.putString("events_json", jsonArray.toString())
                dataMap.putLong("sync_timestamp_ms", nowMs)
                dataMap.putInt("horizon_days", horizonDays)
                dataMap.putInt("progress_window_minutes", progressWindowMinutes)
            }
            Wearable.getDataClient(context)
                .putDataItem(putDataMapReq.asPutDataRequest().setUrgent())
                .await()

            for (node in nodes) {
                Wearable.getMessageClient(context).sendMessage(
                    node.id,
                    MESSAGE_FORCE_SYNC_PATH,
                    payloadJson.toByteArray(Charsets.UTF_8)
                ).await()
            }

            val nodeStatus = when (connReport.state) {
                WearConnectionState.CONNECTED_WITH_APP_INSTALLED ->
                    "Sincronizado com ${connReport.installedAppNodeNames.joinToString()}"
                WearConnectionState.WEARABLE_CONNECTED_APP_MISSING ->
                    "Wearable conectado (${connReport.wearableDisplayNames.joinToString()}), mas o app não está instalado no relógio"
                WearConnectionState.DISCONNECTED ->
                    "Nenhum relógio conectado"
            }
            prefs.recordLastSync(nowMs, events.size, selectedCalendarsCount, triggerSource, nodeStatus)
        } catch (_: Exception) {
            prefs.recordLastSync(
                nowMs,
                events.size,
                selectedCalendarsCount,
                triggerSource,
                "Pacote pronto localmente (${events.size} compromissos)"
            )
        }

        context.sendBroadcast(
            Intent(SyncPreferences.ACTION_SYNC_COMPLETED).setPackage(context.packageName)
        )
        return events.size
    }
}
