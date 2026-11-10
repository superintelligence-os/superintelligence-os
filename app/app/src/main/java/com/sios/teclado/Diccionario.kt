package com.sios.teclado

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/**
 * Sugerencias del teclado: las palabras más frecuentes del idioma (FrequencyWords, CC BY-SA 4.0) y
 * las que escribe el usuario (se aprenden en el móvil, nunca salen). Busca por prefijo sin tildes
 * («cancion» → «canción») y, si no hay nada, corrige a una letra de distancia.
 */
class Diccionario private constructor(private val ctx: Context, val idioma: String) {
    private val palabras: Array<String>
    private val frecuencia: IntArray
    private val claves: Array<String> // sin tildes, en el mismo orden (alfabético por clave)
    private val propias = mutableMapOf<String, Int>()

    init {
        val filas = ctx.assets.open("diccionario-$idioma.txt").bufferedReader().readLines().mapNotNull { l ->
            val t = l.indexOf('\t')
            if (t <= 0) null else Triple(clave(l.substring(0, t)), l.substring(0, t), l.substring(t + 1).toIntOrNull() ?: 1)
        }.sortedBy { it.first }
        claves = Array(filas.size) { filas[it].first }
        palabras = Array(filas.size) { filas[it].second }
        frecuencia = IntArray(filas.size) { filas[it].third }
        ctx.getSharedPreferences("teclado_palabras_$idioma", Context.MODE_PRIVATE).all.forEach { (k, v) -> propias[k] = (v as? Int) ?: 1 }
    }

    /** Tres sugerencias para lo que se está escribiendo (con la misma mayúscula inicial). */
    fun sugerir(prefijo: String): List<String> {
        if (prefijo.isEmpty()) return emptyList()
        val k = clave(prefijo)
        val candidatos = mutableMapOf<String, Long>()
        // Del diccionario: las que empiezan igual.
        var i = buscarPrimera(k)
        var vistas = 0
        while (i < claves.size && claves[i].startsWith(k) && vistas < 3000) {
            candidatos[palabras[i]] = frecuencia[i].toLong()
            i++; vistas++
        }
        // Las propias pesan mucho: si la usas, es que la quieres.
        for ((p, n) in propias) if (clave(p).startsWith(k)) candidatos[p] = (candidatos[p] ?: 0) + n * 5_000_000L
        // Nada que empiece así: correcciones a una letra de distancia.
        if (candidatos.isEmpty() && k.length >= 3) for (idx in claves.indices) {
            if (kotlin.math.abs(claves[idx].length - k.length) <= 1 && distanciaUno(claves[idx], k)) candidatos[palabras[idx]] = frecuencia[idx].toLong()
        }
        val exacta = candidatos.keys.firstOrNull { clave(it) == k && it != prefijo.lowercase(Locale.ROOT) }
        val orden = candidatos.entries.sortedByDescending { it.value }.map { it.key }
        // Nunca se sugiere lo mismo que ya está escrito.
        val lista = (listOfNotNull(exacta) + orden).distinct().filter { !it.equals(prefijo, ignoreCase = true) }.take(3)
        return lista.map { conMayuscula(it, prefijo) }
    }

    /** Recuerda una palabra que el usuario ha escrito entera (para sugerírsela después). */
    fun aprender(palabra: String) {
        val p = palabra.lowercase(Locale.ROOT)
        if (p.length < 2 || p.any { !it.isLetter() && it != '\'' }) return
        propias[p] = (propias[p] ?: 0) + 1
        ctx.getSharedPreferences("teclado_palabras_$idioma", Context.MODE_PRIVATE).edit().putInt(p, propias[p]!!).apply()
    }

    private fun buscarPrimera(k: String): Int {
        var lo = 0
        var hi = claves.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (claves[m] < k) lo = m + 1 else hi = m
        }
        return lo
    }

    private fun distanciaUno(a: String, b: String): Boolean {
        if (a == b) return false
        if (a.length == b.length) return a.indices.count { a[it] != b[it] } == 1
        val (largo, corto) = if (a.length > b.length) a to b else b to a
        var i = 0
        while (i < corto.length && largo[i] == corto[i]) i++
        return largo.substring(i + 1) == corto.substring(i)
    }

    private fun conMayuscula(p: String, como: String) = when {
        como.length > 1 && como.all { !it.isLetter() || it.isUpperCase() } -> p.uppercase(Locale.ROOT)
        como.first().isUpperCase() -> p.replaceFirstChar { it.titlecase(Locale.ROOT) }
        else -> p
    }

    companion object {
        private var cargado: Diccionario? = null

        fun clave(s: String) = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

        val IDIOMAS = setOf("es", "en", "fr", "de", "it", "pt")

        /** El del idioma del móvil (si no lo hay, el inglés); se carga una vez, en segundo plano. */
        fun de(ctx: Context): Diccionario {
            val idioma = Locale.getDefault().language.takeIf { it in IDIOMAS } ?: "en"
            return cargado?.takeIf { it.idioma == idioma } ?: Diccionario(ctx.applicationContext, idioma).also { cargado = it }
        }
    }
}
