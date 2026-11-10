package com.sios.emergencias

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.ajustes.Estilo
import com.sios.ajustes.Grupo
import com.sios.ui.BotonIcono
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

/**
 * Ajustes de SIOS › Emergencias: el SOS (el gesto, la cuenta atrás, el número), tus contactos de
 * emergencia, la ficha médica, la detección de caídas, compartir el viaje y el modo de prueba.
 */
@Composable
internal fun AjustesEmergencias() {
    val ctx = LocalContext.current
    val cambios by Emergencias.cambios.collectAsState()
    val estado by SosServicio.estado.collectAsState()
    var refresco by remember { mutableStateOf(0) }
    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresco++ }
    fun pedirPermisos() {
        val faltan = listOf(Manifest.permission.SEND_SMS, Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { ctx.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (faltan.isNotEmpty()) permisos.launch(faltan.toTypedArray())
    }
    val elegir = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val c = r.data?.data?.let { contactoDe(ctx, it) } ?: return@rememberLauncherForActivityResult
        val lista = Emergencias.contactos(ctx)
        if (lista.none { it.telefono.filter(Char::isDigit).takeLast(9) == c.telefono.filter(Char::isDigit).takeLast(9) }) {
            Emergencias.ponerContactos(ctx, lista + c)
        }
        pedirPermisos()
    }
    fun anadir() = elegir.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))

    // Lee de nuevo cuando cambia algo (aquí o en otra pantalla) o al volver de pedir permisos.
    val clave = cambios + refresco * 1000
    SeccionSos(ctx, clave)
    SeccionContactos(ctx, clave, onAnadir = ::anadir, onPermisos = ::pedirPermisos)
    SeccionFicha(ctx)
    SeccionCaidas(ctx, clave)
    SeccionViaje(ctx, clave, estado, onOtro = ::anadir)
    SeccionPrueba(ctx, clave)
}

/** Nombre y teléfono del contacto elegido en la app de Contactos. */
private fun contactoDe(ctx: Context, uri: android.net.Uri): Emergencias.Contacto? = runCatching {
    ctx.contentResolver.query(
        uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null,
    )?.use { c -> if (c.moveToFirst()) Emergencias.Contacto(c.getString(0).orEmpty(), c.getString(1).orEmpty()) else null }
}.getOrNull()?.takeIf { it.telefono.isNotBlank() }

@Composable
private fun Interruptor(activo: Boolean, habilitado: Boolean = true, onCambio: (Boolean) -> Unit) {
    // Sin poder cambiarlo (fuera de SIOS): el mismo interruptor, apagado a la vista.
    if (habilitado) InterruptorSios(activo, onCambio)
    else Box(Modifier.alpha(0.4f)) { InterruptorSios(activo) {} }
}

@Composable
private fun Subtitulo(texto: String) = Text(texto, style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.padding(bottom = 8.dp))

@Composable
private fun Espacio() = Spacer(Modifier.height(18.dp))

// ——— SOS ———

