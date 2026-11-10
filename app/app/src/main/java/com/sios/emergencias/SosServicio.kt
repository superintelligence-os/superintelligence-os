package com.sios.emergencias

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.sios.R
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lo que pasa después de pedir ayuda (y al compartir el viaje): manda tu ubicación por SMS a tus
 * contactos y, durante el tiempo elegido, la actualizada cada pocos minutos. Es un servicio en primer
 * plano (con su aviso y un botón para parar) que mantiene el móvil despierto mientras dura: en una
 * emergencia manda que funcione, no la batería.
 */
class SosServicio : Service() {
    enum class Modo { SOS, VIAJE }

    enum class Envio { ENVIANDO, ENVIADO, FALLO, SIMULADO, SIN_PERMISO }

    data class Aviso(val contacto: Emergencias.Contacto, val envio: Envio)

    data class Estado(
        val modo: Modo,
        val motivo: Emergencias.Motivo,
        val prueba: Boolean,
        val desde: Long,
        val hasta: Long,
        val avisos: List<Aviso>,
        val lat: Double? = null,
        val lon: Double? = null,
        val precision: Int? = null,
        val horaUbicacion: Long? = null,
        val actualizaciones: Int = 0,
        val terminado: Boolean = false,
    ) {
        val activo get() = !terminado && System.currentTimeMillis() < hasta
    }

    companion object {
        private const val CANAL = "emergencias"
        private const val AVISO = 7112
        private const val ACCION_EMPEZAR = "com.sios.emergencias.EMPEZAR"
        private const val ACCION_PARAR = "com.sios.emergencias.PARAR"
        private const val ACCION_ENVIADO = "com.sios.emergencias.SMS_ENVIADO"

        /** Cada cuánto se mira dónde estás; se manda si te has movido o si hace un rato del último. */
        private const val INTERVALO = 5 * 60_000L
        private const val SIEMPRE_CADA = 15 * 60_000L
        private const val DISTANCIA_MINIMA = 50f

        val estado = MutableStateFlow<Estado?>(null)

        /** Después de pedir ayuda: a todos los contactos, durante los minutos elegidos en Ajustes. */
        fun empezarSos(ctx: Context, motivo: Emergencias.Motivo, prueba: Boolean) {
            val contactos = Emergencias.contactos(ctx)
            val ahora = System.currentTimeMillis()
            val minutos = Emergencias.minutosCompartir(ctx)
            estado.value = Estado(
                Modo.SOS, motivo, prueba, ahora, ahora + minutos * 60_000L,
                contactos.map { Aviso(it, Envio.ENVIANDO) }, terminado = contactos.isEmpty(),
            )
            if (contactos.isEmpty()) return
            arrancar(ctx, Intent(ctx, SosServicio::class.java).setAction(ACCION_EMPEZAR))
        }

        /** Compartir el viaje: con un contacto, durante [minutos]. */
        fun empezarViaje(ctx: Context, contacto: Emergencias.Contacto, minutos: Int, prueba: Boolean) {
            if (estado.value?.let { it.activo && it.modo == Modo.SOS } == true) return // un SOS en marcha manda
            val ahora = System.currentTimeMillis()
            estado.value = Estado(Modo.VIAJE, Emergencias.Motivo.BOTON, prueba, ahora, ahora + minutos * 60_000L, listOf(Aviso(contacto, Envio.ENVIANDO)))
            arrancar(ctx, Intent(ctx, SosServicio::class.java).setAction(ACCION_EMPEZAR))
        }

        /** Parar a mano: se avisa a los contactos de que ya no hace falta (o de que has llegado). */
        fun parar(ctx: Context) {
            if (estado.value?.activo != true) {
                estado.value = estado.value?.copy(terminado = true)
                return
            }
            arrancar(ctx, Intent(ctx, SosServicio::class.java).setAction(ACCION_PARAR))
        }

        private fun arrancar(ctx: Context, i: Intent) {
            runCatching { ctx.startForegroundService(i) }.onFailure { runCatching { ctx.startService(i) } }
        }

        fun duracion(ctx: Context, minutos: Int): String =
            if (minutos < 60 || minutos % 60 != 0) ctx.getString(R.string.em_dur_min, minutos) else ctx.getString(R.string.em_dur_h, minutos / 60)
    }

