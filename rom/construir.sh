#!/bin/bash
# Construye SIOS: GSI de LineageOS 23.2 (Android 16) remasterizada con Claude integrado.
#   rom/construir.sh                    → edición con Google (Play Store): compila la app, monta y comprime
#   rom/construir.sh --puro             → edición sin Google
#   rom/construir.sh --sin-app          → reutiliza la última app compilada (se puede combinar)
# Resultado en rom/salida/: la imagen para fastboot (.img), la de DSU (.img.gz) y sus SHA-256.
set -euo pipefail

VERSION=0.1
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
ROM="$RAIZ/rom"
source "$ROM/herramientas/firma.sh"   # clave con la que se firman los overlays (la de SIOS si está)
CON_APP=1; VARIANTE=GAPPS
for a in "$@"; do
  case "$a" in
    --sin-app) CON_APP=0 ;;
    --puro) VARIANTE=VANILLA ;;
    *) echo "Opción desconocida: $a"; exit 1 ;;
  esac
done
BASE_ID="LineageOS-23.2-20260524-$VARIANTE-EXT4"
BASE="$ROM/base/$BASE_ID-GSI.img"
# Con Google es «SIOS»; sin Google, «SIOS Puro».
if [ "$VARIANTE" = GAPPS ]; then EDICION=""; else EDICION="-puro"; fi
NOMBRE="sios$EDICION-$VERSION-lineage23.2-arm64-ab-ext4"
SALIDA="$ROM/salida"
CAPA="$ROM/staging"
E2=/opt/homebrew/opt/e2fsprogs/sbin
BT=/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home

paso() { printf '\n● %s\n' "$1"; }

[ -f "$BASE" ] || { echo "Falta la imagen base: $BASE"; exit 1; }

if [ "$CON_APP" = 1 ]; then
  paso "Compilando la app Claude"
  (cd "$RAIZ/app" && ./gradlew assembleRelease -q -PcodigoVersion=$(( ($(date +%s) - 1767225600) / 60 )))
fi
APK="$RAIZ/app/app/build/outputs/apk/release/app-release.apk"

paso "Fondo de pantalla"
python3 "$ROM/marca/fondos.py" "$ROM/marca/salida/fondos" && cp "$ROM/marca/salida/fondos/sios.png" "$ROM/marca/salida/default_wallpaper.png"
# «Sin densidad» como el original, y xxhdpi/xxxhdpi porque los overlays del fabricante (y el del
# emulador) traen su fondo en esas densidades y, si no, ganarían al nuestro.
rm -rf "$ROM"/overlay-framework/res/drawable-*
for d in nodpi xxhdpi xxxhdpi; do
  mkdir -p "$ROM/overlay-framework/res/drawable-$d"
  cp "$ROM/marca/salida/default_wallpaper.png" "$ROM/overlay-framework/res/drawable-$d/default_wallpaper.png"
done

paso "Compilando los overlays del sistema"
OVERLAYS=()
for O in "$ROM"/overlay-*; do
  # La pantalla final animada del asistente solo con Google: sin Google el mismo logo sale también en
  # la primera pantalla de LineageOS (y diría «Ya está todo listo» al empezar).
  [ "$VARIANTE" != GAPPS ] && [ "$(basename "$O")" = overlay-bienvenidafinal ] && continue
  # Las animaciones de SIOS para el asistente de Google: sin Google no existe ese asistente.
  [ "$VARIANTE" != GAPPS ] && [ "$(basename "$O")" = overlay-googlesetup ] && continue
  N="Sios$(basename "$O" | sed 's/overlay-//' | awk '{print toupper(substr($0,1,1)) substr($0,2)}')"
  rm -rf "$O/build" && mkdir "$O/build"
  "$BT/aapt2" compile --dir "$O/res" -o "$O/build/res.zip"
  "$BT/aapt2" link -o "$O/build/sin-firmar.apk" --manifest "$O/AndroidManifest.xml" \
    -I /opt/homebrew/share/android-commandlinetools/platforms/android-36/android.jar "$O/build/res.zip" \
    --min-sdk-version 31 --target-sdk-version 36
  "$BT/zipalign" -f 4 "$O/build/sin-firmar.apk" "$O/build/alineado.apk"
  "$BT/apksigner" sign "${FIRMA[@]}" \
    --out "$O/build/$N.apk" "$O/build/alineado.apk"
  OVERLAYS+=("$O/build/$N.apk")
  echo "  $N"
