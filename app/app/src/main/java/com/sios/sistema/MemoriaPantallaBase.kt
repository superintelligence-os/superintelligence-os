package com.sios.sistema

import android.content.ContentValues
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * La base de datos de la memoria de pantalla: cifrada entera con SQLCipher (AES-256) y con una clave
 * que solo se puede abrir con el móvil desbloqueado. La clave de la base (32 bytes al azar) se guarda
 * envuelta con otra del Android Keystore creada con «setUnlockedDeviceRequired»: con la pantalla
 * bloqueada, el Keystore se niega a desenvolverla. Al bloquear se cierra la base y la clave sale de
 * la memoria. Borrar todo destruye también las claves: lo que quedara en el disco ya no se puede leer.
 */
object MemoriaPantallaBase {
    private const val ALIAS = "memoria_pantalla"
    private const val NOMBRE = "memoria_pantalla.db"

    /** Un momento guardado (lo que se va a escribir). [vector]: la huella de la imagen, si la hubo. */
    class Nuevo(val fecha: Long, val app: String, val titulo: String?, val texto: String, val vector: FloatArray?, val miniatura: ByteArray?)

    /** Un resultado de búsqueda. [fragmento] lleva lo encontrado entre \u0001 y \u0002 (para resaltarlo). */
    class Recuerdo(
        val id: Long, val fecha: Long, val app: String, val titulo: String?, val fragmento: String,
        val porImagen: Boolean, val miniatura: ByteArray?, val puntuacion: Double,
    )

    @Volatile private var base: SQLiteDatabase? = null
    private var ultimaLimpieza = 0L

    // La librería nativa, solo cuando hace falta de verdad (no al arrancar la app).
    private val nativa by lazy { System.loadLibrary("sqlcipher") }

    private fun carpeta(ctx: Context) = File(ctx.noBackupFilesDir, "memoria_pantalla").apply { mkdirs() }
    private fun archivoClave(ctx: Context) = File(carpeta(ctx), "clave")
    private fun archivoBase(ctx: Context): File = ctx.getDatabasePath(NOMBRE)

    // ---------- Claves ----------

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun claveMaestra(): SecretKey {
        (keystore().getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Solo con el móvil desbloqueado: con la pantalla de bloqueo puesta no se abre.
                .setUnlockedDeviceRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    /** La clave de la base, desenvuelta (o creada la primera vez). Falla con el móvil bloqueado. */
    private fun claveBase(ctx: Context): ByteArray {
        val f = archivoClave(ctx)
        var maestra = claveMaestra()
        if (f.exists()) {
            val datos = f.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            try {
                c.init(Cipher.DECRYPT_MODE, maestra, GCMParameterSpec(128, datos, 1, datos[0].toInt()))
                return c.doFinal(datos, 1 + datos[0], datos.size - 1 - datos[0])
            } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                // Se quitó el bloqueo de pantalla: la clave ya no sirve y lo guardado no se puede leer.
                // Se empieza de cero (con el móvil bloqueado el error es otro y se respeta).
                android.util.Log.w("SistemaSios", "Memoria de pantalla: clave invalidada, se empieza de cero")
                f.delete()
                listOf("", "-wal", "-shm", "-journal").forEach { File(archivoBase(ctx).path + it).delete() }
                runCatching { keystore().deleteEntry(ALIAS) }
                maestra = claveMaestra()
            }
        }
        val nueva = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, maestra)
        val cifrada = c.doFinal(nueva)
        val iv = c.iv
        f.writeBytes(byteArrayOf(iv.size.toByte()) + iv + cifrada)
        return nueva
    }

    /** Clave en bruto para SQLCipher (x'…'): sin derivación PBKDF2, que tardaría casi un segundo al abrir. */
    private fun frase(clave: ByteArray): ByteArray =
        ("x'" + clave.joinToString("") { "%02X".format(it) } + "'").toByteArray().also { clave.fill(0) }

    // ---------- Abrir y cerrar ----------

