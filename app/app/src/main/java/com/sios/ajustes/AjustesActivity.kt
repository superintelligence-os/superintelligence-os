package com.sios.ajustes

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.sios.datos.Estado
import com.sios.datos.Servicio
import com.sios.ui.HojaCreacion
import com.sios.datos.Creacion
import com.sios.ui.Colores
import com.sios.ui.BotonSecundario
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.AjustesConversacion
import com.sios.ui.ResumenUso
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable

class AjustesActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Servicio.iniciar()
        // El cortafuegos tiene su propia pantalla: desde su recuadro (mantener pulsado) o «seccion=cortafuegos».
        if (esCortafuegos(intent)) {
            startActivity(Intent(this, com.sios.cortafuegos.CortafuegosActivity::class.java))
            finish()
            return
        }
        // La Bóveda también: «seccion=boveda» o mantener pulsado su recuadro.
        if (esBoveda(intent)) {
            startActivity(Intent(this, com.sios.boveda.BovedaActivity::class.java))
            finish()
            return
        }
        val (pagina, grupo) = destinoDe(intent)
        setContent { TemaSios { PantallaAjustes(onAtras = ::finish, inicial = pagina, grupoInicial = grupo) } }
    }

    private fun esCortafuegos(i: Intent?): Boolean {
        i ?: return false
        if (i.getStringExtra("seccion") == "cortafuegos") return true
        if (i.action != "android.service.quicksettings.action.QS_TILE_PREFERENCES") return false
        @Suppress("DEPRECATION")
        return i.getParcelableExtra<android.content.ComponentName>(Intent.EXTRA_COMPONENT_NAME)?.className.orEmpty().endsWith(".CortafuegosTile")
    }

    private fun esBoveda(i: Intent?): Boolean {
        i ?: return false
        if (i.getStringExtra("seccion") == "boveda") return true
        if (i.action != "android.service.quicksettings.action.QS_TILE_PREFERENCES") return false
        @Suppress("DEPRECATION")
        return i.getParcelableExtra<android.content.ComponentName>(Intent.EXTRA_COMPONENT_NAME)?.className.orEmpty().endsWith(".BovedaTile")
    }

    /**
     * A qué subpágina se llega de fuera: el extra «seccion» (el modo coche, el aviso de «Oye Claude»,
     * el uso desde el inicio), mantener pulsado un recuadro de los ajustes rápidos o los permisos de salud.
     */
    private fun destinoDe(i: Intent?): Pair<Pagina?, String?> {
        i ?: return null to null
        i.getStringExtra("seccion")?.let { s ->
            return when (s) {
                "coche" -> Pagina.COCHE to null
                "voz", "oye" -> Pagina.ASISTENTE to "voz"
                "dictado", "traduccion", "sinconexion" -> Pagina.ASISTENTE to "sinconexion"
                "uso" -> Pagina.USO to null
                "copia" -> Pagina.COPIA to null
                "mac", "ordenador" -> Pagina.ORDENADOR to null
                "pantalla" -> Pagina.MEMORIA to "pantalla"
                "sos", "ficha", "caidas", "viaje" -> Pagina.EMERGENCIAS to s
                else -> Pagina.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } to null
            }
        }
        if (i.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES") {
            @Suppress("DEPRECATION")
            val recuadro = i.getParcelableExtra<android.content.ComponentName>(Intent.EXTRA_COMPONENT_NAME)?.className.orEmpty()
            return when {
                recuadro.endsWith(".UsoTile") -> Pagina.USO to null
                recuadro.endsWith(".LeerTile") -> Pagina.APARIENCIA to "accesibilidad"
                recuadro.endsWith(".FocoTile") -> Pagina.NOTIFICACIONES to null
                recuadro.endsWith(".MemoriaPantallaTile") -> Pagina.MEMORIA to "pantalla"
                recuadro.endsWith(".SosTile") -> Pagina.EMERGENCIAS to "sos"
                else -> null to null
            }
        }
        if (i.action == "android.intent.action.VIEW_PERMISSION_USAGE") return Pagina.PRIVACIDAD to "salido"
        return null to null
    }
}

/**
 * Ajustes de SIOS: la portada con las categorías y, encima, la subpágina abierta. La subpágina entra
 * desde la derecha con un muelle y sale igual, con el botón o con el gesto de atrás (sigue al dedo y,
 * si se cancela, vuelve a su sitio). La portada se queda debajo, con su desplazamiento y su búsqueda.
 */
