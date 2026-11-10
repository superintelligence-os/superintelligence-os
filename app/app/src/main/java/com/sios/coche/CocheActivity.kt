package com.sios.coche

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sios.R
import com.sios.sistema.AsistenteActivity
import com.sios.sistema.Coche
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.InterruptorSios
import com.sios.ui.Muelle
import com.sios.ui.TemaSios
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Pantalla de conducción: grande, tranquila y con lo justo. Se cierra sola al desconectar el
 * Bluetooth del coche (si se abrió por él) o con la «×».
 */
class CocheActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(com.sios.ui.Marca.envolver(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setContent {
            TemaSios {
                val activo by Coche.activo.collectAsStateWithLifecycle()
                var visto by remember { mutableStateOf(false) }
                LaunchedEffect(activo) { if (activo) visto = true else if (visto) finish() }
                PantallaCoche(onSalir = { Coche.salir(); finish() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Coche.activo.value) Coche.abrir(this)
    }
}

@Composable
private fun PantallaCoche(onSalir: () -> Unit) {
    val ctx = LocalContext.current
    var ahora by remember { mutableStateOf(LocalDateTime.now()) }
    var sonando by remember { mutableStateOf(Coche.sonando(ctx)) }
    var leer by remember { mutableStateOf(Coche.leer(ctx)) }
    val favoritos = remember { Coche.favoritos(ctx) }
    LaunchedEffect(Unit) {
        while (true) {
            ahora = LocalDateTime.now()
            sonando = Coche.sonando(ctx)
            delay(1_000)
        }
    }
    // Entrada: sube y aparece con muelle.
    val entrada = remember { Animatable(0f) }
    LaunchedEffect(Unit) { entrada.animateTo(1f, Muelle.suave()) }

    Column(
        Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding().navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .graphicsLayer { alpha = entrada.value; translationY = (1f - entrada.value) * 40.dp.toPx() },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Hora, fecha y el coche conectado.
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(ahora.format(DateTimeFormatter.ofPattern(if (android.text.format.DateFormat.is24HourFormat(ctx)) "H:mm" else "h:mm")), style = Texto.Titulo.copy(fontSize = 56.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp), color = Colores.Texto)
                val fecha = ahora.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)).substringBeforeLast(" de ")
                Text(
                    Coche.conectado?.let { stringResource(R.string.coche_conectado, fecha, it) } ?: fecha,
                    style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier.size(52.dp).clip(Formas.Pastilla).background(Colores.Superficie).pulsable(onSalir),
                contentAlignment = Alignment.Center,
            ) { Icon(Iconos.Cruz, stringResource(R.string.coche_salir), tint = Colores.Texto2, modifier = Modifier.size(22.dp)) }
        }

        // Ir a casa / al trabajo.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Destino(Iconos.Casa, stringResource(R.string.coche_casa), Coche.casa(ctx), Modifier.weight(1f))
            Destino(Iconos.Maletin, stringResource(R.string.coche_trabajo), Coche.trabajo(ctx), Modifier.weight(1f))
        }

        // Música.
        Column(
            Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Tarjeta).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AnimatedContent(sonando?.let { it.titulo to it.artista }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "cancion") { cancion ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).clip(Formas.Pequena).background(Colores.AcentoSuave), contentAlignment = Alignment.Center) {
                        Icon(Iconos.Musica, null, tint = Colores.Acento, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cancion?.first ?: stringResource(R.string.coche_sin_musica), style = Texto.Subtitulo.copy(fontSize = 18.sp), color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            cancion?.second?.ifBlank { null } ?: sonando?.app ?: stringResource(R.string.coche_sin_musica_nota),
                            style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BotonMusica(Iconos.Anterior, Modifier.weight(1f)) { Coche.multimedia(ctx, "anterior") }
                BotonMusica(if (sonando?.reproduciendo == true) Iconos.Pausa else Iconos.Reproducir, Modifier.weight(1.4f), principal = true) {
                    Coche.multimedia(ctx, "play")
                }
                BotonMusica(Iconos.Siguiente, Modifier.weight(1f)) { Coche.multimedia(ctx, "siguiente") }
            }
        }

        // Llamar a favoritos.
        if (favoritos.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                favoritos.forEach { f -> Favorito(f, Modifier.weight(1f)) }
                repeat(4 - favoritos.size) { Spacer(Modifier.weight(1f)) }
            }
        } else {
            // Sin favoritos todavía: la app de teléfono, a un toque.
            Row(
                Modifier.fillMaxWidth().height(72.dp).clip(Formas.Tarjeta).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Tarjeta)
                    .pulsable { ctx.startActivity(Intent(ctx, com.sios.telefono.TelefonoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Iconos.Llamar, null, tint = Colores.Acento, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.coche_llamar), style = Texto.Subtitulo.copy(fontSize = 18.sp), color = Colores.Texto)
                    Text(stringResource(R.string.coche_llamar_nota), style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Leer mensajes en voz alta.
        Row(
            Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Iconos.Altavoz, null, tint = Colores.Texto2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.coche_leer), style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.weight(1f))
            InterruptorSios(leer) { leer = it; Coche.ponerLeer(ctx, it) }
        }

        // Hablar con Claude: el botón más grande de la pantalla.
        Row(
            Modifier.fillMaxWidth().height(76.dp).clip(Formas.Pastilla).background(Colores.Acento)
                .pulsable {
                    ctx.startActivity(Intent(ctx, AsistenteActivity::class.java).putExtra(AsistenteActivity.EXTRA_VOZ, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Iconos.Micro, null, tint = Colores.SobreAcento, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.coche_hablar), style = Texto.Subtitulo.copy(fontSize = 19.sp), color = Colores.SobreAcento)
        }
    }
}

