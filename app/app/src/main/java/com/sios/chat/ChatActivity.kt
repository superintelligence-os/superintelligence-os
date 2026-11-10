package com.sios.chat

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.sios.ui.AjustesConversacion
import com.sios.ui.resumenAjustes
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.ajustes.AjustesActivity
import com.sios.datos.Elemento
import com.sios.datos.EstadoPaso
import com.sios.datos.EstadoPermiso
import com.sios.datos.Servicio
import com.sios.datos.Sesion
import com.sios.datos.Turno
import com.sios.ui.BotonIcono
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Markdown
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.launch
import java.util.Calendar

const val EXTRA_NUEVA = "nueva"
const val EXTRA_DICTAR = "dictar"
const val EXTRA_TEXTO = "texto"

class ChatActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private var peticion by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Servicio.iniciar()
        peticion = intent
        setContent { TemaSios { PantallaChat(peticion) { peticion = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        peticion = intent
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaChat(peticion: Intent?, peticionAtendida: () -> Unit) {
    var hojaAjustes by rememberSaveable { mutableStateOf(false) }
    val estado by Servicio.estado.collectAsStateWithLifecycle()
    val sesiones by Servicio.sesiones.collectAsStateWithLifecycle()
    val cajon = rememberDrawerState(DrawerValue.Closed)
    val alcance = rememberCoroutineScope()
    val contexto = LocalContext.current
    var texto by rememberSaveable { mutableStateOf("") }
    val foco = remember { FocusRequester() }
    val idioma = LocalLocale.current.platformLocale
    val avisoDictado = stringResource(R.string.comun_preguntale_a_ia)

    // Dictado dentro del chat (sin la ventana de Google): lo dicho se envía directamente.
    val escucha = remember { com.sios.sistema.Escucha(contexto) { enviarOLocal(contexto, it) } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { escucha.liberar() } }
    val permisoMicro = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { si -> if (si) escucha.empezar() }
    fun dictar() {
        if (!escucha.disponible) {
            // Sin ningún dictado: a Ajustes, a descargar el del propio móvil (funciona sin conexión).
            Toast.makeText(contexto, R.string.chat_sin_reconocimiento_voz, Toast.LENGTH_SHORT).show()
            com.sios.voz.Dictado.abrirAjustes(contexto)
            return
        }
        if (contexto.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) escucha.empezar()
        else permisoMicro.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    // Lo que pide quien abrió el chat: conversación nueva, dictar o texto compartido.
    LaunchedEffect(peticion) {
        val p = peticion ?: return@LaunchedEffect
        if (p.getBooleanExtra(EXTRA_NUEVA, false)) Servicio.nueva()
        p.getStringExtra(Intent.EXTRA_TEXT)?.let { texto = it }
        p.getStringExtra(EXTRA_TEXTO)?.let { texto = it }
        if (p.getBooleanExtra(EXTRA_DICTAR, false)) dictar()
        else if (p.getBooleanExtra(EXTRA_NUEVA, false) || texto.isNotEmpty()) runCatching { foco.requestFocus() }
        peticionAtendida()
    }

    ModalNavigationDrawer(
        drawerState = cajon,
        scrimColor = Colores.Fondo.copy(alpha = 0.6f),
        drawerContent = {
            CajonSesiones(
                sesiones = sesiones,
                actual = estado.conversacionId,
                onNueva = { Servicio.nueva(); alcance.launch { cajon.close() } },
                onAbrir = { Servicio.abrir(it); alcance.launch { cajon.close() } },
                onAjustes = { contexto.startActivity(Intent(contexto, AjustesActivity::class.java)) },
                semana = estado.uso?.semana?.porcentaje,
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Colores.Fondo)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            BarraSuperior(
                titulo = estado.titulo.ifBlank { stringResource(R.string.comun_nueva_conversacion) },
                detalle = if (estado.conectado) resumenAjustes(estado.ajustes, estado.modeloReal) else stringResource(R.string.chat_sin_conexion_servicio),
                libre = estado.ajustes.permisos == "libre",
                onMenu = { alcance.launch { cajon.open() } },
                onDetalle = { hojaAjustes = true },
                onNueva = { Servicio.nueva() },
            )
            Box(Modifier.weight(1f)) {
                if (estado.elementos.isEmpty()) Portada { enviarOLocal(contexto, it) }
                else ListaMensajes(estado.elementos, estado.ocupado)
            }
            CajaTexto(
                texto = texto,
                onTexto = { texto = it },
                ocupado = estado.ocupado,
                foco = foco,
                onEnviar = {
                    val t = texto.trim()
                    if (t.isNotEmpty()) {
                        enviarOLocal(contexto, t)
                        texto = ""
                    }
                },
                onParar = { Servicio.detener() },
                onDictar = { dictar() },
                escucha = escucha,
            )
            Text(
                estado.ultimoTurno?.let { textoTurno(it) } ?: " ",
                style = Texto.Pequeno,
                color = Colores.Texto3,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    // Modelo, esfuerzo y permisos sin salir de la conversación.
    if (hojaAjustes) {
        ModalBottomSheet(
            onDismissRequest = { hojaAjustes = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Colores.Fondo,
            scrimColor = Colores.Fondo.copy(alpha = 0.6f),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            dragHandle = {
                Box(
                    Modifier
                        .padding(top = 12.dp, bottom = 4.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(Formas.Pastilla)
                        .background(Colores.BordeFuerte)
                )
            },
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(stringResource(R.string.chat_ajustes_conversacion), style = Texto.Titulo, color = Colores.Texto)
                Spacer(Modifier.height(20.dp))
                AjustesConversacion(estado.ajustes, estado.ocupado, estado.modeloReal)
                Spacer(Modifier.height(20.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Formas.Tarjeta)
                        .background(Colores.Superficie)
                        .pulsable {
                            hojaAjustes = false
                            contexto.startActivity(Intent(contexto, AjustesActivity::class.java))
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.chat_mas_ajustes), style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.weight(1f))
                    Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** «Sonnet 5.5 · 5,6 k tokens · 4 s», con los decimales del idioma del móvil. */
@Composable
private fun textoTurno(t: Turno): String {
    val tokens = if (t.tokens >= 1000) String.format(LocalLocale.current.platformLocale, "%.1f k", t.tokens / 1000.0) else "${t.tokens}"
    return stringResource(R.string.chat_ultimo_turno, t.modelo, tokens, t.segundos)
}

@Composable
private fun BarraSuperior(
    titulo: String,
    detalle: String,
    libre: Boolean,
    onMenu: () -> Unit,
    onDetalle: () -> Unit,
    onNueva: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotonIcono(Iconos.Menu, stringResource(R.string.chat_conversaciones), onClick = onMenu)
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .pulsable(onDetalle),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(titulo, style = Texto.Subtitulo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            // Pastilla con modelo · esfuerzo · permisos: al tocarla se abre la hoja de ajustes.
            Row(
                Modifier
                    .clip(Formas.Pastilla)
                    .border(1.dp, if (libre) Colores.Rojo.copy(alpha = 0.6f) else Colores.Borde, Formas.Pastilla)
                    .padding(start = 10.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (libre) {
                    Box(Modifier.size(6.dp).clip(Formas.Pastilla).background(Colores.Rojo))
                    Spacer(Modifier.width(6.dp))
                }
                Text(detalle, style = Texto.Pequeno, color = Colores.Texto2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(2.dp))
                Icon(Iconos.Abajo, null, tint = Colores.Texto3, modifier = Modifier.size(14.dp))
            }
        }
        BotonIcono(Iconos.Nueva, stringResource(R.string.comun_nueva_conversacion), onClick = onNueva)
    }
}

private val SUGERENCIAS = listOf(
    R.string.chat_sugerencia_bateria,
    R.string.chat_sugerencia_sms,
    R.string.chat_sugerencia_alarma,
    R.string.chat_sugerencia_semana,
)

/** Las tres demos de la primera vez: qué pasa al tocarlas, y se hacen de verdad. */
private val DEMOS = listOf(
    Triple(R.string.demo_dia, R.string.demo_dia_nota, Iconos.Reloj),
    Triple(R.string.demo_widget, R.string.demo_widget_nota, Iconos.Widget),
    Triple(R.string.demo_mensajes, R.string.demo_mensajes_nota, Iconos.Enviar),
)

@Composable
private fun Portada(onSugerencia: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("sios", android.content.Context.MODE_PRIVATE) }
    var demos by remember { mutableStateOf(!prefs.getBoolean("demos_vistas", false)) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(if (demos) R.string.demo_titulo else R.string.chat_portada_titulo),
            style = Texto.Titulo.copy(fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp)), color = Colores.Texto,
        )
        Spacer(Modifier.height(24.dp))
        androidx.compose.animation.AnimatedContent(demos, label = "portada") { primeraVez ->
            if (primeraVez) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((frase, nota, icono) in DEMOS) {
                    val texto = stringResource(frase)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(Formas.Tarjeta)
                            .background(Colores.Superficie)
                            .border(1.dp, Colores.Borde, Formas.Tarjeta)
                            .pulsable {
                                prefs.edit().putBoolean("demos_vistas", true).apply()
                                demos = false
                                onSugerencia(texto)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).clip(Formas.Pastilla).background(Colores.AcentoSuave), contentAlignment = Alignment.Center) {
                            Icon(icono, null, tint = Colores.Acento, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("«$texto»", style = Texto.Cuerpo.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = Colores.Texto)
                            Text(stringResource(nota), style = Texto.Pequeno, color = Colores.Texto3)
                        }
                    }
                }
                Text(
                    stringResource(R.string.demo_saltar), style = Texto.Pequeno, color = Colores.Texto3,
                    modifier = Modifier.clip(Formas.Pequena).pulsable { prefs.edit().putBoolean("demos_vistas", true).apply(); demos = false }.padding(vertical = 8.dp),
                )
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (id in SUGERENCIAS) {
                    val s = stringResource(id)
                    Text(
                        s,
                        style = Texto.Cuerpo,
                        color = Colores.Texto2,
                        modifier = Modifier
                            .clip(Formas.Pastilla)
                            .border(1.dp, Colores.Borde, Formas.Pastilla)
                            .pulsable { onSugerencia(s) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ListaMensajes(elementos: List<Elemento>, ocupado: Boolean) {
    val lista = rememberLazyListState()
    val ultimo = elementos.lastOrNull()
    // «Pensando»: trabajando y sin nada moviéndose en pantalla (ni texto llegando, ni un paso en
    // curso, ni una confirmación esperando).
    val pensando = ocupado && when (ultimo) {
        is Elemento.Respuesta -> !ultimo.escribiendo || ultimo.texto.isBlank()
        is Elemento.Pasos -> ultimo.pasos.none { it.estado == com.sios.datos.EstadoPaso.EN_CURSO }
        is Elemento.Permiso -> false
        else -> true
    }
    // Sigue el final mientras llegan mensajes o el texto crece.
    LaunchedEffect(elementos.size, (ultimo as? Elemento.Respuesta)?.texto?.length, pensando) {
        if (elementos.isNotEmpty()) lista.animateScrollToItem(elementos.lastIndex + if (pensando) 1 else 0, Int.MAX_VALUE / 2)
    }
    LazyColumn(
        state = lista,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(elementos, key = { it.clave }) { e ->
            Box(Modifier.animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())) {
                when (e) {
                    is Elemento.Usuario -> BurbujaUsuario(e.texto)
                    is Elemento.Respuesta -> Respuesta(e.texto, e.escribiendo)
                    is Elemento.Pasos -> GrupoPasos(e)
                    is Elemento.Permiso -> TarjetaPermiso(e)
                    is Elemento.Error -> Text(e.texto, style = Texto.Pequeno, color = Colores.Rojo)
                }
            }
        }
        if (pensando) item(key = "pensando") {
            Box(Modifier.animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())) { Pensando() }
        }
    }
}

/**
 * Lo que se ve mientras la IA piensa: su logo girando y latiendo, y «Pensando» con un brillo que lo
 * recorre (como en las apps de Claude, ChatGPT o Gemini). Tras unos segundos, el tiempo; con el
 * modelo local, que es más lento, una nota.
 */
@Composable
internal fun Pensando() {
    val t = rememberInfiniteTransition(label = "pensando")
    val giro by t.animateFloat(0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "giro")
    val latido by t.animateFloat(0.82f, 1f, infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.FastOutSlowInEasing), RepeatMode.Reverse), label = "latido")
    val brillo by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "brillo")
    var segundos by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); segundos++ } }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(
            Iconos.Asistente, null, tint = Colores.Acento,
            modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = giro; scaleX = latido; scaleY = latido },
        )
        Spacer(Modifier.width(12.dp))
        Column {
            var ancho by remember { mutableStateOf(1f) }
            val pincel = androidx.compose.ui.graphics.Brush.linearGradient(
                0f to Colores.Texto3, 0.5f to Colores.Texto, 1f to Colores.Texto3,
                start = androidx.compose.ui.geometry.Offset(ancho * (brillo - 0.5f), 0f),
                end = androidx.compose.ui.geometry.Offset(ancho * (brillo + 0.5f), 0f),
            )
            Text(
                if (segundos >= 4) stringResource(R.string.chat_pensando_segundos, segundos) else stringResource(R.string.chat_pensando),
                style = Texto.Cuerpo.copy(brush = pincel),
                modifier = Modifier.onSizeChanged { ancho = it.width.toFloat().coerceAtLeast(1f) },
            )
            androidx.compose.animation.AnimatedVisibility(com.sios.ui.Marca.id == "local" && segundos >= 12) {
                Text(stringResource(R.string.chat_pensando_local), style = Texto.Pequeno, color = Colores.Texto3)
            }
        }
    }
}

@Composable
internal fun BurbujaUsuario(texto: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            texto,
            style = Texto.Cuerpo,
            color = Colores.Texto,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                .background(Colores.Superficie2)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
internal fun Respuesta(texto: String, escribiendo: Boolean) {
    Column {
        if (texto.isNotBlank()) Markdown(texto)
        if (escribiendo && texto.isNotBlank()) {
            // Mientras llega el texto, un punto del color de la IA que late al final.
            val t = rememberInfiniteTransition(label = "latido")
            val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "alfa")
            val e by t.animateFloat(0.8f, 1.1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "escala")
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .size(10.dp)
                    .graphicsLayer { alpha = a; scaleX = e; scaleY = e }
                    .clip(Formas.Pastilla)
                    .background(Colores.Acento)
            )
        }
    }
}

@Composable
private fun Giro(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "giro")
    val angulo by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "angulo")
    Canvas(modifier.size(14.dp).rotate(angulo)) {
        drawArc(Colores.Borde, 0f, 360f, false, style = Stroke(2.dp.toPx()))
        drawArc(Colores.Acento, 0f, 90f, false, style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    }
}

@Composable
internal fun GrupoPasos(e: Elemento.Pasos) {
    val plegable = e.terminado && e.pasos.size >= 3
    var abierto by rememberSaveable(e.clave) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (plegable) {
            val giro by animateFloatAsState(if (abierto) 90f else 0f, Muelle.vivo(), label = "flecha")
            Row(
                Modifier.pulsable { abierto = !abierto },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(14.dp).rotate(giro))
                Spacer(Modifier.width(6.dp))
                Text(pluralStringResource(R.plurals.chat_pasos, e.pasos.size, e.pasos.size), style = Texto.Pequeno, color = Colores.Texto3)
            }
        }
        AnimatedVisibility(
            visible = !plegable || abierto,
            enter = expandVertically(Muelle.suave()) + fadeIn(Muelle.suave()),
            exit = shrinkVertically(Muelle.suave()) + fadeOut(Muelle.suave()),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in e.pasos) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        when (p.estado) {
                            EstadoPaso.EN_CURSO -> Giro()
                            EstadoPaso.HECHO -> Icon(Iconos.Hecho, null, tint = Colores.Acento, modifier = Modifier.size(16.dp))
                            EstadoPaso.ERROR -> Icon(Iconos.Cruz, null, tint = Colores.Rojo, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(p.resumen, style = Texto.Pequeno, color = Colores.Texto2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
internal fun TarjetaPermiso(p: Elemento.Permiso) {
    val pendiente = p.estado == EstadoPermiso.PENDIENTE
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Formas.Tarjeta)
            .background(if (pendiente) Colores.AcentoSuave else Colores.Fondo)
            .border(1.dp, if (pendiente) Colores.Acento else Colores.Borde, Formas.Tarjeta)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(if (pendiente) R.string.chat_permiso_pendiente else R.string.chat_permiso_confirmacion),
            style = Texto.Etiqueta,
            color = if (pendiente) Colores.Acento else Colores.Texto3,
        )
        Text(p.titulo, style = Texto.Subtitulo, color = Colores.Texto)
        // Se pide porque Claude acaba de leer mensajes, avisos o webs de otros.
        p.aviso?.let {
            Text(
                it, style = Texto.Pequeno, color = Colores.Texto,
                modifier = Modifier.fillMaxWidth().clip(Formas.Pequena).background(Colores.Rojo.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
        p.detalle?.let {
            if (p.codigo) Text(
                it,
                style = Texto.Codigo,
                color = Colores.Texto,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(Formas.Pequena)
                    .background(Colores.Superficie)
                    .padding(10.dp),
            ) else Text(it, style = Texto.Cuerpo, color = Colores.Texto)
        }
        AnimatedVisibility(pendiente, exit = shrinkVertically(Muelle.suave()) + fadeOut(Muelle.suave())) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                BotonSecundario(stringResource(R.string.chat_no_permitir), Modifier.weight(1f)) { Servicio.responderPermiso(p.id, false) }
                BotonPrimario(stringResource(R.string.chat_permitir), Modifier.weight(1f)) { Servicio.responderPermiso(p.id, true) }
            }
        }
        AnimatedVisibility(!pendiente, enter = expandVertically(Muelle.suave()) + fadeIn(Muelle.suave())) {
            Text(
                stringResource(if (p.estado == EstadoPermiso.PERMITIDO) R.string.chat_permitido else R.string.chat_no_permitido),
                style = Texto.Pequeno,
                color = if (p.estado == EstadoPermiso.PERMITIDO) Colores.Acento else Colores.Rojo,
            )
        }
    }
}

@Composable
private fun CajaTexto(
    texto: String,
    onTexto: (String) -> Unit,
    ocupado: Boolean,
    foco: FocusRequester,
    onEnviar: () -> Unit,
    onParar: () -> Unit,
    onDictar: () -> Unit,
    escucha: com.sios.sistema.Escucha,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Colores.Superficie)
            .border(1.dp, Colores.Borde, RoundedCornerShape(26.dp))
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (escucha.escuchando) {
            // Escuchando: el orbe late con la voz y se ve lo que va entendiendo.
            Row(Modifier.weight(1f).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                com.sios.sistema.OrbeVoz(escucha.nivel, 32.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    escucha.parcial.ifEmpty { stringResource(if (escucha.local) R.string.voz_escuchando_local else R.string.asistente_escuchando) },
                    style = Texto.Cuerpo,
                    color = if (escucha.parcial.isEmpty()) Colores.Texto3 else Colores.Texto,
                )
            }
        } else BasicTextField(
            value = texto,
            onValueChange = onTexto,
            textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
            cursorBrush = SolidColor(Colores.Acento),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            maxLines = 6,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 10.dp)
                .focusRequester(foco),
            decorationBox = { campo ->
                Box {
                    if (texto.isEmpty()) Text(stringResource(R.string.comun_preguntale_a_ia), style = Texto.Cuerpo, color = Colores.Texto3)
                    campo()
                }
            },
        )
        Spacer(Modifier.width(6.dp))
        when {
            escucha.escuchando -> BotonIcono(Iconos.Parar, stringResource(R.string.comun_detener), tam = 44.dp, color = Colores.SobreAcento, fondo = Colores.Acento) { escucha.parar() }
            ocupado -> BotonIcono(Iconos.Parar, stringResource(R.string.comun_detener), tam = 44.dp, color = Colores.Fondo, fondo = Colores.Texto, onClick = onParar)
            texto.isBlank() -> BotonIcono(Iconos.Micro, stringResource(R.string.comun_dictar), tam = 44.dp, color = Colores.Texto2, onClick = onDictar)
            else -> BotonIcono(Iconos.Enviar, stringResource(R.string.comun_enviar), tam = 44.dp, color = Colores.SobreAcento, fondo = Colores.Acento, onClick = onEnviar)
        }
    }
}

