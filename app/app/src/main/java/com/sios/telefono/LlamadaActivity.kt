package com.sios.telefono

import android.os.Bundle
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.delay

/** Pantalla de llamada: entrante (contestar, colgar, que conteste Claude) y en curso. Sale también con el móvil bloqueado. */
class LlamadaActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        setContent { TemaSios { PantallaLlamada(onFin = ::finish) } }
    }
}

@Composable
private fun PantallaLlamada(onFin: () -> Unit) {
    val ctx = LocalContext.current
    val llamadas by ServicioLlamadas.llamadas.collectAsState()
    ServicioLlamadas.version.collectAsState().value // repintar con cada cambio de estado
    val audio by ServicioLlamadas.audio.collectAsState()
    val claude by Contestador.estado.collectAsState()
    val call = llamadas.firstOrNull { it.state != Call.STATE_DISCONNECTED }
    LaunchedEffect(call) { if (call == null) { delay(700); onFin() } }
    if (call == null) { Box(Modifier.fillMaxSize().background(Colores.Fondo)); return }

    val numero = ServicioLlamadas.numeroDe(call)
    val nombre = remember(numero) { ServicioLlamadas.nombreDe(ctx, numero) }
    val entrante = call.state == Call.STATE_RINGING
    var teclado by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        // Inicial del nombre en un círculo que late mientras suena.
        val latido by rememberInfiniteTransition(label = "l").animateFloat(1f, if (entrante) 1.08f else 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "l")
        Box(Modifier.size(104.dp).scale(latido).clip(Formas.Pastilla).background(Colores.Superficie2), contentAlignment = Alignment.Center) {
            // Contacto: su inicial. Número sin nombre: el icono del teléfono.
            val inicial = nombre.firstOrNull()?.takeIf { it.isLetter() }
            if (inicial != null) Text(inicial.uppercase(), fontSize = 40.sp, color = Colores.Texto, style = Texto.Titulo)
            else Icon(Iconos.Llamar, null, tint = Colores.Texto2, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text(nombre, style = Texto.Titulo.copy(fontSize = 28.sp), color = Colores.Texto, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(estadoTexto(call), style = Texto.Cuerpo, color = Colores.Texto2)

        // Lo que hace Claude cuando contesta por ti.
        AnimatedVisibility(claude != null, enter = fadeIn(Muelle.suave()), exit = fadeOut(Muelle.suave())) {
            claude?.let { TarjetaAsistente(it) }
        }

        Spacer(Modifier.weight(1f))
        AnimatedContent(Triple(entrante, claude != null, teclado), transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "botones") { (sonando, conIa, conTeclado) ->
            when {
                conIa -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Redondo(Iconos.Colgar, stringResource(R.string.tel_colgar), Colores.Rojo) { Contestador.colgar() }
                    Redondo(Iconos.Llamar, stringResource(R.string.tel_hablo_yo), Color(0xFF3FA36B)) { Contestador.pasarme() }
                }
                sonando -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Redondo(Iconos.Colgar, stringResource(R.string.tel_colgar), Colores.Rojo) { call.reject(false, null) }
                    Redondo(Iconos.Asistente, stringResource(R.string.tel_ia_contesta), Colores.Acento) { Contestador.empezar(ctx, call) }
                    Redondo(Iconos.Llamar, stringResource(R.string.tel_contestar), Color(0xFF3FA36B)) { call.answer(VideoProfile.STATE_AUDIO_ONLY) }
                }
                conTeclado -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Teclado(tam = 64.dp) { tecla -> call.playDtmfTone(tecla); call.stopDtmfTone() }
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Redondo(Iconos.Cruz, stringResource(R.string.tel_ocultar_teclado), Colores.Superficie2) { teclado = false }
                        Redondo(Iconos.Colgar, stringResource(R.string.tel_colgar), Colores.Rojo) { call.disconnect() }
                    }
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val mute = audio?.isMuted == true
                    val altavoz = audio?.route == CallAudioState.ROUTE_SPEAKER
                    val espera = call.state == Call.STATE_HOLDING
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        Opcion(Iconos.MicroNo, stringResource(R.string.tel_silencio), mute) { ServicioLlamadas.instancia?.silenciar(!mute) }
                        Opcion(Iconos.Teclado, stringResource(R.string.tel_teclado), false) { teclado = true }
                        Opcion(Iconos.Altavoz, stringResource(R.string.tel_altavoz), altavoz) { ServicioLlamadas.instancia?.altavoz(!altavoz) }
                        Opcion(Iconos.Pausa, stringResource(R.string.tel_espera), espera) { if (espera) call.unhold() else call.hold() }
                    }
                    Spacer(Modifier.height(36.dp))
                    Redondo(Iconos.Colgar, stringResource(R.string.tel_colgar), Colores.Rojo) { call.disconnect() }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun TarjetaAsistente(e: Contestador.Estado) {
    Column(
        Modifier.padding(top = 24.dp).fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(when (e.fase) {
                    Contestador.Fase.Saludando -> R.string.tel_ia_saluda
                    Contestador.Fase.Escuchando -> R.string.tel_ia_escucha
                    Contestador.Fase.Resumiendo -> R.string.tel_ia_resume
                    Contestador.Fase.Listo -> R.string.tel_ia_listo
                    Contestador.Fase.Error -> R.string.tel_ia_error
                }),
                style = Texto.Etiqueta, color = Colores.Texto2,
            )
        }
        e.resumen?.let { Text(it, style = Texto.Subtitulo, color = Colores.Texto) }
        if (e.transcripcion.isNotBlank()) Text("«${e.transcripcion}»", style = Texto.Pequeno, color = Colores.Texto3, maxLines = 4)
    }
}

