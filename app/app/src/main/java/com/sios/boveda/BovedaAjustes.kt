package com.sios.boveda

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.ajustes.BarraSuperior
import com.sios.ajustes.Estilo
import com.sios.ajustes.FilaLista
import com.sios.ajustes.Grupo
import com.sios.ajustes.TituloGrande
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.haceRato
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Una página con barra, título grande y contenido que se desplaza (como las de Ajustes). */
@Composable
private fun PaginaConTitulo(titulo: String, onAtras: () -> Unit, contenido: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding()) {
        BarraSuperior(titulo, scroll, onAtras)
        Column(Modifier.fillMaxSize().verticalScroll(scroll).navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 2.dp, bottom = 40.dp)) {
            Text(titulo, style = TituloGrande, color = Colores.Texto)
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(28.dp)) { contenido() }
        }
    }
}

// ——— Revisión de contraseñas ———

@Composable
internal fun PaginaSalud(nav: Navegacion) {
    val ctx = LocalContext.current
    val entradas by Boveda.entradas.collectAsStateWithLifecycle()
    val filtradas by Boveda.filtradas.collectAsStateWithLifecycle()
    val salud = remember(entradas, filtradas) { Salud.de(entradas, filtradas) }
    var activa by remember { mutableStateOf(Boveda.comprobarFiltradas(ctx)) }
    var explicar by remember { mutableStateOf(false) }
    var comprobando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val alcance = rememberCoroutineScope()
    fun comprobar() {
        if (comprobando) return
        comprobando = true; error = false
        alcance.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Filtradas.comprobar(Boveda.entradas.value.filter { it.tipo == Tipo.CONTRASENA }.map { it.contrasena }) } }
            r.onSuccess { Boveda.guardarFiltradas(it) }.onFailure { error = true }
            comprobando = false
        }
    }
    // Activada y sin comprobar en el último día: se comprueba al entrar.
    LaunchedEffect(activa) { if (activa && System.currentTimeMillis() - Boveda.ultimaComprobacion(ctx) > 86_400_000L) comprobar() }

    PaginaConTitulo(stringResource(R.string.bv_salud), { nav.cerrar() }) {
        // Resumen: lo que hay que revisar, o que todo está bien.
        val bien = salud.problemas == 0
        val color = if (bien) Color(0xFF43A363) else if (salud.filtradas.isNotEmpty()) Colores.Rojo else Color(0xFFD99A2B)
        Row(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(if (bien) IconosBoveda.Escudo else IconosBoveda.EscudoAviso, null, tint = color, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (bien) stringResource(R.string.bv_salud_bien) else pluralStringResource(R.plurals.bv_salud_titulo, salud.problemas, salud.problemas),
                    style = Texto.Subtitulo, color = Colores.Texto,
                )
                Text(stringResource(if (bien) R.string.bv_salud_bien_nota else R.string.bv_salud_nota), style = Texto.Pequeno, color = Colores.Texto3)
            }
        }

        Grupo("filtradas", stringResource(R.string.bv_filtradas), null, Estilo.Tarjeta) {
            InterruptorFila(stringResource(R.string.bv_filtradas_comprobar), activa, stringResource(R.string.bv_filtradas_corto)) { si ->
                if (si) explicar = true else { activa = false; Boveda.ponerComprobarFiltradas(ctx, false) }
            }
            AnimatedVisibility(activa, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val cuando = Boveda.ultimaComprobacion(ctx)
                    Crossfade(Triple(comprobando, error, cuando), animationSpec = Muelle.suave(), label = "comprobando", modifier = Modifier.weight(1f)) { (c, e, w) ->
                        Text(
                            when {
                                c -> stringResource(R.string.bv_filtradas_comprobando)
                                e -> stringResource(R.string.bv_filtradas_error)
                                w > 0 -> stringResource(R.string.bv_filtradas_cuando, haceRato(ctx, w))
                                else -> ""
                            },
                            style = Texto.Pequeno, color = if (e) Colores.Rojo else Colores.Texto3,
                        )
                    }
                    Text(
                        stringResource(R.string.bv_filtradas_ahora), style = Texto.Pequeno.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Colores.Acento,
                        modifier = Modifier.clip(Formas.Pequena).pulsable(::comprobar).padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                }
            }
        }

        if (salud.filtradas.isNotEmpty()) Grupo("lista_filtradas", stringResource(R.string.bv_salud_g_filtradas), stringResource(R.string.bv_salud_g_filtradas_nota), Estilo.Libre) {
            Lista(salud.filtradas.map { it.first }, salud, nav)
        }
        if (salud.repetidas.isNotEmpty()) Grupo("repetidas", stringResource(R.string.bv_salud_g_repetidas), stringResource(R.string.bv_salud_g_repetidas_nota), Estilo.Libre) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { for (g in salud.repetidas) Lista(g, salud, nav) }
        }
        if (salud.debiles.isNotEmpty()) Grupo("debiles", stringResource(R.string.bv_salud_g_debiles), stringResource(R.string.bv_salud_g_debiles_nota), Estilo.Libre) {
            Lista(salud.debiles, salud, nav)
        }
    }
    if (explicar) Hoja({ explicar = false }) {
        Text(stringResource(R.string.bv_filtradas_titulo), style = Texto.Subtitulo, color = Colores.Texto)
        Text(stringResource(R.string.bv_filtradas_explicacion), style = Texto.Cuerpo, color = Colores.Texto2)
        BotonPrimario(stringResource(R.string.bv_filtradas_activar), Modifier.fillMaxWidth()) {
            explicar = false; activa = true; Boveda.ponerComprobarFiltradas(ctx, true); comprobar()
        }
        BotonTexto(stringResource(R.string.bv_cancelar), Modifier.fillMaxWidth()) { explicar = false }
    }
}

