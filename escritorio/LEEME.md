# SIOS Desktop

La continuidad del móvil con SIOS en el ordenador, **Mac y Windows** con el mismo código (Go):
un icono en la barra del Mac o en la bandeja de Windows y una ventana para vincular y mandar cosas.

- **Portapapeles compartido**: copias en uno y pegas en el otro. Lo que el sistema marca como
  sensible (contraseñas de un gestor) no sale del ordenador.
- **Avisos del móvil** en el ordenador (mensajes, avisos…).
- **Enlaces**: «Abrir el enlace copiado en el móvil» (menú) o pegarlo en la ventana; desde el móvil,
  «Enviar al ordenador» al compartir abre el enlace aquí.
- **Archivos** (hasta 25 MB): arrastrarlos a la ventana los deja en Descargas › Claude del móvil; los
  del móvil llegan a Descargas del ordenador.

## Usarlo

1. En el móvil: Ajustes de SIOS › Continuidad con el ordenador › **Vincular un ordenador**.
2. En el ordenador: abrir la app (la primera vez se abre la ventana sola), elegir el móvil (se
   encuentra solo en la Wi-Fi) o escribir la dirección que enseña, y escribir el código.

## Construir

```sh
./construir.sh   # dist/SIOS Desktop.app (+ .zip, universal) y dist/SIOS Desktop.exe (x64)
```

Prueba de punta a punta contra el emulador (`adb forward tcp:8766 tcp:8766` y un código del móvil):
`SIOS_MOVIL=127.0.0.1:8766 SIOS_CODIGO=123456 go test -tags movil -run TestMovil -v`.

## Seguridad

- Vincular: X25519 efímero en los dos lados; el ordenador demuestra que conoce el código con
  HMAC(código, su clave pública) y el móvil responde con HMAC(clave, la suya). La clave sale de HKDF
  sobre el secreto compartido, el código y las dos claves públicas. Cinco fallos invalidan el código.
- Después: cada petición y respuesta con AES-256-GCM; la ruta y la hora van dentro (90 s de margen).
- La ventana local solo atiende en 127.0.0.1 con su host exacto, una cookie que pone el propio
  programa y el mismo origen: ninguna web puede usarla.
- La clave se guarda en el archivo de ajustes del usuario (permisos 600).

## Inteligencia

Elige la IA del móvil desde aquí: las sesiones (ChatGPT, Gemini, Kimi) se abren en el navegador del ordenador y el código se ve en grande; las claves se pegan aquí y viajan cifradas al móvil; el modelo local se recomienda según la RAM del móvil y se descarga en él.

## Por cable

Instalar SIOS (probar sin borrar nada con DSU, o quedarse con él con fastboot) y pasar los datos del móvil anterior. Arrastra la imagen `sios-….img.gz` a la ventana o déjala en Descargas.
