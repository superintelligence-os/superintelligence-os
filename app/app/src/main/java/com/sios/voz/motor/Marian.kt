package com.sios.voz.motor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * Traducción en el propio móvil con los modelos OPUS-MT de la Universidad de Helsinki (Marian,
 * CC BY 4.0 / Apache 2.0), exportados a ONNX y cuantizados a 8 bits por Xenova. Un modelo por
 * dirección (español → inglés, inglés → español…), en ONNX Runtime como el resto de SIOS.
 *
 * Archivos de la carpeta: encoder.onnx, decoder.onnx, source.spm, target.spm y vocab.json.
 * Kotlin puro: el mismo código se prueba en el Mac.
 */
class Marian(carpeta: File, hilos: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val opciones = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(hilos)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
    }
    private val codificador = env.createSession(File(carpeta, "encoder.onnx").path, opciones)
    private val decodificador = Decodificador(env, env.createSession(File(carpeta, "decoder.onnx").path, opciones))
    private val origen = SentencePiece(File(carpeta, "source.spm").readBytes())
    private val ids: Map<String, Int>
    private val piezas: Array<String>
    private val desconocida: Int
    private val finFrase: Int
    private val relleno: Int

    init {
        val v = JSONObject(File(carpeta, "vocab.json").readText())
        val m = HashMap<String, Int>(v.length() * 2)
        for (k in v.keys()) m[k] = v.getInt(k)
        ids = m
        val p = arrayOfNulls<String>((m.values.maxOrNull() ?: 0) + 1)
        for ((k, i) in m) p[i] = k
        piezas = Array(p.size) { p[it] ?: "" }
        desconocida = m["<unk>"] ?: 1
        finFrase = m["</s>"] ?: 0
        relleno = m["<pad>"] ?: (piezas.size - 1)
    }

    /**
     * Traduce una frase (las largas conviene partirlas antes). [prefijo]: el idioma de destino en los
     * modelos de varios idiomas («>>pt<<»).
     */
    fun traducir(frase: String, prefijo: String? = null): String {
        val texto = frase.trim()
        if (texto.isEmpty()) return ""
        val entrada = ArrayList<Long>()
        if (prefijo != null) entrada += (ids[prefijo] ?: desconocida).toLong()
        for (p in origen.trocear(texto).take(400)) entrada += (ids[p] ?: desconocida).toLong()
        entrada += finFrase.toLong()
        val n = entrada.size.toLong()
        val salida = ArrayList<Int>()
        val idsT = OnnxTensor.createTensor(env, LongBuffer.wrap(entrada.toLongArray()), longArrayOf(1, n))
        val mascara = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(entrada.size) { 1 }), longArrayOf(1, n))
        val estados = idsT.use {
            codificador.run(mapOf("input_ids" to idsT, "attention_mask" to mascara)).use { r ->
                val t = r.get(0) as OnnxTensor
                OnnxTensor.createTensor(env, t.floatBuffer as FloatBuffer, t.info.shape)
            }
        }
        decodificador.Frase(estados, mascara).use { f ->
            var logits = f.paso(longArrayOf(relleno.toLong()))[0]
            val max = minOf(400, entrada.size * 3 + 10)
            while (salida.size < max) {
                logits[relleno] = Float.NEGATIVE_INFINITY
                val id = mayor(logits)
                if (id == finFrase) break
                salida += id
                logits = f.paso(longArrayOf(id.toLong()))[0]
            }
        }
        return salida.filter { it != desconocida && it < piezas.size }
            .joinToString("") { piezas[it] }.replace('▁', ' ').replace(Regex("\\s+"), " ").trim()
    }

    override fun close() {
        codificador.close()
        decodificador.close()
    }
}

/**
 * El troceador SentencePiece (modelo «unigram») leído del archivo .spm: normaliza el texto, marca los
 * espacios con «▁» y elige la mejor partición en piezas del vocabulario (Viterbi).
 */
class SentencePiece(datos: ByteArray) {
    private val puntos = HashMap<String, Float>()
    private var largoMax = 1
    private var minimo = 0f
    private var prefijo = true

    init {
        val p = Protobuf(datos)
        while (p.quedan()) {
            val (campo, tipo) = p.etiqueta()
            when {
                campo == 1 && tipo == 2 -> pieza(Protobuf(p.bytes()))
                campo == 3 && tipo == 2 -> normalizador(Protobuf(p.bytes()))
                else -> p.saltar(tipo)
            }
        }
        minimo = puntos.values.minOrNull() ?: 0f
    }

