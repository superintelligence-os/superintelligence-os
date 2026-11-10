package com.sios.cortafuegos

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sios.R

/**
 * Recuadro «Cortafuegos» de los ajustes rápidos: encender y apagar de un toque. Si hace falta el
 * permiso de VPN o hay otra VPN conectada, abre la pantalla del cortafuegos (para pedirlo o avisar
 * antes de cortar la otra VPN). Mantenerlo pulsado también la abre.
 */
class CortafuegosTile : TileService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onStartListening() = pintar()

    override fun onClick() {
        val e = Cortafuegos.estado.value
        if (e.encendido || e.arrancando) {
            Cortafuegos.apagar(this)
        } else if (Cortafuegos.permiso(this) != null || Cortafuegos.otraVpn(this)) {
            abrirPantalla()
            return
        } else {
            Cortafuegos.encender(this)
        }
        pintar()
    }

    private fun abrirPantalla() {
        val i = Intent(this, CortafuegosActivity::class.java)
            .putExtra(CortafuegosActivity.EXTRA_ENCENDER, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }

    private fun pintar() {
        val t = qsTile ?: return
        val e = Cortafuegos.estado.value
        t.icon = Icon.createWithResource(this, R.drawable.ic_cortafuegos)
        t.label = getString(R.string.cf_titulo)
        t.subtitle = when {
            e.arrancando -> getString(R.string.cf_encendiendo_corto)
            e.encendido -> Registro.bloqueadosHoy(this).let { n -> resources.getQuantityString(R.plurals.cf_recuadro_bloqueados, n, numero(n)) }
            else -> getString(R.string.cf_apagado)
        }
        t.state = if (e.encendido || e.arrancando) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
