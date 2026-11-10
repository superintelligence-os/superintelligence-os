package com.sios.ajustes

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.datos.Servicio
import com.sios.ui.Apariencia
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Logo de cada IA (con su color de marca). */
@Composable
fun LogoIa(id: String, tam: androidx.compose.ui.unit.Dp, color: Color) {
    val icono: ImageVector = when (id) {
        "claude", "anthropic" -> Iconos.LogoClaude
        else -> ImageVector.vectorResource(
            when (id) {
                "chatgpt", "openai" -> R.drawable.ic_ia_chatgpt
                "gemini", "gemini_api" -> R.drawable.ic_ia_gemini
                "deepseek" -> R.drawable.ic_ia_deepseek
                "kimi", "kimi_code" -> R.drawable.ic_ia_kimi
                "mistral" -> R.drawable.ic_ia_mistral
                "grok" -> R.drawable.ic_ia_grok
                "openrouter" -> R.drawable.ic_ia_openrouter
                else -> R.drawable.ic_ia_local
            }
        )
    }
    Icon(icono, null, tint = color, modifier = Modifier.size(tam))
}

private fun JSONObject.texto(k: String) = optString(k).takeIf { it.isNotBlank() && it != "null" }

fun colorIa(p: JSONObject): Color {
    val a = p.optJSONArray("acento")
    val s = a?.optString(if (Apariencia.claro) 1 else 0) ?: return Colores.Acento
    return runCatching { Color(0xFF000000 or s.removePrefix("#").toLong(16)) }.getOrDefault(Colores.Acento)
}

/**
 * Inteligencia: con qué IA funciona el sistema. Con la suscripción de siempre (Claude, ChatGPT o
 * Gemini), con la clave de la API de cualquiera (pago por uso) o con un modelo en el propio móvil.
 * Lo mismo se puede hacer desde la app del ordenador.
 */
@Composable
internal fun SeccionInteligencia(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ia by Servicio.ia.collectAsStateWithLifecycle()
    var abierto by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // Mientras algo está en marcha (instalando, entrando, descargando), se refresca solo.
        while (true) {
            Servicio.cargarIa()
            val ocupado = Servicio.ia.value?.toString()?.contains("\"tarea\"") == true
            delay(if (ocupado) 1_500 else 15_000)
        }
    }
    val e = ia
    seccion(stringResource(R.string.ia_titulo), stringResource(R.string.ia_nota)) {
        if (e == null) {
            // Mientras llega la lista: tarjetas de esqueleto con un brillo que las recorre.
            Esqueleto()
            return@seccion
        }
        val lista = e.optJSONArray("proveedores") ?: return@seccion
        val todos = (0 until lista.length()).map { lista.getJSONObject(it) }
        // Sin IA elegida todavía (primer arranque), ninguna sale «En uso».
        val activo = if (e.optBoolean("elegida", true)) e.optString("activo") else ""
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for ((tipo, titulo) in listOf("suscripcion" to R.string.ia_grupo_suscripcion, "clave" to R.string.ia_grupo_clave, "local" to R.string.ia_grupo_local)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(titulo).uppercase(), style = Texto.Etiqueta, color = Colores.Texto3)
                    for (p in todos.filter { (it.optString("grupo").ifBlank { it.optString("tipo") }) == tipo }) {
                        val id = p.optString("id")
                        TarjetaIa(p, activo == id, abierto == id, e) { abierto = if (abierto == id) null else id }
                    }
                }
            }
        }
    }
}

/**
 * Los mensajes del servicio (pasos de instalación, errores) en el idioma del móvil: llegan con una
 * clave y sus datos; sin clave conocida, el texto que manda el servicio (de reserva, en español).
 */
@Composable
private fun textoIa(clave: String?, o: JSONObject, respaldo: String?): String? = textoIaFuera(LocalContext.current, clave, o, respaldo)

