package com.sios.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.datos.Creacion
import com.sios.datos.Servicio
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** QR del texto (módulos negros sobre blanco, sin margen: el margen lo pone la tarjeta). null si no cabe. */
fun qr(texto: String): Bitmap? = runCatching {
    val m = QRCodeWriter().encode(
        texto, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val pixeles = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) 0xFF141413.toInt() else 0xFFFFFFFF.toInt() }
    Bitmap.createBitmap(pixeles, m.width, m.height, Bitmap.Config.ARGB_8888)
}.getOrNull()

/**
 * Compartir una creación: QR grande para que otro SIOS lo escanee y, debajo, enviar o copiar
 * el enlace. Al abrirlo, el otro móvil enseña qué hace y pide permiso antes de añadirlo.
 */
@Composable
fun BloqueCompartir(c: Creacion) {
    val ctx = LocalContext.current
    var enlace by remember(c.id) { mutableStateOf<String?>(null) }
    var imagen by remember(c.id) { mutableStateOf<Bitmap?>(null) }
    var error by remember(c.id) { mutableStateOf(false) }
    LaunchedEffect(c.id) {
        val r = Servicio.exportarCreacion(c.id)
        enlace = r?.optString("enlace")?.takeIf { it.isNotBlank() }
        error = enlace == null
        imagen = enlace?.let { withContext(Dispatchers.Default) { qr(it) } }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            Modifier.fillMaxWidth(0.78f).aspectRatio(1f).clip(Formas.Tarjeta).background(Color.White).padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                imagen != null -> Image(imagen!!.asImageBitmap(), null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
                enlace != null || error -> Text(
                    stringResource(if (error) R.string.compartir_error else R.string.compartir_sin_qr),
                    style = Texto.Pequeno, color = Color(0xFF73726C), textAlign = TextAlign.Center,
                )
                else -> Text(stringResource(R.string.compartir_preparando), style = Texto.Pequeno, color = Color(0xFF73726C))
            }
        }
        Text(stringResource(R.string.compartir_nota), style = Texto.Pequeno, color = Colores.Texto3, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 8.dp))
        // Publicarlo en la galería comunitaria (solo widgets con datos de una fuente; sin nada tuyo).
        if (c.tipo == "widget" && c.plantilla != "html") {
            var abierto by remember(c.id) { mutableStateOf(false) }
            var descripcion by remember(c.id) { mutableStateOf("") }
            var publicando by remember(c.id) { mutableStateOf(false) }
            val alcance = androidx.compose.runtime.rememberCoroutineScope()
            androidx.compose.animation.AnimatedContent(abierto, label = "publicar") { a ->
                if (!a) Text(
                    stringResource(R.string.comunidad_publicar), style = Texto.Pequeno.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Colores.Acento,
                    modifier = Modifier.clip(Formas.Pequena).pulsable { abierto = true }.padding(10.dp),
                ) else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.sios.inicio.Campo(descripcion, { descripcion = it.take(140) }, stringResource(R.string.comunidad_descripcion), androidx.compose.ui.text.input.KeyboardType.Text, secreto = false)
                    Text(stringResource(R.string.comunidad_publicar_nota), style = Texto.Pequeno, color = Colores.Texto3)
                    BotonSecundario(stringResource(if (publicando) R.string.comunidad_publicando else R.string.comunidad_publicar_si), Modifier.fillMaxWidth()) {
                        if (publicando) return@BotonSecundario
                        publicando = true
                        alcance.launch {
                            val r = Servicio.publicarComunidad(c.id, descripcion.trim())
                            publicando = false
                            val error = r?.optString("error")?.takeIf { it.isNotBlank() }
                            android.widget.Toast.makeText(ctx, error ?: ctx.getString(R.string.comunidad_publicado), android.widget.Toast.LENGTH_LONG).show()
                            if (error == null) abierto = false
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonSecundario(stringResource(R.string.compartir_copiar), Modifier.weight(1f)) {
                val e = enlace ?: return@BotonSecundario
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(c.nombre, e))
            }
            BotonPrimario(stringResource(R.string.compartir_enviar), Modifier.weight(1f)) {
                val e = enlace ?: return@BotonPrimario
                val texto = ctx.getString(R.string.compartir_mensaje, c.nombre, e)
                ctx.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto), null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}
