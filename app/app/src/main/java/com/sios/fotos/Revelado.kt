package com.sios.fotos

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * El revelado: todos los ajustes y el filtro en un solo sombreador AGSL, en la GPU para la vista
 * previa (cambia en el mismo fotograma que el dedo) y el mismo sombreador en la CPU al guardar a
 * resolución completa, para que lo guardado sea exactamente lo que se veía.
 *
 * El sombreador trabaja en píxeles de la imagen de origen; el encuadre (giros, espejo, enderezar y
 * recorte) es una matriz del lienzo, y la viñeta se calcula en coordenadas de la salida.
 */
@RequiresApi(33)
class Revelado(val imagen: Bitmap) {
    private val sombreador = RuntimeShader(AGSL)
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = sombreador }
    private var hayMascara = false

    init {
        sombreador.setInputShader("imagen", sombra(imagen, imagen.width, imagen.height))
        val blanco = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) }
        sombreador.setInputShader("mascara", sombra(blanco, imagen.width, imagen.height))
        sombreador.setInputShader("difuso", sombra(blanco, imagen.width, imagen.height))
    }

    /** La máscara del sujeto (alfa) y el fondo desenfocado, a cualquier tamaño: se estiran a la imagen. */
    fun ponerFondo(mascara: Bitmap?, difuso: Bitmap?) {
        hayMascara = mascara != null
        if (mascara != null) sombreador.setInputShader("mascara", sombra(mascara, imagen.width, imagen.height))
        if (difuso != null) sombreador.setInputShader("difuso", sombra(difuso, imagen.width, imagen.height))
    }

    private fun sombra(b: Bitmap, w: Int, h: Int) = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        filterMode = BitmapShader.FILTER_MODE_LINEAR
        if (b.width != w || b.height != h) setLocalMatrix(Matrix().apply { setScale(w.toFloat() / b.width, h.toFloat() / b.height) })
    }

    /** Pasa la edición a los uniformes. aSalida: de píxeles de la imagen a píxeles de la salida (para la viñeta). */
    fun preparar(e: Edicion, aSalida: Matrix, anchoSalida: Float, altoSalida: Float, original: Boolean = false) {
        val s = sombreador
        fun v(a: Ajuste) = if (original) 0f else e.valor(a) / 100f
        s.setFloatUniform("paso", max(1f, max(imagen.width, imagen.height) / 2000f))
        s.setFloatUniform("exposicion", v(Ajuste.EXPOSICION))
        s.setFloatUniform("brillo", v(Ajuste.BRILLO))
        s.setFloatUniform("contraste", v(Ajuste.CONTRASTE))
        s.setFloatUniform("luces", v(Ajuste.LUCES))
        s.setFloatUniform("sombras", v(Ajuste.SOMBRAS))
        s.setFloatUniform("saturacion", v(Ajuste.SATURACION))
        s.setFloatUniform("intensidad", v(Ajuste.INTENSIDAD))
        s.setFloatUniform("calidez", v(Ajuste.CALIDEZ))
        s.setFloatUniform("tinte", v(Ajuste.TINTE))
        s.setFloatUniform("nitidez", v(Ajuste.NITIDEZ))
        s.setFloatUniform("vineta", v(Ajuste.VINETA))
        // Viñeta: la posición en la salida, de 0 a 1.
        val m = FloatArray(9).also { aSalida.getValues(it) }
        s.setFloatUniform("salidaX", m[Matrix.MSCALE_X] / anchoSalida, m[Matrix.MSKEW_X] / anchoSalida, m[Matrix.MTRANS_X] / anchoSalida)
        s.setFloatUniform("salidaY", m[Matrix.MSKEW_Y] / altoSalida, m[Matrix.MSCALE_Y] / altoSalida, m[Matrix.MTRANS_Y] / altoSalida)
        s.setFloatUniform("aspecto", anchoSalida / altoSalida)
        val f = if (original) Filtro.NINGUNO else e.filtro
        s.setFloatUniform("filtroR", f.matriz[0], f.matriz[1], f.matriz[2])
        s.setFloatUniform("filtroG", f.matriz[3], f.matriz[4], f.matriz[5])
        s.setFloatUniform("filtroB", f.matriz[6], f.matriz[7], f.matriz[8])
        s.setFloatUniform("filtroContraste", f.contraste)
        s.setFloatUniform("filtroVelo", f.velo)
        s.setFloatUniform("filtroSombras", f.tonoSombras[0], f.tonoSombras[1], f.tonoSombras[2])
        s.setFloatUniform("filtroLuces", f.tonoLuces[0], f.tonoLuces[1], f.tonoLuces[2])
        s.setFloatUniform("filtroMezcla", if (f == Filtro.NINGUNO) 0f else e.intensidadFiltro / 100f)
        val fondo = if (original || !hayMascara) Fondo.ORIGINAL else e.fondo
        s.setFloatUniform("modoFondo", fondo.ordinal.toFloat())
        val c = e.colorFondo
        s.setFloatUniform("colorFondo", ((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f)
        pincel.shader = s
    }

    /**
     * Dibuja la imagen revelada: aLienzo lleva de píxeles de la imagen al lienzo (encuadre y
     * colocación en pantalla). recorte: lo que se ve, en el lienzo (null: todo).
     */
    fun dibujar(lienzo: Canvas, aLienzo: Matrix, recorte: RectF?) {
        lienzo.save()
        if (recorte != null) lienzo.clipRect(recorte)
        lienzo.concat(aLienzo)
        // Un poco más que la imagen: con el zoom de enderezar, los bordes nunca quedan dentro.
        lienzo.drawRect(-2f, -2f, imagen.width + 2f, imagen.height + 2f, pincel)
        lienzo.restore()
    }

    /**
     * Revela a resolución completa: lo que se guarda. En la GPU, como la vista previa (Android no
     * deja usar sombreadores AGSL en un lienzo de software), por trozos de hasta 2048 px para no
     * pedirle a la GPU superficies enormes; los trozos se juntan en un Bitmap normal.
     */
    fun exportar(e: Edicion): Bitmap {
        val g = e.geometria
        val (w, h) = Encuadre.tamSalida(imagen.width, imagen.height, g)
        val m = Encuadre.aSalida(imagen.width, imagen.height, g)
        preparar(e, m, w.toFloat(), h.toFloat())
        // Sobre transparente: con el fondo quitado se guarda así (en PNG).
        val salida = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val lienzo = Canvas(salida)
        val trozo = 2048
        var y = 0
        while (y < h) {
            var x = 0
            val th = min(trozo, h - y)
            while (x < w) {
                val tw = min(trozo, w - x)
                val parte = enGpu(tw, th) { c ->
                    c.translate(-x.toFloat(), -y.toFloat())
                    dibujar(c, m, RectF(0f, 0f, w.toFloat(), h.toFloat()))
                }
                lienzo.drawBitmap(parte, x.toFloat(), y.toFloat(), null)
                parte.recycle()
                x += trozo
            }
            y += trozo
        }
        return salida
    }

    /** Dibuja fuera de pantalla con la GPU (HardwareRenderer → ImageReader) y lo copia a un Bitmap. */
    private fun enGpu(w: Int, h: Int, dibujo: (Canvas) -> Unit): Bitmap {
        val lector = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val nodo = RenderNode("revelado").apply { setPosition(0, 0, w, h) }
        val renderizador = HardwareRenderer()
        try {
            val c = nodo.beginRecording(w, h)
            try { dibujo(c) } finally { nodo.endRecording() }
            renderizador.setSurface(lector.surface)
            renderizador.setContentRoot(nodo)
            renderizador.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            lector.acquireNextImage().use { imagen ->
                // RGBA en memoria es lo mismo que ARGB_8888 de Android (premultiplicado); cada fila
                // puede venir con relleno al final.
                val plano = imagen.planes[0]
                val paso = plano.rowStride / plano.pixelStride
                val origen = plano.buffer
                val datos = java.nio.ByteBuffer.allocateDirect(paso * h * 4)
                origen.rewind()
                datos.put(origen.limit(minOf(origen.capacity(), datos.capacity())) as java.nio.ByteBuffer)
                datos.rewind()
                val ancha = Bitmap.createBitmap(paso, h, Bitmap.Config.ARGB_8888)
                ancha.copyPixelsFromBuffer(datos)
                return if (paso == w) ancha else Bitmap.createBitmap(ancha, 0, 0, w, h).also { ancha.recycle() }
            }
        } finally {
            renderizador.destroy()
            nodo.discardDisplayList()
            lector.close()
        }
    }
}

/**
 * Las cuentas del encuadre. El orden es siempre: espejo → giros de 90° → enderezar (con el zoom
 * justo para cubrir el marco) → recorte (en fracciones del marco).
 */
object Encuadre {
    /** El marco tras los giros de 90°: ancho y alto. */
    fun marco(w: Int, h: Int, g: Geometria): Pair<Int, Int> = if (g.giro % 2 == 1) h to w else w to h

    /** El zoom mínimo para que, al enderezar, la foto girada siga cubriendo todo el marco. */
    fun zoom(w1: Float, h1: Float, grados: Float): Float {
        val t = Math.toRadians(abs(grados).toDouble())
        val c = cos(t).toFloat()
        val s = sin(t).toFloat()
        return max((w1 * c + h1 * s) / w1, (w1 * s + h1 * c) / h1)
    }

    /** De píxeles de la imagen a píxeles del marco (girado y enderezado, sin recortar). */
    fun aMarco(w: Int, h: Int, g: Geometria): Matrix {
        val m = Matrix()
        if (g.espejo) { m.postScale(-1f, 1f); m.postTranslate(w.toFloat(), 0f) }
        when (((g.giro % 4) + 4) % 4) {
            1 -> { m.postRotate(90f); m.postTranslate(h.toFloat(), 0f) }
            2 -> { m.postRotate(180f); m.postTranslate(w.toFloat(), h.toFloat()) }
            3 -> { m.postRotate(270f); m.postTranslate(0f, w.toFloat()) }
        }
        if (g.enderezar != 0f) {
            val (w1, h1) = marco(w, h, g)
            val z = zoom(w1.toFloat(), h1.toFloat(), g.enderezar)
            m.postRotate(g.enderezar, w1 / 2f, h1 / 2f)
            m.postScale(z, z, w1 / 2f, h1 / 2f)
        }
        return m
    }

    /** De píxeles de la imagen a píxeles de la salida (ya recortada). */
    fun aSalida(w: Int, h: Int, g: Geometria): Matrix {
        val (w1, h1) = marco(w, h, g)
        return aMarco(w, h, g).apply { postTranslate(-g.recorte.left * w1, -g.recorte.top * h1) }
    }

    fun tamSalida(w: Int, h: Int, g: Geometria): Pair<Int, Int> {
        val (w1, h1) = marco(w, h, g)
        return max(1, (g.recorte.width() * w1).roundToInt()) to max(1, (g.recorte.height() * h1).roundToInt())
    }
}

/**
 * El sombreador. Valores de los ajustes de −1 a 1 (nitidez y viñeta, de 0 a 1). La exposición y la
 * calidez se aplican en luz lineal (como en una cámara); el resto, sobre el color ya en pantalla.
 */
private const val AGSL = """
uniform shader imagen;
uniform shader mascara;
uniform shader difuso;
uniform float paso;
uniform float exposicion;
uniform float brillo;
uniform float contraste;
uniform float luces;
uniform float sombras;
uniform float saturacion;
uniform float intensidad;
uniform float calidez;
uniform float tinte;
uniform float nitidez;
uniform float vineta;
uniform float3 salidaX;
uniform float3 salidaY;
uniform float aspecto;
uniform float3 filtroR;
uniform float3 filtroG;
uniform float3 filtroB;
uniform float filtroContraste;
uniform float filtroVelo;
uniform float3 filtroSombras;
uniform float3 filtroLuces;
uniform float filtroMezcla;
uniform float modoFondo;
uniform float3 colorFondo;

const float3 LUMA = float3(0.2126, 0.7152, 0.0722);

half4 main(float2 p) {
    float3 c = imagen.eval(p).rgb;
    // Nitidez: máscara de enfoque con los cuatro vecinos.
    if (nitidez > 0.0) {
        float3 m = (imagen.eval(p + float2(paso, 0.0)).rgb + imagen.eval(p - float2(paso, 0.0)).rgb
                  + imagen.eval(p + float2(0.0, paso)).rgb + imagen.eval(p - float2(0.0, paso)).rgb) * 0.25;
        c = clamp(c + (c - m) * nitidez * 2.2, 0.0, 1.0);
    }
    // Fondo desenfocado: se mezcla antes de revelar, para que lleve los mismos ajustes.
    float a = 1.0;
    if (modoFondo > 0.5) {
        a = mascara.eval(p).a;
        if (modoFondo > 2.5) c = mix(difuso.eval(p).rgb, c, a);
    }
    // Exposición y temperatura en luz lineal; la luminancia se conserva al cambiar el color.
    float3 l = pow(max(c, 0.0), float3(2.2));
    l *= exp2(exposicion * 1.6);
    float3 balance = float3(1.0 + calidez * 0.22, 1.0 - tinte * 0.14, 1.0 - calidez * 0.22);
    l *= balance / dot(balance, LUMA);
    c = pow(max(l, 0.0), float3(1.0 / 2.2));
    // Brillo: medios tonos, sin quemar las luces ni lavar los negros.
    c = pow(clamp(c, 0.0, 1.0), float3(exp2(-brillo * 0.6)));
    // Luces y sombras.
    // Se cambia la luminancia y el color se escala con ella (conserva el tono: los cielos
    // recuperados no se vuelven grises). En las sombras, mitad escala y mitad suma, para no
    // disparar el ruido de los negros.
    float y = dot(c, LUMA);
    float enSombra = 1.0 - smoothstep(0.0, 0.55, y);
    float enLuz = smoothstep(0.45, 1.0, y);
    float y2 = y + sombras * 0.22 * enSombra * enSombra * (1.0 - y) + luces * 0.28 * enLuz * enLuz * (luces > 0.0 ? (1.0 - y) * 1.6 : y);
    y2 = clamp(y2, 0.0, 1.0);
    float k = y2 / max(y, 0.04);
    c = mix(c * k, c + (y2 - y), enSombra * 0.5);
    c = clamp(c, 0.0, 1.0);
    // Contraste: curva en S al subir, hacia el gris al bajar.
    if (contraste > 0.0) {
        c = mix(c, c * c * (3.0 - 2.0 * c), contraste * 0.85);
    } else {
        c = mix(c, 0.5 + (c - 0.5) * 0.55, -contraste);
    }
    // Saturación e intensidad (esta, sobre todo en los colores apagados).
    y = dot(c, LUMA);
    c = mix(float3(y), c, 1.0 + saturacion);
    float s = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    c = mix(float3(y), c, 1.0 + intensidad * (1.0 - s) * 0.9);
    c = clamp(c, 0.0, 1.0);
    // Filtro.
    if (filtroMezcla > 0.0) {
        float3 f = float3(dot(filtroR, c), dot(filtroG, c), dot(filtroB, c));
        f = (f - 0.5) * filtroContraste + 0.5;
        f = f * (1.0 - filtroVelo) + filtroVelo;
        float yf = clamp(dot(f, LUMA), 0.0, 1.0);
        f += filtroSombras * (1.0 - yf) * (1.0 - yf) + filtroLuces * yf * yf;
        c = mix(c, clamp(f, 0.0, 1.0), filtroMezcla);
    }
    // Viñeta, en la foto ya recortada.
    if (vineta > 0.0) {
        float2 q = float2(dot(salidaX, float3(p, 1.0)), dot(salidaY, float3(p, 1.0))) - 0.5;
        q.x *= mix(1.0, aspecto, 0.5);
        float d = length(q) * 1.5;
        c *= 1.0 - vineta * 0.62 * smoothstep(0.4, 1.2, d);
    }
    if (modoFondo > 1.5 && modoFondo < 2.5) c = mix(colorFondo, c, a);
    if (modoFondo > 0.5 && modoFondo < 1.5) return half4(half3(c * a), half(a));
    return half4(half3(c), 1.0);
}
"""
