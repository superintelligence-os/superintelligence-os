package com.sios.voz.motor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer

/**
 * Dictado en el propio móvil con Whisper (OpenAI, licencia MIT), exportado a ONNX y cuantizado a
 * 8 bits (onnx-community, Apache 2.0). Corre en ONNX Runtime, el mismo motor que ya lleva SIOS para
 * la memoria de pantalla: no añade nada al APK.
 *
 * Whisper escucha trozos de hasta 30 segundos y escribe con mayúsculas y signos de puntuación. Entiende
 * casi cien idiomas; SIOS lo usa para español, inglés, francés, alemán, italiano y portugués (y los
 * demás del traductor). Aquí está todo el oído: el espectrograma, el codificador y el decodificador.
 *
 * Archivos de la carpeta: encoder.onnx, decoder.onnx, vocab.json y generation_config.json.
 * Kotlin puro: el mismo código se prueba en el Mac.
 */
class Whisper(carpeta: File, hilos: Int = 4) : AutoCloseable {
    data class Resultado(
        val texto: String,
        val idioma: String,
        /** Probabilidad de que no haya voz (0..1). */
        val sinVoz: Float,
        /** Media del logaritmo de la probabilidad de cada pieza: lo seguro que está. */
        val confianza: Float,
        val msEspectro: Long,
        val msCodificador: Long,
        val msDecodificador: Long,
        val piezas: Int,
    )

