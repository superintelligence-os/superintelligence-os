package com.sios.fotos

import com.sios.datos.Servicio
import com.sios.ui.Marca
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * «Edítala con palabras»: la IA que haya elegido el usuario traduce lo que pide («más cálida y con
 * menos sombras») a los ajustes del editor, en JSON. No genera imágenes.
 *
 * Qué sale del móvil: la frase, los ajustes actuales y unos números de la foto (su luz y su color
 * medios, medidos aquí). La foto no: la vía de preguntas cortas del servicio (/rapido) solo lleva
 * texto, y varias de las IA que se pueden elegir (DeepSeek, Kimi, el modelo local) no ven imágenes.
 * Con esos números la IA sabe si la foto ya es oscura o fría, que es lo que necesita para elegir.
 */
object Palabras {
    /** Solo si hay una IA elegida (sin elegir, la herramienta no sale). */
    val disponible get() = !Marca.sinElegir

    data class Resultado(val edicion: Edicion, val nota: String?)

    private val SISTEMA = """
        Eres el editor de fotos de un móvil. El usuario dice con palabras cómo quiere su foto y tú respondes SOLO con un objeto JSON con los ajustes finales; no generas imágenes ni escribes nada fuera del JSON.
        Ajustes (números enteros): exposicion, brillo, contraste, luces, sombras, saturacion, intensidad, calidez, tinte van de -100 a 100 (0 es sin cambio; calidez positiva = más cálida/amarilla, negativa = más fría/azul; tinte positivo = magenta, negativo = verde; luces negativas recuperan cielos quemados; sombras positivas aclaran lo oscuro). nitidez y vineta van de 0 a 100.
        Filtros: ninguno, vivido, calido, frio, pelicula, mate, dorado, noir (blanco y negro con contraste), plata (blanco y negro suave), retro.
        Formato: {"ajustes": {"calidez": 25, "sombras": 30}, "filtro": "pelicula", "intensidad_filtro": 70, "nota": "Más cálida y con las sombras más claras."}
        Los valores son el valor final de cada ajuste, no una suma. Incluye en "ajustes" solo los que cambien. "filtro" e "intensidad_filtro" solo si hace falta cambiarlos. Sé moderado (cambios de 10 a 40) salvo que pida algo fuerte. "nota": una frase muy breve, en el idioma del usuario, diciendo lo que has hecho.
    """.trimIndent()

    /** Pregunta a la IA. null si no responde o no se entiende lo que devuelve. */
    suspend fun pedir(frase: String, actual: Edicion, analisis: MejoraAuto.Analisis?): Resultado? {
        val mensaje = buildString {
            append("Ajustes actuales: ")
            append(Ajuste.entries.joinToString(", ") { "${it.clave} ${actual.valor(it).roundToInt()}" })
            append(". Filtro actual: ${actual.filtro.clave} (${actual.intensidadFiltro.roundToInt()}).\n")
            if (analisis != null) append("Datos de la foto (medidos en el móvil; no tienes la imagen): ${describir(analisis)}.\n")
            append("Lo que pide el usuario: «${frase.trim().take(400)}»")
        }
        val respuesta = Servicio.rapido(mensaje, SISTEMA) ?: return null
        return leer(respuesta, actual)
    }

    fun describir(a: MejoraAuto.Analisis): String {
        fun f(v: Float) = String.format(Locale.ROOT, "%.2f", v)
        val dominante = when {
            a.frio > 0.03f -> "algo fría (azulada)"
            a.frio < -0.03f -> "algo cálida (anaranjada)"
            a.verde > 0.03f -> "algo verdosa"
            a.verde < -0.03f -> "algo magenta"
            else -> "neutra"
        }
        return "luminancia media ${f(a.media)} (0 negro, 1 blanco), sombras p10 ${f(a.p10)}, luces p90 ${f(a.p90)}, rango ${f(a.p98 - a.p02)}, saturación media ${f(a.saturacion)}, color $dominante"
    }

    /** Lee el JSON de la respuesta (aunque venga entre texto o en un bloque de código). */
    fun leer(respuesta: String, actual: Edicion): Resultado? {
        val desde = respuesta.indexOf('{')
        val hasta = respuesta.lastIndexOf('}')
        if (desde < 0 || hasta <= desde) return null
        val json = runCatching { JSONObject(respuesta.substring(desde, hasta + 1)) }.getOrNull() ?: return null
        var e = actual
        var algo = false
        // Los ajustes, dentro de «ajustes» o sueltos en el objeto.
        val origenes = listOfNotNull(json.optJSONObject("ajustes"), json)
        for (o in origenes) for (clave in o.keys()) {
            val a = Ajuste.deClave(clave) ?: continue
            val v = o.optDouble(clave)
            if (v.isNaN()) continue
            e = e.con(a, v.toFloat().roundToInt().toFloat())
            algo = true
        }
        Filtro.deClave(json.optString("filtro").takeIf { it.isNotBlank() })?.let { e = e.copy(filtro = it); algo = true }
        json.optDouble("intensidad_filtro").takeIf { !it.isNaN() }?.let { e = e.copy(intensidadFiltro = it.toFloat().coerceIn(0f, 100f)); algo = true }
        if (!algo) return null
        return Resultado(e, json.optString("nota").takeIf { it.isNotBlank() }?.take(160))
    }
}
