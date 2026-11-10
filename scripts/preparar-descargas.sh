#!/bin/bash
# Prepara los archivos de una versión de SIOS para publicarla en GitHub (Releases). Con ellos, la
# web descarga SIOS Desktop y SIOS Desktop descarga e instala el sistema solo.
# Deja en rom/salida/descargas/:
#   SIOS-Desktop-Mac.zip, SIOS-Desktop-Windows.exe   la app del ordenador (nombres fijos: la web
#                                                     enlaza a releases/latest/download/<nombre>)
#   sios-<versión>-puro-arm64.img.gz                  SIOS Puro (sin Google). La edición con Google no
#                                                     se publica: sus apps no se pueden redistribuir.
#   imagenes.json   qué imagen hay de cada edición, con su tamaño y su huella SHA-256
#   imagenes.firma  firma Ed25519 de imagenes.json (la comprueba SIOS Desktop antes de descargar)
# Uso: scripts/preparar-descargas.sh [versión]   (después: gh release create v<versión> rom/salida/descargas/*)
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
CLAVE="$HOME/.sios/actualizaciones.key"
VERSION="${1:-0.1}"
SALIDA="$RAIZ/rom/salida/descargas"
[ -f "$CLAVE" ] || { echo "Falta la clave: ejecuta scripts/clave-actualizaciones.sh"; exit 1; }
PURO="$(ls "$RAIZ"/rom/salida/sios-puro-*-arm64-ab-ext4.img.gz 2>/dev/null | tail -1 || true)"
[ -n "$PURO" ] || { echo "No hay imagen de SIOS Puro en rom/salida: ejecuta rom/construir.sh"; exit 1; }

rm -rf "$SALIDA" && mkdir -p "$SALIDA"
echo "● SIOS Desktop"
VERSION="$VERSION" "$RAIZ/escritorio/construir.sh" >/dev/null
cp "$RAIZ/escritorio/dist/SIOS-Desktop-Mac.zip" "$RAIZ/escritorio/dist/SIOS-Desktop-Windows.exe" "$SALIDA/"

echo "● Imágenes"
entrada() { # edición archivo_origen
  local nombre="sios-$VERSION-$1-arm64.img.gz"
  cp -c "$2" "$SALIDA/$nombre" 2>/dev/null || cp "$2" "$SALIDA/$nombre"
  printf '"%s":{"archivo":"%s","tamano":%s,"sha256":"%s"}' "$1" "$nombre" \
    "$(stat -f%z "$SALIDA/$nombre")" "$(shasum -a 256 "$SALIDA/$nombre" | cut -d' ' -f1)"
}
printf '{"version":"%s","imagenes":{%s}}\n' "$VERSION" "$(entrada puro "$PURO")" > "$SALIDA/imagenes.json"
openssl pkeyutl -sign -inkey "$CLAVE" -rawin -in "$SALIDA/imagenes.json" | base64 > "$SALIDA/imagenes.firma"
# Comprobación con la clave pública que lleva SIOS Desktop.
base64 -d -i "$SALIDA/imagenes.firma" > "$SALIDA/.firma.bin"
openssl pkeyutl -verify -pubin -inkey "$RAIZ/escritorio/recursos/clave-actualizaciones.pem" -rawin \
  -in "$SALIDA/imagenes.json" -sigfile "$SALIDA/.firma.bin" >/dev/null
rm "$SALIDA/.firma.bin"
ls -lh "$SALIDA"
echo "● Listo. Para publicarla: gh release create v$VERSION $SALIDA/* --title \"SIOS $VERSION\""
