package com.sios.sistema

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import com.sios.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Memoria de pantalla («¿dónde vi…?»): recuerda lo que pasa por la pantalla para encontrarlo después.
 * Desactivada de serie; se activa al final de su presentación (Ajustes › Memoria del móvil).
 *
 * Cómo captura, sin gastar: las Manos (el servicio de accesibilidad de SIOS) solo piden avisos de
 * cambios de contenido mientras está activa y sin pausa. Cada aviso solo apunta la hora; un hilo de
 * fondo espera a que la pantalla se calme (1,2 s, como mucho 5 s seguidos), deja al menos 4 s entre
 * lecturas y lee el texto visible. Si es casi igual que lo último guardado de esa app, no guarda nada.
 * Si la pantalla es sobre todo imagen, como mucho cada 12 s y solo si la imagen cambió: lee su texto
 * (con [Ocr], en el móvil) y saca su huella (CLIP), sin guardar la imagen. Se escribe en lotes.
 *
 * Nunca guarda: campos de contraseña (ni la pantalla que los tiene), datos marcados como sensibles,
 * apps de banca, pago, contraseñas o códigos (lista, nombre y ventanas protegidas con FLAG_SECURE),
 * el incógnito de los navegadores, la pantalla de bloqueo, la propia SIOS ni las apps excluidas.
 */
object MemoriaPantalla {
    private const val CALMA = 1_200L
    private const val ESPERA_MAX = 5_000L
    private const val INTERVALO_MIN = 4_000L
    private const val INTERVALO_IMAGEN = 12_000L
    private const val IMAGENES_POR_HORA = 120
    private const val LOTE = 6
    private const val ESPERA_LOTE = 30_000L
    private const val MAX_TEXTO = 8_000
    private const val CANAL = "memoria_pantalla"
    private const val ID_AVISO = 4710

    // ---------- Preferencias ----------

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("memoria_pantalla", Context.MODE_PRIVATE)

    /** Activa de verdad: elegido por el usuario y con su clave creada en este móvil (no restaurada de una copia). */
    fun activa(ctx: Context) = prefs(ctx).getBoolean("activa", false) && MemoriaPantallaBase.preparada(ctx)
    fun pausada(ctx: Context) = prefs(ctx).getBoolean("pausada", false)
    /** 7 o 30 días. */
    fun dias(ctx: Context) = prefs(ctx).getInt("dias", 7)
    fun miniaturas(ctx: Context) = prefs(ctx).getBoolean("miniaturas", false)
    fun excluidas(ctx: Context): Set<String> = prefs(ctx).getStringSet("excluidas", emptySet()) ?: emptySet()

    /** Para que la interfaz se entere de los cambios (pausa desde el recuadro o la notificación). */
    data class Estado(val activa: Boolean, val pausada: Boolean, val dias: Int, val miniaturas: Boolean, val excluidas: Set<String>)

    private val _estado = MutableStateFlow(Estado(false, false, 7, false, emptySet()))
    val estado: StateFlow<Estado> = _estado

    fun leer(ctx: Context): Estado = Estado(activa(ctx), pausada(ctx), dias(ctx), miniaturas(ctx), excluidas(ctx)).also { _estado.value = it; grabando = it.activa && !it.pausada }

