package com.sios.sistema

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.sios.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Prepara el agente sin que el usuario abra Termux: la app le pasa a Termux (RUN_COMMAND, permiso
 * concedido de serie en SIOS) /system/etc/sios/preparar.sh, que despliega su sistema base,
 * instala Node y arranca el servicio. Lo propio de cada IA se instala al elegirla. Cada paso llega
 * aquí por [Progreso].
 */
object Preparacion {
    /** Pasos del instalador (instalar.sh), como textos de la app. El 4 es elegir la IA, que hace la app. */
    val PASOS = listOf(
        R.string.preparacion_paso_paquetes,
        R.string.preparacion_paso_servicio,
        R.string.preparacion_paso_conexion,
    )
    const val PASO_SESION = 4

    /** El nombre del paso en el idioma del móvil (el script lo manda en español). */
    fun nombrePaso(ctx: Context, paso: Int, respaldo: String) = PASOS.getOrNull(paso - 1)?.let(ctx::getString) ?: respaldo

    data class Estado(
        val paso: Int = -1, // -1 sin empezar; 0 sistema base; 1..5 instalando; 6 listo para entrar
        val texto: String = "",
        val error: String? = null,
        val enMarcha: Boolean = false,
    )

    private val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    private const val TERMUX = "com.termux"
    private const val PREFS = "preparacion"
    private const val ACCION_FIN = "com.sios.FIN_PREPARACION"

    /** Recupera el último paso conocido (si la app se cerró a mitad de la instalación). */
    fun cargar(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Pasos de la preparación anterior (6, con Debian y Claude Code dentro): ya hecha.
        if (p.getInt("pasos", 0) != PASOS.size) {
            val antes = p.getInt("paso", -1)
            p.edit().putInt("pasos", PASOS.size).putInt("paso", if (antes >= 6) PASO_SESION else -1).remove("error").apply()
        }
        val reciente = System.currentTimeMillis() - p.getLong("ultimo", 0) < 10 * 60_000
        _estado.update {
            it.copy(
                paso = p.getInt("paso", -1),
                texto = p.getString("texto", "") ?: "",
                error = p.getString("error", null),
                enMarcha = reciente && p.getString("error", null) == null && p.getInt("paso", -1) in 0 until PASO_SESION,
            )
        }
    }

