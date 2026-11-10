package com.sios.sistema

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Las cuentas del escáner de documentos, sin nada de Android (se prueban igual en el Mac):
 *  - [detectar]: dónde está el papel en la foto (sus cuatro esquinas);
 *  - [enderezar]: el papel recortado y visto de frente (corrección de perspectiva);
 *  - [mejorar]: aspecto de documento escaneado (fondo blanco y uniforme, sin sombras, texto nítido).
 * Esquinas siempre en el orden: arriba-izquierda, arriba-derecha, abajo-derecha, abajo-izquierda.
 */
object EscanerNucleo {
    enum class Filtro { Documento, Color, Original }

    /** Para las pruebas: qué candidatos ha visto la detección. */
    var depurar: ((String) -> Unit)? = null

    // ---------- Detección del papel ----------

    /**
     * Las esquinas del papel (en píxeles de `img`) o null si no hay nada que se parezca a una hoja.
     * Se mira en pequeño (unos 320 px): el papel es la zona grande, clara y convexa que se distingue
     * del fondo, ya sea por luz (Otsu), por color (distinto del borde de la foto) o por su contorno.
     * `afinar`: después ajusta cada lado al borde real con más resolución (para la foto hecha; en el
     * visor en directo no hace falta).
     */
    fun detectar(img: Pixeles, afinar: Boolean = true): FloatArray? {
        val lado = 320
        val escala = min(1f, lado.toFloat() / max(img.ancho, img.alto))
        val w = max(16, (img.ancho * escala).roundToInt())
        val h = max(16, (img.alto * escala).roundToInt())
        val px = OcrMotor.reducir(img, w, h)
        val luz = FloatArray(w * h) { i -> lum(px[i]) }
        val suave = desenfocar(desenfocar(luz, w, h, 2), w, h, 2)

        val candidatos = mutableListOf<Pair<FloatArray, Float>>()
        // 1. Por luz: el papel es más claro que lo que lo rodea.
        val umbral = otsu(suave)
        mejorCuadrilatero(BooleanArray(w * h) { suave[it] > umbral }, w, h)?.let { candidatos += it }
        // 2. Por color: lo que no se parece al borde de la foto (mesa blanca y papel crema, etc.).
        val fondo = colorDelBorde(px, w, h)
        val distancia = FloatArray(w * h) { i -> distanciaColor(px[i], fondo) }
        val d = desenfocar(desenfocar(distancia, w, h, 2), w, h, 2)
        val ud = otsu(d)
        if (ud > 0.06f) mejorCuadrilatero(BooleanArray(w * h) { d[it] > ud }, w, h)?.let { candidatos += it }

        // 3. Por bordes: el contorno del papel encierra una zona (sirve con mesa y papel claros).
        //    Varios umbrales: con poco contraste el contorno solo aparece con uno bajo; con mucho,
        //    uno bajo también recoge la textura de la mesa. Se queda el que mejor encaja.
        val grad = gradiente(desenfocar(luz, w, h, 1), w, h)
        // También el color: papel crema sobre mesa gris se distingue más por el tono que por la luz.
        for (canal in 0 until 3) {
            val c = FloatArray(w * h) { i -> ((px[i] shr (16 - 8 * canal)) and 0xFF) / 255f }
            val g = gradiente(desenfocar(c, w, h, 1), w, h)
            for (i in g.indices) if (g[i] > grad[i]) grad[i] = g[i]
        }
        val croma = FloatArray(w * h) { i -> val p = px[i]; 3f * ((((p shr 16) and 0xFF) + ((p shr 8) and 0xFF)) / 2f - (p and 0xFF)) / 255f }
        gradiente(desenfocar(croma, w, h, 1), w, h).forEachIndexed { i, v -> if (v > grad[i]) grad[i] = v }
        val fuerte = percentil(grad, 0.99f)
        for (f in floatArrayOf(0.08f, 0.15f, 0.25f, 0.4f)) {
            val ug = max(0.012f, fuerte * f)
            mejorCuadrilatero(dilatar(BooleanArray(w * h) { grad[it] > ug }, w, h), w, h)?.let { candidatos += it.first to it.second * 0.9f }
        }
        depurar?.invoke(candidatos.joinToString { "%.3f".format(it.second) })

        val (q, _) = candidatos.maxByOrNull { it.second } ?: return null
        val grande = FloatArray(8) { k -> if (k % 2 == 0) q[k] / w * img.ancho else q[k] / h * img.alto }
        return if (afinar) afinar(img, grande) ?: grande else grande
    }

