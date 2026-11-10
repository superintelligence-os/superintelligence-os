package com.sios.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Iconos de trazo fino (24×24), planos, dibujados a mano para no depender de librerías. */
object Iconos {
    private fun trazo(nombre: String, vararg caminos: String, relleno: Boolean = false) =
        ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f).apply {
            for (c in caminos) {
                addPath(
                    pathData = PathParser().parsePathString(c).toNodes(),
                    fill = if (relleno) SolidColor(Color.White) else null,
                    stroke = if (relleno) null else SolidColor(Color.White),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** El logo de la IA con la que se ha entrado (con Claude, su chispa); se tiñe con el color que se le pase. */
    val Asistente: ImageVector get() = Marca.logo() ?: LogoClaude

    /** Logo oficial de Claude (la chispa), relleno. */
    val LogoClaude = ImageVector.Builder("claude", 24.dp, 24.dp, 248f, 248f).apply {
        addPath(pathData = PathParser().parsePathString(LOGO_CLAUDE).toNodes(), fill = SolidColor(Color.White))
    }.build()
    val Enviar = trazo("enviar", "M12 19V5", "M5 12l7-7 7 7")
    val Arrastrar = trazo("arrastrar", "M9 6h.01", "M15 6h.01", "M9 12h.01", "M15 12h.01", "M9 18h.01", "M15 18h.01")
    val Menos = trazo("menos", "M7 12h10")
    val Mas = trazo("mas", "M12 6v12", "M6 12h12")
    val Widget = trazo("widget", "M4 5a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z", "M13 5a1 1 0 0 1 1-1h5a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1h-5a1 1 0 0 1-1-1z", "M4 14a1 1 0 0 1 1-1h14a1 1 0 0 1 1 1v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z")
    val Parar = trazo("parar", "M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", relleno = true)
    val Micro = trazo("micro", "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z", "M19 11a7 7 0 0 1-14 0", "M12 18v3")
    val Menu = trazo("menu", "M4 7h16", "M4 12h10", "M4 17h16")
    val Nueva = trazo("nueva", "M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z")
    val Ajustes = trazo("ajustes", "M4 7h10", "M18 7h2", "M4 17h2", "M10 17h10", "M16 5v4", "M8 15v4")
    val Hecho = trazo("hecho", "M5 12.5l4.5 4.5L19 7.5")
    val Cruz = trazo("cruz", "M6 6l12 12", "M18 6L6 18")
    val Derecha = trazo("derecha", "M9 5l7 7-7 7")
    val Abajo = trazo("abajo", "M5 9l7 7 7-7")
    val Atras = trazo("atras", "M15 5l-7 7 7 7")
    val Buscar = trazo("buscar", "M11 4a7 7 0 1 1 0 14a7 7 0 0 1 0-14z", "M20 20l-4-4")
    val Apps = trazo(
        "apps",
        "M5 4h3a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
        "M16 4h3a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z",
        "M5 15h3a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z",
        "M16 15h3a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1z",
    )
    val Papelera = trazo("papelera", "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12", "M9 7V4h6v3")
    val Lapiz = trazo("lapiz", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z")
    val Llamar = trazo("llamar", "M5 4h3l2 5-2.5 1.5a11 11 0 0 0 6 6L15 14l5 2v3a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z")
    val Reloj = trazo("reloj", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 7v5l3 2")
    // Botones de los widgets.
    val Reproducir = trazo("reproducir", "M8 5.5v13l10.5-6.5z")
    val Siguiente = trazo("siguiente", "M6 6l8.5 6L6 18z", "M18 6v12")
    val Anterior = trazo("anterior", "M18 6l-8.5 6L18 18z", "M6 6v12")
    val Navegar = trazo("navegar", "M4 11l16-7-7 16-2-7z")
    val Casa = trazo("casa", "M4 11l8-7 8 7", "M6 9.5V20h12V9.5")
    val Compartir = trazo("compartir", "M12 15V4", "M8 8l4-4 4 4", "M5 13v6h14v-6")
    val Maletin = trazo("maletin", "M4 8h16v11H4z", "M9 8V5.5h6V8", "M4 13h16")
    val Coche = trazo("coche", "M5 16V11.5l2-5h10l2 5V16", "M4 16h16v2.5H4z", "M5 11.5h14", "M7.5 14h.01", "M16.5 14h.01")
    val Musica = trazo("musica", "M9 18V6l11-2v12", "M9 18a2.5 2.5 0 1 1-2.5-2.5A2.5 2.5 0 0 1 9 18z", "M20 16a2.5 2.5 0 1 1-2.5-2.5A2.5 2.5 0 0 1 20 16z")
    val Refrescar = trazo("refrescar", "M20 11a8 8 0 1 0-2.3 6.3", "M20 5v6h-6")
    val Bombilla = trazo("bombilla", "M9 18h6", "M10 21h4", "M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2.1h5c0-.9.4-1.6 1-2.1A6 6 0 0 0 12 3z")
    val Pregunta = trazo("pregunta", "M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1 .9-1 1.7v.5", "M12 17.5v.01")
    // Dirección de una llamada en Recientes: entrante (abajo a la izquierda) y saliente (arriba a la derecha).
    val Entrante = trazo("entrante", "M17 7L7 17", "M7 9v8h8")
    val Saliente = trazo("saliente", "M7 17L17 7", "M9 7h8v8")
    val Colgar = trazo("colgar", "M3 14.5c5-4.7 13-4.7 18 0l-2.4 2.4-3.1-1.6v-2.4a11 11 0 0 0-7 0v2.4l-3.1 1.6z")
    val MicroNo = trazo("micro_no", "M9 9V6a3 3 0 0 1 5.6-1.5", "M15 10v2a3 3 0 0 1-4.6 2.5", "M19 11a7 7 0 0 1-11.5 5.4", "M5 11a7 7 0 0 0 .7 3", "M12 18v3", "M4 4l16 16")
    val Altavoz = trazo("altavoz", "M4 9h4l5-4v14l-5-4H4z", "M16.5 8.5a5 5 0 0 1 0 7", "M19 6a8.5 8.5 0 0 1 0 12")
    val Teclado = trazo("teclado", "M6 5h.01", "M12 5h.01", "M18 5h.01", "M6 11h.01", "M12 11h.01", "M18 11h.01", "M6 17h.01", "M12 17h.01", "M18 17h.01", "M12 21h.01")
    val Pausa = trazo("pausa", "M9 6v12", "M15 6v12")
    val Intro = trazo("intro", "M19 6v5a3 3 0 0 1-3 3H6", "M10 10l-4 4 4 4")
    val Borrar = trazo("borrar", "M9 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6-7z", "M12 9.5l5 5", "M17 9.5l-5 5")
    val Mayus = trazo("mayus", "M12 4l7 8h-4v7H9v-7H5z")
    val Mundo = trazo("mundo", "M12 3a9 9 0 1 1 0 18a9 9 0 0 1 0-18z", "M3 12h18", "M12 3c2.5 2.7 3.6 5.7 3.6 9s-1.1 6.3-3.6 9c-2.5-2.7-3.6-5.7-3.6-9s1.1-6.3 3.6-9z")
    val Camara = trazo("camara", "M4 8a2 2 0 0 1 2-2h2l1.5-2h5L16 6h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2z", "M12 9.5a3.5 3.5 0 1 1 0 7a3.5 3.5 0 0 1 0-7z")
    val Enlace = trazo("enlace", "M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1", "M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1")
    val Documento = trazo("documento", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M9 13h6", "M9 17h4")
    val Telefono = trazo("telefono", "M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z", "M11 18h2")
}

/** Trazado del logo de Claude (248×248), tal como lo usa la app oficial. */
private const val LOGO_CLAUDE =
    "M52.4285 162.873L98.7844 136.879L99.5485 134.602L98.7844 133.334H96.4921L88.7237 132.862L62.2346 132.153L39.3113 131.207L17.0249 130.026L11.4214 128.844L6.2 121.873L6.7094 118.447L11.4214 115.257L18.171 115.847L33.0711 116.911L55.485 118.447L71.6586 119.392L95.728 121.873H99.5485L100.058 120.337L98.7844 119.392L97.7656 118.447L74.5877 102.732L49.4995 86.1905L36.3823 76.62L29.3779 71.7757L25.8121 67.2858L24.2839 57.3608L30.6515 50.2716L39.3113 50.8623L41.4763 51.4531L50.2636 58.1879L68.9842 72.7209L93.4357 90.6804L97.0015 93.6343L98.4374 92.6652L98.6571 91.9801L97.0015 89.2625L83.757 65.2772L69.621 40.8192L63.2534 30.6579L61.5978 24.632C60.9565 22.1032 60.579 20.0111 60.579 17.4246L67.8381 7.49965L71.9133 6.19995L81.7193 7.49965L85.7946 11.0443L91.9074 24.9865L101.714 46.8451L116.996 76.62L121.453 85.4816L123.873 93.6343L124.764 96.1155H126.292V94.6976L127.566 77.9197L129.858 57.3608L132.15 30.8942L132.915 23.4505L136.608 14.4708L143.994 9.62643L149.725 12.344L154.437 19.0788L153.8 23.4505L150.998 41.6463L145.522 70.1215L141.957 89.2625H143.994L146.414 86.7813L156.093 74.0206L172.266 53.698L179.398 45.6635L187.803 36.802L193.152 32.5484H203.34L210.726 43.6549L207.415 55.1159L196.972 68.3492L188.312 79.5739L175.896 96.2095L168.191 109.585L168.882 110.689L170.738 110.53L198.755 104.504L213.91 101.787L231.994 98.7149L240.144 102.496L241.036 106.395L237.852 114.311L218.495 119.037L195.826 123.645L162.07 131.592L161.696 131.893L162.137 132.547L177.36 133.925L183.855 134.279H199.774L229.447 136.524L237.215 141.605L241.8 147.867L241.036 152.711L229.065 158.737L213.019 154.956L175.45 145.977L162.587 142.787H160.805V143.85L171.502 154.366L191.242 172.089L215.82 195.011L217.094 200.682L213.91 205.172L210.599 204.699L188.949 188.394L180.544 181.069L161.696 165.118H160.422V166.772L164.752 173.152L187.803 207.771L188.949 218.405L187.294 221.832L181.308 223.959L174.813 222.777L161.187 203.754L147.305 182.486L136.098 163.345L134.745 164.2L128.075 235.42L125.019 239.082L117.887 241.8L111.902 237.31L108.718 229.984L111.902 215.452L115.722 196.547L118.779 181.541L121.58 162.873L123.291 156.636L123.14 156.219L121.773 156.449L107.699 175.752L86.304 204.699L69.3663 222.777L65.291 224.431L58.2867 220.768L58.9235 214.27L62.8713 208.48L86.304 178.705L100.44 160.155L109.551 149.507L109.462 147.967L108.959 147.924L46.6977 188.512L35.6182 189.93L30.7788 185.44L31.4156 178.115L33.7079 175.752L52.4285 162.873Z"
