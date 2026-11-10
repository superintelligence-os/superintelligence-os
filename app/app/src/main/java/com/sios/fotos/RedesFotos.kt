package com.sios.fotos

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Las entradas y salidas de las dos redes, en Kotlin puro (sin Android): así se prueban igual en el
 * ordenador que en el móvil.
 *
 *  - Sujeto (IS-Net, DIS «general use»): imagen 1024×1024, RGB/255 − 0,5 (CHW); devuelve la
 *    probabilidad de sujeto por píxel (0 a 1).
 *  - Borrador (MI-GAN, «pipeline v2»): imagen RGB en bytes (CHW, cualquier tamaño) y máscara de
 *    bytes donde 0 es lo que se borra y 255 lo que se queda; devuelve la imagen entera ya rellenada.
 */
object RedesFotos {
    const val LADO_SUJETO = 1024

    /** Píxeles ARGB (ya escalados a lado×lado) → tensor de IS-Net. */
    fun entradaSujeto(px: IntArray): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(3 * px.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (canal in 0 until 3) {
            val desplaza = 16 - canal * 8
            for (p in px) fb.put(((p shr desplaza) and 0xFF) / 255f - 0.5f)
        }
        fb.rewind()
        return fb
    }

    /**
     * Salida de IS-Net → alfa de 0 a 255. null si no hay un sujeto claro (nada pasa de la mitad):
     * así no se «inventa» un recorte con ruido.
     */
    fun mascaraSujeto(salida: FloatArray): ByteArray? {
        var mn = Float.MAX_VALUE
        var mx = -Float.MAX_VALUE
        for (v in salida) { if (v < mn) mn = v; if (v > mx) mx = v }
        if (mx < 0.5f) return null
        val rango = (mx - mn).coerceAtLeast(1e-6f)
        var dentro = 0
        val a = ByteArray(salida.size) { i ->
            val v = ((salida[i] - mn) / rango).coerceIn(0f, 1f)
            if (v > 0.5f) dentro++
            (v * 255f + 0.5f).toInt().toByte()
        }
        // Un puñado de píxeles sueltos no es un sujeto.
        if (dentro < salida.size / 400) return null
        return a
    }

    /** Píxeles ARGB → bytes RGB en planos (CHW) para MI-GAN. */
    fun entradaBorrado(px: IntArray): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(3 * px.size)
        for (canal in 0 until 3) {
            val desplaza = 16 - canal * 8
            for (p in px) bb.put(((p shr desplaza) and 0xFF).toByte())
        }
        bb.rewind()
        return bb
    }

    /** Lo pintado (alfa > 0: borrar) → máscara de MI-GAN (0 borrar, 255 dejar). */
    fun mascaraBorrado(pintado: ByteArray): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(pintado.size)
        for (v in pintado) bb.put(if ((v.toInt() and 0xFF) > 24) 0 else 255.toByte())
        bb.rewind()
        return bb
    }

    /** Salida de MI-GAN (RGB en planos) → píxeles ARGB opacos. */
    fun salidaBorrado(b: ByteBuffer, n: Int): IntArray = IntArray(n) { i ->
        val r = b.get(i).toInt() and 0xFF
        val g = b.get(n + i).toInt() and 0xFF
        val a = b.get(2 * n + i).toInt() and 0xFF
        (0xFF shl 24) or (r shl 16) or (g shl 8) or a
    }

    /**
     * Desenfoque de caja en tres pasadas (casi gaussiano), sobre ARGB, en el sitio. Para el fondo
     * desenfocado, que se calcula en pequeño y se estira con filtrado.
     */
    fun desenfocar(px: IntArray, w: Int, h: Int, radio: Int) {
        val tmp = IntArray(px.size)
        repeat(3) {
            caja(px, tmp, w, h, radio, horizontal = true)
            caja(tmp, px, w, h, radio, horizontal = false)
        }
    }

    private fun caja(origen: IntArray, destino: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val largo = if (horizontal) w else h
        val lineas = if (horizontal) h else w
        val ventana = 2 * r + 1
        for (l in 0 until lineas) {
            var sr = 0; var sg = 0; var sb = 0
            fun idx(i: Int): Int { val c = i.coerceIn(0, largo - 1); return if (horizontal) l * w + c else c * w + l }
            for (i in -r..r) { val p = origen[idx(i)]; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
            for (i in 0 until largo) {
                destino[idx(i)] = (0xFF shl 24) or ((sr / ventana) shl 16) or ((sg / ventana) shl 8) or (sb / ventana)
                val sale = origen[idx(i - r)]
                val entra = origen[idx(i + r + 1)]
                sr += ((entra shr 16) and 0xFF) - ((sale shr 16) and 0xFF)
                sg += ((entra shr 8) and 0xFF) - ((sale shr 8) and 0xFF)
                sb += (entra and 0xFF) - (sale and 0xFF)
            }
        }
    }
}
