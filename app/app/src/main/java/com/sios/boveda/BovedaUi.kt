package com.sios.boveda

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Montserrat
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Iconos propios de la Bóveda (el mismo trazo fino de 24×24 que los de la app). */
internal object IconosBoveda {
    private fun icono(nombre: String, vararg trazos: String) = ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f).apply {
        for (c in trazos) addPath(
            pathData = PathParser().parsePathString(c).toNodes(),
            stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
    }.build()

    val Boveda = icono(
        "bv_boveda",
        "M5 3.5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-12a2 2 0 0 1 2-2z",
        "M12 8a3.5 3.5 0 1 1 0 7a3.5 3.5 0 0 1 0-7z", "M12 11.5v.01", "M6.5 9.5v4", "M7 19.5V21", "M17 19.5V21",
    )
    val Candado = icono("bv_candado", "M6 10.5h12a1.5 1.5 0 0 1 1.5 1.5v7a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 19v-7A1.5 1.5 0 0 1 6 10.5z", "M8 10.5V7.5a4 4 0 0 1 8 0v3", "M12 14.5v2")
    val Llave = icono("bv_llave", "M8 11.5a3.75 3.75 0 1 1 0 7.5a3.75 3.75 0 0 1 0-7.5z", "M10.7 12.6L19.5 3.8", "M16.2 7.1l2.6 2.6", "M13.6 9.7l1.9 1.9")
    val Ojo = icono("bv_ojo", "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", "M12 9.5a2.5 2.5 0 1 1 0 5a2.5 2.5 0 0 1 0-5z")
    val OjoNo = icono("bv_ojo_no", "M2.5 12s3.5-6.5 9.5-6.5c1.6 0 3 .5 4.3 1.1", "M21.5 12s-3.5 6.5-9.5 6.5c-1.6 0-3-.5-4.3-1.1", "M10.2 13.8a2.5 2.5 0 0 1 3.6-3.6", "M4 20L20 4")
    val Copiar = icono("bv_copiar", "M9 9h9.5a1.5 1.5 0 0 1 1.5 1.5V20a1.5 1.5 0 0 1-1.5 1.5H9A1.5 1.5 0 0 1 7.5 20v-9.5A1.5 1.5 0 0 1 9 9z", "M4 15V4.5A1.5 1.5 0 0 1 5.5 3H15")
    val Dados = icono("bv_dados", "M5.5 3.5h13a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-13a2 2 0 0 1 2-2z", "M8.5 8.5h.01", "M15.5 8.5h.01", "M12 12h.01", "M8.5 15.5h.01", "M15.5 15.5h.01")
    val Qr = icono("bv_qr", "M4 4h6v6H4z", "M14 4h6v6h-6z", "M4 14h6v6H4z", "M14 14h2.5v2.5H14z", "M18.5 18.5H20V20h-1.5z", "M14 19.5v.5h.5", "M19.5 14h.5v.5", "M7 7h.01", "M17 7h.01", "M7 17h.01")
    val Escudo = icono("bv_escudo", "M12 3l7.5 3v5.5c0 4.5-3.2 8.2-7.5 9.5-4.3-1.3-7.5-5-7.5-9.5V6z", "M9 12l2 2 4-4")
    val EscudoAviso = icono("bv_escudo_aviso", "M12 3l7.5 3v5.5c0 4.5-3.2 8.2-7.5 9.5-4.3-1.3-7.5-5-7.5-9.5V6z", "M12 8.5v4", "M12 15.5h.01")
    val Aviso = icono("bv_aviso", "M12 4l9 15.5H3z", "M12 10v4", "M12 16.8h.01")
    val Tarjeta = icono("bv_tarjeta", "M4.5 5.5h15a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", "M2.5 10h19", "M6 15h4")
    val Dni = icono("bv_dni", "M4.5 5.5h15a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", "M8.5 9.5a2 2 0 1 1 0 4a2 2 0 0 1 0-4z", "M5.8 16.2a3 3 0 0 1 5.4 0", "M14 10h4", "M14 13.5h3")
    val Pasaporte = icono("bv_pasaporte", "M6.5 3h11a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-15A1.5 1.5 0 0 1 6.5 3z", "M12 7.5a3 3 0 1 1 0 6a3 3 0 0 1 0-6z", "M9.5 17h5")
    val Paraguas = icono("bv_paraguas", "M3 12a9 9 0 0 1 18 0z", "M12 12v6.5a2 2 0 0 1-4 0", "M12 3v-.5")
    val Salud = icono("bv_salud", "M12 20.5s-7.5-4.6-7.5-10A4.3 4.3 0 0 1 12 7.6a4.3 4.3 0 0 1 7.5 2.9c0 5.4-7.5 10-7.5 10z", "M12 10.5v5", "M9.5 13h5")
    val Engranaje = icono("bv_engranaje", "M12 9a3 3 0 1 1 0 6a3 3 0 0 1 0-6z", "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 0 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 0 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 0 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 0 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z")
    val Imagen = icono("bv_imagen", "M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5z", "M8.5 8.5h.01", "M20.5 15l-5-5L5 19.5")
    val Subir = icono("bv_subir", "M12 15V4", "M8 8l4-4 4 4", "M5 13v6h14v-6")
    val Bajar = icono("bv_bajar", "M12 4v11", "M8 11l4 4 4-4", "M5 13v6h14v-6")
}

