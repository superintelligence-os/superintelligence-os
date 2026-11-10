package com.sios.camara

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.sios.R
import com.sios.ui.Colores
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Cámara profesional de SIOS. Usa todo lo que la cámara del móvil permite controlar (lo que no
 * permite, no aparece): ISO, velocidad de obturación, enfoque manual, balance de blancos (preajustes
 * o grados Kelvin), compensación de exposición, RAW (DNG) junto al JPEG, cada lente física, zoom,
 * flash, y en vídeo la resolución, los fotogramas por segundo y la estabilización. Además, los
 * modos del fabricante (Noche, HDR, Retrato…) si el móvil los trae, y ayudas de encuadre:
 * cuadrícula, nivel, histograma y temporizador. «IA» abre la cámara que pregunta a la IA.
 */
class CamaraProActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private var permisos by mutableStateOf(false)
    private val pedir = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permisos = it[Manifest.permission.CAMERA] == true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        permisos = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!permisos) pedir.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        setContent { TemaSios { if (permisos) PantallaPro() else Box(Modifier.fillMaxSize().background(Color.Black)) } }
    }
}

// ---------- Estado y capacidades ----------

private enum class Modo { FOTO, VIDEO }
private enum class Control { ISO, VELOCIDAD, ENFOQUE, BLANCOS, EV }

/** Lo que de verdad sabe hacer la cámara elegida. */
private class Capacidades(
    val iso: Range<Int>?,
    val exposicion: Range<Long>?,
    val enfoqueMin: Float,
    val blancosManual: Boolean,
    val ev: Range<Int>,
    val evPaso: Float,
    val raw: Boolean,
    val flash: Boolean,
    val zoom: ClosedFloatingPointRange<Float>,
    val apertura: Float?,
    val lentes: List<Pair<String, String>>,
    val fps: List<Range<Int>>,
    val calidades: List<Quality>,
    val estabilizacion: Boolean,
)

private fun capacidades(ctx: Context, camara: Camera): Capacidades {
    val info = Camera2CameraInfo.from(camara.cameraInfo)
    val cap = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet() ?: emptySet()
    val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in cap
    val ev = camara.cameraInfo.exposureState
    val z = camara.cameraInfo.zoomState.value
    // Lentes físicas de una cámara lógica (ultra gran angular, tele…), con su focal para ponerles nombre.
    val lentes = camara.cameraInfo.physicalCameraInfos.mapNotNull { fisica ->
        val c2 = Camera2CameraInfo.from(fisica)
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return@mapNotNull null
        c2.cameraId to "%.0f mm".format(focal)
    }.distinctBy { it.second }
    val calidades = runCatching { Recorder.getVideoCapabilities(camara.cameraInfo).getSupportedQualities(androidx.camera.core.DynamicRange.SDR) }.getOrDefault(emptyList())
    val estabilizacion = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        ?.any { it != CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF } == true ||
        (info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.any { it != 0 } == true)
    return Capacidades(
        iso = if (manual) info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) else null,
        exposicion = if (manual) info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) else null,
        enfoqueMin = if (manual) info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f else 0f,
        blancosManual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in cap,
        ev = ev.exposureCompensationRange,
        evPaso = ev.exposureCompensationStep.toFloat(),
        raw = ImageCapture.getImageCaptureCapabilities(camara.cameraInfo).supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_RAW_JPEG),
        flash = camara.cameraInfo.hasFlashUnit(),
        zoom = (z?.minZoomRatio ?: 1f)..(z?.maxZoomRatio ?: 1f),
        apertura = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull(),
        lentes = if (lentes.size > 1) lentes else emptyList(),
        fps = camara.cameraInfo.supportedFrameRateRanges.filter { it.lower == it.upper }.sortedBy { it.upper }.ifEmpty { camara.cameraInfo.supportedFrameRateRanges.sortedBy { it.upper } }.distinctBy { it.upper },
        calidades = calidades,
        estabilizacion = estabilizacion,
    )
}

// Valores de siempre en fotografía: así el dial va por pasos que se entienden.
private val ISOS = listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 12800, 25600)
private val VELOCIDADES = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2).map { 1_000_000_000L / it } +
    listOf(1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L, 30_000_000_000L)

private fun textoVelocidad(ns: Long): String = if (ns >= 1_000_000_000L) "${(ns / 1_000_000_000.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.1f".format(it) }}″"
    else "1/${(1_000_000_000.0 / ns).roundToInt()}"

