package com.sios.inicio

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.ajustes.AjustesActivity
import com.sios.sistema.AsistenteActivity
import com.sios.sistema.Memoria
import com.sios.sistema.Shell
import com.sios.ui.AvisoWidget
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.Normalizer

/**
 * El buscador universal (deslizar hacia abajo en el inicio): apps, contactos, ajustes, acciones
 * rápidas («alarma a las 7», «llamar a Laura», «modo avión»), cuentas, lo que hay en el móvil
 * (mensajes, avisos, fotos y archivos de la memoria local) y, siempre, preguntárselo a Claude.
 * Todo menos Claude se resuelve en el móvil, al instante y sin gastar nada.
 */
@Composable
internal fun Buscador(abierto: Boolean, apps: List<App>, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    var consulta by remember { mutableStateOf("") }
    val foco = remember { FocusRequester() }
    val focoGeneral = LocalFocusManager.current
    LaunchedEffect(abierto) {
        if (abierto) { delay(120); runCatching { foco.requestFocus() } } else { consulta = ""; focoGeneral.clearFocus() }
    }
    var resultados by remember { mutableStateOf(Resultados()) }
    LaunchedEffect(consulta, apps) {
        delay(90) // al escribir rápido, solo la última
        resultados = withContext(Dispatchers.Default) { buscar(ctx, consulta, apps) }
    }
    val cerrarY = { accion: () -> Unit -> accion(); onCerrar() }

    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding().imePadding()) {
        // Campo de búsqueda
        Row(
            Modifier.padding(16.dp).fillMaxWidth().height(52.dp).clip(Formas.Pastilla).background(Colores.Superficie)
                .border(1.dp, Colores.Borde, Formas.Pastilla).padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Iconos.Buscar, null, tint = Colores.Texto3, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                consulta, { consulta = it }, singleLine = true,
                textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
                cursorBrush = SolidColor(Colores.Acento),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    // Intro: lo primero que haya (una acción, una app que coincide del todo) o Claude.
                    val r = resultados
                    when {
                        r.acciones.isNotEmpty() -> cerrarY { r.acciones.first().hacer(ctx) }
                        r.apps.size == 1 -> cerrarY { abrirApp(ctx, r.apps.first()) }
                        consulta.isNotBlank() -> cerrarY { preguntarALaIa(ctx, consulta) }
                    }
                }),
                modifier = Modifier.weight(1f).focusRequester(foco),
                decorationBox = { campo ->
                    Box {
                        if (consulta.isEmpty()) Text(stringResource(R.string.buscador_pista), style = Texto.Cuerpo, color = Colores.Texto3, maxLines = 1)
                        campo()
                    }
                },
            )
            if (consulta.isNotEmpty()) Box(Modifier.size(40.dp).clip(Formas.Pastilla).pulsable { consulta = "" }, contentAlignment = Alignment.Center) {
                Icon(Iconos.Cruz, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
            }
        }

        val r = resultados
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            if (consulta.isBlank()) {
                item { Seccion(stringResource(R.string.buscador_prueba)) }
                items(listOf(R.string.buscador_ej_alarma, R.string.buscador_ej_llamar, R.string.buscador_ej_avion, R.string.buscador_ej_fotos)) { id ->
                    val t = stringResource(id)
                    Fila(Iconos.Buscar, t, null) { consulta = t }
                }
                return@LazyColumn
            }
            // Cuenta
            r.cuenta?.let { item { Respuesta(consulta, it) } }
            // Lo que se ha entendido como acción va primero (es lo que se quiere casi siempre); luego, Claude.
            if (r.acciones.isNotEmpty()) {
                item { Seccion(stringResource(R.string.buscador_acciones)) }
                items(r.acciones) { a -> Fila(a.icono, a.titulo, a.nota) { cerrarY { a.hacer(ctx) } } }
                item { Spacer(Modifier.height(6.dp)) }
            }
            item { Fila(Iconos.Asistente, stringResource(R.string.buscador_ia, consulta.trim()), null, acento = true) { cerrarY { preguntarALaIa(ctx, consulta) } } }
            if (r.apps.isNotEmpty()) {
                item { Seccion(stringResource(R.string.buscador_apps)) }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        r.apps.take(4).forEach { app ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                IconoApp(app, tam = 54, conNombre = true, onClick = { cerrarY { abrirApp(ctx, app) } })
                            }
                        }
                        repeat(4 - r.apps.take(4).size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (r.contactos.isNotEmpty()) {
                item { Seccion(stringResource(R.string.buscador_contactos)) }
                items(r.contactos) { c ->
                    Fila(Iconos.Llamar, c.nombre, com.sios.sistema.Llamadas.formatear(c.numero)) { cerrarY { com.sios.telefono.llamar(ctx, c.numero) } }
                }
            }
            if (r.ajustes.isNotEmpty()) {
                item { Seccion(stringResource(R.string.buscador_ajustes)) }
                items(r.ajustes) { a -> Fila(Iconos.Ajustes, stringResource(a.nombre), null) { cerrarY { abrirAjuste(ctx, a) } } }
            }
            if (r.memoria.isNotEmpty()) {
                item { Seccion(stringResource(R.string.buscador_movil)) }
                items(r.memoria) { m ->
                    Fila(
                        when (m.tipo) { "foto", "captura" -> Iconos.Camara; "documento" -> Iconos.Documento; else -> Iconos.Enviar },
                        m.titulo, m.fragmento,
                    ) { cerrarY { runCatching { Memoria.abrir(ctx, m.id) }.onFailure { preguntarALaIa(ctx, consulta) } } }
                }
            }
        }
    }
}

