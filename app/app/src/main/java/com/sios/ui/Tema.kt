package com.sios.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R

/**
 * Apariencia: claro u oscuro (sigue al sistema, que se cambia desde Ajustes de SIOS: claro,
 * oscuro o automático al anochecer) y el color de acento (naranja de Claude, verde, azul o lila).
 */
object Apariencia {
    /** true en modo claro. La pone la app al arrancar y cuando cambia la configuración. */
    var claro by androidx.compose.runtime.mutableStateOf(false)
    var acento by androidx.compose.runtime.mutableStateOf(IA)

    /** «El color de la IA»: el de la IA en uso o, sin elegir, el hueso de SIOS. Va el primero y de serie. */
    const val IA = "ia"

    /** Acento en oscuro y en claro (en claro, algo más oscuro para que contraste igual). */
    val ACENTOS: Map<String, Pair<Long, Long>> get() = linkedMapOf(
        IA to (Marca.acento(true) to Marca.acento(false)),
        "naranja" to (0xFFD97757 to 0xFFC6613F),
        "verde" to (0xFF7CBF8E to 0xFF4E8B62),
        "azul" to (0xFF7AA7E0 to 0xFF3F6FB5),
        "lila" to (0xFFA88FDB to 0xFF7656B8),
    )

    fun leer(ctx: android.content.Context) {
        claro = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES
        val p = ctx.getSharedPreferences("apariencia", android.content.Context.MODE_PRIVATE)
        // Antes «naranja» era el color de la IA (de serie): pasa a «ia»; el naranja ahora es fijo.
        if (!p.getBoolean("acento_ia", false)) {
            if (p.getString("acento", "naranja") == "naranja") p.edit().putString("acento", IA).apply()
            p.edit().putBoolean("acento_ia", true).apply()
        }
        acento = p.getString("acento", IA) ?: IA
    }

    fun ponerAcento(ctx: android.content.Context, nombre: String) {
        acento = nombre
        ctx.getSharedPreferences("apariencia", android.content.Context.MODE_PRIVATE).edit().putString("acento", nombre).apply()
        // La paleta de todo el sistema (barra, ajustes, teclado…) sale del acento elegido.
        runCatching { com.sios.sistema.SistemaSios.aplicarTema(ctx) }
    }

    /** «ia», el de serie, es el color de la IA elegida (el naranja de Claude, el verde de ChatGPT… o el hueso de SIOS). */
    fun colorAcento(oscuro: Boolean = !claro): Long =
        if (acento == IA || acento !in ACENTOS) Marca.acento(oscuro) else ACENTOS.getValue(acento).let { if (oscuro) it.first else it.second }

    /** ¿Sigue el color de la IA (sin acento propio elegido)? */
    val sigueIa get() = acento == IA || acento !in ACENTOS
}

// Paleta oficial de Claude (la de claude.ai y la app de escritorio): grises cálidos y el acento, en
// oscuro y en claro. Cambia sola con el modo del sistema.
object Colores {
    private fun c(oscuro: Long, claro: Long) = Color(if (Apariencia.claro) claro else oscuro)
    val Fondo get() = c(0xFF262624, 0xFFFAF9F5) // fondo de la conversación
    val Superficie get() = c(0xFF30302E, 0xFFFFFFFF) // tarjetas, caja de texto
    val Superficie2 get() = c(0xFF3D3D3A, 0xFFF0EEE6) // botones secundarios, pistas
    val Lateral get() = c(0xFF1F1E1D, 0xFFF5F4ED) // paneles laterales y hojas
    val Borde get() = c(0xFF3E3D3A, 0xFFE3E1D7)
    val BordeFuerte get() = c(0xFF52514D, 0xFFCFCCC1)
    val Texto get() = c(0xFFFAF9F5, 0xFF141413)
    val Texto2 get() = c(0xFFC2C0B6, 0xFF3D3D3A)
    val Texto3 get() = c(0xFF9C9A92, 0xFF73726C)
    val Acento get() = Color(Apariencia.colorAcento())
    val AcentoSuave get() = Acento.copy(alpha = 0.15f)
    // Texto e iconos sobre el acento: blanco sobre los acentos oscuros o saturados (el naranja de
    // Claude, el verde de ChatGPT…) y grafito sobre los claros (el hueso de SIOS sin IA elegida).
    val SobreAcento get() = if (Acento.luminance() > 0.5f) Color(0xFF141413) else Color(0xFFFFFFFF)
    val Rojo get() = c(0xFFDD5353, 0xFFC64545)
}

