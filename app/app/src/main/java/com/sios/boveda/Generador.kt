package com.sios.boveda

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.max

/** Contraseñas nuevas: al azar (con SecureRandom) o con palabras del diccionario del teclado. */
object Generador {
    private val azar = SecureRandom()

    const val MINUSCULAS = "abcdefghijkmnopqrstuvwxyz"
    const val MAYUSCULAS = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    const val NUMEROS = "23456789"
    const val SIMBOLOS = "!#$%&*+-=?@^_~.:;"

    /**
     * Una contraseña al azar. Sin los caracteres que se confunden al leerlos (l, I, O, 0, 1) y con al
     * menos uno de cada grupo elegido.
     */
    fun aleatoria(longitud: Int, mayusculas: Boolean = true, numeros: Boolean = true, simbolos: Boolean = true): String {
        val grupos = listOfNotNull(MINUSCULAS, MAYUSCULAS.takeIf { mayusculas }, NUMEROS.takeIf { numeros }, SIMBOLOS.takeIf { simbolos })
        val todos = grupos.joinToString("")
        val n = longitud.coerceIn(grupos.size, 128)
        val c = CharArray(n) { todos[azar.nextInt(todos.length)] }
        // Uno de cada grupo, en posiciones distintas al azar.
        val posiciones = (0 until n).shuffled(java.util.Random(azar.nextLong())).take(grupos.size)
        grupos.forEachIndexed { i, g -> c[posiciones[i]] = g[azar.nextInt(g.length)] }
        return String(c)
    }

    @Volatile private var palabras: Pair<String, List<String>>? = null

    /** Palabras de 4 a 8 letras del diccionario del teclado (sin tildes), en el idioma del móvil. */
    fun palabras(ctx: Context): List<String> {
        val idioma = Locale.getDefault().language.takeIf { it in setOf("es", "en", "de", "fr", "it", "pt") } ?: "en"
        palabras?.takeIf { it.first == idioma }?.let { return it.second }
        val lista = runCatching {
            ctx.assets.open("diccionario-$idioma.txt").bufferedReader().useLines { lineas ->
                lineas.map { plano(it.substringBefore('\t').trim()) }
                    .filter { it.length in 4..8 && it.all { c -> c in 'a'..'z' } }
                    .distinct().take(8000).toList()
            }
        }.getOrDefault(emptyList())
        palabras = idioma to lista
        return lista
    }

    /** Una frase de [n] palabras («tarde-caballo-jardín» sin tildes), con una en mayúscula y un número. */
    fun frase(ctx: Context, n: Int, separador: String = "-", conNumero: Boolean = true): String {
        val lista = palabras(ctx).ifEmpty { return aleatoria(20) }
        val elegidas = MutableList(n.coerceIn(3, 12)) { lista[azar.nextInt(lista.size)] }
        val i = azar.nextInt(elegidas.size)
        elegidas[i] = elegidas[i].replaceFirstChar { it.uppercase() }
        if (conNumero) {
            val j = azar.nextInt(elegidas.size)
            elegidas[j] = elegidas[j] + azar.nextInt(10)
        }
        return elegidas.joinToString(separador)
    }

    fun plano(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
}

/**
 * Lo fuerte que es una contraseña, estimado en el móvil: los bits de lo que costaría adivinarla,
 * con descuentos por lo que prueba primero quien ataca (las más usadas, palabras del diccionario con
 * números al final, secuencias y repeticiones). No es zxcvbn, pero acierta en lo importante.
 */
object Fuerza {
    /** 0 muy débil, 1 débil, 2 aceptable, 3 fuerte, 4 muy fuerte. */
    fun nivel(c: String): Int {
        val b = bits(c)
        return when {
            c.isEmpty() -> 0
            b < 28 -> 0
            b < 40 -> 1
            b < 60 -> 2
            b < 80 -> 3
            else -> 4
        }
    }

    private val COMUNES = (
        "123456 123456789 12345678 password qwerty 12345 1234567 111111 123123 abc123 1234567890 password1 iloveyou " +
            "000000 qwerty123 qwertyuiop 654321 666666 121212 123321 1q2w3e4r 1qaz2wsx zaq12wsx asdfgh asdfghjkl " +
            "dragon monkey letmein football baseball welcome admin login master hello sunshine princess shadow " +
            "superman batman trustno1 passw0rd p@ssw0rd contraseña contrasena clave secreto hola teamo tequiero " +
            "barcelona realmadrid passwort hallo schatz motdepasse azerty soleil bonjour amore ciao senha " +
            "123mudar flamengo 112233 159753 147258 987654321 aaaaaa"
        ).split(' ').toSet()

    private val SECUENCIAS = listOf("abcdefghijklmnopqrstuvwxyz", "0123456789", "qwertyuiop", "asdfghjkl", "zxcvbnm", "azertyuiop", "qwertzuiop")

    @Volatile private var diccionario: Set<String> = emptySet()

