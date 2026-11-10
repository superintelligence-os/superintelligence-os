package com.sios.sistema

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Fondos de pantalla de SIOS (assets/fondos/<nombre>.webp, hechos con rom/marca/fondos.py). Son de
 * la marca SIOS, no de las IA: al cambiar de IA el fondo no cambia. El de serie es «sios»; el
 * usuario elige otro en Ajustes › Apariencia. Si pone un fondo suyo (una foto), no se toca.
 */
object Fondos {
    /** En el orden en que se enseñan. */
    val TODOS = listOf("sios", "orbita", "espectro", "guias", "hueso")

    private fun p(ctx: Context) = ctx.getSharedPreferences("fondos", Context.MODE_PRIVATE)

    fun elegido(ctx: Context) = p(ctx).getString("elegido", null)?.takeIf { it in TODOS } ?: "sios"

    /** El fondo que hay ahora es uno de SIOS puesto por nosotros (y no una foto del usuario). */
    fun esNuestro(ctx: Context): Boolean = runCatching {
        WallpaperManager.getInstance(ctx).getWallpaperId(WallpaperManager.FLAG_SYSTEM) == p(ctx).getInt("id", -1)
    }.getOrDefault(false)

    /**
     * Al arrancar: pone el fondo de SIOS elegido si hace falta. Sustituye los fondos de IA de
     * versiones anteriores, pero nunca un fondo que haya puesto el usuario.
     */
    fun aplicar(ctx: Context) = Thread {
        runCatching {
            val wm = WallpaperManager.getInstance(ctx)
            val nuestro = p(ctx).getInt("id", -1)
            val actual = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            if (nuestro != -1 && actual != nuestro && p(ctx).getBoolean("puesto", false)) return@Thread
            val archivo = "${elegido(ctx)}.webp"
            if (p(ctx).getString("archivo", null) == archivo && actual == nuestro) return@Thread
            poner(ctx, wm, archivo)
        }
    }.start()

    /** El usuario elige un fondo de SIOS: se pone en el inicio y en el bloqueo. */
    fun elegir(ctx: Context, nombre: String) = Thread {
        if (nombre !in TODOS) return@Thread
        p(ctx).edit().putString("elegido", nombre).apply()
        runCatching { poner(ctx, WallpaperManager.getInstance(ctx), "$nombre.webp") }
    }.start()

    private fun poner(ctx: Context, wm: WallpaperManager, archivo: String) {
        val id = ctx.assets.open("fondos/$archivo").use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
        p(ctx).edit().putInt("id", id).putString("archivo", archivo).putBoolean("puesto", true).apply()
    }

    /** Miniatura para el selector (1/8 del tamaño: 135×300). */
    fun miniatura(ctx: Context, nombre: String): Bitmap? = runCatching {
        ctx.assets.open("fondos/$nombre.webp").use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 }) }
    }.getOrNull()
}
