#!/bin/bash
# Prepara una actualización de SIOS firmada: la app, el agente y (opcional) el aviso de ROM.
# Deja en rom/salida/publicar/ estos archivos, que se suben tal cual a cualquier sitio web estático
# (todos juntos, en la misma carpeta):
#   manifiesto.json   qué hay en esta versión (con la huella SHA-256 de cada archivo)
#   manifiesto.firma  firma Ed25519 del manifiesto (la comprueba cada móvil antes de instalar nada)
#   sios.apk        la app
#   servicio.tar.gz   el agente
#   sistema-*.bin     (con --sistema) el sistema para instalar desde el propio móvil (update_engine)
# Uso: scripts/publicar-version.sh -m "Notas de la versión" [--sin-app] [--rom URL_DE_DESCARGA]
#        [--sistema [--sin-diferencial]] [--sin-sistema]
#
# --sistema: publica las imágenes de rom/salida (con Google y sin Google, las que haya) como
#   actualización del sistema A/B: una carga completa de cada una y, si hay una publicada antes
#   (rom/salida/publicado/), otra diferencial desde esa, mucho más pequeña. Firmadas con
#   ~/.sios/sistema.key (scripts/clave-actualizaciones.sh). Los móviles solo las aceptan si su
#   imagen se construyó con el certificado de esa clave.
# Sin --sistema se mantiene el sistema que ya estuviera publicado (manifiesto y archivos), salvo
# con --sin-sistema, que lo retira.
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
CLAVE="$HOME/.sios/actualizaciones.key"
CLAVE_SISTEMA="$HOME/.sios/sistema.key"
SALIDA="$RAIZ/rom/salida/publicar"
PUBLICADO="$RAIZ/rom/salida/publicado"
NOTAS=""
CON_APP=1
ROM_URL=""
SISTEMA=mantener
DIFERENCIAL=1
while [ $# -gt 0 ]; do
  case "$1" in
    -m) NOTAS="$2"; shift ;;
    --sin-app) CON_APP=0 ;;
    --rom) ROM_URL="$2"; shift ;;
    --sistema) SISTEMA=nuevo ;;
    --sin-sistema) SISTEMA=quitar ;;
    --sin-diferencial) DIFERENCIAL=0 ;;
  esac
  shift
done
[ -f "$CLAVE" ] || { echo "Falta la clave: ejecuta scripts/clave-actualizaciones.sh"; exit 1; }
[ -n "$NOTAS" ] || { echo "Pon unas notas con -m \"…\""; exit 1; }
if [ "$SISTEMA" = nuevo ]; then
  [ -f "$CLAVE_SISTEMA" ] || { echo "Falta la clave del sistema: ejecuta scripts/clave-actualizaciones.sh (y vuelve a construir la ROM con ella)"; exit 1; }
  ls "$RAIZ"/rom/salida/sios*-arm64-ab-ext4.img >/dev/null 2>&1 || { echo "No hay imágenes en rom/salida: ejecuta rom/construir.sh"; exit 1; }
fi

VERSION="$(date +%Y%m%d%H%M%S)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
# Lo que ya hubiera publicado del sistema se guarda para volver a ponerlo (sin --sistema).
echo '[]' > "$TMP/sistema.json"
if [ "$SISTEMA" = mantener ] && [ -f "$SALIDA/manifiesto.json" ]; then
  node -e 'const m = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8")); process.stdout.write(JSON.stringify(m.sistema || []));' "$SALIDA/manifiesto.json" > "$TMP/sistema.json"
  mkdir -p "$TMP/sistema-anterior"
  for f in "$SALIDA"/sistema-*.bin; do [ -e "$f" ] && { cp -c "$f" "$TMP/sistema-anterior/" 2>/dev/null || cp "$f" "$TMP/sistema-anterior/"; }; done
