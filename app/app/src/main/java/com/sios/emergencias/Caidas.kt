package com.sios.emergencias

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sios.R
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Detección de caídas (apagada de serie): el acelerómetro busca la secuencia de una caída de verdad
 * —caída libre, golpe fuerte y, después, el móvil quieto en otra postura— y entonces pregunta
 * «¿Estás bien?» con una cuenta atrás antes de pedir ayuda.
 *
 * Batería: se usa el acelerómetro «que despierta» del concentrador de sensores con lotes (batching):
 * el chip de sensores guarda las lecturas y despierta al procesador cada 10 s para entregarlas de una
 * vez, en lugar de 50 veces por segundo. Las lecturas llevan su hora, así que la caída se reconstruye
 * igual aunque llegue unos segundos después (de todas formas hay que esperar a ver si el móvil se
 * queda quieto). Sin ese sensor, solo funciona mientras el móvil está despierto: se avisa en Ajustes.
 */
object Caidas {
    const val AVISO_CAIDA = 7113

    /** Cómo gasta en este móvil: según el sensor que tenga. */
    enum class Consumo { BAJO, MEDIO, LIMITADO, SIN_SENSOR }

    fun consumo(ctx: Context): Consumo {
        val sm = ctx.getSystemService(SensorManager::class.java) ?: return Consumo.SIN_SENSOR
        val despierta = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        return when {
            despierta != null && despierta.fifoMaxEventCount >= 300 -> Consumo.BAJO
            despierta != null -> Consumo.MEDIO
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null -> Consumo.LIMITADO
            else -> Consumo.SIN_SENSOR
        }
    }

    fun arrancar(ctx: Context) {
        if (consumo(ctx) == Consumo.SIN_SENSOR) return
        runCatching { ctx.startForegroundService(Intent(ctx, CaidasServicio::class.java)) }
            .onFailure { android.util.Log.w("SIOS", "No se pudo arrancar la detección de caídas", it) }
    }

    fun parar(ctx: Context) {
        runCatching { ctx.stopService(Intent(ctx, CaidasServicio::class.java)) }
    }

    /** Ha habido una caída: «¿Estás bien?» encima de todo (también con el móvil bloqueado). */
    fun preguntar(ctx: Context) {
        val app = ctx.applicationContext
        val i = Intent(app, SosActivity::class.java)
            .putExtra(SosActivity.EXTRA_MOTIVO, Emergencias.Motivo.CAIDA.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Un aviso a pantalla completa por si Android no deja abrir la pantalla directamente.
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CANAL_ALERTA) == null) {
                nm.createNotificationChannel(NotificationChannel(CANAL_ALERTA, app.getString(R.string.em_canal_caida), NotificationManager.IMPORTANCE_HIGH).apply { setBypassDnd(true) })
            }
            val pi = PendingIntent.getActivity(app, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(
                AVISO_CAIDA,
                NotificationCompat.Builder(app, CANAL_ALERTA).setSmallIcon(R.drawable.ic_sos)
                    .setContentTitle(app.getString(R.string.em_caida_titulo, ""))
                    .setContentText(app.getString(R.string.em_caida_aviso))
                    .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setColor(0xFFC62828.toInt())
                    .setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true).build(),
            )
        }
        runCatching { app.startActivity(i) }
    }

    private const val CANAL_ALERTA = "caidas_alerta"
    internal const val CANAL = "caidas"
}

/**
 * El detector, sin Android: recibe lecturas del acelerómetro (hora en nanosegundos y m/s²) y dice
 * cuándo ha habido una caída. Las cuatro fases y sus umbrales (en g):
 *  1. caída libre: menos de 0,5 g durante al menos 100 ms;
 *  2. golpe: un pico de al menos 3 g en el segundo siguiente;
 *  3. quietud: de 1,5 s a 5,5 s después del golpe, casi sin movimiento (desviación < 0,12 g) y con
 *     el móvil apoyado (media entre 0,85 y 1,15 g);
 *  4. otra postura: la gravedad apunta al menos 35° distinto que antes de caer (de pie → tumbado).
 * Correr, saltar o sentarse de golpe dan caída libre y golpe, pero no quietud en otra postura.
 */
