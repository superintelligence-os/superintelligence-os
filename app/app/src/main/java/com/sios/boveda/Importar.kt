package com.sios.boveda

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Traer contraseñas y códigos de otros sitios y llevarse la Bóveda:
 * - CSV de Bitwarden, Chrome, Firefox, 1Password, LastPass, KeePass y casi cualquier otro (por las cabeceras).
 * - Códigos de Aegis (JSON sin cifrar), 2FAS (.2fas sin contraseña) y listas de «otpauth://».
 * - La exportación cifrada de la propia Bóveda (.siosboveda), con todo, documentos incluidos.
 */
object Importar {
    /** Lo que se ha leído, sin guardar todavía. */
    class Leido(val entradas: List<Entrada>, val omitidas: Int, val archivos: Map<String, ByteArray> = emptyMap())

    sealed class Error(msg: String) : Exception(msg) {
        class Cifrado(val app: String) : Error("cifrado")
        class Formato : Error("formato")
        class Contrasena : Error("contrasena")
    }

    private val MAGIA = "SIOS-BOVEDA-1".toByteArray()
    private const val ITERACIONES = 600_000

    fun esExportacion(b: ByteArray) = b.size > MAGIA.size && b.copyOfRange(0, MAGIA.size).contentEquals(MAGIA)

    fun leerUri(ctx: Context, uri: Uri): ByteArray =
        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw Error.Formato()

