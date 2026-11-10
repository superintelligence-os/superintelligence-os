package com.sios.ajustes

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.datos.Creacion
import com.sios.datos.Estado
import com.sios.datos.Servicio
import com.sios.ui.BotonIcono
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Marca
import com.sios.ui.Muelle
import com.sios.ui.Texto
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.Normalizer

/*
 * Ajustes de SIOS por categorías: una portada (la IA en uso, el buscador y las categorías) y una
 * subpágina por categoría. Aquí, lo que se comparte: las categorías, el índice del buscador y las
 * piezas con las que se montan las páginas (grupos, filas, barra). El contenido de cada subpágina
 * está en AjustesActivity.kt.
 */

/** Título grande de cada página (el de iOS y Pixel): manda sobre todo lo demás. */
internal val TituloGrande = Texto.Titulo.copy(fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp)

/** Iconos de las categorías que no hay en [Iconos] (mismo trazo fino de 24×24). */
internal object IconosAjustes {
    private fun icono(nombre: String, vararg trazos: String, relleno: String? = null) =
        ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f).apply {
            for (c in trazos) addPath(
                pathData = PathParser().parsePathString(c).toNodes(),
                stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
            if (relleno != null) addPath(pathData = PathParser().parsePathString(relleno).toNodes(), fill = SolidColor(Color.White))
        }.build()

    val Chispas = icono(
        "chispas",
        "M11 3.5l1.6 4.4a2 2 0 0 0 1.2 1.2l4.4 1.6-4.4 1.6a2 2 0 0 0-1.2 1.2L11 17.9l-1.6-4.4a2 2 0 0 0-1.2-1.2L3.8 10.7l4.4-1.6a2 2 0 0 0 1.2-1.2z",
        "M18.5 15.5l.6 1.6 1.6.6-1.6.6-.6 1.6-.6-1.6-1.6-.6 1.6-.6z",
    )
    val Capas = icono("capas", "M12 3.5l8.5 4.5L12 12.5 3.5 8z", "M3.5 12l8.5 4.5 8.5-4.5", "M3.5 16l8.5 4.5 8.5-4.5")
    val Medidor = icono("medidor", "M5.6 18.4a9 9 0 1 1 12.8 0", "M12 13.5l3.5-4")
    val Contraste = icono("contraste", "M12 3a9 9 0 1 1 0 18a9 9 0 0 1 0-18z", relleno = "M12 3a9 9 0 0 1 0 18z")
    val Campana = icono("campana", "M6 9a6 6 0 0 1 12 0c0 6 2.5 8 2.5 8h-17S6 15 6 9z", "M10.3 20.5a2 2 0 0 0 3.4 0")
    val Teclado = icono(
        "teclado_ajustes",
        "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z",
        "M6.5 10h.01", "M10 10h.01", "M14 10h.01", "M17.5 10h.01", "M8 14h8",
    )
    val Personas = icono(
        "personas",
        "M9 4a3.5 3.5 0 1 1 0 7a3.5 3.5 0 0 1 0-7z", "M2.5 20a6.5 6.5 0 0 1 13 0",
        "M16 4.2a3.5 3.5 0 0 1 0 6.6", "M17.5 13.6A6.5 6.5 0 0 1 21.5 20",
    )
    val Escudo = icono("escudo", "M12 3l7.5 3v5.5c0 4.5-3.2 8.2-7.5 9.5-4.3-1.3-7.5-5-7.5-9.5V6z", "M9 12l2 2 4-4")
    val Portatil = icono("portatil", "M5 5.5h14a1 1 0 0 1 1 1V16H4V6.5a1 1 0 0 1 1-1z", "M2.5 19.5h19")
    val Historial = icono("historial", "M3.5 12a8.5 8.5 0 1 0 2.5-6", "M9.5 6H6V2.5", "M12 8v4l2.5 1.5")
    val Info = icono("info", "M12 3a9 9 0 1 1 0 18a9 9 0 0 1 0-18z", "M12 11v5", "M12 7.8h.01")
}

/**
 * Las categorías de Ajustes de SIOS, en el orden de la portada. [color] null: el acento (lo hecho
 * por la IA lleva su color).
 */
