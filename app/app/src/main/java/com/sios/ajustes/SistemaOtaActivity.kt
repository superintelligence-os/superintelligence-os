package com.sios.ajustes

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.sistema.SistemaSios
import com.sios.sistema.SistemaOta
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.InterruptorSios
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.haceRato
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * «Actualización del sistema»: lo que trae la versión nueva de SIOS, la descarga (en segundo plano,
 * reanudable y, si se quiere, solo con wifi), la instalación en la otra ranura mientras se usa el
 * móvil, «Reiniciar para terminar» y, tras reiniciar, la comprobación y el aviso de si se quedó la
 * nueva o se volvió sola a la anterior. La lógica está en [SistemaOta].
 */
class SistemaOtaActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TemaSios { PantallaSistemaOta(onAtras = ::finish) } }
    }
}

/** Lo que enseña la pantalla en cada momento (se calcula de [SistemaOta]; las capturas lo ponen a mano). */
internal sealed interface VistaOta {
    data class NoSoportado(val soporte: SistemaOta.Soporte) : VistaOta
    data class AlDia(val ultima: Long, val buscando: Boolean, val aviso: String?) : VistaOta
    data class Hay(val d: SistemaOta.Disponible, val soloWifi: Boolean, val falta: Pair<Long, Long>?) : VistaOta
    data class Descargando(val d: SistemaOta.Disponible, val hecho: Long, val modo: ModoDescarga) : VistaOta
    data class Instalando(val version: String, val progreso: Int, val fase: String) : VistaOta
    data class Lista(val version: String, val reiniciando: Boolean) : VistaOta
    data class Comprobando(val paso: String) : VistaOta
    data class Error(val mensaje: String) : VistaOta
}

internal enum class ModoDescarga { ACTIVA, PAUSADA, ESPERANDO_WIFI, ESPERANDO_RED }

/** Datos de la cabecera: la versión que hay y en qué ranura. */
internal data class CabeceraOta(val version: String, val compilacion: String, val ranura: String)

internal class AccionesOta(
    val buscar: () -> Unit = {},
    val descargar: () -> Unit = {},
    val soloWifi: (Boolean) -> Unit = {},
    val pausar: () -> Unit = {},
    val seguir: () -> Unit = {},
    val cancelar: () -> Unit = {},
    val reiniciar: () -> Unit = {},
    val reintentar: () -> Unit = {},
    val visto: () -> Unit = {},
    val atras: () -> Unit = {},
)

