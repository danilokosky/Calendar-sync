export interface ProjectSourceFile {
  path: string;
  module: 'root' | 'mobile' | 'wear' | 'watchface' | 'github';
  language: 'kotlin' | 'xml' | 'gradle' | 'yaml' | 'markdown';
  title: string;
  description: string;
  content: string;
}

export const ANDROID_PROJECT_FILES: ProjectSourceFile[] = [
  {
    path: 'settings.gradle.kts',
    module: 'root',
    language: 'gradle',
    title: 'Configuração Multi-Módulo Gradle',
    description: 'Define os 3 módulos instaláveis: :mobile (APK Smartphone), :wear (APK Wear OS) e :watchface (Watch Face Format WFF).',
    content: `pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ChronoWearCalendarSync"
include(":mobile")
include(":wear")
include(":watchface")
`
  },
  {
    path: 'README.md',
    module: 'root',
    language: 'markdown',
    title: 'Documentação Oficial para o GitHub',
    description: 'Instruções de compilação dos APKs (:mobile, :wear, :watchface), permissões de calendário e funcionamento do cache autônomo.',
    content: `# ChronoWear — Sincronizador Autônomo de Calendário & Tarefas (Android + Wear OS)

Aplicativo multi-módulo para **Android (Smartphone)** e **Wear OS (Smartwatch)** com foco em resiliência offline, **Complications com Barra de Progresso Configurável (1h/2h)**, **Tile dos Próximos 3 Eventos** e **Watch Face Format (WFF)** oficial.

## Arquitetura do Projeto

1. **\`:mobile\` (APK Smartphone)**:
   - Lê \`CalendarContract.Instances\` dos calendários selecionados pelo usuário.
   - Executa em segundo plano via **WorkManager** (\`CalendarSyncWorker\`) e **ContentObserver** quando eventos mudam.
   - Envia um pacote de horizonte configurável (**2, 3, 5 ou 7 dias**) para o relógio usando \`DataClient\` (\`/chronowear/calendar_horizon_payload\`).

2. **\`:wear\` (APK Smartwatch Wear OS)**:
   - Armazena todos os eventos recebidos em banco local **Room** (\`CachedEventDao\`).
   - **Operação 100% Autônoma (Offline)**: Caso o smartwatch perca a conexão Bluetooth/Wi-Fi com o smartphone, o \`AutonomousCountdownScheduler\` continua calculando qual é o próximo evento, a contagem regressiva e os **próximos 3 eventos** pelos próximos dias sincronizados.
   - **\`NextEventComplicationService\` (\`RANGED_VALUE\` + \`LONG_TEXT\`)**: Fornece a hora de início, contagem regressiva e barra de progresso automática configurável (ex.: inicia 1h ou 2h antes do evento).
   - **\`UpNextThreeEventsTileService\`**: Exibe os **próximos 3 eventos** em janela dedicada com contador regressivo no topo.

3. **\`:watchface\` (APK Watch Face Format - WFF)**:
   - Contorna as limitações do Watch Face Format declarativo com um \`ComplicationSlot\` amplo superior (\`RANGED_VALUE\` para arco de progresso e contagem regressiva) e janela central de agenda para exibir os **próximos 3 eventos** com atualização automática.

## Como gerar os APKs

\`\`\`bash
./gradlew :mobile:assembleDebug :wear:assembleDebug :watchface:assembleDebug
\`\`\`
`
  },
  {
    path: '.github/workflows/android-build.yml',
    module: 'github',
    language: 'yaml',
    title: 'GitHub Actions CI/CD — Build APKs',
    description: 'Compila automaticamente os 3 APKs (Smartphone, Wear OS e Watch Face) a cada push ou Pull Request no GitHub.',
    content: `name: Build Android & Wear OS APKs

on:
  push:
    branches: [ "main" ]
  pull_request:
    branches: [ "main" ]
  workflow_dispatch:

jobs:
  build-apks:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Repository
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: gradle

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Assemble Mobile, Wear OS and Watch Face APKs
        run: ./gradlew :mobile:assembleDebug :wear:assembleDebug :watchface:assembleDebug

      - name: Upload Smartphone APK
        uses: actions/upload-artifact@v4
        with:
          name: chronowear-mobile-apk
          path: mobile/build/outputs/apk/debug/*.apk

      - name: Upload Wear OS Companion APK
        uses: actions/upload-artifact@v4
        with:
          name: chronowear-wear-apk
          path: wear/build/outputs/apk/debug/*.apk

      - name: Upload WFF Watch Face APK
        uses: actions/upload-artifact@v4
        with:
          name: chronowear-watchface-wff-apk
          path: watchface/build/outputs/apk/debug/*.apk
`
  },
  {
    path: 'mobile/src/main/AndroidManifest.xml',
    module: 'mobile',
    language: 'xml',
    title: 'Manifesto do Smartphone (:mobile)',
    description: 'Declara permissões READ_CALENDAR, FOREGROUND_SERVICE_DATA_SYNC e inicialização do WorkManager.',
    content: `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.READ_CALENDAR" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:allowBackup="true"
        android:label="ChronoWear Sync"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">

        <activity
            android:name=".ui.MobileSyncConfigActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <receiver
            android:name=".sync.CalendarChangeReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.PROVIDER_CHANGED" />
                <data android:scheme="content" android:host="com.android.calendar" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
`
  },
  {
    path: 'mobile/src/main/java/com/chronowear/mobile/sync/CalendarSyncWorker.kt',
    module: 'mobile',
    language: 'kotlin',
    title: 'CalendarSyncWorker.kt (Smartphone Background Sync)',
    description: 'Serviço em segundo plano (WorkManager) que consulta os calendários selecionados para o horizonte de 2, 3, 5 ou 7 dias e envia ao relógio via Wearable Data Layer.',
    content: `package com.chronowear.mobile.sync

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class CalendarSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val prefs = SyncPreferences(applicationContext)
        val selectedCalendarIds = prefs.getSelectedCalendarIds()
        val horizonDays = prefs.getHorizonDays() // 2, 3, 5 ou 7 dias
        val progressWindowMinutes = prefs.getProgressWindowMinutes() // 60m (1h) ou 120m (2h)

        val nowMs = System.currentTimeMillis()
        val endHorizonMs = nowMs + TimeUnit.DAYS.toMillis(horizonDays.toLong())

        val eventsJsonArray = queryUpcomingEvents(
            selectedCalendarIds = selectedCalendarIds,
            startMs = nowMs,
            endMs = endHorizonMs
        )

        // Envia o horizonte completo de eventos para o relógio operar de forma autônoma
        val putDataMapReq = PutDataMapRequest.create(DATA_LAYER_PATH).apply {
            dataMap.putString("events_json", eventsJsonArray.toString())
            dataMap.putLong("sync_timestamp_ms", nowMs)
            dataMap.putInt("horizon_days", horizonDays)
            dataMap.putInt("progress_window_minutes", progressWindowMinutes)
        }

        val putDataReq = putDataMapReq.asPutDataRequest().setUrgent()
        Wearable.getDataClient(applicationContext).putDataItem(putDataReq).await()

        return Result.success()
    }

    private fun queryUpcomingEvents(
        selectedCalendarIds: Set<String>,
        startMs: Long,
        endMs: Long
    ): JSONArray {
        val result = JSONArray()
        if (selectedCalendarIds.isEmpty()) return result

        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, startMs)
        ContentUris.appendId(builder, endMs)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END
        )

        val sortOrder = "\${CalendarContract.Instances.BEGIN} ASC"

        applicationContext.contentResolver.query(
            builder.build(),
            projection,
            null,
            null,
            sortOrder
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val calId = cursor.getLong(1).toString()
                if (!selectedCalendarIds.contains(calId)) continue

                val beginMs = cursor.getLong(4)
                val endEventMs = cursor.getLong(5)
                if (endEventMs <= startMs) continue

                val item = JSONObject().apply {
                    put("id", cursor.getLong(0).toString())
                    put("calendarId", calId)
                    put("title", cursor.getString(2) ?: "Sem título")
                    put("location", cursor.getString(3) ?: "")
                    put("startEpochMs", beginMs)
                    put("endEpochMs", endEventMs)
                }
                result.put(item)
            }
        }
        return result
    }

    companion object {
        const val DATA_LAYER_PATH = "/chronowear/calendar_horizon_payload"
        private const val UNIQUE_WORK_NAME = "ChronoWearPeriodicCalendarSync"

        fun schedulePeriodicSync(context: Context, intervalMinutes: Long = 15L) {
            val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(
                intervalMinutes, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
`
  },
  {
    path: 'wear/src/main/java/com/chronowear/wear/data/PhoneDataListenerService.kt',
    module: 'wear',
    language: 'kotlin',
    title: 'PhoneDataListenerService.kt (Cache Autônomo no Relógio)',
    description: 'Recebe a janela de eventos (2 a 7 dias), salva no banco Room local do relógio e solicita atualização imediata das Complications e do Tile.',
    content: `package com.chronowear.wear.data

import android.content.ComponentName
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.tiles.TileService
import com.chronowear.wear.complication.NextEventComplicationService
import com.chronowear.wear.tile.UpNextThreeEventsTileService
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PhoneDataListenerService : WearableListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type == DataEvent.TYPE_CHANGED &&
                event.dataItem.uri.path == "/chronowear/calendar_horizon_payload"
            ) {
                val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                val eventsJson = dataMap.getString("events_json") ?: "[]"
                val syncTimestampMs = dataMap.getLong("sync_timestamp_ms")
                val horizonDays = dataMap.getInt("horizon_days", 3)
                val progressWindowMinutes = dataMap.getInt("progress_window_minutes", 60)

                serviceScope.launch {
                    val repo = WatchAutonomousRepository.getInstance(applicationContext)
                    repo.replaceHorizonCache(
                        eventsJson = eventsJson,
                        syncTimestampMs = syncTimestampMs,
                        horizonDays = horizonDays,
                        progressWindowMinutes = progressWindowMinutes
                    )

                    // Reagenda alarmes locais no relógio para virada exata de cada evento mesmo offline
                    AutonomousCountdownScheduler.scheduleNextTransition(applicationContext)

                    // Atualiza as Complications da Watch Face imediatamente
                    ComplicationDataSourceUpdateRequester.create(
                        applicationContext,
                        ComponentName(applicationContext, NextEventComplicationService::class.java)
                    ).requestUpdateAll()

                    // Atualiza o Tile dos próximos 3 eventos
                    TileService.getUpdater(applicationContext)
                        .requestUpdate(UpNextThreeEventsTileService::class.java)
                }
            }
        }
    }
}
`
  },
  {
    path: 'wear/src/main/java/com/chronowear/wear/complication/NextEventComplicationService.kt',
    module: 'wear',
    language: 'kotlin',
    title: 'NextEventComplicationService.kt (Complication RangedValue + Top 3)',
    description: 'Fornece a Complication para a Watch Face com temporizador regressivo, barra de progresso configurável (1h ou 2h antes) e agregação dos próximos 3 eventos usando o cache local autônomo.',
    content: `package com.chronowear.wear.complication

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.chronowear.wear.data.WatchAutonomousRepository
import java.time.Instant
import java.util.concurrent.TimeUnit

class NextEventComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        val nowMs = System.currentTimeMillis()

        // Busca os próximos 3 eventos de forma 100% autônoma no banco local do relógio
        val upcomingThree = repo.getNextThreeUpcomingEvents(nowMs)
        val primaryEvent = upcomingThree.firstOrNull()
        val progressWindowMinutes = repo.getProgressWindowMinutes() // ex.: 60 min (1h) ou 120 min (2h)

        if (primaryEvent == null) {
            return buildEmptyComplication(request.complicationType)
        }

        // TimeDifferenceComplicationText permite que o próprio Wear OS atualize o contador regressivo na Watch Face a cada minuto/segundo sem gastar bateria!
        val countdownText = TimeDifferenceComplicationText.Builder(
            style = TimeDifferenceStyle.SHORT_DUAL_UNIT,
            countUpTimeReference = Instant.ofEpochMilli(primaryEvent.startEpochMs)
        ).build()

        // Agrega os próximos 3 eventos em formato compacto para janelas grandes (LONG_TEXT / RANGED_VALUE)
        val threeEventsSummary = upcomingThree.mapIndexed { idx, ev ->
            "\${idx + 1}. \${ev.formatStartHHMM()} \${ev.title}"
        }.joinToString(" • ")

        val windowMs = TimeUnit.MINUTES.toMillis(progressWindowMinutes.toLong()).toFloat()
        val remainingMs = (primaryEvent.startEpochMs - nowMs).coerceAtLeast(0L).toFloat()

        // Barra de progresso automática: vai de 0.0 (quando falta 1h ou 2h) até 100.0 (início do evento)
        val progressPercentage = if (remainingMs >= windowMs) {
            0f
        } else {
            ((1f - (remainingMs / windowMs)) * 100f).coerceIn(0f, 100f)
        }

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = progressPercentage,
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder(threeEventsSummary).build()
                )
                    // No topo (Title): Tempo restante até o próximo evento + hora de início
                    .setTitle(countdownText)
                    // Na janela principal (Text): Próximos 3 eventos agregados
                    .setText(PlainComplicationText.Builder(threeEventsSummary).build())
                    .build()
            }

            ComplicationType.LONG_TEXT -> {
                LongTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(threeEventsSummary).build(),
                    contentDescription = PlainComplicationText.Builder(threeEventsSummary).build()
                )
                    .setTitle(countdownText)
                    .build()
            }

            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val sampleText = PlainComplicationText.Builder(
            "1. 10:00 Daily Standup • 2. 13:30 Revisão WFF • 3. 16:00 Deploy APK"
        ).build()
        val sampleTitle = PlainComplicationText.Builder("Faltam 24m (10:00)").build()

        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 60f, min = 0f, max = 100f, contentDescription = sampleText
            ).setTitle(sampleTitle).setText(sampleText).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = sampleText, contentDescription = sampleText
            ).setTitle(sampleTitle).build()

            else -> null
        }
    }

    private fun buildEmptyComplication(type: ComplicationType): ComplicationData? {
        val emptyText = PlainComplicationText.Builder("Agenda livre no horizonte sincronizado").build()
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = 0f, min = 0f, max = 100f, contentDescription = emptyText
            ).setText(emptyText).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = emptyText, contentDescription = emptyText
            ).build()
            else -> null
        }
    }
}
`
  },
  {
    path: 'wear/src/main/java/com/chronowear/wear/tile/UpNextThreeEventsTileService.kt',
    module: 'wear',
    language: 'kotlin',
    title: 'UpNextThreeEventsTileService.kt (Tile Oficial dos Próximos 3 Eventos)',
    description: 'Implementa um Wear OS Tile nativo para exibir os próximos 3 eventos empilhados com barra de progresso superior e contagem regressiva sem sofrer com truncamento de texto.',
    content: `package com.chronowear.wear.tile

import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.chronowear.wear.data.WatchAutonomousRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future

class UpNextThreeEventsTileService : TileService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = serviceScope.future {
        val repo = WatchAutonomousRepository.getInstance(applicationContext)
        val nowMs = System.currentTimeMillis()
        val nextThree = repo.getNextThreeUpcomingEvents(nowMs)

        val columnBuilder = Column.Builder()
            .setWidth(dp(192f))

        // Cabeçalho com tempo restante para o próximo evento
        val firstEvent = nextThree.firstOrNull()
        val headerLabel = if (firstEvent != null) {
            "PRÓXIMO EM \${firstEvent.formatRemaining(nowMs)} (\${firstEvent.formatStartHHMM()})"
        } else {
            "SEM EVENTOS FUTUROS"
        }

        columnBuilder.addContent(
            Text.Builder()
                .setText(headerLabel)
                .setFontStyle(
                    LayoutElementBuilders.FontStyle.Builder()
                        .setSize(sp(11f))
                        .setColor(argb(0xFFF59E0B.toInt()))
                        .build()
                )
                .build()
        )

        // Lista sempre os próximos 3 eventos na janela grande do Tile
        nextThree.take(3).forEachIndexed { index, ev ->
            columnBuilder.addContent(
                Text.Builder()
                    .setText("\${index + 1}. \${ev.formatStartHHMM()} · \${ev.title}")
                    .setMaxLines(1)
                    .setFontStyle(
                        LayoutElementBuilders.FontStyle.Builder()
                            .setSize(sp(13f))
                            .setColor(argb(0xFFF8FAFC.toInt()))
                            .build()
                    )
                    .build()
            )
        }

        val layout = LayoutElementBuilders.Layout.Builder()
            .setRoot(columnBuilder.build())
            .build()

        val timeline = TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(
                TimelineBuilders.TimelineEntry.Builder()
                    .setLayout(layout)
                    .build()
            )
            .build()

        TileBuilders.Tile.Builder()
            .setResourcesVersion("1")
            .setTileTimeline(timeline)
            .setFreshnessIntervalMillis(60_000L) // Atualiza a cada 1 minuto autonomamente
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion("1").build()
        )
}
`
  },
  {
    path: 'watchface/src/main/res/raw/watchface.xml',
    module: 'watchface',
    language: 'xml',
    title: 'watchface.xml (Watch Face Format Oficial WFF 450x450)',
    description: 'Watch Face declarativa compatível com Wear OS 4 e 5. Resolve as limitações do WFF combinando uma Complication RANGED_VALUE no topo (contagem regressiva + barra de progresso 1h/2h) e uma janela central ampla para os próximos 3 eventos.',
    content: `<?xml version="1.0" encoding="utf-8"?>
<WatchFace width="450" height="450">
    <Metadata key="CLOCK_TYPE" value="DIGITAL" />
    <Metadata key="STEP_GOAL" value="10000" />

    <Scene backgroundColor="#FF090D16">

        <!-- Relógio Digital Superior Esquerdo/Direito Balanceado -->
        <DigitalClock x="45" y="34" width="360" height="58">
            <TimeText
                x="0" y="0" width="360" height="58"
                format="HH:mm"
                hourFormat="SYNC_TO_DEVICE"
                align="CENTER">
                <Font family="SYNC_TO_DEVICE" size="48" weight="BOLD" color="#FFF8FAFC" />
            </TimeText>
        </DigitalClock>

        <!--
            SLOT 1 (TOPO): Contagem Regressiva + Barra de Progresso Automática (1h ou 2h antes)
            Vinculado por padrão ao nosso NextEventComplicationService (RANGED_VALUE)
        -->
        <ComplicationSlot
            x="40" y="102" width="370" height="72"
            slotId="101"
            name="Countdown &amp; Progress Window"
            supportedTypes="RANGED_VALUE SHORT_TEXT"
            defaultDataSource="com.chronowear.wear/.complication.NextEventComplicationService"
            defaultDataSourceType="RANGED_VALUE">

            <BoundingBox x="0" y="0" width="370" height="72" cornerRadius="16" />

            <Complication type="RANGED_VALUE">
                <!-- Rótulo do Tempo Restante e Início do Próximo Evento -->
                <PartText x="16" y="8" width="338" height="28">
                    <Text align="CENTER" ellipsis="TRUE">
                        <Font family="SYNC_TO_DEVICE" size="18" weight="BOLD" color="#FFF59E0B">
                            <Template>%s</Template>
                            <Parameter expression="[COMPLICATION.TITLE]" />
                        </Font>
                    </Text>
                </PartText>

                <!-- Barra de Progresso Configurável (ex.: 1h ou 2h antes do evento) -->
                <PartDraw x="24" y="44" width="322" height="10">
                    <!-- Trilho de fundo -->
                    <RoundRect x="0" y="0" width="322" height="10" cornerRadius="5">
                        <Fill color="#FF1E293B" />
                    </RoundRect>
                    <!-- Preenchimento dinâmico calculado via RANGED_VALUE -->
                    <RoundRect
                        x="0" y="0"
                        width="322 * ([COMPLICATION.VALUE] - [COMPLICATION.MIN]) / ([COMPLICATION.MAX] - [COMPLICATION.MIN])"
                        height="10"
                        cornerRadius="5">
                        <Fill color="#FFF59E0B" />
                    </RoundRect>
                </PartDraw>
            </Complication>
        </ComplicationSlot>

        <!--
            SLOT 2 (JANELA GRANDE CENTRAL): Exibe sempre os Próximos 3 Eventos
            Soluciona a limitação de espaço do Watch Face Format com caixa de 390x190px e 3 linhas dedicadas
        -->
        <ComplicationSlot
            x="30" y="184" width="390" height="196"
            slotId="102"
            name="Next 3 Calendar Events Window"
            supportedTypes="LONG_TEXT RANGED_VALUE"
            defaultDataSource="com.chronowear.wear/.complication.NextEventComplicationService"
            defaultDataSourceType="LONG_TEXT">

            <BoundingBox x="0" y="0" width="390" height="196" cornerRadius="24" />

            <Complication type="LONG_TEXT">
                <PartDraw x="0" y="0" width="390" height="196">
                    <RoundRect x="0" y="0" width="390" height="196" cornerRadius="24">
                        <Fill color="#FF111827" />
                    </RoundRect>
                </PartDraw>

                <!-- Texto multi-linha exibindo os próximos 3 eventos do horizonte autônomo -->
                <PartText x="20" y="18" width="350" height="160">
                    <Text align="START" maxLines="4" ellipsis="TRUE">
                        <Font family="SYNC_TO_DEVICE" size="19" weight="MEDIUM" color="#FFE2E8F0">
                            <Template>%s</Template>
                            <Parameter expression="[COMPLICATION.TEXT]" />
                        </Font>
                    </Text>
                </PartText>
            </Complication>
        </ComplicationSlot>

    </Scene>
</WatchFace>
`
  }
];
