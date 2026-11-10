package com.sios.sistema

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Comprobación de firmas Ed25519 (las del manifiesto de actualizaciones de SIOS, que firma
 * scripts/publicar-version.sh con openssl). Primero con el proveedor del sistema; si este Android no
 * trae Ed25519, con una implementación propia de la verificación de RFC 8032 (solo comprobar: aquí
 * nunca se firma nada).
 */
internal object FirmaEd25519 {
    // Cabecera DER (SubjectPublicKeyInfo) de una clave pública Ed25519: 12 bytes y luego los 32 de la clave.
    private val CABECERA = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)

    /** La clave pública de un PEM «-----BEGIN PUBLIC KEY-----» (la de servicio/clave-actualizaciones.pem). */
    fun clavePem(pem: String): ByteArray? {
        val b64 = pem.lines().filter { it.isNotBlank() && !it.startsWith("-----") }.joinToString("")
        val der = runCatching { java.util.Base64.getDecoder().decode(b64) }.getOrNull() ?: return null
        if (der.size != 44 || !der.copyOfRange(0, 12).contentEquals(CABECERA)) return null
        return der.copyOfRange(12, 44)
    }

    fun verificar(clave: ByteArray, mensaje: ByteArray, firma: ByteArray): Boolean {
        if (clave.size != 32 || firma.size != 64) return false
        val sistema = runCatching {
            val publica = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(CABECERA + clave))
            Signature.getInstance("Ed25519").run { initVerify(publica); update(mensaje); verify(firma) }
        }
        // Un «no» del sistema es un no; solo si no sabe hacerlo se comprueba aquí.
        return sistema.getOrNull() ?: verificarPropio(clave, mensaje, firma)
    }

    // ---------- RFC 8032, sección 5.1.7 (verificación), con coordenadas extendidas ----------

    private val P: BigInteger = BigInteger.TWO.pow(255) - BigInteger.valueOf(19)
    private val L: BigInteger = BigInteger.TWO.pow(252) + BigInteger("27742317777372353535851937790883648493")
    private val D: BigInteger = (BigInteger.valueOf(-121665) * inv(BigInteger.valueOf(121666))).mod(P)
    private val RAIZ_MENOS_UNO: BigInteger = BigInteger.TWO.modPow((P - BigInteger.ONE) / BigInteger.valueOf(4), P)

    private class Punto(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun inv(a: BigInteger) = a.modPow(P - BigInteger.TWO, P)

    private val BASE: Punto by lazy {
        val y = (BigInteger.valueOf(4) * inv(BigInteger.valueOf(5))).mod(P)
        val x = recuperarX(y, false)!!
        Punto(x, y, BigInteger.ONE, (x * y).mod(P))
    }
    private val NEUTRO = Punto(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun sumar(a: Punto, b: Punto): Punto {
        val aa = ((a.y - a.x) * (b.y - b.x)).mod(P)
        val bb = ((a.y + a.x) * (b.y + b.x)).mod(P)
        val cc = (a.t * BigInteger.TWO * D * b.t).mod(P)
        val dd = (a.z * BigInteger.TWO * b.z).mod(P)
        val e = bb - aa
        val f = dd - cc
        val g = dd + cc
        val h = bb + aa
        return Punto((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    private fun multiplicar(k: BigInteger, punto: Punto): Punto {
        var r = NEUTRO
        var q = punto
        var n = k
        while (n.signum() > 0) {
            if (n.testBit(0)) r = sumar(r, q)
            q = sumar(q, q)
            n = n.shiftRight(1)
        }
        return r
    }

    private fun iguales(a: Punto, b: Punto) =
        (a.x * b.z - b.x * a.z).mod(P).signum() == 0 && (a.y * b.z - b.y * a.z).mod(P).signum() == 0

    private fun recuperarX(y: BigInteger, signo: Boolean): BigInteger? {
        if (y >= P) return null
        val x2 = ((y * y - BigInteger.ONE) * inv(D * y * y + BigInteger.ONE)).mod(P)
        if (x2.signum() == 0) return if (signo) null else BigInteger.ZERO
        var x = x2.modPow((P + BigInteger.valueOf(3)) / BigInteger.valueOf(8), P)
        if ((x * x - x2).mod(P).signum() != 0) x = (x * RAIZ_MENOS_UNO).mod(P)
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != signo) x = P - x
        return x
    }

    private fun entero(b: ByteArray) = BigInteger(1, b.reversedArray()) // little-endian

    private fun descomprimir(b: ByteArray): Punto? {
        val copia = b.copyOf()
        val signo = (copia[31].toInt() and 0x80) != 0
        copia[31] = (copia[31].toInt() and 0x7f).toByte()
        val y = entero(copia)
        val x = recuperarX(y, signo) ?: return null
        return Punto(x, y, BigInteger.ONE, (x * y).mod(P))
    }

    internal fun verificarPropio(clave: ByteArray, mensaje: ByteArray, firma: ByteArray): Boolean {
        if (clave.size != 32 || firma.size != 64) return false
        val a = descomprimir(clave) ?: return false
        val rBytes = firma.copyOfRange(0, 32)
        val r = descomprimir(rBytes) ?: return false
        val s = entero(firma.copyOfRange(32, 64))
        if (s >= L) return false
        val h = entero(MessageDigest.getInstance("SHA-512").run { update(rBytes); update(clave); update(mensaje); digest() }).mod(L)
        return iguales(multiplicar(s, BASE), sumar(r, multiplicar(h, a)))
    }
}