/** Estado común de la pantalla: avisos cortos («Copiado») en una pastilla abajo. */
@Stable
internal class Avisador {
    var texto by mutableStateOf<String?>(null)
    var cuenta by mutableStateOf(0)
    fun decir(t: String) { texto = t; cuenta++ }
}

@Composable
internal fun PastillaAviso(a: Avisador, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(a.cuenta) {
        if (a.texto == null) return@LaunchedEffect
        visible = true
        delay(1700)
        visible = false
    }
    AnimatedVisibility(
        visible, modifier,
        enter = fadeIn(Muelle.suave()) + slideInVertically(Muelle.suave()) { it / 2 } + scaleIn(Muelle.suave(), initialScale = 0.92f),
        exit = fadeOut(Muelle.suave()) + slideOutVertically(Muelle.suave()) { it / 2 } + scaleOut(Muelle.suave(), targetScale = 0.92f),
    ) {
        Row(
            Modifier.clip(Formas.Pastilla).background(Colores.Texto).padding(horizontal = 18.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Iconos.Hecho, null, tint = Colores.Fondo, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(a.texto.orEmpty(), style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Fondo)
        }
    }
}

/** Colores apagados para las iniciales (siempre el mismo para el mismo nombre). */
private val TONOS = listOf(0xFF3C9A94, 0xFF4A82DB, 0xFF8B6FD1, 0xFFD96C92, 0xFFDF5A4E, 0xFFD99A2B, 0xFF43A363, 0xFF5566C2, 0xFF5B8BA3, 0xFF389B71)

internal fun tonoDe(s: String): Color = Color(TONOS[Math.floorMod(Generador.plano(s).hashCode(), TONOS.size)])

/** Iconos de las apps unidas a una cuenta, pintados una vez y guardados. */
private object IconosApps {
    private val guardados = android.util.LruCache<String, ImageBitmap>(120)
    fun guardado(p: String): ImageBitmap? = guardados.get(p)
    fun de(ctx: Context, p: String): ImageBitmap? = guardados.get(p) ?: runCatching {
        val d = ctx.packageManager.getApplicationIcon(p)
        val b = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, 108, 108)
        d.draw(Canvas(b))
        b.asImageBitmap()
    }.getOrNull()?.also { guardados.put(p, it) }
}

/**
 * El distintivo de una entrada: el icono de su app si está instalada; si no, la inicial sobre un
 * color propio. Los documentos, el icono de su categoría.
 */
