package com.sios.boveda

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.ui.BotonIcono
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Marca
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * La Bóveda: contraseñas, códigos 2FA y documentos. Se abre con la huella o el PIN y se cierra sola
 * (al apagar la pantalla, al pasar el tiempo elegido o al salir). FLAG_SECURE: no sale en capturas ni
 * en la miniatura de recientes; y mientras está en pantalla las manos de la IA no leen nada.
 */
class BovedaActivity : ComponentActivity() {
    companion object {
        /** 0 contraseñas, 1 códigos, 2 documentos. */
        const val EXTRA_PESTANA = "pestana"
    }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Marca.envolver(base))

    private val fallo = mutableStateOf<String?>(null)
    private var pedirAlVolver = true
    private var autenticando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Boveda.revisar(this)
        // Desde el recuadro de los ajustes rápidos se entra directo en los códigos.
        val pestana = intent.getIntExtra(EXTRA_PESTANA, 0)
        setContent { TemaSios { PantallaBoveda(fallo.value, onAbrir = ::abrir, onAtras = ::finish, pestana = pestana) } }
    }

    override fun onResume() {
        super.onResume()
        Boveda.enPantalla = true
        Boveda.revisar(this)
        // Al entrar (ya creada), la huella directamente: sin pulsar nada.
        if (Boveda.estado.value == Boveda.Estado.CERRADA && pedirAlVolver && !autenticando) {
            pedirAlVolver = false
            abrir()
        }
    }

    override fun onPause() {
        super.onPause()
        Boveda.enPantalla = false
    }

    override fun onStop() {
        super.onStop()
        if (autenticando || isChangingConfigurations) return
        pedirAlVolver = true
        if (Boveda.minutosCierre(this) <= 0) Boveda.cerrar()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        Boveda.tocar()
    }

    private fun abrir() {
        if (autenticando) return
        autenticando = true
        fallo.value = null
        Desbloqueo.pedir(this, onAbierta = { autenticando = false }, onFallo = { f ->
            autenticando = false
            fallo.value = when (f) {
                Desbloqueo.Fallo.Cancelado -> null
                Desbloqueo.Fallo.SinBloqueo -> getString(R.string.bv_sin_bloqueo)
                Desbloqueo.Fallo.Invalidada -> null
                is Desbloqueo.Fallo.Otro -> f.mensaje ?: getString(R.string.bv_error_abrir)
            }
        })
    }
}

// ——— Navegación: páginas que entran desde la derecha con un muelle (como en Ajustes) ———

internal sealed interface Destino {
    data class Ficha(val id: String) : Destino
    data class Editor(val tipo: Tipo, val id: String? = null, val plantilla: Entrada? = null) : Destino
    data object Salud : Destino
    data object Ajustes : Destino
    data class Camara(val modo: ModoCamara, val alLeer: (ResultadoCamara) -> Unit) : Destino
    data class Visor(val id: String, val pagina: Int) : Destino
}

@Stable
internal class Navegacion {
    class Capa(val destino: Destino, val progreso: Animatable<Float, AnimationVector1D>)

    val pila = mutableStateListOf<Capa>()
    /** La página de arriba puede quedarse con el «atrás» (el editor con cambios pregunta antes). */
    var antesDeCerrar: (() -> Boolean)? = null
    var alcance: kotlinx.coroutines.CoroutineScope? = null

    fun abrir(d: Destino) {
        val c = Capa(d, Animatable(0f))
        pila.add(c)
        alcance?.launch { c.progreso.animateTo(1f, Muelle.cajon()) }
    }

    fun cerrar(forzar: Boolean = false) {
        if (!forzar && antesDeCerrar?.invoke() == true) return
        val c = pila.lastOrNull() ?: return
        antesDeCerrar = null
        alcance?.launch {
            c.progreso.animateTo(0f, Muelle.cajon())
            pila.remove(c)
        }
    }

    /** Cambia la página de arriba por otra (al guardar en el editor, vuelve a la ficha). */
    fun reemplazar(d: Destino) {
        val c = pila.lastOrNull() ?: return abrir(d)
        antesDeCerrar = null
        pila[pila.lastIndex] = Capa(d, c.progreso)
    }

    fun vaciar() { pila.clear(); antesDeCerrar = null }
}

