#!/bin/bash
# Termux para SIOS: permite que Claude le pase órdenes (RUN_COMMAND) sin tener que
# activar «allow-external-apps» a mano, se presenta como «SIOS Core» (es el motor del agente: así sale
# en «Aplicaciones activas») con la terminal como «Terminal», y firma Termux, Termux:API y
# Termux:Boot con la clave de SIOS. Las versiones de GitHub vienen firmadas con una clave de pruebas pública:
# cualquier APK firmado con ella podría compartir el usuario de Termux (y leer la sesión de Claude).
#
# La orden sigue protegida por el permiso RUN_COMMAND, que solo tiene concedido la app Claude.
# Uso: rom/termux/parchear.sh   →   rom/termux/build/{Termux,TermuxAPI,TermuxBoot}.apk
set -euo pipefail
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
AQUI="$(cd "$(dirname "$0")" && pwd)"
RAIZ="$(cd "$AQUI/../.." && pwd)"
source "$RAIZ/rom/herramientas/firma.sh"   # clave con la que se firma Termux (la misma que la app)
BT=/opt/homebrew/share/android-commandlinetools/build-tools/37.0.0
SAL="$AQUI/build"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$SAL"

firmar() {  # firmar <entrada.apk> <salida.apk>
  "$BT/zipalign" -f -p 4 "$1" "$TMP/alineado.apk"
  "$BT/apksigner" sign "${FIRMA[@]}" \
    --out "$2" "$TMP/alineado.apk"
}

# 1. Termux: la comprobación de «allow-external-apps» deja pasar siempre.
apktool d -q -f -o "$TMP/termux" "$RAIZ/descargas/termux-arm64.apk"
SMALI=$(grep -rl "checkIfAllowExternalAppsPolicyIsViolated(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;$" "$TMP/termux"/smali*/com/termux/app/utils/ | head -1)
python3 - "$SMALI" <<'PY'
import re, sys
ruta = sys.argv[1]
t = open(ruta).read()
ini = t.index(".method public static checkIfAllowExternalAppsPolicyIsViolated")
fin = t.index(".end method", ini)
cuerpo = t[ini:fin]
nuevo = cuerpo.replace("if-nez v1, :cond_0", "goto :cond_0", 1)
assert nuevo != cuerpo, "no se encontró la comprobación"
open(ruta, "w").write(t[:ini] + nuevo + t[fin:])
PY
# 1b. Nombre e icono: la app es «SIOS Core» (el motor del agente, con el logo de SIOS); la terminal, «Terminal».
python3 - "$TMP/termux" "$RAIZ/app/app/src/main/res/drawable/ic_launcher_frente.xml" <<'PY'
import os, re, shutil, sys
raiz, frente = sys.argv[1], sys.argv[2]
m = os.path.join(raiz, "AndroidManifest.xml")
t = open(m).read()
app = re.search(r"<application [^>]*>", t).group(0)
nuevo = (app.replace('android:label="@string/application_name"', 'android:label="SIOS Core"')
            .replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@mipmap/ic_sios"')
            .replace('android:roundIcon="@mipmap/ic_launcher_round"', 'android:roundIcon="@mipmap/ic_sios"'))
assert nuevo != app, "no se encontró la etiqueta de la app"
t = t.replace(app, nuevo)
actividad = re.search(r'<activity [^>]*android:name="com.termux.app.TermuxActivity"[^>]*>', t).group(0)
t = t.replace(actividad, actividad.replace('android:label="@string/application_name"', 'android:label="Terminal" android:icon="@mipmap/ic_launcher"'))
open(m, "w").write(t)
os.makedirs(os.path.join(raiz, "res", "drawable"), exist_ok=True)
os.makedirs(os.path.join(raiz, "res", "mipmap-anydpi-v26"), exist_ok=True)
shutil.copy(frente, os.path.join(raiz, "res", "drawable", "ic_sios_frente.xml"))
open(os.path.join(raiz, "res", "mipmap-anydpi-v26", "ic_sios.xml"), "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n'
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
    '    <background android:drawable="@color/sios_fondo" />\n'
    '    <foreground android:drawable="@drawable/ic_sios_frente" />\n'
    '    <monochrome android:drawable="@drawable/ic_sios_frente" />\n'
    '</adaptive-icon>\n')
colores = os.path.join(raiz, "res", "values", "colors.xml")
c = open(colores).read() if os.path.exists(colores) else '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n</resources>\n'
open(colores, "w").write(c.replace("</resources>", '    <color name="sios_fondo">#FF262624</color>\n</resources>'))
PY
apktool b -q -o "$TMP/termux.apk" "$TMP/termux"
firmar "$TMP/termux.apk" "$SAL/Termux.apk"

# 2. Termux:API y Termux:Boot: mismo contenido, nueva firma (comparten usuario con Termux).
for par in "termux-api:TermuxAPI" "termux-boot:TermuxBoot"; do
  cp "$RAIZ/descargas/${par%%:*}.apk" "$TMP/${par%%:*}.apk"
  zip -q -d "$TMP/${par%%:*}.apk" 'META-INF/*'
  firmar "$TMP/${par%%:*}.apk" "$SAL/${par##*:}.apk"
done
for f in "$SAL"/*.apk; do echo "  $(basename "$f"): $("$BT/apksigner" verify --print-certs "$f" | grep -m1 SHA-256 | awk '{print $NF}' | cut -c1-16)…"; done