private fun textoIaFuera(ctx: android.content.Context, clave: String?, o: JSONObject, respaldo: String?): String? {
    val d = o.optJSONObject("datos") ?: JSONObject()
    return when (clave) {
        "preparando" -> ctx.getString(R.string.ia_paso_preparando)
        "debian" -> ctx.getString(R.string.ia_paso_debian)
        "claude" -> ctx.getString(R.string.ia_paso_ia)
        "codex" -> ctx.getString(R.string.ia_paso_codex)
        "gemini" -> ctx.getString(R.string.ia_paso_gemini)
        "kimi" -> ctx.getString(R.string.ia_paso_kimi)
        "llama" -> ctx.getString(R.string.ia_paso_llama)
        "comprobando_codigo" -> ctx.getString(R.string.ia_paso_comprobando_codigo)
        "instalar" -> ctx.getString(R.string.ia_error_instalar)
        "tiempo_entrar" -> ctx.getString(R.string.ia_error_tiempo_entrar)
        "sesion_incompleta" -> ctx.getString(R.string.ia_error_sesion_incompleta)
        "descarga_cortada" -> ctx.getString(R.string.ia_error_descarga_cortada)
        "ram" -> ctx.getString(R.string.ia_error_ram, d.optString("modelo"), d.optInt("gb"))
        "descarga_primero" -> ctx.getString(R.string.ia_error_descarga_primero, d.optString("modelo"))
        "descarga_modelo" -> ctx.getString(R.string.ia_error_descarga_modelo)
        "clave_invalida" -> ctx.getString(R.string.ia_error_clave_invalida)
        "respondio" -> ctx.getString(R.string.ia_error_respondio, d.optString("nombre"), d.optString("estado"))
        "local_no_arranca" -> ctx.getString(R.string.ia_error_local_no_arranca)
        "falta_clave" -> ctx.getString(R.string.ia_error_falta_clave)
        else -> respaldo
    }
}

/**
 * Borde de la IA en uso: se ilumina con su color y el degradado gira alrededor de la tarjeta (como el
 * halo cuando maneja el móvil), con un resplandor suave por fuera.
 */
@Composable
private fun Modifier.bordeVivo(color: Color): Modifier {
    val giro = androidx.compose.animation.core.rememberInfiniteTransition(label = "borde")
    val angulo by giro.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(3200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "angulo",
    )
    // Entra con un destello que se asienta (al tocar «Usar»).
    var visto by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visto = true }
    val fuerza by animateFloatAsState(if (visto) 1f else 0f, androidx.compose.animation.core.tween(700), label = "fuerza")
    val claro = androidx.compose.ui.graphics.lerp(color, Color.White, 0.55f)
    val colores = intArrayOf(
        color.toArgb(), color.copy(alpha = 0.15f).toArgb(), claro.toArgb(), color.copy(alpha = 0.15f).toArgb(), color.toArgb(),
    )
    return drawWithContent {
        drawContent()
        val r = 16.dp.toPx()
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val sombra = object : androidx.compose.ui.graphics.ShaderBrush() {
            override fun createShader(size: androidx.compose.ui.geometry.Size) =
                android.graphics.SweepGradient(c.x, c.y, colores, null).apply { setLocalMatrix(android.graphics.Matrix().apply { setRotate(angulo, c.x, c.y) }) }
        }
        val trazo = 1.5.dp.toPx()
        drawRoundRect(color.copy(alpha = 0.10f * fuerza), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r), style = androidx.compose.ui.graphics.drawscope.Stroke(6.dp.toPx()))
        drawRoundRect(
            sombra, topLeft = androidx.compose.ui.geometry.Offset(trazo / 2, trazo / 2),
            size = androidx.compose.ui.geometry.Size(size.width - trazo, size.height - trazo),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r - trazo / 2), alpha = fuerza,
            style = androidx.compose.ui.graphics.drawscope.Stroke(trazo),
        )
    }
}

