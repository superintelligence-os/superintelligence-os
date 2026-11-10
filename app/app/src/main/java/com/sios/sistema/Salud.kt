package com.sios.sistema

import android.content.Context
import android.content.pm.PackageManager
import android.health.connect.AggregateRecordsRequest
import android.health.connect.AggregateRecordsResponse
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.ReadRecordsRequestUsingFilters
import android.health.connect.ReadRecordsResponse
import android.health.connect.TimeInstantRangeFilter
import android.health.connect.datatypes.SleepSessionRecord
import android.health.connect.datatypes.StepsRecord
import android.os.OutcomeReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Pasos y sueño de Health Connect (lo que apuntan el reloj, la pulsera o el propio móvil), para el
 * widget de salud del panel: los pasos de cada uno de los últimos 7 días y lo que se durmió anoche.
 * Solo lectura; el usuario da el permiso en Health Connect.
 */
object Salud {
    const val PASOS = "android.permission.health.READ_STEPS"
    const val SUENO = "android.permission.health.READ_SLEEP"

    fun json(ctx: Context): JSONObject {
        check(ctx.checkSelfPermission(PASOS) == PackageManager.PERMISSION_GRANTED) { "Permite a Claude leer los pasos en Health Connect" }
        val hc = ctx.getSystemService(HealthConnectManager::class.java) ?: error("Este móvil no tiene Health Connect")
        val zona = ZoneId.systemDefault()
        val hoy = LocalDate.now(zona)
        val pasos = JSONArray()
        for (d in 6 downTo 0) {
            val dia = hoy.minusDays(d.toLong())
            val desde = dia.atStartOfDay(zona).toInstant()
            val hasta = dia.plusDays(1).atStartOfDay(zona).toInstant()
            val total = esperar<AggregateRecordsResponse<Long>> { listo ->
                hc.aggregate(
                    AggregateRecordsRequest.Builder<Long>(TimeInstantRangeFilter.Builder().setStartTime(desde).setEndTime(hasta).build())
                        .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL).build(),
                    ctx.mainExecutor, listo,
                )
            }?.get(StepsRecord.STEPS_COUNT_TOTAL) ?: 0L
            pasos.put(JSONObject().put("dia", dia.toString()).put("y", total))
        }
        val r = JSONObject().put("pasos", pasos).put("objetivo", 10_000)
        // Sueño: la última sesión que terminó en las últimas 18 horas.
        if (ctx.checkSelfPermission(SUENO) == PackageManager.PERMISSION_GRANTED) {
            val ahora = java.time.Instant.now()
            val sesiones = esperar<ReadRecordsResponse<SleepSessionRecord>> { listo ->
                hc.readRecords(
                    ReadRecordsRequestUsingFilters.Builder(SleepSessionRecord::class.java)
                        .setTimeRangeFilter(TimeInstantRangeFilter.Builder().setStartTime(ahora.minusSeconds(18 * 3600)).setEndTime(ahora).build()).build(),
                    ctx.mainExecutor, listo,
                )
            }?.records.orEmpty()
            sesiones.maxByOrNull { it.endTime }?.let { s -> r.put("sueno_min", java.time.Duration.between(s.startTime, s.endTime).toMinutes()) }
        }
        return r
    }

    /** Las llamadas de Health Connect son asíncronas: el puente espera (10 s como mucho). */
    private fun <T> esperar(pedir: (OutcomeReceiver<T, HealthConnectException>) -> Unit): T? {
        val listo = CountDownLatch(1)
        var resultado: T? = null
        var fallo: Throwable? = null
        pedir(object : OutcomeReceiver<T, HealthConnectException> {
            override fun onResult(r: T) { resultado = r; listo.countDown() }
            override fun onError(e: HealthConnectException) { fallo = e; listo.countDown() }
        })
        if (!listo.await(10, TimeUnit.SECONDS)) error("Health Connect no respondió")
        fallo?.let { throw it }
        return resultado
    }
}
