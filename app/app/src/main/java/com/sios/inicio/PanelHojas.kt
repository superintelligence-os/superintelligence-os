package com.sios.inicio

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.datos.Creacion
import com.sios.datos.ElementoWidget
import com.sios.datos.Panel
import com.sios.datos.Punto
import com.sios.datos.Servicio
import com.sios.ui.AvisoWidget
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.InterruptorSios
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.WidgetCreacion
import com.sios.ui.pulsable
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

// ---------- Galería de widgets ----------

/** Un widget de la galería: fuente, opciones fijas y tamaños que admite. conIa: necesita datos del usuario. */
private class Modelo(
    val clave: String, val fuente: String, val nombre: Int, val descripcion: Int, val tamanos: List<String>,
    val opciones: JSONObject = JSONObject(), val conIa: Int? = null,
)

private val MODELOS = listOf(
    Modelo("tiempo", "tiempo", R.string.gal_tiempo, R.string.gal_tiempo_d, listOf("pequeno", "mediano")),
    Modelo("baterias", "baterias", R.string.gal_baterias, R.string.gal_baterias_d, listOf("pequeno", "mediano", "grande")),
    Modelo("calendario", "calendario", R.string.gal_agenda, R.string.gal_agenda_d, listOf("mediano", "grande")),
    Modelo("mi_dia", "mi_dia", R.string.gal_dia, R.string.gal_dia_d, listOf("mediano", "grande")),
    Modelo("noticias", "noticias", R.string.gal_noticias, R.string.gal_noticias_d, listOf("mediano", "grande"), conIa = R.string.gal_noticias_p),
    Modelo("diesel", "combustible", R.string.gal_diesel, R.string.gal_combustible_d, listOf("mediano", "grande"), JSONObject().put("tipo", "gasoleo_a").put("cuantas", 3).put("orden", "distancia")),
    Modelo("gasolina", "combustible", R.string.gal_gasolina, R.string.gal_combustible_d, listOf("mediano", "grande"), JSONObject().put("tipo", "gasolina_95").put("cuantas", 3).put("orden", "distancia")),
    Modelo("deportes", "deportes", R.string.gal_equipo, R.string.gal_equipo_d, listOf("pequeno", "mediano", "grande"), conIa = R.string.gal_equipo_p),
    Modelo("cumpleanos", "cumpleanos", R.string.gal_cumples, R.string.gal_cumples_d, listOf("mediano", "grande")),
    Modelo("paquetes", "paquetes", R.string.gal_paquetes, R.string.gal_paquetes_d, listOf("mediano", "grande")),
    Modelo("vuelos", "vuelos", R.string.gal_vuelos, R.string.gal_vuelos_d, listOf("mediano", "grande")),
    Modelo("facturas", "facturas", R.string.gal_facturas, R.string.gal_facturas_d, listOf("mediano", "grande")),
    Modelo("aire", "aire", R.string.gal_aire, R.string.gal_aire_d, listOf("mediano", "grande")),
    Modelo("salud", "salud", R.string.gal_pasos, R.string.gal_pasos_d, listOf("pequeno", "mediano", "grande")),
    Modelo("cripto", "cripto", R.string.gal_cripto, R.string.gal_cripto_d, listOf("mediano", "grande"), JSONObject().put("monedas", JSONArray(listOf("bitcoin", "ethereum")))),
    Modelo("bolsa", "bolsa", R.string.gal_bolsa, R.string.gal_bolsa_d, listOf("mediano", "grande"), conIa = R.string.gal_bolsa_p),
    Modelo("trayecto", "trayecto", R.string.gal_trayecto, R.string.gal_trayecto_d, listOf("pequeno", "mediano"), conIa = R.string.gal_trayecto_p),
    Modelo("wifi", "wifi", R.string.gal_wifi, R.string.gal_wifi_d, listOf("mediano", "grande")),
    Modelo("bluetooth", "bluetooth", R.string.gal_bluetooth, R.string.gal_bluetooth_d, listOf("mediano", "grande")),
    Modelo("casa", "casa", R.string.gal_casa, R.string.gal_casa_d, listOf("mediano", "grande"), conIa = R.string.gal_casa_p),
    Modelo("familia_lista", "familia_lista", R.string.gal_familia_lista, R.string.gal_familia_lista_d, listOf("mediano", "grande")),
    Modelo("familia", "familia", R.string.gal_familia, R.string.gal_familia_d, listOf("mediano", "grande")),
    Modelo("gastos", "gastos", R.string.gal_gastos, R.string.gal_gastos_d, listOf("mediano", "grande")),
    Modelo("suscripciones", "suscripciones", R.string.gal_suscripciones, R.string.gal_suscripciones_d, listOf("mediano", "grande")),
    Modelo("garantias", "garantias", R.string.gal_garantias, R.string.gal_garantias_d, listOf("mediano", "grande")),
    Modelo("viaje", "viaje", R.string.gal_viaje, R.string.gal_viaje_d, listOf("mediano", "grande")),
    Modelo("uso_movil", "uso_movil", R.string.gal_uso, R.string.gal_uso_d, listOf("mediano", "grande")),
    Modelo("diario", "diario", R.string.gal_diario, R.string.gal_diario_d, listOf("mediano", "grande")),
)

