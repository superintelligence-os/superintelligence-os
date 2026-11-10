package com.sios.fotos

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.ui.BotonIcono
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Marca
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** El panel de la herramienta elegida (alto fijo: la foto no salta al cambiar de herramienta). */
@RequiresApi(33)
@Composable
internal fun PanelHerramienta(estado: EstadoEditor, modifier: Modifier = Modifier) {
    AnimatedContent(
        estado.herramienta,
        modifier,
        transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) },
        label = "panel",
    ) { h ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (h) {
                Herramienta.AJUSTAR -> PanelAjustar(estado)
                Herramienta.FILTROS -> PanelFiltros(estado)
                Herramienta.RECORTAR -> PanelRecortar(estado)
                Herramienta.BORRADOR -> PanelBorrador(estado)
                Herramienta.SUJETO -> PanelSujeto(estado)
                Herramienta.PALABRAS -> PanelPalabras(estado)
            }
        }
    }
}

// ---------- Ajustar ----------

@RequiresApi(33)
@Composable
private fun PanelAjustar(estado: EstadoEditor) {
    val lista = rememberLazyListState(initialFirstVisibleItemIndex = max(0, estado.ajusteElegido.ordinal - 2))
    val a = estado.ajusteElegido
    LaunchedEffect(a) { lista.animateScrollToItem(max(0, a.ordinal - 2)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        LazyRow(state = lista, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items(Ajuste.entries) { x -> Dial(x, estado.edicion.valor(x), x == a) { estado.ajusteElegido = x } }
        }
        Spacer(Modifier.height(10.dp))
        Regla(
            estado.edicion.valor(a), a.minimo, a.maximo,
            onCambio = { estado.moverGesto(estado.edicion.con(a, it)) },
            onEmpezar = { estado.empezarGesto() }, onTerminar = { estado.terminarGesto() },
            etiqueta = null,
            paso = if (a.minimo < 0) 2f else 1f, separacion = if (a.minimo < 0) 7f else 5f,
        )
    }
}

// ---------- Filtros ----------

@RequiresApi(33)
@Composable
private fun PanelFiltros(estado: EstadoEditor) {
    // Una miniatura cuadrada del centro de la foto (con los borrados), para todas las muestras.
    val mini = remember(estado.vista) { miniatura(estado.vista, 220) }
    val e = estado.edicion
    val lista = rememberLazyListState(initialFirstVisibleItemIndex = max(0, e.filtro.ordinal - 1))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        LazyRow(state = lista, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Filtro.entries) { f ->
                MiniaturaFiltro(mini, f, e, f == e.filtro) {
                    estado.poner(e.copy(filtro = f, intensidadFiltro = if (f == e.filtro) e.intensidadFiltro else 100f))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(62.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedVisibility(e.filtro != Filtro.NINGUNO, enter = fadeIn(Muelle.suave()), exit = fadeOut(Muelle.suave())) {
                Regla(
                    e.intensidadFiltro, 0f, 100f,
                    onCambio = { estado.moverGesto(estado.edicion.copy(intensidadFiltro = it)) },
                    onEmpezar = { estado.empezarGesto() }, onTerminar = { estado.terminarGesto() },
                    etiqueta = stringResource(R.string.fotos_intensidad_filtro), paso = 1f, separacion = 5f, cero = 100f,
                )
            }
        }
    }
}

/** Recorte cuadrado y pequeño del centro (para las muestras de los filtros). */
private fun miniatura(b: Bitmap, lado: Int): Bitmap {
    val m = min(b.width, b.height)
    val c = Bitmap.createBitmap(b, (b.width - m) / 2, (b.height - m) / 2, m, m)
    return Bitmap.createScaledBitmap(c, lado, lado, true).also { if (it !== c && c !== b) c.recycle() }
}

@RequiresApi(33)
@Composable
private fun MiniaturaFiltro(mini: Bitmap, f: Filtro, e: Edicion, elegida: Boolean, onClick: () -> Unit) {
    val revelado = remember(mini) { Revelado(mini) }
    val borde by animateColorAsState(if (elegida) Colores.Acento else Color.Transparent, Muelle.suave(), label = "bordeFiltro")
    val tinta by animateColorAsState(if (elegida) Colores.Texto else Colores.Texto3, Muelle.suave(), label = "nombreFiltro")
    // La muestra lleva los ajustes actuales: se ve cómo quedaría la foto, no un ejemplo.
    val muestra = e.copy(filtro = f, intensidadFiltro = 100f, geometria = Geometria(), fondo = Fondo.ORIGINAL)
    Column(Modifier.width(68.dp).pulsable(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(66.dp).border(2.dp, borde, Formas.Pequena).padding(3.dp).clip(RoundedCorner8)) {
            Canvas(Modifier.fillMaxSize()) {
                drawIntoCanvas { c ->
                    revelado.preparar(muestra, Matrix(), mini.width.toFloat(), mini.height.toFloat())
                    revelado.dibujar(c.nativeCanvas, Matrix().apply { setScale(size.width / mini.width, size.height / mini.height) }, null)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(f.nombre), style = Texto.Pequeno.copy(fontSize = Texto.Etiqueta.fontSize), color = tinta, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val RoundedCorner8 = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

// ---------- Recortar ----------

/** Proporciones: nombre y ancho/alto (null: libre; 0: la de la foto). */
private val PROPORCIONES = listOf<Pair<Int?, Float?>>(
    R.string.fotos_proporcion_libre to null,
    R.string.fotos_proporcion_original to 0f,
    R.string.fotos_proporcion_cuadrada to 1f,
    null to 4f / 5f,
    null to 3f / 4f,
    null to 9f / 16f,
    null to 16f / 9f,
)

private fun nombreProporcion(p: Float) = when (p) {
    4f / 5f -> "4:5"; 3f / 4f -> "3:4"; 9f / 16f -> "9:16"; 16f / 9f -> "16:9"; else -> ""
}

@RequiresApi(33)
@Composable
private fun PanelRecortar(estado: EstadoEditor) {
    val vista = LocalView.current
    val e = estado.edicion
    val g = e.geometria
    // La proporción elegida sale de la edición (así se ve bien también tras deshacer).
    val (w1, h1) = Encuadre.marco(estado.vista.width, estado.vista.height, g)
    val elegida = when (val p = g.proporcion) {
        null -> 0
        else -> PROPORCIONES.indexOfFirst { (_, q) -> q != null && q != 0f && abs(q - p) < 0.002f }
            .takeIf { it >= 0 } ?: if (abs(p - w1.toFloat() / h1) < 0.002f || abs(p - h1.toFloat() / w1) < 0.002f) 1 else -1
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            PROPORCIONES.forEachIndexed { i, (nombre, p) ->
                Pildora(if (nombre != null) stringResource(nombre) else nombreProporcion(p ?: 0f), elegida == i) {
                    estado.poner(e.copy(geometria = conProporcion(g, p, estado.vista.width, estado.vista.height)))
                }
            }
            AnimatedVisibility(!g.esNeutra, enter = fadeIn(Muelle.suave()), exit = fadeOut(Muelle.suave())) {
                Text(
                    stringResource(R.string.fotos_restablecer), style = Texto.Pequeno, color = Colores.Acento,
                    modifier = Modifier.clip(Formas.Pastilla).pulsable { estado.poner(e.copy(geometria = Geometria())) }.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BotonIcono(IconosFotos.Girar, stringResource(R.string.fotos_girar), tam = 44.dp, color = Colores.Texto2) {
                Haptica.firme(vista); estado.poner(e.copy(geometria = g.girada()))
            }
            Regla(
                g.enderezar, -45f, 45f,
                onCambio = { estado.moverGesto(estado.edicion.copy(geometria = estado.edicion.geometria.copy(enderezar = it))) },
                onEmpezar = { estado.empezarGesto() }, onTerminar = { estado.terminarGesto() },
                etiqueta = null, sufijo = "°", paso = 1f, separacion = 8f,
                modifier = Modifier.weight(1f),
            )
            BotonIcono(IconosFotos.Espejo, stringResource(R.string.fotos_espejo), tam = 44.dp, color = Colores.Texto2) {
                Haptica.firme(vista); estado.poner(e.copy(geometria = g.reflejada()))
            }
        }
    }
}

/** Fija una proporción: el recorte más grande con esa forma, centrado donde estaba. */
internal fun conProporcion(g: Geometria, p: Float?, w0: Int, h0: Int): Geometria {
    if (p == null) return g.copy(proporcion = null)
    val (w1, h1) = Encuadre.marco(w0, h0, g)
    val prop = if (p == 0f) w1.toFloat() / h1 else p
    val pn = prop * h1 / w1
    var w = min(1f, pn)
    var h = w / pn
    if (h > 1f) { h = 1f; w = pn }
    val cx = g.recorte.centerX().coerceIn(w / 2, 1f - w / 2)
    val cy = g.recorte.centerY().coerceIn(h / 2, 1f - h / 2)
    return g.copy(proporcion = prop, recorte = android.graphics.RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2))
}

// ---------- Modelos (descarga) ----------

/** La tarjeta para descargar un modelo: qué hace, cuánto ocupa y el progreso. */
@Composable
private fun TarjetaModelo(modelo: ModeloFotos, titulo: Int, texto: Int) {
    val ctx = LocalContext.current
    val d by modelo.descarga.collectAsState()
    val pago = remember { runCatching { ModelosFotos.redDePago(ctx) }.getOrDefault(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(stringResource(titulo), style = Texto.Subtitulo, color = Colores.Texto)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(texto), style = Texto.Pequeno, color = Colores.Texto3)
        Spacer(Modifier.height(12.dp))
        AnimatedContent(d, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, contentKey = { it::class }, label = "descarga") { x ->
            when (x) {
                is ModeloFotos.Descarga.EnCurso, ModeloFotos.Descarga.Comprobando -> Column {
                    val hecho = (x as? ModeloFotos.Descarga.EnCurso)?.hecho ?: modelo.bytes
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (x is ModeloFotos.Descarga.EnCurso) stringResource(R.string.fotos_descargando, textoTamano(hecho), textoTamano(modelo.bytes))
                            else stringResource(R.string.fotos_comprobando),
                            style = Texto.Pequeno, color = Colores.Texto2, modifier = Modifier.weight(1f),
                        )
                        Text(stringResource(R.string.fotos_cancelar), style = Texto.Pequeno, color = Colores.Acento, modifier = Modifier.clip(Formas.Pastilla).pulsable { modelo.cancelar() }.padding(8.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    BarraProgreso(hecho.toFloat() / modelo.bytes, Modifier.fillMaxWidth())
                }
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Pildora(stringResource(if (x is ModeloFotos.Descarga.Fallo) R.string.fotos_reintentar else R.string.fotos_descargar_boton, textoTamano(modelo.bytes)), true) { modelo.descargar(ctx) }
                    Spacer(Modifier.width(12.dp))
                    val nota = when {
                        x is ModeloFotos.Descarga.Fallo && x.sinRed -> stringResource(R.string.fotos_descarga_sin_red)
                        x is ModeloFotos.Descarga.Fallo -> stringResource(R.string.fotos_descarga_fallo)
                        pago -> stringResource(R.string.fotos_datos_moviles)
                        else -> stringResource(R.string.fotos_una_vez)
                    }
                    Text(nota, style = Texto.Pequeno, color = if (x is ModeloFotos.Descarga.Fallo) Colores.Rojo else Colores.Texto3, maxLines = 2)
                }
            }
        }
    }
}

// ---------- Borrador ----------

@RequiresApi(33)
@Composable
private fun PanelBorrador(estado: EstadoEditor) {
    val ctx = LocalContext.current
    val m = ModelosFotos.BORRADOR
    LaunchedEffect(Unit) { m.comprobar(ctx) }
    val d by m.descarga.collectAsState()
    if (d != ModeloFotos.Descarga.Lista) {
        TarjetaModelo(m, R.string.fotos_modelo_borrar_titulo, R.string.fotos_modelo_borrar_texto)
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val texto = when {
            estado.borrando -> stringResource(R.string.fotos_borrando)
            estado.falloBorrado -> stringResource(R.string.fotos_borrar_fallo)
            else -> stringResource(R.string.fotos_borrar_pista)
        }
        AnimatedContent(texto, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "pistaBorrar") { x ->
            Text(x, style = Texto.Pequeno, color = if (estado.falloBorrado) Colores.Rojo else Colores.Texto2, modifier = Modifier.padding(horizontal = 24.dp), maxLines = 2)
        }
        Spacer(Modifier.height(14.dp))
        Regla(
            estado.grosor, 0f, 100f, onCambio = { estado.grosor = it },
            etiqueta = stringResource(R.string.fotos_pincel), paso = 1f, separacion = 5f, cero = 40f,
        )
    }
}

// ---------- Sujeto ----------

private val COLORES_FONDO = listOf(0xFFFFFFFF, 0xFF141413, 0xFFF0EEE6, 0xFFE8D5C4, 0xFFC9D6E3, 0xFFD3E4CD, 0xFFF4C7C3).map { it.toInt() }

@RequiresApi(33)
@Composable
private fun PanelSujeto(estado: EstadoEditor) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    val m = ModelosFotos.SUJETO
    LaunchedEffect(Unit) { m.comprobar(ctx) }
    val d by m.descarga.collectAsState()
    LaunchedEffect(d) { if (d == ModeloFotos.Descarga.Lista && estado.mascara == null && !estado.sinSujeto) estado.buscarSujeto() }
    if (d != ModeloFotos.Descarga.Lista && estado.mascara == null) {
        TarjetaModelo(m, R.string.fotos_modelo_sujeto_titulo, R.string.fotos_modelo_sujeto_texto)
        return
    }
    val e = estado.edicion
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when {
            estado.buscandoSujeto -> {
                Text(stringResource(R.string.fotos_buscando_sujeto), style = Texto.Pequeno, color = Colores.Texto2)
                Spacer(Modifier.height(10.dp))
                BarraIndeterminada(Modifier.width(160.dp))
            }
            estado.sinSujeto -> {
                Text(stringResource(R.string.fotos_sin_sujeto), style = Texto.Pequeno, color = Colores.Texto2, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(10.dp))
                Pildora(stringResource(R.string.fotos_reintentar), false) { alcance.launch { estado.reintentarSujeto() } }
            }
            else -> {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    for (f in Fondo.entries) Pildora(stringResource(f.nombre), e.fondo == f) { estado.poner(e.copy(fondo = f)) }
                }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.animation.AnimatedVisibility(e.fondo == Fondo.COLOR, enter = fadeIn(Muelle.suave()), exit = fadeOut(Muelle.suave())) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            for (c in COLORES_FONDO) {
                                val elegido = e.colorFondo == c
                                val anillo by animateFloatAsState(if (elegido) 1f else 0f, Muelle.vivo(), label = "anillo")
                                Box(
                                    Modifier.size(34.dp).clip(Formas.Pastilla).border(2.dp, Colores.Acento.copy(alpha = anillo), Formas.Pastilla).padding(4.dp)
                                        .clip(Formas.Pastilla).background(Color(c)).border(1.dp, Colores.Borde, Formas.Pastilla)
                                        .pulsable { estado.poner(e.copy(colorFondo = c)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Barra que va y viene mientras se trabaja sin saber cuánto falta. */
@Composable
internal fun BarraIndeterminada(modifier: Modifier = Modifier) {
    val ir by androidx.compose.animation.core.rememberInfiniteTransition(label = "indeterminada").animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "ir",
    )
    val acento = Colores.Acento
    val pista = Colores.Borde
    Canvas(modifier.height(4.dp)) {
        drawRoundRect(pista, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        val largo = size.width * 0.35f
        drawRoundRect(acento, topLeft = androidx.compose.ui.geometry.Offset((size.width - largo) * ir, 0f), size = androidx.compose.ui.geometry.Size(largo, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
    }
}

// ---------- Palabras ----------

private val EJEMPLOS = listOf(R.string.fotos_ejemplo_1, R.string.fotos_ejemplo_2, R.string.fotos_ejemplo_3, R.string.fotos_ejemplo_4, R.string.fotos_ejemplo_5)

@RequiresApi(33)
@Composable
private fun PanelPalabras(estado: EstadoEditor) {
    val alcance = rememberCoroutineScope()
    val vista = LocalView.current
    var texto by rememberSaveable { mutableStateOf("") }
    fun enviar(frase: String) {
        if (frase.isBlank() || estado.pensando) return
        alcance.launch { if (estado.pedirPalabras(frase)) { Haptica.hecho(vista); texto = "" } }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(48.dp).clip(Formas.Pastilla).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Pastilla).padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (texto.isEmpty()) Text(stringResource(R.string.fotos_palabras_pista), style = Texto.Cuerpo, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    texto, { texto = it.take(300) }, singleLine = true, enabled = !estado.pensando,
                    textStyle = Texto.Cuerpo.copy(color = Colores.Texto), cursorBrush = SolidColor(Colores.Acento),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { enviar(texto) }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val listo = texto.isNotBlank() && !estado.pensando
            val fondo by animateColorAsState(if (listo) Colores.Acento else Colores.Superficie2, Muelle.suave(), label = "enviar")
            BotonIcono(Iconos.Enviar, stringResource(R.string.fotos_enviar), tam = 40.dp, color = if (listo) Colores.SobreAcento else Colores.Texto3, fondo = fondo) { enviar(texto) }
        }
        Spacer(Modifier.height(10.dp))
        // Las sugerencias llegan hasta el borde (se deslizan); el resto, con margen.
        Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.CenterStart) {
            AnimatedContent(
                when { estado.pensando -> 1; estado.notaIa != null || estado.errorIa -> 2; else -> 0 },
                transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "estadoPalabras",
            ) { fase ->
                when (fase) {
                    1 -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        BarraIndeterminada(Modifier.width(48.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.fotos_pensando, Marca.nombre), style = Texto.Pequeno, color = Colores.Texto2)
                    }
                    2 -> Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (estado.errorIa) Iconos.Cruz else Iconos.Hecho, null, tint = if (estado.errorIa) Colores.Rojo else Colores.Acento, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (estado.errorIa) stringResource(R.string.fotos_palabras_error, Marca.nombre) else estado.notaIa.orEmpty(),
                            style = Texto.Pequeno, color = Colores.Texto2, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    else -> Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (ej in EJEMPLOS) { val f = stringResource(ej); Pildora(f, false) { enviar(f) } }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.fotos_palabras_aviso, Marca.nombre), modifier = Modifier.padding(horizontal = 16.dp), style = Texto.Pequeno.copy(fontSize = Texto.Etiqueta.fontSize, lineHeight = Texto.Pequeno.lineHeight * 0.85f), color = Colores.Texto3)
    }
}
