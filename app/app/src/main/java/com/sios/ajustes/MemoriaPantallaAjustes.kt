package com.sios.ajustes

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.sistema.Manos
import com.sios.sistema.MemoriaPantalla
import com.sios.sistema.MemoriaPantallaBase
import com.sios.sistema.MemoriaPantallaModelo
import com.sios.sistema.MemoriaPantallaModelo.Descarga
import com.sios.sistema.MemoriaPantallaPrivacidad
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.InterruptorSios
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Memoria de pantalla en Ajustes de SIOS (dentro de «Memoria del móvil»). Desactivada: qué es y el
 * botón para conocerla (la activación está al final de su presentación). Activa: pausa, buscar,
 * cuánto dura, miniaturas, el modelo de imágenes, las apps excluidas, borrar todo y desactivar.
 */
@Composable
internal fun SeccionMemoriaPantalla(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val estado by MemoriaPantalla.estado.collectAsStateWithLifecycle()
    // Al volver de la presentación (o del recuadro de ajustes rápidos), el estado se vuelve a leer.
    LifecycleResumeEffect(Unit) { MemoriaPantalla.leer(ctx); onPauseOrDispose { } }
    seccion(stringResource(R.string.mp_titulo), stringResource(R.string.mp_nota)) {
        AnimatedContent(
            estado.activa,
            transitionSpec = { (fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave())).using(SizeTransform(clip = false) { _, _ -> Muelle.suave() }) },
            label = "memoria_pantalla",
        ) { activa ->
            if (activa) Activa(estado) else Apagada()
        }
    }
}

@Composable
private fun Apagada() {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(Formas.Pequena).background(Colores.Superficie2), contentAlignment = Alignment.Center) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_memoria_pantalla), null, tint = Colores.Texto2, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.mp_apagada_nota), style = Texto.Pequeno, color = Colores.Texto2, modifier = Modifier.weight(1f))
        }
        BotonPrimario(stringResource(R.string.mp_conocer), Modifier.fillMaxWidth()) {
            ctx.startActivity(Intent(ctx, MemoriaPantallaPresentacionActivity::class.java))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Activa(estado: MemoriaPantalla.Estado) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    var refresco by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresco++; onPauseOrDispose { } }
    // Cuánto hay guardado y cuánto ocupa (la base solo se abre con el móvil desbloqueado: aquí lo está).
    val datos by produceState<Pair<MemoriaPantallaBase.Resumen?, Long>?>(null, refresco, estado.dias) {
        value = withContext(Dispatchers.IO) { runCatching { MemoriaPantallaBase.resumen(ctx) to MemoriaPantallaBase.tamano(ctx) }.getOrNull() }
    }
    var confirmar by remember { mutableStateOf<String?>(null) }
    var apps by remember { mutableStateOf(false) }
    val manos = remember(refresco) { Manos.instancia != null }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Sin el servicio de accesibilidad no se puede leer la pantalla: se dice y se arregla.
        AnimatedVisibility(!manos, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
            AvisoMp(stringResource(R.string.mp_sin_manos), stringResource(R.string.mp_sin_manos_arreglar)) {
                Manos.asegurar(ctx)
                if (Manos.instancia == null) ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                refresco++
            }
        }

        val nota = when {
            estado.pausada -> stringResource(R.string.mp_estado_pausada)
            datos == null -> stringResource(R.string.mp_cargando)
            else -> {
                val n = datos?.first?.momentos ?: 0
                pluralStringResource(R.plurals.mp_momentos, n, n) + " · " +
                    stringResource(R.string.mp_ocupa, Formatter.formatShortFileSize(ctx, datos?.second ?: 0L))
            }
        }
        FilaMp(stringResource(R.string.mp_recordando), nota) {
            InterruptorSios(!estado.pausada) { MemoriaPantalla.pausar(ctx, !it) }
        }

        BotonSecundario(stringResource(R.string.mp_buscar), Modifier.fillMaxWidth()) {
            ctx.startActivity(Intent(ctx, MemoriaPantallaBuscarActivity::class.java))
        }

        SeparadorMp()

        Column {
            Text(stringResource(R.string.mp_dias_titulo), style = Texto.Cuerpo, color = Colores.Texto)
            Text(stringResource(R.string.mp_dias_nota), style = Texto.Pequeno, color = Colores.Texto3)
            Spacer(Modifier.height(10.dp))
            SegmentosMp(
                listOf(stringResource(R.string.mp_dias_7), stringResource(R.string.mp_dias_30)),
                if (estado.dias >= 30) 1 else 0,
            ) { MemoriaPantalla.ponerDias(ctx, if (it == 1) 30 else 7) }
        }

        FilaMp(stringResource(R.string.mp_miniaturas_titulo), stringResource(R.string.mp_miniaturas_nota)) {
            InterruptorSios(estado.miniaturas) { MemoriaPantalla.ponerMiniaturas(ctx, it) }
        }

        ModeloImagenes()

        SeparadorMp()

        FilaPulsable(
            stringResource(R.string.mp_excluidas_titulo),
            if (estado.excluidas.isEmpty()) stringResource(R.string.mp_excluidas_ninguna)
            else pluralStringResource(R.plurals.mp_excluidas_cuenta, estado.excluidas.size, estado.excluidas.size),
        ) { apps = true }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BotonSecundario(stringResource(R.string.mp_borrar_todo), Modifier.weight(1f)) { confirmar = "borrar" }
            BotonTexto(stringResource(R.string.mp_desactivar), Modifier.weight(1f)) { confirmar = "desactivar" }
        }
    }

    if (apps) HojaApps(estado.excluidas) { apps = false }

    confirmar?.let { que ->
        val borrar = que == "borrar"
        val hecho = stringResource(R.string.mp_borrado)
        ConfirmacionMp(
            titulo = stringResource(if (borrar) R.string.mp_borrar_titulo else R.string.mp_desactivar_titulo),
            texto = stringResource(if (borrar) R.string.mp_borrar_texto else R.string.mp_desactivar_texto),
            boton = stringResource(if (borrar) R.string.mp_borrar_todo else R.string.mp_desactivar),
            onCerrar = { confirmar = null },
        ) {
            if (borrar) MemoriaPantalla.borrarTodo(ctx) {
                alcance.launch {
                    refresco++
                    android.widget.Toast.makeText(ctx, hecho, android.widget.Toast.LENGTH_SHORT).show()
                }
            } else MemoriaPantalla.desactivar(ctx)
        }
    }
}

