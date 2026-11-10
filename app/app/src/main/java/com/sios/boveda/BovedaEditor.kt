package com.sios.boveda

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sios.R
import com.sios.ui.BotonIcono
import com.sios.ui.BotonPrimario
import com.sios.ui.BotonSecundario
import com.sios.ui.BotonTexto
import com.sios.ui.Colores
import com.sios.ui.Formas
import com.sios.ui.Iconos
import com.sios.ui.InterruptorSios
import com.sios.ui.Muelle
import com.sios.ui.Texto
import com.sios.ui.pulsable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** La barra del editor: atrás, el título y «Guardar» (apagado mientras no se puede). */
@Composable
private fun BarraEditor(titulo: String, puede: Boolean, onAtras: () -> Unit, onGuardar: () -> Unit) {
    val alfa by animateFloatAsState(if (puede) 1f else 0.4f, Muelle.suave(), label = "guardar")
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        BotonIcono(Iconos.Atras, stringResource(R.string.comun_atras), Modifier.align(Alignment.CenterStart).padding(start = 8.dp), onClick = onAtras)
        Text(titulo, style = Texto.Subtitulo, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(horizontal = 110.dp))
        Text(
            stringResource(R.string.bv_guardar), style = Texto.Subtitulo, color = Colores.Acento,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp).graphicsLayer { alpha = alfa }.clip(Formas.Pequena)
                .pulsable { if (puede) onGuardar() }.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Editor(d: Destino.Editor, nav: Navegacion, avisos: Avisador) {
    val ctx = LocalContext.current
    val original = remember(d) { d.id?.let { Boveda.entrada(it) } ?: d.plantilla ?: Entrada(tipo = d.tipo) }
    val tipo = original.tipo
    var nombre by rememberSaveable { mutableStateOf(original.nombre) }
    var usuario by rememberSaveable { mutableStateOf(original.usuario) }
    var contrasena by rememberSaveable { mutableStateOf(original.contrasena) }
    var webs by rememberSaveable { mutableStateOf(original.sitios.filterNot(Sitios::esApp).joinToString("\n") { Sitios.visible(it) }) }
    val apps = remember { mutableStateListOf<String>().apply { addAll(original.sitios.filter(Sitios::esApp)) } }
    var secreto by rememberSaveable { mutableStateOf(original.totp?.secreto.orEmpty()) }
    var digitos by rememberSaveable { mutableStateOf(original.totp?.digitos ?: 6) }
    var periodo by rememberSaveable { mutableStateOf(original.totp?.periodo ?: 30) }
    var algoritmo by rememberSaveable { mutableStateOf(original.totp?.algoritmo ?: "SHA1") }
    var masOpciones by rememberSaveable { mutableStateOf(original.totp?.let { it.digitos != 6 || it.periodo != 30 || it.algoritmo != "SHA1" } ?: false) }
    var notas by rememberSaveable { mutableStateOf(original.notas) }
    var categoria by rememberSaveable { mutableStateOf(original.categoria.ifEmpty { Categoria.DNI.id }) }
    var numero by rememberSaveable { mutableStateOf(original.numero) }
    var caduca by rememberSaveable { mutableStateOf(original.caduca) }
    val archivos = remember { mutableStateListOf<Archivo>().apply { addAll(original.archivos) } }
    // Páginas añadidas en esta edición: si se descarta, se borran (ya estaban cifradas en el disco).
    val anadidos = remember { mutableStateListOf<String>() }
    var generador by remember { mutableStateOf(false) }
    var descartar by remember { mutableStateOf(false) }
    var leyendo by remember { mutableStateOf(false) }

    // Un «otpauth://» pegado en el campo de la clave: se reparte solo.
    fun ponerSecreto(s: String) {
        val l = Totp.deUri(s)
        if (l != null) {
            secreto = l.clave.secreto; digitos = l.clave.digitos; periodo = l.clave.periodo; algoritmo = l.clave.algoritmo
            if (nombre.isBlank()) nombre = l.emisor.ifBlank { l.cuenta }
            if (usuario.isBlank()) usuario = l.cuenta
        } else secreto = s
    }
    val secretoValido = secreto.isBlank() || Totp.valido(secreto)
    val puede = when (tipo) {
        Tipo.CONTRASENA -> (nombre.isNotBlank() || webs.isNotBlank()) && (contrasena.isNotEmpty() || usuario.isNotBlank() || secreto.isNotBlank()) && secretoValido
        Tipo.CODIGO -> nombre.isNotBlank() && secreto.isNotBlank() && secretoValido
        Tipo.DOCUMENTO -> nombre.isNotBlank() || archivos.isNotEmpty() || numero.isNotBlank()
    }

    fun construir(): Entrada {
        val ahora = System.currentTimeMillis()
        val totp = Totp.limpiar(secreto).takeIf { it.isNotEmpty() && Totp.valido(it) }?.let { Totp.Clave(it, algoritmo, digitos, periodo) }
        val sitios = webs.lines().map { it.trim() }.filter { it.isNotEmpty() }.map(Sitios::normalizar) + apps
        val base = original.copy(
            nombre = nombre.trim().ifBlank { sitios.firstOrNull()?.let(Sitios::visible).orEmpty() },
            usuario = usuario.trim(), sitios = sitios.distinct(), totp = totp, notas = notas.trim(),
            categoria = if (tipo == Tipo.DOCUMENTO) categoria else "", numero = numero.trim(), caduca = caduca.trim(),
            archivos = archivos.toList(), cambiada = ahora,
        )
        return if (d.id == null) base.copy(contrasena = contrasena, contrasenaCambiada = ahora, creada = ahora) else base.conContrasena(contrasena, ahora).copy(cambiada = ahora)
    }
    val cambiado = construir().let { n ->
        n.nombre != original.nombre || n.usuario != original.usuario || n.contrasena != original.contrasena || n.sitios != original.sitios ||
            n.totp != original.totp || n.notas != original.notas || n.numero != original.numero || n.caduca != original.caduca ||
            n.archivos != original.archivos || (tipo == Tipo.DOCUMENTO && n.categoria != original.categoria)
    }
    nav.antesDeCerrar = if (cambiado || anadidos.isNotEmpty()) ({ descartar = true; true }) else null
    DisposableEffect(Unit) { onDispose { nav.antesDeCerrar = null } }

    fun guardar() {
        val n = construir()
        Boveda.guardar(n)
        // Las páginas quitadas, fuera del disco.
        original.archivos.filter { a -> n.archivos.none { it.id == a.id } }.forEach { Archivos.borrar(ctx, it.id) }
        anadidos.clear()
        if (d.id == null) nav.reemplazar(Destino.Ficha(n.id)) else nav.cerrar(forzar = true)
        avisos.decir(ctx.getString(R.string.bv_guardado))
    }

    val elegirArchivo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        leyendo = true
        Thread {
            val a = runCatching { Archivos.desdeUri(ctx, uri) }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                leyendo = false
                if (a != null) { archivos.add(a); anadidos.add(a.id) } else avisos.decir(ctx.getString(R.string.bv_archivo_no))
            }
        }.start()
    }

    val titulo = stringResource(
        when {
            d.id != null -> R.string.bv_editar
            tipo == Tipo.CODIGO -> R.string.bv_nuevo_codigo
            tipo == Tipo.DOCUMENTO -> R.string.bv_nuevo_documento
            else -> R.string.bv_nueva_contrasena
        },
    )
    Column(Modifier.fillMaxSize().background(Colores.Fondo).statusBarsPadding()) {
        BarraEditor(titulo, puede, onAtras = { nav.cerrar() }, onGuardar = ::guardar)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (tipo == Tipo.DOCUMENTO) Column {
                EtiquetaGrupo(stringResource(R.string.bv_categoria), Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in Categoria.entries) {
                        val esta = c.id == categoria
                        val borde by animateColorAsState(if (esta) Colores.Acento else Colores.Borde, Muelle.suave(), label = "cat")
                        val fondo by animateColorAsState(if (esta) Colores.AcentoSuave else Color.Transparent, Muelle.suave(), label = "catf")
                        Row(
                            Modifier.clip(Formas.Pastilla).background(fondo).border(1.dp, borde, Formas.Pastilla).pulsable {
                                val antes = Categoria.de(categoria)
                                categoria = c.id
                                if (nombre.isBlank() || nombre == ctx.getString(nombreCategoria(antes))) nombre = ctx.getString(nombreCategoria(c))
                            }.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(iconoCategoria(c), null, tint = if (esta) Colores.Acento else Colores.Texto3, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(nombreCategoria(c)), style = Texto.Pequeno, color = if (esta) Colores.Texto else Colores.Texto2)
                        }
                    }
                }
            }

            Campo(
                stringResource(if (tipo == Tipo.CODIGO) R.string.bv_servicio else R.string.bv_nombre), nombre, { nombre = it },
                pista = stringResource(if (tipo == Tipo.DOCUMENTO) R.string.bv_nombre_pista_doc else R.string.bv_nombre_pista),
                mayusculas = KeyboardCapitalization.Sentences,
            )

            if (tipo != Tipo.DOCUMENTO) {
                Campo(
                    stringResource(if (tipo == Tipo.CODIGO) R.string.bv_cuenta else R.string.bv_usuario), usuario, { usuario = it },
                    pista = stringResource(R.string.bv_usuario_pista), teclado = KeyboardType.Email,
                )
            }

            if (tipo == Tipo.CONTRASENA) {
                Column {
                    Campo(stringResource(R.string.bv_contrasena), contrasena, { contrasena = it }, secreto = true, mono = true) {
                        BotonIcono(IconosBoveda.Dados, stringResource(R.string.bv_generar), tam = 40.dp, color = Colores.Acento) { generador = true }
                    }
                    AnimatedVisibility(contrasena.isNotEmpty(), enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
                        MedidorFuerza(contrasena, Modifier.padding(top = 10.dp, start = 2.dp))
                    }
                    if (contrasena.isEmpty()) Row(Modifier.padding(top = 10.dp)) {
                        PastillaAccion(stringResource(R.string.bv_generar_una), IconosBoveda.Dados, acento = true) { generador = true }
                    }
                }
                Campo(stringResource(R.string.bv_webs), webs, { webs = it }, pista = stringResource(R.string.bv_webs_pista), teclado = KeyboardType.Uri, lineas = 2)
                if (apps.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.bv_apps_unidas), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(start = 2.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (a in apps.toList()) Row(
                            Modifier.clip(Formas.Pastilla).background(Colores.Superficie2).padding(start = 12.dp, end = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(BovedaAutofill.etiquetaApp(ctx, a.removePrefix(Sitios.APP)), style = Texto.Pequeno, color = Colores.Texto2, maxLines = 1)
                            BotonIcono(Iconos.Cruz, stringResource(R.string.bv_quitar), tam = 32.dp, color = Colores.Texto3) { apps.remove(a) }
                        }
                    }
                }
            }

            if (tipo != Tipo.DOCUMENTO) Column(Modifier.animateContentSize(Muelle.suave())) {
                Campo(
                    stringResource(if (tipo == Tipo.CODIGO) R.string.bv_clave_secreta else R.string.bv_clave_2fa), secreto, ::ponerSecreto,
                    pista = stringResource(R.string.bv_clave_pista), mono = true, mayusculas = KeyboardCapitalization.Characters,
                ) {
                    BotonIcono(IconosBoveda.Qr, stringResource(R.string.bv_escanear_qr), tam = 40.dp, color = Colores.Acento) {
                        nav.abrir(Destino.Camara(ModoCamara.QR) { r ->
                            val t = (r as? ResultadoCamara.Texto)?.texto.orEmpty()
                            if (Totp.deUri(t) != null) ponerSecreto(t) else avisos.decir(ctx.getString(R.string.bv_qr_no_vale))
                        })
                    }
                }
                if (!secretoValido) Text(stringResource(R.string.bv_clave_no_vale), style = Texto.Pequeno, color = Colores.Rojo, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
                else if (secreto.isNotBlank() && tipo == Tipo.CONTRASENA || tipo == Tipo.CODIGO) Text(
                    stringResource(R.string.bv_clave_nota), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(top = 8.dp, start = 2.dp),
                )
                if (secreto.isNotBlank() || tipo == Tipo.CODIGO) {
                    if (!masOpciones) Text(
                        stringResource(R.string.bv_mas_opciones), style = Texto.Pequeno.copy(fontWeight = FontWeight.SemiBold), color = Colores.Acento,
                        modifier = Modifier.padding(top = 4.dp).clip(Formas.Pequena).pulsable { masOpciones = true }.padding(horizontal = 2.dp, vertical = 8.dp),
                    )
                    AnimatedVisibility(masOpciones, enter = fadeIn(Muelle.suave()) + expandVertically(Muelle.suave()), exit = fadeOut(Muelle.suave()) + shrinkVertically(Muelle.suave())) {
                        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            OpcionCodigo(stringResource(R.string.bv_digitos), listOf("6", "8"), digitos.toString()) { digitos = it.toInt() }
                            OpcionCodigo(stringResource(R.string.bv_periodo), listOf("30 s", "60 s"), "$periodo s") { periodo = it.removeSuffix(" s").toInt() }
                            OpcionCodigo(stringResource(R.string.bv_algoritmo), listOf("SHA1", "SHA256", "SHA512"), algoritmo) { algoritmo = it }
                        }
                    }
                }
            }

            if (tipo == Tipo.DOCUMENTO) {
                Campo(stringResource(R.string.bv_numero), numero, { numero = it }, pista = stringResource(R.string.bv_numero_pista), mono = true, mayusculas = KeyboardCapitalization.Characters)
                Campo(stringResource(R.string.bv_caducidad), caduca, { caduca = it }, pista = stringResource(R.string.bv_caducidad_pista), teclado = KeyboardType.Number)
                Column {
                    EtiquetaGrupo(stringResource(R.string.bv_paginas), Modifier.padding(top = 4.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (a in archivos.toList()) PaginaEditable(a) { archivos.remove(a); if (a.id in anadidos) { anadidos.remove(a.id); Archivos.borrar(ctx, a.id) } }
                        if (leyendo) Box(Modifier.size(120.dp, 76.dp).clip(RoundedCornerShape(12.dp)).background(Colores.Superficie), contentAlignment = Alignment.Center) {
                            Text("…", style = Texto.Subtitulo, color = Colores.Texto3)
                        }
                    }
                    Row(Modifier.padding(top = if (archivos.isEmpty() && !leyendo) 0.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PastillaAccion(stringResource(R.string.bv_hacer_foto), Iconos.Camara, acento = true) {
                            nav.abrir(Destino.Camara(ModoCamara.FOTO) { r ->
                                val b = (r as? ResultadoCamara.Foto)?.imagen
                                if (b != null) {
                                leyendo = true
                                Thread {
                                    val a = runCatching { Archivos.desdeFoto(ctx, b, "foto.jpg") }.getOrNull()
                                    android.os.Handler(android.os.Looper.getMainLooper()).post { leyendo = false; if (a != null) { archivos.add(a); anadidos.add(a.id) } }
                                }.start()
                                }
                            })
                        }
                        PastillaAccion(stringResource(R.string.bv_elegir_archivo), IconosBoveda.Subir) { elegirArchivo.launch(arrayOf("image/*", "application/pdf")) }
                    }
                }
            }

            Campo(stringResource(R.string.bv_notas), notas, { notas = it }, lineas = 3, mayusculas = KeyboardCapitalization.Sentences)
        }
    }

    if (generador) HojaGenerador(onCerrar = { generador = false }) { contrasena = it; generador = false }
    if (descartar) Hoja({ descartar = false }) {
        Text(stringResource(R.string.bv_descartar_titulo), style = Texto.Subtitulo, color = Colores.Texto)
        Text(stringResource(R.string.bv_descartar_nota), style = Texto.Cuerpo, color = Colores.Texto2)
        BotonPrimario(stringResource(R.string.bv_seguir_editando), Modifier.fillMaxWidth()) { descartar = false }
        BotonTexto(stringResource(R.string.bv_descartar), Modifier.fillMaxWidth()) {
            descartar = false
            anadidos.forEach { Archivos.borrar(ctx, it) }
            anadidos.clear()
            nav.cerrar(forzar = true)
        }
    }
}