    /**
     * Ajusta cada lado al borde de verdad, con más resolución (unos 900 px): a lo largo del lado se
     * busca, en perpendicular, el salto de luz más fuerte; con esos puntos se traza una recta, y las
     * esquinas son los cruces de rectas vecinas. Si algo sale raro, se queda la estimación gruesa.
     */
    private fun afinar(img: Pixeles, q: FloatArray): FloatArray? {
        val escala = min(1f, 900f / max(img.ancho, img.alto))
        val w = max(16, (img.ancho * escala).roundToInt()); val h = max(16, (img.alto * escala).roundToInt())
        val px = OcrMotor.reducir(img, w, h)
        val luz = desenfocar(FloatArray(w * h) { lum(px[it]) }, w, h, 1)
        fun luzEn(x: Float, y: Float): Float {
            val xi = x.roundToInt().coerceIn(0, w - 1); val yi = y.roundToInt().coerceIn(0, h - 1)
            return luz[yi * w + xi]
        }
        val p = FloatArray(8) { q[it] * (if (it % 2 == 0) w.toFloat() / img.ancho else h.toFloat() / img.alto) }
        val cx = (p[0] + p[2] + p[4] + p[6]) / 4; val cy = (p[1] + p[3] + p[5] + p[7]) / 4
        val radio = max(4f, max(w, h) * 0.025f)
        val rectas = Array(4) { lado ->
            val ax = p[2 * lado]; val ay = p[2 * lado + 1]
            val bx = p[2 * ((lado + 1) % 4)]; val by = p[2 * ((lado + 1) % 4) + 1]
            val l = hypot(bx - ax, by - ay)
            if (l < 10f) return null
            // Normal hacia fuera del papel.
            var nx = -(by - ay) / l; var ny = (bx - ax) / l
            val mx0 = (ax + bx) / 2 - cx; val my0 = (ay + by) / 2 - cy
            if (mx0 * nx + my0 * ny < 0) { nx = -nx; ny = -ny }
            val xs = mutableListOf<Float>(); val ys = mutableListOf<Float>()
            for (i in 1 until 40) {
                val t = 0.1f + 0.8f * i / 40f
                val mx = ax + (bx - ax) * t; val my = ay + (by - ay) * t
                var mejor = 0f; var mejorD = 0f
                var d = -radio
                while (d <= radio) {
                    // Salto de luz de dentro (d - 1,5) a fuera (d + 1,5): el papel suele ser más claro.
                    val dentro = luzEn(mx + nx * (d - 1.5f), my + ny * (d - 1.5f))
                    val fuera = luzEn(mx + nx * (d + 1.5f), my + ny * (d + 1.5f))
                    val salto = abs(dentro - fuera) * (if (dentro > fuera) 1f else 0.6f)
                    if (salto > mejor) { mejor = salto; mejorD = d }
                    d += 0.5f
                }
                if (mejor > 0.04f) { xs += mx + nx * mejorD; ys += my + ny * mejorD }
            }
            if (xs.size < 8) return null
            recta(xs, ys) ?: return null
        }
        val salida = FloatArray(8)
        for (i in 0 until 4) {
            val a = rectas[(i + 3) % 4]; val b = rectas[i]
            val det = a[0] * b[1] - a[1] * b[0]
            if (abs(det) < 1e-4f) return null
            val x = (a[2] * b[1] - a[1] * b[2]) / det
            val y = (a[0] * b[2] - a[2] * b[0]) / det
            // Una esquina que se va lejos de la estimación: mejor no fiarse.
            if (hypot(x - p[2 * i], y - p[2 * i + 1]) > radio * 2.5f) return null
            salida[2 * i] = (x / w * img.ancho).coerceIn(0f, img.ancho.toFloat())
            salida[2 * i + 1] = (y / h * img.alto).coerceIn(0f, img.alto.toFloat())
        }
        return if (convexo(salida)) salida else null
    }

