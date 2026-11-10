#!/bin/bash
# Prepara un emulador «sios» que arranca la ROM SIOS (rom/salida) sobre el hardware
# virtual del emulador de Android 16. Se puede repetir tras cada `rom/construir.sh`.
#   rom/emulador/preparar.sh && rom/emulador/arrancar.sh          (edición con Google)
#   rom/emulador/preparar.sh --puro && rom/emulador/arrancar.sh   (edición sin Google)
#   rom/emulador/preparar.sh --conservar-datos                     (como una actualización: cambia el
#                                                                   sistema y mantiene apps, cuentas y sesión)
#   rom/emulador/preparar.sh --solo-imagen                         (solo la imagen de sistema, sin crear ningún
#                                                                   emulador: la usa SIOS Desktop al instalar)
set -euo pipefail

RAIZ="$(cd "$(dirname "$0")/../.." && pwd)"
SDK=/opt/homebrew/share/android-commandlinetools
ORIGEN="$SDK/system-images/android-36.1/google_apis/arm64-v8a"
DESTINO="$SDK/system-images/android-36.1/sios/arm64-v8a"
PATRON="sios-[0-9]*.img"; CONSERVAR=0; SOLO=0
for a in "$@"; do
  case "$a" in
    --puro) PATRON="sios-puro-*.img" ;;
    --conservar-datos) CONSERVAR=1 ;;
    --solo-imagen) SOLO=1 ;;
  esac
done
GSI="$(ls "$RAIZ"/rom/salida/$PATRON | head -1)"
E2=/opt/homebrew/opt/e2fsprogs/sbin
TRABAJO="$RAIZ/rom/emulador/trabajo"
AVD=~/.android/avd/sios.avd

rm -rf "$TRABAJO" && mkdir -p "$TRABAJO"

echo "● Vendor del emulador sin product ni system_ext en el fstab"
python3 - "$ORIGEN/system.img" "$TRABAJO/vendor.img" <<'EOF'
import struct, sys
orig, salida = sys.argv[1:3]
SUPER = 4096 * 512
with open(orig, "rb") as f:
    f.seek(SUPER + 4096 + 8192); cab = f.read(256)
    tam_cab = struct.unpack("<I", cab[8:12])[0]; tam_t = struct.unpack("<I", cab[44:48])[0]
    f.seek(SUPER + 4096 + 8192 + tam_cab); t = f.read(tam_t)
    po, pn, ps = struct.unpack("<III", cab[80:92]); eo, en, es = struct.unpack("<III", cab[92:104])
    ext = [struct.unpack("<QIQI", t[eo + i * es:eo + (i + 1) * es]) for i in range(en)]
    for i in range(pn):
        p = t[po + i * ps:po + (i + 1) * ps]
        if p[:36].rstrip(b"\0") == b"vendor":
            primera = struct.unpack("<I", p[40:44])[0]
            sectores, _, destino, _ = ext[primera]
            f.seek(SUPER + destino * 512)
            open(salida, "wb").write(f.read(sectores * 512))
EOF
"$E2/debugfs" -R "dump /etc/fstab.ranchu $TRABAJO/fstab.ranchu" "$TRABAJO/vendor.img" 2>/dev/null
CONTEXTO=$("$E2/debugfs" -R "ea_get /etc/fstab.ranchu security.selinux" "$TRABAJO/vendor.img" 2>/dev/null | grep -o 'u:object_r:[a-z_]*:s0')
grep -v -E "^(product|system_ext)[[:space:]]" "$TRABAJO/fstab.ranchu" > "$TRABAJO/fstab.nuevo"
printf '%s\0' "$CONTEXTO" > "$TRABAJO/contexto"
"$E2/debugfs" -w "$TRABAJO/vendor.img" >/dev/null 2>&1 <<EOF
cd /etc
rm fstab.ranchu
write $TRABAJO/fstab.nuevo fstab.ranchu
sif fstab.ranchu mode 0100644
sif fstab.ranchu uid 0
sif fstab.ranchu gid 0
ea_set -f $TRABAJO/contexto fstab.ranchu security.selinux
EOF
echo "  contexto del fstab: $CONTEXTO"
"$E2/debugfs" -R "cat /etc/fstab.ranchu" "$TRABAJO/vendor.img" 2>/dev/null | grep -E "logical" | awk '{print "  monta:", $1, $2}' | sort -u

