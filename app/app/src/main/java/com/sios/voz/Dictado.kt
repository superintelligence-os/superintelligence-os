package com.sios.voz

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.RecognitionService
import java.util.Locale

/**
 * Cuándo se dicta y se traduce en el propio móvil, y con qué.
 *
 * Automático (de serie): con internet, el dictado del sistema (el de Google, en la edición con Google)
 * y la IA para traducir; sin internet, sin reconocedor o sin IA elegida, lo del móvil. Siempre: lo del
 * móvil aunque haya internet (nada sale de él).
 */
object Dictado {
    enum class Modo { AUTOMATICO, SIEMPRE }

    /** Con qué se dicta en el móvil si están los dos: Whisper (preciso) o Vosk (ligero). */
    enum class Motor { PRECISO, LIGERO }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("voz_sin_conexion", Context.MODE_PRIVATE)

    fun modo(ctx: Context) = if (prefs(ctx).getString("modo", "auto") == "siempre") Modo.SIEMPRE else Modo.AUTOMATICO
    fun ponerModo(ctx: Context, m: Modo) = prefs(ctx).edit().putString("modo", if (m == Modo.SIEMPRE) "siempre" else "auto").apply()

    fun motor(ctx: Context) = if (prefs(ctx).getString("motor", "preciso") == "ligero") Motor.LIGERO else Motor.PRECISO
    fun ponerMotor(ctx: Context, m: Motor) = prefs(ctx).edit().putString("motor", if (m == Motor.LIGERO) "ligero" else "preciso").apply()

    /** «es-ES» → «es». */
    fun base(idioma: String?): String = (idioma ?: Locale.getDefault().toLanguageTag()).substringBefore('-').substringBefore('_').lowercase(Locale.ROOT)

    /** Idiomas que entiende Whisper en SIOS (los del traductor que reconoce bien). */
    val IDIOMAS_PRECISO = listOf("es", "en", "fr", "de", "it", "pt", "nl", "pl", "ro", "ar", "zh", "ja", "ru", "uk")

    /** El motor del móvil que toca para ese idioma, o null si no hay ninguno descargado. */
    fun motorLocal(ctx: Context, idioma: String?): Motor? {
        val l = base(idioma)
        val preciso = l in IDIOMAS_PRECISO && ModelosVoz.listo(ctx, ModelosVoz.WHISPER_SMALL)
        val ligero = ModelosVoz.ligero(l)?.let { ModelosVoz.listo(ctx, it) } == true
        return when {
            preciso && ligero -> motor(ctx)
            preciso -> Motor.PRECISO
            ligero -> Motor.LIGERO
            else -> null
        }
    }

    /** ¿Hay algún dictado en el móvil descargado? */
    fun hayLocal(ctx: Context) = ModelosVoz.listo(ctx, ModelosVoz.WHISPER_SMALL) || ModelosVoz.LIGEROS.any { ModelosVoz.listo(ctx, it) }

    /**
     * Otro reconocedor del sistema que no sea el de SIOS (el de Google, si lo hay). SIOS es el asistente
     * del sistema y Android pone su reconocedor como el de serie: hay que buscar el de otra app.
     */
    fun reconocedorExterno(ctx: Context): ComponentName? {
        val propio = ctx.packageName
        val elegido = Settings.Secure.getString(ctx.contentResolver, "voice_recognition_service")
            ?.let { ComponentName.unflattenFromString(it) }?.takeIf { it.packageName != propio }
        if (elegido != null) return elegido
        val lista = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != propio }
        // El de Google primero (el de la app de Google; si no, el de los servicios de voz).
        return lista.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: lista.firstOrNull { it.packageName.startsWith("com.google.") } ?: lista.firstOrNull()
    }

    fun hayInternet(ctx: Context) = com.sios.sistema.SinConexion.hayInternet(ctx)

    /**
     * ¿Se dicta en el móvil? Siempre que se pida o, en automático, si no hay internet o no hay otro
     * reconocedor. null: no hay forma de dictar (ni del móvil ni del sistema).
     */
    fun usarLocal(ctx: Context, idioma: String?): Boolean? {
        val local = motorLocal(ctx, idioma) != null
        val externo = reconocedorExterno(ctx) != null
        return when {
            local && (modo(ctx) == Modo.SIEMPRE || !externo || !hayInternet(ctx)) -> true
            externo -> false
            else -> null
        }
    }

    /** Abre Ajustes de SIOS en «Sin conexión» (para descargar lo que falta). */
    fun abrirAjustes(ctx: Context, seccion: String = "dictado") {
        runCatching {
            ctx.startActivity(
                Intent(ctx, com.sios.ajustes.AjustesActivity::class.java).putExtra("seccion", seccion)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
