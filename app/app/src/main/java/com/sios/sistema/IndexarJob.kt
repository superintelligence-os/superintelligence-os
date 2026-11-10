package com.sios.sistema

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.MediaStore

/**
 * Mantiene al día la memoria del móvil sin gastar batería: se despierta cuando hay fotos nuevas
 * (y, por si acaso, cada pocas horas con el móvil cargando) y lee solo lo que falta.
 */
class IndexarJob : JobService() {
    @Volatile private var hilo: Thread? = null

    override fun onStartJob(p: JobParameters): Boolean {
        hilo = Thread {
            runCatching { Memoria.indexar(this) }
            if (p.jobId == FOTOS) programar(this) // los disparos por contenido son de un solo uso
            jobFinished(p, false)
        }.apply { priority = Thread.MIN_PRIORITY; start() }
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean {
        hilo?.interrupt()
        return true
    }

    companion object {
        private const val FOTOS = 41
        private const val PERIODICO = 42
        private const val YA = 43

        fun programar(ctx: Context, ahora: Boolean = false) {
            if (!Memoria.activa(ctx)) return
            val js = ctx.getSystemService(JobScheduler::class.java)
            val c = ComponentName(ctx, IndexarJob::class.java)
            js.schedule(
                JobInfo.Builder(FOTOS, c)
                    .addTriggerContentUri(JobInfo.TriggerContentUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .setTriggerContentUpdateDelay(15_000)
                    .setTriggerContentMaxDelay(10 * 60_000)
                    .build(),
            )
            if (js.getPendingJob(PERIODICO) == null) {
                js.schedule(JobInfo.Builder(PERIODICO, c).setPeriodic(6 * 3_600_000L).setRequiresCharging(true).setPersisted(true).build())
            }
            if (ahora) js.schedule(JobInfo.Builder(YA, c).setOverrideDeadline(1_000).build())
        }
    }
}
