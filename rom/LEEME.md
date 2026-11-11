# SIOS 0.1

GSI de **LineageOS 23.2 (Android 16 QPR2)** remasterizada con un agente de IA integrado en el
sistema: la IA que elija el usuario (Claude, ChatGPT, Gemini, Kimi, Mistral, Grok, cualquier API o un
modelo en el propio móvil). Mientras no se elige ninguna, el sistema es **SIOS**, neutro y en blanco
y negro. Bases: compilaciones no oficiales de MisterZtr, `LineageOS-23.2-20260524-GAPPS-EXT4-GSI` y
`LineageOS-23.2-20260524-VANILLA-EXT4-GSI` (sin servicios de Google), con su MD5 comprobado contra
SourceForge. Las imágenes base no están en el repositorio: se descargan y se dejan en `rom/base/`.

**Estado: probada en el emulador de Android** (con el hardware virtual del emulador en lugar del de un
móvil): arranca, pasa el asistente de LineageOS, muestra la bienvenida de SIOS e instala el agente
de principio a fin. Sin probar todavía en un móvil real.

## Probar en el emulador

```bash
rom/construir.sh                    # monta la ROM
rom/emulador/preparar.sh            # la mete en un emulador «sios» con datos limpios
rom/emulador/arrancar.sh            # arranca (emulator-5556)
rom/emulador/asistente_lineage.sh   # pasa el asistente de LineageOS y deja SIOS como inicio
```

La variante del emulador solo cambia tres cosas, todas para poder manejarlo por ADB: `ro.debuggable=1`,
`ro.adb.secure=0` y adbd escuchando también en vsock y tcp:5555 (además de 127.0.0.1:5559, que prueba el
«solo localhost» de la ROM). El vendor es el del emulador, con su fstab sin product ni system_ext.

## Ediciones

| Edición | Base | Para quién |
| --- | --- | --- |
| **SIOS** (`rom/construir.sh`) | LineageOS 23.2 GAPPS (MindTheGapps): Play Store, servicios de Google | Uso diario: WhatsApp, banca, Maps… con la IA alrededor |
| **SIOS Puro** (`rom/construir.sh --puro`) | LineageOS 23.2 VANILLA, sin Google | Sin Google; apps libres con Neo Store y las de Play con Aurora Store, sin cuenta |

En las dos, la app de SIOS (`com.sios`) es el inicio (sin preguntar: filtro HOME con prioridad 100,
app privilegiada) y el asistente (el overlay de SIOS va con prioridad 2000, por encima del de Google,
1337).

Las apps de Google no se pueden redistribuir: ni el repositorio ni las descargas oficiales llevan
nada de Google. La edición con Google se la construye cada uno con su imagen base GAPPS.

## Aspecto

**Sin IA elegida, SIOS es neutro y monocromo.** La marca es la de SIOS (`ui/Marca.kt`): su símbolo,
el nombre «SIOS» y el blanco hueso #F4F1EA sobre grafito #141413. El tema del sistema sale del hueso
con el estilo MONOCHROMATIC (`theming_defaults` en `overlay-framework/res/values/config.xml`), y la
paleta ya calculada va en `overlay-framework/res/values/paleta.xml`: así el asistente de bienvenida y
todo lo que se pinta antes de SystemUI tiene ya los colores definitivos y no hay saltos de color. El
fondo de pantalla de serie es el de SIOS (`marca/fondos.py`) y la animación de arranque es el símbolo
de SIOS con el espectro de las IA, que se enfría al hueso (`marca/animacion.py`).

**Al elegir una IA, el sistema toma su marca**: su nombre en los textos (la app escribe «Claude» como
comodín y `Marca` lo cambia por el de la IA elegida; sin IA, por «SIOS»), su logo, su color como
semilla de la paleta, su fondo de pantalla, y la cabecera de las notificaciones. Los colores
del sistema los pone `sistema/ColoresSistema.kt` como capa propia encima de la de Android (igual que
SystemUI con la suya): grises neutros con el acento de la IA y, con Claude, su paleta exacta. Así
Ajustes, Mensajes, Contactos, Reloj y cualquier app con colores del sistema siguen a la IA. Sin IA
elegida esa capa se quita y queda la paleta monocroma de serie. En Ajustes se puede fijar otro acento
en lugar del de la IA.

Iconos: pack propio en el inicio (`app/herramientas/pack_iconos.py`: Lucide, ISC, y Simple Icons,
CC0) sobre baldosas grises. Idioma por defecto: español (`ro.product.locale=es-ES`); se elige en la
primera pantalla o en Ajustes y la app lo sigue (español, inglés, francés, alemán, italiano y
portugués).

Letra del sistema: Montserrat (OFL) en lugar de Google Sans, en las familias `google-sans*` y
`variable-*` de `fonts_customization.xml` (Android 16 dibuja con ellas, no con `config_*FontFamily`).
Apps del sistema con los colores de SIOS: `marca/temas_apps.py` genera una capa por app
(Mensajes, Calendario, Archivos, Calculadora, Contactos) a partir de su APK y escribe sus colores como
referencias a la paleta del sistema (`@android:color/system_…`), así que siguen a SIOS en blanco y
negro y, al elegir una IA, a su color. Respeta las apps que limitan qué se puede cambiar
(`<overlayable>`, como Archivos) y permite medidas sueltas, como el relleno de los botones de la
Calculadora.

