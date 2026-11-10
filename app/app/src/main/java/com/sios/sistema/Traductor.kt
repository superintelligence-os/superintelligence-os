package com.sios.sistema

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

/**
 * Traductor de conversación cara a cara: el móvil en la mesa, la mitad de arriba girada para la otra
 * persona. Cada uno toca su micrófono y habla en su idioma; la traducción sale grande en la mitad del
 * otro y se dice en voz alta en su idioma.
 */
class TraductorActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        val otro = intent.getStringExtra("idioma") ?: "en-GB"
        setContent { TemaSios { PantallaTraductor(otro, onCerrar = ::finish) } }
    }
}

/** Idiomas: código para el dictado y la voz, y su nombre en ese idioma (para que lo lea el otro). */
private val IDIOMAS = listOf(
    "es-ES" to "Español", "en-GB" to "English", "fr-FR" to "Français", "de-DE" to "Deutsch", "it-IT" to "Italiano", "pt-PT" to "Português",
    "nl-NL" to "Nederlands", "pl-PL" to "Polski", "ro-RO" to "Română", "ar-MA" to "العربية", "zh-CN" to "中文", "ja-JP" to "日本語", "ru-RU" to "Русский", "uk-UA" to "Українська",
)

/** «Toca el micrófono y habla», en el idioma de cada mitad (lo lee esa persona). */
private val PISTA = mapOf(
    "es" to "Toca el micrófono y habla", "en" to "Tap the mic and speak", "fr" to "Touche le micro et parle", "de" to "Tippe aufs Mikro und sprich",
    "it" to "Tocca il microfono e parla", "pt" to "Toca no microfone e fala", "nl" to "Tik op de microfoon en spreek", "pl" to "Dotknij mikrofonu i mów",
    "ro" to "Atinge microfonul și vorbește", "ar" to "المس الميكروفون وتحدّث", "zh" to "点按麦克风开始说话", "ja" to "マイクをタップして話してください",
    "ru" to "Нажмите на микрофон и говорите", "uk" to "Торкніться мікрофона й говоріть",
)

private class Lado(idiomaInicial: String) {
    var idioma by mutableStateOf(idiomaInicial)
    var dicho by mutableStateOf("") // lo que dijo esta persona (en su idioma)
    var recibido by mutableStateOf("") // lo que le llega del otro, ya traducido
}

private val http by lazy { okhttp3.OkHttpClient.Builder().callTimeout(40, java.util.concurrent.TimeUnit.SECONDS).build() }

