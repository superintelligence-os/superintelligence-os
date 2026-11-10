package com.sios.ajustes

import android.app.ActivityManager
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import com.sios.voz.Dictado
import com.sios.voz.IconosVoz
import com.sios.voz.ModelosVoz
import com.sios.voz.ModelosVoz.Descarga
import com.sios.voz.TraduccionLocal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Dictado y traducción sin conexión, en Ajustes de SIOS (dentro de «Asistente y voz»): cuándo se usan,
 * los modelos de dictado y los idiomas de traducción (descargar, cuánto ocupan, borrar).
 */
@Composable
internal fun SeccionVozSinConexion(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    // Cada vez que termina una descarga o se borra algo, se vuelve a mirar qué hay.
    val cambios by ModelosVoz.cambios.collectAsStateWithLifecycle()
    val descargas by ModelosVoz.descargas.collectAsStateWithLifecycle()
    var borrar by remember { mutableStateOf<Pair<ModelosVoz.Modelo, String>?>(null) }
    var conDatos by remember { mutableStateOf<ModelosVoz.Modelo?>(null) }
    val ocupado by produceState(0L, cambios) { value = withContext(Dispatchers.IO) { ModelosVoz.TODOS.sumOf { ModelosVoz.ocupa(ctx, it) } } }
    val listos by produceState(emptySet<String>(), cambios) {
        value = withContext(Dispatchers.IO) { ModelosVoz.TODOS.filter { ModelosVoz.listo(ctx, it) }.map { it.id }.toSet() }
    }

    fun pedir(m: ModelosVoz.Modelo) {
        if (ModelosVoz.redDePago(ctx) && m.bytes > 30L * 1024 * 1024) conDatos = m else ModelosVoz.descargar(ctx, m)
    }

    val accion = AccionesModelo(listos, descargas, ::pedir, { m, nombre -> borrar = m to nombre }, { ModelosVoz.cancelar(it.id) })

    seccion(stringResource(R.string.voz_titulo), stringResource(R.string.voz_nota)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TarjetaVoz { Modo() }
            TarjetaVoz { Dictados(accion) }
            TarjetaVoz { Traducciones(accion) }
            AnimatedVisibility(ocupado > 0, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
                Text(
                    stringResource(R.string.voz_ocupan, Formatter.formatShortFileSize(ctx, ocupado)),
                    style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Text(stringResource(R.string.voz_licencias), style = Texto.Pequeno.copy(fontSize = 12.sp, lineHeight = 17.sp), color = Colores.Texto3, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }

    borrar?.let { (m, nombre) ->
        HojaVoz(
            titulo = stringResource(R.string.voz_borrar_titulo, nombre),
            texto = stringResource(R.string.voz_borrar_texto) +
                if (m.clase == ModelosVoz.Clase.DICTADO_LIGERO && com.sios.sistema.OyeAsistente.activo(ctx) && m.idioma == Locale.getDefault().language)
                    "\n\n" + stringResource(R.string.voz_borrar_oye) else "",
            boton = stringResource(R.string.voz_borrar), peligro = true,
            onCerrar = { borrar = null },
        ) { ModelosVoz.borrar(ctx, m) }
    }
    conDatos?.let { m ->
        HojaVoz(
            titulo = stringResource(R.string.voz_datos_titulo),
            texto = stringResource(R.string.voz_datos_texto, Formatter.formatShortFileSize(ctx, m.bytes)),
            boton = stringResource(R.string.voz_descargar), peligro = false,
            onCerrar = { conDatos = null },
        ) { ModelosVoz.descargar(ctx, m) }
    }
}

/** Lo que se puede hacer con un modelo (y qué hay), para no ir pasándolo pieza a pieza. */
private class AccionesModelo(
    val listos: Set<String>,
    val descargas: Map<String, Descarga>,
    val descargar: (ModelosVoz.Modelo) -> Unit,
    val borrar: (ModelosVoz.Modelo, String) -> Unit,
    val cancelar: (ModelosVoz.Modelo) -> Unit,
)

// ---------- Cuándo ----------

@Composable
private fun Modo() {
    val ctx = LocalContext.current
    var modo by remember { mutableStateOf(Dictado.modo(ctx)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(Formas.Pequena).background(Colores.AcentoSuave), contentAlignment = Alignment.Center) {
                Icon(IconosVoz.Candado, null, tint = Colores.Acento, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.voz_privacidad), style = Texto.Pequeno, color = Colores.Texto2, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.voz_cuando), style = Texto.Cuerpo, color = Colores.Texto)
        Spacer(Modifier.height(10.dp))
        SegmentosVoz(
            listOf(stringResource(R.string.voz_modo_auto), stringResource(R.string.voz_modo_siempre)),
            if (modo == Dictado.Modo.SIEMPRE) 1 else 0,
        ) { i -> modo = if (i == 1) Dictado.Modo.SIEMPRE else Dictado.Modo.AUTOMATICO; Dictado.ponerModo(ctx, modo) }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(
            modo, transitionSpec = { (fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave())).using(SizeTransform(clip = false) { _, _ -> Muelle.suave() }) },
            label = "modo_voz",
        ) { m ->
            Text(
                stringResource(if (m == Dictado.Modo.SIEMPRE) R.string.voz_modo_siempre_nota else R.string.voz_modo_auto_nota),
                style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------- Dictado ----------

@Composable
private fun Dictados(a: AccionesModelo) {
    val ctx = LocalContext.current
    val propio = Locale.getDefault().language.takeIf { l -> ModelosVoz.ligero(l) != null } ?: "en"
    val recomendado = remember { recomendarPreciso(ctx) }
    val preciso = ModelosVoz.WHISPER_SMALL
    val ligero = ModelosVoz.ligero(propio)!!
    var otros by remember { mutableStateOf(false) }
    var motor by remember { mutableStateOf(Dictado.motor(ctx)) }
    Column {
        Text(stringResource(R.string.voz_dictado), style = Texto.Subtitulo, color = Colores.Texto)
        Spacer(Modifier.height(4.dp))
        FilaModelo(
            preciso, stringResource(R.string.voz_preciso), stringResource(R.string.voz_preciso_nota), a,
            etiqueta = if (recomendado) stringResource(R.string.voz_recomendado) else null,
        )
        SeparadorVoz()
        FilaModelo(
            ligero, stringResource(R.string.voz_ligero_corto), stringResource(R.string.voz_ligero_nota), a,
            etiqueta = if (!recomendado) stringResource(R.string.voz_recomendado) else null, idioma = nombreIdioma(propio),
        )
        // Los demás idiomas del dictado ligero, plegados.
        SeparadorVoz()
        val giro by animateFloatAsState(if (otros) 90f else 0f, Muelle.suave(), label = "otros")
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(remember { MutableInteractionSource() }, indication = null) { otros = !otros },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.voz_otros_idiomas), style = Texto.Cuerpo, color = Colores.Texto2, modifier = Modifier.weight(1f))
            Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp).rotate(giro))
        }
        AnimatedVisibility(otros, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
            Column {
                ModelosVoz.LIGEROS.filter { it.idioma != propio }.forEach { m ->
                    FilaModelo(m, nombreIdioma(m.idioma!!), null, a, compacta = true, nombreBorrar = stringResource(R.string.voz_ligero, nombreIdioma(m.idioma)))
                }
            }
        }
        // Con los dos, se elige con cuál se dicta.
        val dos = preciso.id in a.listos && a.listos.any { id -> ModelosVoz.LIGEROS.any { it.id == id } }
        AnimatedVisibility(dos, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
            Column(Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.voz_usar), style = Texto.Cuerpo, color = Colores.Texto)
                Spacer(Modifier.height(10.dp))
                SegmentosVoz(
                    listOf(stringResource(R.string.voz_preciso), stringResource(R.string.voz_ligero_corto)),
                    if (motor == Dictado.Motor.LIGERO) 1 else 0,
                ) { i -> motor = if (i == 1) Dictado.Motor.LIGERO else Dictado.Motor.PRECISO; Dictado.ponerMotor(ctx, motor) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.voz_otras_apps), style = Texto.Pequeno, color = Colores.Texto3)
    }
}

