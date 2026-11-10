package com.sios.emergencias

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * La alarma de la cuenta atrás: dos tonos agudos cada segundo (como las alarmas de evacuación), a
 * todo volumen aunque el móvil esté en silencio o en no molestar (sale por el canal de alarma), y una
 * vibración fuerte al mismo ritmo. El tono se genera aquí: no depende de que haya un sonido de alarma.
 * Al parar, el volumen vuelve a como estaba.
 */
object Sirena {
    private var pista: AudioTrack? = null
    private var volumenAntes = -1

    private const val MUESTRAS = 44_100

    /** Un segundo: 1050 Hz, 820 Hz y silencio, con rampas cortas para que no chasquee. */
    private fun segundo(): ShortArray {
        val s = ShortArray(MUESTRAS)
        val tramos = listOf(Triple(0.00, 0.20, 1050.0), Triple(0.24, 0.44, 820.0))
        for ((desde, hasta, hz) in tramos) {
            val a = (desde * MUESTRAS).toInt()
            val b = (hasta * MUESTRAS).toInt()
            val rampa = MUESTRAS / 100
            for (i in a until b) {
                val env = min(1.0, min((i - a).toDouble() / rampa, (b - i).toDouble() / rampa))
                s[i] = (sin(2 * PI * hz * (i - a) / MUESTRAS) * env * 0.9 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return s
    }

    /** [alMaximo]: a todo volumen (de verdad); en una prueba, al volumen de alarma que tenga el móvil. */
    @Synchronized
    fun empezar(ctx: Context, sonido: Boolean, alMaximo: Boolean = true) {
        parar(ctx)
        val app = ctx.applicationContext
        if (sonido) runCatching {
            val audio = app.getSystemService(AudioManager::class.java)
            if (alMaximo) {
                volumenAntes = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            }
            val datos = segundo()
            pista = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(MUESTRAS).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(datos.size * 2)
                .build().apply {
                    write(datos, 0, datos.size)
                    setLoopPoints(0, datos.size, -1)
                    play()
                }
        }.onFailure { android.util.Log.w("SIOS", "Sin sonido de alarma", it) }
        runCatching {
            @Suppress("DEPRECATION")
            app.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 420, 580), intArrayOf(0, 255, 0), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    }

    @Synchronized
    fun parar(ctx: Context) {
        pista?.runCatching { stop(); release() }
        pista = null
        val app = ctx.applicationContext
        runCatching { app.getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }
        if (volumenAntes >= 0) runCatching { app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volumenAntes, 0) }
        volumenAntes = -1
    }
}
