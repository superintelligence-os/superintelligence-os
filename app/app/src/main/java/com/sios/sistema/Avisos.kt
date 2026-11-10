package com.sios.sistema

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sios.R
import com.sios.chat.ChatActivity
import org.json.JSONObject

/**
 * Avisos de Claude (agentes en segundo plano, recordatorios): salen como notificaciones de la app
 * Claude, con su icono y su color, nunca como de Termux.
 */
object Avisos {
    private const val CANAL_DIA = "dia"

    /**
     * «Tu día» en la pantalla de bloqueo: una línea con lo esencial (citas y tiempo), sin sonido.
     * Al tocarla, Claude cuenta el día con detalle.
     */
    fun dia(ctx: Context, texto: String, detalle: String): JSONObject {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL_DIA) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL_DIA, ctx.getString(R.string.dia_canal), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = ctx.getString(R.string.dia_canal_nota)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                },
            )
        }
        val contar = PendingIntent.getActivity(
            ctx, 77,
            Intent(ctx, AsistenteActivity::class.java).putExtra(android.app.SearchManager.QUERY, ctx.getString(R.string.dia_pregunta))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CANAL_DIA)
            .setSmallIcon(com.sios.ui.Marca.recurso())
            .setColor(com.sios.ui.Marca.acento(true).toInt())
            .setContentTitle(ctx.getString(R.string.dia_titulo))
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$texto\n\n$detalle"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contar)
            .setTimeoutAfter(20 * 3_600_000L)
            .setGroup("dia") // suelto: que Android no lo junte con el vistazo
            .build()
        val permitido = nm.areNotificationsEnabled()
        if (permitido) nm.notify("dia", 0, n)
        return JSONObject().put("publicado", permitido)
    }

    private const val CANAL = "agentes"

    fun publicar(
        ctx: Context, id: String, titulo: String, texto: String,
        acciones: List<Pair<String, PendingIntent>> = emptyList(),
        app: String? = null, // una mini-app que abrir al tocarla (el diario); si no, el chat
        silencioso: Boolean = false, // sin sonido ni vibración (de noche, avisos que pueden esperar)
    ): JSONObject {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val canal = if (silencioso) "avisos_silenciosos" else CANAL
        if (silencioso && nm.getNotificationChannel(canal) == null) {
            nm.createNotificationChannel(NotificationChannel(canal, ctx.getString(R.string.avisos_silenciosos_canal), NotificationManager.IMPORTANCE_LOW))
        }
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, ctx.getString(R.string.avisos_canal), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = ctx.getString(R.string.avisos_canal_nota) },
            )
        }
        val abrir = PendingIntent.getActivity(
            ctx, id.hashCode(),
            (if (app != null) Intent(ctx, com.sios.creaciones.MiniAppActivity::class.java).putExtra(com.sios.creaciones.MiniAppActivity.EXTRA_ID, app)
            else Intent(ctx, ChatActivity::class.java)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, canal)
            .setSmallIcon(com.sios.ui.Marca.recurso())
            .setColor(com.sios.ui.Marca.acento(true).toInt())
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setAutoCancel(true)
            .setGroup("aviso-$id") // sin agrupar con otros: así el toque abre lo suyo
            .apply { for ((nombre, accion) in acciones) addAction(0, nombre, accion) }
            .build()
        val permitido = nm.areNotificationsEnabled()
        if (permitido) nm.notify(id, 0, n)
        return JSONObject().put("publicado", permitido)
    }
}