/** Tarjetas vacías con un brillo que pasa, mientras llega la lista de IA. */
@Composable
private fun Esqueleto() {
    val brillo = androidx.compose.animation.core.rememberInfiniteTransition(label = "esqueleto")
    val x by brillo.animateFloat(
        -0.5f, 1.5f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
        label = "x",
    )
    val base = Colores.Superficie2
    val luz = Colores.Texto.copy(alpha = 0.06f)
    Column(
        Modifier.drawWithContent {
            drawContent()
            val centro = size.width * x
            drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Color.Transparent, luz, Color.Transparent), startX = centro - size.width * 0.3f, endX = centro + size.width * 0.3f,
                ),
            )
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(4) {
            Row(
                Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Tarjeta)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(base))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.fillMaxWidth(0.35f).height(12.dp).clip(Formas.Pastilla).background(base))
                    Box(Modifier.fillMaxWidth(0.75f).height(10.dp).clip(Formas.Pastilla).background(base))
                }
            }
        }
    }
}

/** La descripción de cada IA en el idioma del móvil (el servicio la manda en español). */
@Composable
private fun descripcionIa(id: String): String? = when (id) {
    "claude" -> R.string.ia_desc_ia
    "chatgpt" -> R.string.ia_desc_chatgpt
    "gemini" -> R.string.ia_desc_gemini
    "kimi_code" -> R.string.ia_desc_kimi_code
    "deepseek" -> R.string.ia_desc_deepseek
    "kimi" -> R.string.ia_desc_kimi
    "gemini_api" -> R.string.ia_desc_gemini_api
    "openai" -> R.string.ia_desc_openai
    "anthropic" -> R.string.ia_desc_anthropic
    "mistral" -> R.string.ia_desc_mistral
    "grok" -> R.string.ia_desc_grok
    "openrouter" -> R.string.ia_desc_openrouter
    "local" -> R.string.ia_desc_local
    else -> null
}?.let { stringResource(it) }

@Composable
private fun TarjetaIa(p: JSONObject, activa: Boolean, abierta: Boolean, todo: JSONObject, onPulsar: () -> Unit) {
    val id = p.optString("id")
    val color = colorIa(p)
    val listo = p.optBoolean("listo")
    val tarea = p.optJSONObject("tarea")
    val borde by animateColorAsState(if (activa) color else Colores.Borde, Muelle.suave(), label = "borde")
    val giro by animateFloatAsState(if (abierta) 180f else 0f, Muelle.suave(), label = "giro")
    Column(
        Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie)
            .then(if (activa) Modifier.bordeVivo(color) else Modifier.border(1.dp, borde, Formas.Tarjeta)),
    ) {
        Row(
            Modifier.fillMaxWidth().pulsable(onPulsar).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                LogoIa(id, 20.dp, color)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.optString("nombre"), style = Texto.Cuerpo.copy(fontWeight = FontWeight.SemiBold), color = Colores.Texto)
                    if (p.optString("tipo") == "clave" && id in setOf("gemini_api", "openai", "anthropic", "kimi")) {
                        Spacer(Modifier.width(6.dp))
                        Text("API", style = Texto.Etiqueta, color = Colores.Texto3)
                    }
                }
                val estado = when {
                    tarea?.optBoolean("instalando") == true -> textoIa(tarea.texto("paso_clave"), tarea, tarea.texto("paso")) ?: stringResource(R.string.ia_instalando)
                    tarea?.optBoolean("entrando") == true -> textoIa(tarea.texto("paso_clave"), tarea, null) ?: stringResource(R.string.ia_entrando)
                    tarea?.texto("error") != null -> textoIa(tarea.texto("error_clave"), tarea, tarea.texto("error"))!!
                    listo && p.texto("cuenta") != null -> p.texto("cuenta")!!
                    listo && p.texto("clave") != null -> stringResource(R.string.ia_con_clave, p.texto("clave")!!)
                    listo -> stringResource(R.string.ia_listo)
                    else -> descripcionIa(id) ?: p.optString("descripcion")
                }
                Text(estado, style = Texto.Pequeno, color = if (tarea?.texto("error") != null) Colores.Rojo else Colores.Texto3, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            if (activa) {
                Text(
                    stringResource(R.string.ia_en_uso), style = Texto.Etiqueta, color = color,
                    modifier = Modifier.clip(Formas.Pastilla).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp),
                )
            } else Icon(Iconos.Abajo, null, tint = Colores.Texto3, modifier = Modifier.size(18.dp).rotate(giro))
        }
        AnimatedVisibility(abierta, enter = expandVertically(Muelle.suave()) + fadeIn(), exit = shrinkVertically(Muelle.suave()) + fadeOut()) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (p.optString("tipo")) {
                    "suscripcion" -> DetalleSuscripcion(p, activa, color)
                    "clave" -> DetalleClave(p, activa, color)
                    else -> DetalleLocal(p, activa, todo.optJSONObject("local") ?: JSONObject(), color)
                }
            }
        }
    }
}

