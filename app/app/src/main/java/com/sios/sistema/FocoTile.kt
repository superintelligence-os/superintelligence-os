package com.sios.sistema

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sios.R
import java.text.DateFormat
import java.util.Date

/**
 * Recuadro «Foco de Claude» en los ajustes rápidos: una hora sin ruido. Pasan las llamadas, los
 * mensajes de personas, las alarmas y los códigos; lo demás espera y, al terminar, Claude te cuenta
 * lo que te perdiste.
 */
class FocoTile : TileService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onStartListening() = pintar()

    override fun onClick() {
        val b = Bandeja.instancia
        if (b == null) {
            pintar()
            return
        }
        if (Bandeja.focoHasta.value > System.currentTimeMillis()) b.terminarFoco() else {
            b.empezarFoco(60)
            com.sios.datos.Servicio.registrarActividad(
                getString(R.string.foco_registro), null,
                org.json.JSONObject().put("tipo", "puente").put("metodo", "foco").put("extras", org.json.JSONObject().put("minutos", "0")),
            )
        }
        pintar()
    }

    private fun pintar() {
        val t = qsTile ?: return
        val hasta = Bandeja.focoHasta.value
        val activo = hasta > System.currentTimeMillis()
        t.icon = Icon.createWithResource(this, com.sios.ui.Marca.recurso())
        t.label = getString(R.string.foco_titulo)
        t.subtitle = when {
            Bandeja.instancia == null -> getString(R.string.foco_sin_acceso)
            activo -> getString(R.string.foco_hasta, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(hasta)))
            else -> getString(R.string.foco_una_hora)
        }
        t.state = if (activo) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
