package com.sios.voz

import android.content.Context
import com.sios.voz.motor.partirFrases

/**
 * Traducción en el propio móvil (OPUS-MT): de un idioma al inglés, del inglés a otro y, entre dos que
 * no son el inglés, pasando por él. Nada sale del móvil.
 */
object TraduccionLocal {
    /** Idiomas que se pueden traducir sin conexión (el inglés va en todos los paquetes). */
    val IDIOMAS = listOf("es", "en", "fr", "de", "it", "pt")

    /** El paquete que hace falta para un idioma (null para el inglés, que va incluido). */
    fun paquete(idioma: String): ModelosVoz.Modelo? = Dictado.base(idioma).let { if (it == "en") null else ModelosVoz.traduccion(it) }

    private fun tiene(ctx: Context, idioma: String): Boolean {
        val l = Dictado.base(idioma)
        if (l !in IDIOMAS) return false
        return l == "en" || paquete(l)?.let { ModelosVoz.listo(ctx, it) } == true
    }

    /** ¿Se puede traducir de [de] a [a] sin conexión? */
    fun disponible(ctx: Context, de: String, a: String) = tiene(ctx, de) && tiene(ctx, a)

    /** Lo que falta descargar para ese par (vacío si ya está). */
    fun falta(ctx: Context, de: String, a: String): List<ModelosVoz.Modelo> =
        listOf(de, a).map { Dictado.base(it) }.distinct().mapNotNull { l -> paquete(l)?.takeIf { !ModelosVoz.listo(ctx, it) } }

    /**
     * ¿Se traduce en el móvil? Siempre que se pida o, en automático, si no hay internet o no hay IA
     * con la que traducir. null: no se puede de ninguna forma.
     */
    fun usar(ctx: Context, de: String, a: String, iaLista: Boolean): Boolean? {
        val local = disponible(ctx, de, a)
        return when {
            local && (Dictado.modo(ctx) == Dictado.Modo.SIEMPRE || !iaLista || !Dictado.hayInternet(ctx)) -> true
            iaLista -> false
            else -> null
        }
    }

    /** Traduce (bloquea: llamar fuera del hilo principal). null si falta el paquete o falla. */
    fun traducir(ctx: Context, texto: String, de: String, a: String): String? {
        val origen = Dictado.base(de)
        val destino = Dictado.base(a)
        if (texto.isBlank()) return ""
        if (origen == destino) return texto
        if (!disponible(ctx, origen, destino)) return null
        return runCatching {
            MotoresLocales.usando {
                val ingles = if (origen == "en") texto else direccion(ctx, origen, "en", a, texto) ?: return@usando null
                if (destino == "en") ingles else direccion(ctx, "en", destino, a, ingles)
            }
        }.onFailure { android.util.Log.w("SistemaSios", "Traducción sin conexión", it) }.getOrNull()
    }

    /** Una dirección con el inglés, frase a frase. [region]: el idioma de destino completo («pt-BR»). */
    private fun direccion(ctx: Context, de: String, a: String, region: String, texto: String): String? {
        val idioma = if (de == "en") a else de
        val m = paquete(idioma) ?: return null
        // El portugués va con los modelos de lenguas romances: hacia él, con su etiqueta.
        val romance = idioma == "pt"
        val dir = when {
            romance && de == "en" -> "en-ROMANCE"
            romance -> "ROMANCE-en"
            else -> "$de-$a"
        }
        val prefijo = if (romance && de == "en") (if (region.equals("pt-BR", ignoreCase = true)) ">>pt_BR<<" else ">>pt<<") else null
        val motor = MotoresLocales.marian(ctx, m, dir) ?: return null
        return partirFrases(texto).joinToString(" ") { f -> if (f == "\n") "\n" else motor.traducir(f, prefijo) }
            .replace(" \n ", "\n").trim()
    }
}
