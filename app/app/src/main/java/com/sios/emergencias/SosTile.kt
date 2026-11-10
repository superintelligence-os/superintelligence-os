package com.sios.emergencias

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sios.R

/**
 * Recuadro «SOS» de los ajustes rápidos: abre la cuenta atrás del SOS (con el móvil bloqueado
 * también: la pantalla del SOS se enseña encima del bloqueo). Mantenerlo pulsado lleva a Ajustes.
 */
class SosTile : TileService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onStartListening() {
        val t = qsTile ?: return
        val activo = SosServicio.estado.value?.activo == true
        t.icon = Icon.createWithResource(this, R.drawable.ic_sos)
        t.label = getString(R.string.em_tile)
        t.subtitle = getString(
            when {
                activo && SosServicio.estado.value?.modo == SosServicio.Modo.VIAJE -> R.string.em_tile_viaje
                activo -> R.string.em_tile_activo
                else -> R.string.em_tile_nota
            },
        )
        t.state = if (activo) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    override fun onClick() {
        val ver = SosServicio.estado.value?.let { it.activo && it.modo == SosServicio.Modo.SOS } == true
        val i = Intent(this, SosActivity::class.java)
            .putExtra(SosActivity.EXTRA_VER, ver)
            .putExtra(SosActivity.EXTRA_PRUEBA, Emergencias.modoPrueba(this))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}