@Composable
internal fun PantallaAjustes(onAtras: () -> Unit, inicial: Pagina? = null, grupoInicial: String? = null) {
    val estado by Servicio.estado.collectAsStateWithLifecycle()
    val creaciones by Servicio.creaciones.collectAsStateWithLifecycle()
    var hoja by remember { mutableStateOf<Creacion?>(null) }
    LaunchedEffect(Unit) { Servicio.uso(refrescar = true) }

    // La subpágina (se guarda: cambiar de claro a oscuro recrea la pantalla y hay que seguir donde se estaba).
    var abierta by rememberSaveable { mutableStateOf(inicial?.takeIf { it.visible }?.name) }
    var destino by rememberSaveable { mutableStateOf(grupoInicial) }
    val pagina = abierta?.let { n -> Pagina.entries.firstOrNull { it.name == n } }
    // 0: la portada; 1: la subpágina entera a la vista.
    val progreso = remember { Animatable(if (pagina != null) 1f else 0f) }
    val alcance = rememberCoroutineScope()
    // Al volver a la portada (o de otra app), los resúmenes se vuelven a leer.
    var refresco by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { refresco++; onPauseOrDispose { } }

    fun abrir(p: Pagina, grupo: String?) {
        abierta = p.name
        destino = grupo
        alcance.launch { progreso.animateTo(1f, Muelle.cajon()) }
    }
    fun cerrar() {
        alcance.launch {
            progreso.animateTo(0f, Muelle.cajon())
            abierta = null
            destino = null
            refresco++
        }
    }
    // Si la IA deja de ser Claude con el uso abierto, se vuelve a la portada.
    LaunchedEffect(pagina?.visible) { if (pagina != null && !pagina.visible) cerrar() }

    PredictiveBackHandler(enabled = pagina != null) { gesto ->
        try {
            gesto.collect { e -> progreso.snapTo(1f - e.progress * 0.4f) }
            progreso.animateTo(0f, Muelle.cajon())
            abierta = null
            destino = null
            refresco++
        } catch (e: CancellationException) {
            alcance.launch { progreso.animateTo(1f, Muelle.cajon()) }
            throw e
        }
    }

    val densidad = LocalDensity.current
    Box(Modifier.fillMaxSize().background(Colores.Fondo)) {
        // La portada se aparta un poco hacia la izquierda y se oscurece mientras entra la subpágina.
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -progreso.value * size.width * 0.25f }) {
            Portada(estado, creaciones, refresco, onAbrir = ::abrir, onAtras = onAtras)
            if (pagina != null) Box(Modifier.fillMaxSize().graphicsLayer { alpha = progreso.value }.background(Color.Black.copy(alpha = 0.22f)))
        }
        if (pagina != null) {
            val sombra = with(densidad) { 14.dp.toPx() }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = (1f - progreso.value) * size.width }
                    // Una sombra suave en el borde izquierdo, para que se lea como una hoja encima.
                    .drawBehind {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)), startX = -sombra, endX = 0f),
                            topLeft = Offset(-sombra, 0f), size = Size(sombra, size.height),
                        )
                    },
            ) {
                androidx.compose.runtime.key(pagina) {
                    Subpagina(pagina, destino, onAtras = ::cerrar) {
                        ContenidoPagina(pagina, estado, creaciones) { hoja = it }
                    }
                }
            }
        }
    }
    hoja?.let { c -> HojaCreacion(creaciones.firstOrNull { it.id == c.id } ?: c, onCerrar = { hoja = null }) }
}

