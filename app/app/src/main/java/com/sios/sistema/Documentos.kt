package com.sios.sistema

import com.sios.ui.conMarca

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import com.sios.R
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Escáner de documentos con Claude: el texto se lee en el móvil ([Ocr]), Claude (Haiku) dice qué es
 * —factura, ticket, DNI, garantía…—, el importe y las fechas; se guarda como PDF con un nombre claro
 * en Documentos › Claude, el gasto se anota solo y, si vence o caduca, «Al calendario» lo deja
 * preparado (el usuario lo confirma: nunca se crean citas solas).
 */
object Documentos {
    private const val CANAL = "documentos"
    private val http by lazy { okhttp3.OkHttpClient.Builder().callTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build() }

    /**
     * Las páginas del escáner de SIOS ([EscanerActivity]: ya recortadas, enderezadas y limpias), en
     * JPEG en la caché. Se leen, se hace el PDF y se borran.
     */
    fun desdePaginas(ctx: Context, archivos: List<java.io.File>) {
        val app = ctx.applicationContext
        Thread {
            val imagenes = archivos.mapNotNull { f -> runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() }
            try {
                procesar(app, imagenes)
            } finally {
                archivos.forEach { it.delete() }
            }
        }.start()
    }

    private fun procesar(ctx: Context, paginas: List<Bitmap>) {
        if (paginas.isEmpty()) return
        avisar(ctx, ctx.getString(R.string.doc_leyendo), "", null, null)
        // 1. El texto, en el móvil.
        val texto = paginas.joinToString("\n\n") { b -> Ocr.leer(ctx, b) }.trim()
        // 2. Qué es (Claude, en el servicio).
        val d = runCatching {
            val r = okhttp3.Request.Builder().url("http://127.0.0.1:8765/documento")
                .post(JSONObject().put("texto", texto).toString().toRequestBody("application/json".toMediaType())).build()
            http.newCall(r).execute().use { JSONObject(it.body.string()) }
        }.getOrElse { JSONObject().put("tipo", "otro").put("titulo", ctx.getString(R.string.doc_sin_titulo)) }
        val titulo = d.optString("titulo").ifBlank { ctx.getString(R.string.doc_sin_titulo) }.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim()
        val fecha = d.optString("fecha").takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) } ?: SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        // 3. El PDF, con su nombre, en Documentos › Claude.
        val nombre = "$titulo $fecha.pdf"
        val destino = ctx.contentResolver.insert(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/SIOS")
            },
        ) ?: return
        ctx.contentResolver.openOutputStream(destino)?.use { salida ->
            PdfDocument().apply {
                paginas.forEachIndexed { i, b ->
                    // A4 a 72 ppp, la imagen entera dentro con margen.
                    val p = startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                    val escala = minOf(555f / b.width, 802f / b.height)
                    val w = b.width * escala; val h = b.height * escala
                    p.canvas.drawBitmap(b, null, android.graphics.RectF((595 - w) / 2, (842 - h) / 2, (595 + w) / 2, (842 + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                    finishPage(p)
                }
                writeTo(salida)
                close()
            }
        }
        Thread { runCatching { Memoria.indexar(ctx) } }.start()
        // El servicio se queda con dónde está el PDF (para encontrarlo luego desde Claude).
        d.optString("id").takeIf { it.isNotBlank() }?.let { id ->
            runCatching {
                val r = okhttp3.Request.Builder().url("http://127.0.0.1:8765/documento/pdf")
                    .post(JSONObject().put("id", id).put("pdf", destino.toString()).toString().toRequestBody("application/json".toMediaType())).build()
                http.newCall(r).execute().close()
            }
        }
        // 4. El aviso: qué es, importe y fechas; abrir, al calendario y Claude.
        val partes = buildList {
            d.optDouble("importe").takeIf { !it.isNaN() && it > 0 }?.let { add(String.format(Locale.getDefault(), "%.2f €", it)) }
            d.optString("vence").takeIf { it.length == 10 }?.let { add(ctx.getString(R.string.doc_vence, fechaCorta(it))) }
            d.optString("caduca").takeIf { it.length == 10 }?.let { add(ctx.getString(R.string.doc_caduca, fechaCorta(it))) }
            d.optString("devolucion_hasta").takeIf { it.length == 10 }?.let { add(ctx.getString(R.string.doc_devolver, fechaCorta(it))) }
            if (d.optBoolean("gasto")) add(ctx.getString(R.string.doc_gasto))
        }
        val cuerpo = listOf(partes.joinToString(" · "), d.optString("resumen")).filter { it.isNotBlank() }.joinToString("\n")
        val evento = d.optString("vence").takeIf { it.length == 10 }?.let { ctx.getString(R.string.doc_evento_pagar, titulo) to it }
            ?: d.optString("caduca").takeIf { it.length == 10 }?.let { ctx.getString(R.string.doc_evento_renovar, titulo) to restarDias(it, 30) }
        // Facturas de lo que se paga cada mes: «¿Puedo pagar menos?» se lo pregunta a Claude.
        val ahorro = d.optString("categoria").takeIf { d.optString("tipo") in setOf("factura", "recibo") && it in setOf("luz", "gas", "agua", "movil", "internet", "seguro") }
            ?.let { ctx.getString(R.string.doc_ahorro_pregunta, titulo, d.optString("id")) }
        avisar(ctx, titulo, cuerpo, destino, evento, ahorro)
    }

    private fun fechaCorta(iso: String) = runCatching {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).parse(iso)!!)
    }.getOrDefault(iso)

    private fun restarDias(iso: String, dias: Int) = runCatching {
        val f = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        f.format(Date(f.parse(iso)!!.time - dias * 86_400_000L))
    }.getOrDefault(iso)

    private fun avisar(ctx: Context, titulo: String, texto: String, pdf: Uri?, evento: Pair<String, String>?, ahorro: String? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL, ctx.getString(R.string.doc_canal), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        }
        val b = Notification.Builder(ctx, CANAL).conMarca()
            .setSmallIcon(com.sios.ui.Marca.recurso())
            .setColor(com.sios.ui.Apariencia.colorAcento(true).toInt())
            .setContentTitle(titulo)
            .setContentText(texto.lines().firstOrNull().orEmpty())
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setGroup("documentos")
            .setOnlyAlertOnce(true)
        if (pdf == null) {
            b.setProgress(0, 0, true).setOngoing(true)
        } else {
            val abrir = PendingIntent.getActivity(
                ctx, 91, Intent(Intent.ACTION_VIEW).setDataAndType(pdf, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            b.setContentIntent(abrir).setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, ctx.getString(R.string.doc_abrir), abrir).build())
            ahorro?.let { pregunta ->
                val claude = PendingIntent.getActivity(
                    ctx, 93, Intent(ctx, AsistenteActivity::class.java).putExtra(android.app.SearchManager.QUERY, pregunta).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.addAction(Notification.Action.Builder(null, ctx.getString(R.string.doc_ahorro), claude).build())
            }
            evento?.let { (que, dia) ->
                val inicio = runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).parse("$dia 09:00")!!.time }.getOrNull()
                if (inicio != null) {
                    // El calendario con la cita preparada: el usuario la guarda (o no).
                    val cal = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, que)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, inicio)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, inicio + 30 * 60_000)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    b.addAction(Notification.Action.Builder(null, ctx.getString(R.string.doc_calendario), PendingIntent.getActivity(ctx, 92, cal, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
                }
            }
        }
        nm.notify("documento", 0, b.build())
    }
}
