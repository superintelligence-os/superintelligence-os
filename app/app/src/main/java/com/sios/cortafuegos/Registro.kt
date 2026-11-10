package com.sios.cortafuegos

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro local del cortafuegos: por app y por día, a qué dominios intentó ir, cuántas veces y qué
 * se bloqueó. Un archivo por día en el almacenamiento privado de la app; se guardan los últimos 7 y
 * los más viejos se borran solos. Nada sale del móvil.
 */
internal object Registro {
    const val PASA = 0
    /** Bloqueado por la lista de publicidad y rastreadores (o por el usuario). */
    const val LISTA = 1
    /** La app no tiene internet en esta red. */
    const val SIN_RED = 2

    private const val DIAS = 7
    private const val MAX_DOMINIOS = 400
    /** La misma app pidiendo el mismo dominio en menos de esto cuenta una vez (A y AAAA, reintentos). */
    private const val VENTANA = 10_000L

    class Dominio(var consultas: Int = 0, var bloqueadas: Int = 0, var ultima: Long = 0, var motivo: Int = PASA)

    class DeApp {
        val dominios = HashMap<String, Dominio>()
        var consultas = 0
        var lista = 0
        var sinRed = 0
        var ultima = 0L
    }

    private var dia = ""
    private var hoy = HashMap<String, DeApp>()
    private var sucio = false
    private var avisoPendiente = false
    private var guardado = 0L
    private val recientes = object : LinkedHashMap<String, Long>(512, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 4000
    }

    private val _cambios = MutableStateFlow(0)
    /** Sube cuando hay apuntes nuevos (como mucho cada 2 s): las pantallas se refrescan con él. */
    val cambios: StateFlow<Int> = _cambios.asStateFlow()

    private fun fecha(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(ms))
    private fun carpeta(ctx: Context) = File(ctx.filesDir, "cortafuegos/registro").apply { mkdirs() }

    /** Apunta una consulta de [paquete] («» si no se sabe de qué app es). */
    fun apuntar(ctx: Context, paquete: String, dominio: String, motivo: Int) {
        val ahora = System.currentTimeMillis()
        synchronized(this) {
            alDia(ctx, ahora)
            val clave = "$paquete|$dominio|$motivo"
            val antes = recientes[clave]
            if (antes != null && ahora - antes < VENTANA) return
            recientes[clave] = ahora
            val a = hoy.getOrPut(paquete) { DeApp() }
            a.consultas++
            a.ultima = ahora
            when (motivo) {
                LISTA -> a.lista++
                SIN_RED -> a.sinRed++
            }
            val d = a.dominios[dominio] ?: if (a.dominios.size < MAX_DOMINIOS) Dominio().also { a.dominios[dominio] = it } else null
            if (d != null) {
                d.consultas++
                if (motivo != PASA) d.bloqueadas++
                d.ultima = ahora
                d.motivo = motivo
            }
            sucio = true
            avisoPendiente = true
        }
    }

    /** Lo llama el cortafuegos cada pocos segundos: avisa a las pantallas y guarda cada medio minuto. */
    fun tic(ctx: Context) {
        val avisar = synchronized(this) { avisoPendiente.also { avisoPendiente = false } }
        if (avisar) _cambios.value++
        if (System.currentTimeMillis() - guardado > 30_000) guardar(ctx)
    }

    /** Si ha cambiado el día, se guarda el anterior y se empieza uno nuevo. La primera vez, se lee el de hoy. */
    private fun alDia(ctx: Context, ahora: Long) {
        val f = fecha(ahora)
        if (f == dia) return
        if (dia.isNotEmpty() && sucio) escribir(ctx, dia, hoy)
        hoy = leerDia(ctx, f) ?: HashMap()
        dia = f
        sucio = false
        recientes.clear()
        // Rotación: fuera los días que pasan de una semana.
        val limite = fecha(ahora - (DIAS - 1) * 86_400_000L)
        carpeta(ctx).listFiles()?.forEach { if (it.name.removeSuffix(".json") < limite) it.delete() }
    }