@Composable
private fun SeccionSos(ctx: Context, clave: Int) {
    val gesto = remember(clave) { Emergencias.gestoActivo(ctx) }
    val puede = remember { Emergencias.puedeCambiarGesto(ctx) }
    val cuenta = remember(clave) { Emergencias.cuentaAtras(ctx) }
    val sonido = remember(clave) { Emergencias.sonido(ctx) }
    val minutos = remember(clave) { Emergencias.minutosCompartir(ctx) }
    val propio = remember(clave) { Emergencias.numeroPropio(ctx) }
    val numero = remember(clave) { Emergencias.numero(ctx) }
    val pais = remember { Emergencias.pais(ctx) }
    Grupo("sos", stringResource(R.string.em_g_sos), stringResource(R.string.em_g_sos_nota), Estilo.Tarjeta) {
        FilaAjuste(
            stringResource(R.string.em_gesto),
            stringResource(if (puede) R.string.em_gesto_nota else R.string.em_gesto_no_disponible),
        ) { Interruptor(gesto, puede) { Emergencias.ponerGesto(ctx, it) } }
        Espacio()
        Subtitulo(stringResource(R.string.em_cuenta))
        Pastillas(listOf(3, 5, 10).map { "$it" to stringResource(R.string.em_segundos, it) }, cuenta.toString()) { Emergencias.ponerCuentaAtras(ctx, it.toInt()) }
        Espacio()
        FilaAjuste(stringResource(R.string.em_sonido), stringResource(R.string.em_sonido_nota)) { Interruptor(sonido) { Emergencias.ponerSonido(ctx, it) } }
        Espacio()
        NumeroEmergencias(ctx, numero, propio, pais)
        Espacio()
        Subtitulo(stringResource(R.string.em_compartir_despues))
        Pastillas(listOf(30, 60, 120).map { "$it" to SosServicio.duracion(ctx, it) }, minutos.toString()) { Emergencias.ponerMinutosCompartir(ctx, it.toInt()) }
        Spacer(Modifier.height(20.dp))
        BotonSecundario(stringResource(R.string.em_probar_sos), Modifier.fillMaxWidth()) { Emergencias.abrirSos(ctx, prueba = true) }
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.em_probar_sos_nota), style = Texto.Pequeno, color = Colores.Texto3)
    }
}

/** El número de emergencias: automático según el país o uno propio. */
@Composable
private fun NumeroEmergencias(ctx: Context, numero: String, propio: String, pais: String) {
    var editando by rememberSaveable { mutableStateOf(false) }
    var texto by rememberSaveable { mutableStateOf(propio) }
    Column(Modifier.animateContentSize(Muelle.suave())) {
        val nombrePais = Emergencias.nombrePais(pais)
        val nota = when {
            propio.isNotBlank() -> stringResource(R.string.em_numero_propio)
            nombrePais.isBlank() -> stringResource(R.string.em_numero_usar_auto)
            else -> stringResource(R.string.em_numero_auto, nombrePais)
        }
        FilaAjuste(stringResource(R.string.em_numero), nota) {
            Row(Modifier.clip(Formas.Pastilla).background(Rojo.Suave).pulsable { editando = !editando; texto = propio }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                Text(numero, style = Texto.Subtitulo, color = Rojo.Boton)
            }
        }
        AnimatedVisibility(editando, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
            Column(Modifier.padding(top = 12.dp)) {
                Campo(stringResource(R.string.em_numero_otro), texto, Emergencias.numeroDelPais(pais), teclado = KeyboardType.Phone) { texto = it.filter(Char::isDigit).take(6) }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotonTexto(stringResource(R.string.em_numero_usar_auto), Modifier.weight(1f)) { Emergencias.ponerNumeroPropio(ctx, ""); editando = false }
                    BotonPrimario(stringResource(R.string.em_listo), Modifier.weight(1f)) { Emergencias.ponerNumeroPropio(ctx, texto); editando = false }
                }
            }
        }
    }
}

// ——— Contactos ———

@Composable
private fun SeccionContactos(ctx: Context, clave: Int, onAnadir: () -> Unit, onPermisos: () -> Unit) {
    val contactos = remember(clave) { Emergencias.contactos(ctx) }
    val sms = remember(clave) { Emergencias.puedeSms(ctx) }
    Grupo("contactos", stringResource(R.string.em_contactos_titulo), stringResource(R.string.em_contactos_nota), Estilo.Tarjeta) {
        if (contactos.isEmpty()) Text(stringResource(R.string.em_contactos_vacio), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(bottom = 14.dp))
        contactos.forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).clip(Formas.Pastilla).background(Colores.Superficie2), contentAlignment = Alignment.Center) {
                    Text(c.nombre.firstOrNull()?.uppercase() ?: "·", style = Texto.Subtitulo, color = Colores.Texto)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.nombre.ifBlank { c.telefono }, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(com.sios.sistema.Llamadas.formatear(c.telefono), style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1)
                }
                BotonIcono(Iconos.Papelera, stringResource(R.string.em_quitar), tam = 38.dp, color = Colores.Texto3) {
                    Emergencias.ponerContactos(ctx, contactos.filterIndexed { j, _ -> j != i })
                }
            }
        }
        if (contactos.size < Emergencias.MAX_CONTACTOS) BotonSecundario(stringResource(R.string.em_contactos_anadir), Modifier.fillMaxWidth(), onClick = onAnadir)
        AnimatedVisibility(contactos.isNotEmpty() && !sms, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(Formas.Pequena).background(Rojo.Suave).pulsable(onPermisos).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(IconosEm.Alerta, null, tint = Rojo.Boton, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.em_sin_permiso_sms), style = Texto.Pequeno, color = Colores.Texto, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.em_permitir), style = Texto.Subtitulo, color = Rojo.Boton)
            }
        }
    }
}

