package com.sios.datos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ---------- Modelo del chat ----------

enum class EstadoPaso { EN_CURSO, HECHO, ERROR }
enum class EstadoPermiso { PENDIENTE, PERMITIDO, DENEGADO }

data class Paso(val id: String, val resumen: String, val estado: EstadoPaso)

sealed interface Elemento {
    val clave: String

    data class Usuario(override val clave: String, val texto: String) : Elemento
    data class Respuesta(override val clave: String, val texto: String, val escribiendo: Boolean) : Elemento
    data class Pasos(override val clave: String, val pasos: List<Paso>, val terminado: Boolean) : Elemento
    data class Permiso(
        override val clave: String,
        val id: String,
        val titulo: String,
        val detalle: String?,
        val codigo: Boolean,
        val estado: EstadoPermiso,
        val aviso: String? = null, // por qué se pide (Claude leyó contenido de otros)
    ) : Elemento

    data class Error(override val clave: String, val texto: String) : Elemento
}

data class Ventana(val porcentaje: Int, val reinicio: Long?)

data class Uso(
    val cincoHoras: Ventana?,
    val semana: Ventana?,
    val semanaModelos: List<Pair<String, Ventana>>,
    val actualizado: Long?,
)

data class Ajustes(
    val modelo: String = "sonnet",
    val esfuerzo: String = "low",
    val web: Boolean = true,
    val permisos: String = "equilibrado",
    val ahorro: Boolean = true, // pasar a Haiku cuando quede poco del límite
)

/** `titulo` vacío si la conversación no tiene título: la pantalla pone el genérico. */
data class Sesion(val id: String, val titulo: String, val actualizada: Long)

/** Resumen del último turno; el texto («Sonnet 5.5 · 5,6 k tokens · 4 s») lo compone la pantalla. */
data class Turno(val modelo: String, val tokens: Int, val segundos: Int)

/**
 * Algo que ha creado Claude (creaciones.mjs): widget del inicio, mini-app o agente en segundo plano.
 * El diseño es fijo; aquí solo llega el contenido.
 */
data class Creacion(
    val id: String,
    val tipo: String, // widget, app, agente, rutina
    val nombre: String,
    val acento: String,
    val icono: String?,
    val plantilla: String?, // cifra, lista, texto, progreso
    val titulo: String?,
    val valor: String?,
    val unidad: String?,
    val subtitulo: String?,
    val texto: String?,
    val progreso: Float?,
    val elementos: List<ElementoWidget>,
    val cada: Int?, // minutos (agentes y widgets con origen)
    val pausado: Boolean,
    val ultima: Long?,
    val resultado: String?,
    val error: String?,
    val lugar: String = "inicio", // inicio | panel (widgets)
    val alto: Int? = null, // px CSS de un widget html (si no, se ajusta al contenido)
    val datosJson: String = "{}", // los datos tal cual, para los widgets html
    val actualizado: Long = 0,
    val htmlHuella: Int = 0, // cambia cuando Claude rediseña un widget html
    val tamano: String = "mediano", // en el panel: pequeno (1×1), mediano (2×1), grande (2×2)
    val botones: List<Boton> = emptyList(),
    val marcable: Boolean = false,
    val serie: List<Punto> = emptyList(),
    val tipoGrafico: String = "linea",
    val local: String? = null, val visitante: String? = null, val golesLocal: String? = null, val golesVisitante: String? = null,
    val estadoPartido: String? = null, val enJuego: Boolean = false,
    val pila: List<String> = emptyList(), // ids de los widgets de una pila
    val pilaActual: String? = null, // el que toca enseñar ahora
    val automatica: Boolean = false,
)

/** Una fila de un widget: lista, noticias (titular, fuente, resumen, enlace), agenda (hora) o filas (valor). */
data class ElementoWidget(
    val texto: String,
    val detalle: String? = null,
    val valor: String? = null,
    val hora: String? = null,
    val fuente: String? = null,
    val resumen: String? = null,
    val enlace: String? = null,
    val hecho: Boolean? = null, // listas con casillas
    val accion: Accion? = null, // tocar la fila
)

/** Lo que hace un botón o una fila de un widget (lo puso Claude al crearlo). */
data class Accion(val tipo: String, val url: String? = null, val numero: String? = null, val destino: String? = null, val lat: Double? = null, val lon: Double? = null)

data class Boton(val texto: String?, val icono: String?, val accion: Accion?)

data class Punto(val x: String, val y: Double)

/** Modos del panel (trabajo, casa, finde…). */
data class ModoPanel(val id: String, val nombre: String)

/** El panel a la izquierda del inicio: título, orden de sus tarjetas, modos y el activo. */
data class Panel(
    val titulo: String? = null,
    val orden: List<String> = emptyList(),
    val modos: List<ModoPanel> = emptyList(),
    val modo: String? = null,
    val modoManual: String? = null,
)