    private fun pieza(p: Protobuf) {
        var texto = ""
        var puntos = 0f
        var tipo = 1
        while (p.quedan()) {
            val (c, t) = p.etiqueta()
            when {
                c == 1 && t == 2 -> texto = String(p.bytes(), Charsets.UTF_8)
                c == 2 && t == 5 -> puntos = p.flotante()
                c == 3 && t == 0 -> tipo = p.varint().toInt()
                else -> p.saltar(t)
            }
        }
        // Normales (1) y definidas por el usuario (4); las de control y la desconocida no se buscan.
        if (tipo == 1 || tipo == 4) {
            this.puntos[texto] = puntos
            largoMax = maxOf(largoMax, texto.length)
        }
    }

    private fun normalizador(p: Protobuf) {
        while (p.quedan()) {
            val (c, t) = p.etiqueta()
            if (c == 3 && t == 0) prefijo = p.varint() != 0L else p.saltar(t)
        }
    }

    fun trocear(texto: String): List<String> {
        // nmt_nfkc: NFKC, sin caracteres de control y con los espacios juntos.
        val limpio = Normalizer.normalize(texto, Normalizer.Form.NFKC)
            .filter { !Character.isISOControl(it) || it == ' ' || it == '\n' || it == '\t' }
            .replace(Regex("\\s+"), " ").trim()
        if (limpio.isEmpty()) return emptyList()
        val s = (if (prefijo) "▁" else "") + limpio.replace(' ', '▁')
        val n = s.length
        val mejor = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val desde = IntArray(n + 1)
        mejor[0] = 0f
        val castigo = minimo - 10f
        for (fin in 1..n) {
            // Un carácter suelto desconocido siempre es posible (con castigo), así nunca se queda sin camino.
            val unoAtras = if (fin >= 2 && Character.isLowSurrogate(s[fin - 1]) && Character.isHighSurrogate(s[fin - 2])) 2 else 1
            for (largo in 1..minOf(largoMax, fin)) {
                val ini = fin - largo
                if (ini > 0 && Character.isLowSurrogate(s[ini]) && Character.isHighSurrogate(s[ini - 1])) continue
                if (mejor[ini] == Float.NEGATIVE_INFINITY) continue
                val v = puntos[s.substring(ini, fin)]
                val c = when {
                    v != null -> mejor[ini] + v
                    largo == unoAtras -> mejor[ini] + castigo
                    else -> continue
                }
                if (c > mejor[fin]) { mejor[fin] = c; desde[fin] = ini }
            }
        }
        val salida = ArrayList<String>()
        var fin = n
        while (fin > 0) { val ini = desde[fin]; salida += s.substring(ini, fin); fin = ini }
        salida.reverse()
        return salida
    }

    /** Lector mínimo del formato protobuf (lo justo para el .spm). */
    private class Protobuf(private val b: ByteArray) {
        private var i = 0
        fun quedan() = i < b.size
        fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val x = b[i++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl s)
                if (x < 0x80) return r
                s += 7
            }
        }
        fun etiqueta(): Pair<Int, Int> { val v = varint(); return (v ushr 3).toInt() to (v and 7).toInt() }
        fun bytes(): ByteArray { val n = varint().toInt(); return b.copyOfRange(i, i + n).also { i += n } }
        fun flotante(): Float {
            val v = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
            i += 4
            return java.lang.Float.intBitsToFloat(v)
        }
        fun saltar(tipo: Int) {
            when (tipo) {
                0 -> varint()
                1 -> i += 8
                2 -> { val n = varint().toInt(); i += n }
                5 -> i += 4
                else -> error("protobuf: tipo $tipo")
            }
        }
    }
}

/**
 * Parte un texto en frases para traducirlas de una en una (los modelos traducen mejor y más rápido
 * frase a frase). Corta tras «.», «?», «!», «…» o un salto de línea, y respeta los saltos de línea.
 */
fun partirFrases(texto: String): List<String> {
    val salida = ArrayList<String>()
    for (linea in texto.split('\n')) {
        var desde = 0
        val l = linea
        var i = 0
        while (i < l.length) {
            val c = l[i]
            if (c == '.' || c == '?' || c == '!' || c == '…' || c == '。' || c == '？' || c == '！') {
                // Los signos seguidos («?!», «...»), y las comillas o paréntesis que cierran, van con la frase.
                var j = i + 1
                while (j < l.length && (l[j] in ".?!…\"'»”)")) j++
                // «18.45», «3.5», «Sr.» seguido de minúscula: no es final de frase.
                val siguiente = l.getOrNull(j)
                val fin = siguiente == null || (siguiente.isWhitespace() && l.drop(j).trimStart().firstOrNull()?.let { !it.isLowerCase() } != false)
                if (fin) {
                    salida += l.substring(desde, j)
                    desde = j
                }
                i = j
            } else i++
        }
        salida += l.substring(desde)
        salida += "\n"
    }
    if (salida.isNotEmpty()) salida.removeAt(salida.size - 1)
    return salida.map { if (it == "\n") it else it.trim() }.filter { it.isNotEmpty() }
}
