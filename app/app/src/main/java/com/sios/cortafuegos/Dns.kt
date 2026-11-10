package com.sios.cortafuegos

/*
 * Lo justo de IP, UDP y DNS para el cortafuegos: leer una consulta que llega por el túnel y devolver
 * la respuesta por el mismo sitio. Sin dependencias y sin estado (se puede probar en el ordenador).
 */

private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
private fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
private fun put16(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
private fun put32(b: ByteArray, i: Int, v: Long) { put16(b, i, (v ushr 16).toInt() and 0xFFFF); put16(b, i + 2, v.toInt() and 0xFFFF) }

/** Una consulta DNS que ha llegado al túnel: quién la hace (IP y puerto) y el mensaje. */
internal class Consulta(
    val v6: Boolean,
    /** IP de quien pregunta (4 o 16 bytes). */
    val origen: ByteArray,
    /** Nuestra IP de DNS, a la que iba. */
    val destino: ByteArray,
    val puertoOrigen: Int,
    val puertoDestino: Int,
    /** El mensaje DNS (la carga del UDP). */
    val dns: ByteArray,
)

internal object Paquetes {
    /** Lo más grande que se devuelve por el túnel (su MTU, menos las cabeceras). */
    const val MAX_DNS = CortafuegosVpn.MTU - 48

    /** Un paquete del túnel: si es UDP (sin fragmentar), la consulta; si no, null (se descarta). */
    fun leer(p: ByteArray, n: Int): Consulta? {
        if (n < 28) return null
        when ((p[0].toInt() ushr 4) and 0xF) {
            4 -> {
                val ihl = (p[0].toInt() and 0xF) * 4
                if (ihl < 20 || n < ihl + 8 || (p[9].toInt() and 0xFF) != 17) return null
                // Fragmentos: el resolutor del sistema no los manda; si llegan, fuera.
                if ((p[6].toInt() and 0x3F) != 0 || p[7].toInt() != 0) return null
                val fin = minOf(n, u16(p, 2))
                return udp(false, p.copyOfRange(12, 16), p.copyOfRange(16, 20), p, ihl, fin)
            }
            6 -> {
                // Sin cabeceras de extensión: el siguiente encabezado tiene que ser UDP.
                if (n < 48 || (p[6].toInt() and 0xFF) != 17) return null
                val fin = minOf(n, 40 + u16(p, 4))
                return udp(true, p.copyOfRange(8, 24), p.copyOfRange(24, 40), p, 40, fin)
            }
        }
        return null
    }

    private fun udp(v6: Boolean, origen: ByteArray, destino: ByteArray, p: ByteArray, ini: Int, fin: Int): Consulta? {
        if (fin - ini < 8) return null
        val largo = u16(p, ini + 4)
        val finUdp = minOf(fin, ini + largo)
        if (finUdp - ini - 8 < 12) return null
        return Consulta(v6, origen, destino, u16(p, ini), u16(p, ini + 2), p.copyOfRange(ini + 8, finUdp))
    }

    /** El paquete de vuelta: de nuestra IP de DNS a quien preguntó, con las sumas de control. */
    fun respuesta(c: Consulta, dns: ByteArray): ByteArray {
        val largoUdp = 8 + dns.size
        val cab = if (c.v6) 40 else 20
        val b = ByteArray(cab + largoUdp)
        if (c.v6) {
            b[0] = 0x60
            put16(b, 4, largoUdp)
            b[6] = 17
            b[7] = 64
            c.destino.copyInto(b, 8)
            c.origen.copyInto(b, 24)
        } else {
            b[0] = 0x45
            put16(b, 2, b.size)
            b[6] = 0x40 // no fragmentar
            b[8] = 64
            b[9] = 17
            c.destino.copyInto(b, 12)
            c.origen.copyInto(b, 16)
            put16(b, 10, suma(b, 0, 20, 0))
        }
        put16(b, cab, c.puertoDestino)
        put16(b, cab + 2, c.puertoOrigen)
        put16(b, cab + 4, largoUdp)
        dns.copyInto(b, cab + 8)
        // Pseudocabecera: origen, destino, protocolo y largo (obligatoria en IPv6; en IPv4, de paso).
        var pseudo = 17L + largoUdp
        for (ip in arrayOf(c.destino, c.origen)) for (i in ip.indices step 2) pseudo += u16(ip, i)
        val s = suma(b, cab, largoUdp, pseudo)
        put16(b, cab + 6, if (s == 0) 0xFFFF else s)
        return b
    }

    /** Suma de control de Internet (complemento a uno) de un tramo, con un valor inicial. */
    internal fun suma(b: ByteArray, ini: Int, largo: Int, inicial: Long): Int {
        var s = inicial
        var i = ini
        val fin = ini + largo
        while (i + 1 < fin) { s += u16(b, i); i += 2 }
        if (i < fin) s += (b[i].toInt() and 0xFF) shl 8
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }
}

internal object Dns {
    const val NXDOMAIN = 3
    const val SERVFAIL = 2
    private const val OPT = 41
    private const val SOA = 6

    /** La pregunta de una consulta: nombre (en minúsculas, sin punto final), tipo y dónde acaba. */
    class Pregunta(val nombre: String, val tipo: Int, val clase: Int, val fin: Int)

    fun id(m: ByteArray) = u16(m, 0)
    fun truncada(m: ByteArray) = m.size >= 4 && (m[2].toInt() and 0x02) != 0
    fun rcode(m: ByteArray) = if (m.size >= 4) m[3].toInt() and 0x0F else -1

    /** Lee la (única) pregunta de una consulta; null si no es una consulta normal. */
    fun pregunta(m: ByteArray): Pregunta? {
        if (m.size < 17) return null
        if ((m[2].toInt() and 0x80) != 0) return null // es una respuesta
        if (((m[2].toInt() ushr 3) and 0x0F) != 0) return null // solo consultas normales (opcode 0)
        if (u16(m, 4) != 1) return null
        val sb = StringBuilder()
        var i = 12
        while (true) {
            if (i >= m.size) return null
            val l = m[i].toInt() and 0xFF
            if (l == 0) { i++; break }
            if (l and 0xC0 != 0 || i + 1 + l > m.size || sb.length + l > 253) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in 1..l) sb.append((m[i + k].toInt() and 0xFF).toChar().lowercaseChar())
            i += 1 + l
        }
        if (i + 4 > m.size) return null
        return Pregunta(sb.toString(), u16(m, i), u16(m, i + 2), i + 4)
    }

    /**
     * Respuesta sin datos con el código que se diga (NXDOMAIN para lo bloqueado). Sin registro SOA a
     * propósito: así el sistema no la guarda en su caché, que es la misma para todas las apps (si no,
     * lo bloqueado para una app se quedaría bloqueado para las demás).
     */
    fun negativa(consulta: ByteArray, p: Pregunta, rcode: Int): ByteArray {
        val r = ByteArray(p.fin)
        consulta.copyInto(r, 0, 0, p.fin)
        // QR, el mismo opcode y RD que la consulta, RA y el código.
        r[2] = (0x80 or (consulta[2].toInt() and 0x79)).toByte()
        r[3] = (0x80 or (rcode and 0x0F)).toByte()
        put16(r, 4, 1)
        put16(r, 6, 0)
        put16(r, 8, 0)
        put16(r, 10, 0)
        return r
    }

    /** La misma respuesta con otro identificador (las de la caché van a consultas nuevas). */
    fun conId(m: ByteArray, id: Int): ByteArray = m.copyOf().also { put16(it, 0, id) }

    /** Respuesta demasiado grande para el túnel: solo la cabecera y la pregunta, marcada como truncada. */
    fun truncar(m: ByteArray, p: Pregunta): ByteArray {
        val r = negativa(m, p, rcode(m).coerceAtLeast(0))
        r[2] = (r[2].toInt() or 0x02).toByte()
        return r
    }

    /** Salta un nombre (con punteros de compresión); devuelve dónde sigue o -1 si está mal formado. */
    private fun saltarNombre(m: ByteArray, desde: Int): Int {
        var i = desde
        while (i < m.size) {
            val l = m[i].toInt() and 0xFF
            when {
                l == 0 -> return i + 1
                l and 0xC0 == 0xC0 -> return if (i + 2 <= m.size) i + 2 else -1
                l and 0xC0 != 0 -> return -1
                else -> i += 1 + l
            }
        }
        return -1
    }

    /**
     * Recorre los registros de una respuesta: para cada uno, su tipo y dónde está su TTL. Devuelve
     * false si el mensaje está mal formado.
     */
    private inline fun registros(m: ByteArray, cada: (tipo: Int, seccion: Int, posTtl: Int) -> Unit): Boolean {
        if (m.size < 12) return false
        var i = 12
        repeat(u16(m, 4)) {
            i = saltarNombre(m, i)
            if (i < 0 || i + 4 > m.size) return false
            i += 4
        }
        val cuentas = intArrayOf(u16(m, 6), u16(m, 8), u16(m, 10))
        for (seccion in 0..2) repeat(cuentas[seccion]) {
            i = saltarNombre(m, i)
            if (i < 0 || i + 10 > m.size) return false
            val tipo = u16(m, i)
            cada(tipo, seccion, i + 4)
            i += 10 + u16(m, i + 8)
            if (i > m.size) return false
        }
        return true
    }

    /**
     * Cuánto se puede guardar una respuesta en nuestra caché (segundos): el TTL más bajo de sus
     * respuestas o, si no trae ninguna (no existe o no tiene ese tipo), el mínimo de su SOA.
     */
    fun ttl(m: ByteArray): Long {
        var minimo = Long.MAX_VALUE
        var soa = -1L
        val bien = registros(m) { tipo, seccion, pos ->
            if (tipo == OPT) return@registros
            val t = u32(m, pos)
            if (seccion == 0) minimo = minOf(minimo, t)
            else if (seccion == 1 && tipo == SOA) soa = t
        }
        if (!bien) return 0
        return when {
            minimo != Long.MAX_VALUE -> minimo
            soa >= 0 -> minOf(soa, 300)
            else -> 30
        }
    }

    /**
     * La respuesta con todos los TTL rebajados a [maximo] segundos (menos el del registro OPT, que no
     * es un TTL). Es lo que se devuelve al sistema: así no reutiliza para una app lo que pidió otra.
     */
    fun limitarTtl(m: ByteArray, maximo: Long): ByteArray {
        val r = m.copyOf()
        registros(r) { tipo, _, pos -> if (tipo != OPT && u32(r, pos) > maximo) put32(r, pos, maximo) }
        return r
    }
}
