package com.sios.sistema

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.datos.Cuenta
import com.sios.datos.Servicio
import com.sios.inicio.InicioActivity
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable

/** Las cuatro etapas del primer arranque, en orden. */
private enum class Etapa { Conexion, Preparando, Ia, Listo }

/**
 * Primer arranque de SIOS: conectarse, dejar que el sistema se prepare solo y elegir la IA (con su
 * cuenta, una clave de API o un modelo en el propio móvil). Lo demás (tema, asistente, recuadro del
 * panel) viene de serie y cambia al color de la IA elegida.
 */
@Composable
fun Bienvenida(onTerminar: () -> Unit) {
    val ctx = LocalContext.current
    // Primero, qué es SIOS y qué puede hacer (una sola vez; luego, en Ajustes).
    var presentado by rememberSaveable { mutableStateOf(Tour.visto(ctx)) }
    AnimatedContent(
        targetState = presentado,
        transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) },
        label = "presentacion",
    ) { visto ->
        if (!visto) Presentacion { Tour.marcarVisto(ctx); presentado = true } else Puesta(onTerminar)
    }
}

/** Conexión, preparación del sistema y elección de la IA. */
@Composable
private fun Puesta(onTerminar: () -> Unit) {
    val ctx = LocalContext.current
    val internet = rememberInternet(ctx)
    val prep by Preparacion.estado.collectAsState()
    val servicio by Servicio.estado.collectAsState()
    val cuenta = servicio.cuenta
    val ia by Servicio.ia.collectAsState()
    // La IA elegida y lista (con su cuenta, su clave o su modelo descargado).
    val activa = ia?.takeIf { it.optBoolean("elegida") }?.let { e ->
        val lista = e.optJSONArray("proveedores")
        (0 until (lista?.length() ?: 0)).map { lista!!.getJSONObject(it) }.firstOrNull { it.optString("id") == e.optString("activo") }
    }?.takeIf { it.optBoolean("listo") }

    LaunchedEffect(Unit) {
        Preparacion.cargar(ctx)
        SistemaSios.aplicarTema(ctx)
        SistemaSios.activarDepuracion(ctx) // el agente actúa sobre el móvil por ADB local
    }

    // El servicio en marcha manda: si ya responde, la instalación está hecha (aunque se repita).
    val instalado = servicio.conectado || prep.paso >= Preparacion.PASO_SESION
    val etapa = when {
        activa != null -> Etapa.Listo
        instalado -> Etapa.Ia
        !internet && !prep.enMarcha && prep.error == null -> Etapa.Conexion
        else -> Etapa.Preparando
    }

    // Con internet, la preparación arranca sola; si falló, espera a que el usuario reintente.
    LaunchedEffect(internet, instalado) {
        if (internet && !instalado && !prep.enMarcha && prep.error == null) Preparacion.lanzar(ctx)
    }

    // Sin IA elegida, la primera que queda lista (al entrar con su cuenta, poner su clave o bajar
    // el modelo) se elige sola: no hace falta tocar además «Usar».
    LaunchedEffect(ia) {
        val e = ia ?: return@LaunchedEffect
        if (e.optBoolean("elegida")) return@LaunchedEffect
        val lista = e.optJSONArray("proveedores") ?: return@LaunchedEffect
        val lista_ = (0 until lista.length()).map { lista.getJSONObject(it) }
        lista_.firstOrNull { it.optBoolean("listo") }?.let { Servicio.elegirIa(it.optString("id")) }
    }

    // El enlace de inicio de sesión de Claude se abre en el navegador en cuanto llega (una vez por enlace).
    var abierto by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(cuenta.enlace) {
        val enlace = cuenta.enlace ?: return@LaunchedEffect
        if (enlace != abierto) {
            abierto = enlace
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(enlace)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    // Al terminar en el navegador, SIOS vuelve al frente solo.
    var esperabaSesion by remember { mutableStateOf(false) }
    LaunchedEffect(cuenta.entrando, cuenta.conectada) {
        if (cuenta.entrando) esperabaSesion = true
        if (esperabaSesion && cuenta.conectada == true) {
            esperabaSesion = false
            ctx.startActivity(
                Intent(ctx, InicioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Colores.Fondo)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
    ) {
        com.sios.ui.Sios(Modifier.size(40.dp))
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.bienvenida_titulo), style = Texto.Titulo.copy(fontSize = 26.sp), color = Colores.Texto)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.bienvenida_subtitulo), style = Texto.Cuerpo, color = Colores.Texto2)
        Spacer(Modifier.height(32.dp))

        Indicador(etapa)
        Spacer(Modifier.height(16.dp))

        AnimatedContent(
            targetState = etapa,
            transitionSpec = {
                (fadeIn(Muelle.suave()) + slideInVertically(Muelle.suave()) { it / 12 }) togetherWith fadeOut(Muelle.suave())
            },
            label = "etapa",
        ) { e ->
            when (e) {
                Etapa.Conexion -> TarjetaConexion(ctx)
                Etapa.Preparando -> TarjetaPreparando(prep, internet) {
                    Preparacion.actualizar(ctx, prep.copy(error = null))
                    Preparacion.lanzar(ctx)
                }
                Etapa.Ia -> TarjetaElegirIa()
                Etapa.Listo -> TarjetaListo(activa) {
                    SistemaSios.marcarTerminado(ctx)
                    com.sios.teclado.Teclados.deSerie(ctx)
                    onTerminar()
                }
            }
        }

        if (etapa != Etapa.Listo) {
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.bienvenida_mas_tarde),
                style = Texto.Pequeno,
                color = Colores.Texto3,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(Formas.Pastilla)
                    .pulsable {
                        SistemaSios.marcarTerminado(ctx)
                        onTerminar()
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Dos rayas, «Conexión» y «Tu IA», que se rellenan con el acento al avanzar. */
@Composable
private fun Indicador(etapa: Etapa) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val tramos = listOf(
            Triple(stringResource(R.string.bienvenida_tramo_conexion), etapa <= Etapa.Preparando, etapa >= Etapa.Ia),
            Triple(stringResource(R.string.bienvenida_tramo_cuenta), etapa >= Etapa.Ia, etapa == Etapa.Listo),
        )
        tramos.forEach { (nombre, activo, completo) ->
            val relleno by animateFloatAsState(if (completo) 1f else if (activo) 0.35f else 0f, Muelle.suave(), label = "raya")
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(Formas.Pastilla).background(Colores.Superficie2)) {
                    Box(Modifier.fillMaxWidth(relleno).height(3.dp).clip(Formas.Pastilla).background(Colores.Acento))
                }
                Spacer(Modifier.height(8.dp))
                Text(nombre, style = Texto.Etiqueta, color = if (activo || completo) Colores.Texto2 else Colores.Texto3)
            }
        }
    }
}

