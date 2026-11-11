package com.sios.sistema

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/** Ajustes de voz del asistente y el modelo de voz sin conexión (Vosk) que usan las llamadas. */
object VozAsistente {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("voz", Context.MODE_PRIVATE)
    fun responderEnVoz(ctx: Context) = prefs(ctx).getBoolean("responder", true)
    fun ponerResponderEnVoz(ctx: Context, si: Boolean) = prefs(ctx).edit().putBoolean("responder", si).apply()

        /** Modelo de voz del idioma del móvil (se descarga una vez, ~40 MB). */
        fun asegurarModelo(ctx: Context): File {
            // El modelo pequeño (~40 MB) del idioma del móvil; si no hay, el inglés.
            val nombre = when (Locale.getDefault().language) {
                "es" -> "vosk-model-small-es-0.42"
                "fr" -> "vosk-model-small-fr-0.22"
                "de" -> "vosk-model-small-de-0.15"
                "it" -> "vosk-model-small-it-0.22"
                "pt" -> "vosk-model-small-pt-0.3"
                else -> "vosk-model-small-en-us-0.15"
            }
            val dir = File(ctx.filesDir, "vosk")
            val listo = File(dir, nombre)
            if (File(listo, "conf").isDirectory) return listo
            dir.mkdirs()
            val tmp = File(dir, "$nombre.descargando")
            tmp.deleteRecursively()
            // Primero el zip entero, y solo si su huella es la esperada (la misma tabla que usa el dictado sin conexión).
            val zip = File(dir, "$nombre.zip.parte")
            URL("https://alphacephei.com/vosk/models/$nombre.zip").openStream().use { ent -> zip.outputStream().use { ent.copyTo(it) } }
            val esperado = com.sios.voz.ModelosVoz.ligero(Locale.getDefault().language.takeIf { it in setOf("es", "fr", "de", "it", "pt") } ?: "en")
                ?.archivos?.firstOrNull()?.sha256
            val huella = java.security.MessageDigest.getInstance("SHA-256").let { md ->
                zip.inputStream().use { ent -> val buf = ByteArray(1 shl 16); while (true) { val n = ent.read(buf); if (n <= 0) break; md.update(buf, 0, n) } }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            if (esperado == null || !huella.equals(esperado, ignoreCase = true)) {
                zip.delete()
                throw java.io.IOException("El modelo de voz descargado no es el esperado")
            }
            zip.inputStream().use { ent ->
                ZipInputStream(ent).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val destino = File(tmp, e.name.substringAfter('/'))
                        if (!destino.canonicalPath.startsWith(tmp.canonicalPath)) continue // nada fuera de la carpeta
                        if (e.isDirectory) destino.mkdirs() else { destino.parentFile?.mkdirs(); destino.outputStream().use { z.copyTo(it) } }
                    }
                }
            }
            zip.delete()
            tmp.renameTo(listo)
            return listo
        }
}

/** Voz de Claude para responder en alto cuando se le ha hablado. */
object Voz {
    private var tts: TextToSpeech? = null
    private var lista = false

    fun preparar(ctx: Context) {
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { estado ->
            lista = estado == TextToSpeech.SUCCESS
            if (lista) tts?.language = Locale.getDefault()
        }
    }

    /** Dice el texto sin el formato (negritas, listas, código). */
    fun decir(ctx: Context, texto: String, enCola: Boolean = false, idioma: Locale? = null) {
        preparar(ctx)
        // El idioma de esta frase (traductor) o el del móvil.
        if (idioma != null) tts?.language = idioma else if (lista) tts?.language = Locale.getDefault()
        val limpio = texto
            .replace(Regex("```[\\s\\S]*?```"), "")
            .replace(Regex("[*_`#>]+"), "")
            .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")
            .trim()
        if (limpio.isEmpty()) return
        val hablar = { tts?.speak(limpio.take(3000), if (enCola) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, "claude") }
        if (lista) hablar() else Handler(Looper.getMainLooper()).postDelayed({ hablar() }, 800)
    }

    fun callar() { tts?.stop() }
}
