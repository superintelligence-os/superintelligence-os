package com.sios.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.sios.R
import org.json.JSONObject

/**
 * La marca de la IA con la que se ha entrado (Claude, ChatGPT, Gemini, DeepSeek…): su nombre, su
 * color y su logo se ven en todo el sistema. Mientras no se ha elegido ninguna, la de SIOS: su
 * símbolo, su nombre y el blanco hueso sobre grafito. Los textos de la app que nombran a la IA
 * dicen «Claude»; aquí se cambia por el nombre de la IA elegida al leerlos (ver [recursos]).
 * «SIOS», el nombre del sistema, no se toca.
 */
object Marca {
    const val SIOS = "sios"
    private const val HUESO = 0xFFF4F1EA
    private const val GRAFITO = 0xFF141413

    var id by mutableStateOf(SIOS)
        private set
    var nombre by mutableStateOf("SIOS")
        private set
    private var acentos by mutableStateOf(HUESO to GRAFITO)

    /** Sin IA elegida todavía: el sistema se presenta como SIOS. */
    val sinElegir get() = id == SIOS

    private var app: Context? = null
    private val vectores = HashMap<Int, ImageVector>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("marca", Context.MODE_PRIVATE)

    fun iniciar(ctx: Context) {
        app = ctx.applicationContext ?: ctx
        val p = prefs(ctx)
        id = p.getString("id", SIOS) ?: SIOS
        nombre = p.getString("nombre", "SIOS") ?: "SIOS"
        acentos = p.getLong("oscuro", HUESO) to p.getLong("claro", GRAFITO)
    }

    /** Desde el servicio (evento «ia» o /estado): { id, nombre, acento: ["#…", "#…"], tipo }. Devuelve true si ha cambiado. */
    fun poner(ia: JSONObject?): Boolean {
        ia ?: return false
        val ctx = app ?: return false
        val nuevoId = ia.optString("id").ifBlank { return false }
        // El modelo local se llama como el usuario quiera (por defecto, Qwen).
        val nuevoNombre = ia.optString("nombre").ifBlank { if (nuevoId == SIOS) "SIOS" else "Claude" }
        val a = ia.optJSONArray("acento")
        val oscuro = a?.optString(0)?.let(::hex) ?: if (nuevoId == SIOS) HUESO else 0xFFD97757
        val claro = a?.optString(1)?.let(::hex) ?: oscuro
        if (nuevoId == id && nuevoNombre == nombre && acentos == (oscuro to claro)) return false
        id = nuevoId
        nombre = nuevoNombre
        acentos = oscuro to claro
        prefs(ctx).edit().putString("id", id).putString("nombre", nombre).putLong("oscuro", oscuro).putLong("claro", claro).apply()
        // Los colores del sistema y el fondo son los de SIOS: no cambian con la IA.
        runCatching { com.sios.sistema.SistemaSios.aplicarTema(ctx) }
        runCatching { com.sios.sistema.ColoresSistema.aplicar(ctx) }
        return true
    }

    private fun hex(s: String): Long? = runCatching { 0xFF000000 or s.removePrefix("#").toLong(16) }.getOrNull()

    fun acento(oscuro: Boolean): Long = if (oscuro) acentos.first else acentos.second

    /** El contexto de la app, para quien no tiene uno a mano (null antes de iniciar). */
    fun contexto(): Context? = app

    /** Un texto de la app en el idioma del móvil, desde fuera de una pantalla (null antes de iniciar). */
    fun textoDe(id: Int): String? = app?.getString(id)

    /** Logo para notificaciones y accesos rápidos (en un color). */
    fun recurso(): Int = when (id) {
        "chatgpt", "openai" -> R.drawable.ic_ia_chatgpt
        "gemini", "gemini_api" -> R.drawable.ic_ia_gemini
        "deepseek" -> R.drawable.ic_ia_deepseek
        "kimi", "kimi_code" -> R.drawable.ic_ia_kimi
        "mistral" -> R.drawable.ic_ia_mistral
        "grok" -> R.drawable.ic_ia_grok
        "openrouter" -> R.drawable.ic_ia_openrouter
        "local" -> R.drawable.ic_ia_local
        "claude", "anthropic" -> R.drawable.ic_claude
        else -> R.drawable.ic_sios
    }

