package com.sios.ajustes

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.datos.Servicio
import com.sios.sistema.MemoriaPantalla
import com.sios.sistema.MemoriaPantallaBase
import com.sios.sistema.MemoriaPantallaBase.Recuerdo
import com.sios.sistema.MemoriaPantallaModelo
import com.sios.sistema.MemoriaPantallaPrivacidad
import com.sios.ui.BotonIcono
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Marca
import com.sios.ui.Markdown
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Buscar en la memoria de pantalla. Se abre con la huella o el PIN (BiometricPrompt con la
 * credencial del dispositivo) y se vuelve a cerrar en cuanto la pantalla deja de verse. La ventana
 * va con FLAG_SECURE: ni capturas ni la miniatura de recientes.
 */
class MemoriaPantallaBuscarActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Marca.envolver(base))

    private val bloqueada = mutableStateOf(true)
    private val fallo = mutableStateOf<String?>(null)
    private var autenticando = false
    private var pedirAlVolver = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            TemaSios {
                PantallaBuscar(bloqueada.value, fallo.value, onDesbloquear = ::pedir, onCerrar = ::finish)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (bloqueada.value && pedirAlVolver && !autenticando && MemoriaPantalla.activa(this)) {
            pedirAlVolver = false
            pedir()
        }
    }

    override fun onStop() {
        super.onStop()
        // Al irse (a otra app, al bloquear), se cierra: lo encontrado sale de la pantalla y de la memoria.
        if (!autenticando) {
            bloqueada.value = true
            pedirAlVolver = true
            Thread { MemoriaPantallaModelo.liberar() }.start()
        }
    }

    private fun pedir() {
        if (!MemoriaPantalla.hayBloqueo(this)) { fallo.value = getString(R.string.mp_b_error_bloqueo); return }
        autenticando = true
        fallo.value = null
        BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.mp_titulo))
            .setSubtitle(getString(R.string.mp_b_prompt_sub))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()
            .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult?) {
                    autenticando = false
                    bloqueada.value = false
                }

                override fun onAuthenticationError(codigo: Int, mensaje: CharSequence?) {
                    autenticando = false
                    if (codigo != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && codigo != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                        fallo.value = mensaje?.toString()
                    }
                }
            })
    }
}

// ---------- Interpretar la búsqueda ----------

/** La búsqueda ya entendida: palabras para el texto, frase para la imagen, el día y la app. */
private class Consulta(val palabras: List<String>, val paraImagen: String, val desde: Long?, val hasta: Long?, val app: String?)