// ——— Ficha médica ———

@Composable
private fun SeccionFicha(ctx: Context) {
    var f by remember { mutableStateOf(Emergencias.ficha(ctx)) }
    fun cambiar(n: Emergencias.Ficha) { f = n; Emergencias.ponerFicha(ctx, n) }
    Grupo("ficha", stringResource(R.string.em_ficha_titulo), stringResource(R.string.em_ficha_nota), Estilo.Tarjeta) {
        Campo(stringResource(R.string.em_f_nombre), f.nombre, stringResource(R.string.em_f_nombre_pista)) { cambiar(f.copy(nombre = it)) }
        Espacio()
        Campo(stringResource(R.string.em_f_nacimiento), f.nacimiento, stringResource(R.string.em_f_nacimiento_pista)) { cambiar(f.copy(nacimiento = it)) }
        Espacio()
        Text(stringResource(R.string.em_f_sangre), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(bottom = 6.dp))
        Fichas(Emergencias.GRUPOS_SANGRE, f.sangre) { cambiar(f.copy(sangre = it)) }
        Espacio()
        Campo(stringResource(R.string.em_f_alergias), f.alergias, stringResource(R.string.em_f_alergias_pista), lineas = 2) { cambiar(f.copy(alergias = it)) }
        Espacio()
        Campo(stringResource(R.string.em_f_medicacion), f.medicacion, stringResource(R.string.em_f_medicacion_pista), lineas = 2) { cambiar(f.copy(medicacion = it)) }
        Espacio()
        Campo(stringResource(R.string.em_f_afecciones), f.afecciones, stringResource(R.string.em_f_afecciones_pista), lineas = 2) { cambiar(f.copy(afecciones = it)) }
        Espacio()
        Campo(stringResource(R.string.em_f_notas), f.notas, stringResource(R.string.em_f_notas_pista), lineas = 2) { cambiar(f.copy(notas = it)) }
        Espacio()
        Text(stringResource(R.string.em_f_donante), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(bottom = 6.dp))
        Pastillas(
            listOf("si" to stringResource(R.string.em_si), "no" to stringResource(R.string.em_no), "" to stringResource(R.string.em_sin_indicar)),
            f.donante,
        ) { cambiar(f.copy(donante = it)) }
        Espacio()
        FilaAjuste(stringResource(R.string.em_f_bloqueo), stringResource(R.string.em_f_bloqueo_nota)) { Interruptor(f.enBloqueo) { cambiar(f.copy(enBloqueo = it)) } }
        Spacer(Modifier.height(18.dp))
        BotonSecundario(stringResource(R.string.em_f_ver), Modifier.fillMaxWidth()) {
            ctx.startActivity(Intent(ctx, EmergenciasActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

// ——— Caídas ———

@Composable
private fun SeccionCaidas(ctx: Context, clave: Int) {
    val activa = remember(clave) { Emergencias.caidas(ctx) }
    val espera = remember(clave) { Emergencias.esperaCaida(ctx) }
    val consumo = remember { Caidas.consumo(ctx) }
    var explicando by rememberSaveable { mutableStateOf(false) }
    Grupo("caidas", stringResource(R.string.em_caidas_titulo), stringResource(R.string.em_caidas_nota), Estilo.Tarjeta) {
        FilaAjuste(
            stringResource(R.string.em_caidas_activar),
            stringResource(if (activa) R.string.em_caidas_activa else R.string.em_caidas_apagada),
        ) {
            // No se enciende hasta aceptar la explicación: tocarlo solo la abre (o la cierra).
            Interruptor(activa, consumo != Caidas.Consumo.SIN_SENSOR) { si ->
                if (si) explicando = !explicando else { explicando = false; Emergencias.ponerCaidas(ctx, false) }
            }
        }
        if (consumo == Caidas.Consumo.SIN_SENSOR) {
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.em_caidas_sin_sensor), style = Texto.Pequeno, color = Colores.Texto3)
        }
        // Antes de activarla: qué hace, qué no y lo que gasta. Solo se activa con «Activar».
        AnimatedVisibility(explicando && !activa, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie2).padding(16.dp)) {
                Punto(IconosEm.Caida, stringResource(R.string.em_caidas_exp_1_titulo), stringResource(R.string.em_caidas_exp_1))
                Punto(IconosEm.Sos, stringResource(R.string.em_caidas_exp_2_titulo), stringResource(R.string.em_caidas_exp_2, Emergencias.numero(ctx)))
                Punto(IconosEm.Alerta, stringResource(R.string.em_caidas_exp_3_titulo), stringResource(R.string.em_caidas_exp_3))
                Punto(
                    Iconos.Reloj, stringResource(R.string.em_caidas_exp_4_titulo),
                    stringResource(
                        when (consumo) {
                            Caidas.Consumo.BAJO -> R.string.em_caidas_consumo_bajo
                            Caidas.Consumo.MEDIO -> R.string.em_caidas_consumo_medio
                            else -> R.string.em_caidas_consumo_limitado
                        },
                    ),
                    ultimo = true,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BotonTexto(stringResource(R.string.em_ahora_no), Modifier.weight(1f)) { explicando = false }
                    BotonPrimario(stringResource(R.string.em_activar), Modifier.weight(1f)) {
                        explicando = false
                        Emergencias.ponerCaidas(ctx, true)
                    }
                }
            }
        }
        AnimatedVisibility(activa, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
            Column(Modifier.padding(top = 18.dp)) {
                Subtitulo(stringResource(R.string.em_caidas_espera))
                Pastillas(listOf(30, 45, 60).map { "$it" to stringResource(R.string.em_segundos, it) }, espera.toString()) { Emergencias.ponerEsperaCaida(ctx, it.toInt()) }
                Spacer(Modifier.height(18.dp))
                BotonSecundario(stringResource(R.string.em_caidas_probar), Modifier.fillMaxWidth()) { Emergencias.abrirSos(ctx, prueba = true, motivo = Emergencias.Motivo.CAIDA) }
            }
        }
    }
}

@Composable
private fun Punto(icono: ImageVector, titulo: String, texto: String, ultimo: Boolean = false) {
    Row(Modifier.padding(bottom = if (ultimo) 0.dp else 14.dp)) {
        Box(Modifier.size(32.dp).clip(Formas.Pastilla).background(Rojo.Suave), contentAlignment = Alignment.Center) {
            Icon(icono, null, tint = Rojo.Boton, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(titulo, style = Texto.Subtitulo, color = Colores.Texto)
            Text(texto, style = Texto.Pequeno, color = Colores.Texto2)
        }
    }
}

// ——— Compartir mi viaje ———

@Composable
private fun SeccionViaje(ctx: Context, clave: Int, estado: SosServicio.Estado?, onOtro: () -> Unit) {
    val contactos = remember(clave) { Emergencias.contactos(ctx) }
    var elegido by rememberSaveable { mutableStateOf("") }
    var minutos by rememberSaveable { mutableStateOf(60) }
    LaunchedEffect(contactos) { if (elegido.isBlank() || contactos.none { it.telefono == elegido }) elegido = contactos.firstOrNull()?.telefono.orEmpty() }
    val enViaje = estado?.activo == true && estado.modo == SosServicio.Modo.VIAJE
    val enSos = estado?.activo == true && estado.modo == SosServicio.Modo.SOS
    Grupo("viaje", stringResource(R.string.em_viaje_titulo), stringResource(R.string.em_viaje_nota), Estilo.Tarjeta) {
        Column(Modifier.animateContentSize(Muelle.suave())) {
            if (enViaje) {
                val hora = hora(ctx, estado!!.hasta)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(Formas.Pastilla).background(Rojo.Verde.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(IconosEm.Ruta, null, tint = Rojo.Verde, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.em_viaje_activo, estado.avisos.firstOrNull()?.contacto?.nombre.orEmpty(), hora),
                        style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(16.dp))
                BotonPrimario(stringResource(R.string.em_viaje_llegado), Modifier.fillMaxWidth()) { SosServicio.parar(ctx) }
            } else if (contactos.isEmpty()) {
                Text(stringResource(R.string.em_viaje_sin_contactos), style = Texto.Pequeno, color = Colores.Texto3)
                Spacer(Modifier.height(12.dp))
                BotonSecundario(stringResource(R.string.em_contactos_anadir), Modifier.fillMaxWidth(), onClick = onOtro)
            } else {
                Text(stringResource(R.string.em_viaje_con), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(bottom = 6.dp))
                androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    contactos.forEach { c ->
                        val esta = c.telefono == elegido
                        Text(
                            c.nombre.ifBlank { c.telefono }, style = Texto.Subtitulo, color = if (esta) Colores.SobreAcento else Colores.Texto2,
                            modifier = Modifier.clip(Formas.Pastilla).background(if (esta) Colores.Acento else Colores.Superficie2).pulsable { elegido = c.telefono }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.em_viaje_durante), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(bottom = 6.dp))
                Pastillas(listOf(30, 60, 120, 240).map { "$it" to SosServicio.duracion(ctx, it) }, minutos.toString()) { minutos = it.toInt() }
                Spacer(Modifier.height(18.dp))
                if (enSos) Text(stringResource(R.string.em_viaje_con_sos), style = Texto.Pequeno, color = Colores.Texto3)
                else BotonPrimario(stringResource(R.string.em_viaje_empezar), Modifier.fillMaxWidth()) {
                    contactos.firstOrNull { it.telefono == elegido }?.let { SosServicio.empezarViaje(ctx, it, minutos, Emergencias.modoPrueba(ctx)) }
                }
            }
        }
    }
}

// ——— Modo de prueba ———

@Composable
private fun SeccionPrueba(ctx: Context, clave: Int) {
    val activo = remember(clave) { Emergencias.modoPrueba(ctx) }
    val registro = remember(clave) { Emergencias.registro(ctx).take(6) }
    Grupo("prueba", stringResource(R.string.em_prueba_titulo), stringResource(R.string.em_prueba_nota), Estilo.Tarjeta) {
        FilaAjuste(stringResource(R.string.em_prueba_activar), stringResource(R.string.em_prueba_activar_nota)) { Interruptor(activo) { Emergencias.ponerModoPrueba(ctx, it) } }
        if (registro.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.em_prueba_registro), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(bottom = 8.dp))
            registro.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    Icon(if (a.tipo == "llamada") Iconos.Llamar else IconosEm.Mensaje, null, tint = Colores.Texto3, modifier = Modifier.padding(top = 2.dp).size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        val hora = hora(ctx, a.hora)
                        Text("${a.destino} · $hora", style = Texto.Pequeno, color = Colores.Texto)
                        Text(a.texto, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            BotonTexto(stringResource(R.string.em_prueba_borrar), Modifier.fillMaxWidth()) { Emergencias.borrarRegistro(ctx) }
        }
    }
}

/** El color rojo de la categoría en la portada de Ajustes. */
internal const val COLOR_CATEGORIA = 0xFFD9443C

/** Icono de la categoría (el mismo del SOS). */
internal val IconoCategoria: ImageVector get() = IconosEm.Sos