private suspend fun traducir(texto: String, de: String, a: String): String? = withContext(Dispatchers.IO) {
    runCatching {
        val r = okhttp3.Request.Builder().url("http://127.0.0.1:8765/traducir")
            .post(JSONObject().put("texto", texto).put("de", de).put("a", a).toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(r).execute().use { JSONObject(it.body.string()).optString("texto").takeIf { t -> t.isNotBlank() } }
    }.getOrNull()
}

/**
 * Traduce como toca: en el móvil (siempre, o sin internet o sin IA) o con la IA; si la IA falla y los
 * idiomas están descargados, en el móvil. null si no se ha podido.
 */
private suspend fun traducirComoToque(ctx: android.content.Context, texto: String, de: String, a: String, iaLista: Boolean): String? {
    val local = com.sios.voz.TraduccionLocal
    suspend fun enElMovil() = withContext(Dispatchers.Default) { local.traducir(ctx, texto, de, a) }
    return when (local.usar(ctx, de, a, iaLista)) {
        true -> enElMovil()
        false -> traducir(texto, de, a) ?: if (local.disponible(ctx, de, a)) enElMovil() else null
        null -> null
    }
}

@Composable
private fun PantallaTraductor(otroIdioma: String, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    val yo = remember { Lado(Locale.getDefault().let { if (it.language == "es") "es-ES" else it.toLanguageTag() }) }
    val otro = remember { Lado(otroIdioma) }
    var traduciendo by remember { mutableStateOf(false) }
    var hablando by remember { mutableStateOf<Lado?>(null) }
    // Sin conexión: con los idiomas descargados, se traduce en el móvil (siempre, o sin internet o sin IA).
    val estadoIa by com.sios.datos.Servicio.estado.collectAsStateWithLifecycle()
    val iaLista = estadoIa.conectado && !com.sios.ui.Marca.sinElegir
    val cambiosModelos by com.sios.voz.ModelosVoz.cambios.collectAsStateWithLifecycle()
    var red by remember { mutableStateOf(com.sios.voz.Dictado.hayInternet(ctx)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { red = com.sios.voz.Dictado.hayInternet(ctx); onPauseOrDispose { } }
    val sinConexion = remember(yo.idioma, otro.idioma, iaLista, cambiosModelos, red, traduciendo) {
        com.sios.voz.TraduccionLocal.usar(ctx, yo.idioma, otro.idioma, iaLista)
    }
    val escucha = remember {
        Escucha(ctx) { texto ->
            val de = hablando ?: return@Escucha
            val para = if (de === yo) otro else yo
            de.dicho = texto
            traduciendo = true
            alcance.launch {
                val t = traducirComoToque(ctx, texto, de.idioma, para.idioma, com.sios.datos.Servicio.estado.value.conectado && !com.sios.ui.Marca.sinElegir)
                traduciendo = false
                para.recibido = t ?: ctx.getString(if (com.sios.voz.TraduccionLocal.falta(ctx, de.idioma, para.idioma).isNotEmpty()) R.string.voz_trad_falta else R.string.trad_error)
                if (t != null) Voz.decir(ctx, t, idioma = Locale.forLanguageTag(para.idioma))
            }
        }
    }
    DisposableEffect(Unit) { onDispose { escucha.liberar(); Voz.callar() } }
    fun hablar(l: Lado) {
        if (escucha.escuchando) { escucha.parar(); return }
        Voz.callar()
        hablando = l
        escucha.idioma = l.idioma
        escucha.empezar()
    }
    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding()) {
        // La otra persona: su mitad, girada.
        MitadTraductor(otro, escucha.escuchando && hablando === otro, if (hablando === otro) escucha.parcial else "", traduciendo && hablando === yo, Modifier.weight(1f).rotate(180f), onHablar = { hablar(otro) }, onIdioma = { otro.idioma = siguiente(otro.idioma); otro.recibido = ""; otro.dicho = "" })
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            // La cruz sigue en el centro; el sello «Sin conexión» se apoya en la línea, a su izquierda.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.Borde))
                // Sin conexión: se dice (nada sale del móvil). Sin IA ni idiomas descargados: a descargarlos.
                androidx.compose.animation.AnimatedVisibility(
                    sinConexion != false,
                    enter = fadeIn(Muelle.suave()) + androidx.compose.animation.scaleIn(Muelle.suave(), initialScale = 0.9f),
                    exit = fadeOut(Muelle.suave()) + androidx.compose.animation.scaleOut(Muelle.suave(), targetScale = 0.9f),
                ) {
                    com.sios.voz.SelloSinConexion(
                        descargar = sinConexion == null,
                        modifier = Modifier.background(Colores.Fondo).padding(start = 8.dp),
                    ) { com.sios.voz.Dictado.abrirAjustes(ctx, "traduccion") }
                }
            }
            Box(Modifier.padding(horizontal = 12.dp).size(40.dp).clip(Formas.Pastilla).background(Colores.Superficie).pulsable(onCerrar), contentAlignment = Alignment.Center) {
                Icon(Iconos.Cruz, stringResource(R.string.comun_cerrar), tint = Colores.Texto2, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.weight(1f).height(1.dp).background(Colores.Borde))
        }
        MitadTraductor(yo, escucha.escuchando && hablando === yo, if (hablando === yo) escucha.parcial else "", traduciendo && hablando === otro, Modifier.weight(1f), onHablar = { hablar(yo) }, onIdioma = { yo.idioma = siguiente(yo.idioma); yo.recibido = ""; yo.dicho = "" })
    }
}

private fun siguiente(i: String) = IDIOMAS[(IDIOMAS.indexOfFirst { it.first == i } + 1).mod(IDIOMAS.size)].first

@Composable
private fun MitadTraductor(l: Lado, escuchando: Boolean, parcial: String, esperando: Boolean, modifier: Modifier, onHablar: () -> Unit, onIdioma: () -> Unit) {
    val nombre = IDIOMAS.firstOrNull { it.first == l.idioma }?.second ?: l.idioma
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.height(36.dp).clip(Formas.Pastilla).background(Colores.Superficie).pulsable(onIdioma).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                Text(nombre, style = Texto.Pequeno, color = Colores.Texto)
            }
            Spacer(Modifier.weight(1f))
            if (esperando) Text("…", style = Texto.Subtitulo, color = Colores.Texto3)
        }
        // Lo que te dicen (grande) y, debajo, lo que dijiste tú.
        val pista = PISTA[l.idioma.substringBefore('-')] ?: PISTA.getValue("en")
        AnimatedContent(
            if (escuchando) parcial.ifBlank { "…" } else l.recibido.ifBlank { pista },
            transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "traduccion",
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
        ) { t ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Text(
                    t, style = Texto.Titulo.copy(fontSize = if (t.length > 120) 22.sp else 28.sp, lineHeight = if (t.length > 120) 30.sp else 36.sp),
                    color = if (escuchando || t == pista) Colores.Texto3 else Colores.Texto,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(l.dicho, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(72.dp).clip(Formas.Pastilla).background(if (escuchando) Colores.Acento else Colores.Superficie2).pulsable(onHablar),
                contentAlignment = Alignment.Center,
            ) { Icon(Iconos.Micro, null, tint = if (escuchando) Colores.SobreAcento else Colores.Texto, modifier = Modifier.size(30.dp)) }
        }
    }
}
