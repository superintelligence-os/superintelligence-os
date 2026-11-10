package com.sios.sistema

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.StatFs
import androidx.core.app.NotificationCompat
import com.sios.R
import com.sios.datos.Servicio
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Actualización del sistema desde el propio móvil, sin cable, con vuelta atrás automática.
 *
 * SIOS es una GSI: en los móviles A/B virtuales, update_engine escribe el sistema nuevo en la otra
 * ranura mientras se usa el móvil, y al reiniciar se arranca desde ella. La app busca en el canal de
 * actualizaciones (el manifiesto firmado de scripts/publicar-version.sh, con su entrada «sistema»),
 * comprueba la firma Ed25519 del manifiesto, descarga la carga (reanudable, solo con wifi si se
 * elige) y comprueba su SHA-256. Lo que necesita root (update_engine, el gestor de arranque) lo hace
 * el ayudante del sistema, /system/etc/sios/sistema-ota.sh: se le habla con archivos en
 * [carpeta] (orden → él; estado ← él). Tras reiniciar, el ayudante no da el arranque por bueno hasta
 * que la app da señales de vida ([alArrancar] escribe el latido) y, si lo había, el agente responde;
 * si no, vuelve solo a la versión anterior.
 */
object SistemaOta {
    enum class Soporte { SI, NO_SIOS, SIN_AB, SIN_VIRTUAL_AB, SIN_CLAVE }

    /** Lo que se descarga: la carga completa o la diferencial (solo lo que cambia desde la compilación del móvil). */
    data class Pieza(
        val url: String, val tamano: Long, val sha256: String, val espacio: Long,
        val propiedades: Map<String, String>, val diferencial: Boolean,
    )

    data class Disponible(val version: String, val compilacion: String, val notas: String, val publicado: String, val pieza: Pieza) {
        fun json(): String = JSONObject()
            .put("version", version).put("compilacion", compilacion).put("notas", notas).put("publicado", publicado)
            .put("url", pieza.url).put("tamano", pieza.tamano).put("sha256", pieza.sha256).put("espacio", pieza.espacio)
            .put("diferencial", pieza.diferencial).put("propiedades", JSONObject(pieza.propiedades as Map<*, *>))
            .toString()

        companion object {
            fun de(texto: String?): Disponible? = runCatching {
                val o = JSONObject(texto ?: return null)
                val p = o.getJSONObject("propiedades")
                Disponible(
                    o.getString("version"), o.getString("compilacion"), o.optString("notas"), o.optString("publicado"),
                    Pieza(
                        o.getString("url"), o.getLong("tamano"), o.getString("sha256"), o.optLong("espacio"),
                        p.keys().asSequence().associateWith { p.getString(it) }, o.optBoolean("diferencial"),
                    ),
                )
            }.getOrNull()
        }
    }

    /** Lo que cuenta el ayudante en su archivo «estado» (clave=valor). */
    class Ayudante(val datos: Map<String, String>) {
        val fase get() = datos["fase"].orEmpty()
        val progreso get() = datos["progreso"]?.toIntOrNull() ?: 0
        val paso get() = datos["paso"].orEmpty()
        val mensaje get() = datos["mensaje"].orEmpty()
        val codigo get() = datos["codigo"].orEmpty()
        val compilacion get() = datos["compilacion"].orEmpty()
        val ranura get() = datos["ranura"].orEmpty()
        val cuando get() = datos["cuando"]?.toLongOrNull() ?: 0L
        /** Lo último que pasó tras un reinicio (se guarda hasta que la app dice «visto»). */
        val resultado get() = datos["resultado_fase"]?.let {
            Resultado(it, datos["resultado_compilacion"].orEmpty(), datos["resultado_version"].orEmpty(),
                datos["resultado_motivo"].orEmpty(), datos["resultado_cuando"]?.toLongOrNull() ?: 0L)
        }
    }

    data class Resultado(val fase: String, val compilacion: String, val version: String, val motivo: String, val cuando: Long) {
        val vueltaAtras get() = fase == "vuelta_atras"
    }