private fun hayWifi(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

private fun calcularVista(ctx: Context, a: SistemaOta.Ayudante?, buscando: Boolean, aviso: String?): VistaOta {
    val soporte = SistemaOta.soporte()
    if (soporte != SistemaOta.Soporte.SI) return VistaOta.NoSoportado(soporte)
    val d = SistemaOta.disponible(ctx)
    val version = d?.version ?: a?.compilacion.orEmpty()
    when (a?.fase) {
        "comprobando", "volviendo" -> return VistaOta.Comprobando(if (a.fase == "volviendo") "volviendo" else a.paso)
        "lista", "reiniciando" -> return VistaOta.Lista(version, a.fase == "reiniciando")
        "instalando", "verificando", "copiando", "comprobando_descarga" -> return VistaOta.Instalando(version, a.progreso, a.fase)
    }
    when (SistemaOta.faseLocal(ctx)) {
        SistemaOta.DESCARGANDO -> if (d != null) {
            val modo = when {
                SistemaOta.pausada(ctx) -> ModoDescarga.PAUSADA
                SistemaOta.trabajando(ctx) -> ModoDescarga.ACTIVA
                SistemaOta.soloWifi(ctx) && !hayWifi(ctx) -> ModoDescarga.ESPERANDO_WIFI
                else -> ModoDescarga.ESPERANDO_RED
            }
            return VistaOta.Descargando(d, SistemaOta.descargado(ctx), modo)
        }
        SistemaOta.COMPROBANDO -> return VistaOta.Instalando(version, 0, "comprobando_local")
        SistemaOta.ENTREGADA -> return VistaOta.Instalando(version, 0, "esperando")
        SistemaOta.ERROR -> return VistaOta.Error(SistemaOta.mensajeError(ctx, SistemaOta.errorLocal(ctx)))
    }
    if (d != null) {
        val (libre, necesario) = SistemaOta.espacio(ctx, d)
        return VistaOta.Hay(d, SistemaOta.soloWifi(ctx), if (libre < necesario) necesario to libre else null)
    }
    return VistaOta.AlDia(SistemaOta.ultimaBusqueda(ctx), buscando, aviso)
}

@Composable
private fun PantallaSistemaOta(onAtras: () -> Unit) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    var tic by remember { mutableIntStateOf(0) }
    var buscando by remember { mutableStateOf(false) }
    var aviso by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tic++ } }
    val ayudante = remember(tic) { SistemaOta.ayudante(ctx) }
    LaunchedEffect(tic) { SistemaOta.repasar(ctx, ayudante) }
    val buscar: () -> Unit = {
        if (!buscando) alcance.launch {
            buscando = true
            aviso = null
            aviso = when (val b = withContext(Dispatchers.IO) { SistemaOta.buscar(ctx) }) {
                SistemaOta.Busqueda.SinCanal -> ctx.getString(R.string.ota_sin_canal)
                is SistemaOta.Busqueda.Fallo -> if (b.firma) ctx.getString(R.string.ota_firma_mala) else ctx.getString(R.string.ota_error_buscar, b.detalle)
                else -> null
            }
            buscando = false
            tic++
        }
    }
    LaunchedEffect(Unit) {
        // Abrir la pantalla también cuenta como señal de vida, y busca si no hay nada en marcha.
        withContext(Dispatchers.IO) { SistemaOta.latido(ctx) }
        val nada = SistemaOta.faseLocal(ctx) == SistemaOta.NADA && SistemaOta.disponible(ctx) == null &&
            ayudante?.fase.orEmpty() in listOf("", "libre", "error")
        if (SistemaOta.soporte() == SistemaOta.Soporte.SI && nada) buscar()
    }
    val vista = remember(tic, buscando, aviso) { calcularVista(ctx, ayudante, buscando, aviso) }
    val cabecera = remember {
        CabeceraOta(SistemaSios.version().orEmpty(), SistemaSios.compilacion().orEmpty(), SistemaOta.ranura())
    }
    ContenidoSistemaOta(
        cabecera, vista, ayudante?.resultado,
        AccionesOta(
            buscar = buscar,
            descargar = { SistemaOta.descargar(ctx); tic++ },
            soloWifi = { SistemaOta.ponerSoloWifi(ctx, it); tic++ },
            pausar = { SistemaOta.pausar(ctx); tic++ },
            seguir = { SistemaOta.seguir(ctx); tic++ },
            cancelar = { SistemaOta.cancelar(ctx); tic++ },
            reiniciar = { SistemaOta.reiniciar(ctx); tic++ },
            reintentar = { SistemaOta.reintentar(ctx); buscar() },
            visto = { SistemaOta.visto(ctx); tic++ },
            atras = onAtras,
        ),
    )
}

// ———————————————————— la pantalla (sin lógica: así se puede ver en las capturas) ————————————————————

@Composable
internal fun ContenidoSistemaOta(cabecera: CabeceraOta, vista: VistaOta, resultado: SistemaOta.Resultado?, acciones: AccionesOta) {
    val scroll = rememberScrollState()
    val titulo = stringResource(R.string.ota_titulo)
    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding()) {
        BarraSuperior(titulo, scroll, acciones.atras)
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(top = 2.dp, bottom = 40.dp),
        ) {
            Text(titulo, style = TituloGrande, color = Colores.Texto)
            Spacer(Modifier.height(6.dp))
            Text(textoCabecera(cabecera), style = Texto.Pequeno, color = Colores.Texto3)
            Spacer(Modifier.height(22.dp))
            AnimatedVisibility(
                resultado != null,
                enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()),
                exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave()),
            ) {
                if (resultado != null) Column {
                    TarjetaResultado(resultado, acciones.visto)
                    Spacer(Modifier.height(16.dp))
                }
            }
            AnimatedContent(
                targetState = vista,
                contentKey = { it::class },
                transitionSpec = { (fadeIn(Muelle.suave()) + scaleIn(Muelle.suave(), initialScale = 0.97f)) togetherWith fadeOut(Muelle.suave()) },
                label = "vista",
            ) { v -> TarjetaEstado(v, cabecera, acciones, conResultado = resultado != null) }
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.ota_seguridad), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

