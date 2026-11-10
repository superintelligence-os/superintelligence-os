package com.sios.sistema

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallScreeningService
import androidx.core.app.NotificationCompat
import com.sios.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Llamadas con Claude (filtro de llamadas del sistema). Con cada llamada entrante:
 * - si el número está bloqueado por ti, Claude la corta y te lo cuenta;
 * - si es de un contacto, suena como siempre;
 * - si es desconocido, Claude mira en la memoria del móvil (SMS y avisos) quién puede ser y te lo
 *   dice en una notificación mientras suena («Puede ser Correos: hoy te escribieron por un
 *   paquete»), con un botón para bloquearlo. Opcionalmente, los desconocidos suenan en silencio.
 * Android solo deja escuchar el audio de la llamada a la app de teléfono: Claude no contesta por ti.
 */
class Llamadas : CallScreeningService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onScreenCall(detalles: Call.Details) {
        if (detalles.callDirection != Call.Details.DIRECTION_INCOMING) return responder(detalles, CallResponse.Builder().build())
        val numero = detalles.handle?.schemeSpecificPart.orEmpty()
        val digitos = numero.filter { it.isDigit() }.takeLast(9)
        // Después de llamar a emergencias (o con un SOS en marcha), si te devuelven la llamada, suena
        // siempre: ni se bloquea, ni se silencia, ni se le pregunta nada a la IA.
        if (com.sios.emergencias.Emergencias.enVentanaDevolucion(this)) return responder(detalles, CallResponse.Builder().build())
        when {
            digitos.isNotEmpty() && digitos in bloqueados(this) -> {
                responder(detalles, CallResponse.Builder().setDisallowCall(true).setRejectCall(true).setSkipNotification(true).build())
                avisar(this, numero, getString(R.string.llamadas_bloqueada), getString(R.string.llamadas_bloqueada_texto), conBloquear = false)
                com.sios.datos.Servicio.registrarActividad(getString(R.string.llamadas_cortada_registro, formatear(numero)))
            }
            digitos.isEmpty() || esContacto(numero) -> responder(detalles, CallResponse.Builder().build())
            else -> {
                responder(detalles, CallResponse.Builder().setSilenceCall(silenciarDesconocidos(this)).build())
                // Quién puede ser: lo que diga la memoria del móvil sobre ese número.
                Thread {
                    val pista = runCatching { Memoria.buscarNumero(this, digitos) }.getOrNull()
                    val texto = pista?.let { getString(R.string.llamadas_puede_ser, it) } ?: getString(R.string.llamadas_desconocido_texto)
                    avisar(this, numero, getString(R.string.llamadas_desconocido, formatear(numero)), texto, conBloquear = true)
                    // Mientras suena, Claude saca del mensaje quién es y lo pone en el título.
                    if (pista != null) kotlinx.coroutines.runBlocking {
                        val quien = com.sios.datos.Servicio.rapido(
                            pista,
                            "Por este mensaje, ¿quién es la persona o empresa que lo envió? Responde solo con eso, en 2 a 5 palabras " +
                                "(por ejemplo «Andrés, el fontanero» o «Correos»), sin punto final. Si no se sabe, responde «desconocido».",
                        )?.trim()?.trim('.', '«', '»', '"')
                        if (!quien.isNullOrBlank() && !quien.contains("desconocido", true) && quien.length <= 48) {
                            avisar(this@Llamadas, numero, getString(R.string.llamadas_desconocido, quien), "${formatear(numero)} · $texto", conBloquear = true)
                        }
                    }
                }.start()
            }
        }
    }

    private fun responder(d: Call.Details, r: CallResponse) = respondToCall(d, r)

    private fun esContacto(numero: String): Boolean {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(numero))
        return contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } == true
    }

    companion object {
        private const val CANAL = "llamadas"
        private const val BLOQUEAR = "com.sios.llamadas.BLOQUEAR"

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("llamadas", Context.MODE_PRIVATE)
        fun bloqueados(ctx: Context): Set<String> = prefs(ctx).getStringSet("bloqueados", emptySet()).orEmpty()
        fun silenciarDesconocidos(ctx: Context) = prefs(ctx).getBoolean("silenciar", false)
        fun ponerSilenciar(ctx: Context, si: Boolean) = prefs(ctx).edit().putBoolean("silenciar", si).apply()
        /** Desconocidos: si suena unos segundos sin contestar, contesta Claude y te cuenta qué quieren. */
        fun atenderDesconocidos(ctx: Context) = prefs(ctx).getBoolean("atender", false)
        fun ponerAtender(ctx: Context, si: Boolean) = prefs(ctx).edit().putBoolean("atender", si).apply()

        fun bloquear(ctx: Context, numero: String, si: Boolean = true) {
            val d = numero.filter { it.isDigit() }.takeLast(9)
            require(d.length >= 6) { "Número no válido" }
            val l = bloqueados(ctx).toMutableSet()
            if (si) l += d else l -= d
            prefs(ctx).edit().putStringSet("bloqueados", l).apply()
        }

        fun json(ctx: Context): JSONObject = JSONObject()
            .put("filtro_activo", ctx.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_CALL_SCREENING))
            .put("bloqueados", JSONArray(bloqueados(ctx).toList()))
            .put("silenciar_desconocidos", silenciarDesconocidos(ctx))
            .put("claude_atiende_desconocidos", atenderDesconocidos(ctx))

        /** «600 99 90 00» para leerlo de un vistazo. */
        fun formatear(n: String): String {
            val d = n.filter { it.isDigit() }
            return if (d.length == 9) "${d.substring(0, 3)} ${d.substring(3, 5)} ${d.substring(5, 7)} ${d.substring(7)}" else n
        }

        private fun avisar(ctx: Context, numero: String, titulo: String, texto: String, conBloquear: Boolean) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CANAL) == null) {
                nm.createNotificationChannel(NotificationChannel(CANAL, ctx.getString(R.string.llamadas_canal), NotificationManager.IMPORTANCE_HIGH))
            }
            val n = NotificationCompat.Builder(ctx, CANAL)
                .setSmallIcon(com.sios.ui.Marca.recurso())
                .setColor(com.sios.ui.Marca.acento(true).toInt())
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(3 * 60_000L)
                .apply {
                    if (conBloquear) addAction(
                        0, ctx.getString(R.string.llamadas_bloquear),
                        PendingIntent.getBroadcast(
                            ctx, numero.hashCode(), Intent(ctx, Bloquear::class.java).setAction(BLOQUEAR).putExtra("numero", numero),
                            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                        ),
                    )
                }
                .build()
            if (nm.areNotificationsEnabled()) nm.notify("llamada", numero.hashCode(), n)
        }
    }

    /** Botón «Bloquear» de la notificación. */
    class Bloquear : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val numero = i.getStringExtra("numero") ?: return
            runCatching { bloquear(c, numero) }
            com.sios.datos.Servicio.registrarActividad(
                c.getString(R.string.llamadas_bloqueado_ok, formatear(numero)), null,
                org.json.JSONObject().put("tipo", "puente").put("metodo", "llamadas_bloquear")
                    .put("extras", org.json.JSONObject().put("numero", numero).put("si", "0")),
            )
            c.getSystemService(NotificationManager::class.java).cancel("llamada", numero.hashCode())
            android.widget.Toast.makeText(c, c.getString(R.string.llamadas_bloqueado_ok, formatear(numero)), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
