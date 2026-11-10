#!/bin/bash
# Sube el servicio y los atajos de Termux del Mac al emulador o móvil, instala
# dependencias si cambió package.json y reinicia el servicio.
# Uso: scripts/desplegar.sh            (todo)
#      scripts/desplegar.sh --sin-reinicio
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-emulator-5554}"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
TX="$RAIZ/scripts/tx.sh"
T=/data/data/com.termux/files
TMP=/data/local/tmp/sios

adb -s "$SERIAL" shell rm -rf "$TMP"
adb -s "$SERIAL" shell mkdir -p "$TMP/web" "$TMP/bin"
for f in "$RAIZ"/servicio/*.mjs "$RAIZ"/servicio/package.json; do
  adb -s "$SERIAL" push "$f" "$TMP/" >/dev/null
done
adb -s "$SERIAL" push "$RAIZ"/servicio/web/. "$TMP/web/" >/dev/null
adb -s "$SERIAL" push "$RAIZ"/servicio/ia "$TMP/" >/dev/null
adb -s "$SERIAL" push "$RAIZ"/termux/android "$RAIZ"/termux/claude-agente "$RAIZ"/termux/bt "$RAIZ"/termux/navegador "$TMP/bin/" >/dev/null
adb -s "$SERIAL" push "$RAIZ"/termux/iniciar.sh "$TMP/" >/dev/null
adb -s "$SERIAL" shell chmod -R a+r "$TMP"

"$TX" "
set -e
mkdir -p ~/sios/servicio/web
cp $TMP/*.mjs ~/sios/servicio/
cp -r $TMP/web/. ~/sios/servicio/web/
mkdir -p ~/sios/servicio/ia && cp -r $TMP/ia/. ~/sios/servicio/ia/
cp $TMP/iniciar.sh ~/sios/iniciar.sh && chmod +x ~/sios/iniciar.sh
cp $TMP/bin/android $TMP/bin/claude-agente $TMP/bin/bt \$PREFIX/bin/ && chmod +x \$PREFIX/bin/android \$PREFIX/bin/claude-agente \$PREFIX/bin/bt
cp $TMP/bin/navegador ~/sios/navegador && chmod +x ~/sios/navegador
if ! cmp -s $TMP/package.json ~/sios/servicio/package.json; then
  cp $TMP/package.json ~/sios/servicio/package.json
  cd ~/sios/servicio && npm install --no-audit --no-fund 2>&1 | tail -1
fi
"
adb -s "$SERIAL" shell rm -rf "$TMP"
echo "Desplegado."

if [ "${1:-}" != "--sin-reinicio" ]; then
  "$TX" '~/sios/iniciar.sh'
fi