private fun textoEnfoque(d: Float): String = when {
    d <= 0.01f -> "∞"
    1 / d >= 1f -> "%.1f m".format(1 / d)
    else -> "${(100 / d).roundToInt()} cm"
}

/** Ganancias RGGB aproximadas para una temperatura de color (cuerpo negro), normalizadas al verde. */
private fun gananciasKelvin(k: Int): RggbChannelVector {
    val t = k / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = if (t >= 66) 255.0 else if (t <= 19) 0.0 else 138.5177312231 * ln(t - 10) - 305.0447927307
    val rr = r.coerceIn(1.0, 255.0); val gg = g.coerceIn(1.0, 255.0); val bb = b.coerceIn(1.0, 255.0)
    return RggbChannelVector((gg / rr).toFloat(), 1f, 1f, (gg / bb).toFloat())
}

private val IDENTIDAD = ColorSpaceTransform(intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1))

private enum class Blancos(val modo: Int, val texto: Int) {
    AUTO(CameraMetadata.CONTROL_AWB_MODE_AUTO, R.string.pro_auto),
    DIA(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, R.string.pro_wb_dia),
    NUBLADO(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, R.string.pro_wb_nublado),
    SOMBRA(CameraMetadata.CONTROL_AWB_MODE_SHADE, R.string.pro_wb_sombra),
    TUNGSTENO(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, R.string.pro_wb_tungsteno),
    FLUORESCENTE(CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, R.string.pro_wb_fluorescente),
    KELVIN(CameraMetadata.CONTROL_AWB_MODE_OFF, R.string.pro_wb_kelvin),
}

// ---------- Pantalla ----------

