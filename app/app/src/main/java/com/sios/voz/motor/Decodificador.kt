package com.sios.voz.motor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * El decodificador «merged» de los modelos exportados con Optimum (Whisper y Marian): un solo archivo
 * con dos ramas, la del primer paso (sin pasado) y la de los siguientes (con la caché de claves y
 * valores, así cada palabra nueva no vuelve a calcular las anteriores).
 *
 * Kotlin puro y ONNX Runtime: el mismo código corre en el móvil y en las pruebas del Mac.
 */
internal class Decodificador(private val env: OrtEnvironment, private val sesion: OrtSession) : AutoCloseable {
    private val entradas = sesion.inputNames
    private val capas = entradas.count { it.startsWith("past_key_values.") && it.endsWith(".decoder.key") }
    private val forma = (sesion.inputInfo["past_key_values.0.decoder.key"]!!.info as ai.onnxruntime.TensorInfo).shape
    private val cabezas = forma[1]
    private val dim = forma[3]
    private val conMascara = "encoder_attention_mask" in entradas

    override fun close() = sesion.close()

    /**
     * Una frase en curso: guarda la salida del codificador y la caché entre pasos. Hay que cerrarla
     * (suelta los tensores nativos).
     */
    inner class Frase(private val codificado: OnnxTensor, private val mascara: OnnxTensor?) : AutoCloseable {
        private var primero: OrtSession.Result? = null // las claves del codificador (sirven para toda la frase)
        private var anterior: OrtSession.Result? = null // la caché del decodificador del último paso
        private val vacios = ArrayList<OnnxTensor>()

        private fun vacio(): OnnxTensor =
            OnnxTensor.createTensor(env, FloatBuffer.allocate(0), longArrayOf(1, cabezas, 0, dim)).also { vacios += it }

        /**
         * Da los [ids] nuevos (en el primer paso, todo el principio) y devuelve los logits de las
         * posiciones pedidas: [posiciones] vacía = solo la última.
         */
        fun paso(ids: LongArray, posiciones: IntArray = IntArray(0)): Array<FloatArray> {
            val conPasado = primero != null
            val mapa = HashMap<String, OnnxTensor>()
            val propios = ArrayList<OnnxTensor>()
            fun propio(t: OnnxTensor) = t.also { propios += it }
            try {
                mapa["input_ids"] = propio(OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, ids.size.toLong())))
                mapa["encoder_hidden_states"] = codificado
                if (conMascara) mapa["encoder_attention_mask"] = mascara!!
                mapa["use_cache_branch"] = propio(OnnxTensor.createTensor(env, booleanArrayOf(conPasado)))
                for (i in 0 until capas) {
                    for (k in listOf("key", "value")) {
                        mapa["past_key_values.$i.decoder.$k"] =
                            if (conPasado) anterior!!.get("present.$i.decoder.$k").get() as OnnxTensor else vacio()
                        mapa["past_key_values.$i.encoder.$k"] =
                            if (conPasado) primero!!.get("present.$i.encoder.$k").get() as OnnxTensor else vacio()
                    }
                }
                val r = sesion.run(mapa)
                val logits = (r.get("logits").get() as OnnxTensor)
                val v = logits.info.shape[2].toInt()
                val buf = logits.floatBuffer
                val pos = if (posiciones.isEmpty()) intArrayOf(ids.size - 1) else posiciones
                val salida = Array(pos.size) { j -> FloatArray(v).also { buf.position(pos[j] * v); buf.get(it) } }
                // La caché del paso anterior ya no hace falta (la del primero, sí: lleva la del codificador).
                if (anterior != null && anterior !== primero) anterior!!.close()
                if (primero == null) primero = r
                anterior = r
                return salida
            } finally {
                propios.forEach { it.close() }
            }
        }

        override fun close() {
            if (anterior !== primero) anterior?.close()
            primero?.close()
            anterior = null
            primero = null
            vacios.forEach { it.close() }
            vacios.clear()
            codificado.close()
            mascara?.close()
        }
    }
}

/** El índice del mayor (los vetados, con -infinito, nunca ganan). */
internal fun mayor(v: FloatArray): Int {
    var m = 0
    for (i in 1 until v.size) if (v[i] > v[m]) m = i
    return m
}

/** log(softmax(v))[i], sin calcular el vector entero. */
internal fun logProb(v: FloatArray, i: Int): Float {
    var max = Float.NEGATIVE_INFINITY
    for (x in v) if (x > max) max = x
    var suma = 0.0
    for (x in v) if (x != Float.NEGATIVE_INFINITY) suma += Math.exp((x - max).toDouble())
    return (v[i] - max - Math.log(suma)).toFloat()
}
