package com.sios.boveda

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.sios.R
import java.util.concurrent.ConcurrentHashMap

/**
 * El autocompletar de Android con la Bóveda: en apps y webs propone las cuentas de ese sitio y, al
 * entrar con una cuenta nueva, ofrece guardarla. Con la Bóveda cerrada, la propuesta es «Abrir la
 * Bóveda» (huella o PIN) y después salen las cuentas. Nada de esto sale del móvil.
 */
class BovedaAutofill : AutofillService() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val estructura = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        val campos = Analisis.de(estructura)
        // Nunca en la propia SIOS (ni en el editor de la Bóveda) ni si no hay nada que rellenar.
        if (campos.paquete == packageName || !campos.hayAlgo || !Boveda.existe(this)) return callback.onSuccess(null)
        callback.onSuccess(runCatching { Respuestas.para(this, campos, estructura) }.getOrNull())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val estructura = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess()
        val campos = Analisis.de(estructura, conValores = true)
        val contrasena = campos.valorContrasena
        if (campos.paquete == packageName || contrasena.isNullOrEmpty()) return callback.onSuccess()
        val pendiente = Pendiente(campos.paquete, campos.dominio, campos.valorUsuario.orEmpty(), contrasena, etiquetaApp(this, campos.paquete))
        if (Boveda.abierta()) {
            Pendientes.guardar(this, pendiente)
            android.widget.Toast.makeText(this, getString(R.string.bv_af_guardada), android.widget.Toast.LENGTH_SHORT).show()
            return callback.onSuccess()
        }
        // Cerrada: la huella primero (en una ventanita), luego se guarda.
        val id = Pendientes.poner(pendiente)
        val i = Intent(this, BovedaAutofillActivity::class.java).putExtra(BovedaAutofillActivity.EXTRA_GUARDAR, id)
        callback.onSuccess(PendingIntent.getActivity(this, id.hashCode(), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT).intentSender)
    }

    companion object {
        fun etiquetaApp(ctx: Context, paquete: String): String = runCatching {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(paquete, 0)).toString()
        }.getOrDefault(paquete)
    }
}

/** Lo que el autocompletar quiere guardar mientras se pide la huella (solo en memoria, unos minutos). */
class Pendiente(val paquete: String, val dominio: String?, val usuario: String, val contrasena: String, val nombreApp: String, val cuando: Long = System.currentTimeMillis())

object Pendientes {
    private val lista = ConcurrentHashMap<String, Pendiente>()

    fun poner(p: Pendiente): String {
        lista.entries.removeIf { System.currentTimeMillis() - it.value.cuando > 5 * 60_000 }
        val id = java.util.UUID.randomUUID().toString()
        lista[id] = p
        return id
    }

    fun sacar(id: String): Pendiente? = lista.remove(id)

    /** Guarda en la Bóveda (abierta): si ya hay una cuenta con ese usuario en ese sitio, le cambia la contraseña. */
    fun guardar(ctx: Context, p: Pendiente): Entrada {
        val existente = Boveda.coincidencias(p.paquete, p.dominio).firstOrNull { it.tipo == Tipo.CONTRASENA && it.usuario.equals(p.usuario, ignoreCase = true) }
        val e = existente?.conContrasena(p.contrasena) ?: Entrada(
            tipo = Tipo.CONTRASENA,
            nombre = p.dominio?.let { Sitios.raiz(it) } ?: p.nombreApp,
            usuario = p.usuario,
            contrasena = p.contrasena,
            sitios = listOfNotNull(p.dominio?.let { "https://$it" } ?: Sitios.app(p.paquete)),
        )
        Boveda.guardar(e)
        return e
    }
}

/** Los campos de la pantalla que se va a rellenar: usuario, contraseña y código 2FA; y de quién es. */
class Campos(
    val paquete: String,
    val dominio: String?,
    val usuario: List<AutofillId>,
    val contrasena: List<AutofillId>,
    val otp: List<AutofillId>,
    val valorUsuario: String?,
    val valorContrasena: String?,
) {
    val hayAlgo get() = contrasena.isNotEmpty() || otp.isNotEmpty() || usuario.isNotEmpty()
    val todos get() = (usuario + contrasena + otp).distinct()
}

object Analisis {
    private enum class Clase { USUARIO, CONTRASENA, OTP, TEXTO, NADA }

    private val P_USUARIO = Regex("user|usuario|login|e-?mail|correo|account|cuenta|benutzer|identifiant|utente|nome de usu|phone|tel[eé]fono", RegexOption.IGNORE_CASE)
    private val P_CONTRASENA = Regex("pass|contrase|clave|passwort|mot de passe|senha|pwd|pin\\b", RegexOption.IGNORE_CASE)
    private val P_OTP = Regex("otp|one.?time|2fa|totp|mfa|verification|verificaci|c[oó]digo|code|token|tan\\b", RegexOption.IGNORE_CASE)
    private val P_NO = Regex("search|buscar|captcha|promo|coupon|cup[oó]n|newsletter", RegexOption.IGNORE_CASE)

