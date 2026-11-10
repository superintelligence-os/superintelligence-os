package com.sios.voz

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Los modelos de voz y de traducción que funcionan sin conexión. Ninguno va en el APK: se descargan
 * cuando el usuario los pide, de una versión fija y comprobando el SHA-256 de cada archivo (como la
 * memoria de pantalla). Solo se conecta para eso; después, todo pasa en el móvil.
 *
 *  - Dictado preciso: Whisper small (OpenAI, MIT; ONNX cuantizado de onnx-community, Apache 2.0).
 *    Un solo modelo para todos los idiomas, con mayúsculas y puntuación.
 *  - Dictado ligero: Vosk small (Alpha Cephei, Apache 2.0), uno por idioma. Escribe mientras hablas.
 *    Es el mismo modelo que usa «Oye…» para oír su nombre: si ya está, no se baja otra vez.
 *  - Traducción: OPUS-MT (Universidad de Helsinki, Apache 2.0 / CC BY 4.0; ONNX de Xenova), un
 *    paquete por idioma con las dos direcciones con el inglés. Entre dos idiomas que no son el
 *    inglés se pasa por él.
 */
object ModelosVoz {
    enum class Clase { DICTADO_PRECISO, DICTADO_LIGERO, TRADUCCION }

    class Archivo(val ruta: String, val url: String, val bytes: Long, val sha256: String)

    /**
     * Un modelo descargable. [zip]: los de Vosk vienen en un .zip que se descomprime en [carpetaVosk]
     * (la misma carpeta que usa «Oye…»).
     */
    class Modelo(val id: String, val clase: Clase, val idioma: String?, val archivos: List<Archivo>, val carpetaVosk: String? = null) {
        val bytes: Long get() = archivos.sumOf { it.bytes }
    }

    // ---------- Catálogo ----------

    private const val WHISPER = "https://huggingface.co/onnx-community/whisper-small/resolve/36050c46d777d46dc4b5f43f6d90574fc38f8732"
    private const val VOSK = "https://alphacephei.com/vosk/models"
    private const val OPUS_ES_EN = "https://huggingface.co/Xenova/opus-mt-es-en/resolve/eadfd7c658a9d8929ac3b8e996b68a68e2c7d480"
    private const val OPUS_EN_ES = "https://huggingface.co/Xenova/opus-mt-en-es/resolve/4b002a4c7edd54a7ced58877258b87f7efd3f892"
    private const val OPUS_FR_EN = "https://huggingface.co/Xenova/opus-mt-fr-en/resolve/6b166a182780e118c997879d0ad5be4b53671644"
    private const val OPUS_EN_FR = "https://huggingface.co/Xenova/opus-mt-en-fr/resolve/28726206f80896b90035bd99cccd5cc1e151f916"
    private const val OPUS_DE_EN = "https://huggingface.co/Xenova/opus-mt-de-en/resolve/399dfd68706739fffd503f876093e455ae268a06"
    private const val OPUS_EN_DE = "https://huggingface.co/Xenova/opus-mt-en-de/resolve/1ca130c44c4c5441ef16d48aae521a424ab644f7"
    private const val OPUS_IT_EN = "https://huggingface.co/Xenova/opus-mt-it-en/resolve/fd0b89b9c052adc1f2f64152f555aa17353728be"
    private const val OPUS_EN_IT = "https://huggingface.co/Xenova/opus-mt-en-it/resolve/075406e3c8c2c30634d4a1bd8f00c21d9e162011"
    private const val OPUS_ROMANCE_EN = "https://huggingface.co/Xenova/opus-mt-ROMANCE-en/resolve/f60feaabe8bb435a5871e7702b25f53a01e770f8"
    private const val OPUS_EN_ROMANCE = "https://huggingface.co/Xenova/opus-mt-en-ROMANCE/resolve/9d2ba69ac80c8e8453c3d9a1e2323a0e7b8ca3cd"

    private fun opus(dir: String, base: String, enc: Long, encSha: String, dec: Long, decSha: String, spm: Long, spmSha: String, vocab: Long, vocabSha: String) = listOf(
        Archivo("$dir/encoder.onnx", "$base/onnx/encoder_model_quantized.onnx", enc, encSha),
        Archivo("$dir/decoder.onnx", "$base/onnx/decoder_model_merged_quantized.onnx", dec, decSha),
        Archivo("$dir/source.spm", "$base/source.spm", spm, spmSha),
        Archivo("$dir/vocab.json", "$base/vocab.json", vocab, vocabSha),
    )