@Composable
private fun OpcionCodigo(titulo: String, opciones: List<String>, elegida: String, onElegir: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(titulo, style = Texto.Pequeno, color = Colores.Texto2, modifier = Modifier.width(96.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Pestanas(opciones, opciones.indexOf(elegida).coerceAtLeast(0), { onElegir(opciones[it]) }, Modifier.weight(1f))
    }
}

@Composable
private fun PaginaEditable(a: Archivo, onQuitar: () -> Unit) {
    val ctx = LocalContext.current
    val img by produceState<ImageBitmap?>(null, a.id) { value = withContext(Dispatchers.IO) { Archivos.imagen(ctx, a, 360)?.asImageBitmap() } }
    Box(Modifier.size(120.dp, 76.dp).clip(RoundedCornerShape(12.dp)).background(Colores.Superficie), contentAlignment = Alignment.Center) {
        if (img == null) Icon(if (a.esPdf) Iconos.Documento else IconosBoveda.Imagen, null, tint = Colores.Texto3, modifier = Modifier.size(24.dp))
        ImagenSuave(img, Modifier.fillMaxSize())
        BotonIcono(Iconos.Cruz, stringResource(R.string.bv_quitar), Modifier.align(Alignment.TopEnd).padding(4.dp), tam = 28.dp, color = Color.White, fondo = Color.Black.copy(alpha = 0.45f), onClick = onQuitar)
    }
}

// ——— Generador de contraseñas ———

/** El generador: al azar o con palabras, con la longitud y lo que lleva; lo elegido se recuerda. */
@Composable
internal fun HojaGenerador(onCerrar: () -> Unit, onUsar: ((String) -> Unit)?) {
    Hoja(onCerrar) { GeneradorUi(onUsar ?: {}, usar = onUsar != null) }
}

@Composable
internal fun GeneradorUi(onUsar: (String) -> Unit, usar: Boolean = true) {
    val ctx = LocalContext.current
    val p = remember { Boveda.prefs(ctx) }
    var palabras by remember { mutableStateOf(p.getBoolean("gen_palabras", false)) }
    var largo by remember { mutableStateOf(p.getInt("gen_largo", 20).toFloat()) }
    var cuantas by remember { mutableStateOf(p.getInt("gen_cuantas", 5).toFloat()) }
    var mayus by remember { mutableStateOf(p.getBoolean("gen_mayus", true)) }
    var numeros by remember { mutableStateOf(p.getBoolean("gen_numeros", true)) }
    var simbolos by remember { mutableStateOf(p.getBoolean("gen_simbolos", true)) }
    var vuelta by remember { mutableStateOf(0) }
    val generada = remember(palabras, largo.toInt(), cuantas.toInt(), mayus, numeros, simbolos, vuelta) {
        p.edit().putBoolean("gen_palabras", palabras).putInt("gen_largo", largo.toInt()).putInt("gen_cuantas", cuantas.toInt())
            .putBoolean("gen_mayus", mayus).putBoolean("gen_numeros", numeros).putBoolean("gen_simbolos", simbolos).apply()
        if (palabras) Generador.frase(ctx, cuantas.toInt()) else Generador.aleatoria(largo.toInt(), mayus, numeros, simbolos)
    }
    Text(stringResource(R.string.bv_generador), style = Texto.Subtitulo, color = Colores.Texto)
    Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(16.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.CenterStart) {
            androidx.compose.animation.Crossfade(generada, animationSpec = Muelle.suave(), label = "generada") { g -> ContrasenaColor(g, tam = 18) }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MedidorFuerza(generada, Modifier.weight(1f))
            BotonIcono(Iconos.Refrescar, stringResource(R.string.bv_otra), tam = 40.dp, color = Colores.Texto2) { vuelta++ }
        }
    }
    Pestanas(listOf(stringResource(R.string.bv_gen_azar), stringResource(R.string.bv_gen_palabras)), if (palabras) 1 else 0, { palabras = it == 1 })
    val colores = SliderDefaults.colors(thumbColor = Colores.Acento, activeTrackColor = Colores.Acento, inactiveTrackColor = Colores.Superficie2, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
    Column(Modifier.fillMaxWidth().clip(Formas.Tarjeta).background(Colores.Superficie).padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize(Muelle.suave())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (palabras) R.string.bv_gen_cuantas else R.string.bv_gen_largo), style = Texto.Cuerpo, color = Colores.Texto, modifier = Modifier.weight(1f))
            Text(if (palabras) cuantas.toInt().toString() else largo.toInt().toString(), style = Texto.Subtitulo, color = Colores.Texto)
        }
        if (palabras) Slider(cuantas, { cuantas = it }, valueRange = 3f..10f, steps = 6, colors = colores)
        else Slider(largo, { largo = it }, valueRange = 8f..64f, colors = colores)
        if (!palabras) {
            InterruptorFila(stringResource(R.string.bv_gen_mayus), mayus) { mayus = it }
            InterruptorFila(stringResource(R.string.bv_gen_numeros), numeros) { numeros = it }
            InterruptorFila(stringResource(R.string.bv_gen_simbolos), simbolos) { simbolos = it }
        } else Text(stringResource(R.string.bv_gen_palabras_nota), style = Texto.Pequeno, color = Colores.Texto3, modifier = Modifier.padding(bottom = 4.dp))
    }
    if (usar) BotonPrimario(stringResource(R.string.bv_usar), Modifier.fillMaxWidth()) { onUsar(generada) }
    else BotonSecundario(stringResource(R.string.bv_copiar), Modifier.fillMaxWidth()) { onUsar(generada) }
}

@Composable
internal fun InterruptorFila(titulo: String, activo: Boolean, nota: String? = null, onCambio: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, style = Texto.Cuerpo, color = Colores.Texto)
            if (nota != null) Text(nota, style = Texto.Pequeno, color = Colores.Texto3)
        }
        Spacer(Modifier.width(12.dp))
        InterruptorSios(activo, onCambio)
    }
}
