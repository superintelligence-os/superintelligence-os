package com.sios.sistema

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Una imagen en memoria: píxeles ARGB empaquetados, fila a fila (como `Bitmap.getPixels`). */
class Pixeles(val ancho: Int, val alto: Int, val px: IntArray)

/**
 * El lector de texto de SIOS, sin nada de Android (así se prueba igual en el Mac): PP-OCRv5 de
 * PaddleOCR (Apache 2.0) sobre ONNX Runtime, en dos pasos:
 *  1. Detección (PP-OCRv5_mobile_det, DBNet): un mapa de «aquí hay texto»; cada mancha es una línea.
 *  2. Reconocimiento (latin_PP-OCRv5_mobile_rec, CTC): cada línea, enderezada a 48 px de alto, se
 *     lee letra a letra con un diccionario de alfabeto latino (es, en, fr, de, it, pt y 26 más).
 * Todo en el propio dispositivo.
 */
class OcrMotor(
    private val entorno: OrtEnvironment,
    detector: ByteArray,
    reconocedor: ByteArray,
    /** Los caracteres del reconocedor, en orden (sin el «blanco» del CTC ni el espacio final). */
    diccionario: List<String>,
    hilos: Int = 2,
) : AutoCloseable {
    /** Una línea leída. `esquinas`: arriba-izquierda, arriba-derecha, abajo-derecha y abajo-izquierda (x, y). */
    class Linea(val texto: String, val confianza: Float, val esquinas: FloatArray) {
        val izquierda get() = min(min(esquinas[0], esquinas[2]), min(esquinas[4], esquinas[6]))
        val derecha get() = max(max(esquinas[0], esquinas[2]), max(esquinas[4], esquinas[6]))
        val arriba get() = min(min(esquinas[1], esquinas[3]), min(esquinas[5], esquinas[7]))
        val abajo get() = max(max(esquinas[1], esquinas[3]), max(esquinas[5], esquinas[7]))
        val alto get() = abajo - arriba
        override fun toString() = texto
    }

    private val opciones = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(hilos)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // Sin avisos de la conversión de Paddle a ONNX (inofensivos) en el registro.
        setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR)
    }
    private val det: OrtSession = entorno.createSession(detector, opciones)
    private val rec: OrtSession = entorno.createSession(reconocedor, opciones)
    // CTC: el 0 es el «blanco»; después el diccionario y, al final, el espacio.
    private val caracteres: Array<String> = (listOf("") + diccionario + listOf(" ")).toTypedArray()

    override fun close() {
        runCatching { det.close() }
        runCatching { rec.close() }
        runCatching { opciones.close() }
    }

    /**
     * Lee todo el texto de la imagen. `ladoMax`: el lado largo con el que se buscan las líneas (más,
     * más letra pequeña y más lento). Las líneas vuelven en orden de lectura.
     */
    fun leer(img: Pixeles, ladoMax: Int = 1600, confianzaMinima: Float = 0.5f): List<Linea> {
        if (img.ancho < 8 || img.alto < 8) return emptyList()
        val cajas = detectar(img, ladoMax)
        if (cajas.isEmpty()) return emptyList()
        val textos = reconocer(img, cajas)
        return cajas.indices.mapNotNull { i ->
            val (t, c) = textos[i]
            val limpio = t.trim()
            // Motas sueltas (un punto, un guion, una «I» entre dos palabras): fuera si no está muy claro.
            val sinLetras = limpio.none { it.isLetterOrDigit() }
            val basura = (sinLetras && c < 0.9f) || (limpio.length == 1 && c < 0.85f)
            if (limpio.isEmpty() || c < confianzaMinima || basura) null else Linea(limpio, c, cajas[i])
        }.let { l -> renglones(l).flatten() }
    }

    // ---------- 1. Detección ----------

    private fun detectar(img: Pixeles, ladoMax: Int): List<FloatArray> {
        // Múltiplos de 32 (lo pide la red); sin agrandar las imágenes grandes, sí las muy pequeñas.
        val escala = min(1f, ladoMax.toFloat() / max(img.ancho, img.alto)).let { e ->
            if (min(img.ancho, img.alto) * e < 64) 64f / min(img.ancho, img.alto) else e
        }
        val w = max(32, ((img.ancho * escala) / 32f).roundToInt() * 32)
        val h = max(32, ((img.alto * escala) / 32f).roundToInt() * 32)
        val ex = img.ancho.toFloat() / w
        val ey = img.alto.toFloat() / h
        // Entrada: BGR, (x/255 - media) / desviación, por canales (CHW). Muestreo por áreas.
        val plano = w * h
        val fb = FloatBuffer.allocate(3 * plano)
        val datos = fb.array()
        val media = floatArrayOf(0.485f, 0.456f, 0.406f)
        val desv = floatArrayOf(0.229f, 0.224f, 0.225f)
        val muestra = reducir(img, w, h)
        for (i in 0 until plano) {
            val p = muestra[i]
            val b = (p and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val r = ((p shr 16) and 0xFF) / 255f
            datos[i] = (b - media[0]) / desv[0]
            datos[plano + i] = (g - media[1]) / desv[1]
            datos[2 * plano + i] = (r - media[2]) / desv[2]
        }
        val mapa = FloatArray(plano)
        OnnxTensor.createTensor(entorno, fb, longArrayOf(1, 3, h.toLong(), w.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { r ->
                val s = (r.get(0) as OnnxTensor).floatBuffer
                s.get(mapa, 0, min(plano, s.remaining()))
            }
        }
        return cajas(mapa, w, h).map { c -> FloatArray(8) { k -> if (k % 2 == 0) (c[k] * ex).coerceIn(0f, img.ancho - 1f) else (c[k] * ey).coerceIn(0f, img.alto - 1f) } }
    }

    /** DBNet: manchas del mapa por encima de 0,3 → su rectángulo mínimo (girado), agrandado. */
    private fun cajas(mapa: FloatArray, w: Int, h: Int): List<FloatArray> {
        val n = w * h
        val etiqueta = BooleanArray(n)
        val cola = IntArray(n)
        val salida = mutableListOf<FloatArray>()
        var manchas = 0
        for (inicio in 0 until n) {
            if (etiqueta[inicio] || mapa[inicio] <= UMBRAL) continue
            if (++manchas > MAX_CAJAS) break
            // Recorrido en anchura: la cola guarda todos los píxeles de la mancha.
            var cabeza = 0
            var fin = 0
            cola[fin++] = inicio
            etiqueta[inicio] = true
            var suma = 0.0
            var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
            while (cabeza < fin) {
                val p = cola[cabeza++]
                val x = p % w
                val y = p / w
                suma += mapa[p]
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        val q = yy * w + xx
                        if (!etiqueta[q] && mapa[q] > UMBRAL) { etiqueta[q] = true; cola[fin++] = q }
                    }
                }
            }
            if (fin < 6 || suma / fin < UMBRAL_CAJA) continue
            // Contorno: los extremos de cada fila (bastan para la envolvente convexa).
            val filas = y1 - y0 + 1
            val filaMin = IntArray(filas) { Int.MAX_VALUE }
            val filaMax = IntArray(filas) { -1 }
            for (k in 0 until fin) {
                val p = cola[k]; val x = p % w; val fy = p / w - y0
                if (x < filaMin[fy]) filaMin[fy] = x
                if (x > filaMax[fy]) filaMax[fy] = x
            }
            val puntos = ArrayList<Long>(filas * 4)
            for (fy in 0 until filas) if (filaMax[fy] >= 0) {
                val y = y0 + fy
                puntos += pack(filaMin[fy], y); puntos += pack(filaMax[fy] + 1, y)
                puntos += pack(filaMin[fy], y + 1); puntos += pack(filaMax[fy] + 1, y + 1)
            }
            val r = rectanguloMinimo(envolvente(puntos)) ?: continue
            if (min(r.ancho, r.alto) < 3f) continue
            // «Unclip»: la red marca el núcleo de la línea; se agranda lo que dice el modelo (1,5).
            val d = r.ancho * r.alto * UNCLIP / (2f * (r.ancho + r.alto))
            val grande = r.copy(ancho = r.ancho + 2 * d, alto = r.alto + 2 * d)
            if (min(grande.ancho, grande.alto) < 5f) continue
            salida += grande.esquinas()
        }
        return salida
    }

    private fun pack(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    private fun px(p: Long) = (p shr 32).toInt()
    private fun py(p: Long) = p.toInt()

    /** Envolvente convexa (cadena monótona de Andrew). */
    private fun envolvente(puntos: List<Long>): List<FloatArray> {
        val p = puntos.distinct().sortedWith(compareBy<Long>({ px(it) }, { py(it) }))
        if (p.size < 3) return p.map { floatArrayOf(px(it).toFloat(), py(it).toFloat()) }
        fun cruz(o: Long, a: Long, b: Long) = (px(a) - px(o)).toLong() * (py(b) - py(o)) - (py(a) - py(o)).toLong() * (px(b) - px(o))
        val h = LongArray(2 * p.size)
        var k = 0
        for (q in p) { while (k >= 2 && cruz(h[k - 2], h[k - 1], q) <= 0) k--; h[k++] = q }
        val t = k + 1
        for (i in p.size - 2 downTo 0) { val q = p[i]; while (k >= t && cruz(h[k - 2], h[k - 1], q) <= 0) k--; h[k++] = q }
        return (0 until k - 1).map { floatArrayOf(px(h[it]).toFloat(), py(h[it]).toFloat()) }
    }

    private data class Rect(val cx: Float, val cy: Float, val ancho: Float, val alto: Float, val angulo: Float) {
        /** Arriba-izquierda, arriba-derecha, abajo-derecha, abajo-izquierda. */
        fun esquinas(): FloatArray {
            val c = cos(angulo); val s = sin(angulo)
            val ux = c * ancho / 2; val uy = s * ancho / 2
            val vx = -s * alto / 2; val vy = c * alto / 2
            return floatArrayOf(cx - ux - vx, cy - uy - vy, cx + ux - vx, cy + uy - vy, cx + ux + vx, cy + uy + vy, cx - ux + vx, cy - uy + vy)
        }
    }

    /** Rectángulo de área mínima que contiene la envolvente (calibres giratorios, lado a lado). */
    private fun rectanguloMinimo(h: List<FloatArray>): Rect? {
        if (h.size < 3) return null
        var mejor: Rect? = null
        var mejorArea = Float.MAX_VALUE
        for (i in h.indices) {
            val a = h[i]; val b = h[(i + 1) % h.size]
            var ang = kotlin.math.atan2(b[1] - a[1], b[0] - a[0])
            val c = cos(ang); val s = sin(ang)
            var minU = Float.MAX_VALUE; var maxU = -Float.MAX_VALUE; var minV = Float.MAX_VALUE; var maxV = -Float.MAX_VALUE
            for (p in h) {
                val u = p[0] * c + p[1] * s
                val v = -p[0] * s + p[1] * c
                if (u < minU) minU = u; if (u > maxU) maxU = u; if (v < minV) minV = v; if (v > maxV) maxV = v
            }
            var ancho = maxU - minU
            var alto = maxV - minV
            val area = ancho * alto
            if (area < mejorArea) {
                mejorArea = area
                val mu = (minU + maxU) / 2; val mv = (minV + maxV) / 2
                val cx = mu * c - mv * s
                val cy = mu * s + mv * c
                // El lado largo, lo más horizontal posible (el texto se lee de izquierda a derecha).
                while (ang > Math.PI / 2) ang -= Math.PI.toFloat()
                while (ang <= -Math.PI / 2) ang += Math.PI.toFloat()
                if (abs(ang) > Math.PI / 4) {
                    val t = ancho; ancho = alto; alto = t
                    ang = if (ang > 0) ang - (Math.PI / 2).toFloat() else ang + (Math.PI / 2).toFloat()
                }
                mejor = Rect(cx, cy, ancho, alto, ang)
            }
        }
        return mejor
    }

    // ---------- 2. Reconocimiento ----------

    private fun reconocer(img: Pixeles, cajas: List<FloatArray>): List<Pair<String, Float>> {
        val recortes = cajas.map { recortar(img, it) }
        val orden = recortes.indices.sortedBy { recortes[it].ancho.toFloat() / recortes[it].alto }
        val resultado = arrayOfNulls<Pair<String, Float>>(cajas.size)
        for (lote in orden.chunked(LOTE)) {
            val anchoLote = max(MIN_ANCHO, lote.maxOf { recortes[it].ancho })
            val plano = ALTO * anchoLote
            val fb = FloatBuffer.allocate(lote.size * 3 * plano)
            val datos = fb.array()
            lote.forEachIndexed { n, i ->
                val r = recortes[i]
                val base = n * 3 * plano
                for (y in 0 until ALTO) for (x in 0 until r.ancho) {
                    val p = r.px[y * r.ancho + x]
                    val o = base + y * anchoLote + x
                    datos[o] = ((p and 0xFF) / 255f - 0.5f) / 0.5f
                    datos[o + plano] = (((p shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
                    datos[o + 2 * plano] = (((p shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
                }
            }
            OnnxTensor.createTensor(entorno, fb, longArrayOf(lote.size.toLong(), 3, ALTO.toLong(), anchoLote.toLong())).use { t ->
                rec.run(mapOf(rec.inputNames.first() to t)).use { r ->
                    val salida = r.get(0) as OnnxTensor
                    val forma = salida.info.shape
                    val pasos = forma[1].toInt()
                    val clases = forma[2].toInt()
                    val probs = salida.floatBuffer
                    lote.forEachIndexed { n, i -> resultado[i] = descifrar(probs, n * pasos * clases, pasos, clases) }
                }
            }
        }
        return resultado.map { it ?: ("" to 0f) }
    }

    /** CTC voraz: lo más probable en cada paso, sin repetidos ni «blancos». */
    private fun descifrar(p: FloatBuffer, desde: Int, pasos: Int, clases: Int): Pair<String, Float> {
        val sb = StringBuilder()
        var suma = 0f
        var cuenta = 0
        var anterior = -1
        for (t in 0 until pasos) {
            var mejor = 0
            var valor = -Float.MAX_VALUE
            val o = desde + t * clases
            for (c in 0 until clases) { val v = p.get(o + c); if (v > valor) { valor = v; mejor = c } }
            if (mejor != 0 && mejor != anterior && mejor < caracteres.size) {
                sb.append(caracteres[mejor]); suma += valor; cuenta++
            }
            anterior = mejor
        }
        return sb.toString() to (if (cuenta == 0) 0f else suma / cuenta)
    }

    /** La línea, enderezada y a 48 px de alto (promediando si se reduce mucho: sin dientes de sierra). */
    private fun recortar(img: Pixeles, e: FloatArray): Pixeles {
        var ox = e[0]; var oy = e[1]
        var ux = e[2] - e[0]; var uy = e[3] - e[1]   // a lo ancho
        var vx = e[6] - e[0]; var vy = e[7] - e[1]   // a lo alto
        var largo = hypot(ux, uy)
        var grosor = hypot(vx, vy)
        // Texto en vertical: se gira para leerlo de lado.
        if (grosor >= largo * 1.5f) {
            ox = e[6]; oy = e[7]
            val nux = e[0] - e[6]; val nuy = e[1] - e[7]
            val nvx = e[4] - e[6]; val nvy = e[5] - e[7]
            ux = nux; uy = nuy; vx = nvx; vy = nvy
            largo = hypot(ux, uy); grosor = hypot(vx, vy)
        }
        val ancho = min(MAX_ANCHO, max(8, ceil(ALTO * largo / max(grosor, 1f)).toInt()))
        val sub = (grosor / ALTO).let { if (it > 1.5f) min(4, ceil(it).toInt()) else 1 }
        val salida = IntArray(ALTO * ancho)
        for (y in 0 until ALTO) for (x in 0 until ancho) {
            var r = 0f; var g = 0f; var b = 0f
            for (sy in 0 until sub) for (sx in 0 until sub) {
                val fu = (x + (sx + 0.5f) / sub) / ancho
                val fv = (y + (sy + 0.5f) / sub) / ALTO
                val p = bilineal(img, ox + fu * ux + fv * vx - 0.5f, oy + fu * uy + fv * vy - 0.5f)
                r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            val k = sub * sub
            salida[y * ancho + x] = (0xFF shl 24) or ((r / k).roundToInt() shl 16) or ((g / k).roundToInt() shl 8) or (b / k).roundToInt()
        }
        return Pixeles(ancho, ALTO, salida)
    }

    companion object {
        private const val UMBRAL = 0.3f
        private const val UMBRAL_CAJA = 0.6f
        private const val UNCLIP = 1.5f
        private const val MAX_CAJAS = 1000
        private const val ALTO = 48
        private const val MIN_ANCHO = 320
        private const val MAX_ANCHO = 3200
        private const val LOTE = 6

        /**
         * Junta las líneas en renglones (lo que está a la misma altura, de izquierda a derecha): un
         * ticket se lee «TOTAL 12,50» y no «TOTAL» y, mucho después, «12,50». Con la inclinación
         * de la foto: se mide en la dirección de las propias líneas.
         */
        fun renglones(lineas: List<Linea>): List<List<Linea>> {
            if (lineas.isEmpty()) return emptyList()
            // Inclinación de la página: la mediana de la de las líneas largas (pesa más lo que más se lee).
            val angulos = lineas.sortedByDescending { hypot(it.esquinas[2] - it.esquinas[0], it.esquinas[3] - it.esquinas[1]) }
                .take(12).map { kotlin.math.atan2(it.esquinas[3] - it.esquinas[1], it.esquinas[2] - it.esquinas[0]) }.sorted()
            val ang = angulos[angulos.size / 2]
            val c = cos(-ang); val s = sin(-ang)
            class P(val l: Linea, val x: Float, val y: Float, val alto: Float, val izq: Float)
            val ps = lineas.map { l ->
                val e = l.esquinas
                val cx = (e[0] + e[2] + e[4] + e[6]) / 4; val cy = (e[1] + e[3] + e[5] + e[7]) / 4
                val alto = (hypot(e[6] - e[0], e[7] - e[1]) + hypot(e[4] - e[2], e[5] - e[3])) / 2
                val izq = minOf(e[0] * c - e[1] * s, e[6] * c - e[7] * s)
                P(l, cx * c - cy * s, cx * s + cy * c, alto, izq)
            }
            val renglones = mutableListOf<MutableList<P>>()
            for (p in ps.sortedBy { it.y }) {
                val r = renglones.lastOrNull()?.takeIf { r ->
                    val y = r.map { it.y }.average().toFloat()
                    val alto = r.maxOf { it.alto }
                    abs(p.y - y) < min(alto, p.alto) * 0.5f
                }
                if (r != null) r += p else renglones += mutableListOf(p)
            }
            return renglones.map { r -> r.sortedBy { it.izq }.map { it.l } }
        }

        /** El texto entero: un renglón por línea, sus trozos separados por un espacio. */
        fun texto(lineas: List<Linea>): String = renglones(lineas).joinToString("\n") { r -> r.joinToString(" ") { it.texto } }

        /** Reduce (o amplía) la imagen a w×h, promediando por áreas al reducir. */
        fun reducir(img: Pixeles, w: Int, h: Int): IntArray {
            val salida = IntArray(w * h)
            val fx = img.ancho.toFloat() / w
            val fy = img.alto.toFloat() / h
            if (fx <= 1f && fy <= 1f) {
                for (y in 0 until h) for (x in 0 until w) salida[y * w + x] = bilineal(img, (x + 0.5f) * fx - 0.5f, (y + 0.5f) * fy - 0.5f)
                return salida
            }
            for (y in 0 until h) {
                val ya = floor(y * fy).toInt(); val yb = min(img.alto, max(ya + 1, ceil((y + 1) * fy).toInt()))
                for (x in 0 until w) {
                    val xa = floor(x * fx).toInt(); val xb = min(img.ancho, max(xa + 1, ceil((x + 1) * fx).toInt()))
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (yy in ya until yb) {
                        val fila = yy * img.ancho
                        for (xx in xa until xb) { val p = img.px[fila + xx]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++ }
                    }
                    salida[y * w + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
            return salida
        }

        /** Color en (x, y) interpolando los cuatro píxeles vecinos (fuera de la imagen, el borde). */
        fun bilineal(img: Pixeles, x: Float, y: Float): Int {
            val xc = x.coerceIn(0f, img.ancho - 1f)
            val yc = y.coerceIn(0f, img.alto - 1f)
            val x0 = xc.toInt(); val y0 = yc.toInt()
            val x1 = min(x0 + 1, img.ancho - 1); val y1 = min(y0 + 1, img.alto - 1)
            val ax = xc - x0; val ay = yc - y0
            val p00 = img.px[y0 * img.ancho + x0]; val p10 = img.px[y0 * img.ancho + x1]
            val p01 = img.px[y1 * img.ancho + x0]; val p11 = img.px[y1 * img.ancho + x1]
            fun canal(d: Int): Int {
                val a = ((p00 shr d) and 0xFF) * (1 - ax) + ((p10 shr d) and 0xFF) * ax
                val b = ((p01 shr d) and 0xFF) * (1 - ax) + ((p11 shr d) and 0xFF) * ax
                return (a * (1 - ay) + b * ay).roundToInt().coerceIn(0, 255)
            }
            return (0xFF shl 24) or (canal(16) shl 16) or (canal(8) shl 8) or canal(0)
        }

    }
}
