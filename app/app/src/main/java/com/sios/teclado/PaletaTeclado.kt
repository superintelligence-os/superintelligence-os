package com.sios.teclado

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Colores del teclado, sacados de la paleta del sistema (los system_* de Material You). En SIOS esa
 * paleta la pone ColoresSistema con el color de la IA en uso, así que el teclado cambia con ella sin
 * tocar nada; fuera de SIOS sigue al fondo de pantalla, como Gboard. Claro u oscuro según el sistema.
 */
internal class PaletaTeclado(
    val oscuro: Boolean,
    val fondo: Color,
    val tecla: Color, // letras y números
    val especial: Color, // mayúsculas, borrar, ?123…: un punto teñida del acento
    val pulsada: Color,
    val sombra: Color, // el canto de abajo de cada tecla
    val globo: Color, // ventanita de la tecla pulsada y de los acentos
    val texto: Color,
    val texto2: Color,
    val texto3: Color,
    val divisor: Color,
    val acento: Color,
    val sobreAcento: Color,
    val acentoSuave: Color,
    val sobreAcentoSuave: Color,
) {
    companion object {
        fun de(ctx: Context): PaletaTeclado {
            val oscuro = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            // Los papeles de Material 3 (system_surface_container_dark…) existen desde Android 14; antes,
            // la paleta de Claude tal cual.
            val sistema = Build.VERSION.SDK_INT >= 34
            fun c(idOscuro: Int, idClaro: Int, oscuroDef: Long, claroDef: Long): Color =
                if (sistema) runCatching { Color(ctx.getColor(if (oscuro) idOscuro else idClaro)) }.getOrNull() ?: Color(if (oscuro) oscuroDef else claroDef)
                else Color(if (oscuro) oscuroDef else claroDef)

            val superficieBaja = c(android.R.color.system_surface_dim_dark, android.R.color.system_surface_dim_light, 0xFF1F1E1D, 0xFFE8E6DC)
            val superficieAlta = c(android.R.color.system_surface_bright_dark, android.R.color.system_surface_container_lowest_light, 0xFF3D3D3A, 0xFFFFFFFF)
            val contenedor = c(android.R.color.system_surface_container_high_dark, android.R.color.system_outline_variant_light, 0xFF353532, 0xFFD1CFC5)
            val texto = c(android.R.color.system_on_surface_dark, android.R.color.system_on_surface_light, 0xFFFAF9F5, 0xFF141413)
            val texto2 = c(android.R.color.system_on_surface_variant_dark, android.R.color.system_on_surface_variant_light, 0xFFC2C0B6, 0xFF3D3D3A)
            val texto3 = c(android.R.color.system_outline_dark, android.R.color.system_outline_light, 0xFF73726C, 0xFF73726C)
            val divisor = c(android.R.color.system_outline_variant_dark, android.R.color.system_outline_variant_light, 0xFF3E3D3A, 0xFFD1CFC5)
            val acento = c(android.R.color.system_primary_dark, android.R.color.system_primary_light, 0xFFD97757, 0xFFC6613F)
            val sobreAcento = c(android.R.color.system_on_primary_dark, android.R.color.system_on_primary_light, 0xFFFFFFFF, 0xFFFFFFFF)
            val acentoSuave = c(android.R.color.system_primary_container_dark, android.R.color.system_primary_container_light, 0xFF5C2E20, 0xFFF9D5C7)
            val sobreAcentoSuave = c(android.R.color.system_on_primary_container_dark, android.R.color.system_on_primary_container_light, 0xFFF9D5C7, 0xFF5C2E20)

            // En oscuro las letras van un poco más claras que la superficie más alta, para que destaquen
            // sobre el fondo como en iOS; en claro, blancas sobre gris.
            val tecla = if (oscuro) lerp(superficieAlta, texto, 0.07f) else superficieAlta
            val especial = lerp(contenedor, acento, if (oscuro) 0.10f else 0.12f)
            return PaletaTeclado(
                oscuro = oscuro,
                fondo = superficieBaja,
                tecla = tecla,
                especial = especial,
                pulsada = lerp(tecla, texto, if (oscuro) 0.16f else 0.12f),
                sombra = Color.Black.copy(alpha = if (oscuro) 0.40f else 0.16f),
                globo = if (oscuro) lerp(tecla, texto, 0.10f) else tecla,
                texto = texto,
                texto2 = texto2,
                texto3 = texto3,
                divisor = divisor,
                acento = acento,
                sobreAcento = sobreAcento,
                acentoSuave = acentoSuave,
                sobreAcentoSuave = sobreAcentoSuave,
            )
        }
    }
}
