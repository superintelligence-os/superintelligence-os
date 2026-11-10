package com.sios.boveda

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.ui.BotonIcono
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** La barra de arriba para listas: el título pequeño aparece cuando se va el grande. */
@Composable
internal fun BarraLista(titulo: String, lista: LazyListState, onAtras: () -> Unit, acciones: @Composable () -> Unit = {}) {
    val densidad = LocalDensity.current
    val desde = with(densidad) { 36.dp.toPx() }
    val tramo = with(densidad) { 28.dp.toPx() }
    fun alfa() = if (lista.firstVisibleItemIndex > 0) 1f else ((lista.firstVisibleItemScrollOffset - desde) / tramo).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        BotonIcono(Iconos.Atras, stringResource(R.string.comun_atras), Modifier.align(Alignment.CenterStart).padding(start = 8.dp), onClick = onAtras)
        Text(
            titulo, style = Texto.Subtitulo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp).graphicsLayer { alpha = alfa() },
        )
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) { acciones() }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).graphicsLayer { alpha = alfa() }.background(Colores.Borde))
    }
}

@Composable
internal fun CampoBuscar(texto: String, pista: String, onCambio: (String) -> Unit) {
    val foco = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(Formas.Pequena).background(Colores.Superficie2).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Iconos.Buscar, null, tint = Colores.Texto3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (texto.isEmpty()) Text(pista, style = Texto.Cuerpo, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                texto, onCambio, singleLine = true,
                textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
                cursorBrush = SolidColor(Colores.Acento),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { foco.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(texto.isNotEmpty(), enter = fadeIn(Muelle.suave()), exit = fadeOut(Muelle.suave())) {
            BotonIcono(Iconos.Cruz, stringResource(R.string.ajs_borrar), tam = 36.dp, color = Colores.Texto3) { onCambio("") }
        }
    }
}

/** La forma de una fila en una lista agrupada: redondeada arriba la primera y abajo la última. */
internal fun formaFila(primera: Boolean, ultima: Boolean) = RoundedCornerShape(
    topStart = if (primera) 16.dp else 0.dp, topEnd = if (primera) 16.dp else 0.dp,
    bottomStart = if (ultima) 16.dp else 0.dp, bottomEnd = if (ultima) 16.dp else 0.dp,
)

private fun encaja(e: Entrada, q: String): Boolean {
    if (q.isEmpty()) return true
    val texto = Generador.plano(listOf(e.nombre, e.usuario, e.numero, e.sitios.joinToString(" ") { Sitios.visible(it) }).joinToString(" "))
    return q.split(' ').filter { it.isNotBlank() }.all { texto.contains(it) }
}

@Composable
internal fun Principal(nav: Navegacion, avisos: Avisador, onAtras: () -> Unit, pestanaInicial: Int = 0, buscarInicial: String = "") {
    val ctx = LocalContext.current
    val entradas by Boveda.entradas.collectAsStateWithLifecycle()
    val filtradas by Boveda.filtradas.collectAsStateWithLifecycle()
    var pestana by rememberSaveable { mutableStateOf(pestanaInicial) }
    var buscar by rememberSaveable { mutableStateOf(buscarInicial) }
    var anadirCodigo by remember { mutableStateOf(false) }
    val lista = rememberLazyListState()
    val titulo = stringResource(R.string.bv_titulo)
    val salud = remember(entradas, filtradas) { Salud.de(entradas, filtradas) }
    val q = Generador.plano(buscar.trim())
    val contrasenas = remember(entradas, q) { entradas.filter { it.tipo == Tipo.CONTRASENA && encaja(it, q) } }
    val codigos = remember(entradas, q) { entradas.filter { it.totp != null && it.tipo != Tipo.DOCUMENTO && encaja(it, q) } }
    val documentos = remember(entradas, q) { entradas.filter { it.tipo == Tipo.DOCUMENTO && encaja(it, q) } }
    val ahora = rememberAhora()

    fun leerQr(texto: String) {
        when {
            Totp.esMigracion(texto) -> {
                val m = Totp.deMigracion(texto)
                val nuevas = Importar.nuevas(Importar.codigosDe(m?.codigos.orEmpty()), Boveda.entradas.value)
                Boveda.guardarVarias(nuevas)
                avisos.decir(ctx.resources.getQuantityString(R.plurals.bv_importados, nuevas.size, nuevas.size))
            }
            else -> Totp.deUri(texto)?.let { l ->
                nav.abrir(Destino.Editor(Tipo.CODIGO, plantilla = Entrada(tipo = Tipo.CODIGO, nombre = l.emisor.ifBlank { l.cuenta }, usuario = l.cuenta, totp = l.clave)))
            } ?: avisos.decir(ctx.getString(R.string.bv_qr_no_vale))
        }
    }
    fun abrirCamaraQr() = nav.abrir(Destino.Camara(ModoCamara.QR) { r -> (r as? ResultadoCamara.Texto)?.let { leerQr(it.texto) } })

    Box(Modifier.fillMaxSize().background(Colores.Fondo)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            BarraLista(titulo, lista, onAtras) {
                BotonIcono(IconosBoveda.Engranaje, stringResource(R.string.bv_ajustes), color = Colores.Texto2) { nav.abrir(Destino.Ajustes) }
                BotonIcono(IconosBoveda.Candado, stringResource(R.string.bv_cerrar), color = Colores.Texto2) { Boveda.cerrar() }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = lista,
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 120.dp),
            ) {
                item(key = "titulo") {
                    Column {
                        Text(titulo, style = com.sios.ajustes.TituloGrande, color = Colores.Texto)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(IconosBoveda.Candado, null, tint = Colores.Texto3, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            val m = Boveda.minutosCierre(ctx)
                            Text(
                                if (m <= 0) stringResource(R.string.bv_estado_al_salir) else pluralStringResource(R.plurals.bv_estado_minutos, m, m),
                                style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        CampoBuscar(buscar, stringResource(R.string.bv_buscar)) { buscar = it }
                        Spacer(Modifier.height(12.dp))
                        Pestanas(
                            listOf(stringResource(R.string.bv_pestana_contrasenas), stringResource(R.string.bv_pestana_codigos), stringResource(R.string.bv_pestana_documentos)),
                            pestana, { pestana = it },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
                when (pestana) {
                    0 -> {
                        if (salud.problemas > 0 && q.isEmpty()) item(key = "salud") {
                            TarjetaSalud(salud, Modifier.padding(bottom = 16.dp).animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())) { nav.abrir(Destino.Salud) }
                        }
                        if (contrasenas.isEmpty()) item(key = "vacio0") {
                            if (q.isNotEmpty()) SinResultados(buscar)
                            else Vacio(IconosBoveda.Llave, stringResource(R.string.bv_vacio_contrasenas), stringResource(R.string.bv_vacio_contrasenas_nota))
                        }
                        itemsIndexed(contrasenas, key = { _, e -> "c" + e.id }) { i, e ->
                            Box(Modifier.animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())) {
                                FilaCuenta(e, salud, i == 0, i == contrasenas.lastIndex) { nav.abrir(Destino.Ficha(e.id)) }
                            }
                        }
                    }
                    1 -> {
                        if (codigos.isEmpty()) item(key = "vacio1") {
                            if (q.isNotEmpty()) SinResultados(buscar)
                            else Vacio(IconosBoveda.Qr, stringResource(R.string.bv_vacio_codigos), stringResource(R.string.bv_vacio_codigos_nota))
                        }
                        itemsIndexed(codigos, key = { _, e -> "t" + e.id }) { i, e ->
                            Box(Modifier.animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())) {
                                FilaCodigo(e, ahora, i == 0, i == codigos.lastIndex, onCopiar = { c ->
                                    Boveda.copiar(ctx, c)
                                    avisos.decir(ctx.getString(R.string.bv_codigo_copiado))
                                }) { nav.abrir(Destino.Ficha(e.id)) }
                            }
                        }
                    }
                    else -> {
                        if (documentos.isEmpty()) item(key = "vacio2") {
                            if (q.isNotEmpty()) SinResultados(buscar)
                            else Vacio(IconosBoveda.Dni, stringResource(R.string.bv_vacio_documentos), stringResource(R.string.bv_vacio_documentos_nota))
                        }
                        // De dos en dos, como tarjetas.
                        items(documentos.chunked(2), key = { par -> "d" + par.first().id }) { par ->
                            Row(
                                Modifier.padding(bottom = 12.dp).animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave()),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                for (d in par) TarjetaDocumento(d, Modifier.weight(1f)) { nav.abrir(Destino.Ficha(d.id)) }
                                if (par.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        BotonAnadir(stringResource(R.string.bv_anadir), visible = true) {
            when (pestana) {
                0 -> nav.abrir(Destino.Editor(Tipo.CONTRASENA))
                1 -> anadirCodigo = true
                else -> nav.abrir(Destino.Editor(Tipo.DOCUMENTO))
            }
        }
    }
    if (anadirCodigo) Hoja({ anadirCodigo = false }) {
        Text(stringResource(R.string.bv_anadir_codigo), style = Texto.Subtitulo, color = Colores.Texto)
        Text(stringResource(R.string.bv_anadir_codigo_nota), style = Texto.Cuerpo, color = Colores.Texto2)
        com.sios.ui.BotonPrimario(stringResource(R.string.bv_escanear_qr), Modifier.fillMaxWidth()) { anadirCodigo = false; abrirCamaraQr() }
        com.sios.ui.BotonSecundario(stringResource(R.string.bv_escribir_clave), Modifier.fillMaxWidth()) { anadirCodigo = false; nav.abrir(Destino.Editor(Tipo.CODIGO)) }
        BotonTexto(stringResource(R.string.bv_cancelar), Modifier.fillMaxWidth()) { anadirCodigo = false }
    }
}

@Composable
private fun SinResultados(buscar: String) {
    Text(
        stringResource(R.string.bv_sin_resultados, buscar.trim()), style = Texto.Cuerpo, color = Colores.Texto3, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
    )
}

/** «3 contraseñas para revisar»: repetidas, débiles y filtradas, en una línea. Lleva a la revisión. */
@Composable
private fun TarjetaSalud(s: Salud, modifier: Modifier, onClick: () -> Unit) {
    val interaccion = remember { MutableInteractionSource() }
    val pulsado by interaccion.collectIsPressedAsState()
    val fondo by animateColorAsState(if (pulsado) Colores.Superficie2 else Colores.Superficie, Muelle.suave(), label = "salud")
    val ambar = androidx.compose.ui.graphics.Color(0xFFD99A2B)
    val color = if (s.filtradas.isNotEmpty()) Colores.Rojo else ambar
    Row(
        modifier.fillMaxWidth().clip(Formas.Tarjeta).background(fondo).clickable(interaccion, indication = null, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(IconosBoveda.EscudoAviso, null, tint = color, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pluralStringResource(R.plurals.bv_salud_titulo, s.problemas, s.problemas), style = Texto.Subtitulo.copy(fontSize = 15.sp), color = Colores.Texto)
            val partes = listOfNotNull(
                s.filtradas.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.bv_salud_filtradas, it, it) },
                s.repetidas.sumOf { it.size }.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.bv_salud_repetidas, it, it) },
                s.debiles.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.bv_salud_debiles, it, it) },
            )
            Text(partes.joinToString(" · "), style = Texto.Pequeno, color = Colores.Texto3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
    }
}

/** Una cuenta: su distintivo, el nombre y el usuario; un puntito si hay algo que revisar. */
@Composable
internal fun FilaCuenta(e: Entrada, salud: Salud?, primera: Boolean, ultima: Boolean, onClick: () -> Unit) {
    val interaccion = remember { MutableInteractionSource() }
    val pulsado by interaccion.collectIsPressedAsState()
    val fondo by animateColorAsState(if (pulsado) Colores.Superficie2 else Colores.Superficie, Muelle.suave(), label = "fila")
    Box(Modifier.fillMaxWidth().clip(formaFila(primera, ultima)).background(fondo).clickable(interaccion, indication = null, onClick = onClick)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Distintivo(e, 38.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.nombre.ifBlank { e.sitioVisible ?: e.usuario }, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val nota = e.usuario.ifBlank { e.sitioVisible ?: "" }
                if (nota.isNotEmpty()) Text(nota, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (e.totp != null) {
                Spacer(Modifier.width(8.dp))
                Text("2FA", style = Texto.Etiqueta.copy(fontSize = 10.sp), color = Colores.Texto3, modifier = Modifier.clip(Formas.Pastilla).background(Colores.Superficie2).padding(horizontal = 7.dp, vertical = 3.dp))
            }
            val revisar = salud != null && (salud.filtrada(e) > 0 || salud.repetidaCon(e) > 0 || salud.debil(e))
            if (revisar) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(Formas.Pastilla).background(if (salud!!.filtrada(e) > 0) Colores.Rojo else androidx.compose.ui.graphics.Color(0xFFD99A2B)))
            }
            Spacer(Modifier.width(10.dp))
            Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
        }
        if (!ultima) Box(Modifier.align(Alignment.BottomStart).padding(start = 68.dp).fillMaxWidth().height(1.dp).background(Colores.Borde))
    }
}

/** Un código 2FA: tocar lo copia; mantener pulsado abre la ficha. El anillo dice cuánto le queda. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilaCodigo(
    e: Entrada,
    ahora: androidx.compose.runtime.State<Long>,
    primera: Boolean,
    ultima: Boolean,
    onCopiar: (String) -> Unit,
    onAbrir: () -> Unit,
) {
    val clave = e.totp ?: return
    val interaccion = remember { MutableInteractionSource() }
    val pulsado by interaccion.collectIsPressedAsState()
    val fondo by animateColorAsState(if (pulsado) Colores.Superficie2 else Colores.Superficie, Muelle.suave(), label = "fila")
    // El código solo cambia una vez por periodo: no se recalcula en cada fotograma.
    val ventana by remember(clave) { androidx.compose.runtime.derivedStateOf { ahora.value / 1000 / clave.periodo.coerceAtLeast(1) } }
    val codigo = remember(clave, ventana) { runCatching { Totp.codigo(clave, ventana * 1000 * clave.periodo.coerceAtLeast(1)) }.getOrDefault("------") }
    val poco by remember(clave) { androidx.compose.runtime.derivedStateOf { Totp.restante(clave, ahora.value) < 6f } }
    val color by animateColorAsState(if (poco) Colores.Rojo else Colores.Texto, Muelle.suave(), label = "codigo")
    Box(
        Modifier.fillMaxWidth().clip(formaFila(primera, ultima)).background(fondo)
            .combinedClickable(interaccion, indication = null, onLongClick = onAbrir) { onCopiar(codigo) },
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Distintivo(e, 38.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(e.nombre.ifBlank { e.usuario }, style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Texto2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    Totp.agrupar(codigo), color = color, maxLines = 1,
                    style = Texto.Titulo.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 24.sp, letterSpacing = 1.sp),
                )
                if (e.usuario.isNotBlank() && e.usuario != e.nombre) Text(e.usuario, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            AnilloTiempo(clave, ahora, 24.dp)
        }
        if (!ultima) Box(Modifier.align(Alignment.BottomStart).padding(start = 68.dp).fillMaxWidth().height(1.dp).background(Colores.Borde))
    }
}

/** Un documento como una tarjetita: la primera página (o su icono), el nombre y si caduca pronto. */
@Composable
private fun TarjetaDocumento(d: Entrada, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val c = Categoria.de(d.categoria)
    val portada by produceState<ImageBitmap?>(null, d.archivos.firstOrNull()?.id) {
        val a = d.archivos.firstOrNull() ?: return@produceState
        value = withContext(Dispatchers.IO) { Archivos.imagen(ctx, a, 480)?.asImageBitmap() }
    }
    val interaccion = remember { MutableInteractionSource() }
    val pulsado by interaccion.collectIsPressedAsState()
    val fondo by animateColorAsState(if (pulsado) Colores.Superficie2 else Colores.Superficie, Muelle.suave(), label = "doc")
    Column(modifier.clip(Formas.Tarjeta).background(fondo).clickable(interaccion, indication = null, onClick = onClick).padding(10.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.586f).clip(RoundedCornerShape(10.dp)).background(colorCategoria(c).copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            if (portada == null) Icon(iconoCategoria(c), null, tint = colorCategoria(c), modifier = Modifier.size(34.dp))
            ImagenSuave(portada, Modifier.fillMaxSize())
            if (d.archivos.size > 1) Text(
                "${d.archivos.size}", style = Texto.Etiqueta, color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(Formas.Pastilla).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)).padding(horizontal = 7.dp, vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(d.nombre.ifBlank { stringResource(nombreCategoria(c)) }, style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        val caduca = Caducidad.de(d.caduca)
        Text(
            when {
                caduca == null -> stringResource(nombreCategoria(c))
                caduca.vencido -> stringResource(R.string.bv_caducado, d.caduca)
                else -> stringResource(R.string.bv_caduca, d.caduca)
            },
            style = Texto.Pequeno, color = if (caduca?.pronto == true || caduca?.vencido == true) Colores.Rojo else Colores.Texto3,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Spacer(Modifier.height(2.dp))
    }
}

/** La caducidad escrita a mano («05/2031», «12/05/2031», «2031-05-12»): si ha pasado o está cerca (60 días). */
internal class Caducidad(val vencido: Boolean, val pronto: Boolean) {
    companion object {
        fun de(s: String): Caducidad? {
            val t = s.trim()
            if (t.isEmpty()) return null
            val n = Regex("\\d+").findAll(t).map { it.value }.toList()
            val fecha = runCatching {
                when {
                    n.size == 2 && n[1].length == 4 -> java.time.YearMonth.of(n[1].toInt(), n[0].toInt()).atEndOfMonth()
                    n.size == 2 && n[1].length == 2 -> java.time.YearMonth.of(2000 + n[1].toInt(), n[0].toInt()).atEndOfMonth()
                    n.size == 3 && n[0].length == 4 -> java.time.LocalDate.of(n[0].toInt(), n[1].toInt(), n[2].toInt())
                    n.size == 3 -> java.time.LocalDate.of(n[2].toInt().let { if (it < 100) 2000 + it else it }, n[1].toInt(), n[0].toInt())
                    else -> null
                }
            }.getOrNull() ?: return null
            val hoy = java.time.LocalDate.now()
            return Caducidad(fecha.isBefore(hoy), !fecha.isBefore(hoy) && fecha.isBefore(hoy.plusDays(60)))
        }
    }
}
