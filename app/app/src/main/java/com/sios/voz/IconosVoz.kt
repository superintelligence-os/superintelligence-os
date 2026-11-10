package com.sios.voz

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Iconos de «Sin conexión» que no hay en [com.sios.ui.Iconos] (mismo trazo fino de 24×24). */
internal object IconosVoz {
    private fun trazo(nombre: String, vararg caminos: String) =
        ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f).apply {
            for (c in caminos) addPath(
                pathData = PathParser().parsePathString(c).toNodes(),
                stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    /** Candado: lo que pasa en el móvil y no sale de él. */
    val Candado = trazo("candado", "M7.5 10.5V8a4.5 4.5 0 0 1 9 0v2.5", "M6.5 10.5h11a1.5 1.5 0 0 1 1.5 1.5v6.5a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 18.5V12a1.5 1.5 0 0 1 1.5-1.5z", "M12 14.5v2")
    val Descargar = trazo("descargar", "M12 4v10.5", "M7.5 10l4.5 4.5 4.5-4.5", "M5 19.5h14")
}
