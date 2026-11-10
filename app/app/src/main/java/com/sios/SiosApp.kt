package com.sios

import android.app.Application
import com.sios.datos.Servicio

class SiosApp : Application() {
    // Textos con el nombre de la IA elegida. Sin envolver el contexto base: Android lo necesita tal
    // cual para los receptores (con envoltorio, el de arranque y los demás fallaban).
    override fun getResources(): android.content.res.Resources = com.sios.ui.Marca.recursos(super.getResources())

    override fun onCreate() {
        super.onCreate()
        // Arranque directo (antes del primer desbloqueo): aquí solo llegan el receptor de arranque y el
        // teclado, y el almacenamiento normal sigue cifrado (leer sus preferencias cierra la app). Lo justo,
        // con lo accesible antes de desbloquear; todo lo demás, en cuanto se desbloquea.
        if (!desbloqueado()) {
            val accesible = createDeviceProtectedStorageContext()
            runCatching { com.sios.ui.Marca.iniciar(accesible) }
            runCatching { com.sios.ui.Apariencia.leer(accesible) }
            val alDesbloquear = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                    runCatching { unregisterReceiver(this) }
                    iniciar()
                }
            }
            val filtro = android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(alDesbloquear, filtro, RECEIVER_EXPORTED) else registerReceiver(alDesbloquear, filtro)
            return
        }
        iniciar()
    }

    private fun desbloqueado() = getSystemService(android.os.UserManager::class.java)?.isUserUnlocked != false

    private fun iniciar() {
        com.sios.ui.Marca.iniciar(this) // la IA elegida: nombre, color y logo
        com.sios.ui.Apariencia.leer(this) // claro u oscuro y el acento, antes de la primera pantalla
        Servicio.iniciar()
        // Depurar los widgets de diseño libre desde el ordenador (solo en compilaciones de pruebas).
        if (android.os.Build.TYPE != "user") android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        // Tras instalar o actualizar la app (sin reiniciar), las manos de Claude quedan activas.
        runCatching { com.sios.sistema.Manos.activarUnaVez(this) }
        runCatching { com.sios.sistema.SistemaSios.botonEncendido(this) }
        runCatching { com.sios.sistema.IndexarJob.programar(this) }
        runCatching { com.sios.sistema.Disparadores.iniciar(this) }
        runCatching { com.sios.sistema.OyeAsistente.alArrancar(this) }
        runCatching { com.sios.teclado.Teclados.deSerie(this) } // el teclado de SIOS de serie, una sola vez
        runCatching { com.sios.teclado.Teclados.habilitar(this) }
        runCatching { com.sios.sistema.ColoresSistema.vigilar(this) }
        runCatching { com.sios.sistema.Rendimiento.alArrancar(this) }
        // Encontrar mi móvil: avisar al ordenador si cambian la SIM.
        runCatching { com.sios.sistema.Antirrobo.vigilarSim(this) }
        runCatching { com.sios.sistema.Intruso.vigilarDesbloqueo(this) }
        runCatching { com.sios.sistema.Capturas.vigilar(this) }
        runCatching { com.sios.sistema.Continuidad.arrancar(this) }
        runCatching { com.sios.cortafuegos.Cortafuegos.alArrancar(this) } // si estaba encendido (al arrancar o tras actualizar)
        runCatching { com.sios.sistema.ModoEscritorio.alArrancar(this) } // el monitor como ordenador, de serie (una vez)
        runCatching { com.sios.boveda.Boveda.alArrancar(this) } // la Bóveda, autocompletar de serie (una sola vez)
        com.sios.sistema.Fondos.aplicar(this) // el fondo de SIOS elegido
        runCatching { com.sios.sistema.SistemaSios.aplicarTema(this) } // y su color en la paleta del sistema
        runCatching { com.sios.sistema.SistemaSios.ponerNombre(this) }
        Thread { com.sios.sistema.ColoresSistema.arreglarTelecom(this) }.start()
    }

    // Cambió el modo claro/oscuro: la paleta de Claude se actualiza (las pantallas se rehacen solas).
    override fun onConfigurationChanged(nueva: android.content.res.Configuration) {
        super.onConfigurationChanged(nueva)
        runCatching { com.sios.ui.Apariencia.leer(if (desbloqueado()) this else createDeviceProtectedStorageContext()) }
    }
}