/** Sesión de Claude en el móvil (cuenta.mjs). conectada = null mientras no se sabe. */
data class Cuenta(
    val conectada: Boolean? = null,
    val entrando: Boolean = false,
    val enlace: String? = null, // inicio de sesión que hay que abrir en el navegador
    val pideCodigo: Boolean = false, // plan B: el enlace manual pide pegar un código
    val error: String? = null,
    val email: String? = null,
)

data class Estado(
    val conectado: Boolean = false,
    val ocupado: Boolean = false,
    val conversacionId: String = "",
    val titulo: String = "",
    val elementos: List<Elemento> = emptyList(),
    val ajustes: Ajustes = Ajustes(),
    val uso: Uso? = null,
    val modeloReal: String? = null, // el que responde de verdad, p. ej. "claude-sonnet-5-5"
    val ultimoTurno: Turno? = null,
    val cuenta: Cuenta = Cuenta(),
)

/**
 * Cliente del servicio de SIOS (Termux, 127.0.0.1:8765).
 * Mantiene el estado del chat a partir de los eventos SSE; no llama a Claude por su cuenta:
 * solo envía lo que el usuario escribe.
 */
object Servicio {
    private const val BASE = "http://127.0.0.1:8765"
    private val JSON_TIPO = "application/json; charset=utf-8".toMediaType()

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE: conexión abierta indefinidamente
        .build()
    private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private val _creaciones = MutableStateFlow<List<Creacion>>(emptyList())
    val creaciones: StateFlow<List<Creacion>> = _creaciones.asStateFlow()

    private val _sesiones = MutableStateFlow<List<Sesion>>(emptyList())
    val sesiones: StateFlow<List<Sesion>> = _sesiones.asStateFlow()

    private var fuente: EventSource? = null
    private var iniciado = false
    private var contador = 0L
    private fun nuevaClave() = "e${contador++}"

