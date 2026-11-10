package com.sios.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.togetherWith
import com.sios.R
import com.sios.chat.ChatActivity
import com.sios.datos.Creacion
import com.sios.datos.Servicio

/** Acentos que puede elegir Claude: el naranja de Claude y cinco tonos apagados que casan con él. */
fun colorAcento(nombre: String): Color = when (nombre) {
    "verde" -> Color(0xFF7CBF8E)
    "azul" -> Color(0xFF7AA7E0)
    "amarillo" -> Color(0xFFE8C46A)
    "rojo" -> Color(0xFFE0816F)
    "gris" -> Colores.Texto2
    else -> Colores.Acento
}

private val FormaWidget = RoundedCornerShape(20.dp)

/** «hace 5 min», en el idioma del móvil. */
@Composable
private fun haceCuanto(ms: Long): String =
    if (System.currentTimeMillis() - ms < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.creacion_ahora)
    else DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

/**
 * Widget de Claude (inicio o panel). Plantillas fijas: cifra, lista, texto, progreso, noticias,
 * agenda, filas y anillos; o html, de diseño libre (lo dibuja Claude dentro de un marco del sistema).
 * tamano: en la rejilla del panel (pequeno 1×1, mediano 2×1, grande 2×2) la tarjeta tiene un tamaño
 * fijo y el contenido se adapta a él; null en el inicio, donde crece con su contenido.
 * conLargo: false en el panel (allí la pulsación larga es la de arrastrar).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetCreacion(
    c: Creacion, onToque: () -> Unit, onLargo: () -> Unit, modifier: Modifier = Modifier,
    tamano: String? = null, conLargo: Boolean = true, hijosDe: (Creacion) -> List<Creacion> = { emptyList() },
) {
    // Pila: sus widgets, uno encima de otro; cada uno pinta su propia tarjeta.
    if (c.plantilla == "pila") {
        Box(modifier.fillMaxWidth().then(if (tamano != null) Modifier.fillMaxHeight() else Modifier.height(170.dp))) {
            PilaWidget(c, hijosDe(c), tamano ?: "mediano", onMenu = onToque)
        }
        return
    }
    val acento = colorAcento(c.acento)
    val ctx = LocalContext.current
    val abrir = { url: String -> abrirEnlace(ctx, url) }
    val fijo = tamano != null
    val pequeno = tamano == "pequeno"
    // Cuántas filas caben en cada tamaño (en el inicio, como antes).
    fun caben(pq: Int, md: Int, gr: Int, inicio: Int) = when (tamano) { "pequeno" -> pq; "mediano" -> md; "grande" -> gr; else -> inicio }
    Column(
        modifier
            .fillMaxWidth()
            .then(if (fijo) Modifier.fillMaxHeight() else Modifier)
            .clip(FormaWidget)
            .background(Colores.Superficie)
            .border(1.dp, Colores.Borde, FormaWidget)
            .combinedClickable(remember { MutableInteractionSource() }, null, onClick = onToque, onLongClick = if (conLargo) onLargo else null)
            .padding(if (c.plantilla == "html") 0.dp else if (pequeno) 14.dp else 16.dp)
            .then(if (fijo) Modifier else Modifier.animateContentSize(Muelle.suave())),
    ) {
        if (c.plantilla == "html") {
            WidgetHtml(c, if (conLargo) onLargo else null, tamano)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(Formas.Pastilla).background(if (c.error != null) Colores.Rojo else acento))
            Spacer(Modifier.width(8.dp))
            Text(
                (c.titulo ?: c.nombre).uppercase(),
                style = Texto.Etiqueta,
                color = Colores.Texto3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!pequeno) c.ultima?.let { Text(haceCuanto(it), style = Texto.Pequeno.copy(fontSize = 11.sp), color = Colores.Texto3, maxLines = 1) }
        }
        Spacer(Modifier.height(if (pequeno) 8.dp else 10.dp))
        // En tamaño fijo, el cuerpo ocupa el resto y nunca se sale: lo que no cabe no se dibuja.
        Box(if (fijo) Modifier.weight(1f).fillMaxWidth().clipToBounds() else Modifier.fillMaxWidth()) {
            CuerpoWidget(c, acento, tamano, ::caben, abrir)
        }
        if (c.botones.isNotEmpty()) BotonesWidget(c, tamano, acento)
    }
}

@Composable
private fun CuerpoWidget(c: Creacion, acento: Color, tamano: String?, caben: (Int, Int, Int, Int) -> Int, abrir: (String) -> Unit) {
    val pequeno = tamano == "pequeno"
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    val vista = androidx.compose.ui.platform.LocalView.current
    // Tocar una fila: su acción (o tacharla, en las listas con casillas; o abrir su enlace).
    @Composable
    fun alTocar(i: Int, e: com.sios.datos.ElementoWidget): Modifier = when {
        e.accion != null || c.marcable -> Modifier.pulsable {
            vista.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            ejecutarAccion(ctx, alcance, c, e.accion, elemento = i)
        }
        e.enlace != null -> Modifier.pulsable { abrir(e.enlace) }
        else -> Modifier
    }
    val vacio = c.valor == null && c.texto == null && c.elementos.isEmpty() && c.progreso == null && c.serie.isEmpty() && c.local == null
    when {
        vacio -> Text(c.error ?: stringResource(R.string.creacion_sin_datos), style = Texto.Cuerpo.copy(fontSize = 14.sp), color = if (c.error != null) Colores.Rojo else Colores.Texto3, maxLines = caben(4, 3, 6, 3), overflow = TextOverflow.Ellipsis)
        c.elementos.isEmpty() && c.plantilla in listOf("lista", "noticias", "agenda", "filas", "anillos") ->
            Text(c.texto ?: "", style = Texto.Cuerpo.copy(fontSize = 14.sp), color = Colores.Texto2, maxLines = caben(4, 3, 8, 4), overflow = TextOverflow.Ellipsis)
        c.plantilla == "cifra" -> Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    c.valor ?: "—",
                    style = Texto.Gigante.copy(fontSize = if (pequeno) 38.sp else 44.sp, letterSpacing = (-1).sp),
                    color = Colores.Texto, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                c.unidad?.let {
                    Spacer(Modifier.width(4.dp))
                    Text(it, style = Texto.Subtitulo, color = Colores.Texto2, maxLines = 1, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            c.subtitulo?.let { Text(it, style = Texto.Pequeno, color = Colores.Texto2, maxLines = caben(3, 2, 4, 2), overflow = TextOverflow.Ellipsis) }
        }
        c.plantilla == "lista" -> QueQuepan(if (pequeno) 6.dp else 8.dp) {
            c.elementos.take(caben(8, 8, 12, 5)).forEachIndexed { i, e ->
                Row(Modifier.fillMaxWidth().clip(Formas.Pequena).then(alTocar(i, e)), verticalAlignment = Alignment.CenterVertically) {
                    if (c.marcable) Casilla(e.hecho == true, acento) else Box(Modifier.size(5.dp).clip(Formas.Pastilla).background(acento))
                    Spacer(Modifier.width(if (c.marcable) 10.dp else if (pequeno) 8.dp else 10.dp))
                    Text(
                        e.texto, style = Texto.Cuerpo.copy(fontSize = if (pequeno) 13.sp else 14.sp, textDecoration = if (e.hecho == true) androidx.compose.ui.text.style.TextDecoration.LineThrough else null),
                        color = if (e.hecho == true) Colores.Texto3 else Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (!pequeno) e.detalle?.let { Spacer(Modifier.width(8.dp)); Text(it, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1) }
                }
            }
        }
        // Noticias: titular, resumen de una línea (si aporta) y fuente · hora; tocar abre la noticia.
        c.plantilla == "noticias" -> QueQuepan(if (tamano == "mediano") 8.dp else 10.dp) {
            c.elementos.take(caben(3, 4, 6, 3)).forEachIndexed { i, e ->
                Column(Modifier.fillMaxWidth().clip(Formas.Pequena).then(if (e.enlace != null) Modifier.pulsable { abrir(e.enlace) } else Modifier)) {
                    if (i > 0) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.Borde))
                        Spacer(Modifier.height(if (tamano == "mediano") 8.dp else 10.dp))
                    }
                    Text(
                        e.texto, style = Texto.Cuerpo.copy(fontSize = if (pequeno) 14.sp else 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
                        color = Colores.Texto, maxLines = caben(4, 1, 2, 2), overflow = TextOverflow.Ellipsis,
                    )
                    // Sin el resumen si solo repite el titular (y nunca en los tamaños pequeños).
                    if (tamano == null || tamano == "grande") e.resumen?.takeUnless { r -> r.trim().trimEnd('.').equals(e.texto.trim().trimEnd('.'), ignoreCase = true) || e.texto.startsWith(r.trim().trimEnd('.').take(40)) }?.let {
                        Spacer(Modifier.height(3.dp))
                        Text(it, style = Texto.Pequeno, color = Colores.Texto2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val pie = listOfNotNull(e.fuente, e.hora).joinToString(" · ")
                    if (pie.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(pie, style = Texto.Pequeno.copy(fontSize = 11.sp), color = acento, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // Agenda: hora a la izquierda (en el acento), lo que es y dónde. En pequeño, la hora encima.
        c.plantilla == "agenda" -> {
            // La columna de la hora, tan ancha como la hora más larga (nada cortado: «Mañana 21:00»).
            val estiloHora = Texto.Pequeno.copy(fontWeight = FontWeight.Medium)
            val medidor = androidx.compose.ui.text.rememberTextMeasurer()
            val densidad = androidx.compose.ui.platform.LocalDensity.current
            val anchoHora = remember(c.elementos) {
                with(densidad) { (c.elementos.maxOfOrNull { medidor.measure(it.hora ?: "", estiloHora).size.width } ?: 0).toDp() + 12.dp }.coerceIn(44.dp, 120.dp)
            }
            QueQuepan(if (pequeno) 8.dp else if (tamano == "mediano") 8.dp else 10.dp) {
                c.elementos.take(caben(6, 6, 10, 5)).forEachIndexed { i, e ->
                    if (pequeno) Column(Modifier.fillMaxWidth().clip(Formas.Pequena).then(alTocar(i, e))) {
                        Text(e.hora ?: "", style = Texto.Pequeno.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = acento, maxLines = 1)
                        Text(e.texto, style = Texto.Cuerpo.copy(fontSize = 13.sp, lineHeight = 17.sp), color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } else Row(Modifier.fillMaxWidth().clip(Formas.Pequena).then(alTocar(i, e))) {
                        Text(e.hora ?: "", style = estiloHora, color = acento, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(anchoHora).padding(top = 1.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.texto, style = Texto.Cuerpo.copy(fontSize = 14.sp), color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // En mediano el detalle solo cabe si hay pocas filas (con una o dos, se ve).
                            if (tamano != "mediano" || c.elementos.size <= 2) e.detalle?.let { Text(it, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
        // Filas: nombre y detalle, con el valor a la derecha (precios, estados…).
        // En mediano, letra algo más compacta: caben tres filas con su detalle.
        c.plantilla == "filas" -> QueQuepan(if (pequeno) 6.dp else if (tamano == "mediano") 7.dp else 10.dp) {
            val compacta = pequeno || tamano == "mediano"
            c.elementos.take(caben(6, 6, 10, 5)).forEachIndexed { i, e ->
                Row(
                    Modifier.fillMaxWidth().clip(Formas.Pequena).then(alTocar(i, e)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.texto, style = Texto.Cuerpo.copy(fontSize = if (compacta) 13.sp else 14.sp, lineHeight = if (compacta) 17.sp else 21.sp), color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!pequeno) e.detalle?.let { Text(it, style = Texto.Pequeno.copy(fontSize = if (compacta) 11.sp else 13.sp, lineHeight = if (compacta) 14.sp else 18.sp), color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    e.valor?.let {
                        Spacer(Modifier.width(if (pequeno) 6.dp else 12.dp))
                        Text(it, style = Texto.Cuerpo.copy(fontSize = if (compacta) 13.sp else 14.sp, fontWeight = FontWeight.Medium), color = Colores.Texto2, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        // Gráfico: la cifra y su tendencia (en pequeño, la cifra y una línea mínima).
        c.plantilla == "grafico" -> Column(if (tamano != null) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(c.valor ?: "—", style = Texto.Gigante.copy(fontSize = if (pequeno) 26.sp else 30.sp, letterSpacing = (-0.5).sp), color = Colores.Texto, maxLines = 1, softWrap = false)
                c.unidad?.let { Spacer(Modifier.width(4.dp)); Text(it, style = Texto.Pequeno, color = Colores.Texto2, maxLines = 1, modifier = Modifier.padding(bottom = 5.dp)) }
            }
            if (!pequeno) c.subtitulo?.let { Text(it, style = Texto.Pequeno.copy(fontSize = 12.sp), color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(8.dp))
            Grafico(c.serie, c.tipoGrafico, acento, if (tamano != null) Modifier.weight(1f).fillMaxWidth() else Modifier.height(90.dp).fillMaxWidth(), etiquetas = tamano == "grande" || tamano == null)
        }
        c.plantilla == "marcador" -> Column {
            Marcador(c, acento, tamano)
            if (tamano == "grande" || tamano == null) {
                Spacer(Modifier.height(12.dp))
                QueQuepan(8.dp) {
                    c.elementos.forEach { e ->
                        Row {
                            Text(e.hora ?: "", style = Texto.Pequeno.copy(fontWeight = FontWeight.Medium), color = acento, maxLines = 1, modifier = Modifier.width(84.dp))
                            Text(e.texto, style = Texto.Cuerpo.copy(fontSize = 13.sp), color = Colores.Texto2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        // Anillos (baterías y similares): como el widget de baterías del iPhone.
        c.plantilla == "anillos" -> Anillos(c.elementos, acento, tamano)
        c.plantilla == "progreso" -> Column {
            val p by animateFloatAsState((c.progreso ?: 0f).coerceIn(0f, 1f), Muelle.suave(), label = "progreso")
            Text(c.valor ?: "${(p * 100).toInt()} %", style = Texto.Titulo.copy(fontSize = if (pequeno) 26.sp else 20.sp), color = Colores.Texto, maxLines = 1)
            c.subtitulo?.let { Text(it, style = Texto.Pequeno, color = Colores.Texto2, maxLines = caben(2, 1, 2, 1), overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(Formas.Pastilla).background(Colores.Superficie2)) {
                Box(Modifier.fillMaxWidth(p).height(6.dp).clip(Formas.Pastilla).background(acento))
            }
        }
        else -> Text(c.texto ?: c.valor ?: "", style = Texto.Cuerpo.copy(fontSize = if (pequeno) 13.sp else 15.sp, lineHeight = if (pequeno) 18.sp else 22.sp), color = Colores.Texto, maxLines = caben(5, 4, 11, 6), overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Coloca las filas una debajo de otra mientras caben enteras; las que no caben no se dibujan (nada
 * cortado a medias). Sin límite de alto (en el inicio), las coloca todas.
 */
