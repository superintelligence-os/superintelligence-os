package com.sios.sistema

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.RecoverySystem
import android.telephony.SubscriptionManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Encontrar mi móvil: lo que hace el móvil cuando lo piden desde SIOS Desktop (en casa o fuera, por
 * el servidor intermedio cifrado). Modo perdido con mensaje en la pantalla de bloqueo, borrado a
 * distancia y aviso si cambian la SIM. El estado vive en el almacenamiento accesible antes de
 * desbloquear: un móvil perdido que se reinicia vuelve a enseñar su mensaje nada más encenderse.
 */
object Antirrobo {
    private fun prefs(ctx: Context) =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("antirrobo", Context.MODE_PRIVATE)

    fun enPerdido(ctx: Context) = prefs(ctx).getBoolean("perdido", false)
    fun mensaje(ctx: Context) = prefs(ctx).getString("mensaje", "").orEmpty()
    fun telefono(ctx: Context) = prefs(ctx).getString("telefono", "").orEmpty()

    /** Modo perdido: guarda el mensaje y lo enseña encima del bloqueo (o lo quita). */
    fun perdido(ctx: Context, si: Boolean, mensaje: String, telefono: String): JSONObject {
        prefs(ctx).edit().putBoolean("perdido", si).putString("mensaje", mensaje).putString("telefono", telefono).apply()
        if (si) mostrar(ctx) else PerdidoActivity.activa?.finish()
        return JSONObject().put("ok", true)
    }

    fun mostrar(ctx: Context) {
        ctx.startActivity(Intent(ctx, PerdidoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    /** Al encender: si estaba perdido, el mensaje vuelve a salir antes incluso de desbloquear. */
    fun alArrancar(ctx: Context) {
        if (enPerdido(ctx)) runCatching { mostrar(ctx) }
    }

    /**
     * Borrar todo y volver al estado de fábrica. Se ha pedido escribiendo BORRAR en el ordenador; se
     * deja un momento para que la respuesta llegue antes de reiniciar.
     */
    fun borrar(ctx: Context): JSONObject {
        Thread {
            Thread.sleep(3000)
            runCatching { RecoverySystem.rebootWipeUserData(ctx.applicationContext) }
                .onFailure { android.util.Log.e("SistemaSios", "No se pudo borrar el móvil", it) }
        }.start()
        return JSONObject().put("ok", true)
    }

    /** Las SIM que hay puestas (operador y país), sin números completos. */
    fun sim(ctx: Context): JSONObject {
        val lista = JSONArray()
        runCatching {
            ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty().forEach { s ->
                lista.put(JSONObject().put("operador", s.carrierName?.toString().orEmpty()).put("pais", s.countryIso.orEmpty()).put("ranura", s.simSlotIndex))
            }
        }
        return JSONObject().put("sims", lista).put("huella", huellaSim(ctx))
    }

    /** Identifica las SIM puestas sin guardar sus números: huella de sus ICCID. */
    private fun huellaSim(ctx: Context): String = runCatching {
        val ids = ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
            .map { it.iccId.orEmpty() + "|" + it.carrierId }.sorted().joinToString(",")
        MessageDigest.getInstance("SHA-256").digest(ids.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }.getOrDefault("")

    /**
     * Vigila las SIM: si cambian respecto a las que había (la sacan, ponen otra), avisa al ordenador
     * con la ubicación al momento. La primera vez solo apunta las que hay.
     */
    fun vigilarSim(ctx: Context) {
        val app = ctx.applicationContext
        runCatching {
            app.getSystemService(SubscriptionManager::class.java).addOnSubscriptionsChangedListener(
                app.mainExecutor,
                object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() {
                        val ahora = huellaSim(app)
                        val antes = prefs(app).getString("huella_sim", null)
                        prefs(app).edit().putString("huella_sim", ahora).apply()
                        if (antes != null && antes != ahora) {
                            com.sios.datos.Servicio.avisoAntirrobo("sim", sim(app))
                        }
                    }
                },
            )
        }.onFailure { android.util.Log.w("SistemaSios", "No se pueden vigilar las SIM", it) }
    }
}

/** Encima del bloqueo: «Este móvil se ha perdido», el mensaje del dueño y un botón para llamarle. */
class PerdidoActivity : ComponentActivity() {
    companion object {
        @Volatile var activa: PerdidoActivity? = null
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    /** El dueño ha demostrado que lo es (PIN, patrón o huella): ya puede quitar el modo perdido. */
    private val dueno = mutableStateOf(false)

    /**
     * «Soy el dueño». Con el móvil bloqueado, se le pide desbloquearlo. Si ya estaba desbloqueado
     * (o el bloqueo no puede quitarse desde aquí), se le pide el PIN o la huella igualmente: si no,
     * cualquiera que lo encuentre podría quitarlo. Sin bloqueo de pantalla no hay nada que pedir.
     */
    private fun comprobarDueno() {
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { dueno.value = true }
                override fun onDismissError() { pedirCredencial() }
            })
        } else pedirCredencial()
    }

    private fun pedirCredencial() {
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) { dueno.value = true; return }
        runCatching {
            android.hardware.biometrics.BiometricPrompt.Builder(this)
                .setTitle(getString(R.string.perdido_confirmar))
                .setAllowedAuthenticators(
                    android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
                .build()
                .authenticate(android.os.CancellationSignal(), mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { dueno.value = true }
                })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activa = this
        // Mientras está perdido, «atrás» no la quita: solo el dueño (desbloqueando) o el ordenador.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() {} })
        val mensaje = Antirrobo.mensaje(this)
        val telefono = Antirrobo.telefono(this)
        setContent {
            TemaSios {
                val dueno by this@PerdidoActivity.dueno
                val latido = rememberInfiniteTransition(label = "latido")
                val brillo by latido.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "brillo")
                Box(
                    Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding().padding(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.size(96.dp).alpha(brillo), contentAlignment = Alignment.Center) {
                            com.sios.ui.Sios(Modifier.size(84.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.perdido_titulo), style = Texto.Titulo.copy(fontSize = 28.sp), color = Colores.Texto, textAlign = TextAlign.Center)
                        Text(
                            mensaje.ifBlank { stringResource(R.string.perdido_texto_defecto) },
                            style = Texto.Cuerpo.copy(fontSize = 18.sp), color = Colores.Texto2, textAlign = TextAlign.Center,
                        )
                        if (telefono.isNotBlank()) Text(telefono, style = Texto.Titulo.copy(fontSize = 22.sp), color = Colores.Texto)
                        Spacer(Modifier.height(10.dp))
                        if (telefono.isNotBlank()) {
                            // SIOS es el teléfono del sistema: puede llamar al dueño sin desbloquear.
                            BotonPrimario(stringResource(R.string.perdido_llamar), Modifier.fillMaxWidth()) {
                                runCatching {
                                    startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(telefono))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }
                            }
                        }
                        AnimatedVisibility(dueno, enter = fadeIn(Muelle.suave()) + slideInVertically(Muelle.suave()) { it / 3 }) {
                            BotonPrimario(stringResource(R.string.perdido_quitar), Modifier.fillMaxWidth()) {
                                Antirrobo.perdido(this@PerdidoActivity, false, "", "")
                                com.sios.datos.Servicio.avisoAntirrobo("encontrado", JSONObject())
                                finish()
                            }
                        }
                        if (!dueno) BotonTexto(stringResource(R.string.perdido_soy_dueno), Modifier.fillMaxWidth()) { comprobarDueno() }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (activa === this) activa = null
        super.onDestroy()
    }
}