class DetectorCaidas(
    private val umbralLibre: Double = 0.5,
    private val libreMinNs: Long = 100_000_000L,
    private val umbralGolpe: Double = 3.0,
    private val ventanaGolpeNs: Long = 1_000_000_000L,
    private val quietudDesdeNs: Long = 1_500_000_000L,
    private val quietudDuraNs: Long = 4_000_000_000L,
    private val desviacionMax: Double = 0.12,
    private val anguloMin: Double = 35.0,
    /** Tras avisar, no vuelve a saltar en este tiempo. */
    private val descansoNs: Long = 60_000_000_000L,
) {
    private enum class Fase { NORMAL, LIBRE, GOLPE, QUIETUD }

    private var fase = Fase.NORMAL
    private var inicioLibre = 0L
    private var finLibre = 0L
    private var horaGolpe = 0L
    private var pico = 0.0
    private var ultimoAviso = Long.MIN_VALUE / 2

    // Gravedad media antes de caer (filtro lento) y la que se guarda al empezar la caída.
    private val gravedad = DoubleArray(3)
    private var hayGravedad = false
    private val gravedadAntes = DoubleArray(3)
    private var ultimaHora = 0L

    // Quietud: sumas para la media y la desviación del módulo, y la media del vector.
    private var n = 0
    private var suma = 0.0
    private var sumaCuadrados = 0.0
    private val sumaVector = DoubleArray(3)

    /** Lo último que ha pasado, para depurar (y para las pruebas). */
    var motivoDescarte: String? = null
        private set

    /** Una lectura; devuelve true justo cuando se confirma una caída. */
    fun lectura(horaNs: Long, x: Float, y: Float, z: Float): Boolean {
        val gx = x / G; val gy = y / G; val gz = z / G
        val m = sqrt(gx * gx + gy * gy + gz * gz)
        val dt = if (ultimaHora == 0L) 0.0 else (horaNs - ultimaHora) / 1e9
        ultimaHora = horaNs
        when (fase) {
            Fase.NORMAL -> {
                // La gravedad «de antes» se va siguiendo solo mientras no pasa nada (≈1 s de memoria).
                if (m in 0.7..1.3) {
                    val a = if (!hayGravedad) 1.0 else (dt / 1.0).coerceIn(0.0, 1.0)
                    gravedad[0] += (gx - gravedad[0]) * a; gravedad[1] += (gy - gravedad[1]) * a; gravedad[2] += (gz - gravedad[2]) * a
                    hayGravedad = true
                }
                if (m < umbralLibre && horaNs - ultimoAviso > descansoNs) {
                    fase = Fase.LIBRE
                    inicioLibre = horaNs
                    gravedad.copyInto(gravedadAntes)
                }
            }
            Fase.LIBRE -> if (m >= umbralLibre) {
                if (horaNs - inicioLibre >= libreMinNs) {
                    fase = Fase.GOLPE
                    finLibre = horaNs
                    pico = m
                    horaGolpe = horaNs
                } else descartar("caída libre demasiado corta")
            }
            Fase.GOLPE -> {
                if (m > pico) { pico = m; horaGolpe = horaNs }
                if (horaNs - finLibre > ventanaGolpeNs) {
                    if (pico >= umbralGolpe) {
                        fase = Fase.QUIETUD
                        n = 0; suma = 0.0; sumaCuadrados = 0.0; sumaVector.fill(0.0)
                    } else descartar("golpe flojo (%.1f g)".format(java.util.Locale.ROOT, pico))
                }
            }
            Fase.QUIETUD -> {
                val desde = horaGolpe + quietudDesdeNs
                if (horaNs >= desde) {
                    n++; suma += m; sumaCuadrados += m * m
                    sumaVector[0] += gx; sumaVector[1] += gy; sumaVector[2] += gz
                    // Si se mueve mucho a mitad, no hace falta esperar: no es una caída que deje quieto.
                    if (n > 10 && desviacion() > desviacionMax * 2.5) return descartar("se mueve después del golpe").let { false }
                }
                if (horaNs >= desde + quietudDuraNs) return evaluar(horaNs)
            }
        }
        return false
    }

    private fun desviacion(): Double {
        if (n < 2) return 0.0
        val media = suma / n
        return sqrt((sumaCuadrados / n - media * media).coerceAtLeast(0.0))
    }

    private fun evaluar(horaNs: Long): Boolean {
        val media = if (n > 0) suma / n else 0.0
        val angulo = angulo(gravedadAntes, doubleArrayOf(sumaVector[0] / n, sumaVector[1] / n, sumaVector[2] / n))
        return when {
            n < 20 -> descartar("pocas lecturas en la quietud").let { false }
            desviacion() > desviacionMax -> descartar("no se queda quieto").let { false }
            media !in 0.85..1.15 -> descartar("no está apoyado").let { false }
            hayGravedad && angulo < anguloMin -> descartar("misma postura (%.0f°)".format(java.util.Locale.ROOT, angulo)).let { false }
            else -> {
                motivoDescarte = null
                fase = Fase.NORMAL
                ultimoAviso = horaNs
                true
            }
        }
    }

    private fun descartar(motivo: String) {
        motivoDescarte = motivo
        fase = Fase.NORMAL
    }

    private fun angulo(a: DoubleArray, b: DoubleArray): Double {
        val na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2])
        if (na < 1e-6 || nb < 1e-6) return 0.0
        val c = ((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    private companion object {
        const val G = 9.80665
    }
}

/**
 * El servicio que escucha el acelerómetro mientras la detección está activada. Va en primer plano
 * (con un aviso discreto, sin sonido) para que Android no lo cierre.
 */
class CaidasServicio : Service(), SensorEventListener {
    private var detector = DetectorCaidas()

    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Emergencias.caidas(this)) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(Caidas.CANAL) == null) {
            nm.createNotificationChannel(NotificationChannel(Caidas.CANAL, getString(R.string.em_canal_caidas), NotificationManager.IMPORTANCE_MIN).apply { setSound(null, null); setShowBadge(false) })
        }
        val abrir = PendingIntent.getActivity(
            this, 4, Intent(this, com.sios.ajustes.AjustesActivity::class.java).putExtra("seccion", "caidas").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, Caidas.CANAL).setSmallIcon(R.drawable.ic_sos)
            .setContentTitle(getString(R.string.em_caidas_aviso)).setContentText(getString(R.string.em_caidas_aviso_texto))
            .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN).setContentIntent(abrir).build()
        val tipo = if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0
        runCatching { startForeground(Caidas.AVISO_CAIDA + 1, n, tipo) }.onFailure {
            android.util.Log.w("SIOS", "Detección de caídas sin primer plano", it)
        }
        escuchar()
        return START_STICKY
    }

    private fun escuchar() {
        val sm = getSystemService(SensorManager::class.java) ?: return
        sm.unregisterListener(this)
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        // 50 lecturas por segundo, entregadas en lotes de hasta 10 s (lo que quepa en el chip).
        val lote = if (sensor.fifoMaxEventCount > 0) minOf(10_000_000, sensor.fifoMaxEventCount * 20_000 * 8 / 10) else 0
        sm.registerListener(this, sensor, 20_000, lote)
    }

    override fun onSensorChanged(e: SensorEvent) {
        // Con un SOS en marcha no se pregunta otra vez.
        if (detector.lectura(e.timestamp, e.values[0], e.values[1], e.values[2]) && SosServicio.estado.value?.activo != true) {
            android.util.Log.i("SIOS", "Posible caída: «¿Estás bien?»")
            Caidas.preguntar(this)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        getSystemService(SensorManager::class.java)?.unregisterListener(this)
        super.onDestroy()
    }
}

/** Al encender el móvil (antes incluso de desbloquear) o al actualizar SIOS: si estaba activada, sigue. */
class ArranqueEmergencias : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Emergencias.caidas(context)) Caidas.arrancar(context)
    }
}
