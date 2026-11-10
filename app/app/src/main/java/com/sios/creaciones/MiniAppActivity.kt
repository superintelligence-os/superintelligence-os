package com.sios.creaciones

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Mini-app hecha por Claude: su página la sirve el servicio (127.0.0.1:8765/apps/<id>/) con el kit
 * de diseño de SIOS. Solo navega dentro del servicio local: nada de webs externas.
 */
class MiniAppActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT), SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
        val fondo = if (com.sios.ui.Apariencia.claro) 0xFFFAF9F5.toInt() else 0xFF262624.toInt()
        val id = intent.getStringExtra(EXTRA_ID)?.filter { it.isLetterOrDigit() || it == '-' } ?: return finish()
        intent.getStringExtra(EXTRA_NOMBRE)?.let { title = it }
        web = WebView(this).apply {
            setBackgroundColor(fondo)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true // sios.guardar / sios.cargar
            // Los errores de JavaScript de la mini-app, al registro (para depurar lo que hace Claude).
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.i("MiniApp", "${m.messageLevel()} ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                    return true
                }
            }
        }
        // El WebView no pasa los márgenes de las barras a la página: los pone el marco (y el teclado).
        val marco = FrameLayout(this).apply {
            setBackgroundColor(fondo)
            addView(web)
        }
        ViewCompat.setOnApplyWindowInsetsListener(marco) { v, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val teclado = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(top = barras.top, bottom = maxOf(barras.bottom, teclado.bottom))
            WindowInsetsCompat.CONSUMED
        }
        setContentView(marco)
        // Atrás vuelve dentro de la mini-app si puede. Con el atrás predictivo de Android 16,
        // onBackPressed() ya no se llama: el aviso se activa solo cuando hay historial, y así sin
        // historial Android hace su animación de volver al inicio.
        val atras = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = web.goBack()
        }
        onBackPressedDispatcher.addCallback(this, atras)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, r: WebResourceRequest) = r.url.host != "127.0.0.1"
            override fun doUpdateVisitedHistory(view: WebView, url: String?, recargar: Boolean) {
                atras.isEnabled = view.canGoBack()
            }
        }
        web.loadUrl("http://127.0.0.1:8765/apps/$id/" + if (com.sios.ui.Apariencia.claro) "?tema=claro" else "")
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NOMBRE = "nombre"
    }
}
