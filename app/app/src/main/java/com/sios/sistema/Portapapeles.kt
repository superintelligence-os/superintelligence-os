package com.sios.sistema

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import com.sios.R
import java.math.BigInteger

/**
 * Lo que se puede hacer con un texto (copiado o leído de una captura): llamar, ir, abrir, escribir,
 * comprobar un IBAN, seguir un envío, traducir o resumir. Todo con reglas, en el móvil.
 */
object Sugerencias {
    data class Accion(val tipo: String, val titulo: String, val valor: String)

    private val TELEFONO = Regex("(?<![\\d])(?:\\+34\\s?)?[6789]\\d{2}[\\s.-]?\\d{2,3}[\\s.-]?\\d{2,3}[\\s.-]?\\d{0,3}(?![\\d])")
    private val WEB = Regex("(https?://[^\\s]+|www\\.[^\\s]+)", RegexOption.IGNORE_CASE)
    private val CORREO = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val IBAN = Regex("\\b([A-Z]{2}\\d{2}(?:\\s?[A-Z0-9]{4}){4,7}(?:\\s?[A-Z0-9]{1,3})?)\\b")
    private val DIRECCION = Regex("\\b(calle|c/|avda\\.?|avenida|plaza|pza\\.?|paseo|camino|carretera|ctra\\.?|ronda|travesía|glorieta|polígono)\\s+[^,\\n]{2,40}(,?\\s*(n[ºo°]?\\.?\\s*)?\\d{1,4})?", RegexOption.IGNORE_CASE)
    private val POSTAL = Regex("\\b\\d{5}\\s+[A-ZÁÉÍÓÚÑ][\\wáéíóúñ ]{2,30}")
    private val SEGUIMIENTO = Regex("\\b(PQ[A-Z0-9]{14,22}|1Z[A-Z0-9]{16}|[A-Z]{2}\\d{9}[A-Z]{2})\\b")
    private val FECHA = Regex("(\\b\\d{1,2}[/.-]\\d{1,2}([/.-]\\d{2,4})?\\b|\\b\\d{1,2} de (enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|octubre|noviembre|diciembre)\\b|\\b(mañana|el lunes|el martes|el miércoles|el jueves|el viernes|el sábado|el domingo)\\b.{0,20}\\b\\d{1,2}[:.]\\d{2}\\b)", RegexOption.IGNORE_CASE)
    private val ESPANOL = Regex("\\b(el|la|los|las|de|que|y|en|un|una|por|para|con|es|no|se|lo|como|más|pero)\\b", RegexOption.IGNORE_CASE)

    /** IBAN de verdad (dígitos de control, módulo 97). */
    fun ibanValido(iban: String): Boolean {
        val s = iban.replace(" ", "").uppercase()
        if (s.length !in 15..34) return false
        val reordenado = (s.drop(4) + s.take(4)).map { if (it.isLetter()) (it - 'A' + 10).toString() else it.toString() }.joinToString("")
        return runCatching { BigInteger(reordenado).mod(BigInteger.valueOf(97)) == BigInteger.ONE }.getOrDefault(false)
    }

    fun para(ctx: Context, texto: String, deCaptura: Boolean = false): List<Accion> {
        val t = texto.trim()
        if (t.isEmpty()) return emptyList()
        val r = mutableListOf<Accion>()
        IBAN.find(t.uppercase())?.let { m ->
            val iban = m.value.replace(" ", "")
            if (iban.length >= 15) r += Accion("iban", ctx.getString(if (ibanValido(iban)) R.string.sug_iban_ok else R.string.sug_iban_mal), iban)
        }
        SEGUIMIENTO.find(t)?.let { r += Accion("enlace", ctx.getString(R.string.sug_seguimiento), "https://parcelsapp.com/es/tracking/${it.value}") }
        WEB.find(t)?.let { r += Accion("enlace", ctx.getString(R.string.sug_abrir), it.value.let { u -> if (u.startsWith("http")) u else "https://$u" }) }
        CORREO.find(t)?.let { r += Accion("correo", ctx.getString(R.string.sug_correo), it.value) }
        TELEFONO.find(t)?.let { m -> if (m.value.count(Char::isDigit) >= 9) r += Accion("llamar", ctx.getString(R.string.sug_llamar), m.value.filter { it.isDigit() || it == '+' }) }
        (DIRECCION.find(t) ?: POSTAL.find(t))?.let { r += Accion("ir", ctx.getString(R.string.sug_ir), it.value.trim()) }
        if (deCaptura && FECHA.containsMatchIn(t)) r += Accion("calendario", ctx.getString(R.string.sug_calendario), t)
        val palabras = t.split(Regex("\\s+")).size
        if (palabras >= 6 && ESPANOL.findAll(t).count() < palabras / 12) r += Accion("traducir", ctx.getString(R.string.sug_traducir), t)
        if (palabras >= 60) r += Accion("resumir", ctx.getString(R.string.sug_resumir), t)
        return r.distinctBy { it.tipo }
    }