    /** Recta a·x + b·y = c por mínimos cuadrados totales, descartando el 25 % de puntos más lejanos. */
    private fun recta(xs: List<Float>, ys: List<Float>): FloatArray? {
        fun ajustar(ix: List<Int>): FloatArray {
            val mx = ix.map { xs[it] }.average().toFloat(); val my = ix.map { ys[it] }.average().toFloat()
            var sxx = 0f; var sxy = 0f; var syy = 0f
            for (i in ix) { val dx = xs[i] - mx; val dy = ys[i] - my; sxx += dx * dx; sxy += dx * dy; syy += dy * dy }
            // Dirección principal (autovector mayor) y su normal.
            val ang = 0.5f * kotlin.math.atan2(2 * sxy, sxx - syy)
            val a = -kotlin.math.sin(ang); val b = kotlin.math.cos(ang)
            return floatArrayOf(a, b, a * mx + b * my)
        }
        var r = ajustar(xs.indices.toList())
        val dist = xs.indices.sortedBy { abs(r[0] * xs[it] + r[1] * ys[it] - r[2]) }
        r = ajustar(dist.take(max(6, (dist.size * 0.75f).toInt())))
        return if (r.all { it.isFinite() }) r else null
    }

    private fun gradiente(v: FloatArray, w: Int, h: Int) = FloatArray(w * h) { i ->
        val x = i % w; val y = i / w
        if (x == 0 || y == 0 || x == w - 1 || y == h - 1) 0f
        else hypot(v[i + 1] - v[i - 1], v[i + w] - v[i - w])
    }

    private fun percentil(v: FloatArray, f: Float): Float {
        val m = FloatArray(min(v.size, 20_000)) { v[(it.toLong() * v.size / min(v.size, 20_000)).toInt()] }.sorted()
        return m[((m.size - 1) * f).toInt()]
    }