@SuppressLint("UnsafeOptInUsageError", "RestrictedApi")
@Composable
private fun PantallaPro() {
    val ctx = LocalContext.current
    val vista = LocalView.current
    val dueño = LocalLifecycleOwner.current
    val alcance = rememberCoroutineScope()
    val previa = remember { PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FIT_CENTER } }

    var modo by remember { mutableStateOf(Modo.FOTO) }
    var extension by remember { mutableIntStateOf(ExtensionMode.NONE) }
    var extensiones by remember { mutableStateOf(emptyList<Int>()) }
    var trasera by remember { mutableStateOf(true) }
    var lente by remember { mutableStateOf<String?>(null) }
    var camara by remember { mutableStateOf<Camera?>(null) }
    var cap by remember { mutableStateOf<Capacidades?>(null) }
    var captura by remember { mutableStateOf<ImageCapture?>(null) }
    var video by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var grabando by remember { mutableStateOf<Recording?>(null) }
    var segundosGrabando by remember { mutableIntStateOf(0) }

    // Manual: null = automático.
    var iso by remember { mutableStateOf<Int?>(null) }
    var velocidad by remember { mutableStateOf<Long?>(null) }
    var enfoque by remember { mutableStateOf<Float?>(null) }
    var blancos by remember { mutableStateOf(Blancos.AUTO) }
    var kelvin by remember { mutableIntStateOf(5500) }
    var ev by remember { mutableIntStateOf(0) }
    var abierto by remember { mutableStateOf<Control?>(null) }
    // Lo que está midiendo la cámara en automático (se ve en los botones, como en las cámaras de verdad).
    var isoActual by remember { mutableIntStateOf(0) }
    var velocidadActual by remember { mutableStateOf(0L) }
    var enfoqueActual by remember { mutableFloatStateOf(0f) }

    var flash by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var raw by remember { mutableStateOf(false) }
    var rejilla by remember { mutableStateOf(true) }
    var nivel by remember { mutableStateOf(true) }
    var histograma by remember { mutableStateOf(true) }
    var temporizador by remember { mutableIntStateOf(0) }
    var cuenta by remember { mutableIntStateOf(0) }
    var calidad by remember { mutableStateOf<Quality?>(null) }
    var fps by remember { mutableStateOf<Range<Int>?>(null) }
    var estabilizar by remember { mutableStateOf(true) }
    var destello by remember { mutableStateOf(false) }
    var miniatura by remember { mutableStateOf<Bitmap?>(null) }
    var ultima by remember { mutableStateOf<Uri?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val barras = remember { IntArray(64) }
    var histoVersion by remember { mutableIntStateOf(0) }

    // Resultado de cada fotograma: los valores que está usando el automático.
    val alResultado = remember {
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, t: TotalCaptureResult) {
                t.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)?.let { isoActual = it }
                t.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)?.let { velocidadActual = it }
                t.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)?.let { enfoqueActual = it }
            }
        }
    }

    fun aplicarManual() {
        val c = camara ?: return
        val o = CaptureRequestOptions.Builder()
        if (iso != null || velocidad != null) {
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            o.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso ?: isoActual.coerceAtLeast(100))
            o.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, velocidad ?: velocidadActual.coerceAtLeast(1_000_000L))
            // Sin exposición automática, la duración del fotograma la marca la velocidad.
            o.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, (velocidad ?: velocidadActual).coerceAtLeast(33_333_333L))
        }
        if (enfoque != null) {
            o.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            o.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, enfoque!!)
        }
        when (blancos) {
            Blancos.AUTO -> {}
            Blancos.KELVIN -> {
                o.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                o.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                o.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, gananciasKelvin(kelvin))
                o.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_TRANSFORM, IDENTIDAD)
            }
            else -> o.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, blancos.modo)
        }
        runCatching { Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(o.build()) }
        if (iso == null && velocidad == null) runCatching { c.cameraControl.setExposureCompensationIndex(ev) }
    }

    // Vincula la cámara cada vez que cambia algo que necesita otra sesión.
    LaunchedEffect(trasera, lente, modo, extension, raw, calidad, fps, estabilizar) {
        val proveedor = suspendCancellableCoroutine<ProcessCameraProvider> { k ->
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({ k.resume(f.get()) }, ContextCompat.getMainExecutor(ctx))
        }
        val base = if (trasera) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val gestor = runCatching {
            suspendCancellableCoroutine<ExtensionsManager> { k ->
                val f = ExtensionsManager.getInstanceAsync(ctx, proveedor)
                f.addListener({ k.resume(f.get()) }, ContextCompat.getMainExecutor(ctx))
            }
        }.getOrNull()
        extensiones = listOf(ExtensionMode.NIGHT, ExtensionMode.HDR, ExtensionMode.BOKEH, ExtensionMode.FACE_RETOUCH, ExtensionMode.AUTO)
            .filter { gestor?.isExtensionAvailable(base, it) == true }
        val selector = if (extension != ExtensionMode.NONE && gestor != null && extension in extensiones) gestor.getExtensionEnabledCameraSelector(base, extension) else base

        fun <T> Camera2Interop.Extender<T>.comun() = apply {
            setSessionCaptureCallback(alResultado)
            lente?.let { setPhysicalCameraId(it) }
        }
        val pv = Preview.Builder().also { Camera2Interop.Extender(it).comun() }.build().also { it.surfaceProvider = previa.surfaceProvider }
        val grupo = UseCaseGroup.Builder().addUseCase(pv)
        var ic: ImageCapture? = null
        var vc: VideoCapture<Recorder>? = null
        if (modo == Modo.FOTO) {
            ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(flash)
                .also { if (raw) it.setOutputFormat(ImageCapture.OUTPUT_FORMAT_RAW_JPEG) }
                .also { Camera2Interop.Extender(it).comun() }
                .build()
            grupo.addUseCase(ic)
            if (extension == ExtensionMode.NONE) {
                val an = ImageAnalysis.Builder()
                    .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var ultimo = 0L
                an.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { img ->
                    // Histograma de luminancia (plano Y), unas 6 veces por segundo.
                    val ahora = System.currentTimeMillis()
                    if (ahora - ultimo > 160) {
                        ultimo = ahora
                        val y = img.planes[0].buffer
                        val cuenta = IntArray(64)
                        var i = 0
                        while (i < y.limit()) { cuenta[(y.get(i).toInt() and 0xFF) shr 2]++; i += 7 }
                        System.arraycopy(cuenta, 0, barras, 0, 64)
                        histoVersion++
                    }
                    img.close()
                }
                grupo.addUseCase(an)
            }
        } else {
            val selectorCalidad = calidad?.let { QualitySelector.from(it) } ?: QualitySelector.from(Quality.HIGHEST)
            vc = VideoCapture.Builder(Recorder.Builder().setQualitySelector(selectorCalidad).build())
                .also { b -> fps?.let { b.setTargetFrameRate(it) } }
                .setVideoStabilizationEnabled(estabilizar)
                .also { Camera2Interop.Extender(it).comun() }
                .build()
            grupo.addUseCase(vc)
        }
        proveedor.unbindAll()
        val c = runCatching { proveedor.bindToLifecycle(dueño as LifecycleOwner, selector, grupo.build()) }.getOrElse {
            // La combinación no la admite este móvil (p. ej. una lente física con vídeo): sin la lente.
            if (lente != null) { lente = null; return@LaunchedEffect } else throw it
        }
        camara = c
        captura = ic
        video = vc
        cap = capacidades(ctx, c)
        zoom = c.cameraInfo.zoomState.value?.zoomRatio ?: 1f
        aplicarManual()
    }

    // Nivel: inclinación con el acelerómetro.
    var giro by remember { mutableFloatStateOf(0f) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val oyente = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { giro = giro * 0.8f + Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat() * 0.2f }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(oyente, it, SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm.unregisterListener(oyente) }
    }

    LaunchedEffect(grabando) {
        segundosGrabando = 0
        while (grabando != null) { delay(1000); segundosGrabando++ }
    }

    fun nombre() = "SIOS_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
    fun opciones(ext: String, mime: String): ImageCapture.OutputFileOptions {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${nombre()}.$ext")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/SIOS")
        }
        return ImageCapture.OutputFileOptions.Builder(ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv).build()
    }

    fun guardado(uri: Uri?) {
        ultima = uri ?: return
        alcance.launch {
            miniatura = runCatching { ctx.contentResolver.loadThumbnail(uri, Size(160, 160), null) }.getOrNull()
        }
    }

    fun disparar() {
        alcance.launch {
            for (s in temporizador downTo 1) { cuenta = s; vista.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); delay(1000) }
            cuenta = 0
            if (modo == Modo.VIDEO) {
                val vc = video ?: return@launch
                val g = grabando
                if (g != null) { g.stop(); grabando = null; return@launch }
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${nombre()}.mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/SIOS")
                }
                val salida = MediaStoreOutputOptions.Builder(ctx.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(cv).build()
                val p = vc.output.prepareRecording(ctx, salida)
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) p.withAudioEnabled()
                grabando = p.start(ContextCompat.getMainExecutor(ctx)) { e ->
                    if (e is VideoRecordEvent.Finalize) { grabando = null; if (!e.hasError()) guardado(e.outputResults.outputUri) }
                }
                vista.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                return@launch
            }
            val ic = captura ?: return@launch
            destello = true
            vista.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            val alGuardar = object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) { if (r.savedUri?.toString()?.contains("dng") != true || ultima == null) guardado(r.savedUri) }
                override fun onError(e: ImageCaptureException) {
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.pro_error, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            if (raw) ic.takePicture(opciones("dng", "image/x-adobe-dng"), opciones("jpg", "image/jpeg"), ContextCompat.getMainExecutor(ctx), alGuardar)
            else ic.takePicture(opciones("jpg", "image/jpeg"), ContextCompat.getMainExecutor(ctx), alGuardar)
            delay(120)
            destello = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Visor: tocar enfoca y mide ahí; pellizcar hace zoom.
        AndroidView({ previa }, Modifier.fillMaxSize()
            .pointerInput(camara) {
                detectTapGestures { p ->
                    val c = camara ?: return@detectTapGestures
                    val punto = previa.meteringPointFactory.createPoint(p.x, p.y)
                    if (enfoque == null) runCatching { c.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(punto).build()) }
                }
            }
            .pointerInput(camara) {
                detectTransformGestures { _, _, escala, _ ->
                    val c = camara ?: return@detectTransformGestures
                    val r = cap?.zoom ?: return@detectTransformGestures
                    zoom = (zoom * escala).coerceIn(r.start, r.endInclusive)
                    c.cameraControl.setZoomRatio(zoom)
                }
            })

        // Ayudas de encuadre.
        Canvas(Modifier.fillMaxSize()) {
            if (rejilla) for (i in 1..2) {
                drawLine(Color.White.copy(alpha = 0.28f), Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.28f), Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx())
            }
            if (nivel) {
                val recto = abs(giro) < 1.2f
                val c = center
                val largo = size.width * 0.18f
                val r = Math.toRadians(-giro.toDouble())
                val dx = (largo * kotlin.math.cos(r)).toFloat(); val dy = (largo * kotlin.math.sin(r)).toFloat()
                drawLine(if (recto) Colores.Acento else Color.White.copy(alpha = 0.8f), Offset(c.x - dx, c.y - dy), Offset(c.x + dx, c.y + dy), 2.dp.toPx())
                drawCircle(if (recto) Colores.Acento else Color.White.copy(alpha = 0.6f), 3.dp.toPx(), c)
            }
        }
        // Destello al disparar.
        val alfaDestello by animateFloatAsState(if (destello) 0.7f else 0f, tween(if (destello) 40 else 260), label = "destello")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = alfaDestello }.background(Color.Black))
        // Cuenta atrás.
        AnimatedContent(cuenta, Modifier.align(Alignment.Center), label = "cuenta") { n ->
            if (n > 0) Text("$n", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Light)
        }

        Column(Modifier.fillMaxSize()) {
            // Barra de arriba: flash, temporizador, ayudas, RAW e info de exposición.
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val c = cap
                if (c?.flash == true && modo == Modo.FOTO) Pildora(when (flash) { ImageCapture.FLASH_MODE_AUTO -> "⚡ A"; ImageCapture.FLASH_MODE_ON -> "⚡ Sí"; else -> "⚡ No" }, flash != ImageCapture.FLASH_MODE_OFF) {
                    flash = (flash + 1) % 3
                    captura?.flashMode = flash
                }
                Pildora(if (temporizador == 0) "⏱" else "⏱${temporizador}s", temporizador > 0) { temporizador = when (temporizador) { 0 -> 3; 3 -> 10; else -> 0 } }
                Pildora("#", rejilla) { rejilla = !rejilla }
                Pildora("—", nivel) { nivel = !nivel }
                if (modo == Modo.FOTO && extension == ExtensionMode.NONE) Pildora("▁▃▅", histograma) { histograma = !histograma }
                if (c?.raw == true && modo == Modo.FOTO && extension == ExtensionMode.NONE) Pildora("RAW", raw) { raw = !raw }
            }
            // Lectura de la exposición (o el tiempo grabando), en su propia línea.
            Row(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.End) {
                val c = cap
                if (grabando != null) Text("● %d:%02d".format(segundosGrabando / 60, segundosGrabando % 60), color = Color(0xFFFF5A4F), style = Texto.Pequeno.copy(fontFamily = FontFamily.Monospace))
                else Text(
                    listOfNotNull(
                        "ISO ${iso ?: isoActual}".takeIf { (iso ?: isoActual) > 0 },
                        textoVelocidad(velocidad ?: velocidadActual).takeIf { (velocidad ?: velocidadActual) > 0 },
                        c?.apertura?.let { "f/%.1f".format(it) },
                        enfoque?.let { textoEnfoque(it) },
                        if (blancos == Blancos.KELVIN) "${kelvin}K" else null,
                    ).joinToString("  ·  "),
                    color = Color.White.copy(alpha = 0.85f), maxLines = 1, style = Texto.Pequeno.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                )
            }
            // Histograma.
            AnimatedVisibility(histograma && modo == Modo.FOTO && extension == ExtensionMode.NONE, Modifier.align(Alignment.End).padding(12.dp), enter = fadeIn(), exit = fadeOut()) {
                Canvas(Modifier.size(120.dp, 56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(6.dp)) {
                    histoVersion.let { }
                    val max = (barras.maxOrNull() ?: 1).coerceAtLeast(1)
                    val ancho = size.width / barras.size
                    val p = Path().apply {
                        moveTo(0f, size.height)
                        barras.forEachIndexed { i, v -> lineTo(i * ancho, size.height - size.height * v / max) }
                        lineTo(size.width, size.height); close()
                    }
                    drawPath(p, Color.White.copy(alpha = 0.75f))
                }
            }
            Spacer(Modifier.weight(1f))

            // Panel de abajo.
            Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).navigationBarsPadding().padding(top = 10.dp, bottom = 8.dp)) {
                val c = cap
                // Dial del control abierto.
                AnimatedVisibility(abierto != null && c != null, enter = expandVertically(Muelle.suave()) + fadeIn(), exit = shrinkVertically(Muelle.suave()) + fadeOut()) {
                    if (c != null) Dial(abierto, c, iso, velocidad, enfoque, blancos, kelvin, ev, isoActual, velocidadActual,
                        onIso = { iso = it; aplicarManual() }, onVelocidad = { velocidad = it; aplicarManual() },
                        onEnfoque = { enfoque = it; if (it == null) runCatching { camara?.let { cam -> Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions() } }; aplicarManual() },
                        onBlancos = { b, k -> blancos = b; kelvin = k; if (b == Blancos.AUTO) runCatching { camara?.let { cam -> Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions() } }; aplicarManual() },
                        onEv = { ev = it; aplicarManual() },
                        onAutoTodo = { ae ->
                            if (ae) { iso = null; velocidad = null }
                            runCatching { camara?.let { cam -> Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions() } }
                            aplicarManual()
                        })
                }
                // Controles: solo los que este móvil admite.
                if (c != null && extension == ExtensionMode.NONE) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (c.iso != null) Control("ISO", (iso ?: isoActual).takeIf { it > 0 }?.toString() ?: "—", iso != null, abierto == Control.ISO) { abierto = if (abierto == Control.ISO) null else Control.ISO }
                    if (c.exposicion != null) Control(stringResource(R.string.pro_velocidad), textoVelocidad(velocidad ?: velocidadActual.coerceAtLeast(1)), velocidad != null, abierto == Control.VELOCIDAD) { abierto = if (abierto == Control.VELOCIDAD) null else Control.VELOCIDAD }
                    if (c.enfoqueMin > 0f) Control(stringResource(R.string.pro_enfoque), if (enfoque == null) "AF" else textoEnfoque(enfoque!!), enfoque != null, abierto == Control.ENFOQUE) { abierto = if (abierto == Control.ENFOQUE) null else Control.ENFOQUE }
                    Control("WB", if (blancos == Blancos.KELVIN) "${kelvin}K" else stringResource(blancos.texto), blancos != Blancos.AUTO, abierto == Control.BLANCOS) { abierto = if (abierto == Control.BLANCOS) null else Control.BLANCOS }
                    if (c.ev.upper > c.ev.lower) Control("EV", "%+.1f".format(ev * c.evPaso), ev != 0, abierto == Control.EV) { abierto = if (abierto == Control.EV) null else Control.EV }
                }
                // Lentes y zoom.
                if (c != null) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
                ) {
                    for ((id, focal) in c.lentes) Pildora(focal, lente == id) { lente = if (lente == id) null else id }
                    if (c.lentes.isEmpty()) {
                        val pasos = listOf(0.5f, 0.6f, 1f, 2f, 3f, 5f, 10f).filter { it in c.zoom }
                        for (z in pasos) Pildora(if (z < 1f) ".${(z * 10).roundToInt()}" else "${z.roundToInt()}×", abs(zoom - z) < 0.05f) {
                            zoom = z; camara?.cameraControl?.setZoomRatio(z)
                        }
                    }
                }
                // Ajustes de vídeo.
                AnimatedVisibility(modo == Modo.VIDEO && c != null) {
                    if (c != null) Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (q in c.calidades) Pildora(nombreCalidad(q), calidad == q || (calidad == null && q == c.calidades.firstOrNull())) { calidad = q }
                        if (c.fps.isNotEmpty()) Text("·", color = Color.White.copy(alpha = 0.4f))
                        for (r in c.fps) Pildora("${r.upper} fps", fps == r) { fps = if (fps == r) null else r }
                        if (c.estabilizacion) Pildora(stringResource(R.string.pro_estabilizar), estabilizar) { estabilizar = !estabilizar }
                    }
                }
                // Modos: foto, vídeo, los del fabricante y la cámara con IA.
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally),
                ) {
                    Modo(stringResource(R.string.pro_foto), modo == Modo.FOTO && extension == ExtensionMode.NONE) { modo = Modo.FOTO; extension = ExtensionMode.NONE }
                    Modo(stringResource(R.string.pro_video), modo == Modo.VIDEO) { if (grabando == null) { modo = Modo.VIDEO; extension = ExtensionMode.NONE; raw = false } }
                    for (e in extensiones) Modo(stringResource(nombreExtension(e)), extension == e) { modo = Modo.FOTO; extension = e; raw = false; abierto = null }
                    Modo(stringResource(R.string.pro_ia), false) {
                        ctx.startActivity(Intent(ctx, com.sios.sistema.CamaraActivity::class.java))
                    }
                }
                // Disparador, última foto y cambiar de cámara.
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Tocar la última foto la abre en la galería; mantenerla pulsada, en el editor de fotos.
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).combinedClickable(
                        onLongClick = { ultima?.let { vista.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); com.sios.fotos.EditorFotosActivity.abrir(ctx, it) } },
                    ) {
                        ultima?.let { ctx.startActivity(Intent(Intent.ACTION_VIEW, it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }) {
                        miniatura?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Spacer(Modifier.weight(1f))
                    Disparador(modo == Modo.VIDEO, grabando != null) { disparar() }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)).clickable(enabled = grabando == null) {
                        trasera = !trasera; lente = null; iso = null; velocidad = null; enfoque = null; ev = 0; abierto = null
                    }, contentAlignment = Alignment.Center) {
                        androidx.compose.material3.Icon(com.sios.ui.Iconos.Refrescar, stringResource(R.string.pro_girar), tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
    }
}

