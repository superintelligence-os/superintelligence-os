package com.sios.emergencias

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.ui.BotonSecundario
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import kotlinx.coroutines.delay

/**
 * La pantalla del SOS, encima de todo (también del bloqueo, sin desbloquear). La abren:
 *  - el sistema, al pulsar 5 veces el botón de encendido (SystemUI manda ACTION_LAUNCH_EMERGENCY a la
 *    app de SOS del sistema, que en SIOS es esta);
 *  - el botón SOS de la pantalla de emergencias (botón de emergencia del bloqueo y del menú de apagado);
 *  - el recuadro SOS de los ajustes rápidos;
 *  - la detección de caídas, con «¿Estás bien?».
 *
 * Primero, una cuenta atrás enorme con sonido y vibración fuertes y un botón grande para cancelar. Si
 * no se cancela: llama a emergencias y avisa a los contactos con la ubicación. Después enseña qué se
 * ha hecho y deja parar de compartir.
 */
class SosActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PRUEBA = "prueba"
        const val EXTRA_MOTIVO = "motivo"
        /** Solo ver lo que está pasando (desde el aviso): sin cuenta atrás. */
        const val EXTRA_VER = "ver"
    }

    private var contando = false

    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Durante la cuenta atrás, «atrás» no la cancela: para eso está el botón grande.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (!contando) finish() }
        })
        mostrar(intent)
    }

    /**
     * Otra petición con la pantalla ya abierta (otra vez el gesto, una caída, «Probar el SOS»…): si hay
     * una cuenta atrás en marcha, sigue esa; si no, se empieza de nuevo con lo que se pide ahora.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (contando) return
        setIntent(intent)
        generacion++
        mostrar(intent)
    }

    private var generacion = 0

    private fun mostrar(intent: Intent) {
        getSystemService(android.app.NotificationManager::class.java).cancel(Caidas.AVISO_CAIDA)
        val motivo = runCatching { Emergencias.Motivo.valueOf(intent.getStringExtra(EXTRA_MOTIVO).orEmpty()) }.getOrDefault(Emergencias.Motivo.BOTON)
        val prueba = intent.getBooleanExtra(EXTRA_PRUEBA, false) || Emergencias.modoPrueba(this)
        val enMarcha = SosServicio.estado.value?.let { it.activo && it.modo == SosServicio.Modo.SOS } == true
        val soloVer = intent.getBooleanExtra(EXTRA_VER, false) || enMarcha
        val segundos = if (motivo == Emergencias.Motivo.CAIDA) Emergencias.esperaCaida(this) else Emergencias.cuentaAtras(this)
        val sonido = Emergencias.sonido(this) || motivo == Emergencias.Motivo.CAIDA
        val gen = generacion
        setContent {
            TemaSios { androidx.compose.runtime.key(gen) {
                var fase by rememberSaveable { mutableStateOf(if (soloVer) Fase.HECHO else Fase.CUENTA) }
                val inicio = rememberSaveable { SystemClock.elapsedRealtime() }
                var restante by remember { mutableLongStateOf(segundos * 1000L) }
                val ctx = LocalContext.current
                fun pedir() {
                    if (fase != Fase.CUENTA) return
                    fase = Fase.HECHO
                    contando = false
                    Sirena.parar(ctx)
                    Emergencias.pedirAyuda(ctx, motivo, prueba)
                }
                LaunchedEffect(fase) {
                    if (fase != Fase.CUENTA) return@LaunchedEffect
                    contando = true
                    Sirena.empezar(ctx, sonido, alMaximo = !prueba)
                    while (true) {
                        restante = segundos * 1000L - (SystemClock.elapsedRealtime() - inicio)
                        if (restante <= 0) { pedir(); break }
                        delay(80)
                    }
                }
                val estado by SosServicio.estado.collectAsState()
                val cambios by Emergencias.cambios.collectAsState()
                // En prueba, el SMS que se habría mandado en esta prueba (llega cuando hay ubicación).
                val ejemplo = remember(cambios, estado?.desde) {
                    Emergencias.registro(ctx).firstOrNull { it.tipo == "sms" && it.hora >= (estado?.desde ?: Long.MAX_VALUE) }?.texto
                }
                val fondo by animateColorAsState(if (fase == Fase.CUENTA) Rojo.Sos else Colores.Fondo, Muelle.suave(), label = "fondo")
                Box(Modifier.fillMaxSize().background(fondo)) {
                    AnimatedContent(
                        fase,
                        transitionSpec = { (fadeIn(Muelle.suave()) + scaleIn(Muelle.suave(), initialScale = 0.96f)) togetherWith fadeOut(Muelle.suave()) },
                        label = "fase",
                    ) { f ->
                        when (f) {
                            Fase.CUENTA -> CuentaAtras(
                                motivo, prueba, segundos, restante, Emergencias.numero(ctx), Emergencias.contactos(ctx).map { it.nombre.ifBlank { it.telefono } },
                                onCancelar = { contando = false; Sirena.parar(ctx); finish() },
                                onYa = ::pedir,
                            )
                            Fase.HECHO -> Hecho(
                                estado, Emergencias.numero(ctx), ejemplo,
                                onFicha = { startActivity(Intent(ctx, EmergenciasActivity::class.java).putExtra(EmergenciasActivity.EXTRA_FICHA, true)) },
                                // Tocar «Llamar al 112» llama de verdad, también tras una prueba.
                                onLlamar = { Emergencias.marcarLlamadaEmergencia(ctx); Emergencias.llamar(ctx, Emergencias.numero(ctx), prueba = false) },
                                onParar = { SosServicio.parar(ctx) },
                                onCerrar = ::finish,
                            )
                        }
                    }
                }
            } }
        }
    }

    override fun onDestroy() {
        if (contando && isFinishing) Sirena.parar(this)
        super.onDestroy()
    }
}

private enum class Fase { CUENTA, HECHO }

/**
 * La cuenta atrás: el número enorme dentro de un anillo que se vacía, qué va a pasar y el botón de
 * cancelar, grande y abajo (donde llega el pulgar). Con caídas: «¿Estás bien?» y «Estoy bien».
 */
