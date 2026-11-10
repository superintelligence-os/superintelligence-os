package com.sios.emergencias

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.ui.Apariencia
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable

/**
 * Lo común de las pantallas de emergencias: el rojo del SOS (el mismo en claro y en oscuro, para que
 * se reconozca al momento), los iconos y las piezas de los ajustes.
 */
internal object Rojo {
    /** Fondo de la cuenta atrás: rojo intenso, con el blanco encima se lee a pleno sol. */
    val Sos = Color(0xFFC62828)
    val SosHondo = Color(0xFF9F1D1D)
    /** Rojo de los botones y tarjetas de emergencia en las pantallas normales (contrasta en las dos). */
    val Boton get() = if (Apariencia.claro) Color(0xFFC62828) else Color(0xFFD9443C)
    val Suave get() = Boton.copy(alpha = if (Apariencia.claro) 0.10f else 0.16f)
    val Verde = Color(0xFF2E9E5B)
}

/** Iconos de emergencias (mismo trazo fino de 24×24 que [com.sios.ui.Iconos]). */
internal object IconosEm {
    private fun icono(nombre: String, vararg trazos: String, rellenos: List<String> = emptyList()) =
        ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f).apply {
            for (c in trazos) addPath(
                pathData = PathParser().parsePathString(c).toNodes(),
                stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
            for (r in rellenos) addPath(pathData = PathParser().parsePathString(r).toNodes(), fill = SolidColor(Color.White))
        }.build()

    /** Cruz de emergencia dentro de un círculo. */
    val Sos = icono("sos", "M12 3a9 9 0 1 1 0 18a9 9 0 0 1 0-18z", "M12 8v8", "M8 12h8")
    val Ubicacion = icono("ubicacion", "M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11z", "M12 7.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 0 1 0-5z")
    val Corazon = icono("corazon", "M12 20s-7.5-4.6-7.5-10.2A4.3 4.3 0 0 1 12 7.2a4.3 4.3 0 0 1 7.5 2.6C19.5 15.4 12 20 12 20z", "M8 12h2l1-2 2 4 1-2h2")
    val Gota = icono("gota", "M12 3.5s6 6.4 6 10.5a6 6 0 0 1-12 0c0-4.1 6-10.5 6-10.5z")
    val Persona = icono("persona", "M12 4a3.5 3.5 0 1 1 0 7a3.5 3.5 0 0 1 0-7z", "M5 20a7 7 0 0 1 14 0")
    val Alerta = icono("alerta", "M12 4l9 16H3z", "M12 10v4", "M12 17h.01")
    val Caida = icono(
        "caida",
        "M14.5 3.5a2 2 0 1 1 0 4a2 2 0 0 1 0-4z", "M13 9l-4 3 1.5 4", "M9 12l-4.5-1", "M13 9l2.5 4.5 4 .5", "M10.5 16l-3.5 4", "M3 21h18",
    )
    val Ruta = icono("ruta", "M6 19a2 2 0 1 1 0-4a2 2 0 0 1 0 4z", "M18 9a2 2 0 1 1 0-4a2 2 0 0 1 0 4z", "M8 17h7a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h7")
    val Prueba = icono("prueba", "M9 3h6", "M10 3v6l-5 9a2 2 0 0 0 1.8 3h10.4a2 2 0 0 0 1.8-3l-5-9V3", "M7.5 15h9")
    val Mensaje = icono("mensaje", "M4 5h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9l-5 4V6a1 1 0 0 1 1-1z")
    val Hecho = icono("hecho_em", "M5 12.5l4.5 4.5L19 7.5")
}

/** «Ana», «Ana y Luis», «Ana, Luis y Marta» (y «and»/«und»… según el idioma). */
internal fun nombres(lista: List<String>): String =
    runCatching { android.icu.text.ListFormatter.getInstance().format(lista) }.getOrDefault(lista.joinToString(", "))

/** El anillo de la cuenta atrás: se vacía con un muelle, sin saltos entre segundo y segundo. */
@Composable
internal fun Anillo(fraccion: Float, tam: Dp, grosor: Dp, color: Color, pista: Color, modifier: Modifier = Modifier, contenido: @Composable () -> Unit) {
    val f by animateFloatAsState(fraccion.coerceIn(0f, 1f), Muelle.suave(), label = "anillo")
    Box(modifier.size(tam), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val g = grosor.toPx()
            val lado = size.minDimension - g
            val esquina = Offset((size.width - lado) / 2, (size.height - lado) / 2)
            drawArc(pista, 0f, 360f, false, esquina, Size(lado, lado), style = Stroke(g))
            drawArc(color, -90f, 360f * f, false, esquina, Size(lado, lado), style = Stroke(g, cap = StrokeCap.Round))
        }
        contenido()
    }
}

