package com.sios.inicio

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import com.sios.R
import androidx.compose.ui.text.style.TextOverflow
import com.sios.ui.BotonIcono
import com.sios.ui.AvisoFlotante
import com.sios.datos.Creacion
import com.sios.datos.Panel
import com.sios.datos.Servicio
import com.sios.sistema.AsistenteActivity
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.WidgetCreacion
import com.sios.ui.pulsable
import kotlinx.coroutines.delay

/**
 * El panel que sale al deslizar a la derecha en el inicio (como el de widgets del iPhone). Lo diseña
 * Claude entero: cada tarjeta es un widget con lugar «panel» (plantillas fijas, fuentes integradas
 * o diseño libre en html) y se piden cambios con una frase, viéndolos aparecer en directo.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PanelInicio(
    abierto: Boolean,
    panel: Panel,
    creaciones: List<Creacion>,
    onArrastre: (Float) -> Unit,
    onSoltar: (Float) -> Unit,
    onTarjeta: (Creacion) -> Unit,
) {
    val ctx = LocalContext.current
    // El servicio decide qué se ve (modo activo, tarjetas automáticas relevantes): se sigue su orden.
    val porId = remember(creaciones) { creaciones.associateBy { it.id } }
    val tarjetas = remember(panel, creaciones) { panel.orden.mapNotNull { porId[it] }.filter { it.tipo == "widget" } }
    val hijosDe = { c: Creacion -> c.pila.mapNotNull { porId[it] } }
    var hoja by remember { mutableStateOf<String?>(null) } // galeria | menu | historial | ajustes | modos
    // Mientras se ve, el servicio lo sabe (refresca lo del panel y elige qué enseña cada pila).
    LaunchedEffect(abierto) { while (abierto) { delay(120_000); Servicio.refrescarPanel() } }
    val desplazamiento = rememberScrollState()
    val foco = LocalFocusManager.current
    // Al abrirse: refresca lo que toque. Al cerrarse: arriba y sin teclado.
    LaunchedEffect(abierto) {
        if (abierto) Servicio.refrescarPanel() else { foco.clearFocus(); desplazamiento.scrollTo(0) }
    }
    var refrescando by remember { mutableStateOf(false) }
    LaunchedEffect(refrescando) {
        if (refrescando) { Servicio.refrescarPanel(forzar = true); delay(1600); refrescando = false }
    }
    val hoy by produceState(fechaLarga()) { while (true) { delay(60_000); value = fechaLarga() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(Colores.Fondo)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            // Hacia la izquierda se cierra siguiendo al dedo.
            .draggable(rememberDraggableState { onArrastre(it) }, Orientation.Horizontal, onDragStopped = { onSoltar(it) }),
    ) {
        PullToRefreshBox(isRefreshing = refrescando, onRefresh = { refrescando = true }, modifier = Modifier.weight(1f)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(desplazamiento)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            ) {
                Row(Modifier.padding(start = 6.dp, end = 0.dp, top = 12.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(hoy.uppercase(), style = Texto.Etiqueta, color = Colores.Texto3)
                            // Modo del panel (si hay modos): tocar para elegirlo.
                            val modo = panel.modos.firstOrNull { it.id == panel.modo }
                            if (panel.modos.isNotEmpty()) {
                                Spacer(Modifier.width(10.dp))
                                Row(
                                    Modifier.clip(Formas.Pastilla).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Pastilla)
                                        .pulsable { hoja = "modos" }.padding(horizontal = 10.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(6.dp).clip(Formas.Pastilla).background(if (panel.modoManual != null) Colores.Acento else Colores.Texto3))
                                    Spacer(Modifier.width(6.dp))
                                    Text(modo?.nombre ?: stringResource(R.string.panel_modo_auto), style = Texto.Etiqueta, color = Colores.Texto2, maxLines = 1)
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(panel.titulo ?: stringResource(R.string.panel_titulo), style = Texto.Titulo.copy(fontSize = 28.sp), color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BotonIcono(Iconos.Mas, stringResource(R.string.panel_galeria), tam = 40.dp, color = Colores.Texto2, fondo = Colores.Superficie) { hoja = "galeria" }
                    Spacer(Modifier.width(8.dp))
                    BotonIcono(Iconos.Menu, stringResource(R.string.panel_menu_ajustes), tam = 40.dp, color = Colores.Texto2, fondo = Colores.Superficie) { hoja = "menu" }
                }
                if (tarjetas.isEmpty()) {
                    PanelVacio { pedir(ctx, it) }
                } else {
                    RejillaWidgets(
                        claves = tarjetas.map { it.id },
                        tamanoDe = { id -> porId[id]?.tamano ?: "mediano" },
                        onMenu = { id -> porId[id]?.let(onTarjeta) },
                        onOrden = { Servicio.ordenarPanel(it) },
                        desplazamiento = desplazamiento,
                    ) { id, m ->
                        porId[id]?.let { c -> WidgetCreacion(c, onToque = { onTarjeta(c) }, onLargo = {}, tamano = c.tamano, conLargo = false, hijosDe = hijosDe, modifier = m.fillMaxSize()) }
                    }
                    Sugerencias(Modifier.padding(top = 20.dp)) { pedir(ctx, it) }
                }
            }
        }
        Box {
            BarraPedir(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) { pedir(ctx, it) }
            // Lo que contesta un botón de widget aparece encima de la barra y se va solo.
            AvisoFlotante(Modifier.align(Alignment.BottomCenter).padding(bottom = 74.dp))
        }
    }
    when (hoja) {
        "galeria" -> HojaGaleria(onPedir = { pedir(ctx, it) }, onCerrar = { hoja = null })
        "menu" -> HojaMenuPanel(onHistorial = { hoja = "historial" }, onAjustes = { hoja = "ajustes" }, onCerrar = { if (hoja == "menu") hoja = null })
        "historial" -> HojaHistorial(onCerrar = { hoja = null })
        "ajustes" -> HojaAjustesPanel(onPedir = { pedir(ctx, it) }, onCerrar = { hoja = null })
        "modos" -> HojaModos(panel, onCerrar = { hoja = null })
    }
}

private fun fechaLarga(): String =
    java.text.SimpleDateFormat("EEEE, d 'de' MMMM", java.util.Locale.getDefault()).format(java.util.Date())

/**
 * Le pide el cambio a Claude en la hoja rápida, que se abre encima del panel: el usuario ve cómo
 * aparece o cambia la tarjeta mientras Claude trabaja.
 */
