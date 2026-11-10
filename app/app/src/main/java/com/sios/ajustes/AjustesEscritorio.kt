package com.sios.ajustes

import android.content.Intent
import android.hardware.display.DisplayManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.sistema.ModoEscritorio
import com.sios.sistema.ModoEscritorio.AlConectar
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.InterruptorSios
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable

/** Los monitores conectados, al momento: se enteran solos al enchufar o quitar uno. */
@Composable
private fun monitoresConectados(): List<ModoEscritorio.Monitor> {
    val ctx = LocalContext.current
    var lista by remember { mutableStateOf(ModoEscritorio.monitores(ctx)) }
    DisposableEffect(Unit) {
        val dm = ctx.getSystemService(DisplayManager::class.java)
        val oyente = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) { lista = ModoEscritorio.monitores(ctx) }
            override fun onDisplayRemoved(id: Int) { lista = ModoEscritorio.monitores(ctx) }
            override fun onDisplayChanged(id: Int) { lista = ModoEscritorio.monitores(ctx) }
        }
        dm?.registerDisplayListener(oyente, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { dm?.unregisterDisplayListener(oyente) }
    }
    return lista
}

/** El resumen de la portada. */
@Composable
internal fun resumenEscritorio(refresco: Int): String {
    val ctx = LocalContext.current
    val disponible = remember { ModoEscritorio.disponible(ctx) }
    val activo = remember(refresco) { ModoEscritorio.activo(ctx) && ModoEscritorio.alConectar(ctx) == AlConectar.ORDENADOR }
    val monitor = monitoresConectados().firstOrNull()
    return when {
        !disponible -> stringResource(R.string.modoesc_r_no_disponible)
        monitor != null -> stringResource(R.string.modoesc_r_conectado, monitor.nombre.ifBlank { stringResource(R.string.modoesc_monitor) })
        activo -> stringResource(R.string.modoesc_r_activo)
        else -> stringResource(R.string.modoesc_r_duplicar)
    }
}

/**
 * Modo escritorio: el dibujo del monitor (escritorio con ventanas y barra de tareas, o la pantalla del
 * móvil duplicada), si hay alguno conectado, qué pasa al conectarlo y cómo se usa.
 */
