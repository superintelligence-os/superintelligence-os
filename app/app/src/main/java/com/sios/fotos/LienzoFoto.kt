package com.sios.fotos

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.sios.ui.Apariencia
import com.sios.ui.Colores
import com.sios.ui.Muelle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Dónde queda la foto en pantalla: la matriz y los rectángulos (en píxeles de pantalla). */
private class Colocacion(
    val aPantalla: Matrix,
    /** Lo visible (recorte o, al recortar, todo el marco). */
    val visible: RectF,
    val marco: RectF,
    val corte: RectF,
)

private fun interpolar(a: RectF, b: RectF, t: Float) =
    RectF(a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t, a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t)

/**
 * Coloca la foto: t = 0 enseña el recorte llenando la caja; t = 1, el marco entero (para recortar).
 * Entre medias, un zoom suave de uno a otro.
 */
private fun colocar(w0: Int, h0: Int, g: Geometria, corteN: RectF, t: Float, ancho: Float, alto: Float, margenH: Float, margenV: Float): Colocacion {
    val (w1, h1) = Encuadre.marco(w0, h0, g)
    val corte = RectF(corteN.left * w1, corteN.top * h1, corteN.right * w1, corteN.bottom * h1)
    val marco = RectF(0f, 0f, w1.toFloat(), h1.toFloat())
    val v = interpolar(corte, marco, t)
    val cajaW = max(1f, ancho - 2 * margenH)
    val cajaH = max(1f, alto - 2 * margenV)
    val s = min(cajaW / max(1f, v.width()), cajaH / max(1f, v.height()))
    val dx = margenH + (cajaW - v.width() * s) / 2 - v.left * s
    val dy = margenV + (cajaH - v.height() * s) / 2 - v.top * s
    val m = Encuadre.aMarco(w0, h0, g).apply { postScale(s, s); postTranslate(dx, dy) }
    fun pantalla(r: RectF) = RectF(r.left * s + dx, r.top * s + dy, r.right * s + dx, r.bottom * s + dy)
    val corteP = pantalla(corte)
    val marcoP = pantalla(marco)
    return Colocacion(m, interpolar(corteP, marcoP, t), marcoP, corteP)
}

private enum class Agarre { NADA, MOVER, IZQ_ARR, DER_ARR, IZQ_ABA, DER_ABA, IZQ, DER, ARR, ABA }

/** Mueve el recorte según lo agarrado. pn: proporción fija (ancho/alto en fracciones del marco). */
private fun moverRecorte(r0: RectF, agarre: Agarre, dx: Float, dy: Float, pn: Float?): RectF {
    val minimo = 0.1f
    if (agarre == Agarre.MOVER || (pn != null && agarre in listOf(Agarre.IZQ, Agarre.DER, Agarre.ARR, Agarre.ABA))) {
        val x = (r0.left + dx).coerceIn(0f, 1f - r0.width())
        val y = (r0.top + dy).coerceIn(0f, 1f - r0.height())
        return RectF(x, y, x + r0.width(), y + r0.height())
    }
    var l = r0.left; var t = r0.top; var r = r0.right; var b = r0.bottom
    val izq = agarre in listOf(Agarre.IZQ_ARR, Agarre.IZQ_ABA, Agarre.IZQ)
    val der = agarre in listOf(Agarre.DER_ARR, Agarre.DER_ABA, Agarre.DER)
    val arr = agarre in listOf(Agarre.IZQ_ARR, Agarre.DER_ARR, Agarre.ARR)
    val aba = agarre in listOf(Agarre.IZQ_ABA, Agarre.DER_ABA, Agarre.ABA)
    if (izq) l = (r0.left + dx).coerceIn(0f, r - minimo)
    if (der) r = (r0.right + dx).coerceIn(l + minimo, 1f)
    if (arr) t = (r0.top + dy).coerceIn(0f, b - minimo)
    if (aba) b = (r0.bottom + dy).coerceIn(t + minimo, 1f)
    if (pn != null) {
        // Esquina con proporción fija: manda el ancho; si el alto no cabe, manda el alto.
        var w = r - l
        var h = w / pn
        val altoMax = if (arr) r0.bottom else 1f - r0.top
        if (h > altoMax) { h = altoMax; w = h * pn }
        if (h < minimo) { h = minimo; w = h * pn }
        if (izq) l = r - w else r = l + w
        if (arr) t = b - h else b = t + h
        if (l < 0f) { l = 0f; r = l + w }
        if (r > 1f) { r = 1f; l = r - w }
    }
    return RectF(l, t, r, b)
}