    /** Carga (una vez, en segundo plano) las palabras del teclado para reconocerlas en las contraseñas. */
    fun prepararDiccionario(ctx: Context) {
        if (diccionario.isNotEmpty()) return
        val idiomas = setOf(Locale.getDefault().language, "en", "es").filter { it in setOf("es", "en", "de", "fr", "it", "pt") }
        val todas = HashSet<String>()
        for (i in idiomas) runCatching {
            ctx.assets.open("diccionario-$i.txt").bufferedReader().useLines { l ->
                l.take(20000).forEach { w -> Generador.plano(w.substringBefore('\t').trim()).takeIf { it.length >= 3 }?.let(todas::add) }
            }
        }
        diccionario = todas
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    private fun reserva(c: String): Int {
        var r = 0
        if (c.any { it in 'a'..'z' }) r += 26
        if (c.any { it in 'A'..'Z' }) r += 26
        if (c.any { it.isDigit() }) r += 10
        if (c.any { !it.isLetterOrDigit() && it.code < 128 }) r += 33
        if (c.any { it.code >= 128 }) r += 60
        return max(r, 10)
    }

    fun bits(c: String): Double {
        if (c.isEmpty()) return 0.0
        val minus = Generador.plano(c)
        if (minus in COMUNES) return 4.0
        // Palabra + números o símbolos al final («Verano2024!»): lo primero que se prueba.
        val nucleo = minus.trimEnd { !it.isLetter() }.trimStart { !it.isLetter() }
        val resto = minus.length - nucleo.length
        val sinLeet = nucleo.replace('4', 'a').replace('3', 'e').replace('1', 'i').replace('0', 'o').replace('@', 'a').replace('$', 's')
        if (nucleo.length >= 3 && (nucleo in COMUNES || sinLeet in COMUNES || nucleo in diccionario || sinLeet in diccionario)) {
            return log2(20000.0) + resto * log2(12.0) + 2
        }
        // Repeticiones y secuencias: cuentan poco.
        var efectiva = 0.0
        var i = 0
        while (i < c.length) {
            var j = i + 1
            while (j < c.length && (c[j] == c[i] || enSecuencia(minus, i, j))) j++
            efectiva += if (j - i >= 3) 1.5 else (j - i).toDouble()
            i = j
        }
        return efectiva * log2(reserva(c).toDouble())
    }

    private fun enSecuencia(s: String, inicio: Int, j: Int): Boolean {
        if (j - inicio < 1) return false
        val trozo = s.substring(inicio, j + 1)
        return SECUENCIAS.any { it.contains(trozo) || it.reversed().contains(trozo) }
    }
}

/** La revisión de contraseñas: repetidas, débiles y (si se activa) filtradas. Todo se calcula en el móvil. */
class Salud(
    val repetidas: List<List<Entrada>>,
    val debiles: List<Entrada>,
    val filtradas: List<Pair<Entrada, Int>>,
) {
    val problemas: Int get() = (repetidas.flatten().map { it.id } + debiles.map { it.id } + filtradas.map { it.first.id }).toSet().size

    fun repetidaCon(e: Entrada): Int = repetidas.firstOrNull { g -> g.any { it.id == e.id } }?.size?.minus(1) ?: 0
    fun debil(e: Entrada) = debiles.any { it.id == e.id }
    fun filtrada(e: Entrada): Int = filtradas.firstOrNull { it.first.id == e.id }?.second ?: 0

    companion object {
        fun de(entradas: List<Entrada>, filtradas: Map<String, Int>): Salud {
            val conContrasena = entradas.filter { it.tipo == Tipo.CONTRASENA && it.contrasena.isNotEmpty() }
            val repetidas = conContrasena.groupBy { it.contrasena }.values.filter { it.size > 1 }.sortedByDescending { it.size }
            val debiles = conContrasena.filter { Fuerza.nivel(it.contrasena) <= 1 }
            val f = conContrasena.mapNotNull { e -> filtradas[Filtradas.huella(e.contrasena)]?.takeIf { it > 0 }?.let { e to it } }
            return Salud(repetidas, debiles, f)
        }
    }
}

/**
 * ¿Ha salido esta contraseña en alguna filtración? Have I Been Pwned con k-anonimato: del resumen SHA-1
 * de la contraseña solo se envían los 5 primeros caracteres; el servicio devuelve todos los resúmenes
 * que empiezan igual (con relleno, para que ni el tamaño de la respuesta diga nada) y se compara aquí.
 * Ni la contraseña ni su resumen completo salen nunca del móvil. Apagado de serie.
 */
object Filtradas {
    private val http by lazy {
        okhttp3.OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    }

    fun huella(c: String): String =
        MessageDigest.getInstance("SHA-1").digest(c.toByteArray()).joinToString("") { "%02X".format(it) }

    /** Comprueba las contraseñas (sin repetir consultas): huella → veces que ha aparecido (0: ninguna). */
    fun comprobar(contrasenas: Collection<String>): Map<String, Int> {
        val huellas = contrasenas.filter { it.isNotEmpty() }.map(::huella).toSet()
        val resultado = HashMap<String, Int>()
        for ((prefijo, deEste) in huellas.groupBy { it.substring(0, 5) }) {
            val peticion = okhttp3.Request.Builder().url("https://api.pwnedpasswords.com/range/$prefijo")
                .header("Add-Padding", "true").header("User-Agent", "SIOS-Boveda").build()
            val cuerpo = http.newCall(peticion).execute().use { if (!it.isSuccessful) error("HIBP ${it.code}"); it.body.string() }
            val veces = HashMap<String, Int>()
            cuerpo.lineSequence().forEach { l ->
                val i = l.indexOf(':')
                if (i > 0) veces[prefijo + l.substring(0, i).trim().uppercase()] = l.substring(i + 1).trim().toIntOrNull() ?: 0
            }
            for (h in deEste) resultado[h] = veces[h] ?: 0
        }
        return resultado
    }
}