// ---------- Resultados ----------

private data class Resultados(
    val cuenta: String? = null,
    val acciones: List<AccionRapida> = emptyList(),
    val apps: List<App> = emptyList(),
    val contactos: List<ContactoBusqueda> = emptyList(),
    val ajustes: List<Ajuste> = emptyList(),
    val memoria: List<EnMemoria> = emptyList(),
)

private class AccionRapida(val icono: ImageVector, val titulo: String, val nota: String?, val hacer: (Context) -> Unit)
private data class ContactoBusqueda(val nombre: String, val numero: String)
private data class EnMemoria(val id: Long, val tipo: String, val titulo: String, val fragmento: String?)
private class Ajuste(val nombre: Int, val claves: String, val accion: String? = null, val clase: Class<*>? = null, val seccion: String? = null)

/** Ajustes del sistema que se pueden buscar (con sus sinónimos), y a dónde llevan. */
private val AJUSTES = listOf(
    Ajuste(R.string.aj_wifi, "wifi wi-fi red internet inalambrica", Settings.ACTION_WIFI_SETTINGS),
    Ajuste(R.string.aj_bluetooth, "bluetooth cascos auriculares emparejar", Settings.ACTION_BLUETOOTH_SETTINGS),
    Ajuste(R.string.aj_datos, "datos moviles sim operador roaming 4g 5g", Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
    Ajuste(R.string.aj_avion, "modo avion avion vuelo", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
    Ajuste(R.string.aj_pantalla, "pantalla brillo tema oscuro claro letra tamaño", Settings.ACTION_DISPLAY_SETTINGS),
    Ajuste(R.string.aj_sonido, "sonido volumen tono vibracion silencio", Settings.ACTION_SOUND_SETTINGS),
    Ajuste(R.string.aj_bateria, "bateria ahorro energia carga", Intent.ACTION_POWER_USAGE_SUMMARY),
    Ajuste(R.string.aj_ubicacion, "ubicacion gps localizacion", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
    Ajuste(R.string.aj_notificaciones, "notificaciones avisos", "android.settings.ALL_APPS_NOTIFICATION_SETTINGS"),
    Ajuste(R.string.aj_no_molestar, "no molestar silencio modos concentracion", Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
    Ajuste(R.string.aj_apps, "aplicaciones apps desinstalar permisos", Settings.ACTION_APPLICATION_SETTINGS),
    Ajuste(R.string.aj_almacenamiento, "almacenamiento espacio memoria", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
    Ajuste(R.string.aj_seguridad, "seguridad bloqueo pin huella contraseña", Settings.ACTION_SECURITY_SETTINGS),
    Ajuste(R.string.aj_privacidad, "privacidad permisos", Settings.ACTION_PRIVACY_SETTINGS),
    Ajuste(R.string.aj_fecha, "fecha hora zona horaria", Settings.ACTION_DATE_SETTINGS),
    Ajuste(R.string.aj_idioma, "idioma lengua", Settings.ACTION_LOCALE_SETTINGS),
    Ajuste(R.string.aj_teclado, "teclado escribir", Settings.ACTION_INPUT_METHOD_SETTINGS),
    Ajuste(R.string.aj_accesibilidad, "accesibilidad lector letra grande", Settings.ACTION_ACCESSIBILITY_SETTINGS),
    Ajuste(R.string.aj_nfc, "nfc pagar contactless", Settings.ACTION_NFC_SETTINGS),
    Ajuste(R.string.aj_info, "informacion telefono version acerca modelo", Settings.ACTION_DEVICE_INFO_SETTINGS),
    Ajuste(R.string.aj_fondo, "fondo de pantalla wallpaper estilo", Intent.ACTION_SET_WALLPAPER),
    Ajuste(R.string.aj_ia, "claude ajustes de claude modelo permisos copia", clase = AjustesActivity::class.java),
    Ajuste(R.string.tienda_g_predeterminadas, "predeterminadas predeterminada navegador correo email mapas mensajes sms tienda f-droid aurora default browser", clase = AjustesActivity::class.java, seccion = "apps"),
    Ajuste(R.string.modoesc_titulo, "modo escritorio monitor pantalla externa hdmi displayport ordenador desktop", clase = AjustesActivity::class.java, seccion = "escritorio"),
)

private fun sin(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").trim()

private fun buscar(ctx: Context, consulta: String, apps: List<App>): Resultados {
    val q = sin(consulta)
    if (q.isEmpty()) return Resultados()
    val palabras = q.split(Regex("\\s+")).filter { it.isNotBlank() }
    val contactos = contactos(ctx, q)
    return Resultados(
        cuenta = calcular(consulta),
        acciones = acciones(ctx, q, consulta, contactos),
        apps = apps.filter { a -> sin(a.nombre).let { n -> n.startsWith(q) || palabras.all { n.contains(it) } } }
            .sortedBy { if (sin(it.nombre).startsWith(q)) 0 else 1 }.take(8),
        contactos = contactos.take(4),
        ajustes = AJUSTES.filter { a -> palabras.all { p -> a.claves.split(" ").any { it.startsWith(p) } } || sin(ctx.getString(a.nombre)).contains(q) }.take(4),
        memoria = if (utiles(q).length >= 3) runCatching {
            val r = Memoria.buscar(ctx, utiles(q), 6)
            (0 until r.length()).map { i -> r.getJSONObject(i).let { EnMemoria(it.getLong("id"), it.optString("tipo"), it.optString("titulo"), it.optString("fragmento").takeIf { f -> f.isNotBlank() }) } }
        }.getOrDefault(emptyList()) else emptyList(),
    )
}

/** Sin las palabras que no dicen nada («fotos de la playa» → «fotos playa»): si no, «de» lo encuentra todo. */
private val VACIAS = setOf(
    "de", "del", "la", "las", "el", "los", "lo", "un", "una", "unos", "unas", "en", "a", "al", "y", "o", "u", "e", "con", "por", "para", "que",
    "mi", "mis", "tu", "tus", "su", "sus", "me", "se", "es", "the", "of", "to", "and", "in", "on", "my", "le", "les", "des", "du", "et", "der", "die", "das", "und", "il", "di", "da", "do", "dos",
)
private fun utiles(q: String) = q.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 && it !in VACIAS }.joinToString(" ")

private fun contactos(ctx: Context, q: String): List<ContactoBusqueda> = runCatching {
    val digitos = q.filter(Char::isDigit)
    ctx.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE LOCALIZED ASC",
    )?.use { c ->
        buildList {
            while (c.moveToNext()) {
                val nombre = c.getString(0).orEmpty()
                val numero = c.getString(1).orEmpty()
                val n = sin(nombre)
                if (n.split(" ").any { it.startsWith(q.removePrefix("llamar a ").removePrefix("llamar ")) } || n.contains(q) || (digitos.length >= 3 && numero.filter(Char::isDigit).contains(digitos))) add(ContactoBusqueda(nombre, numero))
            }
        }.distinctBy { it.nombre to it.numero.filter(Char::isDigit) }
    }.orEmpty()
}.getOrDefault(emptyList())

/** «2+2*3», «15% de 80», «1.234,5 / 3». Nada si no es una cuenta. */
private fun calcular(texto: String): String? {
    val t = texto.trim().replace("×", "*").replace("÷", "/").replace(Regex("(\\d)\\s*%\\s*de\\s*(\\d)"), "$1/100*$2")
    if (!Regex("^[\\d\\s+\\-*/().,%]+$").matches(t) || !Regex("\\d\\s*[+\\-*/%]\\s*\\(?\\d").containsMatchIn(t)) return null
    val normal = t.replace(".", "").replace(",", ".").replace(Regex("(\\d+(?:\\.\\d+)?)%"), "($1/100)")
    return runCatching { Expresion(normal).valor() }.getOrNull()?.takeIf { it.isFinite() }?.let { v ->
        if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) "%,.0f".format(java.util.Locale("es", "ES"), v)
        else "%,.6f".format(java.util.Locale("es", "ES"), v).trimEnd('0').trimEnd(',')
    }
}

/** Analizador mínimo de expresiones (+ − × ÷ y paréntesis). */
private class Expresion(private val s: String) {
    private var i = 0
    fun valor(): Double { val v = suma(); check(i >= s.length); return v }
    private fun saltar() { while (i < s.length && s[i] == ' ') i++ }
    private fun suma(): Double {
        var v = producto()
        while (true) { saltar(); when { come('+') -> v += producto(); come('-') -> v -= producto(); else -> return v } }
    }
    private fun producto(): Double {
        var v = factor()
        while (true) { saltar(); when { come('*') -> v *= factor(); come('/') -> v /= factor(); else -> return v } }
    }
    private fun factor(): Double {
        saltar()
        if (come('-')) return -factor()
        if (come('(')) { val v = suma(); saltar(); check(come(')')); return v }
        val inicio = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        return s.substring(inicio, i).toDouble()
    }
    private fun come(c: Char): Boolean { saltar(); return if (i < s.length && s[i] == c) { i++; true } else false }
}

/** Acciones que se hacen al momento, sin Claude. */
private fun acciones(ctx: Context, q: String, original: String, contactos: List<ContactoBusqueda>): List<AccionRapida> {
    val r = mutableListOf<AccionRapida>()
    // Alarma: «alarma a las 7», «despiértame a las 7:30», «alarma 6.45»
    Regex("(alarma|despiertame|despertador).*?(\\d{1,2})(?:[:.h](\\d{2}))?").find(q)?.let { m ->
        val h = m.groupValues[2].toInt(); val min = m.groupValues[3].toIntOrNull() ?: 0
        if (h in 0..23 && min in 0..59) r += AccionRapida(Iconos.Reloj, ctx.getString(R.string.buscador_alarma, "%d:%02d".format(h, min)), null) { c ->
            c.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, min).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            AvisoWidget.mostrar(c.getString(R.string.buscador_alarma_hecha, "%d:%02d".format(h, min)))
        }
    }
    // Temporizador: «temporizador 10 min», «cuenta atras de 30 segundos»
    Regex("(temporizador|cuenta atras|timer).*?(\\d+)\\s*(h|hora|horas|min|minuto|minutos|s|seg|segundo|segundos)?").find(q)?.let { m ->
        val n = m.groupValues[2].toInt()
        val segundos = when { m.groupValues[3].startsWith("h") -> n * 3600; m.groupValues[3].startsWith("s") -> n; else -> n * 60 }
        r += AccionRapida(Iconos.Reloj, ctx.getString(R.string.buscador_temporizador, if (segundos >= 60) "${segundos / 60} min" else "$segundos s"), null) { c ->
            c.startActivity(Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, segundos).putExtra(AlarmClock.EXTRA_SKIP_UI, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    // Llamar / escribir a un contacto
    if (Regex("^(llama|llamar|llamada)").containsMatchIn(q)) contactos.firstOrNull()?.let { c ->
        r += AccionRapida(Iconos.Llamar, ctx.getString(R.string.buscador_llamar, c.nombre), c.numero) { x -> com.sios.telefono.llamar(x, c.numero) }
    }
    if (Regex("^(escribe|escribir|mensaje|sms)").containsMatchIn(q)) contactos.firstOrNull()?.let { c ->
        r += AccionRapida(Iconos.Enviar, ctx.getString(R.string.buscador_escribir, c.nombre), c.numero) { x ->
            x.startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:${c.numero}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    // Interruptores
    fun interruptor(palabras: String, nombre: Int, encender: String, apagar: String) {
        if (!Regex("\\b($palabras)\\b").containsMatchIn(q)) return
        val off = Regex("\\b(apaga|apagar|quita|quitar|desactiva|desactivar|off)\\b").containsMatchIn(q)
        r += AccionRapida(Iconos.Ajustes, ctx.getString(if (off) R.string.buscador_apagar else R.string.buscador_encender, ctx.getString(nombre)), null) { _ ->
            Thread { Shell.ejecutar(if (off) apagar else encender) }.start()
        }
    }
    interruptor("wifi|wi-fi", R.string.aj_wifi, "svc wifi enable", "svc wifi disable")
    interruptor("bluetooth", R.string.aj_bluetooth, "svc bluetooth enable", "svc bluetooth disable")
    interruptor("avion|modo avion", R.string.aj_avion, "cmd connectivity airplane-mode enable", "cmd connectivity airplane-mode disable")
    interruptor("datos", R.string.aj_datos, "svc data enable", "svc data disable")
    // Linterna
    // «linterna», «flash» o «enciende la luz» (no cualquier «luz»: «factura de la luz» no es la linterna).
    if (Regex("\\b(linterna|flash)\\b").containsMatchIn(q) || Regex("\\b(enciende|encender|apaga|apagar)\\s+(la\\s+)?luz\\b").containsMatchIn(q)) {
        val off = Regex("\\b(apaga|apagar|off)\\b").containsMatchIn(q)
        r += AccionRapida(Iconos.Bombilla, ctx.getString(if (off) R.string.buscador_linterna_off else R.string.buscador_linterna_on), null) { c ->
            runCatching {
                val cm = c.getSystemService(CameraManager::class.java)
                val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                if (id != null) cm.setTorchMode(id, !off)
            }
        }
    }
    // Traductor, buenas noches y leer pantalla
    if (Regex("\\b(traductor|traducir conversacion|interprete|traduceme)\\b").containsMatchIn(q)) {
        r += AccionRapida(Iconos.Mundo, ctx.getString(R.string.trad_titulo), null) { c ->
            c.startActivity(android.content.Intent(c, com.sios.sistema.TraductorActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    if (Regex("\\b(buenas noches|a dormir|modo noche|me voy a dormir)\\b").containsMatchIn(q)) {
        r += AccionRapida(Iconos.Reloj, ctx.getString(R.string.noche_ahora), null) { _ ->
            com.sios.datos.Servicio.alcanceGlobal { com.sios.datos.Servicio.buenasNoches(org.json.JSONObject().put("ahora", true)) }
        }
    }
    if (Regex("\\b(lee(me)? la pantalla|leer pantalla|leer en voz alta)\\b").containsMatchIn(q)) {
        r += AccionRapida(Iconos.Altavoz, ctx.getString(R.string.leer_titulo), null) { c ->
            val t = com.sios.sistema.Manos.instancia?.textoVisible().orEmpty()
            if (t.isNotBlank()) com.sios.sistema.Voz.decir(c, t)
        }
    }
    // Modo coche
    if (Regex("\\b(modo coche|conducir|conduccion|voy a conducir)\\b").containsMatchIn(q)) {
        r += AccionRapida(Iconos.Coche, ctx.getString(R.string.coche_abrir), null) { c -> com.sios.sistema.Coche.abrir(c) }
    }
    return r
}

private fun abrirAjuste(ctx: Context, a: Ajuste) {
    val i = if (a.clase != null) Intent(ctx, a.clase) else Intent(a.accion)
    a.seccion?.let { i.putExtra("seccion", it) }
    runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Le pregunta a Claude en la hoja rápida. */
internal fun preguntarALaIa(ctx: Context, texto: String) {
    if (texto.isBlank()) return
    ctx.startActivity(Intent(ctx, AsistenteActivity::class.java).putExtra(android.app.SearchManager.QUERY, texto.trim()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------- Piezas ----------

@Composable
private fun Seccion(texto: String) {
    Text(texto.uppercase(), style = Texto.Etiqueta, color = Colores.Texto3, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
private fun Fila(icono: ImageVector, titulo: String, nota: String?, acento: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Formas.Tarjeta).pulsable(onClick).padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(Formas.Pastilla).background(if (acento) Colores.Acento.copy(alpha = 0.16f) else Colores.Superficie),
            contentAlignment = Alignment.Center,
        ) { Icon(icono, null, tint = if (acento) Colores.Acento else Colores.Texto2, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, style = Texto.Cuerpo.copy(fontSize = 15.sp), color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
            nota?.let { Text(it, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun Respuesta(consulta: String, resultado: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(Formas.Tarjeta).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Tarjeta).padding(16.dp)) {
        Text(consulta.trim(), style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("= $resultado", style = Texto.Titulo.copy(fontSize = 26.sp, fontWeight = FontWeight.SemiBold), color = Colores.Texto, maxLines = 1)
    }
}