/**
 * Whisper small va bien en móviles con soltura (8 GB de memoria suele ir con procesadores rápidos);
 * en los demás, el ligero responde antes.
 */
private fun recomendarPreciso(ctx: android.content.Context): Boolean {
    val info = ActivityManager.MemoryInfo()
    ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
    return info.totalMem >= 7_000_000_000L && Runtime.getRuntime().availableProcessors() >= 8
}

// ---------- Traducción ----------

@Composable
private fun Traducciones(a: AccionesModelo) {
    val propio = Locale.getDefault().language
    // El idioma del móvil, el primero.
    val lista = ModelosVoz.TRADUCCIONES.sortedBy { if (it.idioma == propio) 0 else 1 }
    Column {
        Text(stringResource(R.string.voz_traduccion), style = Texto.Subtitulo, color = Colores.Texto)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.voz_traduccion_nota), style = Texto.Pequeno, color = Colores.Texto3)
        Spacer(Modifier.height(4.dp))
        lista.forEachIndexed { i, m ->
            FilaModelo(m, nombreIdioma(m.idioma!!) + " ⇄ " + nombreIdioma("en"), null, a, compacta = true)
            if (i < lista.lastIndex) SeparadorVoz()
        }
    }
}

private fun nombreIdioma(codigo: String): String =
    Locale.forLanguageTag(codigo).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

