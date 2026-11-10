package com.sios.cortafuegos

import android.content.Context

/** Para qué sirve un servidor, dicho en normal. */
internal enum class Categoria { PUBLICIDAD, ANALISIS, RASTREO, FUNCIONAL }

internal class Empresa(val nombre: String, val categoria: Categoria)

/**
 * De quién es cada dominio y para qué sirve (mapa local, assets/cortafuegos/empresas.tsv), y el
 * resumen de lo que hace una app con la red a partir de su registro.
 */
internal object Empresas {
    @Volatile private var mapa: Map<String, Empresa>? = null
    @Volatile private var duenos: List<Pair<String, String>> = emptyList()

    private fun cargar(ctx: Context): Map<String, Empresa> {
        mapa?.let { return it }
        val m = HashMap<String, Empresa>()
        val d = ArrayList<Pair<String, String>>()
        runCatching {
            ctx.assets.open("cortafuegos/empresas.tsv").bufferedReader().forEachLine { l ->
                if (l.isBlank() || l.startsWith("#")) return@forEachLine
                val c = l.split('\t')
                if (c.size < 3) return@forEachLine
                if (c[0] == "@app") { d += c[1] to c[2]; return@forEachLine }
                val cat = when (c[2].trim()) {
                    "publicidad" -> Categoria.PUBLICIDAD
                    "analisis" -> Categoria.ANALISIS
                    "rastreo" -> Categoria.RASTREO
                    else -> Categoria.FUNCIONAL
                }
                m[c[0].trim()] = Empresa(c[1].trim(), cat)
            }
        }
        duenos = d
        return m.also { mapa = it }
    }

    /** La empresa de un dominio (o de su dominio padre más cercano que esté en el mapa). */
    fun de(ctx: Context, dominio: String): Empresa? {
        val m = cargar(ctx)
        var n = dominio
        while (true) {
            m[n]?.let { return it }
            val punto = n.indexOf('.')
            if (punto < 0) return null
            n = n.substring(punto + 1)
        }
    }

    /** De qué empresa es una app (por su paquete), si se sabe. */
    fun duenoDe(ctx: Context, paquete: String): String? {
        cargar(ctx)
        return duenos.firstOrNull { paquete == it.first.trimEnd('.') || paquete.startsWith(it.first) }?.second
    }

    /** Un dominio con lo que se sabe de él, para la ficha de la app. */
    class Destino(
        val dominio: String,
        val empresa: String?,
        /** null: no está en el mapa (si la lista lo bloquea, cuenta como rastreador sin nombre). */
        val categoria: Categoria?,
        val veces: Int,
        val bloqueadas: Int,
        val motivo: Int,
        val ultima: Long,
    )

    /** Resumen de una app para la explicación en lenguaje normal. */
    class Resumen(
        val destinos: List<Destino>,
        /** Empresas de publicidad, análisis o rastreo con las que habló, de más a menos veces. */
        val empresas: List<String>,
        val categorias: Set<Categoria>,
        /** Dominios bloqueados por la lista que no están en el mapa (rastreadores sin nombre). */
        val sinNombre: Int,
        /** Empresas de lo que la app necesita para funcionar. */
        val funcionales: List<String>,
        val consultas: Int,
        val bloqueadasLista: Int,
        val sinRed: Int,
    )

    fun resumen(ctx: Context, paquete: String, app: Registro.DeApp?): Resumen {
        val propia = duenoDe(ctx, paquete)
        val destinos = app?.dominios.orEmpty().map { (dominio, d) ->
            val e = de(ctx, dominio)
            // Con los servidores de su propia empresa, una app está funcionando (Instagram con Meta).
            val cat = when {
                e == null -> null
                // Solo el rastreo: la analítica y los anuncios de su empresa siguen siendo lo que son.
                propia != null && e.nombre == propia && e.categoria == Categoria.RASTREO -> Categoria.FUNCIONAL
                else -> e.categoria
            }
            Destino(dominio, e?.nombre, cat, d.consultas, d.bloqueadas, d.motivo, d.ultima)
        }.sortedWith(compareByDescending<Destino> { it.veces }.thenBy { it.dominio })
        val noFuncionales = destinos.filter { it.categoria != null && it.categoria != Categoria.FUNCIONAL }
        val empresas = noFuncionales.groupBy { it.empresa!! }.entries
            .sortedByDescending { e -> e.value.sumOf { it.veces } }.map { it.key }
        return Resumen(
            destinos = destinos,
            empresas = empresas,
            categorias = noFuncionales.mapNotNull { it.categoria }.toSet(),
            sinNombre = destinos.count { it.categoria == null && it.motivo == Registro.LISTA },
            funcionales = destinos.filter { it.categoria == Categoria.FUNCIONAL }.groupBy { it.empresa!! }.entries
                .sortedByDescending { e -> e.value.sumOf { it.veces } }.map { it.key },
            consultas = app?.consultas ?: 0,
            bloqueadasLista = app?.lista ?: 0,
            sinRed = app?.sinRed ?: 0,
        )
    }
}
