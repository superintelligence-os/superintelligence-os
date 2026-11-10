package com.sios.sistema

import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Leer el texto de una imagen, en el móvil y sin conexión: lo usan la memoria del móvil, las
 * capturas inteligentes, la memoria de pantalla y el escáner de documentos. Es PP-OCRv5 de
 * PaddleOCR (Apache 2.0) sobre ONNX Runtime (ver [OcrMotor]); los modelos (unos 13 MB) van dentro
 * del APK, así que funciona desde el primer momento, también en la edición sin Google.
 *
 * Se carga al primer uso y se suelta tras un par de minutos sin leer nada.
 */
object Ocr {
    /** Una línea leída y dónde está, en píxeles de la imagen. */
    class Linea(val texto: String, val confianza: Float, val caja: android.graphics.RectF, val esquinas: FloatArray)

    private const val DIR = "ocr"
    private const val OCIOSO_MS = 120_000L

    private var motor: OcrMotor? = null
    @Volatile private var ultimoUso = 0L
    private val manejador by lazy { Handler(Looper.getMainLooper()) }
    // Fuera del hilo principal: si justo está leyendo, esperar al candado allí bloquearía la interfaz.
    private val soltar = Runnable { Thread({ liberarSiOcioso() }, "ocr-soltar").start() }

    /** Todo el texto de la imagen, un renglón por línea («» si no hay o falla). */
    fun leer(ctx: Context, imagen: Bitmap, ladoMax: Int = 1600): String =
        runCatching { OcrMotor.texto(leerMotor(ctx, imagen, ladoMax)) }
            .onFailure { android.util.Log.w("SistemaSios", "OCR", it) }.getOrDefault("")

    /** Las líneas, con su posición, en orden de lectura. */
    fun lineas(ctx: Context, imagen: Bitmap, ladoMax: Int = 1600): List<Linea> =
        runCatching {
            leerMotor(ctx, imagen, ladoMax).map { l ->
                Linea(l.texto, l.confianza, android.graphics.RectF(l.izquierda, l.arriba, l.derecha, l.abajo), l.esquinas)
            }
        }.onFailure { android.util.Log.w("SistemaSios", "OCR", it) }.getOrDefault(emptyList())

    /** El texto de una imagen guardada (galería, captura…), reducida a un tamaño razonable. */
    fun leer(ctx: Context, uri: android.net.Uri, ladoMax: Int = 1600): String = runCatching {
        val b = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            val lado = maxOf(info.size.width, info.size.height)
            if (lado > 2 * ladoMax) dec.setTargetSampleSize(lado / (2 * ladoMax))
            dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        leer(ctx, b, ladoMax).also { b.recycle() }
    }.onFailure { android.util.Log.w("SistemaSios", "OCR: $uri", it) }.getOrDefault("")

    @Synchronized
    private fun leerMotor(ctx: Context, imagen: Bitmap, ladoMax: Int): List<OcrMotor.Linea> {
        val m = motor ?: cargar(ctx).also { motor = it }
        ultimoUso = SystemClock.uptimeMillis()
        try {
            return m.leer(pixeles(imagen), ladoMax)
        } finally {
            ultimoUso = SystemClock.uptimeMillis()
            manejador.removeCallbacks(soltar)
            manejador.postDelayed(soltar, OCIOSO_MS + 1_000)
        }
    }

    private fun cargar(ctx: Context): OcrMotor {
        val a = ctx.applicationContext.assets
        val det = a.open("$DIR/deteccion.onnx").use { it.readBytes() }
        val rec = a.open("$DIR/reconocimiento.onnx").use { it.readBytes() }
        val dic = a.open("$DIR/latin.txt").bufferedReader(Charsets.UTF_8).use { r -> r.readLines().let { if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it } }
        return OcrMotor(OrtEnvironment.getEnvironment(), det, rec, dic, hilos = 2)
    }

    @Synchronized
    private fun liberarSiOcioso() {
        if (SystemClock.uptimeMillis() - ultimoUso < OCIOSO_MS) { manejador.postDelayed(soltar, OCIOSO_MS); return }
        motor?.close()
        motor = null
    }

    /** Píxeles ARGB; con transparencia (PNG), sobre blanco: si no, el texto negro se perdería en negro. */
    private fun pixeles(b: Bitmap): Pixeles {
        val fuente = when {
            b.config == Bitmap.Config.HARDWARE -> b.copy(Bitmap.Config.ARGB_8888, false)
            b.hasAlpha() -> Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) } }
            else -> b
        }
        val px = IntArray(fuente.width * fuente.height)
        fuente.getPixels(px, 0, fuente.width, 0, 0, fuente.width, fuente.height)
        if (fuente !== b) fuente.recycle()
        return Pixeles(b.width, b.height, px)
    }
}
