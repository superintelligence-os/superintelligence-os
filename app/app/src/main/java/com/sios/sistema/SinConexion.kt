package com.sios.sistema

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import com.sios.R
import java.text.DateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Lo básico sin conexión: si no hay internet (o el agente no responde), estas órdenes se hacen
 * aquí mismo, sin Claude. Alarmas, temporizadores, linterna, Wi-Fi, Bluetooth, modo avión,
 * No molestar, volumen, brillo, abrir apps, llamar a un contacto, la hora y la batería.
 * Devuelve la respuesta para enseñar, o null si no es una de estas órdenes.
 */
object SinConexion {
    fun hayInternet(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    private fun normal(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").trim().trimEnd('.', '!', '?')

    private val NUMEROS = mapOf(
        "una" to 1, "un" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6, "siete" to 7, "ocho" to 8,
        "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "quince" to 15, "veinte" to 20, "treinta" to 30, "media" to 30,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
    )
    private fun numero(s: String?): Int? = s?.toIntOrNull() ?: NUMEROS[s]

    /**
     * Si no hay internet o el agente no responde, la orden se atiende aquí y queda en la
     * conversación (pregunta y respuesta). Devuelve true si la ha atendido.
     */
    fun atender(ctx: Context, frase: String): Boolean {
        val agenteListo = com.sios.datos.Servicio.estado.value.conectado
        if (hayInternet(ctx) && agenteListo) return false
        // En segundo plano: algunas órdenes hablan con el agente local (no se puede desde el hilo principal).
        val app = ctx.applicationContext
        Thread {
            val respuesta = runCatching { intentar(app, frase) }.getOrNull()
                ?: app.getString(if (!agenteListo) R.string.local_sin_agente else R.string.local_no_entiendo)
            com.sios.datos.Servicio.anadirLocal(frase, respuesta)
        }.start()
        return true
    }

    fun intentar(ctx: Context, frase: String): String? {
        val t = normal(frase)
        val r = ctx.resources
        fun dicho(id: Int, vararg a: Any) = r.getString(id, *a)

        // Alarma: «pon una alarma a las 7:30», «alarma a las 8 y media de la tarde», «set an alarm for 6am»
        Regex("""\b(alarma|alarm)\b.*?\b(?:a las|a la|para las|at|for)?\s*(\d{1,2})(?:[:.h](\d{2}))?\s*(y media|y cuarto|menos cuarto)?\s*(de la manana|de la tarde|de la noche|am|pm)?""")
            .find(t)?.let { m ->
                var h = m.groupValues[2].toInt()
                var min = m.groupValues[3].toIntOrNull() ?: 0
                when (m.groupValues[4]) { "y media" -> min = 30; "y cuarto" -> min = 15; "menos cuarto" -> { h -= 1; min = 45 } }
                if (m.groupValues[5] in setOf("de la tarde", "de la noche", "pm") && h < 12) h += 12
                if (h !in 0..23 || min !in 0..59) return null
                ctx.startActivity(
                    Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return dicho(R.string.local_alarma, "%d:%02d".format(h, min))
            }

        // Temporizador: «temporizador de 10 minutos», «timer for five minutes»
        Regex("""\b(temporizador|timer|cuenta atras)\b.*?(\d+|\w+)\s*(segundos?|minutos?|horas?|seconds?|minutes?|hours?)""").find(t)?.let { m ->
            val n = numero(m.groupValues[2]) ?: return null
            val seg = when {
                m.groupValues[3].startsWith("seg") || m.groupValues[3].startsWith("sec") -> n
                m.groupValues[3].startsWith("h") -> n * 3600
                else -> n * 60
            }
            ctx.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, seg).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return dicho(R.string.local_temporizador, "${m.groupValues[2]} ${m.groupValues[3]}")
        }

        val encender = Regex("""\b(enciende|activa|pon|abre|conecta|turn on|enable|switch on)\b""").containsMatchIn(t)
        val apagar = Regex("""\b(apaga|desactiva|quita|cierra|desconecta|turn off|disable|switch off)\b""").containsMatchIn(t)

        if (Regex("""\b(linterna|flashlight|torch)\b""").containsMatchIn(t)) {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                ?: return dicho(R.string.local_sin_linterna)
            cm.setTorchMode(id, !apagar)
            return dicho(if (apagar) R.string.local_linterna_off else R.string.local_linterna_on)
        }

        // Wi-Fi, Bluetooth, modo avión y datos: con la terminal del propio móvil (no necesita internet).
        val sistema = listOf(
            Triple(Regex("""\b(wifi|wi-fi|wi fi)\b"""), "svc wifi", R.string.local_wifi),
            Triple(Regex("""\b(bluetooth)\b"""), "svc bluetooth", R.string.local_bluetooth),
            Triple(Regex("""\b(datos moviles|datos|mobile data)\b"""), "svc data", R.string.local_datos),
        )
        for ((patron, orden, nombre) in sistema) {
            if (patron.containsMatchIn(t) && (encender || apagar)) {
                if (!Shell.ejecutar("$orden ${if (apagar) "disable" else "enable"}")) return dicho(R.string.local_no_pude)
                return dicho(if (apagar) R.string.local_apagado else R.string.local_encendido, r.getString(nombre))
            }
        }
        if (Regex("""\b(modo avion|airplane mode|flight mode)\b""").containsMatchIn(t) && (encender || apagar)) {
            if (!Shell.ejecutar("cmd connectivity airplane-mode ${if (apagar) "disable" else "enable"}")) return dicho(R.string.local_no_pude)
            return dicho(if (apagar) R.string.local_apagado else R.string.local_encendido, r.getString(R.string.local_avion))
        }
        if (Regex("""\b(no molestar|do not disturb|silencio total)\b""").containsMatchIn(t)) {
            val b = Bandeja.instancia ?: return dicho(R.string.local_no_pude)
            b.requestInterruptionFilter(if (apagar) android.service.notification.NotificationListenerService.INTERRUPTION_FILTER_ALL else android.service.notification.NotificationListenerService.INTERRUPTION_FILTER_PRIORITY)
            return dicho(if (apagar) R.string.local_apagado else R.string.local_encendido, r.getString(R.string.local_no_molestar))
        }

        // Volumen
        val audio = ctx.getSystemService(AudioManager::class.java)
        if (Regex("""\b(volumen|volume)\b""").containsMatchIn(t) || Regex("""\b(silencia|silencio|mute)\b""").containsMatchIn(t)) {
            when {
                Regex("""\b(silencia|silencio|mute)\b""").containsMatchIn(t) -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
                Regex("""\b(sube|subir|mas alto|up|louder)\b""").containsMatchIn(t) -> repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0) }
                Regex("""\b(baja|bajar|mas bajo|down|lower)\b""").containsMatchIn(t) -> repeat(3) { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0) }
                else -> return null
            }
            val pct = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            return dicho(R.string.local_volumen, pct)
        }

