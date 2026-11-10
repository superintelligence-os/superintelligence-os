package com.sios.sistema

import com.sios.ui.conMarca

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.sios.R

/**
 * Capturas inteligentes: al hacer una captura, se lee su texto en el móvil ([Ocr]) y se ofrece lo
 * que tenga sentido —ir a una dirección, llamar, abrir un enlace, añadir una cita al calendario— y
 * siempre «Claude», que recibe la captura. Claude no se llama hasta que el usuario lo toca.
 */
object Capturas {
    private const val CANAL = "capturas"
    private const val HACER = "com.sios.capturas.HACER"
    private var observador: ContentObserver? = null
    private var ultima = 0L

    fun vigilar(ctx: Context) {
        if (observador != null) return
        val app = ctx.applicationContext
        observador = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(propio: Boolean, uri: Uri?) { revisar(app) }
        }.also { app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it) }
    }

    /** La imagen más reciente, si es una captura de hace un momento y no se ha mirado ya. */
    private fun revisar(ctx: Context) {
        val c = runCatching {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.IS_PENDING),
                null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )
        }.getOrNull() ?: return
        c.use {
            if (!it.moveToFirst()) return
            val id = it.getLong(0)
            val ruta = it.getString(1).orEmpty()
            val segundos = it.getLong(2)
            if (it.getInt(3) == 1 || id == ultima || !ruta.contains("Screenshot", ignoreCase = true)) return
            if (System.currentTimeMillis() / 1000 - segundos > 20) return
            ultima = id
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            // Un momento: Android termina de escribir la captura.
            Handler(Looper.getMainLooper()).postDelayed({ leer(ctx, uri) }, 1200)
        }
    }

    private fun leer(ctx: Context, uri: Uri) {
        // Fuera del hilo principal: leer una captura lleva un momento.
        Thread({
            val texto = Ocr.leer(ctx, uri)
            val acciones = if (texto.isBlank()) emptyList() else runCatching { Sugerencias.para(ctx, texto, deCaptura = true).take(2) }.getOrDefault(emptyList())
            avisar(ctx, uri, acciones)
        }, "capturas-ocr").start()
    }

    private fun avisar(ctx: Context, uri: Uri, acciones: List<Sugerencias.Accion>) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, ctx.getString(R.string.capturas_canal), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        }
        val id = 60_000
        val b = Notification.Builder(ctx, CANAL).conMarca()
            .setSmallIcon(com.sios.ui.Marca.recurso())
            .setColor(com.sios.ui.Apariencia.colorAcento(true).toInt())
            .setContentTitle(ctx.getString(R.string.capturas_titulo))
            .setContentText(if (acciones.isEmpty()) ctx.getString(R.string.capturas_texto_ia) else acciones.joinToString(" · ") { it.titulo })
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000)
        acciones.forEachIndexed { i, a ->
            val pi = PendingIntent.getBroadcast(
                ctx, id + i, Intent(ctx, Receptor::class.java).setAction(HACER).putExtra("tipo", a.tipo).putExtra("valor", a.valor).putExtra("titulo", a.titulo),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.addAction(Notification.Action.Builder(null, a.titulo, pi).build())
        }
        // Claude: la captura, compartida con la hoja de Claude.
        val claude = PendingIntent.getActivity(
            ctx, id + 9,
            Intent(Intent.ACTION_SEND).setClass(ctx, AsistenteActivity::class.java).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        b.addAction(Notification.Action.Builder(null, "Claude", claude).build())
        b.setContentIntent(claude)
        nm.notify(id, b.build())
    }

    class Receptor : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            ctx.getSystemService(NotificationManager::class.java).cancel(60_000)
            Sugerencias.hacer(ctx, Sugerencias.Accion(i.getStringExtra("tipo").orEmpty(), i.getStringExtra("titulo").orEmpty(), i.getStringExtra("valor").orEmpty()))
        }
    }
}
