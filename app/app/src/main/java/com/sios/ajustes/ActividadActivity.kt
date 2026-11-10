package com.sios.ajustes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.datos.Servicio
import com.sios.ui.BotonIcono
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * Actividad de Claude: todo lo que ha hecho en el móvil (desde el chat, las rutinas, los agentes y
 * la propia app), lo más reciente arriba, con «Deshacer» cuando se puede.
 */
class ActividadActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Servicio.iniciar()
        setContent { TemaSios { PantallaActividad(onAtras = ::finish) } }
    }
}

@Composable
private fun PantallaActividad(onAtras: () -> Unit) {
    var todo by remember { mutableStateOf(false) }
    var filas by remember { mutableStateOf<List<JSONObject>?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val cambios by Servicio.cambiosActividad.collectAsStateWithLifecycle()
    val alcance = rememberCoroutineScope()
    val hoy = stringResource(R.string.actividad_hoy)
    val ayer = stringResource(R.string.actividad_ayer)

    LaunchedEffect(todo, version, cambios) { filas = Servicio.actividad(todo) }

    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            BotonIcono(Iconos.Atras, stringResource(R.string.comun_atras), onClick = onAtras)
            Spacer(Modifier.width(8.dp))
            Icon(Iconos.Asistente, null, tint = Colores.Acento, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.actividad_titulo), style = Texto.Titulo, color = Colores.Texto)
        }
        // Dos pestañas del mismo ancho: solo lo que ha hecho, o también lo que ha consultado.
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().clip(Formas.Pastilla).background(Colores.Superficie).padding(4.dp),
        ) {
            for ((valor, texto) in listOf(false to R.string.actividad_acciones, true to R.string.actividad_todo)) {
                val activa = todo == valor
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(Formas.Pastilla)
                        .background(if (activa) Colores.Superficie2 else Colores.Superficie)
                        .pulsable { todo = valor },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(texto), style = Texto.Pequeno, color = if (activa) Colores.Texto else Colores.Texto3) }
            }
        }

        AnimatedContent(filas, transitionSpec = { fadeIn(Muelle.suave()) togetherWith fadeOut(Muelle.suave()) }, label = "lista") { lista ->
            when {
                lista == null -> Box(Modifier.fillMaxSize())
                lista.isEmpty() -> Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.actividad_vacia), style = Texto.Cuerpo, color = Colores.Texto3)
                }
                else -> LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    var dia = ""
                    for (f in lista) {
                        val d = diaDe(f.optLong("hora"), hoy, ayer)
                        if (d != dia) {
                            dia = d
                            item(key = "dia-$d") {
                                Text(d, style = Texto.Etiqueta, color = Colores.Texto3, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                            }
                        }
                        item(key = f.optString("id")) {
                            Fila(f) {
                                alcance.launch {
                                    Servicio.deshacer(f.optString("id"))
                                    version++
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fila(f: JSONObject, onDeshacer: () -> Unit) {
    val origen = f.optString("origen")
    val (icono, nombreOrigen) = when (origen) {
        "rutina" -> Iconos.Ajustes to R.string.actividad_origen_rutina
        "agente" -> Iconos.Widget to R.string.actividad_origen_agente
        "app" -> Iconos.Telefono to R.string.actividad_origen_app
        "usuario" -> Iconos.Atras to R.string.actividad_origen_usuario
        else -> Iconos.Asistente to R.string.actividad_origen_chat
    }
    val error = f.optString("error").takeIf { it.isNotBlank() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Formas.Tarjeta)
            .background(Colores.Superficie)
            .animateContentSize(Muelle.suave())
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(Formas.Pastilla).background(Colores.Superficie2), contentAlignment = Alignment.Center) {
            Icon(icono, null, tint = if (origen == "chat") Colores.Acento else Colores.Texto2, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.optString("frase"), style = Texto.Cuerpo, color = Colores.Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val hora = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(f.optLong("hora")))
            Text("$hora · ${stringResource(nombreOrigen)}", style = Texto.Pequeno, color = Colores.Texto3)
            val detalle = error ?: f.optString("detalle").takeIf { it.isNotBlank() && it != "null" }
            if (detalle != null) Text(detalle, style = Texto.Pequeno, color = if (error != null) Colores.Rojo else Colores.Texto2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        when {
            f.optBoolean("deshecha") -> {
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.actividad_deshecho), style = Texto.Pequeno, color = Colores.Texto3)
            }
            f.optBoolean("se_puede_deshacer") -> {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier.height(34.dp).clip(Formas.Pastilla).background(Colores.Superficie2).border(1.dp, Colores.Borde, Formas.Pastilla)
                        .pulsable(onDeshacer).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(R.string.actividad_deshacer), style = Texto.Pequeno, color = Colores.Texto) }
            }
        }
    }
}

private fun diaDe(ms: Long, hoy: String, ayer: String): String {
    val ahora = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val mismoAno = c.get(Calendar.YEAR) == ahora.get(Calendar.YEAR)
    return when {
        mismoAno && c.get(Calendar.DAY_OF_YEAR) == ahora.get(Calendar.DAY_OF_YEAR) -> hoy
        mismoAno && c.get(Calendar.DAY_OF_YEAR) == ahora.get(Calendar.DAY_OF_YEAR) - 1 -> ayer
        else -> DateFormat.getDateInstance(DateFormat.LONG).format(Date(ms)).uppercase()
    }
}
