#!/bin/bash
# Tienda libre y apps de serie de SIOS: se descargan de F-Droid con versión fija, se comprueba su SHA-256
# y la firma (certificado del autor o de F-Droid, el mismo que tendrán las actualizaciones) y se meten en
# la capa tal cual, SIN volver a firmarlas: así se actualizan desde su tienda como cualquier app.
#
#   Las dos ediciones:  Neo Store (/system/priv-app: instala y actualiza sin preguntar, con INSTALL_PACKAGES;
#                       ver etc/permissions/privapp-permissions-sios-tienda.xml)
#   Solo la Puro:       Aurora Store (apps de Play sin cuenta de Google), CoMaps (mapas sin conexión) y
#                       Thunderbird (correo), en /system/app (sin privilegios)
#
# Uso:  rom/tienda/descargar.sh <capa> [--puro]   → <capa>/system/{app,priv-app}/<Nombre>/<Nombre>.apk
#       rom/tienda/descargar.sh --comprobar       → dice qué apps tienen versión nueva en F-Droid
# Las descargas se guardan en rom/tienda/build (no se vuelven a bajar si el SHA-256 cuadra).
#
# Para subir de versión: cambiar el código de versión y los dos SHA-256 de la tabla. El SHA-256 del APK
# está en el índice firmado de F-Droid (https://f-droid.org/repo/index-v2.json, «file.sha256»), y el del
# certificado no debería cambiar nunca (si cambia, la app no se podría actualizar desde la tienda).
set -euo pipefail
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
AQUI="$(cd "$(dirname "$0")" && pwd)"
BT=/opt/homebrew/share/android-commandlinetools/build-tools/37.0.0
CACHE="$AQUI/build"
REPO=https://f-droid.org/repo
ARCHIVO=https://f-droid.org/archive   # F-Droid mueve aquí las versiones viejas

# Nombre | paquete | versión (código) | SHA-256 del APK | SHA-256 del certificado de firma | carpeta | edición
APPS="
NeoStore|com.machiav3lli.fdroid|1304|185816d13dcb9f9fe399adaca15d98c7ea243065f83103628fb31a50d4bc1b46|6d8fa0e2d8f4c1d811b85cc733e87d5dac666740c5d73238e2375b81a11761b5|priv-app|todas
AuroraStore|com.aurora.store|76|fd9c75d90d0f4a7c132b9b4a5a2cf1992a45e03b8d8ff988b7dcfbc0db2c4d11|5c83c7672b929955dc0a1db89a5e6ae4389e2eae7ec939956041694e5815f532|app|puro
CoMaps|app.comaps.fdroid|26100519|90b091be84c5a22f306729ae710b40b1cc1acf3918da6629d15da8027866dc46|ca5180daacf1f26a18f98ff4bce2d62bbaaa39361c998b2267ced1bc0d2f932b|app|puro
Thunderbird|net.thunderbird.android|33|473b33da5963d0a4261a834c845aaf8e3878309565e9f40e61f6e5def21565e5|b6524779b3dbbc5ac17a5ac271ddb29dcfbf723578c238e03c3c217811356dd1|app|puro
"

if [ "${1:-}" = --comprobar ]; then
  while IFS='|' read -r nombre paquete version _; do
    [ -n "$nombre" ] || continue
    ultima=$(curl -fsS --max-time 20 "https://f-droid.org/api/v1/packages/$paquete" \
      | python3 -I -c 'import json,sys; print(json.load(sys.stdin).get("suggestedVersionCode",""))' 2>/dev/null || echo "?")
    if [ "$ultima" = "$version" ]; then echo "  $nombre: $version, al día"; else echo "  $nombre: $version → F-Droid recomienda $ultima"; fi
  done <<< "$APPS"
  exit 0
fi

CAPA="${1:?Uso: descargar.sh <capa> [--puro] | --comprobar}"
PURO=0; [ "${2:-}" = --puro ] && PURO=1
mkdir -p "$CACHE"

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

while IFS='|' read -r nombre paquete version hash certificado carpeta edicion; do
  [ -n "$nombre" ] || continue
  [ "$edicion" = puro ] && [ "$PURO" = 0 ] && continue
  apk="$CACHE/${paquete}_${version}.apk"
  if [ ! -f "$apk" ] || [ "$(sha "$apk")" != "$hash" ]; then
    rm -f "$apk.parcial"
    curl -fsSL --retry 3 --max-time 900 -o "$apk.parcial" "$REPO/${paquete}_${version}.apk" \
      || curl -fsSL --retry 3 --max-time 900 -o "$apk.parcial" "$ARCHIVO/${paquete}_${version}.apk" \
      || { echo "  $nombre: no se ha podido descargar ${paquete}_${version}.apk"; exit 1; }
    mv "$apk.parcial" "$apk"
  fi
  # 1. Es exactamente el APK esperado.
  [ "$(sha "$apk")" = "$hash" ] || { echo "  $nombre: el SHA-256 no cuadra ($(sha "$apk"))"; rm -f "$apk"; exit 1; }
  # 2. La firma es válida y es la de siempre (un solo firmante, con el certificado de la tabla).
  firmas=$("$BT/apksigner" verify --print-certs "$apk" 2>&1) || { echo "  $nombre: la firma no es válida"; echo "$firmas"; exit 1; }
  [ "$(grep -c 'certificate SHA-256 digest' <<< "$firmas")" = 1 ] || { echo "  $nombre: esperaba un solo firmante"; exit 1; }
  grep -q "certificate SHA-256 digest: $certificado" <<< "$firmas" || { echo "  $nombre: firmado con otro certificado"; grep 'SHA-256' <<< "$firmas"; exit 1; }
  # 3. Y es el paquete y la versión que dice ser.
  ficha=$("$BT/aapt2" dump badging "$apk" 2>/dev/null | sed -n 1p)
  grep -q "name='$paquete' versionCode='$version'" <<< "$ficha" || { echo "  $nombre: el APK dice ser otro: $ficha"; exit 1; }
  mkdir -p "$CAPA/system/$carpeta/$nombre"
  cp "$apk" "$CAPA/system/$carpeta/$nombre/$nombre.apk"
  printf '  %-12s %-26s %-9s %s MB  firma %s…\n' "$nombre" "$paquete" "$version" "$(( $(stat -f %z "$apk") / 1024 / 1024 ))" "${certificado:0:16}"
done <<< "$APPS"
