package com.sios.sistema

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.chat.ChatActivity
import com.sios.chat.GrupoPasos
import com.sios.chat.Respuesta
import com.sios.chat.TarjetaPermiso
import com.sios.datos.Elemento
import com.sios.datos.Servicio
import com.sios.ui.BotonIcono
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hoja del asistente: sube desde abajo sobre cualquier app. Cada pregunta abre una
 * conversación nueva (como un buscador) y se puede seguir en el chat completo.
 */
class AsistenteActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Servicio.iniciar()
        // Búsqueda en pantalla (mantener pulsada la barra de navegación): Android pasa una captura.
        val captura = if (intent.action == ACCION_BUSQUEDA_PANTALLA)
            intent.getParcelableExtra(EXTRA_CAPTURA, android.graphics.Bitmap::class.java) else null
        // Búsquedas web o por voz de cualquier app: también las responde Claude.
        val consulta = intent.getStringExtra(android.app.SearchManager.QUERY)?.takeIf { it.isNotBlank() }
        val dictar = intent.action == android.speech.RecognizerIntent.ACTION_WEB_SEARCH
        // Mantener pulsado el botón de encendido: Claude escucha directamente.
        val voz = intent.getBooleanExtra(EXTRA_VOZ, false)
        // La hoja usa el micrófono: «Oye Claude» lo suelta mientras está abierta.
        if (OyeAsistente.activo(this)) runCatching { startService(Intent(this, OyeAsistente::class.java).setAction(OyeAsistente.PAUSAR)) }
        // La voz (para responder en alto) se prepara después del primer fotograma: conectar con el
        // motor de voz retrasaba la aparición de la hoja.
        window.decorView.postDelayed({ if (!isFinishing) Voz.preparar(this) }, 400)
        // «Compartir» desde otra app: texto, enlaces, fotos o un PDF.
        val compartiendo = Compartido.esCompartir(intent)
        if (compartiendo) {
            val i = intent
            val deApp = referrer?.host?.let { p -> runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrNull() }
            lifecycleScope.launch {
                compartido = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Compartido.leer(this@AsistenteActivity, i, deApp) }
                leyendo = false
            }
        }
        leyendo = compartiendo
        // Cuando Claude toma el control para tocar la app de debajo, la hoja se aparta; cuando
        // termina, vuelve con la respuesta.
        lifecycleScope.launch {
            Manos.activo.collect { activo ->
                if (activo && !isFinishing) {
                    apartadaPorIa = true
                    moveTaskToBack(true)
                } else if (!activo && apartadaPorIa) {
                    apartadaPorIa = false
                    if (!isFinishing && !com.sios.inicio.InicioActivity.enPrimerPlano) traerDelante()
                }
            }
        }
        lifecycleScope.launch {
            var antes = false
            Servicio.estado.collect { e ->
                if (antes && !e.ocupado) finTrabajo = android.os.SystemClock.uptimeMillis()
                antes = e.ocupado
            }
        }
        setContent {
            TemaSios {
                HojaAsistente(
                    captura = captura, compartido = compartido, leyendo = leyendo, consulta = consulta,
                    dictar = dictar, voz = voz, deCamara = intent.getStringExtra(EXTRA_RAPIDAS) == "camara", onCerrar = ::finish,
                )
            }
        }
    }

    private var compartido by mutableStateOf<Compartido?>(null)
    private var leyendo by mutableStateOf(false)

    // Otra invocación con la hoja ya abierta (compartir, búsqueda en pantalla…): empieza de cero.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    // Si Claude abre una app mientras responde, esa app tapa la hoja: la hoja vuelve delante (es
    // translúcida, así que la app se ve debajo). Solo cuando la tapan con Claude trabajando (o recién
    // terminado) y no es el inicio lo que queda delante: si el usuario se va, no lo persigue. Tampoco
    // vuelve si es la propia hoja la que abre algo, como el chat completo.
    private var abreLaHoja = false
    private var finTrabajo = 0L
    private var apartadaPorIa = false

    private fun traerDelante() = runCatching {
        getSystemService(android.app.ActivityManager::class.java).moveTaskToFront(
            taskId, 0, android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
        )
    }

    private val volverDelante = Runnable {
        if (isFinishing || isDestroyed || abreLaHoja || Manos.activo.value || com.sios.inicio.InicioActivity.enPrimerPlano) return@Runnable
        if (!getSystemService(android.os.PowerManager::class.java).isInteractive) return@Runnable
        traerDelante()
    }

    private fun claudeTrabajando() =
        Servicio.estado.value.ocupado || android.os.SystemClock.uptimeMillis() - finTrabajo < 3000

    override fun startActivity(intent: Intent, options: Bundle?) {
        abreLaHoja = true
        super.startActivity(intent, options)
    }

    override fun onStart() {
        super.onStart()
        abreLaHoja = false
        window.decorView.removeCallbacks(volverDelante)
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !abreLaHoja && claudeTrabajando()) window.decorView.postDelayed(volverDelante, 300)
    }

    companion object {
        const val ACCION_BUSQUEDA_PANTALLA = "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH"
        const val EXTRA_CAPTURA = "android.app.contextualsearch.extra.SCREENSHOT"
        const val EXTRA_VOZ = "com.sios.asistente.VOZ"
        const val EXTRA_RAPIDAS = "com.sios.asistente.RAPIDAS" // «camara»: preguntas rápidas de la cámara
    }

    override fun finish() {
        super.finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            Voz.callar()
            OyeAsistente.reanudar(this)
        }
    }
}

