package com.sios.voz

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors

/**
 * El oído del móvil: graba del micrófono, nota cuándo se empieza y se deja de hablar, y lo pasa a
 * texto con Whisper (preciso) o Vosk (ligero). Nada sale del móvil.
 *
 * Con Whisper, mientras se habla se van haciendo transcripciones provisionales (si el móvil es lo
 * bastante rápido) y al callarse, la buena. Con Vosk, el texto sale palabra a palabra.
 * Todos los avisos llegan en el hilo principal.
 */
internal class OidoLocal(
    private val ctx: Context,
    idioma: String?,
    private val oyente: Oyente,
    /** El contexto con el que se graba (el de la app que pide el dictado, para los avisos de privacidad). */
    private val contextoAudio: Context = ctx,
    /** Silencio tras la voz para dar por terminada la frase. */
    private val silencioFin: Long = 1400,
    private val parciales: Boolean = true,
) {
    interface Oyente {
        fun listo() {}
        fun empiezaVoz() {}
        /** [db]: decibelios (para el RecognitionService); [nivel]: 0..1, para el orbe. */
        fun volumen(db: Float, nivel: Float) {}
        fun parcial(texto: String) {}
        fun terminaVoz() {}
        fun resultado(texto: String)
        /** Códigos de [SpeechRecognizer] (ERROR_NO_MATCH, ERROR_AUDIO…). */
        fun error(codigo: Int)
    }

    private val lengua = Dictado.base(idioma)
    /** Idioma forzado (si lo pidió quien dicta); si no, Whisper lo detecta, con preferencia por el del móvil. */
    private val forzado = idioma != null
    private val motor = Dictado.motorLocal(ctx, idioma)
    private val principal = Handler(Looper.getMainLooper())
    @Volatile private var parado = false
    @Volatile private var cancelado = false
    private var hilo: Thread? = null
    private val trabajo = Executors.newSingleThreadExecutor { r -> Thread(r, "voz-oido").apply { priority = Thread.NORM_PRIORITY + 1 } }

    val esPreciso get() = motor == Dictado.Motor.PRECISO

    fun empezar() {
        if (motor == null) { trabajo.shutdown(); avisar { oyente.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) }; return }
        // El modelo se carga mientras ya se graba (la primera vez tarda un poco): no se pierde nada.
        trabajo.execute { MotoresLocales.usando { if (esPreciso) MotoresLocales.whisper(ctx) else MotoresLocales.vosk(ctx, lengua) } }
        hilo = Thread({ grabar() }, "voz-micro").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    /** Se deja de grabar y se escribe lo dicho hasta ahora. */
    fun parar() { parado = true }

    /** Se deja de grabar y no se escribe nada. */
    fun cancelar() {
        cancelado = true
        parado = true
        whisperEnCurso?.interrumpir()
    }

    private fun avisar(b: () -> Unit) { principal.post { if (!cancelado) b() } }

    // ---------- Grabación y voz ----------

    private val audio = Muestras()
    @Volatile private var whisperEnCurso: com.sios.voz.motor.Whisper? = null

    @SuppressLint("MissingPermission")
    private fun grabar() {
        val tasa = 16000
        val marco = 320 // 20 ms
        val minimo = AudioRecord.getMinBufferSize(tasa, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val grabadora = runCatching {
            AudioRecord.Builder()
                .setContext(contextoAudio)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(tasa).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimo, tasa * 4)) // dos segundos de margen
                .build()
        }.getOrNull()
        if (grabadora == null || grabadora.state != AudioRecord.STATE_INITIALIZED) {
            grabadora?.release()
            trabajo.shutdown()
            avisar { oyente.error(SpeechRecognizer.ERROR_AUDIO) }
            return
        }
        val reconocedorVosk = if (!esPreciso) VoskEnVivo() else null
        val buf = ShortArray(marco)
        val voz = com.sios.voz.motor.DetectorVoz(silencioFinMs = silencioFin.toInt())
        var marcos = 0
        var ultimoProvisional = 0
        try {
            grabadora.startRecording()
            avisar { oyente.listo() }
            bucle@ while (!parado) {
                val n = grabadora.read(buf, 0, marco)
                if (n <= 0) { if (n < 0) throw IllegalStateException("AudioRecord $n"); continue }
                marcos++
                audio.anadir(buf, n)
                reconocedorVosk?.alimentar(buf, n)
                val evento = voz.marco(buf, n)
                val db = voz.db
                val nivel = voz.nivel
                avisar { oyente.volumen(db, nivel) }
                when (evento) {
                    com.sios.voz.motor.DetectorVoz.Evento.EMPIEZA -> avisar { oyente.empiezaVoz() }
                    com.sios.voz.motor.DetectorVoz.Evento.NADA -> Unit
                    else -> break@bucle // terminó la frase, nadie habla o ya es mucho
                }
                // Provisionales de Whisper: cada segundo nuevo de voz, si el anterior ya acabó.
                // Mejor en una pausa entre palabras (cortar a media palabra le hace inventar); si no llega, a los 2,5 s.
                val nuevo = audio.tamano - ultimoProvisional
                if (voz.hablando && esPreciso && parciales && provisionales && !ocupado && (nuevo >= 16000 && voz.enPausa || nuevo >= 40000)) {
                    ultimoProvisional = audio.tamano
                    provisional(audio.copia(voz.inicioVoz, audio.tamano))
                }
                reconocedorVosk?.parcial()?.let { p -> if (parciales && p.isNotBlank()) avisar { oyente.parcial(p) } }
            }
        } catch (e: Exception) {
            android.util.Log.w("SistemaSios", "Oído del móvil", e)
            runCatching { grabadora.stop() }
            grabadora.release()
            reconocedorVosk?.cerrar()
            trabajo.shutdown()
            avisar { oyente.error(SpeechRecognizer.ERROR_AUDIO) }
            return
        }
        runCatching { grabadora.stop() }
        grabadora.release()
        avisar { oyente.terminaVoz() }
        val hablando = voz.hablando
        if (cancelado) { reconocedorVosk?.cerrar(); trabajo.shutdown(); return }
        if (!hablando && (marcos < 50 || !parado)) { // nadie ha hablado (o apenas un segundo): nada
            reconocedorVosk?.cerrar()
            trabajo.shutdown()
            avisar { oyente.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
            return
        }
        // Lo bueno: con 0,3 s de margen tras la última palabra.
        val desde = if (hablando) voz.inicioVoz else 0
        val hasta = if (hablando) voz.finConMargen(audio.tamano) else audio.tamano
        val muestras = audio.copia(desde, hasta)
        trabajo.execute {
            val texto = runCatching {
                MotoresLocales.usando {
                    if (esPreciso) transcribir(muestras, provisional = false) else reconocedorVosk?.final()
                }
            }.onFailure { android.util.Log.w("SistemaSios", "Oído del móvil: transcribir", it) }.getOrNull()
            reconocedorVosk?.cerrar()
            when {
                cancelado -> Unit
                texto == null -> avisar { oyente.error(SpeechRecognizer.ERROR_CLIENT) }
                texto.isBlank() -> avisar { oyente.error(SpeechRecognizer.ERROR_NO_MATCH) }
                else -> avisar { oyente.resultado(texto) }
            }
            trabajo.shutdown()
        }
    }

    // ---------- Whisper ----------

    @Volatile private var ocupado = false
    @Volatile private var provisionales = true
    /** El idioma que detectó la primera provisional: las siguientes (y la buena) ya no lo buscan. */
    @Volatile private var detectado: String? = null

    private fun provisional(muestras: FloatArray) {
        ocupado = true
        trabajo.execute {
            val t = SystemClock.uptimeMillis()
            val texto = runCatching { MotoresLocales.usando { transcribir(muestras, provisional = true) } }.getOrNull()
            // Si el móvil tarda mucho, no se hacen más (retrasarían la buena).
            if (SystemClock.uptimeMillis() - t > 1800) provisionales = false
            ocupado = false
            if (!texto.isNullOrBlank() && !parado) avisar { oyente.parcial(texto) }
        }
    }

    private fun transcribir(muestras: FloatArray, provisional: Boolean): String? {
        val w = MotoresLocales.whisper(ctx) ?: return null
        whisperEnCurso = w
        try {
            val idioma = if (forzado) lengua else detectado
            // Al detectar, el del móvil va primero entre los posibles y, si duda, gana.
            val posibles = (listOf(lengua) + com.sios.voz.motor.Whisper.SEIS).distinct()
            val r = w.transcribir(muestras, idioma, posibles, preferido = lengua, maxPiezas = if (provisional) 120 else 220) ?: return null
            if (detectado == null && r.texto.isNotBlank()) detectado = r.idioma
            return r.texto
        } finally {
            whisperEnCurso = null
        }
    }

    // ---------- Vosk ----------

    /** Vosk escuchando en directo (escribe mientras hablas). */
    private inner class VoskEnVivo {
        private var r: org.vosk.Recognizer? = null
        private val hechas = StringBuilder()
        private var ultimoParcial = ""
        private var pendiente: String? = null

        private val esperando = ArrayList<ShortArray>()

        /** Sin esperar: si el modelo aún se está cargando, null (y se guarda el audio para después). */
        private fun reconocedor(esperar: Boolean): org.vosk.Recognizer? {
            r?.let { return it }
            val m = (if (esperar) MotoresLocales.vosk(ctx, lengua) else MotoresLocales.voskCargado(lengua)) ?: return null
            val nuevo = org.vosk.Recognizer(m, 16000f)
            r = nuevo
            for (b in esperando) aceptar(nuevo, b, b.size)
            esperando.clear()
            return nuevo
        }

        fun alimentar(b: ShortArray, n: Int) {
            val rec = reconocedor(esperar = false)
            if (rec == null) { esperando += b.copyOf(n); return }
            aceptar(rec, b, n)
        }

        private fun aceptar(rec: org.vosk.Recognizer, b: ShortArray, n: Int) {
            if (rec.acceptWaveForm(b, n)) {
                val t = JSONObject(rec.result).optString("text")
                if (t.isNotBlank()) { if (hechas.isNotEmpty()) hechas.append(' '); hechas.append(t) }
            } else {
                val p = JSONObject(rec.partialResult).optString("partial")
                val todo = listOf(hechas.toString(), p).filter { it.isNotBlank() }.joinToString(" ")
                if (todo != ultimoParcial) { ultimoParcial = todo; pendiente = bonito(todo, final = false) }
            }
        }

        fun parcial(): String? = pendiente?.also { pendiente = null }

        fun final(): String {
            val rec = reconocedor(esperar = true) ?: return ""
            val t = JSONObject(rec.finalResult).optString("text")
            if (t.isNotBlank()) { if (hechas.isNotEmpty()) hechas.append(' '); hechas.append(t) }
            return bonito(hechas.toString(), final = true)
        }

        fun cerrar() { runCatching { r?.close() }; r = null }
    }

    /** Vosk escribe en minúsculas y sin puntos: al menos, la primera en mayúscula. */
    private fun bonito(t: String, final: Boolean): String {
        val s = t.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return s
        return s.replaceFirstChar { it.titlecase(Locale.forLanguageTag(lengua)) }
    }

    /** El audio grabado, en trozos (sin copiar todo cada vez que crece). */
    private class Muestras {
        private val trozos = ArrayList<FloatArray>()
        @Volatile var tamano = 0
            private set

        @Synchronized
        fun anadir(b: ShortArray, n: Int) {
            trozos += FloatArray(n) { b[it] / 32768f }
            tamano += n
        }

        @Synchronized
        fun copia(desde: Int, hasta: Int): FloatArray {
            val salida = FloatArray((hasta - desde).coerceAtLeast(0))
            var pos = 0
            for (t in trozos) {
                val ini = maxOf(desde - pos, 0)
                val fin = minOf(hasta - pos, t.size)
                if (fin > ini) System.arraycopy(t, ini, salida, pos + ini - desde, fin - ini)
                pos += t.size
                if (pos >= hasta) break
            }
            return salida
        }
    }
}