## Apps y Bluetooth para el agente

- **Apps:** el agente ve cada app con su nombre visible (no solo el paquete) y la abre por nombre. Con
  `buscar_app` e `instalar_app` busca e instala apps libres de F-Droid (con confirmación); las de
  Play Store las abre en su ficha. Lo hace a través del «puente» de la app de SIOS
  (`content://com.sios.puente`, solo para `adb shell`).
- **Bluetooth en la terminal:** `bt estado | encender | apagar | buscar | emparejar | olvidar |
  conectar | desconectar | servicios | leer | escribir` (BLE con UUID cortos, p. ej. 180f/2a19).
  Las consultas no piden permiso; lo que cambia algo, sí. Emparejar muestra el diálogo de Android.

## Tienda y apps de serie

`rom/tienda/descargar.sh` baja de F-Droid, con versión fija, cada APK y comprueba su SHA-256 y su firma
(un solo firmante, con el certificado de la tabla); van tal cual, sin volver a firmar, para que se
actualicen desde su tienda. `rom/tienda/descargar.sh --comprobar` dice si hay versiones nuevas.

- **Neo Store** (las dos ediciones, `/system/priv-app`): tienda libre de F-Droid (y los repositorios que
  se le añadan). Con `INSTALL_PACKAGES` (`privapp-permissions-sios-tienda.xml`) instala y actualiza sin
  preguntar. F-Droid 2.0 ya no usa la «Privileged Extension», y su cliente no pide `INSTALL_PACKAGES`.
- **Solo en Puro** (`/system/app`): Aurora Store (Play sin cuenta de Google), CoMaps (mapas sin conexión
  de OpenStreetMap) y Thunderbird (correo). El navegador sigue siendo Jelly.
- Ajustes de SIOS › Apps: cuál se abre para cada cosa (navegador, correo, mapas, mensajes; se cambia en
  Android) y las tiendas.

## Modo escritorio con un monitor

Al conectar un monitor por USB-C (DisplayPort) o HDMI, el monitor es un ordenador (ventanas, barra de
tareas, ratón y teclado) y el móvil sigue siendo el móvil. Es el escritorio de pantallas conectadas de
Android 16 QPR2: `overlay-framework/res/values/escritorio.xml` (escritorio sí, pero no en la pantalla del
móvil), `persist.wm.debug.desktop_experience_devopts=true` en build.prop y, desde Ajustes de SIOS › Modo
escritorio, las ventanas libres (`enable_freeform_support`) y si el monitor duplica
(`mirror_built_in_display`). En el monitor manda el escritorio de Android (Launcher3: barra de tareas,
recientes y ventanas); el inicio de SIOS se queda en el móvil. Sin cable, «Enviar pantalla» solo duplica.

## Creaciones del agente (widgets, mini-apps y agentes)

El agente crea cosas con un diseño ya hecho (herramientas `crear`, `modificar`, `listar_creaciones`,
`ejecutar_creacion`, `borrar_creacion`; fichas en `~/sios/creaciones`, ver `servicio/creaciones.mjs`):

- **Widgets** del inicio con 4 plantillas fijas (cifra, lista, texto, progreso). Sus datos los pone
  el agente o un comando que se refresca solo (≥5 min) sin gastar suscripción.
- **Mini-apps** en el cajón: HTML con un kit cerrado (paleta y Montserrat de SIOS, cabecera común,
  controles de 48 px, componentes fijos; se quitan `<style>`, `style=` y `<h1>`). Datos guardados en
  el servicio (`sios.guardar/cargar`), `sios.preguntar` (el modelo rápido de la IA elegida; Haiku con
  Claude) y `sios.aviso`.
- **Agentes** en segundo plano: un comando barato cada N min (con `nice` y 30 s máx.); la IA (modelo
  rápido, sin herramientas) solo si la salida cambia y hay prompt. Avisan con notificaciones de SIOS.
- Se cambian con una frase: tocar un widget o mantener una mini-app → «Cambiar con …» (la IA elegida).

## Inicio

Panel de la IA (deslizar a la derecha, como el de widgets del iPhone): lo diseña el agente entero
y se cambia con una frase desde su barra, viendo el cambio en directo bajo la hoja rápida. Sus
tarjetas son widgets con `lugar: "panel"`:
- **Plantillas fijas:** cifra, lista, texto, progreso, noticias, agenda y filas.
- **Diseño libre (`plantilla: "html"`):** el agente escribe su propio HTML, CSS y JS, y recibe los
  datos en `sios.datos`. Se sirve en `/widgets/<id>/` con CSP: imágenes https sí; scripts y
  conexiones externas, no.
- **Fuentes integradas** (`servicio/fuentes.mjs`): noticias de sus intereses (Google News; el modelo
  rápido elige y resume), mi día, calendario, cumpleaños, tiempo, gasolina (Ministerio; la provincia
  sale de la ubicación) y dispositivos en la Wi-Fi o Bluetooth cerca.
- **Otros orígenes:** un comando o un agente.