    private fun guardar(ctx: Context, e: Estado) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("paso", e.paso).putString("texto", e.texto).putString("error", e.error)
            .putLong("ultimo", System.currentTimeMillis()).apply()
    }

    fun termuxListo(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(TERMUX, 0) }.isSuccess &&
        ctx.checkSelfPermission("$TERMUX.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED

    /** Lanza (o relanza) la preparación. Es idempotente: lo ya instalado se salta. */
    fun lanzar(ctx: Context): Boolean {
        if (_estado.value.enMarcha) return true
        if (!termuxListo(ctx)) {
            actualizar(ctx, _estado.value.copy(error = ctx.getString(R.string.preparacion_sin_termux)))
            return false
        }
        val fin = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ACCION_FIN).setClass(ctx, Progreso::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // Termux añade el resultado
        )
        val orden = Intent("$TERMUX.RUN_COMMAND")
            .setClassName(TERMUX, "$TERMUX.app.RunCommandService")
            .putExtra("$TERMUX.RUN_COMMAND_PATH", "/system/bin/sh")
            .putExtra("$TERMUX.RUN_COMMAND_ARGUMENTS", arrayOf("/system/etc/sios/preparar.sh", nuevaFicha(ctx)))
            // La carpeta de Termux aún no existe antes de su sistema base; «cache» la crea Android al
            // instalar la app y Termux la acepta como carpeta de trabajo.
            .putExtra("$TERMUX.RUN_COMMAND_WORKDIR", "/data/data/com.termux/cache")
            .putExtra("$TERMUX.RUN_COMMAND_BACKGROUND", true)
            .putExtra("$TERMUX.RUN_COMMAND_COMMAND_LABEL", ctx.getString(R.string.preparacion_etiqueta))
            .putExtra("$TERMUX.RUN_COMMAND_PENDING_INTENT", fin)
        return runCatching { ctx.startForegroundService(orden) }
            .onSuccess { actualizar(ctx, Estado(paso = maxOf(0, _estado.value.paso), texto = ctx.getString(R.string.preparacion_preparando), enMarcha = true)) }
            .onFailure { actualizar(ctx, _estado.value.copy(error = ctx.getString(R.string.preparacion_error_termux, it.message), enMarcha = false)) }
            .isSuccess
    }

    internal fun actualizar(ctx: Context, e: Estado) {
        _estado.value = e
        guardar(ctx, e)
        // Preparado con esta versión del sistema: tras una actualización se vuelve a preparar.
        if (e.paso >= PASO_SESION && e.error == null) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("compilacion", SistemaSios.compilacion()).apply()
        }
    }

    /**
     * Arranca el servicio del agente (al encender el móvil). No se puede confiar en Termux:Boot:
     * Android no avisa del arranque a las apps que nunca se han abierto, y esa no se abre nunca.
     */
    fun arrancarServicio(ctx: Context): Boolean {
        if (!termuxListo(ctx)) return false
        val orden = Intent("$TERMUX.RUN_COMMAND")
            .setClassName(TERMUX, "$TERMUX.app.RunCommandService")
            .putExtra("$TERMUX.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            .putExtra("$TERMUX.RUN_COMMAND_ARGUMENTS", arrayOf("/data/data/com.termux/files/home/sios/iniciar.sh"))
            .putExtra("$TERMUX.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            .putExtra("$TERMUX.RUN_COMMAND_BACKGROUND", true)
            .putExtra("$TERMUX.RUN_COMMAND_COMMAND_LABEL", "SIOS")
        return runCatching { ctx.startForegroundService(orden) }
            .onFailure { android.util.Log.w("SistemaSios", "No se pudo arrancar el agente: ${it.message}") }
            .isSuccess
    }

    /**
     * Tras actualizar SIOS, el servicio del agente (copiado en Termux al prepararlo) se queda
     * en la versión anterior: si ya se había preparado con otra compilación, se repite por detrás.
     */
    fun alDiaTrasActualizar(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Preparado con una versión anterior sin marca: también se pone al día.
        val antes = p.getString("compilacion", null)
            ?: if (p.getInt("paso", -1) >= PASO_SESION) "anterior" else return // nunca preparado: lo hará la bienvenida
        val ahora = SistemaSios.compilacion() ?: return
        android.util.Log.i("SistemaSios", "Arranque: preparado con $antes, sistema $ahora")
        if (antes != ahora) lanzar(ctx) // prepara de nuevo y deja el servicio en marcha
        else arrancarServicio(ctx)
    }

    /**
     * Ficha de un solo uso que viaja como argumento al script y vuelve en cada aviso: así solo
     * cuentan los avisos de esta preparación (los argumentos de un proceso de Termux no los ve
     * ninguna otra app).
     */
    private fun nuevaFicha(ctx: Context): String {
        val ficha = java.util.UUID.randomUUID().toString()
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("ficha", ficha).apply()
        return ficha
    }

    internal fun fichaValida(ctx: Context, ficha: String?) =
        ficha != null && ficha == ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ficha", null)
}

/** Avisos del instalador (am broadcast desde Termux) y resultado final de RUN_COMMAND. */
class Progreso : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val actual = Preparacion.estado.value
        when (intent.action) {
            "com.sios.PROGRESO" -> {
                // El receptor es público (lo llama `am` desde Termux): sin la ficha, se ignora.
                if (!Preparacion.fichaValida(ctx, intent.getStringExtra("ficha"))) return
                val paso = intent.getIntExtra("paso", actual.paso)
                // El error del script viene en español: se cuenta en el idioma del móvil con el paso.
                val error = intent.getStringExtra("error")?.let {
                    ctx.getString(R.string.preparacion_parada_en, Preparacion.nombrePaso(ctx, paso, intent.getStringExtra("texto") ?: actual.texto))
                }
                Preparacion.actualizar(
                    ctx,
                    Preparacion.Estado(
                        paso = paso,
                        texto = intent.getStringExtra("texto") ?: actual.texto,
                        error = error,
                        enMarcha = error == null && paso < Preparacion.PASO_SESION,
                    ),
                )
            }
            "com.sios.FIN_PREPARACION" -> {
                // Es nuestro PendingIntent: llega aunque el script muera sin avisar.
                val r = intent.getBundleExtra("result")
                val codigo = r?.getInt("exitCode", -1) ?: -1
                val fallo = codigo != 0 || !r?.getString("errmsg").isNullOrBlank()
                // El detalle técnico va al registro; en pantalla, un mensaje que se entienda.
                if (fallo) android.util.Log.w("SistemaSios", "Preparación: código $codigo · ${r?.getString("errmsg")} · ${r?.getString("stderr")?.takeLast(1500)}")
                if (fallo && actual.error == null) {
                    val error = if (actual.paso <= 0) ctx.getString(R.string.preparacion_no_empezo)
                    else ctx.getString(R.string.preparacion_parada_en, Preparacion.nombrePaso(ctx, actual.paso, actual.texto))
                    Preparacion.actualizar(ctx, actual.copy(enMarcha = false, error = error))
                } else {
                    Preparacion.actualizar(ctx, actual.copy(enMarcha = false))
                }
            }
        }
    }
}
