package com.chronowear.mobile.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

sealed class GoogleTasksAuthResult {
    data class Authorized(val accessToken: String, val accountEmail: String) : GoogleTasksAuthResult()
    data class NeedsUserConsent(val consentIntent: Intent, val accountEmail: String) : GoogleTasksAuthResult()
    data class UnregisteredOnConsole(
        val packageName: String,
        val sha1Fingerprint: String,
        val accountEmail: String
    ) : GoogleTasksAuthResult()
    data class Error(val reason: String) : GoogleTasksAuthResult()
}

/**
 * Cliente Oficial da API REST do Google Tasks (`https://tasks.googleapis.com/tasks/v1`).
 * Sincroniza as listas e tarefas diretamente da conta Google do usuário via OAuth 2.0,
 * sem depender de leitura local de banco de dados, acessibilidade ou notificações da barra de status.
 */
object GoogleTasksApiClient {

    const val SCOPE_TASKS = "https://www.googleapis.com/auth/tasks"
    const val SCOPE_TASKS_READONLY = "https://www.googleapis.com/auth/tasks.readonly"
    const val OAUTH_SCOPE_STRING = "oauth2:$SCOPE_TASKS $SCOPE_TASKS_READONLY"
    const val WEB_OAUTH_CLIENT_ID = "65152854911-1gc862g8m5f7905c3q54n365cecuthha.apps.googleusercontent.com"
    const val DEFAULT_FIXED_DEBUG_SHA1 = "EC:66:EE:98:17:74:F1:C2:B2:10:D7:1D:19:0E:02:8D:F3:98:4D:71"
    const val DEFAULT_FIXED_DEBUG_SHA1_COMPACT = "EC66EE981774F1C2B210D71D190E028DF3984D71"
    const val ANDROID_PACKAGE_NAME = "com.chronowear.sync"
    const val CLOUD_CONSOLE_PROJECT_ID = "gen-lang-client-0127839135"

    @Volatile
    var pendingConsentIntent: Intent? = null
        private set

