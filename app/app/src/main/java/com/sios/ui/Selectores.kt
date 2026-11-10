package com.sios.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.datos.Ajustes
import com.sios.datos.Servicio

// Opciones que el usuario puede elegir; las mismas en el chat y en Ajustes de SIOS.
data class Eleccion(val id: String, @StringRes val nombre: Int, @StringRes val descripcion: Int)

/** Familias de modelos y sus versiones (las que acepta la suscripción). */
data class Familia(val id: String, @StringRes val nombre: Int, @StringRes val descripcion: Int, val versiones: List<String>)

val FAMILIAS = listOf(
    Familia("haiku", R.string.modelo_haiku, R.string.selector_haiku_desc, listOf("claude-haiku-5-5", "claude-haiku-4-5")),
    Familia("sonnet", R.string.modelo_sonnet, R.string.selector_sonnet_desc, listOf("claude-sonnet-5-5", "claude-sonnet-5", "claude-sonnet-4-5")),
    Familia("opus", R.string.modelo_opus, R.string.selector_opus_desc, listOf("claude-opus-5-5", "claude-opus-5", "claude-opus-4-8", "claude-opus-4-5")),
    Familia("fable", R.string.modelo_fable, R.string.selector_fable_desc, listOf("claude-fable-5-1")),
)

/** La familia de un alias ("sonnet") o de un identificador ("claude-sonnet-5"). */
fun familiaDe(modelo: String) = FAMILIAS.firstOrNull { modelo == it.id || modelo.startsWith("claude-${it.id}-") } ?: FAMILIAS[1]

/** "claude-opus-4-8" → "4.8" */
fun versionDe(id: String) = com.sios.datos.nombreModelo(id).substringAfter(' ')

val ESFUERZOS = listOf(
    Eleccion("low", R.string.selector_esfuerzo_bajo, R.string.selector_esfuerzo_bajo_desc),
    Eleccion("medium", R.string.selector_esfuerzo_medio, R.string.selector_esfuerzo_medio_desc),
    Eleccion("high", R.string.selector_esfuerzo_alto, R.string.selector_esfuerzo_alto_desc),
)

val PERMISOS = listOf(
    Eleccion("preguntar", R.string.selector_permisos_preguntar, R.string.selector_permisos_preguntar_desc),
    Eleccion("equilibrado", R.string.selector_permisos_equilibrado, R.string.selector_permisos_equilibrado_desc),
    Eleccion("libre", R.string.selector_permisos_libre, R.string.selector_permisos_libre_desc),
)

/** Nombre de la opción en el idioma del móvil; si no se conoce, el identificador tal cual. */
@Composable
fun nombreDe(lista: List<Eleccion>, id: String) = lista.firstOrNull { it.id == id }?.let { stringResource(it.nombre) } ?: id

/**
 * Resumen corto para la cabecera del chat. Si ya se sabe qué modelo responde de verdad
 * (`real`, del arranque de Claude Code), se muestra ese, con su versión.
 */
@Composable
fun resumenAjustes(a: Ajustes, real: String?) =
    if (Marca.id != "claude") "${real?.let { com.sios.datos.nombreModelo(it) } ?: Marca.nombre} · ${nombreDe(PERMISOS, a.permisos).lowercase()}"
    else "${com.sios.datos.nombreModelo(real ?: a.modelo)} · ${nombreDe(ESFUERZOS, a.esfuerzo).lowercase()} · ${nombreDe(PERMISOS, a.permisos).lowercase()}"

