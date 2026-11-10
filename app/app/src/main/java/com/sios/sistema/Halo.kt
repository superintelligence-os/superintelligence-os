package com.sios.sistema

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowManager
import android.view.animation.PathInterpolator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.sios.R
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Lo que se ve mientras Claude maneja el móvil: el borde de la pantalla iluminado en naranja (un
 * degradado que gira despacio y respira), cada toque como un círculo con brillo que se expande al
 * soltar, los deslizamientos con su estela, el campo donde escribe resaltado, y una píldora arriba
 * para pararlo. Son ventanas de accesibilidad: por encima de todo y sin permisos extra. El borde y
 * los toques no reciben toques (la app de debajo sigue funcionando); la píldora sí.
 * Todo se llama desde el hilo principal.
 */
class Halo(private val ctx: Context, private val onParar: () -> Unit, private val onCaducado: () -> Unit) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val principal = Handler(Looper.getMainLooper())
    private val lienzo = Lienzo(ctx)
    private var lienzoPuesto = false
    private var pildora: View? = null
    private val pildoraVisible = MutableTransitionState(false)
    private val propietario = PropietarioVentana()
    private val apagarSolo = Runnable { apagar(); onCaducado() }

    var encendido = false
        private set

    /** Enciende el borde y la píldora. Si nadie lo apaga, se apaga solo tras un rato sin actividad. */
    fun encender() {
        principal.removeCallbacks(apagarSolo)
        principal.postDelayed(apagarSolo, SIN_ACTIVIDAD)
        if (encendido) return
        encendido = true
        ponerLienzo()
        lienzo.fundir(1f, 520)
        ponerPildora()
        pildoraVisible.targetState = true
    }

    fun apagar() {
        principal.removeCallbacks(apagarSolo)
        if (!encendido) return
        encendido = false
        pildoraVisible.targetState = false
        lienzo.fundir(0f, 700) { if (!encendido) quitarLienzo() }
        principal.postDelayed({ if (!encendido) quitarPildora() }, 600)
    }

    fun toque(x: Float, y: Float, duracion: Long) = lienzo.anadir(listOf(PointF(x, y)), duracion, deslizar = false)

    fun deslizar(desde: PointF, hasta: PointF, duracion: Long) = lienzo.anadir(listOf(desde, hasta), duracion, deslizar = true)

    fun resaltar(zona: Rect) = lienzo.resaltar(RectF(zona))

    /**
     * Mientras Claude hace un gesto, la píldora deja pasar los toques: si tapa justo lo que Claude
     * quiere tocar (la flecha de atrás, por ejemplo), el toque llega a la app de debajo.
     */
    fun atravesable(si: Boolean) {
        val v = pildora ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        val flags = if (si) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (flags == p.flags) return
        p.flags = flags
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** Lo esconde un momento (para que no salga en la captura que pide Claude). */
    fun ocultar(ms: Long) {
        lienzo.alpha = 0f
        pildora?.alpha = 0f
        principal.postDelayed({ lienzo.alpha = 1f; pildora?.alpha = 1f }, ms)
    }

    private fun ponerLienzo() {
        if (lienzoPuesto) return
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "Claude · halo"
        }
        runCatching { wm.addView(lienzo, p); lienzoPuesto = true }
    }

    private fun quitarLienzo() {
        if (!lienzoPuesto) return
        runCatching { wm.removeView(lienzo) }
        lienzoPuesto = false
    }

    private fun ponerPildora() {
        if (pildora != null) return
        val id = ctx.resources.getIdentifier("status_bar_height", "dimen", "android")
        val barra = if (id != 0) ctx.resources.getDimensionPixelSize(id) else (28 * ctx.resources.displayMetrics.density).toInt()
        val altoBarra = (barra / ctx.resources.displayMetrics.density).dp
        val vista = AJusto(ctx, ComposeView(ctx).apply {
            setContent { TemaSios { Pildora(pildoraVisible, altoBarra) { onParar() } } }
        }).apply {
            setViewTreeLifecycleOwner(propietario)
            setViewTreeSavedStateRegistryOwner(propietario)
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            title = "Claude · parar"
        }
        runCatching { wm.addView(vista, p); pildora = vista }
    }

    private fun quitarPildora() {
        pildora?.let { runCatching { wm.removeView(it) } }
        pildora = null
    }

    private companion object {
        const val SIN_ACTIVIDAD = 45_000L
    }
}