/** Botón enorme de la pantalla roja: blanco y con el texto en rojo, imposible de no ver. */
@Composable
internal fun BotonEnorme(texto: String, modifier: Modifier = Modifier, alto: Dp = 84.dp, relleno: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = alto)
            .clip(Formas.Pastilla)
            .then(if (relleno) Modifier.background(Color.White) else Modifier.border(2.dp, Color.White.copy(alpha = 0.7f), Formas.Pastilla))
            .pulsable(onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texto, style = Texto.Titulo.copy(fontSize = 24.sp),
            color = if (relleno) Rojo.SosHondo else Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Pastillas de una sola elección (3 s / 5 s / 10 s…), con la elegida en el acento. */
@Composable
internal fun Pastillas(opciones: List<Pair<String, String>>, elegida: String, modifier: Modifier = Modifier, onElegir: (String) -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(Formas.Pastilla).background(Colores.Superficie2).padding(3.dp),
    ) {
        for ((id, texto) in opciones) {
            val esta = id == elegida
            val fondo by animateColorAsState(if (esta) Colores.Superficie else Colores.Superficie2, Muelle.suave(), label = "pastilla")
            val tinta by animateColorAsState(if (esta) Colores.Texto else Colores.Texto3, Muelle.suave(), label = "tinta")
            Box(
                Modifier.weight(1f).clip(Formas.Pastilla).background(fondo).pulsable { onElegir(id) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) { Text(texto, style = Texto.Pequeno, color = tinta, maxLines = 1) }
        }
    }
}

/** Pastillas que pueden no caber en una fila (grupos sanguíneos): pasan a la siguiente. */
@Composable
internal fun Fichas(opciones: List<String>, elegida: String, onElegir: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (o in opciones) {
            val esta = o == elegida
            val fondo by animateColorAsState(if (esta) Rojo.Boton else Colores.Superficie2, Muelle.suave(), label = "ficha")
            val tinta by animateColorAsState(if (esta) Color.White else Colores.Texto2, Muelle.suave(), label = "tinta")
            Box(
                Modifier.clip(Formas.Pastilla).background(fondo).pulsable { onElegir(if (esta) "" else o) }.padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, style = Texto.Subtitulo, color = tinta) }
        }
    }
}

/** Campo de texto de la ficha: etiqueta pequeña encima y el texto en una caja suave. */
@Composable
internal fun Campo(
    etiqueta: String,
    valor: String,
    pista: String,
    modifier: Modifier = Modifier,
    lineas: Int = 1,
    teclado: KeyboardType = KeyboardType.Text,
    onCambio: (String) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Text(etiqueta, style = Texto.Pequeno, color = Colores.Texto3)
        Spacer(Modifier.padding(top = 4.dp))
        BasicTextField(
            valor, onCambio,
            textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
            cursorBrush = SolidColor(Colores.Acento),
            singleLine = lineas == 1,
            minLines = lineas,
            maxLines = if (lineas == 1) 1 else 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = teclado),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { campo ->
                Box(Modifier.fillMaxWidth().clip(Formas.Pequena).background(Colores.Superficie2).padding(horizontal = 12.dp, vertical = 11.dp)) {
                    if (valor.isEmpty()) Text(pista, style = Texto.Cuerpo, color = Colores.Texto3, maxLines = lineas)
                    campo()
                }
            },
        )
    }
}

/** Una fila de ajuste: título, nota y lo que va a la derecha (interruptor, valor…). */
@Composable
internal fun FilaAjuste(titulo: String, nota: String?, modifier: Modifier = Modifier, final: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, style = Texto.Cuerpo, color = Colores.Texto)
            if (nota != null) Text(nota, style = Texto.Pequeno, color = Colores.Texto3)
        }
        Spacer(Modifier.width(12.dp))
        final()
    }
}

/** Botón ancho con borde rojo (acciones serias que no son la principal: parar el SOS). */
@Composable
internal fun BotonRojo(texto: String, modifier: Modifier = Modifier, relleno: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .clip(Formas.Pequena)
            .then(if (relleno) Modifier.background(Rojo.Boton) else Modifier.border(1.5.dp, Rojo.Boton, Formas.Pequena))
            .pulsable(onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(texto, style = Texto.Subtitulo, color = if (relleno) Color.White else Rojo.Boton, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** La hora con dos cifras («01:10», «15:40»; o «1:10 PM» con el reloj de 12 h): se lee mejor tras «hasta las». */
internal fun hora(ctx: android.content.Context, ms: Long): String {
    val l = java.util.Locale.getDefault()
    val patron = if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else android.text.format.DateFormat.getBestDateTimePattern(l, "hmma")
    return java.text.SimpleDateFormat(patron, l).format(java.util.Date(ms))
}
