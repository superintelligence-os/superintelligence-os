package com.sios.inicio

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color as ColorAndroid
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.ajustes.AjustesActivity
import com.sios.chat.ChatActivity
import com.sios.chat.EXTRA_DICTAR
import com.sios.chat.EXTRA_NUEVA
import com.sios.datos.Servicio
import com.sios.sistema.Bienvenida
import com.sios.sistema.SistemaSios
import com.sios.ui.BarraAsistente
import com.sios.ui.Pack
import com.sios.creaciones.MiniAppActivity
import com.sios.datos.Creacion
import com.sios.ui.HojaCreacion
import com.sios.ui.IconoCreacion
import com.sios.ui.WidgetCreacion
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.key
import androidx.compose.animation.animateContentSize
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.view.HapticFeedbackConstants
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import com.sios.sistema.Manos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.runtime.saveable.rememberSaveable
import java.text.SimpleDateFormat
import java.util.Date

// Cajón: a partir de este progreso se abre al soltar (y vibra al cruzarlo); por encima de esta
// velocidad (px/s) manda la dirección del gesto.
private const val UMBRAL = 0.35f
private const val VELOCIDAD = 900f

class InicioActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private var volverAlInicio by mutableStateOf(0)
    private var abrirPanel by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.auto(ColorAndroid.TRANSPARENT, ColorAndroid.TRANSPARENT), SystemBarStyle.auto(ColorAndroid.TRANSPARENT, ColorAndroid.TRANSPARENT))
        Servicio.iniciar()
        if (intent?.getBooleanExtra(EXTRA_PANEL, false) == true) abrirPanel = 1
        // En SIOS, la primera vez se muestra la configuración inicial.
        var bienvenida by mutableStateOf(SistemaSios.esSios() && !SistemaSios.terminado(this))
        setContent {
            TemaSios {
                if (bienvenida) Bienvenida { bienvenida = false } else PantallaInicio(volverAlInicio, abrirPanel)
            }
        }
    }

    // La hoja del asistente lo mira para no volver delante cuando el usuario se ha ido al inicio.
    override fun onResume() {
        super.onResume()
        enPrimerPlano = true
    }

    override fun onPause() {
        super.onPause()
        enPrimerPlano = false
    }

    companion object {
        @Volatile var enPrimerPlano = false
        /** Abrir el inicio con el panel abierto (al tocar una actividad en directo). */
        const val EXTRA_PANEL = "com.sios.inicio.PANEL"
    }

    // Pulsar Inicio estando ya en el inicio cierra el cajón de apps.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PANEL, false)) abrirPanel++ else volverAlInicio++
    }
}