    /** Se puede activar solo con un bloqueo de pantalla (la búsqueda pide la huella o el PIN). */
    fun hayBloqueo(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /** Al final de la presentación: prepara la base cifrada y empieza a recordar. */
    fun activar(ctx: Context, dias: Int) {
        val app = ctx.applicationContext
        MemoriaPantallaBase.preparar(app)
        prefs(app).edit().putBoolean("activa", true).putBoolean("pausada", false).putInt("dias", dias).apply()
        // Las manos de SIOS son quienes leen la pantalla: que estén encendidas.
        Manos.asegurar(app)
        aplicar(app)
    }

    /** Apagarla borra todo lo guardado, las claves y el modelo. */
    fun desactivar(ctx: Context) {
        val app = ctx.applicationContext
        prefs(app).edit().putBoolean("activa", false).putBoolean("pausada", false).apply()
        manejador.post {
            pendientes.clear()
            ultimas.clear()
            huellas.clear()
            MemoriaPantallaBase.borrarTodo(app)
        }
        manejador.post { MemoriaPantallaModelo.borrar(app) }
        aplicar(app)
        Manos.asegurar(app)
    }

    fun pausar(ctx: Context, si: Boolean) {
        val app = ctx.applicationContext
        if (!activa(app)) return
        prefs(app).edit().putBoolean("pausada", si).apply()
        if (si) manejador.post { guardarLote(app) }
        aplicar(app)
    }

    fun ponerDias(ctx: Context, dias: Int) {
        val app = ctx.applicationContext
        prefs(app).edit().putInt("dias", dias).apply()
        leer(app)
        manejador.post { runCatching { MemoriaPantallaBase.caducar(app, dias) } }
    }

    fun ponerMiniaturas(ctx: Context, si: Boolean) {
        prefs(ctx).edit().putBoolean("miniaturas", si).apply()
        leer(ctx)
    }

    fun excluir(ctx: Context, app: String, si: Boolean) {
        val nuevas = excluidas(ctx).toMutableSet().apply { if (si) add(app) else remove(app) }
        prefs(ctx).edit().putStringSet("excluidas", nuevas).apply()
        leer(ctx)
    }

    /** Borrar todo (sin desactivar): base y claves nuevas; se sigue recordando desde ahora. */
    fun borrarTodo(ctx: Context, alTerminar: () -> Unit = {}) {
        val app = ctx.applicationContext
        manejador.post {
            pendientes.clear()
            ultimas.clear()
            huellas.clear()
            MemoriaPantallaBase.borrarTodo(app)
            if (prefs(app).getBoolean("activa", false)) runCatching { MemoriaPantallaBase.preparar(app) }
            alTerminar()
        }
    }

    /** Pone todo como dicen las preferencias: avisos de accesibilidad, notificación y recuadro. */
    private fun aplicar(ctx: Context) {
        leer(ctx)
        Manos.instancia?.let { configurar(it) }
        aviso(ctx)
        runCatching { TileService.requestListeningState(ctx, ComponentName(ctx, MemoriaPantallaTile::class.java)) }
    }

    // ---------- Servicio de accesibilidad ----------

    @Volatile private var grabando = false
    private var receptorPantalla: BroadcastReceiver? = null

    private val manejador: Handler by lazy {
        Handler(HandlerThread("memoria-pantalla", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }.looper)
    }

    /** Las manos se han conectado (arranque, actualización): se ponen los avisos que hagan falta. */
    fun alConectar(s: AccessibilityService) {
        val ctx = s.applicationContext
        leer(ctx)
        configurar(s)
        aviso(ctx)
        if (receptorPantalla == null) {
            receptorPantalla = object : BroadcastReceiver() {
                // Al apagar la pantalla: se guarda lo pendiente y se cierra la base (la clave sale de la memoria).
                override fun onReceive(c: Context, i: Intent) {
                    manejador.post {
                        guardarLote(ctx)
                        MemoriaPantallaBase.cerrar()
                        MemoriaPantallaModelo.liberar()
                    }
                }
            }
            runCatching { ctx.registerReceiver(receptorPantalla, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED) }
        }
    }

    fun alDesconectar(s: AccessibilityService) {
        receptorPantalla?.let { r -> runCatching { s.applicationContext.unregisterReceiver(r) } }
        receptorPantalla = null
        manejador.post { guardarLote(s.applicationContext) }
    }

    /**
     * Solo mientras recuerda se piden los avisos de cambios de contenido y desplazamiento (los que
     * llegan a menudo); si no, las manos se quedan con los de siempre (ventanas, para el portapapeles).
     */
    fun configurar(s: AccessibilityService) {
        val info = s.serviceInfo ?: return
        val base = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED
        val tipos = if (grabando) base or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED else base
        if (info.eventTypes == tipos) return
        info.eventTypes = tipos
        runCatching { s.serviceInfo = info }
    }

    private val programada = AtomicBoolean(false)
    @Volatile private var ultimoEvento = 0L
    @Volatile private var primerEvento = 0L
    private var ultimaLectura = 0L
    @Volatile private var tecladoActual: String? = null

    /** Cada aviso de accesibilidad: solo apunta la hora (llegan muchos; aquí no se lee nada). */
    fun evento(s: AccessibilityService, e: AccessibilityEvent) {
        if (!grabando) return
        val app = e.packageName?.toString() ?: return
        // Lo que cambia solo (reloj, batería, el teclado al escribir) no cuenta como «la pantalla cambió».
        if (app == "com.android.systemui" || app == s.packageName || app == tecladoActual) return
        val ahora = SystemClock.uptimeMillis()
        ultimoEvento = ahora
        if (programada.compareAndSet(false, true)) {
            primerEvento = ahora
            manejador.postDelayed({ revisar(s) }, CALMA)
        }
    }

    private fun revisar(s: AccessibilityService) {
        val ahora = SystemClock.uptimeMillis()
        val calma = ahora - ultimoEvento
        if (calma < CALMA && ahora - primerEvento < ESPERA_MAX) {
            manejador.postDelayed({ revisar(s) }, CALMA - calma)
            return
        }
        val desde = ahora - ultimaLectura
        if (desde < INTERVALO_MIN) {
            manejador.postDelayed({ revisar(s) }, INTERVALO_MIN - desde)
            return
        }
        programada.set(false)
        ultimaLectura = ahora
        if (!grabando) return
        runCatching { capturar(s) }.onFailure { android.util.Log.w("SistemaSios", "Memoria de pantalla", it) }
    }

    // ---------- Leer la pantalla ----------

    private class Lectura(
        val lineas: LinkedHashSet<String>,
        val contrasena: Boolean,
        val incognito: Boolean,
        val zonaImagen: Rect?,
        val proporcionImagen: Float,
    )

    /** Lo último guardado de cada app (sus líneas), para no repetir lo mismo. */
    private val ultimas = LinkedHashMap<String, Set<String>>()
    /** Las huellas rápidas de las últimas imágenes analizadas, para no repetirlas. */
    private val huellas = ArrayDeque<Long>()
    private var ultimaImagen = 0L
    private val imagenesHora = ArrayDeque<Long>()
    /** Apps que ya se sabe que protegen su ventana (FLAG_SECURE) y la última pantalla comprobada. */
    private val protegidas = HashSet<String>()
    private var comprobada: String? = null
    private val pendientes = mutableListOf<MemoriaPantallaBase.Nuevo>()
    private var loteProgramado = false

    private fun capturar(s: AccessibilityService) {
        val ctx = s.applicationContext
        val pm = ctx.getSystemService(PowerManager::class.java)
        val km = ctx.getSystemService(KeyguardManager::class.java)
        // Nunca la pantalla de bloqueo (ni nada mientras el móvil está bloqueado o apagado).
        if (!pm.isInteractive || km.isKeyguardLocked) return
        tecladoActual = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        val ventana = s.windows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION } ?: return
        val raiz = ventana.root ?: return
        val app = raiz.packageName?.toString() ?: return
        if (app in protegidas || MemoriaPantallaPrivacidad.fuera(ctx, app) || app in excluidas(ctx)) return

        val navegador = MemoriaPantallaPrivacidad.navegador(app)
        val lectura = leerPantalla(raiz, ventana, navegador)
        if (lectura.contrasena || lectura.incognito) return
        if (navegador && lectura.lineas.any { MemoriaPantallaPrivacidad.textoIncognito(it) }) return

        val ahora = SystemClock.uptimeMillis()
        val conImagen = lectura.zonaImagen != null && lectura.proporcionImagen >= 0.35f &&
            ahora - ultimaImagen >= INTERVALO_IMAGEN && imagenesHora.count { ahora - it < 3_600_000L } < IMAGENES_POR_HORA
        val conMiniatura = miniaturas(ctx)
        val pantallaNueva = "$app|${ventana.title}"

        // ¿Ventana protegida? Se comprueba al cambiar de pantalla y siempre en los navegadores (su incógnito
        // la protege). El mismo pantallazo sirve para la imagen y la miniatura, si hacen falta.
        var captura: Bitmap? = null
        if (pantallaNueva != comprobada || navegador || conImagen || conMiniatura) {
            when (val p = pantallazo(s, ventana, quierePixeles = conImagen || conMiniatura)) {
                is Pantallazo.Protegida -> { if (!navegador) protegidas += app; return }
                is Pantallazo.Hecho -> captura = p.imagen
                Pantallazo.Fallo -> Unit
            }
            comprobada = pantallaNueva
        }

        try {
            val lineas = lectura.lineas
            val anteriores = ultimas[app]
            val comparables = lineas.map(::paraComparar).toSet()
            val parecido = anteriores != null && jaccard(comparables, anteriores) >= 0.75f

            var ocr = ""
            var vector: FloatArray? = null
            val img = captura
            val zonaImagen = lectura.zonaImagen
            if (conImagen && img != null && zonaImagen != null) {
                // La zona de la imagen, en coordenadas de la ventana capturada.
                val v = Rect().also { ventana.getBoundsInScreen(it) }
                val zona = Rect(zonaImagen).apply { offset(-v.left, -v.top) }
                if (!zona.intersect(0, 0, img.width, img.height)) zona.setEmpty()
                if (zona.width() > 32 && zona.height() > 32) {
                    val recorte = Bitmap.createBitmap(img, zona.left, zona.top, zona.width(), zona.height())
                    val h = dHash(recorte)
                    if (huellas.none { java.lang.Long.bitCount(it xor h) < 8 }) {
                        huellas.addLast(h)
                        while (huellas.size > 12) huellas.removeFirst()
                        ultimaImagen = ahora
                        imagenesHora.addLast(ahora)
                        while (imagenesHora.size > IMAGENES_POR_HORA) imagenesHora.removeFirst()
                        ocr = Ocr.leer(ctx, recorte)
                        vector = runCatching { MemoriaPantallaModelo.huellaImagen(ctx, recorte) }
                            .onFailure { android.util.Log.w("SistemaSios", "Memoria de pantalla: huella", it) }.getOrNull()
                        manejador.postDelayed({ MemoriaPantallaModelo.liberarSiOcioso(120_000) }, 125_000)
                    }
                    if (recorte !== img) recorte.recycle()
                }
            }

            // Nada nuevo: ni texto distinto ni una imagen nueva.
            if ((parecido || lineas.sumOf { it.length } < 20) && ocr.isBlank() && vector == null) return

            val texto = buildString {
                for (l in lineas) { if (length + l.length > MAX_TEXTO) break; append(l).append('\n') }
                if (ocr.isNotBlank()) append(ocr.take(2_000))
            }.trim()
            if (texto.isEmpty() && vector == null) return

            ultimas.remove(app)
            ultimas[app] = comparables
            while (ultimas.size > 40) ultimas.remove(ultimas.keys.first())

            val miniatura = if (conMiniatura) captura?.let(::miniatura) else null
            pendientes += MemoriaPantallaBase.Nuevo(System.currentTimeMillis(), app, ventana.title?.toString(), texto, vector, miniatura)
            if (pendientes.size > 40) pendientes.removeAt(0)
            if (pendientes.size >= LOTE) guardarLote(ctx)
            else if (!loteProgramado) {
                loteProgramado = true
                manejador.postDelayed({ guardarLote(ctx) }, ESPERA_LOTE)
            }
        } finally {
            captura?.recycle()
        }
    }