private object Interpretar {
    private fun plano(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    /** Palabras vacías de los seis idiomas: no sirven para encontrar nada. */
    private val VACIAS = (
        "el la los las un una unos unas de del que en y a o por para con lo mi me te se su al es era vi visto donde cual como esto eso " +
            "the a an of in on at to for and or i saw seen was what where which my me this that it " +
            "der die das ein eine und oder im am an zu ich habe gesehen wo was mein " +
            "le les une des du et ou au aux je vu ai ou quel quelle ce " +
            "il lo gli le di e da ho visto dove che " +
            "o os as um uma do da e ou em no na onde"
        ).split(' ').toSet()

    private val HOY = setOf("hoy", "today", "heute", "aujourd'hui", "aujourdhui", "oggi", "hoje")
    private val AYER = setOf("ayer", "yesterday", "gestern", "hier", "ieri", "ontem")
    private val ANTEAYER = setOf("anteayer", "vorgestern", "avant-hier", "anteontem", "altroieri")
    private val IDIOMAS = listOf("es", "en", "de", "fr", "it", "pt").map(Locale::forLanguageTag)

    fun consulta(ctx: Context, texto: String): Consulta {
        var resto = " " + plano(texto).replace(Regex("[^\\p{L}\\p{N}'-]+"), " ") + " "
        val hoy = LocalDate.now()
        var dia: LocalDate? = null
        fun quitar(frase: String): Boolean {
            val f = " $frase "
            if (!resto.contains(f)) return false
            resto = resto.replace(f, " ")
            return true
        }
        // «hoy», «ayer», «anteayer» y los días de la semana (el último que haya pasado; hoy, si es hoy).
        for (h in HOY) if (quitar(h)) dia = hoy
        // «hier» es «ayer» en francés pero «aquí» en alemán: solo con el móvil en francés.
        for (a in AYER) if ((a != "hier" || Locale.getDefault().language == "fr") && quitar(a)) dia = hoy.minusDays(1)
        for (a in ANTEAYER) if (quitar(a)) dia = hoy.minusDays(2)
        if (quitar("day before yesterday")) dia = hoy.minusDays(2)
        for (d in DayOfWeek.entries) for (l in IDIOMAS) {
            val nombre = plano(d.getDisplayName(java.time.format.TextStyle.FULL, l))
            for (variante in setOf(nombre, nombre.replace('-', ' '), nombre.substringBefore('-'))) {
                if (variante.length >= 4 && quitar(variante)) {
                    val atras = ((hoy.dayOfWeek.value - d.value) + 7) % 7
                    dia = hoy.minusDays(atras.toLong())
                }
            }
        }
        // «en Chrome», «de WhatsApp»: una app que esté en la memoria.
        var app: String? = null
        for (p in MemoriaPantallaBase.apps(ctx)) {
            val nombre = plano(MemoriaPantallaPrivacidad.etiqueta(ctx, p))
            if (nombre.length >= 3 && quitar(nombre)) { app = p; break }
        }
        val palabras = resto.split(' ').map { it.trim('\'', '-') }.filter { it.length >= 2 && it !in VACIAS }
        val zona = ZoneId.systemDefault()
        val desde = dia?.atStartOfDay(zona)?.toInstant()?.toEpochMilli()
        val hasta = dia?.plusDays(1)?.atStartOfDay(zona)?.toInstant()?.toEpochMilli()
        // Para la imagen, la frase entera sin el día ni la app (el modelo entiende «el sofá azul»).
        return Consulta(palabras, resto.trim().replace(Regex("\\s+"), " "), desde, hasta, app)
    }
}

/** La búsqueda completa: texto (FTS) y, si está el modelo, imágenes por su huella; mezclados. */
private fun buscar(ctx: Context, texto: String): List<Recuerdo> {
    val c = Interpretar.consulta(ctx, texto)
    val porTexto = MemoriaPantallaBase.buscarTexto(ctx, c.palabras, c.desde, c.hasta, c.app)
    val porImagen = if (c.paraImagen.length >= 3) {
        runCatching { MemoriaPantallaModelo.huellaTexto(ctx, c.paraImagen) }.getOrNull()
            ?.let { MemoriaPantallaBase.buscarVector(ctx, it, c.desde, c.hasta, c.app) }.orEmpty()
    } else emptyList()
    val ya = porTexto.map { it.id }.toSet()
    val imagenes = porImagen.filter { it.id !in ya }
    if (porTexto.isEmpty()) return imagenes
    // Las imágenes, intercaladas (2.º, 5.º, 8.º…): ni tapan lo que coincide por texto ni se pierden al final.
    val salida = porTexto.toMutableList()
    imagenes.forEachIndexed { i, r -> salida.add((1 + i * 3).coerceAtMost(salida.size), r) }
    return salida
}

// ---------- Pantalla ----------

private enum class Modo { Buscar, Preguntar }

private sealed interface Respuesta {
    data object Nada : Respuesta
    data class Pensando(val fragmentos: Int) : Respuesta
    data class Lista(val texto: String, val fragmentos: Int) : Respuesta
    data object SinFragmentos : Respuesta
    data object Fallo : Respuesta
}

@Composable
private fun PantallaBuscar(bloqueada: Boolean, fallo: String?, onDesbloquear: () -> Unit, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val estado by MemoriaPantalla.estado.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { MemoriaPantalla.leer(ctx) }
    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            BotonIcono(Iconos.Atras, stringResource(R.string.comun_atras), Modifier.align(Alignment.CenterStart).padding(start = 8.dp), onClick = onCerrar)
        }
        AnimatedContent(
            when { !estado.activa -> 0; bloqueada -> 1; else -> 2 },
            transitionSpec = { fadeIn(Muelle.suave()) + scaleIn(Muelle.suave(), initialScale = 0.97f) togetherWith fadeOut(Muelle.suave()) },
            label = "buscar",
            modifier = Modifier.fillMaxSize(),
        ) { caso ->
            when (caso) {
                0 -> Centrado(stringResource(R.string.mp_b_desactivada), stringResource(R.string.mp_apagada_nota), stringResource(R.string.mp_b_ir_ajustes)) {
                    ctx.startActivity(Intent(ctx, AjustesActivity::class.java).putExtra("seccion", "pantalla"))
                    onCerrar()
                }
                1 -> Bloqueo(fallo, onDesbloquear)
                else -> Buscador(estado)
            }
        }
    }
}