    /** Trae SIOS al frente (tras entrar en una cuenta en el navegador). */
    private fun volverAlFrente() {
        val ctx = com.sios.ui.Marca.contexto() ?: return
        runCatching {
            ctx.startActivity(
                android.content.Intent(ctx, com.sios.inicio.InicioActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    fun iniciar() {
        if (iniciado) return
        iniciado = true
        conectar()
    }

    private fun conectar() {
        val peticion = Request.Builder().url("$BASE/eventos").build()
        fuente = EventSources.createFactory(http).newEventSource(peticion, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                _estado.update { it.copy(conectado = true) }
                alcance.launch { cargarSesiones() }
                alcance.launch { cargarCreaciones() }
                alcance.launch { get("/cuenta")?.let { c -> _estado.update { it.copy(cuenta = leerCuenta(c)) } } }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                runCatching { aplicar(JSONObject(data)) }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                _estado.update { it.copy(conectado = false) }
                alcance.launch {
                    delay(2000)
                    conectar()
                }
            }

            override fun onClosed(eventSource: EventSource) = onFailure(eventSource, null, null)
        })
    }

    // ---------- Eventos del servicio → estado ----------

    @Synchronized
    private fun aplicar(ev: JSONObject) {
        when (ev.getString("tipo")) {
            "estado" -> {
                val historial = ev.optJSONArray("historial") ?: JSONArray()
                var lista = emptyList<Elemento>()
                for (i in 0 until historial.length()) lista = reducir(lista, historial.getJSONObject(i))
                val conv = ev.optJSONObject("conversacion")
                _estado.update {
                    it.copy(
                        elementos = cerrarTurno(lista),
                        ocupado = ev.optBoolean("ocupado"),
                        conversacionId = conv?.optString("id") ?: "",
                        titulo = conv?.optString("titulo") ?: "",
                        ajustes = ev.optJSONObject("ajustes")?.let(::leerAjustes) ?: it.ajustes,
                        uso = ev.optJSONObject("uso")?.let(::leerUso) ?: it.uso,
                        modeloReal = ev.optString("modeloReal").takeIf { r -> r.isNotBlank() && r != "null" },
                    )
                }
                com.sios.ui.Marca.poner(ev.optJSONObject("ia"))
                alcance.launch { cargarSesiones() }
            }
            "ocupado" -> {
                val v = ev.getBoolean("valor")
                _estado.update { it.copy(ocupado = v, elementos = if (v) it.elementos else cerrarTurno(it.elementos)) }
            }
            "uso" -> _estado.update { it.copy(uso = leerUso(ev.getJSONObject("uso"))) }
            "ajustes" -> _estado.update { it.copy(ajustes = leerAjustes(ev.getJSONObject("ajustes"))) }
            "modelo" -> _estado.update { it.copy(modeloReal = ev.optString("real").takeIf { r -> r.isNotBlank() && r != "null" }) }
            "sesiones" -> alcance.launch { cargarSesiones() }
            "creaciones" -> alcance.launch { cargarCreaciones() }
            "actividad" -> _cambiosActividad.update { it + 1 }
            "cuenta" -> {
                val nueva = leerCuenta(ev.getJSONObject("cuenta"))
                val antes = _estado.value.cuenta
                _estado.update { it.copy(cuenta = nueva) }
                // Se acaba de entrar en el navegador: SIOS vuelve al frente solo.
                if (antes.entrando && nueva.conectada == true) volverAlFrente()
            }
            "ia" -> ev.optJSONObject("ia")?.let { e ->
                // Una IA que estaba entrando (ChatGPT, Gemini, Kimi) ha quedado lista: SIOS vuelve al frente.
                val antes = _ia.value?.optJSONArray("proveedores")
                val ahora = e.optJSONArray("proveedores")
                if (antes != null && ahora != null) {
                    val entrando = (0 until antes.length()).map { antes.getJSONObject(it) }
                        .filter { it.optJSONObject("tarea")?.optBoolean("entrando") == true }.map { it.optString("id") }.toSet()
                    if ((0 until ahora.length()).map { ahora.getJSONObject(it) }.any { it.optString("id") in entrando && it.optBoolean("listo") }) volverAlFrente()
                }
                _ia.value = e
                // La identidad ya resuelta (la IA elegida o, sin elegir, SIOS); los servicios
                // anteriores no la mandan y se saca de la lista.
                e.optJSONObject("identidad")?.let { com.sios.ui.Marca.poner(it) } ?: run {
                    val activo = e.optString("activo")
                    val lista = e.optJSONArray("proveedores")
                    for (i in 0 until (lista?.length() ?: 0)) lista!!.getJSONObject(i).takeIf { it.optString("id") == activo }?.let { com.sios.ui.Marca.poner(it) }
                }
            }
            "usuario" -> {
                // El servicio titula la conversación con el primer mensaje; aquí se refleja al momento.
                val t = ev.getString("texto").replace(Regex("\\s+"), " ")
                _estado.update {
                    it.copy(
                        elementos = reducir(it.elementos, ev),
                        titulo = it.titulo.ifBlank { if (t.length > 60) t.take(59) + "…" else t },
                    )
                }
            }
            "fin" -> {
                val t = ev.optJSONObject("tokens")
                val total = (t?.optInt("entrada") ?: 0) + (t?.optInt("cache") ?: 0) + (t?.optInt("salida") ?: 0)
                _estado.update { it.copy(ultimoTurno = Turno(nombreModelo(ev.optString("modelo")), total, ev.optInt("segundos"))) }
            }
            else -> _estado.update { it.copy(elementos = reducir(it.elementos, ev)) }
        }
    }

    private fun reducir(lista: List<Elemento>, ev: JSONObject): List<Elemento> {
        val ultimo = lista.lastOrNull()
        return when (ev.getString("tipo")) {
            "usuario" -> lista + Elemento.Usuario(nuevaClave(), ev.getString("texto"))
            "texto_inicio" ->
                if (ultimo is Elemento.Respuesta && ultimo.escribiendo) lista
                else lista + Elemento.Respuesta(nuevaClave(), "", true)
            "texto_delta" ->
                if (ultimo is Elemento.Respuesta && ultimo.escribiendo)
                    lista.dropLast(1) + ultimo.copy(texto = ultimo.texto + ev.getString("texto"))
                else lista + Elemento.Respuesta(nuevaClave(), ev.getString("texto"), true)
            "texto" ->
                if (ultimo is Elemento.Respuesta && ultimo.escribiendo)
                    lista.dropLast(1) + ultimo.copy(texto = ev.getString("texto"), escribiendo = false)
                else lista + Elemento.Respuesta(nuevaClave(), ev.getString("texto"), false)
            "herramienta" -> {
                val paso = Paso(ev.getString("id"), ev.getString("resumen"), leerPaso(ev.optString("estado")))
                if (ultimo is Elemento.Pasos && !ultimo.terminado) lista.dropLast(1) + ultimo.copy(pasos = ultimo.pasos + paso)
                else lista + Elemento.Pasos(nuevaClave(), listOf(paso), false)
            }
            "herramienta_fin" -> {
                val id = ev.getString("id")
                val estado = leerPaso(ev.optString("estado"))
                lista.map { e ->
                    if (e is Elemento.Pasos && e.pasos.any { it.id == id })
                        e.copy(pasos = e.pasos.map { if (it.id == id) it.copy(estado = estado) else it })
                    else e
                }
            }
            "permiso" -> lista + Elemento.Permiso(
                nuevaClave(),
                ev.getString("id"),
                ev.getString("titulo"),
                ev.optString("detalle").takeIf { it.isNotBlank() && it != "null" },
                ev.optBoolean("codigo"),
                leerPermiso(ev.optString("estado")),
                ev.optString("aviso").takeIf { it.isNotBlank() && it != "null" },
            )
            "permiso_resuelto" -> {
                val id = ev.getString("id")
                lista.map { if (it is Elemento.Permiso && it.id == id) it.copy(estado = leerPermiso(ev.getString("estado"))) else it }
            }
            // Con clave, en el idioma del móvil; si no, el texto que manda el servicio.
            "error" -> lista + Elemento.Error(nuevaClave(), when (ev.optString("clave")) {
                "sin_ia" -> com.sios.ui.Marca.textoDe(com.sios.R.string.chat_sin_ia)
                else -> null
            } ?: ev.getString("texto"))
            "nueva" -> emptyList()
            else -> lista
        }
    }

    /** Al acabar un turno, los grupos de pasos quedan cerrados y las respuestas, terminadas. */
    private fun cerrarTurno(lista: List<Elemento>) = lista.map {
        when (it) {
            is Elemento.Pasos -> it.copy(terminado = true)
            is Elemento.Respuesta -> it.copy(escribiendo = false)
            else -> it
        }
    }

    private fun leerPaso(s: String) = when (s) {
        "hecho" -> EstadoPaso.HECHO
        "error" -> EstadoPaso.ERROR
        else -> EstadoPaso.EN_CURSO
    }

    private fun leerPermiso(s: String) = when (s) {
        "permitido" -> EstadoPermiso.PERMITIDO
        "denegado" -> EstadoPermiso.DENEGADO
        else -> EstadoPermiso.PENDIENTE
    }

    private fun JSONObject.texto(clave: String) = optString(clave).takeIf { has(clave) && !isNull(clave) && it.isNotBlank() }

    private fun leerAccion(a: JSONObject) = Accion(
        tipo = a.optString("tipo"), url = a.texto("url"), numero = a.texto("numero"), destino = a.texto("destino"),
        lat = a.optDouble("lat").takeIf { !it.isNaN() }, lon = a.optDouble("lon").takeIf { !it.isNaN() },
    )

    private fun leerCreacion(o: JSONObject): Creacion {
        val d = o.optJSONObject("datos") ?: JSONObject()
        val est = o.optJSONObject("estado") ?: JSONObject()
        val els = d.optJSONArray("elementos") ?: JSONArray()
        return Creacion(
            id = o.getString("id"),
            tipo = o.optString("tipo"),
            nombre = o.optString("nombre"),
            acento = o.optString("acento", "naranja"),
            icono = o.texto("icono"),
            plantilla = o.texto("plantilla"),
            titulo = d.texto("titulo"),
            valor = d.texto("valor"),
            unidad = d.texto("unidad"),
            subtitulo = d.texto("subtitulo"),
            texto = d.texto("texto"),
            progreso = if (d.has("progreso")) d.optDouble("progreso").toFloat().takeIf { !it.isNaN() } else null,
            elementos = (0 until els.length()).mapNotNull { i ->
                els.optJSONObject(i)?.let { e ->
                    ElementoWidget(
                        texto = e.optString("texto"), detalle = e.texto("detalle"), valor = e.texto("valor"), hora = e.texto("hora"),
                        fuente = e.texto("fuente"), resumen = e.texto("resumen"), enlace = e.texto("enlace"),
                        hecho = if (e.has("hecho")) e.optBoolean("hecho") else null, accion = e.optJSONObject("accion")?.let(::leerAccion),
                    )
                } ?: els.optString(i).takeIf { it.isNotBlank() }?.let { ElementoWidget(it) }
            },
            cada = (if (o.has("cada")) o.optInt("cada") else o.optJSONObject("origen")?.optInt("cada"))?.takeIf { it > 0 },
            pausado = o.optBoolean("pausado"),
            ultima = est.optLong("ultima").takeIf { it > 0 },
            resultado = est.texto("resultado"),
            error = est.texto("error"),
            lugar = o.optString("lugar", "inicio"),
            alto = o.optInt("alto").takeIf { it > 0 },
            datosJson = d.toString(),
            actualizado = o.optLong("actualizado"),
            htmlHuella = o.optString("html").hashCode(),
            tamano = o.optString("tamano", "mediano"),
            botones = ((o.optJSONArray("botones") ?: d.optJSONArray("botones")) ?: JSONArray()).let { b ->
                (0 until b.length()).mapNotNull { i -> b.optJSONObject(i)?.let { Boton(it.texto("texto"), it.texto("icono"), it.optJSONObject("accion")?.let(::leerAccion)) } }
            },
            marcable = o.optBoolean("marcable"),
            serie = (d.optJSONArray("serie") ?: JSONArray()).let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { p -> Punto(p.optString("x"), p.optDouble("y")).takeIf { !it.y.isNaN() } } }
            },
            tipoGrafico = d.optString("tipo", "linea"),
            local = d.texto("local"), visitante = d.texto("visitante"), golesLocal = d.texto("goles_local"), golesVisitante = d.texto("goles_visitante"),
            estadoPartido = d.texto("estado"), enJuego = d.optBoolean("en_juego"),
            pila = (o.optJSONArray("pila") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } },
            pilaActual = est.texto("actual"),
            automatica = o.has("auto"),
        )
    }

    private val _panel = MutableStateFlow(Panel())
    val panel: StateFlow<Panel> = _panel.asStateFlow()

    /** Nuevo orden del panel (al reordenar a mano). */
    fun ordenarPanel(ids: List<String>) {
        _panel.value = _panel.value.copy(orden = ids)
        alcance.launch { post("/panel", JSONObject().put("orden", JSONArray(ids))) }
    }

    /** La medida en pantalla de un widget de diseño libre (para que Claude sepa si cabe). */
    fun anotarMedida(id: String, json: String) = alcance.launch { runCatching { post("/creaciones/$id/medida", JSONObject(json)) } }

    /** Un botón o una fila de un widget que ejecuta el servicio. Devuelve el aviso que enseñar (o null). */
    suspend fun accionWidget(id: String, boton: Int? = null, elemento: Int? = null): String? = withContext(Dispatchers.IO) {
        val r = post("/creaciones/$id/accion", JSONObject().apply { boton?.let { put("boton", it) }; elemento?.let { put("elemento", it) } })
        cargarCreaciones()
        r?.texto("aviso")
    }

    /** Modo del panel elegido a mano (null: automático). */
    fun elegirModo(id: String?) = alcance.launch { post("/panel", JSONObject().put("modo_manual", id ?: JSONObject.NULL)); cargarCreaciones() }

    /** Galería: añadir un widget de una fuente sin pasar por Claude. */
    fun anadirAlPanel(fuente: String, tamano: String, opciones: JSONObject = JSONObject(), nombre: String? = null) = alcance.launch {
        post("/panel/anadir", JSONObject().put("fuente", fuente).put("tamano", tamano).put("opciones", opciones).apply { nombre?.let { put("nombre", it) } })
        cargarCreaciones()
    }

    suspend fun historialPanel(): JSONArray = withContext(Dispatchers.IO) { get("/panel/historial")?.optJSONArray("historial") ?: JSONArray() }

    fun restaurarPanel(id: String) = alcance.launch { post("/panel/restaurar", JSONObject().put("id", id)); cargarCreaciones() }

    suspend fun ajustesPanel(): JSONObject? = withContext(Dispatchers.IO) { get("/panel/ajustes") }

    /** Compartir: enlace sios://importar?d=… de una creación; vista previa y añadir uno recibido. */
    suspend fun exportarCreacion(id: String): JSONObject? = withContext(Dispatchers.IO) { post("/compartir/exportar", JSONObject().put("id", id)) }
    suspend fun verCompartido(codigo: String): JSONObject? = withContext(Dispatchers.IO) { post("/compartir/ver", JSONObject().put("codigo", codigo)) }
    suspend fun importarCompartido(codigo: String): JSONObject? = withContext(Dispatchers.IO) { post("/compartir/importar", JSONObject().put("codigo", codigo)) }

    /** Lanzar algo del servicio sin esperar (desde sitios sin corrutinas propias, como el buscador). */
    fun alcanceGlobal(b: suspend () -> Unit) { alcance.launch { b() } }

    /** Familia: estado y acciones (crear, unirse, salir, ubicacion, anadir…). */
    suspend fun familia(cambios: JSONObject? = null): JSONObject? = withContext(Dispatchers.IO) { if (cambios == null) get("/familia") else post("/familia", cambios) }

    /** Buenas noches: ajustes (y empezar o terminar ya). */
    suspend fun buenasNoches(cambios: JSONObject? = null): JSONObject? = withContext(Dispatchers.IO) { if (cambios == null) get("/buenas-noches") else post("/buenas-noches", cambios) }

    /** Galería comunitaria: lista, un widget (con su enlace), publicar uno propio, contar un uso. */
    suspend fun comunidad(orden: String): JSONObject? = withContext(Dispatchers.IO) { get("/comunidad?orden=$orden") }
    suspend fun comunidadUno(id: String): JSONObject? = withContext(Dispatchers.IO) { post("/comunidad/uno", JSONObject().put("id", id)) }
    suspend fun publicarComunidad(id: String, descripcion: String): JSONObject? = withContext(Dispatchers.IO) { post("/comunidad/publicar", JSONObject().put("id", id).put("descripcion", descripcion)) }
    suspend fun comunidadUsado(id: String) = withContext(Dispatchers.IO) { post("/comunidad/usado", JSONObject().put("id", id)) }
    suspend fun comunidadDenunciar(id: String): JSONObject? = withContext(Dispatchers.IO) { post("/comunidad/denunciar", JSONObject().put("id", id)) }

    /** Qué ha salido del móvil estos días (destino, motivo y tamaño; nunca el contenido). */
    suspend fun privacidad(): JSONObject? = withContext(Dispatchers.IO) { get("/privacidad") }

    /** La IA del sistema (Claude, ChatGPT, Gemini, API o local). Nunca devuelve claves. */
    private val _ia = MutableStateFlow<JSONObject?>(null)
    val ia: StateFlow<JSONObject?> = _ia
    suspend fun cargarIa(): JSONObject? = withContext(Dispatchers.IO) { get("/ia")?.also { _ia.value = it } }
    private suspend fun accionIa(ruta: String, cuerpo: JSONObject): JSONObject? = withContext(Dispatchers.IO) {
        post(ruta, cuerpo)?.also { if (it.has("proveedores")) _ia.value = it }
    }
    suspend fun elegirIa(id: String) = accionIa("/ia/elegir", JSONObject().put("id", id))
    suspend fun modeloIa(id: String, modelo: String) = accionIa("/ia/modelo", JSONObject().put("id", id).put("modelo", modelo))
    suspend fun modelosIa(id: String): JSONObject? = withContext(Dispatchers.IO) { get("/ia/modelos?id=$id") }
    suspend fun claveIa(id: String, clave: String) = accionIa("/ia/clave", JSONObject().put("id", id).put("clave", clave))
    suspend fun entrarIa(id: String) = accionIa("/ia/entrar", JSONObject().put("id", id))
    suspend fun codigoIa(id: String, codigo: String) = accionIa("/ia/codigo", JSONObject().put("id", id).put("codigo", codigo))
    suspend fun cancelarIa() = accionIa("/ia/cancelar", JSONObject())
    suspend fun salirIa(id: String) = accionIa("/ia/salir", JSONObject().put("id", id))
    suspend fun nombreLocal(nombre: String) = accionIa("/ia/nombre", JSONObject().put("nombre", nombre))
    suspend fun descargarLocal(modelo: String) = accionIa("/ia/local/descargar", JSONObject().put("modelo", modelo))
    suspend fun borrarLocal(modelo: String) = accionIa("/ia/local/borrar", JSONObject().put("modelo", modelo))

    /** Continuidad con el Mac: estado, ajustes y código para vincular. */
    suspend fun continuidad(): JSONObject? = withContext(Dispatchers.IO) { get("/continuidad") }
    suspend fun ponerContinuidad(cambios: JSONObject): JSONObject? = withContext(Dispatchers.IO) { post("/continuidad", cambios) }
    suspend fun codigoContinuidad(): JSONObject? = withContext(Dispatchers.IO) { post("/continuidad/codigo") }

    fun ponerAjustesPanel(cambios: JSONObject) = alcance.launch { post("/panel/ajustes", cambios) }

    /** Home Assistant: dirección y token (los escribe el usuario; se guardan en el agente, nunca se leen de vuelta). */
    suspend fun conectarCasa(url: String?, token: String?): Boolean = withContext(Dispatchers.IO) {
        post("/panel/casa", JSONObject().apply { if (url != null) { put("url", url); put("token", token) } })?.optBoolean("casa") ?: false
    }

    /** Tamaño de un widget del panel, a mano (se ve al momento y se guarda). */
    fun cambiarTamano(id: String, tamano: String) {
        _creaciones.value = _creaciones.value.map { if (it.id == id) it.copy(tamano = tamano) else it }
        alcance.launch { post("/creaciones/$id/tamano", JSONObject().put("tamano", tamano)) }
    }

    /** Al abrir el panel (o al tirar hacia abajo, forzando): el servicio refresca lo que toque. */
    fun refrescarPanel(forzar: Boolean = false) = alcance.launch { post("/panel/refrescar", JSONObject().put("forzar", forzar)) }

    suspend fun cargarCreaciones() = withContext(Dispatchers.IO) {
        val r = get("/creaciones") ?: return@withContext
        r.optJSONObject("panel")?.let { p ->
            val orden = p.optJSONArray("orden") ?: JSONArray()
            val modos = p.optJSONArray("modos") ?: JSONArray()
            _panel.value = Panel(
                p.texto("titulo"), (0 until orden.length()).map { orden.getString(it) },
                (0 until modos.length()).map { modos.getJSONObject(it).let { m -> ModoPanel(m.optString("id"), m.optString("nombre")) } },
                p.texto("modo"), p.texto("modo_manual"),
            )
        }
        val lista = r.optJSONArray("creaciones") ?: return@withContext
        _creaciones.value = (0 until lista.length()).mapNotNull { runCatching { leerCreacion(lista.getJSONObject(it)) }.getOrNull() }
        // Las rutinas, tal cual: la app vigila sus disparadores (sistema/Disparadores.kt).
        _rutinas.value = (0 until lista.length()).map { lista.getJSONObject(it) }.filter { it.optString("tipo") == "rutina" && !it.optBoolean("pausado") }
    }
    private val _rutinas = MutableStateFlow<List<JSONObject>>(emptyList())
    val rutinas: StateFlow<List<JSONObject>> = _rutinas.asStateFlow()

    // ---------- Actualizaciones ----------

    suspend fun actualizaciones(): JSONObject? = withContext(Dispatchers.IO) { get("/actualizaciones") }
    suspend fun buscarActualizaciones(): JSONObject? = withContext(Dispatchers.IO) { lentoPost("/actualizaciones/buscar") }
    suspend fun instalarActualizaciones(): JSONObject? = withContext(Dispatchers.IO) { lentoPost("/actualizaciones/instalar") }
    fun actualizacionesSolas(si: Boolean) = alcance.launch { post("/actualizaciones/ajustes", JSONObject().put("automaticas", si)) }

    private fun lentoPost(ruta: String): JSONObject? = runCatching {
        val peticion = Request.Builder().url("$BASE$ruta").post("{}".toRequestBody(JSON_TIPO)).build()
        lento.newCall(peticion).execute().use { r -> JSONObject(r.body.string()) }
    }.getOrNull()

    // ---------- Actividad de Claude ----------

    private val _cambiosActividad = MutableStateFlow(0)
    /** Sube cada vez que hay una entrada nueva en el registro (para refrescar la pantalla). */
    val cambiosActividad: StateFlow<Int> = _cambiosActividad.asStateFlow()

    suspend fun actividad(todo: Boolean): List<JSONObject> = withContext(Dispatchers.IO) {
        val l = get("/actividad${if (todo) "?todo=1" else ""}")?.optJSONArray("actividad") ?: return@withContext emptyList()
        (0 until l.length()).map { l.getJSONObject(it) }
    }

    suspend fun deshacer(id: String): Boolean = withContext(Dispatchers.IO) { post("/actividad/$id/deshacer")?.optBoolean("ok") == true }

    /** Lo que hace la app por su cuenta (responder, bloquear…) también va al registro. */
    fun registrarActividad(frase: String, detalle: String? = null, deshacer: JSONObject? = null) = alcance.launch {
        post("/actividad", JSONObject().put("frase", frase).put("herramienta", "app").apply {
            if (detalle != null) put("detalle", detalle)
            if (deshacer != null) put("deshacer", deshacer)
        })
    }

    /** Algo ha pasado en el móvil que puede disparar una rutina. */
    fun disparo(evento: JSONObject) = alcance.launch { post("/disparo", evento) }

    fun ejecutarCreacion(id: String) = alcance.launch { post("/creaciones/$id/ejecutar"); cargarCreaciones() }
    fun pausarCreacion(id: String) = alcance.launch { post("/creaciones/$id/pausar"); cargarCreaciones() }
    fun borrarCreacion(id: String) = alcance.launch { post("/creaciones/$id/borrar"); cargarCreaciones() }

    private fun leerCuenta(o: JSONObject) = Cuenta(
        conectada = if (o.has("conectada") && !o.isNull("conectada")) o.getBoolean("conectada") else null,
        entrando = o.optBoolean("entrando"),
        enlace = o.texto("enlace"),
        pideCodigo = o.optBoolean("pideCodigo"),
        error = o.texto("error"),
        email = o.texto("email"),
    )

    private fun leerAjustes(o: JSONObject) = Ajustes(
        o.optString("modelo", "sonnet"),
        o.optString("esfuerzo", "low"),
        o.optBoolean("web", true),
        o.optString("permisos", "equilibrado"),
        o.optBoolean("ahorro", true),
    )

    private fun leerVentana(o: JSONObject?) =
        o?.let { Ventana(it.optInt("porcentaje"), if (it.isNull("reinicio")) null else it.optLong("reinicio")) }

    private fun leerUso(o: JSONObject): Uso {
        val otros = o.optJSONArray("semana_modelos") ?: JSONArray()
        return Uso(
            cincoHoras = leerVentana(o.optJSONObject("cinco_horas")),
            semana = leerVentana(o.optJSONObject("semana")),
            semanaModelos = (0 until otros.length()).map {
                val x = otros.getJSONObject(it)
                x.optString("nombre") to Ventana(x.optInt("porcentaje"), x.optLong("reinicio"))
            },
            actualizado = if (o.isNull("actualizado")) null else o.optLong("actualizado"),
        )
    }

    // ---------- Acciones ----------

    /** Antirrobo: «sim» (han cambiado la SIM) o «encontrado» (el dueño quita el modo perdido en el móvil). */
    fun avisoAntirrobo(que: String, datos: JSONObject) {
        alcance.launch { post("/antirrobo/aviso", JSONObject().put("que", que).put("datos", datos)) }
    }

    /** Igual, pero esperando: dice si el servicio lo ha recibido (para reintentar si no). Fuera del hilo principal. */
    fun avisoAntirroboAhora(que: String, datos: JSONObject): Boolean =
        post("/antirrobo/aviso", JSONObject().put("que", que).put("datos", datos))?.optBoolean("ok") == true

    private fun post(ruta: String, cuerpo: JSONObject = JSONObject()): JSONObject? = runCatching {
        val peticion = Request.Builder().url("$BASE$ruta").post(cuerpo.toString().toRequestBody(JSON_TIPO)).build()
        http.newCall(peticion).execute().use { r -> r.body.string().let { if (it.isBlank()) JSONObject() else JSONObject(it) } }
    }.getOrNull()

    private fun get(ruta: String): JSONObject? = runCatching {
        val peticion = Request.Builder().url("$BASE$ruta").get().build()
        http.newCall(peticion).execute().use { r -> JSONObject(r.body.string()) }
    }.getOrNull()

    fun enviar(texto: String) = alcance.launch { post("/mensaje", JSONObject().put("texto", texto)) }

    /** Una orden atendida sin conexión: se ve en la conversación como cualquier otra. */
    fun anadirLocal(pregunta: String, respuesta: String) = _estado.update {
        it.copy(elementos = it.elementos + Elemento.Usuario(nuevaClave(), pregunta) + Elemento.Respuesta(nuevaClave(), respuesta, false))
    }

    /** Entrar en Claude: el servicio lanza `claude auth login` y manda el enlace por eventos. */
    fun entrar() = alcance.launch { post("/cuenta/entrar") }
    fun enviarCodigo(codigo: String) = alcance.launch { post("/cuenta/codigo", JSONObject().put("codigo", codigo)) }
    fun comprobarCuenta() = alcance.launch {
        get("/cuenta?comprobar=1")?.let { c -> _estado.update { it.copy(cuenta = leerCuenta(c)) } }
    }
    fun detener() = alcance.launch { post("/detener") }
    fun nueva() = alcance.launch { post("/nueva") }

    /** Empieza una conversación nueva con este mensaje (barra de Claude, asistente). imagen: JPEG en base64. */
    fun preguntar(texto: String, imagen: String? = null) = alcance.launch {
        post("/nueva")
        post("/mensaje", JSONObject().put("texto", texto).apply { if (imagen != null) put("imagen", imagen) })
    }

    /** Conversación nueva sobre algo compartido desde otra app (texto, fotos, PDF). */
    fun compartir(texto: String, compartido: JSONObject) = alcance.launch {
        post("/nueva")
        post("/mensaje", JSONObject().put("texto", texto).put("compartido", compartido))
    }

    /** Pregunta corta sin herramientas (Haiku). Devuelve la respuesta o null si falla. */
    suspend fun rapido(texto: String, sistema: String? = null): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val cuerpo = JSONObject().put("texto", texto).apply { if (sistema != null) put("sistema", sistema) }
            val peticion = Request.Builder().url("$BASE/rapido").post(cuerpo.toString().toRequestBody(JSON_TIPO)).build()
            lento.newCall(peticion).execute().use { r -> JSONObject(r.body.string()).optString("respuesta").takeIf { it.isNotBlank() } }
        }.getOrNull()
    }
    private val lento by lazy { http.newBuilder().readTimeout(150, java.util.concurrent.TimeUnit.SECONDS).callTimeout(160, java.util.concurrent.TimeUnit.SECONDS).build() }

    fun responderPermiso(id: String, permitir: Boolean) =
        alcance.launch { post("/permiso", JSONObject().put("id", id).put("permitir", permitir)) }

    fun abrir(id: String) = alcance.launch { post("/sesiones/abrir", JSONObject().put("id", id)) }
    fun borrar(id: String) = alcance.launch { post("/sesiones/borrar", JSONObject().put("id", id)); cargarSesiones() }
    fun renombrar(id: String, titulo: String) =
        alcance.launch { post("/sesiones/renombrar", JSONObject().put("id", id).put("titulo", titulo)); cargarSesiones() }

    fun cambiarAjustes(
        modelo: String? = null,
        esfuerzo: String? = null,
        web: Boolean? = null,
        permisos: String? = null,
        ahorro: Boolean? = null,
    ) = alcance.launch {
        // Se refleja al momento; el servicio lo confirma con su evento "ajustes".
        _estado.update {
            it.copy(
                // Con otro modelo, el real se sabrá cuando arranque el siguiente mensaje.
                modeloReal = if (modelo != null && modelo != it.ajustes.modelo) null else it.modeloReal,
                ajustes = it.ajustes.copy(
                    modelo = modelo ?: it.ajustes.modelo,
                    esfuerzo = esfuerzo ?: it.ajustes.esfuerzo,
                    web = web ?: it.ajustes.web,
                    permisos = permisos ?: it.ajustes.permisos,
                    ahorro = ahorro ?: it.ajustes.ahorro,
                )
            )
        }
        val c = JSONObject()
        modelo?.let { c.put("modelo", it) }
        esfuerzo?.let { c.put("esfuerzo", it) }
        web?.let { c.put("web", it) }
        permisos?.let { c.put("permisos", it) }
        ahorro?.let { c.put("ahorro", it) }
        post("/ajustes", c)
    }

    suspend fun cargarSesiones() = withContext(Dispatchers.IO) {
        val r = get("/sesiones") ?: return@withContext
        val a = r.optJSONArray("sesiones") ?: JSONArray()
        _sesiones.value = (0 until a.length()).map {
            val s = a.getJSONObject(it)
            Sesion(s.getString("id"), s.optString("titulo"), s.optLong("actualizada"))
        }
    }

    /** Uso de la suscripción. `refrescar` lee `/usage` de Claude Code (no gasta suscripción). */
    suspend fun uso(refrescar: Boolean): Uso? = withContext(Dispatchers.IO) {
        val r = get(if (refrescar) "/uso?refrescar=1" else "/uso") ?: return@withContext _estado.value.uso
        leerUso(r).also { u -> _estado.update { it.copy(uso = u) } }
    }
}

/** "claude-sonnet-5-5" → "Sonnet 5.5"; "sonnet" → "Sonnet". */
fun nombreModelo(id: String): String {
    val m = Regex("^claude-([a-z]+)-(\\d+)(?:-(\\d+))?").find(id)
    if (m == null) return id.replaceFirstChar { it.uppercase() }
    val (familia, mayor, menor) = m.destructured
    return familia.replaceFirstChar { it.uppercase() } + " " + mayor + (if (menor.isNotEmpty()) ".$menor" else "")
}