@Composable
private fun PantallaInicio(volverAlInicio: Int, abrirPanel: Int = 0) {
    val ctx = LocalContext.current
    // Cajón de apps: progreso continuo (0 cerrado, 1 abierto) que sigue al dedo y se asienta con un
    // muelle según la velocidad al soltar. Está siempre montado: abrirlo solo mueve una capa.
    val cajonP = remember { Animatable(0f) }
    val alcance = rememberCoroutineScope()
    val vista = LocalView.current
    val alto = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val cajon = cajonP.targetValue > 0.5f
    fun moverCajon(abierto: Boolean, velocidad: Float = 0f) {
        alcance.launch { cajonP.animateTo(if (abierto) 1f else 0f, Muelle.cajon(), initialVelocity = velocidad) }
    }
    // Arrastre: recorrer el 60 % de la pantalla lo abre entero. Al cruzar el umbral, un toque háptico.
    // Dónde va el dedo, al instante: los snapTo se aplican en el siguiente fotograma y, con el
    // móvil ocupado, al soltar aún no habrían llegado (el gesto se perdería). -1: sin arrastre.
    val dedo = remember { floatArrayOf(-1f, -1f) } // [cajón, panel]
    fun arrastrarCajon(d: Float) {
        val antes = if (dedo[0] >= 0f) dedo[0] else cajonP.value
        val ahora = (antes - d / (alto * 0.6f)).coerceIn(0f, 1f)
        if ((antes < UMBRAL) != (ahora < UMBRAL)) vista.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        dedo[0] = ahora
        alcance.launch { cajonP.snapTo(ahora) }
    }
    // Panel (deslizar a la derecha): el mismo gesto, en horizontal. Recorrer el 70 % del ancho lo abre.
    // Abierto o no se guarda: si Android recrea el inicio (un cambio de configuración), sigue abierto.
    var panelGuardado by rememberSaveable { mutableStateOf(false) }
    val panelP = remember { Animatable(if (panelGuardado) 1f else 0f) }
    val ancho = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val panelAbierto = panelP.targetValue > 0.5f
    LaunchedEffect(panelAbierto) { panelGuardado = panelAbierto }
    val panel by Servicio.panel.collectAsStateWithLifecycle()
    fun moverPanel(abierto: Boolean, velocidad: Float = 0f) {
        alcance.launch { panelP.animateTo(if (abierto) 1f else 0f, Muelle.cajon(), initialVelocity = velocidad) }
    }
    fun arrastrarPanel(dx: Float) {
        val antes = if (dedo[1] >= 0f) dedo[1] else panelP.value
        val ahora = (antes + dx / (ancho * 0.7f)).coerceIn(0f, 1f)
        if ((antes < UMBRAL) != (ahora < UMBRAL)) vista.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        dedo[1] = ahora
        alcance.launch { panelP.snapTo(ahora) }
    }
    fun soltarPanel(vx: Float) {
        val p = if (dedo[1] >= 0f) dedo[1] else panelP.value
        dedo[1] = -1f
        val abrir = when {
            vx > VELOCIDAD -> true
            vx < -VELOCIDAD -> false
            else -> p > UMBRAL
        }
        moverPanel(abrir, vx / (ancho * 0.7f))
    }
    val enlacePanel = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.x == 0f || !(panelP.value > 0f || dedo[1] > 0f)) return Offset.Zero
                arrastrarPanel(available.x)
                return Offset(available.x, 0f)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.x <= 0f) return Offset.Zero
                arrastrarPanel(available.x)
                return Offset(available.x, 0f)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!(panelP.value > 0f || dedo[1] > 0f)) return Velocity.Zero
                soltarPanel(available.x)
                return Velocity(available.x, 0f)
            }
        }
    }
    fun soltarCajon(vy: Float) {
        val p = if (dedo[0] >= 0f) dedo[0] else cajonP.value
        dedo[0] = -1f
        val abrir = when {
            vy < -VELOCIDAD -> true
            vy > VELOCIDAD -> false
            else -> p > UMBRAL
        }
        moverCajon(abrir, -vy / (alto * 0.6f))
    }
    var edicion by remember { mutableStateOf(false) } // pulsación larga: mover, quitar y añadir
    var version by remember { mutableStateOf(0) }
    val estado by Servicio.estado.collectAsStateWithLifecycle()
    val creaciones by Servicio.creaciones.collectAsStateWithLifecycle()
    val miniApps = remember(creaciones) { creaciones.filter { it.tipo == "app" } }
    var hoja by remember { mutableStateOf<Creacion?>(null) }

    val apps by produceState(emptyList<App>(), version) { value = withContext(Dispatchers.Default) { cargarApps(ctx) } }
    val escritorio by Escritorio.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Escritorio.cargar(ctx) }
    // Muelle: el que eligió el usuario (si sus apps siguen instaladas) o el de serie.
    val muelle = remember(apps, escritorio.muelle) {
        val elegidas = escritorio.muelle.mapNotNull { buscarApp(apps, it) }
        if (elegidas.size == 4) elegidas else appsMuelle(ctx, apps)
    }

    // Widgets de apps de Android: el inicio es su anfitrión.
    val host = remember { AppWidgetHost(ctx.applicationContext, 0x434c) }
    DisposableEffect(host) {
        host.startListening()
        onDispose { host.stopListening() }
    }
    val idsApp = remember(escritorio) { host.appWidgetIds.toSet() }
    val widgets = remember(escritorio, creaciones, idsApp) { Escritorio.visibles(escritorio, creaciones, idsApp) }
    var hojaAnadir by remember { mutableStateOf(false) }
    var menuApp by remember { mutableStateOf<String?>(null) } // widget de app con el menú abierto
    var buscando by remember { mutableStateOf(false) } // buscador universal (deslizar hacia abajo)
    var huecoMuelle by remember { mutableStateOf<Int?>(null) }
    var pendiente by remember { mutableStateOf<Pair<Int, AppWidgetProviderInfo>?>(null) }
    val configurar = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val (id, _) = pendiente ?: return@rememberLauncherForActivityResult
        pendiente = null
        if (r.resultCode == Activity.RESULT_OK) Escritorio.anadir(ctx, "a:$id") else host.deleteAppWidgetId(id)
    }
    fun colocar(id: Int, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            pendiente = id to info
            configurar.launch(Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).setComponent(info.configure).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        } else Escritorio.anadir(ctx, "a:$id")
    }
    val permiso = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val (id, info) = pendiente ?: return@rememberLauncherForActivityResult
        pendiente = null
        if (r.resultCode == Activity.RESULT_OK) colocar(id, info) else host.deleteAppWidgetId(id)
    }
    fun anadirDeApp(info: AppWidgetProviderInfo) {
        val id = host.allocateAppWidgetId()
        // En SIOS (BIND_APPWIDGET de serie) se coloca directamente; si no, Android pide permiso.
        if (AppWidgetManager.getInstance(ctx).bindAppWidgetIdIfAllowed(id, info.provider)) colocar(id, info)
        else {
            pendiente = id to info
            permiso.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider),
            )
        }
    }
    val peticionCrear = stringResource(R.string.escritorio_peticion_crear, "%s")

    // Apps instaladas o desinstaladas: se recarga la lista.
    DisposableEffect(Unit) {
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { version++ }
        }
        val filtro = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ctx.registerReceiver(receptor, filtro, Context.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(receptor) }
    }

    // El uso se lee con `/usage` (no gasta suscripción) solo al volver al inicio;
    // el servicio no lo repite si la última lectura tiene menos de un minuto.
    var visitas by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { alcance.launch { cajonP.snapTo(0f) }; visitas++ }
    LaunchedEffect(visitas) { Servicio.uso(refrescar = true) }
    LaunchedEffect(volverAlInicio) { if (volverAlInicio > 0) { moverCajon(false); moverPanel(false); buscando = false }; edicion = false }
    BackHandler(buscando) { buscando = false }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { buscando = false }
    LaunchedEffect(abrirPanel) { if (abrirPanel > 0) { moverCajon(false); moverPanel(true) } }
    BackHandler(edicion) { edicion = false }
    // Atrás predictivo: el cajón se encoge con el gesto y se cierra al soltar (o vuelve si se cancela).
    PredictiveBackHandler(enabled = panelAbierto && !cajon && !edicion) { gesto ->
        try {
            gesto.collect { e -> panelP.snapTo(1f - e.progress * 0.3f) }
            moverPanel(false)
        } catch (e: CancellationException) {
            moverPanel(true)
            throw e
        }
    }
    PredictiveBackHandler(enabled = cajon && !edicion) { gesto ->
        try {
            gesto.collect { e -> cajonP.snapTo(1f - e.progress * 0.3f) }
            moverCajon(false)
        } catch (e: CancellationException) {
            moverCajon(true)
            throw e
        }
    }

    fun abrirChat(configurar: Intent.() -> Unit = {}) =
        ctx.startActivity(Intent(ctx, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply(configurar))

    val escalaEdicion by animateFloatAsState(if (edicion) 0.97f else 1f, Muelle.suave(), label = "fondo")
    var bajada by remember { mutableStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Colores.Fondo)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                // El inicio se aleja un poco mientras sube el cajón (se lee en la capa: sin recomponer).
                .graphicsLayer {
                    val p = cajonP.value
                    val q = panelP.value
                    val e = escalaEdicion * (1f - 0.06f * p) * (1f - 0.04f * q)
                    scaleX = e; scaleY = e
                    translationY = -p * size.height * 0.04f
                    // Con el panel, el inicio se aparta un poco hacia la derecha (efecto de profundidad).
                    translationX = q * size.width * 0.22f
                    alpha = (1f - 1.8f * p).coerceAtLeast(0f) * (1f - 0.85f * q)
                }
                .statusBarsPadding()
                .navigationBarsPadding()
                // Hacia arriba: el cajón sigue al dedo. Hacia abajo: el panel de notificaciones.
                // Las páginas de widgets se desplazan en horizontal: en la primera, lo que sobra hacia la
                // derecha abre el panel (y, si está a medio abrir, se mueve el panel antes que la página).
                .nestedScroll(enlacePanel)
                // Hacia la derecha: el panel de Claude, siguiendo al dedo.
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { d -> if (d > 0f || panelP.value > 0f || dedo[1] > 0f) arrastrarPanel(d) },
                    enabled = !edicion && cajonP.value == 0f,
                    onDragStopped = { vx -> if (panelP.value > 0f || dedo[1] > 0f) soltarPanel(vx) else dedo[1] = -1f },
                )
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { d -> if (d < 0f || cajonP.value > 0f || dedo[0] > 0f) arrastrarCajon(d) else bajada += d },
                    enabled = !edicion && panelP.value == 0f,
                    onDragStarted = { bajada = 0f },
                    onDragStopped = { vy ->
                        if (cajonP.value > 0f || dedo[0] > 0f) soltarCajon(vy)
                        // Hacia abajo: el buscador universal (las notificaciones, desde la barra de estado).
                        else if (bajada > 60f || vy > VELOCIDAD) buscando = true
                    },
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { vista.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); edicion = true },
                        onTap = { edicion = false },
                        // Doble toque en un hueco: bloquear (con las manos de Claude, como en los Pixel).
                        onDoubleTap = { if (!edicion && Manos.bloquear()) vista.performHapticFeedback(HapticFeedbackConstants.CONFIRM) },
                    )
                }
        ) {
            Cabecera(
                semana = estado.uso?.semana?.porcentaje,
                cincoHoras = estado.uso?.cincoHoras?.porcentaje,
                onUso = { ctx.startActivity(Intent(ctx, AjustesActivity::class.java).putExtra("seccion", "uso")) },
            )
            // Widgets (de Claude y de apps). En edición se arrastran, se quitan y se desplazan.
            ZonaWidgets(
                claves = widgets,
                creaciones = creaciones,
                host = host,
                edicion = edicion,
                onToqueCreacion = { hoja = it },
                onLargo = { edicion = true },
                onOrdenar = { Escritorio.ordenar(ctx, it) },
                onQuitar = { Escritorio.quitar(ctx, it, host) },
                onMenuApp = { menuApp = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Muelle(
                apps = muelle,
                edicion = edicion,
                onAbrir = { abrirApp(ctx, it) },
                onCambiar = { huecoMuelle = it },
                onOrdenar = { Escritorio.fijarMuelle(ctx, it.map { a -> a.componente.flattenToString() }) },
                onLargo = { edicion = true },
            )
            Spacer(Modifier.height(20.dp))
            AnimatedContent(
                targetState = edicion,
                transitionSpec = { (fadeIn(Muelle.suave()) + slideInVertically(Muelle.suave()) { it / 3 }) togetherWith fadeOut(Muelle.suave()) },
                label = "barra",
            ) { editando ->
                if (editando) {
                    BarraEdicion(
                        onAnadir = { hojaAnadir = true },
                        onAjustes = { ctx.startActivity(Intent(ctx, AjustesActivity::class.java)) },
                        onHecho = { edicion = false },
                    )
                } else {
                    BarraAsistente(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onLogo = { abrirChat() },
                        onTexto = { abrirChat { putExtra(EXTRA_NUEVA, true) } },
                        onMicro = { abrirChat { putExtra(EXTRA_NUEVA, true); putExtra(EXTRA_DICTAR, true) } },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Panel de Claude: entra desde la izquierda.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val q = panelP.value
                    translationX = if (q <= 0f) -size.width * 2f else -(1f - q) * size.width
                    alpha = (0.4f + q).coerceAtMost(1f)
                }
        ) {
            PanelInicio(
                abierto = panelAbierto,
                panel = panel,
                creaciones = creaciones,
                onArrastre = ::arrastrarPanel,
                onSoltar = ::soltarPanel,
                onTarjeta = { hoja = it },
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = cajonP.value
                    alpha = (p * 1.6f).coerceAtMost(1f)
                    // Cerrado, fuera de la pantalla: no se dibuja ni recibe toques.
                    translationY = if (p <= 0f) size.height * 2f else (1f - p) * size.height * 0.18f
                }
        ) {
            Cajon(
                abierto = cajon,
                apps = apps,
                miniApps = miniApps,
                onMiniApp = { abrirMiniApp(ctx, it) },
                onCambiar = { hoja = it },
                onAbrir = { abrirApp(ctx, it) },
                onInfo = { infoApp(ctx, it) },
                progreso = { cajonP.value },
                onArrastre = ::arrastrarCajon,
                onSoltar = ::soltarCajon,
            )
        }

        if (hojaAnadir) {
            val ocultos = creaciones.filter { it.tipo == "widget" && "c:${it.id}" !in widgets }
            HojaAnadirWidget(
                ocultos = ocultos,
                onCreacion = { Escritorio.anadir(ctx, "c:${it.id}") },
                onCrear = { texto ->
                    Servicio.preguntar(peticionCrear.replace("%s", texto))
                    edicion = false
                    abrirChat()
                },
                onApp = { anadirDeApp(it) },
                onCerrar = { hojaAnadir = false },
            )
        }
        huecoMuelle?.let { i ->
            HojaElegirApp(
                apps = apps,
                onElegir = { app -> Escritorio.fijarMuelle(ctx, muelle.map { a -> a.componente.flattenToString() }.toMutableList().also { it[i] = app.componente.flattenToString() }) },
                onCerrar = { huecoMuelle = null },
            )
        }

        // Buscador universal: baja desde arriba con un muelle.
        AnimatedVisibility(
            visible = buscando,
            enter = slideInVertically(Muelle.vivo()) { -it / 4 } + fadeIn(Muelle.suave()),
            exit = slideOutVertically(Muelle.suave()) { -it / 4 } + fadeOut(Muelle.suave()),
        ) { Buscador(buscando, apps, onCerrar = { buscando = false }) }

        hoja?.let { c -> HojaCreacion(creaciones.firstOrNull { it.id == c.id } ?: c, onCerrar = { hoja = null }) }
        menuApp?.let { k -> HojaWidgetApp(k, onQuitar = { Escritorio.quitar(ctx, k, host) }, onCerrar = { menuApp = null }) }
    }
}

private fun abrirMiniApp(ctx: Context, c: Creacion) = ctx.startActivity(
    Intent(ctx, MiniAppActivity::class.java)
        .putExtra(MiniAppActivity.EXTRA_ID, c.id)
        .putExtra(MiniAppActivity.EXTRA_NOMBRE, c.nombre)
        .setData(android.net.Uri.parse("sios://app/${c.id}")) // una tarea por mini-app en recientes
        .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT),
)

@Composable
private fun Cabecera(semana: Int?, cincoHoras: Int?, onUso: () -> Unit) {
    val ahora by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000L - value % 1000L)
        }
    }
    // Fecha en el idioma del móvil: en español sale «EEEE, d 'de' MMMM», como antes.
    val idioma = LocalLocale.current.platformLocale
    val formatoFecha = remember(idioma) { DateFormat.getBestDateTimePattern(idioma, "EEEEdMMMM") }
    Column(Modifier.padding(horizontal = 28.dp, vertical = 36.dp)) {
        Text(SimpleDateFormat("HH:mm", idioma).format(Date(ahora)), style = Texto.Gigante.copy(fontSize = 76.sp), color = Colores.Texto)
        Text(
            SimpleDateFormat(formatoFecha, idioma).format(Date(ahora)).replaceFirstChar { it.uppercase() },
            style = Texto.Cuerpo,
            color = Colores.Texto2,
        )
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier
                .clip(Formas.Pastilla)
                .border(1.dp, Colores.Borde, Formas.Pastilla)
                .pulsable(onUso)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            // El uso del plan es cosa de Claude: con otra IA, la pastilla solo dice cuál está en uso.
            val partes = if (com.sios.ui.Marca.id != "claude") emptyList() else listOfNotNull(
                cincoHoras?.let { stringResource(R.string.uso_cinco_horas_corto, it) },
                semana?.let { stringResource(R.string.inicio_uso_semana, it) },
            )
            Text(
                if (partes.isEmpty()) com.sios.ui.Marca.nombre else partes.joinToString(" · "),
                style = Texto.Pequeno,
                color = Colores.Texto2,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IconoApp(app: App, tam: Int, conNombre: Boolean, onClick: () -> Unit, onLargo: () -> Unit = {}) {
    val vista = LocalView.current
    val toque = remember { MutableInteractionSource() }
    // Al pulsar, la baldosa se hunde un poco y vuelve con muelle; la app se abre creciendo desde ella.
    val pulsado by toque.collectIsPressedAsState()
    val hundido by animateFloatAsState(if (pulsado) 0.9f else 1f, Muelle.vivo(), label = "pulsado")
    var caja by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Column(
        Modifier
            .clip(Formas.Pequena)
            .combinedClickable(
                toque, null,
                onClick = {
                    caja?.let { c -> Origen.marcar(vista, android.graphics.Rect(c.left.toInt(), c.top.toInt(), c.right.toInt(), c.bottom.toInt())) }
                    onClick()
                },
                onLongClick = { vista.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onLargo() },
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Pack de iconos: baldosa gris redondeada; glifo claro (solo Claude, en naranja) si la app está en
        // el pack; si no, su capa monocroma teñida o, en último caso, su icono original recortado.
        val glifo = remember(app.paquete) { Pack.glifo(app.paquete) }
        Box(
            Modifier
                .size(tam.dp)
                .onGloballyPositioned { caja = it.boundsInWindow() }
                .graphicsLayer { scaleX = hundido; scaleY = hundido }
                .clip(Pack.Forma)
                .background(Colores.Superficie)
                .border(1.dp, Colores.Borde, Pack.Forma),
            contentAlignment = Alignment.Center,
        ) {
            when {
                app.paquete == "com.sios" && app.componente.className.endsWith("TelefonoActivity") ->
                    Icon(Iconos.Llamar, app.nombre, tint = Colores.Texto, modifier = Modifier.size((tam * 0.46f).dp))
                app.paquete == "com.sios" && app.componente.className.contains("Camara") ->
                    Icon(Iconos.Camara, app.nombre, tint = Colores.Texto, modifier = Modifier.size((tam * 0.5f).dp))
                app.paquete == "com.sios" ->
                    Icon(Iconos.Asistente, app.nombre, tint = Colores.Acento, modifier = Modifier.size((tam * 0.52f).dp))
                glifo != null ->
                    Icon(Pack.vector(glifo), app.nombre, tint = Colores.Texto, modifier = Modifier.size((tam * if (glifo.relleno) 0.46f else 0.5f).dp))
                app.monocromo ->
                    Image(app.icono, app.nombre, colorFilter = ColorFilter.tint(Colores.Texto), modifier = Modifier.size((tam * 0.76f).dp))
                else ->
                    Image(app.icono, app.nombre, contentScale = ContentScale.Crop, modifier = Modifier.size(tam.dp))
            }
        }
        if (conNombre) {
            Spacer(Modifier.height(6.dp))
            Text(
                app.nombre,
                style = Texto.Pequeno.copy(fontSize = 12.sp),
                color = Colores.Texto2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                // El nombre ocupa todo el ancho de su casilla (4 por fila): «Calculadora» cabe entera.
                modifier = Modifier.width((tam + 34).dp),
            )
        }
    }
}

@Composable
private fun Cajon(
    abierto: Boolean,
    apps: List<App>,
    miniApps: List<Creacion>,
    onMiniApp: (Creacion) -> Unit,
    onCambiar: (Creacion) -> Unit,
    onAbrir: (App) -> Unit,
    onInfo: (App) -> Unit,
    progreso: () -> Float,
    onArrastre: (Float) -> Unit,
    onSoltar: (Float) -> Unit,
) {
    var busqueda by remember { mutableStateOf("") }
    val foco = remember { FocusRequester() }
    val filtradas = remember(apps, busqueda) {
        val b = busqueda.trim().lowercase()
        if (b.isEmpty()) apps else apps.filter { it.nombre.lowercase().contains(b) }
    }
    val miniFiltradas = remember(miniApps, busqueda) {
        val b = busqueda.trim().lowercase()
        if (b.isEmpty()) miniApps else miniApps.filter { it.nombre.lowercase().contains(b) }
    }
    // Al cerrarse: sin búsqueda, sin teclado y la lista arriba para la próxima vez.
    val focoGeneral = LocalFocusManager.current
    val rejilla = rememberLazyGridState()
    LaunchedEffect(abierto) {
        if (!abierto) {
            focoGeneral.clearFocus()
            busqueda = ""
            rejilla.scrollToItem(0)
        }
    }
    // Bajar con la lista ya arriba cierra el cajón siguiendo al dedo; si se vuelve a subir a medio
    // camino, primero se abre del todo y luego desplaza la lista.
    val enlace = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f || progreso() >= 1f) return Offset.Zero
                onArrastre(available.y)
                return Offset(0f, available.y)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                onArrastre(available.y)
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (progreso() >= 1f) return Velocity.Zero
                onSoltar(available.y)
                return available
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Colores.Fondo)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            // Fuera de la lista (buscador y márgenes) también se arrastra para cerrar.
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d -> onArrastre(d) },
                onDragStopped = { vy -> onSoltar(vy) },
            )
            .nestedScroll(enlace)
    ) {
        Row(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .height(52.dp)
                .clip(Formas.Pastilla)
                .background(Colores.Superficie)
                .border(1.dp, Colores.Borde, Formas.Pastilla)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Iconos.Buscar, null, tint = Colores.Texto3, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = busqueda,
                onValueChange = { busqueda = it },
                singleLine = true,
                textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
                cursorBrush = SolidColor(Colores.Acento),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { miniFiltradas.firstOrNull()?.let(onMiniApp) ?: filtradas.firstOrNull()?.let(onAbrir) }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(foco),
                decorationBox = { campo ->
                    Box {
                        if (busqueda.isEmpty()) Text(stringResource(R.string.inicio_buscar_apps), style = Texto.Cuerpo, color = Colores.Texto3)
                        campo()
                    }
                },
            )
        }

        AnimatedVisibility(busqueda.isNotBlank() && filtradas.isEmpty() && miniFiltradas.isEmpty()) {
            Text(
                stringResource(R.string.inicio_ninguna_app),
                style = Texto.Cuerpo,
                color = Colores.Texto3,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
            )
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = rejilla,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Primero las mini-apps que ha hecho Claude, con su glifo en el acento elegido.
            items(miniFiltradas, key = { "creacion:" + it.id }) { c ->
                Box(Modifier.animateItem(placementSpec = Muelle.suave()), contentAlignment = Alignment.Center) {
                    IconoCreacion(c, tam = 54, onClick = { onMiniApp(c) }, onLargo = { onCambiar(c) })
                }
            }
            items(filtradas, key = { it.componente.flattenToString() }) { app ->
                Box(Modifier.animateItem(placementSpec = Muelle.suave()), contentAlignment = Alignment.Center) {
                    IconoApp(app, tam = 54, conNombre = true, onClick = { onAbrir(app) }, onLargo = { onInfo(app) })
                }
            }
        }
    }
}