@Composable
private fun Tarjeta(contenido: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Formas.Tarjeta)
            .border(1.dp, Colores.Borde, Formas.Tarjeta)
            .background(Colores.Superficie)
            .animateContentSize(Muelle.suave())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { contenido() }
}

@Composable
private fun TarjetaConexion(ctx: Context) {
    Tarjeta {
        Text(stringResource(R.string.bienvenida_conexion_titulo), style = Texto.Subtitulo, color = Colores.Texto)
        Text(
            stringResource(R.string.bienvenida_conexion_texto),
            style = Texto.Cuerpo,
            color = Colores.Texto2,
        )
        BotonPrimario(stringResource(R.string.bienvenida_elegir_red), Modifier.fillMaxWidth()) {
            ctx.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        BotonSecundario(stringResource(R.string.bienvenida_sim), Modifier.fillMaxWidth()) {
            runCatching { ctx.startActivity(Intent("android.settings.NETWORK_PROVIDER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        Text(
            stringResource(R.string.bienvenida_esim_nota),
            style = Texto.Pequeno,
            color = Colores.Texto3,
        )
    }
}

@Composable
private fun TarjetaPreparando(prep: Preparacion.Estado, internet: Boolean, onReintentar: () -> Unit) {
    Tarjeta {
        if (prep.error != null) {
            Text(stringResource(R.string.bienvenida_preparacion_parada), style = Texto.Subtitulo, color = Colores.Texto)
            Text(prep.error, style = Texto.Cuerpo, color = Colores.Rojo)
            if (!internet) Text(stringResource(R.string.bienvenida_sin_conexion), style = Texto.Pequeno, color = Colores.Texto3)
            BotonPrimario(stringResource(R.string.bienvenida_reintentar), Modifier.fillMaxWidth(), onReintentar)
            return@Tarjeta
        }
        // Siempre hay algo moviéndose (contador, barra que avanza y brillo, paso que gira): si un paso
        // tarda, se ve que sigue trabajando y no que se ha quedado colgado.
        var segundos by remember { mutableStateOf(0) }
        var enPaso by remember(prep.paso) { mutableStateOf(0f) }
        LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); segundos++ } }
        LaunchedEffect(prep.paso) { while (true) { kotlinx.coroutines.delay(250); enPaso += 0.25f } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bienvenida_preparando_titulo), style = Texto.Subtitulo, color = Colores.Texto, modifier = Modifier.weight(1f))
            Text("%d:%02d".format(segundos / 60, segundos % 60), style = Texto.Pequeno.copy(fontFeatureSettings = "tnum"), color = Colores.Texto3)
        }
        Text(stringResource(R.string.bienvenida_preparando_texto), style = Texto.Cuerpo, color = Colores.Texto2)
        val total = Preparacion.PASOS.size
        // Dentro de cada paso la barra sigue avanzando despacio (se acerca al final del tramo sin llegar).
        val hecho = (prep.paso - 1).coerceAtLeast(0).toFloat()
        val dentro = 0.85f * (1f - kotlin.math.exp(-enPaso / 25f))
        val avance by animateFloatAsState(((hecho + dentro) / total).coerceIn(0.04f, 1f), Muelle.suave(), label = "avance")
        val brillo = androidx.compose.animation.core.rememberInfiniteTransition(label = "brillo")
        val pasada by brillo.animateFloat(
            -0.4f, 1.4f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1600, easing = androidx.compose.animation.core.FastOutSlowInEasing)),
            label = "pasada",
        )
        Box(Modifier.fillMaxWidth().height(4.dp).clip(Formas.Pastilla).background(Colores.Superficie2)) {
            Box(
                Modifier.fillMaxWidth(avance).height(4.dp).clip(Formas.Pastilla).background(Colores.Acento)
                    .drawWithContent {
                        drawContent()
                        val x = size.width * pasada
                        drawRect(
                            androidx.compose.ui.graphics.Brush.horizontalGradient(
                                listOf(androidx.compose.ui.graphics.Color.Transparent, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.55f), androidx.compose.ui.graphics.Color.Transparent),
                                startX = x - size.width * 0.25f, endX = x + size.width * 0.25f,
                            ),
                        )
                    },
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Preparacion.PASOS.forEachIndexed { i, nombre ->
                val n = i + 1
                Fila(stringResource(nombre), hecho = prep.paso > n, actual = prep.paso == n || (n == 1 && prep.paso <= 0))
            }
        }
        if (prep.paso == 3) {
            Text(
                stringResource(R.string.bienvenida_aviso_depuracion),
                style = Texto.Pequeno,
                color = Colores.Acento,
            )
        }
    }
}