@Composable
private fun textoCabecera(c: CabeceraOta): String {
    val fecha = fechaCompilacion(c.compilacion)
    val base = if (fecha != null) stringResource(R.string.ota_version_actual, c.version, fecha) else stringResource(R.string.ota_version_sola, c.version)
    return if (c.ranura.isNotBlank()) base + " · " + stringResource(R.string.ota_ranura, c.ranura.uppercase()) else base
}

private fun fechaCompilacion(c: String): String? = runCatching {
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).parse(c)!!)
}.getOrNull()

private fun fechaPublicada(iso: String): String? = runCatching {
    DateFormat.getDateInstance(DateFormat.LONG, Locale.getDefault()).format(java.util.Date.from(java.time.Instant.parse(iso)))
}.getOrNull()

@Composable
private fun Tarjeta(contenido: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(18.dp).animateContentSize(Muelle.suave()),
    ) { contenido() }
}

@Composable
private fun Etiqueta(texto: String, color: Color = Colores.Texto3) =
    Text(texto.uppercase(), style = Texto.Etiqueta, color = color)

/** Barra de progreso fina; avanza con un muelle, nunca a saltos. */
@Composable
private fun Progreso(fraccion: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraccion.coerceIn(0f, 1f), Muelle.suave(), label = "progreso")
    Box(modifier.fillMaxWidth().height(6.dp).clip(Formas.Pastilla).background(Colores.Borde)) {
        Box(Modifier.fillMaxWidth(f).height(6.dp).clip(Formas.Pastilla).background(Colores.Acento))
    }
}

/** Un círculo con un icono (hecho, aviso…), del color que toque. */
@Composable
private fun Insignia(icono: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Box(Modifier.size(40.dp).clip(Formas.Pastilla).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icono, null, tint = color, modifier = Modifier.size(20.dp))
    }
}

/** Las dos ranuras del sistema: en cuál se está y qué pasa en la otra. */
@Composable
private fun Ranuras(actual: String, otra: String, notaOtra: String, activaOtra: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ranura(actual, stringResource(R.string.ota_ranura_en_uso), false, Modifier.weight(1f))
        Ranura(otra, notaOtra, activaOtra, Modifier.weight(1f))
    }
}

@Composable
private fun Ranura(letra: String, nota: String, resaltada: Boolean, modifier: Modifier) {
    val borde by animateColorAsState(if (resaltada) Colores.Acento else Colores.Borde, Muelle.suave(), label = "ranura")
    Row(
        modifier.clip(Formas.Pequena).background(Colores.Fondo).border(1.dp, borde, Formas.Pequena).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(Formas.Pastilla).background(if (resaltada) Colores.Acento else Colores.Superficie2),
            contentAlignment = Alignment.Center,
        ) { Text(letra.uppercase(), style = Texto.Etiqueta, color = if (resaltada) Colores.SobreAcento else Colores.Texto2) }
        Spacer(Modifier.width(10.dp))
        Text(nota, style = Texto.Pequeno, color = if (resaltada) Colores.Texto else Colores.Texto3, maxLines = 2)
    }
}

private fun otraRanura(r: String) = when (r.lowercase()) { "a" -> "b"; "b" -> "a"; else -> "?" }

