package com.sios.sistema

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.sios.R
import com.sios.datos.Servicio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Notificaciones con cerebro. Claude escucha las notificaciones (solo en el móvil) para:
 * - resumir lo que ha llegado mientras no mirabas, al desbloquear;
 * - recordarte los mensajes que llevan horas sin contestar;
 * - silenciar el ruido en el modo foco y contarte después lo que te perdiste;
 * - contestar mensajes sin abrir la app (con la respuesta rápida de la propia notificación).
 * El agente lo usa a través del puente (notificaciones, responder, foco).
 */
class Bandeja : NotificationListenerService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private val principal = Handler(Looper.getMainLooper())
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var alDesbloquear: BroadcastReceiver? = null

    override fun onListenerConnected() {
        instancia = this
        runCatching { activeNotifications?.forEach { if (!quitarSiSobra(it)) anotar(it, nueva = false) } }
        alDesbloquear = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { resumir() }
        }.also { registerReceiver(it, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED) }
        principal.postDelayed(revisar, REVISION)
    }

    /**
     * Avisos del sistema que en SIOS sobran y se retiran al instante: «qwerty configurado» (el
     * diseño de un teclado físico conectado; se cambia en Ajustes › Sistema › Teclado).
     */
    private fun quitarSiSobra(sbn: StatusBarNotification): Boolean {
        val sobra = sbn.packageName == "android" && sbn.notification.channelId == "PHYSICAL_KEYBOARD"
        if (sobra) runCatching { cancelNotification(sbn.key) }
        return sobra
    }

    override fun onListenerDisconnected() {
        if (instancia === this) instancia = null
        alDesbloquear?.let { runCatching { unregisterReceiver(it) } }
        principal.removeCallbacks(revisar)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (quitarSiSobra(sbn)) return
        val aviso = anotar(sbn, nueva = true) ?: return
        // Modo foco: lo que no es importante se aparta hasta que termine.
        val fin = focoHasta.value
        if (fin > System.currentTimeMillis() && !aviso.importante) {
            runCatching { snoozeNotification(sbn.key, fin - System.currentTimeMillis()) }
            silenciadas += aviso
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, ranking: RankingMap?, razon: Int) {
        val a = avisos.remove(sbn.key) ?: return
        // Tocarla o que la app la quite (porque la has leído o contestado): ya no está pendiente.
        if (razon == REASON_CLICK || razon == REASON_APP_CANCEL || razon == REASON_APP_CANCEL_ALL) pendientes.remove(a.conversacion)
    }

    // ---------- Registro ----------

    /** Una notificación, con lo que Claude necesita de ella. */
    class Aviso(
        val clave: String,
        val app: String,
        val paquete: String,
        val titulo: String,
        val texto: String,
        val cuando: Long,
        val conversacion: String, // paquete + título: agrupa los mensajes de una misma persona o grupo
        val esMensaje: Boolean,
        val importante: Boolean,
        val responder: Notification.Action?,
        val historial: String = texto,
    ) {
        fun json(): JSONObject = JSONObject()
            .put("clave", clave).put("app", app).put("titulo", titulo).put("texto", texto)
            .put("hace_min", (System.currentTimeMillis() - cuando) / 60_000)
            .put("mensaje", esMensaje).put("importante", importante).put("se_puede_responder", responder != null)
    }

    private fun anotar(sbn: StatusBarNotification, nueva: Boolean): Aviso? {
        val n = sbn.notification
        if (sbn.packageName in IGNORAR || sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) return null
        val e = n.extras
        val mensajes = Notification.MessagingStyle.Message.getMessagesFromBundleArray(e.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Parcelable::class.java))
        val ultimo = mensajes.lastOrNull()
        val titulo = (e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: e.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        // texto: el último mensaje. historial: la conversación entera si la app la da (para resumir y responder con contexto).
        val texto = (ultimo?.let { m -> listOfNotNull(m.senderPerson?.name?.takeIf { e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) }, m.text).joinToString(": ") }
            ?: e.getCharSequence(Notification.EXTRA_TEXT) ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        val historial = (mensajes.takeLast(8).takeIf { it.size > 1 }?.joinToString("\n") { m -> "${m.senderPerson?.name ?: "Yo"}: ${m.text}" }
            ?: e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.length > texto.length })?.takeLast(1500) ?: texto
        if (titulo.isBlank() && texto.isBlank()) return null
        val responder = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val esMensaje = n.category == Notification.CATEGORY_MESSAGE || mensajes.isNotEmpty() || responder != null
        val importancia = currentRanking?.let { r -> Ranking().takeIf { r.getRanking(sbn.key, it) }?.importance } ?: NotificationManager.IMPORTANCE_DEFAULT
        // Importante: códigos, llamadas, alarmas, citas y mensajes de personas; la publicidad no, aunque llegue por SMS.
        val todo = "$titulo $texto"
        val importante = CODIGO.containsMatchIn(todo) ||
            !PUBLICIDAD.containsMatchIn(todo) && (esMensaje || n.category in IMPORTANTES || importancia >= NotificationManager.IMPORTANCE_HIGH)
        val aviso = Aviso(
            sbn.key, nombreApp(sbn.packageName), sbn.packageName, titulo, texto,
            // Hora de llegada real: al volver a publicarse, postTime cambia aunque el mensaje sea el mismo.
            (if (nueva) recientes.lastOrNull { it.conversacion == "${sbn.packageName}|$titulo" && it.texto == texto }?.cuando else null) ?: sbn.postTime,
            "${sbn.packageName}|$titulo", esMensaje, importante, responder, historial,
        )
        avisos[sbn.key] = aviso
        // Algunas apps vuelven a publicar todas sus conversaciones con cada mensaje: solo es nuevo un texto que no estaba.
        val repetida = synchronized(recientes) { recientes.any { it.conversacion == aviso.conversacion && it.texto == aviso.texto } }
        // Lo que ya estaba en la barra al conectarse queda anotado como visto (con su hora), para reconocerlo si se republica.
        if (!nueva && !repetida) synchronized(recientes) { recientes.add(0, aviso) }
        if (nueva && !repetida) {
            recientes.removeAll { it.clave == aviso.clave }
            recientes += aviso
            alcance.launch { Memoria.anotarAviso(this@Bandeja, aviso) }
            Disparadores.notificacion(aviso)
            Seguridad.revisar(this, aviso) // ¿estafa? (filtro gratis; Claude solo si hay sospecha)
            Coche.alMensaje(this, aviso) // conduciendo: en voz alta
            Continuidad.aviso(this, aviso) // y al Mac, si está vinculado
            if (DINERO.containsMatchIn(aviso.texto) && aviso.paquete != packageName) alcance.launch { pago(aviso) } // ¿un cobro? a tus gastos
            while (recientes.size > 60) recientes.removeAt(0)
            // Un mensaje de otra persona: pendiente hasta que lo leas o contestes.
            // (En MessagingStyle, los mensajes propios no llevan remitente: si el último es tuyo, ya contestaste.)
            when {
                esMensaje && responder != null && (ultimo == null || ultimo.senderPerson != null) -> pendientes[aviso.conversacion] = Pendiente(aviso)
                ultimo != null && ultimo.senderPerson == null -> pendientes.remove(aviso.conversacion)
            }
        }
        return aviso
    }

    private val DINERO = Regex("(\\d[.,]\\d{2}\\s?(€|EUR))|(€\\s?\\d)", RegexOption.IGNORE_CASE)

    private fun nombreApp(p: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)

    // ---------- Resumen al desbloquear ----------

    /**
     * Al desbloquear: si desde el desbloqueo anterior han llegado al menos 3 avisos (y no hubo otro
     * resumen hace poco), Claude los resume en una notificación. ahora=true lo fuerza (agente, pruebas).
     */
    fun resumir(ahora: Boolean = false) {
        val t = System.currentTimeMillis()
        val desde = ultimoDesbloqueo
        ultimoDesbloqueo = t
        if (!ahora && !Preferencias.resumen(this)) return
        val nuevas = recientes.filter { it.cuando > desde && it.paquete != packageName }.distinctBy { it.conversacion to it.texto }
        if (nuevas.isEmpty() || (!ahora && (nuevas.size < MIN_RESUMEN || t - ultimoResumen < ENTRE_RESUMENES))) return
        ultimoResumen = t
        alcance.launch {
            val lista = nuevas.takeLast(25).joinToString("\n") { "- [${it.app}] ${it.titulo}: ${it.texto.take(200)}" }
            val r = Servicio.rapido(
                lista,
                "Eres Claude en el móvil del usuario. Te paso las notificaciones que han llegado mientras no miraba. " +
                    "Resúmelas en 2 o 3 frases muy cortas en español: primero lo importante (mensajes de personas, citas, códigos, pagos), " +
                    "y agrupa el resto como ruido («y 5 avisos de promociones»). Sin saludos, sin listas, sin comillas.",
            ) ?: return@launch
            // Botones de responder: solo para mensajes importantes de personas (ni publicidad ni códigos), los más recientes.
            val conRespuesta = nuevas.filter { it.responder != null && it.importante && !CODIGO.containsMatchIn(it.texto) }
                .sortedByDescending { it.cuando }.distinctBy { it.conversacion }.take(2)
            publicar(
                "resumen", getString(R.string.bandeja_resumen_titulo, nuevas.size), r,
                acciones = conRespuesta.map { getString(R.string.bandeja_responder_a, it.titulo) to ResponderActivity.intent(this@Bandeja, it.clave) },
            )
        }
    }

    // ---------- Pendientes y fin del modo foco ----------

    class Pendiente(val aviso: Aviso, var avisado: Boolean = false)

    private val revisar = object : Runnable {
        override fun run() {
            val ahora = System.currentTimeMillis()
            if (Preferencias.pendientes(this@Bandeja)) {
                pendientes.values.filter { !it.avisado && ahora - it.aviso.cuando > PENDIENTE && avisos.values.any { a -> a.conversacion == it.aviso.conversacion } }
                    .take(2).forEach { p ->
                        p.avisado = true
                        val horas = ((ahora - p.aviso.cuando) / 3_600_000).toInt().coerceAtLeast(1)
                        publicar(
                            "pendiente-${p.aviso.conversacion.hashCode()}",
                            resources.getQuantityString(R.plurals.bandeja_pendiente_titulo, horas, p.aviso.titulo, horas),
                            p.aviso.texto,
                            acciones = listOf(getString(R.string.bandeja_responder) to ResponderActivity.intent(this@Bandeja, p.aviso.clave)),
                        )
                    }
            }
            val fin = focoHasta.value
            if (fin in 1..ahora) terminarFoco()
            principal.postDelayed(this, REVISION)
        }
    }

    fun empezarFoco(minutos: Int) {
        focoHasta.value = System.currentTimeMillis() + minutos * 60_000L
        silenciadas.clear()
        principal.removeCallbacks(revisar)
        principal.postDelayed(revisar, minOf(REVISION, minutos * 60_000L + 1000))
    }

    fun terminarFoco() {
        focoHasta.value = 0
        val lista = silenciadas.toList().distinctBy { it.conversacion to it.texto }
        silenciadas.clear()
        if (lista.isEmpty()) return
        // Qué esperó, en pocas líneas: «Mensajes · Oferta flash: 70% de descuento».
        val lineas = lista.takeLast(4).joinToString("\n") { "${it.app} · ${it.texto.take(60)}" } +
            if (lista.size > 4) "\n" + getString(R.string.bandeja_y_mas, lista.size - 4) else ""
        publicar("foco", resources.getQuantityString(R.plurals.bandeja_foco_titulo, lista.size, lista.size), lineas)
    }

    private fun publicar(id: String, titulo: String, texto: String, acciones: List<Pair<String, PendingIntent>> = emptyList()) =
        Avisos.publicar(this, id, titulo, texto, acciones)

    // ---------- Responder sin abrir la app ----------

    /** Lo último de una conversación (chat o grupo), de la memoria y de lo que hay en la barra. */
    fun conversacion(nombre: String, horas: Int): JSONObject {
        val desde = System.currentTimeMillis() - horas * 3_600_000L
        val mensajes = sortedMapOf<Long, Pair<String, String>>()
        Memoria.avisosDe(this, nombre, horas).forEach { (f, t, x) -> mensajes[f] = t to x }
        synchronized(recientes) {
            recientes.filter { it.cuando >= desde && Memoria.coincide(it.titulo, nombre) }.forEach { a ->
                a.historial.lines().filter { it.isNotBlank() }.forEachIndexed { i, l -> mensajes.putIfAbsent(a.cuando - (100 - i), "${a.app} · ${a.titulo}" to l) }
            }
        }
        // Sin repetir líneas (las apps vuelven a publicar la conversación con cada mensaje).
        val vistas = HashSet<String>()
        val lista = JSONArray()
        for ((f, par) in mensajes) {
            if (!vistas.add(par.second)) continue
            lista.put(JSONObject().put("hora", java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(f))).put("chat", par.first).put("texto", par.second))
        }
        return JSONObject().put("mensajes", lista).put("memoria", Memoria.activa(this))
    }

    /** Un aviso con un importe: el servicio decide si es un cobro y lo anota (todo en el móvil). */
    private fun pago(a: Aviso) = runCatching {
        val cuerpo = JSONObject().put("app", a.app).put("titulo", a.titulo).put("texto", a.texto).put("cuando", a.cuando).toString()
        val r = okhttp3.Request.Builder().url("http://127.0.0.1:8765/gastos/aviso").post(
            okhttp3.RequestBody.create("application/json".toMediaTypeOrNull(), cuerpo),
        ).build()
        okhttp3.OkHttpClient().newCall(r).execute().close()
    }

    fun responder(clave: String, texto: String): Boolean {
        val a = avisos[clave] ?: recientes.lastOrNull { it.clave == clave } ?: return false
        val accion = a.responder ?: return false
        val entradas = accion.remoteInputs ?: return false
        val datos = Bundle().apply { entradas.filter { it.allowFreeFormInput }.forEach { putCharSequence(it.resultKey, texto) } }
        val intent = Intent()
        RemoteInput.addResultsToIntent(entradas, intent, datos)
        RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        // Algunas apps (Mensajes) responden abriendo una pantalla suya: Claude la permite expresamente.
        val opciones = android.app.ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS)
        accion.actionIntent.send(this, 0, intent, null, null, null, opciones.toBundle())
        pendientes.remove(a.conversacion)
        return true
    }

    companion object {
        @Volatile var instancia: Bandeja? = null
            private set

        val avisos = ConcurrentHashMap<String, Aviso>() // las que están en la barra
        val recientes = java.util.Collections.synchronizedList(mutableListOf<Aviso>()) // las últimas que han llegado
        val pendientes = ConcurrentHashMap<String, Pendiente>()
        val silenciadas = java.util.Collections.synchronizedList(mutableListOf<Aviso>())
        val focoHasta = MutableStateFlow(0L)
        @Volatile private var ultimoResumen = 0L
        @Volatile private var ultimoDesbloqueo = System.currentTimeMillis()

        private const val REVISION = 15 * 60_000L
        private const val PENDIENTE = 3 * 3_600_000L
        private const val MIN_RESUMEN = 3
        private const val ENTRE_RESUMENES = 20 * 60_000L
        private val IGNORAR = setOf("com.sios", "com.termux", "com.termux.api", "android", "com.android.systemui", "com.android.providers.downloads")
        private val IMPORTANTES = setOf(
            Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL, Notification.CATEGORY_ALARM,
            Notification.CATEGORY_REMINDER, Notification.CATEGORY_EVENT, Notification.CATEGORY_MESSAGE,
        )
        private val PUBLICIDAD = Regex("""\b(oferta|descuento|promo\w*|rebajas|gratis|cup[oó]n|black friday|\d{1,2} ?% ?(dto|de descuento|off)?|sale|deal|unsubscribe|darse de baja)\b""", RegexOption.IGNORE_CASE)
        private val CODIGO = Regex("""(c[oó]digo|code|OTP|verificaci[oó]n)\D{0,30}\d{4,8}""", RegexOption.IGNORE_CASE)

        /** Para el agente: lo que hay ahora en la barra y lo que ha llegado hace poco. */
        fun json(): JSONObject = JSONObject()
            .put("activa", instancia != null)
            .put("en_la_barra", JSONArray(avisos.values.sortedByDescending { it.cuando }.distinctBy { it.conversacion to it.texto }.take(40).map { it.json() }))
            .put("sin_contestar", JSONArray(pendientes.values.map { it.aviso.json() }))
            .put("modo_foco_hasta_min", focoHasta.value.takeIf { it > 0 }?.let { (it - System.currentTimeMillis()) / 60_000 } ?: JSONObject.NULL)

        /** SIOS concede el acceso a notificaciones a Claude en el primer arranque. */
        fun componente(ctx: Context) = android.content.ComponentName(ctx, Bandeja::class.java)
    }
}

/** Preferencias de las notificaciones con cerebro (Ajustes de SIOS). Activadas por defecto. */
object Preferencias {
    private fun p(ctx: Context) = ctx.getSharedPreferences("bandeja", Context.MODE_PRIVATE)
    fun resumen(ctx: Context) = p(ctx).getBoolean("resumen", true)
    fun pendientes(ctx: Context) = p(ctx).getBoolean("pendientes", true)
    fun poner(ctx: Context, clave: String, v: Boolean) = p(ctx).edit().putBoolean(clave, v).apply()
}