internal enum class Pagina(@StringRes val titulo: Int, val icono: () -> ImageVector, val color: Long?) {
    INTELIGENCIA(R.string.ia_titulo, { Iconos.Asistente }, null),
    ASISTENTE(R.string.ajs_asistente, { Iconos.Micro }, 0xFF8B6FD1),
    CREACIONES(R.string.ajustes_creaciones, { IconosAjustes.Chispas }, null),
    MEMORIA(R.string.ajustes_memoria, { IconosAjustes.Capas }, 0xFF3C9A94),
    USO(R.string.ajustes_uso_titulo, { IconosAjustes.Medidor }, 0xFFD99A2B),
    APARIENCIA(R.string.ap_titulo, { IconosAjustes.Contraste }, 0xFF4A82DB),
    NOTIFICACIONES(R.string.ajustes_avisos, { IconosAjustes.Campana }, 0xFFDF5A4E),
    LLAMADAS(R.string.ajustes_llamadas, { Iconos.Llamar }, 0xFF43A363),
    TECLADO(R.string.ajs_teclado, { IconosAjustes.Teclado }, 0xFF77808C),
    APPS(R.string.tienda_titulo, { IconosTienda.Bolsa }, 0xFF2B9FC0),
    COCHE(R.string.coche_titulo, { Iconos.Coche }, 0xFF5566C2),
    ESCRITORIO(R.string.modoesc_titulo, { IconosTienda.Monitor }, 0xFF50739E),
    FAMILIA(R.string.fam_titulo, { IconosAjustes.Personas }, 0xFFD96C92),
    EMERGENCIAS(R.string.em_titulo, { com.sios.emergencias.IconoCategoria }, com.sios.emergencias.COLOR_CATEGORIA),
    PRIVACIDAD(R.string.ajs_privacidad, { IconosAjustes.Escudo }, 0xFF3A74C4),
    ORDENADOR(R.string.ajs_ordenador, { IconosAjustes.Portatil }, 0xFF5B8BA3),
    COPIA(R.string.copia_titulo, { IconosAjustes.Historial }, 0xFF389B71),
    SISTEMA(R.string.ajs_sistema, { IconosAjustes.Info }, 0xFF878580);

    /** El uso del plan solo tiene sentido con Claude (los demás tienen sus propios límites). */
    val visible get() = this != USO || Marca.id == "claude"
}

/** El cuadradito de color con el icono de cada categoría. */
@Composable
internal fun CuadroIcono(p: Pagina, tam: androidx.compose.ui.unit.Dp = 30.dp) {
    val fondo = p.color?.let { Color(it) } ?: Colores.Acento
    val tinta = if (p.color == null) Colores.SobreAcento else Color.White
    Box(Modifier.size(tam).clip(RoundedCornerShape(tam * 0.27f)).background(fondo), contentAlignment = Alignment.Center) {
        Icon(p.icono(), null, tint = tinta, modifier = Modifier.size(tam * 0.6f))
    }
}

// ——— Índice del buscador ———

/**
 * Una opción que se puede buscar: dónde está (página y grupo) y con qué textos se encuentra. [claves]:
 * nombres propios que no se traducen (marcas, «bluetooth»). [visible]: si existe en este móvil.
 */
internal class Opcion(
    val pagina: Pagina,
    val grupo: String?,
    @StringRes val titulo: Int,
    val alias: List<Int> = emptyList(),
    val claves: String = "",
    val visible: (android.content.Context) -> Boolean = { true },
)

private val soloClaude: (android.content.Context) -> Boolean = { Marca.id == "claude" }
private val conPin: (android.content.Context) -> Boolean = { com.sios.sistema.PinAleatorio.disponible(it) }

