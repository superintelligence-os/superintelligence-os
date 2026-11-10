package com.sios.emergencias

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.ui.BotonIcono
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable

/**
 * La pantalla de emergencias de SIOS. En SIOS es el «teclado de emergencia» del sistema
 * (config_emergency_dialer_package): la abre el botón «Emergencia» de la pantalla de bloqueo y el del
 * menú de apagado, sin desbloquear. Tiene el SOS, llamar a emergencias, un teclado que solo marca
 * números de emergencia, la ficha médica y tus contactos de emergencia.
 *
 * También responde a «información de emergencia» (ACTION_EMERGENCY_ASSISTANCE) abriendo la ficha.
 */
class EmergenciasActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FICHA = "ficha"
        private const val ASISTENCIA = "android.telephony.action.EMERGENCY_ASSISTANCE"
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        val inicial = when {
            intent.getBooleanExtra(EXTRA_FICHA, false) || intent.action == ASISTENCIA -> Vista.FICHA
            intent.data?.schemeSpecificPart?.isNotBlank() == true -> Vista.TECLADO
            else -> Vista.INICIO
        }
        val marcado = intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart.orEmpty()
        setContent { TemaSios { Emergencias(inicial, marcado, ::finish) } }
    }
}

internal enum class Vista { INICIO, FICHA, TECLADO }

@Composable
private fun Emergencias(inicial: Vista, marcado: String, onSalir: () -> Unit) {
    val ctx = LocalContext.current
    val cambios by Emergencias.cambios.collectAsState()
    val ficha = remember(cambios) { Emergencias.ficha(ctx) }
    val contactos = remember(cambios) { Emergencias.contactos(ctx) }
    val bloqueado = remember { Emergencias.bloqueado(ctx) }
    val numero = remember { Emergencias.numero(ctx) }
    val pais = remember { Emergencias.nombrePais(Emergencias.pais(ctx)) }
    var vista by rememberSaveable { mutableStateOf(inicial) }
    BackHandler(vista != Vista.INICIO && inicial == Vista.INICIO) { vista = Vista.INICIO }
    val atras = { if (vista == Vista.INICIO || inicial != Vista.INICIO) onSalir() else vista = Vista.INICIO }
    // Llamar es siempre de verdad, también en modo de prueba: es lo que se ha pedido tocando el botón.
    val llamar: (String) -> Unit = { n ->
        if (Emergencias.esEmergencia(ctx, n)) Emergencias.marcarLlamadaEmergencia(ctx)
        Emergencias.llamar(ctx, n, prueba = false)
    }
    AnimatedContent(
        vista,
        transitionSpec = {
            val adelante = targetState.ordinal > initialState.ordinal
            (fadeIn(Muelle.suave()) + slideInHorizontally(Muelle.suave()) { if (adelante) it / 4 else -it / 4 }) togetherWith
                (fadeOut(Muelle.suave()) + slideOutHorizontally(Muelle.suave()) { if (adelante) -it / 4 else it / 4 })
        },
        label = "vista",
    ) { v ->
        when (v) {
            Vista.INICIO -> PantallaEmergencias(
                numero, pais, ficha, contactos, bloqueado,
                onAtras = atras,
                onSos = { Emergencias.abrirSos(ctx, prueba = Emergencias.modoPrueba(ctx)) },
                onLlamar = { llamar(numero) },
                onTeclado = { vista = Vista.TECLADO },
                onFicha = { vista = Vista.FICHA },
                onContacto = { llamar(it.telefono) },
                onEditar = { abrirAjustes(ctx) },
                onSistema = { tecladoDelSistema(ctx) },
            )
            Vista.FICHA -> PantallaFicha(ficha, contactos, bloqueado, onAtras = atras, onContacto = { llamar(it.telefono) }, onEditar = { abrirAjustes(ctx) })
            Vista.TECLADO -> TecladoEmergencia(marcado, numero, { Emergencias.esEmergencia(ctx, it) || it in contactos.map { c -> c.telefono.filter(Char::isDigit) } }, onAtras = atras, onLlamar = llamar)
        }
    }
}