@Composable
private fun QueQuepan(separacion: androidx.compose.ui.unit.Dp, contenido: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(contenido, Modifier.fillMaxWidth()) { medibles, limites ->
        val hueco = separacion.roundToPx()
        val libres = limites.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val colocables = mutableListOf<androidx.compose.ui.layout.Placeable>()
        var alto = 0
        for (m in medibles) {
            val p = m.measure(libres)
            val siguiente = alto + (if (colocables.isEmpty()) 0 else hueco) + p.height
            if (limites.hasBoundedHeight && siguiente > limites.maxHeight) break
            alto = siguiente
            colocables += p
        }
        layout(limites.maxWidth, alto) {
            var y = 0
            colocables.forEachIndexed { i, p -> if (i > 0) y += hueco; p.place(0, y); y += p.height }
        }
    }
}

/** Anillos de progreso 0–100 (batería del móvil, cascos, reloj…). Rojo por debajo del 20 %. */
@Composable
private fun Anillos(elementos: List<com.sios.datos.ElementoWidget>, acento: Color, tamano: String?) {
    @Composable
    fun Anillo(e: com.sios.datos.ElementoWidget, tam: androidx.compose.ui.unit.Dp, conCifra: Boolean) {
        val v = e.valor?.filter { it.isDigit() }?.toIntOrNull()
        val p by animateFloatAsState(((v ?: 0) / 100f).coerceIn(0f, 1f), Muelle.suave(), label = "anillo")
        val color = if (v != null && v < 20) Colores.Rojo else acento
        Box(Modifier.size(tam), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                val grosor = size.minDimension * 0.11f
                val estilo = androidx.compose.ui.graphics.drawscope.Stroke(grosor, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                val margen = grosor / 2
                val area = androidx.compose.ui.geometry.Size(size.width - grosor, size.height - grosor)
                drawArc(Colores.Superficie2, 0f, 360f, false, topLeft = androidx.compose.ui.geometry.Offset(margen, margen), size = area, style = estilo)
                if (v != null) drawArc(color, -90f, 360f * p, false, topLeft = androidx.compose.ui.geometry.Offset(margen, margen), size = area, style = estilo)
            }
            if (conCifra) Text(if (v != null) "$v" else "–", style = Texto.Subtitulo.copy(fontSize = (tam.value * 0.26f).sp), color = Colores.Texto, maxLines = 1)
            else Icon(if (e.texto.contains("móvil", true) || e.texto.contains("phone", true)) Iconos.Telefono else Iconos.Mundo, null, tint = Colores.Texto2, modifier = Modifier.size(tam * 0.36f))
        }
    }
    when (tamano) {
        // Pequeño con uno solo (el móvil): un anillo grande con su nombre debajo.
        "pequeno" -> if (elementos.size == 1) Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            val e = elementos.first()
            Anillo(e, 76.dp, conCifra = true)
            Spacer(Modifier.height(8.dp))
            Text(listOfNotNull(e.texto, e.detalle).joinToString(" · "), style = Texto.Pequeno.copy(fontSize = 12.sp), color = Colores.Texto2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
            elementos.take(4).chunked(2).forEach { fila ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    fila.forEach { Anillo(it, 52.dp, conCifra = true) }
                    if (fila.size == 1 && elementos.size > 1) Spacer(Modifier.size(52.dp))
                }
            }
        }
        // Mediano: hasta 4 en fila, con el nombre y el porcentaje debajo.
        "mediano" -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            elementos.take(4).forEach { e ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Anillo(e, 54.dp, conCifra = false)
                    Spacer(Modifier.height(6.dp))
                    Text(e.valor?.let { "$it %" } ?: "–", style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Texto, maxLines = 1)
                }
            }
        }
        // Grande (y el inicio): una fila por dispositivo.
        else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            elementos.take(6).forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Anillo(e, 34.dp, conCifra = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.texto, style = Texto.Cuerpo.copy(fontSize = 14.sp), color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        e.detalle?.let { Text(it, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1) }
                    }
                    Text(e.valor?.let { "$it %" } ?: "–", style = Texto.Cuerpo.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = Colores.Texto2, maxLines = 1)
                }
            }
        }
    }
}