internal val OPCIONES = listOf(
    Opcion(Pagina.INTELIGENCIA, null, R.string.ia_titulo, claves = "ia ai claude chatgpt openai gemini deepseek kimi mistral grok openrouter qwen llama api"),
    Opcion(Pagina.INTELIGENCIA, "ia", R.string.ia_grupo_suscripcion),
    Opcion(Pagina.INTELIGENCIA, "ia", R.string.ia_grupo_clave),
    Opcion(Pagina.INTELIGENCIA, "ia", R.string.ia_grupo_local),
    Opcion(Pagina.INTELIGENCIA, "conversacion", R.string.selector_modelo, claves = "haiku sonnet opus"),
    Opcion(Pagina.INTELIGENCIA, "conversacion", R.string.selector_esfuerzo, visible = soloClaude),
    Opcion(Pagina.INTELIGENCIA, "conversacion", R.string.selector_permisos),

    Opcion(Pagina.ASISTENTE, null, R.string.ajs_asistente, alias = listOf(R.string.ajustes_voz)),
    Opcion(Pagina.ASISTENTE, "voz", R.string.ajustes_oye_titulo),
    Opcion(Pagina.ASISTENTE, "voz", R.string.ajustes_envoz_titulo),
    Opcion(Pagina.ASISTENTE, "sinconexion", R.string.voz_titulo, alias = listOf(R.string.voz_dictado, R.string.voz_traduccion), claves = "offline whisper vosk opus"),
    Opcion(Pagina.ASISTENTE, "asistente", R.string.ajustes_asistente_titulo),
    Opcion(Pagina.ASISTENTE, "puede", R.string.ajustes_manos_titulo, alias = listOf(R.string.ajustes_manos)),
    Opcion(Pagina.ASISTENTE, "puede", R.string.ajustes_web_titulo, alias = listOf(R.string.ajustes_internet)),

    Opcion(Pagina.CREACIONES, null, R.string.ajustes_creaciones, alias = listOf(R.string.creacion_tipo_widget, R.string.creacion_tipo_app, R.string.creacion_tipo_agente, R.string.creacion_tipo_rutina)),

    Opcion(Pagina.MEMORIA, null, R.string.ajustes_memoria),
    Opcion(Pagina.MEMORIA, "memoria", R.string.ajustes_memoria_titulo),
    Opcion(Pagina.MEMORIA, "pantalla", R.string.mp_titulo, alias = listOf(R.string.mp_b_titulo, R.string.mp_buscar), claves = "recall rewind"),
    Opcion(Pagina.MEMORIA, "pantalla", R.string.mp_excluidas_titulo),
    Opcion(Pagina.MEMORIA, "pantalla", R.string.mp_miniaturas_titulo),

    Opcion(Pagina.USO, null, R.string.ajustes_uso_titulo, visible = soloClaude),
    Opcion(Pagina.USO, "ahorro", R.string.ajustes_ahorro_titulo, visible = soloClaude),

    Opcion(Pagina.APARIENCIA, null, R.string.ap_titulo, alias = listOf(R.string.ap_claro, R.string.ap_oscuro)),
    Opcion(Pagina.APARIENCIA, "apariencia", R.string.ap_acento),
    Opcion(Pagina.APARIENCIA, "accesibilidad", R.string.acc_titulo),
    Opcion(Pagina.APARIENCIA, "accesibilidad", R.string.acc_grande),
    Opcion(Pagina.APARIENCIA, "accesibilidad", R.string.acc_contraste),

    Opcion(Pagina.NOTIFICACIONES, null, R.string.ajustes_avisos),
    Opcion(Pagina.NOTIFICACIONES, "avisos", R.string.ajustes_resumen_titulo),
    Opcion(Pagina.NOTIFICACIONES, "avisos", R.string.ajustes_pendientes_titulo),
    Opcion(Pagina.NOTIFICACIONES, "noche", R.string.noche_titulo),
    Opcion(Pagina.NOTIFICACIONES, "noche", R.string.noche_gris),

    Opcion(Pagina.LLAMADAS, null, R.string.ajustes_llamadas),
    Opcion(Pagina.LLAMADAS, "desconocidos", R.string.ajustes_silenciar_titulo),
    Opcion(Pagina.LLAMADAS, "desconocidos", R.string.ajustes_atender_titulo),

    Opcion(Pagina.TECLADO, null, R.string.ajs_teclado, alias = listOf(R.string.teclado_sios_nombre)),

    Opcion(Pagina.APPS, null, R.string.tienda_titulo, alias = listOf(R.string.tienda_g_tiendas), claves = "f-droid fdroid neo store aurora play"),
    Opcion(Pagina.APPS, "predeterminadas", R.string.tienda_g_predeterminadas, alias = listOf(R.string.tienda_navegador, R.string.tienda_correo, R.string.tienda_mapas, R.string.tienda_mensajes), claves = "browser email"),
    Opcion(Pagina.APPS, "tiendas", R.string.tienda_g_tiendas),
    Opcion(Pagina.ESCRITORIO, null, R.string.modoesc_titulo, alias = listOf(R.string.modoesc_monitor), claves = "desktop dex hdmi displayport usb-c monitor"),
    Opcion(Pagina.ESCRITORIO, "modo", R.string.modoesc_al_conectar, alias = listOf(R.string.modoesc_duplicar)),
    Opcion(Pagina.ESCRITORIO, "modo", R.string.modoesc_todas),
    Opcion(Pagina.ESCRITORIO, "uso", R.string.modoesc_enviar_pantalla, claves = "cast miracast"),
    Opcion(Pagina.COCHE, null, R.string.coche_titulo),
    Opcion(Pagina.COCHE, "coche", R.string.coche_tu_coche, claves = "bluetooth"),
    Opcion(Pagina.COCHE, "coche", R.string.coche_auto_titulo),
    Opcion(Pagina.COCHE, "coche", R.string.coche_leer),
    Opcion(Pagina.COCHE, "coche", R.string.coche_responder),

    Opcion(Pagina.FAMILIA, null, R.string.fam_titulo),
    Opcion(Pagina.FAMILIA, "familia", R.string.fam_invitar),
    Opcion(Pagina.FAMILIA, "familia", R.string.fam_ubicacion),

    Opcion(
        Pagina.EMERGENCIAS, null, R.string.em_titulo, alias = listOf(R.string.em_buscar_alias),
        claves = "sos 112 911 999 emergency notfall urgence urgences emergenza emergencia ice",
    ),
    Opcion(Pagina.EMERGENCIAS, "sos", R.string.em_gesto, alias = listOf(R.string.em_g_sos), claves = "power boton encendido"),
    Opcion(Pagina.EMERGENCIAS, "contactos", R.string.em_contactos_titulo, claves = "ice"),
    Opcion(Pagina.EMERGENCIAS, "ficha", R.string.em_ficha_titulo, alias = listOf(R.string.em_f_alergias, R.string.em_f_sangre), claves = "medical id"),
    Opcion(Pagina.EMERGENCIAS, "caidas", R.string.em_caidas_titulo, claves = "fall detection"),
    Opcion(Pagina.EMERGENCIAS, "viaje", R.string.em_viaje_titulo),
    Opcion(Pagina.EMERGENCIAS, "prueba", R.string.em_prueba_titulo),

    Opcion(Pagina.PRIVACIDAD, null, R.string.ajs_privacidad),
    Opcion(
        Pagina.PRIVACIDAD, "red", R.string.cf_titulo, alias = listOf(R.string.cf_buscar_alias, R.string.cf_g_red),
        claves = "firewall vpn dns adblock netguard tracker ads werbung pare-feu pubblicita publicidade",
    ),
    Opcion(
        Pagina.PRIVACIDAD, "boveda", R.string.bv_titulo, alias = listOf(R.string.bv_buscar_alias, R.string.bv_pestana_codigos, R.string.bv_pestana_documentos),
        claves = "vault password passwort mot de passe senha 2fa totp otp authenticator autofill bitwarden keepass",
    ),
    Opcion(Pagina.PRIVACIDAD, "boveda", R.string.bv_aj_autocompletar),
    Opcion(Pagina.PRIVACIDAD, "salido", R.string.priv_titulo),
    Opcion(Pagina.PRIVACIDAD, "actividad", R.string.actividad_titulo),
    Opcion(Pagina.PRIVACIDAD, "bloqueo", R.string.seg_titulo, visible = conPin),
    Opcion(Pagina.PRIVACIDAD, "bloqueo", R.string.pin_aleatorio_titulo, visible = conPin),

    Opcion(Pagina.ORDENADOR, null, R.string.ajs_ordenador, alias = listOf(R.string.mac_titulo), claves = "mac windows pc"),
    Opcion(Pagina.ORDENADOR, "mac", R.string.mac_vincular),
    Opcion(Pagina.ORDENADOR, "mac", R.string.mac_portapapeles),
    Opcion(Pagina.ORDENADOR, "mac", R.string.mac_avisos),

    Opcion(Pagina.COPIA, null, R.string.copia_titulo, claves = "backup"),
    Opcion(Pagina.COPIA, "copia", R.string.copia_auto_titulo),
    Opcion(Pagina.COPIA, "copia", R.string.copia_restaurar),

    Opcion(Pagina.SISTEMA, null, R.string.ajs_sistema),
    Opcion(Pagina.SISTEMA, "actualizaciones", R.string.act_titulo),
    Opcion(Pagina.SISTEMA, "actualizaciones", R.string.act_solas_titulo),
    Opcion(Pagina.SISTEMA, "sistema_ota", R.string.ota_titulo, claves = "ota rom ranura slot"),
    Opcion(Pagina.SISTEMA, "ayuda", R.string.comp_titulo),
    Opcion(Pagina.SISTEMA, "ayuda", R.string.ajustes_tour_titulo),
    Opcion(Pagina.SISTEMA, "servicio", R.string.ajustes_servicio),
    Opcion(Pagina.SISTEMA, "telefono", R.string.comun_ajustes_telefono),
)

