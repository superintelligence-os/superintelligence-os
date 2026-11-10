package com.sios.sistema

import android.Manifest
import android.annotation.SuppressLint
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * Lo que solo se puede hacer cuando Claude viene integrado en la ROM (SIOS):
 * ahí es una app privilegiada con WRITE_SECURE_SETTINGS y MODIFY_DAY_NIGHT_MODE.
 * En un Android normal no hace nada y la app funciona igual.
 */
object SistemaSios {
    /**
     * Colores del sistema (Ajustes, menús, ajustes rápidos): los de SIOS, grafito y hueso
     * (MONOCHROMATIC con el hueso de la marca), sea cual sea la IA: el agente es SIOS. Solo si el
     * usuario elige un acento propio en Apariencia, ese color con TONAL_SPOT. La paleta nunca sale
     * del fondo de pantalla.
     */
    private val SEMILLA get() =
        if (com.sios.ui.Apariencia.sigueIa) "F4F1EA" else "%06X".format(com.sios.ui.Apariencia.colorAcento(true) and 0xFFFFFF)
    private val ESTILO get() = if (com.sios.ui.Apariencia.sigueIa) "MONOCHROMATIC" else "TONAL_SPOT"
    private val TEMA get() =
        "{\"android.theme.customization.color_source\":\"preset\"," +
            "\"android.theme.customization.system_palette\":\"$SEMILLA\"," +
            "\"android.theme.customization.accent_color\":\"$SEMILLA\"," +
            "\"android.theme.customization.theme_style\":\"$ESTILO\"}"

    @SuppressLint("PrivateApi")
    fun version(): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "ro.sios.version") as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun esSios() = version() != null

    /** Marca de la compilación de la ROM (cambia con cada actualización del sistema). */
    @SuppressLint("PrivateApi")
    fun compilacion(): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "ro.sios.compilacion") as String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun puede(ctx: Context, permiso: String) =
        ctx.checkSelfPermission(permiso) == PackageManager.PERMISSION_GRANTED

    fun temaAplicado(ctx: Context): Boolean {
        val actual = Settings.Secure.getString(ctx.contentResolver, "theme_customization_overlay_packages") ?: return false
        val noche = ctx.getSystemService(UiModeManager::class.java).nightMode == UiModeManager.MODE_NIGHT_YES
        return noche && actual.contains(SEMILLA, ignoreCase = true) && actual.contains(ESTILO)
    }

    /**
     * Tema oscuro con los colores de SIOS (o el acento elegido). Solo escribe si hace falta: cada cambio
     * reinicia las pantallas abiertas, y aplicarlo en cada arranque de la pantalla sería un bucle.
     * Devuelve si el tema queda aplicado.
     */
    fun aplicarTema(ctx: Context): Boolean {
        if (temaAplicado(ctx)) return true
        if (puede(ctx, Manifest.permission.WRITE_SECURE_SETTINGS)) {
            val actual = Settings.Secure.getString(ctx.contentResolver, "theme_customization_overlay_packages") ?: ""
            if (!(actual.contains(SEMILLA, ignoreCase = true) && actual.contains(ESTILO))) {
                Settings.Secure.putString(
                    ctx.contentResolver,
                    "theme_customization_overlay_packages",
                    TEMA.dropLast(1) + ",\"_applied_timestamp\":${System.currentTimeMillis()}}",
                )
            }
        }
        if (puede(ctx, "android.permission.MODIFY_DAY_NIGHT_MODE")) {
            val modo = ctx.getSystemService(UiModeManager::class.java)
            if (modo.nightMode != UiModeManager.MODE_NIGHT_YES) runCatching { modo.nightMode = UiModeManager.MODE_NIGHT_YES }
        }
        return temaAplicado(ctx)
    }

    /**
     * Nombre del dispositivo «SIOS» (asistente de Google, Bluetooth, Quick Share) si sigue siendo
     * el del modelo de fábrica: un nombre que haya puesto el usuario no se toca.
     */
    fun ponerNombre(ctx: Context) {
        if (!puede(ctx, Manifest.permission.WRITE_SECURE_SETTINGS)) return
        val actual = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        // «Claude OS» era el nombre que poníamos antes de llamarse SIOS: también se actualiza.
        if (actual.isNullOrBlank() || actual == android.os.Build.MODEL || actual == "Claude OS") {
            Settings.Global.putString(ctx.contentResolver, Settings.Global.DEVICE_NAME, "SIOS")
        }
    }

    /**
     * Mantener pulsado el botón de encendido abre Claude (como el asistente en otros móviles), una
     * sola vez: si el usuario lo cambia en Ajustes, se respeta.
     */
    fun botonEncendido(ctx: Context) {
        val prefs = ctx.getSharedPreferences("sios", Context.MODE_PRIVATE)
        if (prefs.getBoolean("boton_encendido", false) || !puede(ctx, Manifest.permission.WRITE_SECURE_SETTINGS)) return
        Settings.Global.putInt(ctx.contentResolver, "power_button_long_press", 5) // LONG_PRESS_POWER_ASSISTANT
        prefs.edit().putBoolean("boton_encendido", true).apply()
    }

    /** Activa la depuración (necesaria para que el agente actúe sobre el propio móvil). */
    fun activarDepuracion(ctx: Context): Boolean {
        if (!puede(ctx, Manifest.permission.WRITE_SECURE_SETTINGS)) return false
        Settings.Global.putInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
        return Settings.Global.putInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 1)
    }

    fun esInicioPredeterminado(ctx: Context): Boolean {
        val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
        return ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
    }

    fun esAsistente(ctx: Context): Boolean {
        val actual = Settings.Secure.getString(ctx.contentResolver, "voice_interaction_service") ?: return false
        return ComponentName.unflattenFromString(actual)?.packageName == ctx.packageName
    }

    fun terminado(ctx: Context) =
        ctx.getSharedPreferences("sios", Context.MODE_PRIVATE).getBoolean("configurado", false)

    fun marcarTerminado(ctx: Context) =
        ctx.getSharedPreferences("sios", Context.MODE_PRIVATE).edit().putBoolean("configurado", true).apply()
}
