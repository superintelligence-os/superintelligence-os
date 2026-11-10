package com.sios.sistema

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.datos.Servicio
import com.sios.ui.BotonIcono
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable

/**
 * Contestar un mensaje sin abrir su app: el último mensaje, tres respuestas que propone Claude y
 * un campo para escribir la tuya. Se envía con la respuesta rápida de la propia notificación.
 */
class ResponderActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val clave = intent.getStringExtra(EXTRA_CLAVE)
        val aviso = clave?.let { Bandeja.avisos[it] ?: Bandeja.recientes.lastOrNull { a -> a.clave == it } }
        if (aviso == null || aviso.responder == null) return finish()
        setContent { TemaSios { HojaResponder(aviso) { finish() } } }
    }

    override fun finish() {
        super.finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    companion object {
        private const val EXTRA_CLAVE = "clave"

        fun intent(ctx: Context, clave: String): PendingIntent = PendingIntent.getActivity(
            ctx, clave.hashCode(),
            Intent(ctx, ResponderActivity::class.java).putExtra(EXTRA_CLAVE, clave)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

@Composable
private fun HojaResponder(aviso: Bandeja.Aviso, onCerrar: () -> Unit) {
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    var texto by remember { mutableStateOf("") }
    var propuestas by remember { mutableStateOf<List<String>?>(null) }
    var enviado by remember { mutableStateOf(false) }
    val contexto = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        val r = Servicio.rapido(
            "Conversación con ${aviso.titulo}:\n${aviso.historial}",
            "Propón exactamente 3 respuestas cortas y naturales (máximo 8 palabras cada una) al último mensaje de esta conversación, " +
                "en su idioma y con tono cercano: una afirmativa, una negativa o de aplazar, y otra útil. " +
                "Ten en cuenta toda la conversación: no preguntes lo que ya se ha dicho. " +
                "Una por línea, sin numerar, sin comillas ni emojis de más.",
        )
        propuestas = r?.lines()?.map { it.trim().trim('-', '•', '"', ' ') }?.filter { it.isNotBlank() }?.take(3).orEmpty()
    }
    fun cerrar() { visible.targetState = false }
    LaunchedEffect(visible.currentState, visible.targetState) {
        if (!visible.targetState && !visible.currentState) onCerrar()
    }
    fun enviar(t: String) {
        if (t.isBlank() || enviado) return
        enviado = Bandeja.instancia?.responder(aviso.clave, t.trim()) == true
        if (enviado) {
            Servicio.registrarActividad(contexto.getString(R.string.bandeja_enviado, aviso.titulo), "${aviso.app}: ${t.trim()}")
            android.widget.Toast.makeText(contexto, contexto.getString(R.string.bandeja_enviado, aviso.titulo), android.widget.Toast.LENGTH_SHORT).show()
            cerrar()
        }
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { cerrar() } }, contentAlignment = Alignment.BottomCenter) {
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
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(Formas.Pastilla).background(Colores.BordeFuerte))
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.bandeja_responder_titulo, aviso.titulo, aviso.app), style = Texto.Subtitulo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                // El mensaje, como burbuja recibida
                Text(
                    aviso.historial.lines().takeLast(4).joinToString("\n"),
                    style = Texto.Cuerpo, color = Colores.Texto, maxLines = 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp))
                        .background(Colores.Superficie)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.bandeja_sugerencias), style = Texto.Etiqueta, color = Colores.Texto3)
                }
                Spacer(Modifier.height(8.dp))
                AnimatedContent(propuestas, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "propuestas") { lista ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (lista == null) {
                            val pulso by rememberInfiniteTransition(label = "pulso").animateFloat(0.35f, 0.8f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
                            repeat(3) { Box(Modifier.fillMaxWidth().height(46.dp).alpha(pulso).clip(Formas.Pastilla).background(Colores.Superficie)) }
                        } else for (p in lista) {
                            // Todas del mismo alto y ancho: una por fila.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .clip(Formas.Pastilla)
                                    .background(Colores.Superficie)
                                    .border(1.dp, Colores.Borde, Formas.Pastilla)
                                    .pulsable { enviar(p) }
                                    .padding(horizontal = 18.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) { Text(p, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Formas.Pastilla)
                        .background(Colores.Superficie)
                        .border(1.dp, Colores.Borde, Formas.Pastilla)
                        .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = texto,
                        onValueChange = { texto = it },
                        textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
                        cursorBrush = SolidColor(Colores.Acento),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { enviar(texto) }),
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                        decorationBox = { campo ->
                            Box {
                                if (texto.isEmpty()) Text(stringResource(R.string.bandeja_escribe), style = Texto.Cuerpo, color = Colores.Texto3)
                                campo()
                            }
                        },
                    )
                    Spacer(Modifier.width(6.dp))
                    BotonIcono(
                        Iconos.Enviar, stringResource(R.string.comun_enviar), tam = 42.dp,
                        color = if (texto.isBlank()) Colores.Texto3 else Colores.SobreAcento,
                        fondo = if (texto.isBlank()) Colores.Superficie2 else Colores.Acento,
                    ) { enviar(texto) }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
