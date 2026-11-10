package com.sios.sistema

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.sios.R
import com.sios.ui.BotonIcono
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * El escáner de documentos de SIOS, sin Google: la cámara busca el papel mientras se encuadra, la foto
 * se recorta por sus bordes (con las cuatro esquinas a mano y una lupa para afinarlas), se endereza y
 * se limpia como un escaneado. Varias páginas; al guardar pasan a [Documentos] (PDF con nombre claro,
 * gasto y fechas). Todo en el móvil: [EscanerNucleo] hace las cuentas.
 */
class EscanerActivity : ComponentActivity(), AccionesEscaner {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private val estado = EstadoEscaner()
    private var fotoPx: Pixeles? = null
    private var recta: Pixeles? = null
    private val captura by lazy {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TemaSios {
                val ambito = rememberCoroutineScope()
                LaunchedEffect(Unit) { this@EscanerActivity.ambito = ambito }
                val importar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) importarDe(uri) }
                LaunchedEffect(Unit) { this@EscanerActivity.abrirGaleria = { importar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }
                PantallaEscaner(estado, this) { VistaCamara(estado, captura) }
            }
        }
    }

    private var ambito: kotlinx.coroutines.CoroutineScope? = null
    private var abrirGaleria: () -> Unit = {}

    private fun enSegundoPlano(bloque: suspend () -> Unit) {
        val a = ambito ?: return
        a.launch {
            estado.trabajando = true
            try { bloque() } catch (e: Throwable) { android.util.Log.w("SistemaSios", "Escáner", e) } finally { estado.trabajando = false }
        }
    }

    // ---------- Acciones ----------

    override fun capturar() {
        if (estado.trabajando || estado.paginas.size >= EstadoEscaner.MAX_PAGINAS) return
        estado.disparando = true
        captura.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(img: ImageProxy) {
                val grados = img.imageInfo.rotationDegrees
                val b = runCatching { img.toBitmap() }.getOrNull()
                img.close()
                estado.disparando = false
                if (b != null) prepararFoto { girada(reducida(b, LADO_FOTO), grados) }
            }
            override fun onError(e: ImageCaptureException) { estado.disparando = false }
        })
    }

    override fun importar() = abrirGaleria()

    private fun importarDe(uri: android.net.Uri) = prepararFoto {
        android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
            val lado = max(info.size.width, info.size.height)
            if (lado > LADO_FOTO) dec.setTargetSampleSize(lado / LADO_FOTO)
            dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }.let { reducida(it, LADO_FOTO) }
    }

    /** La foto, lista para recortar: se buscan los bordes del papel (o un marco por defecto). */
    private fun prepararFoto(obtener: () -> Bitmap) = enSegundoPlano {
        val (foto, px, q) = withContext(Dispatchers.Default) {
            val f = obtener()
            val p = pixeles(f)
            Triple(f, p, EscanerNucleo.detectar(p))
        }
        fotoPx = px
        estado.foto = foto
        estado.detectada = q != null
        estado.esquinas = esquinas(q ?: EscanerNucleo.marcoPorDefecto(px.ancho, px.alto))
        estado.paso = EstadoEscaner.Paso.Ajuste
    }

    override fun repetir() {
        estado.paso = EstadoEscaner.Paso.Camara
        estado.foto = null
        fotoPx = null
    }

    override fun todaLaFoto() {
        val f = fotoPx ?: return
        if (estado.todaLaFoto) {
            enSegundoPlano {
                val q = withContext(Dispatchers.Default) { EscanerNucleo.detectar(f) }
                estado.esquinas = esquinas(q ?: EscanerNucleo.marcoPorDefecto(f.ancho, f.alto))
                estado.detectada = q != null
            }
        } else estado.esquinas = esquinas(floatArrayOf(0f, 0f, f.ancho.toFloat(), 0f, f.ancho.toFloat(), f.alto.toFloat(), 0f, f.alto.toFloat()))
        estado.todaLaFoto = !estado.todaLaFoto
    }

    override fun moverEsquina(i: Int, p: Offset) {
        val f = fotoPx ?: return
        estado.esquinas = estado.esquinas.toMutableList().also { it[i] = Offset(p.x.coerceIn(0f, f.ancho.toFloat()), p.y.coerceIn(0f, f.alto.toFloat())) }
        estado.todaLaFoto = false
    }

    override fun recortar() {
        val f = fotoPx ?: return
        val q = FloatArray(8) { k -> estado.esquinas[k / 2].let { if (k % 2 == 0) it.x else it.y } }
        if (!EscanerNucleo.convexo(q)) return
        enSegundoPlano {
            recta = withContext(Dispatchers.Default) { EscanerNucleo.enderezar(f, q, LADO_PAGINA) }
            estado.giro = 0
            vistaPrevia()
            estado.paso = EstadoEscaner.Paso.Pagina
        }
    }

    override fun filtro(f: EscanerNucleo.Filtro) {
        if (estado.filtro == f) return
        estado.filtro = f
        enSegundoPlano { vistaPrevia() }
    }

    override fun girar() {
        estado.giro = (estado.giro + 1) % 4
        enSegundoPlano { vistaPrevia() }
    }

    /** La página como quedará, en pequeño (para verla al momento). */
    private suspend fun vistaPrevia() {
        val r = recta ?: return
        val filtro = estado.filtro
        val giro = estado.giro
        estado.vista = withContext(Dispatchers.Default) {
            val e = min(1f, LADO_VISTA.toFloat() / max(r.ancho, r.alto))
            val pequena = Pixeles((r.ancho * e).roundToInt().coerceAtLeast(1), (r.alto * e).roundToInt().coerceAtLeast(1), IntArray(0))
                .let { Pixeles(it.ancho, it.alto, OcrMotor.reducir(r, it.ancho, it.alto)) }
            mapaDeBits(girar(EscanerNucleo.mejorar(pequena, filtro), giro))
        }
    }

    override fun otraPagina() = enSegundoPlano {
        confirmarPagina()
        estado.paso = EstadoEscaner.Paso.Camara
    }

    override fun guardar() = enSegundoPlano {
        if (estado.paso == EstadoEscaner.Paso.Pagina) confirmarPagina()
        val archivos = estado.paginas.map { it.archivo }
        if (archivos.isEmpty()) return@enSegundoPlano
        Documentos.desdePaginas(this, archivos)
        android.widget.Toast.makeText(this, getString(R.string.doc_escaneando), android.widget.Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    /** La página actual, a tamaño completo, con su filtro y su giro, a un JPEG de la caché. */
    private suspend fun confirmarPagina() {
        val r = recta ?: return
        val filtro = estado.filtro
        val giro = estado.giro
        val pagina = withContext(Dispatchers.Default) {
            val b = mapaDeBits(girar(EscanerNucleo.mejorar(r, filtro), giro))
            val dir = File(cacheDir, "escaner").apply { mkdirs() }
            val f = File(dir, "pagina-${SystemClock.elapsedRealtimeNanos()}.jpg")
            f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            val alto = 240
            val mini = Bitmap.createScaledBitmap(b, max(1, b.width * alto / b.height), alto, true)
            b.recycle()
            PaginaHecha(f, mini)
        }
        estado.paginas += pagina
        recta = null
        estado.vista = null
        estado.foto = null
        fotoPx = null
    }

    override fun quitar(p: PaginaHecha) {
        estado.paginas.remove(p)
        p.archivo.delete()
    }

    override fun cerrar() {
        if (estado.paginas.isNotEmpty() && !estado.confirmarSalida) { estado.confirmarSalida = true; return }
        estado.paginas.forEach { it.archivo.delete() }
        finish()
    }

    override fun seguir() { estado.confirmarSalida = false }

    override fun atras() {
        when {
            estado.confirmarSalida -> seguir()
            estado.paso == EstadoEscaner.Paso.Pagina -> estado.paso = EstadoEscaner.Paso.Ajuste
            estado.paso == EstadoEscaner.Paso.Ajuste -> repetir()
            else -> cerrar()
        }
    }

    override fun finish() {
        super.finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    private fun esquinas(q: FloatArray) = List(4) { Offset(q[2 * it], q[2 * it + 1]) }

    companion object {
        /** Lado largo de la foto con la que se trabaja (12 MP serían 48 MB por copia). */
        private const val LADO_FOTO = 2800
        /** Lado largo de la página guardada (A4 a unos 200 ppp). */
        private const val LADO_PAGINA = 2400
        private const val LADO_VISTA = 1400

        fun reducida(b: Bitmap, lado: Int): Bitmap {
            val e = lado.toFloat() / max(b.width, b.height)
            if (e >= 1f) return b
            return Bitmap.createScaledBitmap(b, (b.width * e).roundToInt(), (b.height * e).roundToInt(), true).also { if (it !== b) b.recycle() }
        }

        fun girada(b: Bitmap, grados: Int): Bitmap =
            if (grados == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(grados.toFloat()) }, true)

        fun pixeles(b: Bitmap): Pixeles {
            val f = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
            val px = IntArray(f.width * f.height)
            f.getPixels(px, 0, f.width, 0, 0, f.width, f.height)
            return Pixeles(f.width, f.height, px)
        }

        fun mapaDeBits(p: Pixeles): Bitmap = Bitmap.createBitmap(p.px, p.ancho, p.alto, Bitmap.Config.ARGB_8888)

        fun girar(p: Pixeles, veces: Int): Pixeles { var r = p; repeat(veces) { r = EscanerNucleo.girar(r) }; return r }
    }
}

/** Lo que se puede hacer en el escáner (la actividad; en las pruebas, nada). */
interface AccionesEscaner {
    fun capturar()
    fun importar()
    fun repetir()
    fun todaLaFoto()
    fun moverEsquina(i: Int, p: Offset)
    fun recortar()
    fun filtro(f: EscanerNucleo.Filtro)
    fun girar()
    fun otraPagina()
    fun guardar()
    fun quitar(p: PaginaHecha)
    fun cerrar()
    fun seguir()
    fun atras()
}

class PaginaHecha(val archivo: File, val miniatura: Bitmap)

class EstadoEscaner {
    enum class Paso { Camara, Ajuste, Pagina }

    var paso by mutableStateOf(Paso.Camara)
    /** Mientras se endereza, se limpia o se guarda. */
    var trabajando by mutableStateOf(false)
    var disparando by mutableStateOf(false)
    /** El papel que ve la cámara ahora mismo (esquinas de 0 a 1 sobre la imagen), o null. */
    var vivo by mutableStateOf<List<Offset>?>(null)
    /** La foto hecha, sus esquinas (en píxeles de la foto) y si se encontraron solas. */
    var foto by mutableStateOf<Bitmap?>(null)
    var esquinas by mutableStateOf(listOf<Offset>())
    var detectada by mutableStateOf(false)
    var todaLaFoto by mutableStateOf(false)
    /** La esquina que se está arrastrando (con la lupa), o null. */
    var esquinaActiva by mutableStateOf<Int?>(null)
    /** La página recortada, como quedará. */
    var vista by mutableStateOf<Bitmap?>(null)
    var filtro by mutableStateOf(EscanerNucleo.Filtro.Documento)
    var giro by mutableIntStateOf(0)
    val paginas = mutableStateListOf<PaginaHecha>()
    var confirmarSalida by mutableStateOf(false)

    companion object { const val MAX_PAGINAS = 20 }
}

// ---------- Cámara (con la búsqueda del papel en directo) ----------

@Composable
private fun VistaCamara(estado: EstadoEscaner, captura: ImageCapture) {
    val ctx = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    var permiso by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permiso = it }
    LaunchedEffect(Unit) { if (!permiso) pedir.launch(Manifest.permission.CAMERA) }
    if (!permiso) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.esc_sin_permiso), style = Texto.Pequeno, color = Colores.Texto2, textAlign = TextAlign.Center)
        }
        return
    }
    val vista = remember {
        PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
    }
    AndroidView({ vista }, Modifier.fillMaxSize())
    DisposableEffect(ciclo) {
        val hilo = Executors.newSingleThreadExecutor()
        val futuro = ProcessCameraProvider.getInstance(ctx)
        val cuatroTercios = ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        // Para buscar el papel basta una imagen pequeña: rápido y sin gastar batería.
        val analisis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        var ultima = 0L
        var seguidas = 0
        analisis.setAnalyzer(hilo) { img ->
            try {
                val ahora = SystemClock.uptimeMillis()
                if (ahora - ultima < 120 || estado.paso != EstadoEscaner.Paso.Camara) return@setAnalyzer
                ultima = ahora
                val b = EscanerActivity.girada(EscanerActivity.reducida(img.toBitmap(), 360), img.imageInfo.rotationDegrees)
                val q = EscanerNucleo.detectar(EscanerActivity.pixeles(b), afinar = false)
                // Se enseña tras dos imágenes seguidas con papel (sin parpadeos) y se quita tras tres sin él.
                seguidas = if (q != null) max(1, seguidas + 1) else min(-1, seguidas - 1)
                val nuevo = q?.let { List(4) { i -> Offset(it[2 * i] / b.width, it[2 * i + 1] / b.height) } }
                ContextCompat.getMainExecutor(ctx).execute {
                    if (nuevo != null && seguidas >= 2) estado.vivo = nuevo
                    else if (nuevo == null && seguidas <= -3) estado.vivo = null
                }
                b.recycle()
            } catch (_: Throwable) {
            } finally {
                img.close()
            }
        }
        futuro.addListener({
            val proveedor = futuro.get()
            val previa = Preview.Builder().setResolutionSelector(cuatroTercios.build()).build().also { it.surfaceProvider = vista.surfaceProvider }
            runCatching {
                proveedor.unbindAll()
                proveedor.bindToLifecycle(ciclo, CameraSelector.DEFAULT_BACK_CAMERA, previa, captura, analisis)
            }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            runCatching { futuro.get().unbindAll() }
            hilo.shutdown()
        }
    }
}

