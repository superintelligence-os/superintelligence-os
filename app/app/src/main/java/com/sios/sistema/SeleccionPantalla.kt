package com.sios.sistema

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Muelle
import com.sios.ui.Texto

/**
 * Pantalla congelada para elegir qué preguntar, como «Rodea para buscar» pero con Claude: se rodea
 * con el dedo (trazo naranja) o se toca un punto; lo elegido queda iluminado y el resto, en sombra.
 * Devuelve el recorte en píxeles de la captura (o null = toda la pantalla).
 */
@Composable
fun SeleccionPantalla(captura: Bitmap, onElegir: (Bitmap?) -> Unit, modifier: Modifier = Modifier) {
    var tam by remember { mutableStateOf(IntSize.Zero) }
    val trazo = remember { mutableStateListOf<Offset>() }
    var zona by remember { mutableStateOf<Rect?>(null) }
    // Al abrir, la pantalla se encoge un poco y se redondea (con muelle), como al congelarla.
    var abierta by remember { mutableStateOf(false) }
    val escala by animateFloatAsState(if (abierta) 0.94f else 1f, Muelle.suave(), label = "escala")
    val radio by animateFloatAsState(if (abierta) 28f else 0f, Muelle.suave(), label = "radio")
    val sombra by animateFloatAsState(if (zona != null) 0.55f else 0.18f, Muelle.suave(), label = "sombra")
    androidx.compose.runtime.LaunchedEffect(Unit) { abierta = true }

    fun recortar(r: Rect) {
        // De la vista a los píxeles de la captura (ocupa la pantalla entera, misma proporción).
        val fx = captura.width / tam.width.toFloat()
        val fy = captura.height / tam.height.toFloat()
        val margen = 24f
        val l = ((r.left - margen) * fx).toInt().coerceIn(0, captura.width - 1)
        val t = ((r.top - margen) * fy).toInt().coerceIn(0, captura.height - 1)
        val rr = ((r.right + margen) * fx).toInt().coerceIn(l + 1, captura.width)
        val b = ((r.bottom + margen) * fy).toInt().coerceIn(t + 1, captura.height)
        onElegir(Bitmap.createBitmap(captura, l, t, rr - l, b - t))
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { tam = it },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = escala; scaleY = escala }
                .clip(RoundedCornerShape(radio.dp))
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        // Un toque: la zona de alrededor (un cuadrado de un cuarto del ancho).
                        val lado = size.width / 4f
                        val r = Rect(p.x - lado, p.y - lado, p.x + lado, p.y + lado)
                        zona = r; trazo.clear(); recortar(r)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { trazo.clear(); zona = null; trazo.add(it) },
                        onDragEnd = {
                            if (trazo.size > 3) {
                                val r = Rect(trazo.minOf { it.x }, trazo.minOf { it.y }, trazo.maxOf { it.x }, trazo.maxOf { it.y })
                                zona = r; recortar(r)
                            }
                        },
                    ) { cambio, _ -> cambio.consume(); trazo.add(cambio.position) }
                },
        ) {
            Image(captura.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                // Sombra con un hueco en la zona elegida.
                val z = zona
                if (z == null) drawRect(Color.Black.copy(alpha = sombra))
                else {
                    val hueco = Path().apply {
                        addRect(Rect(Offset.Zero, size))
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(z.inflate(24f), CornerRadius(36f)))
                        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                    }
                    drawPath(hueco, Color.Black.copy(alpha = sombra))
                    drawRoundRect(Colores.Acento, z.inflate(24f).topLeft, Size(z.width + 48f, z.height + 48f), CornerRadius(36f), style = Stroke(width = 5f))
                }
                if (trazo.size > 1 && z == null) {
                    val camino = Path().apply { moveTo(trazo[0].x, trazo[0].y); for (p in trazo.drop(1)) lineTo(p.x, p.y) }
                    // Trazo con halo: uno ancho y suave debajo, otro fino encima.
                    drawPath(camino, Colores.Acento.copy(alpha = 0.35f), style = Stroke(width = 28f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawPath(camino, Colores.Acento, style = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        if (zona == null) {
            Text(
                stringResource(R.string.asistente_rodea),
                style = Texto.Pequeno,
                color = Colores.Texto,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
                    .clip(Formas.Pastilla)
                    .background(Colores.Superficie.copy(alpha = 0.92f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}
