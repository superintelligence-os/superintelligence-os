package com.sios.telefono

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.ui.BotonIcono
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** El teléfono de SIOS: marcar, recientes y contactos. Abre también los enlaces «tel:». */
class TelefonoActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val inicial = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
        setContent { TemaSios { PantallaTelefono(inicial) } }
    }
}

private enum class Pestana(val texto: Int) { Teclado(R.string.tel_pestana_teclado), Recientes(R.string.tel_pestana_recientes), Contactos(R.string.tel_pestana_contactos) }

fun llamar(ctx: Context, numero: String) {
    if (numero.isBlank()) return
    // A emergencias se llama siempre: sin SIM, sin permiso de llamadas o con el móvil bloqueado
    // (y, si algo fallara, con el teclado de emergencia del sistema con el número puesto).
    if (com.sios.emergencias.Emergencias.esEmergencia(ctx, numero)) {
        com.sios.emergencias.Emergencias.marcarLlamadaEmergencia(ctx)
        return com.sios.emergencias.Emergencias.llamar(ctx, numero, prueba = false)
    }
    val tm = ctx.getSystemService(TelecomManager::class.java)
    runCatching { tm.placeCall(Uri.fromParts("tel", numero, null), Bundle()) }
        .onFailure { ctx.startActivity(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", numero, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
private fun PantallaTelefono(inicial: String) {
    val ctx = LocalContext.current
    var pestana by remember { mutableStateOf(if (inicial.isNotEmpty()) Pestana.Teclado else Pestana.Recientes) }
    var numero by remember { mutableStateOf(inicial) }
    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val faltan = listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
            .filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (faltan.isNotEmpty()) permisos.launch(faltan.toTypedArray())
    }

    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Iconos.Llamar, null, tint = Colores.Acento, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.tel_titulo), style = Texto.Titulo, color = Colores.Texto, modifier = Modifier.weight(1f))
            // Emergencias siempre a mano: SOS, el 112 (o el del país), la ficha médica y los contactos.
            com.sios.emergencias.PastillaEmergencias()
        }
        Box(Modifier.weight(1f)) {
            AnimatedContent(pestana, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "pestana") { p ->
                when (p) {
                    Pestana.Teclado -> Marcador(numero, { numero = it }) { llamar(ctx, numero) }
                    Pestana.Recientes -> Recientes { llamar(ctx, it) }
                    Pestana.Contactos -> Contactos { llamar(ctx, it) }
                }
            }
        }
        // Tres pestañas del mismo ancho.
        Row(Modifier.padding(horizontal = 20.dp, vertical = 10.dp).fillMaxWidth().clip(Formas.Pastilla).background(Colores.Superficie).padding(4.dp)) {
            for (p in Pestana.entries) {
                val activa = p == pestana
                Box(
                    Modifier.weight(1f).height(40.dp).clip(Formas.Pastilla).background(if (activa) Colores.Superficie2 else Colores.Superficie).pulsable { pestana = p },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(p.texto), style = Texto.Pequeno, color = if (activa) Colores.Texto else Colores.Texto3) }
            }
        }
    }
}

@Composable
private fun Marcador(numero: String, onNumero: (String) -> Unit, onLlamar: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(44.dp))
            BasicTextField(
                numero, { onNumero(it.filter { c -> c.isDigit() || c in "+*# " }) },
                textStyle = Texto.Titulo.copy(fontSize = 34.sp, color = Colores.Texto, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Colores.Acento), singleLine = true, modifier = Modifier.weight(1f),
            )
            BotonIcono(Iconos.Borrar, stringResource(R.string.tel_borrar), tam = 44.dp, color = if (numero.isEmpty()) Color.Transparent else Colores.Texto2) {
                if (numero.isNotEmpty()) onNumero(numero.dropLast(1))
            }
        }
        Spacer(Modifier.height(24.dp))
        Teclado { onNumero(numero + it) }
        Spacer(Modifier.height(20.dp))
        Box(
            Modifier.size(72.dp).clip(Formas.Pastilla).background(if (numero.isBlank()) Colores.Superficie2 else Color(0xFF3FA36B)).pulsable(onLlamar),
            contentAlignment = Alignment.Center,
        ) { Icon(Iconos.Llamar, stringResource(R.string.tel_llamar), tint = Colores.SobreAcento, modifier = Modifier.size(30.dp)) }
    }
}

private class Reciente(val numero: String, val nombre: String?, val tipo: Int, val fecha: Long, val veces: Int = 1) {
    val perdida get() = tipo == CallLog.Calls.MISSED_TYPE || tipo == CallLog.Calls.REJECTED_TYPE || tipo == CallLog.Calls.BLOCKED_TYPE
    val saliente get() = tipo == CallLog.Calls.OUTGOING_TYPE
}

/** Llamadas seguidas del mismo número y del mismo tipo, en una sola fila con «(3)», como en un buen marcador. */
private fun agrupar(lista: List<Reciente>): List<Reciente> = buildList {
    for (r in lista) {
        val ultimo = lastOrNull()
        val mismoNumero = ultimo != null && ultimo.numero.filter(Char::isDigit).takeLast(9) == r.numero.filter(Char::isDigit).takeLast(9)
        if (ultimo != null && mismoNumero && ultimo.perdida == r.perdida && ultimo.saliente == r.saliente &&
            DateUtils.isToday(ultimo.fecha) == DateUtils.isToday(r.fecha)
        ) {
            set(lastIndex, Reciente(ultimo.numero, ultimo.nombre ?: r.nombre, ultimo.tipo, ultimo.fecha, ultimo.veces + 1))
        } else add(r)
    }
}

private enum class Dia(val texto: Int) { Hoy(R.string.tel_hoy), Ayer(R.string.tel_ayer), Antes(R.string.tel_antes) }