/**
 * Píldora de control, metida en la barra de estado como una isla: no tapa nada de la app que Claude
 * está usando. Logo girando, «Claude al mando» y «Parar».
 */
@Composable
private fun Pildora(visible: MutableTransitionState<Boolean>, altoBarra: androidx.compose.ui.unit.Dp, onParar: () -> Unit) {
    androidx.compose.foundation.layout.Box(
        Modifier.height(altoBarra).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible,
            enter = slideInVertically(Muelle.vivo()) { -it } + fadeIn(Muelle.suave()) + scaleIn(Muelle.vivo(), initialScale = 0.85f),
            exit = slideOutVertically(Muelle.suave()) { -it } + fadeOut(Muelle.suave()) + scaleOut(Muelle.suave(), targetScale = 0.85f),
        ) {
            val giro by rememberInfiniteTransition(label = "giro").animateFloat(
                0f, 360f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "logo",
            )
            val pequeno = Texto.Pequeno.copy(fontSize = 12.sp, lineHeight = 14.sp)
            Row(
                Modifier
                    .height((altoBarra - 4.dp).coerceAtMost(30.dp))
                    .clip(Formas.Pastilla)
                    .background(Colores.Lateral)
                    .border(1.dp, Colores.Acento.copy(alpha = 0.55f), Formas.Pastilla)
                    .padding(start = 10.dp, end = 3.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(13.dp).rotate(giro))
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.manos_usando), style = pequeno, color = Colores.Texto, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(10.dp))
                Row(
                    Modifier
                        .fillMaxHeight()
                        .clip(Formas.Pastilla)
                        .background(Colores.Superficie2)
                        .pulsable(onParar)
                        .padding(start = 8.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Iconos.Parar, null, tint = Colores.Texto, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.manos_parar), style = pequeno, color = Colores.Texto, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/**
 * Mide la píldora a su tamaño natural. Android prueba primero un ancho de diálogo en las ventanas
 * que se ajustan a su contenido y Compose, en vez de avisar de que no cabe, parte el texto: así se
 * avisa (MEASURED_STATE_TOO_SMALL) y el sistema le da el ancho que necesita.
 */
private class AJusto(ctx: Context, private val hijo: View) : android.widget.FrameLayout(ctx) {
    init { addView(hijo) }

    override fun onMeasure(ancho: Int, alto: Int) {
        val libre = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        hijo.measure(libre, libre)
        setMeasuredDimension(resolveSizeAndState(hijo.measuredWidth, ancho, 0), resolveSizeAndState(hijo.measuredHeight, alto, 0))
    }

    override fun onLayout(cambio: Boolean, l: Int, t: Int, r: Int, b: Int) = hijo.layout(0, 0, hijo.measuredWidth, hijo.measuredHeight)
}

/** Ciclo de vida mínimo para poder poner Compose en una ventana que no es de una actividad. */
private class PropietarioVentana : LifecycleOwner, SavedStateRegistryOwner {
    private val ciclo = LifecycleRegistry(this)
    private val guardado = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = ciclo
    override val savedStateRegistry get() = guardado.savedStateRegistry

    init {
        guardado.performRestore(null)
        ciclo.currentState = Lifecycle.State.RESUMED
    }
}

/** El dibujo: borde iluminado, toques, estelas y resaltados. Solo se redibuja mientras hay algo vivo. */
private class Lienzo(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    // El color de la IA en uso (sin elegir, el de SIOS).
    private val naranja = com.sios.ui.Marca.acento(true).toInt()
    private val inicio = SystemClock.uptimeMillis()

    private var intensidad = 0f
    private var animador: ValueAnimator? = null
    private val suave = PathInterpolator(0.2f, 0f, 0f, 1f)

    // Borde: un degradado que gira alrededor de la pantalla. Con una IA, tonos de su color; sin
    // elegir, el espectro de SIOS (los colores de las IA, como el punto del símbolo).
    private val colores = if (com.sios.ui.Marca.sinElegir)
        intArrayOf(0xFFD97757.toInt(), 0xFFE9B44C.toInt(), 0xFF4C8DF6.toInt(), 0xFF7B6CF6.toInt(), 0xFFD97757.toInt())
    else intArrayOf(naranja, tono(naranja, 0.45f), tono(naranja, -0.12f), tono(naranja, 0.3f), tono(naranja, -0.2f), naranja)

    /** Mezcla hacia blanco (f > 0) o hacia negro (f < 0). */
    private fun tono(c: Int, f: Float): Int {
        val hacia = if (f > 0) 255 else 0
        val k = kotlin.math.abs(f)
        fun canal(v: Int) = (v + (hacia - v) * k).toInt()
        return android.graphics.Color.rgb(canal(android.graphics.Color.red(c)), canal(android.graphics.Color.green(c)), canal(android.graphics.Color.blue(c)))
    }
    private fun alfa(c: Int, a: Int) = (c and 0xFFFFFF) or (a shl 24)
    private var barrido: Shader? = null
    private val giro = Matrix()
    private val borde = Path()
    private val bordeFino = Path()
    private val brilloAncho = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 34 * d; maskFilter = BlurMaskFilter(30 * d, BlurMaskFilter.Blur.NORMAL) }
    private val brilloCorto = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10 * d; maskFilter = BlurMaskFilter(8 * d, BlurMaskFilter.Blur.NORMAL) }
    private val linea = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d }

    // Toques
    private class Toque(val puntos: List<PointF>, val inicio: Long, val duracion: Long, val deslizar: Boolean)
    private class Resalte(val zona: RectF, val inicio: Long)
    private val toques = mutableListOf<Toque>()
    private val resaltes = mutableListOf<Resalte>()
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aro = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.5f * d; color = naranja }
    private val estela = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 12 * d; color = naranja
        maskFilter = BlurMaskFilter(7 * d, BlurMaskFilter.Blur.NORMAL)
    }
    private val marco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = naranja }
    private val marcoBrillo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 10 * d; color = naranja; maskFilter = BlurMaskFilter(10 * d, BlurMaskFilter.Blur.NORMAL)
    }

    fun fundir(hasta: Float, ms: Long, alTerminar: (() -> Unit)? = null) {
        animador?.cancel()
        animador = ValueAnimator.ofFloat(intensidad, hasta).apply {
            duration = ms
            interpolator = suave
            addUpdateListener { intensidad = it.animatedValue as Float; invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (intensidad == hasta) alTerminar?.invoke() }
            })
            start()
        }
    }

    fun anadir(puntos: List<PointF>, duracion: Long, deslizar: Boolean) {
        toques += Toque(puntos, SystemClock.uptimeMillis(), duracion, deslizar)
        postInvalidateOnAnimation()
    }

    fun resaltar(zona: RectF) {
        resaltes += Resalte(zona, SystemClock.uptimeMillis())
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val radio = rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: (28 * d)
        borde.reset()
        borde.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radio, radio, Path.Direction.CW)
        val m = linea.strokeWidth / 2
        bordeFino.reset()
        bordeFino.addRoundRect(RectF(m, m, w - m, h - m), (radio - m).coerceAtLeast(0f), (radio - m).coerceAtLeast(0f), Path.Direction.CW)
        barrido = SweepGradient(w / 2f, h / 2f, colores, null)
    }

    override fun onDraw(c: Canvas) {
        val ahora = SystemClock.uptimeMillis()
        val t = (ahora - inicio) / 1000f
        if (intensidad > 0.001f) dibujarBorde(c, t)
        toques.removeAll { ahora - it.inicio > it.duracion + SALIDA }
        for (toque in toques) dibujarToque(c, toque, ahora)
        resaltes.removeAll { ahora - it.inicio > RESALTE }
        for (r in resaltes) dibujarResalte(c, r, ahora)
        if (intensidad > 0.001f || toques.isNotEmpty() || resaltes.isNotEmpty()) postInvalidateOnAnimation()
    }

    private fun dibujarBorde(c: Canvas, t: Float) {
        val sombreado = barrido ?: return
        giro.setRotate((t * 36f) % 360f, width / 2f, height / 2f)
        sombreado.setLocalMatrix(giro)
        val respira = 0.78f + 0.22f * sin(t * 2.1f)
        for ((p, a) in listOf(brilloAncho to 0.85f * respira, brilloCorto to 0.95f, linea to 1f)) {
            p.shader = sombreado
            p.alpha = (255 * intensidad * a).toInt().coerceIn(0, 255)
            c.drawPath(if (p === linea) bordeFino else borde, p)
        }
    }

    private fun dibujarToque(c: Canvas, toque: Toque, ahora: Long) {
        val e = (ahora - toque.inicio).toFloat()
        val avance = suave.getInterpolation((e / toque.duracion).coerceIn(0f, 1f))
        val pos = posicion(toque.puntos, avance)
        val aparece = muelle((e / 260f).coerceIn(0f, 1f))
        val salida = ((e - toque.duracion) / SALIDA).coerceIn(0f, 1f)
        val alfa = 1f - suave.getInterpolation(salida)

        if (toque.deslizar && toque.puntos.size > 1) {
            val a = toque.puntos.first()
            estela.alpha = (110 * alfa).toInt()
            c.drawLine(a.x, a.y, pos.x, pos.y, estela)
        }
        // Brillo alrededor
        val radioBrillo = 52 * d * aparece * (1f + 0.25f * salida)
        relleno.shader = RadialGradient(pos.x, pos.y, radioBrillo.coerceAtLeast(1f), intArrayOf(alfa(naranja, 0x99), alfa(naranja, 0x33), alfa(naranja, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        relleno.alpha = (255 * alfa).toInt()
        c.drawCircle(pos.x, pos.y, radioBrillo, relleno)
        // Núcleo: blanco cálido en el centro que se funde en naranja
        val radio = 15 * d * aparece * (1f - 0.4f * salida) * (if (e < toque.duracion && !toque.deslizar) 0.92f + 0.08f * sin(e / 90f) else 1f)
        relleno.shader = RadialGradient(pos.x, pos.y, radio.coerceAtLeast(1f), intArrayOf(0xFFFFF4EE.toInt(), naranja), floatArrayOf(0.15f, 1f), Shader.TileMode.CLAMP)
        relleno.alpha = (235 * alfa).toInt()
        c.drawCircle(pos.x, pos.y, radio, relleno)
        // Onda al soltar
        if (salida > 0f) {
            val o = suave.getInterpolation(salida)
            aro.alpha = (220 * (1f - o)).toInt()
            c.drawCircle(pos.x, pos.y, 15 * d + 46 * d * o, aro)
        }
    }

    private fun dibujarResalte(c: Canvas, r: Resalte, ahora: Long) {
        val e = (ahora - r.inicio) / RESALTE.toFloat()
        val alfa = if (e < 0.2f) e / 0.2f else 1f - suave.getInterpolation(((e - 0.2f) / 0.8f).coerceIn(0f, 1f))
        val z = RectF(r.zona).apply { inset(-6 * d, -6 * d) }
        marcoBrillo.alpha = (150 * alfa).toInt()
        marco.alpha = (255 * alfa).toInt()
        c.drawRoundRect(z, 14 * d, 14 * d, marcoBrillo)
        c.drawRoundRect(z, 14 * d, 14 * d, marco)
    }

    private fun posicion(puntos: List<PointF>, avance: Float): PointF {
        if (puntos.size == 1) return puntos[0]
        val total = puntos.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum().coerceAtLeast(1f)
        var falta = total * avance
        for ((a, b) in puntos.zipWithNext()) {
            val tramo = hypot(b.x - a.x, b.y - a.y)
            if (falta <= tramo) {
                val f = if (tramo == 0f) 0f else falta / tramo
                return PointF(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f)
            }
            falta -= tramo
        }
        return puntos.last()
    }

    /** Muelle amortiguado de 0 a 1: el círculo aparece con un pequeño rebote. */
    private fun muelle(t: Float) = (1 - Math.exp(-6.0 * t) * Math.cos(2 * PI * 1.1 * t)).toFloat()

    private companion object {
        const val SALIDA = 520L
        const val RESALTE = 1100L
    }
}
