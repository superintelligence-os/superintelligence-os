package com.sios.ajustes

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.sistema.MemoriaPantalla
import com.sios.sistema.MemoriaPantallaModelo
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sin

/** La presentación de la memoria de pantalla. Solo en su última página se puede activar. */
class MemoriaPantallaPresentacionActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TemaSios { PresentacionMemoriaPantalla(onCerrar = ::finish) } }
    }
}

/** Cómo empieza cada punto: lo que sí se guarda, lo que nunca o un dato. */
private enum class Tipo { Si, No, Dato }

private enum class PaginaMp(@StringRes val titulo: Int, @StringRes val texto: Int, val puntos: List<Pair<Tipo, Int>>) {
    Portada(R.string.mp_p1_titulo, R.string.mp_p1_texto, emptyList()),
    Guarda(R.string.mp_p2_titulo, R.string.mp_p2_texto, listOf(Tipo.Si to R.string.mp_p2_1, Tipo.Si to R.string.mp_p2_2, Tipo.Dato to R.string.mp_p2_3)),
    Nunca(
        R.string.mp_p3_titulo, R.string.mp_p3_texto,
        listOf(Tipo.No to R.string.mp_p3_1, Tipo.No to R.string.mp_p3_2, Tipo.No to R.string.mp_p3_3, Tipo.No to R.string.mp_p3_4, Tipo.No to R.string.mp_p3_5),
    ),
    Donde(R.string.mp_p4_titulo, R.string.mp_p4_texto, listOf(Tipo.Dato to R.string.mp_p4_1, Tipo.Dato to R.string.mp_p4_2, Tipo.Dato to R.string.mp_p4_3, Tipo.Dato to R.string.mp_p4_4)),
    Dura(R.string.mp_p5_titulo, R.string.mp_p5_texto, listOf(Tipo.Dato to R.string.mp_p5_1, Tipo.Dato to R.string.mp_p5_2)),
    Activar(R.string.mp_p6_titulo, R.string.mp_p6_texto, emptyList()),
}

private enum class Fase { Eligiendo, Activando, Hecha, Error }