private fun dia(fecha: Long): Dia = when {
    DateUtils.isToday(fecha) -> Dia.Hoy
    DateUtils.isToday(fecha + DateUtils.DAY_IN_MILLIS) -> Dia.Ayer
    else -> Dia.Antes
}

@Composable
private fun Recientes(onLlamar: (String) -> Unit) {
    val ctx = LocalContext.current
    var lista by remember { mutableStateOf<List<Reciente>?>(null) }
    LaunchedEffect(Unit) {
        lista = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.query(
                    CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                    null, null, "${CallLog.Calls.DATE} DESC LIMIT 200",
                )?.use { c -> buildList { while (c.moveToNext()) add(Reciente(c.getString(0).orEmpty(), c.getString(1), c.getInt(2), c.getLong(3))) } }.orEmpty()
            }.getOrDefault(emptyList()).let(::agrupar)
        }
    }
    val l = lista
    when {
        l == null -> Box(Modifier.fillMaxSize())
        l.isEmpty() -> Vacio(R.string.tel_sin_recientes)
        else -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            l.groupBy { dia(it.fecha) }.forEach { (d, delDia) ->
                item(key = d.name) {
                    Text(
                        stringResource(d.texto), style = Texto.Pequeno, color = Colores.Texto3,
                        modifier = Modifier.padding(start = 4.dp, top = if (d == dia(l.first().fecha)) 0.dp else 10.dp, bottom = 2.dp),
                    )
                }
                items(delDia) { r ->
                    val hora = if (d == Dia.Antes) DateUtils.formatDateTime(ctx, r.fecha, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)
                    else DateUtils.formatDateTime(ctx, r.fecha, DateUtils.FORMAT_SHOW_TIME)
                    Fila(
                        titulo = (r.nombre?.takeIf { it.isNotBlank() } ?: com.sios.sistema.Llamadas.formatear(r.numero)) +
                            if (r.veces > 1) "  (${r.veces})" else "",
                        nota = stringResource(when (r.tipo) {
                            CallLog.Calls.OUTGOING_TYPE -> R.string.tel_saliente
                            CallLog.Calls.MISSED_TYPE -> R.string.tel_perdida
                            CallLog.Calls.REJECTED_TYPE, CallLog.Calls.BLOCKED_TYPE -> R.string.tel_rechazada
                            else -> R.string.tel_recibida
                        }) + " · " + hora,
                        rojo = r.perdida,
                        direccion = if (r.saliente) Iconos.Saliente else Iconos.Entrante,
                    ) { onLlamar(r.numero) }
                }
            }
        }
    }
}

private class Contacto(val nombre: String, val numero: String)

@Composable
private fun Contactos(onLlamar: (String) -> Unit) {
    val ctx = LocalContext.current
    var todos by remember { mutableStateOf<List<Contacto>?>(null) }
    var buscar by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        todos = withContext(Dispatchers.IO) {
            runCatching {
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
                )?.use { c -> buildList { while (c.moveToNext()) add(Contacto(c.getString(0).orEmpty(), c.getString(1).orEmpty())) } }.orEmpty()
            }.getOrDefault(emptyList()).distinctBy { it.nombre to it.numero.filter(Char::isDigit) }
        }
    }
    fun sin(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
    Column {
        BasicTextField(
            buscar, { buscar = it }, singleLine = true,
            textStyle = Texto.Cuerpo.copy(color = Colores.Texto), cursorBrush = SolidColor(Colores.Acento),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).fillMaxWidth(),
            decorationBox = { campo ->
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(Formas.Pastilla).background(Colores.Superficie).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Iconos.Buscar, null, tint = Colores.Texto3, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Box { if (buscar.isEmpty()) Text(stringResource(R.string.tel_buscar_contacto), style = Texto.Cuerpo, color = Colores.Texto3); campo() }
                }
            },
        )
        val filtrados = todos?.filter { buscar.isBlank() || sin(it.nombre).contains(sin(buscar)) || it.numero.filter(Char::isDigit).contains(buscar.filter(Char::isDigit).ifEmpty { "x" }) }
        Lista(filtrados, vacio = R.string.tel_sin_contactos) { c -> Fila(c.nombre, com.sios.sistema.Llamadas.formatear(c.numero), false) { onLlamar(c.numero) } }
    }
}

@Composable
private fun <T> Lista(lista: List<T>?, vacio: Int, fila: @Composable (T) -> Unit) {
    when {
        lista == null -> Box(Modifier.fillMaxSize())
        lista.isEmpty() -> Vacio(vacio)
        else -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(lista) { fila(it) }
        }
    }
}

@Composable
private fun Vacio(texto: Int) {
    Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(texto), style = Texto.Cuerpo, color = Colores.Texto3, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Fila(titulo: String, nota: String, rojo: Boolean, direccion: androidx.compose.ui.graphics.vector.ImageVector? = null, onLlamar: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).pulsable(onLlamar).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(Formas.Pastilla).background(Colores.Superficie2), contentAlignment = Alignment.Center) {
            val inicial = titulo.firstOrNull()?.takeIf { it.isLetter() }
            if (inicial != null) Text(inicial.uppercase(), style = Texto.Subtitulo, color = Colores.Texto)
            else Icon(Iconos.Llamar, null, tint = Colores.Texto2, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, style = Texto.Cuerpo, color = if (rojo) Colores.Rojo else Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (direccion != null) {
                    Icon(direccion, null, tint = if (rojo) Colores.Rojo else Colores.Texto3, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(nota, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1)
            }
        }
        Icon(Iconos.Llamar, null, tint = Colores.Texto2, modifier = Modifier.size(20.dp))
    }
}
