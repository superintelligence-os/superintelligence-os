package com.sios.boveda

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.text.Collator
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * La Bóveda de SIOS: contraseñas, códigos 2FA y documentos, cifrados y solo en este móvil.
 *
 * Cómo se cifra: una base SQLCipher (AES-256) cuya clave (32 bytes al azar) se guarda envuelta con
 * una clave del Android Keystore que exige la huella o el PIN en cada uso (sin tiempo de gracia: la
 * clave solo se desenvuelve dentro del BiometricPrompt, con su CryptoObject) y el móvil desbloqueado.
 * La clave del Keystore no sale nunca del chip de seguridad. Abierta, la clave de la base vive solo en
 * memoria; al apagar la pantalla, al pasar el tiempo elegido sin usarla o al pulsar el candado, la base
 * se cierra y todo lo descifrado sale de la memoria.
 *
 * Todo vive en noBackupFilesDir: ni la copia de Android ni la de SIOS se lo llevan (su clave no podría
 * ir con ellos). Para llevársela a otro móvil está la exportación cifrada con contraseña.
 */
object Boveda {
    private const val ALIAS = "boveda"

    enum class Estado { NUEVA, CERRADA, ABIERTA, INVALIDADA }

    class SinBloqueo : Exception("sin bloqueo de pantalla")
    class Invalidada : Exception("clave invalidada")

    private val _estado = MutableStateFlow(Estado.CERRADA)
    val estado: StateFlow<Estado> = _estado
    private val _entradas = MutableStateFlow<List<Entrada>>(emptyList())
    val entradas: StateFlow<List<Entrada>> = _entradas
    /** Huella SHA-1 → veces filtrada (solo si se ha activado la comprobación). */
    private val _filtradas = MutableStateFlow<Map<String, Int>>(emptyMap())
    val filtradas: StateFlow<Map<String, Int>> = _filtradas

    /** La Bóveda está en pantalla: las manos de la IA no leen nada mientras tanto. */
    @Volatile var enPantalla = false

    @Volatile private var base: SQLiteDatabase? = null
    @Volatile private var claveArchivos: ByteArray? = null
    /** En las pruebas de pantalla: abierta sin base de datos ni Keystore. */
    @Volatile private var deMentira = false
    private var app: Context? = null

    private val nativa by lazy { System.loadLibrary("sqlcipher") }
    private val principal by lazy { Handler(Looper.getMainLooper()) }
    private val orden = Collator.getInstance()

    fun carpeta(ctx: Context) = File(ctx.noBackupFilesDir, "boveda").apply { mkdirs() }
    private fun archivoClave(ctx: Context) = File(carpeta(ctx), "clave")
    private fun archivoBase(ctx: Context) = File(carpeta(ctx), "boveda.db")

    fun existe(ctx: Context) = archivoClave(ctx).exists()
    fun abierta() = _estado.value == Estado.ABIERTA

    /** El estado real al entrar (la Bóveda abierta se queda abierta). */
    fun revisar(ctx: Context) {
        if (abierta()) return
        _estado.value = when {
            !existe(ctx) -> Estado.NUEVA
            _estado.value == Estado.INVALIDADA -> Estado.INVALIDADA
            else -> Estado.CERRADA
        }
    }

    fun hayBloqueo(ctx: Context) = ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure

    // ---------- Claves ----------

