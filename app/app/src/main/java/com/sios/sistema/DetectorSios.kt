package com.sios.sistema

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * Detector acústico de «SIOS» (openWakeWord, entrenado en rom/oye; modelos en assets/oye, ~2,5 MB).
 * Reconoce el sonido de la palabra, no el texto: funciona igual en cualquier idioma y no necesita
 * descargar nada. Por cada bloque de 80 ms: espectrograma mel → embedding → clasificador.
 * Nada sale del móvil.
 */
class DetectorSios(ctx: Context) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val opciones = OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) }
    private fun sesion(ctx: Context, nombre: String) = env.createSession(ctx.assets.open("oye/$nombre").use { it.readBytes() }, opciones)
    private val mel = sesion(ctx, "melspectrogram.onnx")
    private val emb = sesion(ctx, "embedding_model.onnx")
    private val cls = sesion(ctx, "sios.onnx")
    private val umbral = umbral(ctx)

    private val anterior = FloatArray(480)
    private val tramas = ArrayDeque<FloatArray>()
    private val embeddings = ArrayDeque<FloatArray>()
    private var espera = 0

    /** Vacía lo oído (al reanudar tras hablar con SIOS, para no volver a dispararse con lo de antes). */
    fun reiniciar() {
        anterior.fill(0f); tramas.clear(); embeddings.clear(); espera = 0
    }

    /** Un bloque de 1280 muestras (80 ms, 16 kHz). true si acaba de oír «SIOS». */
    @Suppress("UNCHECKED_CAST")
    fun procesar(pcm: ShortArray): Boolean {
        val x = FloatArray(1760)
        System.arraycopy(anterior, 0, x, 0, 480)
        for (i in 0 until BLOQUE) x[480 + i] = pcm[i].toFloat() // sin normalizar, como openWakeWord
        System.arraycopy(x, BLOQUE, anterior, 0, 480)

        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 1760)).use { t ->
            mel.run(mapOf("input" to t)).use { r ->
                for (fila in (r[0].value as Array<Array<Array<FloatArray>>>)[0][0]) tramas.addLast(FloatArray(32) { fila[it] / 10f + 2f })
            }
        }
        while (tramas.size > 76) tramas.removeFirst()
        if (tramas.size < 76) return false

        val w = FloatArray(76 * 32).also { var k = 0; for (f in tramas) for (v in f) it[k++] = v }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(w), longArrayOf(1, 76, 32, 1)).use { t ->
            emb.run(mapOf("input_1" to t)).use { r -> embeddings.addLast((r[0].value as Array<Array<Array<FloatArray>>>)[0][0][0].copyOf()) }
        }
        while (embeddings.size > 16) embeddings.removeFirst()
        if (embeddings.size < 16) return false

        val f = FloatArray(16 * 96).also { var k = 0; for (e in embeddings) for (v in e) it[k++] = v }
        val nota = OnnxTensor.createTensor(env, FloatBuffer.wrap(f), longArrayOf(1, 16, 96)).use { t ->
            cls.run(mapOf("input" to t)).use { r -> (r[0].value as Array<FloatArray>)[0][0] }
        }
        if (espera > 0) { espera--; return false }
        if (nota >= umbral) { espera = 25; return true } // 2 s sin volver a dispararse
        return false
    }

    override fun close() {
        runCatching { mel.close(); emb.close(); cls.close() }
    }

    /**
     * Escucha el micrófono en un hilo propio hasta que se para; llama a [alOir] (en ese hilo) al oír «SIOS».
     * Devuelve la función para pararla.
     */
    @SuppressLint("MissingPermission")
    fun escuchar(alOir: () -> Unit, alFallar: (Throwable) -> Unit): () -> Unit {
        val activo = java.util.concurrent.atomic.AtomicBoolean(true)
        val hilo = Thread {
            val minimo = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val mic = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimo, BLOQUE * 4))
            }.getOrElse { alFallar(it); return@Thread }
            try {
                mic.startRecording()
                reiniciar()
                val bloque = ShortArray(BLOQUE)
                while (activo.get()) {
                    var leidas = 0
                    while (leidas < BLOQUE && activo.get()) {
                        val n = mic.read(bloque, leidas, BLOQUE - leidas)
                        if (n < 0) throw IllegalStateException("micrófono: $n")
                        leidas += n
                    }
                    if (activo.get() && procesar(bloque)) alOir()
                }
            } catch (e: Throwable) {
                if (activo.get()) alFallar(e)
            } finally {
                runCatching { mic.stop() }
                mic.release()
            }
        }.apply { name = "oye-sios"; priority = Thread.NORM_PRIORITY - 1; start() }
        return { activo.set(false); hilo.interrupt() }
    }

    companion object {
        const val BLOQUE = 1280

        /** Si la app trae el modelo de «SIOS» (si no, se usa el reconocedor de voz con la comparación de texto). */
        fun disponible(ctx: Context) = runCatching { ctx.assets.list("oye")?.contains("sios.onnx") == true }.getOrDefault(false)

        private fun umbral(ctx: Context) =
            runCatching { ctx.assets.open("oye/umbral.txt").bufferedReader().use { it.readText().trim().toFloat() } }.getOrDefault(0.5f)
    }
}