// ---------- Fila de un modelo ----------

/**
 * Un modelo: su nombre, en qué está (por descargar con su tamaño, descargándose con su barra, listo
 * con lo que ocupa) y lo que se puede hacer (descargar, cancelar, borrar).
 */
@Composable
private fun FilaModelo(
    m: ModelosVoz.Modelo, titulo: String, descripcion: String?, a: AccionesModelo,
    etiqueta: String? = null, compacta: Boolean = false, idioma: String? = null, nombreBorrar: String = idioma?.let { "$titulo · $it" } ?: titulo,
) {
    val ctx = LocalContext.current
    val listo = m.id in a.listos
    val d = a.descargas[m.id] ?: Descarga.Parada
    val tamano = Formatter.formatShortFileSize(ctx, m.bytes)
    val estado = when {
        listo -> stringResource(R.string.voz_listo, tamano)
        d is Descarga.EnCola -> stringResource(R.string.voz_en_cola)
        d is Descarga.EnCurso -> stringResource(R.string.voz_descargando, Formatter.formatShortFileSize(ctx, d.hecho), tamano)
        d is Descarga.Comprobando -> stringResource(R.string.voz_comprobando)
        d is Descarga.Fallo -> stringResource(
            when (d.motivo) {
                ModelosVoz.Motivo.SIN_RED -> R.string.voz_fallo_red
                ModelosVoz.Motivo.SIN_ESPACIO -> R.string.voz_fallo_espacio
                ModelosVoz.Motivo.OTRO -> R.string.voz_fallo
            },
        )
        else -> tamano
    }.let { e -> if (idioma != null) "$idioma · $e" else e }
    Column(Modifier.fillMaxWidth().padding(vertical = if (compacta) 8.dp else 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(titulo, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (etiqueta != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            etiqueta, style = Texto.Etiqueta.copy(fontSize = 10.sp, letterSpacing = 0.4.sp), color = Colores.Acento, maxLines = 1,
                            modifier = Modifier.clip(Formas.Pastilla).background(Colores.AcentoSuave).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
                if (descripcion != null) Text(descripcion, style = Texto.Pequeno, color = Colores.Texto3)
                AnimatedContent(
                    estado, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "estado_modelo",
                ) { e ->
                    Text(
                        e, style = Texto.Pequeno, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = when { d is Descarga.Fallo -> Colores.Rojo; listo -> Colores.Texto2; else -> Colores.Texto3 },
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                when { listo -> 0; d is Descarga.EnCola || d is Descarga.EnCurso || d is Descarga.Comprobando -> 1; else -> 2 },
                transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "accion_modelo",
            ) { caso ->
                when (caso) {
                    0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Iconos.Hecho, null, tint = Colores.Acento, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(2.dp))
                        EnlaceVoz(stringResource(R.string.voz_borrar), Colores.Texto3) { a.borrar(m, nombreBorrar) }
                    }
                    1 -> EnlaceVoz(stringResource(R.string.voz_cancelar), Colores.Texto2) { a.cancelar(m) }
                    else -> BotonDescargar(stringResource(if (d is Descarga.Fallo) R.string.voz_reintentar else R.string.voz_descargar)) { a.descargar(m) }
                }
            }
        }
        AnimatedVisibility(
            d is Descarga.EnCurso || d is Descarga.Comprobando,
            enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()),
            exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave()),
        ) {
            val f = (d as? Descarga.EnCurso)?.let { it.hecho.toFloat() / it.total } ?: 1f
            BarraVoz(f, Modifier.padding(top = 10.dp))
        }
    }
}

