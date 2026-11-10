package com.sios.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/** Markdown mínimo de las respuestas: párrafos, listas, negrita, cursiva, código y enlaces. */
private sealed interface Bloque {
    data class Parrafo(val texto: String) : Bloque
    data class Lista(val items: List<String>, val numerada: Boolean) : Bloque
    data class Codigo(val texto: String) : Bloque
}

private fun bloques(src: String): List<Bloque> {
    val res = mutableListOf<Bloque>()
    src.split("```").forEachIndexed { i, parte ->
        if (i % 2 == 1) {
            res += Bloque.Codigo(parte.substringAfter('\n', parte).trimEnd())
            return@forEachIndexed
        }
        val vineta = Regex("^\\s*[-*•] ")
        val numero = Regex("^\\s*\\d+[.)] ")
        // Línea a línea: las listas pueden ir pegadas a un párrafo sin línea en blanco.
        val parrafo = mutableListOf<String>()
        var lista = mutableListOf<String>()
        var numerada = false
        fun cerrarParrafo() {
            if (parrafo.isNotEmpty()) res += Bloque.Parrafo(parrafo.joinToString("\n") { it.replace(Regex("^#{1,6} "), "") })
            parrafo.clear()
        }
        fun cerrarLista() {
            if (lista.isNotEmpty()) res += Bloque.Lista(lista, numerada)
            lista = mutableListOf()
        }
        for (linea in parte.lines()) {
            when {
                linea.isBlank() -> { cerrarParrafo(); cerrarLista() }
                vineta.containsMatchIn(linea) || numero.containsMatchIn(linea) -> {
                    val esNumero = numero.containsMatchIn(linea)
                    if (lista.isNotEmpty() && esNumero != numerada) cerrarLista()
                    cerrarParrafo()
                    numerada = esNumero
                    lista += linea.replace(if (esNumero) numero else vineta, "")
                }
                else -> { cerrarLista(); parrafo += linea }
            }
        }
        cerrarParrafo()
        cerrarLista()
    }
    return res
}

private val enLinea = Regex("""`([^`]+)`|\*\*([^*]+)\*\*|\*([^*\n]+)\*|\[([^\]]+)]\((https?://[^)\s]+)\)""")

fun textoConFormato(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in enLinea.findAll(s)) {
        append(s.substring(i, m.range.first))
        val (codigo, negrita, cursiva, enlace, url) = m.destructured
        when {
            codigo.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Colores.Superficie2)) { append(" $codigo ") }
            negrita.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Colores.Texto)) { append(negrita) }
            cursiva.isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(cursiva) }
            enlace.isNotEmpty() -> withLink(
                LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = Colores.Acento, textDecoration = TextDecoration.Underline)))
            ) { append(enlace) }
        }
        i = m.range.last + 1
    }
    append(s.substring(i))
}

@Composable
fun Markdown(texto: String, modifier: Modifier = Modifier) {
    val partes = remember(texto) { bloques(texto) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in partes) when (b) {
            is Bloque.Parrafo -> Text(textoConFormato(b.texto), style = Texto.Cuerpo, color = Colores.Texto)
            is Bloque.Lista -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                b.items.forEachIndexed { n, item ->
                    Row {
                        Text(
                            if (b.numerada) "${n + 1}." else "•",
                            style = Texto.Cuerpo,
                            color = if (b.numerada) Colores.Texto2 else Colores.Acento,
                            modifier = Modifier.width(22.dp),
                        )
                        Text(textoConFormato(item), style = Texto.Cuerpo, color = Colores.Texto)
                    }
                }
            }
            is Bloque.Codigo -> Text(
                b.texto,
                style = Texto.Codigo,
                color = Colores.Texto,
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Colores.Superficie, Formas.Pequena)
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
            )
        }
    }
}
