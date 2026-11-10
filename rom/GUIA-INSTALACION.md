# Instalar SIOS en un móvil

Guía para quien instala SIOS en su móvil. El detalle técnico de la ROM está en `LEEME.md`.

## 1. ¿Sirve mi móvil?

SIOS es una **GSI** (imagen genérica de sistema): funciona en la mayoría de móviles Android
modernos, sin una versión hecha a medida para cada modelo. El móvil tiene que cumplir:

| Requisito | Cómo comprobarlo |
| --- | --- |
| Salió con **Android 9 o posterior** (mejor 11+) | Ajustes › Información del teléfono |
| Procesador **arm64** | App *Treble Info* (F-Droid) o `adb shell getprop ro.product.cpu.abi` → `arm64-v8a` |
| Compatible con **Treble** y particiones **A/B** | *Treble Info*: «Treble: sí», «Seamless updates: sí» |
| **Bootloader desbloqueable** | Depende del fabricante (abajo) |

Qué fabricantes lo ponen fácil:

- **Google Pixel** (6 en adelante): desbloqueo oficial. Es la opción más segura.
- **Nothing, Fairphone, OnePlus, Motorola** (la mayoría): desbloqueo oficial desde la web o con un
  comando.
- **Xiaomi**: desbloqueo oficial con espera y cuenta Mi; algunos modelos recientes lo restringen.
- **Samsung**: se puede en muchos modelos europeos, pero rompe Knox para siempre (Samsung Pay y la
  carpeta segura dejan de funcionar).
- **Evita** los móviles de operadora con el bootloader bloqueado, los de procesador Unisoc y los
  de Huawei recientes: no se pueden desbloquear.

Lo que depende del fabricante puede fallar en una GSI: cámara (a veces con menos calidad),
huella, VoLTE y llamadas por Wi-Fi, NFC para pagar, o el brillo automático. Pruébalo primero sin
borrar nada (paso 3).

## 2. Antes de empezar

- **Copia de seguridad** de tus fotos y datos: desbloquear el bootloader **borra el móvil**.
- Si ya usas SIOS: Ajustes de SIOS › **Copia de seguridad** › Hacer copia. Al instalar, el
  último paso de la bienvenida permite **restaurarla**.
- Una **eSIM** ya activada sigue funcionando; descargar una nueva en SIOS no (no hay gestor
  de eSIM en una GSI). Si cambias de móvil, activa la eSIM antes.
- En el ordenador: [herramientas de plataforma de Android](https://developer.android.com/tools/releases/platform-tools)
  (`adb` y `fastboot`).

## 3. Probar sin borrar nada (DSU)

Arranca SIOS de forma temporal; al reiniciar vuelve tu sistema. No hace falta desbloquear en
muchos móviles (Pixel sí lo pide). Los comandos están en `LEEME.md` › «Probar sin borrar nada».

## 4. Instalar de verdad

1. Desbloquea el bootloader (método de tu fabricante).
2. Comprueba el archivo descargado: `shasum -a 256 -c sios-…sha256`.
3. Sigue `LEEME.md` › «Instalar de forma permanente (fastboot)».
4. Al encender: idioma, Wi-Fi o datos, y **Bienvenido a SIOS**: la conexión, la preparación del
   sistema (unos minutos) y **Elige tu IA**: entrar con tu suscripción (Claude, ChatGPT, Gemini,
   Kimi…), pegar la clave de una API o usar un modelo en el propio móvil. Se puede dejar para más
   tarde.
5. En un móvil real sale una vez «¿Permitir depuración?»: marca **Permitir siempre** (es la forma
   en que el agente actúa sobre el propio móvil; solo escucha dentro del móvil, nunca en la red).

## 5. Ediciones

- **SIOS** (con Google): Play Store y las apps de siempre (WhatsApp, bancos…).
- **SIOS Puro**: sin servicios de Google; apps de F-Droid. Algunas apps de bancos no van.

## 6. Primer día: lista de comprobación

Ajustes de SIOS › **Comprobación de SIOS** repasa todo y arregla con un toque lo que
pueda. Además, en un móvil nuevo merece la pena probar a mano lo que el emulador no puede probar:

- [ ] Una llamada entrante y otra saliente; altavoz y silencio.
- [ ] **Que conteste la IA** a una llamada de alguien de confianza: que se oiga el saludo y salga el resumen.
- [ ] **Oye …** (el nombre de tu IA) con la pantalla encendida y con el móvil cargando.
- [ ] Mantener pulsado el **botón de encendido**: abre el asistente escuchando.
- [ ] Rutina por **lugar** (llegar a casa) y por **Bluetooth** (conectar al coche o a los cascos).
- [ ] **Cámara con IA** con buena luz: preguntar, traducir un cartel, escanear una factura.
- [ ] Un día entero de uso normal: batería al final del día (SIOS no debe gastar más de un 5 %
      en reposo; si gasta más, Ajustes › Batería y avísanos con el informe de la comprobación).
- [ ] Bluetooth: emparejar unos cascos pidiéndoselo a la IA («empareja mis cascos»).
- [ ] Actualizaciones: Ajustes de SIOS › Actualizaciones › Buscar.

Si algo falla, en la Comprobación pulsa **Copiar informe** y envíalo junto con el modelo del móvil.

## 7. Volver al sistema original

Descarga la imagen de fábrica de tu fabricante (Google, OnePlus, Xiaomi… las publican) y fléshala
con su herramienta. Si usaste DSU, basta con reiniciar.