    private fun guardarLote(ctx: Context) {
        loteProgramado = false
        if (pendientes.isEmpty()) return
        val lote = pendientes.toList()
        // Si el móvil se bloqueó antes de poder abrir la base, se queda en memoria para la próxima vez.
        runCatching { MemoriaPantallaBase.anadir(ctx, lote, dias(ctx)) }.onSuccess { pendientes.removeAll(lote.toSet()) }
            .onFailure { android.util.Log.w("SistemaSios", "Memoria de pantalla: guardar", it) }
    }

    private val sensible = Regex(
        "card|tarjeta|cvv|cvc|iban|\\bpin\\b|otp|codigo|código|code|passw|contraseñ|senha|mot de passe|kennwort|security|seguridad|2fa",
        RegexOption.IGNORE_CASE,
    )

    private fun leerPantalla(raiz: AccessibilityNodeInfo, ventana: AccessibilityWindowInfo, navegador: Boolean): Lectura {
        val lineas = LinkedHashSet<String>()
        var contrasena = false
        var incognito = false
        val pantalla = Rect().also { ventana.getBoundsInScreen(it) }
        val areaPantalla = (pantalla.width().toLong() * pantalla.height()).coerceAtLeast(1)
        var areaImagen = 0L
        var zona: Rect? = null
        var nodos = 0
        var caracteres = 0

        fun ir(n: AccessibilityNodeInfo, profundidad: Int) {
            if (contrasena || nodos > 1_500 || profundidad > 60 || !n.isVisibleToUser) return
            nodos++
            if (n.isPassword) { contrasena = true; return }
            // Lo que la app marca como sensible (Android 14+) no se lee, ni su contenido.
            if (Build.VERSION.SDK_INT >= 34 && n.isAccessibilityDataSensitive) return
            val id = n.viewIdResourceName.orEmpty().lowercase()
            if (navegador && (id.contains("incognito") || id.contains("private_browsing") || id.contains("inprivate"))) { incognito = true; return }
            val clase = n.className?.toString().orEmpty()
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (clase.endsWith("ImageView") || clase == "android.widget.Image") {
                val area = r.width().toLong() * r.height()
                if (area >= areaPantalla / 25) {
                    areaImagen += area
                    zona = zona?.apply { union(r) } ?: Rect(r)
                }
            }
            val editable = n.isEditable
            val leer = !editable || !(
                n.isShowingHintText ||
                    (n.inputType and InputType.TYPE_MASK_CLASS) in setOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE) ||
                    sensible.containsMatchIn(id) || sensible.containsMatchIn(n.hintText?.toString().orEmpty())
                )
            if (leer && caracteres < MAX_TEXTO) {
                val t = n.text?.toString()?.let(::limpiar)
                val d = n.contentDescription?.toString()?.let(::limpiar)?.takeIf { it != t }
                for (x in listOfNotNull(t, d)) if (x.length >= 2 && x.any(Char::isLetterOrDigit) && lineas.add(x)) caracteres += x.length
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { ir(it, profundidad + 1) }
        }
        ir(raiz, 0)
        return Lectura(lineas, contrasena, incognito, zona, (areaImagen.toFloat() / areaPantalla).coerceAtMost(1f))
    }

