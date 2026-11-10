package com.sios.fotos

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Mejora automática con un toque, en el propio móvil: mira la luz y el color de la foto (su
 * histograma) y propone unos ajustes moderados. Son ajustes normales: se ven en los deslizadores y
 * se pueden retocar o deshacer.
 */
object MejoraAuto {
    /** Lo que se mide de la foto. Los percentiles, de la luminancia (0 a 1, ya en pantalla). */
    data class Analisis(
        val p02: Float, val p10: Float, val p50: Float, val p90: Float, val p98: Float,
        val media: Float,
        /** Azul menos rojo en los medios tonos (positivo: dominante fría). */
        val frio: Float,
        /** Verde menos magenta en los medios tonos (positivo: dominante verde). */
        val verde: Float,
        val saturacion: Float,
    )

    fun analizar(px: IntArray): Analisis {
        val hist = IntArray(256)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var nMedios = 0
        var ss = 0.0
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val y = (0.2126f * r + 0.7152f * g + 0.0722f * b).roundToInt().coerceIn(0, 255)
            hist[y]++
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            ss += (mx - mn) / 255.0
            // El color dominante se mide en los medios tonos poco saturados: lo que debería ser gris.
            if (y in 40..215 && mx - mn < 90) { sr += r; sg += g; sb += b; nMedios++ }
        }
        val total = px.size.coerceAtLeast(1)
        fun percentil(f: Float): Float {
            val objetivo = f * total
            var acumulado = 0
            for (i in 0..255) { acumulado += hist[i]; if (acumulado >= objetivo) return i / 255f }
            return 1f
        }
        val media = (0..255).sumOf { it.toLong() * hist[it] } / (255f * total)
        val n = nMedios.coerceAtLeast(1)
        val (r, g, b) = Triple(sr / n / 255, sg / n / 255, sb / n / 255)
        return Analisis(
            percentil(0.02f), percentil(0.10f), percentil(0.5f), percentil(0.90f), percentil(0.98f), media,
            frio = if (nMedios > total / 20) (b - r).toFloat() else 0f,
            verde = if (nMedios > total / 20) (g - (r + b) / 2).toFloat() else 0f,
            saturacion = (ss / total).toFloat(),
        )
    }

    /** Los ajustes propuestos, solo a partir de la luz y el color medidos. */
    fun ajustes(a: Analisis): Map<Ajuste, Float> {
        val r = HashMap<Ajuste, Float>()
        // Exposición: lleva la mediana hacia un gris medio, sin pasarse.
        val objetivo = 0.44f
        var ev = (2.2f * (ln((objetivo / a.p50.coerceAtLeast(0.02f)).toDouble()) / ln(2.0)).toFloat() * 0.55f).coerceIn(-0.8f, 0.9f)
        // Oscurecer solo si la foto está de verdad quemada: una foto clara a propósito (clave
        // alta, contraluz suave) se deja como está.
        if (ev < 0f) ev = if (a.p98 > 0.97f && a.p50 > 0.6f) maxOf(ev, -0.3f) else 0f
        if (abs(ev) > 0.06f) r[Ajuste.EXPOSICION] = ev / 1.6f * 100f
        // Si al subir la luz se van a quemar las luces, se recuperan (lo que pasaría del blanco).
        val lucesAltas = a.p98 * Math.pow(2.0, (maxOf(ev, 0f) / 2.2f).toDouble()).toFloat()
        if (lucesAltas > 0.96f) r[Ajuste.LUCES] = -((lucesAltas - 0.96f) / 0.14f * 30f).coerceIn(0f, 35f)
        if (a.p10 < 0.13f) r[Ajuste.SOMBRAS] = ((0.13f - a.p10) / 0.13f * 34f).coerceIn(0f, 34f)
        // Contraste: si la foto está lavada (poco rango), un poco de curva.
        val rango = a.p98 - a.p02
        val contraste = ((0.86f - rango) * 90f).coerceIn(0f, 30f)
        if (contraste > 2f) r[Ajuste.CONTRASTE] = contraste
        // Color: corrige a medias la dominante. Tope bajo: un lago turquesa, un cielo azul o un
        // atardecer también «parecen» dominantes y son así a propósito.
        val calidez = (a.frio * 80f).coerceIn(-12f, 12f)
        if (abs(calidez) > 3f) r[Ajuste.CALIDEZ] = calidez
        val tinte = (a.verde * 70f).coerceIn(-8f, 8f)
        if (abs(tinte) > 3f) r[Ajuste.TINTE] = tinte
        r[Ajuste.INTENSIDAD] = if (a.saturacion < 0.22f) 22f else 12f
        // Nitidez moderada: suficiente para paisajes y textos, sin endurecer la piel en los retratos.
        r[Ajuste.NITIDEZ] = 10f
        return r.mapValues { (k, v) -> v.roundToInt().toFloat().coerceIn(k.minimo, k.maximo) }.filterValues { it != 0f }
    }

    /** Píxeles de una versión pequeña (para medir rápido). */
    fun muestra(b: Bitmap, lado: Int = 256): IntArray {
        val escala = lado.toFloat() / maxOf(b.width, b.height)
        val p = if (escala < 1f) Bitmap.createScaledBitmap(b, (b.width * escala).roundToInt().coerceAtLeast(1), (b.height * escala).roundToInt().coerceAtLeast(1), true) else b
        val px = IntArray(p.width * p.height)
        p.getPixels(px, 0, p.width, 0, 0, p.width, p.height)
        if (p !== b) p.recycle()
        return px
    }

    /** Analiza y propone, fuera del hilo principal. */
    suspend fun calcular(b: Bitmap): Map<Ajuste, Float> = withContext(Dispatchers.Default) {
        ajustes(analizar(muestra(b)))
    }
}
