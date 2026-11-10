package com.sios.sistema

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bienestar digital: cuánto se usa cada app (hoy y los últimos días, con las estadísticas de uso de
 * Android) para el resumen semanal y los límites que se piden hablando con Claude.
 */
object Bienestar {
    fun permitido(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }

    /** Milisegundos en primer plano por paquete entre dos instantes (con los eventos: lo exacto). */
    private fun usoEntre(ctx: Context, desde: Long, hasta: Long): Map<String, Long> {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        val eventos = usm.queryEvents(desde, hasta)
        val abiertas = HashMap<String, Long>()
        val total = HashMap<String, Long>()
        val e = UsageEvents.Event()
        fun cerrar(p: String, t: Long) = abiertas.remove(p)?.let { inicio -> total[p] = (total[p] ?: 0) + (t - inicio).coerceAtLeast(0) }
        while (eventos.hasNextEvent()) {
            eventos.getNextEvent(e)
            when (e.eventType) {
                // Delante solo hay una app: la que entra cierra a las demás (algunas no avisan al salir).
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    abiertas.keys.filter { it != e.packageName }.forEach { cerrar(it, e.timeStamp) }
                    abiertas.putIfAbsent(e.packageName, e.timeStamp)
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> cerrar(e.packageName, e.timeStamp)
                // Pantalla apagada o móvil apagado: no cuenta.
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.KEYGUARD_SHOWN, UsageEvents.Event.DEVICE_SHUTDOWN ->
                    abiertas.keys.toList().forEach { cerrar(it, e.timeStamp) }
            }
        }
        // Lo que sigue abierto al final: solo si es ahora y la pantalla está encendida.
        val ahora = System.currentTimeMillis()
        val encendida = ctx.getSystemService(android.os.PowerManager::class.java).isInteractive
        if (hasta >= ahora - 60_000 && encendida) for ((p, inicio) in abiertas) total[p] = (total[p] ?: 0) + (hasta - inicio)
        return total
    }

    private val SIN_CONTAR = setOf("com.sios", "com.android.systemui", "com.android.launcher3", "org.lineageos.trebuchet", "android", "com.android.permissioncontroller")

    /** Solo cuentan las apps que se abren desde el cajón (no los servicios ni el asistente de configuración). */
    private fun cuenta(ctx: Context, p: String) = p !in SIN_CONTAR && ctx.packageManager.getLaunchIntentForPackage(p) != null

    /** {dias: [{dia, minutos}], hoy: [{paquete, app, minutos}], primer_plano}. */
    fun json(ctx: Context, dias: Int = 7): JSONObject {
        check(permitido(ctx)) { "Claude no tiene acceso al uso de las apps" }
        val zona = ZoneId.systemDefault()
        val hoy = LocalDate.now(zona)
        val pm = ctx.packageManager
        fun nombre(p: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        val serie = JSONArray()
        var hoyPorApp = emptyMap<String, Long>()
        for (d in dias - 1 downTo 0) {
            val dia = hoy.minusDays(d.toLong())
            val desde = dia.atStartOfDay(zona).toInstant().toEpochMilli()
            val hasta = if (d == 0) System.currentTimeMillis() else dia.plusDays(1).atStartOfDay(zona).toInstant().toEpochMilli()
            val uso = usoEntre(ctx, desde, hasta).filterKeys { cuenta(ctx, it) }
            if (d == 0) hoyPorApp = uso
            serie.put(JSONObject().put("dia", dia.toString()).put("minutos", uso.values.sum() / 60_000))
        }
        val apps = JSONArray()
        hoyPorApp.entries.filter { it.value >= 60_000 }.sortedByDescending { it.value }.take(10).forEach { (p, ms) ->
            apps.put(JSONObject().put("paquete", p).put("app", nombre(p)).put("minutos", ms / 60_000))
        }
        // La semana entera, por app (para el resumen del domingo).
        val desdeSemana = hoy.minusDays(6).atStartOfDay(zona).toInstant().toEpochMilli()
        val semana = JSONArray()
        usoEntre(ctx, desdeSemana, System.currentTimeMillis()).filterKeys { cuenta(ctx, it) }.entries.filter { it.value >= 60_000 }.sortedByDescending { it.value }.take(10).forEach { (p, ms) ->
            semana.put(JSONObject().put("paquete", p).put("app", nombre(p)).put("minutos", ms / 60_000))
        }
        // La app que está delante ahora: la última que pasó a primer plano y no ha salido (con la pantalla encendida).
        val ev = ctx.getSystemService(UsageStatsManager::class.java).queryEvents(System.currentTimeMillis() - 30 * 60_000, System.currentTimeMillis())
        var primerPlano: String? = null
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> primerPlano = e.packageName
                UsageEvents.Event.ACTIVITY_PAUSED -> if (e.packageName == primerPlano) primerPlano = null
            }
        }
        if (!ctx.getSystemService(android.os.PowerManager::class.java).isInteractive) primerPlano = null
        return JSONObject().put("dias", serie).put("hoy", apps).put("semana", semana).put("primer_plano", primerPlano ?: JSONObject.NULL)
    }

    /** Has llegado al límite de una app: pantalla de Claude encima (cerrarla o cinco minutos más). */
    fun limite(ctx: Context, paquete: String, app: String, minutos: Int): JSONObject {
        ctx.startActivity(
            Intent(ctx, LimiteActivity::class.java).putExtra("paquete", paquete).putExtra("app", app).putExtra("minutos", minutos)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        return JSONObject().put("mostrado", true)
    }
}

/** La pantalla del límite: con calma, sin culpa; «Cerrar» lleva al inicio. */
class LimiteActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val paquete = intent.getStringExtra("paquete").orEmpty()
        val app = intent.getStringExtra("app").orEmpty()
        val minutos = intent.getIntExtra("minutos", 0)
        setContent {
            TemaSios {
                Box(Modifier.fillMaxSize().background(Colores.Fondo).navigationBarsPadding().padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(64.dp).clip(Formas.Pastilla).background(Colores.Superficie), contentAlignment = Alignment.Center) {
                            Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(30.dp))
                        }
                        Text(stringResource(R.string.limite_titulo, app), style = Texto.Titulo.copy(fontSize = 24.sp), color = Colores.Texto, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.limite_texto, minutos), style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        BotonPrimario(stringResource(R.string.limite_cerrar), Modifier.fillMaxWidth()) {
                            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        }
                        BotonSecundario(stringResource(R.string.limite_mas), Modifier.fillMaxWidth()) {
                            Thread {
                                runCatching {
                                    val cuerpo = JSONObject().put("paquete", paquete).toString()
                                    val r = okhttp3.Request.Builder().url("http://127.0.0.1:8765/limites/prorroga")
                                        .post(cuerpo.toRequestBody("application/json".toMediaType())).build()
                                    okhttp3.OkHttpClient().newCall(r).execute().close()
                                }
                            }.start()
                            finish()
                        }
                    }
                }
            }
        }
    }
}