/** Sin mayúsculas ni tildes, letra a letra (así las posiciones coinciden con el texto original). */
private fun plano(s: String): String = buildString {
    for (c in s) append(Normalizer.normalize(c.toString(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().firstOrNull() ?: c)
}

private class Encontrada(val opcion: Opcion, val titulo: String, val orden: Int)

/** Las opciones que casan con todas las palabras de la consulta: primero las que empiezan por ella. */
private fun buscarOpciones(ctx: android.content.Context, consulta: String): List<Encontrada> {
    val q = plano(consulta.trim())
    val palabras = q.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (palabras.isEmpty()) return emptyList()
    return OPCIONES.filter { it.pagina.visible && it.visible(ctx) }.mapNotNull { o ->
        val titulo = ctx.getString(o.titulo)
        val texto = plano((listOf(titulo) + o.alias.map { ctx.getString(it) } + o.claves).joinToString(" "))
        if (!palabras.all { texto.contains(it) }) return@mapNotNull null
        val t = plano(titulo)
        Encontrada(o, titulo, when { t.startsWith(q) -> 0; t.contains(q) -> 1; else -> 2 })
    }.sortedBy { it.orden }.take(30)
}

/** El título con lo buscado resaltado (si está tal cual en él). */
@Composable
private fun resaltar(titulo: String, consulta: String): AnnotatedString {
    val q = plano(consulta.trim())
    val i = if (q.isEmpty()) -1 else plano(titulo).indexOf(q)
    val acento = Colores.Acento
    return buildAnnotatedString {
        if (i < 0) { append(titulo); return@buildAnnotatedString }
        append(titulo.substring(0, i))
        withStyle(SpanStyle(color = acento, fontWeight = FontWeight.SemiBold)) { append(titulo.substring(i, i + q.length)) }
        append(titulo.substring(i + q.length))
    }
}

// ——— Piezas de las páginas ———

/** Cómo se presenta el contenido de un grupo. */
internal enum class Estilo {
    /** En una tarjeta, con margen: interruptores, campos y botones. */
    Tarjeta,
    /** En una tarjeta sin margen: filas que llevan a otro sitio, separadas por una línea. */
    Lista,
    /** Sin tarjeta: secciones que ya traen sus propias tarjetas (la IA, lo que ha salido del móvil…). */
    Libre,
}

/**
 * Dónde está cada grupo de una subpágina, para que el buscador lleve a él y lo ilumine un momento.
 */
@Stable
internal class Anclas {
    var contenido: LayoutCoordinates? = null
    val grupos = HashMap<String, LayoutCoordinates>()
    var destello by mutableStateOf<String?>(null)
}

internal val LocalAnclas = compositionLocalOf<Anclas?> { null }

/**
 * Un grupo de una subpágina: encabezado pequeño, una nota corta si hace falta explicar algo y el
 * contenido (en tarjeta, en lista o tal cual).
 */
@Composable
internal fun Grupo(id: String, titulo: String?, nota: String?, estilo: Estilo, contenido: @Composable () -> Unit) {
    val anclas = LocalAnclas.current
    val brillo = remember { Animatable(0f) }
    val destacado = anclas?.destello == id
    LaunchedEffect(destacado) {
        if (!destacado) return@LaunchedEffect
        brillo.animateTo(1f, Muelle.suave())
        delay(900)
        brillo.animateTo(0f, Muelle.suave())
        anclas?.destello = null
    }
    val densidad = LocalDensity.current
    val acento = Colores.Acento
    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { anclas?.grupos?.put(id, it) }
            // El destello del buscador: un halo suave alrededor del grupo, sin mover nada.
            .drawBehind {
                val b = brillo.value
                if (b <= 0f) return@drawBehind
                val m = with(densidad) { 10.dp.toPx() }
                drawRoundRect(
                    acento.copy(alpha = 0.16f * b), topLeft = Offset(-m, -m),
                    size = Size(size.width + 2 * m, size.height + 2 * m), cornerRadius = CornerRadius(m * 2.2f),
                )
            },
    ) {
        if (titulo != null) Text(
            titulo.uppercase(), style = Texto.Etiqueta, color = Colores.Texto3,
            modifier = Modifier.padding(bottom = if (nota != null) 4.dp else 8.dp),
        )
        if (nota != null) Text(nota, style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(bottom = 12.dp))
        when (estilo) {
            Estilo.Tarjeta -> Column(
                Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(16.dp).animateContentSize(Muelle.suave()),
            ) { contenido() }
            Estilo.Lista -> Column(
                Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).animateContentSize(Muelle.suave()),
            ) { contenido() }
            Estilo.Libre -> contenido()
        }
    }
}

