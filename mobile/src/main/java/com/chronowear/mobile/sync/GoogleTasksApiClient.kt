package com.chronowear.mobile.sync

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
    const val OAUTH_SCOPE_STRING = "oauth2:$SCOPE_TASKS_READONLY"
    const val WEB_OAUTH_CLIENT_ID = "65152854911-1gc862g8m5f7905c3q54n365cecuthha.apps.googleusercontent.com"
    const val FIREBASE_AUTH_REDIRECT_URI = "https://gen-lang-client-0127839135.firebaseapp.com/__/auth/handler"
    const val DEFAULT_FIXED_DEBUG_SHA1 = "8E:EB:C7:D5:9F:32:DB:05:D2:31:E9:51:8B:50:55:79:E2:FB:96:2E"
    const val DEFAULT_FIXED_DEBUG_SHA1_COMPACT = "8EEBC7D59F32DB05D231E9518B505579E2FB962E"
    const val ANDROID_PACKAGE_NAME = "com.chronowear.sync"
    const val CLOUD_CONSOLE_PROJECT_ID = "gen-lang-client-0127839135"

    private val authLock = Any()

    @Volatile
    var isInteractiveConsentInProgress: Boolean = false

    @Volatile
    var pendingConsentIntent: Intent? = null
        private set

    /**
     * Monta a URL OAuth 2.0 oficial (usando o Web Client ID e Redirect URI já autorizados no projeto
     * `gen-lang-client-0127839135`) para autenticação 100% interna no modal do aplicativo Android,
     * sem depender da janela `MinuteMaid` do Google Play Services (que trava na tela "Aprovação da família")
     * e sem exigir cadastro de chave SHA-1 no Google Cloud Console.
     */
    fun buildInAppOAuthUrl(
        loginHintEmail: String? = null,
        forceSelectAccount: Boolean = true
    ): String {
        val encodedClientId = URLEncoder.encode(WEB_OAUTH_CLIENT_ID, "UTF-8")
        val encodedRedirect = URLEncoder.encode(FIREBASE_AUTH_REDIRECT_URI, "UTF-8")
        val scopes = "$SCOPE_TASKS_READONLY https://www.googleapis.com/auth/userinfo.email"
        val encodedScope = URLEncoder.encode(scopes, "UTF-8")
        val promptValue = if (forceSelectAccount) "select_account consent" else "none"
        val encodedPrompt = URLEncoder.encode(promptValue, "UTF-8")
        val hintParam = if (!loginHintEmail.isNullOrBlank() && loginHintEmail.contains("@")) {
            "&login_hint=" + URLEncoder.encode(loginHintEmail.trim(), "UTF-8")
        } else {
            ""
        }
        return "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=$encodedClientId" +
            "&redirect_uri=$encodedRedirect" +
            "&response_type=token" +
            "&include_granted_scopes=true" +
            "&scope=$encodedScope" +
            "&prompt=$encodedPrompt" +
            hintParam
    }

    /**
     * Extrai o `access_token` quando o modal interno redireciona para
     * `https://gen-lang-client-0127839135.firebaseapp.com/__/auth/handler#access_token=...`
     */
    fun extractAccessTokenFromRedirectUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (!url.startsWith(FIREBASE_AUTH_REDIRECT_URI, ignoreCase = true)) return null
        val fragment = url.substringAfter("#", "")
        val query = url.substringAfter("?", "").substringBefore("#")
        val combined = listOf(fragment, query).filter { it.isNotBlank() }.joinToString("&")
        if (combined.isBlank()) return null
        for (pair in combined.split("&")) {
            val parts = pair.split("=", limit = 2)
            if (parts.size == 2 && parts[0].trim() == "access_token") {
                val decoded = try {
                    java.net.URLDecoder.decode(parts[1].trim(), "UTF-8")
                } catch (_: Exception) {
                    parts[1].trim()
                }
                if (decoded.isNotBlank()) return decoded
            }
        }
        return null
    }

    /**
     * Renova silenciosamente o Access Token OAuth 2.0 em 2º plano reutilizando os cookies de sessão
     * já autenticados no WebView interno do app (`prompt=none`), sem abrir janela para o usuário.
     */
    fun refreshTokenSilentlyViaWebView(
        context: Context,
        prefs: SyncPreferences,
        loginHintEmail: String?
    ): String? {
        if (Looper.myLooper() == Looper.getMainLooper() || isInteractiveConsentInProgress) {
            return null
        }
        val hint = (loginHintEmail ?: prefs.getGoogleTasksAccountEmail()).trim()
        val resultRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        var webViewRef: WebView? = null

        fun cleanupAndRelease(token: String?) {
            if (token != null) {
                resultRef.compareAndSet(null, token)
            }
            latch.countDown()
            mainHandler.post {
                try {
                    webViewRef?.stopLoading()
                    webViewRef?.destroy()
                } catch (_: Exception) {
                }
                webViewRef = null
            }
        }

        mainHandler.post {
            try {
                val appCtx = context.applicationContext
                val wv = WebView(appCtx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    val rawUa = settings.userAgentString ?: ""
                    val cleanUa = rawUa
                        .replace("; wv", "")
                        .replace("Version/4.0 ", "")
                        .trim()
                    settings.userAgentString = if (cleanUa.isNotBlank()) {
                        cleanUa
                    } else {
                        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                    }
                }
                webViewRef = wv

                try {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        cookieManager.setAcceptThirdPartyCookies(wv, true)
                    }
                } catch (_: Exception) {
                }

                fun inspectCandidateUrl(candidateUrl: String?): Boolean {
                    if (candidateUrl.isNullOrBlank()) return false
                    val token = extractAccessTokenFromRedirectUrl(candidateUrl)
                    if (!token.isNullOrBlank()) {
                        prefs.setGoogleTasksAccessToken(token)
                        try {
                            CookieManager.getInstance().flush()
                        } catch (_: Exception) {
                        }
                        cleanupAndRelease(token)
                        return true
                    }
                    if (
                        candidateUrl.startsWith(FIREBASE_AUTH_REDIRECT_URI, ignoreCase = true) &&
                        candidateUrl.contains("error=", ignoreCase = true)
                    ) {
                        cleanupAndRelease(null)
                        return true
                    }
                    return false
                }

                wv.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return inspectCandidateUrl(request?.url?.toString())
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        return inspectCandidateUrl(url)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        if (inspectCandidateUrl(url)) return
                        super.onPageStarted(view, url, favicon)
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        if (inspectCandidateUrl(url)) return
                        super.doUpdateVisitedHistory(view, url, isReload)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (inspectCandidateUrl(url)) return
                        // Se terminou de carregar uma página de login/erro sem redirecionar com token, encerra
                        if (!url.isNullOrBlank() && !url.startsWith(FIREBASE_AUTH_REDIRECT_URI, ignoreCase = true)) {
                            mainHandler.postDelayed({
                                if (latch.count > 0) {
                                    cleanupAndRelease(null)
                                }
                            }, 1200L)
                        }
                    }
                }

                val silentUrl = buildInAppOAuthUrl(
                    loginHintEmail = hint,
                    forceSelectAccount = false
                )
                wv.loadUrl(silentUrl)
            } catch (_: Exception) {
                cleanupAndRelease(null)
            }
        }

        try {
            latch.await(8, TimeUnit.SECONDS)
        } catch (_: Exception) {
        } finally {
            if (webViewRef != null) {
                mainHandler.post {
                    try {
                        webViewRef?.stopLoading()
                        webViewRef?.destroy()
                    } catch (_: Exception) {
                    }
                    webViewRef = null
                }
            }
        }
        return resultRef.get()
    }

    /**
     * Consulta o e-mail da conta autenticada a partir do Access Token OAuth 2.0.
     */
    fun fetchUserEmailFromToken(accessToken: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://www.googleapis.com/oauth2/v2/userinfo").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode in 200..299) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(body).optString("email", "").trim()
            } else {
                ""
            }
        } catch (_: Exception) {
            ""
        } finally {
            conn?.disconnect()
        }
    }

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
     * Importante: chamadas em 2º plano (`allowInteractivePrompt = false`) NUNCA invocam
     * `GoogleAuthUtil.getToken` enquanto uma janela de consentimento está aberta ou antes da
     * autorização inicial do usuário, evitando travamento (deadlock) do Google Play Services.
     */
    fun acquireAccessToken(
        context: Context,
        prefs: SyncPreferences,
        explicitEmail: String? = null,
        allowInteractivePrompt: Boolean = false
    ): GoogleTasksAuthResult {
        val email = (explicitEmail ?: resolveDefaultGoogleAccountEmail(context, prefs)).trim()
        val cachedToken = prefs.getGoogleTasksAccessToken().trim()
        val acquiredMs = prefs.getGoogleTasksTokenAcquiredMs()
        val tokenAgeMs = if (acquiredMs > 0L) System.currentTimeMillis() - acquiredMs else 0L
        val isTokenFresh = cachedToken.isNotBlank() && (acquiredMs <= 0L || tokenAgeMs < TimeUnit.MINUTES.toMillis(50))

        // Fast-path: se já possuímos um Access Token recente (< 50 min), retornamos imediatamente.
        if (isTokenFresh) {
            return GoogleTasksAuthResult.Authorized(
                cachedToken,
                email.ifBlank { "Conta Google" }
            )
        }

        // Se o token está próximo de expirar (>= 50 min) ou vazio após ter expirado, tenta renovar
        // silenciosamente via cookies do WebView antes de desistir.
        if (!isInteractiveConsentInProgress && Looper.myLooper() != Looper.getMainLooper()) {
            val silentToken = refreshTokenSilentlyViaWebView(context, prefs, email)
            if (!silentToken.isNullOrBlank()) {
                return GoogleTasksAuthResult.Authorized(
                    silentToken,
                    email.ifBlank { "Conta Google" }
                )
            }
        }

        if (cachedToken.isNotBlank()) {
            return GoogleTasksAuthResult.Authorized(
                cachedToken,
                email.ifBlank { "Conta Google" }
            )
        }

        // Se uma janela de login do Google Play Services já está aberta ou se esta chamada
        // veio de um serviço em 2º plano sem token prévio, não dispare GoogleAuthUtil.getToken!
        if (isInteractiveConsentInProgress || !allowInteractivePrompt) {
            return GoogleTasksAuthResult.Error(
                if (isInteractiveConsentInProgress) {
                    "Conclua o login na janela de autenticação"
                } else {
                    "Toque em 'Conectar / Trocar Conta Google Tasks' para entrar"
                }
            )
        }

        if (email.isBlank()) {
            return GoogleTasksAuthResult.Error("Nenhuma conta Google conectada ao Google Tasks")
        }

        synchronized(authLock) {
            val recheckCached = prefs.getGoogleTasksAccessToken().trim()
            if (recheckCached.isNotBlank()) {
                return GoogleTasksAuthResult.Authorized(recheckCached, email)
            }

            try {
                val account = Account(email, "com.google")
                val token = GoogleAuthUtil.getToken(context, account, OAUTH_SCOPE_STRING)
                if (!token.isNullOrBlank()) {
                    pendingConsentIntent = null
                    isInteractiveConsentInProgress = false
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
                val rawMsg = (e.message ?: e.localizedMessage ?: "").trim()
                if (
                    rawMsg.contains("UnregisteredOnApiConsole", ignoreCase = true) ||
                    rawMsg.contains("UNREGISTERED_ON_API_CONSOLE", ignoreCase = true) ||
                    rawMsg.contains("DEVELOPER_ERROR", ignoreCase = true)
                ) {
                    val sha1 = getAppSignatureSha1(context)
                    val pkg = context.packageName
                    prefs.setGoogleTasksAccountEmail(email)
                    prefs.setGoogleTasksSyncStatus("Toque em 'Conectar / Trocar Conta Google Tasks' para entrar")
                    return GoogleTasksAuthResult.UnregisteredOnConsole(
                        packageName = pkg,
                        sha1Fingerprint = sha1,
                        accountEmail = email
                    )
                }
                return GoogleTasksAuthResult.Error(
                    "Toque em 'Conectar / Trocar Conta Google Tasks' para autenticar"
                )
            }
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

        if (isInteractiveConsentInProgress) {
            return prefs.getTasksJson()
        }

        val authResult = acquireAccessToken(
            context = context,
            prefs = prefs,
            allowInteractivePrompt = false
        )
        val (initialToken, accountEmail) = when (authResult) {
            is GoogleTasksAuthResult.Authorized -> authResult.accessToken to authResult.accountEmail
            is GoogleTasksAuthResult.NeedsUserConsent -> {
                prefs.setGoogleTasksSyncStatus("Toque em 'Conectar / Trocar Conta Google Tasks' para entrar")
                return prefs.getTasksJson()
            }
            is GoogleTasksAuthResult.UnregisteredOnConsole -> {
                prefs.setGoogleTasksSyncStatus("Toque em 'Conectar / Trocar Conta Google Tasks' para entrar")
                return prefs.getTasksJson()
            }
            is GoogleTasksAuthResult.Error -> {
                prefs.setGoogleTasksSyncStatus(authResult.reason)
                return prefs.getTasksJson()
            }
        }

        var activeToken = initialToken
        var listsResponse = executeGetRequest(
            context,
            "https://tasks.googleapis.com/tasks/v1/users/@me/lists?maxResults=20",
            activeToken
        )

        // Se o token expirou (HTTP 401), tenta renovar silenciosamente via WebView (OAuth 2.0 prompt=none)
        // ou via GoogleAuthUtil antes de limpar o token salvo.
        if (listsResponse.first == 401) {
            var renewedToken: String? = refreshTokenSilentlyViaWebView(context, prefs, accountEmail)
            if (renewedToken.isNullOrBlank() && !isInteractiveConsentInProgress && accountEmail.contains("@")) {
                try {
                    GoogleAuthUtil.clearToken(context, activeToken)
                    renewedToken = synchronized(authLock) {
                        GoogleAuthUtil.getToken(
                            context,
                            Account(accountEmail, "com.google"),
                            OAUTH_SCOPE_STRING
                        )
                    }
                } catch (_: Exception) {
                }
            }

            if (!renewedToken.isNullOrBlank()) {
                activeToken = renewedToken
                prefs.setGoogleTasksAccessToken(activeToken)
                listsResponse = executeGetRequest(
                    context,
                    "https://tasks.googleapis.com/tasks/v1/users/@me/lists?maxResults=20",
                    activeToken
                )
            } else {
                prefs.setGoogleTasksAccessToken("")
            }
        }

        val (statusCode, listsBody) = listsResponse
        if (statusCode != 200 || listsBody.isBlank()) {
            prefs.setGoogleTasksSyncStatus(
                if (statusCode == 401) {
                    "Sessão expirada — toque em 'Conectar / Trocar Conta Google Tasks'"
                } else {
                    "Erro HTTP $statusCode ao consultar Google Tasks API"
                }
            )
            return prefs.getTasksJson()
        }

        val sdfIsoLocal = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
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
                val (tCode, tBody) = executeGetRequest(context, tasksUrl, activeToken)
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

                    // Extrai horário apenas se houver explicitamente na nota, no título ou no campo due (quando não for 00:00:00Z).
                    // Nunca usa horário fictício ("09:00") quando a API do Google Tasks não envia hora.
                    val matchedTimeInText = timeRegex.find("$rawTitle $notes")?.groupValues?.getOrNull(1)?.padStart(5, '0')
                    val dueTimePart = extractLocalHHMMFromRfc3339IfNonMidnight(dueRfc3339)
                    val resolvedHHMM = matchedTimeInText ?: dueTimePart ?: ""

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

                    // Deduplica apenas pelo ID único da tarefa no Google Tasks, preservando tarefas distintas com o mesmo título
                    val existingIdx = collectedTasks.indexOfFirst { it.id == item.id }
                    if (existingIdx >= 0) {
                        collectedTasks[existingIdx] = item
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

    private fun executeGetRequest(
        @Suppress("UNUSED_PARAMETER") context: Context,
        urlStr: String,
        accessToken: String
    ): Pair<Int, String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Authorization", "Bearer $accessToken")
                setRequestProperty("Accept", "application/json")
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