// ---------- Pantallas ----------

@Composable
internal fun PantallaEscaner(estado: EstadoEscaner, acciones: AccionesEscaner, camara: @Composable () -> Unit) {
    BackHandler { acciones.atras() }
    Box(Modifier.fillMaxSize().background(Colores.Fondo)) {
        AnimatedContent(
            estado.paso,
            transitionSpec = {
                (fadeIn(Muelle.suave()) + scaleIn(Muelle.suave(), initialScale = 0.97f)) togetherWith
                    (fadeOut(Muelle.suave()) + scaleOut(Muelle.suave(), targetScale = 1.02f))
            },
            label = "paso",
        ) { paso ->
            when (paso) {
                EstadoEscaner.Paso.Camara -> PasoCamara(estado, acciones, camara)
                EstadoEscaner.Paso.Ajuste -> PasoAjuste(estado, acciones)
                EstadoEscaner.Paso.Pagina -> PasoPagina(estado, acciones)
            }
        }
        // ¿Descartar lo escaneado? Tarjeta abajo, con muelle.
        AnimatedVisibility(
            estado.confirmarSalida,
            enter = fadeIn(Muelle.suave()), exit = fadeOut(Muelle.suave()),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).pulsable { acciones.seguir() })
        }
        AnimatedVisibility(
            estado.confirmarSalida,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(Muelle.suave()) + scaleIn(Muelle.vivo(), initialScale = 0.92f),
            exit = fadeOut(Muelle.suave()) + scaleOut(Muelle.suave(), targetScale = 0.95f),
        ) {
            Column(
                Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie)
                    .border(1.dp, Colores.Borde, Formas.Tarjeta).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.esc_descartar_titulo), style = Texto.Subtitulo, color = Colores.Texto)
                Text(stringResource(R.string.esc_descartar_texto, estado.paginas.size), style = Texto.Pequeno, color = Colores.Texto2)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotonSecundario(stringResource(R.string.esc_descartar), Modifier.weight(1f)) { acciones.cerrar() }
                    BotonPrimario(stringResource(R.string.esc_seguir), Modifier.weight(1f)) { acciones.seguir() }
                }
            }
        }
    }
}

