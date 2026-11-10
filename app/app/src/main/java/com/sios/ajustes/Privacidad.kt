package com.sios.ajustes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.datos.Servicio
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import org.json.JSONObject

private fun tamano(b: Long) = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "%.1f MB".format(b / 1048576.0)
}

/**
 * «Qué ha salido de tu móvil»: a qué servidores se ha conectado el sistema estos días, para qué
 * y cuántos datos (nunca el contenido). Comprobable: lo apunta el propio servicio en cada conexión.
 */
@Composable
internal fun SeccionPrivacidad(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    var datos by remember { mutableStateOf<JSONObject?>(null) }
    var todo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { datos = Servicio.privacidad() }
    seccion(stringResource(R.string.priv_titulo), stringResource(R.string.priv_nota)) {
        val d = datos ?: return@seccion Text(stringResource(R.string.ia_cargando), style = Texto.Pequeno, color = Colores.Texto3)
        val lista = d.optJSONArray("destinos")
        val filas = (0 until (lista?.length() ?: 0)).map { lista!!.getJSONObject(it) }
        Column(Modifier.animateContentSize(Muelle.suave()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.optBoolean("local")) Text(
                stringResource(R.string.priv_local), style = Texto.Pequeno, color = Colores.Acento,
                modifier = Modifier.fillMaxWidth().clip(Formas.Pequena).background(Colores.AcentoSuave).padding(12.dp),
            )
            Text(stringResource(R.string.priv_resumen, d.optInt("total"), d.optInt("dias")), style = Texto.Pequeno, color = Colores.Texto3)
            if (filas.isEmpty()) Text(stringResource(R.string.priv_nada), style = Texto.Cuerpo, color = Colores.Texto2)
            for (f in if (todo) filas else filas.take(6)) {
                Row(
                    Modifier.fillMaxWidth().clip(Formas.Pequena).background(Colores.Superficie).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(f.optString("para"), style = Texto.Cuerpo.copy(fontWeight = FontWeight.Medium), color = Colores.Texto)
                        Text(f.optString("destino"), style = Texto.Pequeno.copy(fontFamily = FontFamily.Monospace), color = Colores.Texto3)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(stringResource(R.string.priv_veces, f.optInt("veces")), style = Texto.Pequeno, color = Colores.Texto2)
                        Text(tamano(f.optLong("bytes")), style = Texto.Pequeno, color = Colores.Texto3)
                    }
                }
            }
            if (filas.size > 6 && !todo) Text(
                stringResource(R.string.priv_ver_todo, filas.size), style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Acento,
                modifier = Modifier.clip(Formas.Pequena).pulsable { todo = true }.padding(vertical = 6.dp),
            )
        }
    }
}

/**
 * Seguridad de la pantalla de bloqueo: el teclado del PIN en orden aleatorio (lo trae LineageOS). Solo
 * aparece si el sistema lo permite.
 */
@Composable
internal fun SeccionSeguridad(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (!remember { com.sios.sistema.PinAleatorio.disponible(ctx) }) return
    seccion(stringResource(R.string.seg_titulo), null) { com.sios.sistema.OpcionPinAleatorio() }
}