private fun pedir(ctx: Context, texto: String) {
    if (texto.isBlank()) return
    ctx.startActivity(
        Intent(ctx, AsistenteActivity::class.java)
            .putExtra(android.app.SearchManager.QUERY, ctx.getString(R.string.panel_peticion, texto.trim()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** Ideas de un toque (y al final del panel, para seguir añadiendo). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sugerencias(modifier: Modifier = Modifier, centradas: Boolean = false, onPedir: (String) -> Unit) {
    val ideas = listOf(
        R.string.panel_idea_noticias, R.string.panel_idea_dia, R.string.panel_idea_agenda, R.string.panel_idea_cumples,
        R.string.panel_idea_gasolina, R.string.panel_idea_wifi, R.string.panel_idea_bluetooth, R.string.panel_idea_tiempo,
    )
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, if (centradas) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ideas.forEach { id ->
            val texto = stringResource(id)
            Text(
                texto,
                style = Texto.Pequeno,
                color = Colores.Texto2,
                modifier = Modifier
                    .clip(Formas.Pastilla)
                    .background(Colores.Superficie)
                    .border(1.dp, Colores.Borde, Formas.Pastilla)
                    .pulsable { onPedir(texto) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun PanelVacio(onPedir: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 36.dp, start = 6.dp, end = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(Formas.Pastilla).background(Colores.Superficie), contentAlignment = Alignment.Center) {
            Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.panel_vacio_titulo), style = Texto.Subtitulo, color = Colores.Texto, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.panel_vacio_texto), style = Texto.Cuerpo, color = Colores.Texto3, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Sugerencias(centradas = true, onPedir = onPedir)
    }
}

/** «Pide un cambio a Claude»: crear, cambiar, quitar u ordenar tarjetas con una frase. */
@Composable
private fun BarraPedir(modifier: Modifier = Modifier, onPedir: (String) -> Unit) {
    var texto by remember { mutableStateOf("") }
    val enviar = { onPedir(texto); texto = "" }
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(Formas.Pastilla)
            .background(Colores.Superficie)
            .border(1.dp, Colores.Borde, Formas.Pastilla)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = texto,
            onValueChange = { texto = it },
            singleLine = true,
            textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
            cursorBrush = SolidColor(Colores.Acento),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { enviar() }),
            modifier = Modifier.weight(1f),
            decorationBox = { campo ->
                Box {
                    if (texto.isEmpty()) Text(stringResource(R.string.panel_pedir), style = Texto.Cuerpo, color = Colores.Texto3)
                    campo()
                }
            },
        )
        Box(
            Modifier
                .size(40.dp)
                .clip(Formas.Pastilla)
                .background(if (texto.isBlank()) Colores.Superficie2 else Colores.Acento)
                .pulsable { if (texto.isNotBlank()) enviar() },
            contentAlignment = Alignment.Center,
        ) { Icon(Iconos.Enviar, stringResource(R.string.panel_enviar), tint = if (texto.isBlank()) Colores.Texto3 else Colores.SobreAcento, modifier = Modifier.size(18.dp)) }
    }
}
