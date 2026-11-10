package com.sios.boveda

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sios.R
import com.sios.ui.Marca
import com.sios.ui.TemaSios

/**
 * La ventanita del autocompletar: pide la huella para abrir la Bóveda, deja elegir otra cuenta o
 * guarda la que se acaba de usar. Transparente, sobre la app que se está rellenando, y con
 * FLAG_SECURE (no sale en capturas ni en recientes).
 */
class BovedaAutofillActivity : ComponentActivity() {
    companion object {
        const val EXTRA_MODO = "modo"
        const val EXTRA_PAQUETE = "paquete"
        const val EXTRA_DOMINIO = "dominio"
        const val EXTRA_GUARDAR = "guardar"
        const val MODO_ABRIR = "abrir"
        const val MODO_ELEGIR = "elegir"
    }

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Marca.envolver(base))

    private var eligiendo by mutableStateOf(false)
    private var campos: Campos? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        @Suppress("DEPRECATION")
        val estructura = intent.getParcelableExtra<AssistStructure>(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        campos = estructura?.let { Analisis.de(it) }
        setContent {
            TemaSios {
                if (eligiendo) HojaElegir(
                    paquete = campos?.paquete,
                    dominio = campos?.dominio,
                    onElegir = ::elegida,
                    onCerrar = { setResult(RESULT_CANCELED); finish() },
                )
            }
        }
        if (savedInstanceState != null) { if (Boveda.abierta() && intent.getStringExtra(EXTRA_MODO) == MODO_ELEGIR) eligiendo = true else finish(); return }
        val guardar = intent.getStringExtra(EXTRA_GUARDAR)
        val sub = if (guardar != null) getString(R.string.bv_prompt_guardar) else getString(R.string.bv_prompt_rellenar)
        Desbloqueo.pedir(this, sub, onAbierta = { seguir(guardar) }, onFallo = { f ->
            if (f is Desbloqueo.Fallo.SinBloqueo) Toast.makeText(this, R.string.bv_sin_bloqueo, Toast.LENGTH_LONG).show()
            if (f is Desbloqueo.Fallo.Invalidada) Toast.makeText(this, R.string.bv_invalidada_corto, Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
        })
    }

    private fun seguir(guardar: String?) {
        if (guardar != null) {
            Pendientes.sacar(guardar)?.let { Pendientes.guardar(this, it) }
            Toast.makeText(this, R.string.bv_af_guardada, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val c = campos ?: return finish()
        when (intent.getStringExtra(EXTRA_MODO)) {
            MODO_ELEGIR -> eligiendo = true
            else -> {
                setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, Respuestas.abiertas(this, c)))
                finish()
            }
        }
    }

    /** Elegida una cuenta a mano: queda unida a esta app o web (la próxima vez sale sola). */
    private fun elegida(e: Entrada) {
        val c = campos ?: return finish()
        val sitio = c.dominio?.let { "https://$it" } ?: c.paquete.takeIf { it.isNotEmpty() }?.let(Sitios::app)
        val unida = if (sitio != null && e.sitios.none { Sitios.casa(it, c.paquete, c.dominio) }) e.copy(sitios = e.sitios + sitio, cambiada = System.currentTimeMillis()) else e
        if (unida !== e) runCatching { Boveda.guardar(unida) }
        val d = Respuestas.dataset(this, unida, c)
        if (d == null) { setResult(RESULT_CANCELED); finish(); return }
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, d))
        finish()
    }

    override fun onResume() {
        super.onResume()
        Boveda.enPantalla = true
    }

    override fun onPause() {
        super.onPause()
        Boveda.enPantalla = false
    }

    override fun onStop() {
        super.onStop()
        if (Boveda.minutosCierre(this) <= 0 && !isChangingConfigurations) Boveda.cerrar()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        Boveda.tocar()
    }
}
