package com.sios.sistema

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.sios.R
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable

/**
 * Teclado del PIN en orden aleatorio en la pantalla de bloqueo. Lo trae el SystemUI de LineageOS: el
 * ajuste LineageSettings.System «lockscreen_scramble_pin_layout» (0/1), que vive en el proveedor
 * content://lineagesettings. Escribirlo pide lineageos.permission.WRITE_SETTINGS (de nivel normal en
 * LineageOS 23.2: basta con pedirlo en el manifiesto). Fuera de LineageOS no hay proveedor y no se ofrece.
 */
object PinAleatorio {
    private const val AUTORIDAD = "lineagesettings"
    private val PROVEEDOR = Uri.parse("content://$AUTORIDAD")
    private const val CLAVE = "lockscreen_scramble_pin_layout"

    /** Si el sistema lo trae (LineageOS y derivados). */
    fun disponible(ctx: Context): Boolean = runCatching { ctx.packageManager.resolveContentProvider(AUTORIDAD, 0) != null }.getOrDefault(false)

    /** Si está activado. Se lee igual que LineageSettings.System.getString: el método «GET_system» del proveedor. */
    fun leer(ctx: Context): Boolean = runCatching {
        ctx.contentResolver.call(PROVEEDOR, "GET_system", CLAVE, null)?.getString("value") == "1"
    }.getOrDefault(false)

    /** Lo activa o lo quita. Devuelve true si se ha guardado. SystemUI lo lee al enseñar el teclado del PIN. */
    fun activar(ctx: Context, si: Boolean): Boolean = runCatching {
        ctx.contentResolver.call(PROVEEDOR, "PUT_system", CLAVE, Bundle().apply { putString("value", if (si) "1" else "0") })
        true
    }.getOrDefault(false)

    /** Si hay bloqueo de pantalla seguro (PIN, patrón o contraseña). Android no dice cuál a una app que no es del sistema. */
    fun hayBloqueo(ctx: Context): Boolean = ctx.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    /** Abre los ajustes del sistema para poner un bloqueo (el usuario elige PIN). */
    fun ponerBloqueo(ctx: Context) {
        runCatching { ctx.startActivity(Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * Opción del PIN aleatorio, lista para ponerla donde haga falta (Ajustes y la bienvenida, tras poner
 * el PIN): título, explicación corta e interruptor. Si aún no hay bloqueo de pantalla, lo dice y ofrece
 * ponerlo. Fuera de LineageOS no pinta nada. Se vuelve a leer al volver a la pantalla (por si se ha
 * puesto o quitado el bloqueo en los ajustes del sistema).
 */
@Composable
fun OpcionPinAleatorio(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val disponible = remember { PinAleatorio.disponible(ctx) }
    if (!disponible) return
    var activo by remember { mutableStateOf(PinAleatorio.leer(ctx)) }
    var bloqueo by remember { mutableStateOf(PinAleatorio.hayBloqueo(ctx)) }
    LifecycleResumeEffect(Unit) {
        activo = PinAleatorio.leer(ctx)
        bloqueo = PinAleatorio.hayBloqueo(ctx)
        onPauseOrDispose { }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pin_aleatorio_titulo), style = Texto.Cuerpo, color = Colores.Texto)
                Text(stringResource(R.string.pin_aleatorio_nota), style = Texto.Pequeno, color = Colores.Texto3)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = activo,
                onCheckedChange = { si -> if (PinAleatorio.activar(ctx, si)) activo = PinAleatorio.leer(ctx) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Colores.SobreAcento,
                    checkedTrackColor = Colores.Acento,
                    uncheckedThumbColor = Colores.Texto3,
                    uncheckedTrackColor = Colores.Superficie2,
                    uncheckedBorderColor = Colores.Borde,
                ),
            )
        }
        // Sin bloqueo todavía: se puede dejar activado y ponerlo después.
        AnimatedVisibility(
            !bloqueo,
            enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()),
            exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave()),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.pin_aleatorio_sin_bloqueo), style = Texto.Pequeno, color = Colores.Texto2,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.pin_aleatorio_poner), style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Acento,
                    modifier = Modifier.clip(Formas.Pequena).pulsable { PinAleatorio.ponerBloqueo(ctx) }.padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }
        }
    }
}