Al crear o cambiar una tarjeta, el agente espera a sus primeros datos y arregla los errores en el
momento. Tirar hacia abajo refresca el panel; al abrirlo se refresca lo que haga falta.
Rejilla de 2 columnas como la del iPhone: pequeño 1×1, mediano 2×1 y grande 2×2. Mantener
pulsado y arrastrar reordena; mantener pulsado sin mover abre el menú (tamaño, cambiar con la IA,
quitar). Las plantillas nativas colocan solo las filas que caben enteras. Garantías de diseño:
- **Marco del sistema:** el diseño libre va dentro de un marco con márgenes, letra Montserrat y
  recorte; si algo no cabe, se funde abajo.
- **Revisión en pantalla:** la app mide cada widget de diseño libre y, si no cabe, el agente lo tiene
  que arreglar antes de terminar.
- **Sin páginas de error:** el WebView es invisible hasta que la página carga bien, y se reintenta
  solo.
- **Plantillas antes que HTML:** el agente no puede convertir en HTML una tarjeta de plantilla para
  cambiar opciones.

Widgets que se tocan:
- **Botones y filas con acción:** abrir, llamar, ir (navegación), multimedia, tachar, preguntar a la
  IA, casa (Home Assistant) o un comando.
- **Pilas inteligentes:** varios widgets en un hueco, que se deslizan en vertical. Enseñan el que
  toca según reglas (hora, Wi-Fi, Bluetooth, lugar, carga, foco, próxima cita) o lo que decida
  el agente.
- **Tarjetas automáticas:** paquetes (de SMS y avisos), vuelos (del calendario), partidos de tus
  equipos y facturas. Existen siempre y solo se enseñan cuando son relevantes.
- **Actividades en directo:** Live Updates de Android 16; aparecen en las notificaciones y en el
  bloqueo, con barra de progreso.

Galería y contenido:
- **Galería:** vista previa real en cada tamaño.
- **Gráficos nativos:** de líneas y de barras.
- **Marcador** de partido.
- **Fuentes nuevas:** deportes (ESPN), bolsa (Yahoo), cripto (CoinGecko), trayecto (OSRM, sin
  tráfico en vivo), aire y polen (Open-Meteo), pasos y sueño (Health Connect), casa (Home
  Assistant) y baterías.

Organización:
- **Modos del panel:** trabajo, casa, finde…
- **Historial:** con «Restaurar».
- **Ahorro:** lo del panel solo se refresca si se ha visto, más espaciado con la batería baja.
- **Presupuesto diario de la IA** para el panel.
- **El inicio usa la misma rejilla,** en páginas automáticas, y desde la primera se desliza al panel.

Gestos: deslizar hacia arriba abre el cajón siguiendo al dedo (se asienta con un muelle según la
velocidad y vibra al pasar el umbral); hacia abajo, con la lista arriba, lo cierra; atrás
predictivo; deslizar hacia abajo en el inicio baja las notificaciones; doble toque en un hueco
bloquea la pantalla. Las apps se abren creciendo desde su icono, que se hunde un poco al pulsarlo.
Fluidez: animaciones de ventana al 85 % de serie; la app de SIOS se compila entera tras cada
instalación o actualización (si no, Android la deja sin compilar hasta la noche); sin el límite de
procesos fantasma, para que Android no corte al agente.

Pulsación larga: modo edición. Arrastrar widgets (tarjeta entera), «−» para quitarlos, muelle
reordenable y editable, **Añadir widget** (del agente: ocultos o crear uno nuevo; de apps: widgets de
Android, enmarcados igual). En SIOS los widgets de apps se colocan sin diálogo (BIND_APPWIDGET).

## La IA en todo el sistema

- **Manos** (servicio de accesibilidad `Manos`, activado solo la primera vez): el agente lee la
  pantalla, toca, desliza y escribe (con tildes) en cualquier app. Mientras tanto, el **halo**: borde
  del color de la IA que gira y respira, cada toque como un círculo con brillo, estela al deslizar, el
  campo donde escribe resaltado, y una isla «… al mando · Parar» en la barra de estado. La hoja del
  asistente se aparta mientras el agente toca y vuelve con la respuesta; si el agente abre una app,
  vuelve delante. Sin el servicio, las herramientas usan uiautomator e `input` como antes.
- **Búsqueda en pantalla** (mantener la barra): captura congelada para rodear o tocar; la IA responde
  sobre la imagen sin actuar salvo que se le pida.
- **Compartir con la IA**: textos, enlaces, fotos y PDF (sus 5 primeras páginas como imágenes).
- **Texto seleccionado** (menú de selección de cualquier app): mejorar, traducir (sustituye el texto
  si el campo es editable) y preguntar.
- **Voz**: dictado propio dentro de la hoja y del chat (orbe del color de la IA), sin la ventana de
  Google. Mantener pulsado el botón de encendido abre el asistente escuchando
  (`config_longPressOnPowerBehavior`=5).
- **Notificaciones con cerebro** (`Bandeja`, acceso por `config_defaultListenerAccessPackages`):
  resumen al desbloquear («Mientras no mirabas»), recordatorio de mensajes sin contestar (3 h),
  **Foco** (recuadro de ajustes rápidos: una hora sin ruido y resumen al final) y
  **responder sin abrir la app** con tres respuestas propuestas (respuesta rápida de la notificación).