// ---------- Cajón de conversaciones ----------

/** Grupo del cajón según la última actividad (texto de la app). */
@StringRes
private fun grupo(ms: Long): Int {
    val hoy = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    return when {
        ms >= hoy -> R.string.chat_grupo_hoy
        ms >= hoy - 86_400_000L -> R.string.chat_grupo_ayer
        ms >= hoy - 7 * 86_400_000L -> R.string.chat_grupo_esta_semana
        ms >= hoy - 30 * 86_400_000L -> R.string.chat_grupo_este_mes
        else -> R.string.chat_grupo_anteriores
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CajonSesiones(
    sesiones: List<Sesion>,
    actual: String,
    onNueva: () -> Unit,
    onAbrir: (String) -> Unit,
    onAjustes: () -> Unit,
    semana: Int?,
) {
    var editando by remember { mutableStateOf<Sesion?>(null) }
    ModalDrawerSheet(
        drawerContainerColor = Colores.Fondo,
        drawerShape = RoundedCornerShape(0.dp),
        modifier = Modifier.width(310.dp),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Claude", style = Texto.Titulo, color = Colores.Texto)
            }
            Row(
                Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
                    .clip(Formas.Pequena)
                    .pulsable(onNueva)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Iconos.Nueva, null, tint = Colores.Acento, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.comun_nueva_conversacion), style = Texto.Subtitulo, color = Colores.Texto)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                sesiones.groupBy { grupo(it.actualizada) }.forEach { (nombre, lista) ->
                    item(key = "g-$nombre") {
                        Text(
                            stringResource(nombre).uppercase(),
                            style = Texto.Etiqueta,
                            color = Colores.Texto3,
                            modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
                        )
                    }
                    items(lista, key = { it.id }) { s ->
                        val activa = s.id == actual
                        Text(
                            s.titulo.ifBlank { stringResource(R.string.chat_conversacion) },
                            style = Texto.Cuerpo,
                            color = if (activa) Colores.Texto else Colores.Texto2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(placementSpec = Muelle.suave())
                                .clip(Formas.Pequena)
                                .background(if (activa) Colores.Superficie2 else Colores.Fondo)
                                .combinedClickable(onClick = { onAbrir(s.id) }, onLongClick = { editando = s })
                                .padding(horizontal = 12.dp, vertical = 11.dp),
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .pulsable(onAjustes)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Iconos.Ajustes, null, tint = Colores.Texto2, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.comun_ajustes_ia), style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.weight(1f))
                if (semana != null) Text(stringResource(R.string.uso_semana_corto, semana), style = Texto.Pequeno, color = Colores.Texto3)
            }
        }
    }

    editando?.let { s ->
        val generico = stringResource(R.string.chat_conversacion)
        var titulo by remember(s.id) { mutableStateOf(s.titulo.ifBlank { generico }) }
        AlertDialog(
            onDismissRequest = { editando = null },
            containerColor = Colores.Superficie,
            shape = Formas.Tarjeta,
            title = { Text(stringResource(R.string.chat_conversacion), style = Texto.Subtitulo, color = Colores.Texto) },
            text = {
                OutlinedTextField(
                    value = titulo,
                    onValueChange = { titulo = it },
                    singleLine = true,
                    textStyle = Texto.Cuerpo,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Colores.Acento,
                        unfocusedBorderColor = Colores.Borde,
                        cursorColor = Colores.Acento,
                        focusedTextColor = Colores.Texto,
                        unfocusedTextColor = Colores.Texto,
                    ),
                )
            },
            confirmButton = {
                TextButton({ Servicio.renombrar(s.id, titulo); editando = null }) {
                    Text(stringResource(R.string.chat_guardar), style = Texto.Subtitulo, color = Colores.Acento)
                }
            },
            dismissButton = {
                TextButton({ Servicio.borrar(s.id); editando = null }) {
                    Text(stringResource(R.string.chat_borrar), style = Texto.Subtitulo, color = Colores.Rojo)
                }
            },
        )
    }
}

/** Sin internet (o sin agente), lo básico se hace aquí mismo; si no, va a Claude. */
private fun enviarOLocal(ctx: android.content.Context, texto: String) {
    if (!com.sios.sistema.SinConexion.atender(ctx, texto)) Servicio.enviar(texto)
}