    private fun limpiar(s: String) = s.replace(Regex("\\s+"), " ").trim().take(1_000)

    /** Para comparar, sin lo que cambia solo (horas, contadores, porcentajes). */
    private fun paraComparar(s: String) = s.lowercase().replace(Regex("\\d+([:.,]\\d+)*\\s*%?"), "#")

    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() && b.isEmpty()) return 1f
        val comunes = a.count { it in b }
        return comunes.toFloat() / (a.size + b.size - comunes).coerceAtLeast(1)
    }

    // ---------- Pantallazo (solo para comprobar, para la imagen y para la miniatura) ----------

    private sealed interface Pantallazo {
        class Hecho(val imagen: Bitmap?) : Pantallazo
        data object Protegida : Pantallazo
        data object Fallo : Pantallazo
    }

    private val enElMismoHilo = Executor { it.run() }

    /**
     * Captura solo la ventana de la app (Android 14+), nunca la pantalla entera: así no entran ni las
     * notificaciones ni otras ventanas. Si la ventana está protegida, Android se niega: es la señal.
     */
    private fun pantallazo(s: AccessibilityService, ventana: AccessibilityWindowInfo, quierePixeles: Boolean): Pantallazo {
        if (Build.VERSION.SDK_INT < 34) return Pantallazo.Fallo
        val listo = CountDownLatch(1)
        var resultado: Pantallazo = Pantallazo.Fallo
        s.takeScreenshotOfWindow(ventana.id, enElMismoHilo, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                val hb = r.hardwareBuffer
                resultado = try {
                    Pantallazo.Hecho(if (quierePixeles) Bitmap.wrapHardwareBuffer(hb, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) else null)
                } finally {
                    hb.close()
                }
                listo.countDown()
            }

            override fun onFailure(codigo: Int) {
                resultado = if (codigo == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) Pantallazo.Protegida else Pantallazo.Fallo
                listo.countDown()
            }
        })
        listo.await(2, TimeUnit.SECONDS)
        return resultado
    }

    /** Huella rápida de 64 bits (diferencias de brillo en 9×8): para no analizar dos veces la misma imagen. */
    private fun dHash(b: Bitmap): Long {
        val p = Bitmap.createScaledBitmap(b, 9, 8, true)
        var h = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            fun brillo(c: Int) = Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114
            h = (h shl 1) or (if (brillo(p.getPixel(x, y)) > brillo(p.getPixel(x + 1, y))) 1L else 0L)
        }
        p.recycle()
        return h
    }

    /** Miniatura pequeña (160 px de ancho, JPEG): va dentro de la base cifrada. */
    private fun miniatura(b: Bitmap): ByteArray {
        val ancho = 160
        val p = Bitmap.createScaledBitmap(b, ancho, (b.height.toLong() * ancho / b.width).toInt().coerceAtLeast(1), true)
        return ByteArrayOutputStream().use { o -> p.compress(Bitmap.CompressFormat.JPEG, 55, o); p.recycle(); o.toByteArray() }
    }

    // ---------- Notificación con «Pausar» ----------

    private fun aviso(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val e = _estado.value
        if (!e.activa) { nm.cancel(CANAL, ID_AVISO); return }
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, ctx.getString(R.string.mp_canal), NotificationManager.IMPORTANCE_LOW).apply {
                    description = ctx.getString(R.string.mp_canal_nota)
                    setShowBadge(false)
                },
            )
        }
        val abrir = PendingIntent.getActivity(
            ctx, 4711, Intent(ctx, com.sios.ajustes.MemoriaPantallaBuscarActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val cambiar = PendingIntent.getBroadcast(
            ctx, 4712, Intent(ctx, Receptor::class.java).putExtra("pausar", !e.pausada),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_memoria_pantalla)
            .setColor(com.sios.ui.Marca.acento(true).toInt())
            .setContentTitle(ctx.getString(if (e.pausada) R.string.mp_aviso_pausada else R.string.mp_aviso_activa))
            .setContentText(ctx.getString(if (e.pausada) R.string.mp_aviso_pausada_nota else R.string.mp_aviso_activa_nota))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(abrir)
            .addAction(0, ctx.getString(if (e.pausada) R.string.mp_reanudar else R.string.mp_pausar), cambiar)
            .build()
        if (nm.areNotificationsEnabled()) nm.notify(CANAL, ID_AVISO, n)
    }

    /** El botón de la notificación: pausar o seguir. */
    class Receptor : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = pausar(c, i.getBooleanExtra("pausar", true))
    }
}

