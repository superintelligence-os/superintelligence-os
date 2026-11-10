package com.sios.creaciones

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.datos.Servicio
import com.sios.inicio.InicioActivity
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Abrir un enlace sios://importar?d=… (o su QR): qué es, qué hace y lo que conviene saber
 * (comandos, gasto de Claude, ubicación). Solo se añade si el usuario lo confirma.
 */
class ImportarActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Servicio.iniciar()
        val codigo = intent?.dataString.orEmpty()
        val comunidad = intent?.getStringExtra(EXTRA_COMUNIDAD)
        // Invitación a un grupo familiar: otra pantalla, mismo sitio.
        if (intent?.data?.host == "familia") {
            setContent { TemaSios { UnirseFamilia(codigo, onCerrar = ::finish) } }
            return
        }
        setContent { TemaSios { PantallaImportar(codigo, comunidad, onCerrar = ::finish) } }
    }

    companion object {
        /** El widget viene de la galería comunitaria (su id allí): se cuenta el uso y se puede denunciar. */
        const val EXTRA_COMUNIDAD = "com.sios.importar.COMUNIDAD"
    }

    // Otro enlace con esta pantalla ya abierta: se lee el nuevo.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

private sealed interface Paso {
    data object Cargando : Paso
    data class Vista(val v: JSONObject) : Paso
    data class Hecho(val r: JSONObject) : Paso
    data class Error(val texto: String) : Paso
}