@Composable
private fun Fila(nombre: String, hecho: Boolean, actual: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(20.dp)
                .clip(Formas.Pastilla)
                .background(if (hecho) Colores.Acento else Colores.Superficie2),
            contentAlignment = Alignment.Center,
        ) {
            if (hecho) Icon(Iconos.Hecho, null, tint = Colores.SobreAcento, modifier = Modifier.size(12.dp))
            // El paso en curso: un arco que gira (como un indicador de carga) sobre su círculo.
            if (actual) {
                val giro = androidx.compose.animation.core.rememberInfiniteTransition(label = "giro")
                val angulo by giro.animateFloat(
                    0f, 360f,
                    androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing)),
                    label = "angulo",
                )
                val acento = Colores.Acento
                androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                    val trazo = 1.8.dp.toPx()
                    drawArc(
                        acento, angulo, 100f, false,
                        topLeft = androidx.compose.ui.geometry.Offset(trazo / 2, trazo / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - trazo, size.height - trazo),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(trazo, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            nombre,
            style = Texto.Pequeno,
            color = when {
                hecho -> Colores.Texto2
                actual -> Colores.Texto
                else -> Colores.Texto3
            },
        )
    }
}

/**
 * Elegir la IA: la misma lista que Ajustes › Inteligencia (suscripción, clave de API o modelo en el
 * móvil), cada una con su forma de entrar. Al quedar lista se elige sola (ver [Puesta]).
 */
