package com.chronowear.mobile.sync

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * Escuta mensagens vindas do Smartwatch:
 * 1) Confirmação de que o app Wear OS está instalado (`/chronowear/pong_watch_app`)
 * 2) Solicitação de sincronização iniciada pelo relógio (`/chronowear/request_sync_from_watch`)
 */
class WearRequestListenerService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)
        when (messageEvent.path) {
            "/chronowear/pong_watch_app" -> {
                val prefs = SyncPreferences(applicationContext)
                prefs.markWatchAppInstalledOnNode(messageEvent.sourceNodeId)
                applicationContext.sendBroadcast(
                    Intent(SyncPreferences.ACTION_SYNC_COMPLETED).setPackage(applicationContext.packageName)
                )
            }
            "/chronowear/request_sync_from_watch" -> {
                val prefs = SyncPreferences(applicationContext)
                prefs.markWatchAppInstalledOnNode(messageEvent.sourceNodeId)
                scope.launch {
                    CalendarSyncEngine.performFullSyncToWatch(
                        applicationContext,
                        "Solicitado pelo Relógio"
                    )
                }
            }
        }
    }
}

/**
 * Observador de Notificações de Clima, Tarefas e Resumo de Sono no Smartphone (`PhoneWeatherNotificationListenerService`):
 * 1. Detecta notificações de Tarefas (Samsung Reminder / Google Tasks) ao disparar ou ao concluir/dispensar.
 * 2. Varre notificações ativas na barra de status (`syncActiveTaskNotificationsNow`) durante qualquer sincronização.
 * 3. Detecta notificações de Apps de Clima e de Resumo de Sono.
 */