done

paso "Animación de arranque"
python3 "$ROM/marca/animacion.py" "$ROM/marca/salida"

paso "Preparando la capa"
rm -rf "$CAPA" && mkdir -p "$CAPA"
cp -R "$ROM/capa/." "$CAPA/"
mkdir -p "$CAPA/system/priv-app/SIOS" "$CAPA/system/product/overlay" \
  "$CAPA/system/app/Termux" "$CAPA/system/app/TermuxAPI" "$CAPA/system/app/TermuxBoot" \
  "$CAPA/system/etc/sios/servicio/web" "$CAPA/system/etc/sios/termux"
cp "$APK" "$CAPA/system/priv-app/SIOS/SIOS.apk"
cp "${OVERLAYS[@]}" "$CAPA/system/product/overlay/"
mkdir -p "$CAPA/system/product/media"
cp "$ROM/marca/salida/bootanimation.zip" "$CAPA/system/product/media/bootanimation.zip"
cp "$ROM/marca/salida/bootanimation.zip" "$CAPA/system/product/media/bootanimation-dark.zip"
# Termux parcheado (acepta las órdenes de la app Claude) y firmado con la clave de SIOS,
# y su sistema base listo para desplegar sin abrir Termux. Se rehace si cambian los originales.
TX="$ROM/termux/build"
if [ ! -f "$TX/sistema-base.tar.gz" ] || [ "$RAIZ/descargas/termux-arm64.apk" -nt "$TX/Termux.apk" ] \
   || [ "$ROM/termux/parchear.sh" -nt "$TX/Termux.apk" ]; then
  "$ROM/termux/parchear.sh"
  python3 "$ROM/termux/sistema_base.py" "$TX/Termux.apk" "$TX/sistema-base.tar.gz"