    private fun vosk(idioma: String, nombre: String, bytes: Long, sha: String) =
        Modelo("vosk-$idioma", Clase.DICTADO_LIGERO, idioma, listOf(Archivo("$nombre.zip", "$VOSK/$nombre.zip", bytes, sha)), carpetaVosk = nombre)

    val WHISPER_SMALL = Modelo(
        "whisper-small", Clase.DICTADO_PRECISO, null,
        listOf(
            Archivo("encoder.onnx", "$WHISPER/onnx/encoder_model_quantized.onnx", 92_326_160, "a43a83f3c5361cd591cfa7c36f14b43cf7cb22f47a415cc14a8d557be800fa92"),
            Archivo("decoder.onnx", "$WHISPER/onnx/decoder_model_merged_quantized.onnx", 156_750_845, "ec07c3cbb64172c39791e26ee870a65ac22b458c36722bfe2776b3dbf741e0c9"),
            Archivo("vocab.json", "$WHISPER/vocab.json", 1_036_584, "50d6a919f0a0601d56a04eb583c780d18553aa388254ba3158eb6a00f13e2c1a"),
            Archivo("generation_config.json", "$WHISPER/generation_config.json", 3_893, "f538b28220c6a6d6f1af1458d4141cacb4ef4963df3de98a19490440c412ddf0"),
        ),
    )

    /** Los de Vosk que usa también «Oye…» (mismo nombre y misma carpeta). */
    val LIGEROS = listOf(
        vosk("es", "vosk-model-small-es-0.42", 39_817_833, "09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"),
        vosk("en", "vosk-model-small-en-us-0.15", 41_205_931, "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"),
        vosk("fr", "vosk-model-small-fr-0.22", 42_233_323, "cabf6180e177eb9b3a9a9d43a437bd5e549f3a7d09525e5d69a3fed787be12ad"),
        vosk("de", "vosk-model-small-de-0.15", 46_499_967, "b7e53c90b1f0a38456f4cd62b366ecd58803cd97cd42b06438e2c131713d5e43"),
        vosk("it", "vosk-model-small-it-0.22", 49_665_141, "9ec65e75861d1c6c2e457cccd932705340dcdf233f5b239f00733b4de0bf3267"),
        vosk("pt", "vosk-model-small-pt-0.3", 32_453_112, "6e1ce909032e1afa7a88e68a3d628ecafff302bdf195befab308826c395e93b7"),
    )

