package com.sios.fotos

import android.graphics.RectF
import androidx.annotation.StringRes
import com.sios.R

/**
 * Lo que se ha hecho a una foto, sin tocar sus píxeles: los ajustes, el filtro, el encuadre, el fondo
 * y cuántos borrados están aplicados. Se guarda una lista de estas para deshacer y rehacer; la foto
 * original no se toca nunca (al guardar se escribe una copia).
 */
enum class Ajuste(val clave: String, @StringRes val nombre: Int, val minimo: Float = -100f) {
    EXPOSICION("exposicion", R.string.fotos_exposicion),
    BRILLO("brillo", R.string.fotos_brillo),
    CONTRASTE("contraste", R.string.fotos_contraste),
    LUCES("luces", R.string.fotos_luces),
    SOMBRAS("sombras", R.string.fotos_sombras),
    SATURACION("saturacion", R.string.fotos_saturacion),
    INTENSIDAD("intensidad", R.string.fotos_intensidad),
    CALIDEZ("calidez", R.string.fotos_calidez),
    TINTE("tinte", R.string.fotos_tinte),
    NITIDEZ("nitidez", R.string.fotos_nitidez, 0f),
    VINETA("vineta", R.string.fotos_vineta, 0f),
    ;

    val maximo get() = 100f

    companion object {
        fun deClave(c: String) = entries.firstOrNull { it.clave == c.trim().lowercase() }
    }
}

/**
 * Filtros cuidados: una mezcla de color (matriz 3×3), su contraste, un velo en los negros y un
 * toque de color en sombras y luces. Todos se pueden suavizar con su intensidad.
 */
enum class Filtro(
    val clave: String,
    @StringRes val nombre: Int,
    val matriz: FloatArray = IDENTIDAD,
    val contraste: Float = 1f,
    val velo: Float = 0f,
    val tonoSombras: FloatArray = floatArrayOf(0f, 0f, 0f),
    val tonoLuces: FloatArray = floatArrayOf(0f, 0f, 0f),
) {
    NINGUNO("ninguno", R.string.fotos_filtro_original),
    VIVIDO("vivido", R.string.fotos_filtro_vivido, saturacion(1.28f), contraste = 1.08f),
    CALIDO(
        "calido", R.string.fotos_filtro_calido, multiplicar(saturacion(1.06f), floatArrayOf(1.06f, 1.0f, 0.93f)),
        tonoLuces = floatArrayOf(0.015f, 0.008f, -0.012f),
    ),
    FRIO("frio", R.string.fotos_filtro_frio, multiplicar(saturacion(0.96f), floatArrayOf(0.93f, 1.0f, 1.08f)), tonoSombras = floatArrayOf(-0.01f, 0.0f, 0.03f)),
    PELICULA(
        "pelicula", R.string.fotos_filtro_pelicula, saturacion(0.86f), contraste = 0.96f, velo = 0.055f,
        tonoSombras = floatArrayOf(-0.02f, 0.025f, 0.04f), tonoLuces = floatArrayOf(0.04f, 0.02f, -0.03f),
    ),
    MATE("mate", R.string.fotos_filtro_mate, saturacion(0.9f), contraste = 0.88f, velo = 0.09f),
    DORADO(
        "dorado", R.string.fotos_filtro_dorado, multiplicar(saturacion(1.08f), floatArrayOf(1.06f, 1.01f, 0.9f)), contraste = 1.04f,
        tonoSombras = floatArrayOf(0.01f, 0.004f, -0.008f), tonoLuces = floatArrayOf(0.02f, 0.012f, -0.012f),
    ),
    NOIR("noir", R.string.fotos_filtro_noir, blancoYNegro(0.32f, 0.56f, 0.12f), contraste = 1.14f),
    PLATA("plata", R.string.fotos_filtro_plata, blancoYNegro(0.3f, 0.59f, 0.11f), contraste = 0.92f, velo = 0.05f, tonoSombras = floatArrayOf(0f, 0.004f, 0.012f)),
    RETRO(
        "retro", R.string.fotos_filtro_retro, multiplicar(saturacion(0.72f), floatArrayOf(1.05f, 0.99f, 0.88f)), contraste = 0.94f, velo = 0.07f,
        tonoSombras = floatArrayOf(0.02f, 0.01f, 0.03f), tonoLuces = floatArrayOf(0.025f, 0.012f, -0.025f),
    ),
    ;

    companion object {
        fun deClave(c: String?) = entries.firstOrNull { it.clave == c?.trim()?.lowercase() }
    }
}

private val IDENTIDAD = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

// Pesos de luminancia (Rec. 709) para la saturación.
private const val LR = 0.2126f
private const val LG = 0.7152f
private const val LB = 0.0722f

/** Matriz de saturación: mezcla cada canal con la luminancia. */
private fun saturacion(s: Float) = floatArrayOf(
    LR + (1 - LR) * s, LG - LG * s, LB - LB * s,
    LR - LR * s, LG + (1 - LG) * s, LB - LB * s,
    LR - LR * s, LG - LG * s, LB + (1 - LB) * s,
)