private fun e(texto: String, detalle: String? = null, valor: String? = null, hora: String? = null, fuente: String? = null, resumen: String? = null) =
    ElementoWidget(texto, detalle, valor, hora, fuente, resumen)

/** Datos de ejemplo para la vista previa (con la misma plantilla que tendrá de verdad). */
private fun muestra(m: Modelo, tamano: String): Creacion {
    var plantilla = "filas"
    var titulo = ""
    var valor: String? = null
    var unidad: String? = null
    var subtitulo: String? = null
    var texto: String? = null
    var elementos = emptyList<ElementoWidget>()
    var serie = emptyList<Punto>()
    var progreso: Float? = null
    var marcador: List<String?> = listOf(null, null, null, null, null)
    val semana = listOf("lun", "mar", "mié", "jue", "vie", "sáb", "dom")
    when (m.clave) {
        "tiempo" -> { plantilla = "cifra"; titulo = "El tiempo"; valor = "21"; unidad = "°C"; subtitulo = "Soleado · máx 24° · mín 13°" }
        "baterias" -> { plantilla = "anillos"; titulo = "Baterías"; elementos = listOf(e("Este móvil", valor = "78"), e("Cascos", valor = "45"), e("Reloj", valor = "18")) }
        "calendario" -> { plantilla = "agenda"; titulo = "Agenda"; elementos = listOf(e("Reunión de equipo", "Oficina", hora = "Hoy 10:00"), e("Dentista", hora = "Hoy 17:30"), e("Cena con Ana", "La Tapería", hora = "Mañana 21:00")) }
        "mi_dia" -> { plantilla = "texto"; titulo = "Tu día"; texto = "A las 10 tienes la reunión de equipo y por la tarde el dentista. Hoy hace sol, 24°. Es el cumpleaños de Laura." }
        "noticias" -> { plantilla = "noticias"; titulo = "Noticias para ti"; elementos = listOf(e("La IA ya detecta errores en facturas antes de pagarlas", fuente = "El País", hora = "09:12", resumen = "Las empresas que la usan reducen un 30 % las reclamaciones."), e("El Real Madrid gana en Valencia y recupera el liderato", fuente = "Marca", hora = "08:40", resumen = "Doblete en la segunda parte."), e("Murcia tendrá tren a Almería en 2027", fuente = "La Verdad", hora = "ayer")) }
        "diesel", "gasolina" -> { plantilla = "filas"; titulo = if (m.clave == "diesel") "Gasóleo A · 10 km" else "Gasolina 95 · 10 km"; elementos = listOf(e("RENESUR · Fortuna", "3,0 km", "1,718 €"), e("E.S. PREMIER · Fortuna", "3,3 km", "1,718 €"), e("CEPSA · Fortuna", "6,6 km", "1,735 €")) }
        "deportes" -> { plantilla = "marcador"; titulo = "Real Madrid"; marcador = listOf("Real Madrid", "Barcelona", "2", "1", "67'"); elementos = listOf(e("Real Madrid 3–0 Valencia", hora = "sáb", detalle = "último")) }
        "cumpleanos" -> { plantilla = "agenda"; titulo = "Cumpleaños"; elementos = listOf(e("Laura", "cumple 34", hora = "Hoy"), e("Pablo", hora = "en 3 días"), e("Mamá", "cumple 61", hora = "20 nov")) }
        "paquetes" -> { plantilla = "agenda"; titulo = "Paquetes"; elementos = listOf(e("Zapatillas", "SEUR · llega hoy", hora = "en reparto"), e("Cargador", "Correos · mañana", hora = "en tránsito")) }
        "vuelos" -> { plantilla = "agenda"; titulo = "Vuelos"; elementos = listOf(e("IB 3402 · Madrid → Londres", "T4", hora = "Mañana 10:35")) }
        "facturas" -> { plantilla = "agenda"; titulo = "Facturas"; elementos = listOf(e("Luz", valor = "48,20 €", hora = "jue 14"), e("Internet", valor = "35,00 €", hora = "20 nov")) }
        "aire" -> { plantilla = "filas"; titulo = "Aire y polen"; elementos = listOf(e("Calidad del aire", "índice europeo 24", "Aceptable"), e("Polen de gramíneas", valor = "medio"), e("Polen de olivo", valor = "bajo")) }
        "salud" -> { plantilla = "grafico"; titulo = "Pasos"; valor = "7.842"; unidad = "pasos"; subtitulo = "objetivo 10.000"; serie = listOf(6200, 8100, 5400, 9300, 7000, 11200, 7842).mapIndexed { i, y -> Punto(semana[i], y.toDouble()) } }
        "cripto" -> { plantilla = "filas"; titulo = "Cripto"; elementos = listOf(e("Bitcoin", valor = "67.125 €  ▼ 1,2 %"), e("Ethereum", valor = "2.910 €  ▲ 0,8 %")) }
        "bolsa" -> { plantilla = "filas"; titulo = "Bolsa"; elementos = listOf(e("Banco Santander", "SAN.MC", "6,42 €  ▲ 1,1 %"), e("Inditex", "ITX.MC", "48,90 €  ▼ 0,4 %")) }
        "trayecto" -> { plantilla = "cifra"; titulo = "A casa"; valor = "25"; unidad = "min"; subtitulo = "27,4 km en coche · sin contar el tráfico" }
        "wifi" -> { plantilla = "filas"; titulo = "En tu Wi-Fi · 5"; elementos = listOf(e("Router", "192.168.1.1", "router"), e("Tele del salón", "192.168.1.23", "activo"), e("Portátil", "192.168.1.40", "visto")) }
        "bluetooth" -> { plantilla = "filas"; titulo = "Bluetooth cerca · 3"; elementos = listOf(e("AirPods de Marta", "BLE · emparejado", "muy cerca"), e("Mi Band 8", "BLE", "cerca")) }
        "casa" -> { plantilla = "filas"; titulo = "Casa"; elementos = listOf(e("Luz del salón", valor = "encendido"), e("Enchufe del café", valor = "apagado"), e("Temperatura", valor = "22,5 °C")) }
        "familia_lista" -> { plantilla = "lista"; titulo = "Casa"; elementos = listOf(ElementoWidget("Pan", detalle = "Ana"), ElementoWidget("Leche", detalle = "Ana"), ElementoWidget("Huevos", detalle = "Tú", hecho = true)) }
        "familia" -> { plantilla = "filas"; titulo = "Familia"; elementos = listOf(e("Ana", "hace 5 min", "Calle Mayor, Fortuna"), e("Pablo", "hace 1 h", "Colegio San José"), e("Mamá", valor = "no comparte")) }
        "gastos" -> { plantilla = "grafico"; titulo = "Gastos del mes"; valor = "412"; unidad = "€"; subtitulo = "12 % menos que el mes pasado · sobre todo Mercadona"; serie = listOf(96, 120, 84, 112).mapIndexed { i, y -> Punto(listOf("-3", "-2", "-1", "esta")[i], y.toDouble()) } }
        "suscripciones" -> { plantilla = "filas"; titulo = "Suscripciones"; elementos = listOf(e("Netflix", "renueva el 14", "12,99 €"), e("Spotify", "renueva el 20", "10,99 €"), e("iCloud", "renueva el 2 dic", "2,99 €")) }
        "garantias" -> { plantilla = "filas"; titulo = "En garantía"; elementos = listOf(e("Auriculares Sony", "MediaMarkt", "hasta mar 2028"), e("Zapatillas Nike", "Zalando", "hasta nov 2029")) }
        "viaje" -> { plantilla = "filas"; titulo = "Viaje a Londres"; elementos = listOf(e("Vuelo IB3166 a Londres", hora = "mié 11:30"), e("Tiempo en Londres", valor = "12° / 7° · nubes y claros"), e("Allí", valor = "-1 h · 1 € = 0,83 GBP")) }
        "uso_movil" -> { plantilla = "grafico"; titulo = "Tu móvil hoy"; valor = "2 h 14 min"; subtitulo = "Instagram 48 min · WhatsApp 31 min"; serie = listOf(190, 160, 210, 140, 175, 230, 134).mapIndexed { i, y -> Punto(semana[i], y.toDouble()) } }
        "diario" -> { plantilla = "texto"; titulo = "Tu diario"; texto = "Lunes. Hoy has tenido un día tranquilo: reunión por la mañana, 8.000 pasos y una cena con Ana. Descansa bien." }
    }
    return Creacion(
        id = "muestra-${m.clave}", tipo = "widget", nombre = titulo, acento = "naranja", icono = null, plantilla = plantilla, titulo = titulo,
        valor = valor, unidad = unidad, subtitulo = subtitulo, texto = texto, progreso = progreso, elementos = elementos, cada = null,
        pausado = false, ultima = null, resultado = null, error = null, lugar = "panel", tamano = tamano, serie = serie,
        local = marcador[0], visitante = marcador[1], golesLocal = marcador[2], golesVisitante = marcador[3], estadoPartido = marcador[4], enJuego = marcador[4] != null,
    )
}

