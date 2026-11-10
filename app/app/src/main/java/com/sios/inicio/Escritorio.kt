package com.sios.inicio

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import com.sios.R
import com.sios.datos.Creacion
import com.sios.ui.BotonPrimario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.WidgetCreacion
import com.sios.ui.pulsable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import kotlin.math.roundToInt

/**
 * Lo que hay en la pantalla de inicio: el orden de los widgets («c:<id>» de Claude, «a:<id>» de
 * apps de Android), los widgets de Claude que el usuario quitó del inicio y las apps del muelle.
 * Los widgets nuevos de Claude aparecen solos al final.
 */
object Escritorio {
    data class Estado(
        val orden: List<String> = emptyList(),
        val ocultos: Set<String> = emptySet(),
        val muelle: List<String> = emptyList(),
        val tamanos: Map<String, String> = emptyMap(), // tamaño en la rejilla de los widgets de apps («a:<id>»)
    )

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("escritorio", Context.MODE_PRIVATE)
    private fun lista(s: String?) = runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())

    fun cargar(ctx: Context) {
        val p = prefs(ctx)
        val tamanos = runCatching { org.json.JSONObject(p.getString("tamanos", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrDefault(emptyMap())
        _estado.value = Estado(lista(p.getString("orden", null)), lista(p.getString("ocultos", null)).toSet(), lista(p.getString("muelle", null)), tamanos)
    }

    private fun guardar(ctx: Context, e: Estado) {
        _estado.value = e
        prefs(ctx).edit()
            .putString("orden", JSONArray(e.orden).toString())
            .putString("ocultos", JSONArray(e.ocultos.toList()).toString())
            .putString("muelle", JSONArray(e.muelle).toString())
            .putString("tamanos", org.json.JSONObject(e.tamanos).toString())
            .apply()
    }

    /** Widgets del inicio, en orden: los guardados que sigan existiendo y, al final, los nuevos de Claude. */
    fun visibles(e: Estado, creaciones: List<Creacion>, appWidgets: Set<Int>): List<String> {
        val deIa = creaciones.filter { it.tipo == "widget" && it.lugar == "inicio" }.map { "c:${it.id}" }
        val validos = e.orden.filter { k -> if (k.startsWith("c:")) k in deIa && k !in e.ocultos else k.removePrefix("a:").toIntOrNull() in appWidgets }
        return validos + deIa.filter { it !in e.orden && it !in e.ocultos }
    }

    fun ordenar(ctx: Context, claves: List<String>) = guardar(ctx, _estado.value.copy(orden = claves))

    fun quitar(ctx: Context, clave: String, host: AppWidgetHost) {
        val e = _estado.value
        if (clave.startsWith("a:")) clave.removePrefix("a:").toIntOrNull()?.let(host::deleteAppWidgetId)
        guardar(ctx, e.copy(orden = e.orden - clave, ocultos = if (clave.startsWith("c:")) e.ocultos + clave else e.ocultos))
    }

    fun anadir(ctx: Context, clave: String) {
        val e = _estado.value
        guardar(ctx, e.copy(orden = e.orden.filterNot { it == clave } + clave, ocultos = e.ocultos - clave))
    }

    fun fijarMuelle(ctx: Context, paquetes: List<String>) = guardar(ctx, _estado.value.copy(muelle = paquetes))

    /** Tamaño en la rejilla de un widget de app de Android. */
    fun fijarTamano(ctx: Context, clave: String, tamano: String) = guardar(ctx, _estado.value.copy(tamanos = _estado.value.tamanos + (clave to tamano)))
}

private val FormaWidget = RoundedCornerShape(20.dp)

/** Widget de una app de Android, enmarcado como los de Claude para que el inicio sea coherente. */
@Composable
fun WidgetDeApp(id: Int, host: AppWidgetHost, onLargo: (() -> Unit)?, modifier: Modifier = Modifier, relleno: Boolean = false) {
    val ctx = LocalContext.current
    val info = remember(id) { AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) } ?: return
    val alto = with(LocalDensity.current) { info.minHeight.toDp() }.coerceIn(96.dp, 320.dp)
    Box(
        modifier
            .fillMaxWidth()
            .then(if (relleno) Modifier.fillMaxHeight() else Modifier)
            .clip(FormaWidget)
            .background(Colores.Superficie)
            .border(1.dp, Colores.Borde, FormaWidget),
    ) {
        // Sin margen: el widget llena la tarjeta y su propio fondo encaja con el borde redondeado.
        AndroidView(
            factory = { c -> host.createView(c.applicationContext, id, info).apply { if (onLargo != null) setOnLongClickListener { onLargo(); true } else isLongClickable = false } },
            modifier = if (relleno) Modifier.fillMaxSize() else Modifier.fillMaxWidth().height(alto),
        )
    }
}

/** Tamaño por defecto de un widget de app según lo que pide su proveedor (alto mínimo y ancho). */
fun tamanoDeApp(ctx: android.content.Context, id: Int): String {
    val info = AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) ?: return "mediano"
    val d = ctx.resources.displayMetrics.density
    return when {
        info.minHeight / d > 150 -> "grande"
        info.minWidth / d < 170 && info.minHeight / d <= 150 -> "pequeno"
        else -> "mediano"
    }
}