fi
rm -rf "$SALIDA" && mkdir -p "$SALIDA"
for f in "$TMP"/sistema-anterior/sistema-*.bin; do
  [ -e "$f" ] && { cp -c "$f" "$SALIDA/" 2>/dev/null || cp "$f" "$SALIDA/"; }
done

if [ "$CON_APP" = 1 ]; then
  echo "● Compilando la app"
  (cd "$RAIZ/app" && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleRelease -q -PcodigoVersion=$(( ($(date +%s) - 1767225600) / 60 )) 2>&1 | grep -v '^w:' || true)
fi
cp "$RAIZ/app/app/build/outputs/apk/release/app-release.apk" "$SALIDA/sios.apk"
AAPT="$(ls /opt/homebrew/share/android-commandlinetools/build-tools/*/aapt2 | tail -1)"
CODIGO="$("$AAPT" dump badging "$SALIDA/sios.apk" 2>/dev/null | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1 || true)"
[ -n "$CODIGO" ] || { echo "No se pudo leer el versionCode de la app"; exit 1; }

echo "● Empaquetando el agente"
mkdir -p "$TMP/servicio/web"
cp "$RAIZ"/servicio/*.mjs "$RAIZ"/servicio/package.json "$RAIZ"/servicio/clave-actualizaciones.pem "$TMP/servicio/"
cp "$RAIZ"/servicio/web/* "$TMP/servicio/web/"
cp -R "$RAIZ"/servicio/ia "$TMP/servicio/"  # el motor de IA: sin él el agente no arranca
printf '{"version":"%s"}\n' "$VERSION" > "$TMP/servicio/version.json"
tar -czf "$SALIDA/servicio.tar.gz" -C "$TMP/servicio" .

# El sistema: una carga completa por edición y, si se puede, la diferencial desde la última publicada.
if [ "$SISTEMA" = nuevo ]; then
  E2=/opt/homebrew/opt/e2fsprogs/sbin
  echo '[]' > "$TMP/sistema.json"
  mkdir -p "$PUBLICADO"
  for IMG in "$RAIZ"/rom/salida/sios*-arm64-ab-ext4.img; do
    PROPS="$("$E2/debugfs" -R "cat /system/build.prop" "$IMG" 2>/dev/null)"
    COMP="$(printf '%s\n' "$PROPS" | sed -n 's/^ro.sios.compilacion=//p' | head -n 1)"
    BASE_ID="$(printf '%s\n' "$PROPS" | sed -n 's/^ro.sios.base=//p' | head -n 1)"
    OTA="$(printf '%s\n' "$PROPS" | sed -n 's/^ro.sios.ota=//p' | head -n 1)"
    case "$BASE_ID" in *VANILLA*) EDICION=puro ;; *) EDICION=google ;; esac
    if [ "$OTA" != 1 ]; then
      echo "  $(basename "$IMG") se construyó sin el certificado del sistema: los móviles que la tengan no podrán actualizarse sin cable"
    fi
    echo "● Sistema $EDICION ($COMP): carga completa"
    python3 "$RAIZ/rom/herramientas/carga_sistema.py" --nueva "$IMG" --clave "$CLAVE_SISTEMA" \
      --salida "$SALIDA/sistema-$EDICION-$COMP.bin" --resumen "$TMP/completa-$EDICION.json" >/dev/null
    DELTA=""
    ANTERIOR="$PUBLICADO/sios-$EDICION.img"
    if [ "$DIFERENCIAL" = 1 ] && [ -f "$ANTERIOR" ]; then
      DESDE="$("$E2/debugfs" -R "cat /system/build.prop" "$ANTERIOR" 2>/dev/null | sed -n 's/^ro.sios.compilacion=//p' | head -n 1)"
      if [ -n "$DESDE" ] && [ "$DESDE" != "$COMP" ]; then
        echo "● Sistema $EDICION: carga diferencial desde $DESDE"
        python3 "$RAIZ/rom/herramientas/carga_sistema.py" --nueva "$IMG" --anterior "$ANTERIOR" --clave "$CLAVE_SISTEMA" \
          --salida "$SALIDA/sistema-$EDICION-$DESDE-$COMP.bin" --resumen "$TMP/delta-$EDICION.json" >/dev/null
        DELTA="$TMP/delta-$EDICION.json"
      fi
    fi
    node -e '
const fs = require("fs");
const [lista, completa, delta, edicion, notas] = process.argv.slice(1);
const l = JSON.parse(fs.readFileSync(lista, "utf8"));
const c = JSON.parse(fs.readFileSync(completa, "utf8"));
const pieza = (r) => ({ archivo: r.archivo, tamano: r.tamano, sha256: r.sha256, espacio: r.espacio, propiedades: r.propiedades });
const s = { edicion, base: c.base, version: c.version, compilacion: c.compilacion, tamano_sistema: c.tamano_sistema, notas, completa: pieza(c) };
if (delta) { const d = JSON.parse(fs.readFileSync(delta, "utf8")); s.diferencial = { desde: d.desde, ...pieza(d) }; }
l.push(s);
fs.writeFileSync(lista, JSON.stringify(l));
' "$TMP/sistema.json" "$TMP/completa-$EDICION.json" "$DELTA" "$EDICION" "$NOTAS"
  done
fi

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }
ROM_COMPILACION="$(grep -a -o 'ro.sios.compilacion=[0-9]*' "$RAIZ/rom/build.prop.ultimo" 2>/dev/null | cut -d= -f2 || true)"
node -e '
const [version, notas, codigo, shaApp, shaServ, romUrl, romComp, sistemaJson] = process.argv.slice(1);
const m = {
  version, notas, publicado: new Date().toISOString(),
  app: { archivo: "sios.apk", sha256: shaApp, version_code: Number(codigo) || 0 },
  servicio: { archivo: "servicio.tar.gz", sha256: shaServ, version },
};
if (romUrl) m.rom = { compilacion: romComp || version, descarga: romUrl, notas };
// Actualización del sistema desde el móvil: una entrada por edición (google / puro).
const sistema = JSON.parse(require("fs").readFileSync(sistemaJson, "utf8"));
if (sistema.length) m.sistema = sistema;
process.stdout.write(JSON.stringify(m, null, 1));
' "$VERSION" "$NOTAS" "$CODIGO" "$(sha "$SALIDA/sios.apk")" "$(sha "$SALIDA/servicio.tar.gz")" "$ROM_URL" "$ROM_COMPILACION" "$TMP/sistema.json" > "$SALIDA/manifiesto.json"
# Sin entradas de sistema que los nombren, los .bin viejos sobran.
for f in "$SALIDA"/sistema-*.bin; do
  [ -e "$f" ] || continue
  grep -q "\"$(basename "$f")\"" "$SALIDA/manifiesto.json" || rm -f "$f"
done

openssl pkeyutl -sign -inkey "$CLAVE" -rawin -in "$SALIDA/manifiesto.json" | base64 > "$SALIDA/manifiesto.firma"
# Las imágenes publicadas se guardan: la próxima vez, las cargas diferenciales salen desde ellas
# (copia instantánea en APFS, no ocupa hasta que cambian).
if [ "$SISTEMA" = nuevo ]; then
  for IMG in "$RAIZ"/rom/salida/sios*-arm64-ab-ext4.img; do
    case "$("$E2/debugfs" -R "cat /system/build.prop" "$IMG" 2>/dev/null | sed -n 's/^ro.sios.base=//p')" in
      *VANILLA*) EDICION=puro ;; *) EDICION=google ;;
    esac
    rm -f "$PUBLICADO/sios-$EDICION.img"
    cp -c "$IMG" "$PUBLICADO/sios-$EDICION.img" 2>/dev/null || cp "$IMG" "$PUBLICADO/sios-$EDICION.img"
  done
fi
echo "● Versión $VERSION firmada en $SALIDA"
ls -la "$SALIDA"
