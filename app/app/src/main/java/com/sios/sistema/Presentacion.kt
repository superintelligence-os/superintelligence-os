package com.sios.sistema

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import kotlinx.coroutines.launch

/** Qué se ve en cada página: título, explicación y frases de ejemplo que se le pueden decir a Claude. */
private enum class Pagina(@StringRes val titulo: Int, @StringRes val texto: Int, val ejemplos: List<Int>) {
    Portada(R.string.tour_portada_titulo, R.string.tour_portada_texto, emptyList()),
    Pedir(R.string.tour_pedir_titulo, R.string.tour_pedir_texto, listOf(R.string.tour_pedir_1, R.string.tour_pedir_2, R.string.tour_pedir_3)),
    Apps(R.string.tour_apps_titulo, R.string.tour_apps_texto, listOf(R.string.tour_apps_1, R.string.tour_apps_2, R.string.tour_apps_3)),
    Avisos(R.string.tour_avisos_titulo, R.string.tour_avisos_texto, listOf(R.string.tour_avisos_1, R.string.tour_avisos_2, R.string.tour_avisos_3)),
    Memoria(R.string.tour_memoria_titulo, R.string.tour_memoria_texto, listOf(R.string.tour_memoria_1, R.string.tour_memoria_2, R.string.tour_memoria_3)),
    Solo(R.string.tour_solo_titulo, R.string.tour_solo_texto, listOf(R.string.tour_solo_1, R.string.tour_solo_2, R.string.tour_solo_3)),
    Control(R.string.tour_control_titulo, R.string.tour_control_texto, emptyList()),
}

object Tour {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sios", Context.MODE_PRIVATE)
    fun visto(ctx: Context) = prefs(ctx).getBoolean("tour_visto", false)
    fun marcarVisto(ctx: Context) = prefs(ctx).edit().putBoolean("tour_visto", true).apply()
}

/** La presentación también se puede volver a ver desde Ajustes de SIOS. */
class PresentacionActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TemaSios { Presentacion(final = R.string.comun_cerrar) { finish() } } }
    }
}

/**
 * Qué es SIOS y qué puede hacer Claude en el móvil, en siete páginas que se pasan deslizando.
 * Va al principio del primer arranque y en Ajustes. final: el texto del botón de la última página.
 */
@Composable
fun Presentacion(@StringRes final: Int = R.string.tour_empezar, onTerminar: () -> Unit) {
    val paginas = Pagina.entries
    val estado = rememberPagerState { paginas.size }
    val alcance = rememberCoroutineScope()
    val ultima = estado.currentPage == paginas.lastIndex

    Column(
        Modifier
            .fillMaxSize()
            .background(Colores.Fondo)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        HorizontalPager(estado, Modifier.weight(1f)) { i ->
            val p = paginas[i]
            // Cuánto falta para que esta página quede centrada: el texto entra con un poco de retraso.
            val desvio = (estado.currentPage - i + estado.currentPageOffsetFraction).coerceIn(-1f, 1f)
            // Ilustración siempre a la misma altura y texto debajo: nada salta al pasar de página.
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = 24.dp).height(300.dp), contentAlignment = Alignment.Center) {
                    Ilustracion(p, Modifier.size(270.dp))
                }
                Spacer(Modifier.height(28.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .alpha(1f - kotlin.math.abs(desvio))
                        .padding(bottom = 8.dp),
                ) {
                    Text(stringResource(p.titulo), style = Texto.Titulo.copy(fontSize = 26.sp, lineHeight = 32.sp), color = Colores.Texto)
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(p.texto), style = Texto.Cuerpo, color = Colores.Texto2)
                    if (p.ejemplos.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (e in p.ejemplos) Ejemplo(stringResource(e))
                        }
                    }
                }
            }
        }

        // Puntos de página: el actual se alarga en naranja.
        Row(
            Modifier.align(Alignment.CenterHorizontally).padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            paginas.indices.forEach { i ->
                val activa = i == estado.currentPage
                val ancho by animateDpAsState(if (activa) 22.dp else 6.dp, Muelle.suave(), label = "punto")
                Box(Modifier.size(width = ancho, height = 6.dp).clip(Formas.Pastilla).background(if (activa) Colores.Acento else Colores.Superficie2))
            }
        }

        // Dos huecos del mismo tamaño: saltar (o atrás), solo texto, y seguir, relleno.
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (estado.currentPage == 0) {
                com.sios.ui.BotonTexto(stringResource(R.string.tour_saltar), Modifier.weight(1f), onTerminar)
            } else {
                com.sios.ui.BotonTexto(stringResource(R.string.tour_atras), Modifier.weight(1f)) {
                    alcance.launch { estado.animateScrollToPage(estado.currentPage - 1, animationSpec = Muelle.suave()) }
                }
            }
            BotonSecundario(
                stringResource(
                    when {
                        ultima -> final
                        estado.currentPage == 0 -> R.string.tour_ver
                        else -> R.string.tour_siguiente
                    },
                ),
                Modifier.weight(1f),
            ) {
                if (ultima) onTerminar() else alcance.launch { estado.animateScrollToPage(estado.currentPage + 1, animationSpec = Muelle.suave()) }
            }
        }
    }
}