/** Cerrada: el candado que respira y el botón para abrirla. */
@Composable
private fun Bloqueo(fallo: String?, onDesbloquear: () -> Unit) {
    val reloj = rememberInfiniteTransition(label = "candado")
    val respira by reloj.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "respira")
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(84.dp).clip(Formas.Pastilla).background(Colores.Acento.copy(alpha = 0.10f + 0.08f * respira)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_memoria_pantalla), null, tint = Colores.Acento, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.mp_b_bloqueada), style = Texto.Titulo, color = Colores.Texto, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.mp_b_bloqueada_nota), style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center)
        AnimatedVisibility(fallo != null, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
            Text(fallo.orEmpty(), style = Texto.Pequeno, color = Colores.Rojo, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
        }
        Spacer(Modifier.height(28.dp))
        BotonPrimario(stringResource(R.string.mp_b_desbloquear), Modifier.fillMaxWidth(), onDesbloquear)
    }
}

@Composable
private fun Centrado(titulo: String, nota: String, boton: String, onBoton: () -> Unit) {
    Column(
        Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(titulo, style = Texto.Titulo, color = Colores.Texto, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(nota, style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        BotonSecundario(boton, Modifier.fillMaxWidth(), onBoton)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Buscador(estado: MemoriaPantalla.Estado) {
    val ctx = LocalContext.current
    val foco = LocalFocusManager.current
    val alcance = rememberCoroutineScope()
    val pedirFoco = remember { FocusRequester() }
    var consulta by remember { mutableStateOf("") }
    var modo by remember { mutableStateOf(Modo.Buscar) }
    var resultados by remember { mutableStateOf<List<Recuerdo>?>(null) }
    var respuesta by remember { mutableStateOf<Respuesta>(Respuesta.Nada) }
    val hayIa = !Marca.sinElegir
    val ia = Marca.nombre

    // Al entrar: se borra lo caducado y se cuenta lo que hay.
    val resumen by produceState<Pair<MemoriaPantallaBase.Resumen?, Long>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                runCatching { MemoriaPantallaBase.caducar(ctx, MemoriaPantalla.dias(ctx)) }
                MemoriaPantallaBase.resumen(ctx) to MemoriaPantallaBase.tamano(ctx)
            }.getOrNull()
        }
    }
    LaunchedEffect(Unit) { delay(300); runCatching { pedirFoco.requestFocus() } }

    // La búsqueda local va a la vez que se escribe (con una pausa corta para no buscar letra a letra).
    LaunchedEffect(consulta) {
        if (consulta.isBlank()) { resultados = null; respuesta = Respuesta.Nada; return@LaunchedEffect }
        delay(260)
        resultados = withContext(Dispatchers.IO) { runCatching { buscar(ctx, consulta) }.getOrDefault(emptyList()) }
    }

    fun preguntar() {
        if (consulta.isBlank() || respuesta is Respuesta.Pensando) return
        foco.clearFocus()
        alcance.launch {
            val lista = withContext(Dispatchers.IO) { runCatching { buscar(ctx, consulta) }.getOrDefault(emptyList()) }
            resultados = lista
            // A la IA solo le llegan los fragmentos que coinciden (como mucho 8), nunca el historial.
            val elegidos = lista.take(8)
            val textos = withContext(Dispatchers.IO) { runCatching { MemoriaPantallaBase.textos(ctx, elegidos.map { it.id }) }.getOrDefault(emptyMap()) }
            val fragmentos = elegidos.mapNotNull { r -> textos[r.id]?.takeIf { it.isNotBlank() }?.let { r to it } }
            if (fragmentos.isEmpty()) { respuesta = Respuesta.SinFragmentos; return@launch }
            respuesta = Respuesta.Pensando(fragmentos.size)
            val mensaje = buildString {
                append("Pregunta: ").append(consulta.trim()).append("\n\nFragmentos:\n")
                fragmentos.forEachIndexed { i, (r, t) ->
                    append("[").append(i + 1).append("] ").append(MemoriaPantallaPrivacidad.etiqueta(ctx, r.app))
                        .append(" · ").append(cuando(ctx, r.fecha)).append("\n").append(t).append("\n\n")
                }
            }
            val r = Servicio.rapido(mensaje, SISTEMA)
            respuesta = if (r == null) Respuesta.Fallo else Respuesta.Lista(r, fragmentos.size)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text(stringResource(R.string.mp_b_titulo), style = TituloGrande, color = Colores.Texto)
            Spacer(Modifier.height(16.dp))
            CampoBusqueda(
                consulta,
                if (modo == Modo.Preguntar) stringResource(R.string.mp_b_preguntar_pista, ia) else stringResource(R.string.mp_b_buscar_pista),
                pedirFoco,
                enviar = modo == Modo.Preguntar,
                onCambio = { consulta = it; if (respuesta !is Respuesta.Pensando) respuesta = Respuesta.Nada },
                onEnviar = { if (modo == Modo.Preguntar) preguntar() else foco.clearFocus() },
            )
            if (hayIa) {
                Spacer(Modifier.height(10.dp))
                SegmentosMp(listOf(stringResource(R.string.mp_b_modo_buscar), stringResource(R.string.mp_b_modo_preguntar, ia)), modo.ordinal) {
                    modo = Modo.entries[it]
                    respuesta = Respuesta.Nada
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (estado.pausada) item(key = "pausa") {
                Box(Modifier.animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())) {
                    AvisoMp(stringResource(R.string.mp_estado_pausada), stringResource(R.string.mp_reanudar)) { MemoriaPantalla.pausar(ctx, false) }
                }
            }
            if (modo == Modo.Preguntar) item(key = "ia") {
                Box(Modifier.animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())) {
                    TarjetaRespuesta(respuesta, ia, consulta.isNotBlank()) { preguntar() }
                }
            }
            val r = resultados
            when {
                consulta.isBlank() -> item(key = "vacio") {
                    Box(Modifier.animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())) {
                        Inicio(resumen, estado.dias) { consulta = it }
                    }
                }
                r == null -> Unit
                r.isEmpty() -> item(key = "nada") {
                    Column(
                        Modifier.fillMaxWidth().padding(top = 36.dp).animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(stringResource(R.string.mp_b_nada, consulta.trim()), style = Texto.Subtitulo, color = Colores.Texto2, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.mp_b_nada_nota), style = Texto.Pequeno, color = Colores.Texto3, textAlign = TextAlign.Center)
                    }
                }
                else -> items(r, key = { it.id }) { rec ->
                    Box(Modifier.animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())) {
                        TarjetaRecuerdo(rec) { abrirApp(ctx, rec.app) }
                    }
                }
            }
        }
    }
}