/** Abre un enlace (noticia, web…) en su app o en el navegador. */
fun abrirEnlace(ctx: android.content.Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Widget de diseño libre: la página que sirve el servicio (/widgets/<id>/) en un WebView
 * transparente que se ajusta al alto de su contenido. Si cambian los datos, se le pasan sin recargar;
 * si Claude cambia el diseño, se recarga.
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WidgetHtml(c: Creacion, onLargo: (() -> Unit)?, tamano: String?) {
    val ctx = LocalContext.current
    var alto by remember(c.id) { mutableStateOf(c.alto ?: 96) }
    // Nunca se enseña una página de error ni una a medio cargar: invisible hasta que carga bien y,
    // si falla (el servicio reiniciándose…), se reintenta sola.
    var lista by remember(c.id) { mutableStateOf(false) }
    val aparecer by animateFloatAsState(if (lista) 1f else 0f, Muelle.suave(), label = "aparecer")
    val web = remember(c.id) {
        android.webkit.WebView(ctx).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = android.view.View.OVER_SCROLL_NEVER
            isHapticFeedbackEnabled = false
            if (onLargo != null) setOnLongClickListener { onLargo(); true } else isLongClickable = false
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun alto(px: Int) = post { if (c.alto == null) alto = px.coerceIn(40, 640) }

                /** Cómo ha quedado en pantalla: el servicio se lo cuenta a Claude si no cabe. */
                @android.webkit.JavascriptInterface
                fun medida(json: String) { Servicio.anotarMedida(c.id, json) }
            }, "SiosWidget")
            webViewClient = object : android.webkit.WebViewClient() {
                private var fallo = false
                private var intentos = 0
                override fun onPageStarted(v: android.webkit.WebView, url: String?, f: android.graphics.Bitmap?) {
                    if (url?.startsWith("http") == true) fallo = false
                }
                override fun onReceivedError(v: android.webkit.WebView, r: android.webkit.WebResourceRequest, e: android.webkit.WebResourceError) {
                    if (!r.isForMainFrame) return
                    fallo = true
                    lista = false
                    val url = r.url.toString()
                    v.loadUrl("about:blank")
                    if (intentos++ < 30) v.postDelayed({ v.loadUrl(url) }, (1500L * intentos).coerceAtMost(10_000L))
                }
                override fun onPageFinished(v: android.webkit.WebView, url: String?) {
                    if (url?.startsWith("http") == true && !fallo) { intentos = 0; lista = true }
                }
                override fun shouldOverrideUrlLoading(v: android.webkit.WebView, r: android.webkit.WebResourceRequest): Boolean {
                    val url = r.url.toString()
                    when {
                        url.startsWith("sios-abrir:") -> abrirEnlace(ctx, android.net.Uri.decode(url.removePrefix("sios-abrir:")))
                        r.url.host == "127.0.0.1" -> return false
                        else -> abrirEnlace(ctx, url)
                    }
                    return true
                }
            }
        }
    }
    DisposableEffect(web) { onDispose { web.destroy() } }
    // El tamaño de la celda, el tema y el acento van en la dirección: la página se pinta igual que la app.
    val claro = Apariencia.claro
    val acento = Apariencia.acento
    LaunchedEffect(c.htmlHuella, tamano, claro, acento) {
        web.loadUrl("http://127.0.0.1:8765/widgets/${c.id}/?tema=${if (claro) "claro" else "oscuro"}&acento=$acento" + (tamano?.let { "&tamano=$it" } ?: ""))
    }
    LaunchedEffect(c.datosJson) { web.evaluateJavascript("window.sios && sios._nuevos && sios._nuevos(${c.datosJson})", null) }
    androidx.compose.ui.viewinterop.AndroidView(
        { web },
        (if (tamano != null) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height((c.alto ?: alto).dp))
            .graphicsLayer { alpha = aparecer },
    )
}