@Composable
internal fun SeccionModoEscritorio() {
    val ctx = LocalContext.current
    val disponible = remember { ModoEscritorio.disponible(ctx) }
    var activo by remember { mutableStateOf(ModoEscritorio.activo(ctx)) }
    var alConectar by remember { mutableStateOf(ModoEscritorio.alConectar(ctx)) }
    var todas by remember { mutableStateOf(ModoEscritorio.todasEnVentana(ctx)) }
    var fallo by remember { mutableStateOf(false) }
    val monitores = monitoresConectados()
    fun guardar() { fallo = !ModoEscritorio.poner(ctx, activo, alConectar, todas) }
    val ordenador = disponible && activo && alConectar == AlConectar.ORDENADOR

    Grupo("monitor", null, null, Estilo.Libre) {
        Column(
            Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DibujoMonitor(ordenador, conectado = monitores.isNotEmpty())
            val m = monitores.firstOrNull()
            Row(verticalAlignment = Alignment.CenterVertically) {
                val punto by animateColorAsState(if (m != null) Colores.Acento else Colores.BordeFuerte, Muelle.suave(), label = "punto")
                Box(Modifier.size(8.dp).clip(Formas.Pastilla).background(punto))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (m != null) m.nombre.ifBlank { stringResource(R.string.modoesc_monitor) } else stringResource(R.string.modoesc_sin_monitor),
                        style = Texto.Cuerpo, color = Colores.Texto,
                    )
                    Text(
                        when {
                            m == null -> stringResource(R.string.modoesc_sin_monitor_nota)
                            m.inalambrico -> stringResource(R.string.modoesc_inalambrico_nota, m.ancho, m.alto)
                            ordenador -> stringResource(R.string.modoesc_conectado_ordenador, m.ancho, m.alto)
                            else -> stringResource(R.string.modoesc_conectado_duplicando, m.ancho, m.alto)
                        },
                        style = Texto.Pequeno, color = Colores.Texto3,
                    )
                }
            }
            // No todos los móviles sacan imagen por el cable: se dice antes de que alguien compre un adaptador.
            // Con un monitor ya conectado por cable, sobra.
            AnimatedVisibility(
                m == null || m.inalambrico,
                enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()),
                exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.Borde))
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.modoesc_requisito_titulo), style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Texto)
                    Text(stringResource(R.string.modoesc_requisito), style = Texto.Pequeno, color = Colores.Texto3)
                }
            }
        }
    }

    if (!disponible) {
        Grupo("modo", null, null, Estilo.Tarjeta) {
            Text(stringResource(R.string.modoesc_no_disponible), style = Texto.Pequeno, color = Colores.Texto2)
        }
        return
    }

    Grupo("modo", null, stringResource(R.string.modoesc_nota), Estilo.Tarjeta) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            FilaInterruptor(stringResource(R.string.modoesc_titulo), stringResource(R.string.modoesc_activar_nota), activo) { activo = it; guardar() }
            AnimatedVisibility(
                activo,
                enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()),
                exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave()),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(stringResource(R.string.modoesc_al_conectar), style = Texto.Cuerpo, color = Colores.Texto)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AlConectar.ORDENADOR to R.string.modoesc_ordenador, AlConectar.DUPLICAR to R.string.modoesc_duplicar).forEach { (opcion, nombre) ->
                            val elegido = alConectar == opcion
                            val fondo by animateColorAsState(if (elegido) Colores.Acento else Colores.Superficie, Muelle.suave(), label = "fondo")
                            val borde by animateColorAsState(if (elegido) Colores.Acento else Colores.Borde, Muelle.suave(), label = "borde")
                            val tinta by animateColorAsState(if (elegido) Colores.SobreAcento else Colores.Texto2, Muelle.suave(), label = "tinta")
                            Box(
                                Modifier.weight(1f).heightIn(min = 44.dp).clip(Formas.Pequena).background(fondo)
                                    .border(1.dp, borde, Formas.Pequena)
                                    .pulsable { if (!elegido) { alConectar = opcion; guardar() } }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(nombre), style = Texto.Pequeno, color = tinta, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                        }
                    }
                    AnimatedVisibility(
                        alConectar == AlConectar.ORDENADOR,
                        enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()),
                        exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave()),
                    ) {
                        FilaInterruptor(stringResource(R.string.modoesc_todas), stringResource(R.string.modoesc_todas_nota), todas) { todas = it; guardar() }
                    }
                }
            }
            AnimatedVisibility(fallo, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
                Text(stringResource(R.string.modoesc_fallo), style = Texto.Pequeno, color = Colores.Rojo)
            }
        }
    }

    Grupo("uso", stringResource(R.string.modoesc_g_uso), null, Estilo.Tarjeta) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val consejos = if (ordenador) listOf(R.string.modoesc_uso_conectar, R.string.modoesc_uso_raton, R.string.modoesc_uso_ventanas, R.string.modoesc_uso_movil)
                else listOf(R.string.modoesc_uso_conectar, R.string.modoesc_uso_movil)
            consejos.forEach { t ->
                Row {
                    Box(Modifier.padding(top = 8.dp).size(5.dp).clip(Formas.Pastilla).background(Colores.Texto3))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(t), style = Texto.Pequeno, color = Colores.Texto2)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.modoesc_inalambrica), style = Texto.Pequeno, color = Colores.Texto3)
            BotonSecundario(stringResource(R.string.modoesc_enviar_pantalla), Modifier.fillMaxWidth()) {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_CAST_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}

@Composable
private fun FilaInterruptor(titulo: String, nota: String?, activo: Boolean, onCambio: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, style = Texto.Cuerpo, color = Colores.Texto)
            if (nota != null) Text(nota, style = Texto.Pequeno, color = Colores.Texto3)
        }
        Spacer(Modifier.width(12.dp))
        InterruptorSios(activo, onCambio)
    }
}

/**
 * El monitor dibujado: con el modo escritorio, dos ventanas y la barra de tareas; si duplica, la pantalla
 * del móvil en vertical en medio. Pasa de uno a otro con un muelle, y sin monitor se ve apagado.
 */