/** Una frase de ejemplo, como un mensaje a Claude. */
@Composable
private fun Ejemplo(texto: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Formas.Pequena)
            .background(Colores.Superficie)
            .border(1.dp, Colores.Borde, Formas.Pequena)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(10.dp))
        Text("«$texto»", style = Texto.Pequeno, color = Colores.Texto)
    }
}

// ---------- Ilustraciones animadas (todas con la misma estética: los grises del sistema y el acento de la IA) ----------

@Composable
private fun Ilustracion(p: Pagina, modifier: Modifier) {
    val reloj = rememberInfiniteTransition(label = "reloj")
    val t by reloj.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "t")
    val respira by reloj.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "respira")
    val logo = rememberVectorPainter(Iconos.Asistente)
    val icono = mapOf(
        "telefono" to rememberVectorPainter(Iconos.Telefono),
        "hecho" to rememberVectorPainter(Iconos.Hecho),
        "buscar" to rememberVectorPainter(Iconos.Buscar),
        "micro" to rememberVectorPainter(Iconos.Micro),
        "parar" to rememberVectorPainter(Iconos.Parar),
    )
    val naranja = Colores.Acento

    Canvas(modifier) {
        val c = center
        fun pintarLogo(tam: Float, en: Offset, giro: Float = 0f, alfa: Float = 1f) {
            translate(en.x - tam / 2, en.y - tam / 2) {
                rotate(giro, Offset(tam / 2, tam / 2)) {
                    with(logo) { draw(Size(tam, tam), alpha = alfa, colorFilter = ColorFilter.tint(naranja)) }
                }
            }
        }
        fun pintarIcono(nombre: String, tam: Float, en: Offset, color: androidx.compose.ui.graphics.Color) {
            translate(en.x - tam / 2, en.y - tam / 2) { with(icono.getValue(nombre)) { draw(Size(tam, tam), colorFilter = ColorFilter.tint(color)) } }
        }
        fun brillo(en: Offset, radio: Float, fuerza: Float, color: androidx.compose.ui.graphics.Color = naranja) =
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.35f * fuerza), color.copy(alpha = 0f)), en, radio), radio, en)

        // Móvil esquemático para las páginas que lo usan.
        val movil = Size(size.width * 0.5f, size.height * 0.92f)
        val origen = Offset(c.x - movil.width / 2, c.y - movil.height / 2)
        val radioMovil = 26.dp.toPx()
        fun pintarMovil() {
            drawRoundRect(Colores.Lateral, origen, movil, CornerRadius(radioMovil))
            drawRoundRect(Colores.BordeFuerte, origen, movil, CornerRadius(radioMovil), style = Stroke(1.5.dp.toPx()))
        }
        fun barra(y: Float, ancho: Float, alfa: Float = 1f, color: androidx.compose.ui.graphics.Color = Colores.Superficie2, x: Float = origen.x + 14.dp.toPx()) =
            drawRoundRect(color.copy(alpha = color.alpha * alfa), Offset(x, y), Size(ancho, 7.dp.toPx()), CornerRadius(4.dp.toPx()))

        when (p) {
            Pagina.Portada -> {
                // El símbolo de SIOS: el punto (la IA) late con su color y un brillo detrás.
                val tam = size.minDimension * 0.62f
                val origenS = Offset(c.x - tam / 2, c.y - tam / 2)
                val enPunto = Offset(origenS.x + com.sios.ui.SimboloSios.punto.x * tam / 100f, origenS.y + com.sios.ui.SimboloSios.punto.y * tam / 100f)
                // Sin IA elegida, el punto lleva el espectro de todas y el brillo, su violeta.
                val sinElegir = com.sios.ui.Marca.sinElegir
                brillo(enPunto, tam * 0.42f, 0.6f + 0.4f * respira, if (sinElegir) androidx.compose.ui.graphics.Color(0xFF7B6CF6) else naranja)
                with(com.sios.ui.SimboloSios) { pintar(tam, origenS, Colores.Texto, naranja, escalaPunto = 1f + 0.06f * respira, espectro = sinElegir) }
            }

            Pagina.Pedir -> {
                pintarMovil()
                // Respuesta que se va escribiendo
                val lineas = listOf(0.78f, 0.62f, 0.7f)
                lineas.forEachIndexed { i, f ->
                    val aparece = ((t * 4f - i * 0.6f)).coerceIn(0f, 1f)
                    barra(origen.y + 40.dp.toPx() + i * 16.dp.toPx(), (movil.width - 28.dp.toPx()) * f * aparece, alfa = aparece)
                }
                // Hoja con la caja de Claude y el orbe de voz latiendo
                val hojaY = origen.y + movil.height * 0.62f
                drawRoundRect(Colores.Fondo, Offset(origen.x, hojaY), Size(movil.width, movil.height - (hojaY - origen.y)), CornerRadius(radioMovil))
                val caja = Offset(origen.x + 12.dp.toPx(), origen.y + movil.height - 52.dp.toPx())
                drawRoundRect(Colores.Superficie, caja, Size(movil.width - 24.dp.toPx(), 34.dp.toPx()), CornerRadius(17.dp.toPx()))
                val orbe = Offset(caja.x + 17.dp.toPx(), caja.y + 17.dp.toPx())
                brillo(orbe, 22.dp.toPx(), 0.6f + 0.4f * respira)
                drawCircle(naranja, 6.dp.toPx() + 2.dp.toPx() * respira, orbe)
                barra(caja.y + 13.dp.toPx(), movil.width * 0.42f, color = Colores.Texto3.copy(alpha = 0.5f), x = orbe.x + 16.dp.toPx())
            }

            Pagina.Apps -> {
                pintarMovil()
                barra(origen.y + 44.dp.toPx(), movil.width * 0.6f)
                barra(origen.y + 64.dp.toPx(), movil.width * 0.45f)
                barra(origen.y + 84.dp.toPx(), movil.width * 0.55f)
                // Borde naranja que gira (como el halo real): el degradado gira, el marco no.
                val grosor = 3.dp.toPx()
                val anillo = androidx.compose.ui.graphics.Path().apply {
                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(origen.x, origen.y, origen.x + movil.width, origen.y + movil.height, CornerRadius(radioMovil)))
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(origen.x + grosor, origen.y + grosor, origen.x + movil.width - grosor, origen.y + movil.height - grosor, CornerRadius(radioMovil - grosor)))
                }
                clipPath(anillo) {
                    rotate(t * 360f, c) {
                        drawCircle(
                            Brush.sweepGradient(listOf(naranja, naranja.copy(alpha = 0.25f), Colores.Texto, naranja.copy(alpha = 0.35f), naranja), c),
                            radius = movil.height, center = c,
                        )
                    }
                }
                // Isla «Claude al mando»
                val isla = Size(movil.width * 0.56f, 14.dp.toPx())
                drawRoundRect(Colores.Fondo, Offset(c.x - isla.width / 2, origen.y + 10.dp.toPx()), isla, CornerRadius(7.dp.toPx()))
                drawRoundRect(naranja.copy(alpha = 0.6f), Offset(c.x - isla.width / 2, origen.y + 10.dp.toPx()), isla, CornerRadius(7.dp.toPx()), style = Stroke(1.dp.toPx()))
                // Toques que aparecen y se expanden
                val puntos = listOf(Offset(0.35f, 0.42f), Offset(0.62f, 0.62f), Offset(0.45f, 0.82f))
                val fase = (t * 3f) % 1f
                val p0 = puntos[(t * 3f).toInt() % 3]
                val toque = Offset(origen.x + movil.width * p0.x, origen.y + movil.height * p0.y)
                brillo(toque, 30.dp.toPx(), 1f - fase)
                drawCircle(Colores.Texto.copy(alpha = (1f - fase) * 0.9f), 7.dp.toPx(), toque)
                drawCircle(naranja.copy(alpha = 1f - fase), 7.dp.toPx() + 22.dp.toPx() * fase, toque, style = Stroke(2.dp.toPx()))
            }

            Pagina.Avisos -> {
                pintarMovil()
                val alto = 38.dp.toPx()
                for (i in 0 until 3) {
                    val y = origen.y + 52.dp.toPx() + i * (alto + 8.dp.toPx())
                    drawRoundRect(Colores.Superficie, Offset(origen.x + 10.dp.toPx(), y), Size(movil.width - 20.dp.toPx(), alto), CornerRadius(10.dp.toPx()))
                    drawCircle(Colores.Superficie2, 8.dp.toPx(), Offset(origen.x + 26.dp.toPx(), y + alto / 2))
                    barra(y + 10.dp.toPx(), movil.width * 0.4f, x = origen.x + 40.dp.toPx())
                    drawRoundRect(Colores.Superficie2, Offset(origen.x + 40.dp.toPx(), y + 22.dp.toPx()), Size(movil.width * 0.35f, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                }
                // El resumen de Claude entra desde arriba y se queda con su brillo
                val entra = (t * 2f).coerceAtMost(1f)
                val y = origen.y + 12.dp.toPx() - (1f - FastOutSlowInEasing.transform(entra)) * 30.dp.toPx()
                val tarjeta = Offset(origen.x + 10.dp.toPx(), y)
                val tamTarjeta = Size(movil.width - 20.dp.toPx(), 34.dp.toPx())
                drawRoundRect(Colores.Fondo.copy(alpha = entra), tarjeta, tamTarjeta, CornerRadius(10.dp.toPx()))
                drawRoundRect(naranja.copy(alpha = 0.7f * entra), tarjeta, tamTarjeta, CornerRadius(10.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                pintarLogo(14.dp.toPx(), Offset(tarjeta.x + 16.dp.toPx(), tarjeta.y + 17.dp.toPx()), alfa = entra)
                drawRoundRect(Colores.Texto2.copy(alpha = 0.6f * entra), Offset(tarjeta.x + 30.dp.toPx(), tarjeta.y + 14.dp.toPx()), Size(movil.width * 0.48f, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
            }

            Pagina.Memoria -> {
                pintarMovil()
                val lado = (movil.width - 20.dp.toPx() - 2 * 6.dp.toPx()) / 3
                val elegida = ((t * 4f).toInt() % 9)
                for (f in 0 until 3) for (k in 0 until 3) {
                    val o = Offset(origen.x + 10.dp.toPx() + k * (lado + 6.dp.toPx()), origen.y + 44.dp.toPx() + f * (lado + 6.dp.toPx()))
                    val i = f * 3 + k
                    drawRoundRect(if (i % 2 == 0) Colores.Superficie else Colores.Superficie2, o, Size(lado, lado), CornerRadius(6.dp.toPx()))
                    if (i == elegida) {
                        brillo(Offset(o.x + lado / 2, o.y + lado / 2), lado, 0.8f)
                        drawRoundRect(naranja, o, Size(lado, lado), CornerRadius(6.dp.toPx()), style = Stroke(2.dp.toPx()))
                    }
                }
                // Lupa de Claude buscando
                val lupa = Offset(origen.x + movil.width * 0.5f, origen.y + movil.height * 0.8f)
                drawCircle(Colores.Fondo, 20.dp.toPx(), lupa)
                drawCircle(naranja.copy(alpha = 0.6f), 20.dp.toPx(), lupa, style = Stroke(1.5.dp.toPx()))
                pintarIcono("buscar", 18.dp.toPx(), lupa, naranja)
            }

            Pagina.Solo -> {
                // Disparador → Claude → hecho, con un punto naranja que recorre la línea
                val y = c.y
                val xs = listOf(size.width * 0.16f, c.x, size.width * 0.84f)
                drawLine(Colores.Superficie2, Offset(xs[0], y), Offset(xs[2], y), 3.dp.toPx())
                val viaje = FastOutSlowInEasing.transform((t * 1.25f).coerceAtMost(1f))
                val px = xs[0] + (xs[2] - xs[0]) * viaje
                drawLine(naranja, Offset(xs[0], y), Offset(px, y), 3.dp.toPx())
                brillo(Offset(px, y), 26.dp.toPx(), 1f)
                drawCircle(Colores.Texto, 5.dp.toPx(), Offset(px, y))
                listOf("telefono", "claude", "hecho").forEachIndexed { i, n ->
                    val o = Offset(xs[i], y)
                    val llegado = px >= xs[i] - 1f
                    drawCircle(Colores.Superficie, 30.dp.toPx(), o)
                    drawCircle(if (llegado) naranja else Colores.BordeFuerte, 30.dp.toPx(), o, style = Stroke(2.dp.toPx()))
                    if (n == "claude") pintarLogo(26.dp.toPx(), o, giro = if (llegado) t * 90f else 0f)
                    else pintarIcono(n, 24.dp.toPx(), o, if (llegado) naranja else Colores.Texto2)
                }
                // Ondas suaves alrededor de Claude mientras trabaja
                val onda = (t * 2f) % 1f
                drawCircle(naranja.copy(alpha = 0.35f * (1f - onda)), 30.dp.toPx() + 26.dp.toPx() * onda, Offset(xs[1], y), style = Stroke(1.5.dp.toPx()))
            }

            Pagina.Control -> {
                pintarMovil()
                // Tarjeta de confirmación: «¿Enviar a Marta?» con dos botones iguales
                val tarjeta = Offset(origen.x + 10.dp.toPx(), c.y - 36.dp.toPx())
                val tam = Size(movil.width - 20.dp.toPx(), 82.dp.toPx())
                drawRoundRect(Colores.Superficie, tarjeta, tam, CornerRadius(12.dp.toPx()))
                drawRoundRect(naranja.copy(alpha = 0.3f + 0.4f * respira), tarjeta, tam, CornerRadius(12.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                pintarLogo(14.dp.toPx(), Offset(tarjeta.x + 18.dp.toPx(), tarjeta.y + 18.dp.toPx()))
                drawRoundRect(Colores.Texto2.copy(alpha = 0.6f), Offset(tarjeta.x + 32.dp.toPx(), tarjeta.y + 15.dp.toPx()), Size(tam.width * 0.5f, 6.dp.toPx()), CornerRadius(3.dp.toPx()))
                val boton = Size((tam.width - 30.dp.toPx()) / 2, 24.dp.toPx())
                val yb = tarjeta.y + tam.height - boton.height - 12.dp.toPx()
                drawRoundRect(Colores.Superficie2, Offset(tarjeta.x + 10.dp.toPx(), yb), boton, CornerRadius(8.dp.toPx()))
                drawRoundRect(naranja, Offset(tarjeta.x + 20.dp.toPx() + boton.width, yb), boton, CornerRadius(8.dp.toPx()))
                // Isla con «Parar»
                val isla = Size(movil.width * 0.6f, 16.dp.toPx())
                val oi = Offset(c.x - isla.width / 2, origen.y + 10.dp.toPx())
                drawRoundRect(Colores.Fondo, oi, isla, CornerRadius(8.dp.toPx()))
                drawRoundRect(naranja.copy(alpha = 0.6f), oi, isla, CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
                pintarIcono("parar", 10.dp.toPx(), Offset(oi.x + isla.width - 12.dp.toPx(), oi.y + isla.height / 2), Colores.Texto)
            }
        }
    }
}