private fun nombreCalidad(q: Quality) = when (q) { Quality.UHD -> "4K"; Quality.FHD -> "1080p"; Quality.HD -> "720p"; Quality.SD -> "480p"; else -> "—" }

private fun nombreExtension(e: Int) = when (e) {
    ExtensionMode.NIGHT -> R.string.pro_noche
    ExtensionMode.HDR -> R.string.pro_hdr
    ExtensionMode.BOKEH -> R.string.pro_retrato
    ExtensionMode.FACE_RETOUCH -> R.string.pro_retoque
    else -> R.string.pro_auto
}

// ---------- Piezas ----------

@Composable
private fun Pildora(texto: String, activa: Boolean, onClick: () -> Unit) {
    val fondo by animateColorAsState(if (activa) Colores.Acento.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f), Muelle.suave(), label = "fondo")
    Text(
        texto, color = Color.White, style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
        modifier = Modifier.clip(CircleShape).background(fondo).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Control(nombre: String, valor: String, manual: Boolean, abierto: Boolean, onClick: () -> Unit) {
    val borde by animateColorAsState(if (abierto) Colores.Acento else if (manual) Color.White.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.18f), Muelle.suave(), label = "borde")
    Column(
        Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).border(1.dp, borde, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(nombre.uppercase(), color = if (manual) Colores.Acento else Color.White.copy(alpha = 0.55f), style = Texto.Etiqueta.copy(fontSize = 10.sp))
        Text(valor, color = Color.White, style = Texto.Pequeno.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun Modo(texto: String, activo: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(if (activo) Colores.Acento else Color.White.copy(alpha = 0.7f), Muelle.suave(), label = "modo")
    Text(texto.uppercase(), color = color, style = Texto.Etiqueta.copy(fontSize = 12.sp, letterSpacing = 1.4.sp), modifier = Modifier.clickable(onClick = onClick).padding(vertical = 6.dp))
}

@Composable
private fun Disparador(video: Boolean, grabando: Boolean, onClick: () -> Unit) {
    val toque = remember { MutableInteractionSource() }
    val pulsado by toque.collectIsPressedAsState()
    val escala by animateFloatAsState(if (pulsado) 0.88f else 1f, Muelle.vivo(), label = "escala")
    val interior by animateFloatAsState(if (grabando) 0.42f else 0.84f, Muelle.vivo(), label = "interior")
    val color by animateColorAsState(if (video) Color(0xFFFF453A) else Color.White, Muelle.suave(), label = "color")
    Box(
        Modifier.size(78.dp).graphicsLayer { scaleX = escala; scaleY = escala }.border(3.dp, Color.White, CircleShape)
            .clickable(toque, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(78.dp * interior).clip(if (grabando) androidx.compose.foundation.shape.RoundedCornerShape(8.dp) else CircleShape).background(color))
    }
}

/** El dial del control abierto: deslizador por pasos con su valor, y «Auto» para volver a automático. */
@Composable
private fun Dial(
    control: Control?, c: Capacidades, iso: Int?, velocidad: Long?, enfoque: Float?, blancos: Blancos, kelvin: Int, ev: Int, isoActual: Int, velocidadActual: Long,
    onIso: (Int?) -> Unit, onVelocidad: (Long?) -> Unit, onEnfoque: (Float?) -> Unit, onBlancos: (Blancos, Int) -> Unit, onEv: (Int) -> Unit, onAutoTodo: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (control) {
            Control.ISO -> {
                val pasos = ISOS.filter { c.iso?.contains(it) == true }.ifEmpty { listOf(100) }
                Paso(pasos.map { it.toString() }, iso?.let { v -> pasos.indexOfFirst { it >= v }.coerceAtLeast(0) }, pasos.indexOfFirst { it >= isoActual }.coerceAtLeast(0), onAuto = { onIso(null) }) { onIso(pasos[it]) }
            }
            Control.VELOCIDAD -> {
                val pasos = VELOCIDADES.filter { c.exposicion?.contains(it) == true }.ifEmpty { listOf(8_333_333L) }
                Paso(pasos.map(::textoVelocidad), velocidad?.let { v -> pasos.indexOfFirst { it >= v }.coerceAtLeast(0) }, pasos.indexOfFirst { it >= velocidadActual }.coerceAtLeast(0), onAuto = { onVelocidad(null) }) { onVelocidad(pasos[it]) }
            }
            Control.ENFOQUE -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("∞", color = Color.White.copy(alpha = 0.6f), style = Texto.Pequeno)
                    Slider(
                        value = enfoque ?: 0f, onValueChange = { onEnfoque(it) }, valueRange = 0f..c.enfoqueMin,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Colores.Acento, activeTrackColor = Colores.Acento),
                    )
                    Text(textoEnfoque(c.enfoqueMin), color = Color.White.copy(alpha = 0.6f), style = Texto.Pequeno)
                    Spacer(Modifier.width(10.dp))
                    Pildora(stringResource(R.string.pro_auto), enfoque == null) { onEnfoque(null) }
                }
            }
            Control.BLANCOS -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in Blancos.entries) if (b != Blancos.KELVIN || c.blancosManual) Pildora(stringResource(b.texto), blancos == b) { onBlancos(b, kelvin) }
                }
                if (blancos == Blancos.KELVIN) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("2000K", color = Color(0xFFFFB46B), style = Texto.Pequeno)
                    Slider(
                        value = kelvin.toFloat(), onValueChange = { onBlancos(Blancos.KELVIN, (it / 100).roundToInt() * 100) }, valueRange = 2000f..10000f,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Colores.Acento, activeTrackColor = Colores.Acento),
                    )
                    Text("10000K", color = Color(0xFF9CC3FF), style = Texto.Pequeno)
                }
            }
            Control.EV -> {
                val pasos = (c.ev.lower..c.ev.upper).toList()
                Paso(pasos.map { "%+.1f".format(it * c.evPaso) }, pasos.indexOf(ev).takeIf { ev != 0 }, pasos.indexOf(0).coerceAtLeast(0), onAuto = { onEv(0) }) { onEv(pasos[it]) }
            }
            null -> {}
        }
        if (control == Control.ISO || control == Control.VELOCIDAD) Text(
            stringResource(R.string.pro_nota_manual), color = Color.White.copy(alpha = 0.45f), style = Texto.Pequeno.copy(fontSize = 11.sp),
            modifier = Modifier.clickable { onAutoTodo(true) },
        )
    }
}

