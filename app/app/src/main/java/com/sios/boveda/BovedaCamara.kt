package com.sios.boveda

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sios.R
import com.sios.ui.BotonIcono
import com.sios.ui.BotonPrimario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors

/** La cámara de la Bóveda: leer un QR (2FA o exportación de Google Authenticator) o fotografiar un documento. */
enum class ModoCamara { QR, FOTO }

sealed class ResultadoCamara {
    class Texto(val texto: String) : ResultadoCamara()
    class Foto(val imagen: Bitmap) : ResultadoCamara()
}

/** Lector de QR con ZXing (ya en SIOS para compartir): en el móvil, sin servicios de Google. */
internal object LectorQr {
    private val pistas = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

    private fun leer(f: LuminanceSource): String? {
        val lector = QRCodeReader()
        for (b in listOf(BinaryBitmap(HybridBinarizer(f)), BinaryBitmap(GlobalHistogramBinarizer(f)), BinaryBitmap(HybridBinarizer(f.invert())))) {
            runCatching { return lector.decode(b, pistas).text }
            lector.reset()
        }
        return null
    }

    fun deFotograma(img: ImageProxy): String? {
        val plano = img.planes[0]
        val buf = plano.buffer
        val paso = plano.rowStride
        val datos = ByteArray(paso * img.height)
        buf.rewind()
        buf.get(datos, 0, minOf(buf.remaining(), datos.size))
        return leer(PlanarYUVLuminanceSource(datos, paso, img.height, 0, 0, img.width, img.height, false))
    }

    fun deImagen(ctx: Context, uri: Uri): String? = runCatching {
        val b = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val lado = maxOf(info.size.width, info.size.height)
            if (lado > 1800) { val f = 1800f / lado; d.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt()) }
        }
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        leer(RGBLuminanceSource(b.width, b.height, px))
    }.getOrNull()
}

@Composable
internal fun PaginaCamara(modo: ModoCamara, onCerrar: () -> Unit, onResultado: (ResultadoCamara) -> Unit) {
    val ctx = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    var permiso by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var negado by remember { mutableStateOf(false) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permiso = it; negado = !it }
    LaunchedEffect(Unit) { if (!permiso) pedir.launch(Manifest.permission.CAMERA) }
    var hecho by remember { mutableStateOf(false) }
    var sinQr by remember { mutableStateOf(false) }
    var disparando by remember { mutableStateOf(false) }
    val captura = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val hilo = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { hilo.shutdown() } }
    fun terminar(r: ResultadoCamara) {
        if (hecho) return
        hecho = true
        onResultado(r)
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Thread {
            val t = LectorQr.deImagen(ctx, uri)
            android.os.Handler(android.os.Looper.getMainLooper()).post { if (t != null) terminar(ResultadoCamara.Texto(t)) else sinQr = true }
        }.start()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permiso) {
            val vista = remember { PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
            AndroidView({ vista }, Modifier.fillMaxSize())
            DisposableEffect(ciclo) {
                val futuro = ProcessCameraProvider.getInstance(ctx)
                futuro.addListener({
                    val proveedor = futuro.get()
                    val previa = Preview.Builder().build().also { it.surfaceProvider = vista.surfaceProvider }
                    runCatching {
                        proveedor.unbindAll()
                        if (modo == ModoCamara.QR) {
                            val analisis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            analisis.setAnalyzer(hilo) { img ->
                                val t = runCatching { LectorQr.deFotograma(img) }.getOrNull()
                                img.close()
                                if (t != null && (t.startsWith("otpauth", ignoreCase = true))) {
                                    ContextCompat.getMainExecutor(ctx).execute { terminar(ResultadoCamara.Texto(t)) }
                                }
                            }
                            proveedor.bindToLifecycle(ciclo, CameraSelector.DEFAULT_BACK_CAMERA, previa, analisis)
                        } else proveedor.bindToLifecycle(ciclo, CameraSelector.DEFAULT_BACK_CAMERA, previa, captura)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                onDispose { runCatching { futuro.get().unbindAll() } }
            }
        }
        // El recuadro: cuadrado para el QR, con forma de tarjeta para los documentos. Fuera, oscuro.
        Marco(modo)

        Row(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BotonIcono(Iconos.Cruz, stringResource(R.string.bv_cancelar), color = Color.White, fondo = Color.Black.copy(alpha = 0.35f), onClick = onCerrar)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (modo == ModoCamara.QR) R.string.bv_cam_qr_titulo else R.string.bv_cam_foto_titulo),
                style = Texto.Subtitulo, color = Color.White,
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val nota = when {
                negado -> stringResource(R.string.bv_cam_sin_permiso)
                sinQr -> stringResource(R.string.bv_cam_sin_qr)
                modo == ModoCamara.QR -> stringResource(R.string.bv_cam_qr_nota)
                else -> stringResource(R.string.bv_cam_foto_nota)
            }
            Text(nota, style = Texto.Pequeno, color = Color.White.copy(alpha = 0.85f), textAlign = TextAlign.Center)
            if (modo == ModoCamara.QR) {
                Row(
                    Modifier.clip(Formas.Pastilla).background(Color.White.copy(alpha = 0.16f)).pulsable {
                        sinQr = false
                        galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }.padding(horizontal = 18.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconosBoveda.Imagen, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bv_cam_desde_imagen), style = Texto.Pequeno, color = Color.White)
                }
            } else if (permiso) {
                val escala by animateFloatAsState(if (disparando) 0.88f else 1f, Muelle.vivo(), label = "disparo")
                Box(
                    Modifier.size(76.dp).scale(escala).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape).background(Color.White)
                        .pulsable {
                            if (disparando) return@pulsable
                            disparando = true
                            captura.takePicture(hilo, object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(img: ImageProxy) {
                                    val giro = img.imageInfo.rotationDegrees
                                    val b = runCatching { img.toBitmap() }.getOrNull()
                                    img.close()
                                    val derecha = b?.let { if (giro == 0) it else Bitmap.createBitmap(it, 0, 0, it.width, it.height, Matrix().apply { postRotate(giro.toFloat()) }, true) }
                                    ContextCompat.getMainExecutor(ctx).execute { disparando = false; if (derecha != null) terminar(ResultadoCamara.Foto(derecha)) }
                                }

                                override fun onError(e: ImageCaptureException) {
                                    ContextCompat.getMainExecutor(ctx).execute { disparando = false }
                                }
                            })
                        },
                )
            }
            if (negado) BotonPrimario(stringResource(R.string.bv_cam_permitir), Modifier.fillMaxWidth()) { pedir.launch(Manifest.permission.CAMERA) }
        }
    }
}

@Composable
private fun Marco(modo: ModoCamara) {
    Canvas(Modifier.fillMaxSize()) {
        val ancho = if (modo == ModoCamara.QR) size.width * 0.68f else size.width * 0.88f
        val alto = if (modo == ModoCamara.QR) ancho else ancho / 1.586f
        val o = Offset((size.width - ancho) / 2, (size.height - alto) / 2 - size.height * 0.04f)
        val r = 28.dp.toPx()
        val hueco = Path().apply { addRoundRect(RoundRect(o.x, o.y, o.x + ancho, o.y + alto, CornerRadius(r))) }
        clipPath(hueco, ClipOp.Difference) { drawRect(Color.Black.copy(alpha = 0.5f)) }
        drawRoundRect(Color.White.copy(alpha = 0.9f), o, Size(ancho, alto), CornerRadius(r), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}
