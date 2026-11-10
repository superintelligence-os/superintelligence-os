package com.sios.boveda

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.CreatePasswordResponse
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PasswordCredential
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePasswordCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.sios.R
import com.sios.ui.Marca

/**
 * La Bóveda como proveedor de contraseñas del Credential Manager (Android 14+): las apps que usan
 * «Iniciar sesión con contraseña guardada» ven sus cuentas y pueden guardar nuevas. Las llaves de
 * acceso (passkeys) no: ver la nota del informe (no hay copia ni sincronización que las proteja).
 */
@RequiresApi(34)
class BovedaCredenciales : CredentialProviderService() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Marca.envolver(base))

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val opcion = request.beginGetCredentialOptions.filterIsInstance<BeginGetPasswordOption>().firstOrNull()
        val paquete = request.callingAppInfo?.packageName
        if (opcion == null || !Boveda.existe(this) || paquete == packageName) return callback.onResult(BeginGetCredentialResponse())
        if (!Boveda.abierta()) {
            val i = Intent(this, BovedaCredencialesActivity::class.java).putExtra(BovedaCredencialesActivity.EXTRA_MODO, BovedaCredencialesActivity.MODO_ABRIR)
            val pi = PendingIntent.getActivity(this, 7401, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return callback.onResult(BeginGetCredentialResponse(authenticationActions = listOf(AuthenticationAction(getString(R.string.bv_cm_abrir), pi))))
        }
        callback.onResult(respuesta(this, paquete, opcion))
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (request !is BeginCreatePasswordCredentialRequest || request.callingAppInfo?.packageName == packageName) {
            return callback.onResult(BeginCreateCredentialResponse())
        }
        val i = Intent(this, BovedaCredencialesActivity::class.java).putExtra(BovedaCredencialesActivity.EXTRA_MODO, BovedaCredencialesActivity.MODO_CREAR)
        val pi = PendingIntent.getActivity(this, 7402, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val entrada = CreateEntry.Builder(getString(R.string.bv_nombre_proveedor), pi)
            .setDescription(getString(R.string.bv_cm_guardar_nota))
            .setIcon(Icon.createWithResource(this, R.drawable.ic_boveda))
            .build()
        callback.onResult(BeginCreateCredentialResponse(createEntries = listOf(entrada)))
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    companion object {
        /** Las cuentas de esa app, para el selector del sistema (solo nombres; la contraseña va al elegir). */
        fun respuesta(ctx: Context, paquete: String?, opcion: BeginGetPasswordOption): BeginGetCredentialResponse {
            val cuentas = Boveda.coincidencias(paquete, null).filter { it.contrasena.isNotEmpty() }.take(10)
            val entradas = cuentas.mapIndexed { n, e ->
                val i = Intent(ctx, BovedaCredencialesActivity::class.java)
                    .putExtra(BovedaCredencialesActivity.EXTRA_MODO, BovedaCredencialesActivity.MODO_USAR)
                    .putExtra(BovedaCredencialesActivity.EXTRA_ID, e.id)
                val pi = PendingIntent.getActivity(ctx, 7500 + n, i, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                PasswordCredentialEntry.Builder(ctx, e.usuario.ifBlank { e.nombre }, pi, opcion)
                    .setDisplayName(e.nombre)
                    .setIcon(Icon.createWithResource(ctx, R.drawable.ic_boveda))
                    .build()
            }
            return BeginGetCredentialResponse(credentialEntries = entradas)
        }
    }
}

/** Huella y respuesta para el Credential Manager (transparente, con FLAG_SECURE). */
@RequiresApi(34)
class BovedaCredencialesActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODO = "modo"
        const val EXTRA_ID = "id"
        const val MODO_ABRIR = "abrir"
        const val MODO_USAR = "usar"
        const val MODO_CREAR = "crear"
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (savedInstanceState != null) { finish(); return }
        val modo = intent.getStringExtra(EXTRA_MODO)
        Desbloqueo.pedir(
            this,
            getString(if (modo == MODO_CREAR) R.string.bv_prompt_guardar else R.string.bv_prompt_rellenar),
            onAbierta = { seguir(modo) },
            onFallo = { setResult(RESULT_CANCELED); finish() },
        )
    }

    private fun seguir(modo: String?) {
        val resultado = Intent()
        when (modo) {
            MODO_ABRIR -> {
                val peticion = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
                val opcion = peticion?.beginGetCredentialOptions?.filterIsInstance<BeginGetPasswordOption>()?.firstOrNull()
                if (peticion == null || opcion == null) { setResult(RESULT_CANCELED); finish(); return }
                PendingIntentHandler.setBeginGetCredentialResponse(resultado, BovedaCredenciales.respuesta(this, peticion.callingAppInfo?.packageName, opcion))
            }
            MODO_USAR -> {
                val e = intent.getStringExtra(EXTRA_ID)?.let { Boveda.entrada(it) }
                if (e == null) PendingIntentHandler.setGetCredentialException(resultado, GetCredentialUnknownException(getString(R.string.bv_cm_no_esta)))
                else PendingIntentHandler.setGetCredentialResponse(resultado, GetCredentialResponse(PasswordCredential(e.usuario.ifBlank { e.nombre }, e.contrasena)))
            }
            MODO_CREAR -> {
                val peticion = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
                val pedida = peticion?.callingRequest as? CreatePasswordRequest
                if (pedida == null) {
                    PendingIntentHandler.setCreateCredentialException(resultado, CreateCredentialUnknownException())
                } else {
                    val paquete = peticion.callingAppInfo.packageName
                    Pendientes.guardar(this, Pendiente(paquete, null, pedida.id, pedida.password, BovedaAutofill.etiquetaApp(this, paquete)))
                    PendingIntentHandler.setCreateCredentialResponse(resultado, CreatePasswordResponse())
                    android.widget.Toast.makeText(this, R.string.bv_af_guardada, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            else -> { setResult(RESULT_CANCELED); finish(); return }
        }
        setResult(RESULT_OK, resultado)
        finish()
    }

    override fun onStop() {
        super.onStop()
        if (Boveda.minutosCierre(this) <= 0 && !isChangingConfigurations) Boveda.cerrar()
    }
}
