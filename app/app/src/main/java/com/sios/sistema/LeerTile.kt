package com.sios.sistema

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.sios.R

/**
 * «Leer pantalla» en los ajustes rápidos: lee en voz alta lo que hay en pantalla (sin Claude, al
 * momento). Tocarlo otra vez lo calla. Para quien ve poco o tiene las manos ocupadas.
 */
class LeerTile : TileService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onStartListening() = pintar(false)

    override fun onClick() {
        if (leyendo) {
            Voz.callar()
            leyendo = false
            pintar(false)
            return
        }
        val texto = Manos.instancia?.textoVisible().orEmpty()
        if (texto.isBlank()) {
            Toast.makeText(this, getString(if (Manos.instancia == null) R.string.leer_sin_manos else R.string.leer_nada), Toast.LENGTH_SHORT).show()
            return
        }
        leyendo = true
        pintar(true)
        Voz.decir(this, texto)
    }

    private fun pintar(activo: Boolean) {
        val t = qsTile ?: return
        t.icon = Icon.createWithResource(this, com.sios.ui.Marca.recurso())
        t.label = getString(R.string.leer_titulo)
        t.subtitle = getString(if (activo) R.string.leer_parar else R.string.leer_sub)
        t.state = if (activo) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }

    companion object { @Volatile var leyendo = false }
}