/** Versiones de la familia: «Última» (el alias, siempre la más reciente) y cada versión concreta. */
@Composable
private fun Versiones(familia: Familia, elegido: String, real: String?, onElegir: (String) -> Unit) {
    val opciones = listOf(familia.id to stringResource(R.string.selector_ultima)) + familia.versiones.map { it to versionDe(it) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((id, nombre) in opciones) {
            val esta = id == elegido
            val borde by animateColorAsState(if (esta) Colores.Acento else Colores.Borde, Muelle.suave(), label = "version")
            Text(
                nombre,
                style = Texto.Pequeno,
                color = if (esta) Colores.Texto else Colores.Texto2,
                modifier = Modifier
                    .clip(Formas.Pastilla)
                    .border(1.dp, borde, Formas.Pastilla)
                    .pulsable { onElegir(id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    val explicacion = when {
        elegido == familia.id && real != null && familiaDe(real).id == familia.id ->
            stringResource(R.string.selector_ultima_responde, com.sios.datos.nombreModelo(real))
        elegido == familia.id -> stringResource(R.string.selector_ultima_usa, stringResource(familia.nombre))
        else -> null
    }
    if (explicacion != null) {
        Spacer(Modifier.height(8.dp))
        Text(explicacion, style = Texto.Pequeno, color = Colores.Texto3)
    }
}

/** Pastillas segmentadas (una sola elección), con la elegida en naranja. */
@Composable
fun Segmentado(opciones: List<Eleccion>, elegida: String, onElegir: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Formas.Pastilla)
            .background(Colores.Superficie)
            .border(1.dp, Colores.Borde, Formas.Pastilla)
            .padding(4.dp)
    ) {
        for (o in opciones) {
            val esta = o.id == elegida
            val fondo by animateColorAsState(if (esta) Colores.Acento else Colores.Superficie, Muelle.suave(), label = "fondo")
            val tinta by animateColorAsState(if (esta) Colores.SobreAcento else Colores.Texto2, Muelle.suave(), label = "tinta")
            Box(
                Modifier
                    .weight(1f)
                    .clip(Formas.Pastilla)
                    .background(fondo)
                    .pulsable { onElegir(o.id) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(o.nombre), style = Texto.Subtitulo, color = tinta) }
        }
    }
}

/** Lista de opciones con descripción (una sola elección). `aviso` marca en rojo la opción arriesgada. */
@Composable
fun ListaOpciones(opciones: List<Eleccion>, elegida: String, onElegir: (String) -> Unit, aviso: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in opciones) {
            val esta = o.id == elegida
            val acento = if (o.id == aviso) Colores.Rojo else Colores.Acento
            val borde by animateColorAsState(if (esta) acento else Colores.Borde, Muelle.suave(), label = "borde")
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Formas.Tarjeta)
                    .border(1.dp, borde, Formas.Tarjeta)
                    .pulsable { onElegir(o.id) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(o.nombre), style = Texto.Subtitulo, color = Colores.Texto)
                    Text(stringResource(o.descripcion), style = Texto.Pequeno, color = Colores.Texto2)
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(Formas.Pastilla)
                        .border(1.5.dp, borde, Formas.Pastilla),
                    contentAlignment = Alignment.Center,
                ) {
                    if (esta) Box(Modifier.size(10.dp).clip(Formas.Pastilla).background(acento))
                }
            }
        }
    }
}

@Composable
fun TituloSeccion(titulo: String, nota: String? = null) {
    Column(Modifier.padding(bottom = 12.dp)) {
        Text(titulo, style = Texto.Subtitulo, color = Colores.Texto)
        if (nota != null) {
            Spacer(Modifier.height(2.dp))
            Text(nota, style = Texto.Pequeno, color = Colores.Texto3)
        }
    }
}

/**
 * Modelo, esfuerzo y permisos: lo que se cambia a menudo. Se usa en la hoja del chat y en
 * Ajustes de SIOS. Se aplica desde el siguiente mensaje, en la misma conversación.
 */
@Composable
fun AjustesConversacion(ajustes: Ajustes, ocupado: Boolean, real: String? = null, modifier: Modifier = Modifier) {
    val familia = familiaDe(ajustes.modelo)
    Column(modifier.animateContentSize(Muelle.suave()), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        // Modelos y esfuerzo de Claude solo con Claude; con otra IA, los modelos de esa IA.
        if (Marca.id != "claude") {
            val ia by Servicio.ia.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(Unit) { if (Servicio.ia.value == null) Servicio.cargarIa() }
            val activo = ia?.optString("activo")
            val lista = ia?.optJSONArray("proveedores")
            val p = (0 until (lista?.length() ?: 0)).map { lista!!.getJSONObject(it) }.firstOrNull { it.optString("id") == activo }
            if (p != null) com.sios.ajustes.Modelos(p, Colores.Acento)
        } else Column {
            TituloSeccion(stringResource(R.string.selector_modelo), stringResource(familia.descripcion))
            Segmentado(
                FAMILIAS.map { Eleccion(it.id, it.nombre, it.descripcion) },
                familia.id,
                // Al cambiar de familia se elige «Última»; la versión concreta se fija debajo.
                { if (it != familia.id) Servicio.cambiarAjustes(modelo = it) },
            )
            Spacer(Modifier.height(12.dp))
            Versiones(familia, ajustes.modelo, real) { Servicio.cambiarAjustes(modelo = it) }
        }
        if (Marca.id == "claude") Column {
            TituloSeccion(stringResource(R.string.selector_esfuerzo), stringResource(ESFUERZOS.first { it.id == ajustes.esfuerzo }.descripcion))
            Segmentado(ESFUERZOS, ajustes.esfuerzo, { Servicio.cambiarAjustes(esfuerzo = it) })
        }
        Column {
            TituloSeccion(stringResource(R.string.selector_permisos))
            ListaOpciones(PERMISOS, ajustes.permisos, { Servicio.cambiarAjustes(permisos = it) }, aviso = "libre")
        }
        Text(
            stringResource(if (ocupado) R.string.selector_aplica_al_terminar else R.string.selector_aplica_siguiente),
            style = Texto.Pequeno,
            color = Colores.Texto3,
        )
    }
}
