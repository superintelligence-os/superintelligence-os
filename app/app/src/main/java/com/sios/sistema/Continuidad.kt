package com.sios.sistema

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Continuidad con el Mac (lado de la app): lo que se copia y los avisos van al servicio, que los
 * pasa cifrados al Mac vinculado; el Mac encuentra el móvil en la red local (NSD, _sios._tcp);
 * y «Enviar al Mac» aparece al compartir solo mientras está activa.
 */
object Continuidad {
    private const val PUERTO = 8766
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http by lazy { okhttp3.OkHttpClient.Builder().callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build() }
    private var registro: NsdManager.RegistrationListener? = null
    /** Lo último que llegó del Mac: al ponerlo en el portapapeles no se devuelve. */
    @Volatile private var delMac = ""

    private fun p(ctx: Context) = ctx.getSharedPreferences("continuidad", Context.MODE_PRIVATE)
    fun activa(ctx: Context) = p(ctx).getBoolean("activa", false)

    /** La app lo sabe por Ajustes (y al arrancar se sincroniza con el servicio). */
    fun ponerActiva(ctx: Context, si: Boolean) {
        p(ctx).edit().putBoolean("activa", si).apply()
        ctx.packageManager.setComponentEnabledSetting(
            ComponentName(ctx, "com.sios.EnviarAlMac"),
            if (si) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        if (si) anunciar(ctx) else retirar(ctx)
    }

    fun enviar(ruta: String, cuerpo: JSONObject): JSONObject? = runCatching {
        val r = okhttp3.Request.Builder().url("http://127.0.0.1:8765$ruta").post(cuerpo.toString().toRequestBody("application/json".toMediaType())).build()
        http.newCall(r).execute().use { JSONObject(it.body.string()) }
    }.getOrNull()

    fun portapapeles(ctx: Context, texto: String) {
        if (!activa(ctx) || texto == delMac) return
        alcance.launch { enviar("/continuidad/evento", JSONObject().put("tipo", "portapapeles").put("texto", texto.take(100_000))) }
    }

    fun aviso(ctx: Context, a: Bandeja.Aviso) {
        if (!activa(ctx) || a.paquete == ctx.packageName) return
        alcance.launch {
            enviar("/continuidad/evento", JSONObject().put("tipo", "aviso").put("app", a.app).put("titulo", a.titulo).put("texto", a.texto.take(500)).put("mensaje", a.esMensaje)
                .put("clave", a.clave).put("responder", a.responder != null))
        }
    }

    /** Desde el puente: el Mac ha copiado algo. */
    fun ponerPortapapeles(ctx: Context, texto: String): JSONObject {
        delMac = texto
        Handler(Looper.getMainLooper()).post {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Mac", texto))
        }
        return JSONObject().put("hecho", true)
    }

    // ---------- Que el Mac encuentre el móvil ----------

    fun anunciar(ctx: Context) {
        if (registro != null) return
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val info = NsdServiceInfo().apply {
            serviceName = android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") ?: "SIOS"
            serviceType = "_sios._tcp"
            port = PUERTO
        }
        registro = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { registro = null }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registro) }.onFailure { registro = null }
    }

    fun retirar(ctx: Context) {
        registro?.let { r -> runCatching { ctx.getSystemService(NsdManager::class.java).unregisterService(r) } }
        registro = null
    }

    /** Al arrancar la app: lo que diga el servicio manda (se puede haber cambiado desde Claude). */
    fun arrancar(ctx: Context) {
        val app = ctx.applicationContext
        if (activa(app)) anunciar(app)
        alcance.launch {
            val r = runCatching {
                http.newCall(okhttp3.Request.Builder().url("http://127.0.0.1:8765/continuidad").build()).execute().use { JSONObject(it.body.string()) }
            }.getOrNull() ?: return@launch
            val si = r.optBoolean("activo")
            if (si != activa(app)) Handler(Looper.getMainLooper()).post { ponerActiva(app, si) }
        }
    }
}