/**
 * Los widgets del inicio en la misma rejilla que el panel, repartidos en páginas que caben enteras
 * (se pasan deslizando en horizontal; puntos debajo). Mantener pulsado y arrastrar los ordena;
 * llevarlos al borde los pasa de página; sin moverlos, se abre su menú. En edición, «−» los quita.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ZonaWidgets(
    claves: List<String>,
    creaciones: List<Creacion>,
    host: AppWidgetHost,
    edicion: Boolean,
    onToqueCreacion: (Creacion) -> Unit,
    onLargo: () -> Unit,
    onOrdenar: (List<String>) -> Unit,
    onQuitar: (String) -> Unit,
    onMenuApp: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val porClave = remember(creaciones) { creaciones.associateBy { "c:${it.id}" } }
    val escritorio by Escritorio.estado.collectAsState()
    val tamanoDe = { k: String ->
        porClave[k]?.tamano ?: escritorio.tamanos[k] ?: k.removePrefix("a:").toIntOrNull()?.let { tamanoDeApp(ctx, it) } ?: "mediano"
    }
    val hijosDe = { c: Creacion -> c.pila.mapNotNull { porClave["c:$it"] } }
    val alcance = rememberCoroutineScope()
    BoxWithConstraints(modifier.padding(horizontal = 16.dp)) {
        val celda = (maxWidth - HUECO) / 2
        val filas = ((maxHeight - 28.dp + HUECO) / (celda + HUECO)).toInt().coerceAtLeast(1) // 28: los puntos
        val paginas = remember(claves, escritorio.tamanos, creaciones, filas) { paginar(claves, tamanoDe, filas).filter { it.isNotEmpty() }.ifEmpty { listOf(emptyList()) } }
        val estado = rememberPagerState { paginas.size }
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(estado, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 16.dp, key = { it }) { p ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    val pagina = paginas.getOrElse(p) { emptyList() }
                    RejillaWidgets(
                        claves = pagina,
                        tamanoDe = tamanoDe,
                        onMenu = { k -> porClave[k]?.let(onToqueCreacion) ?: onMenuApp(k) },
                        onOrden = { nuevo -> onOrdenar(paginas.flatMapIndexed { i, pg -> if (i == p) nuevo else pg }) },
                        onBorde = { k, lado ->
                            // Al borde: pasa a la página de al lado (al principio de la siguiente o al final de la anterior).
                            val destino = p + lado
                            if (destino >= 0) {
                                val sin = paginas.map { pg -> pg - k }
                                val antes = sin.take(destino.coerceAtMost(sin.size)).flatten()
                                val nuevo = if (lado > 0) antes + k + sin.drop(destino).flatten()
                                else sin.take(destino + 1).flatten() + k + sin.drop(destino + 1).flatten()
                                onOrdenar(nuevo)
                                alcance.launch { estado.animateScrollToPage(destino.coerceAtMost(paginas.size), animationSpec = Muelle.suave()) }
                            }
                        },
                    ) { k, m ->
                        Box(Modifier.fillMaxSize()) {
                            val c = porClave[k]
                            when {
                                c != null -> WidgetCreacion(c, onToque = { onToqueCreacion(c) }, onLargo = {}, tamano = tamanoDe(k), conLargo = false, hijosDe = hijosDe, modifier = m.fillMaxSize())
                                k.startsWith("a:") -> k.removePrefix("a:").toIntOrNull()?.let { WidgetDeApp(it, host, onLargo = null, modifier = m.fillMaxSize(), relleno = true) }
                            }
                            // En edición, «−» lo quita del inicio.
                            if (edicion) Box(
                                Modifier
                                    .align(Alignment.TopStart)
                                    .offset((-6).dp, (-6).dp)
                                    .size(28.dp)
                                    .clip(Formas.Pastilla)
                                    .background(Colores.Superficie2)
                                    .border(1.dp, Colores.BordeFuerte, Formas.Pastilla)
                                    .pulsable { onQuitar(k) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Iconos.Menos, stringResource(R.string.escritorio_quitar), tint = Colores.Texto, modifier = Modifier.size(14.dp)) }
                        }
                    }
                }
            }
            // Puntos de las páginas (solo si hay más de una).
            Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                if (paginas.size > 1) paginas.indices.forEach { i ->
                    val activo = i == estado.currentPage
                    val ancho by animateFloatAsState(if (activo) 16f else 6f, Muelle.vivo(), label = "punto")
                    Box(Modifier.padding(horizontal = 3.dp).width(ancho.dp).height(6.dp).clip(Formas.Pastilla).background(if (activo) Colores.Texto2 else Colores.Texto3.copy(alpha = 0.45f)))
                }
            }
        }
    }
}

/** Muelle de apps; en edición se reordenan arrastrando de lado y se cambian tocándolas. */
@Composable
fun Muelle(
    apps: List<App>,
    edicion: Boolean,
    onAbrir: (App) -> Unit,
    onCambiar: (Int) -> Unit,
    onOrdenar: (List<App>) -> Unit,
    onLargo: () -> Unit,
) {
    val orden = remember { mutableStateListOf<App>() }
    var arrastrando by remember { mutableStateOf<String?>(null) }
    var dx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(apps) { if (arrastrando == null) { orden.clear(); orden.addAll(apps) } }
    var ancho by remember { mutableFloatStateOf(1f) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .onGloballyPositioned { ancho = it.size.width.toFloat() },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        orden.forEachIndexed { i, app ->
            val esta = app.paquete == arrastrando
            Box(
                Modifier
                    .zIndex(if (esta) 1f else 0f)
                    .offset { IntOffset(if (esta) dx.roundToInt() else 0, 0) }
                    .then(
                        if (edicion) Modifier.pointerInput(app.paquete) {
                            detectDragGestures(
                                onDragStart = { arrastrando = app.paquete; dx = 0f },
                                onDragEnd = { arrastrando = null; dx = 0f; onOrdenar(orden.toList()) },
                                onDragCancel = { arrastrando = null; dx = 0f },
                            ) { cambio, d ->
                                cambio.consume()
                                dx += d.x
                                val hueco = ancho / orden.size
                                val j = orden.indexOfFirst { it.paquete == app.paquete }
                                if (dx > hueco / 2 && j < orden.lastIndex) { orden.add(j + 1, orden.removeAt(j)); dx -= hueco }
                                if (dx < -hueco / 2 && j > 0) { orden.add(j - 1, orden.removeAt(j)); dx += hueco }
                            }
                        } else Modifier,
                    ),
            ) {
                IconoApp(app, tam = 60, conNombre = false, onClick = { if (edicion) onCambiar(i) else onAbrir(app) }, onLargo = onLargo)
                if (edicion) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .size(22.dp)
                            .clip(Formas.Pastilla)
                            .background(Colores.Acento),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Iconos.Lapiz, null, tint = Colores.SobreAcento, modifier = Modifier.size(12.dp)) }
                }
            }
        }
    }
}