/** Deslizador por pasos (ISO, velocidad, EV) con el valor elegido encima. */
@Composable
private fun Paso(etiquetas: List<String>, elegido: Int?, medido: Int, onAuto: () -> Unit, onPaso: (Int) -> Unit) {
    // En automático, el dial empieza en lo que está midiendo la cámara.
    var valor by remember(etiquetas, elegido) { mutableFloatStateOf((elegido ?: medido).toFloat()) }
    val vista = LocalView.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(etiquetas.getOrElse(valor.roundToInt()) { "" }, color = if (elegido != null) Colores.Acento else Color.White, style = Texto.Subtitulo.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.width(76.dp))
        Slider(
            value = valor, onValueChange = {
                val antes = valor.roundToInt()
                valor = it
                if (it.roundToInt() != antes) { vista.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onPaso(it.roundToInt()) }
            },
            valueRange = 0f..(etiquetas.size - 1).coerceAtLeast(1).toFloat(),
            steps = (etiquetas.size - 2).coerceAtLeast(0),
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Colores.Acento, activeTrackColor = Colores.Acento, inactiveTickColor = Color.White.copy(alpha = 0.25f), activeTickColor = Color.White.copy(alpha = 0.4f)),
        )
        Spacer(Modifier.width(10.dp))
        Pildora(stringResource(R.string.pro_auto), elegido == null, onAuto)
    }
}