@Composable
private fun DibujoMonitor(ordenador: Boolean, conectado: Boolean) {
    val escritorio by animateFloatAsState(if (ordenador) 1f else 0f, Muelle.suave(), label = "escritorio")
    val ventanas by animateFloatAsState(if (ordenador) 1f else 0f, Muelle.vivo(), label = "ventanas")
    val luz by animateFloatAsState(if (conectado) 1f else 0.55f, Muelle.suave(), label = "luz")
    val marco = Colores.BordeFuerte
    val pantalla = Colores.Lateral
    val ventana = Colores.Superficie2
    val barra = Colores.Superficie
    val detalle = Colores.Texto3
    val acento = Colores.Acento
    val borde = Colores.Borde
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth(0.86f).height(150.dp)) {
            val dp = density
            val altoPie = 18 * dp
            val alto = size.height - altoPie
            val radio = CornerRadius(10 * dp)
            // Marco y pantalla.
            drawRoundRect(pantalla, size = Size(size.width, alto), cornerRadius = radio)
            drawRoundRect(marco, size = Size(size.width, alto), cornerRadius = radio, style = Stroke(2 * dp))
            // Pie del monitor.
            drawLine(marco, Offset(size.width / 2, alto), Offset(size.width / 2, alto + altoPie * 0.6f), 2 * dp)
            drawLine(marco, Offset(size.width * 0.38f, alto + altoPie * 0.75f), Offset(size.width * 0.62f, alto + altoPie * 0.75f), 2.4f * dp, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            val dentro = Size(size.width - 16 * dp, alto - 16 * dp)
            val origen = Offset(8 * dp, 8 * dp)
            // Duplicar: el móvil en vertical, en medio.
            val m = (1f - escritorio).coerceIn(0f, 1f)
            if (m > 0.01f) {
                val h = dentro.height * (0.86f + 0.06f * m)
                val w = h * 9f / 19f
                val o = Offset(origen.x + (dentro.width - w) / 2, origen.y + (dentro.height - h) / 2)
                drawRoundRect(ventana.copy(alpha = m), o, Size(w, h), CornerRadius(7 * dp))
                drawRoundRect(borde.copy(alpha = m), o, Size(w, h), CornerRadius(7 * dp), style = Stroke(1 * dp))
                drawRoundRect(detalle.copy(alpha = 0.5f * m), Offset(o.x + w * 0.3f, o.y + 5 * dp), Size(w * 0.4f, 3 * dp), CornerRadius(2 * dp))
                drawRoundRect(acento.copy(alpha = 0.8f * m), Offset(o.x + w * 0.18f, o.y + h * 0.72f), Size(w * 0.64f, 7 * dp), CornerRadius(4 * dp))
            }
            // Escritorio: dos ventanas que llegan con un muelle y la barra de tareas abajo.
            if (escritorio > 0.01f) {
                ventanaDibujada(origen, dentro, 0.06f, 0.08f, 0.5f, 0.6f, ventanas, ventana, detalle, borde, dp)
                ventanaDibujada(origen, dentro, 0.4f, 0.2f, 0.52f, 0.56f, (ventanas * 1.15f - 0.15f).coerceIn(0f, 1.1f), ventana, detalle, borde, dp)
                val bw = dentro.width * 0.42f
                val bh = 9 * dp
                val bo = Offset(origen.x + (dentro.width - bw) / 2, origen.y + dentro.height - bh - 3 * dp + (1f - escritorio) * 10 * dp)
                drawRoundRect(barra.copy(alpha = escritorio), bo, Size(bw, bh), CornerRadius(bh / 2))
                for (i in 0 until 5) {
                    val c = if (i == 0) acento else detalle
                    drawCircle(c.copy(alpha = escritorio * if (i == 0) 1f else 0.7f), 2.2f * dp, Offset(bo.x + bw * (0.18f + i * 0.16f), bo.y + bh / 2))
                }
            }
            // Sin monitor, la pantalla dibujada se ve apagada (un velo encima: las ventanas no se transparentan).
            if (luz < 0.999f) drawRoundRect(pantalla.copy(alpha = (1f - luz) / 0.45f * 0.55f), origen, dentro, CornerRadius(4 * dp))
        }
    }
}

private fun DrawScope.ventanaDibujada(
    origen: Offset, dentro: Size, x: Float, y: Float, w: Float, h: Float, p: Float,
    fondo: androidx.compose.ui.graphics.Color, detalle: androidx.compose.ui.graphics.Color, borde: androidx.compose.ui.graphics.Color, dp: Float,
) {
    val a = p.coerceIn(0f, 1f)
    if (a <= 0.01f) return
    val tam = Size(dentro.width * w, dentro.height * h)
    val o = Offset(origen.x + dentro.width * x, origen.y + dentro.height * y + (1f - p) * 14 * dp)
    drawRoundRect(fondo.copy(alpha = a), o, tam, CornerRadius(6 * dp))
    drawRoundRect(borde.copy(alpha = a), o, tam, CornerRadius(6 * dp), style = Stroke(1 * dp))
    // Barra de título: tres puntitos y una línea.
    for (i in 0 until 3) drawCircle(detalle.copy(alpha = a * 0.8f), 1.6f * dp, Offset(o.x + (7 + i * 6) * dp, o.y + 6 * dp))
    drawRoundRect(detalle.copy(alpha = a * 0.35f), Offset(o.x + 8 * dp, o.y + 14 * dp), Size(tam.width * 0.55f, 3 * dp), CornerRadius(2 * dp))
    drawRoundRect(detalle.copy(alpha = a * 0.25f), Offset(o.x + 8 * dp, o.y + 21 * dp), Size(tam.width * 0.38f, 3 * dp), CornerRadius(2 * dp))
}
