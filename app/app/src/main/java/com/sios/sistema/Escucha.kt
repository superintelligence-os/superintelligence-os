package com.sios.sistema

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import com.sios.ui.Colores
import java.util.Locale

/**
 * Dictado dentro de la hoja de Claude, sin la ventana de Google: se ve lo que va entendiendo y un
 * círculo que late con la voz. Escucha el oído del móvil (sin conexión: nada sale de él) si está
 * descargado y toca (siempre, o sin internet, o sin otro reconocedor); si no, el reconocedor del
 * sistema de otra app (el de Google, en la edición con Google).
 */
class Escucha(private val ctx: Context, private val alTerminar: (String) -> Unit) {
    var escuchando by mutableStateOf(false)
        private set
    var parcial by mutableStateOf("")
        private set
    var nivel by mutableFloatStateOf(0f) // 0..1, volumen de la voz
        private set
    /** ¿Escucha el oído del móvil? (para decir «sin conexión» mientras escucha). */
    var local by mutableStateOf(false)
        private set

    private var reconocedor: SpeechRecognizer? = null
    private var oido: com.sios.voz.OidoLocal? = null
    /** Idioma a escuchar (BCP-47, «en-GB»…); si no, el del móvil. */
    var idioma: String? = null

    val disponible get() = com.sios.voz.Dictado.usarLocal(ctx, idioma) != null

    fun empezar() {
        if (escuchando) return
        when (com.sios.voz.Dictado.usarLocal(ctx, idioma)) {
            true -> empezarLocal()
            false -> empezarSistema()
            // Ni del móvil ni del sistema: a Ajustes, a descargar el dictado sin conexión.
            null -> {
                android.widget.Toast.makeText(ctx, com.sios.R.string.voz_falta_dictado, android.widget.Toast.LENGTH_LONG).show()
                com.sios.voz.Dictado.abrirAjustes(ctx)
            }
        }
    }

    private fun empezarLocal() {
        parcial = ""
        escuchando = true
        local = true
        oido = com.sios.voz.OidoLocal(ctx, idioma, object : com.sios.voz.OidoLocal.Oyente {
            override fun volumen(db: Float, nivel: Float) { this@Escucha.nivel = nivel }
            override fun parcial(texto: String) { if (escuchando) parcial = texto }
            override fun terminaVoz() { nivel = 0f }
            override fun resultado(texto: String) = terminar(texto)
            override fun error(codigo: Int) = terminar("")
        }).also { it.empezar() }
    }

    private fun empezarSistema() {
        val otro = com.sios.voz.Dictado.reconocedorExterno(ctx) ?: return
        val r = reconocedor ?: SpeechRecognizer.createSpeechRecognizer(ctx, otro).also { reconocedor = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { nivel = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { nivel = 0f }
            override fun onError(e: Int) { terminar("") }
            override fun onEvent(t: Int, p: Bundle?) {}
            override fun onPartialResults(p: Bundle?) {
                p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { parcial = it }
            }
            override fun onResults(p: Bundle?) {
                terminar(p?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: parcial)
            }
        })
        parcial = ""
        escuchando = true
        local = false
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma ?: Locale.getDefault().toLanguageTag())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true),
        )
    }

    /** Parar a mano: se usa lo entendido hasta ahora. */
    fun parar() {
        if (!escuchando) return
        oido?.parar()
        reconocedor?.stopListening()
    }

    private fun terminar(texto: String) {
        if (!escuchando) return
        escuchando = false
        nivel = 0f
        parcial = ""
        oido = null
        if (texto.isNotBlank()) alTerminar(texto)
    }

    fun liberar() {
        oido?.cancelar()
        oido = null
        reconocedor?.destroy()
        reconocedor = null
        escuchando = false
    }
}

/** Círculo naranja que respira con la voz (halo suave alrededor y núcleo). */
@Composable
fun OrbeVoz(nivel: Float, tam: Dp) {
    val n by animateFloatAsState(nivel, spring(dampingRatio = 0.6f, stiffness = 300f), label = "voz")
    Canvas(Modifier.size(tam)) {
        val r = size.minDimension / 2
        drawCircle(
            Brush.radialGradient(listOf(Colores.Acento.copy(alpha = 0.55f), Colores.Acento.copy(alpha = 0f)), center, r),
            radius = r * (0.7f + 0.3f * n),
        )
        drawCircle(
            Brush.radialGradient(listOf(Colores.Texto, Colores.Acento), center + Offset(-r * 0.12f, -r * 0.12f), r * 0.6f),
            radius = r * (0.42f + 0.12f * n),
        )
    }
}