    sealed interface Busqueda {
        data object AlDia : Busqueda
        data object SinCanal : Busqueda
        data class Hay(val disponible: Disponible) : Busqueda
        data class Fallo(val firma: Boolean, val detalle: String) : Busqueda
    }

    // Lo que hace la app por su cuenta (antes de entregarle la carga al ayudante).
    const val NADA = ""
    const val DESCARGANDO = "descargando"
    const val COMPROBANDO = "comprobando"
    const val ENTREGADA = "entregada"
    const val ERROR = "error"

    private const val ID_DESCARGA = 7301
    private const val ID_BUSCAR = 7302
    private const val CANAL_AVISOS = "sistema_ota"
    private const val CANAL_PROGRESO = "sistema_ota_progreso"
    private const val NOTIF = 7301
    private const val CLAVE_PUBLICA = "/system/etc/sios/servicio/clave-actualizaciones.pem"

    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    // ---------- el sistema ----------

    @SuppressLint("PrivateApi")
    fun prop(nombre: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, nombre) as String
    }.getOrNull().orEmpty()

    fun soporte(): Soporte = when {
        prop("ro.sios.version").isBlank() -> Soporte.NO_SIOS
        prop("ro.boot.slot_suffix").isBlank() -> Soporte.SIN_AB
        prop("ro.virtual_ab.enabled") != "true" -> Soporte.SIN_VIRTUAL_AB
        prop("ro.sios.ota") != "1" -> Soporte.SIN_CLAVE
        else -> Soporte.SI
    }

    /** «google» o «puro»: cada edición tiene su propia carga. */
    fun edicion(): String = if (prop("ro.sios.base").contains("VANILLA")) "puro" else "google"

    /** Ranura en uso: «a» o «b». */
    fun ranura(): String = prop("ro.boot.slot_suffix").trimStart('_')

    /** Carpeta compartida con el ayudante (almacenamiento de antes de desbloquear: así vale nada más arrancar). */
    fun carpeta(ctx: Context): File = File(ctx.createDeviceProtectedStorageContext().filesDir, "sios-ota").apply { mkdirs() }

    private fun prefs(ctx: Context) = ctx.createDeviceProtectedStorageContext().getSharedPreferences("sistema_ota", Context.MODE_PRIVATE)

    fun ayudante(ctx: Context): Ayudante? {
        val f = File(carpeta(ctx), "estado")
        if (!f.exists()) return null
        val datos = runCatching { f.readLines() }.getOrNull()?.mapNotNull { l ->
            val i = l.indexOf('=')
            if (i > 0) l.substring(0, i) to l.substring(i + 1) else null
        }?.toMap() ?: return null
        return Ayudante(datos)
    }

    /** Escribe una orden para el ayudante (de una vez: nunca la lee a medias). */
    private fun ordenar(ctx: Context, campos: List<Pair<String, String>>) {
        val dir = carpeta(ctx)
        val tmp = File(dir, ".orden.tmp")
        tmp.writeText(campos.joinToString("") { (k, v) -> "$k=${v.replace("\n", " ")}\n" })
        tmp.renameTo(File(dir, "orden"))
    }

    // ---------- estado de la app ----------

    fun disponible(ctx: Context): Disponible? = Disponible.de(prefs(ctx).getString("disponible", null))
    fun faseLocal(ctx: Context): String = prefs(ctx).getString("fase", NADA) ?: NADA
    fun errorLocal(ctx: Context): String = prefs(ctx).getString("error", "") ?: ""
    fun soloWifi(ctx: Context): Boolean = prefs(ctx).getBoolean("solo_wifi", true)
    fun ultimaBusqueda(ctx: Context): Long = prefs(ctx).getLong("ultima_busqueda", 0L)
    fun descargado(ctx: Context): Long = File(carpeta(ctx), "carga.bin").length()
    fun trabajando(ctx: Context): Boolean = prefs(ctx).getBoolean("activa", false)
    fun pausada(ctx: Context): Boolean = prefs(ctx).getBoolean("pausada", false)

    private fun ponerFase(ctx: Context, fase: String, error: String = "") =
        prefs(ctx).edit().putString("fase", fase).putString("error", error).apply()

    fun ponerSoloWifi(ctx: Context, si: Boolean) {
        prefs(ctx).edit().putBoolean("solo_wifi", si).apply()
        // Si ya estaba descargando, se vuelve a programar con la condición nueva.
        if (faseLocal(ctx) == DESCARGANDO && !pausada(ctx)) programarDescarga(ctx)
    }

    /** Espacio libre en /data y el que hace falta: la descarga, su copia para update_engine y la instantánea. */
    fun espacio(ctx: Context, d: Disponible): Pair<Long, Long> {
        val libre = runCatching { StatFs(carpeta(ctx).path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        val necesario = d.pieza.tamano - descargado(ctx).coerceAtMost(d.pieza.tamano) + d.pieza.espacio + 300L * 1024 * 1024
        return libre to necesario
    }

    // ---------- buscar ----------

    private suspend fun canal(ctx: Context): String {
        // El que se haya puesto a mano en el agente o, sin él, el que trae la ROM.
        val delAgente = runCatching { Servicio.actualizaciones()?.optString("canal") }.getOrNull().orEmpty()
        val c = delAgente.ifBlank { prop("ro.sios.actualizaciones") }.ifBlank { prefs(ctx).getString("canal", "").orEmpty() }
        if (c.isNotBlank()) prefs(ctx).edit().putString("canal", c).apply()
        return c
    }

    private fun bajar(url: String): ByteArray {
        http.newCall(Request.Builder().url(url).header("Cache-Control", "no-store").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body.bytes()
        }
    }

    /** Busca una versión nueva del sistema para este móvil (sin descargar nada grande). */
    suspend fun buscar(ctx: Context): Busqueda {
        val url = canal(ctx)
        if (url.isBlank()) return Busqueda.SinCanal
        val r = runCatching {
            val texto = bajar(url)
            val firma = java.util.Base64.getDecoder().decode(String(bajar(url.replace(Regex("manifiesto\\.json(\\?.*)?$"), "manifiesto.firma"))).trim())
            val clave = FirmaEd25519.clavePem(File(CLAVE_PUBLICA).readText()) ?: throw IOException("sin clave")
            if (!FirmaEd25519.verificar(clave, texto, firma)) return Busqueda.Fallo(true, "")
            elegir(JSONObject(String(texto)), java.net.URI(url).resolve(".").toString(), SistemaSios.compilacion().orEmpty(), edicion(),
                prefs(ctx).getString("forzar_completa", "").orEmpty())
        }
        prefs(ctx).edit().putLong("ultima_busqueda", System.currentTimeMillis()).apply()
        return r.getOrElse { Busqueda.Fallo(false, it.message ?: it.javaClass.simpleName) }.also { b ->
            if (b is Busqueda.Hay && faseLocal(ctx) in listOf(NADA, ERROR)) prefs(ctx).edit().putString("disponible", b.disponible.json()).apply()
            if (b is Busqueda.AlDia && faseLocal(ctx) in listOf(NADA, ERROR)) prefs(ctx).edit().remove("disponible").apply()
        }
    }

    /**
     * De la lista «sistema» del manifiesto, la de esta edición si es más nueva que la del móvil: la
     * diferencial si sale de la compilación que tiene el móvil (y no falló antes), si no la completa.
     */
    internal fun elegir(m: JSONObject, base: String, actual: String, edicion: String, forzarCompleta: String): Busqueda {
        val lista = m.optJSONArray("sistema") ?: return Busqueda.AlDia
        val s = (0 until lista.length()).map { lista.getJSONObject(it) }.firstOrNull { it.optString("edicion") == edicion }
            ?: return Busqueda.AlDia
        val compilacion = s.optString("compilacion")
        if (compilacion.isBlank() || compilacion <= actual) return Busqueda.AlDia
        val dif = s.optJSONObject("diferencial")
        val usarDif = dif != null && dif.optString("desde") == actual && forzarCompleta != compilacion
        val p = if (usarDif) dif!! else s.getJSONObject("completa")
        val props = p.getJSONObject("propiedades")
        val pieza = Pieza(
            java.net.URI(base).resolve(p.getString("archivo")).toString(), p.getLong("tamano"), p.getString("sha256"),
            p.optLong("espacio"), props.keys().asSequence().associateWith { props.getString(it) }, usarDif,
        )
        return Busqueda.Hay(Disponible(s.optString("version"), compilacion, s.optString("notas"), m.optString("publicado"), pieza))
    }

    // ---------- descargar ----------

    fun descargar(ctx: Context) {
        val d = disponible(ctx) ?: return
        // Otra versión que la que estuviera a medias: se empieza de cero.
        if (prefs(ctx).getString("descargando", "") != d.pieza.url) File(carpeta(ctx), "carga.bin").delete()
        prefs(ctx).edit().putString("descargando", d.pieza.url).putBoolean("pausada", false).apply()
        ponerFase(ctx, DESCARGANDO)
        programarDescarga(ctx)
    }

    private fun programarDescarga(ctx: Context) {
        val d = disponible(ctx) ?: return
        val falta = (d.pieza.tamano - descargado(ctx)).coerceAtLeast(0)
        ctx.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(ID_DESCARGA, ComponentName(ctx, DescargaSistemaJob::class.java))
                .setRequiredNetworkType(if (soloWifi(ctx)) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(falta, 0)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build(),
        )
    }

    fun pausar(ctx: Context) {
        prefs(ctx).edit().putBoolean("pausada", true).apply()
        ctx.getSystemService(JobScheduler::class.java).cancel(ID_DESCARGA)
        quitarProgreso(ctx)
    }

    fun seguir(ctx: Context) {
        prefs(ctx).edit().putBoolean("pausada", false).apply()
        programarDescarga(ctx)
    }

    /** Cancela lo que haya a medias: la descarga, o la instalación en la otra ranura (el ayudante). */
    fun cancelar(ctx: Context) {
        ctx.getSystemService(JobScheduler::class.java).cancel(ID_DESCARGA)
        File(carpeta(ctx), "carga.bin").delete()
        prefs(ctx).edit().putBoolean("pausada", false).remove("descargando").apply()
        ponerFase(ctx, NADA)
        quitarProgreso(ctx)
        val a = ayudante(ctx)?.fase
        if (a in listOf("instalando", "verificando", "copiando", "lista")) ordenar(ctx, listOf("accion" to "cancelar"))
    }

    fun reiniciar(ctx: Context) = ordenar(ctx, listOf("accion" to "reiniciar"))

    /** El resultado del último reinicio ya se ha visto: el ayudante lo olvida. */
    fun visto(ctx: Context) {
        ordenar(ctx, listOf("accion" to "visto"))
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF)
    }

    /** Vuelve a intentarlo tras un error (si falló la diferencial, con la completa). */
    fun reintentar(ctx: Context) {
        ponerFase(ctx, NADA)
        prefs(ctx).edit().remove("disponible").apply()
    }

    /**
     * La descarga en sí (desde [DescargaSistemaJob]). Devuelve true si hay que seguir más tarde (el
     * sistema la ha parado o falló la red): continúa desde donde iba con una petición Range.
     */
    internal fun descargarAhora(ctx: Context, parar: () -> Boolean): Boolean {
        val d = disponible(ctx) ?: return false
        if (faseLocal(ctx) != DESCARGANDO || pausada(ctx)) return false
        val archivo = File(carpeta(ctx), "carga.bin")
        val total = d.pieza.tamano
        prefs(ctx).edit().putBoolean("activa", true).apply()
        try {
            if (archivo.length() > total) archivo.delete()
            var hecho = archivo.length()
            if (hecho < total) {
                val (libre, necesario) = espacio(ctx, d)
                if (libre < necesario) {
                    ponerFase(ctx, ERROR, "sin_espacio")
                    avisar(ctx, ctx.getString(R.string.ota_notif_error), ctx.getString(R.string.ota_err_espacio))
                    return false
                }
                val peticion = Request.Builder().url(d.pieza.url).apply { if (hecho > 0) header("Range", "bytes=$hecho-") }.build()
                http.newCall(peticion).execute().use { r ->
                    if (r.code == 404 || r.code == 410) { ponerFase(ctx, ERROR, "no_esta"); return false }
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    // Si el servidor no sabe continuar (200 en vez de 206), se empieza de cero.
                    if (r.code != 206) hecho = 0
                    RandomAccessFile(archivo, "rw").use { f ->
                        f.setLength(hecho)
                        f.seek(hecho)
                        val entrada = r.body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        var ultimoAviso = -1
                        while (true) {
                            if (parar() || pausada(ctx)) return !pausada(ctx)
                            val n = entrada.read(buf)
                            if (n < 0) break
                            f.write(buf, 0, minOf(n.toLong(), total - hecho).toInt())
                            hecho = minOf(hecho + n, total)
                            val pct = (hecho * 100 / total).toInt()
                            if (pct != ultimoAviso) { ultimoAviso = pct; progreso(ctx, d, pct) }
                            if (hecho >= total) break
                        }
                    }
                }
                if (archivo.length() < total) return true
            }
            // Completa: la huella tiene que ser la del manifiesto firmado.
            ponerFase(ctx, COMPROBANDO)
            if (huella(archivo) != d.pieza.sha256.lowercase()) {
                archivo.delete()
                ponerFase(ctx, ERROR, "huella")
                quitarProgreso(ctx)
                return false
            }
            entregar(ctx, d, archivo)
            quitarProgreso(ctx)
            return false
        } catch (e: IOException) {
            return true // sin red o cortada: se sigue cuando vuelva
        } finally {
            prefs(ctx).edit().putBoolean("activa", false).apply()
        }
    }

    private fun huella(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** La descarga comprobada pasa al ayudante, que la instala en la otra ranura. */
    private fun entregar(ctx: Context, d: Disponible, archivo: File) {
        val campos = mutableListOf(
            "accion" to "instalar",
            "id" to java.util.UUID.randomUUID().toString(),
            "archivo" to archivo.absolutePath,
            "tamano" to d.pieza.tamano.toString(),
            "sha256" to d.pieza.sha256.lowercase(),
            "compilacion" to d.compilacion,
            "version" to d.version,
        )
        for (k in listOf("FILE_HASH", "FILE_SIZE", "METADATA_HASH", "METADATA_SIZE")) campos += k to d.pieza.propiedades[k].orEmpty()
        prefs(ctx).edit().putLong("entregada", System.currentTimeMillis() / 1000).apply()
        ponerFase(ctx, ENTREGADA)
        ordenar(ctx, campos)
    }

    /**
     * Lo que pasa con lo entregado según el ayudante: si ha fallado, la app vuelve a su estado de
     * error (y si era la diferencial, la próxima vez va la completa). Lo llama la pantalla al leer.
     */
    fun repasar(ctx: Context, a: Ayudante?) {
        if (faseLocal(ctx) != ENTREGADA || a == null) return
        val entregada = prefs(ctx).getLong("entregada", 0L)
        when {
            a.fase == "error" && a.cuando >= entregada -> {
                val d = disponible(ctx)
                if (d?.pieza?.diferencial == true) prefs(ctx).edit().putString("forzar_completa", d.compilacion).apply()
                ponerFase(ctx, ERROR, a.codigo.ifBlank { a.mensaje })
            }
            a.fase in listOf("lista", "reiniciando") -> {
                ponerFase(ctx, NADA)
                prefs(ctx).edit().remove("disponible").remove("descargando").apply()
            }
        }
    }

    // ---------- arranque ----------

    /**
     * Al arrancar (antes y después de desbloquear): el latido que espera el ayudante para dar el
     * arranque por bueno, y el aviso de lo que pasó al reiniciar (actualizado o vuelta atrás).
     */
    fun alArrancar(ctx: Context) {
        if (soporte() != Soporte.SI) return
        latido(ctx)
        ayudante(ctx)?.resultado?.let { avisarResultado(ctx, it) }
        programarBusqueda(ctx)
    }

    fun latido(ctx: Context) {
        val arranque = ayudante(ctx)?.datos?.get("arranque") ?: return
        val dir = carpeta(ctx)
        val tmp = File(dir, ".latido.tmp")
        tmp.writeText("arranque=$arranque\ncompilacion=${SistemaSios.compilacion().orEmpty()}\ncuando=${System.currentTimeMillis() / 1000}\n")
        tmp.renameTo(File(dir, "latido"))
    }

    /** Una vez al día, con wifi: si hay versión nueva del sistema, se avisa (no se descarga sola: pesa mucho). */
    private fun programarBusqueda(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (js.getPendingJob(ID_BUSCAR) != null) return
        js.schedule(
            JobInfo.Builder(ID_BUSCAR, ComponentName(ctx, DescargaSistemaJob::class.java))
                .setPeriodic(24 * 3_600_000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build(),
        )
    }

    internal fun buscarYAvisar(ctx: Context) {
        val b = runBlocking { buscar(ctx) }
        if (b !is Busqueda.Hay) return
        val p = prefs(ctx)
        if (p.getString("avisada", "") == b.disponible.compilacion) return
        p.edit().putString("avisada", b.disponible.compilacion).apply()
        avisar(ctx, ctx.getString(R.string.ota_notif_hay), ctx.getString(R.string.ota_notif_hay_texto, b.disponible.version))
    }

    // ---------- avisos ----------

    private fun abrir(ctx: Context) = PendingIntent.getActivity(
        ctx, NOTIF,
        Intent().setClassName(ctx, "com.sios.ajustes.SistemaOtaActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun canales(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL_AVISOS) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL_AVISOS, ctx.getString(R.string.ota_canal_avisos), NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (nm.getNotificationChannel(CANAL_PROGRESO) == null) {
            nm.createNotificationChannel(NotificationChannel(CANAL_PROGRESO, ctx.getString(R.string.ota_canal_progreso), NotificationManager.IMPORTANCE_LOW))
        }
        return nm
    }

    private fun avisar(ctx: Context, titulo: String, texto: String, accion: Pair<String, PendingIntent>? = null) {
        val nm = canales(ctx)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(ctx, CANAL_AVISOS)
            .setSmallIcon(com.sios.ui.Marca.recurso())
            .setColor(com.sios.ui.Marca.acento(true).toInt())
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setContentIntent(abrir(ctx))
            .setAutoCancel(true)
            .apply { if (accion != null) addAction(0, accion.first, accion.second) }
            .build()
        nm.notify(NOTIF, n)
    }

    private fun progreso(ctx: Context, d: Disponible, pct: Int) {
        val nm = canales(ctx)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(ctx, CANAL_PROGRESO)
            .setSmallIcon(com.sios.ui.Marca.recurso())
            .setColor(com.sios.ui.Marca.acento(true).toInt())
            .setContentTitle(ctx.getString(R.string.ota_notif_descargando, d.version))
            .setProgress(100, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrir(ctx))
            .build()
        nm.notify(NOTIF + 1, n)
    }

    private fun quitarProgreso(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF + 1)

    /** Aviso del ayudante (lista, error, actualizado) o, al arrancar, del resultado del reinicio. */
    internal fun alAvisoDelAyudante(ctx: Context, fase: String?) {
        val a = ayudante(ctx)
        repasar(ctx, a)
        when (fase) {
            "lista" -> {
                val reiniciar = PendingIntent.getBroadcast(
                    ctx, NOTIF + 2, Intent(ctx, AvisoSistemaOta::class.java).setAction(AvisoSistemaOta.REINICIAR),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                avisar(ctx, ctx.getString(R.string.ota_notif_lista), ctx.getString(R.string.ota_notif_lista_texto),
                    ctx.getString(R.string.ota_reiniciar) to reiniciar)
            }
            "error" -> avisar(ctx, ctx.getString(R.string.ota_notif_error), mensajeError(ctx, a?.codigo?.ifBlank { a.mensaje }.orEmpty()))
            "actualizado" -> a?.resultado?.let { avisarResultado(ctx, it) }
        }
    }

    private fun avisarResultado(ctx: Context, r: Resultado) {
        val p = prefs(ctx)
        if (p.getLong("resultado_avisado", 0L) == r.cuando) return
        p.edit().putLong("resultado_avisado", r.cuando).apply()
        if (r.vueltaAtras) avisar(ctx, ctx.getString(R.string.ota_notif_vuelta), ctx.getString(R.string.ota_vuelta_nota, r.version.ifBlank { r.compilacion }, motivo(ctx, r.motivo)))
        else avisar(ctx, ctx.getString(R.string.ota_notif_actualizado), ctx.getString(R.string.ota_actualizado, r.version.ifBlank { r.compilacion }))
    }

    fun motivo(ctx: Context, m: String): String = ctx.getString(
        when (m) {
            "sistema" -> R.string.ota_motivo_sistema
            "app" -> R.string.ota_motivo_app
            "agente" -> R.string.ota_motivo_agente
            "arranques" -> R.string.ota_motivo_arranques
            "confirmar" -> R.string.ota_motivo_confirmar
            else -> R.string.ota_motivo_no_arranca
        },
    )

    /** Texto para un error del ayudante, de update_engine o de la descarga. */
    fun mensajeError(ctx: Context, codigo: String): String = when {
        codigo == "huella" || codigo.contains("huella") -> ctx.getString(R.string.ota_err_huella)
        codigo == "sin_espacio" || codigo == "kNotEnoughSpace" || codigo.contains("Space") -> ctx.getString(R.string.ota_err_espacio)
        codigo == "no_esta" -> ctx.getString(R.string.ota_err_no_esta)
        codigo.contains("PubKeyVerification") || codigo.contains("SignatureMismatch") || codigo.contains("SignedDeltaPayloadExpected") ||
            codigo.contains("PayloadVerificationError") -> ctx.getString(R.string.ota_err_clave)
        codigo.contains("OperationHash") || codigo.contains("DownloadStateInitialization") || codigo.contains("SourceHash") ||
            codigo.contains("RootfsVerification") -> ctx.getString(R.string.ota_err_origen)
        codigo.contains("Timestamp") -> ctx.getString(R.string.ota_err_antigua)
        codigo.isBlank() -> ctx.getString(R.string.ota_err_otro, "?")
        else -> ctx.getString(R.string.ota_err_otro, codigo.removePrefix("k"))
    }
}

/** Descarga del sistema (reanudable) y búsqueda diaria. */
class DescargaSistemaJob : JobService() {
    @Volatile private var parar = false

    override fun onStartJob(p: JobParameters): Boolean {
        parar = false
        Thread {
            val seguir = runCatching {
                if (p.jobId == 7302) { SistemaOta.buscarYAvisar(this); false } else SistemaOta.descargarAhora(this) { parar }
            }.getOrDefault(true)
            jobFinished(p, seguir && p.jobId != 7302)
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean {
        parar = true
        return true
    }
}

/** Avisos del ayudante del sistema (am broadcast como root) y el botón «Reiniciar» de la notificación. */
class AvisoSistemaOta : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            REINICIAR -> SistemaOta.reiniciar(ctx)
            else -> SistemaOta.alAvisoDelAyudante(ctx, intent.getStringExtra("fase"))
        }
    }

    companion object {
        const val REINICIAR = "com.sios.SISTEMA_OTA_REINICIAR"
    }
}