@Composable
private fun Lista(l: List<Entrada>, salud: Salud, nav: Navegacion) {
    Column(Modifier.fillMaxWidth()) {
        l.forEachIndexed { i, e -> FilaCuenta(e, salud, i == 0, i == l.lastIndex) { nav.abrir(Destino.Ficha(e.id)) } }
    }
}

// ——— Ajustes de la Bóveda ———

private enum class Importando { NADA, LEYENDO, GUARDANDO }

@Composable
internal fun PaginaAjustes(nav: Navegacion, avisos: Avisador) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    var autocompletar by remember { mutableStateOf(Boveda.esAutocompletar(ctx)) }
    LifecycleResumeEffect(Unit) { autocompletar = Boveda.esAutocompletar(ctx); onPauseOrDispose { } }
    var minutos by remember { mutableIntStateOf(Boveda.minutosCierre(ctx)) }
    var generador by remember { mutableStateOf(false) }
    var borrar by remember { mutableStateOf(false) }
    var exportar by remember { mutableStateOf(false) }
    var contrasenaExport by remember { mutableStateOf<CharArray?>(null) }
    // Importar: los bytes leídos, si piden contraseña, y lo que se va a añadir.
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var pideContrasena by remember { mutableStateOf(false) }
    var leido by remember { mutableStateOf<Importar.Leido?>(null) }
    var estado by remember { mutableStateOf(Importando.NADA) }
    var errorImportar by remember { mutableStateOf<String?>(null) }

    fun error(e: Throwable) = when (e) {
        is Importar.Error.Cifrado -> ctx.getString(R.string.bv_imp_cifrado, e.app)
        is Importar.Error.Contrasena -> ctx.getString(R.string.bv_imp_contrasena_mal)
        else -> ctx.getString(R.string.bv_imp_formato)
    }

    val abrir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        estado = Importando.LEYENDO
        alcance.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Importar.leerUri(ctx, uri) } }
            estado = Importando.NADA
            val b = r.getOrNull() ?: return@launch avisos.decir(ctx.getString(R.string.bv_imp_formato))
            if (Importar.esExportacion(b)) { bytes = b; pideContrasena = true; return@launch }
            runCatching { Importar.leer(b) }.onSuccess { leido = it }.onFailure { errorImportar = error(it) }
        }
    }
    val crear = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val c = contrasenaExport
        contrasenaExport = null
        if (uri == null || c == null) return@rememberLauncherForActivityResult
        estado = Importando.GUARDANDO
        alcance.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Importar.exportar(ctx, uri, c) } }
            c.fill(' ')
            estado = Importando.NADA
            avisos.decir(ctx.getString(if (r.isSuccess) R.string.bv_exp_hecha else R.string.bv_exp_error))
        }
    }

    PaginaConTitulo(stringResource(R.string.bv_ajustes), { nav.cerrar() }) {
        Grupo("autocompletar", stringResource(R.string.bv_aj_autocompletar), stringResource(R.string.bv_aj_autocompletar_nota), Estilo.Tarjeta) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(Formas.Pastilla).background(if (autocompletar) Color(0xFF43A363) else Colores.Texto3))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(if (autocompletar) R.string.bv_aj_autocompletar_si else R.string.bv_aj_autocompletar_no), style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))
            if (!autocompletar) BotonPrimario(stringResource(R.string.bv_aj_usar), Modifier.fillMaxWidth()) {
                if (Boveda.usarParaAutocompletar(ctx) && Boveda.esAutocompletar(ctx)) autocompletar = true
                else runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:" + ctx.packageName))) }
            }
            BotonSecundario(stringResource(R.string.bv_aj_cambiar), Modifier.fillMaxWidth().padding(top = if (autocompletar) 0.dp else 8.dp)) {
                val i = if (android.os.Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_CREDENTIAL_PROVIDER) else Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).setData(Uri.parse("package:" + ctx.packageName))
                runCatching { ctx.startActivity(i) }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
            }
            Text(stringResource(R.string.bv_aj_navegadores), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(top = 12.dp))
        }

        Grupo("cierre", stringResource(R.string.bv_aj_cierre), stringResource(R.string.bv_aj_cierre_nota), Estilo.Libre) {
            val opciones = listOf(0, 1, 5, 15)
            Pestanas(
                opciones.map { if (it == 0) stringResource(R.string.bv_aj_al_salir) else stringResource(R.string.bv_aj_min, it) },
                opciones.indexOf(minutos).coerceAtLeast(0),
                { minutos = opciones[it]; Boveda.ponerMinutosCierre(ctx, minutos) },
            )
        }

        Grupo("herramientas", null, null, Estilo.Lista) {
            FilaLista(stringResource(R.string.bv_salud), stringResource(R.string.bv_aj_salud_nota), separador = true, icono = { IconoFila(IconosBoveda.Escudo) }) { nav.abrir(Destino.Salud) }
            FilaLista(stringResource(R.string.bv_generador), stringResource(R.string.bv_aj_generador_nota), separador = false, icono = { IconoFila(IconosBoveda.Dados) }) { generador = true }
        }

        Grupo("importar", stringResource(R.string.bv_aj_importar), stringResource(R.string.bv_aj_importar_nota), Estilo.Lista) {
            FilaLista(stringResource(R.string.bv_imp_contrasenas), stringResource(R.string.bv_imp_contrasenas_nota), separador = true, icono = { IconoFila(IconosBoveda.Llave) }) {
                abrir.launch(arrayOf("text/*", "application/*", "*/*"))
            }
            FilaLista(stringResource(R.string.bv_imp_codigos), stringResource(R.string.bv_imp_codigos_nota), separador = true, icono = { IconoFila(IconosBoveda.Qr) }) {
                abrir.launch(arrayOf("application/json", "text/*", "*/*"))
            }
            FilaLista(stringResource(R.string.bv_imp_google), stringResource(R.string.bv_imp_google_nota), separador = true, icono = { IconoFila(Iconos.Camara) }) {
                nav.abrir(Destino.Camara(ModoCamara.QR) { r ->
                    val t = (r as? ResultadoCamara.Texto)?.texto.orEmpty()
                    val m = if (Totp.esMigracion(t)) Totp.deMigracion(t) else null
                    if (m != null) leido = Importar.Leido(Importar.codigosDe(m.codigos), m.omitidos)
                    else Totp.deUri(t)?.let { leido = Importar.Leido(Importar.codigosDe(listOf(it)), 0) } ?: avisos.decir(ctx.getString(R.string.bv_qr_no_vale))
                })
            }
            FilaLista(stringResource(R.string.bv_imp_sios), stringResource(R.string.bv_imp_sios_nota), separador = false, icono = { IconoFila(IconosBoveda.Boveda) }) {
                abrir.launch(arrayOf("application/octet-stream", "*/*"))
            }
        }

        Grupo("exportar", stringResource(R.string.bv_aj_exportar), stringResource(R.string.bv_aj_exportar_nota), Estilo.Lista) {
            FilaLista(stringResource(R.string.bv_exp_titulo), stringResource(R.string.bv_exp_fila_nota), separador = false, icono = { IconoFila(IconosBoveda.Bajar) }) { exportar = true }
        }

        Grupo("copia", stringResource(R.string.bv_aj_copia), null, Estilo.Tarjeta) {
            Text(stringResource(R.string.bv_aj_copia_nota), style = Texto.Pequeno, color = Colores.Texto2)
        }

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.bv_aj_borrar), style = Texto.Subtitulo, color = Colores.Rojo,
                modifier = Modifier.clip(Formas.Pequena).pulsable { borrar = true }.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Text(stringResource(R.string.bv_aj_ia), style = Texto.Pequeno, color = Colores.Texto3, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp))
        }
    }

    if (estado != Importando.NADA) Hoja({}) {
        Text(stringResource(if (estado == Importando.GUARDANDO) R.string.bv_exp_guardando else R.string.bv_imp_leyendo), style = Texto.Subtitulo, color = Colores.Texto)
        Text(stringResource(R.string.bv_un_momento), style = Texto.Cuerpo, color = Colores.Texto2)
    }
    if (generador) HojaGenerador(onCerrar = { generador = false }) { g ->
        Boveda.copiar(ctx, g); generador = false; avisos.decir(ctx.getString(R.string.bv_contrasena_copiada))
    }
    if (exportar) HojaContrasena(
        titulo = stringResource(R.string.bv_exp_titulo), nota = stringResource(R.string.bv_exp_nota), repetir = true,
        boton = stringResource(R.string.bv_exp_elegir), onCerrar = { exportar = false },
    ) { c ->
        exportar = false
        contrasenaExport = c
        val fecha = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
        crear.launch("Boveda SIOS $fecha.siosboveda")
    }
    if (pideContrasena) HojaContrasena(
        titulo = stringResource(R.string.bv_imp_sios), nota = stringResource(R.string.bv_imp_contrasena_nota), repetir = false,
        boton = stringResource(R.string.bv_imp_abrir), error = errorImportar, onCerrar = { pideContrasena = false; bytes = null; errorImportar = null },
    ) { c ->
        val b = bytes ?: return@HojaContrasena
        estado = Importando.LEYENDO
        alcance.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Importar.descifrar(b, c) } }
            c.fill(' ')
            estado = Importando.NADA
            r.onSuccess { pideContrasena = false; bytes = null; errorImportar = null; leido = it }.onFailure { errorImportar = error(it) }
        }
    }
    leido?.let { l ->
        val nuevas = remember(l) { Importar.nuevas(l.entradas, Boveda.entradas.value) }
        Hoja({ leido = null }) {
            Text(stringResource(R.string.bv_imp_confirmar), style = Texto.Subtitulo, color = Colores.Texto)
            val ya = l.entradas.size - nuevas.size
            val partes = listOfNotNull(
                pluralStringResource(R.plurals.bv_imp_nuevas, nuevas.size, nuevas.size),
                ya.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.bv_imp_ya, it, it) },
                l.omitidas.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.bv_imp_omitidas, it, it) },
            )
            Text(partes.joinToString(" · "), style = Texto.Cuerpo, color = Colores.Texto2)
            if (nuevas.isNotEmpty()) BotonPrimario(stringResource(R.string.bv_imp_anadir), Modifier.fillMaxWidth()) {
                leido = null
                estado = Importando.GUARDANDO
                alcance.launch {
                    val n = withContext(Dispatchers.IO) { runCatching { Importar.guardar(ctx, l) }.getOrDefault(0) }
                    estado = Importando.NADA
                    avisos.decir(ctx.resources.getQuantityString(R.plurals.bv_importados, n, n))
                }
            }
            Text(stringResource(R.string.bv_imp_borra_archivo), style = Texto.Pequeno, color = Colores.Texto3)
            BotonTexto(stringResource(if (nuevas.isEmpty()) R.string.bv_vale else R.string.bv_cancelar), Modifier.fillMaxWidth()) { leido = null }
        }
    }
    if (errorImportar != null && !pideContrasena) Hoja({ errorImportar = null }) {
        Text(stringResource(R.string.bv_imp_no_se_pudo), style = Texto.Subtitulo, color = Colores.Texto)
        Text(errorImportar.orEmpty(), style = Texto.Cuerpo, color = Colores.Texto2)
        BotonTexto(stringResource(R.string.bv_vale), Modifier.fillMaxWidth()) { errorImportar = null }
    }
    if (borrar) Hoja({ borrar = false }) {
        Text(stringResource(R.string.bv_aj_borrar_titulo), style = Texto.Subtitulo, color = Colores.Texto)
        Text(stringResource(R.string.bv_aj_borrar_nota), style = Texto.Cuerpo, color = Colores.Texto2)
        BotonPeligro(stringResource(R.string.bv_aj_borrar), Modifier.fillMaxWidth()) { borrar = false; Boveda.borrarTodo(ctx) }
        BotonTexto(stringResource(R.string.bv_cancelar), Modifier.fillMaxWidth()) { borrar = false }
    }
}