@Composable
private fun Cabecera(titulo: String, acciones: AccionesEscaner, derecha: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotonIcono(Iconos.Cruz, stringResource(R.string.comun_cerrar), tam = 42.dp, color = Colores.Texto, fondo = Colores.Superficie2) { acciones.cerrar() }
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier.clip(Formas.Pastilla).background(Colores.Superficie2).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(IconosEscaner.Escaner, null, tint = Colores.Acento, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(titulo, style = Texto.Pequeno, color = Colores.Texto)
        }
        Spacer(Modifier.weight(1f))
        derecha()
    }
}

@Composable
private fun PasoCamara(estado: EstadoEscaner, acciones: AccionesEscaner, camara: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Cabecera(stringResource(R.string.esc_titulo), acciones)
        // El visor, 3:4 como la foto: lo que se ve es lo que se escanea.
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).clip(RoundedCornerShape(22.dp)).background(Color.Black)) {
                camara()
                MarcoVivo(estado.vivo)
                // Pista: qué hacer, o que ya lo tiene.
                val texto = if (estado.vivo != null) R.string.esc_detectado else R.string.esc_encuadra
                Box(Modifier.align(Alignment.BottomCenter).padding(14.dp)) {
                    AnimatedContent(texto, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "pista") { t ->
                        Row(
                            Modifier.clip(Formas.Pastilla).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (t == R.string.esc_detectado) {
                                Icon(Iconos.Hecho, null, tint = Colores.Acento, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(stringResource(t), style = Texto.Pequeno, color = Color.White)
                        }
                    }
                }
            }
        }
        // Mandos: galería · disparador · páginas hechas (y guardar).
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BotonIcono(IconosEscaner.Galeria, stringResource(R.string.esc_importar), tam = 52.dp, color = Colores.Texto, fondo = Colores.Superficie2) { acciones.importar() }
            }
            Disparador(estado.disparando || estado.trabajando) { acciones.capturar() }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                androidx.compose.animation.AnimatedVisibility(
                    estado.paginas.isNotEmpty(),
                    enter = fadeIn(Muelle.suave()) + scaleIn(Muelle.vivo(), initialScale = 0.8f),
                    exit = fadeOut(Muelle.suave()) + scaleOut(Muelle.suave(), targetScale = 0.8f),
                ) {
                    estado.paginas.lastOrNull()?.let { p -> PilaPaginas(p.miniatura, estado.paginas.size) { acciones.guardar() } }
                }
            }
        }
    }
}