/** El modelo de imágenes: listo, descargándose (con su barra) o por descargar (con su tamaño). */
@Composable
private fun ModeloImagenes() {
    val ctx = LocalContext.current
    val descarga by MemoriaPantallaModelo.descarga.collectAsStateWithLifecycle()
    val listo = remember(descarga) { MemoriaPantallaModelo.listo(ctx) }
    val tamano = Formatter.formatShortFileSize(ctx, MemoriaPantallaModelo.TAMANO)
    val d = descarga
    Column {
        FilaMp(
            stringResource(R.string.mp_imagenes_titulo),
            when {
                listo -> stringResource(R.string.mp_imagenes_lista, tamano)
                d is Descarga.EnCurso -> stringResource(R.string.mp_descargando, Formatter.formatShortFileSize(ctx, d.hecho), tamano)
                d is Descarga.Comprobando -> stringResource(R.string.mp_comprobando)
                d is Descarga.Fallo -> stringResource(if (d.sinRed) R.string.mp_descarga_sin_red else R.string.mp_descarga_fallo)
                else -> stringResource(R.string.mp_imagenes_falta, tamano)
            },
        ) {
            AnimatedContent(
                when { listo -> 0; d is Descarga.EnCurso || d is Descarga.Comprobando -> 1; else -> 2 },
                transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) },
                label = "modelo",
            ) { caso ->
                when (caso) {
                    0 -> Icon(Iconos.Hecho, null, tint = Colores.Acento, modifier = Modifier.size(22.dp))
                    1 -> EnlaceTexto(stringResource(R.string.mp_cancelar)) { MemoriaPantallaModelo.cancelar() }
                    else -> EnlaceTexto(stringResource(if (d is Descarga.Fallo) R.string.mp_reintentar else R.string.mp_descargar_corto)) {
                        MemoriaPantallaModelo.descargar(ctx)
                    }
                }
            }
        }
        AnimatedVisibility(
            d is Descarga.EnCurso || d is Descarga.Comprobando,
            enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()),
            exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave()),
        ) {
            val f = (d as? Descarga.EnCurso)?.let { it.hecho.toFloat() / it.total } ?: 1f
            BarraMp(f, Modifier.padding(top = 10.dp))
        }
    }
}

// ---------- Piezas ----------

/** Fila con título, nota y algo al final (interruptor, enlace): como las demás de Ajustes. */
@Composable
internal fun FilaMp(titulo: String, nota: String?, final: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, style = Texto.Cuerpo, color = Colores.Texto)
            if (nota != null) Text(nota, style = Texto.Pequeno, color = Colores.Texto3)
        }
        Spacer(Modifier.width(12.dp))
        final()
    }
}