    /**
     * Lê a impressão digital SHA-1 real do certificado que assinou o APK instalado no aparelho.
     * Esse SHA-1 + Package Name (`com.chronowear.sync`) são exigidos pelo Google Play Services
     * na criação do "ID do cliente OAuth 2.0 (Tipo: Android)" no Google Cloud Console.
     */
    @Suppress("DEPRECATION")
    fun getAppSignatureSha1(context: Context): String {
        return try {
            val pm = context.packageManager
            val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = info.signingInfo
                val signers = if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else {
                    null
                }
                signers?.firstOrNull()?.toByteArray()
            } else {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                info.signatures?.firstOrNull()?.toByteArray()
            }

            if (certBytes != null && certBytes.isNotEmpty()) {
                val md = MessageDigest.getInstance("SHA-1")
                val digest = md.digest(certBytes)
                digest.joinToString(":") { b -> String.format(Locale.ROOT, "%02X", b) }
            } else {
                DEFAULT_FIXED_DEBUG_SHA1
            }
        } catch (_: Exception) {
            DEFAULT_FIXED_DEBUG_SHA1
        }
    }

    /**
     * Descobre automaticamente o e-mail da conta Google já usada na Agenda do aparelho
     * caso o usuário ainda não tenha selecionado manualmente uma conta para o Google Tasks.
     */
    fun resolveDefaultAccountEmail(context: Context, prefs: SyncPreferences): String =
        resolveDefaultGoogleAccountEmail(context, prefs)

    fun resolveDefaultGoogleAccountEmail(context: Context, prefs: SyncPreferences): String {
        val saved = prefs.getGoogleTasksAccountEmail().trim()
        if (saved.isNotBlank()) return saved

        if (CalendarSyncEngine.hasCalendarPermission(context)) {
            val calendars = CalendarSyncEngine.loadDeviceCalendars(context)
            val gmailCal = calendars.firstOrNull {
                it.accountName.contains("@gmail.com", ignoreCase = true) ||
                    it.accountName.contains("@googlemail.com", ignoreCase = true) ||
                    it.ownerAccount.contains("@gmail.com", ignoreCase = true)
            } ?: calendars.firstOrNull { it.accountName.contains("@") }

            val candidateEmail = gmailCal?.accountName?.takeIf { it.contains("@") }
                ?: gmailCal?.ownerAccount?.takeIf { it.contains("@") }
                ?: ""
            if (candidateEmail.isNotBlank()) {
                prefs.setGoogleTasksAccountEmail(candidateEmail)
                return candidateEmail
            }
        }
        return ""
    }

    /**
     * Obtém um Access Token OAuth 2.0 válido para a API do Google Tasks.
     */
    fun acquireAccessToken(
        context: Context,
        prefs: SyncPreferences,
        explicitEmail: String? = null
    ): GoogleTasksAuthResult {
        val email = (explicitEmail ?: resolveDefaultGoogleAccountEmail(context, prefs)).trim()
        if (email.isNotBlank()) {
            try {
                val account = Account(email, "com.google")
                val token = GoogleAuthUtil.getToken(context, account, OAUTH_SCOPE_STRING)
                if (!token.isNullOrBlank()) {
                    pendingConsentIntent = null
                    prefs.setGoogleTasksAccountEmail(email)
                    prefs.setGoogleTasksAccessToken(token)
                    return GoogleTasksAuthResult.Authorized(token, email)
                }
            } catch (recoverable: UserRecoverableAuthException) {
                val intent = recoverable.intent
                if (intent != null) {
                    pendingConsentIntent = intent
                    prefs.setGoogleTasksAccountEmail(email)
                    prefs.setGoogleTasksSyncStatus("Autorização pendente para $email")
                    return GoogleTasksAuthResult.NeedsUserConsent(intent, email)
                }
            } catch (e: Exception) {
                val cachedToken = prefs.getGoogleTasksAccessToken()
                if (cachedToken.isNotBlank()) {
                    return GoogleTasksAuthResult.Authorized(cachedToken, email)
                }
                val rawMsg = (e.message ?: e.localizedMessage ?: "").trim()
                if (
                    rawMsg.contains("UnregisteredOnApiConsole", ignoreCase = true) ||
                    rawMsg.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) ||
                    rawMsg.contains("DEVELOPER_ERROR", ignoreCase = true)
                ) {
                    val sha1 = getAppSignatureSha1(context)
                    val pkg = context.packageName
                    prefs.setGoogleTasksAccountEmail(email)
                    prefs.setGoogleTasksSyncStatus(
                        "Requer cadastro do SHA-1 ($sha1) e pacote ($pkg) no Google Cloud Console"
                    )
                    return GoogleTasksAuthResult.UnregisteredOnConsole(
                        packageName = pkg,
                        sha1Fingerprint = sha1,
                        accountEmail = email
                    )
                }
                return GoogleTasksAuthResult.Error(
                    rawMsg.ifBlank { "Falha ao autenticar no Google Tasks" }
                )
            }
        }

        val cachedToken = prefs.getGoogleTasksAccessToken()
        if (cachedToken.isNotBlank()) {
            return GoogleTasksAuthResult.Authorized(cachedToken, email.ifBlank { "Conta Google" })
        }

        return GoogleTasksAuthResult.Error("Nenhuma conta Google conectada ao Google Tasks")
    }

    /**
     * Consulta a API REST oficial do Google Tasks (`https://tasks.googleapis.com/tasks/v1/users/@me/lists`
     * e `/lists/{listId}/tasks`), aplicando a hierarquia estrita:
     * 1. TODAY (Tarefas • Hoje)
     * 2. TOMORROW (Tarefas • Amanhã)
     * 3. UNDATED / OVERDUE <= 30 dias (Tarefas • Sem data)
     */
    fun fetchTasksFromGoogleApi(
        context: Context,
        prefs: SyncPreferences,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        if (!prefs.isTasksSyncEnabled()) {
            prefs.saveTasksJson("[]")
            return "[]"
        }

        val authResult = acquireAccessToken(context, prefs)
        val (initialToken, accountEmail) = when (authResult) {
            is GoogleTasksAuthResult.Authorized -> authResult.accessToken to authResult.accountEmail
            is GoogleTasksAuthResult.NeedsUserConsent -> {
                prefs.setGoogleTasksSyncStatus("Toque em 'Autorizar Google Tasks' para liberar acesso à API")
                return prefs.getTasksJson()
            }
            is GoogleTasksAuthResult.UnregisteredOnConsole -> {
                prefs.setGoogleTasksSyncStatus(
                    "Requer cadastro do SHA-1 (${authResult.sha1Fingerprint}) para ${authResult.packageName} no Cloud Console"
                )
                return prefs.getTasksJson()
            }
            is GoogleTasksAuthResult.Error -> {
                prefs.setGoogleTasksSyncStatus(authResult.reason)
                return prefs.getTasksJson()
            }
        }

        var activeToken = initialToken
        var listsResponse = executeGetRequest(
            "https://tasks.googleapis.com/tasks/v1/users/@me/lists?maxResults=20",
            activeToken
        )

        // Se o token expirou (HTTP 401), limpa o token em cache no Google Play Services e renova 1 vez
        if (listsResponse.first == 401 && accountEmail.contains("@")) {
            try {
                GoogleAuthUtil.clearToken(context, activeToken)
                val refreshed = GoogleAuthUtil.getToken(
                    context,
                    Account(accountEmail, "com.google"),
                    OAUTH_SCOPE_STRING
                )
                if (!refreshed.isNullOrBlank()) {
                    activeToken = refreshed
                    prefs.setGoogleTasksAccessToken(activeToken)
                    listsResponse = executeGetRequest(
                        "https://tasks.googleapis.com/tasks/v1/users/@me/lists?maxResults=20",
                        activeToken
                    )
                }
            } catch (_: Exception) {
            }
        }

        val (statusCode, listsBody) = listsResponse
        if (statusCode != 200 || listsBody.isBlank()) {
            prefs.setGoogleTasksSyncStatus("Erro HTTP $statusCode ao consultar Google Tasks API")
            return prefs.getTasksJson()
        }

        val sdfIsoLocal = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        val todayIso = sdfIsoLocal.format(Date(nowMs))
        val tomorrowIso = sdfIsoLocal.format(Date(nowMs + TimeUnit.DAYS.toMillis(1)))
        val yesterdayIso = sdfIsoLocal.format(Date(nowMs - TimeUnit.DAYS.toMillis(1)))
        val thirtyDaysAgoIso = sdfIsoLocal.format(Date(nowMs - TimeUnit.DAYS.toMillis(30)))
        val timeRegex = Regex("(\\d{1,2}:\\d{2})")

        val collectedTasks = mutableListOf<MobileTaskItem>()
        val taskDueDateIsoMap = mutableMapOf<String, String>()

        try {
            val rootObj = JSONObject(listsBody)
            val listsArr = rootObj.optJSONArray("items") ?: JSONArray()

            for (i in 0 until listsArr.length()) {
                val listObj = listsArr.optJSONObject(i) ?: continue
                val listId = listObj.optString("id", "").trim()
                if (listId.isBlank()) continue

                val encodedListId = URLEncoder.encode(listId, "UTF-8")
                val tasksUrl = "https://tasks.googleapis.com/tasks/v1/lists/$encodedListId/tasks" +
                    "?showCompleted=false&showHidden=false&showDeleted=false&maxResults=100"
                val (tCode, tBody) = executeGetRequest(tasksUrl, activeToken)
                if (tCode != 200 || tBody.isBlank()) continue

                val tasksRoot = JSONObject(tBody)
                val itemsArr = tasksRoot.optJSONArray("items") ?: continue

                for (j in 0 until itemsArr.length()) {
                    val taskObj = itemsArr.optJSONObject(j) ?: continue
                    val status = taskObj.optString("status", "needsAction")
                    val deleted = taskObj.optBoolean("deleted", false)
                    val hidden = taskObj.optBoolean("hidden", false)
                    if (deleted || hidden || status.equals("completed", ignoreCase = true)) continue

                    val rawTitle = taskObj.optString("title", "").trim()
                    if (rawTitle.isBlank()) continue

                    val taskId = taskObj.optString("id", "gt_${i}_$j")
                    val notes = taskObj.optString("notes", "").trim()
                    val dueRfc3339 = taskObj.optString("due", "").trim()
                    val updatedRfc3339 = taskObj.optString("updated", "").trim()

                    val dueDateIso = if (dueRfc3339.length >= 10) dueRfc3339.take(10) else ""
                    val updatedDateIso = if (updatedRfc3339.length >= 10) updatedRfc3339.take(10) else ""

                    // Regra estrita de 1 mês (30 dias): tarefas antigas > 30 dias não são sincronizadas
                    if (dueDateIso.isNotBlank() && dueDateIso < thirtyDaysAgoIso) continue
                    if (dueDateIso.isBlank() && updatedDateIso.isNotBlank() && updatedDateIso < thirtyDaysAgoIso) continue

                    // Extrai horário se houver na nota, no título ou no campo due (quando não for 00:00:00Z)
                    val matchedTimeInText = timeRegex.find("$rawTitle $notes")?.groupValues?.getOrNull(1)?.padStart(5, '0')
                    val dueTimePart = extractLocalHHMMFromRfc3339IfNonMidnight(dueRfc3339)
                    val resolvedHHMM = matchedTimeInText ?: dueTimePart ?: "09:00"

                    val category: String
                    val displayHHMM: String
                    when {
                        dueDateIso.isBlank() -> {
                            category = "UNDATED"
                            displayHHMM = ""
                        }
                        dueDateIso < todayIso -> {
                            category = "OVERDUE"
                            displayHHMM = ""
                        }
                        dueDateIso == todayIso -> {
                            category = "TODAY"
                            displayHHMM = resolvedHHMM
                        }
                        dueDateIso == tomorrowIso -> {
                            category = "TOMORROW"
                            displayHHMM = resolvedHHMM
                        }
                        else -> {
                            // Tarefas futuras além de amanhã não entram no quadro de tarefas
                            continue
                        }
                    }

                    val item = MobileTaskItem(
                        id = "gtask_$taskId",
                        title = rawTitle,
                        dueHHMM = displayHHMM,
                        sourceLabel = "Tasks",
                        completed = false,
                        isTodayOrTimeless = true,
                        scheduleCategory = category,
                        isOverdue = category == "OVERDUE"
                    )
                    taskDueDateIsoMap[item.id] = dueDateIso

                    val existingIdx = collectedTasks.indexOfFirst {
                        it.title.equals(item.title, ignoreCase = true)
                    }
                    if (existingIdx >= 0) {
                        if (categoryPriorityRank(item.scheduleCategory) <= categoryPriorityRank(collectedTasks[existingIdx].scheduleCategory)) {
                            collectedTasks[existingIdx] = item
                        }
                    } else {
                        collectedTasks.add(item)
                    }
                }
            }
        } catch (e: Exception) {
            prefs.setGoogleTasksSyncStatus("Erro ao processar resposta da API: ${e.localizedMessage ?: "JSON"}")
            return prefs.getTasksJson()
        }

        val sortedTasks = collectedTasks.sortedWith(
            compareBy<MobileTaskItem> {
                categoryPriorityRank(it.scheduleCategory)
            }.thenBy { it.dueHHMM.ifBlank { "99:99" } }
        )

        val arr = JSONArray()
        for (t in sortedTasks) {
            val iso = taskDueDateIsoMap[t.id] ?: when (t.scheduleCategory) {
                "TODAY" -> todayIso
                "TOMORROW" -> tomorrowIso
                "OVERDUE" -> yesterdayIso
                else -> ""
            }
            arr.put(
                JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put(
                        "dueHHMM",
                        if (t.scheduleCategory == "TODAY" || t.scheduleCategory == "TOMORROW") {
                            t.dueHHMM
                        } else {
                            ""
                        }
                    )
                    put("dueDateIso", iso)
                    put("sourceLabel", "Tasks")
                    put("completed", false)
                    put("isTodayOrTimeless", true)
                    put("scheduleCategory", t.scheduleCategory)
                    put("isOverdue", t.isOverdue || t.scheduleCategory == "OVERDUE")
                }
            )
        }

        val jsonResult = arr.toString()
        val prevJson = prefs.getTasksJson()
        val alertTs = if (jsonResult != prevJson && sortedTasks.isNotEmpty()) nowMs else 0L
        prefs.saveTasksJson(jsonResult, alertTs)
        prefs.setGoogleTasksLastSyncMs(nowMs)
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMs))
        prefs.setGoogleTasksSyncStatus("Google Tasks API • ${sortedTasks.size} ativas às $timeStr")
        return jsonResult
    }

    private fun categoryPriorityRank(cat: String): Int {
        return when (cat) {
            "TODAY" -> 0
            "TOMORROW" -> 1
            "OVERDUE" -> 2
            else -> 3
        }
    }

    private fun extractLocalHHMMFromRfc3339IfNonMidnight(rfc3339: String): String? {
        if (!rfc3339.contains("T")) return null
        val timePart = rfc3339.substringAfter("T")
        if (timePart.startsWith("00:00:00")) return null
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val clean = rfc3339.substringBefore(".").substringBefore("Z")
            val parsedDate = parser.parse(clean) ?: return null
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(parsedDate)
        } catch (_: Exception) {
            null
        }
    }

    private fun executeGetRequest(urlStr: String, accessToken: String): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Android-Package", ANDROID_PACKAGE_NAME)
                setRequestProperty("X-Android-Cert", DEFAULT_FIXED_DEBUG_SHA1_COMPACT)
            }
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            code to body
        } catch (_: Exception) {
            -1 to ""
        } finally {
            conn?.disconnect()
        }
    }
}