/** Lo que hay dentro de cada categoría: las secciones de siempre, repartidas en grupos. */
@Composable
private fun ContenidoPagina(p: Pagina, estado: Estado, creaciones: List<Creacion>, onCreacion: (Creacion) -> Unit) {
    val ctx = LocalContext.current
    when (p) {
        Pagina.INTELIGENCIA -> {
            SeccionInteligencia { _, n, contenido -> Grupo("ia", null, n, Estilo.Libre, contenido) }
            Grupo("conversacion", stringResource(R.string.ajs_g_conversaciones), null, Estilo.Libre) {
                AjustesConversacion(estado.ajustes, estado.ocupado, estado.modeloReal)
            }
        }

        // El uso del plan solo tiene sentido con Claude (los demás tienen sus propios límites).
        Pagina.USO -> if (com.sios.ui.Marca.id == "claude") {
            Grupo("uso", null, stringResource(R.string.ajustes_uso_nota), Estilo.Tarjeta) { ResumenUso(estado.uso) }
            Grupo("ahorro", null, null, Estilo.Tarjeta) {
                Fila(stringResource(R.string.ajustes_ahorro_titulo), stringResource(R.string.ajustes_ahorro_nota)) {
                    Interruptor(estado.ajustes.ahorro) { Servicio.cambiarAjustes(ahorro = it) }
                }
            }
        }

        Pagina.ASISTENTE -> {
            // Voz: responder en voz alta cuando se le habla.
            Grupo("voz", stringResource(R.string.ajustes_voz), stringResource(R.string.ajustes_voz_nota), Estilo.Tarjeta) {
                var enVoz by remember { mutableStateOf(com.sios.sistema.VozAsistente.responderEnVoz(ctx)) }
                Fila(stringResource(R.string.ajustes_envoz_titulo), stringResource(R.string.ajustes_envoz_nota)) {
                    Interruptor(enVoz) { enVoz = it; com.sios.sistema.VozAsistente.ponerResponderEnVoz(ctx, it) }
                }
            }
            // Dictado y traducción sin conexión: en el móvil, sin internet y sin IA.
            SeccionVozSinConexion { t, n, contenido -> Grupo("sinconexion", t, n, Estilo.Libre, contenido) }
            // Mantener pulsado inicio o el botón de encendido: el asistente del sistema.
            Grupo("asistente", stringResource(R.string.ajs_g_asistente), null, Estilo.Lista) {
                FilaLista(stringResource(R.string.ajustes_asistente_titulo), stringResource(R.string.ajustes_asistente_nota), separador = false) {
                    ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                }
            }
            // Manos de Claude (tocar y escribir en las apps, con el borde naranja) e internet.
            Grupo("puede", stringResource(R.string.ajs_g_puede), null, Estilo.Tarjeta) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    var manos by remember { mutableStateOf(com.sios.sistema.Manos.permitido(ctx)) }
                    Fila(stringResource(R.string.ajustes_manos_titulo), stringResource(R.string.ajustes_manos_nota)) {
                        Interruptor(manos) { manos = it; com.sios.sistema.Manos.permitir(ctx, it) }
                    }
                    Fila(stringResource(R.string.ajustes_web_titulo), stringResource(R.string.ajustes_web_nota)) {
                        Interruptor(estado.ajustes.web) { Servicio.cambiarAjustes(web = it) }
                    }
                }
            }
        }

        // Lo que ha hecho Claude: aquí se ven también los agentes, que no tienen pantalla propia.
        Pagina.CREACIONES -> Grupo(
            "creaciones", null, stringResource(R.string.ajustes_creaciones_nota),
            if (creaciones.isEmpty()) Estilo.Tarjeta else Estilo.Lista,
        ) {
            if (creaciones.isEmpty()) {
                Text(stringResource(R.string.ajustes_creaciones_vacio), style = Texto.Pequeno, color = Colores.Texto3)
            }
            creaciones.forEachIndexed { i, c ->
                val tipo = stringResource(
                    when (c.tipo) {
                        "app" -> R.string.creacion_tipo_app
                        "agente" -> R.string.creacion_tipo_agente
                        "rutina" -> R.string.creacion_tipo_rutina
                        else -> R.string.creacion_tipo_widget
                    },
                )
                val nota = buildList {
                    add(tipo)
                    c.cada?.let { add(stringResource(R.string.creacion_cada, it)) }
                    if (c.pausado) add(stringResource(R.string.creacion_pausado))
                    c.error?.let { add(stringResource(R.string.creacion_error, it)) }
                }.joinToString(" · ")
                FilaLista(c.nombre, nota, separador = i < creaciones.lastIndex) { onCreacion(c) }
            }
        }

        // Memoria del móvil: el índice local de fotos, documentos y avisos y, aparte, la memoria de pantalla.
        Pagina.MEMORIA -> {
            Grupo("memoria", null, stringResource(R.string.ajustes_memoria_nota), Estilo.Tarjeta) {
                var activa by remember { mutableStateOf(com.sios.sistema.Memoria.activa(ctx)) }
                var cuenta by remember { mutableStateOf(0) }
                LaunchedEffect(activa) {
                    cuenta = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.sios.sistema.Memoria.cuenta(ctx) }
                }
                Fila(
                    stringResource(R.string.ajustes_memoria_titulo),
                    if (activa) pluralStringResource(R.plurals.ajustes_memoria_cuenta, cuenta, cuenta) else stringResource(R.string.ajustes_memoria_apagada),
                ) {
                    Interruptor(activa) { activa = it; com.sios.sistema.Memoria.activar(ctx, it) }
                }
            }
            SeccionMemoriaPantalla { t, n, contenido -> Grupo("pantalla", t, n, Estilo.Tarjeta, contenido) }
        }

        Pagina.APARIENCIA -> {
            SeccionApariencia { _, n, contenido -> Grupo("apariencia", null, n, Estilo.Tarjeta, contenido) }
            SeccionAccesibilidad { t, n, contenido -> Grupo("accesibilidad", t, n, Estilo.Tarjeta, contenido) }
        }

        Pagina.NOTIFICACIONES -> {
            // Notificaciones con cerebro: resumen al desbloquear y mensajes sin contestar.
            Grupo("avisos", null, stringResource(R.string.ajustes_avisos_nota), Estilo.Tarjeta) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    var resumen by remember { mutableStateOf(com.sios.sistema.Preferencias.resumen(ctx)) }
                    var pendientes by remember { mutableStateOf(com.sios.sistema.Preferencias.pendientes(ctx)) }
                    Fila(stringResource(R.string.ajustes_resumen_titulo), stringResource(R.string.ajustes_resumen_nota)) {
                        Interruptor(resumen) { resumen = it; com.sios.sistema.Preferencias.poner(ctx, "resumen", it) }
                    }
                    Fila(stringResource(R.string.ajustes_pendientes_titulo), stringResource(R.string.ajustes_pendientes_nota)) {
                        Interruptor(pendientes) { pendientes = it; com.sios.sistema.Preferencias.poner(ctx, "pendientes", it) }
                    }
                }
            }
            SeccionNoche { t, n, contenido -> Grupo("noche", t, n, Estilo.Tarjeta, contenido) }
        }

        // Llamadas: identificar desconocidos (siempre) y, si se quiere, que suenen en silencio.
        Pagina.LLAMADAS -> Grupo("desconocidos", stringResource(R.string.ajs_g_desconocidos), stringResource(R.string.ajustes_llamadas_nota), Estilo.Tarjeta) {
            var silenciar by remember { mutableStateOf(com.sios.sistema.Llamadas.silenciarDesconocidos(ctx)) }
            Fila(stringResource(R.string.ajustes_silenciar_titulo), stringResource(R.string.ajustes_silenciar_nota)) {
                Interruptor(silenciar) { silenciar = it; com.sios.sistema.Llamadas.ponerSilenciar(ctx, it) }
            }
            Spacer(Modifier.height(14.dp))
            var atender by remember { mutableStateOf(com.sios.sistema.Llamadas.atenderDesconocidos(ctx)) }
            Fila(stringResource(R.string.ajustes_atender_titulo), stringResource(R.string.ajustes_atender_nota)) {
                Interruptor(atender) { atender = it; com.sios.sistema.Llamadas.ponerAtender(ctx, it) }
            }
        }

        // Teclado de SIOS: activarlo de un toque (en SIOS) o desde los ajustes del sistema.
        Pagina.TECLADO -> Grupo("teclado", null, stringResource(R.string.ajustes_teclado_nota), Estilo.Tarjeta) {
            var usando by remember { mutableStateOf(com.sios.teclado.Teclados.enUso(ctx)) }
            if (usando) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Iconos.Hecho, null, tint = Colores.Acento, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ajustes_teclado_usando), style = Texto.Cuerpo, color = Colores.Texto)
            }
            else BotonSecundario(stringResource(R.string.teclado_sios_usar), Modifier.fillMaxWidth()) {
                usando = com.sios.teclado.Teclados.usar(ctx)
            }
        }

        // Apps predeterminadas y tiendas; modo escritorio (AjustesApps.kt y AjustesEscritorio.kt).
        Pagina.APPS -> SeccionApps()
        Pagina.ESCRITORIO -> SeccionModoEscritorio()

        Pagina.COCHE -> SeccionCoche { _, n, contenido -> Grupo("coche", null, n, Estilo.Tarjeta, contenido) }

        Pagina.FAMILIA -> SeccionFamilia { _, n, contenido -> Grupo("familia", null, n, Estilo.Tarjeta, contenido) }

        Pagina.EMERGENCIAS -> com.sios.emergencias.AjustesEmergencias()

        Pagina.PRIVACIDAD -> {
            // Red: el cortafuegos (bloqueo de rastreadores e internet por app), en su propia pantalla.
            Grupo("red", stringResource(R.string.cf_g_red), null, Estilo.Lista) {
                val cf by com.sios.cortafuegos.Cortafuegos.estado.collectAsStateWithLifecycle()
                val cambios by com.sios.cortafuegos.Registro.cambios.collectAsStateWithLifecycle()
                var bloqueados by remember { mutableIntStateOf(0) }
                LaunchedEffect(cambios) {
                    bloqueados = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.sios.cortafuegos.Registro.bloqueadosHoy(ctx) }
                }
                val nota = if (cf.encendido || cf.arrancando) pluralStringResource(R.plurals.cf_ajustes_activo, bloqueados, com.sios.cortafuegos.numero(bloqueados))
                    else stringResource(R.string.cf_ajustes_apagado)
                FilaLista(stringResource(R.string.cf_titulo), nota, separador = false) {
                    ctx.startActivity(Intent(ctx, com.sios.cortafuegos.CortafuegosActivity::class.java))
                }
            }
            // La Bóveda (contraseñas, códigos y documentos), en su propia pantalla.
            Grupo("boveda", null, null, Estilo.Lista) {
                FilaLista(stringResource(R.string.bv_titulo), stringResource(R.string.bv_ajustes_nota), separador = false) {
                    ctx.startActivity(Intent(ctx, com.sios.boveda.BovedaActivity::class.java))
                }
            }
            SeccionPrivacidad { t, n, contenido -> Grupo("salido", t, n, Estilo.Libre, contenido) }
            Grupo("actividad", null, null, Estilo.Lista) {
                FilaLista(stringResource(R.string.actividad_titulo), stringResource(R.string.actividad_nota), separador = false) {
                    ctx.startActivity(Intent(ctx, ActividadActivity::class.java))
                }
            }
            SeccionSeguridad { t, n, contenido -> Grupo("bloqueo", t, n, Estilo.Tarjeta, contenido) }
        }

        // Tiene sus propias tarjetas (cada ordenador, el código): va sin tarjeta.
        Pagina.ORDENADOR -> SeccionMac { _, n, contenido -> Grupo("mac", null, n, Estilo.Libre, contenido) }

        Pagina.COPIA -> SeccionCopia { _, n, contenido -> Grupo("copia", null, n, Estilo.Tarjeta, contenido) }

        Pagina.SISTEMA -> {
            SeccionActualizaciones { t, n, contenido -> Grupo("actualizaciones", t, n, Estilo.Tarjeta, contenido) }
            Grupo("sistema_ota", null, null, Estilo.Lista) {
                FilaLista(stringResource(R.string.ota_titulo), stringResource(R.string.ota_nota_ajustes), separador = false) {
                    ctx.startActivity(Intent(ctx, SistemaOtaActivity::class.java))
                }
            }
            Grupo("ayuda", stringResource(R.string.ajs_g_ayuda), null, Estilo.Lista) {
                FilaLista(stringResource(R.string.comp_titulo), stringResource(R.string.comp_nota), separador = true) {
                    ctx.startActivity(Intent(ctx, ComprobacionActivity::class.java))
                }
                FilaLista(stringResource(R.string.ajustes_tour_titulo), stringResource(R.string.ajustes_tour_nota), separador = false) {
                    ctx.startActivity(Intent(ctx, com.sios.sistema.PresentacionActivity::class.java))
                }
            }
            Grupo("servicio", stringResource(R.string.ajustes_servicio), null, Estilo.Tarjeta) {
                Fila(
                    stringResource(if (estado.conectado) R.string.ajustes_conectado else R.string.ajustes_sin_conexion),
                    stringResource(R.string.ajustes_servicio_nota),
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(Formas.Pastilla)
                            .background(if (estado.conectado) Colores.Acento else Colores.Rojo)
                    )
                }
            }
            Grupo("telefono", null, null, Estilo.Lista) {
                FilaLista(stringResource(R.string.comun_ajustes_telefono), null, separador = false) { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }
        }
    }
}