/** La captura, reducida a 1280 px como mucho y en JPEG: bastante para leerla y ligera de enviar. */
private fun aJpegBase64(b: android.graphics.Bitmap): String {
    val escala = minOf(1f, 1280f / maxOf(b.width, b.height))
    val copia = b.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    val r = if (escala < 1f) android.graphics.Bitmap.createScaledBitmap(copia, (copia.width * escala).toInt(), (copia.height * escala).toInt(), true) else copia
    val salida = java.io.ByteArrayOutputStream()
    r.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, salida)
    return android.util.Base64.encodeToString(salida.toByteArray(), android.util.Base64.NO_WRAP)
}

@Composable
private fun HojaAsistente(
    captura: android.graphics.Bitmap?,
    compartido: Compartido?,
    leyendo: Boolean,
    consulta: String?,
    dictar: Boolean,
    voz: Boolean,
    deCamara: Boolean = false,
    onCerrar: () -> Unit,
) {
    val estado by Servicio.estado.collectAsStateWithLifecycle()
    var texto by remember { mutableStateOf("") }
    var preguntado by remember { mutableStateOf(false) }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    val foco = remember { FocusRequester() }
    val contexto = LocalContext.current
    val teclado = LocalSoftwareKeyboardController.current

    fun cerrar() {
        visible.targetState = false
    }
    // Cuando termina la animación de salida, se cierra la actividad.
    LaunchedEffect(visible.currentState, visible.targetState) {
        if (!visible.targetState && !visible.currentState) onCerrar()
    }
    LaunchedEffect(Unit) {
        delay(250)
        runCatching { foco.requestFocus() }
    }

    var conCaptura by remember { mutableStateOf(captura != null) }
    var recorte by remember { mutableStateOf<android.graphics.Bitmap?>(null) } // lo rodeado; null = todo
    var conCompartido by remember { mutableStateOf(true) }
    fun enviar(pregunta: String = texto) {
        val t = pregunta.trim()
        if (t.isEmpty()) return
        teclado?.hide() // deja sitio a la respuesta
        // Sin internet o sin agente: lo básico (alarmas, linterna, wifi…) se hace aquí mismo.
        if (SinConexion.atender(contexto, t)) {
            preguntado = true
            conCaptura = false
            texto = ""
            return
        }
        when {
            preguntado -> Servicio.enviar(t)
            compartido != null && conCompartido -> Servicio.compartir(t, compartido.json())
            else -> Servicio.preguntar(t, if (conCaptura && captura != null) aJpegBase64(recorte ?: captura) else null)
        }
        preguntado = true
        conCaptura = false
        texto = ""
    }
    // Dictado dentro de la hoja (sin la ventana de Google): lo dicho se pregunta directamente.
    val escucha = remember { Escucha(contexto) { enviar(it) } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { escucha.liberar() } }
    val permisoMicro = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { concedido -> if (concedido) escucha.empezar() }
    fun escuchar() {
        teclado?.hide()
        if (contexto.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) escucha.empezar()
        else permisoMicro.launch(android.Manifest.permission.RECORD_AUDIO)
    }
    if (voz) LaunchedEffect(Unit) { delay(350); escuchar() }
    // Si la pregunta fue hablada, la respuesta se dice en voz alta al terminar.
    var porVoz by remember { mutableStateOf(voz) }
    LaunchedEffect(escucha.escuchando) { if (escucha.escuchando) porVoz = true }
    LaunchedEffect(estado.ocupado, preguntado) {
        if (!estado.ocupado && preguntado && porVoz && OyeAsistente.responderEnVoz(contexto)) {
            val ultima = estado.elementos.lastOrNull { it is Elemento.Respuesta } as? Elemento.Respuesta
            if (ultima != null && !ultima.escribiendo) Voz.decir(contexto, ultima.texto)
        }
    }
    // Una búsqueda que llega de otra app (o de la cámara) se pregunta directamente, con lo compartido si lo hay.
    LaunchedEffect(consulta, leyendo) { if (consulta != null && !preguntado && !leyendo) enviar(consulta) }
    if (dictar) LaunchedEffect(Unit) { runCatching { contexto.startActivity(Intent(contexto, com.sios.chat.ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(com.sios.chat.EXTRA_NUEVA, true).putExtra(com.sios.chat.EXTRA_DICTAR, true)) }; onCerrar() }

    // Solo lo de esta pregunta: lo que hay desde el último mensaje del usuario.
    val respuesta = if (!preguntado) emptyList() else
        estado.elementos.drop(estado.elementos.indexOfLast { it is Elemento.Usuario } + 1)

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { cerrar() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Búsqueda en pantalla: la captura congelada, para rodear o tocar lo que se quiere preguntar.
        if (captura != null && !preguntado) {
            SeleccionPantalla(captura, onElegir = { recorte = it; conCaptura = true })
        }
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(Muelle.suave()) { it } + fadeIn(Muelle.suave()),
            exit = slideOutVertically(Muelle.suave()) { it } + fadeOut(Muelle.suave()),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(Colores.Fondo)
                    .border(1.dp, Colores.Borde, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .pointerInput(Unit) { detectTapGestures { } } // los toques dentro no cierran
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(Formas.Pastilla)
                        .background(Colores.BordeFuerte)
                )
                Spacer(Modifier.height(14.dp))

                // Recién enviada, mientras la IA arranca y aún no ha dicho nada: «Pensando» al momento,
                // para que no parezca que no ha pasado nada.
                AnimatedVisibility(
                    preguntado && respuesta.none { it !is Elemento.Usuario },
                    enter = fadeIn(Muelle.suave()) + androidx.compose.animation.expandVertically(Muelle.suave()),
                    exit = fadeOut(Muelle.suave()) + androidx.compose.animation.shrinkVertically(Muelle.suave()),
                ) {
                    Box(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) { com.sios.chat.Pensando() }
                }

                if (respuesta.isNotEmpty()) {
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        for (e in respuesta) when (e) {
                            is Elemento.Respuesta -> Respuesta(e.texto, e.escribiendo)
                            is Elemento.Pasos -> GrupoPasos(e)
                            is Elemento.Permiso -> TarjetaPermiso(e)
                            is Elemento.Error -> Text(e.texto, style = Texto.Pequeno, color = Colores.Rojo)
                            is Elemento.Usuario -> {}
                        }
                    }
                    // La pregunta es la conversación actual del servicio: el chat la muestra tal cual.
                    Text(
                        stringResource(R.string.asistente_seguir_chat),
                        style = Texto.Pequeno,
                        color = Colores.Acento,
                        modifier = Modifier
                            .padding(horizontal = 6.dp, vertical = 8.dp)
                            .pulsable {
                                contexto.startActivity(
                                    Intent(contexto, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                                onCerrar()
                            },
                    )
                    Spacer(Modifier.height(4.dp))
                }

                // Lo que había en pantalla: miniatura, quitarla con un toque y preguntas rápidas.
                AnimatedVisibility(conCaptura && captura != null && !preguntado) {
                    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            (recorte ?: captura)?.let {
                                androidx.compose.foundation.Image(
                                    it.asImageBitmap(), null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.size(width = 44.dp, height = 72.dp).clip(Formas.Pequena).border(1.dp, Colores.Borde, Formas.Pequena),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(if (recorte != null) R.string.asistente_seleccion_titulo else R.string.asistente_pantalla_titulo), style = Texto.Subtitulo, color = Colores.Texto)
                                Text(stringResource(R.string.asistente_pantalla_nota), style = Texto.Pequeno, color = Colores.Texto3)
                            }
                            BotonIcono(Iconos.Cruz, stringResource(R.string.asistente_pantalla_quitar), tam = 36.dp, color = Colores.Texto2, fondo = Colores.Superficie) { conCaptura = false }
                        }
                        val rapidas = listOf(
                            stringResource(R.string.asistente_rapida_que),
                            stringResource(R.string.asistente_rapida_traducir),
                            stringResource(R.string.asistente_rapida_resumir),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (r in rapidas) Text(
                                r,
                                style = Texto.Pequeno,
                                color = Colores.Texto,
                                modifier = Modifier
                                    .clip(Formas.Pastilla)
                                    .background(Colores.Superficie)
                                    .border(1.dp, Colores.Borde, Formas.Pastilla)
                                    .pulsable { enviar(r) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
                // Lo compartido desde otra app: vista previa y preguntas rápidas.
                AnimatedVisibility((leyendo || compartido != null) && conCompartido && !preguntado) {
                    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(52.dp).clip(Formas.Pequena).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Pequena),
                                contentAlignment = Alignment.Center,
                            ) {
                                val m = compartido?.miniatura
                                if (m != null) androidx.compose.foundation.Image(
                                    m.asImageBitmap(), null,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                ) else Icon(
                                    if (compartido?.titulo == stringResource(R.string.compartido_enlace)) Iconos.Enlace else Iconos.Documento,
                                    null, tint = Colores.Acento, modifier = Modifier.size(22.dp),
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(compartido?.titulo ?: stringResource(R.string.compartido_leyendo), style = Texto.Subtitulo, color = Colores.Texto)
                                Text(
                                    compartido?.detalle?.takeIf { it.isNotBlank() } ?: stringResource(R.string.asistente_pantalla_nota),
                                    style = Texto.Pequeno, color = Colores.Texto3, maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            BotonIcono(Iconos.Cruz, stringResource(R.string.asistente_pantalla_quitar), tam = 36.dp, color = Colores.Texto2, fondo = Colores.Superficie) { conCompartido = false }
                        }
                        val rapidas = if (deCamara) listOf(
                            stringResource(R.string.asistente_rapida_que),
                            stringResource(R.string.asistente_rapida_traducir),
                            stringResource(R.string.asistente_rapida_explicar),
                        ) else listOf(
                            stringResource(R.string.asistente_rapida_resumir),
                            stringResource(R.string.asistente_rapida_traducir),
                            stringResource(R.string.asistente_rapida_explicar),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (r in rapidas) Text(
                                r,
                                style = Texto.Pequeno,
                                color = if (compartido != null) Colores.Texto else Colores.Texto3,
                                modifier = Modifier
                                    .clip(Formas.Pastilla)
                                    .background(Colores.Superficie)
                                    .border(1.dp, Colores.Borde, Formas.Pastilla)
                                    .pulsable { if (compartido != null) enviar(r) }
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Formas.Pastilla)
                        .background(Colores.Superficie)
                        .border(1.dp, Colores.Borde, Formas.Pastilla)
                        .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (escucha.escuchando) {
                        Box(Modifier.padding(horizontal = 2.dp).size(32.dp), contentAlignment = Alignment.Center) { OrbeVoz(escucha.nivel, 32.dp) }
                        Text(
                            escucha.parcial.ifEmpty { stringResource(if (escucha.local) R.string.voz_escuchando_local else R.string.asistente_escuchando) },
                            style = Texto.Cuerpo,
                            color = if (escucha.parcial.isEmpty()) Colores.Texto3 else Colores.Texto,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 4.dp, top = 10.dp, bottom = 10.dp),
                        )
                    } else Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.padding(horizontal = 8.dp).size(20.dp))
                    if (!escucha.escuchando) BasicTextField(
                        value = texto,
                        onValueChange = { texto = it },
                        textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
                        cursorBrush = SolidColor(Colores.Acento),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { enviar() }),
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp)
                            .focusRequester(foco),
                        decorationBox = { campo ->
                            Box {
                                if (texto.isEmpty()) Text(
                                    stringResource(if (preguntado) R.string.asistente_responder else R.string.comun_preguntale_a_ia),
                                    style = Texto.Cuerpo,
                                    color = Colores.Texto3,
                                )
                                campo()
                            }
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    if (escucha.escuchando)
                        BotonIcono(Iconos.Parar, stringResource(R.string.comun_detener), tam = 42.dp, color = Colores.SobreAcento, fondo = Colores.Acento) { escucha.parar() }
                    else if (estado.ocupado && preguntado)
                        BotonIcono(Iconos.Parar, stringResource(R.string.comun_detener), tam = 42.dp, color = Colores.Fondo, fondo = Colores.Texto) { Servicio.detener() }
                    else if (texto.isBlank() && !estado.ocupado && escucha.disponible)
                        BotonIcono(Iconos.Micro, stringResource(R.string.asistente_hablar), tam = 42.dp, color = Colores.Texto, fondo = Colores.Superficie2) { escuchar() }
                    else
                        BotonIcono(
                            Iconos.Enviar, stringResource(R.string.comun_enviar), tam = 42.dp,
                            color = if (texto.isBlank()) Colores.Texto3 else Colores.SobreAcento,
                            fondo = if (texto.isBlank()) Colores.Superficie2 else Colores.Acento,
                        ) { enviar() }
                }
            }
        }
    }
}
