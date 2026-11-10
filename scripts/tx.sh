#!/bin/bash
# Ejecuta un comando dentro del entorno de Termux del emulador (o móvil) vía adb run-as.
# Uso: scripts/tx.sh 'pkg install -y nodejs-lts'
# Requiere la build "github-debug" de Termux (run-as solo funciona con apps depurables).
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-emulator-5554}"
P=/data/data/com.termux/files/usr
H=/data/data/com.termux/files/home

PRELOAD=""
for lib in libtermux-exec-ld-preload.so libtermux-exec.so; do
  if adb -s "$SERIAL" shell run-as com.termux test -f "$P/lib/$lib" 2>/dev/null; then
    PRELOAD="$P/lib/$lib"
    break
  fi
done

# El comando va en base64 para no pelearse con las comillas de adb shell.
CMD_B64=$(printf '%s' "$1" | base64)

adb -s "$SERIAL" shell run-as com.termux env \
  PREFIX="$P" HOME="$H" TMPDIR="$P/tmp" LANG=en_US.UTF-8 TERM=xterm-256color \
  PATH="$P/bin" LD_PRELOAD="$PRELOAD" \
  "$P/bin/bash" -c "\"cd $H && echo $CMD_B64 | base64 -d | bash -l\""
