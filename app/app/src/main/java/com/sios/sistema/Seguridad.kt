package com.sios.sistema

import com.sios.ui.conMarca

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.BlockedNumberContract
import com.sios.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Detector de estafas: cada mensaje nuevo (SMS, WhatsApp, correo…) pasa por el filtro del servicio
 * (gratis); si es sospechoso, Claude lo valora y, si hay riesgo, avisa con la explicación, «Bloquear»
 * y «Es de fiar». No toca el mensaje original.
 */
object Seguridad {
    private const val CANAL = "seguridad"
    private const val BLOQUEAR = "com.sios.seguridad.BLOQUEAR"
    private const val FIAR = "com.sios.seguridad.FIAR"
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { okhttp3.OkHttpClient.Builder().callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build() }
    private val APPS_DE_MENSAJES = Regex("(messaging|mms|sms|whatsapp|telegram|signal|mail|gm|outlook|messenger|instagram|viber|line)", RegexOption.IGNORE_CASE)

    fun revisar(ctx: Context, aviso: Bandeja.Aviso) {
        if (aviso.paquete == ctx.packageName) return
        if (!aviso.esMensaje && !APPS_DE_MENSAJES.containsMatchIn(aviso.paquete)) return
        val app = ctx.applicationContext
        alcance.launch {
            val r = runCatching {
                val cuerpo = JSONObject().put("texto", aviso.texto).put("de", aviso.titulo).put("app", aviso.app).toString()
                val peticion = okhttp3.Request.Builder().url("http://127.0.0.1:8765/estafa").post(cuerpo.toRequestBody("application/json".toMediaType())).build()
                http.newCall(peticion).execute().use { JSONObject(it.body.string()) }
            }.getOrNull() ?: return@launch
            val riesgo = r.optString("riesgo")
            if (riesgo == "medio" || riesgo == "alto") avisar(app, aviso, riesgo, r.optString("explicacion"), r.optString("consejo"))
        }
    }

    private fun avisar(ctx: Context, aviso: Bandeja.Aviso, riesgo: String, explicacion: String, consejo: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, ctx.getString(R.string.estafa_canal), NotificationManager.IMPORTANCE_HIGH))
        }
        val id = 50_000 + (aviso.clave.hashCode() and 0x7fff)
        fun accion(que: String, texto: Int) = Notification.Action.Builder(
            null, ctx.getString(texto),
            PendingIntent.getBroadcast(
                ctx, id * 2 + if (que == BLOQUEAR) 0 else 1,
                Intent(ctx, Receptor::class.java).setAction(que).putExtra("de", aviso.titulo).putExtra("id", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()
        val texto = listOf(explicacion, consejo).filter { it.isNotBlank() }.joinToString(" ")
        val n = Notification.Builder(ctx, CANAL).conMarca()
            .setSmallIcon(com.sios.ui.Marca.recurso())
            .setColor(if (riesgo == "alto") 0xFFE0816F.toInt() else com.sios.ui.Marca.acento(true).toInt())
            .setContentTitle(ctx.getString(if (riesgo == "alto") R.string.estafa_titulo_alto else R.string.estafa_titulo_medio, aviso.titulo.ifBlank { aviso.app }))
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setCategory(Notification.CATEGORY_STATUS)
            .addAction(accion(BLOQUEAR, R.string.estafa_bloquear))
            .addAction(accion(FIAR, R.string.estafa_fiar))
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    /** Bloquear (SMS y llamadas, en el sistema: Claude es el marcador) o marcar de fiar. */
    class Receptor : BroadcastReceiver() {
        override fun onReceive(ctx: Context, i: Intent) {
            val de = i.getStringExtra("de").orEmpty()
            ctx.getSystemService(NotificationManager::class.java).cancel(i.getIntExtra("id", 0))
            when (i.action) {
                BLOQUEAR -> {
                    runCatching {
                        ctx.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, ContentValues().apply { put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, de) })
                    }
                    runCatching { if (de.count(Char::isDigit) >= 6) Llamadas.bloquear(ctx, de, true) }
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.estafa_bloqueado, de), android.widget.Toast.LENGTH_SHORT).show()
                }
                FIAR -> alcance.launch {
                    runCatching {
                        val peticion = okhttp3.Request.Builder().url("http://127.0.0.1:8765/estafa/seguro")
                            .post(JSONObject().put("de", de).toString().toRequestBody("application/json".toMediaType())).build()
                        http.newCall(peticion).execute().close()
                    }
                }
            }
        }
    }
}