    private fun keystore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun crearClaveMaestra(ctx: Context): SecretKey {
        runCatching { keystore().deleteEntry(ALIAS) }
        fun spec(chip: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // Huella fuerte o PIN en cada uso: la clave solo funciona dentro del BiometricPrompt.
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
            // Añadir una huella nueva no la invalida (quitar el bloqueo de pantalla, sí: así lo hace Android).
            .setInvalidatedByBiometricEnrollment(false)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(chip)
            .build()
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        // En el chip StrongBox si el móvil lo tiene; si no, en el entorno seguro (TEE).
        val chip = ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        return runCatching { gen.init(spec(chip)); gen.generateKey() }.getOrElse {
            if (!chip) throw it
            gen.init(spec(false)); gen.generateKey()
        }
    }

    /** Lo que hay que firmar con la huella: el cifrador listo para envolver o desenvolver la clave. */
    class Preparado internal constructor(val cifrador: Cipher, internal val nueva: Boolean)

    /** Prepara la apertura (o la creación). Lanza [SinBloqueo] o [Invalidada]. */
    fun preparar(ctx: Context): Preparado {
        if (!hayBloqueo(ctx)) throw SinBloqueo()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (!existe(ctx)) {
            c.init(Cipher.ENCRYPT_MODE, crearClaveMaestra(ctx))
            return Preparado(c, true)
        }
        val k = keystore().getKey(ALIAS, null) as? SecretKey ?: run { _estado.value = Estado.INVALIDADA; throw Invalidada() }
        val datos = archivoClave(ctx).readBytes()
        try {
            c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, datos, 1, datos[0].toInt()))
        } catch (e: KeyPermanentlyInvalidatedException) {
            _estado.value = Estado.INVALIDADA
            throw Invalidada()
        }
        return Preparado(c, false)
    }

    /** Tras la huella: desenvuelve (o crea) la clave de la base y abre la Bóveda. En segundo plano. */
    fun completar(ctx: Context, p: Preparado, cifrador: Cipher) {
        val a = ctx.applicationContext
        val clave: ByteArray
        if (p.nueva) {
            // Una Bóveda nueva: fuera lo que quedara de otra anterior que ya no se podía abrir.
            borrarArchivos(a)
            clave = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val envuelta = cifrador.doFinal(clave)
            val iv = cifrador.iv
            archivoClave(a).writeBytes(byteArrayOf(iv.size.toByte()) + iv + envuelta)
        } else {
            val datos = archivoClave(a).readBytes()
            clave = cifrador.doFinal(datos, 1 + datos[0], datos.size - 1 - datos[0])
        }
        abrirBase(a, clave)
    }

    private fun abrirBase(ctx: Context, clave: ByteArray) {
        nativa
        val frase = ("x'" + clave.joinToString("") { "%02X".format(it) } + "'").toByteArray()
        clave.fill(0)
        val d = try {
            SQLiteDatabase.openOrCreateDatabase(archivoBase(ctx), frase, null, null, null)
        } finally {
            frase.fill(0)
        }
        d.rawQuery("PRAGMA secure_delete = ON", arrayOf<String>()).use { it.moveToFirst() }
        if (d.version == 0) {
            d.execSQL("CREATE TABLE IF NOT EXISTS entradas (id TEXT PRIMARY KEY, datos TEXT NOT NULL)")
            d.execSQL("CREATE TABLE IF NOT EXISTS interno (clave TEXT PRIMARY KEY, valor BLOB)")
            d.version = 1
        }
        base = d
        // La clave de los archivos de los documentos, guardada dentro de la base.
        claveArchivos = leerInterno(d, "archivos") ?: ByteArray(32).also {
            SecureRandom().nextBytes(it)
            escribirInterno(d, "archivos", it)
        }
        val lista = d.rawQuery("SELECT datos FROM entradas", arrayOf<String>()).use { c ->
            buildList { while (c.moveToNext()) runCatching { add(Entrada.de(JSONObject(c.getString(0)))) } }
        }
        _filtradas.value = leerInterno(d, "filtradas")?.let { b ->
            runCatching { JSONObject(String(b)).let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } }.getOrNull()
        }.orEmpty()
        _entradas.value = ordenar(lista)
        alAbrir(ctx)
    }

    private fun leerInterno(d: SQLiteDatabase, clave: String): ByteArray? =
        d.rawQuery("SELECT valor FROM interno WHERE clave = ?", arrayOf(clave)).use { if (it.moveToFirst()) it.getBlob(0) else null }

    private fun escribirInterno(d: SQLiteDatabase, clave: String, valor: ByteArray) {
        d.insertWithOnConflict("interno", null, ContentValues().apply { put("clave", clave); put("valor", valor) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun ordenar(l: List<Entrada>) = l.sortedWith { a, b -> orden.compare(a.nombre.ifBlank { a.usuario }, b.nombre.ifBlank { b.usuario }) }

    // ---------- Abrir, cerrar y cerrarse sola ----------

    private val cierre = Runnable { cerrar() }
    private var receptor: BroadcastReceiver? = null

    private fun alAbrir(ctx: Context) {
        app = ctx.applicationContext
        principal.post {
            _estado.value = Estado.ABIERTA
            // Al apagar la pantalla, siempre se cierra.
            if (receptor == null) {
                val r = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) { cerrar() }
                }
                runCatching { ctx.applicationContext.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED) }
                receptor = r
            }
            tocar()
        }
        Thread { runCatching { Fuerza.prepararDiccionario(ctx) } }.start()
    }

    /** Se está usando: vuelve a contar el tiempo para cerrarse sola. */
    fun tocar() {
        principal.removeCallbacks(cierre)
        if (!abierta()) return
        val minutos = app?.let { minutosCierre(it) } ?: 5
        // «Al salir»: se cierra al dejar la pantalla; por si acaso, y para el autocompletar, a los 30 s.
        principal.postDelayed(cierre, if (minutos <= 0) 30_000L else minutos * 60_000L)
    }

    @Synchronized
    fun cerrar() {
        principal.removeCallbacks(cierre)
        runCatching { base?.close() }
        base = null
        claveArchivos?.fill(0)
        claveArchivos = null
        deMentira = false
        _entradas.value = emptyList()
        _filtradas.value = emptyMap()
        receptor?.let { r -> runCatching { app?.unregisterReceiver(r) } }
        receptor = null
        _estado.value = app?.let { if (existe(it)) Estado.CERRADA else Estado.NUEVA } ?: Estado.CERRADA
    }

    /** Abierta con estos datos y sin cifrado (solo para las capturas de pantalla de las pruebas). */
    internal fun abrirDeMentira(lista: List<Entrada>, filtradas: Map<String, Int> = emptyMap()) {
        deMentira = true
        _entradas.value = ordenar(lista)
        _filtradas.value = filtradas
        _estado.value = Estado.ABIERTA
    }

    internal fun ponerEstado(e: Estado) { _estado.value = e }

    // ---------- Entradas ----------

    fun entrada(id: String): Entrada? = _entradas.value.firstOrNull { it.id == id }

    @Synchronized
    fun guardar(e: Entrada) {
        check(abierta()) { "La Bóveda está cerrada" }
        base?.insertWithOnConflict(
            "entradas", null,
            ContentValues().apply { put("id", e.id); put("datos", e.json().toString()) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        _entradas.value = ordenar(_entradas.value.filter { it.id != e.id } + e)
        tocar()
    }

    @Synchronized
    fun guardarVarias(l: List<Entrada>) {
        if (l.isEmpty()) return
        check(abierta())
        base?.let { d ->
            d.beginTransaction()
            try {
                for (e in l) d.insertWithOnConflict("entradas", null, ContentValues().apply { put("id", e.id); put("datos", e.json().toString()) }, SQLiteDatabase.CONFLICT_REPLACE)
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
        val ids = l.map { it.id }.toSet()
        _entradas.value = ordenar(_entradas.value.filter { it.id !in ids } + l)
        tocar()
    }

    @Synchronized
    fun borrar(ctx: Context, id: String) {
        check(abierta())
        val e = entrada(id)
        base?.delete("entradas", "id = ?", arrayOf(id))
        e?.archivos?.forEach { Archivos.borrar(ctx, it.id) }
        _entradas.value = _entradas.value.filter { it.id != id }
        tocar()
    }

    /** Las entradas que valen para una app o una web (las que tienen algo que rellenar). */
    fun coincidencias(paquete: String?, dominio: String?): List<Entrada> =
        _entradas.value.filter { e ->
            e.tipo != Tipo.DOCUMENTO && (e.contrasena.isNotEmpty() || e.totp != null) &&
                e.sitios.any { Sitios.casa(it, paquete, dominio) }
        }

    internal fun claveDeArchivos(): ByteArray = claveArchivos?.copyOf() ?: error("La Bóveda está cerrada")

    fun guardarFiltradas(m: Map<String, Int>) {
        val todo = _filtradas.value + m
        _filtradas.value = todo
        base?.let { escribirInterno(it, "filtradas", JSONObject(todo).toString().toByteArray()) }
        app?.let { prefs(it).edit().putLong("filtradas_cuando", System.currentTimeMillis()).apply() }
    }

    /** Borra la Bóveda entera: base, archivos y la clave del Keystore. No se puede deshacer. */
    fun borrarTodo(ctx: Context) {
        cerrar()
        borrarArchivos(ctx.applicationContext)
        runCatching { keystore().deleteEntry(ALIAS) }
        _estado.value = Estado.NUEVA
    }

    private fun borrarArchivos(ctx: Context) {
        archivoClave(ctx).delete()
        listOf("", "-wal", "-shm", "-journal").forEach { File(archivoBase(ctx).path + it).delete() }
        File(carpeta(ctx), "docs").deleteRecursively()
    }

    // ---------- Ajustes (sin secretos: van en las preferencias normales) ----------

    fun prefs(ctx: Context) = ctx.getSharedPreferences("boveda", Context.MODE_PRIVATE)

    /** Minutos sin usarla hasta que se cierra sola (0: al salir de la Bóveda). */
    fun minutosCierre(ctx: Context) = prefs(ctx).getInt("cierre", 5)
    fun ponerMinutosCierre(ctx: Context, m: Int) { prefs(ctx).edit().putInt("cierre", m).apply(); tocar() }

    /** La comprobación de filtradas (Have I Been Pwned con k-anonimato). Apagada de serie. */
    fun comprobarFiltradas(ctx: Context) = prefs(ctx).getBoolean("filtradas", false)
    fun ponerComprobarFiltradas(ctx: Context, si: Boolean) {
        prefs(ctx).edit().putBoolean("filtradas", si).apply()
        if (!si) { _filtradas.value = emptyMap(); base?.delete("interno", "clave = ?", arrayOf("filtradas")) }
    }
    fun ultimaComprobacion(ctx: Context) = prefs(ctx).getLong("filtradas_cuando", 0)

    // ---------- Autocompletar ----------

    fun servicioAutofill(ctx: Context) = ComponentName(ctx, BovedaAutofill::class.java)
    fun servicioCredenciales(ctx: Context) = ComponentName(ctx, BovedaCredenciales::class.java)

    /** ¿Es la Bóveda el servicio de autocompletar del sistema? */
    fun esAutocompletar(ctx: Context): Boolean = runCatching {
        ctx.getSystemService(android.view.autofill.AutofillManager::class.java)?.hasEnabledAutofillServices() == true
    }.getOrDefault(false)

    /**
     * Pone la Bóveda como autocompletar (y como proveedor de contraseñas del Credential Manager) con
     * el permiso de sistema de SIOS. Devuelve false si no se ha podido (entonces se abre Android).
     */
    fun usarParaAutocompletar(ctx: Context): Boolean {
        val cr = ctx.contentResolver
        val ok = runCatching { Settings.Secure.putString(cr, "autofill_service", servicioAutofill(ctx).flattenToString()) }.getOrDefault(false)
        if (android.os.Build.VERSION.SDK_INT >= 34) runCatching {
            val nuestro = servicioCredenciales(ctx).flattenToString()
            val actuales = Settings.Secure.getString(cr, "credential_service").orEmpty().split(':').filter { it.isNotBlank() }
            if (nuestro !in actuales) Settings.Secure.putString(cr, "credential_service", (actuales + nuestro).joinToString(":"))
            Settings.Secure.putString(cr, "credential_service_primary", nuestro)
        }
        return ok
    }

    /**
     * Al arrancar SIOS por primera vez con la Bóveda: queda como autocompletar de serie. Solo una vez;
     * si luego el usuario elige otro en Android, se respeta.
     */
    fun alArrancar(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("de_serie", false)) return
        if (usarParaAutocompletar(ctx)) p.edit().putBoolean("de_serie", true).apply()
    }

    // ---------- Portapapeles ----------

    private val limpiar = Runnable {
        val c = app ?: return@Runnable
        val cm = c.getSystemService(ClipboardManager::class.java)
        runCatching {
            if (cm.primaryClipDescription?.label == ETIQUETA) cm.clearPrimaryClip()
        }
    }
    private const val ETIQUETA = "Bóveda"

    /**
     * Copia un secreto marcado como sensible (Android no lo enseña en la vista previa, el portapapeles
     * de SIOS no lo envía al ordenador) y lo borra del portapapeles a los 45 segundos.
     */
    fun copiar(ctx: Context, texto: String) {
        app = ctx.applicationContext
        val clip = ClipData.newPlainText(ETIQUETA, texto)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        principal.removeCallbacks(limpiar)
        principal.postDelayed(limpiar, 45_000L)
    }
}
