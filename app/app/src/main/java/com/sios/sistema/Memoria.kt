package com.sios.sistema

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * La memoria del móvil: un índice que vive solo en el móvil con el texto de las fotos y capturas
 * (leído aquí mismo, sin conexión), los documentos (PDF y texto) de Descargas y Documentos, y el
 * historial de notificaciones. Claude busca en él cuando le preguntas «¿dónde está la foto del DNI?»
 * o «¿cuál era el wifi del hotel?». Nada sale del móvil salvo lo que Claude encuentra al buscar.
 */
object Memoria {
    private const val MAX_POR_PASADA = 150

    private class Base(ctx: Context) : SQLiteOpenHelper(ctx, "memoria.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            // Búsqueda sin tildes ni mayúsculas: «informacion» encuentra «Información».
            db.execSQL("CREATE VIRTUAL TABLE elementos USING fts4(tipo, titulo, texto, fecha, ruta, notindexed=tipo, notindexed=fecha, notindexed=ruta, tokenize=unicode61 \"remove_diacritics=1\")")
            db.execSQL("CREATE TABLE vistos (ruta TEXT PRIMARY KEY, modificado INTEGER)")
        }

        override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) {}
    }

    @Volatile private var base: Base? = null
    private fun db(ctx: Context) = (base ?: synchronized(this) { base ?: Base(ctx.applicationContext).also { base = it } }).writableDatabase

    fun activa(ctx: Context) = ctx.getSharedPreferences("memoria", Context.MODE_PRIVATE).getBoolean("activa", true)

    fun activar(ctx: Context, si: Boolean) {
        ctx.getSharedPreferences("memoria", Context.MODE_PRIVATE).edit().putBoolean("activa", si).apply()
        if (!si) borrarTodo(ctx) else IndexarJob.programar(ctx, ahora = true)
    }

    fun borrarTodo(ctx: Context) {
        db(ctx).apply { execSQL("DELETE FROM elementos"); execSQL("DELETE FROM vistos") }
    }

    fun cuenta(ctx: Context): Int = db(ctx).rawQuery("SELECT count(*) FROM elementos", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    // ---------- Copia de seguridad ----------

    /** La base de datos entera, en una foto coherente (VACUUM INTO), para la copia. */
    fun copiar(ctx: Context): ByteArray? = runCatching {
        val tmp = File(ctx.cacheDir, "memoria-copia.db")
        tmp.delete()
        db(ctx).execSQL("VACUUM INTO '${tmp.path.replace("'", "''")}'")
        tmp.readBytes().also { tmp.delete() }
    }.getOrNull()

    /** Cambia la memoria por la de una copia (la app se reinicia justo después). */
    fun reemplazar(ctx: Context, datos: ByteArray) = synchronized(this) {
        base?.close()
        base = null
        val f = ctx.getDatabasePath("memoria.db")
        File(f.path + "-wal").delete()
        File(f.path + "-shm").delete()
        f.parentFile?.mkdirs()
        f.writeBytes(datos)
    }

    // ---------- Indexar ----------

    /** Una pasada: fotos y capturas nuevas, y documentos nuevos o cambiados. Devuelve cuántos ha añadido. */
    /**
     * Las fotos indexadas antes de que hubiera etiquetas de contenido se vuelven a mirar una vez
     * (para que «fotos de la playa» las encuentre).
     */
    private fun repasarParaEtiquetas(ctx: Context) {
        val p = ctx.getSharedPreferences("memoria", Context.MODE_PRIVATE)
        // Las etiquetas salen de CLIP (el de «Buscar en imágenes»): si se descarga después, se repasa una vez.
        val clave = if (MemoriaPantallaModelo.listo(ctx)) "etiquetas_clip_v1" else "etiquetas_v1"
        if (p.getBoolean(clave, false)) return
        runCatching { db(ctx).delete("vistos", "ruta LIKE 'content://media/%'", null) }
        p.edit().putBoolean("etiquetas_v1", true).putBoolean(clave, true).apply()
    }

    fun indexar(ctx: Context): Int {
        repasarParaEtiquetas(ctx)
        if (!activa(ctx)) return 0
        var hechos = 0
        hechos += fotos(ctx, MAX_POR_PASADA)
        hechos += documentos(ctx, MAX_POR_PASADA - hechos)
        // CLIP ocupa mucha memoria: se suelta al rato si nadie más lo usa.
        if (hechos > 0) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ MemoriaPantallaModelo.liberarSiOcioso(120_000) }, 125_000)
        return hechos
    }

    private fun visto(ctx: Context, ruta: String, modificado: Long): Boolean =
        db(ctx).rawQuery("SELECT 1 FROM vistos WHERE ruta = ? AND modificado = ?", arrayOf(ruta, modificado.toString())).use { it.moveToFirst() }

    private fun guardar(ctx: Context, tipo: String, titulo: String, texto: String, fecha: Long, ruta: String, modificado: Long) {
        val d = db(ctx)
        d.beginTransaction()
        try {
            d.delete("elementos", "ruta = ?", arrayOf(ruta))
            d.insert("elementos", null, ContentValues().apply {
                put("tipo", tipo); put("titulo", titulo); put("texto", texto); put("fecha", fecha.toString()); put("ruta", ruta)
            })
            d.insertWithOnConflict("vistos", null, ContentValues().apply { put("ruta", ruta); put("modificado", modificado) }, SQLiteDatabase.CONFLICT_REPLACE)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    private fun fotos(ctx: Context, limite: Int): Int {
        if (limite <= 0) return 0
        var hechos = 0
        val col = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.RELATIVE_PATH,
        )
        runCatching {
            ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, col, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")
        }.getOrNull()?.use { c ->
            while (c.moveToNext() && hechos < limite) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                val modificado = c.getLong(3)
                if (visto(ctx, uri.toString(), modificado)) continue
                val ruta = c.getString(4).orEmpty()
                val tipo = if (ruta.contains("Screenshot", ignoreCase = true) || ruta.contains("Captura", ignoreCase = true)) "captura" else "foto"
                val texto = runCatching { leerImagen(ctx, uri) }.onFailure { android.util.Log.w("SistemaSios", "Memoria: no se pudo leer $uri", it) }.getOrDefault("")
                val fecha = c.getLong(2).takeIf { it > 0 } ?: (modificado * 1000)
                guardar(ctx, tipo, c.getString(1).orEmpty(), texto, fecha, uri.toString(), modificado)
                hechos++
            }
        }
        return hechos
    }

    private fun documentos(ctx: Context, limite: Int): Int {
        if (limite <= 0 || !Environment.isExternalStorageManager()) return 0
        var hechos = 0
        val raiz = Environment.getExternalStorageDirectory()
        val carpetas = listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DOCUMENTS).map { File(raiz, it) }
        val archivos = carpetas.flatMap { d -> d.walkTopDown().maxDepth(4).filter { it.isFile && it.extension.lowercase() in EXTENSIONES }.toList() }
            .sortedByDescending { it.lastModified() }
        for (f in archivos) {
            if (hechos >= limite) break
            val modificado = f.lastModified()
            if (visto(ctx, f.path, modificado)) continue
            val texto = runCatching {
                if (f.extension.equals("pdf", true)) leerPdf(ctx, f) else f.readText().take(20_000)
            }.getOrDefault("")
            guardar(ctx, "documento", f.name, texto, modificado, f.path, modificado)
            hechos++
        }
        return hechos
    }

    private val EXTENSIONES = setOf("pdf", "txt", "md", "csv", "json")

    private fun leerImagen(ctx: Context, uri: Uri): String {
        val b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            val lado = maxOf(info.size.width, info.size.height)
            if (lado > 2000) dec.setTargetSampleSize((lado + 1999) / 2000)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        // Con transparencia (PNG), el lector vería letras negras sobre negro: se pone fondo blanco.
        val opaca = if (!b.hasAlpha()) b else Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        }
        // El texto que tenga y, además, qué se ve en ella (en español, para buscar «playa», «perro»…).
        val etiquetas = runCatching { etiquetar(ctx, opaca) }.onFailure { android.util.Log.w("SistemaSios", "Memoria: etiquetas", it) }.getOrDefault("")
        return listOf(Ocr.leer(ctx, opaca), etiquetas).filter { it.isNotBlank() }.joinToString("\n")
    }

    /**
     * Qué se ve en la foto, con CLIP en el móvil (el mismo modelo que «Buscar en imágenes» de la
     * memoria de pantalla). Solo si ya está descargado: nunca se descarga para esto. Sin él, la foto
     * se encuentra por su texto, su nombre y su fecha.
     */
    private fun etiquetar(ctx: Context, b: Bitmap): String {
        if (!MemoriaPantallaModelo.listo(ctx)) return ""
        val imagen = MemoriaPantallaModelo.huellaImagen(ctx, b) ?: return ""
        val textos = huellasEtiquetas(ctx) ?: return ""
        // Clasificación «sin entrenar» de CLIP: parecido con cada frase, en probabilidades (×100, softmax).
        val parecidos = textos.map { t -> var s = 0f; for (i in t.indices) s += t[i] * imagen[i]; s }
        val maximo = parecidos.max()
        val exp = parecidos.map { kotlin.math.exp((it - maximo) * 100.0) }
        val suma = exp.sum()
        // Las claras (≥ 10 % y no muy por detrás de la primera), hasta tres. Probado con fotos de casa,
        // gatos en un sofá, un ticket y capturas: «dormitorio, mueble», «sofá, gato», «ticket».
        val primera = exp.max() / suma
        val elegidas = exp.indices.filter { exp[it] / suma >= 0.1 && exp[it] / suma >= primera * 0.35 }.sortedByDescending { exp[it] }.take(3)
        if (elegidas.isEmpty()) return ""
        return "Se ve: " + elegidas.joinToString(", ") { ETIQUETAS[it] }
    }

    @Volatile private var huellas: List<FloatArray>? = null

    /** La huella de cada etiqueta (frases en español), calculada una vez y guardada junto al modelo. */
    @Synchronized
    private fun huellasEtiquetas(ctx: Context): List<FloatArray>? {
        huellas?.let { return it }
        val archivo = File(ctx.noBackupFilesDir, "memoria_pantalla/modelo/etiquetas-${ETIQUETAS.joinToString("|").hashCode()}.bin")
        val leidas = runCatching {
            val b = java.nio.ByteBuffer.wrap(archivo.readBytes()).asFloatBuffer()
            val dim = b.remaining() / ETIQUETAS.size
            List(ETIQUETAS.size) { FloatArray(dim).also { v -> b.get(v) } }
        }.getOrNull()
        val r = leidas ?: ETIQUETAS.map { MemoriaPantallaModelo.huellaTexto(ctx, "una foto: $it") ?: return null }.also { l ->
            runCatching {
                val bb = java.nio.ByteBuffer.allocate(l.sumOf { it.size } * 4)
                l.forEach { v -> v.forEach { bb.putFloat(it) } }
                archivo.parentFile?.mkdirs()
                archivo.writeBytes(bb.array())
            }
        }
        huellas = r
        return r
    }

    /** Lo que se reconoce en las fotos, en español (con sinónimos para encontrarlas mejor). */
    private val ETIQUETAS = listOf(
        "playa costa", "mar", "cielo", "atardecer puesta de sol", "montaña", "lago",
        "río", "cascada", "bosque", "árbol", "flor flores", "planta", "jardín",
        "nieve", "nubes", "desierto", "roca", "perro", "gato", "pájaro", "caballo",
        "mascota", "pez", "insecto", "mariposa", "comida", "tarta pastel", "pizza",
        "pan", "fruta", "verdura", "café", "bebida", "vino", "cerveza",
        "postre", "helado", "comida plato", "persona gente", "sonrisa", "selfi",
        "bebé", "niño niña", "gente multitud", "fiesta", "boda", "coche", "bicicleta bici",
        "moto", "autobús", "tren", "avión", "barco", "edificio", "casa",
        "ciudad", "calle", "puente", "iglesia", "castillo", "monumento", "torre",
        "habitación", "cocina", "dormitorio", "mueble", "sofá", "mesa", "silla",
        "cama", "ventana", "puerta", "documento", "papel", "ticket recibo factura",
        "cartel", "captura", "texto", "libro", "ordenador", "móvil teléfono",
        "televisión tele", "juguete", "ropa", "zapato zapatillas", "joya joyas", "bolso bolsa",
        "gafas de sol", "sombrero gorra", "deporte", "fútbol", "baloncesto", "natación piscina",
        "piscina", "estadio", "concierto", "instrumento música", "guitarra",
        "navidad", "fuegos artificiales", "noche", "luna", "sol", "arcoíris",
    )

    private fun leerPdf(ctx: Context, f: File): String = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { pdf ->
            (0 until minOf(pdf.pageCount, 3)).joinToString("\n") { n ->
                pdf.openPage(n).use { p ->
                    val ancho = 1400
                    val b = Bitmap.createBitmap(ancho, (ancho.toFloat() * p.height / p.width).toInt().coerceAtMost(2800), Bitmap.Config.ARGB_8888)
                    Canvas(b).drawColor(Color.WHITE)
                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    Ocr.leer(ctx, b)
                }
            }
        }
    }

    /** Las notificaciones también se recuerdan (códigos, direcciones, wifis que te mandan…). */
    fun anotarAviso(ctx: Context, a: Bandeja.Aviso) {
        if (!activa(ctx)) return
        runCatching { guardar(ctx, "aviso", "${a.app} · ${a.titulo}", a.texto, a.cuando, "aviso:${a.clave}:${a.texto.hashCode()}", a.cuando) }
    }

    /** ¿El chat se llama así? Por nombre (sin tildes) o, si es un número, por sus cifras («600111222» = «600 11 12 22»). */
    fun coincide(chat: String, buscado: String): Boolean {
        if (normalizar(chat).contains(normalizar(buscado).trim())) return true
        val cifras = buscado.filter(Char::isDigit)
        return cifras.length >= 6 && chat.filter(Char::isDigit).contains(cifras)
    }

    /** Mensajes de un chat o grupo (por su nombre) llegados como aviso en las últimas `horas`. */
    fun avisosDe(ctx: Context, nombre: String, horas: Int): List<Triple<Long, String, String>> {
        if (!activa(ctx)) return emptyList()
        val desde = System.currentTimeMillis() - horas * 3_600_000L
        val lista = mutableListOf<Triple<Long, String, String>>()
        runCatching {
            db(ctx).rawQuery("SELECT titulo, texto, fecha FROM elementos WHERE tipo = 'aviso' ORDER BY rowid DESC LIMIT 3000", null).use { c ->
                while (c.moveToNext()) {
                    val fecha = c.getString(2).toLongOrNull() ?: continue
                    if (fecha < desde) continue
                    val titulo = c.getString(0)
                    // «App · Conversación»: el nombre del chat va detrás del punto.
                    if (coincide(titulo.substringAfter(" · "), nombre)) lista += Triple(fecha, titulo, c.getString(1))
                }
            }
        }
        return lista
    }

    // ---------- Buscar ----------

    private fun normalizar(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    /** Busca palabras (con prefijo: «factur» encuentra «factura»). Lo más reciente primero. */
    fun buscar(ctx: Context, consulta: String, limite: Int = 12): JSONArray {
        val palabras = normalizar(consulta).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        if (palabras.isEmpty()) return JSONArray()
        val consultaFts = palabras.joinToString(" OR ") { "$it*" }
        val filas = JSONArray()
        db(ctx).rawQuery(
            "SELECT rowid, tipo, titulo, fecha, snippet(elementos, '«', '»', '…', -1, 12), titulo || ' ' || texto FROM elementos WHERE elementos MATCH ? LIMIT 300",
            arrayOf(consultaFts),
        ).use { c ->
            val resultados = mutableListOf<Pair<Int, JSONObject>>()
            while (c.moveToNext()) {
                // Puntuación: cuántas palabras distintas de la búsqueda aparecen (las que más, primero).
                val todo = normalizar(c.getString(5))
                val aciertos = palabras.count { todo.contains(it) }
                resultados += aciertos to JSONObject()
                    .put("id", c.getLong(0)).put("tipo", c.getString(1)).put("titulo", c.getString(2))
                    .put("fecha", c.getString(3).toLongOrNull() ?: 0).put("fragmento", c.getString(4))
            }
            resultados.sortedWith(compareByDescending<Pair<Int, JSONObject>> { it.first }.thenByDescending { it.second.getLong("fecha") })
                .take(limite).forEach { filas.put(it.second) }
        }
        return filas
    }

    /**
     * Qué sabe la memoria de un número de teléfono (los 9 últimos dígitos), para decir quién puede
     * ser cuando llama: el último mensaje o aviso en el que aparece. null si no aparece.
     */
    fun buscarNumero(ctx: Context, digitos: String): String? {
        if (digitos.length < 6) return null
        return db(ctx).rawQuery(
            "SELECT tipo, titulo, texto, fecha FROM elementos WHERE replace(replace(replace(titulo || ' ' || texto, ' ', ''), '-', ''), '.', '') LIKE ? ORDER BY CAST(fecha AS INTEGER) DESC LIMIT 1",
            arrayOf("%$digitos%"),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val fecha = c.getString(3).toLongOrNull() ?: 0
            val cuando = if (System.currentTimeMillis() - fecha < 60_000) ctx.getString(com.sios.R.string.memoria_hace_un_momento)
                else android.text.format.DateUtils.getRelativeTimeSpanString(fecha, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
            val texto = c.getString(2).replace(Regex("\\s+"), " ").take(120)
            val dondeApp = c.getString(1).substringBefore(" · ")
            "${cuando.toString().replaceFirstChar { it.uppercase() }} ($dondeApp): «$texto»"
        }
    }

    /** La ruta (uri de MediaStore o archivo) de un resultado. */
    fun ruta(ctx: Context, id: Long): Pair<String, String>? =
        db(ctx).rawQuery("SELECT tipo, ruta FROM elementos WHERE rowid = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }

    /** La imagen de un resultado (foto, captura o primera página del PDF), en JPEG base64, para que Claude la mire. */
    fun imagen(ctx: Context, id: Long): String {
        val (tipo, ruta) = ruta(ctx, id) ?: error("No existe ese resultado")
        val b = when {
            tipo == "documento" && ruta.endsWith(".pdf", true) -> ParcelFileDescriptor.open(File(ruta), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf -> pdf.openPage(0).use { p ->
                    Bitmap.createBitmap(1100, (1100f * p.height / p.width).toInt(), Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.WHITE); p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                } }
            }
            tipo == "foto" || tipo == "captura" -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, Uri.parse(ruta))) { dec, info, _ ->
                val lado = maxOf(info.size.width, info.size.height)
                if (lado > 1400) dec.setTargetSampleSize((lado + 1399) / 1400)
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            else -> error("Ese resultado no es una imagen ni un PDF")
        }
        return Compartido.aJpeg(b)
    }

    /** Abre un resultado con su app (galería, visor de PDF…). */
    fun abrir(ctx: Context, id: Long) {
        val (tipo, ruta) = ruta(ctx, id) ?: error("No existe ese resultado")
        val (uri, mime) = when (tipo) {
            "foto", "captura" -> Uri.parse(ruta) to "image/*"
            "documento" -> {
                val f = File(ruta)
                val tipoMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
                val enStore = buscarEnMediaStore(ctx, f) ?: error("No se puede abrir ese archivo")
                enStore to tipoMime
            }
            else -> error("Un aviso no se puede abrir")
        }
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun buscarEnMediaStore(ctx: Context, f: File): Uri? {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        return ctx.contentResolver.query(uri, arrayOf(MediaStore.Files.FileColumns._ID), "${MediaStore.Files.FileColumns.DATA} = ?", arrayOf(f.path), null)?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(uri, c.getLong(0)) else null
        }
    }
}
