package com.sios.sistema

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.util.Base64
import com.sios.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Lo que llega a Claude desde «Compartir» en otra app: un texto o enlace, fotos o un PDF (sus
 * primeras páginas, como imágenes). Se prepara aquí para enseñarlo en la hoja y enviarlo con la
 * primera pregunta.
 */
class Compartido(
    val titulo: String, // «Un enlace», «3 fotos», «Un PDF · 4 páginas»…
    val detalle: String, // el texto o el nombre del archivo, para la vista previa
    val miniatura: Bitmap?,
    val imagenes: List<String>, // JPEG en base64
    val texto: String?,
    val descripcion: String, // para Claude: «un enlace de Chrome», «2 fotos»…
) {
    fun json(): JSONObject = JSONObject()
        .put("descripcion", descripcion)
        .put("imagenes", JSONArray(imagenes))
        .apply { if (texto != null) put("texto", texto) }

    companion object {
        private const val MAX_IMAGENES = 6
        private const val MAX_PAGINAS = 5

        fun esCompartir(i: Intent) = i.action == Intent.ACTION_SEND || i.action == Intent.ACTION_SEND_MULTIPLE

        /** Lee el intent (fuera del hilo principal: decodificar fotos y PDF lleva un momento). */
        fun leer(ctx: Context, i: Intent, deApp: String?): Compartido? {
            val r = ctx.resources
            val uris = buildList {
                if (i.action == Intent.ACTION_SEND_MULTIPLE) {
                    i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { addAll(it) }
                } else {
                    i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let { add(it) }
                }
            }
            val texto = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val asunto = i.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf { it.isNotEmpty() }
            val desde = deApp?.let { " (${r.getString(R.string.compartido_desde, it)})" } ?: ""

            val tipos = uris.map { ctx.contentResolver.getType(it) ?: i.type ?: "" }
            // Un PDF: sus primeras páginas como imágenes.
            val pdf = uris.indices.firstOrNull { tipos[it] == "application/pdf" }
            if (pdf != null) {
                val (paginas, total) = paginasPdf(ctx, uris[pdf])
                if (paginas.isEmpty()) return null
                val nombre = nombre(ctx, uris[pdf]) ?: "PDF"
                return Compartido(
                    titulo = r.getQuantityString(R.plurals.compartido_pdf, total, total),
                    detalle = nombre,
                    miniatura = paginas.first(),
                    imagenes = paginas.map(::aJpeg),
                    texto = null,
                    descripcion = "un PDF «$nombre» de $total páginas" + (if (total > paginas.size) " (van las ${paginas.size} primeras)" else "") + desde,
                )
            }
            // Fotos
            val fotos = uris.indices.filter { tipos[it].startsWith("image/") }.take(MAX_IMAGENES).mapNotNull { foto(ctx, uris[it]) }
            if (fotos.isNotEmpty()) {
                return Compartido(
                    titulo = r.getQuantityString(R.plurals.compartido_fotos, fotos.size, fotos.size),
                    detalle = texto ?: asunto ?: "",
                    miniatura = fotos.first(),
                    imagenes = fotos.map(::aJpeg),
                    texto = texto,
                    descripcion = (if (fotos.size == 1) "una foto" else "${fotos.size} fotos") + desde,
                )
            }
            // Un archivo de texto
            val txt = uris.indices.firstOrNull { tipos[it].startsWith("text/") }
            val contenido = texto ?: txt?.let { leerTexto(ctx, uris[it]) }
            if (contenido.isNullOrBlank()) return null
            val enlace = Regex("""^https?://\S+$""").matches(contenido)
            return Compartido(
                titulo = r.getString(if (enlace) R.string.compartido_enlace else R.string.compartido_texto),
                detalle = listOfNotNull(asunto, contenido).joinToString(" · ").replace(Regex("\\s+"), " ").take(160),
                miniatura = null,
                imagenes = emptyList(),
                texto = listOfNotNull(asunto, contenido).joinToString("\n\n"),
                descripcion = (if (enlace) "un enlace" else "un texto") + desde,
            )
        }

        private fun foto(ctx: Context, uri: Uri): Bitmap? = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                val lado = maxOf(info.size.width, info.size.height)
                if (lado > 1568) dec.setTargetSampleSize((lado + 1567) / 1568)
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull()

        private fun paginasPdf(ctx: Context, uri: Uri): Pair<List<Bitmap>, Int> = runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { fd ->
                PdfRenderer(fd).use { pdf ->
                    val paginas = (0 until minOf(pdf.pageCount, MAX_PAGINAS)).map { n ->
                        pdf.openPage(n).use { p ->
                            val ancho = 1240
                            val alto = (ancho.toFloat() * p.height / p.width).toInt().coerceAtMost(2400)
                            Bitmap.createBitmap(ancho, alto, Bitmap.Config.ARGB_8888).also { b ->
                                Canvas(b).drawColor(Color.WHITE)
                                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                    paginas to pdf.pageCount
                }
            }
        }.getOrDefault(emptyList<Bitmap>() to 0)

        private fun leerTexto(ctx: Context, uri: Uri): String? = runCatching {
            ctx.contentResolver.openInputStream(uri)!!.use { it.readNBytes(60_000).toString(Charsets.UTF_8) }
        }.getOrNull()

        private fun nombre(ctx: Context, uri: Uri): String? = runCatching {
            ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()

        fun aJpeg(b: Bitmap): String {
            val escala = minOf(1f, 1568f / maxOf(b.width, b.height))
            val r = if (escala < 1f) Bitmap.createScaledBitmap(b, (b.width * escala).toInt(), (b.height * escala).toInt(), true) else b
            val salida = ByteArrayOutputStream()
            r.compress(Bitmap.CompressFormat.JPEG, 82, salida)
            return Base64.encodeToString(salida.toByteArray(), Base64.NO_WRAP)
        }
    }
}
