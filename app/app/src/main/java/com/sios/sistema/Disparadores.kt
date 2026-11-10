package com.sios.sistema

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.BatteryManager
import com.sios.datos.Servicio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Los ojos de las rutinas: la app ve lo que pasa en el móvil (Wi-Fi, Bluetooth, carga, batería,
 * lugares y notificaciones) y se lo cuenta al servicio de Claude, que ejecuta las rutinas que
 * tocan. Solo se vigila lo que alguna rutina necesita; sin rutinas no hay nada escuchando.
 */
object Disparadores {
    private const val LUGAR = "com.sios.rutina.LUGAR"
    private var iniciado = false
    private var red: String? = null
    private var nivelAnterior = -1
    private val lugares = mutableSetOf<String>() // ids de rutinas con su alerta de proximidad puesta

    fun iniciar(ctx: Context) {
        if (iniciado) return
        iniciado = true
        val app = ctx.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            Servicio.rutinas.collect { rutinas -> ponerLugares(app, rutinas) }
        }
        vigilarWifi(app)
        val filtro = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        app.registerReceiver(receptor, filtro, Context.RECEIVER_EXPORTED)
        app.registerReceiver(receptor, IntentFilter(LUGAR), Context.RECEIVER_NOT_EXPORTED)
    }

    private fun hay(tipo: String) = Servicio.rutinas.value.any { it.optJSONObject("disparador")?.optString("tipo") == tipo }

    private fun enviar(tipo: String, datos: JSONObject.() -> Unit) {
        if (!hay(tipo)) return
        Servicio.disparo(JSONObject().put("tipo", tipo).apply(datos))
    }

    @SuppressLint("MissingPermission")
    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    val nombre = runCatching { d?.alias ?: d?.name }.getOrNull() ?: d?.address ?: return
                    enviar("bluetooth") { put("dispositivo", nombre).put("conectado", i.action == BluetoothDevice.ACTION_ACL_CONNECTED) }
                    Coche.bluetooth(c, nombre, i.action == BluetoothDevice.ACTION_ACL_CONNECTED)
                }
                Intent.ACTION_POWER_CONNECTED -> enviar("carga") { put("conectado", true) }
                Intent.ACTION_POWER_DISCONNECTED -> enviar("carga") { put("conectado", false) }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val nivel = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    if (nivel >= 0 && nivel != nivelAnterior) {
                        nivelAnterior = nivel
                        enviar("bateria") { put("nivel", nivel) }
                    }
                }
                LUGAR -> {
                    val id = i.getStringExtra("id") ?: return
                    val entrando = i.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
                    enviar("lugar") { put("id", id).put("al", if (entrando) "entrar" else "salir") }
                }
            }
        }
    }

    /** Wi-Fi: el nombre de la red al conectar y al desconectar. */
    private fun vigilarWifi(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val peticion = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        cm.registerNetworkCallback(peticion, object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) {
                val ssid = (caps.transportInfo as? WifiInfo)?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != WifiManagerSinNombre } ?: return
                if (ssid == red) return
                red = ssid
                enviar("wifi") { put("red", ssid).put("conectado", true) }
            }

            override fun onLost(n: Network) {
                val antes = red ?: return
                red = null
                enviar("wifi") { put("red", antes).put("conectado", false) }
            }
        })
    }

    private const val WifiManagerSinNombre = "<unknown ssid>"

    /** Lugares: una alerta de proximidad por rutina (entrar o salir del círculo). */
    @SuppressLint("MissingPermission")
    private fun ponerLugares(ctx: Context, rutinas: List<JSONObject>) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val conLugar = rutinas.filter { it.optJSONObject("disparador")?.optString("tipo") == "lugar" }
        if (conLugar.isNotEmpty() && ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Avisos.publicar(
                ctx, "rutinas-ubicacion", ctx.getString(com.sios.R.string.rutinas_ubicacion_titulo),
                ctx.getString(com.sios.R.string.rutinas_ubicacion_texto),
                acciones = listOf(ctx.getString(com.sios.R.string.rutinas_ubicacion_permitir) to PendingIntent.getActivity(
                    ctx, 7, Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
                )),
            )
            return
        }
        // Cada sitio, su alerta; una rutina puede tener varios (puntos: todos los súper de alrededor).
        val alertas = conLugar.flatMap { r ->
            val id = r.getString("id")
            val d = r.getJSONObject("disparador")
            val puntos = d.optJSONArray("puntos")
            val radio = d.optDouble("radio", 150.0).toFloat()
            if (puntos != null && puntos.length() > 0) (0 until puntos.length()).map { i -> Triple("$id#$i", id, puntos.getJSONObject(i).let { Triple(it.getDouble("lat"), it.getDouble("lon"), radio) }) }
            else listOf(Triple(id, id, Triple(d.getDouble("lat"), d.getDouble("lon"), radio)))
        }
        val claves = alertas.map { it.first }.toSet()
        for (clave in lugares - claves) lm.removeProximityAlert(intentLugar(ctx, clave.substringBefore('#'), clave))
        for ((clave, id, p) in alertas) {
            // Se vuelve a poner siempre: si la rutina ha cambiado de sitio, la alerta se actualiza.
            runCatching { lm.addProximityAlert(p.first, p.second, p.third, -1, intentLugar(ctx, id, clave)) }
        }
        lugares.clear()
        lugares += claves
    }

    private fun intentLugar(ctx: Context, id: String, clave: String = id) = PendingIntent.getBroadcast(
        ctx, clave.hashCode(), Intent(LUGAR).setPackage(ctx.packageName).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** Desde la bandeja: una notificación nueva (solo si alguna rutina mira notificaciones). */
    fun notificacion(a: Bandeja.Aviso) = enviar("notificacion") {
        put("app", a.app).put("paquete", a.paquete).put("titulo", a.titulo).put("texto", a.texto)
    }
}