fi
cp "$TX/Termux.apk" "$CAPA/system/app/Termux/Termux.apk"
cp "$TX/TermuxAPI.apk" "$CAPA/system/app/TermuxAPI/TermuxAPI.apk"
cp "$TX/TermuxBoot.apk" "$CAPA/system/app/TermuxBoot/TermuxBoot.apk"
cp "$TX/sistema-base.tar.gz" "$CAPA/system/etc/sios/sistema-base.tar.gz"
# Tienda libre (Neo Store) y, sin Google, Aurora Store, mapas y correo: de F-Droid con versión fija, SHA-256
# y firma comprobados, sin volver a firmar (se actualizan desde su tienda). Ver rom/tienda/descargar.sh.
if [ "$VARIANTE" = GAPPS ]; then bash "$ROM/tienda/descargar.sh" "$CAPA"; else bash "$ROM/tienda/descargar.sh" "$CAPA" --puro; fi
cp "$RAIZ"/servicio/*.mjs "$RAIZ/servicio/package.json" "$RAIZ/servicio/clave-actualizaciones.pem" "$CAPA/system/etc/sios/servicio/"
printf '{"version":"%s"}\n' "$(date +%Y%m%d%H%M%S)" > "$CAPA/system/etc/sios/servicio/version.json"
cp -R "$RAIZ/servicio/web/." "$CAPA/system/etc/sios/servicio/web/"
# El motor de IA (Claude, ChatGPT, Gemini, Kimi, API y local) vive en servicio/ia: sin él el
# servicio no arranca.
cp -R "$RAIZ/servicio/ia" "$CAPA/system/etc/sios/servicio/"
cp "$RAIZ"/termux/* "$CAPA/system/etc/sios/termux/"
# Las apps del sistema no extraen sus librerías nativas al instalarse: van desempaquetadas
# junto al APK, en lib/arm64 (si no, Termux se cierra al preparar su sistema base).
for d in "$CAPA"/system/app/* "$CAPA"/system/priv-app/*; do
  apk=$(ls "$d"/*.apk 2>/dev/null | head -1)
  [ -n "$apk" ] || continue
  if unzip -l "$apk" 'lib/arm64-v8a/*.so' >/dev/null 2>&1; then
    mkdir -p "$d/lib/arm64"
    unzip -j -o -q "$apk" 'lib/arm64-v8a/*.so' -d "$d/lib/arm64"
    echo "  $(basename "$d"): $(ls "$d/lib/arm64" | wc -l | tr -d ' ') librerías nativas"
  fi
done
# Letra de SIOS en todo el sistema: Montserrat (la de la app Claude, licencia OFL) como familias
# «sios-sans» y «sios-sans-medium», y en lugar de Google Sans en las familias que ya trae la base.
mkdir -p "$CAPA/system/product/fonts" "$CAPA/system/product/etc"
cp "$RAIZ/app/app/src/main/res/font/montserrat.ttf" "$CAPA/system/product/fonts/Montserrat-Sios.ttf"
"$E2/debugfs" -R "dump /system/product/etc/fonts_customization.xml $CAPA/system/product/etc/fonts_customization.xml" "$BASE" 2>/dev/null
python3 - "$CAPA/system/product/etc/fonts_customization.xml" <<'PY'
import sys
p = sys.argv[1]
try:
    s = open(p, encoding="utf8").read()
except FileNotFoundError:
    s = '<?xml version="1.0" encoding="utf-8"?>\n<fonts-modification version="1">\n</fonts-modification>\n'
def familia(nombre, pesos):
    f = "\n".join(
        f'        <font weight="{p}" style="normal">Montserrat-Sios.ttf\n            <axis tag="wght" stylevalue="{v}"/>\n        </font>'
        for p, v in pesos)
    return f'    <family customizationType="new-named-family" name="{nombre}">\n{f}\n    </family>\n'
if "sios-sans" not in s:
    nuevo = familia("sios-sans", [(300, 300), (400, 400), (500, 500), (600, 600), (700, 700)]) + \
            familia("sios-sans-medium", [(400, 500), (500, 600), (700, 700)])
    s = s.replace("</fonts-modification>", nuevo + "</fonts-modification>")
# Android 16 dibuja casi todo (Ajustes, barra de estado, apps del sistema) con las familias
# «google-sans*» y «variable-*» de la tipografía Expressive, no con config_*FontFamily: se pasan a
# Montserrat con el mismo peso. Las cursivas se quedan (Montserrat-Sios es solo redonda).
import re
def a_montserrat(m):
    atributos, cuerpo = m.group(1), m.group(2)
    peso = re.search(r'weight="(\d+)"', atributos)
    peso = int(peso.group(1)) if peso else 400
    eje = re.search(r'tag="wght"\s+stylevalue="([\d.]+)"', cuerpo)
    valor = max(100, min(900, round(float(eje.group(1))))) if eje else peso
    return (f'<font weight="{peso}" style="normal">Montserrat-Sios.ttf\n'
            f'            <axis tag="wght" stylevalue="{valor}"/>\n        </font>')
def familia_a_montserrat(m):
    if "italic" in m.group(1):
        return m.group(0)
    familia = re.sub(r'<font\b([^>]*)>\s*GoogleSans[^<\s]*\.ttf(.*?)</font>', a_montserrat, m.group(0), flags=re.S)
    # Google Sans distinguía algunas entradas del mismo peso por otros ejes (opsz, GRAD); en Montserrat
    # quedan idénticas y Android no arranca con fuentes repetidas en una familia («already been added»).
    vistas = set()
    def unica(f):
        if f.group(0) in vistas:
            return ""
        vistas.add(f.group(0))
        return f.group(0)
    return re.sub(r'\s*<font\b[^>]*>Montserrat-Sios\.ttf.*?</font>', unica, familia, flags=re.S)
s = re.sub(r'<family\b([^>]*name="(?:google-sans|variable-)[^"]*"[^>]*)>.*?</family>', familia_a_montserrat, s, flags=re.S)
open(p, "w", encoding="utf8").write(s)
PY
echo "  Letra: Montserrat como sios-sans y en lugar de Google Sans ($(grep -c Montserrat-Sios "$CAPA/system/product/etc/fonts_customization.xml") entradas)"
# Actualizaciones del sistema sin cable (A/B, con vuelta atrás): update_engine solo acepta cargas
# firmadas con un certificado de otacerts.zip, así que se pone el de SIOS (y se quita el del autor de
# la GSI: con él, cualquiera con su clave podría colar un sistema). El ayudante (sistema-ota.sh y su
# .rc) ya va en la capa; corre en el dominio permisivo de la GSI (phhsu_daemon), así que se comprueba
# que la base lo tiene. Y phh-on-boot.sh, que da cada arranque por bueno nada más terminar de
# arrancar, no lo hace mientras haya una actualización por confirmar (la confirma el ayudante cuando
# SIOS está sano; si no, se vuelve a la versión anterior).
paso "Actualizaciones del sistema sin cable"
CERT="$ROM/ota/clave-sistema.x509.pem"
OTA=0
if [ ! -f "$CERT" ]; then
  echo "  Sin $CERT (créalo con scripts/clave-actualizaciones.sh): esta imagen no aceptará actualizaciones del sistema sin cable"
elif ! "$E2/debugfs" -R "cat /system/system_ext/etc/selinux/system_ext_sepolicy.cil" "$BASE" 2>/dev/null | grep '(typepermissive phhsu_daemon)' >/dev/null; then
  echo "  La base no trae el dominio phhsu_daemon de TrebleDroid: sin actualizaciones del sistema sin cable"
else
  OTA=1
  mkdir -p "$CAPA/system/etc/security"
  CERTS="$(mktemp -d)"
  cp "$CERT" "$CERTS/sios.x509.pem" && touch -t 200901010000 "$CERTS/sios.x509.pem"
  rm -f "$CAPA/system/etc/security/otacerts.zip"
  (cd "$CERTS" && zip -q -X "$CAPA/system/etc/security/otacerts.zip" sios.x509.pem)
  rm -rf "$CERTS"
  echo "  otacerts.zip: solo el certificado de SIOS ($(openssl x509 -in "$CERT" -noout -fingerprint -sha256 | cut -d= -f2 | cut -c1-23)…)"
  mkdir -p "$CAPA/system/bin"
  "$E2/debugfs" -R "dump /system/bin/phh-on-boot.sh $CAPA/system/bin/phh-on-boot.sh" "$BASE" 2>/dev/null
  if [ -s "$CAPA/system/bin/phh-on-boot.sh" ]; then
    sed -i '' -E 's@^([[:space:]]*)bootctl mark-boot-successful$@\1[ -f /data/misc/sios-ota/pendiente ] || bootctl mark-boot-successful  # SIOS: con una actualización por confirmar, lo hace sistema-ota.sh@' "$CAPA/system/bin/phh-on-boot.sh"
    if grep -v 'sios-ota/pendiente' "$CAPA/system/bin/phh-on-boot.sh" | grep -q 'mark-boot-successful'; then
      echo "phh-on-boot.sh marca el arranque de una forma que no se reconoce: revisa construir.sh"; exit 1
    fi
    echo "  phh-on-boot.sh: no confirma el arranque si hay una actualización pendiente"
  else
    rm -f "$CAPA/system/bin/phh-on-boot.sh"
  fi
fi
if [ "$OTA" = 0 ]; then
  # Sin certificado propio el ayudante no tiene nada que hacer: fuera de la imagen.
  rm -f "$CAPA/system/etc/sios/sistema-ota.sh" "$CAPA/system/etc/init/sios-ota.rc"
fi
find "$CAPA" -name ".DS_Store" -delete

paso "Copiando y ampliando la imagen base"
mkdir -p "$SALIDA"
IMG="$SALIDA/$NOMBRE.img"
cp "$BASE" "$IMG"
MB_CAPA=$(du -sm "$CAPA" | cut -f1)
python3 -c "import os,sys; p=sys.argv[1]; os.truncate(p, os.path.getsize(p) + (int(sys.argv[2]) + 64) * 1024 * 1024)" "$IMG" "$MB_CAPA"
"$E2/e2fsck" -fy "$IMG" >/dev/null 2>&1 || true
"$E2/resize2fs" "$IMG" >/dev/null 2>&1
echo "  Capa: ${MB_CAPA} MB · imagen: $(( $(stat -f %z "$IMG") / 1024 / 1024 )) MB"

paso "Marca y versión en build.prop"
TMP="$(mktemp -d)"
"$E2/debugfs" -R "dump /system/build.prop $TMP/build.prop" "$IMG" 2>/dev/null
"$E2/debugfs" -R "dump /system/product/etc/build.prop $TMP/product.prop" "$IMG" 2>/dev/null
# Número de compilación visible en Ajustes; la huella (fingerprint) no se toca por compatibilidad.
sed -i '' -E "s/^ro.build.display.id=(.*)$/ro.build.display.id=SIOS $VERSION (\1)/" "$TMP/build.prop"
# Idioma del primer arranque: español (se puede cambiar en la primera pantalla del asistente).
sed -i '' -E "s/^ro.product.locale=.*/ro.product.locale=es-ES/" "$TMP/build.prop" "$TMP/product.prop"
{
  printf '\n# SIOS\n'
  printf 'ro.sios.version=%s\n' "$VERSION"
  printf 'ro.sios.base=%s\n' "$BASE_ID"
  # Marca de esta compilación: si cambia tras una actualización, la app pone al día el agente.
  printf 'ro.sios.compilacion=%s\n' "$(date +%Y%m%d%H%M%S)"
  # Canal de actualizaciones (carpeta web con manifiesto.json firmado). CANAL=… al construir.
  printf 'ro.sios.actualizaciones=%s\n' "${CANAL:-}"
  # 1: se puede actualizar el sistema desde el propio móvil (A/B virtual, carga firmada por SIOS).
  printf 'ro.sios.ota=%s\n' "$OTA"
  # El agente vive en Termux (Node + Claude Code y sus procesos hijos): sin el límite de «procesos
  # fantasma» de Android 12+, que los mata cuando pasan de 32 y corta a Claude a media tarea.
  printf 'persist.sys.fflag.override.settings_enable_monitor_phantom_procs=false\n'
  # Modo escritorio con un monitor: las «funciones de escritorio» de Android 16 (pantallas conectadas,
  # barra de tareas en el monitor…). Con overlay-framework/res/values/escritorio.xml; la app pone el resto.
  printf 'persist.wm.debug.desktop_experience_devopts=true\n'
} >> "$TMP/build.prop"
# Versión que Ajustes muestra en «Información del teléfono» (se mantiene la referencia a la base).
sed -i '' -E "s/^ro.lineage.display.version=.*$/ro.lineage.display.version=SIOS $VERSION (LineageOS 23.2)/" "$TMP/product.prop"
sed -i '' -E "s/^ro.lineage.version=.*$/ro.lineage.version=SIOS $VERSION (LineageOS 23.2)/" "$TMP/product.prop"
mkdir -p "$CAPA/system/product/etc"
cp "$TMP/build.prop" "$CAPA/system/build.prop"
cp "$TMP/product.prop" "$CAPA/system/product/etc/build.prop"

