package com.sios.voz

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sios.voz.motor.Marian
import com.sios.voz.motor.Whisper
import java.io.File

/**
 * Los motores cargados en memoria (Whisper ocupa unos 400 MB mientras está cargado; cada dirección de
 * traducción, unos 150 MB). Se cargan la primera vez que hacen falta y se sueltan solos tras unos
 * minutos sin usarse, para no quitarle memoria al resto del móvil.
 */
internal object MotoresLocales {
    private const val OCIOSO_MS = 3 * 60_000L

    private var whisper: Whisper? = null
    private val vosk = java.util.concurrent.ConcurrentHashMap<String, org.vosk.Model>()
    // Como mucho dos direcciones de traducción a la vez (las dos de una conversación, o las de un pivote).
    private val marian = LinkedHashMap<String, Marian>()
    @Volatile private var ultimoUso = 0L
    @Volatile private var enUso = 0
    private val principal = Handler(Looper.getMainLooper())

    private val hilos get() = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    @Synchronized
    fun whisper(ctx: Context): Whisper? {
        tocar()
        whisper?.let { return it }
        val m = ModelosVoz.WHISPER_SMALL
        if (!ModelosVoz.listo(ctx, m)) return null
        return runCatching { Whisper(ModelosVoz.carpeta(ctx, m), hilos) }
            .onFailure { android.util.Log.w("SistemaSios", "Whisper", it) }.getOrNull()?.also { whisper = it }
    }

    @Synchronized
    fun vosk(ctx: Context, idioma: String): org.vosk.Model? {
        tocar()
        vosk[idioma]?.let { return it }
        val m = ModelosVoz.ligero(idioma) ?: return null
        if (!ModelosVoz.listo(ctx, m)) return null
        return runCatching { org.vosk.Model(ModelosVoz.carpeta(ctx, m).path) }
            .onFailure { android.util.Log.w("SistemaSios", "Vosk", it) }.getOrNull()?.also { vosk[idioma] = it }
    }

    /** El de Vosk si ya está en memoria, sin esperar a cargarlo. */
    fun voskCargado(idioma: String): org.vosk.Model? = vosk[idioma]

    /** Una dirección de traducción («es-en», «en-ROMANCE»…) del paquete [m]. */
    @Synchronized
    fun marian(ctx: Context, m: ModelosVoz.Modelo, direccion: String): Marian? {
        tocar()
        marian.remove(direccion)?.let { marian[direccion] = it; return it }
        if (!ModelosVoz.listo(ctx, m)) return null
        val dir = File(ModelosVoz.carpeta(ctx, m), direccion)
        val nuevo = runCatching { Marian(dir, 2) }.onFailure { android.util.Log.w("SistemaSios", "Marian $direccion", it) }.getOrNull() ?: return null
        while (marian.size >= 2) marian.remove(marian.keys.first())?.close()
        marian[direccion] = nuevo
        return nuevo
    }

    /** Mientras se está usando un motor no se suelta (un dictado largo, una traducción). */
    fun <T> usando(bloque: () -> T): T {
        synchronized(this) { enUso++ }
        try { return bloque() } finally { synchronized(this) { enUso--; tocar() } }
    }

    private fun tocar() {
        ultimoUso = SystemClock.uptimeMillis()
        principal.removeCallbacks(revisar)
        principal.postDelayed(revisar, OCIOSO_MS + 1000)
    }

    private val revisar = Runnable {
        Thread {
            synchronized(this) {
                if (enUso == 0 && SystemClock.uptimeMillis() - ultimoUso >= OCIOSO_MS) soltarTodo()
            }
        }.start()
    }

    @Synchronized
    fun soltarTodo() {
        runCatching { whisper?.close() }
        whisper = null
        vosk.values.forEach { runCatching { it.close() } }
        vosk.clear()
        marian.values.forEach { runCatching { it.close() } }
        marian.clear()
    }

    /** Antes de borrar un modelo: si estaba cargado, fuera. */
    @Synchronized
    fun soltar(m: ModelosVoz.Modelo) {
        when (m.clase) {
            ModelosVoz.Clase.DICTADO_PRECISO -> { runCatching { whisper?.close() }; whisper = null }
            ModelosVoz.Clase.DICTADO_LIGERO -> vosk.remove(m.idioma)?.let { runCatching { it.close() } }
            ModelosVoz.Clase.TRADUCCION -> {
                val dirs = m.archivos.map { it.ruta.substringBefore('/') }.toSet()
                dirs.forEach { d -> marian.remove(d)?.let { runCatching { it.close() } } }
            }
        }
    }
}