    /** La base abierta (la abre si hace falta). Lanza si el móvil está bloqueado. */
    @Synchronized
    private fun db(ctx: Context): SQLiteDatabase {
        base?.takeIf { it.isOpen }?.let { return it }
        nativa
        val app = ctx.applicationContext
        val f = archivoBase(app).apply { parentFile?.mkdirs() }
        val frase = frase(claveBase(app))
        val d = try {
            SQLiteDatabase.openOrCreateDatabase(f, frase, null, null, null)
        } finally {
            frase.fill(0)
        }
        // Lo borrado se sobrescribe con ceros (no se queda en páginas libres del archivo).
        d.rawQuery("PRAGMA secure_delete = ON", arrayOf<String>()).use { it.moveToFirst() }
        if (d.version == 0) crear(d)
        base = d
        return d
    }

    private fun crear(d: SQLiteDatabase) {
        d.beginTransaction()
        try {
            d.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
            d.execSQL(
                "CREATE TABLE IF NOT EXISTS capturas (id INTEGER PRIMARY KEY, fecha INTEGER NOT NULL, app TEXT NOT NULL, " +
                    "titulo TEXT, texto TEXT NOT NULL, imagen INTEGER NOT NULL DEFAULT 0, vector BLOB, miniatura BLOB)",
            )
            d.execSQL("CREATE INDEX IF NOT EXISTS capturas_fecha ON capturas(fecha)")
            // Texto completo sin tildes ni mayúsculas: «informacion» encuentra «Información».
            d.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS capturas_fts USING fts5(texto, content='capturas', content_rowid='id', " +
                    "tokenize='unicode61 remove_diacritics 2')",
            )
            d.execSQL("CREATE TRIGGER IF NOT EXISTS capturas_ai AFTER INSERT ON capturas BEGIN INSERT INTO capturas_fts(rowid, texto) VALUES (new.id, new.texto); END")
            d.execSQL("CREATE TRIGGER IF NOT EXISTS capturas_ad AFTER DELETE ON capturas BEGIN INSERT INTO capturas_fts(capturas_fts, rowid, texto) VALUES ('delete', old.id, old.texto); END")
            d.version = 1
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** Cierra la base y suelta la clave (al bloquear el móvil o al pausar). */
    @Synchronized
    fun cerrar() {
        runCatching { base?.close() }
        base = null
    }

    /**
     * ¿Se activó en este móvil? La clave envuelta no va en las copias de seguridad (noBackupFilesDir):
     * al restaurar una copia en otro móvil, la función no queda activa sin pasar por su presentación.
     */
    fun preparada(ctx: Context) = archivoClave(ctx).exists()

    /** ¿Se puede abrir ahora? (el móvil desbloqueado y la clave en su sitio). */
    fun disponible(ctx: Context): Boolean = runCatching { db(ctx); true }.getOrDefault(false)

    /** Prepara las claves y la base al activar la función (con el usuario delante y desbloqueado). */
    fun preparar(ctx: Context) {
        db(ctx)
    }

    // ---------- Escribir ----------

    fun anadir(ctx: Context, lote: List<Nuevo>, dias: Int) {
        if (lote.isEmpty()) return
        val d = db(ctx)
        d.beginTransaction()
        try {
            for (n in lote) {
                d.insert("capturas", 0, ContentValues().apply {
                    put("fecha", n.fecha); put("app", n.app); put("titulo", n.titulo); put("texto", n.texto)
                    put("imagen", if (n.vector != null) 1 else 0)
                    n.vector?.let { put("vector", cuantizar(it)) }
                    n.miniatura?.let { put("miniatura", it) }
                })
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        // El borrado automático, como mucho una vez por hora (y siempre al abrir la búsqueda).
        if (System.currentTimeMillis() - ultimaLimpieza > 3_600_000L) caducar(ctx, dias)
    }

    /** Borra lo que tenga más de [dias] días y devuelve el espacio al sistema. */
    fun caducar(ctx: Context, dias: Int) {
        val d = db(ctx)
        val limite = System.currentTimeMillis() - dias * 86_400_000L
        val borrados = d.delete("capturas", "fecha < ?", arrayOf(limite.toString()))
        if (borrados > 0) {
            runCatching { d.execSQL("INSERT INTO capturas_fts(capturas_fts) VALUES ('optimize')") }
            runCatching { d.rawQuery("PRAGMA incremental_vacuum", arrayOf<String>()).use { while (it.moveToNext()) Unit } }
        }
        ultimaLimpieza = System.currentTimeMillis()
    }

    /**
     * Borrar todo: cierra, borra los archivos y destruye las dos claves (la del Keystore y la envuelta).
     * Aunque quedara algo en el almacenamiento, ya no hay forma de descifrarlo.
     */
    @Synchronized
    fun borrarTodo(ctx: Context) {
        cerrar()
        val f = archivoBase(ctx)
        listOf("", "-wal", "-shm", "-journal").forEach { File(f.path + it).delete() }
        archivoClave(ctx).delete()
        runCatching { keystore().deleteEntry(ALIAS) }
    }

    /** Cuánto ocupa la base en disco (con su diario). */
    fun tamano(ctx: Context): Long {
        val f = archivoBase(ctx)
        return listOf("", "-wal", "-journal").sumOf { File(f.path + it).length() }
    }

    class Resumen(val momentos: Int, val desde: Long?, val conImagen: Int)

    fun resumen(ctx: Context): Resumen? = runCatching {
        db(ctx).rawQuery("SELECT count(*), min(fecha), sum(imagen) FROM capturas", arrayOf<String>()).use { c ->
            c.moveToFirst()
            Resumen(c.getInt(0), if (c.isNull(1)) null else c.getLong(1), c.getInt(2))
        }
    }.getOrNull()

    /** Las apps que hay en la memoria (para buscar «en Instagram»). */
    fun apps(ctx: Context): List<String> = runCatching {
        db(ctx).rawQuery("SELECT DISTINCT app FROM capturas", arrayOf<String>()).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
    }.getOrDefault(emptyList())

    // ---------- Buscar ----------

    private fun normalizar(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    /**
     * Búsqueda por texto completo: cualquiera de las palabras (con prefijo), ordenado por relevancia
     * (BM25) y, entre parecidos, lo más reciente. [desde]/[hasta]: el día que se haya dicho («el martes»).
     */
    fun buscarTexto(ctx: Context, palabras: List<String>, desde: Long?, hasta: Long?, app: String?, limite: Int = 60): List<Recuerdo> {
        val limpias = palabras.map { normalizar(it).filter { c -> c.isLetterOrDigit() } }.filter { it.length >= 2 }.distinct()
        if (limpias.isEmpty()) return if (desde != null || app != null) recientes(ctx, desde, hasta, app, limite) else emptyList()
        val consulta = limpias.joinToString(" OR ") { "\"$it\"*" }
        val filtros = StringBuilder()
        val args = mutableListOf(consulta)
        if (desde != null) { filtros.append(" AND c.fecha >= ?"); args += desde.toString() }
        if (hasta != null) { filtros.append(" AND c.fecha < ?"); args += hasta.toString() }
        if (app != null) { filtros.append(" AND c.app = ?"); args += app }
        val sql = "SELECT c.id, c.fecha, c.app, c.titulo, snippet(capturas_fts, 0, char(1), char(2), '…', 16), c.imagen, c.miniatura, " +
            "bm25(capturas_fts), c.texto FROM capturas_fts JOIN capturas c ON c.id = capturas_fts.rowid " +
            "WHERE capturas_fts MATCH ?$filtros ORDER BY bm25(capturas_fts) LIMIT 400"
        val ahora = System.currentTimeMillis()
        val lista = mutableListOf<Recuerdo>()
        db(ctx).rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                val todo = normalizar(c.getString(8))
                // Cuántas palabras distintas aparecen manda; luego BM25 y un poco la cercanía en el tiempo.
                val aciertos = limpias.count { todo.contains(it) }
                val dias = (ahora - c.getLong(1)) / 86_400_000.0
                val puntos = aciertos * 10.0 - c.getDouble(7) + 2.0 / (1.0 + dias)
                lista += Recuerdo(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4), false, c.getBlob(6), puntos)
            }
        }
        return sinRepetidos(lista.sortedByDescending { it.puntuacion }).take(limite)
    }

    /** Lo último de un día o de una app, cuando la búsqueda solo dice «ayer» o «en Chrome». */
    private fun recientes(ctx: Context, desde: Long?, hasta: Long?, app: String?, limite: Int): List<Recuerdo> {
        val filtros = mutableListOf<String>()
        val args = mutableListOf<String>()
        if (desde != null) { filtros += "fecha >= ?"; args += desde.toString() }
        if (hasta != null) { filtros += "fecha < ?"; args += hasta.toString() }
        if (app != null) { filtros += "app = ?"; args += app }
        val donde = if (filtros.isEmpty()) "" else "WHERE " + filtros.joinToString(" AND ")
        return db(ctx).rawQuery("SELECT id, fecha, app, titulo, substr(texto, 1, 220), miniatura FROM capturas $donde ORDER BY fecha DESC LIMIT $limite", args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(Recuerdo(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4).replace('\n', ' '), false, c.getBlob(5), 0.0)) }
        }
    }

