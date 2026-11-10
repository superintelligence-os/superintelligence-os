package com.sios.cortafuegos

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Process
import android.service.quicksettings.TileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Cortafuegos y bloqueo de rastreadores de SIOS: lo que se ha elegido (qué apps no tienen internet en
 * wifi o en datos, qué dominios se permiten o bloquean a mano), el estado para las pantallas y
 * encender o apagar el servicio (ver [CortafuegosVpn], donde se explica cómo funciona por dentro).
 */
object Cortafuegos {
    enum class Red { WIFI, DATOS }

    /** Por qué una app es esencial: no se le quita la red sin preguntar (y la propia SIOS, nunca). */
    enum class Esencial { PROPIA, AGENTE, GOOGLE, TELEFONIA, SISTEMA }

    data class Estado(
        /** El túnel está levantado y filtrando. */
        val encendido: Boolean = false,
        val arrancando: Boolean = false,
        /** No se pudo encender (otra VPN fijada como siempre activa, sin permiso…). */
        val error: Boolean = false,
        /** Se apagó solo: otra VPN se conectó o se desconectó desde los ajustes del sistema. */
        val revocado: Boolean = false,
        /** «VPN siempre activa» está puesta para el cortafuegos. */
        val siempreActiva: Boolean = false,
        /** «Bloquear conexiones sin VPN»: deja sin red a las apps que el cortafuegos deja fuera. */
        val bloqueoSinVpn: Boolean = false,
        /** DNS privado fijo (con nombre): el sistema lo usa en vez del cortafuegos y no se filtra nada. */
        val dnsPrivado: String? = null,
        val red: Red? = null,
    )

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    internal fun ponerEstado(ctx: Context, f: (Estado) -> Estado) {
        val antes = _estado.value
        _estado.update(f)
        // El recuadro de los ajustes rápidos se repinta cuando cambia algo que enseña.
        if (antes.encendido != _estado.value.encendido || antes.arrancando != _estado.value.arrancando) {
            runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, CortafuegosTile::class.java)) }
        }
    }

    internal fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("cortafuegos", Context.MODE_PRIVATE)

    /** ¿Lo quiere encendido el usuario? (Puede estar arrancando o haberse caído.) */
    fun quiereActivo(ctx: Context) = prefs(ctx).getBoolean("activo", false)

    // ——— Reglas ———

    /** Lo elegido, ya preparado para consultarlo en cada petición (se rehace al cambiar algo). */
    internal class Reglas(
        val sinWifi: Set<String>,
        val sinDatos: Set<String>,
        val dominiosPermitidos: Set<String>,
        val dominiosBloqueados: Set<String>,
        /** Por uid (lo que se sabe de cada consulta): sin red en wifi / en datos. */
        val uidSinWifi: Set<Int>,
        val uidSinDatos: Set<Int>,
    )

    @Volatile private var reglas: Reglas? = null

    internal fun reglas(ctx: Context): Reglas = reglas ?: synchronized(this) {
        reglas ?: construirReglas(ctx).also { reglas = it }
    }

    private fun construirReglas(ctx: Context): Reglas {
        val p = prefs(ctx)
        val pm = ctx.packageManager
        val sinWifi = p.getStringSet("sin_wifi", emptySet())!!.toSet()
        val sinDatos = p.getStringSet("sin_datos", emptySet())!!.toSet()
        fun uids(s: Set<String>) = s.mapNotNull { runCatching { pm.getPackageUid(it, 0) }.getOrNull() }.toSet()
        return Reglas(
            sinWifi, sinDatos,
            p.getStringSet("permitidos", emptySet())!!.toSet(),
            p.getStringSet("bloqueados", emptySet())!!.toSet(),
            uids(sinWifi), uids(sinDatos),
        )
    }

    /** Se ha cambiado algo: se rehacen las reglas y, si hace falta, el túnel (las apps que quedan fuera). */
    private fun cambiado(ctx: Context) {
        synchronized(this) { reglas = null }
        CortafuegosVpn.instancia?.reglasCambiadas()
        Registro.tic(ctx)
    }

    fun sinRed(ctx: Context, paquete: String, red: Red): Boolean =
        paquete in (if (red == Red.WIFI) reglas(ctx).sinWifi else reglas(ctx).sinDatos)

    /** Quita (o devuelve) internet a una app en una red. La propia SIOS no se puede bloquear. */
    fun ponerSinRed(ctx: Context, paquete: String, red: Red, sin: Boolean) {
        if (paquete == ctx.packageName) return
        val clave = if (red == Red.WIFI) "sin_wifi" else "sin_datos"
        val p = prefs(ctx)
        val s = p.getStringSet(clave, emptySet())!!.toMutableSet()
        if (sin) s.add(paquete) else s.remove(paquete)
        p.edit().putStringSet(clave, s).apply()
        cambiado(ctx)
    }

    /** Permitir siempre un dominio que bloquea la lista (si una app deja de funcionar por él). */
    fun permitirDominio(ctx: Context, dominio: String, si: Boolean) = cambiarConjunto(ctx, "permitidos", dominio, si)

    /** Bloquear siempre un dominio aunque no esté en la lista. */
    fun bloquearDominio(ctx: Context, dominio: String, si: Boolean) = cambiarConjunto(ctx, "bloqueados", dominio, si)

    private fun cambiarConjunto(ctx: Context, clave: String, dominio: String, si: Boolean) {
        val p = prefs(ctx)
        val s = p.getStringSet(clave, emptySet())!!.toMutableSet()
        if (si) s.add(dominio) else s.remove(dominio)
        // Lo contrario deja de valer: un dominio no puede estar permitido y bloqueado a la vez.
        val otra = if (clave == "permitidos") "bloqueados" else "permitidos"
        val o = p.getStringSet(otra, emptySet())!!.toMutableSet()
        if (si) o.remove(dominio)
        p.edit().putStringSet(clave, s).putStringSet(otra, o).apply()
        cambiado(ctx)
    }

    // ——— Apps esenciales ———

    private val AGENTE = setOf("com.termux", "com.termux.api", "com.termux.boot")
    private val GOOGLE = setOf("com.google.android.gms", "com.google.android.gsf", "com.android.vending")
    private val TELEFONIA = setOf(
        "com.android.phone", "com.android.server.telecom", "com.android.providers.telephony", "com.android.mms.service",
        "com.android.ims", "com.android.ims.rcsservice", "com.google.android.ims", "com.android.stk", "com.android.carrierconfig",
        "com.android.carrierdefaultapp", "com.android.cellbroadcastreceiver", "com.android.cellbroadcastreceiver.module",
        "com.google.android.cellbroadcastreceiver", "com.android.cellbroadcastservice", "org.codeaurora.ims",
        "com.shannon.imsservice", "com.sec.imsservice", "com.mediatek.ims",
    )
    private val SISTEMA = setOf(
        "android", "com.android.networkstack", "com.google.android.networkstack", "com.android.networkstack.tethering",
        "com.google.android.networkstack.tethering", "com.android.captiveportallogin", "com.google.android.captiveportallogin",
        "com.android.networkstack.inprocess", "com.android.providers.downloads",
    )

    /** Si la app es esencial (y por qué). Las de uid de sistema (por debajo de 10000) lo son todas. */
    fun esencial(ctx: Context, paquete: String, uid: Int = -1): Esencial? = when {
        paquete == ctx.packageName -> Esencial.PROPIA
        paquete in AGENTE -> Esencial.AGENTE
        paquete in GOOGLE -> Esencial.GOOGLE
        paquete in TELEFONIA || uid == Process.PHONE_UID -> Esencial.TELEFONIA
        paquete in SISTEMA || (uid in 0 until Process.FIRST_APPLICATION_UID) -> Esencial.SISTEMA
        else -> null
    }

    /**
     * Las apps que van fuera del túnel (ni se filtran ni se bloquean): la propia SIOS (que hace de
     * filtro) y las esenciales a las que el usuario no ha quitado la red. Así, si algo fallara en el
     * cortafuegos, lo imprescindible (el agente, Google Play, la telefonía) sigue con internet.
     */
    internal fun fuera(ctx: Context): Set<String> {
        val r = reglas(ctx)
        val esenciales = AGENTE + GOOGLE + TELEFONIA + SISTEMA
        return (esenciales.filter { it !in r.sinWifi && it !in r.sinDatos } + ctx.packageName).toSet()
    }

    // ——— Encender y apagar ———

    /** El permiso de VPN del sistema: si hace falta pedirlo, el intent para pedirlo; si no, null. */
    fun permiso(ctx: Context): Intent? = runCatching { VpnService.prepare(ctx) }.getOrNull()

    /** ¿Hay otra VPN conectada (no la nuestra)? Android solo permite una: al encender, se cortaría. */
    fun otraVpn(ctx: Context): Boolean {
        if (CortafuegosVpn.instancia?.levantado == true) return false
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n -> cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    /** Enciende el cortafuegos (el permiso de VPN tiene que estar dado: ver [permiso]). */
    fun encender(ctx: Context) {
        prefs(ctx).edit().putBoolean("activo", true).apply()
        ponerEstado(ctx) { it.copy(arrancando = true, error = false, revocado = false) }
        val i = Intent(ctx, CortafuegosVpn::class.java)
        try {
            ctx.startService(i)
        } catch (_: IllegalStateException) {
            // Desde segundo plano (al arrancar el móvil): como servicio en primer plano un momento.
            runCatching { ctx.startForegroundService(i.putExtra(CortafuegosVpn.EXTRA_PRIMER_PLANO, true)) }
                .onFailure { ponerEstado(ctx) { e -> e.copy(arrancando = false, error = true) } }
        }
    }

    fun apagar(ctx: Context) {
        prefs(ctx).edit().putBoolean("activo", false).apply()
        val vpn = CortafuegosVpn.instancia
        if (vpn != null) vpn.apagar() else ponerEstado(ctx) { it.copy(encendido = false, arrancando = false) }
    }

    /** Al arrancar el móvil o la app (tras actualizarla): si estaba encendido, vuelve a encenderse. */
    fun alArrancar(ctx: Context) {
        if (!quiereActivo(ctx) || CortafuegosVpn.instancia != null) return
        // Sin permiso (se quitó desde los ajustes) o con otra VPN conectada, no se fuerza nada.
        if (permiso(ctx) != null || otraVpn(ctx)) return
        encender(ctx)
    }

    /** La red por la que sale ahora el móvil (para saber qué reglas valen). */
    internal fun redDe(caps: NetworkCapabilities?): Red? = when {
        caps == null -> null
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Red.DATOS
        else -> Red.WIFI // wifi, ethernet, USB…: lo que no se paga por datos
    }
}