    /** Paquetes de traducción: cada idioma con el inglés, en las dos direcciones. */
    val TRADUCCIONES = listOf(
        Modelo(
            "trad-es", Clase.TRADUCCION, "es",
            opus("es-en", OPUS_ES_EN, 52_899_742, "c01e70f8455efc350831aa2af7ed187b16829241f0018ba07d1ba643a391bc18", 60_212_804, "4cd91ab30240d295ce907b5100826031838c672005e57e524d711122d75605fa", 825_924, "e236ee6d866b635c0142114f8647f39831f9d92534aa2aad75c942f6a78ad0e3", 1_720_044, "b074b4cca0036ade5a39ea97faabd534e1015482c480fc2cb02c6481983eb163") +
                opus("en-es", OPUS_EN_ES, 52_899_742, "03434edd1147e6c49e22066e9dc5888235e99cc35a6711d1c4ea5b06455d5da9", 60_212_804, "58ee5ddd6e22d1693d8722b90c1486afb93700a4376cf28fd74175052ad530ae", 801_636, "4dd547c24816a335e7b0b2e63376a8f1b3cbfc671eda5ab808dd44fdadaa8791", 1_720_044, "b074b4cca0036ade5a39ea97faabd534e1015482c480fc2cb02c6481983eb163"),
        ),
        Modelo(
            "trad-fr", Clase.TRADUCCION, "fr",
            opus("fr-en", OPUS_FR_EN, 50_090_398, "e727cb26ac6bf816394c49671af69c9ae5798868fed1e40849872415dffc1772", 57_381_512, "73bc7ac8e29c42e6f212ebcc29a2991d3646a04aabb642c0036aa54b40e4e1a9", 802_397, "78d0e717c77053f1c4b856d8661d9cb87c64f083a35418c087b9146300e4f585", 1_458_196, "f2ba9c69ae20f96b8bd821239a9152be422394f980350b77907cffc183db5f2d") +
                opus("en-fr", OPUS_EN_FR, 50_090_398, "0a81bdba62f53223740a8c6c6f58e716eba8ea1f92dfa569caf27ed5e3b6a0f7", 57_381_512, "333b244bce16023df04541c8cf9fd60aec9b0569da393c4b831d561897b0bda8", 778_395, "173e9f493a668fe396d599e28d414a201193094e6ffd7a4678e5aab0f6d3d838", 1_458_196, "f2ba9c69ae20f96b8bd821239a9152be422394f980350b77907cffc183db5f2d"),
        ),
        Modelo(
            "trad-de", Clase.TRADUCCION, "de",
            opus("de-en", OPUS_DE_EN, 49_366_942, "4cedda8f8c89b72a42b3c6cd1e7a27f2de24457093e3bf80cb3e46829641fcd8", 56_652_404, "4712800584b2d77c2f9e620bf29523e2b20b62f65ade5011ee01780e2b0ea5d3", 796_845, "bbd1f495eea99c8e21ae086d9146e0fa7b096c3dfdd9ba07ab8b631889df5c9b", 1_389_436, "d5acea957b265a78554999144459c5e391e0df525864edc8287bc090290baa44") +
                opus("en-de", OPUS_EN_DE, 49_366_942, "15834b45fabd2dfb8c6c029b3ca3e7289aeefd90ece798ce42bcf548d1bd3b8d", 56_652_404, "8b46a825964cdd182fe47cc780f0fb87d357eae1f47cf20aa63c1c09be5b510c", 768_489, "678f2a1177d8389f67b66299762dcc4fc567e89b07e212ba91b0c56daecf47ce", 1_389_436, "d5acea957b265a78554999144459c5e391e0df525864edc8287bc090290baa44"),
        ),
        Modelo(
            "trad-it", Clase.TRADUCCION, "it",
            opus("it-en", OPUS_IT_EN, 60_773_278, "a025d2d85c90da23880116b67175e43e71921d8d2bf446b9f1eacdc86015aa4c", 68_147_852, "aebc2789cd2ef8062fbda020bac90b61e68458ba98a4c43cea63c1d2310ba2b8", 813_709, "1bd307e2756991d470e4b40172147f7889b46a323b1a9a2150e66274825b1f21", 2_530_593, "08e5b03ab20b2b9b85d41aed1b36dce2650afcb6e96274a06b7869b2e06884d5") +
                opus("en-it", OPUS_EN_IT, 60_597_150, "b51c5110c7ba356ef01ee400d427afe162e657de1d7a2e9a40deedd811b49e6f", 67_970_348, "f9db146ed7665ea7d437dc4df564e3af5df923366de4d52aaf2c4b4e3e4ae4f1", 789_468, "2efff2c37e51842c2817a6770280979b5dd803cebbe2928e576d42a888f89537", 2_511_647, "40c98ffc0013f2f09ed9127418131738ad9c2de4d8f7f52d52532332bb2bf78c"),
        ),
        // El portugués va con los modelos «ROMANCE» (varias lenguas romances): hacia él se pide con «>>pt<<».
        Modelo(
            "trad-pt", Clase.TRADUCCION, "pt",
            opus("ROMANCE-en", OPUS_ROMANCE_EN, 52_899_741, "13750ba31ee80c88f6bbb28aa03320674c50394efb95f791d5c4f5c8894232bf", 60_212_803, "5c0d95a8072a463c747343cf25ac9b4a17e9a90e08131e7efab12691ca13c979", 800_087, "baeab21ec00d0b490382b82499ef0348235c5a8e75de28aec8290adf62b007c4", 1_590_308, "32c8eeec099b448425a7da4e09ec10acbe424a733780c9bcec2062846f03e706") +
                opus("en-ROMANCE", OPUS_EN_ROMANCE, 52_899_742, "b4ddfd9338ba3d7c6b9c2d1410ed94fb7e06741226918cef6148031101bbd019", 60_212_804, "b5f6d442da7fa69f9d500df90373d2fdca4f799c43696b41888353b5c3c8eb13", 779_155, "b1f89e7b05828846baf19109a61029a36838f1c4f3defc218e6aee0ee2787864", 1_587_364, "d9c3b6b01d3a9c9403befa60fd17e4a50dc4929698e3d6b9c2e7f066a9d1f041"),
        ),
    )