@Composable
private fun PresentacionMemoriaPantalla(onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val paginas = PaginaMp.entries
    val estado = rememberPagerState { paginas.size }
    val alcance = rememberCoroutineScope()
    var dias by rememberSaveable { mutableIntStateOf(7) }
    var fase by rememberSaveable { mutableStateOf(if (MemoriaPantalla.activa(ctx)) Fase.Hecha else Fase.Eligiendo) }
    val ultima = estado.currentPage == paginas.lastIndex
    val bloqueo = remember { MemoriaPantalla.hayBloqueo(ctx) }

    fun activar(conModelo: Boolean) {
        if (fase == Fase.Activando) return
        fase = Fase.Activando
        alcance.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { MemoriaPantalla.activar(ctx, dias) }.isSuccess }
            if (ok && conModelo) MemoriaPantallaModelo.descargar(ctx)
            fase = if (ok) Fase.Hecha else Fase.Error
        }
    }

    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding()) {
        HorizontalPager(estado, Modifier.weight(1f), userScrollEnabled = fase != Fase.Activando) { i ->
            val p = paginas[i]
            val desvio = (estado.currentPage - i + estado.currentPageOffsetFraction).coerceIn(-1f, 1f)
            Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
                // La ilustración siempre a la misma altura: nada salta al pasar de página.
                Box(Modifier.fillMaxWidth().padding(top = 16.dp).height(230.dp), contentAlignment = Alignment.Center) {
                    IlustracionMp(p, Modifier.size(220.dp))
                }
                Spacer(Modifier.height(22.dp))
                Column(Modifier.fillMaxWidth().weight(1f).alpha(1f - abs(desvio)).verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                    Text(stringResource(p.titulo), style = Texto.Titulo.copy(fontSize = 26.sp, lineHeight = 32.sp), color = Colores.Texto)
                    Spacer(Modifier.height(10.dp))
                    val texto = if (p == PaginaMp.Activar) stringResource(p.texto, Formatter.formatShortFileSize(ctx, MemoriaPantallaModelo.TAMANO)) else stringResource(p.texto)
                    Text(texto, style = Texto.Cuerpo, color = Colores.Texto2)
                    if (p.puntos.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { for ((tipo, t) in p.puntos) Punto(tipo, stringResource(t)) }
                    }
                    if (p == PaginaMp.Dura) {
                        Spacer(Modifier.height(18.dp))
                        Text(stringResource(R.string.mp_dias_titulo).uppercase(), style = Texto.Etiqueta, color = Colores.Texto3)
                        Spacer(Modifier.height(8.dp))
                        SegmentosMp(listOf(stringResource(R.string.mp_dias_7), stringResource(R.string.mp_dias_30)), if (dias >= 30) 1 else 0) {
                            dias = if (it == 1) 30 else 7
                        }
                    }
                    if (p == PaginaMp.Activar) FinalActivar(fase, bloqueo)
                }
            }
        }

        // Puntos de página: el actual se alarga con el acento.
        Row(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            paginas.indices.forEach { i ->
                val activa = i == estado.currentPage
                val ancho by animateDpAsState(if (activa) 22.dp else 6.dp, Muelle.suave(), label = "punto")
                Box(Modifier.size(width = ancho, height = 6.dp).clip(Formas.Pastilla).background(if (activa) Colores.Acento else Colores.Superficie2))
            }
        }

        // Botones: avanzar en las páginas; en la última, activar (con o sin el modelo) y, después, «Hecho».
        AnimatedContent(
            when {
                !ultima -> 0
                fase == Fase.Hecha -> 2
                !bloqueo -> 3
                else -> 1
            },
            transitionSpec = { (fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave())).using(SizeTransform(clip = false) { _, _ -> Muelle.suave() }) },
            label = "botones",
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
        ) { caso ->
            when (caso) {
                0 -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (estado.currentPage == 0) BotonTexto(stringResource(R.string.mp_ahora_no), Modifier.weight(1f), onCerrar)
                    else BotonTexto(stringResource(R.string.tour_atras), Modifier.weight(1f)) {
                        alcance.launch { estado.animateScrollToPage(estado.currentPage - 1, animationSpec = Muelle.suave()) }
                    }
                    BotonSecundario(stringResource(R.string.tour_siguiente), Modifier.weight(1f)) {
                        alcance.launch { estado.animateScrollToPage(estado.currentPage + 1, animationSpec = Muelle.suave()) }
                    }
                }
                1 -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BotonPrimario(
                        stringResource(R.string.mp_activar_con, Formatter.formatShortFileSize(ctx, MemoriaPantallaModelo.TAMANO)),
                        Modifier.fillMaxWidth().alpha(if (fase == Fase.Activando) 0.6f else 1f),
                    ) { activar(conModelo = true) }
                    BotonTexto(stringResource(R.string.mp_activar_sin), Modifier.fillMaxWidth()) { activar(conModelo = false) }
                }
                2 -> BotonPrimario(stringResource(R.string.mp_hecho), Modifier.fillMaxWidth(), onCerrar)
                else -> BotonPrimario(stringResource(R.string.mp_poner_bloqueo), Modifier.fillMaxWidth()) {
                    ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            }
        }
    }
}