/** Las páginas hechas: la última en miniatura con el número, y «Guardar». */
@Composable
private fun PilaPaginas(miniatura: Bitmap, n: Int, onClick: () -> Unit) {
    Row(
        Modifier.clip(Formas.Pastilla).background(Colores.Acento).pulsable(onClick).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            miniatura.asImageBitmap(), null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 30.dp, height = 40.dp).clip(RoundedCornerShape(6.dp)).background(Color.White),
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.esc_guardar_n, n), style = Texto.Pequeno, color = Colores.SobreAcento)
    }
}

@Composable
private fun Disparador(ocupado: Boolean, onClick: () -> Unit) {
    val escala by animateFloatAsState(if (ocupado) 0.88f else 1f, Muelle.vivo(), label = "disparo")
    Box(
        Modifier.size(78.dp).scale(escala).clip(CircleShape).border(4.dp, Colores.Texto, CircleShape).padding(8.dp)
            .clip(CircleShape).background(Colores.Acento).pulsable { if (!ocupado) onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(IconosEscaner.Escaner, stringResource(R.string.esc_disparar), tint = Colores.SobreAcento, modifier = Modifier.size(28.dp))
    }
}

/** El papel encontrado en directo: el contorno sigue al papel con un muelle, sin saltos. */
@Composable
private fun MarcoVivo(vivo: List<Offset>?) {
    // El último papel visto se recuerda (sin recomponer) para que el contorno se desvanezca en su sitio.
    val ultimo = remember { arrayOfNulls<List<Offset>>(1) }
    if (vivo != null) ultimo[0] = vivo
    val visible by animateFloatAsState(if (vivo != null) 1f else 0f, Muelle.suave(), label = "marco")
    val q = ultimo[0] ?: return
    val p = List(4) { i -> animateOffsetAsState(q[i], Muelle.vivo(), label = "esquina$i").value }
    val acento = Colores.Acento
    Canvas(Modifier.fillMaxSize().alpha(visible)) {
        val pts = p.map { Offset(it.x * size.width, it.y * size.height) }
        val camino = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1..3) lineTo(pts[i].x, pts[i].y); close() }
        drawPath(camino, acento.copy(alpha = 0.18f))
        drawPath(camino, acento, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (c in pts) drawCircle(acento, 5.dp.toPx(), c)
    }
}