    private fun dilatar(m: BooleanArray, w: Int, h: Int) = BooleanArray(w * h) { i ->
        val x = i % w; val y = i / w
        var r = false
        loop@ for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until w && yy in 0 until h && m[yy * w + xx]) { r = true; break@loop }
        }
        r
    }

    /** El cuadrilátero de la mancha más grande de la máscara, con su nota (0 si no convence). */
    private fun mejorCuadrilatero(m: BooleanArray, w: Int, h: Int): Pair<FloatArray, Float>? {
        val mascara = rellenarHuecos(abrir(m, w, h), w, h)
        // La mancha más grande.
        val etiqueta = IntArray(w * h)
        val cola = IntArray(w * h)
        var mejor = 0; var mejorTam = 0; var n = 0
        for (i in 0 until w * h) {
            if (!mascara[i] || etiqueta[i] != 0) continue
            n++
            var cabeza = 0; var fin = 0
            cola[fin++] = i; etiqueta[i] = n
            while (cabeza < fin) {
                val p = cola[cabeza++]; val x = p % w; val y = p / w
                if (x > 0 && mascara[p - 1] && etiqueta[p - 1] == 0) { etiqueta[p - 1] = n; cola[fin++] = p - 1 }
                if (x < w - 1 && mascara[p + 1] && etiqueta[p + 1] == 0) { etiqueta[p + 1] = n; cola[fin++] = p + 1 }
                if (y > 0 && mascara[p - w] && etiqueta[p - w] == 0) { etiqueta[p - w] = n; cola[fin++] = p - w }
                if (y < h - 1 && mascara[p + w] && etiqueta[p + w] == 0) { etiqueta[p + w] = n; cola[fin++] = p + w }
            }
            if (fin > mejorTam) { mejorTam = fin; mejor = n }
        }
        val fraccion = mejorTam.toFloat() / (w * h)
        if (mejor == 0 || fraccion < 0.08f || fraccion > 0.985f) return null
        // Contorno: extremos de cada fila → envolvente convexa → cuadrilátero de área máxima.
        val puntos = mutableListOf<FloatArray>()
        var bordes = 0
        for (y in 0 until h) {
            var a = -1; var b = -1
            for (x in 0 until w) if (etiqueta[y * w + x] == mejor) { if (a < 0) a = x; b = x }
            if (a >= 0) {
                puntos += floatArrayOf(a.toFloat(), y.toFloat()); puntos += floatArrayOf(b + 1f, y.toFloat())
                puntos += floatArrayOf(a.toFloat(), y + 1f); puntos += floatArrayOf(b + 1f, y + 1f)
                if (a == 0) bordes++; if (b == w - 1) bordes++
            }
        }
        val casco = simplificar(envolvente(puntos), 1.2f)
        if (casco.size < 4) return null
        val q = cuadrilateroMaximo(casco) ?: return null
        val areaQ = area(q)
        if (areaQ <= 0f) return null
        // Nota: lo bien que el cuadrilátero explica la mancha (sin sobrar ni faltar) y que no sea
        // simplemente toda la foto (la mancha pegada a los cuatro bordes suele ser fondo).
        val relleno = min(mejorTam / areaQ, areaQ / mejorTam)
        val pegado = bordes.toFloat() / (2 * h)
        if (relleno < 0.8f || !convexo(q)) return null
        val nota = relleno * relleno * sqrt(fraccion) * (1f - pegado * 0.8f)
        return ordenar(q) to nota
    }

    private fun lum(p: Int) = (0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)) / 255f

    private fun distanciaColor(p: Int, c: FloatArray): Float {
        val r = ((p shr 16) and 0xFF) / 255f - c[0]; val g = ((p shr 8) and 0xFF) / 255f - c[1]; val b = (p and 0xFF) / 255f - c[2]
        return sqrt(r * r + g * g + b * b)
    }

    /** El color típico (mediana por canal) del marco de la foto: casi siempre, la mesa. */
    private fun colorDelBorde(px: IntArray, w: Int, h: Int): FloatArray {
        val m = max(2, min(w, h) / 25)
        val r = mutableListOf<Int>(); val g = mutableListOf<Int>(); val b = mutableListOf<Int>()
        for (y in 0 until h) for (x in 0 until w) if (x < m || y < m || x >= w - m || y >= h - m) {
            val p = px[y * w + x]; r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
        }
        fun med(l: MutableList<Int>) = l.sorted()[l.size / 2] / 255f
        return floatArrayOf(med(r), med(g), med(b))
    }

    private fun otsu(v: FloatArray): Float {
        val hist = IntArray(256)
        for (x in v) hist[(x.coerceIn(0f, 1f) * 255).toInt()]++
        val total = v.size.toDouble()
        var suma = 0.0
        for (i in 0..255) suma += i * hist[i].toDouble()
        var sumaB = 0.0; var pesoB = 0.0; var mejor = 0.0; var umbral = 127
        for (i in 0..255) {
            pesoB += hist[i]
            if (pesoB == 0.0) continue
            val pesoF = total - pesoB
            if (pesoF == 0.0) break
            sumaB += i * hist[i].toDouble()
            val mb = sumaB / pesoB; val mf = (suma - sumaB) / pesoF
            val entre = pesoB * pesoF * (mb - mf) * (mb - mf)
            if (entre > mejor) { mejor = entre; umbral = i }
        }
        return (umbral + 0.5f) / 255f
    }

    /** Desenfoque de caja (separable) de radio r. */
    private fun desenfocar(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val t = FloatArray(w * h); val s = FloatArray(w * h)
        for (y in 0 until h) {
            var acc = 0f
            for (x in -r..r) acc += v[y * w + x.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                t[y * w + x] = acc / (2 * r + 1)
                acc += v[y * w + (x + r + 1).coerceAtMost(w - 1)] - v[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (y in -r..r) acc += t[y.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                s[y * w + x] = acc / (2 * r + 1)
                acc += t[(y + r + 1).coerceAtMost(h - 1) * w + x] - t[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return s
    }

    /** Apertura 3×3 (erosión y dilatación): quita hilos finos que unen el papel con el fondo. */
    private fun abrir(m: BooleanArray, w: Int, h: Int): BooleanArray {
        fun paso(a: BooleanArray, todos: Boolean) = BooleanArray(w * h) { i ->
            val x = i % w; val y = i / w
            var r = todos
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val xx = (x + dx).coerceIn(0, w - 1); val yy = (y + dy).coerceIn(0, h - 1)
                val v = a[yy * w + xx]
                if (todos && !v) { r = false; break@loop }
                if (!todos && v) { r = true; break@loop }
            }
            r
        }
        return paso(paso(m, true), false)
    }

    /** Rellena lo que no se puede alcanzar desde el borde (las letras dentro del papel). */
    private fun rellenarHuecos(m: BooleanArray, w: Int, h: Int): BooleanArray {
        val fuera = BooleanArray(w * h)
        val cola = IntArray(w * h)
        var fin = 0
        fun meter(p: Int) { if (!m[p] && !fuera[p]) { fuera[p] = true; cola[fin++] = p } }
        for (x in 0 until w) { meter(x); meter((h - 1) * w + x) }
        for (y in 0 until h) { meter(y * w); meter(y * w + w - 1) }
        var cabeza = 0
        while (cabeza < fin) {
            val p = cola[cabeza++]; val x = p % w; val y = p / w
            if (x > 0) meter(p - 1); if (x < w - 1) meter(p + 1); if (y > 0) meter(p - w); if (y < h - 1) meter(p + w)
        }
        return BooleanArray(w * h) { !fuera[it] }
    }

    private fun envolvente(puntos: List<FloatArray>): List<FloatArray> {
        val p = puntos.sortedWith(compareBy<FloatArray>({ it[0] }, { it[1] }))
        if (p.size < 3) return p
        fun cruz(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val h = arrayOfNulls<FloatArray>(2 * p.size)
        var k = 0
        for (q in p) { while (k >= 2 && cruz(h[k - 2]!!, h[k - 1]!!, q) <= 0) k--; h[k++] = q }
        val t = k + 1
        for (i in p.size - 2 downTo 0) { val q = p[i]; while (k >= t && cruz(h[k - 2]!!, h[k - 1]!!, q) <= 0) k--; h[k++] = q }
        return (0 until k - 1).map { h[it]!! }
    }

    /** Quita los vértices casi alineados (Douglas–Peucker en un polígono cerrado). */
    private fun simplificar(p: List<FloatArray>, eps: Float): List<FloatArray> {
        if (p.size <= 8) return p
        var r = p
        // Se repite con más tolerancia si quedan demasiados vértices (la búsqueda es cúbica).
        var e = eps
        while (r.size > 60) {
            val salida = mutableListOf<FloatArray>()
            for (i in r.indices) {
                val a = r[(i - 1 + r.size) % r.size]; val b = r[i]; val c = r[(i + 1) % r.size]
                if (distanciaRecta(b, a, c) > e) salida += b
            }
            r = if (salida.size >= 4) salida else return r
            e *= 1.6f
        }
        return r
    }

    private fun distanciaRecta(p: FloatArray, a: FloatArray, b: FloatArray): Float {
        val l = hypot(b[0] - a[0], b[1] - a[1])
        if (l < 1e-3f) return hypot(p[0] - a[0], p[1] - a[1])
        return abs((b[0] - a[0]) * (a[1] - p[1]) - (a[0] - p[0]) * (b[1] - a[1])) / l
    }

    /** Los cuatro vértices del casco que encierran más área (diagonal i–k; j y l, el mejor de cada lado). */
    private fun cuadrilateroMaximo(c: List<FloatArray>): FloatArray? {
        val n = c.size
        if (n < 4) return null
        fun tri(a: FloatArray, b: FloatArray, d: FloatArray) = abs((b[0] - a[0]) * (d[1] - a[1]) - (b[1] - a[1]) * (d[0] - a[0])) / 2
        var mejor = 0f
        var q: IntArray? = null
        for (i in 0 until n) for (k in i + 2 until n) {
            if (i == 0 && k == n - 1) continue
            var aj = 0f; var bj = -1
            for (j in i + 1 until k) { val t = tri(c[i], c[j], c[k]); if (t > aj) { aj = t; bj = j } }
            var al = 0f; var bl = -1
            for (l in (k + 1 until n) + (0 until i)) { val t = tri(c[i], c[l], c[k]); if (t > al) { al = t; bl = l } }
            if (bj >= 0 && bl >= 0 && aj + al > mejor) { mejor = aj + al; q = intArrayOf(i, bj, k, bl) }
        }
        val r = q ?: return null
        return FloatArray(8) { c[r[it / 2]][it % 2] }
    }

    fun area(q: FloatArray): Float {
        var s = 0f
        for (i in 0 until 4) { val j = (i + 1) % 4; s += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1] }
        return abs(s) / 2
    }

    fun convexo(q: FloatArray): Boolean {
        var signo = 0
        for (i in 0 until 4) {
            val a = i; val b = (i + 1) % 4; val c = (i + 2) % 4
            val z = (q[2 * b] - q[2 * a]) * (q[2 * c + 1] - q[2 * b + 1]) - (q[2 * b + 1] - q[2 * a + 1]) * (q[2 * c] - q[2 * b])
            val s = if (z > 0) 1 else if (z < 0) -1 else 0
            if (s == 0) return false
            if (signo == 0) signo = s else if (s != signo) return false
        }
        return true
    }

    /** Ordena las esquinas: arriba-izquierda, arriba-derecha, abajo-derecha, abajo-izquierda. */
    fun ordenar(q: FloatArray): FloatArray {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4; val cy = (q[1] + q[3] + q[5] + q[7]) / 4
        val idx = (0 until 4).sortedBy { kotlin.math.atan2(q[2 * it + 1] - cy, q[2 * it] - cx) } // de -π a π: empieza arriba-izquierda
        // atan2 en pantalla (y hacia abajo): -135° es arriba-izquierda; se gira la lista para empezar ahí.
        val inicio = idx.indices.minBy { i -> q[2 * idx[i]] + q[2 * idx[i] + 1] }
        return FloatArray(8) { k -> val i = idx[(inicio + k / 2) % 4]; q[2 * i + k % 2] }
    }

    /** Un marco por defecto (por si no se encuentra el papel): la foto con un margen. */
    fun marcoPorDefecto(ancho: Int, alto: Int, margen: Float = 0.08f): FloatArray {
        val mx = ancho * margen; val my = alto * margen
        return floatArrayOf(mx, my, ancho - mx, my, ancho - mx, alto - my, mx, alto - my)
    }

    // ---------- Perspectiva ----------

    /** El tamaño de la hoja enderezada: la media de los lados opuestos, sin pasar de `ladoMax`. */
    fun tamano(q: FloatArray, ladoMax: Int): Pair<Int, Int> {
        val arriba = hypot(q[2] - q[0], q[3] - q[1]); val abajo = hypot(q[4] - q[6], q[5] - q[7])
        val izq = hypot(q[6] - q[0], q[7] - q[1]); val der = hypot(q[4] - q[2], q[5] - q[3])
        var w = max(arriba, abajo); var h = max(izq, der)
        val e = min(1f, ladoMax / max(w, h))
        w *= e; h *= e
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    /** Homografía (3×3, por filas) que lleva los 4 puntos `de` a los 4 puntos `a`. */
    fun homografia(de: FloatArray, a: FloatArray): DoubleArray {
        val m = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val x = de[2 * i].toDouble(); val y = de[2 * i + 1].toDouble(); val u = a[2 * i].toDouble(); val v = a[2 * i + 1].toDouble()
            m[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            m[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (c in 0 until 8) {
            val piv = (c until 8).maxBy { abs(m[it][c]) }
            val t = m[c]; m[c] = m[piv]; m[piv] = t
            if (abs(m[c][c]) < 1e-12) continue
            for (r in 0 until 8) if (r != c) { val f = m[r][c] / m[c][c]; for (k in c..8) m[r][k] -= f * m[c][k] }
        }
        return DoubleArray(9) { if (it == 8) 1.0 else m[it][8] / m[it][it] }
    }

    /** La hoja vista de frente: cada píxel de destino se busca en la foto (homografía inversa). */
    fun enderezar(img: Pixeles, q: FloatArray, ladoMax: Int = 2400): Pixeles {
        val (w, h) = tamano(q, ladoMax)
        // Un pelo hacia dentro (0,5 %): el borde exacto suele traer una línea de la mesa.
        val mx = w * 0.005f; val my = h * 0.005f
        val hm = homografia(floatArrayOf(-mx, -my, w + mx, -my, w + mx, h + my, -mx, h + my), q)
        val salida = IntArray(w * h)
        for (y in 0 until h) {
            val yy = y + 0.5
            for (x in 0 until w) {
                val xx = x + 0.5
                val d = hm[6] * xx + hm[7] * yy + hm[8]
                val u = (hm[0] * xx + hm[1] * yy + hm[2]) / d
                val v = (hm[3] * xx + hm[4] * yy + hm[5]) / d
                salida[y * w + x] = OcrMotor.bilineal(img, (u - 0.5).toFloat(), (v - 0.5).toFloat())
            }
        }
        return Pixeles(w, h, salida)
    }

    // ---------- Aspecto de documento ----------

    /**
     * Fondo blanco y uniforme: se estima la luz del papel (lo más claro de cada zona, suavizado) y se
     * divide por ella, así desaparecen sombras y degradados. Después, según el filtro, gris con
     * negro profundo (Documento) o color limpio.
     */
    fun mejorar(img: Pixeles, filtro: Filtro): Pixeles {
        if (filtro == Filtro.Original) return img
        val w = img.ancho; val h = img.alto
        // La luz del papel, en una rejilla de bloques de ~1/40 del lado.
        val bloque = max(4, max(w, h) / 40)
        val bw = (w + bloque - 1) / bloque; val bh = (h + bloque - 1) / bloque
        val fondo = Array(3) { FloatArray(bw * bh) }
        for (by in 0 until bh) for (bx in 0 until bw) {
            // Un percentil alto (no el máximo, que puede ser un brillo): el 90 % de cada canal.
            val hr = IntArray(256); val hg = IntArray(256); val hb = IntArray(256); var n = 0
            val paso = max(1, bloque / 8)
            var y = by * bloque
            while (y < min(h, (by + 1) * bloque)) {
                var x = bx * bloque
                while (x < min(w, (bx + 1) * bloque)) {
                    val p = img.px[y * w + x]; hr[(p shr 16) and 0xFF]++; hg[(p shr 8) and 0xFF]++; hb[p and 0xFF]++; n++
                    x += paso
                }
                y += paso
            }
            fun pct(hh: IntArray): Float { var a = 0; val obj = n * 0.9f; for (i in 0..255) { a += hh[i]; if (a >= obj) return i.toFloat() }; return 255f }
            fondo[0][by * bw + bx] = pct(hr); fondo[1][by * bw + bx] = pct(hg); fondo[2][by * bw + bx] = pct(hb)
        }
        // Las letras grandes oscurecen su bloque: se toma el máximo de los vecinos y se suaviza.
        val f = fondo.map { c -> desenfocar(maximoVecinos(c, bw, bh), bw, bh, 1) }
        val salida = IntArray(w * h)
        val luzOut = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / bloque - 0.5f).coerceIn(0f, bh - 1f)
            val y0 = fy.toInt(); val y1 = min(y0 + 1, bh - 1); val ay = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / bloque - 0.5f).coerceIn(0f, bw - 1f)
                val x0 = fx.toInt(); val x1 = min(x0 + 1, bw - 1); val ax = fx - x0
                val p = img.px[y * w + x]
                var r = 0f; var g = 0f; var b = 0f
                for (c in 0 until 3) {
                    val m = f[c]
                    val bg = (m[y0 * bw + x0] * (1 - ax) + m[y0 * bw + x1] * ax) * (1 - ay) + (m[y1 * bw + x0] * (1 - ax) + m[y1 * bw + x1] * ax) * ay
                    val v = ((p shr (16 - 8 * c)) and 0xFF) / max(bg, 24f)
                    when (c) { 0 -> r = v; 1 -> g = v; else -> b = v }
                }
                val i = y * w + x
                if (filtro == Filtro.Documento) luzOut[i] = 0.299f * r + 0.587f * g + 0.114f * b
                else salida[i] = color(r, g, b)
            }
        }
        if (filtro == Filtro.Color) return Pixeles(w, h, salida)
        // Documento: el papel, blanco; la tinta, negra; una curva suave entre medias (sin dientes).
        val muestra = FloatArray(min(luzOut.size, 40_000)) { luzOut[(it.toLong() * luzOut.size / min(luzOut.size, 40_000)).toInt()] }.sorted()
        val negro = (muestra[(muestra.size * 0.01f).toInt()] * 0.9f).coerceIn(0f, 0.55f)
        val blanco = 0.9f
        for (i in luzOut.indices) {
            val t = ((luzOut[i] - negro) / (blanco - negro)).coerceIn(0f, 1f)
            val v = (t.pow(1.25f) * 255).roundToInt()
            salida[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        return Pixeles(w, h, salida)
    }

    private fun maximoVecinos(v: FloatArray, w: Int, h: Int) = FloatArray(w * h) { i ->
        val x = i % w; val y = i / w
        var m = 0f
        for (dy in -1..1) for (dx in -1..1) { val xx = x + dx; val yy = y + dy; if (xx in 0 until w && yy in 0 until h) m = max(m, v[yy * w + xx]) }
        m
    }

    /** Color normalizado (1 = blanco del papel) a ARGB, con un poco más de contraste y viveza. */
    private fun color(r: Float, g: Float, b: Float): Int {
        fun c(v: Float) = (((v - 0.08f) / 0.86f).coerceIn(0f, 1f).pow(1.25f) * 255).roundToInt()
        val l = (r + g + b) / 3
        val s = 1.15f
        return (0xFF shl 24) or (c(l + (r - l) * s) shl 16) or (c(l + (g - l) * s) shl 8) or c(l + (b - l) * s)
    }

    /** Gira la hoja 90° en el sentido de las agujas del reloj. */
    fun girar(img: Pixeles): Pixeles {
        val w = img.ancho; val h = img.alto
        val s = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) s[x * h + (h - 1 - y)] = img.px[y * w + x]
        return Pixeles(h, w, s)
    }
}
