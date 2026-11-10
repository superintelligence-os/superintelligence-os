package com.sios.inicio

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.text.Collator
import java.util.Locale

/**
 * Una app del cajón. `monocromo`: el icono es la capa monocroma del icono adaptable (se tiñe con el
 * color del tema); si no, es el icono original a sangre (fondo + primer plano), que el inicio
 * recorta con la forma del pack. Las apps del pack de iconos usan su glifo en vez de esto.
 */
data class App(
    val nombreBase: String,
    val paquete: String,
    val componente: ComponentName,
    val icono: ImageBitmap,
    val monocromo: Boolean,
) {
    /** La app del asistente se llama como la IA elegida (Claude, ChatGPT, Gemini…). */
    val nombre: String get() = if (paquete == "com.sios" && componente.className.endsWith("ChatActivity")) com.sios.ui.Marca.nombre
        else if (paquete == "com.sios") com.sios.ui.Marca.cadena(nombreBase) else nombreBase
}

private const val TAM = 144 // px del mapa de bits del icono

private fun aMapa(d: Drawable, ampliar: Boolean): Bitmap {
    val b = Bitmap.createBitmap(TAM, TAM, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    if (ampliar) {
        // Las capas adaptables miden 108 dp y solo se ve el centro (72 dp): se amplían 1,5×.
        val m = TAM / 4
        d.setBounds(-m, -m, TAM + m, TAM + m)
    } else {
        d.setBounds(0, 0, TAM, TAM)
    }
    d.draw(c)
    return b
}

/** Icono adaptable sin la máscara del sistema: fondo y primer plano llenando todo el cuadrado. */
private fun aSangre(d: AdaptiveIconDrawable): Bitmap {
    val b = Bitmap.createBitmap(TAM, TAM, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val m = TAM / 4
    for (capa in listOfNotNull(d.background, d.foreground)) {
        capa.setBounds(-m, -m, TAM + m, TAM + m)
        capa.draw(c)
    }
    return b
}

/** Complementos de Termux: solo abren una pantalla de ayuda, no pintan nada en el cajón. */
private val OCULTAS = setOf("com.termux.api", "com.termux.boot")

/** Marcadores del sistema que sustituye el teléfono de Claude. */
private val MARCADORES = setOf("com.android.dialer", "com.google.android.dialer")

fun cargarApps(ctx: Context): List<App> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val orden = Collator.getInstance(Locale.getDefault()) // orden alfabético del idioma del móvil
    // Con el teléfono de Claude como predeterminado, el marcador antiguo sobra en el cajón.
    val telefonoSios = runCatching {
        ctx.getSystemService(android.app.role.RoleManager::class.java).isRoleHeld(android.app.role.RoleManager.ROLE_DIALER)
    }.getOrDefault(false)
    return pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .mapNotNull { r ->
            val ai = r.activityInfo ?: return@mapNotNull null
            if (ai.packageName in OCULTAS) return@mapNotNull null
            if (telefonoSios && ai.packageName in MARCADORES) return@mapNotNull null
            val d = ai.loadIcon(pm)
            val adaptable = d as? AdaptiveIconDrawable
            val mono = if (android.os.Build.VERSION.SDK_INT >= 33) adaptable?.monochrome else null // capa monocroma: Android 13+
            App(
                nombreBase = r.loadLabel(pm).toString(),
                paquete = ai.packageName,
                componente = ComponentName(ai.packageName, ai.name),
                icono = when {
                    mono != null -> aMapa(mono, ampliar = true)
                    adaptable != null -> aSangre(adaptable)
                    else -> aMapa(d, ampliar = false)
                }.asImageBitmap(),
                monocromo = mono != null,
            )
        }
        .sortedWith { a, b -> orden.compare(a.nombre, b.nombre) }
}

/** Las cuatro apps del muelle: teléfono, mensajes, navegador y cámara (las predeterminadas). */
fun appsMuelle(ctx: Context, apps: List<App>): List<App> {
    val pm = ctx.packageManager
    // La actividad exacta (no solo el paquete): la app Claude tiene varias entradas en el cajón.
    fun predeterminada(i: Intent) = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        ?.let { android.content.ComponentName(it.packageName, it.name).flattenToString() }
    val claves = listOfNotNull(
        predeterminada(Intent(Intent.ACTION_DIAL)),
        Telephony.Sms.getDefaultSmsPackage(ctx),
        predeterminada(Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai"))),
        predeterminada(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)),
    )
    val elegidas = claves.mapNotNull { buscarApp(apps, it) }.distinctBy { it.componente }
    return (elegidas + apps.filterNot { a -> elegidas.any { it.componente == a.componente } }).take(4)
}

/** Una app por su clave guardada: componente («paquete/Actividad») o, en muelles antiguos, el paquete. */
fun buscarApp(apps: List<App>, clave: String): App? =
    if ('/' in clave) apps.firstOrNull { it.componente.flattenToString() == clave || it.componente.flattenToShortString() == clave }
        ?: apps.firstOrNull { it.paquete == clave.substringBefore('/') }
    else apps.firstOrNull { it.paquete == clave && it.componente.className.substringAfterLast('.') !in ENTRADAS_EXTRA }
        ?: apps.firstOrNull { it.paquete == clave }

/** Entradas secundarias de la app Claude: con solo el paquete, se refiere a la principal. */
private val ENTRADAS_EXTRA = setOf("CamaraActivity", "CamaraProActivity", "TelefonoActivity")

/** Icono que se acaba de tocar (vista y caja en la ventana): la app se abre creciendo desde él. */
object Origen {
    private var vista: android.view.View? = null
    private var caja: android.graphics.Rect? = null
    private var cuando = 0L

    fun marcar(v: android.view.View, r: android.graphics.Rect) { vista = v; caja = r; cuando = android.os.SystemClock.uptimeMillis() }

    /** Opciones de apertura desde el icono, si se tocó hace un momento (no vale para aperturas por voz). */
    fun opciones(): android.os.Bundle? {
        val v = vista ?: return null
        val r = caja ?: return null
        vista = null; caja = null
        if (android.os.SystemClock.uptimeMillis() - cuando > 1500) return null
        return android.app.ActivityOptions.makeScaleUpAnimation(v, r.left, r.top, r.width(), r.height()).toBundle()
    }
}

fun abrirApp(ctx: Context, app: App) {
    ctx.startActivity(
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(app.componente)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        Origen.opciones(),
    )
}

fun infoApp(ctx: Context, app: App) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.paquete}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
