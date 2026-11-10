package com.sios.sistema

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import com.sios.datos.Servicio
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Las manos de Claude: servicio de accesibilidad con el que el agente lee lo que hay en pantalla y
 * toca, desliza y escribe en cualquier app, mucho más rápido que uiautomator y con tildes y emojis.
 * Mientras lo hace, el Halo ilumina el borde y dibuja cada toque. El agente lo usa a través del
 * puente (métodos manos_*); estas funciones se llaman desde el hilo del binder.
 */
class Manos : AccessibilityService() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private val principal = Handler(Looper.getMainLooper())
    private lateinit var halo: Halo

    override fun onServiceConnected() {
        halo = Halo(this, onParar = { parar() }, onCaducado = { activo.value = false })
        instancia = this
        // Memoria de pantalla: pide los avisos de cambios de contenido solo si está activa.
        runCatching { MemoriaPantalla.alConectar(this) }
    }

    // Portapapeles inteligente: solo mira las ventanas de SystemUI (la vista previa de lo copiado).
    private val portapapeles by lazy { Portapapeles(this) }

    override fun onAccessibilityEvent(evento: AccessibilityEvent?) {
        evento ?: return
        // El portapapeles, solo con los cambios de ventana (no con cada cambio de contenido de la barra).
        val deVentana = evento.eventType and (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED) != 0
        if (deVentana && evento.packageName == "com.android.systemui") runCatching { portapapeles.revisar() }
        MemoriaPantalla.evento(this, evento)
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instancia === this) instancia = null
        runCatching { MemoriaPantalla.alDesconectar(this) }
        principal.post { halo.apagar() }
        activo.value = false
        super.onDestroy()
    }

    /** El usuario pulsa «Parar» en la píldora: se corta la respuesta y se apaga el halo. */
    private fun parar() {
        Servicio.detener()
        fin()
    }

    private fun enPrincipal(bloque: () -> Unit) {
        val listo = CountDownLatch(1)
        principal.post { runCatching(bloque); listo.countDown() }
        listo.await(2, TimeUnit.SECONDS)
    }

    /**
     * Enciende el halo. La primera vez, también aparta la hoja del asistente (si estaba encima) y
     * espera a que se vaya, para leer y tocar la app de debajo y no la hoja.
     */
    private fun empezar() {
        val antes = activo.value
        enPrincipal { halo.encender() }
        if (!antes) {
            activo.value = true
            Thread.sleep(450)
        }
    }

    fun fin() {
        enPrincipal { halo.apagar() }
        activo.value = false
    }

    fun ocultar(ms: Long) = enPrincipal { halo.ocultar(ms) }

    // ---------- Leer ----------

    /** Todo el texto visible, en orden de lectura, sin encender el halo (para leerlo en voz alta). */
    fun textoVisible(): String {
        // La Bóveda en pantalla: nada de lo que enseña llega a la IA.
        if (com.sios.boveda.Boveda.enPantalla) return ""
        val raiz = rootInActiveWindow ?: return ""
        val partes = mutableListOf<String>()
        fun ir(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val t = (n.text?.toString() ?: n.contentDescription?.toString()).orEmpty().trim()
            if (t.isNotEmpty() && partes.lastOrNull() != t) partes += t
            for (i in 0 until n.childCount) n.getChild(i)?.let(::ir)
        }
        ir(raiz)
        return partes.joinToString(". ").take(4000)
    }

    fun leer(): JSONObject {
        if (com.sios.boveda.Boveda.enPantalla) error("La Bóveda está abierta en pantalla: no se puede leer")
        empezar()
        val raiz = rootInActiveWindow ?: windows.firstOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            ?: error("No se puede leer la pantalla ahora mismo")
        val elementos = JSONArray()
        recorrer(raiz, elementos)
        return JSONObject().put("app", raiz.packageName?.toString() ?: "desconocida").put("elementos", elementos)
    }

    private fun recorrer(n: AccessibilityNodeInfo, salida: JSONArray) {
        if (!n.isVisibleToUser) return
        val texto = n.text?.toString().orEmpty()
        val desc = n.contentDescription?.toString().orEmpty()
        val pulsable = n.isClickable || n.isLongClickable
        val desplazable = n.isScrollable
        val editable = n.isEditable
        if (texto.isNotEmpty() || desc.isNotEmpty() || pulsable || desplazable || editable) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                salida.put(
                    JSONObject()
                        .put("texto", if (n.isShowingHintText) "" else texto)
                        .put("desc", desc.ifEmpty { if (n.isShowingHintText) texto else "" })
                        .put("id", n.viewIdResourceName?.substringAfterLast('/').orEmpty())
                        .put("tipo", n.className?.toString()?.substringAfterLast('.').orEmpty())
                        .put("pulsable", pulsable)
                        .put("desplazable", desplazable)
                        .put("editable", editable)
                        .put("marcado", n.isChecked)
                        .put("activo", n.isEnabled)
                        .put("x", r.centerX())
                        .put("y", r.centerY()),
                )
            }
        }
        for (i in 0 until n.childCount) n.getChild(i)?.let { recorrer(it, salida) }
    }

    /**
     * El texto visible de la app de delante, sin encender el halo (para el teclado: «Responder»
     * con la conversación que hay en pantalla). Deja fuera el propio teclado.
     */
    fun textoPantalla(): String {
        val app = windows.firstOrNull { it.isActive && it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            ?: rootInActiveWindow ?: return ""
        val trozos = mutableListOf<String>()
        fun recorrer(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            // Muchas apps (Mensajes, WhatsApp) ponen el texto de la burbuja en la descripción.
            val t = n.text?.toString()?.takeIf { it.isNotBlank() && !n.isEditable }
            val d = n.contentDescription?.toString()?.takeIf { it.isNotBlank() && it != t }
            listOfNotNull(t, d).forEach { if (trozos.lastOrNull() != it) trozos += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { recorrer(it) }
        }
        recorrer(app)
        return trozos.joinToString("\n").takeLast(4000)
    }

    // ---------- Tocar y deslizar ----------

    fun tocar(x: Float, y: Float, largo: Boolean): Boolean {
        empezar()
        val ms = if (largo) 850L else 70L
        val trazo = Path().apply { moveTo(x, y) }
        principal.post { halo.toque(x, y, if (largo) ms else 110L) }
        return gesto(GestureDescription.StrokeDescription(trazo, 0, ms))
    }

    fun deslizar(desde: PointF, hasta: PointF, ms: Long): Boolean {
        empezar()
        val trazo = Path().apply { moveTo(desde.x, desde.y); lineTo(hasta.x, hasta.y) }
        principal.post { halo.deslizar(desde, hasta, ms) }
        return gesto(GestureDescription.StrokeDescription(trazo, 0, ms))
    }

    private fun gesto(trazo: GestureDescription.StrokeDescription): Boolean {
        enPrincipal { halo.atravesable(true) }
        Thread.sleep(90) // a que el sistema aplique el cambio de la ventana antes del gesto
        try {
            return gestoSinPildora(trazo)
        } finally {
            principal.postDelayed({ halo.atravesable(false) }, 250)
        }
    }

    private fun gestoSinPildora(trazo: GestureDescription.StrokeDescription): Boolean {
        val listo = CountDownLatch(1)
        var hecho = false
        val ok = dispatchGesture(
            GestureDescription.Builder().addStroke(trazo).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { hecho = true; listo.countDown() }
                override fun onCancelled(g: GestureDescription?) { listo.countDown() }
            },
            principal,
        )
        if (!ok) return false
        listo.await(trazo.duration + 3000, TimeUnit.MILLISECONDS)
        return hecho
    }

    // ---------- Escribir ----------

    /** Escribe en el campo con el foco, donde esté el cursor. Devuelve si pudo pulsar Intro (si se pidió). */
    fun escribir(texto: String, enviar: Boolean): JSONObject {
        empezar()
        val campo = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: error("No hay ningún campo de texto con el foco: tócalo antes")
        val r = Rect().also { campo.getBoundsInScreen(it) }
        principal.post { halo.resaltar(r) }
        val actual = if (campo.isShowingHintText) "" else campo.text?.toString().orEmpty()
        val ini = campo.textSelectionStart.takeIf { it in 0..actual.length } ?: actual.length
        val fin = campo.textSelectionEnd.takeIf { it in ini..actual.length } ?: ini
        val nuevo = actual.substring(0, ini) + texto + actual.substring(fin)
        val escrito = campo.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, nuevo) },
        )
        if (!escrito) error("Este campo no deja escribir")
        val cursor = ini + texto.length
        campo.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
        val enviado = enviar && campo.refresh() && campo.performAction(AccessibilityAction.ACTION_IME_ENTER.id)
        return JSONObject().put("escrito", true).put("intro", enviado)
    }

    companion object {
        @Volatile var instancia: Manos? = null
            private set

        /** Bloquea la pantalla (doble toque en el inicio). Falso si el servicio no está activo. */
        fun bloquear(): Boolean = instancia?.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) ?: false

        /** Claude está manejando el móvil: la hoja del asistente se aparta mientras dura. */
        val activo = MutableStateFlow(false)

        private fun componente(ctx: Context) = ComponentName(ctx, Manos::class.java).flattenToString()

        private fun prefs(ctx: Context) = ctx.getSharedPreferences("sios", Context.MODE_PRIVATE)

        /** Lo que quiere el usuario (Ajustes de SIOS › «Claude puede usar tus apps»). Sí por defecto. */
        fun permitido(ctx: Context) = prefs(ctx).getBoolean("manos_permitidas", true)

        fun permitir(ctx: Context, si: Boolean) {
            prefs(ctx).edit().putBoolean("manos_permitidas", si).apply()
            asegurar(ctx)
        }

        fun enSistema(ctx: Context) = componente(ctx) in
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')

        /**
         * Pone el servicio de accesibilidad como diga el interruptor de Claude. Android lo quita solo
         * si la app se cierra de golpe (actualizar, restaurar una copia): así vuelve sin que el
         * usuario tenga que buscarlo en Ajustes. Solo en SIOS (necesita WRITE_SECURE_SETTINGS).
         */
        fun asegurar(ctx: Context) {
            if (ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val cr = ctx.contentResolver
            val nuestro = componente(ctx)
            val lista = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').filter { it.isNotBlank() }
            // También encendido si la memoria de pantalla está activa (es quien lee la pantalla); el
            // agente sigue sin poder tocar nada si el usuario no se lo permite (ver Puente.manos).
            val quiere = permitido(ctx) || MemoriaPantalla.activa(ctx)
            if (quiere == (nuestro in lista)) return
            val nueva = if (quiere) lista + nuestro else lista - nuestro
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, nueva.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (nueva.isEmpty()) 0 else 1)
        }

        /** Nombre antiguo, por compatibilidad con quien lo llame. */
        fun activarUnaVez(ctx: Context) = asegurar(ctx)
    }
}
