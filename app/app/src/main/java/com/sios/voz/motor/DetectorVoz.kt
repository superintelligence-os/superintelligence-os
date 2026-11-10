package com.sios.voz.motor

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Cuándo se empieza y se deja de hablar, por la energía de cada trozo de 20 ms comparada con el ruido
 * de fondo (que se aprende al principio y se sigue mientras no se habla). El tiempo se cuenta en
 * muestras, no con el reloj: así se prueba igual en el Mac con un WAV.
 *
 * Kotlin puro: el mismo código corre en el móvil y en las pruebas del Mac.
 */
class DetectorVoz(
    /** Silencio tras la voz para dar la frase por terminada. */
    private val silencioFinMs: Int = 1400,
    /** Sin voz en este tiempo: nadie habla. */
    private val esperaMaxMs: Int = 8000,
    private val maximoMs: Int = 120_000,
    private val tasa: Int = 16000,
) {
    enum class Evento { NADA, EMPIEZA, TERMINA, NADIE, MAXIMO }

    var hablando = false
        private set
    /** Muestra donde empieza la voz (con 0,4 s de antes, para no comerse la primera sílaba). */
    var inicioVoz = 0
        private set
    /** Muestra donde acabó la última voz. */
    var finVoz = 0
        private set
    var db = -90f
        private set
    /** 0..1, para el orbe que late con la voz. */
    var nivel = 0f
        private set

    /** ¿Este trozo es de pausa (entre palabras)? Buen momento para una transcripción provisional. */
    var enPausa = false
        private set

    private var suelo = -60f
    private var marcos = 0
    private var conVoz = 0
    private var muestras = 0

    /** Un trozo nuevo (lo normal, 20 ms). */
    fun marco(b: ShortArray, n: Int): Evento {
        if (n <= 0) return Evento.NADA
        marcos++
        muestras += n
        var suma = 0.0
        for (i in 0 until n) { val v = b[i] / 32768.0; suma += v * v }
        db = (20 * log10(sqrt(suma / n) + 1e-9)).toFloat()
        // El ruido de fondo: se aprende en los primeros 200 ms y sigue despacio mientras no se habla.
        if (marcos <= 10) suelo = if (marcos == 1) db else (suelo * (marcos - 1) + db) / marcos
        else if (!hablando && db < suelo + 6) suelo = suelo * 0.97f + db * 0.03f
        val umbral = maxOf(suelo + 10f, -52f)
        nivel = ((db - suelo - 4f) / 30f).coerceIn(0f, 1f)
        if (db > umbral) conVoz++ else conVoz = 0
        enPausa = db < umbral - 4f
        val ms = (muestras.toLong() * 1000 / tasa).toInt()
        if (!hablando && conVoz >= 3) {
            hablando = true
            inicioVoz = maxOf(0, muestras - tasa * 4 / 10)
            finVoz = muestras
            return Evento.EMPIEZA
        }
        if (hablando && db > umbral - 4f) finVoz = muestras
        if (hablando && (muestras - finVoz).toLong() * 1000 / tasa > silencioFinMs) return Evento.TERMINA
        if (!hablando && ms > esperaMaxMs) return Evento.NADIE
        if (ms > maximoMs) return Evento.MAXIMO
        return Evento.NADA
    }

    /** Hasta dónde vale el audio: la última voz y 0,3 s de margen. */
    fun finConMargen(total: Int) = minOf(total, finVoz + tasa * 3 / 10)
}
