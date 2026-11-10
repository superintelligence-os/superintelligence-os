package com.sios.boveda

import android.app.Activity
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import com.sios.R

/**
 * Abrir la Bóveda: la huella (biometría fuerte) o el PIN/patrón del móvil, con el cifrador de la
 * clave dentro del BiometricPrompt (CryptoObject). Sin esa autenticación, el Keystore no la suelta.
 */
object Desbloqueo {
    sealed class Fallo {
        object Cancelado : Fallo()
        object SinBloqueo : Fallo()
        object Invalidada : Fallo()
        class Otro(val mensaje: String?) : Fallo()
    }

    fun pedir(act: Activity, subtitulo: String? = null, onAbierta: () -> Unit, onFallo: (Fallo) -> Unit) {
        if (Boveda.abierta()) { onAbierta(); return }
        val p = try {
            Boveda.preparar(act)
        } catch (e: Boveda.SinBloqueo) {
            onFallo(Fallo.SinBloqueo); return
        } catch (e: Boveda.Invalidada) {
            onFallo(Fallo.Invalidada); return
        } catch (e: Exception) {
            onFallo(Fallo.Otro(e.message)); return
        }
        BiometricPrompt.Builder(act)
            .setTitle(act.getString(R.string.bv_prompt_titulo))
            .setSubtitle(subtitulo ?: act.getString(R.string.bv_prompt_sub))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .setConfirmationRequired(false)
            .build()
            .authenticate(BiometricPrompt.CryptoObject(p.cifrador), CancellationSignal(), act.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                    val cifrador = r.cryptoObject?.cipher ?: p.cifrador
                    Thread {
                        val error = runCatching { Boveda.completar(act, p, cifrador) }.exceptionOrNull()
                        act.runOnUiThread { if (error == null) onAbierta() else onFallo(Fallo.Otro(error.message)) }
                    }.start()
                }

                override fun onAuthenticationError(codigo: Int, mensaje: CharSequence?) {
                    if (codigo == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED || codigo == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) onFallo(Fallo.Cancelado)
                    else onFallo(Fallo.Otro(mensaje?.toString()))
                }
            })
    }
}
