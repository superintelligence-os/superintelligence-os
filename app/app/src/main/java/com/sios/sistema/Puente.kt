package com.sios.sistema

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject

/**
 * Puente entre el agente (Termux) y lo que solo puede hacer una app: nombres visibles de las apps,
 * Bluetooth y las manos de Claude (leer y tocar la pantalla). Se usa con `content call --uri content://com.sios.puente --method <m>` desde
 * `adb shell` (el agente lo hace con `android …`). Solo atiende a la shell de depuración, que en
 * SIOS solo escucha dentro del móvil, y a la propia app. Los valores van codificados como en
 * una URL (`--extra mac:s:AA%3ABB…`). Responde un JSON en la clave «json».
 */
class Puente : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val llamante = Binder.getCallingUid()
        if (llamante != SHELL && llamante != Process.myUid()) throw SecurityException("El puente de Claude solo atiende a la terminal del agente")
        val ctx = context ?: error("sin contexto")
        val e = extras ?: Bundle()
        // Los valores llegan codificados como en una URL: `content call` no admite «:» (las MAC los llevan).
        fun texto(clave: String) = (e.getString(clave) ?: arg)?.let(Uri::decode) ?: error("Falta «$clave»")
        val mac by lazy { texto("mac") }
        val identidad = Binder.clearCallingIdentity() // a partir de aquí, con los permisos de la app
        val respuesta = try {
            when (method) {
                "apps" -> JSONObject().put("apps", apps())
                "ubicacion" -> ubicacion(ctx)
                "conversacion" -> (Bandeja.instancia ?: error("Claude no tiene acceso a las notificaciones"))
                    .conversacion(texto("nombre"), runCatching { texto("horas").toInt() }.getOrDefault(24).coerceIn(1, 168))
                "continuidad_activa" -> {
                    val si = e.getString("si") == "1"
                    android.os.Handler(android.os.Looper.getMainLooper()).post { Continuidad.ponerActiva(ctx, si) }
                    JSONObject().put("activa", si)
                }
                "traductor" -> {
                    ctx.startActivity(android.content.Intent(ctx, TraductorActivity::class.java).putExtra("idioma", texto("idioma")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    JSONObject().put("abierto", true)
                }
                "sonar" -> Sonar.empezar(ctx, (e.getString("de") ?: "").let(Uri::decode))
                // Encontrar mi móvil (desde SIOS Desktop): modo perdido, borrar a distancia y las SIM.
                "perdido" -> Antirrobo.perdido(ctx, e.getString("si") == "1", (e.getString("mensaje") ?: "").let(Uri::decode), (e.getString("telefono") ?: "").let(Uri::decode))
                "borrar_todo" -> Antirrobo.borrar(ctx)
                "sim" -> Antirrobo.sim(ctx)
                "portapapeles_poner" -> Continuidad.ponerPortapapeles(ctx, texto("texto"))
                "en_directo" -> EnDirecto.publicar(ctx, texto("id"), texto("titulo"), (e.getString("texto") ?: "").let(Uri::decode), (e.getString("chip") ?: "").let(Uri::decode), e.getString("progreso")?.let(Uri::decode)?.toFloatOrNull(), (e.getString("acento") ?: "naranja"))
                "en_directo_fin" -> EnDirecto.quitar(ctx, texto("id"))
                "salud" -> Salud.json(ctx)
                "notificar" -> Avisos.publicar(ctx, texto("id"), texto("titulo"), texto("texto"), app = e.getString("app")?.let(Uri::decode), silencioso = e.getString("silencioso") == "1")
                "proxima_alarma" -> JSONObject().put("cuando", ctx.getSystemService(android.app.AlarmManager::class.java).nextAlarmClock?.triggerTime ?: JSONObject.NULL)
                "uso_apps" -> Bienestar.json(ctx, runCatching { texto("dias").toInt() }.getOrDefault(7).coerceIn(1, 28))
                "limite" -> Bienestar.limite(ctx, texto("paquete"), texto("app"), texto("minutos").toInt())
                "vistazo" -> Vistazo.publicar(ctx, JSONArray(texto("piezas")))
                "vistazo_fin" -> Vistazo.quitar(ctx)
                "coche" -> { if (e.getString("si") == "0") Coche.salir() else Coche.abrir(ctx); JSONObject().put("activo", Coche.activo.value) }
                "dia" -> Avisos.dia(ctx, texto("texto"), texto("detalle"))
                "bt_estado" -> Bluetooth.estado(ctx)
                "bt_baterias" -> Bluetooth.baterias(ctx)
                "bt_encender" -> Bluetooth.encender(ctx, true)
                "bt_apagar" -> Bluetooth.encender(ctx, false)
                "bt_buscar" -> Bluetooth.buscar(ctx, runCatching { texto("segundos").toInt() }.getOrDefault(8))
                "bt_emparejar" -> Bluetooth.emparejar(ctx, mac)
                "bt_olvidar" -> Bluetooth.olvidar(ctx, mac)
                "bt_conectar" -> Bluetooth.conectar(ctx, mac, true)
                "bt_desconectar" -> Bluetooth.conectar(ctx, mac, false)
                "ble_servicios" -> Bluetooth.servicios(ctx, mac)
                "ble_leer" -> Bluetooth.leer(ctx, mac, texto("servicio"), texto("caracteristica"))
                "ble_escribir" -> Bluetooth.escribir(ctx, mac, texto("servicio"), texto("caracteristica"), texto("valor"))
                "notificaciones" -> Bandeja.json()
                "responder" -> JSONObject().put("enviado", (Bandeja.instancia ?: error("Claude no tiene acceso a las notificaciones")).responder(texto("clave"), texto("texto")))
                "foco" -> {
                    val b = Bandeja.instancia ?: error("Claude no tiene acceso a las notificaciones")
                    val min = texto("minutos").toInt()
                    android.os.Handler(android.os.Looper.getMainLooper()).post { if (min > 0) b.empezarFoco(min) else b.terminarFoco() }
                    JSONObject().put("hecho", true)
                }
                "resumir" -> { (Bandeja.instancia ?: error("Claude no tiene acceso a las notificaciones")).resumir(ahora = true); JSONObject() }
                "memoria_buscar" -> JSONObject().put("activa", Memoria.activa(ctx)).put("elementos", Memoria.cuenta(ctx))
                    .put("resultados", Memoria.buscar(ctx, texto("consulta")))
                "memoria_ver" -> JSONObject().put("imagen", Memoria.imagen(ctx, texto("id").toLong()))
                "memoria_abrir" -> { Memoria.abrir(ctx, texto("id").toLong()); JSONObject().put("hecho", true) }
                "memoria_indexar" -> {
                    if (e.getString("rehacer") == "1") Memoria.borrarTodo(ctx)
                    JSONObject().put("nuevos", Memoria.indexar(ctx)).put("elementos", Memoria.cuenta(ctx))
                }
                "llamadas" -> Llamadas.json(ctx)
                "llamadas_bloquear" -> { Llamadas.bloquear(ctx, texto("numero"), e.getString("si") != "0"); Llamadas.json(ctx) }
                "llamadas_silenciar" -> { Llamadas.ponerSilenciar(ctx, texto("si") == "1"); Llamadas.json(ctx) }
                "llamadas_atender" -> { Llamadas.ponerAtender(ctx, texto("si") == "1"); Llamadas.json(ctx) }
                "manos" -> JSONObject().put("activo", Manos.instancia != null && Manos.permitido(context!!))
                "manos_leer" -> manos().leer()
                "manos_tocar" -> JSONObject().put("hecho", manos().tocar(texto("x").toFloat(), texto("y").toFloat(), e.getString("largo") == "1"))
                "manos_deslizar" -> JSONObject().put(
                    "hecho",
                    manos().deslizar(
                        android.graphics.PointF(texto("x1").toFloat(), texto("y1").toFloat()),
                        android.graphics.PointF(texto("x2").toFloat(), texto("y2").toFloat()),
                        runCatching { texto("ms").toLong() }.getOrDefault(320L),
                    ),
                )
                "manos_escribir" -> manos().escribir(texto("texto"), e.getString("enviar") == "1")
                "manos_ocultar" -> { Manos.instancia?.ocultar(runCatching { texto("ms").toLong() }.getOrDefault(700L)); JSONObject() }
                "manos_fin" -> { Manos.instancia?.fin(); JSONObject() }
                else -> JSONObject().put("error", "Método desconocido: $method")
            }
        } catch (ex: Exception) {
            JSONObject().put("error", (ex.cause ?: ex).message ?: ex.toString())
        } finally {
            Binder.restoreCallingIdentity(identidad)
        }
        return Bundle().apply { putString("json", respuesta.toString()) }
    }

    /** Si Android quitó el servicio (y el usuario no lo ha apagado), se recupera aquí y se espera a que conecte. */
    private fun manos(): Manos {
        val ctx = context!!
        // El servicio puede estar encendido solo para la memoria de pantalla: sin permiso, el agente no lo usa.
        if (Manos.permitido(ctx)) Manos.instancia?.let { return it }
        if (Manos.permitido(ctx)) {
            Manos.asegurar(ctx)
            repeat(30) { Manos.instancia?.let { m -> return m }; Thread.sleep(100) }
        }
        error("Claude no tiene permiso para usar tus apps (Ajustes de SIOS › Usar tus apps)")
    }

    /** Apps que se pueden abrir, con el nombre que ve el usuario («WhatsApp», no com.whatsapp). */
    /**
     * Dónde está el móvil (para el panel: gasolina cerca, tiempo…). La última posición si tiene menos
     * de 10 min; si no, una lectura nueva (12 s como mucho) del mejor proveedor disponible.
     */
    private fun ubicacion(ctx: android.content.Context): JSONObject {
        val lm = ctx.getSystemService(android.location.LocationManager::class.java)
        check(ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "Claude no tiene permiso de ubicación" }
        check(lm.isLocationEnabled) { "La ubicación del móvil está desactivada" }
        val proveedores = listOf(android.location.LocationManager.FUSED_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.GPS_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        @Suppress("MissingPermission")
        var mejor = proveedores.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (mejor == null || System.currentTimeMillis() - mejor.time > 10 * 60_000) {
            // A todos los proveedores a la vez: gana la primera posición nueva.
            val listo = java.util.concurrent.CountDownLatch(1)
            val señales = proveedores.map { android.os.CancellationSignal() }
            proveedores.forEachIndexed { i, p ->
                @Suppress("MissingPermission")
                runCatching {
                    lm.getCurrentLocation(p, señales[i], ctx.mainExecutor) { l ->
                        if (l != null && listo.count > 0) { mejor = l; listo.countDown() }
                    }
                }
            }
            listo.await(12, java.util.concurrent.TimeUnit.SECONDS)
            señales.forEach { it.cancel() }
        }
        val l = mejor ?: error("No sé dónde está el móvil ahora mismo")
        return JSONObject().put("lat", l.latitude).put("lon", l.longitude).put("precision", l.accuracy.toDouble()).put("hace_s", (System.currentTimeMillis() - l.time) / 1000)
    }

    private fun apps(): JSONArray {
        val pm = context!!.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return JSONArray(
            pm.queryIntentActivities(i, PackageManager.MATCH_ALL)
                .distinctBy { it.activityInfo.packageName }
                .map { JSONObject().put("nombre", it.loadLabel(pm).toString()).put("paquete", it.activityInfo.packageName) }
                .sortedBy { it.getString("nombre").lowercase() },
        )
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private companion object {
        const val SHELL = 2000 // uid de adb shell
    }
}
