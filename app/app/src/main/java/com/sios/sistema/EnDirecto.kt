package com.sios.sistema

import com.sios.ui.conMarca

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.sios.R
import com.sios.inicio.InicioActivity
import org.json.JSONObject

/**
 * Actividades en directo de los widgets (un partido en juego, un paquete en reparto, el vuelo de
 * hoy): Live Updates de Android 16. Salen como un chip en la barra de estado y arriba del todo en el
 * bloqueo, con la misma información que la tarjeta del panel. Las publica el servicio cuando los
 * datos de un widget traen `vivo` y las quita cuando dejan de traerlo.
 */
object EnDirecto {
    private const val CANAL = "en_directo"

    private fun canal(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, ctx.getString(R.string.en_directo_canal), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
            )
        }
        return nm
    }

    private fun numero(id: String) = 40_000 + (id.hashCode() and 0x7fff)

    fun publicar(ctx: Context, id: String, titulo: String, texto: String, chip: String, progreso: Float?, acento: String): JSONObject {
        val nm = canal(ctx)
        // Tocarla abre el panel (el inicio, que lo abre con un extra).
        val abrir = PendingIntent.getActivity(
            ctx, numero(id), Intent(ctx, InicioActivity::class.java).putExtra(InicioActivity.EXTRA_PANEL, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(ctx, CANAL).conMarca()
            .setSmallIcon(com.sios.ui.Marca.recurso())
            .setContentTitle(titulo)
            .setContentText(texto.ifBlank { null })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setColor(com.sios.ui.colorAcento(acento).let { android.graphics.Color.argb((it.alpha * 255).toInt(), (it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) })
        // Android 16: pide que se «promocione» (chip en la barra de estado) con un texto corto.
        runCatching { b.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType).invoke(b, true) }
        if (chip.isNotBlank()) runCatching { b.javaClass.getMethod("setShortCriticalText", String::class.java).invoke(b, chip) }
        if (progreso != null) {
            runCatching {
                val estilo = Class.forName("android.app.Notification\$ProgressStyle").getConstructor().newInstance()
                estilo.javaClass.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(estilo, (progreso.coerceIn(0f, 1f) * 100).toInt())
                b.setStyle(estilo as Notification.Style)
            }.onFailure { b.setProgress(100, (progreso * 100).toInt(), false) }
        }
        nm.notify(numero(id), b.build())
        return JSONObject().put("publicada", true)
    }

    fun quitar(ctx: Context, id: String): JSONObject {
        ctx.getSystemService(NotificationManager::class.java).cancel(numero(id))
        return JSONObject().put("quitada", true)
    }
}