/** Lo que se le dice a la IA: responder solo con los fragmentos, sin inventar. */
private const val SISTEMA = "Eres la memoria de pantalla del móvil del usuario. Te paso fragmentos de texto que vio en su pantalla, " +
    "cada uno numerado y con la app y la hora. Responde a su pregunta usando solo esos fragmentos, en el idioma de la pregunta, " +
    "breve y directo. Si la respuesta está, dala tal cual (códigos, direcciones, números exactos) y di de qué fragmento sale, " +
    "por ejemplo [2]. Si no está en los fragmentos, dilo; no inventes ni supongas."

@Composable
private fun CampoBusqueda(
    texto: String, pista: String, foco: FocusRequester, enviar: Boolean,
    onCambio: (String) -> Unit, onEnviar: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(Formas.Pequena).background(Colores.Superficie2).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Iconos.Buscar, null, tint = Colores.Texto3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (texto.isEmpty()) Text(pista, style = Texto.Cuerpo, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                texto, onCambio, singleLine = true,
                textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
                cursorBrush = SolidColor(Colores.Acento),
                keyboardOptions = KeyboardOptions(imeAction = if (enviar) ImeAction.Send else ImeAction.Search),
                keyboardActions = KeyboardActions(onSend = { onEnviar() }, onSearch = { onEnviar() }),
                modifier = Modifier.fillMaxWidth().focusRequester(foco),
            )
        }
        AnimatedVisibility(
            texto.isNotEmpty(),
            enter = fadeIn(Muelle.suave()) + scaleIn(Muelle.suave(), initialScale = 0.6f),
            exit = fadeOut(Muelle.suave()) + scaleOut(Muelle.suave(), targetScale = 0.6f),
        ) {
            BotonIcono(Iconos.Cruz, stringResource(R.string.ajs_borrar), tam = 36.dp, color = Colores.Texto3) { onCambio("") }
        }
    }
}