    /** Ejecuta una sugerencia (desde el chip del portapapeles o el aviso de una captura). */
    fun hacer(ctx: Context, a: Accion) {
        val nueva = Intent.FLAG_ACTIVITY_NEW_TASK
        runCatching {
            when (a.tipo) {
                "llamar" -> ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", a.valor, null)).addFlags(nueva))
                "ir" -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(a.valor)}")).addFlags(nueva))
                "enlace" -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.valor)).addFlags(nueva))
                "correo" -> ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${a.valor}")).addFlags(nueva))
                "traducir" -> ctx.startActivity(Intent(Intent.ACTION_PROCESS_TEXT).setClassName(ctx, "com.sios.sistema.TextoTraducir").putExtra(Intent.EXTRA_PROCESS_TEXT, a.valor).addFlags(nueva))
                "resumir" -> ctx.startActivity(Intent(ctx, AsistenteActivity::class.java).putExtra(android.app.SearchManager.QUERY, ctx.getString(R.string.sug_pedir_resumen, a.valor.take(3000))).addFlags(nueva))
                "calendario" -> ctx.startActivity(Intent(ctx, AsistenteActivity::class.java).putExtra(android.app.SearchManager.QUERY, ctx.getString(R.string.sug_pedir_calendario, a.valor.take(2000))).addFlags(nueva))
                "claude" -> ctx.startActivity(Intent(ctx, AsistenteActivity::class.java).putExtra(android.app.SearchManager.QUERY, ctx.getString(R.string.sug_pedir_ia, a.valor.take(2000))).addFlags(nueva))
            }
        }.onFailure {
            // Sin app de mapas: la búsqueda del sitio en el navegador.
            if (a.tipo == "ir") runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(a.valor)}")).addFlags(nueva)) }
        }
    }
}

/**
 * Portapapeles inteligente: cuando Android enseña la vista previa de lo copiado (SystemUI), las
 * manos de Claude la leen y, si sirve para algo, aparece encima un chip con la acción (y «Claude»).
 * No hace falta ningún permiso especial ni leer el portapapeles en segundo plano.
 */
class Portapapeles(private val servicio: Manos) {
    private val principal = Handler(Looper.getMainLooper())
    private var vista: View? = null
    private var ultimo = ""
    private var cuando = 0L
    private val quitar = Runnable { esconder() }

    /** Mira si la ventana nueva es la vista previa del portapapeles y qué hay copiado. */
    fun revisar() {
        // Lo copiado como sensible (contraseñas y códigos de la Bóveda o de otro gestor): ni chip ni al ordenador.
        val sensible = runCatching {
            servicio.getSystemService(android.content.ClipboardManager::class.java).primaryClipDescription
                ?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        }.getOrDefault(false)
        if (sensible) return
        val texto = servicio.windows.orEmpty().firstNotNullOfOrNull { w ->
            w.root?.takeIf { it.packageName == "com.android.systemui" }?.let(::textoCopiado)
        } ?: return
        if (texto == ultimo && System.currentTimeMillis() - cuando < 4000) return
        ultimo = texto; cuando = System.currentTimeMillis()
        // Al Mac, si está la continuidad: el texto entero si se puede leer (Claude es el teclado), si no la vista previa.
        val entero = runCatching {
            servicio.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(servicio)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
        Continuidad.portapapeles(servicio, entero ?: texto)
        val acciones = Sugerencias.para(servicio, texto).take(2)
        if (acciones.isEmpty() && texto.length < 40) return
        principal.post { mostrar(texto, acciones) }
    }

    private fun textoCopiado(raiz: AccessibilityNodeInfo): String? {
        for (id in listOf("com.android.systemui:id/clipboard_text_preview", "com.android.systemui:id/text_preview")) {
            raiz.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, servicio.resources.displayMetrics).toInt()

    private fun mostrar(texto: String, acciones: List<Sugerencias.Accion>) {
        esconder()
        val claro = com.sios.ui.Apariencia.claro
        val fondo = if (claro) 0xFFFFFFFF.toInt() else 0xFF30302E.toInt()
        val tinta = if (claro) 0xFF141413.toInt() else 0xFFFAF9F5.toInt()
        val acento = com.sios.ui.Apariencia.colorAcento().toInt()
        val fila = LinearLayout(servicio).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            background = GradientDrawable().apply { cornerRadius = dp(26f).toFloat(); setColor(fondo); setStroke(dp(1f), if (claro) 0xFFE3E1D7.toInt() else 0xFF3E3D3A.toInt()) }
            elevation = dp(8f).toFloat()
        }
        fun boton(titulo: String, principalB: Boolean, accion: () -> Unit) = TextView(servicio).apply {
            text = titulo
            textSize = 14f
            setTextColor(if (principalB) 0xFFFFFFFF.toInt() else tinta)
            typeface = android.graphics.Typeface.create(androidx.core.content.res.ResourcesCompat.getFont(servicio, R.font.montserrat), 600, false)
            setPadding(dp(14f), dp(9f), dp(14f), dp(9f))
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(if (principalB) acento else 0) }
            setOnClickListener { accion(); esconder() }
        }
        acciones.forEachIndexed { i, a ->
            if (i > 0) fila.addView(View(servicio), LinearLayout.LayoutParams(dp(4f), 1))
            fila.addView(boton(a.titulo, i == 0) { Sugerencias.hacer(servicio, a) })
        }
        fila.addView(View(servicio), LinearLayout.LayoutParams(dp(4f), 1))
        fila.addView(boton("✳ Claude", acciones.isEmpty()) { Sugerencias.hacer(servicio, Sugerencias.Accion("claude", "", texto)) })
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; x = dp(16f); y = dp(150f) }
        runCatching { servicio.getSystemService(WindowManager::class.java).addView(fila, p) }.onFailure { return }
        vista = fila
        // Entra con un muelle (de abajo y un poco pequeño) y se va sola a los 7 s.
        fila.alpha = 0f; fila.translationY = dp(24f).toFloat(); fila.scaleX = 0.92f; fila.scaleY = 0.92f
        fila.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(1.4f)).start()
        principal.postDelayed(quitar, 7000)
    }

    fun esconder() {
        principal.removeCallbacks(quitar)
        val v = vista ?: return
        vista = null
        v.animate().alpha(0f).translationY(v.height / 3f).setDuration(220).withEndAction {
            runCatching { servicio.getSystemService(WindowManager::class.java).removeView(v) }
        }.start()
    }
}
