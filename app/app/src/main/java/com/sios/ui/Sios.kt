package com.sios.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Símbolo de SIOS: un anillo (la o, el sistema) cuyo lado izquierdo sube recto con un punto encima
 * (la i, la inteligencia). El punto lleva el color de la IA en uso. Misma geometría que
 * rom/marca/sios/simbolo.py, en una rejilla de 100×100.
 */
object SimboloSios {
    private const val CX = 52f
    private const val CY = 56f
    private const val R = 29f
    private const val T = 12.5f
    private const val RECTO = 11f
    private const val HUECO = 6f
    private const val RP = 7.6f

    /** Centro del punto en la rejilla (para poner un brillo detrás). */
    val punto = Offset(CX - R, CY - RECTO - T / 2 - HUECO - RP)

    /** Dibuja el símbolo de lado [tam] con su esquina en [origen]. progreso: 0..1 (el anillo se dibuja). */
    /** El espectro de las IA (naranja, ámbar, azul, violeta): el punto cuando aún no hay IA elegida. */
    val ESPECTRO = listOf(0f to Color(0xFFD97757), 0.38f to Color(0xFFE9B44C), 0.68f to Color(0xFF4C8DF6), 1f to Color(0xFF7B6CF6))

    fun DrawScope.pintar(tam: Float, origen: Offset, anillo: Color, puntoColor: Color, progreso: Float = 1f, escalaPunto: Float = 1f, espectro: Boolean = false) {
        val k = tam / 100f
        val trazo = Stroke(T * k, cap = StrokeCap.Round)
        val tl = Offset(origen.x + (CX - R) * k, origen.y + (CY - R) * k)
        // El anillo (284°) y el palo de la i se dibujan seguidos.
        val total = 284f + Math.toDegrees((RECTO / R).toDouble()).toFloat()
        val hecho = total * progreso.coerceIn(0f, 1f)
        drawArc(anillo, -104f, minOf(hecho, 284f), false, tl, Size(2 * R * k, 2 * R * k), style = trazo)
        if (hecho > 284f) {
            val f = ((hecho - 284f) / (total - 284f)).coerceIn(0f, 1f)
            val x = origen.x + (CX - R) * k
            drawLine(anillo, Offset(x, origen.y + CY * k), Offset(x, origen.y + (CY - RECTO * f) * k), T * k, StrokeCap.Round)
        }
        if (escalaPunto > 0f) {
            val c = Offset(origen.x + punto.x * k, origen.y + punto.y * k)
            val r = RP * k * escalaPunto
            if (espectro) drawCircle(Brush.linearGradient(*ESPECTRO.toTypedArray(), start = c - Offset(r, r), end = c + Offset(r, r)), r, c)
            else drawCircle(puntoColor, r, c)
        }
    }
}

/** El símbolo. Sin [punto], el color de la IA en uso o, sin elegir, el espectro de SIOS. */
@Composable
fun Sios(modifier: Modifier, anillo: Color = Colores.Texto, punto: Color? = null) {
    val espectro = punto == null && Marca.sinElegir
    val color = punto ?: Colores.Acento
    Canvas(modifier) {
        with(SimboloSios) { pintar(size.minDimension, Offset((size.width - size.minDimension) / 2, (size.height - size.minDimension) / 2), anillo, color, espectro = espectro) }
    }
}