- **Memoria del móvil** (todo local, SQLite FTS4): texto de fotos y capturas (OCR abierto PP-OCRv5 en el propio móvil),
  PDF y documentos de Descargas/Documentos y el historial de notificaciones. Herramientas
  `buscar_en_movil`, `ver_resultado`, `abrir_resultado`. Se puede apagar y borrar en Ajustes.
- **Rutinas** (tipo de creación `rutina`): «cuando pase X, haz Y» con disparadores de hora, lugar,
  Wi-Fi, Bluetooth, batería, carga o notificación, y acciones de comando, abrir app, aviso o la IA.
- **Llamadas** (`config_defaultCallScreening`): identifica desconocidos con la memoria («Te llama
  Marta, clínica dental»), bloquea números y, si se quiere, silencia desconocidos.
- **Médico del móvil** (`revisar_movil`) y **Tu día** en la pantalla de bloqueo (citas y tiempo,
  cada mañana a partir de las 7:00).
- **Vistazo en el bloqueo** (`sistema/Vistazo.kt`): hasta 4 cifras de los widgets (tiempo, cascos,
  trayecto, próxima cita…) en una notificación fija con vista propia (plegada y desplegada). Las
  elige el servicio (`creaciones.vistazo()`, solo cifras que caben enteras) o el agente (`panel`
  acción `vistazo`). Canal de importancia normal sin sonido: las silenciosas no salen en el bloqueo.
- **Bienestar digital** (`sistema/Bienestar.kt`, `servicio/bienestar.mjs`): uso por app con las
  estadísticas de Android (`appops GET_USAGE_STATS`, concedido por el servicio), límites diarios por
  app hablando con el agente (herramienta `bienestar`) con pantalla de SIOS «Cerrar la app / 5 minutos
  más», resumen el domingo a las 20:00 y fuente `uso_movil` para widgets. Sin gastar suscripción.
- **Diario nocturno** (`servicio/diario.mjs`): a las 22:30 el modelo rápido escribe unas líneas del
  día con lo que sabe el móvil (citas, tiempo, pasos, fotos, llamadas, uso); aviso que abre la mini-app
  Diario (`/apps/diario/`). Herramienta `diario` y fuente `diario`.
- **Modo coche** (`coche/CocheActivity.kt`, `sistema/Coche.kt`): se abre solo al conectar el Bluetooth
  del coche (Ajustes › Modo coche), lee los mensajes en voz alta, casa/trabajo, música de la sesión
  activa, favoritos y hablar con la IA. También con el buscador («modo coche») y la herramienta
  `modo_coche`.
- **Compartir creaciones** (`servicio/compartir.mjs`, `ui/Compartir.kt`, `creaciones/ImportarActivity.kt`):
  enlace `sios://importar?d=…` (ficha comprimida, sin estado ni datos personales) y su QR desde la
  hoja de cada widget. Al abrirlo se enseña qué es y lo que conviene saber (comandos enteros, gasto de
  la IA, ubicación) y solo se añade si se confirma.
- **Desde el ordenador** (SIOS Desktop): hacer sonar el móvil aunque esté en silencio
  («Aquí estoy», `sistema/Sonar.kt`), SMS y llamadas con búsqueda de contactos, y el panel de widgets
  del móvil pintado en la ventana.
- **Al volante**: en modo coche, a quien escribe se le contesta una vez por viaje «Voy conduciendo…»
  (respuesta rápida de la notificación; se apaga en Ajustes › Modo coche).
- **Resumir grupos** (herramienta `conversacion`): los mensajes de un chat o grupo que han llegado como
  aviso (memoria del móvil + barra), por nombre o por número.
- **Recordatorios por contexto** (`servicio/recordatorios.mjs`, herramienta `recordar`): una sola vez
  (`una_vez`) al llegar a un tipo de sitio (los más cercanos, con Overpass y, si no responde,
  Nominatim; rutinas con `puntos`), a una dirección, al conectar el Bluetooth del coche, una Wi-Fi o
  al cargar. Si ya estás allí al crearlo, no salta hasta la próxima vez.
- **Gastos y suscripciones** (`servicio/gastos.mjs`, herramienta `gastos`, fuentes `gastos` y
  `suscripciones`): cobros de los avisos y SMS del banco (reglas, sin IA; lo que pide pagar o lleva
  enlaces no cuenta), tickets escaneados y a mano; suscripciones por repetición o comercio conocido, con
  aviso 2 días antes de renovar.
- **Escáner de documentos** (`sistema/Documentos.kt`, cámara › Documento): el escáner propio de SIOS (bordes, perspectiva y filtro); texto con el OCR abierto PP-OCRv5, el modelo rápido dice qué es,
  importe y fechas; PDF con nombre claro en `Documents/SIOS`, gasto anotado y «Al calendario» con la cita
  preparada (nunca se crea sola).
- **Carga inteligente** (`servicio/bateria.mjs`): aviso al 80 % cargando (sin sonido de noche),
  aprendiendo la hora a la que se desenchufa y mirando la alarma. Una GSI no puede cortar la carga.
- **Galería comunitaria** (`comunidad/`, Cloudflare Worker + KV; pestaña «Comunidad» al añadir un
  widget y «Publicar en la comunidad» en su hoja): solo widgets de fuentes integradas, sin comandos,
  HTML, prompts ni datos personales (se quita en el móvil y el servidor lo vuelve a comprobar); 5
  publicaciones al día por conexión, se oculta con 3 denuncias, borrado con la clave de administración.
