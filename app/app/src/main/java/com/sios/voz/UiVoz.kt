package com.sios.voz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Texto
import com.sios.ui.pulsable

/**
 * Sello pequeño «Sin conexión» (con su candado): lo que se ve se hace en el móvil y no sale de él.
 * [descargar]: aún no se puede (faltan idiomas) y lleva a descargarlos.
 */
@Composable
fun SelloSinConexion(descargar: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(32.dp)
            .clip(Formas.Pastilla)
            .background(if (descargar) Colores.AcentoSuave else Colores.Superficie)
            .pulsable(onClick)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (descargar) IconosVoz.Descargar else IconosVoz.Candado, null,
            tint = if (descargar) Colores.Acento else Colores.Texto2, modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(if (descargar) R.string.voz_sello_descargar else R.string.voz_sello),
            style = Texto.Pequeno, color = if (descargar) Colores.Acento else Colores.Texto2, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