/**
 * Fila de una lista (portada, resultados del buscador, enlaces): al pulsar se ilumina con un muelle,
 * sin encogerse, para que la lista no pierda la alineación.
 */
@Composable
internal fun FilaLista(
    titulo: AnnotatedString,
    nota: String?,
    separador: Boolean,
    icono: (@Composable () -> Unit)? = null,
    final: (@Composable () -> Unit)? = null,
    notaLineas: Int = 2,
    onClick: () -> Unit,
) {
    val interaccion = remember { MutableInteractionSource() }
    val pulsado by interaccion.collectIsPressedAsState()
    val fondo by animateColorAsState(if (pulsado) Colores.Superficie2 else Colores.Superficie.copy(alpha = 0f), Muelle.suave(), label = "fila")
    Box(Modifier.fillMaxWidth().background(fondo).clickable(interaccion, indication = null, onClick = onClick)) {
        Row(
            // Con icono, todas a la misma altura (lleven nota o no): la lista se lee como una columna.
            Modifier.fillMaxWidth().heightIn(min = if (icono != null) 64.dp else 52.dp).padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icono != null) { icono(); Spacer(Modifier.width(14.dp)) }
            Column(Modifier.weight(1f)) {
                Text(titulo, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (nota != null) Text(nota, style = Texto.Pequeno, color = Colores.Texto3, maxLines = notaLineas, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            final?.invoke()
            Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
        }
        // La línea empieza donde empieza el texto (como en iOS), no en el borde.
        if (separador) Box(
            Modifier.align(Alignment.BottomStart).padding(start = if (icono != null) 60.dp else 16.dp)
                .fillMaxWidth().height(1.dp).background(Colores.Borde),
        )
    }
}

@Composable
internal fun FilaLista(
    titulo: String, nota: String?, separador: Boolean, icono: (@Composable () -> Unit)? = null, notaLineas: Int = 2, onClick: () -> Unit,
) = FilaLista(AnnotatedString(titulo), nota, separador, icono, null, notaLineas, onClick)

/**
 * Barra de arriba: atrás y, cuando el título grande se va con el desplazamiento, el título pequeño
 * (aparece poco a poco, siguiendo al dedo; se lee en la capa, sin recomponer).
 */
@Composable
internal fun BarraSuperior(titulo: String, scroll: ScrollState, onAtras: () -> Unit) {
    val densidad = LocalDensity.current
    val desde = with(densidad) { 36.dp.toPx() }
    val tramo = with(densidad) { 28.dp.toPx() }
    fun alfa() = ((scroll.value - desde) / tramo).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        BotonIcono(Iconos.Atras, stringResource(R.string.comun_atras), Modifier.align(Alignment.CenterStart).padding(start = 8.dp), onClick = onAtras)
        Text(
            titulo, style = Texto.Subtitulo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp).graphicsLayer { alpha = alfa() },
        )
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).graphicsLayer { alpha = alfa() }.background(Colores.Borde))
    }
}

