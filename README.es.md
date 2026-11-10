# SIOS

[English](README.md) · **Español**

SIOS es una GSI (imagen genérica de sistema) de Android 16, basada en LineageOS 23.2, en la que **la
IA que eliges es el agente del sistema**: Claude, ChatGPT, Gemini, Kimi, Mistral, Grok, cualquier API
compatible con OpenAI o un modelo que corre en el propio móvil. El agente lee la pantalla, toca,
escribe, abre apps, manda mensajes y ejecuta órdenes, siempre con las mismas herramientas del móvil y
pidiéndote confirmación para lo delicado.

Mientras no eliges ninguna IA, el sistema es neutro: es **SIOS**, en blanco y negro. Al elegir una,
el sistema toma su nombre, su logo y su color.

> **Estado: temprano (0.1).** Probado de punta a punta en el emulador de Android. **Todavía sin
> probar en un móvil real.** Ver [Estado](#estado).

## Índice

- [Funciones](#funciones)
- [Privacidad](#privacidad)
- [Ediciones](#ediciones)
- [Estado](#estado)
- [Construir](#construir)
- [Instalar](#instalar)
- [Estructura del repositorio](#estructura-del-repositorio)
- [Licencia y marcas](#licencia-y-marcas)

## Funciones

**El agente**
- Cualquier IA: con tu suscripción (Claude Code, Codex, Gemini CLI, Kimi Code, Grok, Mistral), con
  una clave de pago por uso (DeepSeek, OpenAI, Anthropic, Gemini, Kimi, OpenRouter…) o un modelo
  local (llama.cpp con Qwen3, recomendado según la RAM del móvil). Los programas se instalan la
  primera vez que se eligen.
- Todas usan las mismas herramientas del móvil por MCP. Lo que no está en la lista de permitidas
  pide confirmación. Lo que viene de terceros (SMS, notificaciones, webs) le llega marcado como
  datos y, después de leerlo, lo que envía, llama, instala o ejecuta vuelve a preguntar.
- En todo el sistema: barra del inicio, hoja del asistente, mantener pulsado el botón de encendido,
  «Oye …», búsqueda en pantalla, compartir, menú del texto seleccionado (mejorar, traducir,
  preguntar) y un servicio de accesibilidad («Manos») con el que el agente actúa dentro de cualquier
  app, con un halo visible y un botón de Parar.
- Creaciones: el agente hace widgets del inicio (plantillas fijas o HTML libre en un entorno
  cerrado), mini-apps, agentes en segundo plano y rutinas («cuando pase X, haz Y»), y los cambia con
  una frase.

**Apps de serie** (Kotlin + Jetpack Compose, una sola app privilegiada, `com.sios`)
- Inicio y panel de widgets, cajón de apps, ajustes, teclado (6 idiomas, barra de la IA), teléfono y
  filtro de llamadas, cámara profesional (controles manuales, RAW), editor de fotos con IA en el
  móvil (recortar el sujeto, borrar objetos) y escáner de documentos.
- **Bóveda**: contraseñas, códigos 2FA y documentos, cifrados con SQLCipher y una clave del chip de
  seguridad que pide la huella o el PIN en cada uso. Autorrelleno y proveedor del Credential Manager.
- **Emergencias**: SOS (cinco pulsaciones del botón de encendido), ficha médica en el bloqueo,
  detección de caídas y «compartir mi viaje». Funciona antes del primer desbloqueo y nunca pasa por
  la IA.
- **Cortafuegos y bloqueo de rastreadores**: internet por app (Wi-Fi / datos) y bloqueo por DNS con
  una VPN local que solo lleva el DNS. Nada va a un servidor de VPN.
- **Memoria de pantalla** («¿dónde vi…?»): apagada de serie, local y cifrada. Nunca guarda campos de
  contraseña, apps de banca o pago, el incógnito ni la pantalla de bloqueo.
- **Voz sin conexión**: dictado (Whisper o Vosk) y traducción (OPUS-MT) en el móvil.
- Resumen de notificaciones, modo foco, detector de estafas, bienestar digital, modo coche, buenas
  noches, diario, recordatorios por contexto, gastos y suscripciones, garantías, modo escritorio con
  un monitor, copias cifradas (archivo `.sios`) y actualizaciones del sistema sin cable (A/B, con
  vuelta atrás automática).
- Interfaz en español, inglés, francés, alemán, italiano y portugués.

**SIOS Desktop** (Go, macOS y Windows)
- Instalar SIOS por cable: probarlo sin borrar nada (DSU) o quedarse con él (fastboot), y pasar
  fotos, documentos, música, contactos y la lista de apps del móvil anterior.
- Continuidad: portapapeles compartido, avisos del móvil, enlaces y archivos, SMS y llamadas desde el
  ordenador y copia automática de fotos por Wi-Fi.
- **Encontrar mi móvil**: hacerlo sonar, modo perdido, borrado a distancia, aviso si cambian la SIM
  y, si se quiere, foto a quien falla al desbloquearlo. Fuera de casa va por Nostr, cifrado de
  extremo a extremo.
- Elegir la IA del móvil y entrar en ella desde el ordenador.

## Privacidad

Lo que **se queda en el móvil**: la bóveda, la memoria de pantalla, el índice de búsqueda del móvil
(texto de fotos, documentos y notificaciones), los datos de emergencia, las copias (salvo que las
guardes en una carpeta en la nube), el dictado y la traducción sin conexión, y todo lo que hace el
modelo local.

Lo que **sale del móvil**, y solo cuando se usa:
- Los mensajes a la IA que elegiste, hacia ese proveedor (nada con el modelo local).
- Las fuentes de datos de los widgets que añadas (tiempo, noticias, gasolina, deportes, bolsa,
  mapas…), las búsquedas que pidas y la instalación de apps (F-Droid / Neo Store; Play Store en la
  edición con Google).
- La descarga, una vez, de los modelos desde Hugging Face (solo al activar la función) y la
  instalación de los programas de cada IA desde GitHub o npm.
- La comprobación de contraseñas filtradas de la bóveda (apagada de serie): solo los 5 primeros
  caracteres del resumen SHA-1 van a Have I Been Pwned.
- La continuidad con el ordenador: en la red local; fuera de ella, solo sobres cifrados de extremo a
  extremo por servidores públicos de Nostr (o por tu propio servidor intermedio).
- Los servicios opcionales de la comunidad (galería de widgets, Familia): solo si quien distribuye
  SIOS monta `comunidad/`. Lo de Familia va cifrado de extremo a extremo.
- La búsqueda de actualizaciones, solo si la compilación lleva un canal de actualizaciones.

**Ajustes › Qué ha salido de tu móvil** enseña cada conexión hacia fuera del servicio y del agente
(destino, para qué y tamaño, nunca el contenido) y cada mensaje mandado a una IA de suscripción. La
edición con Google lleva además los servicios de Google Play, que hablan con Google por su cuenta.

## Ediciones

| Edición | Base | Para quién |
| --- | --- | --- |
| **SIOS** (`rom/construir.sh`) | LineageOS 23.2 GAPPS (MindTheGapps: Play Store, servicios de Google) | Uso diario con WhatsApp, banca, Maps… |
| **SIOS Puro** (`rom/construir.sh --puro`) | LineageOS 23.2 VANILLA, sin Google | Sin Google: apps libres con Neo Store, las de Play con Aurora Store (sin cuenta), CoMaps y Thunderbird |

Las apps de Google no se pueden redistribuir: **ni este repositorio ni ninguna descarga oficial
llevan apps de Google.** La edición con Google se la compila cada uno a partir de una imagen base
GAPPS.

## Estado

- Probado en el emulador de Android: arranca, pasa el asistente, muestra la bienvenida de SIOS,
  instala el agente y funciona de principio a fin.
- **Todavía sin probar en un móvil real.** Lo que depende del fabricante (cámara, huella, VoLTE, NFC
  de pago, brillo automático) puede fallar en algunos modelos, como en cualquier GSI.
- Sin desplegar: el servidor de la galería comunitaria y Familia (`comunidad/`) y un canal de
  actualizaciones.
- SIOS Desktop no va firmado con certificado de desarrollador (Mac: clic derecho › Abrir; Windows:
  SmartScreen › Más información › Ejecutar de todos modos).
- El modo escritorio con un monitor está preparado, pero sin probar con un monitor de verdad.
- eSIM: una eSIM activada antes de instalar sigue funcionando; descargar una nueva no (una GSI no
  trae gestor de eSIM).
- En un móvil real el agente usa ADB local (solo 127.0.0.1): hay que aceptar una vez «Permitir
  depuración».

La lista completa de limitaciones está en [`rom/LEEME.md`](rom/LEEME.md).

## Construir

Todo se construye en **macOS** (Apple Silicon: los scripts usan las rutas de Homebrew en
`/opt/homebrew`).

Requisitos (sacados de los scripts):

| Herramienta | Para qué |
| --- | --- |
| `openjdk@21` (Homebrew) | Gradle, apktool, apksigner |
| Herramientas de línea de órdenes de Android (Homebrew `android-commandlinetools`) con `platforms;android-36`, la plataforma Android 37.2 (`compileSdk`), `build-tools;36.0.0`, `build-tools;37.0.0`, `platform-tools` y, para probar, `emulator` | App, overlays, firma, emulador |
| `e2fsprogs` (Homebrew) | Editar la imagen ext4 del sistema (`debugfs`, `resize2fs`, `e2fsck`) |
| `apktool` | Parchear Termux (`rom/termux/parchear.sh`) |
| Python 3 con Pillow | Fondos, animación de arranque, imágenes |
| Google Chrome (en `/Applications`) | Dibujar la marca sin ventana |
| Go 1.27+ y las herramientas de línea de órdenes de Xcode | SIOS Desktop (`escritorio/construir.sh`) |
| Node.js | El servicio (corre en Termux, en el móvil), `relay/` y `comunidad/` (Wrangler) |
| `openssl`, `zip`, `curl`; `ffmpeg` (opcional) | Claves, empaquetado, descargas, vista previa de la animación |

Pasos:

1. **Imágenes base.** Descarga las GSI no oficiales de LineageOS 23.2 de MisterZtr (arm64, A/B,
   ext4) de SourceForge, comprueba sus sumas, extrae los `.img` y déjalos en `rom/base/` con los
   nombres que espera `rom/construir.sh` (`LineageOS-23.2-20260524-GAPPS-EXT4-GSI.img` y
   `LineageOS-23.2-20260524-VANILLA-EXT4-GSI.img`).
2. **Termux.** Deja las versiones arm64 de Termux, Termux:API y Termux:Boot de sus publicaciones de
   GitHub en `descargas/` como `termux-arm64.apk`, `termux-api.apk` y `termux-boot.apk`.
3. **Firma.** Los overlays, la app y el Termux parcheado se firman con `~/.android/debug.keystore`
   (Gradle lo crea en la primera compilación de depuración). Lo que vayas a distribuir, fírmalo con
   tu propia clave.
4. **Opcional:** `scripts/clave-actualizaciones.sh` crea las claves de las actualizaciones (las
   privadas se quedan en `~/.sios/`; las públicas van al repositorio) para que la imagen acepte
   actualizaciones firmadas sin cable.
5. Construir:

```bash
rom/construir.sh            # SIOS (con Google): compila la app y monta la ROM
rom/construir.sh --puro     # SIOS Puro
rom/construir.sh --sin-app  # reutiliza la última app compilada
```

El resultado queda en `rom/salida/`: la imagen para fastboot (`.img`), la de DSU (`.img.gz`) y sus
SHA-256. Las apps de serie (Neo Store; en Puro, también Aurora Store, CoMaps y Thunderbird) las baja
de F-Droid `rom/tienda/descargar.sh`, con versión, SHA-256 y certificado de firma fijos.

Probar en el emulador:

```bash
rom/emulador/preparar.sh            # mete la ROM en un emulador llamado «sios»
rom/emulador/arrancar.sh            # lo arranca (emulator-5556)
rom/emulador/asistente_lineage.sh   # pasa el asistente de LineageOS
```

SIOS Desktop: `escritorio/construir.sh` (app universal de Mac y `.exe` de Windows x64 en
`escritorio/dist/`).

## Instalar

Lo más fácil es **SIOS Desktop › Por cable**: descarga `adb` y `fastboot` de Google si faltan,
comprueba que el móvil es compatible (arm64, Treble, base del fabricante con Android 13 o más) y
ofrece dos pasos:

1. **Probar sin borrar nada (DSU).** SIOS arranca como sistema temporal; al reiniciar vuelve tu
   Android, tal como estaba.
2. **Quedarse con SIOS (fastboot).** Pide el cargador de arranque desbloqueado y borra el móvil. Se
   confirma escribiendo INSTALAR.

Los comandos a mano y una guía paso a paso están en [`rom/LEEME.md`](rom/LEEME.md) y
[`rom/GUIA-INSTALACION.md`](rom/GUIA-INSTALACION.md). Desbloquear el cargador de arranque borra el
móvil: haz antes una copia.

## Estructura del repositorio

| Ruta | Qué es |
| --- | --- |
| `app/` | La app de SIOS (Kotlin + Compose, paquete `com.sios`): inicio, ajustes, teclado, teléfono, cámara, bóveda, emergencias, cortafuegos, memoria de pantalla, editor de fotos, voz sin conexión, modo escritorio… |
| `servicio/` | El servicio del agente (Node.js, en Termux): motores de IA (`ia/`), herramientas MCP del móvil, chat, creaciones, continuidad, actualizaciones |
| `termux/` | Órdenes y scripts de arranque que van a Termux (`android`, `bt`, `claude-agente`…) |
| `mcp/` | Servidor MCP independiente que expone el móvil por ADB |
| `escritorio/` | SIOS Desktop (Go): instalar por cable, continuidad, encontrar mi móvil |
| `rom/` | Scripts que remasterizan la GSI en macOS (`construir.sh`), overlays, capa del sistema (`capa/`), marca (`marca/`), OTA, emulador, parche de Termux, apps de serie |
| `relay/` | Servidor intermedio opcional (Cloudflare Worker) para quien lo prefiera a Nostr |
| `comunidad/` | Cloudflare Worker opcional de la galería comunitaria de widgets y de Familia |
| `scripts/` | Ayudas de desarrollo: desplegar en el emulador, claves de actualización, publicar una versión |
| `descargas/` | Descargas de terceros (los APK de Termux, fuera del repositorio) y la letra Montserrat |

El código, los identificadores y los comentarios están en español. Ver [CONTRIBUTING.md](CONTRIBUTING.md).

## Licencia y marcas

El código propio de SIOS tiene licencia **GNU Affero General Public License v3.0 o posterior**
([LICENSE](LICENSE)). Copyright © Los autores de SIOS. Para contribuir hay que firmar el
[CLA](CLA.md), que permite al proyecto ofrecer también el código con licencias comerciales.

Lo de terceros mantiene su licencia: LineageOS/AOSP, Termux, las apps de F-Droid de serie, los
modelos de IA que se descargan bajo demanda, letras, iconos, listas de bloqueo, diccionarios y
librerías. Ver [THIRD_PARTY.md](THIRD_PARTY.md).

Claude, ChatGPT, Gemini, Kimi, Mistral, Grok, DeepSeek, OpenRouter, Qwen, Android y los demás nombres
y logos que se mencionan son marcas de sus dueños. **SIOS no está afiliado a ninguno de ellos ni
cuenta con su respaldo o patrocinio.** Sus nombres y logos solo se usan para identificar la IA que ha
elegido el usuario.

Fallos de seguridad: avísanos en privado, ver [SECURITY.md](SECURITY.md).
