#!/bin/bash
# Arranca el emulador «sios» (SIOS) en el puerto 5556 para no chocar con el de pruebas.
# Registro: /tmp/sios-emu.log
set -euo pipefail
SDK=/opt/homebrew/share/android-commandlinetools
ANDROID_SDK_ROOT=$SDK nohup "$SDK/emulator/emulator" -avd sios -port 5556 -no-snapshot -gpu host \
  -show-kernel >/tmp/sios-emu.log 2>&1 &
echo "Arrancando SIOS en emulator-5556 (registro: /tmp/sios-emu.log)"