/**
 * Una subpágina: barra, título grande y los grupos. Si se llega desde el buscador ([destino]), se
 * baja hasta ese grupo y se ilumina.
 */
@Composable
internal fun Subpagina(p: Pagina, destino: String?, onAtras: () -> Unit, contenido: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val anclas = remember { Anclas() }
    val margen = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(destino) {
        if (destino == null) return@LaunchedEffect
        // Primero que termine de entrar la página (y de cargar lo que pide al servicio).
        delay(380)
        val g = anclas.grupos[destino]
        val raiz = anclas.contenido
        if (g != null && raiz != null && g.isAttached && raiz.isAttached) {
            val y = raiz.localPositionOf(g, Offset.Zero).y
            scroll.animateScrollTo((y - margen).toInt().coerceAtLeast(0), Muelle.suave())
        }
        anclas.destello = destino
    }
    val titulo = stringResource(p.titulo)
    Column(
        Modifier
            .fillMaxSize()
            .background(Colores.Fondo)
            // Encima de la portada: que ningún toque la atraviese.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .statusBarsPadding(),
    ) {
        BarraSuperior(titulo, scroll, onAtras)
        CompositionLocalProvider(LocalAnclas provides anclas) {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(scroll)
                    .onGloballyPositioned { anclas.contenido = it }
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 2.dp, bottom = 40.dp),
            ) {
                Text(titulo, style = TituloGrande, color = Colores.Texto)
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(28.dp)) { contenido() }
            }
        }
    }
}

// ——— Portada ———

/**
 * La portada: cabecera, buscador, la IA en uso y las categorías agrupadas, cada una con una línea
 * que resume lo que hay dentro. Al escribir en el buscador, las categorías dejan sitio a los resultados.
 */
@Composable
internal fun Portada(
    estado: Estado,
    creaciones: List<Creacion>,
    refresco: Int,
    onAbrir: (Pagina, String?) -> Unit,
    onAtras: () -> Unit,
) {
    val scroll = rememberScrollState()
    var consulta by rememberSaveable { mutableStateOf("") }
    val foco = LocalFocusManager.current
    val abrir: (Pagina, String?) -> Unit = { p, g -> foco.clearFocus(); onAbrir(p, g) }
    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding()) {
        BarraSuperior(stringResource(R.string.comun_ajustes_ia), scroll, onAtras)
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(scroll)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 2.dp, bottom = 40.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.sios.ui.Sios(Modifier.size(30.dp))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.comun_ajustes_ia), style = TituloGrande, color = Colores.Texto)
            }
            Spacer(Modifier.height(18.dp))
            Buscador(consulta) { consulta = it }
            Spacer(Modifier.height(20.dp))
            AnimatedContent(
                consulta.isBlank(),
                transitionSpec = {
                    (fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave())).using(SizeTransform(clip = false) { _, _ -> Muelle.suave() })
                },
                label = "portada",
            ) { sinBuscar ->
                if (sinBuscar) Categorias(estado, creaciones, refresco, abrir)
                else Resultados(consulta, abrir)
            }
        }
    }
}

