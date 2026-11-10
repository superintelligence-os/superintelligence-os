package com.sios.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.sin

/**
 * El símbolo de SIOS animado, como en la animación de arranque: un cometa dibuja el anillo pasando
 * por el espectro de las IA (naranja → ámbar → azul → violeta) y la estela se enfría al color del
 * texto; el punto sale con un muelle y, si [ondas], el símbolo suelta ondas suaves en bucle (sónar).
 * Misma geometría que rom/marca/sios/simbolo.py (rejilla de 100×100).
 */
@Composable
fun SimboloSiosAnimado(modifier: Modifier = Modifier, ondas: Boolean = false, color: Color = Colores.Texto) {
    val trazo = remember { Animatable(0f) }
    val punto = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        trazo.animateTo(1f, tween(1300, easing = { x -> x * x * x * (x * (6 * x - 15) + 10) }))
        punto.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow))
    }
    val bucle = rememberInfiniteTransition(label = "sios")
    val fase by bucle.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "fase")
    val giro by bucle.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "giro")

    val geo = remember { Geometria() }
    Canvas(modifier) {
        val esc = minOf(size.width, size.height) / 100f
        val dx = (size.width - 100f * esc) / 2f
        val dy = (size.height - 100f * esc) / 2f
        fun p(x: Float, y: Float) = Offset(dx + x * esc, dy + y * esc)
        val centro = p(geo.cx, geo.cy)

        // Ondas del sónar, desde el centro del anillo (dos a destiempo), solo con el símbolo ya hecho.
        if (ondas && punto.value > 0.5f) {
            for (k in 0..1) {
                val f = (fase + k * 0.5f) % 1f
                val alfa = (1f - f) * (1f - f) * 0.5f * minOf(1f, punto.value)
                drawCircle(
                    lerp(ESPECTRO[0].second, ESPECTRO[3].second, f), radius = (geo.r + f * 34f) * esc, center = centro,
                    alpha = alfa, style = Stroke(width = 2.4f * esc * (1f - f * 0.6f)),
                )
            }
        }

        withTransform({ translate(dx, dy); scale(esc, esc, Offset.Zero) }) {
            val largo = geo.medida.length
            val hecho = largo * trazo.value
            if (hecho > 0.01f) {
                // La estela, ya fría.
                val estela = Path()
                geo.medida.getSegment(0f, hecho, estela, true)
                drawPath(estela, color, style = Stroke(geo.grosor, cap = StrokeCap.Round, join = StrokeJoin.Round))
                // La cabeza del cometa: un tramo corto con el color del espectro que toca, que se apaga al llegar.
                val apagado = 1f - ((trazo.value - 0.85f) / 0.15f).coerceIn(0f, 1f)
                if (apagado > 0f) {
                    val cabeza = Path()
                    geo.medida.getSegment(maxOf(0f, hecho - largo * 0.16f), hecho, cabeza, true)
                    drawPath(cabeza, espectro(trazo.value), alpha = apagado, style = Stroke(geo.grosor, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    val punta = geo.medida.getPosition(hecho)
                    drawCircle(
                        Brush.radialGradient(listOf(espectro(trazo.value).copy(alpha = 0.55f * apagado), Color.Transparent), punta, geo.grosor * 1.6f),
                        geo.grosor * 1.6f, punta,
                    )
                }
            }
            // El punto: sale de lo alto del palo con un muelle, con el espectro girando despacio.
            val s = punto.value
            if (s > 0.001f) {
                val sube = (1f - minOf(1f, s)) * 10f
                val c = Offset(geo.px, geo.py + sube)
                val r = geo.rp * s
                rotate(giro, c) {
                    drawCircle(Brush.sweepGradient(listOf(ESPECTRO[0].second, ESPECTRO[1].second, ESPECTRO[2].second, ESPECTRO[3].second, ESPECTRO[0].second), c), r, c)
                }
            }
        }
    }
}

private val ESPECTRO = listOf(
    0f to Color(0xFFD97757), 0.38f to Color(0xFFE9B44C), 0.68f to Color(0xFF4C8DF6), 1f to Color(0xFF7B6CF6),
)

private fun espectro(f: Float): Color {
    for (i in 0 until ESPECTRO.size - 1) {
        val (a, ca) = ESPECTRO[i]
        val (b, cb) = ESPECTRO[i + 1]
        if (f <= b) return lerp(ca, cb, ((f - a) / (b - a)).coerceIn(0f, 1f))
    }
    return ESPECTRO.last().second
}

/** Trazo «io»: el anillo empieza arriba a la derecha, da la vuelta por abajo, sube recto y deja sitio al punto. */
private class Geometria {
    val cx = 52f
    val cy = 56f
    val r = 29f
    val grosor = 12.5f
    val rp = 7.6f
    val px: Float
    val py: Float
    val medida: PathMeasure

    init {
        val ini = Math.toRadians(-104.0)
        val fin = Math.toRadians(180.0)
        val camino = Path()
        camino.moveTo(cx + r * cos(ini).toFloat(), cy + r * sin(ini).toFloat())
        // Del ángulo inicial al final en sentido horario (284°), en pasos cortos para que la medida sea exacta.
        val pasos = 96
        for (i in 1..pasos) {
            val a = ini + (fin - ini) * i / pasos
            camino.lineTo(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
        }
        val x1 = cx + r * cos(fin).toFloat()
        val y1 = cy + r * sin(fin).toFloat()
        val arriba = y1 - 11f
        camino.lineTo(x1, arriba)
        px = x1
        py = arriba - grosor / 2f - 6f - rp
        medida = PathMeasure().apply { setPath(camino, false) }
    }
}
