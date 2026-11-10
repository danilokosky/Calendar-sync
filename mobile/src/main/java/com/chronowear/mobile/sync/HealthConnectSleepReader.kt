package com.chronowear.mobile.sync

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Leitor Oficial de Sono e Passos via Android Health Connect (`androidx.health.connect.client`).
 *
 * Compatível universalmente com qualquer plataforma que grave dados de saúde no Android:
 * - Samsung Health (Galaxy Watch / Galaxy Ring / Smartphone Galaxy)
 * - Google Fitbit / Pixel Watch
 * - Google Fit, Garmin, Oura, Zepp/Xiaomi, Sleep as Android
 *
 * Não utiliza leitura de notificações nem dados genéricos fixos.
 */
object HealthConnectSleepReader {

    val READ_SLEEP_PERMISSION: String =
        HealthPermission.getReadPermission(SleepSessionRecord::class)

    val READ_STEPS_PERMISSION: String =
        HealthPermission.getReadPermission(StepsRecord::class)

    val REQUIRED_HEALTH_PERMISSIONS: Set<String> =
        setOf(READ_SLEEP_PERMISSION, READ_STEPS_PERMISSION)

    private val TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    /**
     * Verifica se o Health Connect está disponível no aparelho.
     */
    fun getSdkStatus(context: Context): Int {
        return try {
            HealthConnectClient.getSdkStatus(context)
        } catch (_: Exception) {
            HealthConnectClient.SDK_UNAVAILABLE
        }
    }