    private val principal = Handler(Looper.getMainLooper())
    private var despierto: PowerManager.WakeLock? = null
    private var ubicacionEncendida = false
    private var ultimaEnviada: Location? = null
    private var horaUltimoEnvio = 0L
    private val codigos = AtomicInteger(1)
    private var receptor: BroadcastReceiver? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val e = estado.value
        if (e == null) {
            // Android ha vuelto a crear el servicio sin nada que hacer (el proceso se cerró): fuera.
            stopSelf()
            return START_NOT_STICKY
        }
        primerPlano(e)
        when (intent?.action) {
            ACCION_PARAR -> terminar(manual = true)
            else -> empezar(e)
        }
        return START_NOT_STICKY
    }

    private fun primerPlano(e: Estado) {
        val n = aviso(e)
        runCatching { startForeground(AVISO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
            .onFailure {
                // Sin permiso de ubicación no puede ser un servicio de ubicación: los SMS salen igual.
                android.util.Log.w("SIOS", "SOS sin servicio de ubicación en primer plano", it)
                runCatching { getSystemService(NotificationManager::class.java).notify(AVISO, n) }
            }
    }

    private fun empezar(e: Estado) {
        principal.removeCallbacksAndMessages(null)
        escucharEnvios()
        despierto?.let { if (it.isHeld) it.release() }
        despierto = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SIOS:emergencias").apply {
            setReferenceCounted(false)
            acquire(e.hasta - System.currentTimeMillis() + 2 * 60_000L)
        }
        if (e.modo == Modo.SOS && !e.prueba) ubicacionEncendida = Ubicacion.encenderSiHaceFalta(this)
        ultimaEnviada = null
        // Si hay una posición de hace nada, el primer SMS sale ya; si no, se espera un poco a una buena.
        val reciente = Ubicacion.ultima(this)?.takeIf { System.currentTimeMillis() - it.time < 2 * 60_000L && it.accuracy < 100f }
        if (reciente != null) primerMensaje(reciente)
        else Ubicacion.obtener(this, 25_000L) { primerMensaje(it) }
        principal.postDelayed(::actualizar, INTERVALO)
        principal.postDelayed({ terminar(manual = false) }, e.hasta - System.currentTimeMillis())
    }

    private fun primerMensaje(l: Location?) {
        val e = estado.value ?: return
        if (e.terminado) return
        guardarUbicacion(l)
        val texto = Mensajes.primero(this, e, l)
        e.avisos.forEachIndexed { i, a -> enviar(i, a.contacto, texto, e.prueba, primero = true) }
        if (l != null) { ultimaEnviada = l; horaUltimoEnvio = System.currentTimeMillis() }
        else {
            // Sin ubicación todavía: se vuelve a intentar enseguida y se manda en cuanto haya una.
            principal.postDelayed({ Ubicacion.obtener(this, 45_000L) { it?.let(::mandarActualizacion) } }, 15_000L)
        }
    }

    private fun actualizar() {
        val e = estado.value ?: return
        if (!e.activo) return
        Ubicacion.obtener(this, 30_000L) { l ->
            if (l != null) {
                val movido = ultimaEnviada?.let { it.distanceTo(l) > DISTANCIA_MINIMA } ?: true
                if (movido || System.currentTimeMillis() - horaUltimoEnvio >= SIEMPRE_CADA - 30_000L) mandarActualizacion(l)
                else guardarUbicacion(l)
            }
            principal.postDelayed(::actualizar, INTERVALO)
        }
    }

    private fun mandarActualizacion(l: Location) {
        val e = estado.value ?: return
        if (!e.activo) return
        guardarUbicacion(l)
        val texto = Mensajes.actualizacion(this, e, l)
        e.avisos.forEachIndexed { i, a -> enviar(i, a.contacto, texto, e.prueba, primero = false) }
        ultimaEnviada = l
        horaUltimoEnvio = System.currentTimeMillis()
        estado.value = estado.value?.copy(actualizaciones = (estado.value?.actualizaciones ?: 0) + 1)
        refrescarAviso()
    }

    private fun guardarUbicacion(l: Location?) {
        l ?: return
        estado.value = estado.value?.copy(lat = l.latitude, lon = l.longitude, precision = l.accuracy.toInt(), horaUbicacion = l.time)
    }

    private fun terminar(manual: Boolean) {
        val e = estado.value
        principal.removeCallbacksAndMessages(null)
        if (e != null && !e.terminado) {
            val texto = Mensajes.final(this, e, manual, ultimaEnviada)
            e.avisos.forEach { enviar(-1, it.contacto, texto, e.prueba, primero = false) }
            estado.value = e.copy(terminado = true)
        }
        if (ubicacionEncendida) Ubicacion.apagar(this)
        ubicacionEncendida = false
        // Un momento para que salgan los últimos SMS antes de soltar el móvil.
        principal.postDelayed({
            despierto?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 4_000L)
    }

    override fun onDestroy() {
        principal.removeCallbacksAndMessages(null)
        receptor?.let { runCatching { unregisterReceiver(it) } }
        receptor = null
        despierto?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ——— SMS ———

    private fun escucharEnvios() {
        if (receptor != null) return
        receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val indice = i.getIntExtra("indice", -1)
                if (indice < 0) return
                marcar(indice, if (resultCode == Activity.RESULT_OK) Envio.ENVIADO else Envio.FALLO)
            }
        }
        registerReceiver(receptor, IntentFilter(ACCION_ENVIADO), RECEIVER_NOT_EXPORTED)
    }

    private fun marcar(indice: Int, envio: Envio) {
        val e = estado.value ?: return
        if (indice !in e.avisos.indices) return
        // Un fallo no tapa un envío bueno anterior (el primer mensaje es el que cuenta).
        val antes = e.avisos[indice].envio
        if (antes == Envio.ENVIADO && envio == Envio.FALLO) return
        estado.value = e.copy(avisos = e.avisos.mapIndexed { j, a -> if (j == indice) a.copy(envio = envio) else a })
    }

    /** Manda un SMS (o lo apunta, en modo de prueba). [indice] ≥ 0: el resultado se ve en la pantalla. */
    private fun enviar(indice: Int, c: Emergencias.Contacto, texto: String, prueba: Boolean, primero: Boolean) {
        if (prueba) {
            Emergencias.apuntar(this, "sms", c.nombre.ifBlank { c.telefono }, texto)
            if (primero) marcar(indice, Envio.SIMULADO)
            return
        }
        if (!Emergencias.puedeSms(this)) {
            if (primero) marcar(indice, Envio.SIN_PERMISO)
            return
        }
        runCatching {
            val sms = getSystemService(SmsManager::class.java)
            val partes = sms.divideMessage(texto)
            val enviados = ArrayList<PendingIntent?>(partes.size)
            repeat(partes.size) { p ->
                // Solo se sigue la última parte: si llega, ha llegado todo.
                enviados += if (primero && p == partes.lastIndex) PendingIntent.getBroadcast(
                    this, codigos.incrementAndGet(), Intent(ACCION_ENVIADO).setPackage(packageName).putExtra("indice", indice),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                ) else null
            }
            sms.sendMultipartTextMessage(c.telefono, null, partes, enviados, null)
        }.onFailure {
            android.util.Log.e("SIOS", "No se pudo mandar el SMS de emergencia", it)
            if (primero) marcar(indice, Envio.FALLO)
        }
    }

    // ——— Aviso ———

    private fun refrescarAviso() {
        val e = estado.value ?: return
        runCatching { getSystemService(NotificationManager::class.java).notify(AVISO, aviso(e)) }
    }

    private fun aviso(e: Estado): android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, getString(R.string.em_canal), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                setBypassDnd(true)
            })
        }
        val hora = hora(this, e.hasta)
        val abrir = PendingIntent.getActivity(
            this, 1, Intent(this, SosActivity::class.java).putExtra(SosActivity.EXTRA_VER, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val parar = PendingIntent.getService(
            this, 2, Intent(this, SosServicio::class.java).setAction(ACCION_PARAR), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (titulo, texto, boton) = when (e.modo) {
            Modo.SOS -> Triple(
                getString(if (e.prueba) R.string.em_aviso_sos_prueba else R.string.em_aviso_sos),
                resources.getQuantityString(R.plurals.em_aviso_sos_texto, e.avisos.size, e.avisos.size, hora),
                getString(R.string.em_parar_sos),
            )
            Modo.VIAJE -> Triple(
                getString(R.string.em_aviso_viaje),
                getString(R.string.em_aviso_viaje_texto, e.avisos.firstOrNull()?.contacto?.nombre.orEmpty(), hora),
                getString(R.string.em_viaje_llegado),
            )
        }
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFFC62828.toInt())
            .setContentIntent(abrir)
            .addAction(0, boton, parar)
            .build()
    }
}

