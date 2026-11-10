package com.sios.teclado

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.Settings

/** Poner el teclado de SIOS como teclado del móvil (en SIOS, de un toque). */
object Teclados {
    private fun id(ctx: Context) = ComponentName(ctx, TecladoSios::class.java).flattenToShortString()

    private fun puede(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun enUso(ctx: Context) = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == id(ctx)

    /** Lo activa y lo pone por defecto. Sin permiso (fuera de SIOS), abre los ajustes del sistema. */
    fun usar(ctx: Context): Boolean {
        if (!puede(ctx)) {
            ctx.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        return ponerPorDefecto(ctx)
    }

    /** Que aparezca en la lista de teclados (se puede cambiar con el globo del teclado). */
    fun habilitar(ctx: Context) {
        if (!puede(ctx)) return
        val cr = ctx.contentResolver
        val activos = Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS).orEmpty()
        // Cada entrada es «paquete/servicio», a veces seguida de «;subtipo;subtipo».
        if (activos.split(':').none { it.substringBefore(';') == id(ctx) }) {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_INPUT_METHODS, listOf(activos, id(ctx)).filter { it.isNotBlank() }.joinToString(":"))
        }
    }

    private fun ponerPorDefecto(ctx: Context): Boolean = runCatching {
        habilitar(ctx)
        Settings.Secure.putString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, id(ctx))
    }.getOrDefault(false)

    /**
     * El teclado de SIOS de serie, una sola vez: si luego el usuario elige otro, se respeta. Se llama al
     * encender el móvil (también antes del primer desbloqueo, para que el asistente de bienvenida del
     * sistema ya lo use) y al abrir la app. La marca va en el almacenamiento accesible antes de desbloquear.
     */
    fun deSerie(ctx: Context) {
        if (!puede(ctx)) return // fuera de SIOS no se toca nada (ni se abren ajustes en segundo plano)
        val marca = ctx.createDeviceProtectedStorageContext().getSharedPreferences("teclado", Context.MODE_PRIVATE)
        if (marca.getBoolean("de_serie", false)) return
        // Instalaciones de antes: la marca vivía en el almacenamiento normal (legible solo tras desbloquear).
        val desbloqueado = ctx.getSystemService(UserManager::class.java)?.isUserUnlocked != false
        if (desbloqueado && ctx.getSharedPreferences("sios", Context.MODE_PRIVATE).getBoolean("teclado_de_serie", false)) {
            marca.edit().putBoolean("de_serie", true).apply()
            return
        }
        // Android elige su teclado por defecto durante el primer arranque y puede pisar el nuestro: no
        // se da por hecho hasta verlo puesto en una llamada posterior, ya con la configuración inicial
        // terminada. Hasta entonces se vuelve a poner (al encender y al abrir la app).
        val yaEra = enUso(ctx)
        if (!yaEra) ponerPorDefecto(ctx)
        val configurado = Settings.Secure.getInt(ctx.contentResolver, "user_setup_complete", 0) == 1
        if (yaEra && configurado) marca.edit().putBoolean("de_serie", true).apply()
    }
}