    private val env = OrtEnvironment.getEnvironment()
    private val opciones = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(hilos)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
    }
    private val codificador = env.createSession(File(carpeta, "encoder.onnx").path, opciones)
    private val decodificador = Decodificador(env, env.createSession(File(carpeta, "decoder.onnx").path, opciones))

    // ---------- Piezas del vocabulario ----------

    private val textos: Array<String>
    private val idiomas: Map<String, Int> // «es» → id de <|es|>
    private val vetadas: IntArray
    private val vetadasAlEmpezar: IntArray
    private val inicio: Int // <|startoftranscript|>
    private val fin: Int // <|endoftext|>
    private val transcribir: Int
    private val sinMarcas: Int // <|notimestamps|>
    private val nadaQueOir: Int // <|nocaptions|>: la probabilidad de que no haya voz
    private val primeraEspecial: Int

    init {
        val vocab = JSONObject(File(carpeta, "vocab.json").readText())
        val conf = JSONObject(File(carpeta, "generation_config.json").readText())
        fin = conf.optInt("eos_token_id", 50257)
        inicio = conf.optInt("decoder_start_token_id", 50258)
        sinMarcas = conf.optInt("no_timestamps_token_id", 50363)
        nadaQueOir = sinMarcas - 1
        transcribir = conf.optJSONObject("task_to_id")?.optInt("transcribe", 50359) ?: 50359
        primeraEspecial = fin + 1
        val t = arrayOfNulls<String>(fin + 1)
        for (k in vocab.keys()) { val id = vocab.getInt(k); if (id < t.size) t[id] = k }
        textos = Array(t.size) { t[it] ?: "" }
        val li = conf.getJSONObject("lang_to_id")
        idiomas = li.keys().asSequence().associate { it.removePrefix("<|").removeSuffix("|>") to li.getInt(it) }
        fun lista(n: String) = conf.optJSONArray(n)?.let { a -> IntArray(a.length()) { a.getInt(it) } } ?: IntArray(0)
        vetadas = lista("suppress_tokens")
        vetadasAlEmpezar = lista("begin_suppress_tokens")
    }

    /**
     * Escribe lo que se dice en [audio] (16 kHz, mono, -1..1). Más de 30 segundos se trocea por los
     * silencios. [idioma]: «es», «en»…; null = lo detecta entre [posibles].
     */
    @Synchronized
    fun transcribir(audio: FloatArray, idioma: String?, posibles: Collection<String> = SEIS, preferido: String? = null, maxPiezas: Int = 220): Resultado? {
        interrumpido = false
        return try {
            transcribirTodo(audio, idioma, posibles, preferido, maxPiezas)
        } catch (e: ai.onnxruntime.OrtException) {
            if (interrumpido) null else throw e
        } finally {
            ejecucion?.close()
            ejecucion = null
        }
    }

    @Volatile private var interrumpido = false
    @Volatile private var ejecucion: OrtSession.RunOptions? = null

    /** Corta la transcripción en curso (desde otro hilo): [transcribir] devuelve null. */
    fun interrumpir() {
        interrumpido = true
        runCatching { ejecucion?.setTerminate(true) }
    }

    private fun transcribirTodo(audio: FloatArray, idioma: String?, posibles: Collection<String>, preferido: String?, maxPiezas: Int): Resultado? {
        val trozos = trocear(audio)
        if (trozos.size == 1) return transcribirTrozo(audio, idioma, posibles, preferido, maxPiezas)
        var lengua = idioma
        val partes = trozos.map { (a, b) ->
            (transcribirTrozo(audio.copyOfRange(a, b), lengua, posibles, preferido, maxPiezas) ?: return null)
                .also { if (lengua == null && it.texto.isNotEmpty()) lengua = it.idioma }
        }
        return Resultado(
            partes.joinToString(" ") { it.texto }.replace(Regex("\\s+"), " ").trim(),
            lengua ?: partes.first().idioma,
            partes.minOf { it.sinVoz },
            partes.map { it.confianza }.average().toFloat(),
            partes.sumOf { it.msEspectro }, partes.sumOf { it.msCodificador }, partes.sumOf { it.msDecodificador }, partes.sumOf { it.piezas },
        )
    }

    /** Trozos de como mucho 28 s, cortando en el momento más callado entre los 20 y los 28 s. */
    private fun trocear(audio: FloatArray): List<Pair<Int, Int>> {
        val max = 28 * MUESTRAS_SEGUNDO
        if (audio.size <= max) return listOf(0 to audio.size)
        val salida = ArrayList<Pair<Int, Int>>()
        var desde = 0
        while (audio.size - desde > max) {
            val ventana = MUESTRAS_SEGUNDO / 10
            var mejor = desde + max
            var menos = Double.MAX_VALUE
            var p = desde + 20 * MUESTRAS_SEGUNDO
            while (p + ventana <= desde + max) {
                var e = 0.0
                for (i in p until p + ventana) e += audio[i] * audio[i]
                if (e < menos) { menos = e; mejor = p + ventana / 2 }
                p += ventana / 2
            }
            salida += desde to mejor
            desde = mejor
        }
        salida += desde to audio.size
        return salida
    }

    private fun transcribirTrozo(audio: FloatArray, idioma: String?, posibles: Collection<String>, preferido: String?, maxPiezas: Int): Resultado? {
        if (interrumpido) return null
        val opcionesRun = OrtSession.RunOptions().also { ejecucion?.close(); ejecucion = it }
        val t0 = System.nanoTime()
        val mel = espectrograma(audio)
        val t1 = System.nanoTime()
        val estados = OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, MEL.toLong(), MARCOS.toLong())).use { entrada ->
            codificador.run(mapOf("input_features" to entrada), opcionesRun).use { r ->
                val t = r.get(0) as OnnxTensor
                OnnxTensor.createTensor(env, t.floatBuffer, t.info.shape)
            }
        }
        val t2 = System.nanoTime()

        // El idioma: el pedido o el más probable de los posibles (un paso con solo <|startoftranscript|>).
        val lengua = idioma?.takeIf { it in idiomas } ?: detectar(estados, posibles, preferido)
        val salida = ArrayList<Int>()
        var sumaLog = 0.0
        var sinVoz: Float
        decodificador.Frase(estados, null).use { frase ->
            val principio = longArrayOf(inicio.toLong(), idiomas.getValue(lengua).toLong(), transcribir.toLong(), sinMarcas.toLong())
            val r = frase.paso(principio, intArrayOf(0, principio.size - 1))
            sinVoz = Math.exp(logProb(r[0], nadaQueOir).toDouble()).toFloat()
            var logits = r[1]
            while (salida.size < maxPiezas) {
                if (interrumpido) return null
                vetar(logits, salida.isEmpty())
                val id = mayor(logits)
                if (id == fin) break
                sumaLog += logProb(logits, id)
                salida += id
                if (repite(salida)) break
                logits = frase.paso(longArrayOf(id.toLong()))[0]
            }
        }
        val t3 = System.nanoTime()
        val confianza = if (salida.isEmpty()) 0f else (sumaLog / salida.size).toFloat()
        var texto = limpiar(texto(salida))
        // Sin voz (o casi) y poco seguro: Whisper se inventaría una frase hecha. Mejor nada.
        if (sinVoz > 0.6f && confianza < -0.7f) texto = ""
        if (esInventada(texto)) texto = ""
        return Resultado(texto, lengua, sinVoz, confianza, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, (t3 - t2) / 1_000_000, salida.size)
    }

    /**
     * El idioma más probable de los [posibles]. Al [preferido] (el del móvil) se le da un poco de
     * ventaja: en frases muy cortas («vale», «ok») Whisper duda y lo normal es hablar en el propio.
     */
    private fun detectar(estados: OnnxTensor, posibles: Collection<String>, preferido: String?): String {
        // La frase de prueba no debe cerrar «estados» (se usa después): se pasa una copia.
        val copia = OnnxTensor.createTensor(env, estados.floatBuffer, estados.info.shape)
        decodificador.Frase(copia, null).use { f ->
            val l = f.paso(longArrayOf(inicio.toLong()))[0]
            return posibles.filter { it in idiomas }.maxByOrNull { l[idiomas.getValue(it)] + if (it == preferido) VENTAJA_PREFERIDO else 0f } ?: "en"
        }
    }

    private fun vetar(l: FloatArray, alEmpezar: Boolean) {
        for (i in vetadas) if (i < l.size) l[i] = Float.NEGATIVE_INFINITY
        if (alEmpezar) for (i in vetadasAlEmpezar) if (i < l.size) l[i] = Float.NEGATIVE_INFINITY
        // Ni marcas de tiempo ni piezas de control: solo texto o el final.
        for (i in primeraEspecial until l.size) l[i] = Float.NEGATIVE_INFINITY
    }

    /**
     * ¿Se ha quedado en bucle? La misma tira de piezas tres veces seguidas (cinco si es de una o dos
     * piezas, que «ja ja ja» existe). Se deja una y se para.
     */
    private fun repite(s: ArrayList<Int>): Boolean {
        for (n in 1..24) {
            val veces = if (n <= 2) 5 else 3
            if (s.size < n * veces) break
            val ultimo = s.size - n
            var igual = true
            for (k in 0 until (veces - 1) * n) if (s[ultimo + (k % n)] != s[s.size - veces * n + k]) { igual = false; break }
            if (igual) {
                repeat((veces - 1) * n) { s.removeAt(s.size - 1) }
                return true
            }
        }
        return false
    }

    private fun texto(ids: List<Int>): String {
        val bytes = java.io.ByteArrayOutputStream()
        for (id in ids) if (id < textos.size) for (c in textos[id]) BYTE_DE[c.code].let { if (it >= 0) bytes.write(it) }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun limpiar(t: String) = t.replace(Regex("\\s+"), " ").trim()

    override fun close() {
        codificador.close()
        decodificador.close()
    }

    companion object {
        const val MUESTRAS_SEGUNDO = 16000
        private const val N_FFT = 400
        private const val SALTO = 160
        private const val MEL = 80
        private const val MARCOS = 3000
        private const val BINS = N_FFT / 2 + 1

        val SEIS = listOf("es", "en", "fr", "de", "it", "pt")

        /** Ventaja (en logits) del idioma del móvil al detectar el idioma. */
        private const val VENTAJA_PREFERIDO = 1.5f

        /** Frases que Whisper se inventa con silencio o ruido (vienen de los subtítulos con los que aprendió). */
        private val INVENTADAS = Regex(
            "^(¡?suscr[ií]bete.*|.*amara\\.org.*|subt[ií]tulos? (realizados? )?por.*|gracias por ver(lo| el v[ií]deo)?[.!]*|" +
                "thanks? (you )?for watching[.!]*|sous-titr.*|untertitel.*|sottotitoli.*|legendas pela.*|\\.+|…)$",
            RegexOption.IGNORE_CASE,
        )

        fun esInventada(t: String) = t.isNotEmpty() && INVENTADAS.matches(t.trim())

        // El byte de cada carácter del vocabulario (el «bytes_to_unicode» de GPT-2).
        private val BYTE_DE = IntArray(512) { -1 }.also { m ->
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            for (i in bs.indices) m[cs[i]] = bs[i]
        }

        private val VENTANA = FloatArray(N_FFT) { (0.5 - 0.5 * Math.cos(2 * Math.PI * it / N_FFT)).toFloat() }
        private val COS = FloatArray(BINS * N_FFT)
        private val SEN = FloatArray(BINS * N_FFT)

        /** Los 80 filtros mel (escala y normalización «slaney», como librosa y el propio Whisper). */
        internal val FILTROS: Array<FloatArray>

        init {
            for (k in 0 until BINS) for (n in 0 until N_FFT) {
                val a = 2 * Math.PI * ((k.toLong() * n) % N_FFT) / N_FFT
                COS[k * N_FFT + n] = Math.cos(a).toFloat()
                SEN[k * N_FFT + n] = Math.sin(a).toFloat()
            }
            val fSp = 200.0 / 3
            val logPaso = Math.log(6.4) / 27
            fun aMel(f: Double) = if (f < 1000) f / fSp else 15 + Math.log(f / 1000) / logPaso
            fun aHz(m: Double) = if (m < 15) fSp * m else 1000 * Math.exp(logPaso * (m - 15))
            val maxMel = aMel(8000.0)
            val f = DoubleArray(MEL + 2) { aHz(maxMel * it / (MEL + 1)) }
            FILTROS = Array(MEL) { i ->
                val norma = 2.0 / (f[i + 2] - f[i])
                FloatArray(BINS) { k ->
                    val hz = 8000.0 * k / (BINS - 1)
                    val baja = (hz - f[i]) / (f[i + 1] - f[i])
                    val alta = (f[i + 2] - hz) / (f[i + 2] - f[i + 1])
                    (maxOf(0.0, minOf(baja, alta)) * norma).toFloat()
                }
            }
        }

        /**
         * El espectrograma log-mel de Whisper (80 × 3000, 30 s): ventanas de 25 ms cada 10 ms, con el
         * audio rellenado de silencio hasta los 30 s. Solo se calculan las ventanas con sonido.
         */
        fun espectrograma(audio: FloatArray): FloatArray {
            val n = minOf(audio.size, 30 * MUESTRAS_SEGUNDO)
            val total = 30 * MUESTRAS_SEGUNDO
            fun muestra(i: Int): Float {
                // Relleno «reflect» en los bordes (como torch.stft con center=True).
                val j = if (i < 0) -i else if (i >= total) 2 * (total - 1) - i else i
                return if (j < n) audio[j] else 0f
            }
            val mel = FloatArray(MEL * MARCOS) { Float.NaN }
            val conSonido = minOf(MARCOS, (n + N_FFT / 2) / SALTO + 1)
            val trozo = FloatArray(N_FFT)
            val potencia = FloatArray(BINS)
            var maximo = Float.NEGATIVE_INFINITY
            for (t in 0 until conSonido) {
                val c = t * SALTO - N_FFT / 2
                for (i in 0 until N_FFT) trozo[i] = muestra(c + i) * VENTANA[i]
                for (k in 0 until BINS) {
                    var re = 0f; var im = 0f
                    val b = k * N_FFT
                    for (i in 0 until N_FFT) { re += trozo[i] * COS[b + i]; im += trozo[i] * SEN[b + i] }
                    potencia[k] = re * re + im * im
                }
                for (m in 0 until MEL) {
                    val fl = FILTROS[m]
                    var s = 0f
                    for (k in 0 until BINS) if (fl[k] != 0f) s += fl[k] * potencia[k]
                    val v = Math.log10(maxOf(s, 1e-10f).toDouble()).toFloat()
                    mel[m * MARCOS + t] = v
                    if (v > maximo) maximo = v
                }
            }
            // El resto es silencio: log10(1e-10) = -10.
            if (conSonido < MARCOS) maximo = maxOf(maximo, -10f)
            val suelo = maximo - 8f
            for (i in mel.indices) {
                val v = if (mel[i].isNaN()) -10f else mel[i]
                mel[i] = (maxOf(v, suelo) + 4f) / 4f
            }
            return mel
        }
    }
}