@Composable
private fun IconoFila(icono: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Colores.AcentoSuave), contentAlignment = Alignment.Center) {
        Icon(icono, null, tint = Colores.Acento, modifier = Modifier.size(18.dp))
    }
}

/** Pedir una contraseña (y repetirla, para exportar): con su fuerza y sin dejar seguir si no vale. */
@Composable
private fun HojaContrasena(
    titulo: String,
    nota: String,
    repetir: Boolean,
    boton: String,
    error: String? = null,
    onCerrar: () -> Unit,
    onListo: (CharArray) -> Unit,
) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    Hoja(onCerrar) {
        Text(titulo, style = Texto.Subtitulo, color = Colores.Texto)
        Text(nota, style = Texto.Cuerpo, color = Colores.Texto2)
        Campo(stringResource(R.string.bv_contrasena), a, { a = it }, secreto = true)
        if (repetir) {
            MedidorFuerza(a)
            Campo(stringResource(R.string.bv_exp_repetir), b, { b = it }, secreto = true)
        }
        val corta = repetir && a.isNotEmpty() && a.length < 10
        val distinta = repetir && b.isNotEmpty() && a != b
        val aviso = error ?: when {
            corta -> stringResource(R.string.bv_exp_corta)
            distinta -> stringResource(R.string.bv_exp_distintas)
            else -> null
        }
        AnimatedVisibility(aviso != null, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
            Text(aviso.orEmpty(), style = Texto.Pequeno, color = Colores.Rojo)
        }
        val vale = a.isNotEmpty() && (!repetir || (a.length >= 10 && a == b))
        val alfa by androidx.compose.animation.core.animateFloatAsState(if (vale) 1f else 0.45f, Muelle.suave(), label = "listo")
        BotonPrimario(boton, Modifier.fillMaxWidth().graphicsLayer { alpha = alfa }) { if (vale) onListo(a.toCharArray()) }
        BotonTexto(stringResource(R.string.bv_cancelar), Modifier.fillMaxWidth(), onCerrar)
    }
}

