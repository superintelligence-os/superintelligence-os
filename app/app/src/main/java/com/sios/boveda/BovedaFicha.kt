package com.sios.boveda

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.ajustes.BarraSuperior
import com.sios.ui.BotonIcono
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.haceRato
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una etiqueta de grupo, como en Ajustes. */
@Composable
internal fun EtiquetaGrupo(t: String, modifier: Modifier = Modifier) =
    Text(t.uppercase(), style = Texto.Etiqueta, color = Colores.Texto3, modifier = modifier.padding(bottom = 8.dp, start = 2.dp))

/** Un dato de la ficha: etiqueta pequeña y valor; los botones a la derecha. */
@Composable
private fun Dato(
    etiqueta: String,
    separador: Boolean,
    acciones: @Composable () -> Unit = {},
    debajo: (@Composable () -> Unit)? = null,
    valor: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(etiqueta, style = Texto.Pequeno, color = Colores.Texto3)
                Spacer(Modifier.height(2.dp))
                valor()
                if (debajo != null) { Spacer(Modifier.height(8.dp)); debajo() }
            }
            Spacer(Modifier.width(6.dp))
            acciones()
        }
        if (separador) Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Colores.Borde))
    }
}

/** Un aviso de la revisión en la ficha: filtrada, repetida o débil. */
@Composable
private fun AvisoFicha(icono: ImageVector, color: Color, texto: String) {
    Row(
        Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(texto, style = Texto.Pequeno, color = Colores.Texto2, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun Ficha(id: String, nav: Navegacion, avisos: Avisador) {
    val ctx = LocalContext.current
    val entradas by Boveda.entradas.collectAsStateWithLifecycle()
    val filtradas by Boveda.filtradas.collectAsStateWithLifecycle()
    val e = entradas.firstOrNull { it.id == id }
    // Recién borrada: la página se va (y mientras sale se sigue viendo lo último).
    var ultima by remember { mutableStateOf(e) }
    if (e != null) ultima = e
    val x = ultima ?: return
    LaunchedEffect(e == null) { if (e == null) nav.cerrar(forzar = true) }
    val salud = remember(entradas, filtradas) { Salud.de(entradas, filtradas) }
    val scroll = rememberScrollState()
    var ver by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf(false) }
    var anteriores by remember { mutableStateOf(false) }
    val ahora = rememberAhora()
    fun copiar(t: String, aviso: Int) { Boveda.copiar(ctx, t); avisos.decir(ctx.getString(aviso)) }
    val titulo = x.nombre.ifBlank { x.sitioVisible ?: x.usuario }

    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding()) {
        Box {
            BarraSuperior(titulo, scroll) { nav.cerrar() }
            BotonIcono(Iconos.Lapiz, stringResource(R.string.bv_editar), Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), color = Colores.Texto2) {
                nav.abrir(Destino.Editor(x.tipo, x.id))
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Distintivo(x, 56.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(titulo, style = com.sios.ajustes.TituloGrande.copy(fontSize = 24.sp, lineHeight = 30.sp), color = Colores.Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = when (x.tipo) {
                        Tipo.DOCUMENTO -> stringResource(nombreCategoria(Categoria.de(x.categoria)))
                        else -> x.sitioVisible ?: stringResource(if (x.tipo == Tipo.CODIGO) R.string.bv_tipo_codigo else R.string.bv_tipo_contrasena)
                    }
                    Text(sub, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Lo que hay que revisar.
            val filtrada = salud.filtrada(x)
            val repetida = salud.repetidaCon(x)
            if (filtrada > 0 || repetida > 0 || salud.debil(x)) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (filtrada > 0) AvisoFicha(IconosBoveda.EscudoAviso, Colores.Rojo, pluralStringResource(R.plurals.bv_ficha_filtrada, filtrada, java.text.NumberFormat.getIntegerInstance().format(filtrada)))
                if (repetida > 0) AvisoFicha(IconosBoveda.Aviso, Color(0xFFD99A2B), pluralStringResource(R.plurals.bv_ficha_repetida, repetida, repetida))
                if (salud.debil(x)) AvisoFicha(IconosBoveda.Aviso, Color(0xFFD99A2B), stringResource(R.string.bv_ficha_debil))
            }

            if (x.tipo != Tipo.DOCUMENTO) {
                Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).animateContentSize(Muelle.suave())) {
                    val filas = listOfNotNull(
                        "u".takeIf { x.usuario.isNotEmpty() },
                        "c".takeIf { x.contrasena.isNotEmpty() },
                        "t".takeIf { x.totp != null },
                    )
                    for ((i, f) in filas.withIndex()) {
                        val sep = i < filas.lastIndex
                        when (f) {
                            "u" -> Dato(stringResource(R.string.bv_usuario), sep, acciones = {
                                BotonIcono(IconosBoveda.Copiar, stringResource(R.string.bv_copiar), color = Colores.Texto2) { copiar(x.usuario, R.string.bv_usuario_copiado) }
                            }) { Text(x.usuario, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                            "c" -> Dato(
                                stringResource(R.string.bv_contrasena), sep,
                                acciones = {
                                    BotonIcono(if (ver) IconosBoveda.OjoNo else IconosBoveda.Ojo, stringResource(if (ver) R.string.bv_ocultar else R.string.bv_ver), color = Colores.Texto2) { ver = !ver }
                                    BotonIcono(IconosBoveda.Copiar, stringResource(R.string.bv_copiar), color = Colores.Texto2) { copiar(x.contrasena, R.string.bv_contrasena_copiada) }
                                },
                                debajo = { MedidorFuerza(x.contrasena) },
                            ) {
                                Crossfade(ver, animationSpec = Muelle.suave(), label = "ver") { v ->
                                    if (v) ContrasenaColor(x.contrasena)
                                    else PuntosSecretos(x.contrasena.length.coerceIn(8, 14))
                                }
                            }
                            "t" -> {
                                val clave = x.totp!!
                                val ventana by remember(clave) { androidx.compose.runtime.derivedStateOf { ahora.value / 1000 / clave.periodo.coerceAtLeast(1) } }
                                val codigo = remember(clave, ventana) { runCatching { Totp.codigo(clave, ventana * 1000 * clave.periodo) }.getOrDefault("------") }
                                Dato(stringResource(R.string.bv_codigo), sep, acciones = {
                                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { AnilloTiempo(clave, ahora, 22.dp) }
                                    BotonIcono(IconosBoveda.Copiar, stringResource(R.string.bv_copiar), color = Colores.Texto2) { copiar(codigo, R.string.bv_codigo_copiado) }
                                }) {
                                    Text(Totp.agrupar(codigo), style = Texto.Titulo.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 24.sp, letterSpacing = 1.sp), color = Colores.Texto)
                                }
                            }
                        }
                    }
                }
                if (x.sitios.isNotEmpty()) Column {
                    EtiquetaGrupo(stringResource(R.string.bv_sitios))
                    Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie)) {
                        x.sitios.forEachIndexed { i, s ->
                            val app = Sitios.esApp(s)
                            val nombre = if (app) BovedaAutofill.etiquetaApp(ctx, s.removePrefix(Sitios.APP)) else Sitios.visible(s)
                            Box(Modifier.fillMaxWidth().pulsableSuave {
                                if (!app) runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Sitios.normalizar(s))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (app) Iconos.Telefono else Iconos.Mundo, null, tint = Colores.Texto3, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(nombre, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (app && nombre != s.removePrefix(Sitios.APP)) Text(s.removePrefix(Sitios.APP), style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                if (i < x.sitios.lastIndex) Box(Modifier.align(Alignment.BottomStart).padding(start = 46.dp).fillMaxWidth().height(1.dp).background(Colores.Borde))
                            }
                        }
                    }
                }
            } else {
                // Documento: sus páginas (tocar para verlas en grande) y sus datos.
                if (x.archivos.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    x.archivos.forEachIndexed { i, a -> Miniatura(a, x.archivos.size == 1) { nav.abrir(Destino.Visor(x.id, i)) } }
                }
                val filas = listOfNotNull("n".takeIf { x.numero.isNotEmpty() }, "c".takeIf { x.caduca.isNotEmpty() })
                if (filas.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie)) {
                    for ((i, f) in filas.withIndex()) when (f) {
                        "n" -> Dato(stringResource(R.string.bv_numero), i < filas.lastIndex, acciones = {
                            BotonIcono(IconosBoveda.Copiar, stringResource(R.string.bv_copiar), color = Colores.Texto2) { copiar(x.numero, R.string.bv_numero_copiado) }
                        }) { Text(x.numero, style = Texto.Cuerpo.copy(fontFamily = FontFamily.Monospace, letterSpacing = 0.5.sp), color = Colores.Texto) }
                        "c" -> {
                            val c = Caducidad.de(x.caduca)
                            Dato(stringResource(R.string.bv_caducidad), i < filas.lastIndex) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(x.caduca, style = Texto.Cuerpo, color = Colores.Texto)
                                    if (c?.vencido == true || c?.pronto == true) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            stringResource(if (c.vencido) R.string.bv_vencido else R.string.bv_vence_pronto),
                                            style = Texto.Etiqueta, color = Colores.Rojo,
                                            modifier = Modifier.clip(Formas.Pastilla).background(Colores.Rojo.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (x.notas.isNotBlank()) Column {
                EtiquetaGrupo(stringResource(R.string.bv_notas))
                Text(x.notas, style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(16.dp))
            }

            if (x.anteriores.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).animateContentSize(Muelle.suave())) {
                Row(
                    Modifier.fillMaxWidth().pulsableSuave { anteriores = !anteriores }.heightIn(min = 52.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(pluralStringResource(R.plurals.bv_anteriores, x.anteriores.size, x.anteriores.size), style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.weight(1f))
                    val giro by animateFloatAsState(if (anteriores) 180f else 0f, Muelle.suave(), label = "giro")
                    Icon(Iconos.Abajo, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = giro })
                }
                if (anteriores) for (a in x.anteriores) {
                    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(1.dp).background(Colores.Borde))
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.contrasena, style = Texto.Codigo, color = Colores.Texto2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.bv_usada_hasta, haceRato(ctx, a.hasta)), style = Texto.Pequeno, color = Colores.Texto3)
                        }
                        BotonIcono(IconosBoveda.Copiar, stringResource(R.string.bv_copiar), color = Colores.Texto3) { copiar(a.contrasena, R.string.bv_contrasena_copiada) }
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.bv_modificada, haceRato(ctx, x.cambiada)), style = Texto.Pequeno, color = Colores.Texto3)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.bv_borrar), style = Texto.Subtitulo, color = Colores.Rojo,
                    modifier = Modifier.clip(Formas.Pequena).pulsable { borrar = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
    if (borrar) Hoja({ borrar = false }) {
        Text(stringResource(R.string.bv_borrar_titulo, titulo), style = Texto.Subtitulo, color = Colores.Texto)
        Text(stringResource(if (x.tipo == Tipo.DOCUMENTO) R.string.bv_borrar_nota_doc else R.string.bv_borrar_nota), style = Texto.Cuerpo, color = Colores.Texto2)
        BotonPeligro(stringResource(R.string.bv_borrar), Modifier.fillMaxWidth()) {
            borrar = false
            Thread { runCatching { Boveda.borrar(ctx, x.id) } }.start()
        }
        BotonTexto(stringResource(R.string.bv_cancelar), Modifier.fillMaxWidth()) { borrar = false }
    }
}

/** La contraseña a la vista, con los números y los símbolos en color (se distinguen mejor). */
@Composable
internal fun ContrasenaColor(c: String, modifier: Modifier = Modifier, tam: Int = 16) {
    val acento = Colores.Acento
    val azul = Color(0xFF4A82DB)
    val texto = Colores.Texto
    val anotado = remember(c, acento, texto) {
        androidx.compose.ui.text.buildAnnotatedString {
            for (ch in c) {
                val color = when {
                    ch.isDigit() -> acento
                    !ch.isLetterOrDigit() -> azul
                    else -> texto
                }
                pushStyle(androidx.compose.ui.text.SpanStyle(color = color)); append(ch); pop()
            }
        }
    }
    Text(anotado, style = Texto.Codigo.copy(fontSize = tam.sp, lineHeight = (tam + 7).sp, letterSpacing = 0.4.sp), modifier = modifier)
}

/** Pulsación que solo ilumina (para filas que no deben encogerse). */
@Composable
internal fun Modifier.pulsableSuave(onClick: () -> Unit): Modifier {
    val interaccion = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pulsado by interaccion.collectIsPressedAsState()
    val fondo by androidx.compose.animation.animateColorAsState(if (pulsado) Colores.Superficie2 else Colores.Superficie.copy(alpha = 0f), Muelle.suave(), label = "pulsar")
    return this.background(fondo).clickable(interaccion, indication = null, onClick = onClick)
}

/** Una página de un documento en pequeño (la primera, más grande si es la única). */
@Composable
private fun Miniatura(a: Archivo, sola: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val ancho = if (sola) LocalConfiguration.current.screenWidthDp.dp - 40.dp else 200.dp
    val img by produceState<ImageBitmap?>(null, a.id) { value = withContext(Dispatchers.IO) { Archivos.imagen(ctx, a, 900)?.asImageBitmap() } }
    Box(
        Modifier.width(ancho).height(ancho / 1.586f).clip(RoundedCornerShape(14.dp)).background(Colores.Superficie).pulsable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (img == null) Icon(if (a.esPdf) Iconos.Documento else IconosBoveda.Imagen, null, tint = Colores.Texto3, modifier = Modifier.size(30.dp))
        ImagenSuave(img, Modifier.fillMaxSize())
        if (a.esPdf) Text(
            "PDF", style = Texto.Etiqueta, color = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).clip(Formas.Pastilla).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** La vista rápida de un documento: a pantalla completa, sobre negro, con zoom de dos dedos. */
@Composable
internal fun Visor(id: String, pagina: Int, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val e = Boveda.entrada(id) ?: return
    // Cada página de cada archivo (los PDF pueden tener varias).
    val paginas by produceState<List<Pair<Archivo, Int>>>(e.archivos.map { it to 0 }, id) {
        value = withContext(Dispatchers.IO) { e.archivos.flatMap { a -> if (a.esPdf) (0 until Archivos.paginasPdf(ctx, a)).map { a to it } else listOf(a to 0) } }
    }
    val inicio = e.archivos.take(pagina).sumOf { a -> paginas.count { it.first.id == a.id } }
    val estado = rememberPagerState(initialPage = inicio.coerceAtLeast(0)) { paginas.size }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(estado, Modifier.fillMaxSize(), key = { "${paginas[it].first.id}-${paginas[it].second}" }) { i ->
            val (a, n) = paginas[i]
            val ancho = (LocalConfiguration.current.screenWidthDp * 3).coerceAtMost(2000)
            val img by produceState<ImageBitmap?>(null, a.id, n) { value = withContext(Dispatchers.IO) { Archivos.imagen(ctx, a, ancho, n)?.asImageBitmap() } }
            Zoom { ImagenSuave(img, Modifier.fillMaxSize(), ContentScale.Fit) }
        }
        Row(Modifier.statusBarsPadding().padding(8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BotonIcono(Iconos.Cruz, stringResource(R.string.bv_cerrar_visor), color = Color.White, fondo = Color.Black.copy(alpha = 0.35f), onClick = onCerrar)
            Spacer(Modifier.weight(1f))
            if (paginas.size > 1) Text(
                "${estado.currentPage + 1} / ${paginas.size}", style = Texto.Pequeno, color = Color.White,
                modifier = Modifier.clip(Formas.Pastilla).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

/**
 * Zoom con dos dedos (y doble toque para acercar o volver), que vuelve a su sitio con un muelle. Con
 * un dedo y sin zoom no se queda el gesto: así se pasa de página.
 */
@Composable
private fun Zoom(contenido: @Composable () -> Unit) {
    val escala = remember { Animatable(1f) }
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }
    val alcance = rememberCoroutineScope()
    fun volver() = alcance.launch {
        launch { escala.animateTo(1f, Muelle.suave()) }
        launch { dx.animateTo(0f, Muelle.suave()) }
        launch { dy.animateTo(0f, Muelle.suave()) }
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (escala.value > 1.5f) volver()
                    else alcance.launch { escala.animateTo(2.5f, Muelle.suave()) }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val dedos = ev.changes.count { it.pressed }
                        if (dedos >= 2 || escala.value > 1.01f) {
                            val s = (escala.value * ev.calculateZoom()).coerceIn(1f, 5f)
                            val pan = ev.calculatePan()
                            alcance.launch {
                                escala.snapTo(s)
                                dx.snapTo(dx.value + pan.x); dy.snapTo(dy.value + pan.y)
                            }
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                    if (escala.value <= 1.05f) volver()
                }
            }
            .graphicsLayer { scaleX = escala.value; scaleY = escala.value; translationX = dx.value; translationY = dy.value },
    ) { contenido() }
}