        // Brillo: «brillo al 30 %», «sube el brillo»
        if (Regex("""\b(brillo|brightness)\b""").containsMatchIn(t)) {
            val cr = ctx.contentResolver
            val actual = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val objetivo = Regex("""(\d{1,3})\s*%?""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { it.coerceIn(1, 100) * 255 / 100 }
                ?: when {
                    Regex("""\b(sube|mas|up|max)""").containsMatchIn(t) -> (actual + 64).coerceAtMost(255)
                    Regex("""\b(baja|menos|down|min)""").containsMatchIn(t) -> (actual - 64).coerceAtLeast(1)
                    else -> return null
                }
            runCatching {
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, objetivo)
            }.getOrElse { return dicho(R.string.local_no_pude) }
            return dicho(R.string.local_brillo, objetivo * 100 / 255)
        }

        // Hora, fecha y batería
        if (Regex("""\b(que hora|hora es|what time|la hora)\b""").containsMatchIn(t)) {
            return dicho(R.string.local_hora, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date()))
        }
        if (Regex("""\b(que dia|fecha|what day|date)\b""").containsMatchIn(t)) {
            return dicho(R.string.local_fecha, DateFormat.getDateInstance(DateFormat.FULL).format(Date()))
        }
        if (Regex("""\b(bateria|battery)\b""").containsMatchIn(t)) {
            val b = ctx.getSystemService(BatteryManager::class.java)
            return dicho(R.string.local_bateria, b.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        }

        // Llamar: «llama a mamá», «call Lucía»
        Regex("""^(?:llama|llamar|call)\s+(?:a\s+)?(.+)$""").find(t)?.let { m ->
            val (nombre, numero) = contacto(ctx, m.groupValues[1]) ?: return dicho(R.string.local_sin_contacto, m.groupValues[1])
            ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(numero)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return dicho(R.string.local_llamando, nombre)
        }

        // Abrir una app: «abre WhatsApp», «open camera»
        Regex("""^(?:abre|abrir|open|lanza|inicia)\s+(?:la\s+app\s+(?:de\s+)?|el\s+|la\s+)?(.+)$""").find(t)?.let { m ->
            val buscada = m.groupValues[1]
            val pm = ctx.packageManager
            val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val app = apps.firstOrNull { normal(it.loadLabel(pm).toString()) == buscada }
                ?: apps.firstOrNull { normal(it.loadLabel(pm).toString()).startsWith(buscada) }
                ?: apps.firstOrNull { normal(it.loadLabel(pm).toString()).contains(buscada) }
                ?: return null
            ctx.startActivity(pm.getLaunchIntentForPackage(app.activityInfo.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return dicho(R.string.local_abriendo, app.loadLabel(pm))
        }
        return null
    }

    @SuppressLint("Range")
    private fun contacto(ctx: Context, nombre: String): Pair<String, String>? = runCatching {
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null,
        )?.use { c ->
            var mejor: Pair<String, String>? = null
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val nn = normal(n)
                if (nn == nombre) return@runCatching n to c.getString(1)
                if (mejor == null && (nn.startsWith(nombre) || nn.contains(nombre))) mejor = n to c.getString(1)
            }
            mejor
        }
    }.getOrNull()
}

/**
 * Órdenes de sistema sin internet, a través del agente (que corre en el propio móvil). Solo admite
 * una lista cerrada (Wi-Fi, Bluetooth, datos, modo avión): el resto se rechaza.
 */
object Shell {
    private val http by lazy { okhttp3.OkHttpClient.Builder().callTimeout(15, java.util.concurrent.TimeUnit.SECONDS).build() }

    fun ejecutar(orden: String): Boolean = runCatching {
        val cuerpo = org.json.JSONObject().put("orden", orden).toString().toRequestBody("application/json".toMediaType())
        val r = okhttp3.Request.Builder().url("http://127.0.0.1:8765/local").post(cuerpo).build()
        http.newCall(r).execute().use { it.isSuccessful && org.json.JSONObject(it.body.string()).optBoolean("ok") }
    }.getOrDefault(false)
}