@Composable
private fun TarjetaEstado(v: VistaOta, cabecera: CabeceraOta, acciones: AccionesOta, conResultado: Boolean = false) {
    val ctx = LocalContext.current
    when (v) {
        is VistaOta.NoSoportado -> Tarjeta {
            Insignia(Iconos.Telefono, Colores.Texto2)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(if (v.soporte == SistemaOta.Soporte.NO_SIOS) R.string.ota_titulo else R.string.ota_no_soportado), style = Texto.Subtitulo, color = Colores.Texto)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    when (v.soporte) {
                        SistemaOta.Soporte.SIN_AB -> R.string.ota_no_ab
                        SistemaOta.Soporte.SIN_VIRTUAL_AB -> R.string.ota_no_vab
                        SistemaOta.Soporte.SIN_CLAVE -> R.string.ota_no_clave
                        else -> R.string.ota_no_sios
                    },
                ),
                style = Texto.Cuerpo, color = Colores.Texto2,
            )
        }

        is VistaOta.AlDia -> Tarjeta {
            // Debajo del aviso de «ya tienes…» sobra otro círculo: la tarjeta va sin él.
            if (!conResultado || v.buscando) {
                when {
                    v.buscando -> Latido()
                    v.aviso != null -> Insignia(Iconos.Pregunta, Colores.Texto2)
                    else -> Insignia(Iconos.Hecho, Colores.Acento)
                }
                Spacer(Modifier.height(14.dp))
            }
            Text(stringResource(if (v.buscando) R.string.ota_buscando else if (v.aviso != null) R.string.ota_no_se_sabe else R.string.ota_al_dia), style = Texto.Subtitulo, color = Colores.Texto)
            Spacer(Modifier.height(6.dp))
            val nota = when {
                v.buscando -> stringResource(R.string.ota_buscando_nota)
                v.aviso != null -> v.aviso
                v.ultima > 0 -> stringResource(R.string.ota_al_dia_nota, haceRato(ctx, v.ultima))
                else -> stringResource(R.string.ota_nunca)
            }
            Text(nota, style = Texto.Cuerpo, color = Colores.Texto2)
            AnimatedVisibility(!v.buscando, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
                Column {
                    Spacer(Modifier.height(18.dp))
                    BotonSecundario(stringResource(R.string.ota_buscar), Modifier.fillMaxWidth()) { acciones.buscar() }
                }
            }
        }

        is VistaOta.Hay -> Tarjeta {
            Etiqueta(stringResource(R.string.ota_nueva), Colores.Acento)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.ota_sios_version, v.d.version.ifBlank { v.d.compilacion }), style = Texto.Titulo, color = Colores.Texto)
            fechaPublicada(v.d.publicado)?.let {
                Text(stringResource(R.string.ota_publicada, it), style = Texto.Pequeno, color = Colores.Texto3)
            }
            if (v.d.notas.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Etiqueta(stringResource(R.string.ota_lo_que_trae))
                Spacer(Modifier.height(6.dp))
                Text(v.d.notas, style = Texto.Cuerpo, color = Colores.Texto)
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.Borde))
            Spacer(Modifier.height(14.dp))
            val tam = Formatter.formatShortFileSize(ctx, v.d.pieza.tamano)
            Text(
                stringResource(if (v.d.pieza.diferencial) R.string.ota_tamano_diferencial else R.string.ota_tamano_completa, tam),
                style = Texto.Pequeno, color = Colores.Texto2,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.ota_solo_wifi), style = Texto.Cuerpo, color = Colores.Texto)
                    Text(stringResource(R.string.ota_solo_wifi_nota), style = Texto.Pequeno, color = Colores.Texto3)
                }
                Spacer(Modifier.width(12.dp))
                InterruptorSios(v.soloWifi, acciones.soloWifi)
            }
            if (v.falta != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.ota_err_espacio_falta, Formatter.formatShortFileSize(ctx, v.falta.first), Formatter.formatShortFileSize(ctx, v.falta.second)),
                    style = Texto.Pequeno, color = Colores.Rojo,
                )
            }
            Spacer(Modifier.height(18.dp))
            BotonPrimario(stringResource(R.string.ota_descargar), Modifier.fillMaxWidth().graphicsLayer { alpha = if (v.falta == null) 1f else 0.5f }) {
                if (v.falta == null) acciones.descargar()
            }
        }

        is VistaOta.Descargando -> Tarjeta {
            Text(stringResource(R.string.ota_descargando_titulo, v.d.version.ifBlank { v.d.compilacion }), style = Texto.Subtitulo, color = Colores.Texto)
            Spacer(Modifier.height(14.dp))
            Progreso(v.hecho.toFloat() / v.d.pieza.tamano.coerceAtLeast(1))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.ota_descargado, Formatter.formatShortFileSize(ctx, v.hecho), Formatter.formatShortFileSize(ctx, v.d.pieza.tamano)),
                    style = Texto.Pequeno, color = Colores.Texto2, modifier = Modifier.weight(1f),
                )
                Text("${(v.hecho * 100 / v.d.pieza.tamano.coerceAtLeast(1))} %", style = Texto.Pequeno, color = Colores.Texto)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(
                    when (v.modo) {
                        ModoDescarga.ACTIVA -> R.string.ota_descarga_fondo
                        ModoDescarga.PAUSADA -> R.string.ota_pausada
                        ModoDescarga.ESPERANDO_WIFI -> R.string.ota_esperando_wifi
                        ModoDescarga.ESPERANDO_RED -> R.string.ota_esperando_red
                    },
                ),
                style = Texto.Pequeno, color = Colores.Texto3,
            )
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (v.modo == ModoDescarga.PAUSADA) BotonPrimario(stringResource(R.string.ota_seguir), Modifier.weight(1f), acciones.seguir)
                else BotonSecundario(stringResource(R.string.ota_pausar), Modifier.weight(1f), acciones.pausar)
                BotonTexto(stringResource(R.string.ota_cancelar), Modifier.weight(1f), acciones.cancelar)
            }
        }

        is VistaOta.Instalando -> Tarjeta {
            Text(stringResource(R.string.ota_instalando_titulo, v.version), style = Texto.Subtitulo, color = Colores.Texto)
            Spacer(Modifier.height(14.dp))
            val conPorcentaje = v.fase == "instalando" || v.fase == "verificando"
            if (conPorcentaje) Progreso(v.progreso / 100f) else Indeterminado()
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(
                        when (v.fase) {
                            "comprobando_local", "comprobando_descarga" -> R.string.ota_comprobando_descarga
                            "copiando", "esperando" -> R.string.ota_preparando
                            "verificando" -> R.string.ota_verificando
                            else -> R.string.ota_instalando
                        },
                    ),
                    style = Texto.Pequeno, color = Colores.Texto2, modifier = Modifier.weight(1f),
                )
                if (conPorcentaje) Text("${v.progreso} %", style = Texto.Pequeno, color = Colores.Texto)
            }
            Spacer(Modifier.height(16.dp))
            Ranuras(cabecera.ranura.ifBlank { "a" }, otraRanura(cabecera.ranura.ifBlank { "a" }), stringResource(R.string.ota_ranura_instalando), true)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.ota_instalando_nota), style = Texto.Pequeno, color = Colores.Texto3)
            Spacer(Modifier.height(14.dp))
            BotonTexto(stringResource(R.string.ota_cancelar), Modifier.fillMaxWidth(), acciones.cancelar)
        }

        is VistaOta.Lista -> Tarjeta {
            Insignia(Iconos.Hecho, Colores.Acento)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.ota_lista, v.version), style = Texto.Subtitulo, color = Colores.Texto)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.ota_lista_nota), style = Texto.Cuerpo, color = Colores.Texto2)
            Spacer(Modifier.height(16.dp))
            Ranuras(cabecera.ranura.ifBlank { "a" }, otraRanura(cabecera.ranura.ifBlank { "a" }), stringResource(R.string.ota_ranura_lista), true)
            Spacer(Modifier.height(18.dp))
            BotonPrimario(stringResource(if (v.reiniciando) R.string.ota_reiniciando else R.string.ota_reiniciar), Modifier.fillMaxWidth()) {
                if (!v.reiniciando) acciones.reiniciar()
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotonTexto(stringResource(R.string.ota_mas_tarde), Modifier.weight(1f), acciones.atras)
                BotonTexto(stringResource(R.string.ota_descartar), Modifier.weight(1f), acciones.cancelar)
            }
        }

        is VistaOta.Comprobando -> Tarjeta {
            Latido()
            Spacer(Modifier.height(14.dp))
            Text(stringResource(if (v.paso == "volviendo") R.string.ota_volviendo else R.string.ota_comprobando), style = Texto.Subtitulo, color = Colores.Texto)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    when (v.paso) {
                        "sistema" -> R.string.ota_paso_sistema
                        "app" -> R.string.ota_paso_app
                        "desbloqueo" -> R.string.ota_paso_desbloqueo
                        "agente" -> R.string.ota_paso_agente
                        "volviendo" -> R.string.ota_volviendo_nota
                        else -> R.string.ota_paso_sistema
                    },
                ),
                style = Texto.Cuerpo, color = Colores.Texto2,
            )
            Spacer(Modifier.height(16.dp))
            Ranuras(cabecera.ranura.ifBlank { "b" }, otraRanura(cabecera.ranura.ifBlank { "b" }), stringResource(R.string.ota_ranura_anterior), v.paso == "volviendo")
            if (v.paso != "volviendo") {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.ota_comprobando_nota), style = Texto.Pequeno, color = Colores.Texto3)
            }
        }

        is VistaOta.Error -> Tarjeta {
            Insignia(Iconos.Cruz, Colores.Rojo)
            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.ota_error_titulo), style = Texto.Subtitulo, color = Colores.Texto)
            Spacer(Modifier.height(6.dp))
            Text(v.mensaje, style = Texto.Cuerpo, color = Colores.Texto2)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.ota_error_nota), style = Texto.Pequeno, color = Colores.Texto3)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotonPrimario(stringResource(R.string.ota_reintentar), Modifier.weight(1f), acciones.reintentar)
                BotonTexto(stringResource(R.string.ota_cancelar), Modifier.weight(1f), acciones.cancelar)
            }
        }
    }
}

