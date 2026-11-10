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
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Leitor Oficial de Sono via Android Health Connect (`androidx.health.connect.client`).
 *
 * Compatível universalmente com qualquer plataforma que grave sessões de sono no Android:
 * - Samsung Health (Galaxy Watch / Galaxy Ring)
 * - Google Fitbit / Pixel Watch
 * - Google Fit, Garmin, Oura, Zepp/Xiaomi, Sleep as Android
 *
 * Não utiliza leitura de notificações nem dados genéricos fixos.
 */
object HealthConnectSleepReader {

    val READ_SLEEP_PERMISSION: String =
        HealthPermission.getReadPermission(SleepSessionRecord::class)

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
     * - No Android 14+ (API 34+), `android.permission.health.READ_SLEEP` também é verificável via `checkSelfPermission`.
     * - Combina com o estado verificado junto ao `HealthConnectClient.permissionController`.
     */
    fun hasSleepPermissionSync(context: Context): Boolean {
        val prefs = SyncPreferences(context)
        if (Build.VERSION.SDK_INT >= 34) {
            val runtimeGranted = try {
                context.checkSelfPermission(READ_SLEEP_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
            if (runtimeGranted) {
                if (!prefs.isHealthConnectSleepPermissionGranted()) {
                    prefs.setHealthConnectSleepPermissionGranted(true)
                }
                return true
            }
        }
        return prefs.isHealthConnectSleepPermissionGranted()
    }

    /**
     * Cria o Intent oficial do Android / Health Connect para solicitar explicitamente a permissão `READ_SLEEP`.
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
                contract.createIntent(context, setOf(READ_SLEEP_PERMISSION))
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
     * 1. Atualizar o status real da permissão `READ_SLEEP`.
     * 2. Se concedida, ler as sessões reais de sono (`SleepSessionRecord`) encerradas hoje,
     *    calcular os minutos reais em cada estágio (Acordado, REM, Leve, Profundo) e a pontuação
     *    estimada, salvando em `SyncPreferences`.
     * 3. Se não houver registro de sono hoje, limpa os dados para que o mostrador informe
     *    fielmente que não há dados de sono registrados para hoje.
     */
    suspend fun refreshPermissionAndReadTodaySleep(
        context: Context,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        val prefs = SyncPreferences(context)
        val status = getSdkStatus(context)
        if (status != HealthConnectClient.SDK_AVAILABLE) {
            val runtimeGranted = Build.VERSION.SDK_INT >= 34 && try {
                context.checkSelfPermission(READ_SLEEP_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
            prefs.setHealthConnectSleepPermissionGranted(runtimeGranted)
            if (!runtimeGranted) {
                prefs.clearSleepDataForToday()
            }
            return false
        }

        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val grantedSet = client.permissionController.getGrantedPermissions()
            val runtimeGranted = Build.VERSION.SDK_INT >= 34 && try {
                context.checkSelfPermission(READ_SLEEP_PERMISSION) == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) {
                false
            }
            val isGranted = grantedSet.contains(READ_SLEEP_PERMISSION) || runtimeGranted
            prefs.setHealthConnectSleepPermissionGranted(isGranted)

            if (!isGranted) {
                prefs.clearSleepDataForToday()
                return false
            }

            if (!prefs.isSleepSyncEnabled()) {
                return true
            }

            val zone = ZoneId.systemDefault()
            val todayDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val startOfTodayInstant = todayDate.atStartOfDay(zone).toInstant()
            // Busca nas últimas 36h para capturar o início do sono na noite anterior
            val queryStartInstant = startOfTodayInstant.minusSeconds(12L * 3600L)
            val queryEndInstant = Instant.ofEpochMilli(nowMs).plusSeconds(60L)

            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(queryStartInstant, queryEndInstant)
                )
            )

            // Filtra sessões cujo despertar (endTime) ocorreu na data de HOJE
            val todaySessions = response.records.filter { record ->
                val endLocalDate: LocalDate = record.endTime.atZone(zone).toLocalDate()
                endLocalDate == todayDate &&
                    record.endTime.toEpochMilli() > record.startTime.toEpochMilli()
            }.sortedBy { it.startTime }

            if (todaySessions.isEmpty()) {
                prefs.clearSleepDataForToday()
                return true
            }

            // Consolida as sessões da noite encerradas hoje (ex.: caso haja 1 sessão principal ou 2 blocos na mesma noite)
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
                return true
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
            true
        } catch (_: Exception) {
            false
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
