package com.sios.ajustes

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.inicio.InicioActivity
import com.sios.sistema.Copia
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Texto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Qué se va a hacer con la contraseña que pide la hoja. */
private sealed class Peticion {
    data class Crear(val destino: Uri) : Peticion()
    data class Restaurar(val origen: Uri) : Peticion()
    data class Automatica(val carpeta: Uri) : Peticion()
}

/** Sección «Copia de seguridad» de Ajustes: última copia, automática semanal, copia ahora y restaurar. */
@Composable
fun SeccionCopia(titulo: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var peticion by remember { mutableStateOf<Peticion?>(null) }
    var automatica by remember { mutableStateOf(Copia.automatica(ctx)) }
    var ultima by remember { mutableStateOf(Copia.ultima(ctx)) }

    val crear = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) peticion = Peticion.Crear(uri)
    }
    val restaurar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) peticion = Peticion.Restaurar(uri)
    }
    val carpeta = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) peticion = Peticion.Automatica(uri)
    }

    val nota = if (ultima > 0) stringResource(
        R.string.copia_ultima, com.sios.ui.haceRato(ctx, ultima),
    ) else stringResource(R.string.copia_ninguna)
    titulo(stringResource(R.string.copia_titulo), nota) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.copia_auto_titulo), style = Texto.Cuerpo, color = Colores.Texto)
                    Text(stringResource(R.string.copia_auto_nota), style = Texto.Pequeno, color = Colores.Texto3)
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = automatica,
                    onCheckedChange = { si -> if (si) carpeta.launch(null) else { Copia.desactivarAutomatica(ctx); automatica = false } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Colores.SobreAcento, checkedTrackColor = Colores.Acento,
                        uncheckedThumbColor = Colores.Texto3, uncheckedTrackColor = Colores.Superficie2, uncheckedBorderColor = Colores.Borde,
                    ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotonSecundario(stringResource(R.string.copia_ahora), Modifier.weight(1f)) { crear.launch(Copia.nombreSugerido()) }
                BotonSecundario(stringResource(R.string.copia_restaurar), Modifier.weight(1f)) { restaurar.launch(arrayOf("*/*")) }
            }
        }
    }

    peticion?.let { p ->
        HojaContrasena(
            p,
            onCerrar = { peticion = null },
            onHecho = {
                peticion = null
                automatica = Copia.automatica(ctx)
                ultima = Copia.ultima(ctx)
            },
        )
    }
}

/** Solo restaurar (para el primer arranque de un móvil nuevo). */
@Composable
fun BotonRestaurarCopia(modifier: Modifier = Modifier) {
    var peticion by remember { mutableStateOf<Peticion?>(null) }
    val restaurar = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) peticion = Peticion.Restaurar(uri)
    }
    BotonSecundario(stringResource(R.string.copia_restaurar_otro), modifier) { restaurar.launch(arrayOf("*/*")) }
    peticion?.let { HojaContrasena(it, onCerrar = { peticion = null }, onHecho = { peticion = null }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HojaContrasena(p: Peticion, onCerrar: () -> Unit, onHecho: () -> Unit) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    var clave by remember { mutableStateOf("") }
    var repetida by remember { mutableStateOf("") }
    var trabajando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nueva = p !is Peticion.Restaurar // al crear se pide dos veces
    val valida = clave.length >= 8 && (!nueva || clave == repetida)
    val errContrasena = stringResource(R.string.copia_error_contrasena)
    val errNoEs = stringResource(R.string.copia_error_no_es)

    ModalBottomSheet(
        onDismissRequest = { if (!trabajando) onCerrar() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Colores.Lateral,
    ) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                stringResource(when (p) { is Peticion.Restaurar -> R.string.copia_hoja_restaurar; is Peticion.Automatica -> R.string.copia_hoja_auto; else -> R.string.copia_hoja_crear }),
                style = Texto.Titulo, color = Colores.Texto,
            )
            Text(
                stringResource(if (nueva) R.string.copia_hoja_nota_nueva else R.string.copia_hoja_nota_restaurar),
                style = Texto.Cuerpo, color = Colores.Texto2,
            )
            CampoClave(clave, stringResource(R.string.copia_contrasena)) { clave = it; error = null }
            if (nueva) CampoClave(repetida, stringResource(R.string.copia_repetir)) { repetida = it; error = null }
            val aviso = error ?: if (nueva && repetida.isNotEmpty() && clave != repetida) stringResource(R.string.copia_no_coinciden)
                else if (clave.isNotEmpty() && clave.length < 8) stringResource(R.string.copia_corta) else null
            if (aviso != null) Text(aviso, style = Texto.Pequeno, color = Colores.Rojo)
            BotonPrimario(
                stringResource(if (trabajando) R.string.copia_trabajando else if (p is Peticion.Restaurar) R.string.copia_restaurar else R.string.copia_guardar),
                Modifier.fillMaxWidth(),
            ) {
                if (!valida || trabajando) return@BotonPrimario
                trabajando = true
                alcance.launch {
                    val r = withContext(Dispatchers.IO) {
                        runCatching {
                            when (p) {
                                is Peticion.Crear -> Copia.crear(ctx, p.destino, clave.toCharArray())
                                is Peticion.Restaurar -> Copia.restaurar(ctx, p.origen, clave.toCharArray())
                                is Peticion.Automatica -> {
                                    Copia.activarAutomatica(ctx, p.carpeta, clave.toCharArray())
                                    Copia.copiaAutomatica(ctx) // la primera, ya
                                }
                            }
                        }
                    }
                    trabajando = false
                    r.onSuccess {
                        if (p is Peticion.Restaurar) reiniciar(ctx) else onHecho()
                    }.onFailure { e ->
                        error = when (e) {
                            is Copia.ErrorCopia.Contrasena -> errContrasena
                            is Copia.ErrorCopia.NoEsCopia -> errNoEs
                            else -> e.message ?: e.toString()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CampoClave(valor: String, pista: String, onCambio: (String) -> Unit) {
    BasicTextField(
        value = valor,
        onValueChange = onCambio,
        singleLine = true,
        textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
        cursorBrush = SolidColor(Colores.Acento),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { campo ->
            Box(
                Modifier.fillMaxWidth().height(50.dp).clip(Formas.Pequena).background(Colores.Superficie)
                    .border(1.dp, Colores.Borde, Formas.Pequena).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (valor.isEmpty()) Text(pista, style = Texto.Cuerpo, color = Colores.Texto3)
                campo()
            }
        },
    )
}

/** Tras restaurar, la app vuelve a arrancar con todo lo restaurado (es el inicio: se relanza sola). */
private fun reiniciar(ctx: Context) {
    // Se programa el inicio para dentro de un momento y la app sale; al volver, lee lo restaurado.
    val i = android.app.PendingIntent.getActivity(
        ctx, 9, Intent(ctx, InicioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        android.app.PendingIntent.FLAG_IMMUTABLE,
    )
    ctx.getSystemService(android.app.AlarmManager::class.java).set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 600, i)
    com.sios.sistema.Manos.asegurar(ctx)
    Process.killProcess(Process.myPid())
}