echo "● Sistema depurable solo para el emulador (ADB con shell y root, como las imágenes oficiales)"
# La ROM de rom/salida no se toca: se clona y solo en la copia se cambia build.prop.
GSI_EMU="$TRABAJO/system-emulador.img"
cp -c "$GSI" "$GSI_EMU"
"$E2/debugfs" -R "dump /system/build.prop $TRABAJO/build.prop" "$GSI_EMU" 2>/dev/null
sed -i '' -e 's/^ro.debuggable=.*/ro.debuggable=1/' "$TRABAJO/build.prop"
grep -q "^ro.adb.secure=" "$TRABAJO/build.prop" && sed -i '' 's/^ro.adb.secure=.*/ro.adb.secure=0/' "$TRABAJO/build.prop" || echo "ro.adb.secure=0" >> "$TRABAJO/build.prop"
printf 'u:object_r:system_file:s0\0' > "$TRABAJO/contexto-system"
"$E2/debugfs" -w "$GSI_EMU" >/dev/null 2>&1 <<EOF
cd /system
rm build.prop
write $TRABAJO/build.prop build.prop
sif build.prop mode 0100600
sif build.prop uid 0
sif build.prop gid 0
ea_set -f $TRABAJO/contexto-system build.prop security.selinux
EOF
"$E2/debugfs" -R "cat /system/build.prop" "$GSI_EMU" 2>/dev/null | grep -E "^ro.(debuggable|adb.secure|sios.version)" | sed 's/^/  /'
# El emulador se maneja por ADB en el puerto 5555 abierto; se mantienen (vsock y tcp) y se añade 127.0.0.1:5559
# para comprobar que la configuración «solo localhost» de la ROM funciona.
"$E2/debugfs" -R "cat /system/etc/init/sios.rc" "$GSI_EMU" 2>/dev/null | sed 's#tcp:localhost:5555#vsock:5555,tcp:5555,tcp:localhost:5559#' > "$TRABAJO/sios.rc"
"$E2/debugfs" -w "$GSI_EMU" >/dev/null 2>&1 <<EOF
cd /system/etc/init
rm sios.rc
write $TRABAJO/sios.rc sios.rc
sif sios.rc mode 0100644
sif sios.rc uid 0
sif sios.rc gid 0
ea_set -f $TRABAJO/contexto-system sios.rc security.selinux
EOF
grep listen_addrs "$TRABAJO/sios.rc" | sed 's/^ */  /'
GSI="$GSI_EMU"

echo "● Imagen de sistema con SIOS"
rm -rf "$DESTINO" && mkdir -p "$DESTINO"
for f in "$ORIGEN"/*; do
  [ "$(basename "$f")" = "system.img" ] && continue
  cp -c -R "$f" "$DESTINO/"
done
python3 "$RAIZ/rom/emulador/imagen_emulador.py" "$ORIGEN/system.img" "$GSI" "$TRABAJO/vendor.img" "$DESTINO/system.img"
sed -i '' -e 's/^SystemImage.TagId=.*/SystemImage.TagId=sios/' -e 's/^SystemImage.TagDisplay=.*/SystemImage.TagDisplay=SIOS/' "$DESTINO/source.properties" 2>/dev/null || true

if [ "$SOLO" = 1 ]; then
  echo "Listo: imagen de SIOS para emulador en $DESTINO"
  exit 0
fi

if [ "$CONSERVAR" = 1 ] && [ -f "$AVD/userdata-qemu.img" ]; then
  # Los datos (userdata-qemu.img) van aparte de la imagen de sistema: se conservan tal cual.
  echo "● Emulador «sios»: sistema nuevo, datos conservados"
  echo "Listo. Arranca con: rom/emulador/arrancar.sh"
  exit 0
fi

echo "● Emulador «sios» (datos limpios: primer arranque de verdad)"
rm -rf "$AVD" ~/.android/avd/sios.ini
cp -R ~/.android/avd/sios36.avd "$AVD"
rm -rf "$AVD"/*.img "$AVD"/*.qcow2 "$AVD"/snapshots "$AVD"/*.lock "$AVD"/data "$AVD"/tmpAdbCmds 2>/dev/null || true
sed -i '' -e 's#^image.sysdir.1=.*#image.sysdir.1=system-images/android-36.1/sios/arm64-v8a/#' \
  -e 's#^tag.id=.*#tag.id=sios#' -e 's#^tag.ids=.*#tag.ids=sios#' \
  -e 's#^tag.display=.*#tag.display=SIOS#' -e 's#^tag.displaynames=.*#tag.displaynames=SIOS#' \
  -e 's#^avd.name=.*#avd.name=sios#' -e 's#^AvdId=.*#AvdId=sios#' "$AVD/config.ini"
cat > ~/.android/avd/sios.ini <<EOF
avd.ini.encoding=UTF-8
path=$AVD
path.rel=avd/sios.avd
target=android-36.1
EOF
grep -E "image.sysdir|tag.id=" "$AVD/config.ini"
echo "Listo. Arranca con: rom/emulador/arrancar.sh"
