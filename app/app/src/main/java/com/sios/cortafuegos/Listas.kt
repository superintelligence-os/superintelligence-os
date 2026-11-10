package com.sios.cortafuegos

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Las listas de bloqueo: la elegida (compacta, de serie; o completa, descargada), la propia de SIOS y
 * lo que el usuario ha permitido o bloqueado a mano. Un dominio bloquea también sus subdominios.
 */
internal object Listas {
    /** De dónde sale cada lista y dónde actualizarla. */
    enum class Fuente(val id: String, val url: String, val asset: String?) {
        // AdAway (CC BY 3.0): publicidad y analítica de apps de móvil; pequeña y con pocos falsos positivos.
        COMPACTA("compacta", "https://adaway.org/hosts.txt", "cortafuegos/lista-compacta.txt"),
        // StevenBlack «unified» (MIT; cada fuente con su licencia): mucho más amplia, ~80 000 dominios.
        COMPLETA("completa", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", null),
    }

    @Volatile private var dominios: Set<String> = emptySet()
    @Volatile private var propios: Set<String> = emptySet()
    @Volatile private var cargada: Fuente? = null

    private fun carpeta(ctx: Context) = File(ctx.filesDir, "cortafuegos").apply { mkdirs() }
    private fun archivo(ctx: Context, f: Fuente) = File(carpeta(ctx), "lista-${f.id}.txt")

    fun elegida(ctx: Context): Fuente =
        Fuente.entries.firstOrNull { it.id == Cortafuegos.prefs(ctx).getString("lista", null) } ?: Fuente.COMPACTA

    /** ¿Está en el móvil? La compacta siempre (viene en la app); la completa, si se ha descargado. */
    fun disponible(ctx: Context, f: Fuente) = f.asset != null || archivo(ctx, f).exists()

    /** Cuántos dominios tiene la lista en uso (0 si aún no se ha cargado). */
    fun cuantos(): Int = dominios.size + propios.size

    /** Cuándo se actualizó la lista elegida (0: la que trae la app). */
    fun actualizada(ctx: Context, f: Fuente = elegida(ctx)): Long =
        archivo(ctx, f).takeIf { it.exists() }?.lastModified() ?: 0L

    /** Carga la lista elegida en memoria (si no lo está ya). Tarda poco: se hace al encender. */
    fun cargar(ctx: Context, forzar: Boolean = false) {
        val f = elegida(ctx)
        if (!forzar && cargada == f && dominios.isNotEmpty()) return
        if (propios.isEmpty()) propios = leer(ctx.assets.open("cortafuegos/lista-sios.txt").bufferedReader().readLines())
        val descargada = archivo(ctx, f)
        val lineas = when {
            descargada.exists() -> descargada.readLines()
            f.asset != null -> ctx.assets.open(f.asset).bufferedReader().readLines()
            // La completa sin descargar (no debería pasar): la compacta, mejor que nada.
            else -> ctx.assets.open(Fuente.COMPACTA.asset!!).bufferedReader().readLines()
        }
        dominios = leer(lineas)
        cargada = f
    }

    /**
     * Cuántos dominios tiene una lista (con la propia de SIOS), sin dejarla cargada en memoria si el
     * cortafuegos está apagado: se cuenta una vez y se guarda. Fuera del hilo principal.
     */
    fun contar(ctx: Context, f: Fuente): Int {
        if (cargada == f && dominios.isNotEmpty()) return cuantos()
        val p = Cortafuegos.prefs(ctx)
        val fecha = actualizada(ctx, f)
        if (p.getLong("cuantos_${f.id}_fecha", -1) == fecha) p.getInt("cuantos_${f.id}", -1).takeIf { it >= 0 }?.let { return it }
        val propiosAhora = propios.ifEmpty { leer(ctx.assets.open("cortafuegos/lista-sios.txt").bufferedReader().readLines()) }
        val descargada = archivo(ctx, f)
        val n = when {
            descargada.exists() -> leer(descargada.readLines()).size
            f.asset != null -> leer(ctx.assets.open(f.asset).bufferedReader().readLines()).size
            else -> 0
        } + propiosAhora.size
        p.edit().putInt("cuantos_${f.id}", n).putLong("cuantos_${f.id}_fecha", fecha).apply()
        return n
    }