@Composable
private fun TarjetaElegirIa() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        com.sios.ajustes.SeccionInteligencia { _, _, contenido ->
            Text(stringResource(R.string.bienvenida_ia_titulo), style = Texto.Subtitulo, color = Colores.Texto)
            Text(stringResource(R.string.bienvenida_ia_texto), style = Texto.Cuerpo, color = Colores.Texto2)
            Spacer(Modifier.height(4.dp))
            contenido()
        }
    }
}

@Composable
private fun TarjetaListo(ia: org.json.JSONObject?, onEmpezar: () -> Unit) {
    Tarjeta {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(Formas.Pastilla).background(Colores.Acento), contentAlignment = Alignment.Center) {
                Icon(Iconos.Hecho, null, tint = Colores.SobreAcento, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.bienvenida_listo_titulo), style = Texto.Subtitulo, color = Colores.Texto)
        }
        val nombre = ia?.optString("nombre").orEmpty().ifBlank { com.sios.ui.Marca.nombre }
        val cuentaIa = ia?.optString("cuenta")?.takeIf { it.isNotBlank() && it != "null" }
        Text(
            cuentaIa?.let { stringResource(R.string.bienvenida_listo_con_email, nombre, it) }
                ?: stringResource(R.string.bienvenida_listo, nombre),
            style = Texto.Cuerpo,
            color = Colores.Texto2,
        )
        // Extra de seguridad: los números del PIN de la pantalla de bloqueo, en orden aleatorio.
        OpcionPinAleatorio(Modifier.fillMaxWidth())
        BotonPrimario(stringResource(R.string.bienvenida_empezar), Modifier.fillMaxWidth(), onEmpezar)
        // ¿Vienes de otro SIOS? Aquí se recupera todo (conversaciones, rutinas, ajustes, memoria).
        com.sios.ajustes.BotonRestaurarCopia(Modifier.fillMaxWidth())
    }
}

/** true mientras haya una red con internet comprobado (Wi‑Fi, datos o cable). */
@Composable
private fun rememberInternet(ctx: Context): Boolean {
    val cm = remember { ctx.getSystemService(ConnectivityManager::class.java) }
    fun ahora() = cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    var hay by remember { mutableStateOf(ahora()) }
    DisposableEffect(cm) {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { hay = ahora() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { hay = ahora() }
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) { hay = ahora() } // ahorro de datos, etc.
            override fun onLost(network: Network) { hay = ahora() }
        }
        cm.registerDefaultNetworkCallback(cb)
        onDispose { cm.unregisterNetworkCallback(cb) }
    }
    return hay
}