/**
 * Lo que la memoria de pantalla no guarda nunca, decidido por app. Android no tiene una categoría
 * «finanzas» para las apps; se combinan una lista de bancos, pagos, gestores de contraseñas y
 * códigos, palabras del nombre de la app y, al leer, la protección de la ventana (FLAG_SECURE), que
 * ponen casi todas las apps de banca y el incógnito de los navegadores.
 */
object MemoriaPantallaPrivacidad {
    private val SISTEMA = setOf(
        "android", "com.android.systemui", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.credentialmanager", "com.google.android.gms", "com.android.keychain", "com.android.settings.intelligence",
    )

    /** Siempre privados (navegadores que solo tienen modo privado). */
    private val PRIVADOS = setOf("org.mozilla.focus", "org.mozilla.klar", "org.torproject.torbrowser", "org.torproject.torbrowser_alpha")

    private val NAVEGADORES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.fennec_fdroid", "io.github.forkmaintainers.iceraven",
        "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.gx", "com.opera.mini.native",
        "com.sec.android.app.sbrowser", "com.duckduckgo.mobile.android", "com.vivaldi.browser", "org.lineageos.jelly",
        "com.kiwibrowser.browser", "org.bromite.bromite", "org.cromite.cromite", "app.vanadium.browser", "us.spotco.mulch",
        "com.ecosia.android", "com.mi.globalbrowser", "com.huawei.browser", "com.yandex.browser",
    )

    /** Bancos, pagos, cripto, gestores de contraseñas y apps de códigos (prefijos de paquete). */
    private val PREFIJOS = listOf(
        // España
        "com.bbva", "es.bbva", "es.lacaixa", "es.caixabank", "com.caixabank", "com.imaginbank", "es.bancosantander", "com.santander",
        "net.inverline.bancosabadell", "com.bancsabadell", "es.bancsabadell", "es.ibercaja", "com.ibercaja", "es.unicaja", "com.unicaja",
        "es.cajamar", "com.cajamar", "com.kutxabank", "es.kutxabank", "es.evobanco", "com.bankinter", "es.bankinter", "com.openbank",
        "es.openbank", "es.ing.", "com.ingdirect", "www.ingdirect", "es.abanca", "com.abanca", "es.cecabank", "es.bancamarch",
        "es.ruralvia", "com.rsi", "es.laboralkutxa", "com.lkmovil", "es.pibank", "es.cajasiete", "com.globalcaja", "com.bancomediolanum",
        "es.deutschebank", "com.myinvestor", "es.bancoprivado", "es.cajaingenieros", "es.caixaontinyent", "es.caixapopular", "com.bizum",
        // Europa y América
        "com.revolut", "de.number26", "com.n26", "com.wise", "com.transferwise", "com.paypal", "com.venmo", "com.squareup.cash",
        "com.monzo", "com.starlingbank", "co.uk.starlingbank", "com.chase", "com.wf.wellsfargomobile", "com.infonow.bofa",
        "com.bankofamerica", "com.citi", "com.usaa", "com.capitalone", "com.americanexpress", "com.amex", "com.barclays", "uk.co.hsbc",
        "com.hsbc", "com.lloydsbank", "com.grppl.android", "com.rbs", "com.natwest", "de.commerzbanking", "de.dkb", "de.comdirect",
        "com.db.pwcc", "com.db.pbc", "de.sparkasse", "com.starfinanz", "de.fiducia", "de.postbank", "de.consorsbank", "de.ingdiba",
        "de.traderepublic", "fr.creditagricole", "net.bnpparibas", "com.bnpp", "fr.societegenerale", "mobi.societegenerale",
        "com.boursorama", "fr.lcl", "fr.banquepopulaire", "fr.caisseepargne", "com.cic_prod", "fr.creditmutuel", "fr.laposte.lbp",
        "com.latuabancaperandroid", "it.copergmps", "eu.unicredit", "it.unicredit", "it.bnl", "com.fineco", "it.poste.bancoposta",
        "posteitaliane.posteapp", "it.mediolanum", "it.bancomat", "it.satispay", "pt.cgd", "pt.millenniumbcp", "wit.android.bcpbankingapp",
        "pt.novobanco", "pt.santander", "pt.bancobpi", "pt.sibs.android.mbway", "br.com.bb", "com.itau", "br.com.bradesco",
        "com.nu.production", "br.com.intermedium", "com.mercadopago", "ar.com.santander", "com.bancogalicia",
        // Cripto y carteras
        "com.coinbase", "com.binance", "com.kraken", "com.bitpanda", "com.monaco.mobile", "piuk.blockchain", "io.metamask",
        "com.wallet.crypto.trustapp", "com.google.android.apps.walletnfcrel", "com.samsung.android.spay", "com.curve",
        // Contraseñas y códigos
        "com.x8bit.bitwarden", "com.bitwarden", "com.agilebits.onepassword", "com.onepassword", "com.lastpass", "com.dashlane",
        "keepass2android", "com.kunzisoft.keepass", "org.keepassdx", "com.google.android.apps.authenticator2", "com.authy",
        "com.beemdevelopment.aegis", "com.azure.authenticator", "org.fedorahosted.freeotp", "org.liberty.android.freeotpplus",
        "proton.android.pass", "com.proton.pass", "com.nordpass", "com.keepersecurity", "com.enpass", "de.mobilesoftwareag.cryptomator",
    )

    /** Palabras del nombre o del paquete que delatan una app de dinero o de contraseñas. */
    private val PALABRAS = Regex(
        "\\b(bank|banking|banco|banca|bancaria|banque|bancaire|bancoposta|caixa|caja|kutxa|sparkasse|volksbank|raiffeisen|" +
            "wallet|cartera|monedero|crypto|bitcoin|pay|bizum|finanzas|finance|trading|broker|invest|authenticator|password|" +
            "passwords|contraseñas|passwort|senhas|vault|2fa|otp)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val etiquetas = HashMap<String, String>()

    fun navegador(app: String) = app in NAVEGADORES

    /** ¿Se queda fuera siempre (sin que el usuario lo elija)? */
    fun fuera(ctx: Context, app: String): Boolean {
        if (app == ctx.packageName || app in SISTEMA || app in PRIVADOS) return true
        val minusculas = app.lowercase()
        if (PREFIJOS.any { p -> val raiz = p.trimEnd('.'); minusculas == raiz || minusculas.startsWith("$raiz.") }) return true
        if (minusculas.split('.').any { it.contains("bank") || it.contains("banco") || it.contains("banca") }) return true
        return PALABRAS.containsMatchIn(etiqueta(ctx, app))
    }

    fun etiqueta(ctx: Context, app: String): String = synchronized(etiquetas) {
        etiquetas.getOrPut(app) {
            runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(app, 0)).toString() }.getOrDefault(app)
        }
    }

    private val INCOGNITO = Regex(
        "\\b(incognito|incógnito|inkognito|in incognito|navegación privada|navegacion privada|private browsing|private tab|" +
            "inprivate|navigation privée|navigazione anonima|navigazione in incognito|navegação privada|privates surfen|privater modus|modo privado)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Un texto corto que anuncia el modo incógnito («Pestañas de incógnito», «Private browsing»…). */
    fun textoIncognito(linea: String) = linea.length < 80 && INCOGNITO.containsMatchIn(linea)
}