// ---------- Ajuste de las esquinas ----------

@Composable
private fun PasoAjuste(estado: EstadoEscaner, acciones: AccionesEscaner) {
    val foto = estado.foto ?: return
    val imagen = remember(foto) { foto.asImageBitmap() }
    val esquinas = estado.esquinas
    val valido = esquinas.size == 4 && EscanerNucleo.convexo(FloatArray(8) { esquinas[it / 2].let { o -> if (it % 2 == 0) o.x else o.y } })
    Column(Modifier.fillMaxSize()) {
        Cabecera(stringResource(R.string.esc_ajusta_titulo), acciones) {
            // Toda la foto (sin recortar) o volver a buscar el papel.
            AnimatedContent(estado.todaLaFoto, transitionSpec = { (fadeIn(Muelle.suave()) + scaleIn(Muelle.vivo(), initialScale = 0.8f)) togetherWith fadeOut(Muelle.suave()) }, label = "toda") { toda ->
                BotonIcono(
                    if (toda) IconosEscaner.Escaner else IconosEscaner.Ampliar, stringResource(if (toda) R.string.esc_detectar else R.string.esc_toda),
                    tam = 42.dp, color = Colores.Texto, fondo = Colores.Superficie2,
                ) { acciones.todaLaFoto() }
            }
        }
        Text(
            stringResource(if (!valido) R.string.esc_forma_rara else if (estado.detectada || estado.todaLaFoto) R.string.esc_ajusta else R.string.esc_no_encontrado),
            style = Texto.Pequeno, color = if (valido) Colores.Texto2 else Colores.Rojo, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 4.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            EditorEsquinas(imagen, esquinas, estado.esquinaActiva, { estado.esquinaActiva = it }) { i, p -> acciones.moverEsquina(i, p) }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            BotonSecundario(stringResource(R.string.esc_repetir), Modifier.weight(1f)) { acciones.repetir() }
            val alfa by animateFloatAsState(if (valido && !estado.trabajando) 1f else 0.45f, Muelle.suave(), label = "recortar")
            BotonPrimario(stringResource(R.string.esc_recortar), Modifier.weight(1f).alpha(alfa)) { if (valido) acciones.recortar() }
        }
    }
}