// ---------- Piezas ----------

@Composable
private fun TarjetaVoz(contenido: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(16.dp), content = contenido)
}

@Composable
private fun SeparadorVoz() = Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.Borde))

@Composable
private fun EnlaceVoz(texto: String, color: Color, onClick: () -> Unit) {
    Text(
        texto, style = Texto.Subtitulo.copy(fontSize = 14.sp), color = color, maxLines = 1,
        modifier = Modifier.clip(Formas.Pequena).pulsable(onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** Descargar: pastilla suave con su flecha (el botón principal de la página es otro). */
@Composable
private fun BotonDescargar(texto: String, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(Formas.Pastilla).background(Colores.AcentoSuave).pulsable(onClick).padding(start = 9.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IconosVoz.Descargar, null, tint = Colores.Acento, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(texto, style = Texto.Subtitulo.copy(fontSize = 13.sp), color = Colores.Acento, maxLines = 1)
    }
}

/** Barra de progreso fina, que avanza con un muelle. */
@Composable
private fun BarraVoz(fraccion: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraccion.coerceIn(0f, 1f), Muelle.suave(), label = "barra_voz")
    Box(modifier.fillMaxWidth().height(4.dp).clip(Formas.Pastilla).background(Colores.Borde)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(Formas.Pastilla).background(Colores.Acento))
    }
}

/** Elegir entre dos opciones: la marca de la elegida se desliza con un muelle (no salta). */
@Composable
private fun SegmentosVoz(opciones: List<String>, elegida: Int, onElegir: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp).clip(Formas.Pequena).background(Colores.Superficie2).padding(3.dp)) {
        val ancho = maxWidth / opciones.size
        val x by animateDpAsState(ancho * elegida, Muelle.vivo(), label = "segmento_voz")
        Box(Modifier.offset(x = x).width(ancho).fillMaxHeight().clip(RoundedCornerShape(8.dp)).background(Colores.Superficie))
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            opciones.forEachIndexed { i, o ->
                val color by animateColorAsState(if (i == elegida) Colores.Texto else Colores.Texto3, Muelle.suave(), label = "segmento_voz_texto")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, indication = null) { onElegir(i) },
                    contentAlignment = Alignment.Center,
                ) { Text(o, style = Texto.Pequeno, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Confirmar (borrar un modelo, descargar con datos): una hoja que sube con su muelle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaVoz(titulo: String, texto: String, boton: String, peligro: Boolean, onCerrar: () -> Unit, onSi: () -> Unit) {
    val hoja = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val alcance = rememberCoroutineScope()
    fun cerrar(despues: () -> Unit = {}) = alcance.launch { hoja.hide() }.invokeOnCompletion { despues(); onCerrar() }
    ModalBottomSheet(onCerrar, sheetState = hoja, containerColor = Colores.Lateral, contentColor = Colores.Texto) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text(titulo, style = Texto.Titulo, color = Colores.Texto)
            Spacer(Modifier.height(10.dp))
            Text(texto, style = Texto.Cuerpo, color = Colores.Texto2)
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotonTexto(stringResource(R.string.voz_cancelar), Modifier.weight(1f)) { cerrar() }
                Box(
                    Modifier.weight(1f).height(46.dp).clip(Formas.Pequena).background(if (peligro) Colores.Rojo else Colores.Acento).pulsable { cerrar(onSi) },
                    contentAlignment = Alignment.Center,
                ) { Text(boton, style = Texto.Subtitulo, color = if (peligro) Color.White else Colores.SobreAcento) }
            }
        }
    }
}
