package com.sios.sistema

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Los colores de la IA elegida en todo el sistema (sin elegir, SIOS usa la paleta en blanco y negro
 * de Android, sin esta capa). Android genera su paleta a partir de un color y, en
 * modo oscuro, el naranja de Claude se le convierte en fondos marrones y botones salmón. Aquí se
 * fija la paleta exacta de Claude (gris #262624, tarjetas #30302E, texto #FAF9F5, naranja #D97757)
 * como capa propia encima de la de Android, igual que hace SystemUI con la suya: así Ajustes,
 * Mensajes, Contactos, Reloj y cualquier app con colores del sistema se ven como Claude.
 * Necesita CHANGE_OVERLAY_PACKAGES (app privilegiada de SIOS); fuera de SIOS no hace nada.
 */
object ColoresSistema {
    private const val NOMBRE = "claude"
    private const val TAG = "SistemaSios"

    // Grises cálidos de Claude por luminosidad (tono 0 = blanco … 1000 = negro), y la escala del naranja.
    private val GRISES = mapOf(
        0 to 0xFFFFFFFF, 10 to 0xFFFAF9F5, 50 to 0xFFF0EEE6, 100 to 0xFFE8E6DC, 200 to 0xFFC2C0B6, 300 to 0xFFA8A69E,
        400 to 0xFF9C9A92, 500 to 0xFF73726C, 600 to 0xFF5E5D59, 700 to 0xFF3D3D3A, 800 to 0xFF30302E, 900 to 0xFF262624, 1000 to 0xFF000000,
    )
    private val NARANJAS = mapOf(
        0 to 0xFFFFFFFF, 10 to 0xFFFDF4F0, 50 to 0xFFF9E3DA, 100 to 0xFFE9A68C, 200 to 0xFFD97757, 300 to 0xFFC6613F,
        400 to 0xFFB5573A, 500 to 0xFF9A4730, 600 to 0xFF7F3B25, 700 to 0xFF622D1C, 800 to 0xFF4A2214, 900 to 0xFF33170E, 1000 to 0xFF000000,
    )

    // Papeles de Material 3, en oscuro y en claro.
    private val OSCURO = mapOf(
        "background" to 0xFF262624, "on_background" to 0xFFFAF9F5,
        "surface" to 0xFF262624, "surface_dim" to 0xFF1F1E1D, "surface_bright" to 0xFF3D3D3A,
        "surface_container_lowest" to 0xFF1F1E1D, "surface_container_low" to 0xFF2A2A27, "surface_container" to 0xFF30302E,
        "surface_container_high" to 0xFF353532, "surface_container_highest" to 0xFF3D3D3A, "surface_variant" to 0xFF3D3D3A,
        "on_surface" to 0xFFFAF9F5, "on_surface_variant" to 0xFFC2C0B6, "outline" to 0xFF73726C, "outline_variant" to 0xFF3E3D3A,
        "inverse_surface" to 0xFFFAF9F5, "inverse_on_surface" to 0xFF262624, "inverse_primary" to 0xFFB5573A,
        "primary" to 0xFFD97757, "on_primary" to 0xFFFFFFFF, "primary_container" to 0xFF5C2E20, "on_primary_container" to 0xFFF9D5C7,
        "primary_dim" to 0xFFC6613F, "surface_tint" to 0xFFD97757,
        "secondary" to 0xFFC2C0B6, "on_secondary" to 0xFF262624, "secondary_container" to 0xFF3D3D3A, "on_secondary_container" to 0xFFFAF9F5,
        "secondary_dim" to 0xFF9C9A92,
        // Claude tiene un solo acento: el «tercer» color de Material es el mismo naranja (lo usan, por ejemplo,
        // el «=» de la Calculadora o los botones destacados de Reloj).
        "tertiary" to 0xFFD97757, "on_tertiary" to 0xFFFFFFFF, "tertiary_container" to 0xFF5C2E20, "on_tertiary_container" to 0xFFF9D5C7,
        "tertiary_dim" to 0xFFC6613F,
        "primary_fixed" to 0xFFF9D5C7, "primary_fixed_dim" to 0xFFE9A68C, "on_primary_fixed" to 0xFF33170E, "on_primary_fixed_variant" to 0xFF622D1C,
        "secondary_fixed" to 0xFFE8E6DC, "secondary_fixed_dim" to 0xFFC2C0B6, "on_secondary_fixed" to 0xFF141413, "on_secondary_fixed_variant" to 0xFF3D3D3A,
        "tertiary_fixed" to 0xFFF9D5C7, "tertiary_fixed_dim" to 0xFFE9A68C, "on_tertiary_fixed" to 0xFF33170E, "on_tertiary_fixed_variant" to 0xFF622D1C,
        "shadow" to 0xFF000000, "scrim" to 0xFF000000,
    )
    private val CLARO = mapOf(
        "background" to 0xFFFAF9F5, "on_background" to 0xFF141413,
        "surface" to 0xFFFAF9F5, "surface_dim" to 0xFFE8E6DC, "surface_bright" to 0xFFFFFFFF,
        "surface_container_lowest" to 0xFFFFFFFF, "surface_container_low" to 0xFFF5F4ED, "surface_container" to 0xFFF0EEE6,
        "surface_container_high" to 0xFFEBE9E0, "surface_container_highest" to 0xFFE8E6DC, "surface_variant" to 0xFFE8E6DC,
        "on_surface" to 0xFF141413, "on_surface_variant" to 0xFF3D3D3A, "outline" to 0xFF73726C, "outline_variant" to 0xFFD1CFC5,
        "inverse_surface" to 0xFF30302E, "inverse_on_surface" to 0xFFFAF9F5, "inverse_primary" to 0xFFE9A68C,
        "primary" to 0xFFC6613F, "on_primary" to 0xFFFFFFFF, "primary_container" to 0xFFF9D5C7, "on_primary_container" to 0xFF5C2E20,
        "primary_dim" to 0xFFB5573A, "surface_tint" to 0xFFC6613F,
        "secondary" to 0xFF5E5D59, "on_secondary" to 0xFFFFFFFF, "secondary_container" to 0xFFE8E6DC, "on_secondary_container" to 0xFF141413,
        "secondary_dim" to 0xFF73726C,
        "tertiary" to 0xFFC6613F, "on_tertiary" to 0xFFFFFFFF, "tertiary_container" to 0xFFF9D5C7, "on_tertiary_container" to 0xFF5C2E20,
        "tertiary_dim" to 0xFFB5573A,
        "primary_fixed" to 0xFFF9D5C7, "primary_fixed_dim" to 0xFFE9A68C, "on_primary_fixed" to 0xFF33170E, "on_primary_fixed_variant" to 0xFF622D1C,
        "secondary_fixed" to 0xFFE8E6DC, "secondary_fixed_dim" to 0xFFC2C0B6, "on_secondary_fixed" to 0xFF141413, "on_secondary_fixed_variant" to 0xFF3D3D3A,
        "tertiary_fixed" to 0xFFF9D5C7, "tertiary_fixed_dim" to 0xFFE9A68C, "on_tertiary_fixed" to 0xFF33170E, "on_tertiary_fixed_variant" to 0xFF622D1C,
        "shadow" to 0xFF000000, "scrim" to 0xFF000000,
    )

    /** Todos los colores que Android 16 genera en su paleta dinámica (los mismos que pone SystemUI). */
    private val RECURSOS = listOf(
        "system_accent1_0_dark", "system_accent1_0_light", "system_accent1_1000_dark", "system_accent1_1000_light", "system_accent1_100_dark", "system_accent1_100_light",
        "system_accent1_10_dark", "system_accent1_10_light", "system_accent1_200_dark", "system_accent1_200_light", "system_accent1_300_dark", "system_accent1_300_light",
        "system_accent1_400_dark", "system_accent1_400_light", "system_accent1_500_dark", "system_accent1_500_light", "system_accent1_50_dark", "system_accent1_50_light",
        "system_accent1_600_dark", "system_accent1_600_light", "system_accent1_700_dark", "system_accent1_700_light", "system_accent1_800_dark", "system_accent1_800_light",
        "system_accent1_900_dark", "system_accent1_900_light", "system_accent2_0_dark", "system_accent2_0_light", "system_accent2_1000_dark", "system_accent2_1000_light",
        "system_accent2_100_dark", "system_accent2_100_light", "system_accent2_10_dark", "system_accent2_10_light", "system_accent2_200_dark", "system_accent2_200_light",
        "system_accent2_300_dark", "system_accent2_300_light", "system_accent2_400_dark", "system_accent2_400_light", "system_accent2_500_dark", "system_accent2_500_light",
        "system_accent2_50_dark", "system_accent2_50_light", "system_accent2_600_dark", "system_accent2_600_light", "system_accent2_700_dark", "system_accent2_700_light",
        "system_accent2_800_dark", "system_accent2_800_light", "system_accent2_900_dark", "system_accent2_900_light", "system_accent3_0_dark", "system_accent3_0_light",
        "system_accent3_1000_dark", "system_accent3_1000_light", "system_accent3_100_dark", "system_accent3_100_light", "system_accent3_10_dark", "system_accent3_10_light",
        "system_accent3_200_dark", "system_accent3_200_light", "system_accent3_300_dark", "system_accent3_300_light", "system_accent3_400_dark", "system_accent3_400_light",
        "system_accent3_500_dark", "system_accent3_500_light", "system_accent3_50_dark", "system_accent3_50_light", "system_accent3_600_dark", "system_accent3_600_light",
        "system_accent3_700_dark", "system_accent3_700_light", "system_accent3_800_dark", "system_accent3_800_light", "system_accent3_900_dark", "system_accent3_900_light",
        "system_background_dark", "system_background_light", "system_brand_a_dark", "system_brand_a_light", "system_brand_b_dark", "system_brand_b_light",
        "system_brand_c_dark", "system_brand_c_light", "system_brand_d_dark", "system_brand_d_light", "system_clock_hour_dark", "system_clock_hour_light",
        "system_clock_minute_dark", "system_clock_minute_light", "system_clock_second_dark", "system_clock_second_light", "system_control_activated_dark", "system_control_activated_light",
        "system_control_highlight_dark", "system_control_highlight_light", "system_control_normal_dark", "system_control_normal_light", "system_error_container_dark", "system_error_container_light",
        "system_error_dark", "system_error_dim_dark", "system_error_dim_light", "system_error_light", "system_inverse_on_surface_dark", "system_inverse_on_surface_light",
        "system_inverse_primary_dark", "system_inverse_primary_light", "system_inverse_surface_dark", "system_inverse_surface_light", "system_neutral1_0_dark", "system_neutral1_0_light",
        "system_neutral1_1000_dark", "system_neutral1_1000_light", "system_neutral1_100_dark", "system_neutral1_100_light", "system_neutral1_10_dark", "system_neutral1_10_light",
        "system_neutral1_200_dark", "system_neutral1_200_light", "system_neutral1_300_dark", "system_neutral1_300_light", "system_neutral1_400_dark", "system_neutral1_400_light",
        "system_neutral1_500_dark", "system_neutral1_500_light", "system_neutral1_50_dark", "system_neutral1_50_light", "system_neutral1_600_dark", "system_neutral1_600_light",
        "system_neutral1_700_dark", "system_neutral1_700_light", "system_neutral1_800_dark", "system_neutral1_800_light", "system_neutral1_900_dark", "system_neutral1_900_light",
        "system_neutral2_0_dark", "system_neutral2_0_light", "system_neutral2_1000_dark", "system_neutral2_1000_light", "system_neutral2_100_dark", "system_neutral2_100_light",
        "system_neutral2_10_dark", "system_neutral2_10_light", "system_neutral2_200_dark", "system_neutral2_200_light", "system_neutral2_300_dark", "system_neutral2_300_light",
        "system_neutral2_400_dark", "system_neutral2_400_light", "system_neutral2_500_dark", "system_neutral2_500_light", "system_neutral2_50_dark", "system_neutral2_50_light",
        "system_neutral2_600_dark", "system_neutral2_600_light", "system_neutral2_700_dark", "system_neutral2_700_light", "system_neutral2_800_dark", "system_neutral2_800_light",
        "system_neutral2_900_dark", "system_neutral2_900_light", "system_on_background_dark", "system_on_background_light", "system_on_error_container_dark", "system_on_error_container_light",
        "system_on_error_dark", "system_on_error_light", "system_on_primary_container_dark", "system_on_primary_container_light", "system_on_primary_dark", "system_on_primary_fixed",
        "system_on_primary_fixed_variant", "system_on_primary_light", "system_on_secondary_container_dark", "system_on_secondary_container_light", "system_on_secondary_dark", "system_on_secondary_fixed",
        "system_on_secondary_fixed_variant", "system_on_secondary_light", "system_on_shade_active_dark", "system_on_shade_active_light", "system_on_shade_active_variant_dark", "system_on_shade_active_variant_light",
        "system_on_shade_inactive_dark", "system_on_shade_inactive_light", "system_on_shade_inactive_variant_dark", "system_on_shade_inactive_variant_light", "system_on_surface_dark", "system_on_surface_light",
        "system_on_surface_variant_dark", "system_on_surface_variant_light", "system_on_tertiary_container_dark", "system_on_tertiary_container_light", "system_on_tertiary_dark", "system_on_tertiary_fixed",
        "system_on_tertiary_fixed_variant", "system_on_tertiary_light", "system_on_theme_app_dark", "system_on_theme_app_light", "system_outline_dark", "system_outline_light",
        "system_outline_variant_dark", "system_outline_variant_light", "system_overview_background_dark", "system_overview_background_light", "system_palette_key_color_error_dark", "system_palette_key_color_error_light",
        "system_palette_key_color_neutral_dark", "system_palette_key_color_neutral_light", "system_palette_key_color_neutral_variant_dark", "system_palette_key_color_neutral_variant_light", "system_palette_key_color_primary_dark", "system_palette_key_color_primary_light",
        "system_palette_key_color_secondary_dark", "system_palette_key_color_secondary_light", "system_palette_key_color_tertiary_dark", "system_palette_key_color_tertiary_light", "system_primary_container_dark", "system_primary_container_light",
        "system_primary_dark", "system_primary_dim_dark", "system_primary_dim_light", "system_primary_fixed", "system_primary_fixed_dim", "system_primary_light",
        "system_scrim_dark", "system_scrim_light", "system_secondary_container_dark", "system_secondary_container_light", "system_secondary_dark", "system_secondary_dim_dark",
        "system_secondary_dim_light", "system_secondary_fixed", "system_secondary_fixed_dim", "system_secondary_light", "system_shade_active_dark", "system_shade_active_light",
        "system_shade_disabled_dark", "system_shade_disabled_light", "system_shade_inactive_dark", "system_shade_inactive_light", "system_shadow_dark", "system_shadow_light",
        "system_surface_bright_dark", "system_surface_bright_light", "system_surface_container_dark", "system_surface_container_high_dark", "system_surface_container_high_light", "system_surface_container_highest_dark",
        "system_surface_container_highest_light", "system_surface_container_light", "system_surface_container_low_dark", "system_surface_container_low_light", "system_surface_container_lowest_dark", "system_surface_container_lowest_light",
        "system_surface_dark", "system_surface_dim_dark", "system_surface_dim_light", "system_surface_light", "system_surface_tint_dark", "system_surface_tint_light",
        "system_surface_variant_dark", "system_surface_variant_light", "system_tertiary_container_dark", "system_tertiary_container_light", "system_tertiary_dark", "system_tertiary_dim_dark",
        "system_tertiary_dim_light", "system_tertiary_fixed", "system_tertiary_fixed_dim", "system_tertiary_light", "system_text_hint_inverse_dark", "system_text_hint_inverse_light",
        "system_text_primary_inverse_dark", "system_text_primary_inverse_disable_only_dark", "system_text_primary_inverse_disable_only_light", "system_text_primary_inverse_light", "system_text_secondary_and_tertiary_inverse_dark", "system_text_secondary_and_tertiary_inverse_disabled_dark",
        "system_text_secondary_and_tertiary_inverse_disabled_light", "system_text_secondary_and_tertiary_inverse_light", "system_theme_app_dark", "system_theme_app_light", "system_theme_app_ring_dark", "system_theme_app_ring_light",
        "system_theme_notif_dark", "system_theme_notif_light", "system_under_surface_dark", "system_under_surface_light", "system_weather_temp_dark", "system_weather_temp_light",
        "system_widget_background_dark", "system_widget_background_light",
    )

    private val PALETA = Regex("""^system_(accent[123]|neutral[12])_(\d+)(_light|_dark)?$""")
    private val PAPEL = Regex("""^system_(.+)_(light|dark)$""")

    /** El color de Claude para un recurso. actual: lo que pone ahora Android (para los que no tienen papel fijo). */
    fun color(nombre: String, actual: Int): Int? {
        if ("error" in nombre) return null // el rojo de error se queda como está
        PALETA.find(nombre)?.let { m ->
            val tono = m.groupValues[2].toInt()
            val escala = if (m.groupValues[1].startsWith("neutral") || m.groupValues[1] == "accent2") GRISES else NARANJAS
            return if (escala === NARANJAS) teñir(interpolar(escala, tono)) else interpolar(escala, tono)
        }
        PAPEL.find(nombre)?.let { m ->
            val tabla = if (m.groupValues[2] == "dark") OSCURO else CLARO
            tabla[m.groupValues[1]]?.let { return teñir(it.toInt()) }
        }
        return teñir(convertir(actual))
    }

    private fun interpolar(escala: Map<Int, Long>, tono: Int): Int {
        escala[tono]?.let { return it.toInt() }
        val claves = escala.keys.sorted()
        val a = claves.last { it <= tono }
        val b = claves.first { it >= tono }
        val t = (tono - a).toFloat() / (b - a)
        val ca = escala[a]!!.toInt(); val cb = escala[b]!!.toInt()
        fun mezcla(x: Int, y: Int) = (x + (y - x) * t).toInt()
        return Color.rgb(mezcla(Color.red(ca), Color.red(cb)), mezcla(Color.green(ca), Color.green(cb)), mezcla(Color.blue(ca), Color.blue(cb)))
    }

    /** Para lo demás (reloj, panel rápido, widgets…): misma luminosidad, en gris cálido o en el naranja de Claude. */
    private fun convertir(c: Int): Int {
        val (l, a, b) = lab(c)
        val croma = sqrt(a * a + b * b)
        return if (croma < 12) desdeLab(l, 0.3, 2.0, Color.alpha(c))
        else {
            // Naranja de Claude (h ≈ 45°), con menos color hacia el blanco y el negro.
            val k = (1 - abs(l - 62) / 62 * 0.7).coerceIn(0.15, 1.0)
            desdeLab(l, 33.0 * k, 33.0 * k, Color.alpha(c))
        }
    }

    // ---------- CIELAB (D65) ----------

    private fun lin(v: Double) = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    private fun gam(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055
    private fun f(t: Double) = if (t > 0.008856) cbrt(t) else 7.787 * t + 16.0 / 116
    private fun fi(t: Double) = if (t * t * t > 0.008856) t * t * t else (t - 16.0 / 116) / 7.787

    /** Acento elegido (Ajustes de SIOS › Apariencia): verde, azul o lila; null si es el naranja de Claude. */
    private var acento: Int? = null

    /**
     * Pasa un color del naranja de Claude al acento elegido: misma luminosidad y misma intensidad,
     * con el tono del acento. Los grises (sin color) se quedan como están.
     */
    private fun teñir(c: Int): Int {
        val base = acento ?: return c
        val (l, a, b) = lab(c)
        val croma = sqrt(a * a + b * b)
        if (croma < 12) return c
        val (_, aa, ba) = lab(base)
        val cromaBase = sqrt(aa * aa + ba * ba).coerceAtLeast(1.0)
        return desdeLab(l, a.let { croma * aa / cromaBase }, b.let { croma * ba / cromaBase }, Color.alpha(c))
    }

    private fun lab(c: Int): Triple<Double, Double, Double> {
        val r = lin(Color.red(c) / 255.0); val g = lin(Color.green(c) / 255.0); val b = lin(Color.blue(c) / 255.0)
        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = (0.2126 * r + 0.7152 * g + 0.0722 * b)
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
        return Triple(116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z)))
    }

    private fun desdeLab(l: Double, a: Double, b: Double, alfa: Int): Int {
        val fy = (l + 16) / 116
        val x = 0.95047 * fi(fy + a / 500); val y = fi(fy); val z = 1.08883 * fi(fy - b / 200)
        fun canal(v: Double) = (gam(v).coerceIn(0.0, 1.0) * 255).toInt()
        return Color.argb(alfa, canal(3.2406 * x - 1.5372 * y - 0.4986 * z), canal(-0.9689 * x + 1.8758 * y + 0.0415 * z), canal(0.0557 * x - 0.2040 * y + 1.0570 * z))
    }

    // ---------- Aplicar ----------

    /** Registra (o renueva) la capa «claude» y la pone por encima de la de Android. */
    fun aplicar(ctx: Context): Boolean {
        if (ctx.checkSelfPermission("android.permission.CHANGE_OVERLAY_PACKAGES") != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        return runCatching {
            val res = ctx.resources
            val capa = android.content.om.FabricatedOverlay(NOMBRE, "android")
            // La vía de sistema exige el paquete dueño (el constructor público lo deja vacío).
            capa.javaClass.getMethod("setOwningPackage", String::class.java).invoke(capa, ctx.packageName)
            var n = 0
            // Huella de la paleta de Claude (no de lo leído: eso ya lleva nuestra capa y cambiaría).
            com.sios.ui.Apariencia.leer(ctx)
            val nombreAcento = com.sios.ui.Apariencia.acento
            com.sios.ui.Marca.iniciar(ctx)
            val marca = com.sios.ui.Marca.id
            // Sin IA elegida, SIOS va en blanco y negro con la paleta de Android (la misma que lleva
            // la ROM de serie): no se pone capa encima y, si había una de antes, se quita.
            if (com.sios.ui.Marca.sinElegir && com.sios.ui.Apariencia.sigueIa) {
                val om = ctx.getSystemService(android.content.om.OverlayManager::class.java)
                if (activa(ctx, om)) {
                    val builder = Class.forName("android.content.om.OverlayManagerTransaction\$Builder").getConstructor().newInstance()
                    builder.javaClass.getMethod("unregisterFabricatedOverlay", android.content.om.OverlayIdentifier::class.java)
                        .invoke(builder, capa.identifier)
                    om.commit(builder.javaClass.getMethod("build").invoke(builder) as android.content.om.OverlayManagerTransaction)
                    ctx.getSharedPreferences("colores", Context.MODE_PRIVATE).edit().remove("huella").apply()
                    Log.i(TAG, "Sin IA elegida: colores de SIOS (capa propia quitada)")
                }
                return@runCatching true
            }
            acento = if (com.sios.ui.Apariencia.sigueIa) com.sios.ui.Marca.acento(true).takeIf { marca != "claude" && marca != com.sios.ui.Marca.SIOS }?.toInt()
                else com.sios.ui.Apariencia.ACENTOS[nombreAcento]?.first?.toInt()
            val huella = (OSCURO.toString() + CLARO.toString() + RECURSOS.size + nombreAcento + marca + com.sios.ui.Marca.acento(true)).hashCode()
            for (nombre in RECURSOS) {
                val id = res.getIdentifier(nombre, "color", "android")
                if (id == 0) continue
                val valor = color(nombre, res.getColor(id, null)) ?: continue
                capa.setResourceValue("android:color/$nombre", TypedValue.TYPE_INT_COLOR_ARGB8, valor, null)
                n++
            }
            // OverlayManagerTransaction.Builder y setHighestPriority son API de sistema: igual que SystemUI.
            val om = ctx.getSystemService(android.content.om.OverlayManager::class.java)
            // Si ya está puesta y con los mismos colores, no se toca: volver a registrarla cambia la
            // configuración y Android recrearía las pantallas abiertas (el inicio perdería el panel).
            val prefs = ctx.getSharedPreferences("colores", Context.MODE_PRIVATE)
            val yaActiva = activa(ctx, om)
            if (prefs.getInt("huella", 0) == huella && yaActiva) return@runCatching true
            Log.i(TAG, "Hay que aplicar los colores: huella ${prefs.getInt("huella", 0) == huella}, activa $yaActiva")
            val builder = Class.forName("android.content.om.OverlayManagerTransaction\$Builder").getConstructor().newInstance()
            builder.javaClass.getMethod("registerFabricatedOverlay", android.content.om.FabricatedOverlay::class.java).invoke(builder, capa)
            builder.javaClass.getMethod("setEnabled", android.content.om.OverlayIdentifier::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(builder, capa.identifier, true, android.os.Process.myUserHandle().hashCode())
            val transaccion = builder.javaClass.getMethod("build").invoke(builder) as android.content.om.OverlayManagerTransaction
            om.commit(transaccion)
            runCatching {
                om.javaClass.getMethod("setHighestPriority", String::class.java, UserHandle::class.java).invoke(om, ctx.packageName, android.os.Process.myUserHandle())
            }
            prefs.edit().putInt("huella", huella).apply()
            Log.i(TAG, "Colores de Claude aplicados ($n colores)")
            true
        }.onFailure { Log.w(TAG, "No se pudieron aplicar los colores de Claude", it) }.getOrDefault(false)
    }

    /**
     * Telecom busca la pantalla de llamada del marcador del sistema en una clase fija
     * (com.android.incallui.InCallServiceImpl, la del Teléfono de AOSP). El marcador es Claude, con
     * otro InCallService: sin esto las llamadas salen sin pantalla. La ROM nueva lo trae como overlay
     * (rom/overlay-telecom); aquí se arregla también en los móviles ya instalados.
     */
    fun arreglarTelecom(ctx: Context) {
        val clase = "com.sios.telefono.ServicioLlamadas"
        val telecom = "com.android.server.telecom"
        runCatching {
            val res = ctx.packageManager.getResourcesForApplication(telecom)
            val id = res.getIdentifier("incall_default_class", "string", telecom)
            if (id == 0 || res.getString(id) == clase) return
            if (ctx.checkSelfPermission("android.permission.CHANGE_OVERLAY_PACKAGES") != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val capa = android.content.om.FabricatedOverlay("telecom", telecom)
            capa.javaClass.getMethod("setOwningPackage", String::class.java).invoke(capa, ctx.packageName)
            capa.setResourceValue("$telecom:string/incall_default_class", TypedValue.TYPE_STRING, clase, null)
            val builder = Class.forName("android.content.om.OverlayManagerTransaction\$Builder").getConstructor().newInstance()
            builder.javaClass.getMethod("registerFabricatedOverlay", android.content.om.FabricatedOverlay::class.java).invoke(builder, capa)
            builder.javaClass.getMethod("setEnabled", android.content.om.OverlayIdentifier::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(builder, capa.identifier, true, android.os.Process.myUserHandle().hashCode())
            ctx.getSystemService(android.content.om.OverlayManager::class.java)
                .commit(builder.javaClass.getMethod("build").invoke(builder) as android.content.om.OverlayManagerTransaction)
            Log.i(TAG, "Telecom ya enlaza la pantalla de llamada de Claude")
        }.onFailure { Log.w(TAG, "No se pudo arreglar Telecom", it) }
    }

    /** ¿Está nuestra capa registrada y activa? (API de sistema, por reflexión). */
    private fun activa(ctx: Context, om: android.content.om.OverlayManager): Boolean = runCatching {
        @Suppress("UNCHECKED_CAST")
        val capas = om.javaClass.getMethod("getOverlayInfosForTarget", String::class.java, UserHandle::class.java)
            .invoke(om, "android", android.os.Process.myUserHandle()) as List<Any>
        capas.any { i ->
            i.javaClass.getMethod("getPackageName").invoke(i) == ctx.packageName &&
                i.javaClass.getMethod("getOverlayName").invoke(i) == NOMBRE &&
                i.javaClass.getMethod("isEnabled").invoke(i) == true
        }
    }.onFailure { Log.w(TAG, "No se pudo mirar si la capa está activa", it) }.getOrDefault(false)

    /**
     * Mantener la capa encima: Android regenera la suya cuando cambia el tema; entonces se vuelve a
     * poner la de Claude. Se llama al arrancar la app y al encender el móvil.
     */
    fun vigilar(ctx: Context) {
        val app = ctx.applicationContext
        val principal = Handler(Looper.getMainLooper())
        val reaplicar = Runnable { aplicar(app) }
        val observador = object : ContentObserver(principal) {
            override fun onChange(propio: Boolean) {
                principal.removeCallbacks(reaplicar)
                principal.postDelayed(reaplicar, 2500) // después de que SystemUI ponga la suya
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(Settings.Secure.getUriFor("theme_customization_overlay_packages"), false, observador)
            app.contentResolver.registerContentObserver(Settings.Secure.getUriFor("ui_night_mode"), false, observador)
        }
        aplicar(app)
        principal.postDelayed(reaplicar, 15_000) // tras el arranque, SystemUI aplica la suya un poco después
    }
}
