#!/bin/bash
# Arranca el emulador de SIOS (si no lo está), activa el ADB por red que usa
# Termux para controlar el propio móvil y deja el chat accesible desde el Mac.
#   scripts/emulador.sh          → abre el emulador y lo prepara
#   Chat en el Mac: http://127.0.0.1:8765
set -euo pipefail

SDK=/opt/homebrew/share/android-commandlinetools
SERIAL=emulator-5554
AVD=sios36

if ! adb devices | grep -q "^$SERIAL"; then
  ANDROID_SDK_ROOT=$SDK nohup "$SDK/emulator/emulator" -avd "$AVD" -no-boot-anim >/tmp/sios-emu.log 2>&1 &
  echo "Arrancando el emulador…"
fi

adb -s "$SERIAL" wait-for-device
until [ "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done

# Tras cada arranque, adbd vuelve a USB: Termux necesita que escuche en el puerto 5555.
if [ "$(adb -s "$SERIAL" shell getprop service.adb.tcp.port | tr -d '\r')" != "5555" ]; then
  adb -s "$SERIAL" tcpip 5555 >/dev/null
  sleep 3
fi
adb -s "$SERIAL" forward tcp:8765 tcp:8765 >/dev/null

# Termux:Boot arranca el servicio solo; si aún no está, se lanza a mano.
for _ in $(seq 1 15); do
  curl -s -o /dev/null http://127.0.0.1:8765/estado && break
  sleep 2
done
if ! curl -s -o /dev/null http://127.0.0.1:8765/estado; then
  "$(dirname "$0")/tx.sh" '~/sios/iniciar.sh'
fi
echo "Listo. Chat: http://127.0.0.1:8765 (también en el emulador, icono Claude)"
