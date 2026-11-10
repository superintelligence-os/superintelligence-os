package com.sios.boveda

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sios.R

/**
 * Recuadro «Bóveda» en los ajustes rápidos: abre la Bóveda en los códigos (para copiar uno en un momento)
 * y, si está abierta, la cierra al momento. Mantenerlo pulsado lleva también a la Bóveda.
 */
class BovedaTile : TileService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onStartListening() = pintar()

    override fun onClick() {
        if (Boveda.abierta()) {
            Boveda.cerrar()
            pintar()
            return
        }
        val i = Intent(this, BovedaActivity::class.java).putExtra(BovedaActivity.EXTRA_PESTANA, 1).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 4714, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }

    private fun pintar() {
        val t = qsTile ?: return
        val abierta = Boveda.abierta()
        t.icon = Icon.createWithResource(this, R.drawable.ic_boveda)
        t.label = getString(R.string.bv_titulo)
        t.subtitle = getString(if (abierta) R.string.bv_recuadro_abierta else R.string.bv_recuadro_cerrada)
        t.state = if (abierta) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