@Composable
private fun Buscador(consulta: String, onCambio: (String) -> Unit) {
    val foco = LocalFocusManager.current
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(Formas.Pequena).background(Colores.Superficie2).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Iconos.Buscar, null, tint = Colores.Texto3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (consulta.isEmpty()) Text(stringResource(R.string.ajs_buscar), style = Texto.Cuerpo, color = Colores.Texto3, maxLines = 1)
            BasicTextField(
                consulta, onCambio, singleLine = true,
                textStyle = Texto.Cuerpo.copy(color = Colores.Texto),
                cursorBrush = SolidColor(Colores.Acento),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { foco.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedVisibility(
            consulta.isNotEmpty(),
            enter = fadeIn(Muelle.suave()) + scaleIn(Muelle.suave(), initialScale = 0.6f),
            exit = fadeOut(Muelle.suave()) + scaleOut(Muelle.suave(), targetScale = 0.6f),
        ) {
            BotonIcono(Iconos.Cruz, stringResource(R.string.ajs_borrar), tam = 36.dp, color = Colores.Texto3) { onCambio("") }
        }
    }
}

/** Una tarjeta con filas de categorías (solo las que se ven en este móvil). */
@Composable
private fun GrupoCategorias(paginas: List<Pagina>, resumen: @Composable (Pagina) -> String, onAbrir: (Pagina, String?) -> Unit) {
    val visibles = paginas.filter { it.visible }
    if (visibles.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).animateContentSize(Muelle.suave())) {
        visibles.forEachIndexed { i, p ->
            FilaLista(stringResource(p.titulo), resumen(p), separador = i < visibles.lastIndex, icono = { CuadroIcono(p) }, notaLineas = 1) { onAbrir(p, null) }
        }
    }
}