- **Más desde el ordenador**: contestar los mensajes del móvil desde la ventana (respuesta rápida de
  la notificación) y copias automáticas de fotos, vídeos y documentos al ordenador cada 10 minutos
  con el móvil en la Wi-Fi (a trozos de 4 MB, cifrado; `/copia/lista` y `/copia/trozo`).
- **Documentos con memoria** (`servicio/documentos.mjs`, herramienta `documentos`): de cada escaneo se
  guarda lo extraído (categoría, productos, plazos). Aviso 3 días y 1 día antes de que acabe una
  devolución, y un mes antes de que acabe la garantía (3 años por ley para productos). En las facturas
  de luz, gas, móvil, internet o seguros, «¿Puedo pagar menos?» le pide a la IA que compare ofertas.
  Fuente `garantias`.
- **Modo viaje** (fuente `viaje`, tarjeta automática 3 días antes): del calendario, el destino, el
  tiempo allí, la diferencia horaria y el cambio de moneda (Open-Meteo y Frankfurter).
- **Accesibilidad**: recuadro «Leer pantalla» (lee en voz alta lo que hay delante), texto más grande y
  más contraste en Ajustes de SIOS.
- **Traductor de conversación** (`sistema/Traductor.kt`): pantalla partida, cada uno habla en su
  idioma (14) y la traducción (modelo rápido de la IA, o en el móvil con OPUS-MT) se dice en voz alta
  en el del otro.
- **Buenas noches** (`servicio/buenasnoches.mjs`): No molestar con prioridad, lo demás espera,
  pantalla en gris y por la mañana «Buenos días · N avisos mientras dormías».
- **La IA atiende a desconocidos** («Que conteste …»): si un número que no está en contactos suena
  5 s, contesta la IA, pregunta qué quiere y lo resume en un aviso (Ajustes › Llamadas).
- **Familia** (`servicio/familia.mjs` + `/familia/<grupo>` en el Worker): lista compartida y, quien
  quiera, dónde está (calle y pueblo). Cifrado de extremo a extremo con AES-256-GCM; la clave viaja en
  el enlace de invitación (QR); el servidor solo guarda paquetes cerrados. Widgets `familia_lista`
  (tocar tacha para todos) y `familia`.
- **Continuidad con el ordenador** (`servicio/continuidad.mjs`, `sistema/Continuidad.kt`, carpeta
  `escritorio/`): con Mac y Windows. Vincular con un código de 6 cifras (X25519 + HKDF; la clave
  nunca viaja) y, después, todo cifrado con AES-256-GCM con la ruta y la hora dentro, en el puerto
  8766 de la red local y solo si se activa. Portapapeles compartido (lo marcado como sensible en el
  ordenador no sale), avisos del móvil en el ordenador, enlaces y archivos (hasta 25 MB) de uno a
  otro, «Enviar al ordenador» al compartir y descubrimiento con NSD (`_sios._tcp`). Fuera de casa,
  las mismas rutas por Nostr (`servicio/nostr.mjs`): sobres cifrados con la clave del emparejamiento
  en varios servidores públicos a la vez, o en un servidor propio (`relay/`).

Para poner al día un SIOS ya configurado sin tocar sus datos: `scripts/actualizar-sios.sh`.
Ojo con el emulador en macOS: el dictado de Google (su ventana) cuelga la GPU emulada; por eso el
dictado es propio.

## El motor del agente (Termux)

Termux se presenta como «SIOS Core» (con el logo de SIOS) y su terminal como «Terminal»
(`termux/parchear.sh`); sin permiso de notificaciones, así que su aviso fijo no aparece. El
dispositivo se llama «SIOS» (overlay de SettingsProvider + la app en el primer arranque si sigue el
nombre del modelo).

## Qué lleva

| Dónde | Qué |
| --- | --- |
| `/system/priv-app/SIOS` | App de SIOS (`com.sios`: inicio, chat, asistente, ajustes, teclado, teléfono…) como app privilegiada |
| `/system/app/Termux*` | Termux, Termux:API y Termux:Boot preinstalados, firmados con la clave de quien construye (las versiones de GitHub usan una clave de pruebas pública) y con Termux parcheado para aceptar órdenes de la app de SIOS (`termux/parchear.sh`) |
| `/system/product/overlay/SiosFramework.apk` | SIOS como asistente, búsqueda en pantalla, teléfono y filtro de llamadas de serie; acceso a notificaciones; botón de encendido al asistente; emergencias; tema MONOCHROMATIC de SIOS (`theming_defaults`) con su paleta ya calculada (`paleta.xml`), fondo de SIOS (`marca/fondos.py`) y modo escritorio |
| `/system/etc/permissions/privapp-permissions-sios.xml` | `WRITE_SECURE_SETTINGS`, `MODIFY_DAY_NIGHT_MODE`, `START_ACTIVITIES_FROM_BACKGROUND`, `BLUETOOTH_PRIVILEGED`, `BIND_APPWIDGET` y `CAPTURE_AUDIO_OUTPUT` (para que la IA conteste llamadas) para la app de SIOS |
| `/system/etc/default-permissions/…` | Permisos de serie de Termux:API (SMS, contactos, ubicación…) y `RUN_COMMAND` de Termux para la app de SIOS |
| `/system/etc/sysconfig/sios.xml` | Termux y la app de SIOS fuera del ahorro de batería |
| `/system/etc/sios/` | `preparar.sh` + `instalar.sh` + sistema base de Termux (`sistema-base.tar.gz`, `termux/sistema_base.py`) + servicio (con los motores de IA de `servicio/ia`) + atajos de Termux |
| `/system/product/overlay/SiosSystemui.apk` | Recuadro de uso de la IA el primero del panel, de serie |
| `/system/etc/init/sios.rc` | adbd solo en 127.0.0.1:5555, para el agente (nunca en la red) |
| `/system/product/overlay/SiosSetupwizard.apk` | Asistente de bienvenida «Welcome to SIOS»: nombre, logo y color |
| `/system/product/overlay/SiosPlataforma.apk` | «Versión de SIOS» en Información del teléfono |
| `/system/product/media/bootanimation*.zip` | Animación de arranque: el símbolo de SIOS con el espectro de las IA, que se enfría al hueso y respira (`marca/animacion.py`) |
| `build.prop` | `ro.sios.version=0.1`, número de compilación y versión con el nombre SIOS |