@Composable
private fun BotonUsar(p: JSONObject, activa: Boolean) {
    val alcance = rememberCoroutineScope()
    if (!activa && p.optBoolean("listo")) {
        BotonPrimario(stringResource(R.string.ia_usar, p.optString("nombre")), Modifier.fillMaxWidth()) {
            alcance.launch { Servicio.elegirIa(p.optString("id")) }
        }
    }
}

/** Modelos para elegir (chips). «auto» es lo recomendado por cada servicio. */
@Composable
internal fun Modelos(p: JSONObject, color: Color) {
    val id = p.optString("id")
    val alcance = rememberCoroutineScope()
    var modelos by remember(id) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(id, p.optBoolean("listo")) {
        val r = Servicio.modelosIa(id)?.optJSONArray("modelos") ?: return@LaunchedEffect
        modelos = (0 until r.length()).map { r.getJSONObject(it).let { m -> m.optString("id") to m.optString("nombre") } }
    }
    if (modelos.isEmpty() || id == "claude") return
    val elegido = p.optString("modelo", "auto")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.ia_modelo), style = Texto.Pequeno, color = Colores.Texto3)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((mid, nombre) in modelos.take(40)) {
                val si = mid == elegido
                Text(
                    nombre, style = Texto.Pequeno, color = if (si) color else Colores.Texto2,
                    modifier = Modifier.clip(Formas.Pastilla).background(if (si) color.copy(alpha = 0.14f) else Colores.Superficie2)
                        .pulsable { alcance.launch { Servicio.modeloIa(id, mid) } }.padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun DetalleSuscripcion(p: JSONObject, activa: Boolean, color: Color) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    val id = p.optString("id")
    val tarea = p.optJSONObject("tarea")
    val estadoChat by Servicio.estado.collectAsStateWithLifecycle()
    // Claude entra con el flujo de su cuenta (enlace que vuelve solo); ChatGPT y Gemini, con el suyo.
    val enlace = if (id == "claude") estadoChat.cuenta.enlace else tarea?.texto("enlace")
    val codigo = tarea?.texto("codigo")
    val pideCodigo = if (id == "claude") estadoChat.cuenta.pideCodigo else tarea?.optBoolean("pideCodigo") == true
    var pegado by remember { mutableStateOf("") }
    if (p.optBoolean("listo")) {
        BotonUsar(p, activa)
        Modelos(p, color)
        Text(
            stringResource(R.string.ia_salir), style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Rojo,
            modifier = Modifier.clip(Formas.Pequena).pulsable { alcance.launch { Servicio.salirIa(id) } }.padding(vertical = 6.dp),
        )
        return
    }
    when {
        codigo != null -> {
            // ChatGPT: código de dispositivo, se escribe en la web de OpenAI (aquí o en el ordenador).
            Column(
                Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Fondo).border(1.dp, color, Formas.Tarjeta).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.ia_codigo_titulo), style = Texto.Pequeno, color = Colores.Texto3)
                Text(codigo, style = Texto.Titulo.copy(fontSize = 30.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Light), color = Colores.Texto)
                Text(stringResource(R.string.ia_codigo_nota, enlace ?: ""), style = Texto.Pequeno, color = Colores.Texto3, textAlign = TextAlign.Center)
            }
            BotonPrimario(stringResource(R.string.ia_abrir_web), Modifier.fillMaxWidth()) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(enlace)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        enlace != null -> {
            BotonPrimario(stringResource(R.string.ia_abrir_web), Modifier.fillMaxWidth()) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(enlace)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            if (pideCodigo) {
                Text(stringResource(R.string.ia_pega_codigo), style = Texto.Pequeno, color = Colores.Texto3)
                com.sios.inicio.Campo(pegado, { pegado = it.trim() }, stringResource(R.string.ia_codigo_pista), KeyboardType.Text, secreto = false)
                BotonSecundario(stringResource(R.string.ia_continuar), Modifier.fillMaxWidth()) {
                    if (pegado.isNotBlank()) alcance.launch { Servicio.codigoIa(id, pegado); pegado = "" }
                }
            }
            Text(
                stringResource(R.string.ia_cancelar), style = Texto.Pequeno, color = Colores.Texto3,
                modifier = Modifier.clip(Formas.Pequena).pulsable { alcance.launch { Servicio.cancelarIa() } }.padding(vertical = 6.dp),
            )
        }
        tarea?.optBoolean("instalando") == true || tarea?.optBoolean("entrando") == true ->
            Text(stringResource(R.string.ia_espera), style = Texto.Pequeno, color = Colores.Texto3)
        else -> {
            BotonPrimario(stringResource(R.string.ia_entrar_con, p.optString("nombre")), Modifier.fillMaxWidth()) {
                alcance.launch { Servicio.entrarIa(id) }
            }
            Text(stringResource(R.string.ia_entrar_nota), style = Texto.Pequeno, color = Colores.Texto3)
        }
    }
}