paso "Metiendo la capa en la imagen"
printf 'u:object_r:system_file:s0\0' > "$TMP/contexto"
python3 "$ROM/herramientas/capa_a_debugfs.py" "$CAPA" "$TMP/contexto" > "$TMP/ordenes.txt"
"$E2/debugfs" -w -f "$TMP/ordenes.txt" "$IMG" > "$TMP/debugfs.log" 2>&1 || true
# Los «ya existe» de mkdir y los «no existe» de rm son normales; cualquier otro error, no.
if grep -i -E "error|no space|could not" "$TMP/debugfs.log" | grep -v -i -E "file exists|not found by ext2_lookup|ext2_unlink" ; then
  echo "debugfs ha dado errores: $TMP/debugfs.log"
  exit 1
fi

paso "Comprobando la imagen"
"$E2/e2fsck" -fn "$IMG" > "$TMP/e2fsck.log" 2>&1 || { cat "$TMP/e2fsck.log"; exit 1; }
for ruta in /system/priv-app/SIOS/SIOS.apk /system/product/overlay/SiosFramework.apk \
  /system/etc/sios/instalar.sh /system/etc/sios/servicio/ia/ia.mjs \
  /system/etc/permissions/privapp-permissions-sios.xml /system/etc/sios/sistema-ota.sh \
  /system/etc/init/sios-ota.rc /system/bin/phh-on-boot.sh; do
  contexto=$("$E2/debugfs" -R "ea_get $ruta security.selinux" "$IMG" 2>/dev/null | tr -d '\0' | head -1)
  printf '  %-58s %s\n' "$ruta" "$contexto"
done
grep -a -E "ro.sios|ro.build.display.id" <("$E2/debugfs" -R "cat /system/build.prop" "$IMG" 2>/dev/null) | sed 's/^/  /'
grep -a "ro.lineage.display.version" <("$E2/debugfs" -R "cat /system/product/etc/build.prop" "$IMG" 2>/dev/null) | sed 's/^/  /'

paso "Comprimiendo para DSU"
gzip -c -6 "$IMG" > "$IMG.gz"
(cd "$SALIDA" && shasum -a 256 "$NOMBRE.img" "$NOMBRE.img.gz" > "$NOMBRE.sha256")
rm -rf "$TMP"
ls -la "$SALIDA"
echo
echo "SIOS $VERSION listo en $SALIDA"