/** Los SMS, en el idioma del móvil. Cortos y claros: los lee alguien con prisa. */
internal object Mensajes {
    /** «SOS de Ana:» (o «SOS:» sin nombre en la ficha); al compartir el viaje, solo el nombre. */
    private fun firma(ctx: Context, e: SosServicio.Estado): String {
        val nombre = Emergencias.ficha(ctx).nombre.trim()
        return when {
            e.modo == SosServicio.Modo.VIAJE -> if (nombre.isBlank()) "" else ctx.getString(R.string.em_sms_firma_viaje, nombre) + " "
            nombre.isBlank() -> ctx.getString(R.string.em_sms_firma) + " "
            else -> ctx.getString(R.string.em_sms_firma_nombre, nombre) + " "
        }
    }

    private fun bateria(ctx: Context): Int =
        runCatching { ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)

    private fun donde(ctx: Context, l: Location?): String =
        if (l == null) ctx.getString(R.string.em_sms_sin_ubicacion)
        else ctx.getString(R.string.em_sms_ubicacion, Ubicacion.enlace(l), l.accuracy.toInt().coerceAtLeast(1))

    private fun conBateria(ctx: Context, texto: String): String {
        val b = bateria(ctx)
        return if (b in 0..100) texto + " " + ctx.getString(R.string.em_sms_bateria, b) else texto
    }

