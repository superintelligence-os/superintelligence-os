package com.sios.fotos

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class Herramienta { RECORTAR, AJUSTAR, FILTROS, BORRADOR, SUJETO, PALABRAS }

/**
 * Todo lo que pasa en el editor. La foto original no se toca: la vista previa (≤ 2048 px) se
 * revela en la GPU con cada cambio, y al guardar se revela la foto completa con la misma edición.
 *
 * Los borrados son lo único que cambia píxeles: cada uno guarda la zona rellenada (a resolución
 * completa) y la edición dice cuántos hay aplicados; deshacer uno es aplicar uno menos.
 */
@RequiresApi(33)
@Stable
class EstadoEditor(
    private val app: Context,
    /** La foto, a tamaño de pantalla y sin tocar. */
    val vistaOriginal: Bitmap,
    /** La foto entera (para borrar y para guardar); la carga quien abre el editor. */
    private val cargarCompleta: suspend () -> Bitmap,
) {
    private val historial = Historial()

    var edicion by mutableStateOf(Edicion())
        private set
    var puedeDeshacer by mutableStateOf(false)
        private set
    var puedeRehacer by mutableStateOf(false)
        private set

    /** La vista previa con los borrados aplicados. */
    var vista by mutableStateOf(vistaOriginal)
        private set

    var herramienta by mutableStateOf(Herramienta.AJUSTAR)
    var ajusteElegido by mutableStateOf(Ajuste.EXPOSICION)
    /** Manteniendo pulsada la foto: se ve la original. */
    var comparando by mutableStateOf(false)
    /** Un gesto continuo en marcha (deslizador, recorte): sin muelles, sigue al dedo. */
    var arrastrando by mutableStateOf(false)
        private set

    // ---------- Edición y deshacer ----------

    fun poner(e: Edicion) {
        val antes = edicion.borrados
        historial.poner(e)
        sincronizar(antes)
    }

    /** Durante un gesto continuo: un solo paso en la pila al soltar. */
    fun empezarGesto() { historial.empezarGesto(); arrastrando = true }
    fun moverGesto(e: Edicion) { historial.poner(e, agrupar = true); edicion = historial.actual }
    fun terminarGesto() { historial.terminarGesto(); arrastrando = false; sincronizar(edicion.borrados) }

    fun deshacer() { val antes = edicion.borrados; historial.deshacer(); sincronizar(antes) }
    fun rehacer() { val antes = edicion.borrados; historial.rehacer(); sincronizar(antes) }

    private fun sincronizar(borradosAntes: Int) {
        edicion = historial.actual
        puedeDeshacer = historial.puedeDeshacer
        puedeRehacer = historial.puedeRehacer
        if (edicion.borrados != borradosAntes) rehacerVista()
    }

    // ---------- Mejora automática ----------

    var calculandoAuto by mutableStateOf(false)
        private set
    private var propuesta: Map<Ajuste, Float>? = null
    private var antesDeAuto: Map<Ajuste, Float> = emptyMap()
    val autoActiva get() = propuesta != null && edicion.ajustes == propuesta

    /** Un toque: aplica la mejora (o la quita si ya estaba puesta). */
    suspend fun alternarAuto() {
        if (autoActiva) { poner(edicion.copy(ajustes = antesDeAuto)); return }
        if (calculandoAuto) return
        calculandoAuto = true
        try {
            val p = propuesta ?: MejoraAuto.calcular(vista).also { propuesta = it }
            antesDeAuto = edicion.ajustes
            poner(edicion.copy(ajustes = p))
        } finally {
            calculandoAuto = false
        }
    }

    /** Lo que se mide de la foto (para «Edítala con palabras»). */
    val analisis: MejoraAuto.Analisis by lazy { MejoraAuto.analizar(MejoraAuto.muestra(vistaOriginal)) }

    // ---------- Sujeto y fondo ----------

    var mascara by mutableStateOf<Bitmap?>(null)
        private set
    var difuso by mutableStateOf<Bitmap?>(null)
        private set
    var buscandoSujeto by mutableStateOf(false)
        private set
    var sinSujeto by mutableStateOf(false)
        private set

    suspend fun buscarSujeto() {
        if (buscandoSujeto || mascara != null) return
        buscandoSujeto = true
        sinSujeto = false
        try {
            val base = vista
            val m = withContext(Dispatchers.Default) { runCatching { ModelosFotos.sujeto(app, base) }.getOrNull() }
            if (m == null) { sinSujeto = true; return }
            difuso = withContext(Dispatchers.Default) { hacerDifuso(base) }
            mascara = m
            if (edicion.fondo == Fondo.ORIGINAL) poner(edicion.copy(fondo = Fondo.QUITAR))
        } finally {
            buscandoSujeto = false
        }
    }

    suspend fun reintentarSujeto() {
        sinSujeto = false
        buscarSujeto()
    }

    // ---------- Edítala con palabras ----------

    var pensando by mutableStateOf(false)
        private set
    var notaIa by mutableStateOf<String?>(null)
        private set
    var errorIa by mutableStateOf(false)
        private set

    /** Pide los ajustes a la IA elegida y los aplica (se deshacen como cualquier otro cambio). */
    suspend fun pedirPalabras(frase: String): Boolean {
        if (pensando) return false
        pensando = true
        errorIa = false
        notaIa = null
        try {
            val r = Palabras.pedir(frase, edicion, withContext(Dispatchers.Default) { analisis })
            if (r == null) { errorIa = true; return false }
            poner(r.edicion)
            notaIa = r.nota ?: frase
            return true
        } finally {
            pensando = false
        }
    }

    /** Para las capturas: el resultado de una petición ya hecha. */
    internal fun ponerNota(nota: String?, error: Boolean = false) { notaIa = nota; errorIa = error }

    /** Para las pruebas y las capturas: una máscara ya hecha. */
    internal fun ponerMascara(m: Bitmap) {
        mascara = m
        difuso = hacerDifuso(vista)
    }

    // ---------- Borrador mágico ----------

    private class Parche(val zona: Rect, val imagen: Bitmap)

    private val parches = mutableListOf<Parche>()
    private var completa: Bitmap? = null

    /** El trazo que se está pintando, en píxeles de la vista previa. */
    var trazo by mutableStateOf<List<Offset>>(emptyList())
    /** Tamaño del pincel, de 0 a 100. */
    var grosor by mutableFloatStateOf(40f)
    var borrando by mutableStateOf(false)
        private set
    /** Cambia cada vez que termina un borrado (para el fundido de la zona nueva). */
    var borradosHechos by mutableIntStateOf(0)
        private set
    var falloBorrado by mutableStateOf(false)

    /** El radio del pincel en píxeles de la vista previa. */
    val radioPincel get() = min(vista.width, vista.height) * (0.012f + grosor / 100f * 0.07f)

    suspend fun foto(): Bitmap = completa ?: cargarCompleta().also { completa = it }

    /** Borra lo pintado con el trazo actual. */
    suspend fun borrarTrazo() {
        val puntos = trazo
        if (puntos.isEmpty() || borrando) return
        borrando = true
        falloBorrado = false
        try {
            val parche = withContext(Dispatchers.Default) {
                runCatching {
                    val total = foto()
                    val escala = total.width.toFloat() / vistaOriginal.width
                    val radio = radioPincel * escala * 1.15f + 3f
                    var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                    for (p in puntos) { x0 = min(x0, p.x * escala); y0 = min(y0, p.y * escala); x1 = max(x1, p.x * escala); y1 = max(y1, p.y * escala) }
                    x0 -= radio; y0 -= radio; x1 += radio; y1 += radio
                    // Contexto alrededor: el modelo rellena mirando lo que rodea a lo borrado.
                    val margen = max(64f, max(x1 - x0, y1 - y0) * 0.75f)
                    val zona = Rect(
                        (x0 - margen).toInt().coerceAtLeast(0), (y0 - margen).toInt().coerceAtLeast(0),
                        (x1 + margen).roundToInt().coerceAtMost(total.width), (y1 + margen).roundToInt().coerceAtMost(total.height),
                    )
                    if (zona.width() < 8 || zona.height() < 8) return@runCatching null
                    // La zona tal como está ahora (con los borrados anteriores).
                    val actual = Bitmap.createBitmap(zona.width(), zona.height(), Bitmap.Config.ARGB_8888)
                    Canvas(actual).apply {
                        drawBitmap(total, -zona.left.toFloat(), -zona.top.toFloat(), null)
                        for (p in parches.take(edicion.borrados)) if (Rect.intersects(p.zona, zona)) drawBitmap(p.imagen, (p.zona.left - zona.left).toFloat(), (p.zona.top - zona.top).toFloat(), null)
                    }
                    // Lo pintado, como máscara de la zona.
                    val pintado = Bitmap.createBitmap(zona.width(), zona.height(), Bitmap.Config.ARGB_8888)
                    val camino = Path().apply {
                        moveTo(puntos[0].x * escala - zona.left, puntos[0].y * escala - zona.top)
                        for (p in puntos.drop(1)) lineTo(p.x * escala - zona.left, p.y * escala - zona.top)
                        if (puntos.size == 1) lineTo(puntos[0].x * escala - zona.left + 0.1f, puntos[0].y * escala - zona.top)
                    }
                    Canvas(pintado).drawPath(camino, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; strokeWidth = radio * 2; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = -1
                    })
                    val px = IntArray(zona.width() * zona.height()).also { pintado.getPixels(it, 0, zona.width(), 0, 0, zona.width(), zona.height()) }
                    pintado.recycle()
                    val alfa = ByteArray(px.size) { (px[it] ushr 24).toByte() }
                    val hecho = ModelosFotos.borrar(app, actual, alfa) ?: return@runCatching null
                    actual.recycle()
                    Parche(zona, hecho)
                }.onFailure { android.util.Log.w("SistemaSios", "Fotos: borrar", it) }.getOrNull()
            }
            if (parche == null) { falloBorrado = true; return }
            while (parches.size > edicion.borrados) parches.removeAt(parches.lastIndex)
            parches += parche
            poner(edicion.copy(borrados = parches.size))
            borradosHechos++
        } finally {
            trazo = emptyList()
            borrando = false
        }
    }

    /** La vista previa con los primeros n borrados (al borrar, deshacer o rehacer). */
    private fun rehacerVista() {
        val n = edicion.borrados.coerceAtMost(parches.size)
        if (n == 0) { vista = vistaOriginal; return }
        val v = vistaOriginal.copy(Bitmap.Config.ARGB_8888, true)
        val escala = vistaOriginal.width.toFloat() / (completa?.width ?: vistaOriginal.width)
        val lienzo = Canvas(v)
        val pincel = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        for (p in parches.take(n)) {
            lienzo.drawBitmap(p.imagen, null, RectF(p.zona.left * escala, p.zona.top * escala, p.zona.right * escala, p.zona.bottom * escala), pincel)
        }
        vista = v
        if (mascara != null) difuso = hacerDifuso(v)
    }

    // ---------- Guardar ----------

    /** La foto completa revelada con la edición actual (lo que se guarda). */
    suspend fun revelarCompleta(): Bitmap = withContext(Dispatchers.Default) {
        val e = edicion
        val total = foto()
        val base = if (e.borrados == 0) total else total.copy(Bitmap.Config.ARGB_8888, true).also { b ->
            val lienzo = Canvas(b)
            for (p in parches.take(e.borrados)) lienzo.drawBitmap(p.imagen, p.zona.left.toFloat(), p.zona.top.toFloat(), null)
        }
        val r = Revelado(base)
        if (mascara != null && e.fondo != Fondo.ORIGINAL) r.ponerFondo(mascara, hacerDifuso(base))
        r.exportar(e)
    }

    fun liberar() {
        ModelosFotos.liberar()
    }

    companion object {
        /** El fondo desenfocado: en pequeño (512 px) y estirado con filtrado, sirve a cualquier tamaño. */
        fun hacerDifuso(b: Bitmap): Bitmap {
            val escala = 512f / max(b.width, b.height)
            val w = max(1, (b.width * escala).roundToInt())
            val h = max(1, (b.height * escala).roundToInt())
            // Reducir en pasos (a la mitad) para no perder detalle de golpe.
            var actual = b
            while (actual.width / 2 >= w * 2) {
                val siguiente = Bitmap.createScaledBitmap(actual, actual.width / 2, actual.height / 2, true)
                if (actual !== b) actual.recycle()
                actual = siguiente
            }
            val pequena = Bitmap.createScaledBitmap(actual, w, h, true)
            if (actual !== b && actual !== pequena) actual.recycle()
            val px = IntArray(w * h).also { pequena.getPixels(it, 0, w, 0, 0, w, h) }
            RedesFotos.desenfocar(px, w, h, radio = max(2, w / 70))
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