Al primer arranque, tras el asistente del sistema (idioma, red, bloqueo…), la app muestra
**Bienvenido a SIOS**: conéctate, deja que se prepare y elige tu IA.

1. **Conexión.** Sin internet, botones a Wi‑Fi/datos móviles y a SIM y eSIM.
2. **Preparación.** En cuanto hay red, la app prepara el agente sola: le pasa a Termux `preparar.sh`
   (RUN_COMMAND, sin abrir Termux), que despliega el sistema base y sigue con `instalar.sh --auto`
   (Node, herramientas de Termux, servicio, ADB local). Cada paso llega a la app con una ficha de un
   solo uso. Unos minutos con buena red. En un móvil real aparece una vez «¿Permitir depuración?»: la
   app avisa de marcar «Permitir siempre».
3. **Tu IA.** Entrar con una suscripción (Claude, ChatGPT, Gemini, Kimi…), pegar la clave de una API o
   usar un modelo en el propio móvil. Lo propio de cada IA (Claude Code, Codex, Gemini CLI, Kimi Code,
   llama.cpp) se instala al elegirla (`servicio/ia/instalar.sh`). Con Claude, «Entrar con Claude»
   lanza `claude auth login` con un `BROWSER` que le pasa el enlace a la app, se abre en el navegador
   y Claude.ai vuelve solo a Claude Code por `localhost` (sin copiar códigos); si eso fallara, la app
   ofrece pegar el código. Se puede dejar para más tarde y cambiar cuando se quiera en Ajustes ›
   Inteligencia.

La instalación a mano sigue funcionando: `bash /system/etc/sios/instalar.sh` en Termux.

**eSIM:** la GSI no trae gestor de eSIM (el de Google necesita sus servicios y OpenEUICC exige la
clave de plataforma). Una eSIM ya activada antes de instalar SIOS sigue funcionando; descargar
una nueva no.

## Cualquier IA

SIOS (SuperIntelligence OS) funciona con la IA que elijas en Ajustes › Inteligencia o desde la app del ordenador:

- **Con tu suscripción:** Claude (Claude Code), ChatGPT (Codex de OpenAI, código de dispositivo), Gemini (Gemini CLI, cuenta de Google), Kimi (Kimi Code, planes Plus a Ultra), Grok (la API va incluida en Plus, Premium y Super: se pega la clave del plan) y Mistral (el plan Pro de Le Chat incluye crédito de API). Los programas se instalan la primera vez que se eligen (`servicio/ia/instalar.sh`).
- **Pago por uso con clave:** DeepSeek, Kimi, Gemini, OpenAI, Anthropic, OpenRouter… La clave se comprueba antes de guardarla, viaja cifrada desde el ordenador y se queda en el móvil con permisos 600.
- **En el móvil:** modelo local con llama.cpp, recomendado según la RAM (12 GB: Qwen3 8B; 8 GB: 4B; 6 GB: 1.7B; 3 GB: 0.6B). Solo se pueden elegir los descargados que caben. Se le puede poner nombre. El motor precalienta sus instrucciones: el primer mensaje tarda unos segundos en vez de minutos.

Todas usan las mismas herramientas del móvil por MCP. Las que no son Claude Code van por `/mcp/vigilado`, donde el propio servicio aplica el modo de permisos y pide confirmación con la tarjeta de siempre. Kimi Code y Codex no tienen terminal ni edición de archivos (agente con herramientas cerradas; sandbox de solo lectura).

La marca sigue a la IA: nombre en los textos, logo, color del sistema (semilla de la paleta), fondo de pantalla (`rom/marca/fondos.py`), y cabecera de las notificaciones. Sin IA elegida, todo es SIOS (ver «Aspecto»).

## La marca SIOS