@Composable
private fun Destino(icono: ImageVector, nombre: String, direccion: String, modifier: Modifier) {
    val ctx = LocalContext.current
    Column(
        modifier.height(112.dp).clip(Formas.Tarjeta).background(Colores.Superficie).border(1.dp, Colores.Borde, Formas.Tarjeta)
            .pulsable { if (direccion.isBlank()) abrirAjustesCoche(ctx) else navegar(ctx, direccion) }
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icono, null, tint = Colores.Acento, modifier = Modifier.size(26.dp))
        Column {
            Text(nombre, style = Texto.Subtitulo.copy(fontSize = 18.sp), color = Colores.Texto, maxLines = 1)
            Text(
                direccion.ifBlank { stringResource(R.string.coche_sin_direccion) },
                style = Texto.Pequeno, color = Colores.Texto3, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BotonMusica(icono: ImageVector, modifier: Modifier, principal: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(60.dp).clip(Formas.Pastilla).background(if (principal) Colores.Acento else Colores.Superficie2).pulsable(onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icono, null, tint = if (principal) Colores.SobreAcento else Colores.Texto, modifier = Modifier.size(26.dp)) }
}

@Composable
private fun Favorito(f: Coche.Favorito, modifier: Modifier) {
    val ctx = LocalContext.current
    val foto = remember(f.foto) {
        f.foto?.let { u -> runCatching { ctx.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull() }
    }
    Column(
        modifier.clip(Formas.Tarjeta).pulsable { if (f.numero.isNotBlank()) com.sios.telefono.llamar(ctx, f.numero) }.padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp).clip(Formas.Pastilla).background(Colores.Superficie2), contentAlignment = Alignment.Center) {
            if (foto != null) Image(foto.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(f.nombre.take(1).uppercase(), style = Texto.Subtitulo.copy(fontSize = 22.sp), color = Colores.Texto)
        }
        Spacer(Modifier.height(6.dp))
        Text(f.nombre.substringBefore(" "), style = Texto.Pequeno, color = Colores.Texto2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun navegar(ctx: Context, destino: String) {
    val q = Uri.encode(destino)
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

private fun abrirAjustesCoche(ctx: Context) {
    ctx.startActivity(Intent(ctx, com.sios.ajustes.AjustesActivity::class.java).putExtra("seccion", "coche").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
