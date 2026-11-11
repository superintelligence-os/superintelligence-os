package com.sios.sistema

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.sios.R
import com.sios.ajustes.AjustesActivity
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * «Oye SIOS»: un oído que funciona sin conexión. Con el detector acústico de «SIOS» (DetectorSios,
 * dentro de la app); si no lo trae, con Vosk (un modelo de unos 40 MB que se descarga al activarlo). Escucha solo con la pantalla encendida o el móvil cargando, para no gastar
 * batería, y mientras escucha hay una notificación fija (Android lo exige). Al oír «Oye SIOS»
 * (o «Hey SIOS»), suelta el micrófono y abre la hoja de Claude escuchando la pregunta.
 */
class OyeAsistente : Service() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private var escucha: SpeechService? = null
    private var modelo: Model? = null
    // Con el detector acústico de «SIOS» (assets/oye) no hace falta el reconocedor de voz ni descargar nada.
    private var detector: DetectorSios? = null
    private var pararDetector: (() -> Unit)? = null
    private val listo get() = detector != null || modelo != null
    private val principal = Handler(Looper.getMainLooper())
    private var pausada = false

    private val pantalla = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = ajustarSegunEstado()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, aviso(getString(R.string.oye_preparando)), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        registerReceiver(pantalla, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, RECEIVER_EXPORTED)
        Thread {
            runCatching { if (DetectorSios.disponible(this)) detector = DetectorSios(this) else modelo = Model(asegurarModelo(this).path) }
                .onFailure { e -> principal.post { avisar(getString(R.string.oye_error, e.message ?: "")) ; stopSelf() } }
                .onSuccess { principal.post { ajustarSegunEstado() } }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PAUSAR -> { pausada = true; parar() }
            REANUDAR -> { pausada = false; ajustarSegunEstado() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(pantalla) }
        parar()
        modelo?.close()
        detector?.close()
        super.onDestroy()
    }

    /** Escucha solo si toca: pantalla encendida o cargando, y sin la hoja de Claude abierta. */
    private fun ajustarSegunEstado() {
        val encendida = getSystemService(PowerManager::class.java).isInteractive
        val cargando = getSystemService(BatteryManager::class.java).isCharging
        if (!pausada && listo && (encendida || cargando)) empezar() else parar()
    }

    private fun empezar() {
        if (escucha != null || pararDetector != null) return
        detector?.let { d ->
            pararDetector = d.escuchar(
                alOir = { principal.post { meLlaman() } },
                alFallar = { e -> principal.post { avisar(getString(R.string.oye_error, e.message ?: "")); pararDetector = null; principal.postDelayed({ ajustarSegunEstado() }, 3000) } },
            )
            avisar(getString(R.string.oye_escuchando))
            return
        }
        val m = modelo ?: return
        runCatching {
            val r = Recognizer(m, 16000f)
            escucha = SpeechService(r, 16000f).also { it.startListening(oido) }
            avisar(getString(R.string.oye_escuchando))
        }.onFailure { avisar(getString(R.string.oye_error, it.message ?: "")) }
    }

    private fun parar() {
        escucha?.let { runCatching { it.stop(); it.shutdown() } }
        escucha = null
        pararDetector?.invoke()
        pararDetector = null
        if (listo) avisar(getString(if (pausada) R.string.oye_pausada else R.string.oye_en_espera))
    }

    private val oido = object : RecognitionListener {
        override fun onPartialResult(h: String?) = Unit // la llamada cuenta tras la pausa
        override fun onResult(h: String?) = mirar(h)
        override fun onFinalResult(h: String?) = mirar(h)
        override fun onError(e: Exception?) { parar(); principal.postDelayed({ ajustarSegunEstado() }, 3000) }
        override fun onTimeout() { parar(); ajustarSegunEstado() }
    }

    private fun mirar(json: String?) {
        val texto = runCatching { JSONObject(json ?: return).optString("text") }.getOrNull() ?: return
        if (esLlamada(texto)) meLlaman()
    }

    private fun meLlaman() {
        if (pausada) return
        // ¡Me llaman! Se suelta el micrófono y se abre la hoja de Claude escuchando.
        pausada = true
        parar()
        startActivity(
            Intent(this, AsistenteActivity::class.java).putExtra(AsistenteActivity.EXTRA_VOZ, true).putExtra(EXTRA_POR_VOZ, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        // Por si la hoja no avisa al cerrarse, vuelve a escuchar en medio minuto.
        principal.postDelayed({ if (pausada) { pausada = false; ajustarSegunEstado() } }, 30_000)
    }

    private fun aviso(texto: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("oye_claude") // el canal de antes de «Oye SIOS»
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, getString(R.string.oye_canal), NotificationManager.IMPORTANCE_MIN))
        }
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(com.sios.ui.Marca.recurso())
            .setColor(com.sios.ui.Marca.acento(true).toInt())
            .setContentTitle(getString(R.string.oye_titulo))
            .setContentText(texto)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(PendingIntent.getActivity(this, 3, Intent(this, AjustesActivity::class.java).putExtra("seccion", "voz"), PendingIntent.FLAG_IMMUTABLE))
            .build()
    }

    private fun avisar(texto: String) = getSystemService(NotificationManager::class.java).notify(1, aviso(texto))

    companion object {
        private const val CANAL = "oye_sios"
        const val PAUSAR = "com.sios.oye.PAUSAR"
        const val REANUDAR = "com.sios.oye.REANUDAR"
        const val EXTRA_POR_VOZ = "com.sios.oye.POR_VOZ"

        // «Oye SIOS», sea cual sea la IA: el agente es SIOS y se le llama siempre igual.
        // El modelo de voz sin conexión no conoce la palabra y la escribe como la oye («si os»,
        // «seos», «ceos», «sie uns»…). En español «oye, si os parece…» es una frase normal, así que
        // la llamada cuenta solo si es lo último que se dice antes de una pausa y la frase es corta:
        // «Oye SIOS» … y la hoja se abre escuchando. (Un detector acústico propio la sustituirá.)
        private val SALUDOS = setOf(
            "oye", "oy", "oie", "oi", "hoy", "voy", "soy", "hey", "ey", "ei", "he", "hi", "hai", "hola", "ola",
            "ehi", "e", "dis", "di", "hallo", "halo", "herr", "ciao", "salut", "ok", "okey",
        )
        private val FORMAS = Regex("(sios|sio|sius|seos|seus|sieos|sieus|sieuns|siros|sirus|silos|sieuse|sies|sisos)$")

        private fun normal(s: String) = java.text.Normalizer.normalize(s.lowercase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

        /** Fonética simplificada: «ce/ci» y «z» suenan a «s», la «h» no suena, «y» es «i», sin letras dobles. */
        private fun fonetica(s: String) = s.replace(Regex("c(?=[eiy])"), "s").replace('z', 's').replace('y', 'i')
            .replace("h", "").replace('x', 's').replace(Regex("(.)\\1+"), "$1")

        fun esLlamada(texto: String): Boolean {
            val t = normal(texto).split(' ').filter { it.isNotEmpty() }
            if (t.isEmpty() || t.size > 4) return false
            for (n in 1..3) {
                if (n > t.size) break
                val cola = fonetica(t.takeLast(n).joinToString(""))
                if (cola.length > 9 || !FORMAS.containsMatchIn(cola)) continue
                val antes = t.dropLast(n)
                // Sin saludo, solo si suena casi igual («sios», «si os»).
                if (antes.isEmpty()) return cola.length <= 6 || n >= 2
                val ultimo = antes.last()
                if (antes.size <= 2 && (ultimo in SALUDOS || fonetica(ultimo) in SALUDOS)) return true
            }
            return false
        }

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("voz", Context.MODE_PRIVATE)
        fun activo(ctx: Context) = prefs(ctx).getBoolean("oye", false)
        fun responderEnVoz(ctx: Context) = prefs(ctx).getBoolean("responder", true)
        fun ponerResponderEnVoz(ctx: Context, si: Boolean) = prefs(ctx).edit().putBoolean("responder", si).apply()

        fun activar(ctx: Context, si: Boolean) {
            prefs(ctx).edit().putBoolean("oye", si).apply()
            val i = Intent(ctx, OyeAsistente::class.java)
            if (si) ctx.startForegroundService(i) else ctx.stopService(i)
        }

        /** Al arrancar el móvil o la app: si estaba activado, vuelve a escuchar. */
        fun alArrancar(ctx: Context) {
            if (activo(ctx)) runCatching { ctx.startForegroundService(Intent(ctx, OyeAsistente::class.java)) }
        }

        /** La hoja de Claude se ha cerrado: se puede volver a escuchar. */
        fun reanudar(ctx: Context) {
            if (activo(ctx)) runCatching { ctx.startService(Intent(ctx, OyeAsistente::class.java).setAction(REANUDAR)) }
        }

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

        /** Para comprobarlo sin hablar: pasa un WAV (16 kHz, mono) por el mismo oído. */
        fun probarArchivo(ctx: Context, wav: File): JSONObject {
            val m = Model(asegurarModelo(ctx).path)
            val r = Recognizer(m, 16000f)
            wav.inputStream().use { ent ->
                ent.skip(44)
                val buf = ByteArray(4096)
                while (true) { val n = ent.read(buf); if (n <= 0) break; r.acceptWaveForm(buf, n) }
            }
            val texto = JSONObject(r.finalResult).optString("text")
            r.close(); m.close()
            return JSONObject().put("texto", texto).put("llamada", esLlamada(texto))
        }
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
