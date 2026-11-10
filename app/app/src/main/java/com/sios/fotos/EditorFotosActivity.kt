package com.sios.fotos

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.ui.Colores
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * El editor de fotos del sistema. Se abre con «Editar» desde cualquier galería (ACTION_EDIT) o
 * manteniendo pulsada la última foto en la cámara. Nunca toca la original: guarda una copia al lado.
 */
class EditorFotosActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        val uri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri == null || Build.VERSION.SDK_INT < 33) { finish(); return }
        enableEdgeToEdge()
        setContent { TemaSios { Pantalla(uri) } }
    }

    @RequiresApi(33)
    @androidx.compose.runtime.Composable
    private fun Pantalla(uri: Uri) {
        val alcance = rememberCoroutineScope()
        var guardando by remember { mutableStateOf(false) }
        val completa = remember { CargaCompleta(applicationContext, uri) }
        val estado by produceState<EstadoEditor?>(null, uri) {
            value = withContext(Dispatchers.IO) {
                runCatching { EstadoEditor(applicationContext, decodificar(applicationContext, uri, LADO_VISTA, 0), completa::cargar) }
                    .onFailure { android.util.Log.w("SistemaSios", "Fotos: abrir", it) }.getOrNull()
            }
            if (value == null) {
                Toast.makeText(this@EditorFotosActivity, R.string.fotos_error_abrir, Toast.LENGTH_LONG).show()
                finish()
            }
        }
        DisposableEffect(Unit) { onDispose { estado?.liberar() } }
        AnimatedContent(estado, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "carga") { e ->
            if (e == null) Box(Modifier.fillMaxSize().background(Colores.Lateral), contentAlignment = Alignment.Center) {
                BarraIndeterminada(Modifier.width(96.dp))
            } else EditorFotos(e, guardando, onCerrar = ::finish, onGuardar = {
                guardando = true
                alcance.launch {
                    val nueva = runCatching {
                        val b = e.revelarCompleta()
                        withContext(Dispatchers.IO) { guardarCopia(applicationContext, uri, b, png = e.edicion.transparente) }
                    }.onFailure { android.util.Log.w("SistemaSios", "Fotos: guardar", it) }.getOrNull()
                    guardando = false
                    if (nueva == null) {
                        Toast.makeText(this@EditorFotosActivity, R.string.fotos_error_guardar, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@EditorFotosActivity, R.string.fotos_guardada, Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_OK, Intent().setDataAndType(nueva, if (e.edicion.transparente) "image/png" else "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        finish()
                    }
                }
            })
        }
    }

    /** La foto entera se carga una vez, cuando hace falta (al borrar o al guardar). */
    private class CargaCompleta(private val ctx: Context, private val uri: Uri) {
        private var b: Bitmap? = null
        @RequiresApi(33)
        suspend fun cargar(): Bitmap = b ?: withContext(Dispatchers.IO) { decodificar(ctx, uri, 0, MAX_PIXELES) }.also { b = it }
    }

    companion object {
        /** La vista previa: de sobra para la pantalla y rápida de revelar. */
        private const val LADO_VISTA = 2048
        /** Hasta 25 MP a resolución completa (Android no dibuja mapas de bits de más de 100 MB); más, se reduce. */
        private const val MAX_PIXELES = 25_000_000L

        /** Abre el editor con una foto (para la galería, la cámara o quien sea). */
        fun abrir(ctx: Context, uri: Uri) {
            ctx.startActivity(
                Intent(Intent.ACTION_EDIT).setClass(ctx, EditorFotosActivity::class.java).setDataAndType(uri, "image/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /**
         * Lee la foto ya girada según su EXIF (ImageDecoder lo hace solo), en ARGB de software y
         * modificable. lado: lado largo máximo (0 = sin límite); pixeles: máximo total (0 = sin límite).
         */
        @RequiresApi(33)
        fun decodificar(ctx: Context, uri: Uri, lado: Int, pixeles: Long): Bitmap {
            val fuente = ImageDecoder.createSource(ctx.contentResolver, uri)
            return ImageDecoder.decodeBitmap(fuente) { decodificador, info, _ ->
                decodificador.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decodificador.isMutableRequired = true
                // Siempre en sRGB: el sombreador y los modelos trabajan en sRGB.
                decodificador.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                val w = info.size.width
                val h = info.size.height
                var escala = 1.0
                if (lado > 0 && max(w, h) > lado) escala = lado.toDouble() / max(w, h)
                if (pixeles > 0 && w.toLong() * h > pixeles) escala = minOf(escala, sqrt(pixeles.toDouble() / (w.toLong() * h)))
                if (escala < 1.0) decodificador.setTargetSize(max(1, (w * escala).toInt()), max(1, (h * escala).toInt()))
            }.let { if (it.config != Bitmap.Config.ARGB_8888) it.copy(Bitmap.Config.ARGB_8888, true) else it }
        }

        /**
         * Guarda la copia junto a la original (misma carpeta si es de la galería; si no, en
         * Imágenes/SIOS), con la misma fecha para que salga a su lado, y copia el EXIF útil (fecha,
         * cámara, ubicación). Devuelve su dirección.
         */
        fun guardarCopia(ctx: Context, original: Uri, b: Bitmap, png: Boolean): Uri {
            val cr = ctx.contentResolver
            var nombre = "IMG_${System.currentTimeMillis()}"
            var carpeta = Environment.DIRECTORY_PICTURES + "/SIOS"
            var fecha: Long? = null
            runCatching {
                cr.query(original, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }?.let { nombre = it.substringBeforeLast('.') }
                        c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH).takeIf { it >= 0 }?.let { c.getString(it) }?.takeIf { it.isNotBlank() }?.let { carpeta = it }
                        c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN).takeIf { it >= 0 }?.let { if (!c.isNull(it)) fecha = c.getLong(it) }
                    }
                }
            }
            val valores = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${nombre}_editada" + if (png) ".png" else ".jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, if (png) "image/png" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, carpeta)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                fecha?.let { put(MediaStore.MediaColumns.DATE_TAKEN, it) }
            }
            val nueva = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores) ?: error("MediaStore no la acepta")
            try {
                cr.openOutputStream(nueva)!!.use { s -> b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, s) }
                if (!png) runCatching { copiarExif(ctx, original, nueva) }
                cr.update(nueva, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                runCatching { cr.delete(nueva, null, null) }
                throw t
            }
            return nueva
        }

        private val ETIQUETAS_EXIF = listOf(
            android.media.ExifInterface.TAG_DATETIME_ORIGINAL, android.media.ExifInterface.TAG_DATETIME_DIGITIZED,
            android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL, android.media.ExifInterface.TAG_MAKE, android.media.ExifInterface.TAG_MODEL,
            android.media.ExifInterface.TAG_F_NUMBER, android.media.ExifInterface.TAG_EXPOSURE_TIME, "ISOSpeedRatings",
            android.media.ExifInterface.TAG_FOCAL_LENGTH, android.media.ExifInterface.TAG_GPS_LATITUDE, android.media.ExifInterface.TAG_GPS_LATITUDE_REF,
            android.media.ExifInterface.TAG_GPS_LONGITUDE, android.media.ExifInterface.TAG_GPS_LONGITUDE_REF, android.media.ExifInterface.TAG_GPS_ALTITUDE,
            android.media.ExifInterface.TAG_GPS_ALTITUDE_REF, android.media.ExifInterface.TAG_GPS_DATESTAMP, android.media.ExifInterface.TAG_GPS_TIMESTAMP,
        )

        private fun copiarExif(ctx: Context, de: Uri, a: Uri) {
            val origen = ctx.contentResolver.openInputStream(de)?.use { android.media.ExifInterface(it) } ?: return
            ctx.contentResolver.openFileDescriptor(a, "rw")?.use { fd ->
                val destino = android.media.ExifInterface(fd.fileDescriptor)
                for (t in ETIQUETAS_EXIF) origen.getAttribute(t)?.let { destino.setAttribute(t, it) }
                // Los píxeles ya van girados: la copia se ve derecha sin girarla otra vez.
                destino.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL.toString())
                destino.setAttribute(android.media.ExifInterface.TAG_SOFTWARE, "SIOS")
                destino.saveAttributes()
            }
        }
    }
}