    /** Lee un archivo de otra app (no la exportación de SIOS, que va con [descifrar]). */
    fun leer(bytes: ByteArray): Leido {
        val texto = String(bytes, Charsets.UTF_8).removePrefix("﻿").trim()
        if (texto.startsWith("{")) {
            val o = runCatching { JSONObject(texto) }.getOrNull() ?: throw Error.Formato()
            return when {
                o.has("db") -> aegis(o)
                o.has("services") || o.has("servicesEncrypted") -> dosFas(o)
                o.has("items") && o.optBoolean("encrypted", false) -> throw Error.Cifrado("Bitwarden")
                o.has("items") -> bitwardenJson(o)
                else -> throw Error.Formato()
            }
        }
        val lineas = texto.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lineas.isNotEmpty() && lineas.all { it.startsWith("otpauth", ignoreCase = true) }) return otpauth(lineas)
        return csv(texto)
    }

    // ---------- CSV ----------

    /** CSV con comillas, comas y saltos de línea dentro de las comillas (RFC 4180). */
    fun filas(texto: String): List<List<String>> {
        val filas = mutableListOf<List<String>>()
        var fila = mutableListOf<String>()
        val campo = StringBuilder()
        var comillas = false
        var i = 0
        val sep = if (texto.lineSequence().firstOrNull()?.let { l -> l.count { it == ';' } > l.count { it == ',' } } == true) ';' else ','
        while (i < texto.length) {
            val c = texto[i]
            if (comillas) {
                if (c == '"') {
                    if (i + 1 < texto.length && texto[i + 1] == '"') { campo.append('"'); i++ } else comillas = false
                } else campo.append(c)
            } else when (c) {
                '"' -> comillas = true
                sep -> { fila.add(campo.toString()); campo.clear() }
                '\r' -> {}
                '\n' -> { fila.add(campo.toString()); campo.clear(); filas.add(fila); fila = mutableListOf() }
                else -> campo.append(c)
            }
            i++
        }
        if (campo.isNotEmpty() || fila.isNotEmpty()) { fila.add(campo.toString()); filas.add(fila) }
        return filas.filter { f -> f.any { it.isNotBlank() } }
    }

    private val NOMBRE = setOf("name", "title", "nombre", "titulo", "título", "account", "item name")
    private val URL = setOf("url", "login_uri", "website", "web site", "uri", "urls", "login url", "web", "sitio")
    private val USUARIO = setOf("username", "login_username", "login", "user", "user name", "login name", "email", "e-mail", "usuario", "benutzername", "identifiant")
    private val CONTRASENA = setOf("password", "login_password", "pass", "contraseña", "contrasena", "passwort", "mot de passe", "senha")
    private val TOTP = setOf("login_totp", "totp", "otpauth", "otp", "one-time password", "2fa", "authenticator key", "otp secret")
    private val NOTAS = setOf("notes", "note", "extra", "comments", "comment", "notas", "notizen")
    private val TIPO = setOf("type", "tipo")

    private fun csv(texto: String): Leido {
        val f = filas(texto)
        if (f.size < 2) throw Error.Formato()
        val cab = f[0].map { it.trim().lowercase() }
        fun col(nombres: Set<String>) = cab.indexOfFirst { it in nombres }
        val iNombre = col(NOMBRE); val iUrl = col(URL); val iUsuario = col(USUARIO); val iContrasena = col(CONTRASENA)
        val iTotp = col(TOTP); val iNotas = col(NOTAS); val iTipo = col(TIPO)
        if (iContrasena < 0 && iTotp < 0) throw Error.Formato()
        var omitidas = 0
        val ahora = System.currentTimeMillis()
        val entradas = f.drop(1).mapNotNull { r ->
            fun v(i: Int) = if (i in r.indices) r[i].trim() else ""
            // Bitwarden exporta también notas, tarjetas e identidades: aquí solo van los accesos.
            val tipo = v(iTipo).lowercase()
            if (tipo.isNotEmpty() && tipo !in setOf("login", "password", "contraseña", "1")) { omitidas++; return@mapNotNull null }
            val contrasena = r.getOrNull(iContrasena).orEmpty()
            val totp = v(iTotp).let { t -> if (t.isEmpty()) null else Totp.deUri(t)?.clave ?: Totp.limpiar(t).takeIf { Totp.valido(it) }?.let { Totp.Clave(it) } }
            if (contrasena.isEmpty() && totp == null) { omitidas++; return@mapNotNull null }
            val sitios = v(iUrl).split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }.map(Sitios::normalizar).distinct()
            val nombre = v(iNombre).ifBlank { sitios.firstOrNull()?.let(Sitios::visible) ?: v(iUsuario) }
            Entrada(
                tipo = Tipo.CONTRASENA, nombre = nombre, usuario = v(iUsuario), contrasena = contrasena,
                sitios = sitios, totp = totp, notas = r.getOrNull(iNotas).orEmpty().trim(), creada = ahora,
            )
        }
        return Leido(entradas, omitidas)
    }

    private fun bitwardenJson(o: JSONObject): Leido {
        val items = o.getJSONArray("items")
        var omitidas = 0
        val l = (0 until items.length()).mapNotNull { i ->
            val it = items.getJSONObject(i)
            val login = it.optJSONObject("login")
            if (it.optInt("type", 1) != 1 || login == null) { omitidas++; return@mapNotNull null }
            val uris = login.optJSONArray("uris")?.let { a -> (0 until a.length()).mapNotNull { a.getJSONObject(it).optString("uri").takeIf { u -> u.isNotBlank() } } }.orEmpty()
            val totp = login.optString("totp").takeIf { it.isNotBlank() && it != "null" }?.let { t -> Totp.deUri(t)?.clave ?: Totp.limpiar(t).takeIf { Totp.valido(it) }?.let { Totp.Clave(it) } }
            Entrada(
                nombre = it.optString("name"), usuario = login.optString("username").takeIf { it != "null" }.orEmpty(),
                contrasena = login.optString("password").takeIf { it != "null" }.orEmpty(), sitios = uris.map(Sitios::normalizar), totp = totp,
                notas = it.optString("notes").takeIf { it != "null" }.orEmpty(),
            )
        }
        return Leido(l, omitidas)
    }

    // ---------- Códigos ----------

    private fun deCodigo(l: Totp.Leido) = Entrada(tipo = Tipo.CODIGO, nombre = l.emisor.ifBlank { l.cuenta }, usuario = l.cuenta, totp = l.clave)

    fun codigosDe(l: List<Totp.Leido>) = l.map(::deCodigo)

    private fun otpauth(lineas: List<String>): Leido {
        val leidos = mutableListOf<Totp.Leido>()
        var omitidas = 0
        for (l in lineas) {
            when {
                Totp.esMigracion(l) -> Totp.deMigracion(l)?.let { leidos += it.codigos; omitidas += it.omitidos } ?: omitidas++
                else -> Totp.deUri(l)?.let { leidos += it } ?: omitidas++
            }
        }
        return Leido(leidos.map(::deCodigo), omitidas)
    }

    private fun aegis(o: JSONObject): Leido {
        val db = o.opt("db")
        if (db is String) throw Error.Cifrado("Aegis")
        val lista = (db as? JSONObject)?.optJSONArray("entries") ?: throw Error.Formato()
        var omitidas = 0
        val l = (0 until lista.length()).mapNotNull { i ->
            val e = lista.getJSONObject(i)
            val info = e.optJSONObject("info")
            if (e.optString("type") != "totp" || info == null) { omitidas++; return@mapNotNull null }
            val secreto = Totp.limpiar(info.optString("secret"))
            if (!Totp.valido(secreto)) { omitidas++; return@mapNotNull null }
            Entrada(
                tipo = Tipo.CODIGO, nombre = e.optString("issuer").ifBlank { e.optString("name") }, usuario = e.optString("name"),
                totp = Totp.Clave(secreto, info.optString("algo", "SHA1").uppercase(), info.optInt("digits", 6), info.optInt("period", 30)),
                notas = e.optString("note"),
            )
        }
        return Leido(l, omitidas)
    }

    private fun dosFas(o: JSONObject): Leido {
        val servicios = o.optJSONArray("services") ?: JSONArray()
        if (servicios.length() == 0 && o.optString("servicesEncrypted").isNotBlank()) throw Error.Cifrado("2FAS")
        var omitidas = 0
        val l = (0 until servicios.length()).mapNotNull { i ->
            val s = servicios.getJSONObject(i)
            val otp = s.optJSONObject("otp") ?: JSONObject()
            val tipo = otp.optString("tokenType", "TOTP").uppercase()
            val secreto = Totp.limpiar(s.optString("secret"))
            if (tipo != "TOTP" || !Totp.valido(secreto)) { omitidas++; return@mapNotNull null }
            Entrada(
                tipo = Tipo.CODIGO, nombre = otp.optString("issuer").ifBlank { s.optString("name") }, usuario = otp.optString("account"),
                totp = Totp.Clave(secreto, otp.optString("algorithm", "SHA1").uppercase(), otp.optInt("digits", 6), otp.optInt("period", 30)),
            )
        }
        return Leido(l, omitidas)
    }

    // ---------- Juntar con lo que ya hay ----------

    /** Quita lo que ya está (mismo tipo, usuario, contraseña y sitios o misma clave 2FA). */
    fun nuevas(leidas: List<Entrada>, actuales: List<Entrada>): List<Entrada> {
        fun huella(e: Entrada) = listOf(e.tipo.id, e.nombre.lowercase(), e.usuario.lowercase(), e.contrasena, e.totp?.secreto.orEmpty(), e.sitios.map(Sitios::visible).sorted().joinToString()).joinToString("\u0000")
        val hay = actuales.map(::huella).toMutableSet()
        val ids = actuales.map { it.id }.toSet()
        return leidas.filter { e -> (e.id !in ids || Boveda.entrada(e.id)?.cambiada?.let { it < e.cambiada } == true) && hay.add(huella(e)) }
    }

    // ---------- Exportación cifrada de la Bóveda ----------

    private fun clave(contrasena: CharArray, sal: ByteArray, iteraciones: Int) =
        SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(contrasena, sal, iteraciones, 256)).encoded, "AES")

    /**
     * Todo, documentos incluidos, en un .siosboveda cifrado con la contraseña que se elija
     * (PBKDF2-SHA256 con 600 000 vueltas y AES-256-GCM). Sin esa contraseña no se puede abrir.
     */
    fun exportar(ctx: Context, destino: Uri, contrasena: CharArray) {
        val entradas = Boveda.entradas.value
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("boveda.json"))
            z.write(JSONObject().put("version", 1).put("creada", System.currentTimeMillis()).put("entradas", JSONArray().apply { entradas.forEach { put(it.json()) } }).toString().toByteArray())
            z.closeEntry()
            for (e in entradas) for (a in e.archivos) {
                val datos = runCatching { Archivos.leer(ctx, a) }.getOrNull() ?: continue
                z.putNextEntry(ZipEntry("archivos/${a.id}")); z.write(datos); z.closeEntry()
                datos.fill(0)
            }
        }
        val claro = zip.toByteArray()
        val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val vueltas = java.nio.ByteBuffer.allocate(4).putInt(ITERACIONES).array()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, clave(contrasena, sal, ITERACIONES), GCMParameterSpec(128, iv)) }
        c.updateAAD(MAGIA + vueltas)
        val cifrado = c.doFinal(claro)
        claro.fill(0)
        ctx.contentResolver.openOutputStream(destino, "w")!!.use { it.write(MAGIA + vueltas + sal + iv + cifrado) }
    }

    fun descifrar(bytes: ByteArray, contrasena: CharArray): Leido {
        if (!esExportacion(bytes) || bytes.size < MAGIA.size + 4 + 28) throw Error.Formato()
        var i = MAGIA.size
        val vueltasB = bytes.copyOfRange(i, i + 4); i += 4
        val vueltas = java.nio.ByteBuffer.wrap(vueltasB).int.takeIf { it in 10_000..10_000_000 } ?: throw Error.Formato()
        val sal = bytes.copyOfRange(i, i + 16); i += 16
        val iv = bytes.copyOfRange(i, i + 12); i += 12
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, clave(contrasena, sal, vueltas), GCMParameterSpec(128, iv)) }
        c.updateAAD(MAGIA + vueltasB)
        val claro = try { c.doFinal(bytes, i, bytes.size - i) } catch (e: javax.crypto.AEADBadTagException) { throw Error.Contrasena() }
        var json: JSONObject? = null
        val archivos = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(claro)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when {
                    e.name == "boveda.json" -> json = JSONObject(String(z.readBytes()))
                    e.name.startsWith("archivos/") && !e.name.contains("..") -> archivos[e.name.removePrefix("archivos/")] = z.readBytes()
                }
            }
        }
        claro.fill(0)
        val lista = json?.optJSONArray("entradas") ?: throw Error.Formato()
        return Leido((0 until lista.length()).mapNotNull { runCatching { Entrada.de(lista.getJSONObject(it)) }.getOrNull() }, 0, archivos)
    }

    /** Guarda lo leído en la Bóveda (abierta): las entradas nuevas y sus archivos. Devuelve cuántas. */
    fun guardar(ctx: Context, l: Leido): Int {
        val nuevas = nuevas(l.entradas, Boveda.entradas.value)
        for (e in nuevas) for (a in e.archivos) l.archivos[a.id]?.let { Archivos.guardarBytes(ctx, a.id, it) }
        Boveda.guardarVarias(nuevas)
        l.archivos.values.forEach { it.fill(0) }
        return nuevas.size
    }
}
