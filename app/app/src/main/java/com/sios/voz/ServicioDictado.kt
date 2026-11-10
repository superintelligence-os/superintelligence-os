package com.sios.voz

import android.content.Context
import android.content.ContextParams
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * El dictado de SIOS para las demás apps (el «servicio de reconocimiento de voz» del sistema). SIOS es
 * el asistente y Android pone este como reconocedor de serie: cualquier app que use el dictado del
 * sistema dicta con él.
 *
 * Si hay un modelo del móvil para ese idioma (y toca: siempre, o sin internet), se dicta aquí mismo y
 * nada sale del móvil; el micrófono se graba a nombre de la app que lo pidió (su aviso de privacidad).
 * Si no, se pasa al reconocedor de otra app (el de Google, en la edición con Google) tal cual.
 */
open class ServicioDictado : RecognitionService() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private var oido: OidoLocal? = null
    private var puente: SpeechRecognizer? = null

    override fun onStartListening(intent: Intent?, cb: Callback?) {
        cb ?: return
        parar(cancelar = true)
        val idioma = intent?.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
        val parciales = intent?.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false) == true
        val silencio = intent?.getLongExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0L)?.takeIf { it in 500..10_000 } ?: 1400L
        when (Dictado.usarLocal(this, idioma)) {
            true -> local(cb, idioma, parciales, silencio)
            false -> pasar(cb, intent)
            null -> seguro { cb.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
        }
    }

    /** La app que dicta puede haberse ido: sus avisos fallan (RemoteException) y no deben tumbar SIOS. */
    private fun seguro(b: () -> Unit) { runCatching(b) }

    private fun local(cb: Callback, idioma: String?, parciales: Boolean, silencio: Long) {
        // Se graba a nombre de la app que dicta (Android 12+): su icono sale en el aviso del micrófono.
        val audio = runCatching {
            createContext(ContextParams.Builder().setNextAttributionSource(cb.callingAttributionSource).build())
        }.getOrDefault(this)
        oido = OidoLocal(
            this, idioma,
            object : OidoLocal.Oyente {
                override fun listo() = seguro { cb.readyForSpeech(Bundle()) }
                override fun empiezaVoz() = seguro { cb.beginningOfSpeech() }
                override fun volumen(db: Float, nivel: Float) = seguro { cb.rmsChanged(-2f + nivel * 12f) }
                override fun parcial(texto: String) = seguro { cb.partialResults(lista(texto)) }
                override fun terminaVoz() = seguro { cb.endOfSpeech() }
                override fun resultado(texto: String) { oido = null; seguro { cb.results(lista(texto)) } }
                override fun error(codigo: Int) { oido = null; seguro { cb.error(codigo) } }
            },
            contextoAudio = audio, silencioFin = silencio, parciales = parciales,
        ).also { it.empezar() }
    }

    private fun lista(texto: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(texto))
        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.9f))
    }

    /** Sin modelo del móvil (o con internet en automático): el reconocedor de otra app, sin tocar nada. */
    private fun pasar(cb: Callback, intent: Intent?) {
        val otro = Dictado.reconocedorExterno(this) ?: return seguro { cb.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }
        val r = runCatching { SpeechRecognizer.createSpeechRecognizer(this, otro) }.getOrNull() ?: return seguro { cb.error(SpeechRecognizer.ERROR_CLIENT) }
        puente = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) = seguro { cb.readyForSpeech(p ?: Bundle()) }
            override fun onBeginningOfSpeech() = seguro { cb.beginningOfSpeech() }
            override fun onRmsChanged(rms: Float) = seguro { cb.rmsChanged(rms) }
            override fun onBufferReceived(b: ByteArray?) { if (b != null) seguro { cb.bufferReceived(b) } }
            override fun onEndOfSpeech() = seguro { cb.endOfSpeech() }
            override fun onError(e: Int) { soltarPuente(); seguro { cb.error(e) } }
            override fun onResults(p: Bundle?) { soltarPuente(); seguro { cb.results(p ?: Bundle()) } }
            override fun onPartialResults(p: Bundle?) = seguro { cb.partialResults(p ?: Bundle()) }
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        r.startListening(intent ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
    }

    private fun soltarPuente() {
        val r = puente ?: return
        puente = null
        android.os.Handler(mainLooper).post { runCatching { r.destroy() } }
    }

    private fun parar(cancelar: Boolean) {
        oido?.let { if (cancelar) it.cancelar() else it.parar() }
        if (cancelar) oido = null
        puente?.let { if (cancelar) { runCatching { it.cancel() }; soltarPuente() } else runCatching { it.stopListening() } }
    }

    override fun onStopListening(cb: Callback?) = parar(cancelar = false)
    override fun onCancel(cb: Callback?) = parar(cancelar = true)

    override fun onDestroy() {
        parar(cancelar = true)
        super.onDestroy()
    }

    /** Qué idiomas se pueden dictar en el móvil ya mismo y cuáles tras descargarlos (Android 13+). */
    @androidx.annotation.RequiresApi(33)
    override fun onCheckRecognitionSupport(intent: Intent, cb: SupportCallback) {
        val preciso = ModelosVoz.listo(this, ModelosVoz.WHISPER_SMALL)
        val instalados = Dictado.IDIOMAS_PRECISO.filter { l -> preciso || ModelosVoz.ligero(l)?.let { ModelosVoz.listo(this, it) } == true }
        val posibles = Dictado.IDIOMAS_PRECISO - instalados.toSet()
        runCatching {
            cb.onSupportResult(
                RecognitionSupport.Builder()
                    .setInstalledOnDeviceLanguages(instalados)
                    .setSupportedOnDeviceLanguages(posibles)
                    .build(),
            )
        }
    }
}
