package com.sios.sistema

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Tiendas y apps predeterminadas.
 *
 * Tiendas que trae SIOS (rom/tienda/descargar.sh): Neo Store en las dos ediciones (apps libres de F-Droid;
 * es app privilegiada con INSTALL_PACKAGES, así instala y actualiza sin preguntar) y, en la edición Puro,
 * Aurora Store (apps de Google Play sin cuenta de Google). Con Google, además, Play Store.
 *
 * Apps predeterminadas: Android no deja que una app elija las de otra (hace falta MANAGE_ROLE_HOLDERS, que
 * es de firma). Aquí se ve cuál hay para cada cosa y se lleva al sitio del sistema donde se cambia.
 */
object AppsDeSerie {
    const val NEO_STORE = "com.machiav3lli.fdroid"
    const val AURORA = "com.aurora.store"
    const val PLAY = "com.android.vending"
    private val F_DROID = listOf("org.fdroid.fdroid", "org.fdroid.basic", "com.looker.droidify")

    /** Una tienda instalada. [tipo]: de dónde vienen sus apps (para el texto de la fila). */
    data class Tienda(val paquete: String, val nombre: String, val tipo: TipoTienda)
    enum class TipoTienda { LIBRE, PLAY_SIN_CUENTA, PLAY }

    fun tiendas(ctx: Context): List<Tienda> = buildList {
        (listOf(NEO_STORE) + F_DROID).forEach { p -> nombre(ctx, p)?.let { add(Tienda(p, it, TipoTienda.LIBRE)) } }
        nombre(ctx, AURORA)?.let { add(Tienda(AURORA, it, TipoTienda.PLAY_SIN_CUENTA)) }
        nombre(ctx, PLAY)?.let { add(Tienda(PLAY, it, TipoTienda.PLAY)) }
    }

    /** La tienda libre con la que buscar lo que falte (la de SIOS o la que haya puesto el usuario). */
    fun tiendaLibre(ctx: Context): String? = (listOf(NEO_STORE) + F_DROID).firstOrNull { instalada(ctx, it) }

    /** Lo que se puede elegir. [rol]: si es un rol de Android (se cambia en «Apps predeterminadas» del sistema). */
    enum class Tipo(val rol: Boolean, val buscar: String) {
        NAVEGADOR(true, "browser"),
        CORREO(false, "email"),
        MAPAS(false, "maps"),
        MENSAJES(true, "sms"),
    }

    /** Qué app hace cada cosa: [paquete] null si Android pregunta cada vez; [opciones]: cuántas pueden. */
    data class Eleccion(val tipo: Tipo, val paquete: String?, val nombre: String?, val opciones: Int)

    fun eleccion(ctx: Context, tipo: Tipo): Eleccion {
        val pm = ctx.packageManager
        if (tipo == Tipo.MENSAJES) {
            val p = runCatching { android.provider.Telephony.Sms.getDefaultSmsPackage(ctx) }.getOrNull()
            val opciones = pm.queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")), 0).map { it.activityInfo.packageName }.distinct().size
            return Eleccion(tipo, p, p?.let { nombre(ctx, it) }, opciones)
        }
        val intento = intento(tipo)
        val opciones = pm.queryIntentActivities(intento, PackageManager.MATCH_ALL).map { it.activityInfo.packageName }.distinct()
        // Con una predeterminada, resolveActivity da esa app; si no, el selector del sistema («android» o IntentResolver).
        val elegida = pm.resolveActivity(intento, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it in opciones }
        return Eleccion(tipo, elegida, elegida?.let { nombre(ctx, it) }, opciones.size)
    }

    private fun intento(tipo: Tipo): Intent = when (tipo) {
        Tipo.NAVEGADOR -> Intent(Intent.ACTION_VIEW, Uri.parse("https://sios.invalid/")).addCategory(Intent.CATEGORY_BROWSABLE)
        Tipo.CORREO -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        Tipo.MAPAS -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        Tipo.MENSAJES -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
    }

    /**
     * Al tocar una fila: sin ninguna app, a buscarla en la tienda libre; los roles, a «Apps
     * predeterminadas» del sistema; el correo y los mapas (no son roles), a «Abrir de forma
     * predeterminada» de la app elegida (ahí se borra la elección).
     */
    fun cambiar(ctx: Context, e: Eleccion) {
        val i = when {
            e.opciones == 0 -> tiendaLibre(ctx)?.let { buscarEnTienda(it, e.tipo.buscar) } ?: Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            e.tipo.rol -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            e.paquete != null -> Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, Uri.parse("package:${e.paquete}"))
            else -> Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
        abrir(ctx, i) || abrir(ctx, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
    }

    fun todas(ctx: Context) { abrir(ctx, Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }

    fun abrirTienda(ctx: Context, paquete: String) {
        ctx.packageManager.getLaunchIntentForPackage(paquete)?.let { abrir(ctx, it) }
    }

    /** La búsqueda de la tienda libre (market://search, solo esa tienda: con Google, Play también lo abre). */
    private fun buscarEnTienda(paquete: String, consulta: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$consulta")).setPackage(paquete)

    private fun abrir(ctx: Context, i: Intent): Boolean = runCatching {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)

    fun instalada(ctx: Context, paquete: String) = runCatching {
        ctx.packageManager.getApplicationInfo(paquete, 0).enabled
    }.getOrDefault(false)

    fun nombre(ctx: Context, paquete: String): String? = runCatching {
        val pm = ctx.packageManager
        val info = pm.getApplicationInfo(paquete, 0)
        if (!info.enabled) null else pm.getApplicationLabel(info).toString()
    }.getOrNull()

    fun icono(ctx: Context, paquete: String): android.graphics.drawable.Drawable? =
        runCatching { ctx.packageManager.getApplicationIcon(paquete) }.getOrNull()
}
