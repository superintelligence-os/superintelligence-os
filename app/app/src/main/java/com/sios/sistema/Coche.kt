package com.sios.sistema

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import com.sios.coche.CocheActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Modo coche: se enciende solo al conectar el Bluetooth del coche (el que se marque en Ajustes) o
 * a mano. Mientras está activo, los mensajes que llegan se leen en voz alta y la pantalla de
 * conducción enseña lo justo: ir a casa o al trabajo, la música, llamar y hablar con Claude.
 */
object Coche {
    private fun p(ctx: Context) = ctx.getSharedPreferences("coche", Context.MODE_PRIVATE)

    private val _activo = MutableStateFlow(false)
    val activo: StateFlow<Boolean> = _activo

    /** El nombre del coche conectado ahora (si se activó por Bluetooth). */
    var conectado: String? = null
        private set

    fun coches(ctx: Context): Set<String> = p(ctx).getStringSet("coches", emptySet()) ?: emptySet()
    fun ponerCoche(ctx: Context, nombre: String, si: Boolean) =
        p(ctx).edit().putStringSet("coches", coches(ctx).toMutableSet().apply { if (si) add(nombre) else remove(nombre) }).apply()

    fun casa(ctx: Context) = p(ctx).getString("casa", "").orEmpty()
    fun trabajo(ctx: Context) = p(ctx).getString("trabajo", "").orEmpty()
    fun ponerDireccion(ctx: Context, clave: String, valor: String) = p(ctx).edit().putString(clave, valor.trim()).apply()

    fun leer(ctx: Context) = p(ctx).getBoolean("leer", true)
    fun ponerLeer(ctx: Context, si: Boolean) = p(ctx).edit().putBoolean("leer", si).apply()

    fun automatico(ctx: Context) = p(ctx).getBoolean("automatico", true)

    fun responderConduciendo(ctx: Context) = p(ctx).getBoolean("responder", true)
    fun ponerResponder(ctx: Context, si: Boolean) = p(ctx).edit().putBoolean("responder", si).apply()

    /** Conversaciones a las que ya se ha dicho «voy conduciendo» en este viaje (una vez cada una). */
    private val contestadas = HashSet<String>()
    fun ponerAutomatico(ctx: Context, si: Boolean) = p(ctx).edit().putBoolean("automatico", si).apply()

    /** Dispositivos Bluetooth emparejados (para elegir cuál es el coche). */
    @SuppressLint("MissingPermission")
    fun emparejados(ctx: Context): List<String> = runCatching {
        ctx.getSystemService(BluetoothManager::class.java).adapter.bondedDevices.mapNotNull { it.alias ?: it.name }.distinct().sorted()
    }.getOrDefault(emptyList())

    fun abrir(ctx: Context) {
        _activo.value = true
        ctx.startActivity(Intent(ctx, CocheActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    fun salir() {
        _activo.value = false
        synchronized(contestadas) { contestadas.clear() }
        conectado = null
        Voz.callar()
    }

    /** Desde Disparadores: un dispositivo Bluetooth se ha conectado o desconectado. */
    fun bluetooth(ctx: Context, nombre: String, siConectado: Boolean) {
        if (nombre !in coches(ctx) || !automatico(ctx)) return
        if (siConectado) {
            conectado = nombre
            abrir(ctx)
        } else if (conectado == nombre) {
            salir()
        }
    }

    /** Desde la Bandeja: un mensaje nuevo; con el modo coche activo, en voz alta. */
    fun alMensaje(ctx: Context, aviso: Bandeja.Aviso) {
        if (!_activo.value || !aviso.esMensaje || aviso.paquete == ctx.packageName) return
        val de = aviso.titulo.ifBlank { aviso.app }
        if (leer(ctx)) Voz.decir(ctx, ctx.getString(com.sios.R.string.coche_mensaje_de, de, aviso.app) + ". " + aviso.texto.take(400), enCola = true)
        // «Voy conduciendo»: una vez por conversación y viaje, solo a personas (con respuesta rápida).
        if (responderConduciendo(ctx) && aviso.responder != null && synchronized(contestadas) { contestadas.add(aviso.conversacion) }) {
            val b = Bandeja.instancia ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                runCatching { b.responder(aviso.clave, ctx.getString(com.sios.R.string.coche_respuesta)) }
            }, 1500)
        }
    }

    // ---------- Música (la sesión multimedia que esté sonando) ----------

    class Sonando(val titulo: String, val artista: String, val app: String, val reproduciendo: Boolean)

    private fun controlador(ctx: Context): MediaController? = runCatching {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        val sesiones = msm.getActiveSessions(ComponentName(ctx, Bandeja::class.java))
        sesiones.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sesiones.firstOrNull()
    }.getOrNull()

    fun sonando(ctx: Context): Sonando? {
        val c = controlador(ctx) ?: return null
        val m = c.metadata
        val titulo = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (titulo.isBlank()) return null
        val app = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString() }.getOrDefault("")
        return Sonando(
            titulo, m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(), app,
            c.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    fun multimedia(ctx: Context, que: String) {
        val c = controlador(ctx)
        if (c == null) {
            // Nada sonando: el botón de reproducir del sistema despierta la última app de música.
            if (que == "play") ctx.getSystemService(android.media.AudioManager::class.java).apply {
                dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
            }
            return
        }
        val t = c.transportControls
        when (que) {
            "play" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "siguiente" -> t.skipToNext()
            "anterior" -> t.skipToPrevious()
        }
    }

    // ---------- Favoritos para llamar ----------

    class Favorito(val nombre: String, val numero: String, val foto: Uri?)

    /** Los contactos con estrella; si hay pocos, se completan con los más llamados últimamente. */
    fun favoritos(ctx: Context, cuantos: Int = 4): List<Favorito> {
        val lista = mutableListOf<Favorito>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI),
                "${ContactsContract.CommonDataKinds.Phone.STARRED}=1", null, "${ContactsContract.CommonDataKinds.Phone.TIMES_CONTACTED} DESC",
            )?.use { c ->
                while (c.moveToNext() && lista.size < cuantos) {
                    val nombre = c.getString(0) ?: continue
                    if (lista.none { it.nombre == nombre }) lista += Favorito(nombre, c.getString(1).orEmpty(), c.getString(2)?.let(Uri::parse))
                }
            }
        }
        if (lista.size < cuantos) runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.CACHED_PHOTO_URI),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                var vistas = 0
                while (c.moveToNext() && lista.size < cuantos && vistas++ < 80) {
                    val nombre = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    if (lista.none { it.nombre == nombre }) lista += Favorito(nombre, c.getString(1).orEmpty(), c.getString(2)?.let(Uri::parse))
                }
            }
        }
        return lista
    }
}