/**
 * La foto con el cuadrilátero encima: fuera del papel, en penumbra; las esquinas se arrastran (la más
 * cercana al dedo) y, mientras, una lupa enseña el detalle para dejarla justo en el borde.
 */
@Composable
internal fun EditorEsquinas(imagen: ImageBitmap, esquinas: List<Offset>, activa: Int?, onActiva: (Int?) -> Unit, onMover: (Int, Offset) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val densidad = LocalDensity.current
        val anchoPx = with(densidad) { maxWidth.toPx() }
        val altoPx = with(densidad) { maxHeight.toPx() }
        val escala = min(anchoPx / imagen.width, altoPx / imagen.height)
        val iw = imagen.width * escala; val ih = imagen.height * escala
        val origen = Offset((anchoPx - iw) / 2, (altoPx - ih) / 2)
        fun aPantalla(p: Offset) = origen + p * escala
        fun aImagen(p: Offset) = (p - origen) / escala

        var dedo by remember { mutableStateOf(Offset.Zero) }
        // El gesto vive mientras no cambie el encaje de la foto: lee siempre lo último.
        val esquinasAhora by rememberUpdatedState(esquinas)
        val alActiva by rememberUpdatedState(onActiva)
        val alMover by rememberUpdatedState(onMover)
        val acento = Colores.Acento
        val radioToque = with(densidad) { 48.dp.toPx() }
        val tamLupa = with(densidad) { 116.dp.toPx() }
        val margen = with(densidad) { 24.dp.toPx() }

        Box(
            Modifier.fillMaxSize().pointerInput(escala, origen) {
                var indice: Int? = null
                detectDragGestures(
                    onDragStart = { p ->
                        val l = esquinasAhora
                        if (l.size != 4) return@detectDragGestures
                        val (i, d) = l.mapIndexed { i, e -> i to (aPantalla(e) - p).getDistance() }.minBy { it.second }
                        if (d < radioToque * 1.5f) { indice = i; alActiva(i); dedo = aPantalla(l[i]) }
                    },
                    onDragEnd = { indice = null; alActiva(null) },
                    onDragCancel = { indice = null; alActiva(null) },
                    onDrag = { cambio, delta ->
                        val i = indice ?: return@detectDragGestures
                        cambio.consume()
                        dedo += delta
                        alMover(i, aImagen(dedo))
                    },
                )
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val marco = Rect(origen, androidx.compose.ui.geometry.Size(iw, ih))
                val redondeo = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(marco, androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()))) }
                val pts = esquinas.map(::aPantalla)
                val papel = Path().apply { if (pts.size == 4) { moveTo(pts[0].x, pts[0].y); for (i in 1..3) lineTo(pts[i].x, pts[i].y); close() } }
                clipPath(redondeo) {
                    drawImage(imagen, IntOffset.Zero, IntSize(imagen.width, imagen.height), IntOffset(origen.x.roundToInt(), origen.y.roundToInt()),
                        IntSize(iw.roundToInt(), ih.roundToInt()), filterQuality = FilterQuality.Medium)
                    // Penumbra fuera del papel.
                    if (pts.size == 4) drawPath(Path().apply { fillType = PathFillType.EvenOdd; addRect(marco); addPath(papel) }, Color.Black.copy(alpha = 0.5f))
                }
                if (pts.size == 4) drawPath(papel, acento, style = Stroke(2.5.dp.toPx(), join = StrokeJoin.Round))
            }
            // Las cuatro asas, que crecen al cogerlas.
            esquinas.forEachIndexed { i, e ->
                val p = aPantalla(e)
                val crece by animateFloatAsState(if (activa == i) 1.3f else 1f, Muelle.vivo(), label = "asa$i")
                Box(
                    Modifier.offset { IntOffset((p.x - with(densidad) { 13.dp.toPx() }).roundToInt(), (p.y - with(densidad) { 13.dp.toPx() }).roundToInt()) }
                        .size(26.dp).scale(crece).clip(CircleShape).background(Color.White).border(3.dp, acento, CircleShape),
                )
            }
            // La lupa: arriba del dedo (o abajo si no cabe), con la esquina en el centro.
            val i = activa
            val visible by animateFloatAsState(if (i != null) 1f else 0f, Muelle.vivo(), label = "lupa")
            val recordada = remember { intArrayOf(0) }
            if (i != null) recordada[0] = i
            val ultimaEsquina = recordada[0]
            if (visible > 0.01f && esquinas.size == 4) {
                val centro = aPantalla(esquinas[ultimaEsquina])
                val arriba = centro.y - tamLupa - margen * 2 > 0
                val lupaPos = Offset(
                    (centro.x - tamLupa / 2).coerceIn(0f, anchoPx - tamLupa),
                    if (arriba) centro.y - tamLupa - margen * 2 else (centro.y + margen * 2).coerceAtMost(altoPx - tamLupa),
                )
                Lupa(imagen, esquinas, ultimaEsquina, escala * 3f, Modifier.offset { IntOffset(lupaPos.x.roundToInt(), lupaPos.y.roundToInt()) }
                    .size(with(densidad) { tamLupa.toDp() }).scale(0.6f + 0.4f * visible).alpha(visible))
            }
        }
    }
}