@Composable
private fun Categorias(estado: Estado, creaciones: List<Creacion>, refresco: Int, onAbrir: (Pagina, String?) -> Unit) {
    val ctx = LocalContext.current
    // Lo que se lee de las preferencias se vuelve a leer al volver de una subpágina o de otra app.
    val memoria = remember(refresco) { com.sios.sistema.Memoria.activa(ctx) }
    val teclado = remember(refresco) { com.sios.teclado.Teclados.enUso(ctx) }
    val copia = remember(refresco) { com.sios.sistema.Copia.ultima(ctx) }
    val pin = remember { com.sios.sistema.PinAleatorio.disponible(ctx) }
    // Con el cortafuegos encendido, la privacidad lo resume: lo bloqueado hoy.
    val cortafuegos = remember(refresco) {
        if (com.sios.cortafuegos.Cortafuegos.quiereActivo(ctx)) com.sios.cortafuegos.Registro.bloqueadosHoy(ctx) else -1
    }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val emergencias = remember(refresco) { com.sios.emergencias.Emergencias.contactos(ctx).isNotEmpty() }
    val resumen: @Composable (Pagina) -> String = { p ->
        when (p) {
            Pagina.INTELIGENCIA -> ""
            Pagina.ASISTENTE -> stringResource(R.string.ajs_r_asistente)
            Pagina.CREACIONES ->
                if (creaciones.isEmpty()) stringResource(R.string.ajs_r_creaciones_vacio)
                else androidx.compose.ui.res.pluralStringResource(R.plurals.ajs_r_creaciones, creaciones.size, creaciones.size)
            Pagina.MEMORIA -> stringResource(if (memoria) R.string.ajs_r_memoria else R.string.ajustes_memoria_apagada)
            Pagina.USO -> estado.uso?.semana?.porcentaje?.let { stringResource(R.string.ajs_r_uso, it) } ?: stringResource(R.string.ajs_r_uso_sin)
            Pagina.APARIENCIA -> stringResource(R.string.ajs_r_apariencia)
            Pagina.NOTIFICACIONES -> stringResource(R.string.ajs_r_notificaciones)
            Pagina.LLAMADAS -> stringResource(R.string.ajs_r_llamadas)
            Pagina.TECLADO -> stringResource(if (teclado) R.string.ajs_r_teclado_si else R.string.ajs_r_teclado_no)
            Pagina.APPS -> resumenApps(refresco)
            Pagina.COCHE -> stringResource(R.string.ajs_r_coche)
            Pagina.ESCRITORIO -> resumenEscritorio(refresco)
            Pagina.FAMILIA -> stringResource(R.string.ajs_r_familia)
            Pagina.EMERGENCIAS -> stringResource(if (emergencias) R.string.em_r_resumen else R.string.em_r_sin_contactos)
            Pagina.PRIVACIDAD ->
                if (cortafuegos >= 0) androidx.compose.ui.res.pluralStringResource(R.plurals.cf_r_privacidad, cortafuegos, com.sios.cortafuegos.numero(cortafuegos))
                else stringResource(if (pin) R.string.ajs_r_privacidad else R.string.ajs_r_privacidad_sin_pin)
            Pagina.ORDENADOR -> stringResource(R.string.ajs_r_ordenador)
            Pagina.COPIA -> if (copia > 0) stringResource(R.string.ajs_r_copia, com.sios.ui.haceRato(ctx, copia)) else stringResource(R.string.ajs_r_copia_nunca)
            Pagina.SISTEMA -> stringResource(R.string.act_version, version) +
                (if (estado.conectado) "" else " · " + stringResource(R.string.ajustes_sin_conexion))
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TarjetaIaEnUso(estado) { onAbrir(Pagina.INTELIGENCIA, null) }
        // Lo que hace la IA; cómo se ve y se usa el móvil; tus datos y el sistema.
        GrupoCategorias(listOf(Pagina.ASISTENTE, Pagina.CREACIONES, Pagina.MEMORIA, Pagina.USO), resumen, onAbrir)
        GrupoCategorias(listOf(Pagina.APARIENCIA, Pagina.NOTIFICACIONES, Pagina.LLAMADAS, Pagina.TECLADO, Pagina.APPS, Pagina.COCHE, Pagina.ESCRITORIO, Pagina.FAMILIA), resumen, onAbrir)
        GrupoCategorias(listOf(Pagina.EMERGENCIAS, Pagina.PRIVACIDAD, Pagina.ORDENADOR, Pagina.COPIA, Pagina.SISTEMA), resumen, onAbrir)
    }
}

private fun JSONObject.texto(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }

/**
 * La IA en uso, arriba del todo (como la cuenta en iOS): su logo, su nombre y con qué cuenta y modelo
 * funciona. Lleva a Inteligencia.
 */
@Composable
private fun TarjetaIaEnUso(estado: Estado, onClick: () -> Unit) {
    val ia by Servicio.ia.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { Servicio.cargarIa() }
    val e = ia
    val activo = e?.takeIf { it.optBoolean("elegida", true) }?.texto("activo")
    val lista = e?.optJSONArray("proveedores")
    val p = (0 until (lista?.length() ?: 0)).map { lista!!.getJSONObject(it) }.firstOrNull { it.optString("id") == activo }
    val sinElegir = if (e != null) p == null else Marca.sinElegir
    val id = p?.optString("id") ?: Marca.id
    val color = p?.let { colorIa(it) } ?: Color(Marca.acento(!com.sios.ui.Apariencia.claro))
    val nombre = if (sinElegir) stringResource(R.string.ajs_ia_elegir) else p?.optString("nombre") ?: Marca.nombre
    val nota = if (sinElegir) stringResource(R.string.ajs_ia_elegir_nota) else {
        val cuenta = p?.texto("cuenta") ?: p?.texto("clave")?.let { stringResource(R.string.ia_con_clave, it) }
        val modelo = when {
            id == "claude" -> stringResource(com.sios.ui.familiaDe(estado.ajustes.modelo).nombre)
            id == "local" -> e?.optJSONObject("local")?.optJSONArray("modelos")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optBoolean("elegido") }?.texto("nombre")
            }
            else -> p?.texto("modelo")?.takeIf { it != "auto" }
        }
        listOfNotNull(cuenta, modelo).joinToString(" · ").ifBlank { stringResource(R.string.ia_listo) }
    }
    val interaccion = remember { MutableInteractionSource() }
    val pulsado by interaccion.collectIsPressedAsState()
    val fondo by animateColorAsState(if (pulsado) Colores.Superficie2 else Colores.Superficie, Muelle.suave(), label = "ia")
    Row(
        Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(fondo)
            .clickable(interaccion, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(if (sinElegir) Colores.Superficie2 else color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            if (sinElegir) com.sios.ui.Sios(Modifier.size(26.dp)) else LogoIa(id, 26.dp, color)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(nombre, style = Texto.Subtitulo.copy(fontSize = 17.sp), color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(nota, style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Icon(Iconos.Derecha, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun Resultados(consulta: String, onAbrir: (Pagina, String?) -> Unit) {
    val ctx = LocalContext.current
    val encontradas = remember(consulta, Marca.nombre) { buscarOpciones(ctx, consulta) }
    if (encontradas.isEmpty()) {
        Text(
            stringResource(R.string.ajs_buscar_nada, consulta.trim()), style = Texto.Cuerpo, color = Colores.Texto3,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 36.dp, start = 12.dp, end = 12.dp),
        )
        return
    }
    Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).animateContentSize(Muelle.suave())) {
        encontradas.forEachIndexed { i, r ->
            val enLaPagina = r.opcion.grupo != null
            FilaLista(
                resaltar(r.titulo, consulta),
                // Dónde está: la categoría (si no es la propia categoría).
                if (enLaPagina) stringResource(r.opcion.pagina.titulo) else null,
                separador = i < encontradas.lastIndex,
                icono = { CuadroIcono(r.opcion.pagina) },
                notaLineas = 1,
            ) { onAbrir(r.opcion.pagina, r.opcion.grupo) }
        }
    }
}
