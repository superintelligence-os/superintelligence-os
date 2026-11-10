package com.sios.ajustes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sios.R
import com.sios.sistema.AppsDeSerie
import com.sios.ui.Colores
import com.sios.ui.Texto

/** Iconos de las páginas de apps y del modo escritorio (mismo trazo fino de 24×24 que [IconosAjustes]). */
internal object IconosTienda {
    private fun icono(nombre: String, vararg trazos: String) =
        ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f).apply {
            for (c in trazos) addPath(
                pathData = PathParser().parsePathString(c).toNodes(),
                stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    /** Una bolsa: la tienda y las apps. */
    val Bolsa = icono("bolsa", "M5 8h14l-1.1 11.1a2 2 0 0 1-2 1.9H8.1a2 2 0 0 1-2-1.9z", "M9 8V6.5a3 3 0 0 1 6 0V8")
    /** Un monitor: el modo escritorio. */
    val Monitor = icono("monitor", "M4 4.5h16a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 15V6A1.5 1.5 0 0 1 4 4.5z", "M12 16.5V20", "M8 20h8")
    val Navegador = icono("navegador", "M12 3a9 9 0 1 1 0 18a9 9 0 0 1 0-18z", "M3 12h18", "M12 3c2.5 2.6 3.8 5.6 3.8 9s-1.3 6.4-3.8 9c-2.5-2.6-3.8-5.6-3.8-9S9.5 5.6 12 3z")
    val Correo = icono("correo", "M4 5.5h16a1.5 1.5 0 0 1 1.5 1.5v10a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 17V7A1.5 1.5 0 0 1 4 5.5z", "M3 7l9 6.5L21 7")
    val Mapa = icono("mapa", "M9 4L3.5 6v14L9 18l6 2 5.5-2V4L15 6z", "M9 4v14", "M15 6v14")
    val Mensaje = icono("mensaje", "M4.5 5h15a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1-1.5 1.5H10l-4.5 3.5V17h-1A1.5 1.5 0 0 1 3 15.5v-9A1.5 1.5 0 0 1 4.5 5z")
}

/** El icono de una app ya dibujado (se recuerda por paquete: la lista no recompone para nada). */
@Composable
internal fun IconoApp(paquete: String?, alternativo: ImageVector, tam: Dp = 30.dp) {
    val ctx = LocalContext.current
    val imagen: ImageBitmap? = remember(paquete) { paquete?.let { AppsDeSerie.icono(ctx, it) }?.let { dibujar(it, 96) } }
    val forma = RoundedCornerShape(tam * 0.27f)
    if (imagen != null) Image(imagen, null, Modifier.size(tam).clip(forma))
    else Box(Modifier.size(tam).clip(forma).background(Colores.Superficie2), contentAlignment = Alignment.Center) {
        Icon(alternativo, null, tint = Colores.Texto2, modifier = Modifier.size(tam * 0.6f))
    }
}

private fun dibujar(d: android.graphics.drawable.Drawable, px: Int): ImageBitmap {
    val b = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    d.setBounds(0, 0, px, px)
    d.draw(c)
    return b.asImageBitmap()
}

/** El resumen de la portada: el navegador y la tienda libre. */
@Composable
internal fun resumenApps(refresco: Int): String {
    val ctx = LocalContext.current
    val navegador = remember(refresco) { AppsDeSerie.eleccion(ctx, AppsDeSerie.Tipo.NAVEGADOR).nombre }
    val tienda = remember(refresco) { AppsDeSerie.tiendaLibre(ctx)?.let { AppsDeSerie.nombre(ctx, it) } }
    return listOfNotNull(
        stringResource(R.string.tienda_r_navegador, navegador ?: stringResource(R.string.tienda_preguntar_corto)),
        tienda,
    ).joinToString(" · ")
}

/**
 * Apps: cuál se abre para cada cosa (navegador, correo, mapas, mensajes) y las tiendas. Se vuelve a leer
 * al volver de los ajustes del sistema, que es donde se cambian.
 */
@Composable
internal fun SeccionApps() {
    val ctx = LocalContext.current
    var vuelta by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { vuelta++; onPauseOrDispose { } }
    val elecciones = remember(vuelta) { AppsDeSerie.Tipo.entries.map { AppsDeSerie.eleccion(ctx, it) } }
    val tiendas = remember(vuelta) { AppsDeSerie.tiendas(ctx) }

    Grupo("predeterminadas", stringResource(R.string.tienda_g_predeterminadas), stringResource(R.string.tienda_g_predeterminadas_nota), Estilo.Lista) {
        elecciones.forEach { e ->
            val (titulo, icono) = when (e.tipo) {
                AppsDeSerie.Tipo.NAVEGADOR -> R.string.tienda_navegador to IconosTienda.Navegador
                AppsDeSerie.Tipo.CORREO -> R.string.tienda_correo to IconosTienda.Correo
                AppsDeSerie.Tipo.MAPAS -> R.string.tienda_mapas to IconosTienda.Mapa
                AppsDeSerie.Tipo.MENSAJES -> R.string.tienda_mensajes to IconosTienda.Mensaje
            }
            val nota = when {
                e.nombre != null -> e.nombre
                e.opciones == 0 -> stringResource(R.string.tienda_ninguna)
                else -> stringResource(R.string.tienda_preguntar)
            }
            FilaLista(stringResource(titulo), nota, separador = true, icono = { IconoApp(e.paquete, icono) }, notaLineas = 1) {
                AppsDeSerie.cambiar(ctx, e)
            }
        }
        FilaLista(stringResource(R.string.tienda_todas), stringResource(R.string.tienda_todas_nota), separador = false) { AppsDeSerie.todas(ctx) }
    }

    if (tiendas.isNotEmpty()) Grupo("tiendas", stringResource(R.string.tienda_g_tiendas), stringResource(R.string.tienda_g_tiendas_nota), Estilo.Lista) {
        tiendas.forEachIndexed { i, t ->
            val nota = stringResource(
                when (t.tipo) {
                    AppsDeSerie.TipoTienda.LIBRE -> if (t.paquete == AppsDeSerie.NEO_STORE) R.string.tienda_neo_nota else R.string.tienda_libre_nota
                    AppsDeSerie.TipoTienda.PLAY_SIN_CUENTA -> R.string.tienda_aurora_nota
                    AppsDeSerie.TipoTienda.PLAY -> R.string.tienda_play_nota
                },
            )
            FilaLista(t.nombre, nota, separador = i < tiendas.lastIndex, icono = { IconoApp(t.paquete, IconosTienda.Bolsa) }) {
                AppsDeSerie.abrirTienda(ctx, t.paquete)
            }
        }
    }
    // Las apps de serie se actualizan desde su tienda, no con SIOS: dicho una vez, abajo y en pequeño.
    Text(stringResource(R.string.tienda_actualizaciones_nota), style = Texto.Pequeno, color = Colores.Texto3)
}
