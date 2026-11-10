package com.sios.inicio

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.creaciones.ImportarActivity
import com.sios.datos.Servicio
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.launch
import org.json.JSONArray

private val COLOR = mapOf("naranja" to 0xFFD97757, "verde" to 0xFF7CBF8E, "azul" to 0xFF7AA7E0, "amarillo" to 0xFFE8C46A, "rojo" to 0xFFE0816F, "gris" to 0xFFC2C0B6, "lila" to 0xFFA88FDB)

/** Widgets que comparte la gente (solo de fuentes integradas). Al tocar uno, la pantalla de importar. */
@Composable
internal fun ListaComunidad(onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    var orden by remember { mutableStateOf("populares") }
    var estado by remember { mutableStateOf<Any?>(null) } // null cargando · JSONArray · String (error)
    var abriendo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(orden) {
        estado = null
        val r = Servicio.comunidad(orden)
        estado = r?.optJSONArray("widgets") ?: (r?.optString("error")?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.comunidad_sin_conexion))
    }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("populares" to R.string.comunidad_populares, "nuevos" to R.string.comunidad_nuevos).forEach { (o, t) ->
                val activo = o == orden
                Box(
                    Modifier.height(34.dp).clip(Formas.Pastilla).background(if (activo) Colores.Texto else Colores.Superficie)
                        .pulsable { orden = o }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(t), style = Texto.Pequeno, color = if (activo) Colores.Fondo else Colores.Texto2) }
            }
        }
        Spacer(Modifier.height(12.dp))
        AnimatedContent(estado, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "comunidad") { e ->
            when (e) {
                null -> Text(stringResource(R.string.comunidad_cargando), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(vertical = 28.dp).fillMaxWidth(), textAlign = TextAlign.Center)
                is String -> Text(e, style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(vertical = 28.dp).fillMaxWidth(), textAlign = TextAlign.Center)
                is JSONArray -> if (e.length() == 0) {
                    Text(stringResource(R.string.comunidad_vacia), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(vertical = 28.dp).fillMaxWidth(), textAlign = TextAlign.Center)
                } else Column {
                    for (i in 0 until e.length()) {
                        val w = e.getJSONObject(i)
                        val id = w.optString("id")
                        Row(
                            Modifier.fillMaxWidth().clip(Formas.Tarjeta).pulsable {
                                if (abriendo != null) return@pulsable
                                abriendo = id
                                alcance.launch {
                                    val enlace = Servicio.comunidadUno(id)?.optString("enlace").orEmpty()
                                    abriendo = null
                                    if (enlace.startsWith("sios://")) {
                                        ctx.startActivity(Intent(ctx, ImportarActivity::class.java).setData(Uri.parse(enlace)).putExtra(ImportarActivity.EXTRA_COMUNIDAD, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        onCerrar()
                                    }
                                }
                            }.padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(Formas.Pequena).background(Color(COLOR[w.optString("acento")] ?: 0xFFD97757).copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Iconos.Widget, null, tint = Color(COLOR[w.optString("acento")] ?: 0xFFD97757), modifier = Modifier.size(18.dp)) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(w.optString("nombre"), style = Texto.Subtitulo.copy(fontSize = 15.sp), color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val usos = w.optInt("usos")
                                Text(
                                    listOf(w.optString("descripcion"), if (usos > 0) ctx.resources.getQuantityString(R.plurals.comunidad_usos, usos, usos) else null).filter { !it.isNullOrBlank() }.joinToString(" · "),
                                    style = Texto.Pequeno, color = Colores.Texto3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            if (abriendo == id) Text("…", style = Texto.Subtitulo, color = Colores.Texto3)
                            else Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                else -> Unit
            }
        }
    }
}
