package com.sios.sistema

import android.app.WallpaperManager
import android.content.Context

/**
 * Fondo de pantalla de la IA elegida (assets/fondos/<id>.webp, hechos con rom/marca/fondos.py).
 * Se cambia solo al cambiar de IA, salvo que el usuario haya puesto un fondo suyo: si el fondo
 * actual no es el último que pusimos nosotros, no se toca.
 */
object Fondos {
    private val ALIAS = mapOf("openai" to "chatgpt", "anthropic" to "claude", "gemini_api" to "gemini", "kimi_code" to "kimi")
    private fun p(ctx: Context) = ctx.getSharedPreferences("fondos", Context.MODE_PRIVATE)

    fun automatico(ctx: Context) = p(ctx).getBoolean("automatico", true)
    fun ponerAutomatico(ctx: Context, si: Boolean) = p(ctx).edit().putBoolean("automatico", si).apply()

    fun aplicar(ctx: Context, ia: String) = Thread {
        runCatching {
            if (!automatico(ctx)) return@Thread
            val wm = WallpaperManager.getInstance(ctx)
            val nuestro = p(ctx).getInt("id", -1)
            val actual = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM)
            // Un fondo puesto a mano (distinto del nuestro y del de fábrica) manda.
            if (nuestro != -1 && actual != nuestro && p(ctx).getBoolean("puesto", false)) return@Thread
            val nombre = ALIAS[ia] ?: ia
            val archivo = if (ctx.assets.list("fondos")?.contains("$nombre.webp") == true) "$nombre.webp" else "sios.webp"
            if (p(ctx).getString("archivo", null) == archivo && actual == nuestro) return@Thread
            val id = ctx.assets.open("fondos/$archivo").use { wm.setStream(it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }
            p(ctx).edit().putInt("id", id).putString("archivo", archivo).putBoolean("puesto", true).apply()
        }
    }.start()
}
