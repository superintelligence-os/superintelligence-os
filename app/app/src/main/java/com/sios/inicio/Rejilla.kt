package com.sios.inicio

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.zIndex
import com.sios.ui.Muelle

// ---------- Rejilla de 2 columnas (como los widgets del iPhone), para el panel y el inicio ----------

const val COLUMNAS = 2
val HUECO: Dp = 12.dp

/** Celdas que ocupa cada tamaño: pequeño 1×1, mediano 2×1, grande 2×2. */
fun celdas(tamano: String) = when (tamano) { "pequeno" -> 1 to 1; "grande" -> 2 to 2; else -> 2 to 1 }

/**
 * Coloca los widgets en orden en la primera posición libre (de arriba abajo, de izquierda a
 * derecha), así un pequeño puede rellenar el hueco que deja otro. Devuelve columna y fila de cada
 * uno y el número de filas.
 */
fun colocar(orden: List<String>, tamano: (String) -> String): Pair<Map<String, Pair<Int, Int>>, Int> {
    val ocupadas = HashSet<Pair<Int, Int>>()
    val sitio = LinkedHashMap<String, Pair<Int, Int>>()
    var filas = 0
    for (id in orden) {
        val (an, al) = celdas(tamano(id))
        var fila = 0
        buscar@ while (true) {
            for (col in 0..(COLUMNAS - an)) {
                val libre = (0 until al).all { f -> (0 until an).all { k -> (fila + f to col + k) !in ocupadas } }
                if (libre) {
                    for (f in 0 until al) for (k in 0 until an) ocupadas += fila + f to col + k
                    sitio[id] = col to fila
                    filas = maxOf(filas, fila + al)
                    break@buscar
                }
            }
            fila++
        }
    }
    return sitio to filas
}

/**
 * Reparte los widgets en páginas que caben enteras (`filasPorPagina`): van en orden y, cuando uno ya
 * no cabe en la página, empieza la siguiente. Así nada se corta nunca.
 */
fun paginar(orden: List<String>, tamano: (String) -> String, filasPorPagina: Int): List<List<String>> {
    if (filasPorPagina < 1) return listOf(orden)
    val paginas = mutableListOf(mutableListOf<String>())
    for (id in orden) {
        val prueba = paginas.last() + id
        if (paginas.last().isNotEmpty() && colocar(prueba, tamano).second > filasPorPagina) paginas += mutableListOf(id)
        else paginas.last() += id
    }
    return paginas
}

/**
 * La rejilla: mantener pulsado un widget lo levanta y se arrastra; los demás se apartan con un muelle
 * y el orden se guarda al soltar. Mantener pulsado sin moverlo abre su menú. Con `desplazamiento`
 * (el panel), cerca de los bordes de arriba y abajo se desplaza solo; con `onBorde` (las páginas del
 * inicio), llevarlo al borde izquierdo o derecho lo pasa a la página de al lado.
 */