    /** ¿Lo bloquea la lista (o el usuario)? */
    fun bloqueado(ctx: Context, nombre: String): Boolean {
        val r = Cortafuegos.reglas(ctx)
        return coincide(nombre, r.dominiosBloqueados) || coincide(nombre, propios) || coincide(nombre, dominios)
    }

    /** ¿Lo bloquea solo la lista (para saber si se puede «permitir»)? */
    fun enLista(nombre: String) = coincide(nombre, propios) || coincide(nombre, dominios)

    fun permitido(ctx: Context, nombre: String) = coincide(nombre, Cortafuegos.reglas(ctx).dominiosPermitidos)

    /** El dominio o alguno de sus padres está en el conjunto (sin llegar al dominio de primer nivel). */
    fun coincide(nombre: String, conjunto: Set<String>): Boolean {
        if (conjunto.isEmpty()) return false
        var n = nombre
        while (true) {
            if (n in conjunto) return true
            val punto = n.indexOf('.')
            if (punto < 0) return false
            n = n.substring(punto + 1)
            if ('.' !in n) return false
        }
    }

    private val VALIDO = Regex("^[a-z0-9_]([a-z0-9_-]{0,62}\\.)+[a-z0-9-]{2,63}$")
    private val NO_SON = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0", "ip6-localhost", "ip6-loopback")

    /** Lee una lista en formato hosts («0.0.0.0 dominio») o de un dominio por línea. */
    fun leer(lineas: List<String>): Set<String> {
        val s = HashSet<String>(lineas.size * 2)
        for (l in lineas) {
            val t = l.substringBefore('#').trim()
            if (t.isEmpty()) continue
            val partes = t.split(' ', '\t').filter { it.isNotEmpty() }
            // En formato hosts, lo primero es una IP: los dominios van detrás.
            val nombres = if (partes.size > 1 && (partes[0].contains(':') || partes[0].first().isDigit() && partes[0].count { it == '.' } == 3)) partes.drop(1) else partes.take(1)
            for (n in nombres) {
                val d = n.lowercase().trimEnd('.')
                if (d !in NO_SON && VALIDO.matches(d)) s.add(d)
            }
        }
        return s
    }

    /**
     * Descarga la lista de [f] y la deja lista para usar. Devuelve cuántos dominios tiene o lanza el
     * error. Lo único que sale del móvil es la descarga (del repositorio público de la lista).
     */
    fun actualizar(ctx: Context, f: Fuente): Int {
        val http = okhttp3.OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
        val texto = http.newCall(okhttp3.Request.Builder().url(f.url).build()).execute().use { r ->
            check(r.isSuccessful) { "HTTP ${r.code}" }
            val cuerpo = r.body
            // Una lista de bloqueo no pasa de unos pocos MB: más es que algo va mal.
            check(cuerpo.contentLength() < 20_000_000) { "demasiado grande" }
            cuerpo.string()
        }
        val nuevos = leer(texto.lines())
        // Si llega casi vacía (una página de error, un cambio de formato), se queda la que había.
        check(nuevos.size >= 1000) { "lista vacía" }
        val destino = archivo(ctx, f)
        val temporal = File(destino.path + ".tmp")
        temporal.writeText(
            "# ${f.url} · ${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())}\n" +
                nuevos.sorted().joinToString("\n"),
        )
        check(temporal.renameTo(destino)) { "no se pudo guardar" }
        if (elegida(ctx) == f) { dominios = nuevos; cargada = f }
        return nuevos.size
    }

    /** Cambia de lista (la completa tiene que estar descargada) y la carga. Fuera del hilo principal. */
    fun elegir(ctx: Context, f: Fuente) {
        Cortafuegos.prefs(ctx).edit().putString("lista", f.id).apply()
        cargar(ctx, forzar = true)
    }
}