/** Antes de escribir: cuánto hay guardado, si falta el modelo y unas ideas de qué buscar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Inicio(resumen: Pair<MemoriaPantallaBase.Resumen?, Long>?, dias: Int, onEjemplo: (String) -> Unit) {
    val ctx = LocalContext.current
    val modelo = remember { MemoriaPantallaModelo.listo(ctx) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(16.dp)) {
            Text(stringResource(R.string.mp_b_vacio_titulo), style = Texto.Subtitulo, color = Colores.Texto)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.mp_b_vacio_nota), style = Texto.Pequeno, color = Colores.Texto3)
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (e in listOf(R.string.mp_b_ejemplo_1, R.string.mp_b_ejemplo_2, R.string.mp_b_ejemplo_3)) {
                    val t = stringResource(e)
                    Text(
                        t, style = Texto.Pequeno, color = Colores.Texto,
                        modifier = Modifier.clip(Formas.Pastilla).background(Colores.Superficie2).pulsable { onEjemplo(t) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        val n = resumen?.first?.momentos
        AnimatedContent(n, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "resumen") { cuenta ->
            Text(
                if (cuenta == null) stringResource(R.string.mp_cargando)
                else pluralStringResource(R.plurals.mp_momentos, cuenta, cuenta) + " · " +
                    stringResource(R.string.mp_ocupa, Formatter.formatShortFileSize(ctx, resumen?.second ?: 0L)) + " · " +
                    stringResource(R.string.mp_se_borra, dias),
                style = Texto.Pequeno, color = Colores.Texto3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        if (!modelo) Text(stringResource(R.string.mp_b_sin_modelo), style = Texto.Pequeno, color = Colores.Texto3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** La respuesta de la IA, con lo que se le ha enviado bien a la vista. */
@Composable
private fun TarjetaRespuesta(r: Respuesta, ia: String, hayConsulta: Boolean, onPreguntar: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Tarjeta).padding(16.dp),
    ) {
        AnimatedContent(
            r,
            transitionSpec = { (fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave())).using(SizeTransform(clip = false) { _, _ -> Muelle.suave() }) },
            contentKey = { it::class },
            label = "respuesta",
        ) { e ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (e) {
                    Respuesta.Nada -> {
                        Text(stringResource(R.string.mp_b_ia_explica, ia), style = Texto.Pequeno, color = Colores.Texto3)
                        if (hayConsulta) BotonPrimario(stringResource(R.string.mp_b_preguntar), Modifier.fillMaxWidth(), onPreguntar)
                    }
                    is Respuesta.Pensando -> {
                        Puntos()
                        Text(pluralStringResource(R.plurals.mp_b_se_envian, e.fragmentos, ia, e.fragmentos), style = Texto.Pequeno, color = Colores.Texto3)
                    }
                    is Respuesta.Lista -> {
                        Markdown(e.texto)
                        Text(pluralStringResource(R.plurals.mp_b_se_envian, e.fragmentos, ia, e.fragmentos), style = Texto.Pequeno, color = Colores.Texto3)
                    }
                    Respuesta.SinFragmentos -> Text(stringResource(R.string.mp_b_sin_fragmentos, ia), style = Texto.Pequeno, color = Colores.Texto2)
                    Respuesta.Fallo -> {
                        Text(stringResource(R.string.mp_b_fallo_ia, ia), style = Texto.Pequeno, color = Colores.Texto2)
                        BotonSecundario(stringResource(R.string.mp_reintentar), Modifier.fillMaxWidth(), onPreguntar)
                    }
                }
            }
        }
    }
}

