package com.sios.sistema

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.datos.Servicio
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Claude sobre el texto seleccionado en cualquier app (menú de selección de Android): mejorarlo,
 * traducirlo o preguntar por él. Si el campo se puede editar, el resultado lo sustituye con un toque.
 * Una sola actividad para las tres entradas del menú (alias con su propio nombre).
 */
class TextoActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val texto = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        val soloLectura = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, false)
        val accion = when (componentName.className.substringAfterLast('.')) {
            "TextoTraducir" -> Accion.Traducir
            "TextoPreguntar" -> Accion.Preguntar
            else -> Accion.Mejorar
        }
        if (texto.isBlank()) return finish()
        // Preguntar abre la hoja del asistente con el texto, para hablarlo con el agente completo.
        if (accion == Accion.Preguntar) {
            startActivity(
                Intent(this, AsistenteActivity::class.java)
                    .setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return finish()
        }
        setContent {
            TemaSios {
                HojaTexto(accion, texto, editable = !soloLectura, onCerrar = { resultado ->
                    if (resultado != null) setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, resultado))
                    finish()
                })
            }
        }
    }

    override fun finish() {
        super.finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}

private enum class Accion { Mejorar, Traducir, Preguntar }

private fun instrucciones(accion: Accion): String {
    val idioma = Locale.getDefault().getDisplayLanguage(Locale("es"))
    return when (accion) {
        Accion.Mejorar -> "Reescribe el texto que te da el usuario para que quede claro, natural y sin faltas, " +
            "en su mismo idioma y con su mismo tono y longitud aproximada. Devuelve solo el texto reescrito, sin comillas ni comentarios."
        else -> "Traduce el texto que te da el usuario al $idioma; si ya está en $idioma, tradúcelo al inglés. " +
            "Mantén el tono y el formato. Devuelve solo la traducción, sin comillas ni comentarios."
    }
}

@Composable
private fun HojaTexto(accion: Accion, original: String, editable: Boolean, onCerrar: (String?) -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    var resultado by remember { mutableStateOf<String?>(null) }
    var fallo by remember { mutableStateOf(false) }
    var elegido by remember { mutableStateOf<String?>(null) }
    var intento by remember { mutableStateOf(0) }
    val contexto = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(intento) {
        fallo = false
        resultado = null
        val r = Servicio.rapido(original, instrucciones(accion))
        if (r == null) fallo = true else resultado = r
    }
    fun cerrar(conResultado: String? = null) {
        elegido = conResultado
        visible.targetState = false
    }
    LaunchedEffect(visible.currentState, visible.targetState) {
        if (!visible.targetState && !visible.currentState) onCerrar(elegido)
    }

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { cerrar() } },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(Muelle.suave()) { it } + fadeIn(Muelle.suave()),
            exit = slideOutVertically(Muelle.suave()) { it } + fadeOut(Muelle.suave()),
        ) {
            val forma = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(forma)
                    .background(Colores.Fondo)
                    .border(1.dp, Colores.Borde, forma)
                    .pointerInput(Unit) { detectTapGestures { } }
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(Formas.Pastilla).background(Colores.BordeFuerte))
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val giro by rememberInfiniteTransition(label = "giro").animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "logo")
                    Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(20.dp).rotate(if (resultado == null && !fallo) giro else 0f))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(if (accion == Accion.Mejorar) R.string.texto_mejorar else R.string.texto_traducir),
                        style = Texto.Subtitulo, color = Colores.Texto,
                    )
                }
                Spacer(Modifier.height(14.dp))
                AnimatedContent(
                    targetState = when { fallo -> 2; resultado != null -> 1; else -> 0 },
                    transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) },
                    label = "resultado",
                ) { fase ->
                    when (fase) {
                        0 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Mientras piensa: barras que laten, con el ancho aproximado del texto.
                            val pulso by rememberInfiniteTransition(label = "pulso").animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
                            for (f in listOf(1f, 0.92f, 0.6f)) Box(Modifier.fillMaxWidth(f).height(14.dp).alpha(pulso).clip(Formas.Pastilla).background(Colores.Superficie2))
                        }
                        1 -> SelectionContainer {
                            Text(
                                resultado.orEmpty(), style = Texto.Cuerpo, color = Colores.Texto,
                                modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                        else -> Text(stringResource(R.string.texto_fallo), style = Texto.Cuerpo, color = Colores.Texto2)
                    }
                }
                Spacer(Modifier.height(18.dp))
                // Dos botones del mismo tamaño: el principal (sustituir o reintentar) y copiar/cerrar.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val listo = resultado != null
                    Boton(
                        stringResource(if (fallo) R.string.texto_reintentar else if (editable) R.string.texto_sustituir else R.string.texto_copiar),
                        principal = true, activo = listo || fallo, modifier = Modifier.weight(1f),
                    ) {
                        when {
                            fallo -> intento++
                            editable -> cerrar(resultado)
                            else -> { copiar(contexto, resultado.orEmpty()); cerrar() }
                        }
                    }
                    Boton(
                        stringResource(if (editable && listo) R.string.texto_copiar else R.string.comun_cerrar),
                        principal = false, activo = true, modifier = Modifier.weight(1f),
                    ) {
                        if (editable && listo) copiar(contexto, resultado.orEmpty())
                        cerrar()
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

private fun copiar(ctx: android.content.Context, t: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Claude", t))
}

@Composable
private fun Boton(texto: String, principal: Boolean, activo: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(48.dp)
            .alpha(if (activo) 1f else 0.45f)
            .clip(Formas.Pastilla)
            .background(if (principal) Colores.Acento else Colores.Superficie2)
            .pulsable { if (activo) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(texto, style = Texto.Subtitulo, color = if (principal) Colores.SobreAcento else Colores.Texto)
    }
}
