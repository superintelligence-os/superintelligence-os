package com.sios.sistema

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * La «huella» de las imágenes (y de lo que se busca), con CLIP en el propio móvil.
 *
 * Dos modelos que comparten el mismo espacio de 512 números:
 *  - Imágenes: CLIP ViT-B/32 de OpenAI (licencia MIT), exportado a ONNX y cuantizado a 8 bits por
 *    Xenova (Transformers.js), en huggingface.co/Xenova/clip-vit-base-patch32 (MIT).
 *  - Texto de la consulta: clip-ViT-B-32-multilingual-v1 de sentence-transformers (Apache 2.0), un
 *    DistilBERT multilingüe destilado sobre ese mismo CLIP: entiende español, inglés, alemán, francés,
 *    italiano y portugués («el sofá azul» encuentra la foto del sofá azul). ONNX cuantizado para arm64.
 * Ambas licencias permiten redistribuirlos. No van en el APK: se descargan de Hugging Face al activar
 * la función, en una revisión fija y comprobando el SHA-256 de cada archivo (el de su almacén LFS).
 * Solo se guarda el vector: ni la imagen ni una descripción.
 */
object MemoriaPantallaModelo {
    private class Archivo(val nombre: String, val url: String, val bytes: Long, val sha256: String)

    private const val VISION = "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/6b7c523703ae26e39be4b03774966f8571c7f523"
    private const val TEXTO = "https://huggingface.co/sentence-transformers/clip-ViT-B-32-multilingual-v1/resolve/58edf8cada9e398793dca955574a48cbb7f18be2"

    private val ARCHIVOS = listOf(
        Archivo("vision.onnx", "$VISION/onnx/vision_model_quantized.onnx", 89_117_001, "583fd1110a514667812fee7d684952aaf82a99b959760c8d7dca7e0ab9839299"),
        Archivo("texto.onnx", "$TEXTO/onnx/model_qint8_arm64.onnx", 135_336_307, "3bed77e83926519660b5c0833cb3321cfde952331edec4f7d501c7d384b8a8a7"),
        Archivo("proyeccion.safetensors", "$TEXTO/2_Dense/model.safetensors", 1_572_984, "d12568dc7300970a4d3dbb49068ad16cd89b99840b74b026f8e48071e9414f74"),
        Archivo("vocab.txt", "$TEXTO/vocab.txt", 995_526, "fe0fda7c425b48c516fc8f160d594c8022a0808447475c1a7c6d6479763f310c"),
    )

    /** Lo que ocupa el modelo descargado (se enseña antes de descargarlo). */
    val TAMANO: Long = ARCHIVOS.sumOf { it.bytes }

    private fun carpeta(ctx: Context) = File(ctx.noBackupFilesDir, "memoria_pantalla/modelo").apply { mkdirs() }

    fun listo(ctx: Context): Boolean = ARCHIVOS.all { File(carpeta(ctx), it.nombre).length() == it.bytes }

    // ---------- Descarga ----------

    sealed interface Descarga {
        data object Parada : Descarga
        data class EnCurso(val hecho: Long, val total: Long) : Descarga
        data object Comprobando : Descarga
        data object Lista : Descarga
        data class Fallo(val sinRed: Boolean) : Descarga
    }

    private val _descarga = MutableStateFlow<Descarga>(Descarga.Parada)
    val descarga: StateFlow<Descarga> = _descarga

    @Volatile private var hilo: Thread? = null