    /**
     * Verificação rápida (síncrona) para o Quadro de Permissões Pendentes na aba Início:
     * Verifica se ambas as permissões do Health Connect (`READ_SLEEP` e `READ_STEPS`) estão concedidas.
     */
    fun hasSleepPermissionSync(context: Context): Boolean {
        val prefs = SyncPreferences(context)
        if (Build.VERSION.SDK_INT >= 34) {
            val runtimeSleepGranted = try {
                context.checkSelfPermission(READ_SLEEP_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
            val runtimeStepsGranted = try {
                context.checkSelfPermission(READ_STEPS_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
            if (runtimeSleepGranted && runtimeStepsGranted) {
                if (!prefs.isHealthConnectSleepPermissionGranted() || !prefs.isHealthConnectStepsPermissionGranted()) {
                    prefs.setHealthConnectSleepPermissionGranted(true)
                    prefs.setHealthConnectStepsPermissionGranted(true)
                }
                return true
            }
        }
        return prefs.isHealthConnectSleepPermissionGranted() && prefs.isHealthConnectStepsPermissionGranted()
    }

    /**
     * Cria o Intent oficial do Android / Health Connect para solicitar explicitamente as permissões
     * `READ_SLEEP` e `READ_STEPS` de uma só vez.
     */
    fun createRequestSleepPermissionIntent(context: Context): Intent {
        return try {
            val status = getSdkStatus(context)
            if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=com.google.android.apps.healthdata")
                )
            } else {
                val contract = PermissionController.createRequestPermissionResultContract()
                contract.createIntent(context, REQUIRED_HEALTH_PERMISSIONS)
            }
        } catch (_: Exception) {
            try {
                HealthConnectClient.getHealthConnectManageDataIntent(context)
            } catch (_: Exception) {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                )
            }
        }
    }

    /**
     * Consulta o `HealthConnectClient` de forma assíncrona para:
     * 1. Atualizar o status real das permissões `READ_SLEEP` e `READ_STEPS`.
     * 2. Ler as sessões reais de sono (`SleepSessionRecord`) encerradas hoje e salvar em `SyncPreferences`.
     * 3. Ler a contagem real de passos (`StepsRecord.COUNT_TOTAL`) dos últimos 7 dias (incluindo hoje)
     *    com deduplicação nativa entre relógio e telefone, salvando em `SyncPreferences`.
     */
    suspend fun refreshPermissionAndReadTodaySleep(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = SyncPreferences(context)
        val status = getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            val runtimeSleep = Build.VERSION.SDK_INT >= 34 && try {
                context.checkSelfPermission(READ_SLEEP_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
            val runtimeSteps = Build.VERSION.SDK_INT >= 34 && try {
                context.checkSelfPermission(READ_STEPS_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
            prefs.setHealthConnectSleepPermissionGranted(runtimeSleep)
            prefs.setHealthConnectStepsPermissionGranted(runtimeSteps)
            if (!runtimeSleep) prefs.clearSleepDataForToday()
            if (!runtimeSteps) prefs.clearStepsData()
            return false
        }

        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val grantedSet = client.permissionController.getGrantedPermissions()
            val runtimeSleep = Build.VERSION.SDK_INT >= 34 && try {
                context.checkSelfPermission(READ_SLEEP_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
            val runtimeSteps = Build.VERSION.SDK_INT >= 34 && try {
                context.checkSelfPermission(READ_STEPS_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }

            val isSleepGranted = grantedSet.contains(READ_SLEEP_PERMISSION) || runtimeSleep
            val isStepsGranted = grantedSet.contains(READ_STEPS_PERMISSION) || runtimeSteps
            prefs.setHealthConnectSleepPermissionGranted(isSleepGranted)
            prefs.setHealthConnectStepsPermissionGranted(isStepsGranted)

            val zone = ZoneId.systemDefault()
            val todayDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val queryEndInstant = Instant.ofEpochMilli(nowMs).plusSeconds(60L)

            if (isSleepGranted && prefs.isSleepSyncEnabled()) {
                readTodaySleepInternal(client, prefs, zone, todayDate, queryEndInstant)
            } else if (!isSleepGranted) {
                prefs.clearSleepDataForToday()
            }

            if (isStepsGranted) {
                readWeeklyStepsInternal(client, prefs, zone, todayDate, queryEndInstant, nowMs)
            } else {
                prefs.clearStepsData()
            }

            isSleepGranted && isStepsGranted
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun readTodaySleepInternal(
        client: HealthConnectClient,
        prefs: SyncPreferences,
        zone: ZoneId,
        todayDate: LocalDate,
        queryEndInstant: Instant
    ) {
        try {
            val startOfTodayInstant = todayDate.atStartOfDay(zone).toInstant()
            val queryStartInstant = startOfTodayInstant.minusSeconds(12L * 3600L)

            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(queryStartInstant, queryEndInstant)
                )
            )

            val todaySessions = response.records.filter { record ->
                val endLocalDate: LocalDate = record.endTime.atZone(zone).toLocalDate()
                endLocalDate == todayDate &&
                    record.endTime.toEpochMilli() > record.startTime.toEpochMilli()
            }.sortedBy { it.startTime }

            if (todaySessions.isEmpty()) {
                prefs.clearSleepDataForToday()
                return
            }

            val firstSession = todaySessions.first()
            val lastSession = todaySessions.last()
            val bedtimeHHMM = firstSession.startTime.atZone(zone).format(TIME_FORMATTER)
            val wakeHHMM = lastSession.endTime.atZone(zone).format(TIME_FORMATTER)
            val wakeTimestampMs = lastSession.endTime.toEpochMilli()

            var awakeMinutes = 0
            var remMinutes = 0
            var lightMinutes = 0
            var deepMinutes = 0

            for (session in todaySessions) {
                if (session.stages.isNotEmpty()) {
                    for (stage in session.stages) {
                        val stageDurationMin = (
                            (stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()) / 60_000L
                            ).toInt().coerceAtLeast(0)
                        when (stage.stage) {
                            SleepSessionRecord.STAGE_TYPE_AWAKE,
                            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> {
                                awakeMinutes += stageDurationMin
                            }
                            SleepSessionRecord.STAGE_TYPE_REM -> {
                                remMinutes += stageDurationMin
                            }
                            SleepSessionRecord.STAGE_TYPE_DEEP -> {
                                deepMinutes += stageDurationMin
                            }
                            SleepSessionRecord.STAGE_TYPE_LIGHT,
                            SleepSessionRecord.STAGE_TYPE_SLEEPING,
                            SleepSessionRecord.STAGE_TYPE_UNKNOWN -> {
                                lightMinutes += stageDurationMin
                            }
                            else -> {
                                lightMinutes += stageDurationMin
                            }
                        }
                    }
                } else {
                    val rawSessionMin = (
                        (session.endTime.toEpochMilli() - session.startTime.toEpochMilli()) / 60_000L
                        ).toInt().coerceAtLeast(0)
                    lightMinutes += rawSessionMin
                }
            }

            val totalAsleepMinutes = (remMinutes + lightMinutes + deepMinutes).let { asleepSum ->
                if (asleepSum > 0) {
                    asleepSum
                } else {
                    val rawTotal = todaySessions.sumOf {
                        ((it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000L).toInt().coerceAtLeast(0)
                    }
                    (rawTotal - awakeMinutes).coerceAtLeast(0)
                }
            }

            if (totalAsleepMinutes <= 0) {
                prefs.clearSleepDataForToday()
                return
            }

            val estimatedScore = calculateSleepScore(
                totalSleepMinutes = totalAsleepMinutes,
                awakeMinutes = awakeMinutes,
                remMinutes = remMinutes,
                deepMinutes = deepMinutes
            )

            val stagesCsv = "$awakeMinutes,$remMinutes,$lightMinutes,$deepMinutes"
            prefs.saveSleepSnapshot(
                totalDurationMinutes = totalAsleepMinutes,
                sleepScore = estimatedScore,
                bedtimeHHMM = bedtimeHHMM,
                wakeHHMM = wakeHHMM,
                stagesMinutesCsv = stagesCsv,
                wakeTimestampMs = wakeTimestampMs,
                hasDataToday = true
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Lê a contagem real de passos dos últimos 7 dias (encerrando Hoje na 7ª barra) via Health Connect.
     * Utiliza `AggregateRequest(StepsRecord.COUNT_TOTAL)` para deduplicar automaticamente passos gravados
     * pelo relógio e pelo celular ao mesmo tempo, com fallback para `readRecords(StepsRecord::class)`.
     */
    private suspend fun readWeeklyStepsInternal(
        client: HealthConnectClient,
        prefs: SyncPreferences,
        zone: ZoneId,
        todayDate: LocalDate,
        queryEndInstant: Instant,
        nowMs: Long
    ) {
        try {
            val dailySteps = mutableListOf<Int>()
            val dailyLabels = mutableListOf<String>()

            for (daysAgo in 6 downTo 0) {
                val date = todayDate.minusDays(daysAgo.toLong())
                val dayStartInstant = date.atStartOfDay(zone).toInstant()
                val dayEndInstant = if (daysAgo == 0) {
                    queryEndInstant
                } else {
                    date.plusDays(1L).atStartOfDay(zone).toInstant()
                }

                val aggregatedCount = try {
                    val aggResponse = client.aggregate(
                        AggregateRequest(
                            metrics = setOf(StepsRecord.COUNT_TOTAL),
                            timeRangeFilter = TimeRangeFilter.between(dayStartInstant, dayEndInstant)
                        )
                    )
                    (aggResponse[StepsRecord.COUNT_TOTAL] ?: 0L).toInt().coerceAtLeast(0)
                } catch (_: Exception) {
                    0
                }

                val finalDaySteps = if (aggregatedCount > 0) {
                    aggregatedCount
                } else {
                    try {
                        val rawResponse = client.readRecords(
                            ReadRecordsRequest(
                                recordType = StepsRecord::class,
                                timeRangeFilter = TimeRangeFilter.between(dayStartInstant, dayEndInstant)
                            )
                        )
                        rawResponse.records.sumOf { it.count }.toInt().coerceAtLeast(0)
                    } catch (_: Exception) {
                        0
                    }
                }

                dailySteps.add(finalDaySteps)
                dailyLabels.add(shortPtLabelForDayOfWeek(date.dayOfWeek))
            }

            val todaySteps = dailySteps.lastOrNull() ?: 0
            prefs.saveStepsSnapshot(
                todaySteps = todaySteps,
                dailyGoal = prefs.getStepsDailyGoal(),
                weekStepsCsv = dailySteps.joinToString(","),
                weekLabelsCsv = dailyLabels.joinToString(","),
                nowMs = nowMs
            )
        } catch (_: Exception) {
        }
    }

    private fun shortPtLabelForDayOfWeek(dow: DayOfWeek): String {
        return when (dow) {
            DayOfWeek.MONDAY -> "Seg"
            DayOfWeek.TUESDAY -> "Ter"
            DayOfWeek.WEDNESDAY -> "Qua"
            DayOfWeek.THURSDAY -> "Qui"
            DayOfWeek.FRIDAY -> "Sex"
            DayOfWeek.SATURDAY -> "Sáb"
            DayOfWeek.SUNDAY -> "Dom"
        }
    }

    /**
     * Calcula uma pontuação estimada (0 a 100) baseada nos dados reais do Health Connect:
     * - Duração total dormida (meta ideal entre 7h e 8h = até 55 pts)
     * - Proporção restauradora de Sono Profundo + REM (meta ~35% a 42% = até 28 pts)
     * - Eficiência / Continuidade (baixo tempo acordado durante a noite = até 17 pts)
     */
    private fun calculateSleepScore(
        totalSleepMinutes: Int,
        awakeMinutes: Int,
        remMinutes: Int,
        deepMinutes: Int
    ): Int {
        if (totalSleepMinutes <= 0) return 0

        val durationScore = (totalSleepMinutes.toFloat() / 450f).coerceIn(0f, 1.04f) * 53f
        val restorativeRatio = (remMinutes + deepMinutes).toFloat() / totalSleepMinutes.toFloat()
        val restorativeScore = (restorativeRatio / 0.38f).coerceIn(0f, 1.08f) * 28f

        val totalInBed = (totalSleepMinutes + awakeMinutes).coerceAtLeast(1)
        val awakeRatio = awakeMinutes.toFloat() / totalInBed.toFloat()
        val continuityScore = (1f - (awakeRatio / 0.22f).coerceIn(0f, 1f)) * 19f

        return (durationScore + restorativeScore + continuityScore).roundToInt().coerceIn(1, 100)
    }
}