@Composable
internal fun PantallaBoveda(fallo: String?, onAbrir: () -> Unit, onAtras: () -> Unit, pestana: Int = 0) {
    val estado by Boveda.estado.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Colores.Fondo)) {
        AnimatedContent(
            estado,
            transitionSpec = { (fadeIn(Muelle.suave()) + scaleIn(Muelle.suave(), initialScale = 0.97f)) togetherWith fadeOut(Muelle.suave()) },
            contentKey = { it == Boveda.Estado.ABIERTA },
            label = "boveda",
        ) { e ->
            when (e) {
                Boveda.Estado.ABIERTA -> Abierta(onAtras, pestana)
                else -> Cerrada(e, fallo, onAbrir, onAtras)
            }
        }
    }
}

@Composable
private fun Abierta(onAtras: () -> Unit, pestana: Int) {
    val nav = remember { Navegacion() }
    val alcance = rememberCoroutineScope()
    nav.alcance = alcance
    val avisos = remember { Avisador() }
    val ancho = with(LocalDensity.current) { 14.dp.toPx() }

    PredictiveBackHandler(enabled = nav.pila.isNotEmpty()) { gesto ->
        val c = nav.pila.lastOrNull()
        // El editor con cambios: el gesto no arrastra, pregunta.
        if (c == null || nav.antesDeCerrar != null) {
            gesto.collect { }
            nav.cerrar()
            return@PredictiveBackHandler
        }
        try {
            gesto.collect { ev -> c.progreso.snapTo(1f - ev.progress * 0.4f) }
            nav.cerrar(forzar = true)
        } catch (e: CancellationException) {
            alcance.launch { c.progreso.animateTo(1f, Muelle.cajon()) }
            throw e
        }
    }

    Box(Modifier.fillMaxSize()) {
        val pila = nav.pila
        val primera = pila.firstOrNull()
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val p = primera?.takeIf { it.destino !is Destino.Visor }?.progreso?.value ?: 0f
                translationX = -p * size.width * 0.25f
            },
        ) {
            Principal(nav, avisos, onAtras, pestanaInicial = pestana)
            if (primera != null) Box(Modifier.fillMaxSize().graphicsLayer { alpha = primera.progreso.value }.background(Color.Black.copy(alpha = 0.22f)))
        }
        pila.forEachIndexed { i, capa ->
            val siguiente = pila.getOrNull(i + 1)
            val visor = capa.destino is Destino.Visor
            androidx.compose.runtime.key(capa) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val p = capa.progreso.value
                            if (visor) {
                                alpha = p
                                scaleX = 0.94f + 0.06f * p; scaleY = scaleX
                            } else {
                                val atras = siguiente?.takeIf { it.destino !is Destino.Visor }?.progreso?.value ?: 0f
                                translationX = (1f - p) * size.width - atras * size.width * 0.25f
                            }
                        }
                        .drawBehind {
                            if (!visor) drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)), startX = -ancho, endX = 0f),
                                topLeft = Offset(-ancho, 0f), size = Size(ancho, size.height),
                            )
                        }
                        // Encima de lo de abajo: que ningún toque la atraviese.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
                ) {
                    Pagina(capa.destino, nav, avisos)
                    if (siguiente != null) Box(Modifier.fillMaxSize().graphicsLayer { alpha = siguiente.progreso.value }.background(Color.Black.copy(alpha = 0.22f)))
                }
            }
        }
        PastillaAviso(avisos, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }
}

@Composable
private fun Pagina(d: Destino, nav: Navegacion, avisos: Avisador) {
    when (d) {
        is Destino.Ficha -> Ficha(d.id, nav, avisos)
        is Destino.Editor -> Editor(d, nav, avisos)
        Destino.Salud -> PaginaSalud(nav)
        Destino.Ajustes -> PaginaAjustes(nav, avisos)
        is Destino.Camara -> PaginaCamara(d.modo, onCerrar = { nav.cerrar(forzar = true) }) { r -> nav.cerrar(forzar = true); d.alLeer(r) }
        is Destino.Visor -> Visor(d.id, d.pagina) { nav.cerrar(forzar = true) }
    }
}

// ——— Cerrada, nueva o perdida ———