    fun de(e: AssistStructure, conValores: Boolean = false): Campos {
        val paquete = e.activityComponent?.packageName.orEmpty()
        var dominio: String? = null
        val usuario = mutableListOf<AutofillId>()
        val contrasena = mutableListOf<AutofillId>()
        val otp = mutableListOf<AutofillId>()
        var vUsuario: String? = null
        var vContrasena: String? = null
        var ultimoTexto: Pair<AutofillId, String?>? = null

        fun valor(n: AssistStructure.ViewNode) = n.autofillValue?.takeIf { it.isText }?.textValue?.toString()

        fun ir(n: AssistStructure.ViewNode) {
            n.webDomain?.takeIf { it.isNotBlank() && dominio == null }?.let { dominio = it.lowercase().removePrefix("www.") }
            val id = n.autofillId
            if (id != null && n.visibility == View.VISIBLE && n.autofillType == View.AUTOFILL_TYPE_TEXT && n.importantForAutofill != View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS) {
                when (clase(n)) {
                    Clase.USUARIO -> { usuario += id; if (vUsuario == null) vUsuario = valor(n) }
                    Clase.CONTRASENA -> {
                        contrasena += id
                        if (vContrasena.isNullOrEmpty()) vContrasena = valor(n)
                        // Sin campo de usuario reconocido: el de texto de justo antes.
                        if (usuario.isEmpty()) ultimoTexto?.let { (u, v) -> usuario += u; vUsuario = v }
                    }
                    Clase.OTP -> otp += id
                    Clase.TEXTO -> ultimoTexto = id to valor(n)
                    Clase.NADA -> {}
                }
            }
            for (i in 0 until n.childCount) ir(n.getChildAt(i))
        }
        for (i in 0 until e.windowNodeCount) ir(e.getWindowNodeAt(i).rootViewNode)
        return Campos(paquete, dominio, usuario.distinct(), contrasena.distinct(), otp.distinct(), vUsuario.takeIf { conValores }, vContrasena.takeIf { conValores })
    }

    private fun clase(n: AssistStructure.ViewNode): Clase {
        // 1. Lo que la app dice que es (autofillHints).
        n.autofillHints?.map { it.lowercase() }?.let { h ->
            when {
                h.any { it.contains("otp") || it.contains("one-time") } -> return Clase.OTP
                h.any { it.contains("password") } -> return Clase.CONTRASENA
                h.any { it.contains("username") || it.contains("email") || it == "phone" } -> return Clase.USUARIO
                h.any { it == "off" || it == "no" } -> return Clase.NADA
            }
        }
        // 2. En las webs, el HTML: <input type="password" autocomplete="…" name="…">.
        n.htmlInfo?.takeIf { it.tag.equals("input", ignoreCase = true) }?.let { html ->
            val a = html.attributes.orEmpty().associate { it.first.lowercase() to it.second.orEmpty() }
            val tipo = a["type"]?.lowercase().orEmpty()
            val ac = a["autocomplete"]?.lowercase().orEmpty()
            val nombre = listOf(a["name"], a["id"], a["placeholder"], a["aria-label"]).joinToString(" ")
            when {
                tipo in setOf("hidden", "submit", "button", "checkbox", "radio", "search", "file", "image") -> return Clase.NADA
                ac.contains("one-time-code") -> return Clase.OTP
                tipo == "password" || ac.contains("current-password") || ac.contains("new-password") -> return Clase.CONTRASENA
                ac.contains("username") || tipo == "email" || ac == "email" -> return Clase.USUARIO
                P_NO.containsMatchIn(nombre) -> return Clase.NADA
                P_OTP.containsMatchIn(nombre) && (tipo == "tel" || tipo == "number" || a["inputmode"] == "numeric") -> return Clase.OTP
                P_USUARIO.containsMatchIn(nombre) -> return Clase.USUARIO
                tipo == "text" || tipo == "tel" || tipo.isEmpty() -> return Clase.TEXTO
            }
        }
        // 3. En las apps: el tipo de teclado, el id del campo y la pista.
        val t = n.inputType
        val clase = t and InputType.TYPE_MASK_CLASS
        val variacion = t and InputType.TYPE_MASK_VARIATION
        val pistas = listOf(n.idEntry, n.hint?.toString(), n.contentDescription?.toString()).joinToString(" ")
        if (clase == InputType.TYPE_CLASS_TEXT && variacion in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            )
        ) return Clase.CONTRASENA
        if (clase == InputType.TYPE_CLASS_NUMBER && variacion == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return if (P_OTP.containsMatchIn(pistas)) Clase.OTP else Clase.CONTRASENA
        }
        if (P_NO.containsMatchIn(pistas)) return Clase.NADA
        if (clase == InputType.TYPE_CLASS_TEXT && variacion in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)) return Clase.USUARIO
        if (clase == InputType.TYPE_CLASS_NUMBER && P_OTP.containsMatchIn(pistas)) return Clase.OTP
        val esEdicion = n.className?.contains("EditText") == true || t != 0
        if (!esEdicion) return Clase.NADA
        if (P_CONTRASENA.containsMatchIn(pistas)) return Clase.CONTRASENA
        if (P_OTP.containsMatchIn(pistas) && clase != InputType.TYPE_CLASS_TEXT) return Clase.OTP
        if (P_USUARIO.containsMatchIn(pistas)) return Clase.USUARIO
        return if (clase == InputType.TYPE_CLASS_TEXT || clase == InputType.TYPE_CLASS_PHONE) Clase.TEXTO else Clase.NADA
    }
}

