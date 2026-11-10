package com.sios.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.datos.Uso
import com.sios.datos.Ventana
import java.text.SimpleDateFormat
import java.util.Date

/** Pulsación con un pequeño muelle: nada cambia de golpe. */
@Composable
fun Modifier.pulsable(onClick: () -> Unit): Modifier {
    val interaccion = remember { MutableInteractionSource() }
    val pulsado by interaccion.collectIsPressedAsState()
    val escala by animateFloatAsState(if (pulsado) 0.95f else 1f, Muelle.vivo(), label = "pulsacion")
    return this
        .scale(escala)
        .clickable(interaccion, indication = null, onClick = onClick)
}

@Composable
fun BotonIcono(
    icono: ImageVector,
    descripcion: String,
    modifier: Modifier = Modifier,
    tam: Dp = 40.dp,
    color: Color = Colores.Texto,
    fondo: Color = Color.Transparent,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(tam)
            .clip(Formas.Pastilla)
            .background(fondo)
            .pulsable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icono, descripcion, tint = color, modifier = Modifier.size(tam * 0.5f))
    }
}

@Composable
fun BotonPrimario(texto: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(Formas.Pequena)
            .background(Colores.Acento)
            .pulsable(onClick),
        contentAlignment = Alignment.Center,
    ) { Text(texto, style = Texto.Subtitulo, color = Colores.SobreAcento) }
}

/** Botón de solo texto (atrás, saltar): sin fondo, para que el de avanzar mande. */
@Composable
fun BotonTexto(texto: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(Formas.Pequena)
            .pulsable(onClick),
        contentAlignment = Alignment.Center,
    ) { Text(texto, style = Texto.Subtitulo, color = Colores.Texto) }
}

@Composable
fun BotonSecundario(texto: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(Formas.Pequena)
            .background(Colores.Superficie2)
            .pulsable(onClick),
        contentAlignment = Alignment.Center,
    ) { Text(texto, style = Texto.Subtitulo, color = Colores.Texto) }
}

/**
 * La barra de Claude: sustituye a la de Google. Asterisco (abre Claude), texto (preguntar)
 * y micrófono (dictar).
 */
@Composable
fun BarraAsistente(
    modifier: Modifier = Modifier,
    onLogo: () -> Unit,
    onTexto: () -> Unit,
    onMicro: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(Formas.Pastilla)
            .background(Colores.Superficie)
            .border(1.dp, Colores.Borde, Formas.Pastilla)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotonIcono(Iconos.Asistente, stringResource(R.string.comun_abrir_ia), tam = 44.dp, color = Colores.Acento, onClick = onLogo)
        Text(
            stringResource(R.string.comun_preguntale_a_ia),
            style = Texto.Cuerpo,
            color = Colores.Texto3,
            modifier = Modifier
                .weight(1f)
                .clickable(remember { MutableInteractionSource() }, null, onClick = onTexto)
                .padding(vertical = 16.dp, horizontal = 4.dp),
        )
        BotonIcono(Iconos.Micro, stringResource(R.string.comun_dictar), tam = 44.dp, color = Colores.Texto2, onClick = onMicro)
    }
}

/** Cuándo se renueva la ventana de uso, con la fecha en el idioma del móvil. */
@Composable
fun horaReinicio(epoch: Long?): String {
    if (epoch == null) return ""
    val fecha = Date(epoch * 1000)
    val dias = (epoch * 1000 - System.currentTimeMillis()) / 86_400_000.0
    val formato = if (dias < 1) "HH:mm" else "EEE d, HH:mm"
    return stringResource(R.string.uso_se_renueva, SimpleDateFormat(formato, LocalLocale.current.platformLocale).format(fecha))
}

/** Barra fina de uso de la suscripción. */
@Composable
fun BarraUso(nombre: String, ventana: Ventana?, modifier: Modifier = Modifier) {
    val pct = ventana?.porcentaje ?: 0
    val ancho by animateFloatAsState((pct / 100f).coerceIn(0f, 1f), Muelle.suave(), label = "uso")
    val color = when {
        ventana == null -> Colores.Texto3
        pct >= 90 -> Colores.Rojo
        else -> Colores.Acento
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(nombre, style = Texto.Pequeno, color = Colores.Texto2, modifier = Modifier.weight(1f))
            Text(if (ventana == null) stringResource(R.string.uso_sin_datos) else stringResource(R.string.uso_porcentaje, pct), style = Texto.Pequeno, color = if (ventana == null) Colores.Texto3 else Colores.Texto)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(Formas.Pastilla)
                .background(Colores.Borde)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ancho)
                    .height(4.dp)
                    .clip(Formas.Pastilla)
                    .background(color)
            )
        }
        if (ventana?.reinicio != null) {
            Spacer(Modifier.height(4.dp))
            Text(horaReinicio(ventana.reinicio), style = Texto.Pequeno, color = Colores.Texto3)
        }
    }
}

@Composable
fun ResumenUso(uso: Uso?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BarraUso(stringResource(R.string.uso_sesion_cinco_horas), uso?.cincoHoras)
        BarraUso(stringResource(R.string.uso_semana_todos), uso?.semana)
        uso?.semanaModelos?.forEach { (nombre, v) -> BarraUso(stringResource(R.string.uso_semana_modelo, nombre), v) }
    }
}

@Composable
fun Separador(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(1.dp).background(Colores.Borde))

@Composable
fun EspacioH(ancho: Dp) = Spacer(Modifier.width(ancho))

/** «hace un momento», «hace 5 minutos», «ayer»…: sin el «hace 0 minutos» de Android. */
fun haceRato(ctx: android.content.Context, ms: Long): String =
    if (System.currentTimeMillis() - ms < 60_000) ctx.getString(com.sios.R.string.memoria_hace_un_momento)
    else android.text.format.DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString().lowercase()

/** Interruptor con los colores de Claude (el mismo en toda la app). */
@Composable
fun InterruptorSios(activo: Boolean, onCambio: (Boolean) -> Unit) {
    androidx.compose.material3.Switch(
        checked = activo,
        onCheckedChange = onCambio,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Colores.SobreAcento,
            checkedTrackColor = Colores.Acento,
            uncheckedThumbColor = Colores.Texto3,
            uncheckedTrackColor = Colores.Superficie2,
            uncheckedBorderColor = Colores.Borde,
        ),
    )
}