@Composable
private fun DetalleClave(p: JSONObject, activa: Boolean, color: Color) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    val id = p.optString("id")
    var clave by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var comprobando by remember { mutableStateOf(false) }
    if (p.optBoolean("listo")) {
        BotonUsar(p, activa)
        Modelos(p, color)
    }
    com.sios.inicio.Campo(clave, { clave = it.trim(); error = null }, stringResource(if (p.optBoolean("listo")) R.string.ia_clave_cambiar else R.string.ia_clave_pista), KeyboardType.Password, secreto = true)
    AnimatedVisibility(error != null) { Text(error ?: "", style = Texto.Pequeno, color = Colores.Rojo) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        p.texto("claveDe")?.let { web ->
            BotonSecundario(stringResource(R.string.ia_clave_conseguir), Modifier.weight(1f)) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        BotonPrimario(stringResource(if (comprobando) R.string.ia_comprobando else R.string.ia_clave_guardar), Modifier.weight(1f)) {
            if (clave.isBlank() || comprobando) return@BotonPrimario
            comprobando = true
            alcance.launch {
                val r = Servicio.claveIa(id, clave)
                comprobando = false
                error = r?.texto("error")?.let { textoIaFuera(ctx, r.texto("clave"), r, it) } ?: if (r == null) ctx.getString(R.string.ia_sin_servicio) else null
                if (error == null) clave = ""
                Servicio.cargarIa()
            }
        }
    }
    Text(stringResource(R.string.ia_clave_nota), style = Texto.Pequeno, color = Colores.Texto3)
    if (p.optBoolean("listo")) Text(
        stringResource(R.string.ia_clave_quitar), style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Rojo,
        modifier = Modifier.clip(Formas.Pequena).pulsable { alcance.launch { Servicio.salirIa(id) } }.padding(vertical = 6.dp),
    )
}