    /**
     * Descarga lo que falte, reanudando donde se quedó (Range) y comprobando cada archivo. En un hilo
     * propio: el proceso de SIOS sigue vivo (es el inicio y tiene el servicio de accesibilidad).
     */
    @Synchronized
    fun descargar(ctx: Context, alTerminar: (Boolean) -> Unit = {}) {
        if (hilo?.isAlive == true) return
        val app = ctx.applicationContext
        if (listo(app)) { _descarga.value = Descarga.Lista; alTerminar(true); return }
        hilo = Thread {
            val ok = runCatching { bajarTodo(app) }.onFailure { android.util.Log.w("SistemaSios", "Memoria de pantalla: descarga", it) }.isSuccess
            _descarga.value = if (ok) Descarga.Lista else Descarga.Fallo(sinRed = !hayRed(app))
            alTerminar(ok)
        }.apply { name = "memoria-modelo"; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    fun cancelar() {
        hilo?.interrupt()
        hilo = null
        _descarga.value = Descarga.Parada
    }

    private fun hayRed(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /** ¿Con datos móviles (o una red que se paga por uso)? Para avisar antes de bajar el modelo. */
    fun redDePago(ctx: Context): Boolean = ctx.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    }

    private fun bajarTodo(ctx: Context) {
        val dir = carpeta(ctx)
        fun hechoAntes(hasta: Int) = ARCHIVOS.take(hasta).sumOf { it.bytes }
        ARCHIVOS.forEachIndexed { i, a ->
            val destino = File(dir, a.nombre)
            if (destino.length() == a.bytes) return@forEachIndexed
            val parte = File(dir, a.nombre + ".parte")
            var ultimoAviso = 0L
            while (parte.length() < a.bytes) {
                if (Thread.interrupted()) throw InterruptedException()
                val desde = parte.length()
                val peticion = Request.Builder().url(a.url).apply { if (desde > 0) header("Range", "bytes=$desde-") }.build()
                http.newCall(peticion).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    // Si el servidor no reanuda (200 en vez de 206), se empieza de cero.
                    val reanuda = r.code == 206
                    RandomAccessFile(parte, "rw").use { f ->
                        if (!reanuda) f.setLength(0) else f.seek(desde)
                        val entrada = r.body.byteStream()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.interrupted()) throw InterruptedException()
                            val n = entrada.read(buf)
                            if (n < 0) break
                            f.write(buf, 0, n)
                            val ahora = SystemClock.uptimeMillis()
                            if (ahora - ultimoAviso > 120) {
                                ultimoAviso = ahora
                                _descarga.value = Descarga.EnCurso(hechoAntes(i) + f.filePointer, TAMANO)
                            }
                        }
                    }
                }
            }
            _descarga.value = Descarga.Comprobando
            if (sha256(parte) != a.sha256) {
                parte.delete()
                error("El archivo ${a.nombre} no coincide")
            }
            parte.renameTo(destino)
            _descarga.value = Descarga.EnCurso(hechoAntes(i + 1), TAMANO)
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { e ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = e.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Borra el modelo (al desactivar la función o desde Ajustes). */
    fun borrar(ctx: Context) {
        cancelar()
        liberar()
        carpeta(ctx).deleteRecursively()
        _descarga.value = Descarga.Parada
    }

    // ---------- Sesiones de ONNX Runtime ----------

    private val entorno by lazy { OrtEnvironment.getEnvironment() }
    private var vision: OrtSession? = null
    private var texto: OrtSession? = null
    private var proyeccion: FloatArray? = null
    private var vocabulario: HashMap<String, Int>? = null
    @Volatile private var ultimoUso = 0L

    private fun opciones() = OrtSession.SessionOptions().apply {
        // Dos hilos: rápido sin acaparar el procesador (el usuario sigue usando el móvil).
        setIntraOpNumThreads(2)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
    }

    /** Suelta los modelos de la memoria (unos 250 MB) tras un rato sin usarlos. */
    @Synchronized
    fun liberar() {
        runCatching { vision?.close() }
        runCatching { texto?.close() }
        vision = null
        texto = null
        proyeccion = null
        vocabulario = null
    }

    @Synchronized
    fun liberarSiOcioso(ms: Long) {
        if (SystemClock.uptimeMillis() - ultimoUso > ms) liberar()
    }

    // ---------- Imagen → huella ----------

    /** La huella (512 números, normalizada) de una imagen. null si el modelo no está. */
    @Synchronized
    fun huellaImagen(ctx: Context, imagen: Bitmap): FloatArray? {
        if (!listo(ctx)) return null
        ultimoUso = SystemClock.uptimeMillis()
        val s = vision ?: entorno.createSession(File(carpeta(ctx), "vision.onnx").path, opciones()).also { vision = it }
        val entrada = OnnxTensor.createTensor(entorno, pixeles(imagen), longArrayOf(1, 3, 224, 224))
        entrada.use {
            s.run(mapOf(s.inputNames.first() to entrada)).use { r ->
                // «image_embeds» (ya proyectada); si se llamara de otra forma, la primera salida de 512.
                val nombre = s.outputNames.firstOrNull { it == "image_embeds" } ?: s.outputNames.first()
                val v = (r.get(nombre).get() as OnnxTensor).floatBuffer
                return normalizar(FloatArray(v.remaining()).also { v.get(it) })
            }
        }
    }

    /** Como el preprocesado de CLIP: lado corto a 224, recorte central, RGB normalizado (CHW). */
    private fun pixeles(b: Bitmap): FloatBuffer {
        val escala = 224f / minOf(b.width, b.height)
        val w = maxOf(224, Math.round(b.width * escala))
        val h = maxOf(224, Math.round(b.height * escala))
        val recorte = Bitmap.createBitmap(224, 224, Bitmap.Config.ARGB_8888)
        Canvas(recorte).drawBitmap(
            b, android.graphics.Rect(0, 0, b.width, b.height),
            android.graphics.RectF(-(w - 224) / 2f, -(h - 224) / 2f, (224 + (w - 224) / 2f), (224 + (h - 224) / 2f)),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        val px = IntArray(224 * 224).also { recorte.getPixels(it, 0, 224, 0, 0, 224, 224) }
        recorte.recycle()
        val media = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val desv = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)
        val fb = FloatBuffer.allocate(3 * 224 * 224)
        for (canal in 0 until 3) {
            val desplaza = 16 - canal * 8
            for (p in px) fb.put((((p shr desplaza) and 0xFF) / 255f - media[canal]) / desv[canal])
        }
        fb.rewind()
        return fb
    }

    // ---------- Texto → huella ----------

    /** La huella de lo que se busca, en el mismo espacio que las imágenes. null si el modelo no está. */
    @Synchronized
    fun huellaTexto(ctx: Context, consulta: String): FloatArray? {
        if (!listo(ctx) || consulta.isBlank()) return null
        ultimoUso = SystemClock.uptimeMillis()
        val dir = carpeta(ctx)
        val s = texto ?: entorno.createSession(File(dir, "texto.onnx").path, opciones()).also { texto = it }
        val vocab = vocabulario ?: HashMap<String, Int>(130_000).also { m ->
            File(dir, "vocab.txt").useLines { l -> l.forEachIndexed { i, t -> m[t] = i } }
            vocabulario = m
        }
        val w = proyeccion ?: leerProyeccion(File(dir, "proyeccion.safetensors")).also { proyeccion = it }
        val ids = Tokenizador(vocab).codificar(consulta, 128)
        val n = ids.size.toLong()
        val entradas = HashMap<String, OnnxTensor>()
        try {
            for (nombre in s.inputNames) entradas[nombre] = when (nombre) {
                "input_ids" -> OnnxTensor.createTensor(entorno, LongBuffer.wrap(ids), longArrayOf(1, n))
                "token_type_ids" -> OnnxTensor.createTensor(entorno, LongBuffer.wrap(LongArray(ids.size)), longArrayOf(1, n))
                else -> OnnxTensor.createTensor(entorno, LongBuffer.wrap(LongArray(ids.size) { 1 }), longArrayOf(1, n)) // attention_mask
            }
            s.run(entradas).use { r ->
                val nombre = s.outputNames.firstOrNull { it == "last_hidden_state" || it == "token_embeddings" } ?: s.outputNames.first()
                val oculto = (r.get(nombre).get() as OnnxTensor).floatBuffer
                // Media de los tokens (como el «Pooling» del modelo) y la capa densa 768 → 512.
                val dim = oculto.remaining() / ids.size
                val media = FloatArray(dim)
                for (t in ids.indices) for (d in 0 until dim) media[d] += oculto.get(t * dim + d)
                for (d in 0 until dim) media[d] /= ids.size
                val salida = w.size / dim
                val v = FloatArray(salida) { o -> var acc = 0f; for (d in 0 until dim) acc += w[o * dim + d] * media[d]; acc }
                return normalizar(v)
            }
        } finally {
            entradas.values.forEach { it.close() }
        }
    }

    /** La capa densa (512×768, float32) del formato safetensors: cabecera JSON y los números detrás. */
    private fun leerProyeccion(f: File): FloatArray {
        val datos = f.readBytes()
        val bb = ByteBuffer.wrap(datos).order(ByteOrder.LITTLE_ENDIAN)
        val largo = bb.long.toInt()
        val cabecera = JSONObject(String(datos, 8, largo, Charsets.UTF_8))
        val clave = cabecera.keys().asSequence().first { it != "__metadata__" }
        val t = cabecera.getJSONObject(clave)
        require(t.getString("dtype") == "F32")
        val desde = t.getJSONArray("data_offsets").getLong(0).toInt()
        val hasta = t.getJSONArray("data_offsets").getLong(1).toInt()
        val fb = ByteBuffer.wrap(datos, 8 + largo + desde, hasta - desde).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private fun normalizar(v: FloatArray): FloatArray {
        val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat().coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= n
        return v
    }

    /**
     * El tokenizador WordPiece de BERT, con mayúsculas y tildes (el modelo es «cased»): separa por
     * espacios y signos, aísla los caracteres chinos y trocea cada palabra en piezas del vocabulario.
     */
    private class Tokenizador(private val vocab: Map<String, Int>) {
        private val cls = vocab["[CLS]"] ?: 101
        private val sep = vocab["[SEP]"] ?: 102
        private val unk = vocab["[UNK]"] ?: 100

        fun codificar(texto: String, maximo: Int): LongArray {
            val ids = mutableListOf(cls.toLong())
            for (palabra in palabras(Normalizer.normalize(texto, Normalizer.Form.NFC))) {
                for (id in piezas(palabra)) { if (ids.size < maximo - 1) ids += id.toLong() }
            }
            ids += sep.toLong()
            return ids.toLongArray()
        }

        private fun signo(c: Int): Boolean {
            if (c in 33..47 || c in 58..64 || c in 91..96 || c in 123..126) return true
            return when (Character.getType(c).toByte()) {
                Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION -> true
                else -> false
            }
        }

        private fun chino(c: Int) = c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF || c in 0x20000..0x2A6DF || c in 0x2A700..0x2B73F ||
            c in 0x2B740..0x2B81F || c in 0x2B820..0x2CEAF || c in 0xF900..0xFAFF || c in 0x2F800..0x2FA1F

        private fun palabras(t: String): List<String> {
            val salida = mutableListOf<String>()
            val actual = StringBuilder()
            fun cerrar() { if (actual.isNotEmpty()) { salida += actual.toString(); actual.clear() } }
            var i = 0
            while (i < t.length) {
                val c = t.codePointAt(i)
                i += Character.charCount(c)
                when {
                    c == 0 || c == 0xFFFD || (Character.isISOControl(c) && c != '\t'.code && c != '\n'.code && c != '\r'.code) -> Unit
                    Character.isWhitespace(c) || Character.isSpaceChar(c) -> cerrar()
                    signo(c) || chino(c) -> { cerrar(); salida += String(Character.toChars(c)) }
                    else -> actual.appendCodePoint(c)
                }
            }
            cerrar()
            return salida
        }

        private fun piezas(palabra: String): List<Int> {
            if (palabra.length > 100) return listOf(unk)
            val salida = mutableListOf<Int>()
            var inicio = 0
            while (inicio < palabra.length) {
                var fin = palabra.length
                var encontrada: Int? = null
                while (inicio < fin) {
                    val trozo = (if (inicio > 0) "##" else "") + palabra.substring(inicio, fin)
                    val v = vocab[trozo]
                    if (v != null) { encontrada = v; break }
                    fin--
                }
                val id = encontrada ?: return listOf(unk)
                salida += id
                inicio = fin
            }
            return salida
        }
    }
}
