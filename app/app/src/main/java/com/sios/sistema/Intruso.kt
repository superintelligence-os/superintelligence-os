package com.sios.sistema

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.UserHandle
import android.util.Base64
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.sios.R
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * Foto a quien intente desbloquear el móvil (antirrobo, apagado de serie). Tras dos intentos
 * fallidos seguidos, la cámara frontal hace una foto pequeña, se guarda en el móvil y se manda al
 * ordenador vinculado (por la red local o por Nostr, cifrada como todo lo de «Encontrar mi móvil»).
 *
 * Android avisa de los intentos fallidos a los administradores del dispositivo (permiso
 * «watch-login»): por eso hace falta activar SIOS como administrador, con la confirmación del sistema.
 * La foto la hace una pantalla transparente encima del bloqueo (una app en segundo plano no puede
 * usar la cámara); el punto verde de cámara en uso de Android se ve, como debe ser.
 */
object Intruso {
    private const val INTENTOS = 2
    private const val MAX_FOTOS = 10

    private fun prefs(ctx: Context) =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("intruso", Context.MODE_PRIVATE)

    /** Las fotos, en el almacenamiento que existe antes del primer desbloqueo (el móvil puede estar recién encendido). */
    private fun carpeta(ctx: Context) = File(ctx.createDeviceProtectedStorageContext().filesDir, "intrusos").apply { mkdirs() }

    fun admin(ctx: Context) = ComponentName(ctx, IntrusoAdmin::class.java)

    fun activo(ctx: Context): Boolean =
        prefs(ctx).getBoolean("activo", false) && ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(ctx))

    /** Lo enciende: si SIOS aún no es administrador, el sistema pide confirmarlo (devuelve el intent para lanzarlo). */
    fun encender(ctx: Context): Intent? {
        prefs(ctx).edit().putBoolean("activo", true).apply()
        if (ctx.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(ctx))) return null
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(ctx))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, ctx.getString(R.string.intruso_explicacion_admin))
    }

    /** Lo apaga y deja de ser administrador (no hace falta para nada más). */
    fun apagar(ctx: Context) {
        prefs(ctx).edit().putBoolean("activo", false).apply()
        runCatching { ctx.getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(admin(ctx)) }
    }

    internal fun intentoFallido(ctx: Context) {
        if (!prefs(ctx).getBoolean("activo", false)) return
        val n = prefs(ctx).getInt("seguidos", 0) + 1
        prefs(ctx).edit().putInt("seguidos", n).apply()
        // Una foto al llegar a dos intentos y otra cada tres más (no una por intento).
        if (n == INTENTOS || (n > INTENTOS && (n - INTENTOS) % 3 == 0)) {
            runCatching {
                ctx.startActivity(
                    Intent(ctx, FotoIntrusoActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                )
            }.onFailure { android.util.Log.w("SIOS", "No se pudo abrir la cámara para la foto", it) }
        }
    }

    internal fun desbloqueado(ctx: Context) {
        prefs(ctx).edit().putInt("seguidos", 0).apply()
        mandarPendientes(ctx)
    }

    internal fun guardar(ctx: Context, jpeg: ByteArray) {
        val cuando = System.currentTimeMillis()
        File(carpeta(ctx), "$cuando.jpg").writeBytes(jpeg)
        carpeta(ctx).listFiles()?.sortedByDescending { it.name }?.drop(MAX_FOTOS)?.forEach { it.delete() }
        prefs(ctx).edit().putStringSet("pendientes", (prefs(ctx).getStringSet("pendientes", emptySet()).orEmpty() + "$cuando")).apply()
        mandarPendientes(ctx)
    }

    /**
     * Manda al servicio las fotos que no han llegado (antes del primer desbloqueo el servicio no está
     * en marcha: se reintenta al desbloquear y al arrancar la app).
     */
    fun mandarPendientes(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val pendientes = prefs(app).getStringSet("pendientes", emptySet()).orEmpty()
            for (c in pendientes.sorted()) {
                val f = File(carpeta(app), "$c.jpg")
                if (!f.exists()) { quitarPendiente(app, c); continue }
                val datos = JSONObject().put("cuando", c.toLong()).put("foto", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
                if (com.sios.datos.Servicio.avisoAntirroboAhora("intruso", datos)) quitarPendiente(app, c)
            }
        }.start()
    }

    private fun quitarPendiente(ctx: Context, c: String) = synchronized(this) {
        prefs(ctx).edit().putStringSet("pendientes", prefs(ctx).getStringSet("pendientes", emptySet()).orEmpty() - c).apply()
    }

    /** Al desbloquear, lo pendiente sale (y se reinicia la cuenta de intentos). */
    fun vigilarDesbloqueo(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = desbloqueado(app)
            }, IntentFilter(Intent.ACTION_USER_PRESENT))
        }
        mandarPendientes(app)
    }

    /** Foto pequeña (lado mayor 360 px, JPEG): basta para reconocer a alguien y cabe en «Encontrar mi móvil». */
    internal fun reducir(original: Bitmap, giro: Int): ByteArray {
        val escala = 360f / maxOf(original.width, original.height)
        val m = Matrix().apply { postScale(escala, escala); postRotate(giro.toFloat()) }
        val b = Bitmap.createBitmap(original, 0, 0, original.width, original.height, m, true)
        var calidad = 70
        while (true) {
            val salida = ByteArrayOutputStream()
            b.compress(Bitmap.CompressFormat.JPEG, calidad, salida)
            if (salida.size() <= 20_000 || calidad <= 30) return salida.toByteArray()
            calidad -= 10
        }
    }
}

/** Android le cuenta los intentos de desbloqueo (y los aciertos) al administrador del dispositivo. */
class IntrusoAdmin : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) = Intruso.intentoFallido(context)
    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) = Intruso.desbloqueado(context)
}

/** Pantalla transparente encima del bloqueo: abre la cámara frontal, hace la foto y se cierra sola. */
class FotoIntrusoActivity : ComponentActivity() {
    private val hilo = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // Por si la cámara no responde: nunca se queda encima del bloqueo.
        window.decorView.postDelayed({ if (!isFinishing) finish() }, 8_000)
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener({
            runCatching {
                val proveedor = futuro.get()
                val captura = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                proveedor.unbindAll()
                // La frontal (la de quien mira la pantalla); si el móvil no tiene, la que haya.
                val camara = if (proveedor.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                proveedor.bindToLifecycle(this, camara, captura)
                // Un momento para que la exposición se ajuste (si no, sale negra).
                window.decorView.postDelayed({
                    captura.takePicture(hilo, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(imagen: ImageProxy) {
                            runCatching {
                                // toBitmap vale para JPEG y para YUV (según el móvil, la cámara entrega uno u otro).
                                Intruso.guardar(this@FotoIntrusoActivity, Intruso.reducir(imagen.toBitmap(), imagen.imageInfo.rotationDegrees))
                            }.onFailure { android.util.Log.w("SIOS", "No se pudo guardar la foto de intruso", it) }
                            imagen.close()
                            runOnUiThread { finish() }
                        }

                        override fun onError(e: ImageCaptureException) {
                            android.util.Log.w("SIOS", "Foto de intruso fallida", e)
                            runOnUiThread { finish() }
                        }
                    })
                }, 700)
            }.onFailure { finish() }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        hilo.shutdown()
        super.onDestroy()
    }
}
