package com.sios.sistema

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Claude como asistente digital del sistema (gesto o botón del asistente). */
class AsistenteServicio : VoiceInteractionService()

class SesionAsistenteServicio : VoiceInteractionSessionService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SesionAsistente(this)
}

/** Al invocar el asistente se abre la hoja de Claude sobre la app actual. */
class SesionAsistente(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // Mantener pulsado el botón de encendido (tipo de invocación 6): Claude escucha directamente.
        val porBoton = args?.getInt("invocation_type", -1) == 6
        startAssistantActivity(
            Intent(context, AsistenteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(AsistenteActivity.EXTRA_VOZ, porBoton),
        )
        hide()
    }
}

/**
 * Android exige un servicio de reconocimiento junto al de asistente (y lo pone como el de serie): es
 * el dictado de SIOS, en el móvil y sin conexión si está descargado (ver [com.sios.voz.ServicioDictado]).
 */
class Reconocimiento : com.sios.voz.ServicioDictado()