@OptIn(ExperimentalTextApi::class)
private fun montserrat(peso: Int) =
    Font(R.font.montserrat, FontWeight(peso), variationSettings = FontVariation.Settings(FontVariation.weight(peso)))

val Montserrat = FontFamily(montserrat(300), montserrat(400), montserrat(500), montserrat(600), montserrat(700))

object Texto {
    val Gigante = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Light, fontSize = 64.sp, letterSpacing = (-2).sp)
    val Titulo = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-0.3).sp)
    val Subtitulo = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    val Cuerpo = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp)
    val Pequeno = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val Etiqueta = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.8.sp)
    val Codigo = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
}

object Formas {
    val Pastilla = RoundedCornerShape(percent = 50)
    val Tarjeta = RoundedCornerShape(16.dp)
    val Pequena = RoundedCornerShape(10.dp)
}

/** Muelles suaves para todo lo que se mueve: nada de cambios bruscos. */
object Muelle {
    fun <T> suave() = spring<T>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)
    fun <T> vivo() = spring<T>(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)
    /** Gestos que siguen al dedo (cajón): firme y sin rebote, para que asiente sin temblar. */
    fun cajon() = spring<Float>(dampingRatio = 0.9f, stiffness = 420f, visibilityThreshold = 0.001f)
}

@Composable
fun TemaSios(contenido: @Composable () -> Unit) {
    // Por si cambia sin recrear la pantalla (la app lo pone también al arrancar).
    val oscuro = androidx.compose.foundation.isSystemInDarkTheme()
    if (Apariencia.claro == oscuro) Apariencia.claro = !oscuro
    val esquema = if (oscuro) darkColorScheme(
        primary = Colores.Acento,
        onPrimary = Colores.SobreAcento,
        secondary = Colores.Acento,
        background = Colores.Fondo,
        onBackground = Colores.Texto,
        surface = Colores.Fondo,
        onSurface = Colores.Texto,
        surfaceVariant = Colores.Superficie2,
        onSurfaceVariant = Colores.Texto2,
        surfaceContainer = Colores.Superficie,
        surfaceContainerHigh = Colores.Superficie2,
        surfaceContainerHighest = Colores.Superficie2,
        outline = Colores.Borde,
        outlineVariant = Colores.Borde,
        error = Colores.Rojo,
    ) else androidx.compose.material3.lightColorScheme(
        primary = Colores.Acento,
        onPrimary = Colores.SobreAcento,
        secondary = Colores.Acento,
        background = Colores.Fondo,
        onBackground = Colores.Texto,
        surface = Colores.Fondo,
        onSurface = Colores.Texto,
        surfaceVariant = Colores.Superficie2,
        onSurfaceVariant = Colores.Texto2,
        surfaceContainer = Colores.Superficie,
        surfaceContainerHigh = Colores.Superficie2,
        surfaceContainerHighest = Colores.Superficie2,
        outline = Colores.Borde,
        outlineVariant = Colores.Borde,
        error = Colores.Rojo,
    )
    MaterialTheme(
        colorScheme = esquema,
        typography = Typography(
            bodyLarge = Texto.Cuerpo,
            bodyMedium = Texto.Cuerpo,
            labelLarge = Texto.Subtitulo,
            titleLarge = Texto.Titulo,
        ),
        shapes = Shapes(
            small = Formas.Pequena,
            medium = Formas.Tarjeta,
            large = Formas.Tarjeta,
            extraLarge = RoundedCornerShape(24.dp),
        ),
        content = contenido,
    )
}
