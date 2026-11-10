package com.sios.fotos

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Iconos del editor de fotos: el mismo trazo fino (24×24) que los del resto del sistema. */
internal object IconosFotos {
    private fun trazo(nombre: String, vararg caminos: String) =
        ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f).apply {
            for (c in caminos) {
                addPath(
                    pathData = PathParser().parsePathString(c).toNodes(),
                    stroke = SolidColor(Color.White),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    // Herramientas.
    val Recortar = trazo("recortar", "M6 2.5v13.5a2 2 0 0 0 2 2h13.5", "M2.5 6h13.5a2 2 0 0 1 2 2v13.5")
    val Ajustar = trazo("ajustar", "M4 7h9", "M17 7h3", "M4 17h3", "M11 17h9", "M15 5v4", "M9 15v4")
    val Filtros = trazo(
        "filtros",
        "M12 3.5a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9z",
        "M8.5 10a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9z",
        "M15.5 10a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9z",
    )
    val Borrador = trazo("borrador", "M7 20l-4.3-4.3a1 1 0 0 1 0-1.4l10-10a1 1 0 0 1 1.4 0l5.6 5.6a1 1 0 0 1 0 1.4L11 20", "M21 20H7", "M5.5 10.5l8 8")
    val Sujeto = trazo(
        "sujeto",
        "M12 7a3 3 0 1 1 0 6 3 3 0 0 1 0-6z", "M7 18.5a5 5 0 0 1 10 0",
        "M3 8V5a2 2 0 0 1 2-2h3", "M16 3h3a2 2 0 0 1 2 2v3", "M21 16v3a2 2 0 0 1-2 2h-3", "M8 21H5a2 2 0 0 1-2-2v-3",
    )
    val Palabras = trazo("palabras", "M20.5 12a8.5 8.5 0 0 1-12.3 7.6L3.5 20.5l1-4.4A8.5 8.5 0 1 1 20.5 12z", "M8.5 10.5h7", "M8.5 14h4.5")
    val Varita = trazo("varita", "M14.5 4V2", "M14.5 14v-2", "M8.5 8h2", "M18.5 8h2", "M17.3 10.8l1.2 1.2", "M17.3 5.2l1.2-1.2", "M11.7 5.2L10.5 4", "M3 21l9.5-9.5")
    val Deshacer = trazo("deshacer", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val Rehacer = trazo("rehacer", "M15 14l5-5-5-5", "M20 9H9.5a5.5 5.5 0 0 0 0 11H13")
    val Girar = trazo("girar", "M4 13V9a2 2 0 0 1 2-2h7", "M10 4l3 3-3 3", "M8 13h10a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z")
    val Espejo = trazo("espejo", "M12 3v18", "M8.5 7L3.5 17h5z", "M15.5 7l5 10h-5z")

    // Ajustes.
    private val Exposicion = trazo("exposicion", "M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z", "M7.5 9.5h4", "M9.5 7.5v4", "M12.5 14.5h4")
    private val Brillo = trazo(
        "brillo", "M12 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8z", "M12 2.5v2", "M12 19.5v2", "M4.9 4.9l1.4 1.4", "M17.7 17.7l1.4 1.4",
        "M2.5 12h2", "M19.5 12h2", "M6.3 17.7l-1.4 1.4", "M19.1 4.9l-1.4 1.4",
    )
    private val Contraste = trazo("contraste", "M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z", "M12 3v18", "M12 7.5h5.8", "M12 12h9", "M12 16.5h5.8")
    private val Luces = trazo("luces", "M3.5 18.5h17", "M7 18.5a5 5 0 0 1 10 0", "M12 6v3", "M5.6 10.6L7 12", "M18.4 10.6L17 12")
    private val Sombras = trazo("sombras", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
    private val Saturacion = trazo("saturacion", "M12 3l5.4 7.2a6.5 6.5 0 1 1-10.8 0z")
    private val Intensidad = trazo("intensidad", "M12 3l5.4 7.2a6.5 6.5 0 1 1-10.8 0z", "M9 15.5a3 3 0 0 0 3 3")
    private val Calidez = trazo("calidez", "M14 14.8V5a2 2 0 0 0-4 0v9.8a4 4 0 1 0 4 0z", "M12 15v-4")
    private val Tinte = trazo(
        "tinte",
        "M12 3a9 9 0 1 0 0 18c1 0 1.5-.7 1.5-1.5 0-.4-.2-.8-.4-1.1-.3-.3-.4-.7-.4-1.1 0-.8.7-1.5 1.5-1.5H16a5 5 0 0 0 5-5c0-4.4-4-7.8-9-7.8z",
        "M7.5 11.5h.01", "M10 7.5h.01", "M15 8h.01",
    )
    private val Nitidez = trazo("nitidez", "M12 4l8.5 15.5h-17z")
    private val Vineta = trazo("vineta", "M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z", "M12 7.5a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9z")

    fun de(a: Ajuste): ImageVector = when (a) {
        Ajuste.EXPOSICION -> Exposicion
        Ajuste.BRILLO -> Brillo
        Ajuste.CONTRASTE -> Contraste
        Ajuste.LUCES -> Luces
        Ajuste.SOMBRAS -> Sombras
        Ajuste.SATURACION -> Saturacion
        Ajuste.INTENSIDAD -> Intensidad
        Ajuste.CALIDEZ -> Calidez
        Ajuste.TINTE -> Tinte
        Ajuste.NITIDEZ -> Nitidez
        Ajuste.VINETA -> Vineta
    }
}