@Composable
internal fun CuentaAtras(
    motivo: Emergencias.Motivo,
    prueba: Boolean,
    segundos: Int,
    restanteMs: Long,
    numero: String,
    contactos: List<String>,
    onCancelar: () -> Unit,
    onYa: () -> Unit,
) {
    val caida = motivo == Emergencias.Motivo.CAIDA
    val queda = ((restanteMs + 999) / 1000).toInt().coerceIn(0, segundos)
    val latido = rememberInfiniteTransition(label = "latido")
    val halo by latido.animateFloat(0f, 1f, infiniteRepeatable(tween(1000), RepeatMode.Restart), label = "halo")
    BoxWithConstraints(Modifier.fillMaxSize().background(Rojo.Sos).statusBarsPadding().navigationBarsPadding()) {
        // En pantallas bajas, todo algo más compacto; el anillo se queda con el sitio que sobre.
        val compacta = maxHeight < 700.dp
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if (compacta) 10.dp else 18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (caida) IconosEm.Caida else IconosEm.Sos, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (caida) R.string.em_caida_etiqueta else R.string.em_sos_etiqueta).uppercase(), style = Texto.Etiqueta.copy(fontSize = 13.sp), color = Color.White)
                if (prueba) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.em_prueba_etiqueta).uppercase(), style = Texto.Etiqueta, color = Rojo.SosHondo,
                        modifier = Modifier.clip(Formas.Pastilla).background(Color.White).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(Modifier.weight(0.3f))
            Spacer(Modifier.height(12.dp))
            val grande = if (caida) (if (compacta) 28.sp else 34.sp) else (if (compacta) 22.sp else 26.sp)
            Text(
                stringResource(if (caida) R.string.em_caida_titulo else R.string.em_cuenta_titulo, numero),
                style = Texto.Titulo.copy(fontSize = grande, lineHeight = grande * 1.2f),
                color = Color.White, textAlign = TextAlign.Center,
            )
            if (caida) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.em_caida_texto, numero),
                    style = Texto.Cuerpo.copy(fontSize = if (compacta) 15.sp else 17.sp, lineHeight = if (compacta) 21.sp else 24.sp),
                    color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(if (compacta) 14.dp else 24.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val tam = minOf(maxWidth, maxHeight / 1.1f, 250.dp)
                // Un halo que sale del anillo cada segundo: se nota aunque no se oiga.
                Box(
                    Modifier.size(tam).graphicsLayer { val e = 1f + halo * 0.08f; scaleX = e; scaleY = e; alpha = (1f - halo) * 0.35f }
                        .clip(Formas.Pastilla).background(Color.White),
                )
                Anillo(restanteMs / (segundos * 1000f), tam, (tam.value / 21f).dp, Color.White, Color.White.copy(alpha = 0.22f), Modifier.clip(Formas.Pastilla).background(Rojo.Sos)) {
                    AnimatedContent(
                        queda,
                        transitionSpec = { (fadeIn(Muelle.vivo()) + scaleIn(Muelle.vivo(), initialScale = 0.7f)) togetherWith (fadeOut(Muelle.suave()) + scaleOut(Muelle.suave(), targetScale = 1.2f)) },
                        label = "numero",
                    ) { n ->
                        val cifra = (tam.value * 0.48f).sp
                        Text(n.toString(), style = Texto.Gigante.copy(fontSize = cifra, lineHeight = cifra), color = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(if (compacta) 14.dp else 24.dp))
            Text(
                if (contactos.isEmpty()) stringResource(R.string.em_cuenta_sin_contactos)
                else stringResource(R.string.em_cuenta_contactos, nombres(contactos)),
                style = Texto.Cuerpo.copy(fontSize = if (compacta) 15.sp else 17.sp, lineHeight = if (compacta) 21.sp else 24.sp),
                color = Color.White.copy(alpha = 0.92f), textAlign = TextAlign.Center,
                maxLines = if (compacta) 2 else 3, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(0.3f))
            Spacer(Modifier.height(if (compacta) 14.dp else 24.dp))
            BotonEnorme(stringResource(if (caida) R.string.em_estoy_bien else R.string.em_cancelar), alto = if (compacta) 68.dp else 84.dp, onClick = onCancelar)
            Spacer(Modifier.height(10.dp))
            BotonEnorme(stringResource(if (caida) R.string.em_pedir_ayuda_ya else R.string.em_llamar_ya, numero), alto = if (compacta) 60.dp else 72.dp, relleno = false, onClick = onYa)
            Spacer(Modifier.height(if (compacta) 12.dp else 20.dp))
        }
    }
}