@Composable
fun RejillaWidgets(
    claves: List<String>,
    tamanoDe: (String) -> String,
    onMenu: (String) -> Unit,
    onOrden: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
    desplazamiento: ScrollState? = null,
    onBorde: ((String, Int) -> Unit)? = null,
    contenido: @Composable (String, Modifier) -> Unit,
) {
    val vista = LocalView.current
    val densidad = LocalDensity.current
    var orden by remember { mutableStateOf(claves) }
    var arrastrado by remember { mutableStateOf<String?>(null) }
    var dedo by remember { mutableStateOf(Offset.Zero) } // centro del widget levantado, en la rejilla
    var arribaVentana by remember { mutableStateOf(0f) }
    // Lo que llega de fuera manda, salvo mientras se arrastra.
    LaunchedEffect(claves) { orden = if (arrastrado == null) claves else orden.filter { it in claves } + claves.filter { it !in orden } }

    BoxWithConstraints(modifier.fillMaxWidth().onGloballyPositioned { arribaVentana = it.boundsInWindow().top }) {
        val hueco = with(densidad) { HUECO.toPx() }
        val anchoTotal = constraints.maxWidth.toFloat()
        val celda = (anchoTotal - hueco * (COLUMNAS - 1)) / COLUMNAS
        fun tamPx(id: String): Size {
            val (an, al) = celdas(tamanoDe(id))
            return Size(celda * an + hueco * (an - 1), celda * al + hueco * (al - 1))
        }
        fun origen(sitio: Pair<Int, Int>) = Offset(sitio.first * (celda + hueco), sitio.second * (celda + hueco))
        val (sitios, filas) = colocar(orden, tamanoDe)
        val altoTotal = if (filas == 0) 0f else filas * celda + (filas - 1) * hueco

        // Reordena en vivo: el widget va al hueco cuyo centro queda más cerca del dedo.
        fun reordenar() {
            val id = arrastrado ?: return
            val resto = orden.filter { it != id }
            var mejor = orden
            var distancia = Float.MAX_VALUE
            for (i in 0..resto.size) {
                val prueba = resto.toMutableList().apply { add(i, id) }
                val s = colocar(prueba, tamanoDe).first[id] ?: continue
                val t = tamPx(id)
                val centro = origen(s) + Offset(t.width / 2, t.height / 2)
                val d = (centro - dedo).getDistance()
                if (d < distancia - 1f) { distancia = d; mejor = prueba }
            }
            if (mejor != orden) {
                orden = mejor
                vista.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
            }
        }

        // Mientras se arrastra: desplazamiento automático (panel) o paso de página (inicio).
        val altoVentana = with(densidad) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
        val borde = with(densidad) { 28.dp.toPx() }
        LaunchedEffect(arrastrado) {
            var enBordeDesde = 0L
            while (arrastrado != null) {
                withFrameNanos { }
                if (desplazamiento != null) {
                    val y = arribaVentana + dedo.y
                    val paso = when {
                        y > altoVentana - with(densidad) { 170.dp.toPx() } -> 14f
                        y < with(densidad) { 190.dp.toPx() } -> -14f
                        else -> 0f
                    }
                    if (paso != 0f) {
                        val hecho = desplazamiento.scrollBy(paso)
                        if (hecho != 0f) { dedo += Offset(0f, hecho); reordenar() }
                    }
                }
                if (onBorde != null) {
                    val lado = when { dedo.x < borde -> -1; dedo.x > anchoTotal - borde -> 1; else -> 0 }
                    if (lado == 0) enBordeDesde = 0L
                    else if (enBordeDesde == 0L) enBordeDesde = System.currentTimeMillis()
                    else if (System.currentTimeMillis() - enBordeDesde > 450) {
                        val id = arrastrado ?: break
                        arrastrado = null
                        vista.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
                        onBorde(id, lado)
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(with(densidad) { altoTotal.toDp() })) {
            for (id in orden) {
                val sitio = sitios[id]
                if (sitio != null) key(id) {
                    val t = tamPx(id)
                    val destino = origen(sitio)
                    val pos by animateOffsetAsState(destino, spring(dampingRatio = 0.82f, stiffness = 420f), label = "sitio")
                    val levantado = arrastrado == id
                    val escala by animateFloatAsState(if (levantado) 1.05f else 1f, Muelle.vivo(), label = "levantar")
                    Box(
                        Modifier
                            .zIndex(if (levantado) 1f else 0f)
                            .offset { (if (levantado) dedo - Offset(t.width / 2, t.height / 2) else pos).round() }
                            .size(with(densidad) { t.width.toDp() }, with(densidad) { t.height.toDp() })
                            .graphicsLayer {
                                scaleX = escala; scaleY = escala
                                shadowElevation = if (levantado) 24f else 0f
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                                clip = false
                            }
                            .levantarYArrastrar(
                                clave = id,
                                onInicio = {
                                    vista.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    dedo = destino + Offset(t.width / 2, t.height / 2)
                                    arrastrado = id
                                },
                                onMover = { d -> if (arrastrado == id) { dedo += d; reordenar() } },
                                onFin = { movido ->
                                    if (arrastrado == id) {
                                        arrastrado = null
                                        if (movido) onOrden(orden) else onMenu(id)
                                    }
                                },
                            ),
                    ) { contenido(id, Modifier) }
                }
            }
        }
    }
}

/**
 * Pulsación larga que levanta el widget y lo arrastra. Va en la pasada inicial: así gana al
 * desplazamiento y a lo que haya dentro del widget (también al diseño libre, que es una vista web).
 * Antes de la pulsación larga no toca nada: los toques y el desplazamiento siguen igual.
 */
private fun Modifier.levantarYArrastrar(clave: Any, onInicio: () -> Unit, onMover: (Offset) -> Unit, onFin: (Boolean) -> Unit) =
    pointerInput(clave) {
        awaitEachGesture {
            val abajo = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // null: se ha cumplido el tiempo de la pulsación larga sin soltar ni moverse.
            val antes = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                var sigue = true
                while (sigue) {
                    val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == abajo.id }
                    sigue = c != null && c.pressed && !c.isConsumed && (c.position - abajo.position).getDistance() <= viewConfiguration.touchSlop
                }
                false
            }
            if (antes == null) levantar(abajo.id, onInicio, onMover, onFin) // si no, toque o desplazamiento: no es para nosotros
        }
    }

private suspend fun AwaitPointerEventScope.levantar(dedo: PointerId, onInicio: () -> Unit, onMover: (Offset) -> Unit, onFin: (Boolean) -> Unit) {
    onInicio()
    var recorrido = 0f
    var sigue = true
    while (sigue) {
        val c = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == dedo }
        if (c == null) {
            sigue = false
        } else {
            val d = c.positionChange()
            c.consume()
            if (!c.pressed) sigue = false
            else {
                recorrido += d.getDistance()
                if (d != Offset.Zero) onMover(d)
            }
        }
    }
    onFin(recorrido > viewConfiguration.touchSlop)
}