/** Las respuestas del autocompletar: las cuentas que valen, «Buscar en la Bóveda» y «Abrir la Bóveda». */
object Respuestas {
    private fun fila(ctx: Context, titulo: String, nota: String?): Presentations {
        val v = RemoteViews(ctx.packageName, R.layout.boveda_autofill_fila)
        v.setTextViewText(R.id.bv_titulo, titulo)
        if (nota.isNullOrBlank()) v.setViewVisibility(R.id.bv_nota, View.GONE) else v.setTextViewText(R.id.bv_nota, nota)
        return Presentations.Builder().setMenuPresentation(v).build()
    }

    private fun accion(ctx: Context, modo: String, campos: Campos, codigo: Int): IntentSender {
        val i = Intent(ctx, BovedaAutofillActivity::class.java)
            .putExtra(BovedaAutofillActivity.EXTRA_MODO, modo)
            .putExtra(BovedaAutofillActivity.EXTRA_PAQUETE, campos.paquete)
            .putExtra(BovedaAutofillActivity.EXTRA_DOMINIO, campos.dominio)
        // Mutable: Android añade la estructura de la pantalla (EXTRA_ASSIST_STRUCTURE) al lanzarla.
        return PendingIntent.getActivity(ctx, codigo, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT).intentSender
    }

    /** Un dataset con los valores de una cuenta para los campos que hay. */
    fun dataset(ctx: Context, e: Entrada, campos: Campos): Dataset? {
        val b = Dataset.Builder(fila(ctx, e.nombre.ifBlank { e.sitioVisible ?: "" }, e.usuario.ifBlank { null }))
        var algo = false
        if (e.usuario.isNotEmpty()) for (id in campos.usuario) { b.setField(id, Field.Builder().setValue(AutofillValue.forText(e.usuario)).build()); algo = true }
        if (e.contrasena.isNotEmpty()) for (id in campos.contrasena) { b.setField(id, Field.Builder().setValue(AutofillValue.forText(e.contrasena)).build()); algo = true }
        e.totp?.let { t -> for (id in campos.otp) { b.setField(id, Field.Builder().setValue(AutofillValue.forText(Totp.codigo(t))).build()); algo = true } }
        return if (algo) b.build() else null
    }

    /** La respuesta cuando la Bóveda está abierta. */
    fun abiertas(ctx: Context, campos: Campos): FillResponse {
        val r = FillResponse.Builder()
        val cuentas = Boveda.coincidencias(campos.paquete, campos.dominio).take(8)
        for (e in cuentas) dataset(ctx, e, campos)?.let(r::addDataset)
        // «Buscar en la Bóveda»: para elegir otra cuenta (y que quede unida a esta app o web).
        val buscar = Dataset.Builder(fila(ctx, ctx.getString(R.string.bv_af_buscar), null))
        for (id in campos.todos) buscar.setField(id, Field.Builder().build())
        buscar.setAuthentication(accion(ctx, BovedaAutofillActivity.MODO_ELEGIR, campos, 7302))
        r.addDataset(buscar.build())
        guardarSiHace(r, campos)
        return r.build()
    }

    fun para(ctx: Context, campos: Campos, estructura: AssistStructure): FillResponse {
        if (Boveda.abierta()) return abiertas(ctx, campos)
        val r = FillResponse.Builder()
        r.setAuthentication(
            campos.todos.toTypedArray(),
            accion(ctx, BovedaAutofillActivity.MODO_ABRIR, campos, 7301),
            fila(ctx, ctx.getString(R.string.bv_af_abrir), ctx.getString(R.string.bv_af_abrir_nota)),
        )
        guardarSiHace(r, campos)
        return r.build()
    }

    /** Que Android ofrezca guardar la cuenta al enviar el formulario (si hay contraseña). */
    private fun guardarSiHace(r: FillResponse.Builder, campos: Campos) {
        if (campos.contrasena.isEmpty()) return
        val tipo = SaveInfo.SAVE_DATA_TYPE_PASSWORD or (if (campos.usuario.isNotEmpty()) SaveInfo.SAVE_DATA_TYPE_USERNAME else 0)
        r.setSaveInfo(
            SaveInfo.Builder(tipo, campos.contrasena.toTypedArray())
                .apply { if (campos.usuario.isNotEmpty()) setOptionalIds(campos.usuario.toTypedArray()) }
                .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                .build(),
        )
    }
}
