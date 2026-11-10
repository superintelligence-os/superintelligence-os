package com.sios.sistema

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display

/**
 * Modo escritorio: al conectar un monitor (USB-C con DisplayPort o un adaptador HDMI), el monitor es un
 * ordenador —ventanas libres, barra de tareas, ratón y teclado— y el móvil sigue siendo el móvil.
 *
 * Lo pone Android 16 (QPR2) con su escritorio de pantallas conectadas; SIOS solo lo deja preparado:
 *  - La ROM (overlay-framework/res/values/escritorio.xml) dice que el móvil admite escritorio en
 *    monitores pero no en su propia pantalla (config_isDesktopModeSupported sí,
 *    config_canInternalDisplayHostDesktops no).
 *  - build.prop enciende las «funciones de escritorio» de Android (persist.wm.debug.desktop_experience_devopts):
 *    gestión del contenido de cada pantalla, barra de tareas en monitores, arrastrar ventanas de una
 *    pantalla a otra…
 *  - Aquí, lo que se puede cambiar sin reiniciar: las ventanas libres (enable_freeform_support: sin ellas el
 *    monitor no es «apto para escritorio») y si el monitor duplica la pantalla del móvil
 *    (mirror_built_in_display). En el monitor, el escritorio es el de Android (Launcher3: barra de tareas,
 *    recientes y ventanas); el inicio de SIOS se queda en el móvil.
 *
 * Se escribe en los ajustes del sistema (WRITE_SECURE_SETTINGS, app privilegiada) solo al cambiar algo
 * aquí o la primera vez; si el usuario los toca en Opciones para desarrolladores, se respeta.
 */
object ModoEscritorio {
    /** Qué hace el monitor al conectarlo. */
    enum class AlConectar { ORDENADOR, DUPLICAR }

    private const val VENTANAS = "enable_freeform_support"
    private const val DUPLICAR = "mirror_built_in_display"
    private const val NO_REDIMENSIONABLES = "enable_non_resizable_multi_window"
    private const val FORZAR_REDIMENSIONABLES = "force_resizable_activities"
    private const val VERSION_DE_SERIE = 1

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("modo_escritorio", Context.MODE_PRIVATE)

    fun activo(ctx: Context) = prefs(ctx).getBoolean("activo", true)

    fun alConectar(ctx: Context): AlConectar =
        runCatching { AlConectar.valueOf(prefs(ctx).getString("al_conectar", null) ?: "") }.getOrDefault(AlConectar.ORDENADOR)

    /** Todas las apps en ventanas, también las que solo saben ir a pantalla completa (alguna se ve rara). */
    fun todasEnVentana(ctx: Context) = prefs(ctx).getBoolean("todas", false)

    /**
     * ¿Trae esta ROM el escritorio de Android 16 preparado? (la capa del sistema de SIOS y la propiedad
     * de build.prop). Sin ello —otra base o una versión vieja de SIOS— la opción explica que no está.
     */
    fun disponible(ctx: Context): Boolean {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier("config_isDesktopModeSupported", "bool", "android")
        val admite = id != 0 && runCatching { res.getBoolean(id) }.getOrDefault(false)
        return admite && propiedad("persist.wm.debug.desktop_experience_devopts") == "true"
    }

    fun poner(ctx: Context, activo: Boolean = activo(ctx), alConectar: AlConectar = alConectar(ctx), todas: Boolean = todasEnVentana(ctx)): Boolean {
        prefs(ctx).edit().putBoolean("activo", activo).putString("al_conectar", alConectar.name).putBoolean("todas", todas).apply()
        return aplicar(ctx)
    }

    /** Lo de serie, una sola vez (la primera vez que arranca la app en una ROM que lo trae). */
    fun alArrancar(ctx: Context) {
        val p = prefs(ctx)
        if (p.getInt("de_serie", 0) >= VERSION_DE_SERIE || !disponible(ctx)) return
        if (aplicar(ctx)) p.edit().putInt("de_serie", VERSION_DE_SERIE).apply()
    }

    /** Pasa la elección a los ajustes del sistema. false si no se ha podido (sin el permiso, en pruebas). */
    fun aplicar(ctx: Context): Boolean = runCatching {
        val cr = ctx.contentResolver
        val ordenador = activo(ctx) && alConectar(ctx) == AlConectar.ORDENADOR
        val todas = ordenador && todasEnVentana(ctx)
        Settings.Global.putInt(cr, VENTANAS, if (ordenador) 1 else 0)
        Settings.Secure.putInt(cr, DUPLICAR, if (ordenador) 0 else 1)
        Settings.Global.putInt(cr, NO_REDIMENSIONABLES, if (todas) 1 else 0)
        Settings.Global.putInt(cr, FORZAR_REDIMENSIONABLES, if (todas) 1 else 0)
        true
    }.getOrDefault(false)

    /** Un monitor conectado ahora mismo (por cable o inalámbrico). */
    data class Monitor(val nombre: String, val ancho: Int, val alto: Int, val inalambrico: Boolean)

    /** Los monitores conectados: pantallas físicas o inalámbricas que no son la del móvil (ni virtuales). */
    fun monitores(ctx: Context): List<Monitor> {
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return emptyList()
        return dm.displays.filter { it.displayId != Display.DEFAULT_DISPLAY }.mapNotNull { d ->
            val tipo = tipo(d)
            val esMonitor = when (tipo) {
                TIPO_EXTERNA, TIPO_INALAMBRICA -> true
                null -> d.flags and Display.FLAG_PRIVATE == 0 && d.flags and Display.FLAG_PRESENTATION != 0
                else -> false
            }
            if (!esMonitor) return@mapNotNull null
            val modo = d.mode
            Monitor(d.name ?: "", modo.physicalWidth, modo.physicalHeight, tipo == TIPO_INALAMBRICA)
        }
    }

    // Display.getType() es una API oculta (la app está en la lista blanca de APIs ocultas de la ROM).
    private const val TIPO_EXTERNA = 2
    private const val TIPO_INALAMBRICA = 3
    private fun tipo(d: Display): Int? = runCatching { Display::class.java.getMethod("getType").invoke(d) as Int }.getOrNull()

    private fun propiedad(nombre: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, nombre) as String
    }.getOrNull()
}
