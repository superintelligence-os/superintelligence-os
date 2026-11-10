package com.sios.sistema

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.sios.R
import com.sios.ui.BotonIcono
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cámara de Claude: apuntar y preguntar. Tres modos del mismo tamaño:
 * - Preguntar: la foto va a la hoja de Claude con «¿Qué es esto?» y demás preguntas rápidas;
 * - Traducir: Claude traduce todo el texto que se ve;
 * - Documento: el escáner de SIOS ([EscanerActivity]: bordes, esquinas, enderezado, varias páginas) y
 *   Documentos: PDF con nombre claro en Documentos › Claude, gasto anotado y fechas al calendario.
 */
class CamaraActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private var congelada by mutableStateOf<Bitmap?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TemaSios { PantallaCamara(congelada, modo, onModo = ::cambiarModo, onFoto = { congelada = it; enviar(it) }, onCerrar = ::finish) } }
    }

    // Al volver de la hoja de Claude, la cámara sigue en vivo.
    override fun onResume() {
        super.onResume()
        congelada = null
    }

    private var modo by mutableStateOf(Modo.Preguntar)
    fun cambiarModo(m: Modo) {
        modo = m
        if (m == Modo.Documento) escaner.launch(Intent(this, EscanerActivity::class.java))
    }

    // El escáner de SIOS (sin Google): si se guarda, la cámara ha terminado; si se vuelve atrás,
    // la cámara sigue donde estaba, en «Preguntar».
    private val escaner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) finish() else modo = Modo.Preguntar
    }

    private fun enviar(b: Bitmap) {
        val dir = File(cacheDir, "compartido").apply { mkdirs() }
        val f = File(dir, "camara.jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.archivos", f)
        val consulta = when (modo) {
            Modo.Preguntar -> null
            Modo.Traducir -> getString(R.string.camara_pregunta_traducir, Locale.getDefault().getDisplayLanguage(Locale.getDefault()))
            Modo.Documento -> getString(R.string.camara_pregunta_documento)
        }
        startActivity(
            Intent(this, AsistenteActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(AsistenteActivity.EXTRA_RAPIDAS, "camara")
                .apply { if (consulta != null) putExtra(android.app.SearchManager.QUERY, consulta) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    override fun finish() {
        super.finish()
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }
}

enum class Modo(val texto: Int) { Preguntar(R.string.camara_preguntar), Traducir(R.string.camara_traducir), Documento(R.string.camara_documento) }

@Composable
private fun PantallaCamara(congelada: Bitmap?, modo: Modo, onModo: (Modo) -> Unit, onFoto: (Bitmap) -> Unit, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    var permiso by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permiso = it; if (!it) onCerrar() }
    LaunchedEffect(Unit) { if (!permiso) pedir.launch(Manifest.permission.CAMERA) }
    var disparando by remember { mutableStateOf(false) }
    val captura = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    LaunchedEffect(congelada) { if (congelada == null) disparando = false }

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
                        proveedor.bindToLifecycle(ciclo, CameraSelector.DEFAULT_BACK_CAMERA, previa, captura)
                    }
                }, ContextCompat.getMainExecutor(ctx))
                onDispose { runCatching { futuro.get().unbindAll() } }
            }
        }
        // La foto hecha se queda congelada mientras Claude la mira.
        AnimatedVisibility(congelada != null, enter = fadeIn(Muelle.suave()), exit = fadeOut(Muelle.suave())) {
            congelada?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }

        // Degradado arriba: la barra de estado y los botones se leen aunque la imagen sea clara.
        Box(
            Modifier.fillMaxWidth().height(140.dp)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))),
        )
        Row(Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BotonIcono(Iconos.Cruz, stringResource(R.string.comun_cerrar), tam = 42.dp, color = Colores.Texto, fondo = Colores.Fondo.copy(alpha = 0.6f), onClick = onCerrar)
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.clip(Formas.Pastilla).background(Colores.Fondo.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.camara_titulo), style = Texto.Pequeno, color = Colores.Texto)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Colores.Fondo.copy(alpha = 0.72f)).navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Tres modos del mismo ancho.
            Row(Modifier.fillMaxWidth().clip(Formas.Pastilla).background(Colores.Superficie).padding(4.dp)) {
                for (m in Modo.entries) {
                    val activo = m == modo
                    Box(
                        Modifier.weight(1f).height(36.dp).clip(Formas.Pastilla).background(if (activo) Colores.Superficie2 else Colores.Superficie)
                            .pulsable { onModo(m) },
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(m.texto), style = Texto.Pequeno, color = if (activo) Colores.Texto else Colores.Texto3) }
                }
            }
            // Disparador: aro blanco con el naranja de Claude; mientras piensa, el logo gira.
            val escala by animateFloatAsState(if (disparando) 0.9f else 1f, Muelle.vivo(), label = "disparo")
            val giro by rememberInfiniteTransition(label = "giro").animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "g")
            Box(
                Modifier.size(78.dp).scale(escala).clip(Formas.Pastilla).border(4.dp, Colores.Texto, Formas.Pastilla).padding(8.dp)
                    .clip(Formas.Pastilla).background(Colores.Acento)
                    .pulsable {
                        if (disparando || !permiso) return@pulsable
                        disparando = true
                        captura.takePicture(ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(img: ImageProxy) {
                                val b = img.toBitmap().girada(img.imageInfo.rotationDegrees)
                                img.close()
                                onFoto(b)
                            }
                            override fun onError(e: ImageCaptureException) { disparando = false }
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Iconos.Asistente, stringResource(R.string.camara_disparar), tint = Colores.SobreAcento,
                    modifier = Modifier.size(28.dp).rotate(if (disparando) giro else 0f))
            }
        }
    }
}

private fun Bitmap.girada(grados: Int): Bitmap =
    if (grados == 0) this else Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(grados.toFloat()) }, true)
