package com.chronowear.mobile.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LocalCalendarItem(
    val id: String,
    val displayName: String,
    val accountName: String,
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
    val colorInt: Int
)

object CalendarSyncEngine {

    const val DATA_LAYER_PATH = "/chronowear/calendar_horizon_payload"
    const val MESSAGE_FORCE_SYNC_PATH = "/chronowear/force_sync_payload"

    fun hasCalendarPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * Lista todos os calendários do aparelho e inicializa todos como selecionados no primeiro uso
     * para que o usuário já veja seus eventos reais imediatamente.
     */
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
            CalendarContract.Calendars.CALENDAR_COLOR
        )

        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(0).toString()
                val name = cursor.getString(1) ?: "Calendário Principal"
                val account = cursor.getString(2) ?: "Conta Local"
                val color = if (!cursor.isNull(3)) cursor.getInt(3) else 0xFFF59E0B.toInt()
                val count = countEventsForCalendar(context, calId, nowMs, endMs)
                result.add(LocalCalendarItem(calId, name, account, color, count))
            }
        }

        // Se for a primeira vez que o usuário abriu o app após conceder permissão,
        // seleciona automaticamente todos os calendários encontrados.
        if (!prefs.hasInitializedCalendars() && result.isNotEmpty()) {
            prefs.setSelectedCalendarIds(result.map { it.id }.toSet())
            prefs.markCalendarsInitialized()
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
            CalendarContract.Instances.END
        )

        context.contentResolver.query(
            builder.build(),
            projection,
            "${CalendarContract.Instances.CALENDAR_ID} = ?",
            arrayOf(calendarId),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val evEnd = cursor.getLong(1)
                if (evEnd > startMs) count++
            }
        }
        return count
    }

    /**
     * Consulta todos os eventos futuros dentro do horizonte configurado (2, 3, 5 ou 7 dias)
     * pertencentes aos calendários selecionados.
     */
    fun queryHorizonEvents(context: Context): List<LocalCalendarEvent> {
        if (!hasCalendarPermission(context)) return emptyList()

        val prefs = SyncPreferences(context)
        val calendars = loadDeviceCalendars(context)
        val calMap = calendars.associateBy { it.id }
        val selectedIds = prefs.getSelectedCalendarIds()
        if (selectedIds.isEmpty()) return emptyList()

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
            CalendarContract.Instances.END
        )

        val events = mutableListOf<LocalCalendarEvent>()

        context.contentResolver.query(
            builder.build(),
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(1).toString()
                if (!selectedIds.contains(calId)) continue

                val beginMs = cursor.getLong(4)
                val endEventMs = cursor.getLong(5)
                if (endEventMs <= nowMs) continue

                val calMeta = calMap[calId]
                events.add(
                    LocalCalendarEvent(
                        id = cursor.getLong(0).toString(),
                        calendarId = calId,
                        calendarName = calMeta?.displayName ?: "Agenda",
                        title = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: "Compromisso sem título",
                        location = cursor.getString(3) ?: "",
                        startEpochMs = beginMs,
                        endEpochMs = endEventMs,
                        colorInt = calMeta?.colorInt ?: 0xFFF59E0B.toInt()
                    )
                )
            }
        }

        return events.sortedBy { it.startEpochMs }
    }

    /**
     * Executa a sincronização completa: lê os eventos do horizonte no smartphone,
     * envia via DataClient e também via MessageClient para todos os nós Wear OS conectados,
     * grava o log de sincronização e notifica a UI do smartphone.
     */
    suspend fun performFullSyncToWatch(context: Context): Int {
        val prefs = SyncPreferences(context)
        val nowMs = System.currentTimeMillis()

        if (!hasCalendarPermission(context)) {
            prefs.recordLastSync(nowMs, 0, 0, "Permissão READ_CALENDAR pendente")
            return 0
        }

        val events = queryHorizonEvents(context)
        val horizonDays = prefs.getHorizonDays()
        val progressWindowMinutes = prefs.getProgressWindowMinutes()
        val selectedCalendarsCount = prefs.getSelectedCalendarIds().size

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

        var connectedNodesCount = 0
        try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            connectedNodesCount = nodes.size

            val putDataMapReq = PutDataMapRequest.create(DATA_LAYER_PATH).apply {
                dataMap.putString("events_json", jsonArray.toString())
                dataMap.putLong("sync_timestamp_ms", nowMs)
                dataMap.putInt("horizon_days", horizonDays)
                dataMap.putInt("progress_window_minutes", progressWindowMinutes)
            }
            Wearable.getDataClient(context)
                .putDataItem(putDataMapReq.asPutDataRequest().setUrgent())
                .await()

            // Envia também mensagem direta para despertar imediatamente o app do relógio
            for (node in nodes) {
                Wearable.getMessageClient(context).sendMessage(
                    node.id,
                    MESSAGE_FORCE_SYNC_PATH,
                    payloadJson.toByteArray(Charsets.UTF_8)
                ).await()
            }

            val nodeStatus = if (connectedNodesCount > 0) {
                "Sincronizado com $connectedNodesCount relógio(s)"
            } else {
                "Salvo na fila Data Layer (Relógio offline)"
            }
            prefs.recordLastSync(nowMs, events.size, selectedCalendarsCount, nodeStatus)
        } catch (e: Exception) {
            prefs.recordLastSync(
                nowMs,
                events.size,
                selectedCalendarsCount,
                "Fila local pronta (${events.size} eventos)"
            )
        }

        context.sendBroadcast(Intent(SyncPreferences.ACTION_SYNC_COMPLETED).setPackage(context.packageName))
        return events.size
    }
}