/** Tamaño en pantalla de una celda de la rejilla, para que la vista previa sea igual que en el panel. */
private fun medida(tamano: String, celda: Dp, hueco: Dp): Pair<Dp, Dp> = when (tamano) {
    "pequeno" -> celda to celda
    "grande" -> (celda * 2 + hueco) to (celda * 2 + hueco)
    else -> (celda * 2 + hueco) to celda
}

/**
 * La galería: cada widget con su vista previa real (los mismos componentes que en el panel), sus
 * tamaños y «Añadir». Los que necesitan algo del usuario (sus intereses, su equipo…) se configuran
 * con Claude.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HojaGaleria(onPedir: (String) -> Unit, onCerrar: () -> Unit) {
    var elegido by remember { mutableStateOf<Modelo?>(null) }
    var pestana by remember { mutableStateOf(0) }
    ModalBottomSheet(onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Colores.Lateral, contentColor = Colores.Texto) {
        AnimatedContent(elegido, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "galeria") { m ->
            if (m == null) {
                Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.panel_galeria), style = Texto.Titulo, color = Colores.Texto)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(if (pestana == 0) R.string.panel_galeria_texto else R.string.comunidad_texto), style = Texto.Pequeno, color = Colores.Texto3)
                    Spacer(Modifier.height(14.dp))
                    // Modelos de SIOS | lo que comparte la gente.
                    Row(Modifier.fillMaxWidth().clip(Formas.Pastilla).background(Colores.Superficie).padding(4.dp)) {
                        listOf(R.string.galeria_modelos, R.string.galeria_comunidad).forEachIndexed { i, t ->
                            val activa = i == pestana
                            Box(
                                Modifier.weight(1f).height(36.dp).clip(Formas.Pastilla).background(if (activa) Colores.Superficie2 else androidx.compose.ui.graphics.Color.Transparent).pulsable { pestana = i },
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(t), style = Texto.Pequeno, color = if (activa) Colores.Texto else Colores.Texto3) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    if (pestana == 1) ListaComunidad(onCerrar)
                    else MODELOS.forEach { x ->
                        Row(
                            Modifier.fillMaxWidth().clip(Formas.Tarjeta).pulsable { elegido = x }.padding(vertical = 12.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(x.nombre), style = Texto.Subtitulo.copy(fontSize = 15.sp), color = Colores.Texto)
                                Text(stringResource(x.descripcion), style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            } else DetalleModelo(m, onVolver = { elegido = null }, onPedir = { onPedir(it); onCerrar() }, onAnadido = onCerrar)
        }
    }
}

@Composable
private fun DetalleModelo(m: Modelo, onVolver: () -> Unit, onPedir: (String) -> Unit, onAnadido: () -> Unit) {
    var tamano by remember(m) { mutableStateOf(m.tamanos.first()) }
    val pedido = m.conIa?.let { stringResource(it) }
    Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(Formas.Pastilla).pulsable(onVolver), contentAlignment = Alignment.Center) {
                Icon(Iconos.Atras, null, tint = Colores.Texto2, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            Column {
                Text(stringResource(m.nombre), style = Texto.Subtitulo, color = Colores.Texto)
                Text(stringResource(m.descripcion), style = Texto.Pequeno, color = Colores.Texto3)
            }
        }
        Spacer(Modifier.height(18.dp))
        // Vista previa a tamaño real, centrada.
        BoxWithConstraints(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
            val hueco = 12.dp
            val celda = (maxWidth - hueco) / 2
            val alto = medida(tamano, celda, hueco).second
            val escala = minOf(1f, 300.dp / alto)
            AnimatedContent(tamano, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "previa") { t ->
                val (an, al) = medida(t, celda, hueco)
                Box(Modifier.size(an * escala, al * escala)) {
                    WidgetCreacion(muestra(m, t), onToque = {}, onLargo = {}, tamano = t, conLargo = false, modifier = Modifier.size(an * escala, al * escala))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            m.tamanos.forEach { t ->
                val activo = t == tamano
                Box(
                    Modifier.weight(1f).height(40.dp).clip(Formas.Pequena)
                        .background(if (activo) Colores.Superficie2 else Colores.Superficie)
                        .border(1.dp, if (activo) Colores.Texto3 else Colores.Borde, Formas.Pequena)
                        .pulsable { tamano = t },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(when (t) { "pequeno" -> R.string.tamano_pequeno; "grande" -> R.string.tamano_grande; else -> R.string.tamano_mediano }), style = Texto.Pequeno, color = if (activo) Colores.Texto else Colores.Texto2)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        if (pedido != null) {
            BotonPrimario(stringResource(R.string.panel_con_ia), Modifier.fillMaxWidth()) { onPedir(pedido + " (" + tamano + ")") }
        } else {
            BotonPrimario(stringResource(R.string.panel_anadir), Modifier.fillMaxWidth()) {
                Servicio.anadirAlPanel(m.fuente, tamano, m.opciones)
                onAnadido()
            }
        }
    }
}

// ---------- Historial ----------

/** Fotos anteriores del panel: tocar una lo deja como estaba (y se guarda una foto de cómo está ahora). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HojaHistorial(onCerrar: () -> Unit) {
    val lista by produceState<JSONArray?>(null) { value = Servicio.historialPanel() }
    val restaurado = stringResource(R.string.panel_restaurado)
    ModalBottomSheet(onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Colores.Lateral, contentColor = Colores.Texto) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.panel_menu_historial), style = Texto.Titulo, color = Colores.Texto)
            Spacer(Modifier.height(14.dp))
            val l = lista
            if (l != null && l.length() == 0) Text(stringResource(R.string.panel_historial_vacio), style = Texto.Cuerpo, color = Colores.Texto3)
            if (l != null) for (i in 0 until l.length()) {
                val h = l.getJSONObject(i)
                val tarjetas = h.optJSONArray("tarjetas")?.let { t -> (0 until t.length()).joinToString(" · ") { t.getString(it) } }.orEmpty()
                Row(
                    Modifier.fillMaxWidth().clip(Formas.Tarjeta).pulsable {
                        Servicio.restaurarPanel(h.getString("id"))
                        AvisoWidget.mostrar(restaurado)
                        onCerrar()
                    }.padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            DateUtils.getRelativeDateTimeString(androidx.compose.ui.platform.LocalContext.current, h.getLong("cuando"), DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString(),
                            style = Texto.Subtitulo.copy(fontSize = 15.sp), color = Colores.Texto,
                        )
                        Text(tarjetas.ifEmpty { "—" }, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.panel_restaurar), style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Acento)
                }
            }
        }
    }
}

// ---------- Menú, modos y ajustes ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HojaMenuPanel(onHistorial: () -> Unit, onAjustes: () -> Unit, onCerrar: () -> Unit) {
    ModalBottomSheet(onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Colores.Lateral, contentColor = Colores.Texto) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            FilaMenu(Iconos.Refrescar, stringResource(R.string.panel_menu_historial)) { onCerrar(); onHistorial() }
            FilaMenu(Iconos.Ajustes, stringResource(R.string.panel_menu_ajustes)) { onCerrar(); onAjustes() }
        }
    }
}

@Composable
private fun FilaMenu(icono: androidx.compose.ui.graphics.vector.ImageVector, texto: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(52.dp).clip(Formas.Tarjeta).pulsable(onClick).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icono, null, tint = Colores.Texto2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(texto, style = Texto.Cuerpo, color = Colores.Texto)
    }
}

/** Elegir el modo del panel: automático (según dónde y cuándo) o uno fijo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HojaModos(panel: Panel, onCerrar: () -> Unit) {
    ModalBottomSheet(onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Colores.Lateral, contentColor = Colores.Texto) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.panel_modo), style = Texto.Titulo, color = Colores.Texto)
            Spacer(Modifier.height(12.dp))
            val opciones = listOf<Pair<String?, String>>(null to stringResource(R.string.panel_modo_auto)) + panel.modos.map { it.id to it.nombre }
            opciones.forEach { (id, nombre) ->
                val activo = panel.modoManual == id
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(Formas.Tarjeta).pulsable { Servicio.elegirModo(id); onCerrar() }.padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(nombre, style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.weight(1f))
                    if (activo) Icon(Iconos.Hecho, null, tint = Colores.Acento, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/**
 * Ajustes del panel: cuánto Claude puede gastar al día en él, las tarjetas automáticas y la
 * conexión con Home Assistant (dirección y token: los escribe el usuario aquí, nunca en el chat).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HojaAjustesPanel(onPedir: (String) -> Unit, onCerrar: () -> Unit) {
    var ajustes by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(Unit) { ajustes = Servicio.ajustesPanel() }
    val alcance = rememberCoroutineScope()
    fun cambiar(cambios: JSONObject) {
        Servicio.ponerAjustesPanel(cambios)
        alcance.launch { kotlinx.coroutines.delay(300); ajustes = Servicio.ajustesPanel() }
    }
    ModalBottomSheet(onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Colores.Lateral, contentColor = Colores.Texto) {
        val a = ajustes
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.panel_menu_ajustes), style = Texto.Titulo, color = Colores.Texto)
            Spacer(Modifier.height(18.dp))
            if (a == null) return@Column
            // Presupuesto de Claude
            val presupuesto = a.optInt("presupuesto", 30)
            val gasto = a.optJSONObject("gasto")
            Text(stringResource(R.string.panel_ajustes_presupuesto), style = Texto.Subtitulo.copy(fontSize = 15.sp), color = Colores.Texto)
            Text(stringResource(R.string.panel_ajustes_presupuesto_nota, gasto?.optInt("usadas") ?: 0, presupuesto), style = Texto.Pequeno, color = Colores.Texto3)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf(10, 30, 60, 100).forEach { v ->
                    val activo = v == presupuesto
                    Box(
                        Modifier.weight(1f).padding(end = 8.dp).height(40.dp).clip(Formas.Pequena)
                            .background(if (activo) Colores.Superficie2 else Colores.Superficie)
                            .border(1.dp, if (activo) Colores.Texto3 else Colores.Borde, Formas.Pequena)
                            .pulsable { cambiar(JSONObject().put("presupuesto", v)) },
                        contentAlignment = Alignment.Center,
                    ) { Text("$v", style = Texto.Pequeno, color = if (activo) Colores.Texto else Colores.Texto2) }
                }
            }
            Spacer(Modifier.height(24.dp))
            // Tarjetas automáticas
            val auto = a.optJSONObject("automaticas") ?: JSONObject()
            val tipos = auto.optJSONArray("tipos")?.let { t -> (0 until t.length()).map { t.getString(it) } } ?: emptyList()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.panel_ajustes_automaticas), style = Texto.Subtitulo.copy(fontSize = 15.sp), color = Colores.Texto)
                    Text(stringResource(R.string.panel_ajustes_automaticas_nota), style = Texto.Pequeno, color = Colores.Texto3)
                }
                InterruptorSios(auto.optBoolean("activo", true)) { cambiar(JSONObject().put("automaticas", JSONObject().put("activo", it))) }
            }
            if (auto.optBoolean("activo", true)) {
                Spacer(Modifier.height(6.dp))
                listOf("paquetes" to R.string.tipo_paquetes, "deportes" to R.string.tipo_deportes, "vuelos" to R.string.tipo_vuelos, "facturas" to R.string.tipo_facturas).forEach { (t, nombre) ->
                    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(nombre), style = Texto.Cuerpo, color = Colores.Texto2, modifier = Modifier.weight(1f))
                        InterruptorSios(t in tipos) { si ->
                            val nuevos = if (si) tipos + t else tipos - t
                            cambiar(JSONObject().put("automaticas", JSONObject().put("tipos", JSONArray(nuevos))))
                        }
                    }
                }
                val equipos = auto.optJSONArray("equipos")?.let { q -> (0 until q.length()).map { q.getJSONObject(it).optString("equipo") } } ?: emptyList()
                Text(
                    if (equipos.isEmpty()) stringResource(R.string.panel_equipos_ninguno) else stringResource(R.string.panel_equipos, equipos.joinToString(", ")),
                    style = Texto.Pequeno, color = Colores.Texto3,
                )
                Spacer(Modifier.height(8.dp))
                val pedirEquipos = stringResource(R.string.panel_equipos_pedido)
                BotonSecundario(stringResource(R.string.panel_equipos_elegir), Modifier.fillMaxWidth()) { onPedir(pedirEquipos); onCerrar() }
            }
            Spacer(Modifier.height(24.dp))
            // Casa
            CasaAjustes(a.optBoolean("casa")) { alcance.launch { ajustes = Servicio.ajustesPanel() } }
        }
    }
}

@Composable
private fun CasaAjustes(conectada: Boolean, onCambio: () -> Unit) {
    val alcance = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    Text(stringResource(R.string.panel_casa), style = Texto.Subtitulo.copy(fontSize = 15.sp), color = Colores.Texto)
    Spacer(Modifier.height(8.dp))
    if (conectada) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(Formas.Pastilla).background(androidx.compose.ui.graphics.Color(0xFF7CBF8E)))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.panel_casa_conectada), style = Texto.Cuerpo, color = Colores.Texto2, modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.panel_casa_desconectar), style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Rojo,
                modifier = Modifier.clip(Formas.Pequena).pulsable { alcance.launch { Servicio.conectarCasa(null, null); onCambio() } }.padding(8.dp),
            )
        }
        return
    }
    Campo(url, { url = it }, stringResource(R.string.panel_casa_url), KeyboardType.Uri, secreto = false)
    Spacer(Modifier.height(8.dp))
    Campo(token, { token = it }, stringResource(R.string.panel_casa_token), KeyboardType.Password, secreto = true)
    Spacer(Modifier.height(10.dp))
    BotonPrimario(stringResource(R.string.panel_casa_conectar), Modifier.fillMaxWidth()) {
        if (url.isNotBlank() && token.isNotBlank()) alcance.launch { Servicio.conectarCasa(url.trim(), token.trim()); token = ""; onCambio() }
    }
}

@Composable
internal fun Campo(valor: String, onCambio: (String) -> Unit, pista: String, teclado: KeyboardType, secreto: Boolean) {
    BasicTextField(
        valor, onCambio, singleLine = true,
        textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
        cursorBrush = SolidColor(Colores.Acento),
        keyboardOptions = KeyboardOptions(keyboardType = teclado),
        visualTransformation = if (secreto) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(Formas.Pequena).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Pequena).padding(horizontal = 14.dp),
        decorationBox = { campo ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (valor.isEmpty()) Text(pista, style = Texto.Cuerpo, color = Colores.Texto3)
                campo()
            }
        },
    )
}