    /** Logo para la interfaz (null con Claude: se usa la chispa de siempre, ya dibujada). */
    fun logo(): ImageVector? {
        val r = recurso()
        if (r == R.drawable.ic_claude) return null
        val ctx = app ?: return null
        return vectores.getOrPut(r) { ImageVector.vectorResource(null, ctx.resources, r) }
    }

    /**
     * Marca invisible (U+2060, unidor de palabras) tras un «Claude» que nombra a Claude en concreto
     * (por ejemplo, en la lista de IA para elegir): ese no se cambia.
     */
    const val FIJO = '\u2060'

    /** «Claude» → el nombre de la IA elegida, sin tocar «SIOS» ni los «Claude» marcados con [FIJO]. */
    fun texto(s: CharSequence): CharSequence {
        if (id == "claude" || !s.contains("Claude")) return s
        if (s !is Spanned) return cambiar(s.toString())
        val sb = SpannableStringBuilder(s)
        var i = sb.indexOf("Claude")
        while (i >= 0) {
            val fijo = sb.startsWith(" OS", i + 6) || (i + 6 < sb.length && sb[i + 6] == FIJO)
            if (!fijo) sb.replace(i, i + 6, nombre)
            i = sb.indexOf("Claude", i + if (fijo) 6 else nombre.length)
        }
        return sb
    }

    private val CAMBIABLE = Regex("Claude(?! OS|\u2060)")
    private fun cambiar(s: String) = CAMBIABLE.replace(s, Regex.escapeReplacement(nombre))

    fun cadena(s: String): String = if (id == "claude") s else cambiar(s)

    private var recursosApp: Pair<Resources, Resources>? = null

    /** Los recursos de la app con el nombre de la IA (para Application.getResources). */
    fun recursos(base: Resources): Resources =
        recursosApp?.takeIf { it.first === base }?.second ?: Recursos(base).also { recursosApp = base to it }

    /** Contexto cuyos textos ya dicen el nombre de la IA elegida (para attachBaseContext). */
    fun envolver(base: Context): Context = object : ContextWrapper(base) {
        private var r: Resources? = null
        override fun getResources(): Resources {
            val b = super.getResources()
            return r?.takeIf { (it as? Recursos)?.base === b } ?: Recursos(b).also { r = it }
        }
    }

    @Suppress("DEPRECATION")
    private class Recursos(val base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getText(id: Int): CharSequence = texto(base.getText(id))
        override fun getText(id: Int, def: CharSequence?): CharSequence? = base.getText(id, def)?.let(::texto)
        override fun getQuantityText(id: Int, quantity: Int): CharSequence = texto(base.getQuantityText(id, quantity))
        override fun getString(id: Int): String = texto(base.getString(id)).toString()
        // Se cambia la plantilla y luego se rellena: los nombres que se pasan («Usar %1$s» con
        // «Claude» en la lista de IA) se quedan como están.
        override fun getString(id: Int, vararg formatArgs: Any?): String =
            String.format(base.configuration.locales[0], cadena(base.getString(id)), *formatArgs)
        override fun getQuantityString(id: Int, quantity: Int): String = cadena(base.getQuantityString(id, quantity))
        override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
            String.format(base.configuration.locales[0], cadena(base.getQuantityText(id, quantity).toString()), *formatArgs)
    }
}

/** La cabecera de la notificación dice el nombre de la IA en uso (permiso de app del sistema). */
fun android.app.Notification.Builder.conMarca(): android.app.Notification.Builder =
    addExtras(android.os.Bundle().apply { putString("android.substName", Marca.nombre) })