@Composable
private fun Fila(titulo: String, nota: String?, final: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, style = Texto.Cuerpo, color = Colores.Texto)
            if (nota != null) Text(nota, style = Texto.Pequeno, color = Colores.Texto3)
        }
        Spacer(Modifier.width(12.dp))
        final()
    }
}


/** Versión instalada, si hay algo nuevo y la opción de que se instale solo. */
@Composable
private fun SeccionActualizaciones(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val alcance = rememberCoroutineScope()
    var info by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var trabajando by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { info = Servicio.actualizaciones() }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val disponible = info?.optJSONObject("disponible")
    val hayInstalable = disponible != null && (disponible.optJSONObject("app") != null || disponible.optJSONObject("servicio") != null)
    val nota = when {
        trabajando -> stringResource(R.string.act_trabajando)
        info?.optBoolean("sin_canal") == true || info?.optString("canal").isNullOrBlank() -> stringResource(R.string.act_sin_canal)
        info?.optString("error").let { !it.isNullOrBlank() && it != "null" } -> stringResource(R.string.act_error, info!!.optString("error"))
        hayInstalable -> stringResource(R.string.act_disponible, disponible!!.optString("notas"))
        disponible?.optJSONObject("rom") != null -> stringResource(R.string.act_rom, disponible.optJSONObject("rom")!!.optString("descarga"))
        info?.optLong("ultima_busqueda", 0L)?.let { it > 0 } == true -> stringResource(
            R.string.act_al_dia,
            com.sios.ui.haceRato(ctx, info!!.optLong("ultima_busqueda")),
        )
        else -> stringResource(R.string.act_nunca)
    }
    seccion(stringResource(R.string.act_titulo), stringResource(R.string.act_version, version)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(nota, style = Texto.Pequeno, color = Colores.Texto2)
            var solas by remember(info) { mutableStateOf(info?.optBoolean("automaticas", true) ?: true) }
            Fila(stringResource(R.string.act_solas_titulo), stringResource(R.string.act_solas_nota)) {
                Interruptor(solas) { solas = it; Servicio.actualizacionesSolas(it) }
            }
            // Un solo botón, a lo ancho: buscar o, si hay algo, instalarlo.
            val boton = stringResource(if (hayInstalable) R.string.act_instalar else R.string.act_buscar)
            val accion: () -> Unit = {
                if (!trabajando) alcance.launch {
                    trabajando = true
                    if (hayInstalable) Servicio.instalarActualizaciones() else Servicio.buscarActualizaciones()
                    info = Servicio.actualizaciones()
                    trabajando = false
                }
            }
            if (hayInstalable) com.sios.ui.BotonPrimario(boton, Modifier.fillMaxWidth(), accion)
            else com.sios.ui.BotonSecundario(boton, Modifier.fillMaxWidth(), accion)
        }
    }
}