private fun blancoYNegro(r: Float, g: Float, b: Float) = floatArrayOf(r, g, b, r, g, b, r, g, b)

/** Multiplica cada fila (canal de salida) por un factor. */
private fun multiplicar(m: FloatArray, canales: FloatArray) = FloatArray(9) { m[it] * canales[it / 3] }

/**
 * El encuadre: giros de 90°, espejo, enderezar (en grados, con zoom para no dejar esquinas vacías) y
 * el recorte, en fracciones del marco ya girado y enderezado.
 */
data class Geometria(
    val giro: Int = 0,
    val espejo: Boolean = false,
    val enderezar: Float = 0f,
    val recorte: RectF = RectF(0f, 0f, 1f, 1f),
    /** Proporción fijada (ancho/alto en píxeles de salida) o null si es libre. */
    val proporcion: Float? = null,
) {
    val esNeutra get() = giro % 4 == 0 && !espejo && enderezar == 0f && recorte == RectF(0f, 0f, 1f, 1f)

    /**
     * Gira lo que se ve 90° a la derecha. Los giros conmutan, así que enderezar no cambia; el
     * recorte gira con la foto (x, y) → (1 − y, x).
     */
    fun girada(): Geometria {
        val r = recorte
        return copy(giro = (giro + 1) % 4, recorte = RectF(1f - r.bottom, r.left, 1f - r.top, r.right), proporcion = proporcion?.let { 1f / it })
    }

    /**
     * Refleja lo que se ve en horizontal. El espejo se aplica antes de girar, así que el giro y el
     * ángulo cambian de sentido para que el resultado sea el reflejo exacto de lo que había.
     */
    fun reflejada(): Geometria {
        val r = recorte
        return copy(espejo = !espejo, giro = (4 - giro) % 4, enderezar = -enderezar, recorte = RectF(1f - r.right, r.top, 1f - r.left, r.bottom))
    }
}

/** Qué se hace con el fondo cuando se ha encontrado el sujeto. */
enum class Fondo(val clave: String, @StringRes val nombre: Int) {
    ORIGINAL("original", R.string.fotos_fondo_original),
    QUITAR("quitar", R.string.fotos_fondo_quitar),
    COLOR("color", R.string.fotos_fondo_color),
    DESENFOCAR("desenfocar", R.string.fotos_fondo_desenfocar),
}

data class Edicion(
    val ajustes: Map<Ajuste, Float> = emptyMap(),
    val filtro: Filtro = Filtro.NINGUNO,
    val intensidadFiltro: Float = 100f,
    val geometria: Geometria = Geometria(),
    val fondo: Fondo = Fondo.ORIGINAL,
    val colorFondo: Int = 0xFFFFFFFF.toInt(),
    /** Cuántos borrados (de la lista del editor) están aplicados: deshacer uno es bajar este número. */
    val borrados: Int = 0,
) {
    fun valor(a: Ajuste) = ajustes[a] ?: 0f

    fun con(a: Ajuste, v: Float): Edicion {
        val limpio = v.coerceIn(a.minimo, a.maximo)
        return copy(ajustes = if (limpio == 0f) ajustes - a else ajustes + (a to limpio))
    }

    val sinCambios get() = ajustes.isEmpty() && filtro == Filtro.NINGUNO && geometria.esNeutra && fondo == Fondo.ORIGINAL && borrados == 0
    val transparente get() = fondo == Fondo.QUITAR
}

/** Deshacer y rehacer: una pila de ediciones. Los gestos continuos (un deslizador) cuentan como uno. */
class Historial(inicial: Edicion = Edicion()) {
    private val pasado = ArrayDeque<Edicion>()
    private val futuro = ArrayDeque<Edicion>()
    var actual: Edicion = inicial
        private set

    val puedeDeshacer get() = pasado.isNotEmpty()
    val puedeRehacer get() = futuro.isNotEmpty()

    /** Un cambio nuevo. agrupar: sustituye al último si es parte del mismo gesto (no llena la pila). */
    fun poner(nueva: Edicion, agrupar: Boolean = false) {
        if (nueva == actual) return
        if (!agrupar) {
            pasado.addLast(actual)
            if (pasado.size > 100) pasado.removeFirst()
        }
        futuro.clear()
        actual = nueva
    }

    /** Empieza un gesto continuo: guarda el punto al que volver con deshacer. */
    fun empezarGesto() {
        pasado.addLast(actual)
        if (pasado.size > 100) pasado.removeFirst()
        futuro.clear()
    }

    /** Si el gesto acabó sin cambiar nada, no deja un paso vacío en la pila. */
    fun terminarGesto() {
        if (pasado.lastOrNull() == actual) pasado.removeLast()
    }

    fun deshacer(): Edicion? {
        val anterior = pasado.removeLastOrNull() ?: return null
        futuro.addLast(actual)
        actual = anterior
        return anterior
    }

    fun rehacer(): Edicion? {
        val siguiente = futuro.removeLastOrNull() ?: return null
        pasado.addLast(actual)
        actual = siguiente
        return siguiente
    }
}
