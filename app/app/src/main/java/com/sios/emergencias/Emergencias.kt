package com.sios.emergencias

import android.Manifest
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import com.sios.R
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Emergencias de SIOS: SOS (cinco pulsaciones del botón de encendido, el botón de emergencia del
 * bloqueo, el del menú de apagado y el recuadro de ajustes rápidos), la ficha médica que se ve sin
 * desbloquear, la detección de caídas y «compartir mi viaje».
 *
 * Todo se queda en el móvil y nada pasa por la IA. Se guarda en el almacenamiento accesible antes de
 * desbloquear: después de un reinicio, con el móvil aún bloqueado, el SOS y la ficha tienen que
 * funcionar igual.
 *
 * Modo de prueba: hace todo (cuenta atrás, sonido, ubicación, mensajes) menos llamar y mandar SMS; lo
 * que se habría enviado se apunta en [registro]. Las pruebas automáticas y «Probar el SOS» lo usan
 * siempre.
 */
object Emergencias {
    private fun prefs(ctx: Context) =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("emergencias", Context.MODE_PRIVATE)

    /** Ajuste de Android que lee el sistema (GestureLauncherService) para el gesto de las 5 pulsaciones. */
    private const val GESTO = "emergency_gesture_enabled"

    /** Acción con la que SystemUI abre la app de SOS al detectar las 5 pulsaciones. */
    const val ACCION_GESTO = "com.android.systemui.action.LAUNCH_EMERGENCY"

    // ——— Contactos y ficha ———

    data class Contacto(val nombre: String, val telefono: String)