/** Barra del modo edición: añadir widget, ajustes y hecho. */
@Composable
fun BarraEdicion(onAnadir: () -> Unit, onAjustes: () -> Unit, onHecho: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BotonPrimario(stringResource(R.string.escritorio_anadir_widget), Modifier.weight(1.4f), onAnadir)
        com.sios.ui.BotonSecundario(stringResource(R.string.escritorio_ajustes), Modifier.weight(1f), onAjustes)
        com.sios.ui.BotonSecundario(stringResource(R.string.escritorio_hecho), Modifier.weight(1f), onHecho)
    }
}

/**
 * Hoja «Añadir widget»: los de Claude que no están en el inicio, crear uno nuevo con una frase y
 * los widgets de las apps instaladas.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HojaAnadirWidget(
    ocultos: List<Creacion>,
    onCreacion: (Creacion) -> Unit,
    onCrear: (String) -> Unit,
    onApp: (AppWidgetProviderInfo) -> Unit,
    onCerrar: () -> Unit,
) {
    val ctx = LocalContext.current
    var peticion by rememberSaveable { mutableStateOf("") }
    val proveedores = remember {
        AppWidgetManager.getInstance(ctx).installedProviders
            .sortedWith(compareBy({ it.loadLabel(ctx.packageManager)?.lowercase() }))
    }
    ModalBottomSheet(
        onDismissRequest = onCerrar,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Colores.Lateral,
        contentColor = Colores.Texto,
    ) {
        LazyColumn(
            Modifier.navigationBarsPadding().imePadding().heightIn(max = 640.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text(stringResource(R.string.escritorio_de_ia), style = Texto.Etiqueta, color = Colores.Texto3) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    BasicTextField(
                        value = peticion,
                        onValueChange = { peticion = it },
                        textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
                        cursorBrush = SolidColor(Colores.Acento),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(Formas.Pequena)
                            .background(Colores.Superficie)
                            .border(1.dp, Colores.Borde, Formas.Pequena)
                            .padding(14.dp),
                        decorationBox = { campo ->
                            Box {
                                if (peticion.isEmpty()) Text(stringResource(R.string.escritorio_crear_pista), style = Texto.Cuerpo, color = Colores.Texto3)
                                campo()
                            }
                        },
                    )
                    BotonPrimario(stringResource(R.string.escritorio_crear), Modifier.fillMaxWidth()) {
                        if (peticion.isNotBlank()) { onCrear(peticion.trim()); onCerrar() }
                    }
                }
            }
            items(ocultos, key = { "c:" + it.id }) { c ->
                FilaHoja(Iconos.Asistente, c.nombre, stringResource(R.string.creacion_tipo_widget)) { onCreacion(c); onCerrar() }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.escritorio_de_apps), style = Texto.Etiqueta, color = Colores.Texto3)
            }
            items(proveedores, key = { it.provider.flattenToString() }) { p ->
                val app = remember(p) { runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(p.provider.packageName, 0)).toString() }.getOrDefault("") }
                FilaHoja(Iconos.Widget, p.loadLabel(ctx.packageManager) ?: app, app) { onApp(p); onCerrar() }
            }
        }
    }
}

@Composable
private fun FilaHoja(icono: androidx.compose.ui.graphics.vector.ImageVector, titulo: String, nota: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Formas.Pequena)
            .background(Colores.Superficie)
            .pulsable(onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = Colores.Acento, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (nota.isNotBlank() && nota != titulo) Text(nota, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1)
        }
        Icon(Iconos.Mas, null, tint = Colores.Texto3, modifier = Modifier.size(18.dp))
    }
}

/** Hoja para elegir la app de un hueco del muelle. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HojaElegirApp(apps: List<App>, onElegir: (App) -> Unit, onCerrar: () -> Unit) {
    var busqueda by rememberSaveable { mutableStateOf("") }
    val filtradas = remember(apps, busqueda) { apps.filter { it.nombre.contains(busqueda.trim(), ignoreCase = true) } }
    ModalBottomSheet(
        onDismissRequest = onCerrar,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Colores.Lateral,
        contentColor = Colores.Texto,
    ) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp)) {
            BasicTextField(
                value = busqueda,
                onValueChange = { busqueda = it },
                singleLine = true,
                textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
                cursorBrush = SolidColor(Colores.Acento),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(Formas.Pequena)
                    .background(Colores.Superficie)
                    .border(1.dp, Colores.Borde, Formas.Pequena)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                decorationBox = { campo ->
                    Box {
                        if (busqueda.isEmpty()) Text(stringResource(R.string.inicio_buscar_apps), style = Texto.Cuerpo, color = Colores.Texto3)
                        campo()
                    }
                },
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filtradas, key = { it.componente.flattenToString() }) { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(Formas.Pequena)
                            .combinedClickable(remember { MutableInteractionSource() }, null, onClick = { onElegir(app); onCerrar() })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconoApp(app, tam = 40, conNombre = false, onClick = { onElegir(app); onCerrar() })
                        Spacer(Modifier.width(12.dp))
                        Text(app.nombre, style = Texto.Cuerpo, color = Colores.Texto)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Menú de un widget de app de Android en el inicio: su tamaño en la rejilla y quitarlo. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HojaWidgetApp(clave: String, onQuitar: () -> Unit, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val escritorio by Escritorio.estado.collectAsState()
    val id = clave.removePrefix("a:").toIntOrNull() ?: return
    val info = remember(id) { AppWidgetManager.getInstance(ctx).getAppWidgetInfo(id) }
    val actual = escritorio.tamanos[clave] ?: tamanoDeApp(ctx, id)
    androidx.compose.material3.ModalBottomSheet(onCerrar, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Colores.Lateral, contentColor = Colores.Texto) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(info?.loadLabel(ctx.packageManager) ?: "Widget", style = Texto.Subtitulo, color = Colores.Texto)
            Text(stringResource(R.string.creacion_tamano), style = Texto.Etiqueta, color = Colores.Texto3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("pequeno" to R.string.tamano_pequeno, "mediano" to R.string.tamano_mediano, "grande" to R.string.tamano_grande).forEach { (t, nombre) ->
                    val elegido = actual == t
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(Formas.Pequena)
                            .background(if (elegido) Colores.Acento else Colores.Superficie)
                            .border(1.dp, if (elegido) Colores.Acento else Colores.Borde, Formas.Pequena)
                            .pulsable { Escritorio.fijarTamano(ctx, clave, t) },
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(nombre), style = Texto.Pequeno, color = if (elegido) Colores.SobreAcento else Colores.Texto2) }
                }
            }
            Text(
                stringResource(R.string.escritorio_quitar), style = Texto.Subtitulo.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = Colores.Rojo,
                modifier = Modifier.clip(Formas.Pequena).pulsable { onQuitar(); onCerrar() }.padding(vertical = 10.dp),
            )
        }
    }
}