@Composable
private fun Cerrada(estado: Boveda.Estado, fallo: String?, onAbrir: () -> Unit, onAtras: () -> Unit) {
    val ctx = LocalContext.current
    var borrar by remember { mutableStateOf(false) }
    val nueva = estado == Boveda.Estado.NUEVA
    val perdida = estado == Boveda.Estado.INVALIDADA
    val sinBloqueo = remember(estado, fallo) { !Boveda.hayBloqueo(ctx) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(52.dp)) {
            BotonIcono(Iconos.Atras, stringResource(R.string.comun_atras), Modifier.align(Alignment.CenterStart).padding(start = 8.dp), onClick = onAtras)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(26.dp)).background(if (perdida) Colores.Rojo.copy(alpha = 0.14f) else Colores.AcentoSuave),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (perdida) IconosBoveda.Aviso else IconosBoveda.Boveda, null, tint = if (perdida) Colores.Rojo else Colores.Acento, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(if (perdida) R.string.bv_invalidada_titulo else R.string.bv_titulo),
                style = com.sios.ajustes.TituloGrande, color = Colores.Texto, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(
                    when {
                        perdida -> R.string.bv_invalidada_nota
                        nueva -> R.string.bv_nueva_nota
                        else -> R.string.bv_cerrada_nota
                    },
                ),
                style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp),
            )
            if (nueva) {
                Spacer(Modifier.height(28.dp))
                Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Punto(IconosBoveda.Candado, R.string.bv_nueva_p1_titulo, R.string.bv_nueva_p1)
                    Punto(IconosBoveda.Llave, R.string.bv_nueva_p2_titulo, R.string.bv_nueva_p2)
                    Punto(IconosBoveda.Escudo, R.string.bv_nueva_p3_titulo, R.string.bv_nueva_p3)
                }
            }
            val aviso = when {
                sinBloqueo && !perdida -> stringResource(R.string.bv_sin_bloqueo)
                else -> fallo
            }
            if (aviso != null) {
                Spacer(Modifier.height(18.dp))
                Text(aviso, style = Texto.Pequeno, color = Colores.Rojo, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                perdida -> {
                    BotonPrimario(stringResource(R.string.bv_empezar_de_cero), Modifier.fillMaxWidth()) { borrar = true }
                    Text(stringResource(R.string.bv_invalidada_importar), style = Texto.Pequeno, color = Colores.Texto3, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                sinBloqueo -> {
                    BotonPrimario(stringResource(R.string.bv_poner_bloqueo), Modifier.fillMaxWidth()) {
                        runCatching { ctx.startActivity(Intent(android.app.admin.DevicePolicyManager.ACTION_SET_NEW_PASSWORD)) }
                            .onFailure { ctx.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                    }
                }
                nueva -> BotonPrimario(stringResource(R.string.bv_crear), Modifier.fillMaxWidth(), onAbrir)
                else -> {
                    BotonPrimario(stringResource(R.string.bv_abrir), Modifier.fillMaxWidth(), onAbrir)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(IconosBoveda.Candado, null, tint = Colores.Texto3, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.bv_solo_aqui), style = Texto.Pequeno, color = Colores.Texto3)
                    }
                }
            }
        }
    }
    if (borrar) Hoja({ borrar = false }) {
        Text(stringResource(R.string.bv_empezar_titulo), style = Texto.Subtitulo, color = Colores.Texto)
        Text(stringResource(R.string.bv_empezar_nota), style = Texto.Cuerpo, color = Colores.Texto2)
        BotonPeligro(stringResource(R.string.bv_empezar_de_cero), Modifier.fillMaxWidth()) { borrar = false; Boveda.borrarTodo(ctx) }
        BotonTexto(stringResource(R.string.bv_cancelar), Modifier.fillMaxWidth()) { borrar = false }
    }
}

@Composable
private fun Punto(icono: androidx.compose.ui.graphics.vector.ImageVector, titulo: Int, nota: Int) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(36.dp).clip(Formas.Pequena).background(Colores.Superficie), contentAlignment = Alignment.Center) {
            Icon(icono, null, tint = Colores.Acento, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(titulo), style = Texto.Subtitulo.copy(fontSize = Texto.Cuerpo.fontSize), color = Colores.Texto)
            Text(stringResource(nota), style = Texto.Pequeno, color = Colores.Texto3)
        }
    }
}