    val TODOS: List<Modelo> = listOf(WHISPER_SMALL) + LIGEROS + TRADUCCIONES

    fun porId(id: String) = TODOS.firstOrNull { it.id == id }
    fun ligero(idioma: String) = LIGEROS.firstOrNull { it.idioma == idioma }
    fun traduccion(idioma: String) = TRADUCCIONES.firstOrNull { it.idioma == idioma }

    // ---------- Dónde están ----------

    private fun raiz(ctx: Context) = File(ctx.noBackupFilesDir, "voz")

    /** Carpeta de un modelo ya descargado (la de Vosk, compartida con «Oye…»). */
    fun carpeta(ctx: Context, m: Modelo): File =
        if (m.carpetaVosk != null) File(File(ctx.filesDir, "vosk"), m.carpetaVosk) else File(raiz(ctx), m.id)

    private fun temporal(ctx: Context, m: Modelo) = File(raiz(ctx), "descargas/${m.id}").apply { mkdirs() }

    fun listo(ctx: Context, m: Modelo): Boolean {
        val dir = carpeta(ctx, m)
        return if (m.carpetaVosk != null) File(dir, "conf").isDirectory && File(dir, "am").isDirectory
        else m.archivos.all { File(dir, it.ruta).length() == it.bytes }
    }

    /** Lo que ocupa de verdad en el móvil (0 si no está). */
    fun ocupa(ctx: Context, m: Modelo): Long {
        if (!listo(ctx, m)) return 0
        return carpeta(ctx, m).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Lo que se baja (el .zip de Vosk ocupa algo más una vez descomprimido). */
    fun tamanoDescarga(m: Modelo) = m.bytes

    // ---------- Descargas ----------

    sealed interface Descarga {
        data object Parada : Descarga
        data object EnCola : Descarga
        data class EnCurso(val hecho: Long, val total: Long) : Descarga
        data object Comprobando : Descarga
        data class Fallo(val motivo: Motivo) : Descarga
    }

    enum class Motivo { SIN_RED, SIN_ESPACIO, OTRO }

    private val _descargas = MutableStateFlow<Map<String, Descarga>>(emptyMap())
    /** El estado de cada descarga (los que no aparecen, parados). */
    val descargas: StateFlow<Map<String, Descarga>> = _descargas

    private val _cambios = MutableStateFlow(0)
    /** Sube cada vez que un modelo termina de bajarse o se borra (para volver a mirar qué hay). */
    val cambios: StateFlow<Int> = _cambios

    private val cola = LinkedHashSet<String>()
    @Volatile private var actual: String? = null
    @Volatile private var hilo: Thread? = null

    private fun poner(id: String, d: Descarga) {
        _descargas.value = _descargas.value.toMutableMap().also { if (d == Descarga.Parada) it.remove(id) else it[id] = d }
    }

    fun estado(id: String): Descarga = _descargas.value[id] ?: Descarga.Parada

    /** Pide un modelo: se baja en cuanto acaben los anteriores (de uno en uno, en segundo plano). */
    @Synchronized
    fun descargar(ctx: Context, m: Modelo) {
        val app = ctx.applicationContext
        if (listo(app, m)) { poner(m.id, Descarga.Parada); return }
        if (m.id == actual || !cola.add(m.id)) return
        poner(m.id, Descarga.EnCola)
        if (hilo?.isAlive != true) hilo = Thread { trabajar(app) }.apply { name = "voz-modelos"; priority = Thread.NORM_PRIORITY - 1; start() }
    }

    @Synchronized
    fun cancelar(id: String) {
        if (cola.remove(id)) poner(id, Descarga.Parada)
        if (actual == id) hilo?.interrupt()
    }

    @Synchronized
    private fun siguiente(): String? = cola.firstOrNull()?.also { cola.remove(it); actual = it }

    private fun trabajar(ctx: Context) {
        while (true) {
            val id = siguiente() ?: break
            val m = porId(id) ?: continue
            val resultado = runCatching { bajar(ctx, m) }
            synchronized(this) { actual = null }
            resultado.onSuccess { poner(id, Descarga.Parada); _cambios.value++ }
                .onFailure { e ->
                    android.util.Log.w("SistemaSios", "Voz sin conexión: descarga de $id", e)
                    val interrumpida = e is InterruptedException || e is java.io.InterruptedIOException || Thread.interrupted()
                    poner(
                        id,
                        when {
                            interrumpida -> Descarga.Parada
                            e is SinEspacio -> Descarga.Fallo(Motivo.SIN_ESPACIO)
                            !hayRed(ctx) -> Descarga.Fallo(Motivo.SIN_RED)
                            else -> Descarga.Fallo(Motivo.OTRO)
                        },
                    )
                }
        }
    }

    private class SinEspacio : Exception()

    private fun hayRed(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }

    /** ¿Con datos móviles (o una red que se paga por uso)? Para avisar antes de bajar cientos de MB. */
    fun redDePago(ctx: Context): Boolean = ctx.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    }