/** Interruptor con los colores de Claude: el mismo en toda la pantalla. */
@Composable
private fun Interruptor(activo: Boolean, onCambio: (Boolean) -> Unit) {
    Switch(
        checked = activo,
        onCheckedChange = onCambio,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Colores.SobreAcento,
            checkedTrackColor = Colores.Acento,
            uncheckedThumbColor = Colores.Texto3,
            uncheckedTrackColor = Colores.Superficie2,
            uncheckedBorderColor = Colores.Borde,
        ),
    )
}

/**
 * Apariencia: claro, oscuro o automático (al anochecer; lo cambia todo el sistema, apps incluidas)
 * y el color de acento de SIOS (la app, el sistema y las apps nativas).
 */
@Composable
private fun SeccionApariencia(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ui = ctx.getSystemService(android.app.UiModeManager::class.java)
    var modo by remember { mutableStateOf(ui.nightMode) }
    val acento = com.sios.ui.Apariencia.acento
    seccion(stringResource(R.string.ap_titulo), stringResource(R.string.ap_nota)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    android.app.UiModeManager.MODE_NIGHT_AUTO to R.string.ap_auto,
                    android.app.UiModeManager.MODE_NIGHT_NO to R.string.ap_claro,
                    android.app.UiModeManager.MODE_NIGHT_YES to R.string.ap_oscuro,
                ).forEach { (m, nombre) ->
                    val elegido = modo == m
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(Formas.Pequena)
                            .background(if (elegido) Colores.Acento else Colores.Superficie)
                            .border(1.dp, if (elegido) Colores.Acento else Colores.Borde, Formas.Pequena)
                            .pulsable {
                                modo = m
                                val orden = when (m) { android.app.UiModeManager.MODE_NIGHT_NO -> "no"; android.app.UiModeManager.MODE_NIGHT_YES -> "yes"; else -> "auto" }
                                Thread { com.sios.sistema.Shell.ejecutar("cmd uimode night $orden") }.start()
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(nombre), style = Texto.Pequeno, color = if (elegido) Colores.SobreAcento else Colores.Texto2) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ap_acento), style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.weight(1f))
                com.sios.ui.Apariencia.ACENTOS.forEach { (nombre, colores) ->
                    val elegido = acento == nombre
                    Box(
                        Modifier.padding(start = 10.dp).size(34.dp).clip(Formas.Pastilla)
                            .border(2.dp, if (elegido) Colores.Texto else androidx.compose.ui.graphics.Color.Transparent, Formas.Pastilla)
                            .padding(4.dp).clip(Formas.Pastilla)
                            .background(androidx.compose.ui.graphics.Color(if (com.sios.ui.Apariencia.claro) colores.second else colores.first))
                            .pulsable {
                                com.sios.ui.Apariencia.ponerAcento(ctx, nombre)
                                Thread { com.sios.sistema.ColoresSistema.aplicar(ctx) }.start()
                            },
                    )
                }
            }
            // Fondos de SIOS: el elegido, con un borde que entra con muelle.
            Text(stringResource(R.string.ap_fondo), style = Texto.Cuerpo, color = Colores.Texto)
            var fondo by remember { mutableStateOf(com.sios.sistema.Fondos.elegido(ctx)) }
            var propio by remember { mutableStateOf(!com.sios.sistema.Fondos.esNuestro(ctx)) }
            val miniaturas = remember { com.sios.sistema.Fondos.TODOS.associateWith { com.sios.sistema.Fondos.miniatura(ctx, it)?.asImageBitmap() } }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                com.sios.sistema.Fondos.TODOS.forEach { nombre ->
                    val elegido = !propio && fondo == nombre
                    val borde by androidx.compose.animation.animateColorAsState(
                        if (elegido) Colores.Texto else androidx.compose.ui.graphics.Color.Transparent, com.sios.ui.Muelle.suave(), label = "borde",
                    )
                    val escala by androidx.compose.animation.core.animateFloatAsState(if (elegido) 1f else 0.94f, com.sios.ui.Muelle.suave(), label = "escala")
                    Box(
                        Modifier.width(72.dp).height(160.dp).graphicsLayer { scaleX = escala; scaleY = escala }
                            .clip(Formas.Pequena).border(2.dp, borde, Formas.Pequena).padding(3.dp).clip(Formas.Pequena)
                            .background(Colores.Superficie)
                            .pulsable { fondo = nombre; propio = false; com.sios.sistema.Fondos.elegir(ctx, nombre) },
                    ) {
                        miniaturas[nombre]?.let {
                            androidx.compose.foundation.Image(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        }
                    }
                }
            }
        }
    }
}


