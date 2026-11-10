package com.sios.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.datos.Accion
import com.sios.datos.Boton
import com.sios.datos.Creacion
import com.sios.datos.Punto
import com.sios.datos.Servicio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- Avisos de los botones ----------

/** El aviso que deja un botón de widget («Hecho», la respuesta de Claude…): lo enseña el panel. */
object AvisoWidget {
    val texto = mutableStateOf<String?>(null)
    fun mostrar(t: String) { texto.value = t }
}

// ---------- Acciones ----------

/**
 * Ejecuta la acción de un botón o una fila. Lo que se resuelve en el móvil (abrir un enlace, llamar,
 * navegar) lo hace la app al instante; lo demás (multimedia, tachar, casa, Claude, comandos) el
 * servicio, que solo ejecuta acciones que estén en la ficha del widget.
 */
fun ejecutarAccion(ctx: Context, alcance: CoroutineScope, c: Creacion, a: Accion?, boton: Int? = null, elemento: Int? = null) {
    when (a?.tipo) {
        "enlace" -> a.url?.let { abrirEnlace(ctx, it) }
        "llamar" -> a.numero?.let { com.sios.telefono.llamar(ctx, it) }
        "navegar" -> {
            val destino = if (a.lat != null && a.lon != null) "${a.lat},${a.lon}" else Uri.encode(a.destino ?: "")
            val navegacion = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$destino")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(navegacion) }.onFailure {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$destino")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        else -> alcance.launch {
            val aviso = Servicio.accionWidget(c.id, boton, elemento)
            if (!aviso.isNullOrBlank()) AvisoWidget.mostrar(aviso)
        }
    }
}

private fun iconoBoton(nombre: String?, a: Accion?): ImageVector? = when (nombre ?: a?.tipo) {
    "llamar" -> Iconos.Llamar
    "navegar", "ir" -> Iconos.Navegar
    "enlace", "web" -> Iconos.Enlace
    "play_pause", "multimedia", "play" -> Iconos.Reproducir
    "pausa" -> Iconos.Pausa
    "siguiente" -> Iconos.Siguiente
    "anterior" -> Iconos.Anterior
    "casa", "luz", "bombilla" -> Iconos.Bombilla
    "refrescar" -> Iconos.Refrescar
    "claude" -> Iconos.Asistente
    "pregunta" -> Iconos.Pregunta
    "hecho", "tachar" -> Iconos.Hecho
    "mensaje" -> Iconos.Enviar
    else -> null
}

/**
 * Botones al pie del widget, todos del mismo alto y ancho. En pequeño, solo iconos (y como mucho
 * dos); si un botón no tiene icono, su texto.
 */