private fun abrirAjustes(ctx: Context) {
    // Solo con el móvil desbloqueado (si no, Android pide el PIN antes).
    ctx.startActivity(Intent(ctx, com.sios.ajustes.AjustesActivity::class.java).putExtra("seccion", "emergencias").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** El teclado de emergencias de Android, por si se prefiere (o si algo falla aquí). */
private fun tecladoDelSistema(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Emergencias.ACCION_TECLADO_EMERGENCIA).setComponent(ComponentName("com.android.phone", "com.android.phone.EmergencyDialer"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** ¿Se puede enseñar la ficha (y los contactos) ahora? Bloqueado, solo si el dueño lo ha permitido. */
private fun visible(f: Emergencias.Ficha, bloqueado: Boolean) = !bloqueado || f.enBloqueo

@Composable
internal fun PantallaEmergencias(
    numero: String,
    pais: String,
    ficha: Emergencias.Ficha,
    contactos: List<Emergencias.Contacto>,
    bloqueado: Boolean,
    onAtras: () -> Unit,
    onSos: () -> Unit,
    onLlamar: () -> Unit,
    onTeclado: () -> Unit,
    onFicha: () -> Unit,
    onContacto: (Emergencias.Contacto) -> Unit,
    onEditar: () -> Unit,
    onSistema: () -> Unit,
) {
    val ver = visible(ficha, bloqueado)
    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            BotonIcono(Iconos.Atras, stringResource(R.string.comun_atras), Modifier.align(Alignment.CenterStart).padding(start = 8.dp), onClick = onAtras)
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.em_titulo), style = Texto.Titulo.copy(fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.6).sp), color = Colores.Texto)
            Text(
                if (pais.isBlank()) stringResource(R.string.em_numero_solo, numero) else stringResource(R.string.em_numero_pais, numero, pais),
                style = Texto.Cuerpo, color = Colores.Texto3,
            )
            Spacer(Modifier.height(20.dp))
            BotonSos(numero, onSos)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Baldosa(Iconos.Llamar, stringResource(R.string.em_llamar_a, numero), stringResource(R.string.em_llamar_nota), Modifier.weight(1f), Rojo.Verde, onLlamar)
                Baldosa(Iconos.Teclado, stringResource(R.string.em_teclado), stringResource(R.string.em_teclado_nota), Modifier.weight(1f), Colores.Texto2, onTeclado)
            }
            if (ver && !ficha.vacia) {
                Spacer(Modifier.height(24.dp))
                Etiqueta(stringResource(R.string.em_ficha_titulo))
                ResumenFicha(ficha, onFicha)
            }
            if (ver && contactos.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Etiqueta(stringResource(R.string.em_contactos_titulo))
                ListaContactos(contactos, onContacto)
            }
            if (bloqueado && !ver) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.em_ficha_oculta), style = Texto.Pequeno, color = Colores.Texto3)
            }
            if (!bloqueado && (ficha.vacia || contactos.isEmpty())) {
                Spacer(Modifier.height(24.dp))
                Row(
                    Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).pulsable(onEditar).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(IconosEm.Corazon, null, tint = Rojo.Boton, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.em_completar_titulo), style = Texto.Subtitulo, color = Colores.Texto)
                        Text(stringResource(R.string.em_completar_texto), style = Texto.Pequeno, color = Colores.Texto3)
                    }
                    Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            BotonTexto(stringResource(R.string.em_teclado_sistema), Modifier.fillMaxWidth(), onClick = onSistema)
        }
    }
}

@Composable
private fun Etiqueta(texto: String) =
    Text(texto.uppercase(), style = Texto.Etiqueta, color = Colores.Texto3, modifier = Modifier.padding(bottom = 8.dp))

/** El SOS: una tarjeta roja grande, lo primero que se ve. */
@Composable
private fun BotonSos(numero: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 148.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
            .background(Rojo.Boton).pulsable(onClick).padding(horizontal = 22.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("SOS", style = Texto.Titulo.copy(fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = 1.sp), color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.em_sos_boton_texto, numero), style = Texto.Cuerpo.copy(lineHeight = 21.sp), color = Color.White.copy(alpha = 0.92f))
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(72.dp).clip(Formas.Pastilla).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(IconosEm.Sos, null, tint = Color.White, modifier = Modifier.size(38.dp))
        }
    }
}