/** Modo coche: cuál es el coche (Bluetooth), a dónde se va casi siempre y si se leen los mensajes. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SeccionCoche(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val emparejados = remember { com.sios.sistema.Coche.emparejados(ctx) }
    var coches by remember { mutableStateOf(com.sios.sistema.Coche.coches(ctx)) }
    var casa by remember { mutableStateOf(com.sios.sistema.Coche.casa(ctx)) }
    var trabajo by remember { mutableStateOf(com.sios.sistema.Coche.trabajo(ctx)) }
    var leer by remember { mutableStateOf(com.sios.sistema.Coche.leer(ctx)) }
    var automatico by remember { mutableStateOf(com.sios.sistema.Coche.automatico(ctx)) }
    var responder by remember { mutableStateOf(com.sios.sistema.Coche.responderConduciendo(ctx)) }
    seccion(stringResource(R.string.coche_titulo), stringResource(R.string.coche_nota)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.coche_tu_coche), style = Texto.Cuerpo, color = Colores.Texto)
            if (emparejados.isEmpty()) {
                Text(stringResource(R.string.coche_sin_bluetooth), style = Texto.Pequeno, color = Colores.Texto3)
            } else {
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    emparejados.forEach { nombre ->
                        val elegido = nombre in coches
                        Box(
                            Modifier.height(40.dp).clip(Formas.Pastilla)
                                .background(if (elegido) Colores.Acento else Colores.Superficie)
                                .border(1.dp, if (elegido) Colores.Acento else Colores.Borde, Formas.Pastilla)
                                .pulsable { com.sios.sistema.Coche.ponerCoche(ctx, nombre, !elegido); coches = com.sios.sistema.Coche.coches(ctx) }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(nombre, style = Texto.Pequeno, color = if (elegido) Colores.SobreAcento else Colores.Texto2, maxLines = 1) }
                    }
                }
            }
            com.sios.inicio.Campo(casa, { casa = it; com.sios.sistema.Coche.ponerDireccion(ctx, "casa", it) }, stringResource(R.string.coche_casa_pista), androidx.compose.ui.text.input.KeyboardType.Text, secreto = false)
            com.sios.inicio.Campo(trabajo, { trabajo = it; com.sios.sistema.Coche.ponerDireccion(ctx, "trabajo", it) }, stringResource(R.string.coche_trabajo_pista), androidx.compose.ui.text.input.KeyboardType.Text, secreto = false)
            Fila(stringResource(R.string.coche_auto_titulo), stringResource(R.string.coche_auto_nota)) {
                Interruptor(automatico) { automatico = it; com.sios.sistema.Coche.ponerAutomatico(ctx, it) }
            }
            Fila(stringResource(R.string.coche_leer), stringResource(R.string.coche_leer_nota)) {
                Interruptor(leer) { leer = it; com.sios.sistema.Coche.ponerLeer(ctx, it) }
            }
            Fila(stringResource(R.string.coche_responder), stringResource(R.string.coche_responder_nota)) {
                Interruptor(responder) { responder = it; com.sios.sistema.Coche.ponerResponder(ctx, it) }
            }
            BotonSecundario(stringResource(R.string.coche_abrir), Modifier.fillMaxWidth()) { com.sios.sistema.Coche.abrir(ctx) }
        }
    }
}


/** Continuidad con el Mac: activarla, vincular con un código y qué se comparte. */
@Composable
private fun SeccionMac(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val alcance = androidx.compose.runtime.rememberCoroutineScope()
    var estado by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var ahora by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            Servicio.continuidad()?.let { e ->
                estado = e
                // La app sigue al servicio (por si se activó al pedir un código o desde otro sitio).
                if (e.optBoolean("activo") != com.sios.sistema.Continuidad.activa(ctx)) com.sios.sistema.Continuidad.ponerActiva(ctx, e.optBoolean("activo"))
            }
            ahora = System.currentTimeMillis()
            kotlinx.coroutines.delay(if (estado?.optJSONObject("codigo") != null) 1_000 else 5_000)
        }
    }
    val e = estado
    val activo = e?.optBoolean("activo") == true
    fun cambiar(clave: String, valor: Any) = alcance.launch {
        Servicio.ponerContinuidad(org.json.JSONObject().put(clave, valor))?.let { estado = it }
        if (clave == "activo") com.sios.sistema.Continuidad.ponerActiva(ctx, valor as Boolean)
    }
    seccion(stringResource(R.string.mac_titulo), stringResource(R.string.mac_nota)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Fila(stringResource(R.string.mac_activar), null) { Interruptor(activo) { cambiar("activo", it) } }
            androidx.compose.animation.AnimatedVisibility(activo) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val macs = e?.optJSONArray("macs")
                    if (macs != null) for (i in 0 until macs.length()) {
                        val m = macs.getJSONObject(i)
                        Row(
                            Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(m.optString("nombre"), style = Texto.Cuerpo, color = Colores.Texto)
                                val visto = m.optLong("visto")
                                Text(
                                    when {
                                        visto <= 0 -> stringResource(R.string.mac_vinculado)
                                        ahora - visto < 120_000 -> stringResource(R.string.mac_ahora)
                                        else -> stringResource(R.string.mac_visto, android.text.format.DateUtils.getRelativeTimeSpanString(visto, ahora, 60_000).toString().replaceFirstChar { it.lowercase() })
                                    },
                                    style = Texto.Pequeno, color = Colores.Texto3,
                                )
                            }
                            Text(
                                stringResource(R.string.mac_quitar), style = Texto.Pequeno.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Colores.Rojo,
                                modifier = Modifier.clip(Formas.Pequena).pulsable { cambiar("quitar", m.optString("id")) }.padding(12.dp),
                            )
                        }
                    }
                    val codigo = e?.optJSONObject("codigo")
                    if (codigo != null) {
                        val quedan = ((codigo.optLong("hasta") - ahora) / 1000).coerceIn(0, 300)
                        Column(
                            Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).border(1.dp, Colores.Acento, Formas.Tarjeta).padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.mac_codigo_titulo), style = Texto.Pequeno, color = Colores.Texto3)
                            val c = codigo.optString("codigo")
                            Text(
                                "${c.take(3)} ${c.drop(3)}",
                                style = Texto.Titulo.copy(fontSize = 40.sp, letterSpacing = 6.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light),
                                color = Colores.Texto,
                            )
                            Text(
                                stringResource(R.string.mac_codigo_nota, e.optString("ip").takeIf { it.isNotBlank() && it != "null" } ?: "—", e.optInt("puerto"), quedan / 60, quedan % 60),
                                style = Texto.Pequeno, color = Colores.Texto3, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    } else {
                        BotonSecundario(stringResource(R.string.mac_vincular), Modifier.fillMaxWidth()) {
                            alcance.launch { Servicio.codigoContinuidad()?.let { estado = it; ahora = System.currentTimeMillis() } }
                        }
                    }
                    Fila(stringResource(R.string.mac_portapapeles), stringResource(R.string.mac_portapapeles_nota)) {
                        Interruptor(e?.optBoolean("portapapeles") != false) { cambiar("portapapeles", it) }
                    }
                    Fila(stringResource(R.string.mac_avisos), stringResource(R.string.mac_avisos_nota)) {
                        Interruptor(e?.optBoolean("avisos") != false) { cambiar("avisos", it) }
                    }
                    Fila(stringResource(R.string.mac_encontrar), stringResource(R.string.mac_encontrar_nota)) {
                        Interruptor(e?.optBoolean("encontrar") != false) { cambiar("encontrar", it) }
                    }
                    // La foto necesita que SIOS sea administrador (para saber de los intentos fallidos): lo confirma el sistema.
                    var intruso by remember { mutableStateOf(com.sios.sistema.Intruso.activo(ctx)) }
                    val pedirAdmin = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    ) { intruso = com.sios.sistema.Intruso.activo(ctx) }
                    LifecycleResumeEffect(Unit) { intruso = com.sios.sistema.Intruso.activo(ctx); onPauseOrDispose { } }
                    Fila(stringResource(R.string.intruso_titulo), stringResource(R.string.intruso_nota)) {
                        Interruptor(intruso) { si ->
                            if (si) {
                                val pedir = com.sios.sistema.Intruso.encender(ctx)
                                if (pedir != null) pedirAdmin.launch(pedir) else intruso = true
                            } else {
                                com.sios.sistema.Intruso.apagar(ctx)
                                intruso = false
                            }
                        }
                    }
                }
            }
        }
    }
}


