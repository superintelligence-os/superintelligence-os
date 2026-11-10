package com.sios.boveda

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Códigos de un solo uso por tiempo (TOTP, RFC 6238): el secreto en Base32, como lo dan las webs en
 * su QR («otpauth://totp/…»), y la importación de la exportación de Google Authenticator
 * («otpauth-migration://offline?data=…», un protobuf sencillo que se lee aquí a mano).
 */
object Totp {
    data class Clave(
        val secreto: String,
        val algoritmo: String = "SHA1",
        val digitos: Int = 6,
        val periodo: Int = 30,
    ) {
        fun json(): JSONObject = JSONObject().put("s", secreto).put("a", algoritmo).put("d", digitos).put("p", periodo)

        companion object {
            fun de(o: JSONObject) = Clave(o.getString("s"), o.optString("a", "SHA1"), o.optInt("d", 6), o.optInt("p", 30))
        }
    }

    /** Un código leído de un QR o importado: la clave y para quién es. */
    data class Leido(val clave: Clave, val emisor: String, val cuenta: String)

    // ---------- Base32 (RFC 4648) ----------

    private const val ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** El secreto tal como lo escriben las webs: con espacios, guiones, minúsculas o sin relleno. */
    fun limpiar(s: String): String = s.uppercase().filter { it != ' ' && it != '-' && it != '=' && !it.isWhitespace() }

    fun valido(s: String): Boolean {
        val l = limpiar(s)
        return l.length >= 16 && l.all { it in ALFABETO } && runCatching { base32(l) }.getOrNull()?.size?.let { it >= 10 } == true
    }

    fun base32(s: String): ByteArray {
        val l = limpiar(s)
        val salida = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in l) {
            val v = ALFABETO.indexOf(c)
            require(v >= 0) { "Base32 no válido" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                salida.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
        }
        return salida.toByteArray()
    }

    fun aBase32(b: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (x in b) {
            buffer = (buffer shl 8) or (x.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALFABETO[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALFABETO[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    // ---------- Códigos ----------

    /** El código en este momento (o en [ms]). */
    fun codigo(c: Clave, ms: Long = System.currentTimeMillis()): String {
        val contador = (ms / 1000) / c.periodo.coerceAtLeast(1)
        val algoritmo = when (c.algoritmo.uppercase()) {
            "SHA256" -> "HmacSHA256"
            "SHA512" -> "HmacSHA512"
            else -> "HmacSHA1"
        }
        val mac = Mac.getInstance(algoritmo).apply { init(SecretKeySpec(base32(c.secreto), algoritmo)) }
        val h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(contador).array())
        val o = h[h.size - 1].toInt() and 0x0F
        val n = ((h[o].toInt() and 0x7F) shl 24) or ((h[o + 1].toInt() and 0xFF) shl 16) or
            ((h[o + 2].toInt() and 0xFF) shl 8) or (h[o + 3].toInt() and 0xFF)
        val digitos = c.digitos.coerceIn(6, 8)
        var m = 1
        repeat(digitos) { m *= 10 }
        return (n % m).toString().padStart(digitos, '0')
    }

    /** Segundos que le quedan al código actual. */
    fun restante(c: Clave, ms: Long = System.currentTimeMillis()): Float {
        val p = c.periodo.coerceAtLeast(1) * 1000L
        return (p - ms % p) / 1000f
    }

    /** «482913» → «482 913» (y «12345678» → «1234 5678»): se lee y se dicta mejor. */
    fun agrupar(codigo: String): String = when (codigo.length) {
        6 -> codigo.substring(0, 3) + " " + codigo.substring(3)
        8 -> codigo.substring(0, 4) + " " + codigo.substring(4)
        else -> codigo
    }

    // ---------- otpauth:// ----------

    private fun decodificar(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    private fun parametros(consulta: String): Map<String, String> = consulta.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i).lowercase() to decodificar(it.substring(i + 1))
    }.toMap()

    /** Lee un «otpauth://totp/Emisor:cuenta?secret=…». null si no es un TOTP (los HOTP no se admiten). */
    fun deUri(uri: String): Leido? {
        val t = uri.trim()
        if (!t.startsWith("otpauth://", ignoreCase = true)) return null
        val resto = t.substring("otpauth://".length)
        val tipo = resto.substringBefore('/').lowercase()
        if (tipo != "totp") return null
        val etiqueta = decodificar(resto.substringAfter('/').substringBefore('?'))
        val p = parametros(resto.substringAfter('?', ""))
        val secreto = limpiar(p["secret"] ?: return null)
        if (!valido(secreto)) return null
        val emisorEtiqueta = if (etiqueta.contains(':')) etiqueta.substringBefore(':').trim() else ""
        val cuenta = if (etiqueta.contains(':')) etiqueta.substringAfter(':').trim() else etiqueta.trim()
        val algoritmo = (p["algorithm"] ?: "SHA1").uppercase().takeIf { it in setOf("SHA1", "SHA256", "SHA512") } ?: "SHA1"
        val digitos = p["digits"]?.toIntOrNull()?.takeIf { it in 6..8 } ?: 6
        val periodo = p["period"]?.toIntOrNull()?.takeIf { it in 10..300 } ?: 30
        return Leido(Clave(secreto, algoritmo, digitos, periodo), (p["issuer"] ?: emisorEtiqueta).trim(), cuenta)
    }

    /** El URI de una clave (para exportarla o volver a enseñarla como QR). */
    fun aUri(c: Clave, emisor: String, cuenta: String): String {
        fun cod(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        val etiqueta = if (emisor.isNotBlank()) "${cod(emisor)}:${cod(cuenta)}" else cod(cuenta)
        return "otpauth://totp/$etiqueta?secret=${c.secreto}" +
            (if (emisor.isNotBlank()) "&issuer=${cod(emisor)}" else "") +
            "&algorithm=${c.algoritmo}&digits=${c.digitos}&period=${c.periodo}"
    }

    // ---------- Exportación de Google Authenticator ----------

    /** Resultado de leer un QR de exportación: los TOTP y cuántos se han dejado (HOTP). */
    class Migracion(val codigos: List<Leido>, val omitidos: Int, val lote: Int, val lotes: Int)

    fun esMigracion(s: String) = s.trim().startsWith("otpauth-migration://", ignoreCase = true)

    fun deMigracion(uri: String): Migracion? {
        val consulta = uri.trim().substringAfter('?', "")
        val datos = consulta.split('&').firstOrNull { it.startsWith("data=") }?.removePrefix("data=") ?: return null
        val b64 = decodificar(datos).replace(' ', '+')
        val bytes = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
        val codigos = mutableListOf<Leido>()
        var omitidos = 0
        var lote = 0
        var lotes = 1
        runCatching {
            Protobuf(bytes).campos { campo, valor ->
                when (campo) {
                    1 -> {
                        val p = Protobuf(valor as ByteArray)
                        var secreto = ByteArray(0)
                        var nombre = ""
                        var emisor = ""
                        var algoritmo = 1
                        var digitos = 1
                        var tipo = 2
                        p.campos { c, v ->
                            when (c) {
                                1 -> secreto = v as ByteArray
                                2 -> nombre = String(v as ByteArray)
                                3 -> emisor = String(v as ByteArray)
                                4 -> algoritmo = (v as Long).toInt()
                                5 -> digitos = (v as Long).toInt()
                                6 -> tipo = (v as Long).toInt()
                            }
                        }
                        if (tipo != 2 || secreto.isEmpty() || algoritmo == 4) { omitidos++; return@campos }
                        val alg = when (algoritmo) { 2 -> "SHA256"; 3 -> "SHA512"; else -> "SHA1" }
                        val cuenta = if (emisor.isNotEmpty() && nombre.startsWith("$emisor:")) nombre.removePrefix("$emisor:").trim() else nombre
                        codigos += Leido(Clave(aBase32(secreto), alg, if (digitos == 2) 8 else 6, 30), emisor, cuenta)
                    }
                    3 -> lotes = (valor as Long).toInt().coerceAtLeast(1)
                    4 -> lote = (valor as Long).toInt()
                }
            }
        }.onFailure { return null }
        return Migracion(codigos, omitidos, lote, lotes)
    }

    /** Lector mínimo de protobuf: varints (Long) y campos con longitud (ByteArray). */
    private class Protobuf(private val b: ByteArray) {
        private var i = 0

        private fun varint(): Long {
            var r = 0L
            var s = 0
            while (true) {
                val x = b[i++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl s)
                if (x and 0x80 == 0) return r
                s += 7
                require(s < 64)
            }
        }

        fun campos(f: (Int, Any) -> Unit) {
            while (i < b.size) {
                val k = varint()
                val campo = (k shr 3).toInt()
                when ((k and 7).toInt()) {
                    0 -> f(campo, varint())
                    2 -> {
                        val n = varint().toInt()
                        require(n >= 0 && i + n <= b.size)
                        f(campo, b.copyOfRange(i, i + n))
                        i += n
                    }
                    1 -> i += 8
                    5 -> i += 4
                    else -> error("protobuf no válido")
                }
            }
        }
    }
}
