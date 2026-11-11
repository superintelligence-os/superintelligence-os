package com.sios.ajustes

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.datos.Servicio
import com.sios.sistema.Bandeja
import com.sios.sistema.SistemaSios
import com.sios.sistema.Manos
import com.sios.sistema.Preparacion
import com.sios.sistema.SinConexion
import com.sios.teclado.Teclados
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.BotonIcono
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Comprobación de SIOS: repasa todo lo que necesita Claude para funcionar en este móvil y, si
 * algo falla, lo arregla con un toque (o dice dónde se arregla). Pensada para el primer día en un
 * móvil real y para cuando algo no va.
 */
class ComprobacionActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Servicio.iniciar()
        setContent { TemaSios { PantallaComprobacion(onAtras = ::finish) } }
    }
}

/** Una comprobación: si está bien, qué pasa y cómo arreglarlo (null si no tiene arreglo aquí). */
private class Punto(val titulo: Int, val ok: Boolean?, val detalle: String, val opcional: Boolean = false, val arreglar: ((Context) -> Unit)? = null)

private val http by lazy { OkHttpClient.Builder().callTimeout(40, TimeUnit.SECONDS).build() }

private fun agente(): JSONObject? = runCatching {
    http.newCall(Request.Builder().url("http://127.0.0.1:8765/comprobar").build()).execute().use { JSONObject(it.body.string()) }
}.getOrNull()

