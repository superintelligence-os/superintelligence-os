#!/data/data/com.termux/files/usr/bin/bash
# Arranca (o reinicia) el servicio de SIOS en segundo plano.
# El wake lock evita que Android duerma Termux mientras el agente trabaja.
set -u
DIR="$HOME/sios/servicio"
LOG="$HOME/sios/servicio.log"

termux-wake-lock
adb connect 127.0.0.1:5555 >/dev/null 2>&1 || true

pkill -f "node $DIR/servicio.mjs" 2>/dev/null && sleep 1
nohup node "$DIR/servicio.mjs" >>"$LOG" 2>&1 &
sleep 1
if pgrep -f "node $DIR/servicio.mjs" >/dev/null; then
  echo "SIOS en marcha: http://127.0.0.1:8765"
else
  echo "No arrancó. Últimas líneas del registro:"
  tail -20 "$LOG"
  exit 1
fi
