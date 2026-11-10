package com.sios.fotos

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.floor
import kotlin.math.roundToInt

/** Las cifras con ancho fijo, para que el número no baile al deslizar. */
internal val TextoCifra = Texto.Subtitulo.copy(fontFeatureSettings = "tnum")

/** Toques de la háptica: suave en cada marca, más firme al pasar por cero o llegar al tope. */
internal object Haptica {
    fun marca(v: android.view.View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }
    fun firme(v: android.view.View) {
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.KEYBOARD_TAP)
    }
    fun hecho(v: android.view.View) = v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
}

/**
 * Deslizador de precisión: una regla que se arrastra bajo una aguja fija (como en las apps de
 * fotos). Cada marca es una unidad de «paso»; vibra suave cada 5 unidades y firme al pasar por el
 * cero o llegar a un tope. Doble toque: vuelve a cero.
 */
@Composable
internal fun Regla(
    valor: Float,
    minimo: Float,
    maximo: Float,
    onCambio: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onEmpezar: () -> Unit = {},
    onTerminar: () -> Unit = {},
    etiqueta: String? = null,
    sufijo: String = "",
    /** Unidades por marca pequeña. */
    paso: Float = 2f,
    /** Separación entre marcas pequeñas. */
    separacion: Float = 7f,
    cero: Float = 0f,
) {
    val vista = LocalView.current
    val densidad = androidx.compose.ui.platform.LocalDensity.current.density
    val pxPorUnidad = separacion * densidad / paso
    val actual by rememberUpdatedState(valor)
    var interno by remember { mutableFloatStateOf(valor) }
    val cambio by rememberUpdatedState(onCambio)
    val acento = Colores.Acento
    val marcaColor = Colores.Texto3
    val marcaFuerte = Colores.Texto2
    val mostrado = valor.roundToInt()
    val activo = mostrado != cero.roundToInt()
    val colorCifra by animateColorAsState(if (activo) Colores.Acento else Colores.Texto2, Muelle.suave(), label = "cifra")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (etiqueta != null) Text(etiqueta, style = Texto.Pequeno, color = Colores.Texto2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            val signo = if (mostrado > 0 && minimo < 0) "+" else ""
            Text("$signo$mostrado$sufijo", style = TextoCifra, color = colorCifra, textAlign = TextAlign.Center, modifier = Modifier.width(64.dp))
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(minimo, maximo) {
                    detectTapGestures(onDoubleTap = {
                        Haptica.firme(vista)
                        onEmpezar(); cambio(cero); onTerminar()
                    })
                }
                .pointerInput(minimo, maximo, pxPorUnidad) {
                    detectHorizontalDragGestures(
                        onDragStart = { interno = actual; onEmpezar() },
                        onDragEnd = { onTerminar() },
                        onDragCancel = { onTerminar() },
                    ) { cambioPuntero, dx ->
                        cambioPuntero.consume()
                        val antes = interno.roundToInt()
                        interno = (interno - dx / pxPorUnidad).coerceIn(minimo, maximo)
                        val despues = interno.roundToInt()
                        if (despues != antes) {
                            // Pasar por el cero (o quedarse en él) y los topes, firme; cada 5, suave.
                            val cruzaCero = sign(antes - cero) != sign(despues - cero)
                            when {
                                cruzaCero || despues.toFloat() == minimo || despues.toFloat() == maximo -> Haptica.firme(vista)
                                Math.floorDiv(antes, 5) != Math.floorDiv(despues, 5) -> Haptica.marca(vista)
                            }
                            cambio(despues.toFloat())
                        }
                    }
                },
        ) {
            val centro = size.width / 2
            val medio = size.height / 2
            // Las marcas visibles, desplazadas con el valor (la aguja se queda en el centro).
            val unidades = (size.width / 2 / pxPorUnidad).toInt() + 2
            var u = (floor((valor - unidades) / paso) * paso)
            while (u <= valor + unidades) {
                if (u >= minimo - 0.001f && u <= maximo + 0.001f) {
                    val x = centro + (u - valor) * pxPorUnidad
                    val grande = abs(u.roundToInt()) % 10 == 0
                    val esCero = u.roundToInt() == cero.roundToInt()
                    val alto = when { esCero -> 22.dp.toPx(); grande -> 15.dp.toPx(); else -> 8.dp.toPx() }
                    // Se desvanecen hacia los bordes.
                    val alfa = (1f - abs(x - centro) / centro).coerceIn(0f, 1f)
                    drawLine(
                        (if (grande || esCero) marcaFuerte else marcaColor).copy(alpha = 0.25f + 0.75f * alfa),
                        Offset(x, medio - alto / 2), Offset(x, medio + alto / 2),
                        strokeWidth = if (esCero) 2.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
                u += paso
            }
            // La aguja.
            drawLine(acento, Offset(centro, 2.dp.toPx()), Offset(centro, size.height - 2.dp.toPx()), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

/** Botón en forma de píldora (proporciones, fondos, sugerencias). */
@Composable
internal fun Pildora(texto: String, elegida: Boolean, modifier: Modifier = Modifier, icono: ImageVector? = null, onClick: () -> Unit) {
    val fondo by animateColorAsState(if (elegida) Colores.Acento else Colores.Superficie2, Muelle.suave(), label = "pildora")
    val tinta by animateColorAsState(if (elegida) Colores.SobreAcento else Colores.Texto, Muelle.suave(), label = "pildoraTexto")
    Row(
        modifier
            .height(34.dp)
            .clip(Formas.Pastilla)
            .background(fondo)
            .pulsable(onClick)
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icono != null) {
            Icon(icono, null, tint = tinta, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(texto, style = Texto.Pequeno, color = tinta, maxLines = 1)
    }
}

/**
 * Un ajuste en la fila de diales: su icono en un círculo con un arco que marca cuánto se ha movido.
 */
@Composable
internal fun Dial(a: Ajuste, valor: Float, elegido: Boolean, onClick: () -> Unit) {
    val escala by animateFloatAsState(if (elegido) 1f else 0.9f, Muelle.vivo(), label = "dial")
    val tinta by animateColorAsState(if (elegido) Colores.Texto else Colores.Texto3, Muelle.suave(), label = "dialTinta")
    val arco by animateFloatAsState(valor / 100f, Muelle.suave(), label = "arco")
    val acento = Colores.Acento
    val pista = Colores.Borde
    val fondo by animateColorAsState(if (elegido) Colores.Superficie2 else Color.Transparent, Muelle.suave(), label = "dialFondo")
    Column(
        Modifier.width(68.dp).pulsable(onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(50.dp).scale(escala), contentAlignment = Alignment.Center) {
            Box(Modifier.size(50.dp).clip(Formas.Pastilla).background(fondo))
            Canvas(Modifier.size(50.dp)) {
                val grosor = 2.5.dp.toPx()
                val tam = Size(size.width - grosor, size.height - grosor)
                val origen = Offset(grosor / 2, grosor / 2)
                drawArc(pista, 0f, 360f, false, origen, tam, style = Stroke(grosor))
                if (arco != 0f) drawArc(acento, -90f, 360f * arco, false, origen, tam, style = Stroke(grosor, cap = StrokeCap.Round))
            }
            Icon(IconosFotos.de(a), null, tint = tinta, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(a.nombre), style = Texto.Pequeno.copy(fontSize = Texto.Etiqueta.fontSize), color = tinta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Herramienta de la barra inferior: icono y nombre, con un punto bajo la elegida. */
@Composable
internal fun BotonHerramienta(icono: ImageVector, nombre: String, elegida: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tinta by animateColorAsState(if (elegida) Colores.Texto else Colores.Texto3, Muelle.suave(), label = "herramienta")
    val punto by animateFloatAsState(if (elegida) 1f else 0f, Muelle.vivo(), label = "punto")
    val acento = Colores.Acento
    Column(modifier.pulsable(onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icono, nombre, tint = tinta, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(nombre, style = Texto.Pequeno.copy(fontSize = Texto.Etiqueta.fontSize), color = tinta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.size(4.dp)) { drawCircle(acento, radius = size.minDimension / 2 * punto) }
    }
}

/** Barra de progreso fina con muelle. */
@Composable
internal fun BarraProgreso(fraccion: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraccion.coerceIn(0f, 1f), Muelle.suave(), label = "progreso")
    Box(modifier.height(4.dp).clip(Formas.Pastilla).background(Colores.Borde)) {
        Box(Modifier.fillMaxWidth(f).height(4.dp).clip(Formas.Pastilla).background(Colores.Acento))
    }
}

/** Una fila con espaciado regular (para los paneles). */
@Composable
internal fun FilaCentrada(contenido: @Composable () -> Unit) =
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) { contenido() }

@Composable
internal fun textoTamano(bytes: Long): String = stringResource(R.string.fotos_mb, (bytes / 1_000_000.0).roundToInt())