/**
 * Después de pedir ayuda: qué se ha hecho (la llamada, a quién se ha avisado y con qué ubicación) y,
 * mientras se comparte, cómo pararlo. En modo de prueba, lo que se habría enviado.
 */
@Composable
internal fun Hecho(
    estado: SosServicio.Estado?,
    numero: String,
    ejemploSms: String?,
    onFicha: () -> Unit,
    onLlamar: () -> Unit,
    onParar: () -> Unit,
    onCerrar: () -> Unit,
) {
    val ctx = LocalContext.current
    val prueba = estado?.prueba == true
    val caida = estado?.motivo == Emergencias.Motivo.CAIDA
    // Centrado en vertical; si no cabe (pantalla pequeña, muchos contactos), se desplaza.
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(88.dp).clip(Formas.Pastilla).background(Rojo.Suave), contentAlignment = Alignment.Center) {
            Icon(if (prueba) IconosEm.Prueba else com.sios.ui.Iconos.Llamar, null, tint = Rojo.Boton, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(
            stringResource(if (prueba) R.string.em_hecho_prueba_titulo else R.string.em_hecho_titulo, numero),
            style = Texto.Titulo.copy(fontSize = 26.sp, lineHeight = 32.sp), color = Colores.Texto, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (prueba) R.string.em_hecho_prueba_texto else if (caida) R.string.em_hecho_caida_texto else R.string.em_hecho_texto),
            style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        // A quién se ha avisado.
        val avisos = estado?.avisos.orEmpty()
        Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(16.dp).animateContentSize(Muelle.suave())) {
            Text(stringResource(R.string.em_hecho_avisados).uppercase(), style = Texto.Etiqueta, color = Colores.Texto3)
            Spacer(Modifier.height(10.dp))
            if (avisos.isEmpty()) Text(stringResource(R.string.em_hecho_sin_contactos), style = Texto.Pequeno, color = Colores.Texto3)
            avisos.forEachIndexed { i, a ->
                if (i > 0) Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(34.dp).clip(Formas.Pastilla).background(Colores.Superficie2), contentAlignment = Alignment.Center) {
                        Text(a.contacto.nombre.firstOrNull()?.uppercase() ?: "·", style = Texto.Subtitulo, color = Colores.Texto)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.contacto.nombre.ifBlank { a.contacto.telefono }, style = Texto.Cuerpo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(com.sios.sistema.Llamadas.formatear(a.contacto.telefono), style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1)
                    }
                    Spacer(Modifier.width(8.dp))
                    EstadoEnvio(a.envio)
                }
            }
        }

        // Dónde estás y hasta cuándo se comparte.
        if (estado != null && avisos.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(IconosEm.Ubicacion, null, tint = if (estado.activo) Rojo.Boton else Colores.Texto3, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    val hasta = hora(ctx, estado.hasta)
                    Text(
                        stringResource(if (estado.activo) R.string.em_hecho_compartiendo else R.string.em_hecho_compartido),
                        style = Texto.Cuerpo, color = Colores.Texto,
                    )
                    if (estado.activo) Text(
                        estado.precision?.let { stringResource(R.string.em_hecho_precision, hasta, it) } ?: stringResource(R.string.em_hecho_buscando, hasta),
                        style = Texto.Pequeno, color = Colores.Texto3,
                    )
                }
            }
        }

        // En prueba: cómo se vería el mensaje.
        AnimatedVisibility(prueba && ejemploSms != null, enter = fadeIn(Muelle.suave())) {
            Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(Formas.Tarjeta).border(1.dp, Colores.Borde, Formas.Tarjeta).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(IconosEm.Mensaje, null, tint = Colores.Texto3, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.em_hecho_asi_sms).uppercase(), style = Texto.Etiqueta, color = Colores.Texto3)
                }
                Spacer(Modifier.height(8.dp))
                Text(ejemploSms.orEmpty(), style = Texto.Pequeno, color = Colores.Texto2)
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BotonSecundario(stringResource(R.string.em_ver_ficha), Modifier.weight(1f), onClick = onFicha)
            BotonSecundario(stringResource(R.string.em_volver_llamar, numero), Modifier.weight(1f), onClick = onLlamar)
        }
        if (estado?.activo == true && avisos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            BotonRojo(stringResource(R.string.em_parar_sos), onClick = onParar)
        }
        Spacer(Modifier.height(4.dp))
        BotonTexto(stringResource(R.string.em_cerrar), Modifier.fillMaxWidth(), onClick = onCerrar)
        Spacer(Modifier.height(16.dp))
    }
    }
}

@Composable
private fun EstadoEnvio(e: SosServicio.Envio) {
    val (texto, color) = when (e) {
        SosServicio.Envio.ENVIANDO -> stringResource(R.string.em_envio_enviando) to Colores.Texto3
        SosServicio.Envio.ENVIADO -> stringResource(R.string.em_envio_enviado) to Rojo.Verde
        SosServicio.Envio.SIMULADO -> stringResource(R.string.em_envio_simulado) to Colores.Texto2
        SosServicio.Envio.FALLO -> stringResource(R.string.em_envio_fallo) to Rojo.Boton
        SosServicio.Envio.SIN_PERMISO -> stringResource(R.string.em_envio_sin_permiso) to Rojo.Boton
    }
    val c by animateColorAsState(color, Muelle.suave(), label = "envio")
    Text(texto, style = Texto.Pequeno, color = c, modifier = Modifier.clip(Formas.Pastilla).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp))
}
