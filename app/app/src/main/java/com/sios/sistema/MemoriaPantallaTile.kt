package com.sios.sistema

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sios.R

/**
 * Recuadro «Memoria de pantalla» en los ajustes rápidos: pausar o seguir recordando de un toque.
 * Desactivada, lleva a su sitio en Ajustes de SIOS (para conocerla y activarla allí).
 */
class MemoriaPantallaTile : TileService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onStartListening() = pintar()

    override fun onClick() {
        if (!MemoriaPantalla.activa(this)) {
            val i = Intent(this, com.sios.ajustes.AjustesActivity::class.java)
                .putExtra("seccion", "pantalla").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 4713, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(i)
            }
            return
        }
        MemoriaPantalla.pausar(this, !MemoriaPantalla.pausada(this))
        pintar()
    }

    private fun pintar() {
        val t = qsTile ?: return
        val activa = MemoriaPantalla.activa(this)
        val pausada = MemoriaPantalla.pausada(this)
        t.icon = Icon.createWithResource(this, R.drawable.ic_memoria_pantalla)
        t.label = getString(R.string.mp_titulo)
        t.subtitle = getString(
            when {
                !activa -> R.string.mp_recuadro_apagada
                pausada -> R.string.mp_recuadro_pausada
                else -> R.string.mp_recuadro_activa
            },
        )
        // Desactivada no se marca como «no disponible»: tocarla lleva a activarla.
        t.state = if (activa && !pausada) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
