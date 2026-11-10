package com.sios.boveda

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/*
 * Lo que se guarda en la Bóveda: contraseñas (con su código 2FA si lo tienen), códigos 2FA sueltos y
 * documentos. Cada entrada se guarda como un JSON dentro de la base cifrada; los archivos de los
 * documentos van aparte, cifrados también (ver Archivos.kt).
 */

enum class Tipo(val id: String) {
    CONTRASENA("contrasena"),
    CODIGO("codigo"),
    DOCUMENTO("documento");

    companion object {
        fun de(id: String) = entries.firstOrNull { it.id == id } ?: CONTRASENA
    }
}

/** Una página de un documento (foto o PDF), cifrada en su propio archivo. */
data class Archivo(val id: String, val mime: String, val nombre: String, val tam: Long) {
    val esPdf get() = mime == "application/pdf"

    fun json(): JSONObject = JSONObject().put("id", id).put("mime", mime).put("nombre", nombre).put("tam", tam)

    companion object {
        fun de(o: JSONObject) = Archivo(o.getString("id"), o.optString("mime", "image/jpeg"), o.optString("nombre"), o.optLong("tam"))
    }
}

/** Una contraseña anterior (por si el autocompletar guardó una nueva y hay que volver atrás). */
data class Anterior(val contrasena: String, val hasta: Long)

data class Entrada(
    val id: String = UUID.randomUUID().toString(),
    val tipo: Tipo = Tipo.CONTRASENA,
    val nombre: String = "",
    val usuario: String = "",
    val contrasena: String = "",
    /** Webs («https://example.com») y apps («androidapp://com.example»), como en Bitwarden. */
    val sitios: List<String> = emptyList(),
    val totp: Totp.Clave? = null,
    val notas: String = "",
    // Documentos
    val categoria: String = "",
    val numero: String = "",
    /** Caducidad tal como se escribe en el documento («05/2031» o «12/05/2031»). */
    val caduca: String = "",
    val archivos: List<Archivo> = emptyList(),
    val creada: Long = System.currentTimeMillis(),
    val cambiada: Long = creada,
    val contrasenaCambiada: Long = creada,
    val anteriores: List<Anterior> = emptyList(),
) {
    /** El sitio principal, para enseñarlo bajo el nombre (sin «https://» ni «www.»). */
    val sitioVisible: String?
        get() = sitios.firstOrNull()?.let { Sitios.visible(it) }

    /** Copia con una contraseña nueva: la anterior pasa al historial (las cinco últimas). */
    fun conContrasena(nueva: String, ahora: Long = System.currentTimeMillis()): Entrada {
        if (nueva == contrasena) return this
        val historial = if (contrasena.isEmpty()) anteriores else (listOf(Anterior(contrasena, ahora)) + anteriores).take(5)
        return copy(contrasena = nueva, contrasenaCambiada = ahora, anteriores = historial, cambiada = ahora)
    }

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("tipo", tipo.id).put("nombre", nombre)
        .put("usuario", usuario).put("contrasena", contrasena)
        .put("sitios", JSONArray(sitios))
        .put("totp", totp?.json())
        .put("notas", notas)
        .put("categoria", categoria).put("numero", numero).put("caduca", caduca)
        .put("archivos", JSONArray().apply { archivos.forEach { put(it.json()) } })
        .put("creada", creada).put("cambiada", cambiada).put("contrasenaCambiada", contrasenaCambiada)
        .put("anteriores", JSONArray().apply { anteriores.forEach { put(JSONObject().put("c", it.contrasena).put("h", it.hasta)) } })

    companion object {
        fun de(o: JSONObject): Entrada {
            fun lista(n: String) = o.optJSONArray(n)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val creada = o.optLong("creada", System.currentTimeMillis())
            return Entrada(
                id = o.getString("id"),
                tipo = Tipo.de(o.optString("tipo")),
                nombre = o.optString("nombre"),
                usuario = o.optString("usuario"),
                contrasena = o.optString("contrasena"),
                sitios = lista("sitios"),
                totp = o.optJSONObject("totp")?.let { Totp.Clave.de(it) },
                notas = o.optString("notas"),
                categoria = o.optString("categoria"),
                numero = o.optString("numero"),
                caduca = o.optString("caduca"),
                archivos = o.optJSONArray("archivos")?.let { a -> (0 until a.length()).map { Archivo.de(a.getJSONObject(it)) } }.orEmpty(),
                creada = creada,
                cambiada = o.optLong("cambiada", creada),
                contrasenaCambiada = o.optLong("contrasenaCambiada", creada),
                anteriores = o.optJSONArray("anteriores")?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it) }.map { Anterior(it.optString("c"), it.optLong("h")) }
                }.orEmpty(),
            )
        }
    }
}

/** Categorías de documento, con la clave que se guarda (no cambia con el idioma). */
enum class Categoria(val id: String) {
    DNI("dni"), PASAPORTE("pasaporte"), CONDUCIR("conducir"), TARJETA("tarjeta"),
    SEGURO("seguro"), SALUD("salud"), OTRO("otro");

    companion object {
        fun de(id: String) = entries.firstOrNull { it.id == id } ?: OTRO
    }
}

/** Webs y apps: cómo se escriben, cómo se enseñan y cuándo una entrada vale para lo que se rellena. */
object Sitios {
    const val APP = "androidapp://"

    fun app(paquete: String) = APP + paquete

    fun esApp(s: String) = s.startsWith(APP)

    /** El nombre del servidor de una web escrita a mano o importada («Example.com/login» → «example.com»). */
    fun host(s: String): String? {
        if (esApp(s)) return null
        val t = s.trim()
        if (t.isEmpty()) return null
        val conEsquema = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
        return runCatching { java.net.URI(conEsquema.replace(" ", "%20")).host }.getOrNull()?.lowercase()?.removePrefix("www.")?.takeIf { it.contains('.') || it == "localhost" }
    }

    /**
     * El dominio registrable («cuentas.banco.co.uk» → «banco.co.uk»), con la lista de sufijos públicos
     * que trae OkHttp. Si no estuviera disponible, una aproximación: los dos últimos trozos (tres con
     * los segundos niveles habituales de los países, como «co.uk» o «com.es»).
     */
    fun raiz(host: String): String {
        val h = host.lowercase().removePrefix("www.")
        runCatching { okhttp3.HttpUrl.Builder().scheme("https").host(h).build().topPrivateDomain() }.getOrNull()?.let { return it }
        val p = h.split('.')
        if (p.size <= 2 || h.all { it.isDigit() || it == '.' }) return h
        val segundo = p[p.size - 2]
        val tres = p.last().length == 2 && segundo in setOf("co", "com", "org", "net", "gov", "gob", "edu", "ac", "nom", "ne", "or")
        return p.takeLast(if (tres) 3 else 2).joinToString(".")
    }

    fun visible(s: String): String = if (esApp(s)) s.removePrefix(APP) else host(s) ?: s.trim()

    /** ¿Vale este sitio para lo que se está rellenando (una app o una web)? */
    fun casa(sitio: String, paquete: String?, dominio: String?): Boolean {
        if (esApp(sitio)) return paquete != null && sitio.removePrefix(APP) == paquete
        val h = host(sitio) ?: return false
        val d = dominio?.lowercase()?.removePrefix("www.") ?: return false
        return h == d || raiz(h) == raiz(d)
    }

    /** Lo que se guarda al escribir una web a mano: con «https://» y sin espacios. */
    fun normalizar(s: String): String {
        val t = s.trim()
        if (t.isEmpty() || esApp(t)) return t
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) t else "https://$t"
    }
}