/** La última página, debajo del texto: avisos (sin bloqueo, datos móviles), licencias y el progreso. */
@Composable
private fun FinalActivar(fase: Fase, bloqueo: Boolean) {
    val ctx = LocalContext.current
    val descarga by MemoriaPantallaModelo.descarga.collectAsStateWithLifecycle()
    val pago = remember { MemoriaPantallaModelo.redDePago(ctx) }
    Spacer(Modifier.height(16.dp))
    AnimatedContent(
        fase,
        transitionSpec = { (fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave())).using(SizeTransform(clip = false) { _, _ -> Muelle.suave() }) },
        label = "fase",
    ) { f ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                !bloqueo -> AvisoMp(stringResource(R.string.mp_sin_bloqueo), null)
                f == Fase.Error -> AvisoMp(stringResource(R.string.mp_error_activar), null)
                f == Fase.Hecha -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Iconos.Hecho, null, tint = Colores.Acento, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.mp_activada), style = Texto.Subtitulo, color = Colores.Texto)
                    }
                    val d = descarga
                    val tamano = Formatter.formatShortFileSize(ctx, MemoriaPantallaModelo.TAMANO)
                    when (d) {
                        is MemoriaPantallaModelo.Descarga.EnCurso -> {
                            Text(stringResource(R.string.mp_descargando, Formatter.formatShortFileSize(ctx, d.hecho), tamano), style = Texto.Pequeno, color = Colores.Texto3)
                            BarraMp(d.hecho.toFloat() / d.total)
                            Text(stringResource(R.string.mp_descarga_sigue), style = Texto.Pequeno, color = Colores.Texto3)
                        }
                        MemoriaPantallaModelo.Descarga.Comprobando -> Text(stringResource(R.string.mp_comprobando), style = Texto.Pequeno, color = Colores.Texto3)
                        is MemoriaPantallaModelo.Descarga.Fallo -> AvisoMp(
                            stringResource(if (d.sinRed) R.string.mp_descarga_sin_red else R.string.mp_descarga_fallo),
                            stringResource(R.string.mp_reintentar),
                        ) { MemoriaPantallaModelo.descargar(ctx) }
                        else -> Unit
                    }
                }
                else -> {
                    if (pago) AvisoMp(stringResource(R.string.mp_red_pago), null)
                    Text(stringResource(R.string.mp_licencias), style = Texto.Pequeno, color = Colores.Texto3)
                }
            }
        }
    }
}

@Composable
private fun Punto(tipo: Tipo, texto: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 2.dp).size(20.dp).clip(Formas.Pastilla)
                .background(if (tipo == Tipo.Si) Colores.AcentoSuave else Colores.Superficie2),
            contentAlignment = Alignment.Center,
        ) {
            when (tipo) {
                Tipo.Si -> Icon(Iconos.Hecho, null, tint = Colores.Acento, modifier = Modifier.size(13.dp))
                Tipo.No -> Icon(Iconos.Cruz, null, tint = Colores.Texto2, modifier = Modifier.size(12.dp))
                Tipo.Dato -> Box(Modifier.size(5.dp).clip(Formas.Pastilla).background(Colores.Texto2))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(texto, style = Texto.Cuerpo.copy(fontSize = 14.sp, lineHeight = 21.sp), color = Colores.Texto, modifier = Modifier.weight(1f))
    }
}

// ---------- Ilustraciones animadas (la estética de la presentación de SIOS: grises y el acento) ----------

