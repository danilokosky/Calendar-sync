package com.chronowear.mobile.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.CalendarContract
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.chronowear.mobile.sync.CalendarSyncWorker
import com.chronowear.mobile.sync.SyncPreferences

class MobileSyncConfigActivity : Activity() {

    private lateinit var prefs: SyncPreferences
    private lateinit var calendarsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = SyncPreferences(this)

        val scrollView = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        val titleView = TextView(this).apply {
            text = "ChronoWear — Sincronização Wear OS"
            textSize = 20f
        }
        root.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Selecione os calendários, a janela da barra de progresso (1h/2h) e o horizonte autônomo offline (2 a 7 dias)."
            textSize = 13f
            setPadding(0, 12, 0, 32)
        }
        root.addView(subtitleView)

        // Seção 1: Barra de Progresso
        val progressLabel = TextView(this).apply {
            text = "1. Janela da Barra de Progresso antes do Evento:"
            textSize = 15f
        }
        root.addView(progressLabel)

        val progressGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 8, 0, 28)
        }
        val currentWindow = prefs.getProgressWindowMinutes()
        listOf(30 to "30m", 60 to "1 hora", 120 to "2 horas", 180 to "3 horas").forEach { (mins, label) ->
            val rb = RadioButton(this).apply {
                text = label
                id = mins
                isChecked = (currentWindow == mins)
            }
            progressGroup.addView(rb)
        }
        progressGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.setProgressWindowMinutes(checkedId)
            CalendarSyncWorker.enqueueImmediateSync(this)
        }
        root.addView(progressGroup)

        // Seção 2: Horizonte Autônomo Offline
        val horizonLabel = TextView(this).apply {
            text = "2. Horizonte de Cache Autônomo no Relógio:"
            textSize = 15f
        }
        root.addView(horizonLabel)

        val horizonGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 8, 0, 28)
        }
        val currentHorizon = prefs.getHorizonDays()
        listOf(2 to "2 dias", 3 to "3 dias", 5 to "5 dias", 7 to "7 dias").forEach { (days, label) ->
            val rb = RadioButton(this).apply {
                text = label
                id = days
                isChecked = (currentHorizon == days)
            }
            horizonGroup.addView(rb)
        }
        horizonGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.setHorizonDays(checkedId)
            CalendarSyncWorker.enqueueImmediateSync(this)
        }
        root.addView(horizonGroup)

        // Seção 3: Lista de Calendários
        val calHeader = TextView(this).apply {
            text = "3. Calendários Sincronizados:"
            textSize = 15f
            setPadding(0, 8, 0, 12)
        }
        root.addView(calHeader)

        calendarsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(calendarsContainer)

        val syncNowButton = Button(this).apply {
            text = "Sincronizar Agora com o Relógio"
            gravity = Gravity.CENTER
            setOnClickListener {
                CalendarSyncWorker.schedulePeriodicSync(this@MobileSyncConfigActivity, prefs.getSyncIntervalMinutes())
                CalendarSyncWorker.enqueueImmediateSync(this@MobileSyncConfigActivity)
                Toast.makeText(
                    this@MobileSyncConfigActivity,
                    "Sincronização enviada para o Wear OS!",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        root.addView(syncNowButton)

        scrollView.addView(root)
        setContentView(scrollView)

        checkCalendarPermissionAndLoad()
    }

    private fun checkCalendarPermissionAndLoad() {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 1001)
        } else {
            loadCalendarsList()
            CalendarSyncWorker.schedulePeriodicSync(this, prefs.getSyncIntervalMinutes())
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadCalendarsList()
            CalendarSyncWorker.schedulePeriodicSync(this, prefs.getSyncIntervalMinutes())
            CalendarSyncWorker.enqueueImmediateSync(this)
        }
    }

    private fun loadCalendarsList() {
        calendarsContainer.removeAllViews()
        val selectedIds = prefs.getSelectedCalendarIds().toMutableSet()

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )

        val cursor: Cursor? = contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0).toString()
                val name = it.getString(1) ?: "Calendário"
                val account = it.getString(2) ?: ""

                val cb = CheckBox(this).apply {
                    text = "$name ($account)"
                    isChecked = selectedIds.contains(id)
                    setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) selectedIds.add(id) else selectedIds.remove(id)
                        prefs.setSelectedCalendarIds(selectedIds)
                        CalendarSyncWorker.enqueueImmediateSync(this@MobileSyncConfigActivity)
                    }
                }
                calendarsContainer.addView(cb)
            }
        }
    }
}