/**
 * La foto revelada, con sus gestos: mantener pulsado para ver la original, pellizcar para ampliar,
 * pintar con el borrador y, al recortar, las asas del recorte con su rejilla.
 */
@RequiresApi(33)
@Composable
internal fun LienzoFoto(estado: EstadoEditor, modifier: Modifier = Modifier) {
    val vistaAndroid = LocalView.current
    val alcance = rememberCoroutineScope()
    val e = estado.edicion
    val g = e.geometria
    val recortando = estado.herramienta == Herramienta.RECORTAR
    // El fondo se pone al crear el revelado: así el primer fotograma ya sale con él.
    val revelado = remember(estado.vista, estado.mascara, estado.difuso) { Revelado(estado.vista).apply { ponerFondo(estado.mascara, estado.difuso) } }
    val original = remember(estado.vistaOriginal) { Revelado(estado.vistaOriginal) }

    // Todo lo que cambia de golpe en la edición se anima con muelles; lo que sigue al dedo, no.
    val especie = if (estado.arrastrando) snap() else Muelle.suave<Float>()
    val t by animateFloatAsState(if (recortando) 1f else 0f, Muelle.suave(), label = "recortar")
    val cl by animateFloatAsState(g.recorte.left, especie, label = "cl")
    val ct by animateFloatAsState(g.recorte.top, especie, label = "ct")
    val cr by animateFloatAsState(g.recorte.right, especie, label = "cr")
    val cb by animateFloatAsState(g.recorte.bottom, especie, label = "cb")
    val valores = Ajuste.entries.associateWith { a -> animateFloatAsState(e.valor(a), especie, label = a.clave) }
    val intensidadFiltro by animateFloatAsState(e.intensidadFiltro, especie, label = "intensidadFiltro")
    val rejilla by animateFloatAsState(if (estado.arrastrando && recortando) 1f else 0f, Muelle.suave(), label = "rejilla")
    val comparar by animateFloatAsState(if (estado.comparando) 1f else 0f, Muelle.suave(), label = "comparar")

    // Giro de 90° y espejo: la foto gira o se voltea a la vista en vez de saltar. Mientras se
    // prepara la animación se sigue dibujando el encuadre de antes (así no hay un fotograma de golpe).
    var tam by remember { mutableStateOf(Size.Zero) }
    val giro = remember { Animatable(0f) }
    var giroInicial by remember { mutableFloatStateOf(0f) }
    val volteo = remember { Animatable(1f) }
    var gMostrada by remember { mutableStateOf(g) }
    var compensa by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(g.giro, g.espejo) {
        val antes = gMostrada
        if (antes.giro == g.giro && antes.espejo == g.espejo) { gMostrada = g; return@LaunchedEffect }
        if (g.espejo != antes.espejo) {
            volteo.snapTo(-1f); gMostrada = g
            volteo.animateTo(1f, Muelle.suave())
        } else {
            val paso = ((g.giro - antes.giro) % 4 + 4) % 4
            fun escala(geo: Geometria): Float {
                val c = colocar(estado.vista.width, estado.vista.height, geo, geo.recorte, if (recortando) 1f else 0f, tam.width, tam.height, 16f, 12f)
                return c.marco.width() / Encuadre.marco(estado.vista.width, estado.vista.height, geo).first
            }
            compensa = if (tam.width > 0f) escala(antes) / escala(g) else 1f
            giroInicial = -90f * paso
            giro.snapTo(giroInicial); gMostrada = g
            giro.animateTo(0f, Muelle.suave())
        }
    }
    val gD = if (gMostrada.giro != g.giro || gMostrada.espejo != g.espejo) gMostrada else g

    // Ampliar con dos dedos (fuera del recorte).
    var zoom by remember { mutableFloatStateOf(1f) }
    var desplaza by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(recortando) { if (recortando) { zoom = 1f; desplaza = Offset.Zero } }
    val zoomVisto by animateFloatAsState(zoom, if (zoom == 1f) Muelle.suave() else snap(), label = "zoom")
    val desplazaX by animateFloatAsState(desplaza.x, if (zoom == 1f) Muelle.suave() else snap(), label = "dx")
    val desplazaY by animateFloatAsState(desplaza.y, if (zoom == 1f) Muelle.suave() else snap(), label = "dy")

    val colocacion = remember { mutableStateOf<Colocacion?>(null) }
    val acento = Colores.Acento
    val tablero = remember(Apariencia.claro) { tablero(Apariencia.claro) }
    val fundidoBorrado = remember { Animatable(1f) }
    var anterior by remember { mutableStateOf<Bitmap?>(null) }
    val reveladoAnterior = remember(anterior) { anterior?.let { Revelado(it) } }
    // Mientras se borra, el trazo late suavemente.
    val latido by androidx.compose.animation.core.rememberInfiniteTransition(label = "latido").animateFloat(
        0.35f, 0.6f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "latido",
    )
    LaunchedEffect(estado.borradosHechos) {
        if (estado.borradosHechos > 0) { fundidoBorrado.snapTo(0f); fundidoBorrado.animateTo(1f, Muelle.suave()); anterior = null }
    }

    /** De la pantalla a píxeles de la vista previa. */
    fun aVista(p: Offset): Offset {
        val c = colocacion.value ?: return p
        val total = Matrix(c.aPantalla).apply { postScale(zoom, zoom, tam.width / 2, tam.height / 2); postTranslate(desplaza.x, desplaza.y) }
        val inv = Matrix().also { total.invert(it) }
        val v = floatArrayOf(p.x, p.y).also { inv.mapPoints(it) }
        return Offset(v[0], v[1])
    }

    val gestos = if (recortando) Modifier.pointerInput(Unit) {
        var agarre = Agarre.NADA
        var inicio = RectF()
        var total = Offset.Zero
        detectDragGestures(
            onDragStart = { p ->
                val c = colocacion.value ?: return@detectDragGestures
                val k = c.corte
                val radio = 34.dp.toPx()
                fun cerca(x: Float, y: Float) = hypot(p.x - x, p.y - y) < radio
                agarre = when {
                    cerca(k.left, k.top) -> Agarre.IZQ_ARR
                    cerca(k.right, k.top) -> Agarre.DER_ARR
                    cerca(k.left, k.bottom) -> Agarre.IZQ_ABA
                    cerca(k.right, k.bottom) -> Agarre.DER_ABA
                    abs(p.x - k.left) < radio * 0.7f && p.y in k.top..k.bottom -> Agarre.IZQ
                    abs(p.x - k.right) < radio * 0.7f && p.y in k.top..k.bottom -> Agarre.DER
                    abs(p.y - k.top) < radio * 0.7f && p.x in k.left..k.right -> Agarre.ARR
                    abs(p.y - k.bottom) < radio * 0.7f && p.x in k.left..k.right -> Agarre.ABA
                    k.contains(p.x, p.y) -> Agarre.MOVER
                    else -> Agarre.NADA
                }
                if (agarre != Agarre.NADA) {
                    inicio = RectF(estado.edicion.geometria.recorte)
                    total = Offset.Zero
                    estado.empezarGesto()
                }
            },
            onDragEnd = { if (agarre != Agarre.NADA) { estado.terminarGesto(); Haptica.marca(vistaAndroid) }; agarre = Agarre.NADA },
            onDragCancel = { if (agarre != Agarre.NADA) estado.terminarGesto(); agarre = Agarre.NADA },
        ) { cambio, d ->
            if (agarre == Agarre.NADA) return@detectDragGestures
            cambio.consume()
            val c = colocacion.value ?: return@detectDragGestures
            total += d
            val geo = estado.edicion.geometria
            val (w1, h1) = Encuadre.marco(estado.vista.width, estado.vista.height, geo)
            val pn = geo.proporcion?.let { it * h1 / w1 }
            val nuevo = moverRecorte(inicio, agarre, total.x / c.marco.width(), total.y / c.marco.height(), pn)
            estado.moverGesto(estado.edicion.copy(geometria = geo.copy(recorte = nuevo)))
        }
    } else Modifier.pointerInput(estado.herramienta) {
        var ultimoToque = 0L
        awaitEachGesture {
            val abajo = awaitFirstDown(requireUnconsumed = false)
            val pintar = estado.herramienta == Herramienta.BORRADOR && !estado.borrando
            var varios = false
            var movido = false
            var comparacion: Job? = null
            if (!pintar) comparacion = alcance.launch { delay(220); estado.comparando = true }
            val puntos = ArrayList<Offset>()
            if (pintar) { puntos += aVista(abajo.position); estado.trazo = puntos.toList() }
            while (true) {
                val ev = awaitPointerEvent()
                val dedos = ev.changes.count { it.pressed }
                if (dedos == 0) break
                if (dedos >= 2) {
                    if (!varios) { varios = true; comparacion?.cancel(); estado.comparando = false; puntos.clear(); estado.trazo = emptyList() }
                    // Ampliar alrededor de los dedos: el punto bajo ellos se queda bajo ellos.
                    val c = ev.calculateCentroid()
                    val nuevo = (zoom * ev.calculateZoom()).coerceIn(1f, 6f)
                    val f = nuevo / zoom
                    val centro = Offset(tam.width / 2, tam.height / 2)
                    desplaza = c + ev.calculatePan() - centro - (c - desplaza - centro) * f
                    zoom = nuevo
                    if (zoom == 1f) desplaza = Offset.Zero
                    ev.changes.forEach { it.consume() }
                } else if (!varios) {
                    val ch = ev.changes.first { it.pressed }
                    if ((ch.position - abajo.position).getDistance() > viewConfiguration.touchSlop) movido = true
                    if (pintar) {
                        puntos += aVista(ch.position)
                        estado.trazo = puntos.toList()
                        ch.consume()
                    } else if (movido) {
                        comparacion?.cancel(); estado.comparando = false
                        if (zoom > 1f) { desplaza += ch.position - ch.previousPosition; ch.consume() }
                    }
                }
            }
            comparacion?.cancel()
            estado.comparando = false
            if (pintar && puntos.isNotEmpty() && !varios) {
                anterior = estado.vista
                alcance.launch { estado.borrarTrazo() }
            } else if (!movido && !varios && !pintar) {
                // Doble toque: vuelve al tamaño normal.
                val ahora = SystemClock.uptimeMillis()
                if (ahora - ultimoToque < 300) { zoom = 1f; desplaza = Offset.Zero }
                ultimoToque = ahora
            }
        }
    }

    Canvas(modifier.then(gestos)) {
        tam = size
        val w0 = estado.vista.width
        val h0 = estado.vista.height
        val margenH = (if (recortando) 28.dp else 16.dp).toPx()
        val margenV = (if (recortando) 28.dp else 12.dp).toPx()
        val corte = if (gD === g) RectF(cl, ct, cr, cb) else gD.recorte
        val c = colocar(w0, h0, gD, corte, t, size.width, size.height, margenH, margenV)
        colocacion.value = c
        val (wS, hS) = Encuadre.tamSalida(w0, h0, gD)
        val aSalida = Encuadre.aSalida(w0, h0, gD)
        val animada = Edicion(
            ajustes = valores.mapValues { it.value.value }.filterValues { it != 0f },
            filtro = e.filtro, intensidadFiltro = intensidadFiltro, geometria = gD, fondo = e.fondo, colorFondo = e.colorFondo,
        )
        val centro = Offset(size.width / 2, size.height / 2)
        val k = if (giro.value != 0f && giroInicial != 0f) 1f + (compensa - 1f) * (giro.value / giroInicial) else 1f
        withTransform({
            translate(desplazaX, desplazaY)
            scale(zoomVisto, zoomVisto, centro)
            if (giro.value != 0f) { rotate(giro.value, centro); scale(k, k, centro) }
            if (volteo.value != 1f) scale(volteo.value, 1f, centro)
        }) {
            drawIntoCanvas { lienzo ->
                val nc = lienzo.nativeCanvas
                val verOriginal = comparar >= 0.5f
                if (e.transparente && !verOriginal) {
                    nc.save(); nc.clipRect(c.visible); nc.drawPaint(tablero); nc.restore()
                }
                val r = if (verOriginal) original else revelado
                r.preparar(animada, aSalida, wS.toFloat(), hS.toFloat(), original = verOriginal)
                r.dibujar(nc, c.aPantalla, c.visible)
                // Al acabar un borrado, la zona nueva aparece fundiéndose sobre la de antes.
                val rv = reveladoAnterior
                if (rv != null && fundidoBorrado.value < 1f && !verOriginal) {
                    rv.preparar(animada, aSalida, wS.toFloat(), hS.toFloat())
                    nc.saveLayerAlpha(c.visible, ((1f - fundidoBorrado.value) * 255).toInt())
                    rv.dibujar(nc, c.aPantalla, c.visible)
                    nc.restore()
                }
                // El trazo del borrador.
                if (estado.trazo.isNotEmpty()) {
                    nc.save(); nc.concat(c.aPantalla)
                    val alfa = if (estado.borrando) latido else 0.5f
                    val pincel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                        strokeWidth = estado.radioPincel * 2
                        color = android.graphics.Color.argb((alfa * 255).toInt(), (acento.red * 255).toInt(), (acento.green * 255).toInt(), (acento.blue * 255).toInt())
                    }
                    val camino = android.graphics.Path()
                    estado.trazo.forEachIndexed { i, p -> if (i == 0) camino.moveTo(p.x, p.y) else camino.lineTo(p.x, p.y) }
                    if (estado.trazo.size == 1) camino.lineTo(estado.trazo[0].x + 0.1f, estado.trazo[0].y)
                    nc.drawPath(camino, pincel)
                    nc.restore()
                }
            }
            if (t > 0.01f) capaRecorte(c, t, rejilla)
        }
    }
}