/** Tres puntos que respiran mientras piensa la IA. */
@Composable
private fun Puntos() {
    val reloj = rememberInfiniteTransition(label = "puntos")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(20.dp)) {
        for (i in 0 until 3) {
            val a by reloj.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(700, delayMillis = i * 180, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "punto$i",
            )
            Box(Modifier.size(7.dp).clip(Formas.Pastilla).background(Colores.Acento.copy(alpha = a)))
        }
    }
}

/** Un momento encontrado: app, cuándo, el fragmento con lo buscado resaltado y la miniatura, si la hay. */
@Composable
private fun TarjetaRecuerdo(r: Recuerdo, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val lado = with(LocalDensity.current) { 22.dp.roundToPx() }
    val icono by produceState<ImageBitmap?>(null, r.app) {
        value = withContext(Dispatchers.IO) { runCatching { ctx.packageManager.getApplicationIcon(r.app).toBitmap(lado, lado).asImageBitmap() }.getOrNull() }
    }
    val miniatura = remember(r.id) { r.miniatura?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() } }
    val acento = Colores.Acento
    val fragmento = remember(r.fragmento, acento) { resaltar(r.fragmento, acento) }
    Row(
        Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).pulsable(onClick).padding(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(Colores.Superficie2)) {
                    icono?.let { Image(it, null, Modifier.size(22.dp)) }
                }
                Spacer(Modifier.width(8.dp))
                // Si no cabe, se acorta el nombre de la app; la hora se ve siempre.
                Text(
                    MemoriaPantallaPrivacidad.etiqueta(ctx, r.app),
                    style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Text(" · " + cuando(ctx, r.fecha), style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, softWrap = false)
                if (r.porImagen) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.mp_b_por_imagen), style = Texto.Etiqueta.copy(fontSize = 10.sp), color = Colores.Acento,
                        modifier = Modifier.clip(Formas.Pastilla).background(Colores.AcentoSuave).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(fragmento, style = Texto.Cuerpo.copy(fontSize = 14.sp, lineHeight = 21.sp), color = Colores.Texto2, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (miniatura != null) {
            Spacer(Modifier.width(12.dp))
            Image(
                miniatura, null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 48.dp, height = 86.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Colores.Borde, RoundedCornerShape(8.dp)),
            )
        }
    }
}

/** Lo encontrado (entre \u0001 y \u0002) en el color del acento. */
private fun resaltar(s: String, color: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var dentro = false
    val limpio = s.replace('\n', ' ')
    var i = 0
    while (i < limpio.length) {
        when (val ch = limpio[i]) {
            '\u0001' -> dentro = true
            '\u0002' -> dentro = false
            else -> if (dentro) withStyle(SpanStyle(color = color, fontWeight = FontWeight.SemiBold)) { append(ch) } else append(ch)
        }
        i++
    }
}

/** «Hoy · 14:32», «Ayer · 9:05», «martes · 18:40», «4 nov · 11:12». */
private fun cuando(ctx: Context, ms: Long): String {
    val hora = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(ms))
    val zona = ZoneId.systemDefault()
    val diaEl = java.time.Instant.ofEpochMilli(ms).atZone(zona).toLocalDate()
    val dias = java.time.temporal.ChronoUnit.DAYS.between(diaEl, LocalDate.now(zona))
    val dia = when (dias) {
        0L -> ctx.getString(R.string.mp_b_hoy)
        1L -> ctx.getString(R.string.mp_b_ayer)
        in 2L..6L -> diaEl.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() }
        else -> DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
    }
    return ctx.getString(R.string.mp_b_cuando, dia, hora)
}

/** Abre la app donde se vio (no se puede volver a esa pantalla exacta: se abre la app). */
private fun abrirApp(ctx: Context, app: String) {
    val i = ctx.packageManager.getLaunchIntentForPackage(app)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (i == null) {
        android.widget.Toast.makeText(ctx, ctx.getString(R.string.mp_b_no_abre), android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    runCatching { ctx.startActivity(i) }
}