@Composable
private fun IlustracionMp(p: PaginaMp, modifier: Modifier) {
    val reloj = rememberInfiniteTransition(label = "reloj")
    val t by reloj.animateFloat(0f, 1f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "t")
    val respira by reloj.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "respira")
    val acento = Colores.Acento
    val lateral = Colores.Lateral
    val fondo = Colores.Fondo
    val sup = Colores.Superficie
    val sup2 = Colores.Superficie2
    val borde = Colores.BordeFuerte
    val texto = Colores.Texto
    val texto2 = Colores.Texto2
    val texto3 = Colores.Texto3

    Canvas(modifier) {
        val c = center
        val movil = Size(size.width * 0.5f, size.height * 0.94f)
        val origen = Offset(c.x - movil.width / 2, c.y - movil.height / 2)
        val radio = 24.dp.toPx()
        val formaMovil = RoundRect(Rect(origen, movil), CornerRadius(radio))
        fun pintarMovil() {
            drawRoundRect(lateral, origen, movil, CornerRadius(radio))
            drawRoundRect(borde, origen, movil, CornerRadius(radio), style = Stroke(1.5.dp.toPx()))
        }
        fun barra(x: Float, y: Float, ancho: Float, color: Color = sup2, alto: Float = 7.dp.toPx()) =
            drawRoundRect(color, Offset(x, y), Size(ancho, alto), CornerRadius(alto / 2))
        fun brillo(en: Offset, r: Float, fuerza: Float) =
            drawCircle(Brush.radialGradient(listOf(acento.copy(alpha = 0.35f * fuerza), acento.copy(alpha = 0f)), en, r), r, en)
        val m = 12.dp.toPx()

        when (p) {
            // Lo que pasó por la pantalla sube despacio; la lupa encuentra un momento y lo ilumina.
            PaginaMp.Portada -> {
                pintarMovil()
                val alto = 34.dp.toPx()
                val hueco = 8.dp.toPx()
                val paso = alto + hueco
                val n = 7
                val total = paso * n
                val lupa = Offset(c.x + movil.width * 0.2f, c.y + 6.dp.toPx())
                clipPath(Path().apply { addRoundRect(formaMovil) }) {
                    for (i in 0 until n) {
                        val y = origen.y + ((i * paso - t * total) % total + total) % total - alto
                        val o = Offset(origen.x + m, y)
                        val ancho = movil.width - 2 * m
                        val cerca = (1f - abs((y + alto / 2) - lupa.y) / (paso * 0.7f)).coerceIn(0f, 1f)
                        val elegida = i % 3 == 1
                        drawRoundRect(sup, o, Size(ancho, alto), CornerRadius(9.dp.toPx()))
                        if (elegida && cerca > 0f) drawRoundRect(acento.copy(alpha = cerca), o, Size(ancho, alto), CornerRadius(9.dp.toPx()), style = Stroke(1.6.dp.toPx()))
                        barra(o.x + 10.dp.toPx(), o.y + 9.dp.toPx(), ancho * (0.45f + 0.1f * (i % 3)), if (elegida) sup2.copy(alpha = 1f) else sup2)
                        barra(o.x + 10.dp.toPx(), o.y + 20.dp.toPx(), ancho * 0.3f, if (elegida && cerca > 0f) acento.copy(alpha = 0.35f + 0.5f * cerca) else sup2, 5.dp.toPx())
                    }
                }
                brillo(lupa, 44.dp.toPx(), 0.5f + 0.4f * respira)
                val r = 22.dp.toPx()
                drawCircle(fondo.copy(alpha = 0.35f), r, lupa)
                drawCircle(acento, r, lupa, style = Stroke(2.5.dp.toPx()))
                drawLine(acento, Offset(lupa.x + r * 0.72f, lupa.y + r * 0.72f), Offset(lupa.x + r * 1.35f, lupa.y + r * 1.35f), 4.dp.toPx(), StrokeCap.Round)
            }

            // El texto se subraya al guardarse; la imagen se convierte en su huella (solo números).
            PaginaMp.Guarda -> {
                pintarMovil()
                val ancho = movil.width - 2 * m
                for (i in 0 until 3) {
                    val y = origen.y + 30.dp.toPx() + i * 15.dp.toPx()
                    val f = listOf(0.8f, 0.62f, 0.7f)[i]
                    barra(origen.x + m, y, ancho * f)
                    val marca = ((t * 3.2f) - i * 0.35f).coerceIn(0f, 1f)
                    barra(origen.x + m, y + 8.dp.toPx(), ancho * f * marca, acento.copy(alpha = 0.8f), 2.dp.toPx())
                }
                val lado = ancho
                val o = Offset(origen.x + m, origen.y + 84.dp.toPx())
                val fase = ((t - 0.3f) / 0.35f).coerceIn(0f, 1f)
                val suave = FastOutSlowInEasing.transform(fase)
                // La imagen (un paisaje sencillo) se desvanece…
                val alfaImagen = 1f - suave
                if (alfaImagen > 0f) {
                    drawRoundRect(sup.copy(alpha = alfaImagen), o, Size(lado, lado * 0.8f), CornerRadius(10.dp.toPx()))
                    drawCircle(acento.copy(alpha = 0.7f * alfaImagen), 8.dp.toPx(), Offset(o.x + lado * 0.72f, o.y + lado * 0.24f))
                    val monte = Path().apply {
                        moveTo(o.x + lado * 0.08f, o.y + lado * 0.7f)
                        lineTo(o.x + lado * 0.36f, o.y + lado * 0.36f)
                        lineTo(o.x + lado * 0.56f, o.y + lado * 0.58f)
                        lineTo(o.x + lado * 0.68f, o.y + lado * 0.46f)
                        lineTo(o.x + lado * 0.92f, o.y + lado * 0.7f)
                        close()
                    }
                    drawPath(monte, texto3.copy(alpha = 0.6f * alfaImagen))
                }
                // …y queda su huella: una rejilla de puntos que late.
                if (suave > 0f) {
                    val n = 8
                    val paso = lado / n
                    for (f in 0 until 6) for (k in 0 until n) {
                        val semilla = ((f * 7 + k * 13) % 11) / 10f
                        val a = (0.25f + 0.75f * semilla) * suave * (0.75f + 0.25f * sin((t * 6.28f * 2) + semilla * 6f))
                        drawCircle(acento.copy(alpha = a.coerceIn(0f, 1f)), paso * 0.18f * (0.6f + semilla * 0.6f), Offset(o.x + paso * (k + 0.5f), o.y + lado * 0.08f + paso * (f + 0.5f)))
                    }
                }
            }

            // Un inicio de sesión: la contraseña queda tapada y el escudo late encima.
            PaginaMp.Nunca -> {
                pintarMovil()
                val ancho = movil.width - 2 * m
                val alto = 30.dp.toPx()
                val y1 = origen.y + 56.dp.toPx()
                val y2 = y1 + alto + 12.dp.toPx()
                drawRoundRect(sup, Offset(origen.x + m, y1), Size(ancho, alto), CornerRadius(8.dp.toPx()))
                barra(origen.x + m + 10.dp.toPx(), y1 + 11.dp.toPx(), ancho * 0.5f)
                drawRoundRect(sup, Offset(origen.x + m, y2), Size(ancho, alto), CornerRadius(8.dp.toPx()))
                for (i in 0 until 6) drawCircle(texto2.copy(alpha = 0.7f), 3.dp.toPx(), Offset(origen.x + m + 14.dp.toPx() + i * 11.dp.toPx(), y2 + alto / 2))
                // Rayado sobre lo privado.
                clipPath(Path().apply { addRoundRect(RoundRect(Rect(Offset(origen.x + m, y2), Size(ancho, alto)), CornerRadius(8.dp.toPx()))) }) {
                    var x = origen.x + m - alto
                    while (x < origen.x + m + ancho) {
                        drawLine(fondo.copy(alpha = 0.5f), Offset(x, y2 + alto), Offset(x + alto, y2), 3.dp.toPx())
                        x += 9.dp.toPx()
                    }
                }
                drawRoundRect(acento, Offset(origen.x + m, y2 + alto + 16.dp.toPx()), Size(ancho, 26.dp.toPx()), CornerRadius(8.dp.toPx()))
                // Escudo.
                val centro = Offset(c.x, origen.y + movil.height * 0.74f)
                val e = 30.dp.toPx() * (1f + 0.04f * respira)
                brillo(centro, e * 1.9f, 0.5f + 0.5f * respira)
                val escudo = Path().apply {
                    moveTo(centro.x, centro.y - e)
                    lineTo(centro.x + e * 0.85f, centro.y - e * 0.62f)
                    cubicTo(centro.x + e * 0.85f, centro.y + e * 0.2f, centro.x + e * 0.45f, centro.y + e * 0.8f, centro.x, centro.y + e)
                    cubicTo(centro.x - e * 0.45f, centro.y + e * 0.8f, centro.x - e * 0.85f, centro.y + e * 0.2f, centro.x - e * 0.85f, centro.y - e * 0.62f)
                    close()
                }
                drawPath(escudo, fondo)
                drawPath(escudo, acento, style = Stroke(2.2.dp.toPx()))
                val visto = Path().apply {
                    moveTo(centro.x - e * 0.35f, centro.y)
                    lineTo(centro.x - e * 0.08f, centro.y + e * 0.28f)
                    lineTo(centro.x + e * 0.38f, centro.y - e * 0.25f)
                }
                drawPath(visto, acento, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round))
            }

            // Lo que entra en el candado sale convertido en algo ilegible.
            PaginaMp.Donde -> {
                pintarMovil()
                val centro = Offset(c.x, c.y + 4.dp.toPx())
                val cuerpo = Size(56.dp.toPx(), 44.dp.toPx())
                // Texto que cae hacia el candado.
                for (i in 0 until 3) {
                    val f = ((t * 1.5f) + i / 3f) % 1f
                    val y = origen.y + 24.dp.toPx() + f * (centro.y - cuerpo.height / 2 - origen.y - 40.dp.toPx())
                    val a = (1f - f) * 0.9f
                    barra(c.x - 24.dp.toPx() + (i - 1) * 6.dp.toPx(), y, 48.dp.toPx() * (0.6f + 0.2f * i), texto3.copy(alpha = a), 6.dp.toPx())
                }
                brillo(centro, 70.dp.toPx(), 0.45f + 0.35f * respira)
                val arco = Path().apply {
                    moveTo(centro.x - cuerpo.width * 0.3f, centro.y - cuerpo.height * 0.1f)
                    lineTo(centro.x - cuerpo.width * 0.3f, centro.y - cuerpo.height * 0.55f)
                    cubicTo(centro.x - cuerpo.width * 0.3f, centro.y - cuerpo.height * 1.15f, centro.x + cuerpo.width * 0.3f, centro.y - cuerpo.height * 1.15f, centro.x + cuerpo.width * 0.3f, centro.y - cuerpo.height * 0.55f)
                    lineTo(centro.x + cuerpo.width * 0.3f, centro.y - cuerpo.height * 0.1f)
                }
                drawPath(arco, texto2, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                drawRoundRect(acento, Offset(centro.x - cuerpo.width / 2, centro.y - cuerpo.height * 0.1f), cuerpo, CornerRadius(10.dp.toPx()))
                drawCircle(fondo, 5.dp.toPx(), Offset(centro.x, centro.y + cuerpo.height * 0.32f))
                // Debajo, lo cifrado: piezas de anchos cambiantes.
                val base = centro.y + cuerpo.height + 22.dp.toPx()
                for (fila in 0 until 3) {
                    var x = origen.x + m
                    var k = 0
                    while (x < origen.x + movil.width - m - 6.dp.toPx()) {
                        val w = (6 + ((fila * 5 + k * 3 + (t * 10).toInt()) % 5) * 4).dp.toPx()
                        val ancho = minOf(w, origen.x + movil.width - m - x)
                        barra(x, base + fila * 13.dp.toPx(), ancho, sup2, 6.dp.toPx())
                        x += ancho + 4.dp.toPx()
                        k++
                    }
                }
            }

            // Una línea de tiempo: lo nuevo entra por la derecha y, pasado el límite, se apaga solo.
            PaginaMp.Dura -> {
                val y = c.y + 34.dp.toPx()
                val x0 = size.width * 0.04f
                val x1 = size.width * 0.9f
                val limite = size.width * 0.22f
                drawLine(sup2, Offset(x0, y), Offset(x1, y), 3.dp.toPx(), StrokeCap.Round)
                for (i in 0..8) {
                    val x = x0 + (x1 - x0) * i / 8f
                    drawLine(sup2, Offset(x, y - 6.dp.toPx()), Offset(x, y + 6.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
                drawLine(
                    texto3, Offset(limite, y - 118.dp.toPx()), Offset(limite, y + 22.dp.toPx()), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                )
                for (i in 0 until 6) {
                    val f = ((t + i / 6f) % 1f)
                    val x = x1 - f * (x1 - x0)
                    val alto = listOf(44f, 78f, 36f, 64f, 52f, 86f)[i].dp.toPx()
                    val desvanece = ((x - x0) / (limite - x0)).coerceIn(0f, 1f)
                    val color = if (i % 2 == 0) acento else texto2
                    drawRoundRect(color.copy(alpha = 0.85f * desvanece), Offset(x - 7.dp.toPx(), y - alto - 14.dp.toPx()), Size(14.dp.toPx(), alto), CornerRadius(7.dp.toPx()))
                }
                // «Ahora», a la derecha.
                brillo(Offset(x1, y), 26.dp.toPx(), 0.6f + 0.4f * respira)
                drawCircle(acento, 6.dp.toPx() + 1.5.dp.toPx() * respira, Offset(x1, y))
            }

            // La imagen y las palabras llegan al mismo punto: así se encuentra «el sofá azul».
            PaginaMp.Activar -> {
                val lado = size.width * 0.36f
                val img = Offset(size.width * 0.02f, c.y - lado * 1.02f)
                drawRoundRect(sup, img, Size(lado, lado), CornerRadius(12.dp.toPx()))
                val azul = Color(0xFF4A82DB)
                drawRoundRect(azul, Offset(img.x + lado * 0.14f, img.y + lado * 0.38f), Size(lado * 0.72f, lado * 0.22f), CornerRadius(6.dp.toPx()))
                drawRoundRect(azul.copy(alpha = 0.85f), Offset(img.x + lado * 0.1f, img.y + lado * 0.54f), Size(lado * 0.8f, lado * 0.2f), CornerRadius(6.dp.toPx()))
                drawRoundRect(texto3, Offset(img.x + lado * 0.16f, img.y + lado * 0.74f), Size(lado * 0.06f, lado * 0.1f), CornerRadius(2.dp.toPx()))
                drawRoundRect(texto3, Offset(img.x + lado * 0.78f, img.y + lado * 0.74f), Size(lado * 0.06f, lado * 0.1f), CornerRadius(2.dp.toPx()))
                val pastilla = Size(size.width * 0.42f, 38.dp.toPx())
                val txt = Offset(size.width * 0.02f, c.y + lado * 0.3f)
                drawRoundRect(sup, txt, pastilla, CornerRadius(17.dp.toPx()))
                barra(txt.x + 14.dp.toPx(), txt.y + 13.dp.toPx(), pastilla.width * 0.55f, texto3.copy(alpha = 0.7f))
                val meta = Offset(size.width * 0.84f, c.y)
                val desdeImg = Offset(img.x + lado, img.y + lado / 2)
                val desdeTxt = Offset(txt.x + pastilla.width, txt.y + pastilla.height / 2)
                for (d in listOf(desdeImg, desdeTxt)) {
                    drawLine(sup2, d, meta, 2.dp.toPx(), StrokeCap.Round)
                    val f = FastOutSlowInEasing.transform((t * 1.4f).coerceAtMost(1f))
                    val punto = Offset(d.x + (meta.x - d.x) * f, d.y + (meta.y - d.y) * f)
                    drawLine(acento, d, punto, 2.dp.toPx(), StrokeCap.Round)
                    drawCircle(texto, 3.5.dp.toPx(), punto)
                }
                val llegado = t * 1.4f >= 1f
                brillo(meta, 48.dp.toPx(), if (llegado) 0.6f + 0.4f * respira else 0.25f)
                drawCircle(fondo, 20.dp.toPx(), meta)
                drawCircle(if (llegado) acento else borde, 20.dp.toPx(), meta, style = Stroke(2.dp.toPx()))
                for (k in 0 until 3) drawCircle(acento.copy(alpha = if (llegado) 0.9f - k * 0.25f else 0.25f), (3 + k * 4).dp.toPx(), meta, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}