/** Oscurece lo que queda fuera, pinta el borde, la rejilla de tercios y las asas. */
private fun DrawScope.capaRecorte(c: Colocacion, t: Float, rejilla: Float) {
    val k = c.corte
    val m = c.marco
    val velo = Color.Black.copy(alpha = 0.55f * t)
    drawRect(velo, Offset(m.left, m.top), Size(m.width(), k.top - m.top))
    drawRect(velo, Offset(m.left, k.bottom), Size(m.width(), m.bottom - k.bottom))
    drawRect(velo, Offset(m.left, k.top), Size(k.left - m.left, k.height()))
    drawRect(velo, Offset(k.right, k.top), Size(m.right - k.right, k.height()))
    val blanco = Color.White
    drawRect(blanco.copy(alpha = 0.9f * t), Offset(k.left, k.top), Size(k.width(), k.height()), style = Stroke(1.dp.toPx()))
    val lineas = blanco.copy(alpha = (0.28f + 0.42f * rejilla) * t)
    for (i in 1..2) {
        val x = k.left + k.width() * i / 3
        val y = k.top + k.height() * i / 3
        drawLine(lineas, Offset(x, k.top), Offset(x, k.bottom), 1.dp.toPx())
        drawLine(lineas, Offset(k.left, y), Offset(k.right, y), 1.dp.toPx())
    }
    val largo = min(20.dp.toPx(), min(k.width(), k.height()) / 3)
    val grosor = 3.dp.toPx()
    val asa = blanco.copy(alpha = t)
    val o = grosor / 2
    for ((x, y, sx, sy) in listOf(
        listOf(k.left, k.top, 1f, 1f), listOf(k.right, k.top, -1f, 1f),
        listOf(k.left, k.bottom, 1f, -1f), listOf(k.right, k.bottom, -1f, -1f),
    )) {
        drawLine(asa, Offset(x - o * sx, y - o * sy), Offset(x + largo * sx, y - o * sy), grosor, cap = StrokeCap.Round)
        drawLine(asa, Offset(x - o * sx, y - o * sy), Offset(x - o * sx, y + largo * sy), grosor, cap = StrokeCap.Round)
    }
}

/** Tablero de ajedrez (lo transparente), en tonos del tema. */
private fun tablero(claro: Boolean): Paint {
    val lado = 24
    val b = Bitmap.createBitmap(lado * 2, lado * 2, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    val a = if (claro) 0xFFFFFFFF.toInt() else 0xFF3A3A38.toInt()
    val z = if (claro) 0xFFE6E4DC.toInt() else 0xFF2C2C2A.toInt()
    c.drawColor(a)
    val p = Paint().apply { color = z }
    c.drawRect(0f, 0f, lado.toFloat(), lado.toFloat(), p)
    c.drawRect(lado.toFloat(), lado.toFloat(), lado * 2f, lado * 2f, p)
    return Paint().apply { shader = BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
}