    /** Baja lo que falte (reanudando donde se quedó), lo comprueba y lo deja en su carpeta. */
    private fun bajar(ctx: Context, m: Modelo) {
        val tmp = temporal(ctx, m)
        val falta = m.archivos.sumOf { a -> (a.bytes - File(tmp, a.ruta).length().coerceAtMost(a.bytes)) }
        // Sitio para lo que falta y, en los de Vosk, para descomprimirlo (algo más del doble).
        val libre = StatFs(raiz(ctx).apply { mkdirs() }.path).availableBytes
        if (libre < falta * (if (m.carpetaVosk != null) 3 else 1) + 200L * 1024 * 1024) throw SinEspacio()
        var hechoAntes = 0L
        for (a in m.archivos) {
            val destino = File(tmp, a.ruta)
            destino.parentFile?.mkdirs()
            if (destino.length() != a.bytes || !destino.exists()) {
                val parte = File(destino.path + ".parte")
                bajarArchivo(a, parte, hechoAntes, m.bytes, m.id)
                poner(m.id, Descarga.Comprobando)
                if (sha256(parte) != a.sha256) {
                    parte.delete()
                    error("${a.ruta} no coincide")
                }
                if (!parte.renameTo(destino)) error("no se pudo guardar ${a.ruta}")
            }
            hechoAntes += a.bytes
            poner(m.id, Descarga.EnCurso(hechoAntes, m.bytes))
        }
        val final = carpeta(ctx, m)
        if (m.carpetaVosk != null) {
            poner(m.id, Descarga.Comprobando)
            descomprimir(File(tmp, m.archivos.first().ruta), final)
        } else {
            final.deleteRecursively()
            final.parentFile?.mkdirs()
            if (!tmp.renameTo(final)) error("no se pudo mover el modelo")
        }
        tmp.deleteRecursively()
    }

    private fun bajarArchivo(a: Archivo, parte: File, hechoAntes: Long, total: Long, id: String) {
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
                        if (f.filePointer > a.bytes) error("${a.ruta}: más grande de lo esperado")
                        val ahora = SystemClock.uptimeMillis()
                        if (ahora - ultimoAviso > 150) {
                            ultimoAviso = ahora
                            poner(id, Descarga.EnCurso(hechoAntes + f.filePointer, total))
                        }
                    }
                }
            }
        }
    }

    /** Descomprime el .zip de Vosk (su carpeta de dentro, sin nada fuera de ella). */
    private fun descomprimir(zip: File, destino: File) {
        val tmp = File(destino.path + ".descomprimiendo")
        tmp.deleteRecursively()
        tmp.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val ruta = e.name.substringAfter('/', "")
                if (ruta.isEmpty()) continue
                val f = File(tmp, ruta)
                if (!f.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) continue
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
            }
        }
        destino.deleteRecursively()
        destino.parentFile?.mkdirs()
        if (!tmp.renameTo(destino)) error("no se pudo mover el modelo")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { e ->
            val buf = ByteArray(256 * 1024)
            while (true) { val n = e.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Borra un modelo (y lo suelta de la memoria si estaba cargado). */
    fun borrar(ctx: Context, m: Modelo) {
        cancelar(m.id)
        MotoresLocales.soltar(m)
        carpeta(ctx, m).deleteRecursively()
        temporal(ctx, m).deleteRecursively()
        poner(m.id, Descarga.Parada)
        _cambios.value++
    }
}
