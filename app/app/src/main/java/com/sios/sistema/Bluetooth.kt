package com.sios.sistema

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Bluetooth del móvil para la terminal (`bt` en Termux, a través de [Puente]): estado, encender,
 * buscar, emparejar, conectar y BLE (servicios, leer y escribir características).
 * Claude es app privilegiada en SIOS (BLUETOOTH_PRIVILEGED y API ocultas permitidas), así que
 * puede conectar perfiles y olvidar dispositivos; emparejar sigue pidiendo confirmación al usuario.
 * Todo es síncrono: se llama desde un hilo del puente, nunca desde la interfaz.
 */
@SuppressLint("MissingPermission")
object Bluetooth {
    private fun adaptador(ctx: Context): BluetoothAdapter =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: error("Este móvil no tiene Bluetooth")

    private fun encendido(ctx: Context): BluetoothAdapter =
        adaptador(ctx).also { check(it.isEnabled) { "El Bluetooth está apagado (bt encender)" } }

    private fun tipo(t: Int) = when (t) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "clásico"
        BluetoothDevice.DEVICE_TYPE_LE -> "ble"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
        else -> "desconocido"
    }

    private fun oculto(d: BluetoothDevice, metodo: String): Any? = d.javaClass.getMethod(metodo).invoke(d)

    private fun conectado(d: BluetoothDevice) = runCatching { oculto(d, "isConnected") as Boolean }.getOrDefault(false)

    private fun json(d: BluetoothDevice, rssi: Int? = null, nombre: String? = null) = JSONObject()
        .put("nombre", d.name ?: nombre ?: JSONObject.NULL)
        .put("mac", d.address)
        .put("tipo", tipo(d.type))
        .put("emparejado", d.bondState == BluetoothDevice.BOND_BONDED)
        .put("conectado", conectado(d))
        .apply { if (rssi != null) put("rssi", rssi) }

    fun estado(ctx: Context): JSONObject {
        val a = adaptador(ctx)
        return JSONObject()
            .put("encendido", a.isEnabled)
            .put("nombre", a.name ?: JSONObject.NULL)
            .put("emparejados", JSONArray(if (a.isEnabled) a.bondedDevices.map { json(it) } else emptyList()))
    }

    /**
     * Batería de los dispositivos Bluetooth conectados (cascos, relojes, mandos…), la que informan por
     * HFP o BLE. -1 si no la dan. getBatteryLevel() es API oculta: la app es del sistema.
     */
    fun baterias(ctx: Context): JSONObject {
        val a = adaptador(ctx)
        val lista = if (!a.isEnabled) emptyList() else a.bondedDevices.filter { conectado(it) }.map { d ->
            val nivel = runCatching { oculto(d, "getBatteryLevel") as Int }.getOrDefault(-1)
            json(d).put("bateria", nivel)
        }
        return JSONObject().put("encendido", a.isEnabled).put("dispositivos", JSONArray(lista))
    }

    /** Enciende o apaga y espera a que termine (unos segundos). */
    @Suppress("DEPRECATION")
    fun encender(ctx: Context, si: Boolean): JSONObject {
        val a = adaptador(ctx)
        if (a.isEnabled != si) {
            val aceptado = if (si) a.enable() else a.disable()
            if (!aceptado) error("Android no dejó ${if (si) "encender" else "apagar"} el Bluetooth")
            repeat(40) { if (a.isEnabled == si && a.state == if (si) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF) return estado(ctx); Thread.sleep(200) }
        }
        return estado(ctx)
    }

    /** Busca dispositivos clásicos y BLE durante unos segundos; ordenados por señal. */
    fun buscar(ctx: Context, segundos: Int): JSONObject {
        val a = encendido(ctx)
        val vistos = LinkedHashMap<String, JSONObject>()
        val receptor = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                synchronized(vistos) { vistos[d.address] = json(d, rssi.takeIf { it != Short.MIN_VALUE.toInt() }) }
            }
        }
        val ble = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                synchronized(vistos) {
                    val previo = vistos[r.device.address]
                    if (previo == null || previo.isNull("nombre")) vistos[r.device.address] = json(r.device, r.rssi, r.scanRecord?.deviceName)
                }
            }
        }
        ctx.registerReceiver(receptor, IntentFilter(BluetoothDevice.ACTION_FOUND), Context.RECEIVER_EXPORTED)
        try {
            a.startDiscovery()
            a.bluetoothLeScanner?.startScan(ble)
            Thread.sleep(segundos.coerceIn(2, 30) * 1000L)
        } finally {
            a.cancelDiscovery()
            runCatching { a.bluetoothLeScanner?.stopScan(ble) }
            ctx.unregisterReceiver(receptor)
        }
        val lista = synchronized(vistos) { vistos.values.sortedByDescending { it.optInt("rssi", -999) } }
        return JSONObject().put("encontrados", JSONArray(lista))
    }

    private fun dispositivo(ctx: Context, mac: String): BluetoothDevice {
        require(BluetoothAdapter.checkBluetoothAddress(mac.uppercase())) { "MAC no válida: $mac" }
        return encendido(ctx).getRemoteDevice(mac.uppercase())
    }

    /** Pide emparejar: Android muestra su diálogo (con el código) y el usuario lo confirma. */
    fun emparejar(ctx: Context, mac: String): JSONObject {
        val d = dispositivo(ctx, mac)
        if (d.bondState != BluetoothDevice.BOND_BONDED) {
            if (!d.createBond()) error("No se pudo empezar a emparejar")
            // Hasta 60 s: lo que tarde el usuario en confirmar el código.
            repeat(120) { if (d.bondState != BluetoothDevice.BOND_BONDING && it > 2) return json(d); Thread.sleep(500) }
        }
        return json(d)
    }

    fun olvidar(ctx: Context, mac: String): JSONObject {
        val d = dispositivo(ctx, mac)
        if (d.bondState == BluetoothDevice.BOND_BONDED) oculto(d, "removeBond")
        repeat(20) { if (d.bondState == BluetoothDevice.BOND_NONE) return json(d); Thread.sleep(250) }
        return json(d)
    }

    /** Conecta o desconecta todos los perfiles que admita (audio, manos libres, entrada…). */
    fun conectar(ctx: Context, mac: String, si: Boolean): JSONObject {
        val d = dispositivo(ctx, mac)
        check(d.bondState == BluetoothDevice.BOND_BONDED) { "Primero hay que emparejarlo (bt emparejar $mac)" }
        oculto(d, if (si) "connect" else "disconnect")
        repeat(40) { if (conectado(d) == si) return json(d); Thread.sleep(250) }
        return json(d)
    }

    // ---------- BLE (GATT) ----------

    /** Una conexión GATT de un solo uso: conecta, descubre servicios, hace la operación y cierra. */
    private class Sesion(ctx: Context, d: BluetoothDevice) : BluetoothGattCallback() {
        private val conectada = CountDownLatch(1)
        private var operacion = CountDownLatch(1)
        var estado = -1
        var valor: ByteArray? = null
        val gatt: BluetoothGatt = d.connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE)

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, nuevo: Int) {
            if (nuevo == BluetoothProfile.STATE_CONNECTED) g.discoverServices() else conectada.countDown()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            estado = status
            conectada.countDown()
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, status: Int) {
            valor = v; estado = status; operacion.countDown()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            estado = status; operacion.countDown()
        }

        fun esperarServicios() {
            if (!conectada.await(20, TimeUnit.SECONDS) || gatt.services.isEmpty()) error("No se pudo conectar por BLE")
        }

        fun esperar(): Int {
            if (!operacion.await(10, TimeUnit.SECONDS)) error("El dispositivo no respondió")
            operacion = CountDownLatch(1)
            return estado
        }

        fun caracteristica(servicio: String, car: String): BluetoothGattCharacteristic =
            gatt.getService(uuid(servicio))?.getCharacteristic(uuid(car)) ?: error("No existe $servicio / $car")

        fun cerrar() = runCatching { gatt.disconnect(); gatt.close() }
    }

    /** Admite UUID completos y cortos de 16 bits («180f» → 0000180f-0000-1000-8000-00805f9b34fb). */
    private fun uuid(s: String): UUID =
        if (s.length <= 4) UUID.fromString("0000${s.padStart(4, '0')}-0000-1000-8000-00805f9b34fb") else UUID.fromString(s)

    private fun <T> conSesion(ctx: Context, mac: String, bloque: (Sesion) -> T): T {
        val s = Sesion(ctx, dispositivo(ctx, mac))
        try {
            s.esperarServicios()
            return bloque(s)
        } finally {
            s.cerrar()
        }
    }

    private fun propiedades(p: Int) = JSONArray(
        listOfNotNull(
            "leer".takeIf { p and BluetoothGattCharacteristic.PROPERTY_READ != 0 },
            "escribir".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 },
            "escribir-sin-respuesta".takeIf { p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 },
            "notificar".takeIf { p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 },
            "indicar".takeIf { p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 },
        ),
    )

    fun servicios(ctx: Context, mac: String): JSONObject = conSesion(ctx, mac) { s ->
        JSONObject().put(
            "servicios",
            JSONArray(
                s.gatt.services.map { sv ->
                    JSONObject().put("uuid", sv.uuid.toString()).put(
                        "caracteristicas",
                        JSONArray(sv.characteristics.map { c -> JSONObject().put("uuid", c.uuid.toString()).put("propiedades", propiedades(c.properties)) }),
                    )
                },
            ),
        )
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun leer(ctx: Context, mac: String, servicio: String, car: String): JSONObject = conSesion(ctx, mac) { s ->
        val c = s.caracteristica(servicio, car)
        check(s.gatt.readCharacteristic(c)) { "No se pudo leer" }
        check(s.esperar() == BluetoothGatt.GATT_SUCCESS) { "Lectura rechazada (${s.estado})" }
        val v = s.valor ?: ByteArray(0)
        JSONObject().put("hex", hex(v)).put("texto", String(v, Charsets.UTF_8)).put("bytes", JSONArray(v.map { it.toInt() and 0xff }))
    }

    /** `valor` en hexadecimal («0a1b») o como texto con el prefijo «texto:». */
    fun escribir(ctx: Context, mac: String, servicio: String, car: String, valor: String): JSONObject = conSesion(ctx, mac) { s ->
        val c = s.caracteristica(servicio, car)
        val datos = if (valor.startsWith("texto:")) valor.removePrefix("texto:").toByteArray() else
            valor.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val tipo = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        check(s.gatt.writeCharacteristic(c, datos, tipo) == BluetoothStatusCodes.SUCCESS) { "No se pudo escribir" }
        if (tipo == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) check(s.esperar() == BluetoothGatt.GATT_SUCCESS) { "Escritura rechazada (${s.estado})" }
        JSONObject().put("escrito", hex(datos))
    }
}
