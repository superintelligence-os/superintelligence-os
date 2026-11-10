package com.sios.sistema

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import com.sios.R
import org.json.JSONObject

/**
 * «Enviar al Mac» en la hoja de compartir (solo con la continuidad activa): un enlace se abre en el
 * Mac, un texto llega a su portapapeles y un archivo (hasta 25 MB) a sus Descargas. Sin pantalla:
 * un aviso breve y listo.
 */
class EnviarAlMacActivity : Activity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val app = applicationContext
        val texto = i.getStringExtra(Intent.EXTRA_TEXT)?.trim()
        val archivo = i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        Thread {
            val r = runCatching {
                when {
                    archivo != null -> {
                        val nombre = contentResolver.query(archivo, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "archivo"
                        val datos = contentResolver.openInputStream(archivo)?.use { it.readBytes() } ?: error("sin datos")
                        if (datos.size > 25 * 1024 * 1024) return@runCatching R.string.mac_grande
                        Continuidad.enviar("/continuidad/archivo", JSONObject().put("nombre", nombre).put("datos", android.util.Base64.encodeToString(datos, android.util.Base64.NO_WRAP)))
                    }
                    texto != null && Regex("^https?://\\S+$").matches(texto) -> Continuidad.enviar("/continuidad/evento", JSONObject().put("tipo", "enlace").put("url", texto))
                    texto != null -> Continuidad.enviar("/continuidad/evento", JSONObject().put("tipo", "portapapeles").put("texto", texto).put("avisar", true))
                    else -> null
                }?.let { if (it.optBoolean("enviado")) R.string.mac_enviado else R.string.mac_sin_mac } ?: R.string.mac_error
            }.getOrDefault(R.string.mac_error)
            runOnUiThread { Toast.makeText(app, app.getString(r), Toast.LENGTH_SHORT).show() }
        }.start()
        finish()
    }
}