@Composable
private fun FilaPulsable(titulo: String, nota: String, onClick: () -> Unit) {
    val interaccion = remember { MutableInteractionSource() }
    val pulsado by interaccion.collectIsPressedAsState()
    val alfa by animateFloatAsState(if (pulsado) 0.55f else 1f, Muelle.suave(), label = "fila")
    Row(
        Modifier.fillMaxWidth().clickable(interaccion, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
            Text(titulo, style = Texto.Cuerpo, color = Colores.Texto.copy(alpha = alfa))
            Text(nota, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun EnlaceTexto(texto: String, onClick: () -> Unit) {
    Text(
        texto, style = Texto.Subtitulo.copy(fontSize = 14.sp), color = Colores.Acento,
        modifier = Modifier.clip(Formas.Pequena).pulsable(onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

@Composable
private fun SeparadorMp() = Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.Borde))

/** Barra de progreso fina, que avanza con un muelle. */
@Composable
internal fun BarraMp(fraccion: Float, modifier: Modifier = Modifier) {
    val f by animateFloatAsState(fraccion.coerceIn(0f, 1f), Muelle.suave(), label = "barra")
    Box(modifier.fillMaxWidth().height(4.dp).clip(Formas.Pastilla).background(Colores.Borde)) {
        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(Formas.Pastilla).background(Colores.Acento))
    }
}

/** Aviso destacado (algo que falta), con su botón. */
@Composable
internal fun AvisoMp(texto: String, boton: String?, onBoton: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().clip(Formas.Pequena).background(Colores.AcentoSuave).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(texto, style = Texto.Pequeno, color = Colores.Texto)
        if (boton != null) Text(
            boton, style = Texto.Subtitulo.copy(fontSize = 14.sp), color = Colores.Acento,
            modifier = Modifier.clip(Formas.Pequena).pulsable(onBoton).padding(vertical = 2.dp),
        )
    }
}

/**
 * Elegir entre dos o tres opciones: la marca de la elegida se desliza con un muelle (no salta).
 */
@Composable
internal fun SegmentosMp(opciones: List<String>, elegida: Int, onElegir: (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp).clip(Formas.Pequena).background(Colores.Superficie2).padding(3.dp)) {
        val ancho: Dp = maxWidth / opciones.size
        val x by androidx.compose.animation.core.animateDpAsState(ancho * elegida, Muelle.vivo(), label = "segmento")
        Box(Modifier.offset(x = x).width(ancho).fillMaxHeight().clip(RoundedPequena).background(Colores.Superficie))
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            opciones.forEachIndexed { i, o ->
                val color by animateColorAsState(if (i == elegida) Colores.Texto else Colores.Texto3, Muelle.suave(), label = "segmento_texto")
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, indication = null) { onElegir(i) },
                    contentAlignment = Alignment.Center,
                ) { Text(o, style = Texto.Pequeno, color = color, maxLines = 1) }
            }
        }
    }
}

private val RoundedPequena = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)

/** Botón de una acción que no tiene vuelta atrás (borrar, desactivar): en rojo. */
@Composable
internal fun BotonPeligroMp(texto: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(46.dp).clip(Formas.Pequena).background(Colores.Rojo).pulsable(onClick),
        contentAlignment = Alignment.Center,
    ) { Text(texto, style = Texto.Subtitulo, color = Color.White) }
}

/** Confirmar algo que no se puede deshacer: una hoja que sube con su muelle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmacionMp(titulo: String, texto: String, boton: String, onCerrar: () -> Unit, onSi: () -> Unit) {
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
                BotonTexto(stringResource(R.string.mp_cancelar), Modifier.weight(1f)) { cerrar() }
                BotonPeligroMp(boton, Modifier.weight(1f)) { cerrar(onSi) }
            }
        }
    }
}

private class AppInstalada(val paquete: String, val nombre: String, val icono: ImageBitmap?, val siempreFuera: Boolean)

/** Las apps del móvil, para elegir cuáles no se guardan. Las que nunca se guardan, ya marcadas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaApps(excluidas: Set<String>, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val densidad = LocalDensity.current
    val lado = with(densidad) { 32.dp.roundToPx() }
    val lista by produceState<List<AppInstalada>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }.distinct().filter { it != ctx.packageName }
                .map { p ->
                    val info = runCatching { pm.getApplicationInfo(p, 0) }.getOrNull()
                    AppInstalada(
                        p,
                        info?.let { pm.getApplicationLabel(it).toString() } ?: p,
                        info?.let { runCatching { pm.getApplicationIcon(it).toBitmap(lado, lado).asImageBitmap() }.getOrNull() },
                        MemoriaPantallaPrivacidad.fuera(ctx, p),
                    )
                }
                .sortedBy { it.nombre.lowercase() }
        }
    }
    ModalBottomSheet(onCerrar, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Colores.Lateral, contentColor = Colores.Texto) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 12.dp)) {
            Text(stringResource(R.string.mp_excluidas_titulo), style = Texto.Titulo, color = Colores.Texto)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.mp_siempre_fuera), style = Texto.Pequeno, color = Colores.Texto3)
            Spacer(Modifier.height(14.dp))
            val l = lista
            AnimatedContent(l == null, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "apps") { cargando ->
                if (cargando || l == null) {
                    Text(stringResource(R.string.mp_cargando), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(vertical = 24.dp))
                } else LazyColumn(Modifier.heightIn(max = 520.dp)) {
                    items(l, key = { it.paquete }) { a ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(32.dp).clip(Formas.Pequena).background(Colores.Superficie2)) {
                                a.icono?.let { Image(it, null, Modifier.size(32.dp)) }
                            }
                            Spacer(Modifier.width(14.dp))
                            Text(a.nombre, style = Texto.Cuerpo, color = if (a.siempreFuera) Colores.Texto3 else Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            if (a.siempreFuera) Text(stringResource(R.string.mp_siempre_fuera_app), style = Texto.Pequeno, color = Colores.Texto3)
                            else InterruptorSios(a.paquete in excluidas) { MemoriaPantalla.excluir(ctx, a.paquete, it) }
                        }
                    }
                }
            }
        }
    }
}