    fun contactos(ctx: Context): List<Contacto> = runCatching {
        val a = JSONArray(prefs(ctx).getString("contactos", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Contacto(it.optString("n"), it.optString("t")) }.filter { it.telefono.isNotBlank() }
    }.getOrDefault(emptyList())

    fun ponerContactos(ctx: Context, lista: List<Contacto>) {
        val a = JSONArray()
        lista.take(MAX_CONTACTOS).forEach { a.put(JSONObject().put("n", it.nombre).put("t", it.telefono)) }
        prefs(ctx).edit().putString("contactos", a.toString()).apply()
        cambios.value++
    }

    const val MAX_CONTACTOS = 5

    /** La ficha médica: lo que verían los servicios de emergencia o quien te ayude. */
    data class Ficha(
        val nombre: String = "",
        val nacimiento: String = "",
        val sangre: String = "",
        val alergias: String = "",
        val medicacion: String = "",
        val afecciones: String = "",
        val notas: String = "",
        /** "si", "no" o "" (sin indicar). */
        val donante: String = "",
        /** Se ve en la pantalla de bloqueo sin desbloquear. */
        val enBloqueo: Boolean = true,
    ) {
        val vacia get() = listOf(nombre, nacimiento, sangre, alergias, medicacion, afecciones, notas, donante).all { it.isBlank() }

        fun json(): JSONObject = JSONObject().put("nombre", nombre).put("nacimiento", nacimiento).put("sangre", sangre)
            .put("alergias", alergias).put("medicacion", medicacion).put("afecciones", afecciones).put("notas", notas)
            .put("donante", donante).put("bloqueo", enBloqueo)

        companion object {
            fun de(j: JSONObject) = Ficha(
                j.optString("nombre"), j.optString("nacimiento"), j.optString("sangre"), j.optString("alergias"),
                j.optString("medicacion"), j.optString("afecciones"), j.optString("notas"), j.optString("donante"),
                j.optBoolean("bloqueo", true),
            )
        }
    }

    val GRUPOS_SANGRE = listOf("A+", "A−", "B+", "B−", "AB+", "AB−", "0+", "0−")

    fun ficha(ctx: Context): Ficha = runCatching { Ficha.de(JSONObject(prefs(ctx).getString("ficha", "{}")!!)) }.getOrDefault(Ficha())

    fun ponerFicha(ctx: Context, f: Ficha) {
        prefs(ctx).edit().putString("ficha", f.json().toString()).apply()
        cambios.value++
    }

    /** Sube cada vez que cambia algo guardado: las pantallas abiertas se vuelven a leer. */
    val cambios = MutableStateFlow(0)

    // ——— Ajustes ———

    /** ¿El sistema tiene el gesto de las 5 pulsaciones? (config_emergencyGestureEnabled; en SIOS, sí). */
    fun gestoDisponible(): Boolean = configBool("config_emergencyGestureEnabled", true)

    private fun configBool(nombre: String, defecto: Boolean): Boolean = runCatching {
        val r = android.content.res.Resources.getSystem()
        val id = r.getIdentifier(nombre, "bool", "android")
        if (id == 0) defecto else r.getBoolean(id)
    }.getOrDefault(defecto)

    fun gestoActivo(ctx: Context): Boolean =
        gestoDisponible() && Settings.Secure.getInt(ctx.contentResolver, GESTO, if (configBool("config_defaultEmergencyGestureEnabled", true)) 1 else 0) != 0

    /** Solo la app del sistema (SIOS) puede cambiarlo: es un ajuste seguro de Android. */
    fun puedeCambiarGesto(ctx: Context) =
        gestoDisponible() && ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun ponerGesto(ctx: Context, si: Boolean) {
        runCatching { Settings.Secure.putInt(ctx.contentResolver, GESTO, if (si) 1 else 0) }
        cambios.value++
    }

    fun cuentaAtras(ctx: Context) = prefs(ctx).getInt("cuenta", 5)
    fun ponerCuentaAtras(ctx: Context, s: Int) = editar(ctx) { putInt("cuenta", s) }

    fun sonido(ctx: Context) = prefs(ctx).getBoolean("sonido", true)
    fun ponerSonido(ctx: Context, si: Boolean) = editar(ctx) { putBoolean("sonido", si) }

    /** Minutos que se sigue mandando la ubicación después del SOS. */
    fun minutosCompartir(ctx: Context) = prefs(ctx).getInt("compartir", 60)
    fun ponerMinutosCompartir(ctx: Context, m: Int) = editar(ctx) { putInt("compartir", m) }

    /** El modo de prueba se apaga solo al día siguiente: que nadie se quede sin SOS por olvidarlo puesto. */
    fun modoPrueba(ctx: Context): Boolean {
        val p = prefs(ctx)
        return p.getBoolean("prueba", false) && System.currentTimeMillis() - p.getLong("prueba_desde", 0) < 24 * 60 * 60_000L
    }

    fun ponerModoPrueba(ctx: Context, si: Boolean) = editar(ctx) { putBoolean("prueba", si); putLong("prueba_desde", System.currentTimeMillis()) }

    fun caidas(ctx: Context) = prefs(ctx).getBoolean("caidas", false)
    fun ponerCaidas(ctx: Context, si: Boolean) {
        editar(ctx) { putBoolean("caidas", si) }
        if (si) Caidas.arrancar(ctx) else Caidas.parar(ctx)
    }

    /** Segundos para responder a «¿Estás bien?» antes de pedir ayuda. */
    fun esperaCaida(ctx: Context) = prefs(ctx).getInt("espera_caida", 45)
    fun ponerEsperaCaida(ctx: Context, s: Int) = editar(ctx) { putInt("espera_caida", s) }

    private fun editar(ctx: Context, cambio: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs(ctx).edit().apply(cambio).apply()
        cambios.value++
    }

    // ——— Número de emergencias ———

    /** El número que ha puesto el usuario («» = automático según el país). */
    fun numeroPropio(ctx: Context) = prefs(ctx).getString("numero", "").orEmpty()
    fun ponerNumeroPropio(ctx: Context, n: String) = editar(ctx) { putString("numero", n.filter { it.isDigit() }.take(6)) }

    fun numero(ctx: Context): String = numeroPropio(ctx).ifBlank { numeroDelPais(pais(ctx)) }

    /** País de la red (si hay cobertura), si no el de la SIM y, sin SIM, el del idioma del móvil. */
    fun pais(ctx: Context): String {
        val tm = runCatching { ctx.getSystemService(TelephonyManager::class.java) }.getOrNull()
        return listOfNotNull(
            runCatching { tm?.networkCountryIso }.getOrNull(),
            runCatching { tm?.simCountryIso }.getOrNull(),
            Locale.getDefault().country,
        ).firstOrNull { it.isNotBlank() }.orEmpty().lowercase(Locale.ROOT)
    }

    /**
     * El número general de emergencias de cada país. El 112 vale en toda Europa y en casi cualquier
     * red móvil del mundo; donde la gente marca otro (el 911 en América, el 999 en el Reino Unido…),
     * ese. El usuario lo puede cambiar.
     */
    fun numeroDelPais(pais: String): String = when (pais) {
        "us", "ca", "mx", "pr", "gu", "vi", "as", "mp", "ar", "uy", "py", "ec", "cr", "pa", "sv", "hn", "do", "ph" -> "911"
        "gb", "hk", "mo", "my" -> "999"
        "sg" -> "995"
        "au" -> "000"
        "nz" -> "111"
        "jp", "kr", "tw" -> "119"
        "cn" -> "120"
        "br" -> "192"
        "co" -> "123"
        "cl" -> "131"
        "pe" -> "105"
        "ve" -> "911"
        "il" -> "101"
        else -> "112"
    }

    fun nombrePais(pais: String): String =
        if (pais.isBlank()) "" else Locale.Builder().setRegion(pais.uppercase(Locale.ROOT)).build().getDisplayCountry(Locale.getDefault())

    /** ¿Es un número de emergencias? Lo dice el sistema (la base de datos de la red y la SIM); sin él, la lista conocida. */
    fun esEmergencia(ctx: Context, numero: String): Boolean {
        val n = numero.filter { it.isDigit() || it == '+' }
        if (n.isBlank()) return false
        val sistema = runCatching { ctx.getSystemService(TelephonyManager::class.java).isEmergencyNumber(n) }.getOrNull()
        return sistema ?: (n in setOf("112", "911", "999", "000", "111", "119", "110", "120", "190", "192", "061", "062", "091", "080", "085", numero(ctx)))
    }

    // ——— Llamar ———

    /** ¿Está el móvil bloqueado? (en la pantalla de emergencias solo se puede llamar a emergencias y a tus contactos). */
    fun bloqueado(ctx: Context) = runCatching { ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)

    /**
     * Llama a emergencias (o a un contacto) ya, sin pasar por el marcador. SIOS es el teléfono del
     * sistema: Android le deja llamar a emergencias directamente. Si algo falla, se abre el teclado de
     * emergencias del sistema con el número puesto, para que nunca se quede sin poder llamar.
     * En modo de prueba solo se apunta.
     */
    fun llamar(ctx: Context, numero: String, prueba: Boolean, altavoz: Boolean = false) {
        if (numero.isBlank()) return
        if (prueba) {
            apuntar(ctx, "llamada", numero, ctx.getString(R.string.em_reg_llamada, numero))
            return
        }
        val uri = Uri.fromParts("tel", numero, null)
        val extras = Bundle().apply { if (altavoz) putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, true) }
        val puede = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        val hecho = puede && runCatching { ctx.getSystemService(TelecomManager::class.java).placeCall(uri, extras) }.isSuccess
        if (hecho) return
        android.util.Log.w("SIOS", "No se pudo llamar directamente a $numero; se abre el teclado de emergencias")
        val intentos = listOf(
            Intent(ACCION_TECLADO_EMERGENCIA, uri).setComponent(ComponentName("com.android.phone", "com.android.phone.EmergencyDialer")),
            Intent(Intent.ACTION_DIAL, uri),
        )
        for (i in intentos) if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }

    /** La acción del teclado de emergencias (Intent.ACTION_DIAL_EMERGENCY, de sistema). */
    const val ACCION_TECLADO_EMERGENCIA = "android.intent.action.DIAL_EMERGENCY"

    /**
     * Después de llamar a emergencias, durante una hora: si te devuelven la llamada, suena siempre
     * (sin silenciar desconocidos, sin que conteste la IA y sin consultarle quién es).
     */
    fun marcarLlamadaEmergencia(ctx: Context) = prefs(ctx).edit().putLong("ultima_llamada", System.currentTimeMillis()).apply()

    fun enVentanaDevolucion(ctx: Context): Boolean = runCatching {
        System.currentTimeMillis() - prefs(ctx).getLong("ultima_llamada", 0) < 60 * 60_000L || SosServicio.estado.value?.modo == SosServicio.Modo.SOS
    }.getOrDefault(false)

    // ——— SOS ———

    enum class Motivo { BOTON, CAIDA }

    /**
     * Pide ayuda: llama a emergencias y arranca el servicio que manda la ubicación a los contactos (ahora
     * y, después, la actualizada cada pocos minutos).
     */
    fun pedirAyuda(ctx: Context, motivo: Motivo, prueba: Boolean) {
        val app = ctx.applicationContext
        val numero = numero(app)
        if (!prueba) marcarLlamadaEmergencia(app)
        SosServicio.empezarSos(app, motivo, prueba)
        // Con el altavoz: el móvil puede estar en el suelo o en un bolsillo, y así te oyen y los oyes.
        llamar(app, numero, prueba, altavoz = motivo == Motivo.CAIDA)
    }

    /** Abre la cuenta atrás del SOS (desde la pantalla de emergencias, el recuadro o la prueba). */
    fun abrirSos(ctx: Context, prueba: Boolean, motivo: Motivo = Motivo.BOTON) {
        ctx.startActivity(
            Intent(ctx, SosActivity::class.java)
                .putExtra(SosActivity.EXTRA_PRUEBA, prueba)
                .putExtra(SosActivity.EXTRA_MOTIVO, motivo.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
    }

    // ——— Registro de lo simulado ———

    data class Apunte(val hora: Long, val tipo: String, val destino: String, val texto: String)

    /** Lo que habría salido del móvil en modo de prueba (lo último primero). */
    fun registro(ctx: Context): List<Apunte> = runCatching {
        val a = JSONArray(prefs(ctx).getString("registro", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Apunte(it.optLong("h"), it.optString("tipo"), it.optString("a"), it.optString("texto")) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun apuntar(ctx: Context, tipo: String, destino: String, texto: String) {
        val nuevo = JSONArray().put(JSONObject().put("h", System.currentTimeMillis()).put("tipo", tipo).put("a", destino).put("texto", texto))
        registro(ctx).take(29).forEach { nuevo.put(JSONObject().put("h", it.hora).put("tipo", it.tipo).put("a", it.destino).put("texto", it.texto)) }
        prefs(ctx).edit().putString("registro", nuevo.toString()).apply()
        android.util.Log.i("SIOS", "Emergencias (prueba): $tipo a $destino")
        cambios.value++
    }

    fun borrarRegistro(ctx: Context) = editar(ctx) { remove("registro") }

    // ——— Permisos ———

    fun puedeSms(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    fun puedeUbicacion(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