/** Icono de una mini-app en el cajón: la misma baldosa del pack con su glifo en el acento elegido. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IconoCreacion(c: Creacion, tam: Int, onClick: () -> Unit, onLargo: () -> Unit) {
    Column(
        Modifier
            .clip(Formas.Pequena)
            .combinedClickable(remember { MutableInteractionSource() }, null, onClick = onClick, onLongClick = onLargo)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(tam.dp)
                .clip(Pack.Forma)
                .background(Colores.Superficie)
                .border(1.dp, Colores.Borde, Pack.Forma),
            contentAlignment = Alignment.Center,
        ) {
            val glifo = ICONOS_CREACIONES[c.icono] ?: ICONOS_CREACIONES.getValue("sparkles")
            Icon(Pack.vector(glifo), c.nombre, tint = colorAcento(c.acento), modifier = Modifier.size((tam * 0.5f).dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            c.nombre,
            style = Texto.Pequeno.copy(fontSize = 12.sp),
            color = Colores.Texto2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width((tam + 16).dp),
        )
    }
}

/**
 * Hoja para cambiar una creación con una frase («ponlo en verde», «añade el tiempo de mañana»).
 * Se lo pide a Claude en una conversación nueva y abre el chat para ver cómo lo hace y confirmar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaCreacion(c: Creacion, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    var peticion by rememberSaveable(c.id) { mutableStateOf("") }
    var compartiendo by rememberSaveable(c.id) { mutableStateOf(false) }
    val tipo = stringResource(
        when (c.tipo) {
            "app" -> R.string.creacion_tipo_app
            "agente" -> R.string.creacion_tipo_agente
            "rutina" -> R.string.creacion_tipo_rutina
            else -> R.string.creacion_tipo_widget
        },
    )
    val mensaje = stringResource(R.string.creacion_peticion, tipo, c.nombre, c.id, peticion.trim())
    ModalBottomSheet(
        onDismissRequest = onCerrar,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Colores.Lateral,
        contentColor = Colores.Texto,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.nombre, style = Texto.Subtitulo, color = Colores.Texto)
                    val nota = buildList {
                        add(tipo)
                        c.cada?.let { add(stringResource(R.string.creacion_cada, it)) }
                        if (c.pausado) add(stringResource(R.string.creacion_pausado))
                    }.joinToString(" · ")
                    Text(nota, style = Texto.Pequeno, color = Colores.Texto3)
                }
                // Compartir (enlace y QR); con el QR a la vista, el mismo botón vuelve.
                Box(
                    Modifier.size(40.dp).clip(Formas.Pastilla).background(if (compartiendo) Colores.Acento else Colores.Superficie)
                        .pulsable { compartiendo = !compartiendo },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (compartiendo) Iconos.Cruz else Iconos.Compartir, stringResource(R.string.compartir_titulo),
                        tint = if (compartiendo) Colores.SobreAcento else Colores.Texto2, modifier = Modifier.size(18.dp),
                    )
                }
            }
            androidx.compose.animation.AnimatedContent(
                compartiendo,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(Muelle.suave()) + androidx.compose.animation.scaleIn(Muelle.suave(), initialScale = 0.96f)) togetherWith
                        androidx.compose.animation.fadeOut(Muelle.suave())
                },
                label = "compartir",
            ) { qr ->
            if (qr) BloqueCompartir(c) else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            c.error?.let { Text(stringResource(R.string.creacion_error, it), style = Texto.Pequeno, color = Colores.Rojo) }
            // En el panel: tamaño en la rejilla, al momento (como en el iPhone).
            if (c.tipo == "widget" && (c.lugar == "panel" || c.lugar == "inicio")) {
                Text(stringResource(R.string.creacion_tamano), style = Texto.Etiqueta, color = Colores.Texto3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("pequeno" to R.string.tamano_pequeno, "mediano" to R.string.tamano_mediano, "grande" to R.string.tamano_grande).forEach { (t, nombre) ->
                        val elegido = c.tamano == t
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(Formas.Pequena)
                                .background(if (elegido) Colores.Acento else Colores.Superficie)
                                .border(1.dp, if (elegido) Colores.Acento else Colores.Borde, Formas.Pequena)
                                .pulsable { Servicio.cambiarTamano(c.id, t) },
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(nombre), style = Texto.Pequeno, color = if (elegido) Colores.SobreAcento else Colores.Texto2) }
                    }
                }
            }
            if (c.tipo == "agente") c.resultado?.let { Text(it, style = Texto.Cuerpo, color = Colores.Texto2, maxLines = 6) }

            Text(stringResource(R.string.creacion_cambiar_titulo), style = Texto.Etiqueta, color = Colores.Texto3)
            BasicTextField(
                value = peticion,
                onValueChange = { peticion = it },
                textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
                cursorBrush = SolidColor(Colores.Acento),
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Formas.Pequena)
                    .background(Colores.Superficie)
                    .border(1.dp, Colores.Borde, Formas.Pequena)
                    .padding(14.dp),
                decorationBox = { campo ->
                    Box {
                        if (peticion.isEmpty()) Text(stringResource(R.string.creacion_cambiar_pista), style = Texto.Cuerpo, color = Colores.Texto3)
                        campo()
                    }
                },
            )
            BotonPrimario(stringResource(R.string.creacion_enviar), Modifier.fillMaxWidth()) {
                if (peticion.isBlank()) return@BotonPrimario
                Servicio.preguntar(mensaje)
                ctx.startActivity(Intent(ctx, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                onCerrar()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.tipo != "app" && (c.tipo == "agente" || c.cada != null)) {
                    BotonSecundario(stringResource(R.string.creacion_ejecutar), Modifier.weight(1f)) { Servicio.ejecutarCreacion(c.id) }
                    BotonSecundario(stringResource(if (c.pausado) R.string.creacion_reanudar else R.string.creacion_pausar), Modifier.weight(1f)) {
                        Servicio.pausarCreacion(c.id)
                    }
                }
                Text(
                    stringResource(R.string.creacion_quitar),
                    style = Texto.Subtitulo.copy(fontWeight = FontWeight.Medium),
                    color = Colores.Rojo,
                    modifier = Modifier
                        .clip(Formas.Pequena)
                        .pulsable { Servicio.borrarCreacion(c.id); onCerrar() }
                        .padding(horizontal = 16.dp, vertical = 13.dp),
                )
            }
            }
            }
        }
    }
}