    fun primero(ctx: Context, e: SosServicio.Estado, l: Location?): String {
        val minutos = ((e.hasta - e.desde) / 60_000L).toInt()
        val cuerpo = when {
            e.modo == SosServicio.Modo.VIAJE -> ctx.getString(R.string.em_sms_viaje, SosServicio.duracion(ctx, minutos))
            e.motivo == Emergencias.Motivo.CAIDA -> ctx.getString(R.string.em_sms_caida, Emergencias.numero(ctx))
            else -> ctx.getString(R.string.em_sms_sos, Emergencias.numero(ctx))
        }
        val seguir = if (e.modo == SosServicio.Modo.SOS) " " + ctx.getString(R.string.em_sms_seguire, SosServicio.duracion(ctx, minutos)) else ""
        return conBateria(ctx, "${firma(ctx, e)}$cuerpo ${donde(ctx, l)}$seguir")
    }

    fun actualizacion(ctx: Context, e: SosServicio.Estado, l: Location): String {
        val hora = hora(ctx, l.time)
        return conBateria(ctx, firma(ctx, e) + ctx.getString(R.string.em_sms_actualizacion, hora) + " " + donde(ctx, l))
    }

    fun final(ctx: Context, e: SosServicio.Estado, manual: Boolean, l: Location?): String {
        val cuerpo = when {
            e.modo == SosServicio.Modo.VIAJE && manual -> ctx.getString(R.string.em_sms_viaje_llegado)
            e.modo == SosServicio.Modo.VIAJE -> ctx.getString(R.string.em_sms_viaje_fin)
            manual -> ctx.getString(R.string.em_sms_parado)
            else -> ctx.getString(R.string.em_sms_fin)
        }
        val ultima = if (!manual && l != null) " " + ctx.getString(R.string.em_sms_ultima, Ubicacion.enlace(l)) else ""
        return "${firma(ctx, e)}$cuerpo$ultima"
    }
}