class PhoneWeatherNotificationListenerService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        activeInstance = this
        syncActiveTaskNotificationsInternal(applicationContext)
    }

    override fun onListenerDisconnected() {
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val prefs = SyncPreferences(applicationContext)
        val pkg = sbn.packageName.lowercase(Locale.ROOT)
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
        val combinedLower = "$title $text".lowercase(Locale.ROOT)

        // 1) Verifica se é um lembrete de Tarefa (Samsung Reminder ou Google Tasks)
        if (extractAndRecordTaskFromStatusBarNotification(sbn, prefs)) {
            val isSamsungReminderPkg = pkg.contains("com.samsung.android.app.reminder") || pkg.contains("reminder")
            val sourceLabel = if (isSamsungReminderPkg) "Reminder" else "Tasks"
            scope.launch {
                CalendarSyncEngine.performFullSyncToWatch(
                    context = applicationContext,
                    triggerSource = "Lembrete de Tarefa ($sourceLabel)"
                )
            }
            return
        }

        // 2) Verifica se é um relatório de Sono (Samsung Health, Health Connect, Sleep as Android, etc.)
        val isSleepAppPkg = pkg.contains("shealth") ||
            pkg.contains("healthdata") ||
            pkg.contains("sleep") ||
            pkg.contains("fitness")
        val hasSleepKeywords = combinedLower.contains("sono") ||
            combinedLower.contains("sleep") ||
            combinedLower.contains("dormiu") ||
            combinedLower.contains("pontuação do sono")

        if (prefs.isSleepSyncEnabled() && isSleepAppPkg && hasSleepKeywords) {
            val hoursRegex = Regex("(\\d{1,2})\\s*h\\s*(\\d{1,2})?\\s*m?")
            val match = hoursRegex.find(combinedLower)
            val parsedMins = if (match != null) {
                val h = match.groupValues[1].toIntOrNull() ?: 7
                val m = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 42
                (h * 60 + m).coerceIn(120, 840)
            } else {
                prefs.getSleepDurationMinutes()
            }

            val scoreRegex = Regex("(\\d{2,3})\\s*(pts|pontos|score)")
            val parsedScore = scoreRegex.find(combinedLower)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: prefs.getSleepScore()

            prefs.saveSleepSnapshot(
                totalDurationMinutes = parsedMins,
                sleepScore = parsedScore,
                bedtimeHHMM = prefs.getSleepBedtimeHHMM(),
                wakeHHMM = prefs.getSleepWakeHHMM(),
                stagesMinutesCsv = prefs.getSleepStagesCsv(),
                wakeTimestampMs = System.currentTimeMillis()
            )

            scope.launch {
                CalendarSyncEngine.performFullSyncToWatch(
                    context = applicationContext,
                    triggerSource = "Resumo de Sono Detectado"
                )
            }
            return
        }

        // 3) Verifica se é um gatilho de Clima escolhido pelo usuário
        if (!prefs.isNotificationsSyncEnabled()) return

        val selectedPkgs = prefs.getSelectedWeatherTriggerPackages().map { it.lowercase(Locale.ROOT) }.toSet()
        val isSelectedTriggerPkg = selectedPkgs.any { sel -> pkg == sel || pkg.contains(sel) }
        if (!isSelectedTriggerPkg) return

        val isGeneralGoogleSearchApp = pkg.contains("googlequicksearchbox")
        val hasWeatherKeywords = combinedLower.contains("chuva") ||
            combinedLower.contains("temperatura") ||
            combinedLower.contains("previsão") ||
            combinedLower.contains("frente fria") ||
            combinedLower.contains("tempestade") ||
            combinedLower.contains("clima") ||
            combinedLower.contains("°c") ||
            combinedLower.contains("°")

        if (!isGeneralGoogleSearchApp || hasWeatherKeywords) {
            val catalogMatch = SyncPreferences.KNOWN_WEATHER_TRIGGER_CATALOG.firstOrNull {
                pkg == it.packageName.lowercase(Locale.ROOT) || pkg.contains(it.packageName.lowercase(Locale.ROOT))
            }
            val sourceLabel = catalogMatch?.displayName ?: when {
                isGeneralGoogleSearchApp -> "App Google"
                pkg.contains("daemonapp") || pkg.contains("samsung") -> "App Clima"
                else -> try {
                    val ai = applicationContext.packageManager.getApplicationInfo(sbn.packageName, 0)
                    applicationContext.packageManager.getApplicationLabel(ai).toString()
                } catch (_: Exception) {
                    "App Clima"
                }
            }

            val rawSummary = when {
                title.isNotBlank() && text.isNotBlank() -> "$title: $text"
                title.isNotBlank() -> title
                text.isNotBlank() -> text
                else -> "Mudança climática nas próximas horas"
            }
            val formattedHeadline = "$sourceLabel • $rawSummary".take(42)

            scope.launch {
                CalendarSyncEngine.fetchPhoneHourlyWeatherIfNeeded(
                    context = applicationContext,
                    forceRefresh = true
                )
                val nowMs = System.currentTimeMillis()
                prefs.recordObservedWeatherNotification(
                    sourceAppLabel = sourceLabel,
                    headline = formattedHeadline,
                    timestampMs = nowMs
                )
                CalendarSyncEngine.performFullSyncToWatch(
                    context = applicationContext,
                    triggerSource = "Trigger App Clima ($sourceLabel)"
                )
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return

        val prefs = SyncPreferences(applicationContext)
        if (!prefs.isTasksSyncEnabled()) return

        val pkg = sbn.packageName.lowercase(Locale.ROOT)
        val isSamsungReminderPkg = pkg.contains("com.samsung.android.app.reminder") || pkg.contains("reminder")
        val isGoogleTasksPkg = pkg.contains("com.google.android.apps.tasks") ||
            pkg.contains("tasks") ||
            pkg.contains("microsoft.todos")
        val allowedBySourceToggle = (isSamsungReminderPkg && prefs.isSamsungReminderSyncEnabled()) ||
            (isGoogleTasksPkg && prefs.isGoogleTasksSyncEnabled())
        if (!allowedBySourceToggle) return

        val extras = sbn.notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()
        val sourceLabel = if (isSamsungReminderPkg) "Reminder" else "Tasks"
        val taskHeadline = when {
            title.isNotBlank() && !title.equals("Reminder", ignoreCase = true) && !title.equals("Tasks", ignoreCase = true) -> title
            text.isNotBlank() -> text
            else -> ""
        }
        if (taskHeadline.isNotBlank()) {
            prefs.removeObservedTaskNotification(taskHeadline, sourceLabel)
        }
        scope.launch {
            CalendarSyncEngine.performFullSyncToWatch(
                context = applicationContext,
                triggerSource = "Tarefa Concluída/Atualizada ($sourceLabel)"
            )
        }
    }

    private fun syncActiveTaskNotificationsInternal(context: Context) {
        try {
            val prefs = SyncPreferences(context)
            if (!prefs.isTasksSyncEnabled()) return
            val activeList = activeNotifications ?: return
            for (sbn in activeList) {
                extractAndRecordTaskFromStatusBarNotification(sbn, prefs)
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        @Volatile
        private var activeInstance: PhoneWeatherNotificationListenerService? = null

        fun syncActiveTaskNotificationsNow(context: Context) {
            activeInstance?.syncActiveTaskNotificationsInternal(context)
        }

        private fun extractAndRecordTaskFromStatusBarNotification(
            sbn: StatusBarNotification,
            prefs: SyncPreferences
        ): Boolean {
            if (!prefs.isTasksSyncEnabled()) return false
            val pkg = sbn.packageName.lowercase(Locale.ROOT)
            val isSamsungReminderPkg = pkg.contains("com.samsung.android.app.reminder") || pkg.contains("reminder")
            val isGoogleTasksPkg = pkg.contains("com.google.android.apps.tasks") ||
                pkg.contains("tasks") ||
                pkg.contains("microsoft.todos")
            val allowedBySourceToggle = (isSamsungReminderPkg && prefs.isSamsungReminderSyncEnabled()) ||
                (isGoogleTasksPkg && prefs.isGoogleTasksSyncEnabled())
            if (!allowedBySourceToggle) return false

            val extras = sbn.notification?.extras ?: return false
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val text = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                )?.toString()?.trim().orEmpty()
            val sourceLabel = if (isSamsungReminderPkg) "Reminder" else "Tasks"
            val taskHeadline = when {
                title.isNotBlank() &&
                    !title.equals("Reminder", ignoreCase = true) &&
                    !title.equals("Lembrete", ignoreCase = true) &&
                    !title.equals("Tasks", ignoreCase = true) &&
                    !title.equals("Tarefas", ignoreCase = true) -> title
                text.isNotBlank() -> text
                else -> ""
            }
            if (taskHeadline.isBlank()) return false

            val timeRegex = Regex("(\\d{1,2}:\\d{2})")
            val dueHHMM = timeRegex.find("$title $text")?.groupValues?.getOrNull(1)?.padStart(5, '0') ?: ""
            prefs.recordObservedTaskNotification(
                title = taskHeadline,
                sourceLabel = sourceLabel,
                dueHHMM = dueHHMM,
                timestampMs = sbn.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
            return true
        }
    }
}

/**
 * Serviço em Tempo Real de Sincronização de Tarefas (`ChronoWearTasksAccessibilityService`):
 * Como o Samsung Reminder (`com.samsung.android.app.reminder`) restringe seu ContentProvider a apps
 * com assinatura de sistema da Samsung (`protectionLevel="signature"`) e o Google Tasks (`com.google.android.apps.tasks`)
 * possui ContentProvider privado (`exported="false"`), este serviço observa em tempo real as janelas de:
 * - Samsung Calendar (`com.samsung.android.calendar`)
 * - Samsung Reminder (`com.samsung.android.app.reminder`)
 * - Google Tasks (`com.google.android.apps.tasks`)
 * - Google Calendar (`com.google.android.calendar`)
 *
 * Assim que o usuário cria, edita, visualiza ou conclui uma tarefa em qualquer um desses aplicativos,
 * o serviço extrai a lista atualizada e dispara imediatamente o push para o relógio Wear OS.
 */
class ChronoWearTasksAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncDebounceJob: Job? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.lowercase(Locale.ROOT) ?: return
        val isSupportedPkg = pkg.contains("com.samsung.android.calendar") ||
            pkg.contains("com.samsung.android.app.reminder") ||
            pkg.contains("com.google.android.apps.tasks") ||
            pkg.contains("com.google.android.calendar")
        if (!isSupportedPkg) return

        val prefs = SyncPreferences(applicationContext)
        if (!prefs.isTasksSyncEnabled()) return

        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return

        try {
            // Nunca captura textos parciais enquanto o usuário ainda está digitando em um EditText
            if (hasFocusedEditText(root)) return

            val nowMs = System.currentTimeMillis()
            val changed = when {
                pkg.contains("com.samsung.android.calendar") ->
                    parseSamsungCalendarWindow(root, prefs, nowMs)
                pkg.contains("com.samsung.android.app.reminder") ->
                    parseSamsungReminderWindow(root, prefs, nowMs)
                pkg.contains("com.google.android.apps.tasks") || pkg.contains("com.google.android.calendar") ->
                    parseGoogleTasksWindow(root, prefs, nowMs)
                else -> false
            }

            if (changed) {
                syncDebounceJob?.cancel()
                syncDebounceJob = scope.launch {
                    delay(650L)
                    CalendarSyncEngine.performFullSyncToWatch(
                        context = applicationContext,
                        triggerSource = "Tarefas em Tempo Real"
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
        syncDebounceJob?.cancel()
    }

    override fun onDestroy() {
        syncDebounceJob?.cancel()
        super.onDestroy()
    }

    private fun hasFocusedEditText(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val cls = node.className?.toString().orEmpty()
        if (node.isFocused && cls.contains("EditText", ignoreCase = true)) {
            return true
        }
        for (i in 0 until node.childCount) {
            if (hasFocusedEditText(node.getChild(i))) return true
        }
        return false
    }

    private data class FlatNodeEntry(
        val text: String,
        val contentDesc: String,
        val viewId: String,
        val className: String,
        val isCheckable: Boolean,
        val isChecked: Boolean
    )

    private data class RowContainerInfo(
        val texts: List<String>,
        val contentDescs: List<String>,
        val hasCheckableChild: Boolean,
        val isCheckedOrCompleted: Boolean
    )

    private fun collectFlatNodes(node: AccessibilityNodeInfo?, out: MutableList<FlatNodeEntry>) {
        if (node == null || !node.isVisibleToUser) return
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText", ignoreCase = true)) return

        val txt = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        if (txt.isNotBlank() || desc.isNotBlank() || node.isCheckable) {
            out.add(
                FlatNodeEntry(
                    text = txt,
                    contentDesc = desc,
                    viewId = id,
                    className = cls,
                    isCheckable = node.isCheckable,
                    isChecked = node.isChecked
                )
            )
        }
        for (i in 0 until node.childCount) {
            collectFlatNodes(node.getChild(i), out)
        }
    }

    private fun collectRowContainers(node: AccessibilityNodeInfo?, out: MutableList<RowContainerInfo>) {
        if (node == null || !node.isVisibleToUser) return
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText", ignoreCase = true)) return

        val directFlat = mutableListOf<FlatNodeEntry>()
        collectFlatNodes(node, directFlat)

        val isLeafContainer = node.childCount in 1..8 &&
            directFlat.count { it.text.isNotBlank() } in 1..5 &&
            !cls.contains("RecyclerView", ignoreCase = true) &&
            !cls.contains("ListView", ignoreCase = true) &&
            !cls.contains("ScrollView", ignoreCase = true) &&
            !cls.contains("ViewPager", ignoreCase = true)

        if (isLeafContainer) {
            val texts = directFlat.mapNotNull { it.text.takeIf { s -> s.isNotBlank() } }
            val descs = directFlat.mapNotNull { it.contentDesc.takeIf { s -> s.isNotBlank() } }
            val hasCheck = directFlat.any {
                it.isCheckable ||
                    it.className.contains("CheckBox", ignoreCase = true) ||
                    it.className.contains("RadioButton", ignoreCase = true) ||
                    it.viewId.contains("check") ||
                    it.viewId.contains("complete") ||
                    it.viewId.contains("reminder") ||
                    it.viewId.contains("task")
            }
            val isDone = directFlat.any {
                it.isChecked ||
                    it.contentDesc.contains("concluíd", ignoreCase = true) ||
                    it.contentDesc.contains("completed", ignoreCase = true) ||
                    it.contentDesc.contains("marcado", ignoreCase = true)
            }
            out.add(RowContainerInfo(texts, descs, hasCheck, isDone))
        }

        for (i in 0 until node.childCount) {
            collectRowContainers(node.getChild(i), out)
        }
    }

    /**
     * 1) Parser em Tempo Real do Samsung Calendar (`com.samsung.android.calendar`):
     * Captura tarefas e lembretes exibidos no popup diário da agenda oficial (como "12:30 ○ fazer almoço",
     * "18:00 ○ Fazer almoço", "23:00 ○ Fazer o jantar"), diferenciando-os automaticamente dos
     * compromissos comuns da agenda (que possuem intervalo "HH:mm - HH:mm", como "13:30 - 15:30").
     */
    private fun parseSamsungCalendarWindow(
        root: AccessibilityNodeInfo,
        prefs: SyncPreferences,
        nowMs: Long
    ): Boolean {
        if (!prefs.isSamsungReminderSyncEnabled() && !prefs.isGoogleTasksSyncEnabled()) return false

        val allNodes = mutableListOf<FlatNodeEntry>()
        collectFlatNodes(root, allNodes)
        if (allNodes.isEmpty()) return false

        val allTexts = allNodes.map { it.text }.filter { it.isNotBlank() }

        // Verifica se o popup de dia da agenda está aberto (ex.: "Adic. em 9 de nov." ou cabeçalho do dia)
        val addOnDayRegex = Regex("(?:adic\\.?|adicionar|add).*?(\\d{1,2})\\s*(?:de\\s+)?([a-zç]{3,})?", RegexOption.IGNORE_CASE)
        val addBtnText = allTexts.firstOrNull { addOnDayRegex.containsMatchIn(it) } ?: return false
        val addMatch = addOnDayRegex.find(addBtnText) ?: return false
        val popupDay = addMatch.groupValues[1].toIntOrNull() ?: return false
        val popupMonthToken = addMatch.groupValues.getOrNull(2)?.lowercase(Locale.ROOT)?.take(3).orEmpty()

        val monthMap = mapOf(
            "jan" to Calendar.JANUARY,
            "fev" to Calendar.FEBRUARY, "feb" to Calendar.FEBRUARY,
            "mar" to Calendar.MARCH,
            "abr" to Calendar.APRIL, "apr" to Calendar.APRIL,
            "mai" to Calendar.MAY, "may" to Calendar.MAY,
            "jun" to Calendar.JUNE,
            "jul" to Calendar.JULY,
            "ago" to Calendar.AUGUST, "aug" to Calendar.AUGUST,
            "set" to Calendar.SEPTEMBER, "sep" to Calendar.SEPTEMBER,
            "out" to Calendar.OCTOBER, "oct" to Calendar.OCTOBER,
            "nov" to Calendar.NOVEMBER,
            "dez" to Calendar.DECEMBER, "dec" to Calendar.DECEMBER
        )

        val todayCal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val popupCal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            if (popupMonthToken in monthMap) {
                set(Calendar.MONTH, monthMap[popupMonthToken]!!)
            }
            set(Calendar.DAY_OF_MONTH, popupDay)
        }

        val dayDiff = CalendarSyncEngine.calendarDaysDiff(todayCal.timeInMillis, popupCal.timeInMillis)
        val dayCategory = when {
            dayDiff == 0 -> "TODAY"
            dayDiff == 1 -> "TOMORROW"
            dayDiff in -30..-1 -> "OVERDUE"
            else -> return false
        }

        val singleTimeRegex = Regex("^(\\d{1,2}:\\d{2})$")
        val timeRangeRegex = Regex("\\d{1,2}:\\d{2}\\s*[-–~]\\s*\\d{1,2}:\\d{2}")

        // Títulos de eventos normais do CalendarContract para nunca confundir compromisso com tarefa
        val calendarEventTitlesLower = if (CalendarSyncEngine.hasCalendarPermission(applicationContext)) {
            CalendarSyncEngine.queryHorizonEvents(applicationContext)
                .map { it.title.trim().lowercase(Locale.ROOT) }
                .toSet()
        } else {
            emptySet()
        }

        val capturedTasks = mutableListOf<MobileTaskItem>()
        val completedTitles = mutableSetOf<String>()

        // Estratégia 1: Analisa a sequência linear de textos no popup do dia:
        // No Samsung Calendar, cada linha de Tarefa/Lembrete no popup diário exibe:
        // [HH:mm] -> [Título da Tarefa] -> [HH:mm] (horário simples repetido, sem "- HH:mm" de término!)
        // Enquanto um Compromisso normal exibe:
        // [HH:mm] -> [Título do Compromisso] -> [HH:mm - HH:mm]
        var i = 0
        while (i < allNodes.size) {
            val currentText = allNodes[i].text
            val timeMatch = singleTimeRegex.find(currentText)
            if (timeMatch != null && i + 1 < allNodes.size) {
                val hhmm = timeMatch.groupValues[1].padStart(5, '0')
                // Procura o título logo após o horário inicial
                var titleIdx = i + 1
                var rowHasCompletedCheck = allNodes[i].isChecked
                while (titleIdx < allNodes.size && allNodes[titleIdx].text.isBlank()) {
                    if (allNodes[titleIdx].isChecked ||
                        allNodes[titleIdx].contentDesc.contains("concluíd", ignoreCase = true) ||
                        allNodes[titleIdx].contentDesc.contains("completed", ignoreCase = true)
                    ) {
                        rowHasCompletedCheck = true
                    }
                    titleIdx++
                }
                if (titleIdx < allNodes.size) {
                    val candidateTitle = allNodes[titleIdx].text.trim()
                    val subtitleText = if (titleIdx + 1 < allNodes.size) allNodes[titleIdx + 1].text.trim() else ""

                    val isTimeRangeEvent = timeRangeRegex.containsMatchIn(subtitleText) ||
                        timeRangeRegex.containsMatchIn(candidateTitle)
                    val isSingleTimeTaskSubtitle = singleTimeRegex.matches(subtitleText) ||
                        subtitleText.equals("Dia todo", ignoreCase = true) ||
                        subtitleText.equals("All day", ignoreCase = true)

                    if (!isTimeRangeEvent &&
                        isSingleTimeTaskSubtitle &&
                        ! isIgnoredUiChromeText(candidateTitle) &&
                        !calendarEventTitlesLower.contains(candidateTitle.lowercase(Locale.ROOT))
                    ) {
                        if (rowHasCompletedCheck) {
                            completedTitles.add(candidateTitle)
                        } else {
                            val sourceLabel = if (prefs.isSamsungReminderSyncEnabled()) "Reminder" else "Tasks"
                            capturedTasks.add(
                                MobileTaskItem(
                                    id = "scal_${dayCategory}_${hhmm}_${candidateTitle.hashCode()}",
                                    title = candidateTitle,
                                    dueHHMM = if (dayCategory == "TODAY" || dayCategory == "TOMORROW") hhmm else "",
                                    sourceLabel = sourceLabel,
                                    completed = false,
                                    isTodayOrTimeless = true,
                                    scheduleCategory = dayCategory,
                                    isOverdue = dayCategory == "OVERDUE"
                                )
                            )
                        }
                        i = titleIdx + 2
                        continue
                    }
                }
            }
            i++
        }

        val targetSource = if (prefs.isSamsungReminderSyncEnabled()) "Reminder" else "Tasks"
        return prefs.recordObservedScreenTasksSnapshot(
            capturedTasks = capturedTasks,
            completedTitles = completedTitles,
            replaceSourceLabel = targetSource,
            replaceScheduleCategory = dayCategory,
            nowMs = nowMs
        )
    }

    /**
     * 2) Parser em Tempo Real do App Samsung Reminder (`com.samsung.android.app.reminder`):
     * Captura tarefas criadas, editadas ou concluídas diretamente no app Samsung Reminder.
     */
    private fun parseSamsungReminderWindow(
        root: AccessibilityNodeInfo,
        prefs: SyncPreferences,
        nowMs: Long
    ): Boolean {
        if (!prefs.isSamsungReminderSyncEnabled()) return false

        val allNodes = mutableListOf<FlatNodeEntry>()
        collectFlatNodes(root, allNodes)
        if (allNodes.isEmpty()) return false

        val allTexts = allNodes.map { it.text }.filter { it.isNotBlank() }
        val hasEmptyState = allTexts.any {
            it.equals("Sem lembretes", ignoreCase = true) ||
                it.equals("Nenhum lembrete", ignoreCase = true) ||
                it.equals("No reminders", ignoreCase = true)
        }
        if (hasEmptyState) {
            return prefs.recordObservedScreenTasksSnapshot(
                capturedTasks = emptyList(),
                clearAllForSourceLabel = "Reminder",
                nowMs = nowMs
            )
        }

        val rows = mutableListOf<RowContainerInfo>()
        collectRowContainers(root, rows)

        val capturedTasks = mutableListOf<MobileTaskItem>()
        val completedTitles = mutableSetOf<String>()
        val timeRegex = Regex("(\\d{1,2}:\\d{2})")

        for (row in rows) {
            if (!row.hasCheckableChild) continue
            val cleanTexts = row.texts.map { it.trim() }.filter { it.isNotBlank() && !isIgnoredUiChromeText(it) }
            if (cleanTexts.isEmpty()) continue

            val title = cleanTexts.firstOrNull { !timeRegex.matches(it) } ?: continue
            if (row.isCheckedOrCompleted) {
                completedTitles.add(title)
                continue
            }

            val metaCombined = cleanTexts.drop(1).joinToString(" ").lowercase(Locale.ROOT)
            val matchedHHMM = timeRegex.find( cleanTexts.joinToString(" ") )?.groupValues?.getOrNull(1)?.padStart(5, '0').orEmpty()

            val category = when {
                metaCombined.contains("atras") || metaCombined.contains("ontem") || metaCombined.contains("passad") -> "OVERDUE"
                metaCombined.contains("amanh") || metaCombined.contains("tomorrow") -> "TOMORROW"
                metaCombined.contains("hoje") || metaCombined.contains("today") || matchedHHMM.isNotBlank() -> "TODAY"
                else -> "UNDATED"
            }

            capturedTasks.add(
                MobileTaskItem(
                    id = "srem_ui_${title.hashCode()}",
                    title = title,
                    dueHHMM = if (category == "TODAY" || category == "TOMORROW") matchedHHMM.ifBlank { "09:00" } else "",
                    sourceLabel = "Reminder",
                    completed = false,
                    isTodayOrTimeless = true,
                    scheduleCategory = category,
                    isOverdue = category == "OVERDUE"
                )
            )
        }

        if (capturedTasks.isEmpty() && completedTitles.isEmpty()) return false

        return prefs.recordObservedScreenTasksSnapshot(
            capturedTasks = capturedTasks,
            completedTitles = completedTitles,
            nowMs = nowMs
        )
    }

    /**
     * 3) Parser em Tempo Real do App Google Tasks (`com.google.android.apps.tasks`):
     * Captura tarefas criadas, editadas ou concluídas no Google Tasks.
     */
    private fun parseGoogleTasksWindow(
        root: AccessibilityNodeInfo,
        prefs: SyncPreferences,
        nowMs: Long
    ): Boolean {
        if (!prefs.isGoogleTasksSyncEnabled()) return false

        val allNodes = mutableListOf<FlatNodeEntry>()
        collectFlatNodes(root, allNodes)
        if (allNodes.isEmpty()) return false

        val allTexts = allNodes.map { it.text }.filter { it.isNotBlank() }
        val hasEmptyState = allTexts.any {
            it.contains("Ainda não há tarefas", ignoreCase = true) ||
                it.contains("Nenhuma tarefa", ignoreCase = true) ||
                it.contains("Tudo pronto", ignoreCase = true) ||
                it.contains("No tasks yet", ignoreCase = true)
        }
        if (hasEmptyState) {
            return prefs.recordObservedScreenTasksSnapshot(
                capturedTasks = emptyList(),
                clearAllForSourceLabel = "Tasks",
                nowMs = nowMs
            )
        }

        val rows = mutableListOf<RowContainerInfo>()
        collectRowContainers(root, rows)

        val capturedTasks = mutableListOf<MobileTaskItem>()
        val completedTitles = mutableSetOf<String>()
        val timeRegex = Regex("(\\d{1,2}:\\d{2})")

        for (row in rows) {
            val isTaskRow = row.hasCheckableChild || row.contentDescs.any {
                it.contains("concluída", ignoreCase = true) ||
                    it.contains("completed", ignoreCase = true) ||
                    it.contains("tarefa", ignoreCase = true) ||
                    it.contains("task", ignoreCase = true)
            }
            if (!isTaskRow) continue

            val cleanTexts = row.texts.map { it.trim() }.filter { it.isNotBlank() && !isIgnoredUiChromeText(it) }
            if (cleanTexts.isEmpty()) continue

            val title = cleanTexts.firstOrNull { !isDateOrTimeBadgeOnly(it) } ?: continue
            if (row.isCheckedOrCompleted) {
                completedTitles.add(title)
                continue
            }

            val dateTextsLower = cleanTexts.filter { it != title }.joinToString(" ").lowercase(Locale.ROOT)
            val matchedHHMM = timeRegex.find(dateTextsLower)?.groupValues?.getOrNull(1)?.padStart(5, '0').orEmpty()

            val category = when {
                dateTextsLower.contains("ontem") ||
                    dateTextsLower.contains("atrás") ||
                    dateTextsLower.contains("atras") ||
                    dateTextsLower.contains("semana") ||
                    dateTextsLower.contains("yesterday") ||
                    dateTextsLower.contains("ago") -> "OVERDUE"
                dateTextsLower.contains("amanh") || dateTextsLower.contains("tomorrow") -> "TOMORROW"
                dateTextsLower.contains("hoje") || dateTextsLower.contains("today") || matchedHHMM.isNotBlank() -> "TODAY"
                else -> "UNDATED"
            }

            capturedTasks.add(
                MobileTaskItem(
                    id = "gtask_ui_${title.hashCode()}",
                    title = title,
                    dueHHMM = if (category == "TODAY" || category == "TOMORROW") matchedHHMM.ifBlank { "09:00" } else "",
                    sourceLabel = "Tasks",
                    completed = false,
                    isTodayOrTimeless = true,
                    scheduleCategory = category,
                    isOverdue = category == "OVERDUE"
                )
            )
        }

        if (capturedTasks.isEmpty() && completedTitles.isEmpty()) return false

        return prefs.recordObservedScreenTasksSnapshot(
            capturedTasks = capturedTasks,
            completedTitles = completedTitles,
            replaceSourceLabel = if (capturedTasks.isNotEmpty()) "Tasks" else null,
            nowMs = nowMs
        )
    }

    private fun isDateOrTimeBadgeOnly(text: String): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        if (Regex("^\\d{1,2}:\\d{2}$").matches(lower)) return true
        if (lower == "hoje" || lower == "amanhã" || lower == "ontem" || lower == "today" || lower == "tomorrow") return true
        if (lower.startsWith("hoje,") || lower.startsWith("amanhã,") || lower.startsWith("ontem,")) return true
        if (Regex("^\\d{1,2}\\s+de\\s+[a-zç]{3,}.*").matches(lower)) return true
        return false
    }

    private fun isIgnoredUiChromeText(text: String): Boolean {
        val clean = text.trim()
        if (clean.length <= 1) return true
        if (clean.all { it.isDigit() }) return true
        val lower = clean.lowercase(Locale.ROOT)
        val ignoredExact = setOf(
            "lembrete", "lembretes", "reminder", "reminders",
            "tarefa", "tarefas", "tasks", "google tasks", "minhas tarefas", "my tasks",
            "hoje", "amanhã", "ontem", "sem data", "sem alerta", "sem horário",
            "concluídas", "concluídos", "completed", "salvar", "save", "cancelar", "cancel",
            "excluir", "delete", "editar", "edit", "adicionar", "nova tarefa", "novo lembrete",
            "segunda-feira", "terça-feira", "quarta-feira", "quinta-feira", "sexta-feira", "sábado", "domingo",
            "janeiro", "fevereiro", "março", "abril", "maio", "junho",
            "julho", "agosto", "setembro", "outubro", "novembro", "dezembro",
            "dia todo", "all day", "próximos", "passados", "atrasados", "favoritos", "todas"
        )
        if (lower in ignoredExact) return true
        if (lower.startsWith("adic.") || lower.startsWith("adicionar em") || lower.startsWith("concluíd")) return true
        return false
    }
}