    fun guardar(ctx: Context) {
        val (d, copia) = synchronized(this) {
            guardado = System.currentTimeMillis()
            if (!sucio || dia.isEmpty()) return
            sucio = false
            dia to copiar(hoy)
        }
        escribir(ctx, d, copia)
    }

    private fun escribir(ctx: Context, d: String, datos: Map<String, DeApp>) = runCatching {
        val apps = JSONObject()
        for ((p, a) in datos) {
            val doms = JSONObject()
            for ((n, x) in a.dominios) doms.put(n, JSONArray().put(x.consultas).put(x.bloqueadas).put(x.ultima).put(x.motivo))
            apps.put(p, JSONObject().put("c", a.consultas).put("l", a.lista).put("s", a.sinRed).put("u", a.ultima).put("d", doms))
        }
        val destino = File(carpeta(ctx), "$d.json")
        val temporal = File(destino.path + ".tmp")
        temporal.writeText(JSONObject().put("dia", d).put("apps", apps).toString())
        temporal.renameTo(destino)
    }

    private fun leerDia(ctx: Context, d: String): HashMap<String, DeApp>? = runCatching {
        val f = File(carpeta(ctx), "$d.json")
        if (!f.exists()) return null
        val apps = JSONObject(f.readText()).getJSONObject("apps")
        val r = HashMap<String, DeApp>()
        for (p in apps.keys()) {
            val o = apps.getJSONObject(p)
            val a = DeApp().apply {
                consultas = o.optInt("c"); lista = o.optInt("l"); sinRed = o.optInt("s"); ultima = o.optLong("u")
            }
            val doms = o.optJSONObject("d") ?: JSONObject()
            for (n in doms.keys()) {
                val x = doms.getJSONArray(n)
                a.dominios[n] = Dominio(x.optInt(0), x.optInt(1), x.optLong(2), x.optInt(3))
            }
            r[p] = a
        }
        r
    }.getOrNull()

    private fun copiar(m: Map<String, DeApp>): HashMap<String, DeApp> {
        val r = HashMap<String, DeApp>(m.size)
        for ((p, a) in m) r[p] = DeApp().also { c ->
            c.consultas = a.consultas; c.lista = a.lista; c.sinRed = a.sinRed; c.ultima = a.ultima
            for ((n, d) in a.dominios) c.dominios[n] = Dominio(d.consultas, d.bloqueadas, d.ultima, d.motivo)
        }
        return r
    }

    private fun sumar(a: DeApp, b: DeApp) {
        a.consultas += b.consultas; a.lista += b.lista; a.sinRed += b.sinRed; a.ultima = maxOf(a.ultima, b.ultima)
        for ((n, d) in b.dominios) {
            val x = a.dominios[n]
            if (x == null) a.dominios[n] = Dominio(d.consultas, d.bloqueadas, d.ultima, d.motivo)
            else {
                x.consultas += d.consultas; x.bloqueadas += d.bloqueadas
                if (d.ultima > x.ultima) { x.ultima = d.ultima; x.motivo = d.motivo }
            }
        }
    }

    /** Lo de hoy (si [dias] es 1) o lo de los últimos [dias] días, por app. Fuera del hilo principal. */
    fun periodo(ctx: Context, dias: Int): Map<String, DeApp> {
        val ahora = System.currentTimeMillis()
        val r = synchronized(this) { alDia(ctx, ahora); copiar(hoy) }
        for (i in 1 until dias) {
            val otro = leerDia(ctx, fecha(ahora - i * 86_400_000L)) ?: continue
            for ((p, a) in otro) { val x = r[p]; if (x == null) r[p] = a else sumar(x, a) }
        }
        return r
    }

    /** Rastreadores bloqueados hoy (en todas las apps). */
    fun bloqueadosHoy(ctx: Context): Int = synchronized(this) {
        alDia(ctx, System.currentTimeMillis())
        hoy.values.sumOf { it.lista }
    }

    /** Borra todo el registro (el de hoy también). */
    fun borrar(ctx: Context) {
        synchronized(this) {
            hoy = HashMap(); sucio = false; recientes.clear()
            carpeta(ctx).listFiles()?.forEach { it.delete() }
        }
        _cambios.value++
    }
}