/** Mientras se espera sin saber cuánto falta: un tramo que va y viene, suave. */
@Composable
private fun Indeterminado() {
    val t = rememberInfiniteTransition(label = "indeterminado")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "x")
    Box(Modifier.fillMaxWidth().height(6.dp).clip(Formas.Pastilla).background(Colores.Borde)) {
        Box(
            Modifier.fillMaxWidth(0.32f).height(6.dp)
                .graphicsLayer { translationX = x * size.width * (1f / 0.32f - 1f) }
                .clip(Formas.Pastilla).background(Colores.Acento),
        )
    }
}

/** Un punto que respira: se está comprobando. */
@Composable
private fun Latido() {
    val t = rememberInfiniteTransition(label = "latido")
    val e by t.animateFloat(0.85f, 1.15f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "e")
    Box(Modifier.size(40.dp).clip(Formas.Pastilla).background(Colores.AcentoSuave), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).graphicsLayer { scaleX = e; scaleY = e }.clip(Formas.Pastilla).background(Colores.Acento))
    }
}

@Composable
private fun TarjetaResultado(r: SistemaOta.Resultado, onVisto: () -> Unit) {
    val ctx = LocalContext.current
    val color = if (r.vueltaAtras) Color(0xFFE0A33A) else Colores.Acento
    Column(
        Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), Formas.Tarjeta).padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Insignia(if (r.vueltaAtras) Iconos.Anterior else Iconos.Hecho, color)
            Spacer(Modifier.width(12.dp))
            Text(
                if (r.vueltaAtras) stringResource(R.string.ota_vuelta) else stringResource(R.string.ota_actualizado, r.version.ifBlank { r.compilacion }),
                style = Texto.Subtitulo, color = Colores.Texto, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (r.vueltaAtras) stringResource(R.string.ota_vuelta_nota, r.version.ifBlank { r.compilacion }, SistemaOta.motivo(ctx, r.motivo))
            else stringResource(R.string.ota_actualizado_nota),
            style = Texto.Cuerpo, color = Colores.Texto2,
        )
        Spacer(Modifier.height(8.dp))
        BotonTexto(stringResource(R.string.ota_entendido), Modifier.align(Alignment.End), onVisto)
    }
}
