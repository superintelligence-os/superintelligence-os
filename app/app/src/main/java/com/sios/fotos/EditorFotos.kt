package com.sios.fotos

import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.ui.BotonIcono
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.launch

/**
 * El editor: la barra de arriba (cerrar, deshacer, rehacer, mejora automática y guardar), la foto,
 * el panel de la herramienta y la barra de herramientas.
 */
@RequiresApi(33)
@Composable
fun EditorFotos(estado: EstadoEditor, guardando: Boolean, onCerrar: () -> Unit, onGuardar: () -> Unit) {
    val alcance = rememberCoroutineScope()
    val vista = LocalView.current
    var confirmarSalida by remember { mutableStateOf(false) }
    val cambios = !estado.edicion.sinCambios
    androidx.activity.compose.BackHandler(enabled = cambios && !guardando) { confirmarSalida = true }

    Box(Modifier.fillMaxSize().background(Colores.Lateral)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Barra de arriba.
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BotonIcono(Iconos.Cruz, stringResource(R.string.fotos_cerrar), tam = 44.dp, color = Colores.Texto) {
                    if (cambios) confirmarSalida = true else onCerrar()
                }
                Spacer(Modifier.width(4.dp))
                val aDeshacer by animateFloatAsState(if (estado.puedeDeshacer) 1f else 0.35f, Muelle.suave(), label = "deshacer")
                val aRehacer by animateFloatAsState(if (estado.puedeRehacer) 1f else 0.35f, Muelle.suave(), label = "rehacer")
                BotonIcono(IconosFotos.Deshacer, stringResource(R.string.fotos_deshacer), Modifier.alpha(aDeshacer), tam = 44.dp, color = Colores.Texto) {
                    if (estado.puedeDeshacer) { Haptica.marca(vista); estado.deshacer() }
                }
                BotonIcono(IconosFotos.Rehacer, stringResource(R.string.fotos_rehacer), Modifier.alpha(aRehacer), tam = 44.dp, color = Colores.Texto) {
                    if (estado.puedeRehacer) { Haptica.marca(vista); estado.rehacer() }
                }
                Spacer(Modifier.weight(1f))
                BotonAuto(estado.autoActiva, estado.calculandoAuto) {
                    Haptica.firme(vista)
                    alcance.launch { estado.alternarAuto() }
                }
                Spacer(Modifier.width(8.dp))
                val aGuardar by animateFloatAsState(if (cambios) 1f else 0.4f, Muelle.suave(), label = "guardar")
                Box(
                    Modifier.alpha(aGuardar).height(38.dp).clip(Formas.Pastilla).background(Colores.Acento)
                        .pulsable { if (cambios && !guardando) onGuardar() }.padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.fotos_guardar), style = Texto.Subtitulo.copy(fontSize = Texto.Pequeno.fontSize), color = Colores.SobreAcento) }
                Spacer(Modifier.width(4.dp))
            }
            // La foto.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LienzoFoto(estado, Modifier.fillMaxSize())
                androidx.compose.animation.AnimatedVisibility(
                    estado.comparando, Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    enter = fadeIn(Muelle.suave()) + scaleIn(Muelle.suave(), initialScale = 0.9f), exit = fadeOut(Muelle.suave()) + scaleOut(Muelle.suave(), targetScale = 0.9f),
                ) {
                    Text(
                        stringResource(R.string.fotos_original), style = Texto.Pequeno, color = Color.White,
                        modifier = Modifier.clip(Formas.Pastilla).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            // Panel de la herramienta.
            PanelHerramienta(estado, Modifier.fillMaxWidth().height(158.dp))
            // Herramientas.
            val herramientas = buildList {
                add(Triple(Herramienta.RECORTAR, IconosFotos.Recortar, R.string.fotos_herr_recortar))
                add(Triple(Herramienta.AJUSTAR, IconosFotos.Ajustar, R.string.fotos_herr_ajustar))
                add(Triple(Herramienta.FILTROS, IconosFotos.Filtros, R.string.fotos_herr_filtros))
                add(Triple(Herramienta.BORRADOR, IconosFotos.Borrador, R.string.fotos_herr_borrar))
                add(Triple(Herramienta.SUJETO, IconosFotos.Sujeto, R.string.fotos_herr_sujeto))
                if (Palabras.disponible) add(Triple(Herramienta.PALABRAS, IconosFotos.Palabras, R.string.fotos_herr_palabras))
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                for ((h, icono, nombre) in herramientas) {
                    BotonHerramienta(icono, stringResource(nombre), estado.herramienta == h, Modifier.weight(1f)) {
                        if (estado.herramienta != h) { Haptica.marca(vista); estado.herramienta = h }
                    }
                }
            }
        }
        // Guardando: un velo con el aviso, sin tapar la foto del todo.
        AnimatedVisibility(guardando, enter = fadeIn(Muelle.suave()), exit = fadeOut(Muelle.suave())) {
            Box(Modifier.fillMaxSize().background(Colores.Lateral.copy(alpha = 0.72f)).pulsable {}, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BarraIndeterminada(Modifier.width(120.dp))
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.fotos_guardando), style = Texto.Subtitulo, color = Colores.Texto)
                }
            }
        }
    }

    if (confirmarSalida) AlertDialog(
        onDismissRequest = { confirmarSalida = false },
        containerColor = Colores.Superficie,
        title = { Text(stringResource(R.string.fotos_descartar_titulo), style = Texto.Titulo, color = Colores.Texto) },
        text = { Text(stringResource(R.string.fotos_descartar_texto), style = Texto.Cuerpo, color = Colores.Texto2) },
        confirmButton = { TextButton({ confirmarSalida = false; onCerrar() }) { Text(stringResource(R.string.fotos_descartar), color = Colores.Rojo, style = Texto.Subtitulo) } },
        dismissButton = { TextButton({ confirmarSalida = false }) { Text(stringResource(R.string.fotos_seguir), color = Colores.Texto, style = Texto.Subtitulo) } },
    )
}

/** «Auto»: la mejora automática con un toque; se queda encendida mientras sus ajustes sigan puestos. */
@Composable
private fun BotonAuto(activa: Boolean, calculando: Boolean, onClick: () -> Unit) {
    val fondo by animateColorAsState(if (activa) Colores.AcentoSuave else Colores.Superficie2, Muelle.suave(), label = "autoFondo")
    val tinta by animateColorAsState(if (activa) Colores.Acento else Colores.Texto, Muelle.suave(), label = "autoTinta")
    val giro by animateFloatAsState(if (calculando) 1f else 0f, Muelle.suave(), label = "autoGiro")
    Row(
        Modifier.height(38.dp).clip(Formas.Pastilla).background(fondo).pulsable(onClick).padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IconosFotos.Varita, stringResource(R.string.fotos_auto_desc), tint = tinta, modifier = Modifier.size(18.dp).alpha(1f - giro * 0.5f))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.fotos_auto), style = Texto.Pequeno, color = tinta)
    }
}
