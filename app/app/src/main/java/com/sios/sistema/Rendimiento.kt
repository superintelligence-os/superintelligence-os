package com.sios.sistema

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Fluidez desde el primer minuto: tras instalar o actualizar la app, Android la deja sin compilar
 * hasta que el móvil pasa la noche cargando, y el inicio y la hoja de Claude irían a tirones. Una vez
 * por versión, el servicio la compila entera en segundo plano (ruta fija /optimizar).
 */
object Rendimiento {
    private val http by lazy { okhttp3.OkHttpClient.Builder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build() }

    fun alArrancar(ctx: Context) {
        val app = ctx.applicationContext
        val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
        val prefs = app.getSharedPreferences("rendimiento", Context.MODE_PRIVATE)
        if (prefs.getLong("compilada", -1) == version) return
        // Después del arranque, para no competir con él (y con el servicio ya en marcha).
        Handler(Looper.getMainLooper()).postDelayed({
            Thread {
                val ok = runCatching {
                    val r = okhttp3.Request.Builder().url("http://127.0.0.1:8765/optimizar").post("{}".toRequestBody("application/json".toMediaType())).build()
                    http.newCall(r).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) prefs.edit().putLong("compilada", version).apply()
            }.start()
        }, 20_000)
    }
}