@Composable
fun BotonesWidget(c: Creacion, tamano: String?, acento: Color) {
    val ctx = LocalContext.current
    val vista = LocalView.current
    val alcance = rememberCoroutineScope()
    val pequeno = tamano == "pequeno"
    val botones = c.botones.take(if (pequeno) 2 else 3)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        botones.forEachIndexed { i, b ->
            val icono = iconoBoton(b.icono, b.accion)
            val principal = i == 0
            Row(
                Modifier
                    .weight(1f)
                    .height(if (pequeno) 34.dp else 36.dp)
                    .clip(Formas.Pastilla)
                    .background(if (principal) acento else Colores.Superficie2)
                    .pulsable {
                        vista.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        ejecutarAccion(ctx, alcance, c, b.accion, boton = i)
                    }
                    .padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (principal) Colores.SobreAcento else Colores.Texto
                if (icono != null) Icon(icono, b.texto, tint = color, modifier = Modifier.size(16.dp))
                if (!pequeno || icono == null) b.texto?.let {
                    if (icono != null) Spacer(Modifier.width(6.dp))
                    Text(it, style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Casilla de las listas que se tachan (se marca al tocar la fila). */
@Composable
fun Casilla(hecho: Boolean, acento: Color) {
    val lleno by animateFloatAsState(if (hecho) 1f else 0f, Muelle.vivo(), label = "casilla")
    Box(
        Modifier
            .size(18.dp)
            .clip(Formas.Pastilla)
            .border(1.5.dp, if (hecho) acento else Colores.Texto3, Formas.Pastilla)
            .background(acento.copy(alpha = lleno)),
        contentAlignment = Alignment.Center,
    ) { if (hecho) Icon(Iconos.Hecho, null, tint = Colores.SobreAcento, modifier = Modifier.size(12.dp)) }
}

// ---------- Gráfico ----------

/**
 * Línea suave con relleno degradado (o barras redondeadas, la última en el acento). Sin ejes: lo
 * justo para ver la tendencia; en grande, las etiquetas de la primera y la última.
 */
@Composable
fun Grafico(serie: List<Punto>, tipo: String, acento: Color, modifier: Modifier = Modifier, etiquetas: Boolean = false) {
    if (serie.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("—", style = Texto.Pequeno, color = Colores.Texto3) }
        return
    }
    val aparecer by animateFloatAsState(1f, Muelle.suave(), label = "grafico")
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val ys = serie.map { it.y }
            val min = ys.min()
            val max = ys.max()
            val rango = (max - min).takeIf { it > 0 } ?: 1.0
            val margen = size.height * 0.1f
            fun y(v: Double) = (size.height - margen - ((v - min) / rango * (size.height - 2 * margen)).toFloat())
            if (tipo == "barras") {
                val hueco = size.width / serie.size
                val ancho = hueco * 0.62f
                serie.forEachIndexed { i, p ->
                    val alto = ((p.y - min * 0.9) / (max - min * 0.9).coerceAtLeast(1e-9) * size.height * aparecer).toFloat().coerceAtLeast(4f)
                    drawRoundRect(
                        if (i == serie.lastIndex) acento else acento.copy(alpha = 0.35f),
                        topLeft = Offset(i * hueco + (hueco - ancho) / 2, size.height - alto),
                        size = Size(ancho, alto),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(ancho / 3, ancho / 3),
                    )
                }
            } else {
                val paso = size.width / (serie.size - 1)
                val puntos = serie.mapIndexed { i, p -> Offset(i * paso, y(p.y)) }
                val linea = Path().apply {
                    moveTo(puntos[0].x, puntos[0].y)
                    for (i in 1 until puntos.size) {
                        val a = puntos[i - 1]
                        val b = puntos[i]
                        val medio = (a.x + b.x) / 2
                        cubicTo(medio, a.y, medio, b.y, b.x, b.y)
                    }
                }
                val relleno = Path().apply {
                    addPath(linea)
                    lineTo(puntos.last().x, size.height)
                    lineTo(puntos.first().x, size.height)
                    close()
                }
                drawPath(relleno, Brush.verticalGradient(listOf(acento.copy(alpha = 0.28f * aparecer), Color.Transparent)))
                drawPath(linea, acento, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawCircle(acento, 3.6.dp.toPx(), puntos.last())
                drawCircle(Colores.Superficie, 1.6.dp.toPx(), puntos.last())
            }
        }
        if (etiquetas) Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(serie.first().x, style = Texto.Pequeno.copy(fontSize = 10.sp), color = Colores.Texto3, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(serie.last().x, style = Texto.Pequeno.copy(fontSize = 10.sp), color = Colores.Texto3, maxLines = 1)
        }
    }
}

// ---------- Marcador ----------

/** Escudo sin imagen: las iniciales del equipo en un círculo. */
@Composable
private fun Escudo(nombre: String, tam: androidx.compose.ui.unit.Dp) {
    val iniciales = nombre.split(" ", "-").filter { it.isNotBlank() && it.first().isLetter() }.let { p ->
        if (p.size >= 2) "${p[0].first()}${p[1].first()}" else nombre.take(3)
    }.uppercase()
    Box(Modifier.size(tam).clip(Formas.Pastilla).background(Colores.Superficie2), contentAlignment = Alignment.Center) {
        Text(iniciales, style = Texto.Pequeno.copy(fontSize = (tam.value * 0.32f).sp, fontWeight = FontWeight.Bold), color = Colores.Texto, maxLines = 1)
    }
}

/** Un partido: equipos, marcador (o la hora si no ha empezado) y en qué va. */
@Composable
fun Marcador(c: Creacion, acento: Color, tamano: String?) {
    val local = c.local ?: return
    val visitante = c.visitante ?: return
    val pequeno = tamano == "pequeno"
    val antes = c.golesLocal == null
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Escudo(local, if (pequeno) 30.dp else 38.dp)
                Spacer(Modifier.height(5.dp))
                Text(if (pequeno) local.take(3).uppercase() else local, style = Texto.Pequeno.copy(fontSize = 12.sp), color = Colores.Texto2, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
            Text(
                if (antes) "vs" else "${c.golesLocal} – ${c.golesVisitante}",
                style = Texto.Titulo.copy(fontSize = if (antes) 18.sp else if (pequeno) 24.sp else 30.sp, fontWeight = FontWeight.SemiBold),
                color = if (antes) Colores.Texto3 else Colores.Texto, maxLines = 1,
            )
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Escudo(visitante, if (pequeno) 30.dp else 38.dp)
                Spacer(Modifier.height(5.dp))
                Text(if (pequeno) visitante.take(3).uppercase() else visitante, style = Texto.Pequeno.copy(fontSize = 12.sp), color = Colores.Texto2, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(if (pequeno) 6.dp else 8.dp))
        c.estadoPartido?.let { e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (c.enJuego) {
                    Box(Modifier.size(6.dp).clip(Formas.Pastilla).background(Colores.Rojo))
                    Spacer(Modifier.width(6.dp))
                }
                Text(e, style = Texto.Pequeno.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium), color = if (c.enJuego) Colores.Texto else Colores.Texto3, maxLines = 1)
            }
        }
    }
}

// ---------- Pila ----------

/**
 * Varios widgets en el mismo hueco, como las pilas del iPhone: se pasan deslizando en vertical y,
 * si el usuario no la está tocando, se pone sola en el que Claude (o sus reglas) dice que toca.
 * Puntos a la derecha: cuántos hay y cuál se ve.
 */
@Composable
fun PilaWidget(c: Creacion, hijos: List<Creacion>, tamano: String?, onMenu: () -> Unit) {
    if (hijos.isEmpty()) return
    val inicial = hijos.indexOfFirst { it.id == c.pilaActual }.coerceAtLeast(0)
    val estado = rememberPagerState(initialPage = inicial) { hijos.size }
    var tocadaHace by androidx.compose.runtime.remember { mutableStateOf(0L) }
    LaunchedEffect(estado) { snapshotFlow { estado.isScrollInProgress }.collect { if (it) tocadaHace = System.currentTimeMillis() } }
    // Lo que elige Claude, salvo que el usuario la haya movido hace menos de un minuto.
    LaunchedEffect(c.pilaActual, hijos.size) {
        val i = hijos.indexOfFirst { it.id == c.pilaActual }
        if (i >= 0 && i != estado.currentPage && System.currentTimeMillis() - tocadaHace > 60_000) estado.animateScrollToPage(i, animationSpec = Muelle.suave())
    }
    Box(Modifier.fillMaxSize()) {
        VerticalPager(estado, Modifier.fillMaxSize(), pageSpacing = 12.dp, key = { hijos[it].id }) { i ->
            WidgetCreacion(hijos[i], onToque = onMenu, onLargo = onMenu, tamano = tamano, conLargo = false, modifier = Modifier.fillMaxSize())
        }
        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 5.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            hijos.indices.forEach { i ->
                val activo = i == estado.currentPage
                val alto by animateFloatAsState(if (activo) 14f else 5f, Muelle.vivo(), label = "punto")
                Box(Modifier.width(5.dp).height(alto.dp).clip(Formas.Pastilla).background(if (activo) Colores.Texto2 else Colores.Texto3.copy(alpha = 0.45f)))
            }
        }
    }
}

// ---------- Aviso del panel ----------

/** La píldora que aparece abajo tras pulsar un botón y se va sola. */
@Composable
fun AvisoFlotante(modifier: Modifier = Modifier) {
    val texto by AvisoWidget.texto
    LaunchedEffect(texto) {
        if (texto != null) { delay(3800); AvisoWidget.texto.value = null }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = texto != null,
        enter = androidx.compose.animation.fadeIn(Muelle.suave()) + androidx.compose.animation.slideInVertically(Muelle.vivo()) { it / 2 },
        exit = androidx.compose.animation.fadeOut(Muelle.suave()) + androidx.compose.animation.slideOutVertically(Muelle.suave()) { it / 2 },
        modifier = modifier,
    ) {
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .clip(Formas.Tarjeta)
                .background(Colores.Superficie2)
                .border(1.dp, Colores.Borde, Formas.Tarjeta)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(texto ?: "", style = Texto.Cuerpo.copy(fontSize = 14.sp), color = Colores.Texto, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}
