package com.sios.emergencias

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.Locale

/**
 * Dónde está el móvil, para el SOS y para compartir el viaje: sin servicios de Google (los
 * proveedores de Android: fusionado, GPS y red), todo en el móvil. El enlace del mapa es solo texto
 * dentro del SMS: no se consulta a nadie para hacerlo.
 */
object Ubicacion {
    private val principal by lazy { Handler(Looper.getMainLooper()) }

    private fun proveedores(lm: LocationManager) =
        listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    private fun permiso(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** La última posición conocida (la más reciente de todos los proveedores). */
    @Suppress("MissingPermission")
    fun ultima(ctx: Context): Location? {
        if (!permiso(ctx)) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return proveedores(lm).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /**
     * Una posición nueva: se pide a todos los proveedores a la vez y gana la más precisa que llegue
     * dentro de [esperaMs] (si llega una muy buena, antes). Sin ninguna nueva, la última conocida.
     */
    @Suppress("MissingPermission")
    fun obtener(ctx: Context, esperaMs: Long, listo: (Location?) -> Unit) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (lm == null || !permiso(ctx)) return listo(null)
        val lista = proveedores(lm)
        if (lista.isEmpty()) return listo(ultima(ctx))
        val senales = lista.map { CancellationSignal() }
        var mejor: Location? = null
        var pendientes = lista.size
        var terminado = false
        fun acabar() {
            if (terminado) return
            terminado = true
            senales.forEach { runCatching { it.cancel() } }
            listo(mejor ?: ultima(ctx))
        }
        lista.forEachIndexed { i, p ->
            runCatching {
                lm.getCurrentLocation(p, senales[i], ctx.mainExecutor) { l ->
                    if (l != null && (mejor == null || l.accuracy < mejor!!.accuracy)) mejor = l
                    pendientes--
                    // Buena de verdad (menos de 30 m) o ya han contestado todos: no hace falta esperar más.
                    if ((mejor?.accuracy ?: Float.MAX_VALUE) < 30f || pendientes <= 0) acabar()
                }
            }.onFailure { pendientes-- }
        }
        principal.postDelayed(::acabar, esperaMs)
    }

    fun encendida(ctx: Context) = runCatching { ctx.getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false)

    /**
     * En una emergencia, si la ubicación del móvil está apagada, SIOS la enciende (la app del sistema
     * puede: tiene WRITE_SECURE_SETTINGS). Devuelve si la ha encendido ella, para apagarla al terminar.
     */
    fun encenderSiHaceFalta(ctx: Context): Boolean {
        if (encendida(ctx)) return false
        return runCatching {
            val lm = ctx.getSystemService(LocationManager::class.java)
            LocationManager::class.java.getMethod("setLocationEnabledForUser", Boolean::class.javaPrimitiveType, android.os.UserHandle::class.java)
                .invoke(lm, true, Process.myUserHandle())
            true
        }.getOrDefault(false)
    }

    fun apagar(ctx: Context) {
        runCatching {
            val lm = ctx.getSystemService(LocationManager::class.java)
            LocationManager::class.java.getMethod("setLocationEnabledForUser", Boolean::class.javaPrimitiveType, android.os.UserHandle::class.java)
                .invoke(lm, false, Process.myUserHandle())
        }
    }

    /** Enlace de mapa que abre cualquier móvil (y el navegador): con 5 decimales, un metro más o menos. */
    fun enlace(lat: Double, lon: Double): String =
        "https://maps.google.com/?q=" + String.format(Locale.ROOT, "%.5f,%.5f", lat, lon)

    fun enlace(l: Location) = enlace(l.latitude, l.longitude)
}