/** Botón redondo grande (contestar, colgar…), todos del mismo tamaño, con su texto debajo. */
@Composable
private fun Redondo(icono: ImageVector, texto: String, color: Color, tam: Dp = 72.dp, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Box(Modifier.size(tam).clip(Formas.Pastilla).background(color).pulsable(onClick), contentAlignment = Alignment.Center) {
            Icon(icono, texto, tint = Colores.SobreAcento, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(texto, style = Texto.Pequeno, color = Colores.Texto2, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** Opción de la llamada en curso (silencio, altavoz…): se ilumina en naranja si está activa. */
@Composable
private fun Opcion(icono: ImageVector, texto: String, activa: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(
            Modifier.size(60.dp).clip(Formas.Pastilla).background(if (activa) Colores.Acento else Colores.Superficie).pulsable(onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icono, texto, tint = if (activa) Colores.SobreAcento else Colores.Texto, modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.height(8.dp))
        Text(texto, style = Texto.Pequeno, color = Colores.Texto2, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun estadoTexto(call: Call): String {
    var ahora by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); ahora = System.currentTimeMillis() } }
    return when (call.state) {
        Call.STATE_RINGING -> stringResource(R.string.tel_entrante)
        Call.STATE_DIALING, Call.STATE_CONNECTING -> stringResource(R.string.tel_llamando)
        Call.STATE_HOLDING -> stringResource(R.string.tel_en_espera)
        Call.STATE_ACTIVE -> {
            val s = ((ahora - call.details.connectTimeMillis) / 1000).coerceAtLeast(0)
            "%d:%02d".format(s / 60, s % 60)
        }
        else -> stringResource(R.string.tel_terminada)
    }
}

/** Teclado numérico de 12 teclas del mismo tamaño (marcador y tonos durante la llamada). */
@Composable
fun Teclado(tam: Dp = 72.dp, onTecla: (Char) -> Unit) {
    val filas = listOf("123", "456", "789", "*0#")
    val letras = mapOf('2' to "ABC", '3' to "DEF", '4' to "GHI", '5' to "JKL", '6' to "MNO", '7' to "PQRS", '8' to "TUV", '9' to "WXYZ", '0' to "+")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (f in filas) Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            for (c in f) Box(
                Modifier.size(tam).clip(Formas.Pastilla).background(Colores.Superficie).pulsable { onTecla(c) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(c.toString(), fontSize = 26.sp, color = Colores.Texto, style = Texto.Titulo)
                    letras[c]?.let { Text(it, fontSize = 9.sp, color = Colores.Texto3, style = Texto.Etiqueta) }
                }
            }
        }
    }
}