    /**
     * Búsqueda por la huella de las imágenes: la consulta (ya pasada por el codificador de texto) contra
     * todas las huellas guardadas. Son pocas miles de 512 números: se recorren todas en unos milisegundos.
     */
    fun buscarVector(ctx: Context, consulta: FloatArray, desde: Long?, hasta: Long?, app: String?, limite: Int = 12): List<Recuerdo> {
        val filtros = StringBuilder("vector IS NOT NULL")
        val args = mutableListOf<String>()
        if (desde != null) { filtros.append(" AND fecha >= ?"); args += desde.toString() }
        if (hasta != null) { filtros.append(" AND fecha < ?"); args += hasta.toString() }
        if (app != null) { filtros.append(" AND app = ?"); args += app }
        val todos = mutableListOf<Pair<Long, Double>>()
        db(ctx).rawQuery("SELECT id, vector FROM capturas WHERE $filtros", args.toTypedArray()).use { c ->
            while (c.moveToNext()) todos += c.getLong(0) to similitud(consulta, c.getBlob(1)).toDouble()
        }
        if (todos.isEmpty()) return emptyList()
        // CLIP da similitudes bajas en general: medido con este par de modelos, lo que coincide queda en
        // 0,28–0,33 y lo que no, en 0,19–0,26. Se queda lo que pasa de 0,27 y está cerca del mejor.
        val mejor = todos.maxOf { it.second }
        val elegidos = todos.filter { it.second >= 0.27 && it.second >= mejor - 0.04 }.sortedByDescending { it.second }.take(limite)
        if (elegidos.isEmpty()) return emptyList()
        val porId = elegidos.toMap()
        val marcas = elegidos.joinToString(",") { "?" }
        return db(ctx).rawQuery(
            "SELECT id, fecha, app, titulo, substr(texto, 1, 220), miniatura FROM capturas WHERE id IN ($marcas)",
            elegidos.map { it.first.toString() }.toTypedArray(),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Recuerdo(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), c.getString(4).replace('\n', ' '), true, c.getBlob(5), porId.getValue(c.getLong(0))),
                )
            }
        }.sortedByDescending { it.puntuacion }
    }

    /** Los fragmentos (texto) de unos resultados, para preguntar a la IA solo por ellos. */
    fun textos(ctx: Context, ids: List<Long>, maximo: Int = 700): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val marcas = ids.joinToString(",") { "?" }
        return db(ctx).rawQuery("SELECT id, texto FROM capturas WHERE id IN ($marcas)", ids.map { it.toString() }.toTypedArray()).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getString(1).take(maximo)) }
        }
    }

    /** El mismo fragmento de la misma app guardado varias veces seguidas (al desplazar): solo el último. */
    private fun sinRepetidos(lista: List<Recuerdo>): List<Recuerdo> {
        val vistos = HashSet<String>()
        return lista.filter { vistos.add(it.app + "|" + it.fragmento.filter(Char::isLetterOrDigit).lowercase()) }
    }

    // ---------- Huellas ----------

    /** 512 números en 516 bytes: la escala (float) y cada componente en un byte con signo. */
    private fun cuantizar(v: FloatArray): ByteArray {
        val max = v.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-6f)
        val b = java.nio.ByteBuffer.allocate(4 + v.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putFloat(max / 127f)
        for (x in v) b.put(Math.round(x / max * 127f).coerceIn(-127, 127).toByte())
        return b.array()
    }

    private fun similitud(q: FloatArray, datos: ByteArray): Float {
        if (datos.size != 4 + q.size) return 0f
        val b = java.nio.ByteBuffer.wrap(datos).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val escala = b.float
        var s = 0f
        var n = 0f
        for (i in q.indices) {
            val x = datos[4 + i] * escala
            s += q[i] * x
            n += x * x
        }
        return if (n <= 0f) 0f else s / kotlin.math.sqrt(n)
    }
}