`rom/marca/sios/simbolo.py` tiene la geometría de todo: el símbolo «io» (un anillo cuyo lado izquierdo sube y lleva un punto encima: la i y la o comparten trazo; el punto toma el color de la IA en uso) y el logotipo «sios» (s + símbolo + s, monolínea). De ahí salen la animación de arranque (`animacion.py`), los fondos (`fondos.py`), los vectores de Android (`sios/android.py`) y los iconos del escritorio (`sios/iconos_escritorio.py`).

## Cámara profesional

La app Cámara usa todo lo que la cámara del móvil deja controlar, y solo eso: ISO, velocidad de obturación, enfoque manual, balance de blancos (preajustes o Kelvin), EV, RAW (DNG) junto al JPEG, lentes físicas, zoom, flash; en vídeo, resolución, fps y estabilización; los modos del fabricante (Noche, HDR, Retrato, Retoque) si los trae; cuadrícula, nivel, histograma y temporizador. Guarda en DCIM/SIOS. «Preguntar» abre la cámara que pregunta a la IA.

## Privacidad comprobable

Ajustes › Qué ha salido de tu móvil: cada conexión a internet del servicio y del motor (destino, para qué y tamaño, nunca el contenido) y cada mensaje mandado a una IA de suscripción. Con el modelo local, las conversaciones no salen.

## Instalar y pasar los datos desde el ordenador

SIOS Desktop › Por cable: descarga las herramientas oficiales de Google si faltan, comprueba si el móvil es compatible (ARM64, Treble, base del fabricante Android 13+), deja **probar SIOS sin borrar nada** (DSU: al reiniciar vuelves a tu sistema) y, con el cargador desbloqueado y escribiendo INSTALAR, **quedarse con SIOS** (fastboot). También pasa fotos, documentos, música, contactos (vCard) y la lista de apps del móvil anterior al nuevo.

## Requisitos del móvil

- **arm64, A/B**, con **bootloader desbloqueado** (para quedarse con SIOS; para probar con DSU no
  siempre hace falta).
- Compatible con Treble y con la base del fabricante (vendor) en **Android 13 o posterior**: es lo
  que comprueba SIOS Desktop.
- Lo que dependa del fabricante (cámara, huella, VoLTE, brillo) puede fallar: es una GSI.

## Probar sin borrar nada (DSU)

Arranca SIOS de forma temporal; al reiniciar vuelve el sistema original.

```bash
adb push salida/sios-0.1-lineage23.2-arm64-ab-ext4.img.gz /sdcard/Download/
adb shell setprop persist.sys.fflag.override.settings_dynamic_system true
adb shell am start-activity \
  -n com.android.dynsystem/com.android.dynsystem.VerificationActivity \
  -a android.os.image.action.START_INSTALL \
  -d file:///storage/emulated/0/Download/sios-0.1-lineage23.2-arm64-ab-ext4.img.gz \
  --el KEY_SYSTEM_SIZE 3626696704 \
  --el KEY_USERDATA_SIZE 8589934592
# Cuando termine, la notificación «Reiniciar» arranca SIOS.
```

Para la edición Puro: `sios-puro-0.1-…img.gz` y `KEY_SYSTEM_SIZE 2913615872`. `KEY_SYSTEM_SIZE` es
el tamaño exacto de la `.img` sin comprimir; cambia en cada compilación (`stat -f %z` en el Mac).

## Instalar de forma permanente (fastboot)

Borra el móvil. Los pasos exactos dependen del modelo; el general:

```bash
adb reboot fastboot                      # fastbootd (particiones dinámicas)
fastboot flash system salida/sios-0.1-lineage23.2-arm64-ab-ext4.img
fastboot -w                              # borrar datos
fastboot reboot
```

Algunos móviles necesitan además `fastboot --disable-verity --disable-verification flash vbmeta vbmeta.img`
con un `vbmeta.img` vacío de AOSP.

## Actualizar el sistema sin cable (A/B, con vuelta atrás)

En móviles **A/B virtuales** (casi todos los que salieron con Android 11 o después), SIOS se
actualiza desde el propio móvil: Ajustes → Sistema → Actualización del sistema. update_engine
escribe el sistema nuevo en la otra ranura mientras se usa el móvil, se reinicia y, si la versión
nueva no arranca bien (no termina de arrancar, la app de SIOS no da señales o el agente no vuelve),
el móvil vuelve solo a la anterior. Solo viaja la partición `system` (la GSI lleva dentro
`system_ext` y `product`); el resto del firmware de la otra ranura se copia de la actual.

Una vez:

```bash
scripts/clave-actualizaciones.sh   # crea ~/.sios/sistema.key (RSA-4096) y rom/ota/clave-sistema.x509.pem
rom/construir.sh                   # la imagen lleva ese certificado en otacerts.zip (y ro.sios.ota=1)
```

Esa primera imagen con el certificado se instala por cable; a partir de ahí, cada versión:

```bash
rom/construir.sh && rom/construir.sh --puro
scripts/publicar-version.sh -m "Lo que trae" --sistema
```

`--sistema` añade al manifiesto firmado una carga completa por edición (≈1 GB) y otra diferencial
desde la última publicada (rom/salida/publicado/; decenas de MB si cambia poco). Se suben todos los
archivos de rom/salida/publicar al canal (el servidor debe aceptar peticiones Range para que la
descarga se pueda reanudar). El canal se fija al construir con `CANAL=https://…` (queda en
`ro.sios.actualizaciones`); sin él, la imagen no busca actualizaciones. Detalles y límites:
`rom/herramientas/carga_sistema.py` y `rom/capa/system/etc/sios/sistema-ota.sh`.