private fun permiso(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

private fun detallesApp(ctx: Context) = ctx.startActivity(
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
)

private fun comprobar(ctx: Context): List<Punto> {
    val a = agente()
    fun parte(clave: String) = a?.optJSONObject(clave)
    val roles = ctx.getSystemService(RoleManager::class.java)
    fun rol(r: String) = runCatching { roles.isRoleHeld(r) }.getOrDefault(false)
    val ok = ctx.getString(R.string.comp_ok)
    return listOf(
        Punto(R.string.comp_internet, SinConexion.hayInternet(ctx), if (SinConexion.hayInternet(ctx)) ok else ctx.getString(R.string.comp_internet_no)) {
            it.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        Punto(R.string.comp_agente, a != null, if (a != null) ok else ctx.getString(R.string.comp_agente_no)) { Preparacion.arrancarServicio(it) },
        Punto(R.string.comp_cuenta, a?.optJSONObject("cuenta")?.optBoolean("conectada"), if (a?.optJSONObject("cuenta")?.optBoolean("conectada") == true) ok else ctx.getString(R.string.comp_cuenta_no)) {
            Servicio.entrar()
        },
        Punto(R.string.comp_ia_code, parte("claude")?.optBoolean("ok"), parte("claude")?.optString("detalle").orEmpty().ifBlank { ctx.getString(R.string.comp_sin_dato) }) { Preparacion.lanzar(it) },
        Punto(R.string.comp_adb, parte("adb")?.optBoolean("ok"), if (parte("adb")?.optBoolean("ok") == true) ok else ctx.getString(R.string.comp_adb_no)) {
            SistemaSios.activarDepuracion(it); Preparacion.arrancarServicio(it)
        },
        Punto(R.string.comp_energia, parte("energia")?.optBoolean("ok"), parte("energia")?.optString("detalle").orEmpty().ifBlank { ctx.getString(R.string.comp_sin_dato) }) {
            it.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        Punto(R.string.comp_espacio, parte("espacio")?.optBoolean("ok"), ctx.getString(R.string.comp_espacio_libre, parte("espacio")?.optString("detalle").orEmpty())),
        Punto(R.string.comp_manos, Manos.instancia != null, if (Manos.instancia != null) ok else ctx.getString(R.string.comp_manos_no)) {
            Manos.permitir(it, true)
        },
        Punto(R.string.comp_avisos, Bandeja.instancia != null, if (Bandeja.instancia != null) ok else ctx.getString(R.string.comp_avisos_no)) {
            it.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        Punto(R.string.comp_asistente, SistemaSios.esAsistente(ctx), if (SistemaSios.esAsistente(ctx)) ok else ctx.getString(R.string.comp_rol_no)) {
            it.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        Punto(R.string.comp_telefono, rol(RoleManager.ROLE_DIALER), if (rol(RoleManager.ROLE_DIALER)) ok else ctx.getString(R.string.comp_rol_no), opcional = true) {
            it.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        Punto(R.string.comp_filtro, rol(RoleManager.ROLE_CALL_SCREENING), if (rol(RoleManager.ROLE_CALL_SCREENING)) ok else ctx.getString(R.string.comp_rol_no), opcional = true) {
            it.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        Punto(R.string.comp_oir_llamadas, permiso(ctx, Manifest.permission.CAPTURE_AUDIO_OUTPUT),
            if (permiso(ctx, Manifest.permission.CAPTURE_AUDIO_OUTPUT)) ok else ctx.getString(R.string.comp_oir_llamadas_no), opcional = true),
        Punto(R.string.teclado_sios_nombre, Teclados.enUso(ctx), if (Teclados.enUso(ctx)) ok else ctx.getString(R.string.comp_teclado_no), opcional = true) { Teclados.usar(it) },
        Punto(R.string.comp_micro, permiso(ctx, Manifest.permission.RECORD_AUDIO), if (permiso(ctx, Manifest.permission.RECORD_AUDIO)) ok else ctx.getString(R.string.comp_permiso_no)) { detallesApp(it) },
        Punto(R.string.comp_camara, permiso(ctx, Manifest.permission.CAMERA), if (permiso(ctx, Manifest.permission.CAMERA)) ok else ctx.getString(R.string.comp_permiso_no), opcional = true) { detallesApp(it) },
        Punto(R.string.comp_contactos, permiso(ctx, Manifest.permission.READ_CONTACTS), if (permiso(ctx, Manifest.permission.READ_CONTACTS)) ok else ctx.getString(R.string.comp_permiso_no), opcional = true) { detallesApp(it) },
        Punto(R.string.comp_ubicacion, permiso(ctx, Manifest.permission.ACCESS_FINE_LOCATION), if (permiso(ctx, Manifest.permission.ACCESS_FINE_LOCATION)) ok else ctx.getString(R.string.comp_ubicacion_no), opcional = true) { detallesApp(it) },
        Punto(R.string.comp_actualizaciones, if (a?.optString("canal").isNullOrBlank()) null else true,
            a?.optString("canal")?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.comp_sin_canal), opcional = true),
    )
}

@Composable
private fun PantallaComprobacion(onAtras: () -> Unit) {
    val ctx = LocalContext.current
    var puntos by remember { mutableStateOf<List<Punto>?>(null) }
    var vuelta by remember { mutableIntStateOf(0) }
    LaunchedEffect(vuelta) { puntos = null; puntos = withContext(Dispatchers.IO) { comprobar(ctx) } }
    val fallos = puntos?.count { it.ok == false && !it.opcional } ?: 0

    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BotonIcono(Iconos.Atras, stringResource(R.string.comun_atras), onClick = onAtras)
            Spacer(Modifier.width(8.dp))
            Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.comp_titulo), style = Texto.Titulo, color = Colores.Texto)
        }
        Text(
            when {
                puntos == null -> stringResource(R.string.comp_mirando)
                fallos == 0 -> stringResource(R.string.comp_todo_bien)
                else -> androidx.compose.ui.res.pluralStringResource(R.plurals.comp_fallos, fallos, fallos)
            },
            style = Texto.Cuerpo, color = if (fallos > 0) Colores.Texto else Colores.Texto2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(puntos.orEmpty()) { p -> FilaPunto(p) { p.arreglar?.invoke(ctx); vuelta++ } }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BotonSecundario(stringResource(R.string.comp_copiar), Modifier.weight(1f)) {
                val informe = puntos.orEmpty().joinToString("\n") { "${if (it.ok == true) "✓" else if (it.ok == false) "✗" else "·"} ${ctx.getString(it.titulo)}: ${it.detalle}" }
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("SIOS", informe))
                android.widget.Toast.makeText(ctx, R.string.comp_copiado, android.widget.Toast.LENGTH_SHORT).show()
            }
            BotonPrimario(stringResource(R.string.comp_repetir), Modifier.weight(1f)) { vuelta++ }
        }
    }
}

@Composable
private fun FilaPunto(p: Punto, onArreglar: () -> Unit) {
    val color = when (p.ok) { true -> Color(0xFF3FA36B); false -> if (p.opcional) Colores.Texto3 else Colores.Rojo; null -> Colores.Texto3 }
    Row(
        Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).animateContentSize().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(Formas.Pastilla).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(if (p.ok == true) Iconos.Hecho else if (p.ok == false) Iconos.Cruz else Iconos.Menos, null, tint = color, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(p.titulo), style = Texto.Cuerpo, color = Colores.Texto)
            Text(p.detalle, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 2)
        }
        if (p.ok == false && p.arreglar != null) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.height(34.dp).clip(Formas.Pastilla).background(Colores.Superficie2).border(1.dp, Colores.Borde, Formas.Pastilla).pulsable(onArreglar).padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.comp_arreglar), style = Texto.Pequeno, color = Colores.Texto) }
        }
    }
}
