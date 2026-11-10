package com.sios.fotos

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Los modelos de IA del editor de fotos. No van en el APK: se descargan la primera vez que se usa
 * su herramienta, de una revisión fija de Hugging Face, comprobando el SHA-256 (el de su almacén
 * LFS), y después funcionan sin conexión. Se ejecutan con ONNX Runtime en el propio móvil: la foto
 * no sale de él.
 *
 *  - Sujeto: IS-Net («DIS general use», Apache 2.0), 179 MB. Buen recorte de personas, animales y
 *    objetos con sus bordes. Probado frente a U²-Netp (4,6 MB, mucho peor en bordes y sin sujeto
 *    claro) y BiRefNet lite (MIT, mejor aún pero 4-5 veces más lento en CPU y 224 MB).
 *  - Borrador: MI-GAN de Picsart («pipeline v2», MIT), 28 MB. Hecho para móviles: recorta solo la
 *    zona que se borra, así que tarda lo mismo con fotos de 12 MP.
 */
class ModeloFotos(
    val id: String,
    private val nombreArchivo: String,
    private val url: String,
    /** Lo que ocupa (se enseña antes de descargarlo). */
    val bytes: Long,
    private val sha256: String,
) {
    sealed interface Descarga {
        data object Parada : Descarga
        data class EnCurso(val hecho: Long, val total: Long) : Descarga
        data object Comprobando : Descarga
        data object Lista : Descarga
        data class Fallo(val sinRed: Boolean) : Descarga
    }

    private val _descarga = MutableStateFlow<Descarga>(Descarga.Parada)
    val descarga: StateFlow<Descarga> = _descarga

    @Volatile private var hilo: Thread? = null

    private fun carpeta(ctx: Context) = File(ctx.noBackupFilesDir, "fotos/$id").apply { mkdirs() }
    private fun archivo(ctx: Context) = File(carpeta(ctx), nombreArchivo)

    fun listo(ctx: Context): Boolean = archivo(ctx).length() == bytes

    /** Pone el estado a «Lista» si ya estaba descargado (al abrir la herramienta). */
    fun comprobar(ctx: Context) {
        if (listo(ctx)) _descarga.value = Descarga.Lista
        else if (_descarga.value == Descarga.Lista) _descarga.value = Descarga.Parada
    }

    @Synchronized
    fun descargar(ctx: Context) {
        if (hilo?.isAlive == true) return
        val app = ctx.applicationContext
        if (listo(app)) { _descarga.value = Descarga.Lista; return }
        _descarga.value = Descarga.EnCurso(0, bytes)
        hilo = Thread {
            val ok = runCatching { bajar(app) }.onFailure { android.util.Log.w("SistemaSios", "Fotos: descarga de $id", it) }.isSuccess
            _descarga.value = if (ok) Descarga.Lista else if (Thread.currentThread().isInterrupted) Descarga.Parada else Descarga.Fallo(sinRed = !hayRed(app))
        }.apply { name = "fotos-$id"; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    fun cancelar() {
        hilo?.interrupt()
        hilo = null
        _descarga.value = Descarga.Parada
    }

    fun borrar(ctx: Context) {
        cancelar()
        liberar()
        carpeta(ctx).deleteRecursively()
        _descarga.value = Descarga.Parada
    }

    private fun bajar(ctx: Context) {
        val destino = archivo(ctx)
        val parte = File(carpeta(ctx), "$nombreArchivo.parte")
        var ultimoAviso = 0L
        // Reanuda donde se quedó (Range); si el servidor no reanuda, empieza de cero.
        while (parte.length() < bytes) {
            if (Thread.interrupted()) throw InterruptedException()
            val desde = parte.length()
            val peticion = Request.Builder().url(url).apply { if (desde > 0) header("Range", "bytes=$desde-") }.build()
            http.newCall(peticion).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                RandomAccessFile(parte, "rw").use { f ->
                    if (r.code != 206) f.setLength(0) else f.seek(desde)
                    val entrada = r.body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.interrupted()) throw InterruptedException()
                        val n = entrada.read(buf)
                        if (n < 0) break
                        f.write(buf, 0, n)
                        val ahora = SystemClock.uptimeMillis()
                        if (ahora - ultimoAviso > 120) { ultimoAviso = ahora; _descarga.value = Descarga.EnCurso(f.filePointer, bytes) }
                    }
                }
            }
        }
        _descarga.value = Descarga.Comprobando
        if (parte.length() != bytes || resumen(parte) != sha256) {
            parte.delete()
            error("$nombreArchivo no coincide")
        }
        parte.renameTo(destino)
    }

    private fun resumen(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { e ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = e.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hayRed(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    // ---------- Sesión de ONNX Runtime ----------

    private var sesion: OrtSession? = null

    @Synchronized
    fun sesion(ctx: Context): OrtSession? {
        if (!listo(ctx)) return null
        return sesion ?: ModelosFotos.entorno.createSession(archivo(ctx).path, OrtSession.SessionOptions().apply {
            // Cuatro hilos: es una acción que el usuario espera mirando la pantalla.
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }).also { sesion = it }
    }

    /** Suelta el modelo de la memoria (al cerrar el editor). */
    @Synchronized
    fun liberar() {
        runCatching { sesion?.close() }
        sesion = null
    }

    companion object {
        private val http by lazy {
            OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
        }
    }
}

object ModelosFotos {
    internal val entorno: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    val SUJETO = ModeloFotos(
        "sujeto", "isnet.onnx",
        "https://huggingface.co/BritishWerewolf/IS-Net/resolve/9783722d9f964c0286a411e7e8e6fede947d5a53/onnx/model.onnx",
        178_648_008, "60920e99c45464f2ba57bee2ad08c919a52bbf852739e96947fbb4358c0d964a",
    )

    val BORRADOR = ModeloFotos(
        "borrador", "migan.onnx",
        "https://huggingface.co/andraniksargsyan/migan/resolve/406830d0fa60666da0071c342ad2fbc8f30c5c64/migan_pipeline_v2.onnx",
        28_079_181, "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b",
    )

    /** ¿Con datos móviles (o una red de pago)? Para avisar antes de descargar. */
    fun redDePago(ctx: Context): Boolean = ctx.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered

    fun liberar() { SUJETO.liberar(); BORRADOR.liberar() }

    /**
     * El sujeto de la foto: una máscara (ALPHA_8, 1024×1024, se estira a la foto con filtrado).
     * null si no hay modelo o no hay un sujeto claro.
     */
    fun sujeto(ctx: Context, foto: Bitmap): Bitmap? {
        val s = SUJETO.sesion(ctx) ?: return null
        val lado = RedesFotos.LADO_SUJETO
        val pequena = Bitmap.createScaledBitmap(foto, lado, lado, true)
        val px = IntArray(lado * lado).also { pequena.getPixels(it, 0, lado, 0, 0, lado, lado) }
        if (pequena !== foto) pequena.recycle()
        val salida = OnnxTensor.createTensor(entorno, RedesFotos.entradaSujeto(px), longArrayOf(1, 3, lado.toLong(), lado.toLong())).use { t ->
            s.run(mapOf(s.inputNames.first() to t)).use { r ->
                // «output_image» es la salida final; las demás son intermedias.
                val nombre = s.outputNames.firstOrNull { it == "output_image" } ?: s.outputNames.first()
                val fb = (r.get(nombre).get() as OnnxTensor).floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
        val alfa = RedesFotos.mascaraSujeto(salida) ?: return null
        return Bitmap.createBitmap(lado, lado, Bitmap.Config.ALPHA_8).apply { copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(alfa)) }
    }

    /**
     * Borra lo pintado en una zona de la foto. zona: ARGB de la región; pintado: alfa de la misma
     * región (más de 0: borrar). Devuelve la región ya rellenada (o null sin modelo).
     */
    fun borrar(ctx: Context, zona: Bitmap, pintado: ByteArray): Bitmap? {
        val s = BORRADOR.sesion(ctx) ?: return null
        val w = zona.width
        val h = zona.height
        val px = IntArray(w * h).also { zona.getPixels(it, 0, w, 0, 0, w, h) }
        val forma = longArrayOf(1, 3, h.toLong(), w.toLong())
        val resultado = OnnxTensor.createTensor(entorno, RedesFotos.entradaBorrado(px), forma, OnnxJavaType.UINT8).use { imagen ->
            OnnxTensor.createTensor(entorno, RedesFotos.mascaraBorrado(pintado), longArrayOf(1, 1, h.toLong(), w.toLong()), OnnxJavaType.UINT8).use { mascara ->
                s.run(mapOf("image" to imagen, "mask" to mascara)).use { r ->
                    RedesFotos.salidaBorrado((r.get(0) as OnnxTensor).byteBuffer, w * h)
                }
            }
        }
        return Bitmap.createBitmap(resultado, w, h, Bitmap.Config.ARGB_8888)
    }
}