/** Buenas noches: cada noche solo, de qué hora a qué hora, pantalla en gris, o empezar ya. */
@Composable
private fun SeccionNoche(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val alcance = androidx.compose.runtime.rememberCoroutineScope()
    var a by remember { mutableStateOf<org.json.JSONObject?>(null) }
    LaunchedEffect(Unit) { a = Servicio.buenasNoches() }
    fun cambiar(clave: String, valor: Any) = alcance.launch { Servicio.buenasNoches(org.json.JSONObject().put(clave, valor))?.let { a = it } }
    val e = a ?: return
    var desde by remember(e.optString("desde")) { mutableStateOf(e.optString("desde")) }
    var hasta by remember(e.optString("hasta")) { mutableStateOf(e.optString("hasta")) }
    seccion(stringResource(R.string.noche_titulo), stringResource(R.string.noche_nota)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Fila(stringResource(R.string.noche_cada), null) { Interruptor(e.optBoolean("activo")) { cambiar("activo", it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.noche_de), style = Texto.Cuerpo, color = Colores.Texto2)
                Box(Modifier.weight(1f)) {
                    com.sios.inicio.Campo(desde, { v -> desde = v.filter { it.isDigit() || it == ':' }.take(5); if (Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(desde)) cambiar("desde", desde) }, "23:30", androidx.compose.ui.text.input.KeyboardType.Number, secreto = false)
                }
                Text(stringResource(R.string.noche_a), style = Texto.Cuerpo, color = Colores.Texto2)
                Box(Modifier.weight(1f)) {
                    com.sios.inicio.Campo(hasta, { v -> hasta = v.filter { it.isDigit() || it == ':' }.take(5); if (Regex("^([01]\\d|2[0-3]):[0-5]\\d$").matches(hasta)) cambiar("hasta", hasta) }, "07:30", androidx.compose.ui.text.input.KeyboardType.Number, secreto = false)
                }
            }
            Fila(stringResource(R.string.noche_gris), stringResource(R.string.noche_gris_nota)) { Interruptor(e.optBoolean("gris")) { cambiar("gris", it) } }
            val enCurso = e.optBoolean("en_curso")
            BotonSecundario(stringResource(if (enCurso) R.string.noche_terminar else R.string.noche_ahora), Modifier.fillMaxWidth()) { cambiar("ahora", !enCurso) }
        }
    }
}

