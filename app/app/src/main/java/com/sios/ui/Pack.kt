package com.sios.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Pack de iconos de SIOS: baldosa gris redondeada con un glifo claro (los glifos están en
 * PackIconos.kt, generado). Se construyen una vez y se reutilizan.
 */
object Pack {
    /** Forma de todas las baldosas: cuadrado redondeado, como los iconos de la app de Claude. */
    val Forma = RoundedCornerShape(percent = 28)

    private val porPaquete: Map<String, Glifo> by lazy { GLIFOS.flatMap { g -> g.paquetes.map { it to g } }.toMap() }
    private val hechos = HashMap<String, ImageVector>()

    fun glifo(paquete: String): Glifo? = porPaquete[paquete]

    @Synchronized
    fun vector(g: Glifo): ImageVector = hechos.getOrPut(g.nombre) {
        ImageVector.Builder(g.nombre, 24.dp, 24.dp, 24f, 24f).apply {
            for (t in g.trazados) {
                addPath(
                    pathData = PathParser().parsePathString(t).toNodes(),
                    fill = if (g.relleno) SolidColor(Color.White) else null,
                    stroke = if (g.relleno) null else SolidColor(Color.White),
                    strokeLineWidth = 1.6f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
    }
}