@Composable
private fun DetalleLocal(p: JSONObject, activa: Boolean, local: JSONObject, color: Color) {
    val alcance = rememberCoroutineScope()
    val ram = local.optInt("ram")
    val recomendado = local.texto("recomendado")
    val modelos = local.optJSONArray("modelos")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    Text(
        if (recomendado != null) stringResource(R.string.ia_local_ram, ram, modelos.firstOrNull { it.optString("id") == recomendado }?.optString("nombre") ?: recomendado)
        else stringResource(R.string.ia_local_poca_ram, ram),
        style = Texto.Pequeno, color = Colores.Texto2,
    )
    BotonUsar(p, activa)
    // Su nombre: con él contesta, se presenta y se le llama («Oye …»).
    var nombre by remember(p.optString("nombre")) { mutableStateOf(p.optString("nombre")) }
    Text(stringResource(R.string.ia_local_nombre), style = Texto.Pequeno, color = Colores.Texto3)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { com.sios.inicio.Campo(nombre, { nombre = it.take(24) }, "Qwen", KeyboardType.Text, secreto = false) }
        AnimatedVisibility(nombre.trim() != p.optString("nombre") && nombre.isNotBlank()) {
            Text(
                stringResource(R.string.ia_clave_guardar), style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = color,
                modifier = Modifier.clip(Formas.Pequena).pulsable { alcance.launch { Servicio.nombreLocal(nombre.trim()) } }.padding(10.dp),
            )
        }
    }
    Text(stringResource(R.string.ia_local_nombre_nota, p.optString("nombre")), style = Texto.Pequeno, color = Colores.Texto3)
    for (m in modelos) {
        val mid = m.optString("id")
        val t = m.optJSONObject("tarea")
        Row(
            Modifier.fillMaxWidth().clip(Formas.Pequena).background(Colores.Fondo).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.optString("nombre"), style = Texto.Cuerpo, color = if (m.optBoolean("cabe")) Colores.Texto else Colores.Texto3)
                    if (mid == recomendado) {
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.ia_recomendado), style = Texto.Etiqueta, color = color)
                    }
                }
                val nota = when {
                    t?.optBoolean("descargando") == true -> stringResource(R.string.ia_descargando, (t.optDouble("progreso") * 100).toInt())
                    t?.texto("error") != null -> textoIa(t.texto("error_clave"), t, t.texto("error"))!!
                    !m.optBoolean("cabe") -> stringResource(R.string.ia_local_no_cabe, m.optInt("ram"))
                    else -> stringResource(R.string.ia_local_tamano, m.optDouble("tamano").toString().replace(".", ","), m.optString("nota"))
                }
                Text(nota, style = Texto.Pequeno, color = Colores.Texto3)
                if (t?.optBoolean("descargando") == true) {
                    val avance by animateFloatAsState(t.optDouble("progreso").toFloat(), Muelle.suave(), label = "descarga")
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(Formas.Pastilla).background(Colores.Superficie2)) {
                        Box(Modifier.fillMaxWidth(avance).height(4.dp).clip(Formas.Pastilla).background(color))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            val (texto, accion) = when {
                m.optBoolean("elegido") && m.optBoolean("descargado") -> stringResource(R.string.ia_local_elegido) to null
                m.optBoolean("descargado") -> stringResource(R.string.ia_local_elegir) to { alcance.launch { Servicio.modeloIa("local", mid) }; Unit }
                t?.optBoolean("descargando") == true -> "" to null
                m.optBoolean("cabe") -> stringResource(R.string.ia_local_descargar) to { alcance.launch { Servicio.descargarLocal(mid) }; Unit }
                else -> "" to null
            }
            if (texto.isNotEmpty()) Text(
                texto, style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = if (accion != null) color else Colores.Texto3,
                modifier = Modifier.clip(Formas.Pequena).let { if (accion != null) it.pulsable(accion) else it }.padding(8.dp),
            )
        }
    }
    Text(stringResource(R.string.ia_local_nota), style = Texto.Pequeno, color = Colores.Texto3)
}
