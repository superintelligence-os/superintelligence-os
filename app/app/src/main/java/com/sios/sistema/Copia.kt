package com.sios.sistema

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import com.sios.datos.Servicio
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Copia de seguridad de SIOS en un solo archivo «.sios», cifrado con la contraseña del
 * usuario (AES-256-GCM con clave derivada por PBKDF2). Dentro: los datos del agente (conversaciones,
 * creaciones, rutinas, ajustes, actividad), los ajustes de la app (bloqueados, avisos, inicio…) y la
 * memoria del móvil. No va la sesión de Claude. Se guarda en la carpeta que elija el usuario (Drive
 * incluido) y, si quiere, sola una vez a la semana con el móvil cargando.
 */
object Copia {
    private const val MAGIA = "SIOS-COPIA-1"
    private const val ITERACIONES = 210_000
    private const val ALIAS = "sios-copia"
    private const val GUARDAR = 4 // copias automáticas que se conservan en la carpeta

    private val http by lazy { OkHttpClient.Builder().readTimeout(180, TimeUnit.SECONDS).build() }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("copia", Context.MODE_PRIVATE)

    // ---------- Crear y restaurar ----------

    /** Junta todo, lo cifra con la contraseña y lo escribe en destino. */
    fun crear(ctx: Context, destino: Uri, contrasena: CharArray) {
        val agente = http.newCall(Request.Builder().url("http://127.0.0.1:8765/copia").build()).execute().use { r ->
            if (!r.isSuccessful) error("El agente no ha podido preparar su parte de la copia")
            r.body.bytes()
        }
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            fun meter(nombre: String, datos: ByteArray) { z.putNextEntry(ZipEntry(nombre)); z.write(datos); z.closeEntry() }
            meter("agente.tar.gz", agente)
            // Ajustes de la app (salvo los de la propia copia, que llevan su clave).
            File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles()?.filter { it.name != "copia.xml" }?.forEach {
                meter("ajustes/${it.name}", it.readBytes())
            }
            // La memoria del móvil, en una foto coherente de la base de datos.
            Memoria.copiar(ctx)?.let { meter("memoria.db", it) }
            meter("info.json", """{"creada":${System.currentTimeMillis()},"version":"${version(ctx)}"}""".toByteArray())
        }
        val cifrado = cifrar(zip.toByteArray(), contrasena)
        ctx.contentResolver.openOutputStream(destino, "w")!!.use { it.write(cifrado) }
        prefs(ctx).edit().putLong("ultima", System.currentTimeMillis()).apply()
    }

    /** Descifra la copia (error claro si la contraseña no es) y lo devuelve todo a su sitio. */
    fun restaurar(ctx: Context, origen: Uri, contrasena: CharArray) {
        val bytes = ctx.contentResolver.openInputStream(origen)!!.use { it.readBytes() }
        val zip = descifrar(bytes, contrasena)
        var agente: ByteArray? = null
        var memoria: ByteArray? = null
        val ajustes = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val datos = z.readBytes()
                when {
                    e.name == "agente.tar.gz" -> agente = datos
                    e.name == "memoria.db" -> memoria = datos
                    e.name.startsWith("ajustes/") && !e.name.contains("..") && e.name.endsWith(".xml") -> ajustes[e.name.removePrefix("ajustes/")] = datos
                }
            }
        }
        agente?.let { a ->
            http.newCall(Request.Builder().url("http://127.0.0.1:8765/copia/restaurar").post(a.toRequestBody("application/gzip".toMediaType())).build())
                .execute().use { if (!it.isSuccessful) error("El agente no ha podido restaurar su parte") }
        }
        memoria?.let { Memoria.reemplazar(ctx, it) }
        // Los ajustes quedan exactamente como en la copia: lo que no venía en ella se quita.
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        if (ajustes.isNotEmpty()) dir.listFiles()?.filter { it.name != "copia.xml" && it.name !in ajustes }?.forEach { it.delete() }
        for ((nombre, datos) in ajustes) File(dir, nombre).writeBytes(datos)
    }

    // ---------- Cifrado ----------

    private fun clave(contrasena: CharArray, sal: ByteArray): SecretKey {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(contrasena, sal, ITERACIONES, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    private fun cifrar(datos: ByteArray, contrasena: CharArray): ByteArray {
        val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, clave(contrasena, sal), GCMParameterSpec(128, iv)) }
        c.updateAAD(MAGIA.toByteArray())
        return MAGIA.toByteArray() + sal + iv + c.doFinal(datos)
    }

    private fun descifrar(todo: ByteArray, contrasena: CharArray): ByteArray {
        val m = MAGIA.toByteArray()
        if (todo.size < m.size + 28 || !todo.copyOfRange(0, m.size).contentEquals(m)) throw ErrorCopia.NoEsCopia
        val sal = todo.copyOfRange(m.size, m.size + 16)
        val iv = todo.copyOfRange(m.size + 16, m.size + 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, clave(contrasena, sal), GCMParameterSpec(128, iv)) }
        c.updateAAD(m)
        return try {
            c.doFinal(todo, m.size + 28, todo.size - m.size - 28)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw ErrorCopia.Contrasena
        }
    }

    sealed class ErrorCopia(msg: String) : Exception(msg) {
        object Contrasena : ErrorCopia("contrasena")
        object NoEsCopia : ErrorCopia("no_es_copia")
    }

    // ---------- Copia automática ----------

    /** La contraseña de las copias automáticas, guardada cifrada con una clave del Keystore del móvil. */
    fun guardarContrasena(ctx: Context, contrasena: CharArray) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val k = (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k) }
        val datos = c.doFinal(String(contrasena).toByteArray())
        prefs(ctx).edit()
            .putString("contrasena", Base64.encodeToString(c.iv + datos, Base64.NO_WRAP))
            .apply()
    }

    private fun contrasena(ctx: Context): CharArray? = runCatching {
        val todo = Base64.decode(prefs(ctx).getString("contrasena", null) ?: return null, Base64.NO_WRAP)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val k = ks.getKey(ALIAS, null) as SecretKey
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, todo.copyOfRange(0, 12))) }
        String(c.doFinal(todo, 12, todo.size - 12)).toCharArray()
    }.getOrNull()

    fun carpeta(ctx: Context): Uri? = prefs(ctx).getString("carpeta", null)?.let(Uri::parse)
    fun ultima(ctx: Context): Long = prefs(ctx).getLong("ultima", 0)
    fun automatica(ctx: Context) = carpeta(ctx) != null && prefs(ctx).contains("contrasena")

    /** Activa las copias semanales en esa carpeta, con esa contraseña. */
    fun activarAutomatica(ctx: Context, carpeta: Uri, contrasena: CharArray) {
        ctx.contentResolver.takePersistableUriPermission(carpeta, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        guardarContrasena(ctx, contrasena)
        prefs(ctx).edit().putString("carpeta", carpeta.toString()).apply()
        CopiaJob.programar(ctx)
    }

    fun desactivarAutomatica(ctx: Context) {
        prefs(ctx).edit().remove("carpeta").remove("contrasena").apply()
        ctx.getSystemService(JobScheduler::class.java).cancel(CopiaJob.ID)
    }

    /** Una copia en la carpeta elegida («SIOS 2026-11-08.sios»), y borra las más viejas. */
    fun copiaAutomatica(ctx: Context) {
        val dir = DocumentFile.fromTreeUri(ctx, carpeta(ctx) ?: return) ?: return
        val clave = contrasena(ctx) ?: return
        val nombre = "SIOS ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date())}.sios"
        val archivo = dir.createFile("application/octet-stream", nombre) ?: error("No se puede escribir en la carpeta")
        crear(ctx, archivo.uri, clave)
        dir.listFiles().filter { it.name?.startsWith("SIOS ") == true && it.name?.endsWith(".sios") == true }
            .sortedByDescending { it.lastModified() }.drop(GUARDAR).forEach { it.delete() }
        Servicio.registrarActividad("Copia de seguridad automática", nombre)
    }

    fun nombreSugerido() = "SIOS ${SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())}.sios"

    private fun version(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
}

/** Copia automática semanal (con el móvil cargando y sin usar). */
class CopiaJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            val ok = runCatching { Copia.copiaAutomatica(this) }.isSuccess
            jobFinished(p, !ok)
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters) = true

    companion object {
        const val ID = 51
        fun programar(ctx: Context) {
            ctx.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(ID, ComponentName(ctx, CopiaJob::class.java))
                    .setPeriodic(7 * 24 * 3_600_000L)
                    .setRequiresCharging(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY) // por si la carpeta es de Drive
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