// ——— Elegir cuenta (desde el autocompletar) ———

@Composable
internal fun HojaElegir(paquete: String?, dominio: String?, onElegir: (Entrada) -> Unit, onCerrar: () -> Unit) {
    Hoja(onCerrar) { ContenidoElegir(paquete, dominio, onElegir, onCerrar) }
}

@Composable
internal fun ContenidoElegir(paquete: String?, dominio: String?, onElegir: (Entrada) -> Unit, onCerrar: () -> Unit) {
    val ctx = LocalContext.current
    val entradas by Boveda.entradas.collectAsStateWithLifecycle()
    var buscar by remember { mutableStateOf("") }
    val q = Generador.plano(buscar.trim())
    val lista = remember(entradas, q) {
        entradas.filter { it.tipo != Tipo.DOCUMENTO && (it.contrasena.isNotEmpty() || it.totp != null) }
            .filter { e -> q.isEmpty() || Generador.plano("${e.nombre} ${e.usuario} ${e.sitios.joinToString(" ") { Sitios.visible(it) }}").contains(q) }
    }
    val donde = dominio ?: paquete?.let { BovedaAutofill.etiquetaApp(ctx, it) }.orEmpty()
    run {
        Text(stringResource(R.string.bv_af_elegir), style = Texto.Subtitulo, color = Colores.Texto)
        if (donde.isNotEmpty()) Text(stringResource(R.string.bv_af_elegir_nota, donde), style = Texto.Pequeno, color = Colores.Texto3)
        CampoBuscar(buscar, stringResource(R.string.bv_buscar)) { buscar = it }
        if (lista.isEmpty()) Text(
            stringResource(R.string.bv_sin_resultados, buscar.trim()), style = Texto.Cuerpo, color = Colores.Texto3, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        )
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            itemsIndexed(lista, key = { _, e -> e.id }) { i, e -> FilaCuenta(e, null, i == 0, i == lista.lastIndex) { onElegir(e) } }
        }
        BotonTexto(stringResource(R.string.bv_cancelar), Modifier.fillMaxWidth(), onCerrar)
    }
}
