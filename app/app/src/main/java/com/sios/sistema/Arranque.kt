package com.sios.sistema

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * En SIOS, el tema oscuro de Claude se aplica en el primer encendido, antes de que aparezca el
 * asistente de bienvenida del sistema. Solo esa vez: si el usuario cambia el tema después, se respeta.
 * La marca se guarda en el almacenamiento accesible antes de desbloquear (arranque directo).
 */
class Arranque : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SistemaSios.esSios()) return
        SistemaSios.ponerNombre(context) // antes de que el asistente de bienvenida lo enseñe
        // Un móvil perdido que se reinicia vuelve a enseñar su mensaje nada más encenderse.
        Antirrobo.alArrancar(context)
        // Tras actualizar el sistema, la señal de que la app ha arrancado (sin ella se vuelve a la versión anterior).
        runCatching { SistemaOta.alArrancar(context) }
        // Con el móvil desbloqueado: arranca el agente (y, si el sistema se ha actualizado, lo pone al día).
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Preparacion.alDiaTrasActualizar(context)
            Manos.activarUnaVez(context)
            SistemaSios.botonEncendido(context)
        }
        // El teclado de SIOS de serie, una sola vez, ya desde el primer encendido (antes de desbloquear
        // también: así el asistente de bienvenida del sistema lo usa para el PIN y la wifi).
        runCatching { com.sios.teclado.Teclados.deSerie(context) }
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("arranque", Context.MODE_PRIVATE)
        if (prefs.getBoolean("tema_inicial", false)) return
        if (SistemaSios.aplicarTema(context)) prefs.edit().putBoolean("tema_inicial", true).apply()
    }
}
