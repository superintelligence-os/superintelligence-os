package com.sios.sistema

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sios.R
import com.sios.ui.BotonPrimario
import com.sios.ui.Colores
import com.sios.ui.SimboloSiosAnimado
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import org.json.JSONObject

/**
 * «Buscar el móvil» desde el ordenador: suena la alarma a todo volumen (aunque esté en silencio o
 * en no molestar: es una alarma), vibra y enseña «Aquí estoy» encima de todo, hasta que se toca o
 * pasa un minuto. Al acabar, el volumen vuelve a como estaba.
 */
object Sonar {
    private var reproductor: MediaPlayer? = null
    private var volumenAntes = -1
    private val principal = Handler(Looper.getMainLooper())
    private val parar = Runnable { callar(null) }

    fun empezar(ctx: Context, de: String): JSONObject {
        val app = ctx.applicationContext
        principal.post {
            if (reproductor == null) {
                val audio = app.getSystemService(AudioManager::class.java)
                volumenAntes = audio.getStreamVolume(AudioManager.STREAM_ALARM)
                audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                reproductor = runCatching {
                    MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                        setDataSource(app, RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                        isLooping = true
                        prepare()
                        start()
                    }
                }.getOrNull()
                runCatching {
                    app.getSystemService(VibratorManager::class.java).defaultVibrator
                        .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                }
            }
            principal.removeCallbacks(parar)
            principal.postDelayed(parar, 60_000)
            app.startActivity(Intent(app, SonarActivity::class.java).putExtra("de", de).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        return JSONObject().put("sonando", true)
    }

    fun callar(actividad: SonarActivity?) {
        principal.removeCallbacks(parar)
        reproductor?.runCatching { stop(); release() }
        reproductor = null
        actividad?.let { a ->
            runCatching { a.getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }
            if (volumenAntes >= 0) a.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volumenAntes, 0)
            a.finish()
        } ?: activa?.let { callar(it) }
        volumenAntes = -1
    }

    @Volatile var activa: SonarActivity? = null
}

class SonarActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Sonar.activa = this
        val de = intent.getStringExtra("de").orEmpty()
        setContent {
            TemaSios {
                Box(Modifier.fillMaxSize().background(Colores.Fondo).navigationBarsPadding().padding(28.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // El símbolo de SIOS se dibuja como al arrancar y luego suelta ondas, como un sónar.
                        SimboloSiosAnimado(Modifier.size(168.dp), ondas = true)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.sonar_titulo), style = Texto.Titulo.copy(fontSize = 30.sp), color = Colores.Texto)
                        Text(stringResource(R.string.sonar_texto, de), style = Texto.Cuerpo, color = Colores.Texto2, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        BotonPrimario(stringResource(R.string.sonar_parar), Modifier.fillMaxWidth()) { Sonar.callar(this@SonarActivity) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (Sonar.activa === this) Sonar.activa = null
        super.onDestroy()
    }
}