@Composable
internal fun Distintivo(e: Entrada, tam: Dp = 40.dp) {
    if (e.tipo == Tipo.DOCUMENTO) {
        val c = Categoria.de(e.categoria)
        Box(Modifier.size(tam).clip(RoundedCornerShape(tam * 0.28f)).background(colorCategoria(c).copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(iconoCategoria(c), null, tint = colorCategoria(c), modifier = Modifier.size(tam * 0.55f))
        }
        return
    }
    val ctx = LocalContext.current
    val paquete = e.sitios.firstOrNull { Sitios.esApp(it) }?.removePrefix(Sitios.APP)
    val icono by produceState(paquete?.let { IconosApps.guardado(it) }, paquete) {
        if (paquete != null) value = withContext(Dispatchers.IO) { IconosApps.de(ctx, paquete) }
    }
    val img = icono
    if (img != null) {
        Image(img, null, Modifier.size(tam).clip(RoundedCornerShape(tam * 0.26f)))
        return
    }
    val nombre = e.nombre.ifBlank { e.sitioVisible ?: e.usuario }.ifBlank { "?" }
    val tono = tonoDe(nombre)
    Box(Modifier.size(tam).clip(RoundedCornerShape(tam * 0.28f)).background(tono.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
        Text(
            nombre.trim().first().uppercase(), color = tono,
            style = TextStyle(fontFamily = Montserrat, fontWeight = FontWeight.SemiBold, fontSize = (tam.value * 0.42f).sp),
        )
    }
}

internal fun iconoCategoria(c: Categoria): ImageVector = when (c) {
    Categoria.DNI -> IconosBoveda.Dni
    Categoria.PASAPORTE -> IconosBoveda.Pasaporte
    Categoria.CONDUCIR -> Iconos.Coche
    Categoria.TARJETA -> IconosBoveda.Tarjeta
    Categoria.SEGURO -> IconosBoveda.Paraguas
    Categoria.SALUD -> IconosBoveda.Salud
    Categoria.OTRO -> Iconos.Documento
}

internal fun colorCategoria(c: Categoria): Color = Color(
    when (c) {
        Categoria.DNI -> 0xFF4A82DB
        Categoria.PASAPORTE -> 0xFF5566C2
        Categoria.CONDUCIR -> 0xFF3C9A94
        Categoria.TARJETA -> 0xFFD99A2B
        Categoria.SEGURO -> 0xFF8B6FD1
        Categoria.SALUD -> 0xFFDF5A4E
        Categoria.OTRO -> 0xFF878580
    },
)

internal fun nombreCategoria(c: Categoria): Int = when (c) {
    Categoria.DNI -> R.string.bv_cat_dni
    Categoria.PASAPORTE -> R.string.bv_cat_pasaporte
    Categoria.CONDUCIR -> R.string.bv_cat_conducir
    Categoria.TARJETA -> R.string.bv_cat_tarjeta
    Categoria.SEGURO -> R.string.bv_cat_seguro
    Categoria.SALUD -> R.string.bv_cat_salud
    Categoria.OTRO -> R.string.bv_cat_otro
}

/**
 * Pestañas de la Bóveda: como el segmentado de Ajustes, con la letra algo más pequeña para que
 * «Contraseñas» quepa en todos los idiomas.
 */
@Composable
internal fun Pestanas(opciones: List<String>, elegida: Int, onElegir: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(Formas.Pastilla).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Pastilla).padding(4.dp),
    ) {
        opciones.forEachIndexed { i, o ->
            val esta = i == elegida
            val fondo by animateColorAsState(if (esta) Colores.Acento else Colores.Superficie, Muelle.suave(), label = "pestana")
            val tinta by animateColorAsState(if (esta) Colores.SobreAcento else Colores.Texto2, Muelle.suave(), label = "tinta")
            Box(
                Modifier.weight(1f).clip(Formas.Pastilla).background(fondo).pulsable { onElegir(i) }.padding(vertical = 9.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(o, style = Texto.Pequeno.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold), color = tinta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Una pastilla pequeña con icono («Generar», «Escanear»): acción secundaria junto a un campo. */
@Composable
internal fun PastillaAccion(texto: String, icono: ImageVector, modifier: Modifier = Modifier, acento: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.clip(Formas.Pastilla).background(if (acento) Colores.AcentoSuave else Colores.Superficie2).pulsable(onClick).padding(start = 10.dp, end = 13.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icono, null, tint = if (acento) Colores.Acento else Colores.Texto2, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(texto, style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = if (acento) Colores.Acento else Colores.Texto2, maxLines = 1)
    }
}

/** Botón de acción peligrosa: como el principal, en rojo. */
@Composable
internal fun BotonPeligro(texto: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(46.dp).clip(Formas.Pequena).background(Colores.Rojo).pulsable(onClick), contentAlignment = Alignment.Center) {
        Text(texto, style = Texto.Subtitulo, color = Color.White)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Hoja(onCerrar: () -> Unit, contenido: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onCerrar,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Colores.Lateral,
        contentColor = Colores.Texto,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) { contenido() }
    }
}

/**
 * Un campo de formulario: la etiqueta pequeña encima y la caja. [secreto]: puntos en vez de letras
 * (con el ojo para verlo). [final]: botones a la derecha, dentro de la caja.
 */
@Composable
internal fun Campo(
    etiqueta: String,
    valor: String,
    onCambio: (String) -> Unit,
    modifier: Modifier = Modifier,
    pista: String? = null,
    secreto: Boolean = false,
    mono: Boolean = false,
    lineas: Int = 1,
    teclado: KeyboardType = KeyboardType.Text,
    mayusculas: KeyboardCapitalization = KeyboardCapitalization.None,
    final: (@Composable () -> Unit)? = null,
) {
    var ver by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Text(etiqueta, style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(Formas.Pequena).background(Colores.Superficie2).padding(start = 14.dp, end = 4.dp),
            verticalAlignment = if (lineas > 1) Alignment.Top else Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 13.dp), contentAlignment = if (lineas > 1) Alignment.TopStart else Alignment.CenterStart) {
                val estilo = (if (mono) Texto.Codigo.copy(fontSize = 15.sp) else Texto.Cuerpo).copy(color = Colores.Texto)
                if (valor.isEmpty() && pista != null) Text(pista, style = estilo.copy(color = Colores.Texto3), maxLines = lineas, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    valor, onCambio,
                    singleLine = lineas == 1,
                    minLines = if (lineas > 1) 3 else 1,
                    textStyle = estilo,
                    cursorBrush = SolidColor(Colores.Acento),
                    visualTransformation = if (secreto && !ver) PasswordVisualTransformation('●') else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (secreto) KeyboardType.Password else teclado,
                        capitalization = mayusculas,
                        autoCorrectEnabled = !secreto && !mono && teclado == KeyboardType.Text && lineas > 1,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (secreto) {
                com.sios.ui.BotonIcono(
                    if (ver) IconosBoveda.OjoNo else IconosBoveda.Ojo,
                    stringResource(if (ver) R.string.bv_ocultar else R.string.bv_ver),
                    tam = 40.dp, color = Colores.Texto3,
                ) { ver = !ver }
            }
            final?.invoke()
            if (!secreto && final == null) Spacer(Modifier.width(10.dp))
        }
    }
}

/** Una contraseña oculta: puntos de verdad (el «•» de Montserrat se queda en nada). */
@Composable
internal fun PuntosSecretos(n: Int, modifier: Modifier = Modifier) {
    val color = Colores.Texto
    Row(modifier.height(23.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(n) { Box(Modifier.size(7.dp).clip(CircleShape).background(color)) }
    }
}

/** La fuerza de una contraseña: cuatro rayitas que se llenan con un muelle y su nombre. */
@Composable
internal fun MedidorFuerza(contrasena: String, modifier: Modifier = Modifier) {
    val nivel = if (contrasena.isEmpty()) -1 else Fuerza.nivel(contrasena)
    val color by animateColorAsState(colorFuerza(nivel), Muelle.suave(), label = "fuerza")
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 4) {
            val lleno by animateFloatAsState(if (nivel >= 0 && i <= (nivel - 1).coerceAtLeast(0) && (nivel > 0 || i == 0)) 1f else 0f, Muelle.suave(), label = "raya")
            Box(Modifier.weight(1f).height(4.dp).clip(Formas.Pastilla).background(Colores.Borde)) {
                Box(Modifier.fillMaxWidth(lleno).height(4.dp).clip(Formas.Pastilla).background(color))
            }
            if (i < 3) Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(if (nivel < 0) "" else stringResource(nombreFuerza(nivel)), style = Texto.Pequeno, color = if (nivel < 0) Colores.Texto3 else color, maxLines = 1, modifier = Modifier.width(92.dp))
    }
}

internal fun colorFuerza(n: Int): Color = when (n) {
    -1 -> Color(0x00000000)
    0 -> Color(0xFFDF5A4E)
    1 -> Color(0xFFE0874A)
    2 -> Color(0xFFD99A2B)
    3 -> Color(0xFF6DAE5A)
    else -> Color(0xFF43A363)
}

internal fun nombreFuerza(n: Int): Int = when (n) {
    0 -> R.string.bv_fuerza_0
    1 -> R.string.bv_fuerza_1
    2 -> R.string.bv_fuerza_2
    3 -> R.string.bv_fuerza_3
    else -> R.string.bv_fuerza_4
}

/** El reloj de los códigos: en las pruebas se congela (si no, la pantalla nunca está quieta). */
internal object Reloj {
    @Volatile var fijo: Long? = null
}

/** La hora, al ritmo de la pantalla (para los anillos de los códigos y el cambio de código). */
@Composable
internal fun rememberAhora(): androidx.compose.runtime.State<Long> {
    val estado = remember { mutableLongStateOf(Reloj.fijo ?: System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        if (Reloj.fijo != null) return@LaunchedEffect
        while (true) withFrameMillis { estado.longValue = System.currentTimeMillis() }
    }
    return estado
}

/** El anillo que se vacía mientras el código vale. Se pinta en la capa: no recompone nada. */
@Composable
internal fun AnilloTiempo(clave: Totp.Clave, ahora: androidx.compose.runtime.State<Long>, tam: Dp = 22.dp) {
    val acento = Colores.Acento
    val rojo = Colores.Rojo
    val pista = Colores.Borde
    Canvas(Modifier.size(tam)) {
        val periodo = clave.periodo.coerceAtLeast(1) * 1000f
        val f = (periodo - ahora.value % periodo.toLong()) / periodo
        val trazo = size.minDimension * 0.16f
        val r = Size(size.width - trazo, size.height - trazo)
        val o = Offset(trazo / 2, trazo / 2)
        drawArc(pista, 0f, 360f, false, o, r, style = Stroke(trazo))
        drawArc(if (f < 0.2f) rojo else acento, -90f, -360f * f, false, o, r, style = Stroke(trazo, cap = StrokeCap.Round))
    }
}

/** Una tarjeta vacía con un icono grande y dos líneas: lo que se ve cuando aún no hay nada. */
@Composable
internal fun Vacio(icono: ImageVector, titulo: String, nota: String, modifier: Modifier = Modifier, accion: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Colores.AcentoSuave), contentAlignment = Alignment.Center) {
            Icon(icono, null, tint = Colores.Acento, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(titulo, style = Texto.Subtitulo, color = Colores.Texto, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(nota, style = Texto.Pequeno, color = Colores.Texto3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (accion != null) { Spacer(Modifier.height(16.dp)); accion() }
    }
}

/** El botón flotante de añadir: una pastilla con el acento, abajo a la derecha. */
@Composable
internal fun BoxScope.BotonAnadir(texto: String, visible: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
        enter = fadeIn(Muelle.suave()) + scaleIn(Muelle.suave(), initialScale = 0.8f),
        exit = fadeOut(Muelle.suave()) + scaleOut(Muelle.suave(), targetScale = 0.8f),
    ) {
        Row(
            Modifier.height(52.dp).clip(Formas.Pastilla).background(Colores.Acento).pulsable(onClick).padding(start = 16.dp, end = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Iconos.Mas, null, tint = Colores.SobreAcento, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(texto, style = Texto.Subtitulo, color = Colores.SobreAcento)
        }
    }
}

/** Una imagen que aparece con un fundido corto, sin saltos (el hueco ya tiene su tamaño). */
@Composable
internal fun ImagenSuave(b: ImageBitmap?, modifier: Modifier, escala: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop) {
    val alfa by animateFloatAsState(if (b != null) 1f else 0f, Muelle.suave(), label = "imagen")
    Box(modifier) { if (b != null) Image(b, null, Modifier.fillMaxSize().alpha(alfa), contentScale = escala) }
}