/** Accesibilidad: texto más grande, más contraste y «Leer pantalla». */
@Composable
private fun SeccionAccesibilidad(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    var grande by remember { mutableStateOf(android.provider.Settings.System.getFloat(ctx.contentResolver, android.provider.Settings.System.FONT_SCALE, 1f) > 1.05f) }
    var contraste by remember { mutableStateOf(android.provider.Settings.Secure.getInt(ctx.contentResolver, "high_text_contrast_enabled", 0) == 1) }
    seccion(stringResource(R.string.acc_titulo), stringResource(R.string.acc_nota)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Fila(stringResource(R.string.acc_grande), stringResource(R.string.acc_grande_nota)) {
                Interruptor(grande) { si -> grande = si; Thread { com.sios.sistema.Shell.ejecutar("settings put system font_scale ${if (si) "1.3" else "1.0"}") }.start() }
            }
            Fila(stringResource(R.string.acc_contraste), stringResource(R.string.acc_contraste_nota)) {
                Interruptor(contraste) { si -> contraste = si; Thread { com.sios.sistema.Shell.ejecutar("settings put secure high_text_contrast_enabled ${if (si) 1 else 0}") }.start() }
            }
            Text(stringResource(R.string.acc_leer_nota), style = Texto.Pequeno, color = Colores.Texto3)
        }
    }
}


/** Familia: crear el grupo, invitar (QR o enlace), compartir dónde estás y salir. */
@Composable
private fun SeccionFamilia(seccion: @Composable (String, String?, @Composable () -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val alcance = androidx.compose.runtime.rememberCoroutineScope()
    var f by remember { mutableStateOf<org.json.JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { f = Servicio.familia() }
    fun hacer(cambios: org.json.JSONObject) = alcance.launch {
        val r = Servicio.familia(cambios)
        error = r?.optString("error")?.takeIf { it.isNotBlank() }
        if (error == null && r != null) f = r
    }
    seccion(stringResource(R.string.fam_titulo), stringResource(R.string.fam_nota)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val e = f
            if (e == null || e.isNull("grupo")) {
                var nombre by remember { mutableStateOf("") }
                var yo by remember { mutableStateOf("") }
                com.sios.inicio.Campo(nombre, { nombre = it.take(30) }, stringResource(R.string.fam_nombre_grupo), androidx.compose.ui.text.input.KeyboardType.Text, secreto = false)
                com.sios.inicio.Campo(yo, { yo = it.take(30) }, stringResource(R.string.fam_tu_nombre), androidx.compose.ui.text.input.KeyboardType.Text, secreto = false)
                BotonSecundario(stringResource(R.string.fam_crear), Modifier.fillMaxWidth()) {
                    if (yo.isNotBlank()) hacer(org.json.JSONObject().put("accion", "crear").put("nombre", nombre.ifBlank { ctx.getString(R.string.fam_titulo) }).put("yo", yo))
                }
            } else {
                val miembros = e.optJSONArray("miembros")?.length() ?: 0
                Text(stringResource(R.string.fam_grupo, e.optString("grupo"), e.optString("yo")) + " · " + pluralStringResource(R.plurals.fam_miembros, miembros, miembros), style = Texto.Cuerpo, color = Colores.Texto)
                var invitar by remember { mutableStateOf(false) }
                androidx.compose.animation.AnimatedVisibility(invitar) {
                    val enlace = e.optString("enlace")
                    val imagen = remember(enlace) { com.sios.ui.qr(enlace) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        if (imagen != null) Box(Modifier.size(220.dp).clip(Formas.Tarjeta).background(androidx.compose.ui.graphics.Color.White).padding(14.dp)) {
                            androidx.compose.foundation.Image(imagen.asImageBitmap(), null, filterQuality = androidx.compose.ui.graphics.FilterQuality.None, modifier = Modifier.fillMaxSize())
                        }
                        Text(stringResource(R.string.fam_invitar_nota), style = Texto.Pequeno, color = Colores.Texto3, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        BotonSecundario(stringResource(R.string.compartir_enviar), Modifier.fillMaxWidth()) {
                            ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, ctx.getString(R.string.fam_invitacion, e.optString("grupo"), enlace)), null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                if (!invitar) BotonSecundario(stringResource(R.string.fam_invitar), Modifier.fillMaxWidth()) { invitar = true }
                Fila(stringResource(R.string.fam_ubicacion), stringResource(R.string.fam_ubicacion_nota)) {
                    Interruptor(e.optBoolean("compartir_ubicacion")) { hacer(org.json.JSONObject().put("accion", "ubicacion").put("si", it)) }
                }
                Text(
                    stringResource(R.string.fam_salir), style = Texto.Pequeno.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Colores.Rojo,
                    modifier = Modifier.clip(Formas.Pequena).pulsable { hacer(org.json.JSONObject().put("accion", "salir")) }.padding(vertical = 10.dp),
                )
            }
            error?.let { Text(it, style = Texto.Pequeno, color = Colores.Rojo) }
        }
    }
}