@Composable
private fun Baldosa(icono: ImageVector, titulo: String, nota: String, modifier: Modifier, color: Color, onClick: () -> Unit) {
    Column(modifier.clip(Formas.Tarjeta).background(Colores.Superficie).pulsable(onClick).padding(16.dp)) {
        Box(Modifier.size(40.dp).clip(Formas.Pastilla).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icono, null, tint = color, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(titulo, style = Texto.Subtitulo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(nota, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Lo más importante de la ficha a primera vista: quién eres, grupo sanguíneo, alergias y medicación. */
@Composable
private fun ResumenFicha(f: Emergencias.Ficha, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).pulsable(onClick).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(Formas.Pastilla).background(Rojo.Suave), contentAlignment = Alignment.Center) {
                Icon(IconosEm.Corazon, null, tint = Rojo.Boton, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(f.nombre.ifBlank { stringResource(R.string.em_ficha_titulo) }, style = Texto.Subtitulo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (f.nacimiento.isNotBlank()) Text(f.nacimiento, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1)
            }
            if (f.sangre.isNotBlank()) GrupoSangre(f.sangre)
        }
        val lineas = listOf(R.string.em_f_alergias to f.alergias, R.string.em_f_medicacion to f.medicacion, R.string.em_f_afecciones to f.afecciones).filter { it.second.isNotBlank() }
        lineas.forEach { (etiqueta, valor) ->
            Spacer(Modifier.height(12.dp))
            Text(stringResource(etiqueta), style = Texto.Pequeno, color = Colores.Texto3)
            Text(valor, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.em_ver_ficha_entera), style = Texto.Pequeno, color = Rojo.Boton, modifier = Modifier.weight(1f))
            Icon(Iconos.Derecha, null, tint = Rojo.Boton, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun GrupoSangre(g: String) {
    Row(
        Modifier.clip(Formas.Pastilla).background(Rojo.Suave).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IconosEm.Gota, null, tint = Rojo.Boton, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(g, style = Texto.Subtitulo, color = Rojo.Boton)
    }
}

@Composable
private fun ListaContactos(contactos: List<Emergencias.Contacto>, onContacto: (Emergencias.Contacto) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie)) {
        contactos.forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().pulsable { onContacto(c) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(Formas.Pastilla).background(Colores.Superficie2), contentAlignment = Alignment.Center) {
                    Text(c.nombre.firstOrNull()?.uppercase() ?: "·", style = Texto.Subtitulo, color = Colores.Texto)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.nombre.ifBlank { c.telefono }, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(com.sios.sistema.Llamadas.formatear(c.telefono), style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1)
                }
                Box(Modifier.size(40.dp).clip(Formas.Pastilla).background(Rojo.Verde.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Icon(Iconos.Llamar, stringResource(R.string.tel_llamar), tint = Rojo.Verde, modifier = Modifier.size(18.dp))
                }
            }
            if (i < contactos.lastIndex) Box(Modifier.padding(start = 68.dp).fillMaxWidth().height(1.dp).background(Colores.Borde))
        }
    }
}

/** La ficha médica entera, para quien te esté ayudando: grande y legible. */
@Composable
internal fun PantallaFicha(
    f: Emergencias.Ficha,
    contactos: List<Emergencias.Contacto>,
    bloqueado: Boolean,
    onAtras: () -> Unit,
    onContacto: (Emergencias.Contacto) -> Unit,
    onEditar: () -> Unit,
) {
    val ver = visible(f, bloqueado)
    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            BotonIcono(Iconos.Atras, stringResource(R.string.comun_atras), Modifier.align(Alignment.CenterStart).padding(start = 8.dp), onClick = onAtras)
            if (!bloqueado) BotonIcono(Iconos.Lapiz, stringResource(R.string.em_editar), Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), color = Colores.Texto2, onClick = onEditar)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(Formas.Pastilla).background(Rojo.Suave), contentAlignment = Alignment.Center) {
                    Icon(IconosEm.Corazon, null, tint = Rojo.Boton, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    val conNombre = ver && f.nombre.isNotBlank()
                    if (conNombre || (ver && !f.vacia)) Text(stringResource(R.string.em_ficha_titulo).uppercase(), style = Texto.Etiqueta, color = Colores.Texto3)
                    Text(
                        if (conNombre) f.nombre else if (ver && !f.vacia) stringResource(R.string.em_f_sin_nombre) else stringResource(R.string.em_ficha_titulo),
                        style = Texto.Titulo.copy(fontSize = 26.sp, lineHeight = 32.sp), color = Colores.Texto,
                    )
                }
            }
            if (!ver) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.em_ficha_oculta), style = Texto.Cuerpo, color = Colores.Texto2)
                return@Column
            }
            if (f.vacia) {
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.em_ficha_vacia), style = Texto.Cuerpo, color = Colores.Texto2)
            }
            if (f.sangre.isNotBlank() || f.nacimiento.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (f.sangre.isNotBlank()) Dato(stringResource(R.string.em_f_sangre), f.sangre, Modifier.weight(1f), rojo = true)
                    if (f.nacimiento.isNotBlank()) Dato(stringResource(R.string.em_f_nacimiento), f.nacimiento, Modifier.weight(1f))
                }
            }
            listOf(
                R.string.em_f_alergias to f.alergias, R.string.em_f_medicacion to f.medicacion,
                R.string.em_f_afecciones to f.afecciones, R.string.em_f_notas to f.notas,
            ).filter { it.second.isNotBlank() }.forEach { (etiqueta, valor) ->
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(16.dp)) {
                    Text(stringResource(etiqueta), style = Texto.Pequeno, color = Colores.Texto3)
                    Spacer(Modifier.height(2.dp))
                    Text(valor, style = Texto.Cuerpo.copy(fontSize = 17.sp, lineHeight = 25.sp), color = Colores.Texto)
                }
            }
            if (f.donante.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Dato(stringResource(R.string.em_f_donante), stringResource(if (f.donante == "si") R.string.em_si else R.string.em_no), Modifier.fillMaxWidth())
            }
            if (contactos.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Etiqueta(stringResource(R.string.em_contactos_titulo))
                ListaContactos(contactos, onContacto)
            }
        }
    }
}

