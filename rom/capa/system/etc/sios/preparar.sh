#!/system/bin/sh
# SIOS: prepara el agente sin abrir Termux.
# Lo lanza la app Claude con RUN_COMMAND (corre como el usuario de Termux, en segundo plano):
#   1. despliega el sistema base de Termux que trae la ROM (lo que Termux haría al abrirse),
#   2. sigue con instalar.sh en modo automático, que avisa a la app de cada paso.
# Se puede repetir: si el sistema base ya está, va directo al instalador.

# Termux arranca las órdenes con PATH apuntando a su propio bin, que aún no existe.
export PATH=/system/bin
F=/data/data/com.termux/files
P=$F/usr
ORIGEN=/system/etc/sios
# Ficha de la app: viaja en cada aviso para que la app sepa que es suyo.
export SIOS_FICHA="${1:-}"

avisar() {  # avisar <paso> <texto> [error]
  /system/bin/am broadcast --user 0 -n com.sios/.sistema.Progreso \
    -a com.sios.PROGRESO --es ficha "$SIOS_FICHA" --ei paso "$1" --es texto "$2" ${3:+--es error "$3"} >/dev/null 2>&1
}

if [ ! -x "$P/bin/bash" ]; then
  avisar 0 "Preparando el sistema"
  # Antes del sistema base ni siquiera existe la carpeta files de Termux.
  mkdir -p "$F/home"
  rm -rf "$F/usr-staging"
  mkdir -p "$F/usr-staging"
  if ! /system/bin/tar -xzf "$ORIGEN/sistema-base.tar.gz" -C "$F/usr-staging"; then
    avisar 0 "Preparando el sistema" "No se pudo desplegar el sistema base."
    exit 10
  fi
  rm -rf "$P"
  mv "$F/usr-staging" "$P"
  mkdir -p "$P/tmp"
fi

export PREFIX="$P" HOME="$F/home" TMPDIR="$P/tmp" LANG=en_US.UTF-8 PATH="$P/bin"
export TERMUX__PREFIX="$P" TERMUX__HOME="$F/home" TERMUX__ROOTFS_DIR="$F"
mkdir -p "$HOME" && cd "$HOME"
for lib in libtermux-exec-ld-preload.so libtermux-exec.so; do
  [ -f "$P/lib/$lib" ] && { export LD_PRELOAD="$P/lib/$lib"; break; }
done

# Segunda fase del sistema base (registra los paquetes en dpkg). Solo la primera vez.
SEGUNDA="$P/etc/termux/bootstrap/termux-bootstrap-second-stage.sh"
if [ -f "$SEGUNDA" ] && [ ! -f "$P/var/lib/sios-base" ]; then
  "$P/bin/bash" "$SEGUNDA" >/dev/null 2>&1 || true
  : > "$P/var/lib/sios-base"
fi

exec "$P/bin/bash" "$ORIGEN/instalar.sh" --auto
