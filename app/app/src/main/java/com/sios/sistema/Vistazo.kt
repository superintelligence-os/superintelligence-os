package com.sios.sistema

import com.sios.ui.conMarca

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.sios.R
import com.sios.inicio.InicioActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vistazo en la pantalla de bloqueo: hasta cuatro cifras de los widgets (el tiempo, las baterías,
 * cuánto se tarda a casa, la próxima cita…) en una notificación silenciosa y fija. Las elige el
 * servicio (o Claude, con la herramienta panel); al tocarla se abre el panel.
 */
object Vistazo {
    // Importancia normal (sin sonido ni vibración): las silenciosas no salen en el bloqueo.
    private const val CANAL = "vistazo_bloqueo"
    private val VALORES = intArrayOf(R.id.valor1, R.id.valor2, R.id.valor3, R.id.valor4)
    private val ETIQUETAS = intArrayOf(R.id.etiqueta1, R.id.etiqueta2, R.id.etiqueta3, R.id.etiqueta4)
    private val PIEZAS = intArrayOf(R.id.pieza1, R.id.pieza2, R.id.pieza3, R.id.pieza4)

    fun publicar(ctx: Context, piezas: JSONArray): JSONObject {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.deleteNotificationChannel("vistazo")
            nm.createNotificationChannel(
                NotificationChannel(CANAL, ctx.getString(R.string.vistazo_canal), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    description = ctx.getString(R.string.vistazo_canal_nota)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        val n = minOf(4, piezas.length())
        fun vista(diseno: Int) = RemoteViews(ctx.packageName, diseno).apply {
            for (i in 0 until 4) {
                if (i < n) {
                    val p = piezas.getJSONObject(i)
                    setTextViewText(VALORES[i], p.optString("valor"))
                    setTextViewText(ETIQUETAS[i], p.optString("etiqueta"))
                    setViewVisibility(PIEZAS[i], View.VISIBLE)
                } else {
                    setViewVisibility(PIEZAS[i], View.GONE)
                }
            }
        }
        val abrir = PendingIntent.getActivity(
            ctx, 81,
            Intent(ctx, InicioActivity::class.java).putExtra(InicioActivity.EXTRA_PANEL, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val resumen = (0 until n).joinToString(" · ") { piezas.getJSONObject(it).let { p -> "${p.optString("etiqueta")} ${p.optString("valor")}" } }
        val notificacion = Notification.Builder(ctx, CANAL).conMarca()
            .setSmallIcon(com.sios.ui.Marca.recurso())
            .setColor(com.sios.ui.Apariencia.colorAcento(true).toInt())
            .setContentText(resumen)
            .setCustomContentView(vista(R.layout.vistazo))
            .setCustomBigContentView(vista(R.layout.vistazo_grande))
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setGroup("vistazo") // en su propio grupo: Android no lo junta con los avisos (y el toque abre el panel)
            .setContentIntent(abrir)
            .build()
        val permitido = nm.areNotificationsEnabled()
        if (permitido) nm.notify("vistazo", 0, notificacion)
        return JSONObject().put("publicado", permitido).put("piezas", n)
    }

    fun quitar(ctx: Context): JSONObject {
        ctx.getSystemService(NotificationManager::class.java).cancel("vistazo", 0)
        return JSONObject().put("quitado", true)
    }
}