@Composable
private fun PantallaImportar(codigo: String, comunidad: String?, onCerrar: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val alcance = rememberCoroutineScope()
    var paso by remember { mutableStateOf<Paso>(Paso.Cargando) }
    var anadiendo by remember { mutableStateOf(false) }
    LaunchedEffect(codigo) {
        val v = Servicio.verCompartido(codigo)
        paso = when {
            v == null -> Paso.Error(ctx.getString(R.string.importar_sin_servicio))
            v.has("error") -> Paso.Error(v.optString("error"))
            else -> Paso.Vista(v)
        }
    }
    Box(
        Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(paso, transitionSpec = { (fadeIn(Muelle.suave()) + scaleIn(Muelle.suave(), initialScale = 0.96f)) togetherWith fadeOut(Muelle.suave()) }, label = "importar") { p ->
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(64.dp).clip(Formas.Pastilla).background(Colores.Superficie), contentAlignment = Alignment.Center) {
                    Icon(if (p is Paso.Hecho) Iconos.Hecho else Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(28.dp))
                }
                when (p) {
                    Paso.Cargando -> Text(stringResource(R.string.importar_cargando), style = Texto.Cuerpo, color = Colores.Texto3)
                    is Paso.Error -> {
                        Text(stringResource(R.string.importar_error_titulo), style = Texto.Titulo, color = Colores.Texto, textAlign = TextAlign.Center)
                        Text(p.texto, style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        BotonSecundario(stringResource(R.string.comun_cerrar), Modifier.fillMaxWidth(), onCerrar)
                    }
                    is Paso.Vista -> {
                        val v = p.v
                        Text(stringResource(if (comunidad != null) R.string.comunidad_de else R.string.importar_titulo), style = Texto.Pequeno, color = Colores.Texto3)
                        Text(v.optString("nombre"), style = Texto.Titulo.copy(fontSize = 24.sp), color = Colores.Texto, textAlign = TextAlign.Center)
                        Text(v.optString("descripcion"), style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center)
                        val avisos = v.optJSONArray("avisos")
                        if (avisos != null && avisos.length() > 0) {
                            Column(
                                Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Tarjeta).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(stringResource(R.string.importar_antes), style = Texto.Etiqueta, color = Colores.Texto3)
                                for (i in 0 until avisos.length()) {
                                    val a = avisos.getJSONObject(i)
                                    val fuerte = a.optString("tipo") == "comando"
                                    Row(verticalAlignment = Alignment.Top) {
                                        Box(Modifier.padding(top = 7.dp).size(6.dp).clip(Formas.Pastilla).background(if (fuerte) Colores.Rojo else Colores.Acento))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(a.optString("texto"), style = Texto.Cuerpo, color = Colores.Texto)
                                            // Los comandos, enteros y en letra de código: que se vea qué se va a ejecutar.
                                            val detalle = a.optJSONArray("detalle")
                                            if (detalle != null) for (j in 0 until detalle.length()) {
                                                Text(
                                                    detalle.getString(j), style = Texto.Pequeno.copy(fontFamily = FontFamily.Monospace), color = Colores.Texto2,
                                                    modifier = Modifier.fillMaxWidth().clip(Formas.Pequena).background(Colores.Superficie2).padding(10.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        BotonPrimario(stringResource(if (anadiendo) R.string.importar_anadiendo else R.string.importar_anadir), Modifier.fillMaxWidth()) {
                            if (anadiendo) return@BotonPrimario
                            anadiendo = true
                            alcance.launch {
                                val r = Servicio.importarCompartido(codigo)
                                anadiendo = false
                                paso = when {
                                    r == null -> Paso.Error(ctx.getString(R.string.importar_sin_servicio))
                                    r.has("error") -> Paso.Error(r.optString("error"))
                                    else -> Paso.Hecho(r).also { if (comunidad != null) Servicio.comunidadUsado(comunidad) }
                                }
                            }
                        }
                        BotonSecundario(stringResource(R.string.importar_no), Modifier.fillMaxWidth(), onCerrar)
                        if (comunidad != null) {
                            var denunciado by remember { mutableStateOf(false) }
                            Text(
                                stringResource(if (denunciado) R.string.comunidad_denunciado else R.string.comunidad_denunciar),
                                style = Texto.Pequeno, color = Colores.Texto3,
                                modifier = Modifier.clip(Formas.Pequena).pulsable {
                                    if (!denunciado) { denunciado = true; alcance.launch { Servicio.comunidadDenunciar(comunidad) } }
                                }.padding(10.dp),
                            )
                        }
                    }
                    is Paso.Hecho -> {
                        Text(stringResource(R.string.importar_hecho, p.r.optString("nombre")), style = Texto.Titulo, color = Colores.Texto, textAlign = TextAlign.Center)
                        val enPanel = p.r.optString("lugar") == "panel"
                        val enInicio = p.r.optString("tipo") == "widget" && !enPanel
                        Text(
                            stringResource(if (enPanel) R.string.importar_hecho_panel else if (enInicio) R.string.importar_hecho_inicio else R.string.importar_hecho_otro),
                            style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        BotonPrimario(stringResource(R.string.importar_ver), Modifier.fillMaxWidth()) {
                            ctx.startActivity(
                                Intent(ctx, InicioActivity::class.java).putExtra(InicioActivity.EXTRA_PANEL, enPanel)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            )
                            onCerrar()
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun UnirseFamilia(enlace: String, onCerrar: () -> Unit) {
    val alcance = rememberCoroutineScope()
    val grupo = remember(enlace) { android.net.Uri.parse(enlace).getQueryParameter("n").orEmpty().ifBlank { "Familia" } }
    var yo by remember { mutableStateOf("") }
    var estado by remember { mutableStateOf<String?>(null) } // null · "uniendo" · "hecho" · error
    Box(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(64.dp).clip(Formas.Pastilla).background(Colores.Superficie), contentAlignment = Alignment.Center) {
                Icon(if (estado == "hecho") Iconos.Hecho else Iconos.Casa, null, tint = Colores.Acento, modifier = Modifier.size(28.dp))
            }
            Text(stringResource(if (estado == "hecho") R.string.fam_unido else R.string.fam_unirse_titulo, grupo), style = Texto.Titulo.copy(fontSize = 24.sp), color = Colores.Texto, textAlign = TextAlign.Center)
            if (estado == "hecho") {
                Text(stringResource(R.string.fam_unido_nota), style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center)
                BotonPrimario(stringResource(R.string.comun_cerrar), Modifier.fillMaxWidth(), onCerrar)
            } else {
                Text(stringResource(R.string.fam_unirse_nota), style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center)
                com.sios.inicio.Campo(yo, { yo = it.take(30) }, stringResource(R.string.fam_tu_nombre), androidx.compose.ui.text.input.KeyboardType.Text, secreto = false)
                estado?.takeIf { it != "uniendo" }?.let { Text(it, style = Texto.Pequeno, color = Colores.Rojo, textAlign = TextAlign.Center) }
                BotonPrimario(stringResource(if (estado == "uniendo") R.string.importar_anadiendo else R.string.fam_unirme), Modifier.fillMaxWidth()) {
                    if (yo.isBlank() || estado == "uniendo") return@BotonPrimario
                    estado = "uniendo"
                    alcance.launch {
                        val r = Servicio.familia(JSONObject().put("accion", "unirse").put("enlace", enlace).put("yo", yo.trim()))
                        estado = r?.optString("error")?.takeIf { it.isNotBlank() } ?: if (r == null) "Claude se está poniendo en marcha. Inténtalo en un momento." else "hecho"
                    }
                }
                BotonSecundario(stringResource(R.string.importar_no), Modifier.fillMaxWidth(), onCerrar)
            }
        }
    }
}