Quien distribuya sus propias imágenes debe crear sus propias claves: las privadas nunca van al
repositorio (solo `servicio/clave-actualizaciones.pem` y `rom/ota/clave-sistema.x509.pem`, que son
públicas).

## Volver a construir

```bash
rom/construir.sh           # compila la app y monta la ROM (≈1 min)
rom/construir.sh --sin-app # reutiliza la última app compilada
```

Requisitos del Mac y pasos previos (imágenes base, APK de Termux, firma): ver el `README.es.md` de la
raíz.

## Limitaciones de la 0.1

- **Galería comunitaria y Familia sin publicar** (las dos usan el mismo servidor): el servidor está
  hecho y probado en local, pero no desplegado. Para ponerlo en marcha: `cd comunidad && npx wrangler
  deploy` (con el identificador de tu espacio KV en `wrangler.jsonc`) y la clave de administración con
  `npx wrangler secret put ADMIN < ~/.sios/comunidad-admin.key` (crearla antes con
  `openssl rand -hex 24`). Se le dice dónde está con `SIOS_COMUNIDAD` (o en `~/sios/comunidad.json`
  del móvil); sin servidor, la galería comunitaria y Familia no salen.
- **Probado en emulador, no en un móvil real.** Lo que no se puede probar en el emulador está en
  `GUIA-INSTALACION.md` › «Primer día: lista de comprobación».
- **Que la IA conteste llamadas** usa el modo de audio de fondo de la app de teléfono (API de sistema)
  y `CAPTURE_AUDIO_OUTPUT`: solo funciona con la app de SIOS como app privilegiada de la ROM, y el
  emulador no lleva audio real en las llamadas. Si no puede oír la llamada, lo dice y deja contestar.
- **eSIM:** sin gestor en la GSI. Una eSIM activada antes sigue funcionando; descargar una nueva no.
- Lo que depende del fabricante (cámara, huella, VoLTE, NFC de pago, brillo automático) puede fallar
  en algunos modelos: es una GSI.
- El agente usa ADB local (127.0.0.1): en un móvil real hay que aceptar una vez «Permitir depuración».
- El recuadro del panel sale pequeño la primera vez: se ensancha a mano una vez.
- La licencia de LineageOS se sigue mostrando con su nombre en Ajustes: es la atribución legal de la base.
- Modo escritorio: preparado con lo que trae Android 16 QPR2 (es experimental en esta versión de Android) y
  sin probar aún con un monitor de verdad; necesita un móvil con salida de vídeo por USB-C.
- Continuidad: el contenido viaja cifrado, pero el servidor del 8766 escucha en toda la red local
  mientras está activa (sin vincular solo responde a `/vincular`, y con el código). Probado contra el
  emulador con `adb forward`; en un móvil real hace falta la misma Wi-Fi. La app de escritorio no va
  firmada con certificado de desarrollador: la primera vez, en el Mac, clic derecho › Abrir; en
  Windows, SmartScreen › Más información › Ejecutar de todos modos.
- Idiomas de la app: español, inglés, francés, alemán, italiano y portugués (también el
  contestador, con el modelo de Vosk de cada idioma). El teclado sugiere palabras en los seis.

## Fallos que encontró la prueba en el emulador (ya corregidos)

- Las llamadas salían sin pantalla: Telecom busca la del marcador del sistema en
  `com.android.incallui.InCallServiceImpl`. Ahora lo arregla `overlay-telecom` (y la app, con una capa
  fabricada, en los móviles ya instalados; Telecom la lee al arrancar).
- La bienvenida reaplicaba el tema en cada redibujado: bucle de reinicios que se tragaba los toques.
- Termux se cerraba al preparar su sistema: las apps del sistema necesitan sus librerías nativas
  desempaquetadas en `lib/arm64`.
- `instalar.sh` se cortaba en silencio en el paso 1 (`yes |` con `pipefail`); ahora dice dónde falla.
- «ADB por red» ya no existe en LineageOS 23.2: lo sustituye `sios.rc` (solo localhost).
- El overlay del sistema con el fondo en 5 densidades no cargaba (`STATE_NO_IDMAP`): ahora va en nodpi,
  xxhdpi y xxxhdpi.
- Termux arranca las órdenes con `PATH` en su propio `bin`, que antes del sistema base no existe:
  `preparar.sh` fija `PATH=/system/bin`. Su carpeta de trabajo es `cache` (la única que ya existe).
- Termux publicó Node.js compilado contra libicu 78 sin declararlo y apt instalaba la 79: el
  instalador detecta la librería que falta e instala esa versión.
- Las versiones nuevas de proot-distro guardan Debian en otra carpeta: el instalador comprueba
  Debian entrando en él, no por la ruta.
- SystemUI aplaza los cambios de tema hasta terminar el asistente: el color inicial se fija con
  `theming_defaults`.

## Licencia

El código propio de SIOS es AGPL-3.0-or-later (`LICENSE` en la raíz). LineageOS/AOSP, Termux, las
apps de F-Droid de serie y el resto de componentes de terceros mantienen su licencia: ver
`THIRD_PARTY.md`.