@Composable
private fun Lupa(imagen: ImageBitmap, esquinas: List<Offset>, i: Int, zoom: Float, modifier: Modifier) {
    val acento = Colores.Acento
    val borde = Colores.Texto
    Canvas(modifier.clip(CircleShape).background(Color.Black).border(3.dp, borde, CircleShape)) {
        val c = esquinas[i]
        val mitad = size.width / 2
        fun aLupa(p: Offset) = Offset(mitad, mitad) + (p - c) * zoom
        val circulo = Path().apply { addOval(Rect(Offset.Zero, size)) }
        clipPath(circulo) {
            val desde = aLupa(Offset.Zero)
            drawImage(imagen, IntOffset.Zero, IntSize(imagen.width, imagen.height), IntOffset(desde.x.roundToInt(), desde.y.roundToInt()),
                IntSize((imagen.width * zoom).roundToInt(), (imagen.height * zoom).roundToInt()), filterQuality = FilterQuality.High)
            // Los dos lados que llegan a esta esquina.
            val a = aLupa(esquinas[(i + 3) % 4]); val b = aLupa(esquinas[(i + 1) % 4])
            val centro = Offset(mitad, mitad)
            drawLine(acento, centro, a, 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(acento, centro, b, 2.dp.toPx(), cap = StrokeCap.Round)
            cruz(centro)
        }
    }
}

private fun DrawScope.cruz(c: Offset) {
    val l = 9.dp.toPx()
    drawCircle(Color.White, 3.dp.toPx(), c)
    drawLine(Color.White, c - Offset(l, 0f), c + Offset(l, 0f), 1.5.dp.toPx())
    drawLine(Color.White, c - Offset(0f, l), c + Offset(0f, l), 1.5.dp.toPx())
}

// ---------- La página: filtro, giro, varias páginas ----------

@Composable
private fun PasoPagina(estado: EstadoEscaner, acciones: AccionesEscaner) {
    Column(Modifier.fillMaxSize()) {
        Cabecera(stringResource(R.string.esc_pagina_n, estado.paginas.size + 1), acciones) {
            BotonIcono(IconosEscaner.Girar, stringResource(R.string.esc_girar), tam = 42.dp, color = Colores.Texto, fondo = Colores.Superficie2) { acciones.girar() }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(estado.vista, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "vista") { v ->
                if (v != null) {
                    Image(
                        v.asImageBitmap(), stringResource(R.string.esc_pagina_n, estado.paginas.size + 1), contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Mientras se limpia la página: un aviso pequeño, sin tapar nada.
            androidx.compose.animation.AnimatedVisibility(estado.trabajando, Modifier.align(Alignment.BottomCenter), enter = fadeIn(Muelle.suave()), exit = fadeOut(Muelle.suave())) {
                Text(
                    stringResource(R.string.esc_procesando), style = Texto.Pequeno, color = Color.White,
                    modifier = Modifier.clip(Formas.Pastilla).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        // Las páginas ya hechas (se quitan con la cruz) y la actual, resaltada.
        if (estado.paginas.isNotEmpty()) Miniaturas(estado, acciones)
        SelectorFiltro(estado.filtro) { acciones.filtro(it) }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val mas = estado.paginas.size + 1 < EstadoEscaner.MAX_PAGINAS
            BotonSecundario(stringResource(R.string.esc_otra), Modifier.weight(1f).alpha(if (mas) 1f else 0.45f)) { if (mas && !estado.trabajando) acciones.otraPagina() }
            BotonPrimario(stringResource(R.string.esc_guardar_n, estado.paginas.size + 1), Modifier.weight(1f)) { if (!estado.trabajando) acciones.guardar() }
        }
    }
}

@Composable
private fun Miniaturas(estado: EstadoEscaner, acciones: AccionesEscaner) {
    var elegida by remember { mutableStateOf<PaginaHecha?>(null) }
    val lista = rememberLazyListState()
    LaunchedEffect(estado.paginas.size) { if (estado.paginas.isNotEmpty()) lista.animateScrollToItem(estado.paginas.size) }
    LazyRow(
        state = lista,
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(estado.paginas, key = { it.archivo.path }) { p ->
            Box(Modifier.animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())) {
                Image(
                    p.miniatura.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 42.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).background(Color.White)
                        .border(1.dp, Colores.Borde, RoundedCornerShape(8.dp)).pulsable { elegida = if (elegida == p) null else p },
                )
                AnimatedVisibility(elegida == p, Modifier.align(Alignment.Center), enter = scaleIn(Muelle.vivo()) + fadeIn(Muelle.suave()), exit = scaleOut(Muelle.suave()) + fadeOut(Muelle.suave())) {
                    BotonIcono(Iconos.Papelera, stringResource(R.string.esc_quitar), tam = 32.dp, color = Color.White, fondo = Colores.Rojo) {
                        elegida = null
                        acciones.quitar(p)
                    }
                }
            }
        }
        item(key = "actual") {
            Box(
                Modifier.animateItem(fadeInSpec = Muelle.suave(), placementSpec = Muelle.suave(), fadeOutSpec = Muelle.suave())
                    .size(width = 42.dp, height = 56.dp).clip(RoundedCornerShape(8.dp)).background(Colores.AcentoSuave)
                    .border(2.dp, Colores.Acento, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("${estado.paginas.size + 1}", style = Texto.Pequeno, color = Colores.Acento) }
        }
    }
}

/** Documento · Color · Original, tres del mismo ancho (como los modos de la cámara). */
@Composable
private fun SelectorFiltro(actual: EscanerNucleo.Filtro, onCambio: (EscanerNucleo.Filtro) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(Formas.Pastilla).background(Colores.Superficie).padding(4.dp)) {
        for (f in EscanerNucleo.Filtro.entries) {
            val activo = f == actual
            val fondo by androidx.compose.animation.animateColorAsState(if (activo) Colores.Superficie2 else Colores.Superficie, Muelle.suave(), label = "filtro")
            Box(
                Modifier.weight(1f).height(36.dp).clip(Formas.Pastilla).background(fondo).pulsable { onCambio(f) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(when (f) { EscanerNucleo.Filtro.Documento -> R.string.esc_filtro_documento; EscanerNucleo.Filtro.Color -> R.string.esc_filtro_color; EscanerNucleo.Filtro.Original -> R.string.esc_filtro_original }),
                    style = Texto.Pequeno, color = if (activo) Colores.Texto else Colores.Texto3,
                )
            }
        }
    }
}

/** Iconos del escáner (trazo fino, 24×24, como los de [Iconos]). */
internal object IconosEscaner {
    private fun trazo(nombre: String, vararg caminos: String) =
        ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f).apply {
            for (c in caminos) addPath(
                pathData = PathParser().parsePathString(c).toNodes(), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    /** Las cuatro esquinas de un papel, con una línea de texto. */
    val Escaner = trazo("escaner", "M4 8V5a1 1 0 0 1 1-1h3", "M16 4h3a1 1 0 0 1 1 1v3", "M20 16v3a1 1 0 0 1-1 1h-3", "M8 20H5a1 1 0 0 1-1-1v-3", "M8 10h8", "M8 14h5")
    val Galeria = trazo("galeria", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M4 16l4.5-4.5 4 4 2.5-2.5L20 18", "M15.5 8.5h.01")
    /** Las cuatro esquinas hacia fuera: toda la foto, sin recortar. */
    val Ampliar = trazo("ampliar", "M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5")
    val Girar = trazo("girar", "M19 12a7 7 0 1 1-2.05-4.95", "M19 4v4h-4")
}