@Composable
private fun Dato(etiqueta: String, valor: String, modifier: Modifier, rojo: Boolean = false) {
    Column(modifier.clip(Formas.Tarjeta).background(if (rojo) Rojo.Suave else Colores.Superficie).padding(16.dp)) {
        Text(etiqueta, style = Texto.Pequeno, color = if (rojo) Rojo.Boton else Colores.Texto3)
        Spacer(Modifier.height(2.dp))
        Text(valor, style = Texto.Titulo.copy(fontSize = 22.sp), color = if (rojo) Rojo.Boton else Colores.Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Teclado de emergencia: solo deja llamar a números de emergencia (los que reconoce el sistema) y a
 * tus contactos de emergencia. Así, con el móvil bloqueado, nadie puede llamar a otro sitio.
 */
@Composable
internal fun TecladoEmergencia(inicial: String, numeroPais: String, permitido: (String) -> Boolean, onAtras: () -> Unit, onLlamar: (String) -> Unit) {
    var numero by rememberSaveable { mutableStateOf(inicial.filter { it.isDigit() || it == '+' }) }
    val vale = numero.isNotBlank() && permitido(numero)
    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            BotonIcono(Iconos.Atras, stringResource(R.string.comun_atras), Modifier.align(Alignment.CenterStart).padding(start = 8.dp), onClick = onAtras)
            Text(stringResource(R.string.em_teclado), style = Texto.Subtitulo, color = Colores.Texto, modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.fillMaxSize().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
            Text(
                numero.ifEmpty { " " }, style = Texto.Titulo.copy(fontSize = 40.sp, lineHeight = 46.sp), color = Colores.Texto,
                textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
            val nota = when {
                numero.isEmpty() -> stringResource(R.string.em_teclado_pista, numeroPais)
                vale -> stringResource(R.string.em_teclado_vale)
                else -> stringResource(R.string.em_teclado_no_vale)
            }
            val colorNota by animateColorAsState(if (numero.isNotEmpty() && !vale) Rojo.Boton else Colores.Texto3, Muelle.suave(), label = "nota")
            Text(nota, style = Texto.Pequeno, color = colorNota, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp).heightIn(min = 36.dp))
            Spacer(Modifier.height(18.dp))
            val filas = listOf("123", "456", "789", " 0⌫")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (fila in filas) Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    for (c in fila) {
                        if (c == ' ') { Spacer(Modifier.size(76.dp)); continue }
                        Box(
                            Modifier.size(76.dp).clip(Formas.Pastilla).background(if (c == '⌫') Color.Transparent else Colores.Superficie)
                                .pulsable {
                                    numero = when (c) {
                                        '⌫' -> numero.dropLast(1)
                                        else -> (numero + c).take(15)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (c == '⌫') Icon(Iconos.Borrar, stringResource(R.string.tel_borrar), tint = if (numero.isEmpty()) Colores.Texto3.copy(alpha = 0.4f) else Colores.Texto2, modifier = Modifier.size(26.dp))
                            else Text(c.toString(), style = Texto.Titulo.copy(fontSize = 28.sp), color = Colores.Texto)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            val fondo by animateColorAsState(if (vale) Rojo.Verde else Colores.Superficie2, Muelle.suave(), label = "llamar")
            Box(
                Modifier.size(76.dp).clip(Formas.Pastilla).background(fondo).pulsable { if (vale) onLlamar(numero) },
                contentAlignment = Alignment.Center,
            ) { Icon(Iconos.Llamar, stringResource(R.string.tel_llamar), tint = if (vale) Color.White else Colores.Texto3, modifier = Modifier.size(30.dp)) }
        }
    }
}

/** En la app de teléfono: «Emergencias» siempre a mano, arriba a la derecha. */
@Composable
internal fun PastillaEmergencias() {
    val ctx = LocalContext.current
    Row(
        Modifier.clip(Formas.Pastilla).background(Rojo.Suave)
            .pulsable { ctx.startActivity(Intent(ctx, EmergenciasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(IconosEm.Sos, null, tint = Rojo.Boton, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.em_tel_emergencias), style = Texto.Pequeno, color = Rojo.Boton, maxLines = 1)
    }
}
