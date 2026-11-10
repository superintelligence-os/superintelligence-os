package com.sios.sistema

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.sios.R
import com.sios.chat.ChatActivity
import com.sios.datos.Servicio
import com.sios.datos.Uso
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Recuadro de los ajustes rápidos con el uso de la suscripción.
 * Se actualiza al abrir el panel con `/usage` de Claude Code, que no gasta suscripción.
 */
class UsoTile : TileService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private var alcance: CoroutineScope? = null

    override fun onStartListening() {
        pintar(Servicio.estado.value.uso)
        alcance = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { a ->
            a.launch { pintar(Servicio.uso(refrescar = true)) }
        }
    }

    override fun onStopListening() {
        alcance?.cancel()
        alcance = null
    }

    override fun onClick() {
        val intent = Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    }

    private fun pintar(uso: Uso?) {
        val tile = qsTile ?: return
        val partes = listOfNotNull(
            uso?.cincoHoras?.let { getString(R.string.uso_cinco_horas_corto, it.porcentaje) },
            uso?.semana?.let { getString(R.string.uso_semana_corto, it.porcentaje) },
        )
        val marca = com.sios.ui.Marca
        tile.label = marca.nombre
        tile.subtitle = if (marca.id != "claude") {
            // Con otra IA no hay plan de Claude que medir: se enseña el modelo en uso.
            val ia = Servicio.ia.value
            val p = ia?.optJSONArray("proveedores")?.let { l -> (0 until l.length()).map { l.getJSONObject(it) }.firstOrNull { it.optString("id") == ia.optString("activo") } }
            p?.optString("modelo")?.takeIf { it.isNotBlank() && it != "auto" && it != "null" } ?: p?.optString("empresa") ?: ""
        } else if (partes.isEmpty()) getString(R.string.uso_tile_sin_datos) else partes.joinToString(" · ")
        tile.stateDescription = tile.subtitle
        tile.icon = Icon.createWithResource(this, com.sios.ui.Marca.recurso())
        tile.state = Tile.STATE_ACTIVE
        tile.updateTile()
    }
}
