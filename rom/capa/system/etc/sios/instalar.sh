#!/data/data/com.termux/files/usr/bin/bash
# SIOS: prepara el agente en Termux.
#   bash /system/etc/sios/instalar.sh
# Instala Node.js y las herramientas de Termux, copia el servicio de SIOS y lo deja arrancando con
# el móvil. Lo propio de cada IA (Claude Code, Codex, Gemini CLI, llama.cpp…) se instala cuando se
# elige esa IA. Se puede repetir sin problema.
#
# Con --auto lo lanza la app de SIOS por detrás (vía preparar.sh): no hay terminal y cada paso se
# avisa a la app.
set -euo pipefail
AUTO=0; [ "${1:-}" = "--auto" ] && AUTO=1

ORIGEN=/system/etc/sios

ACTUAL="inicio"; N=0
avisar() {  # a la app de SIOS: número de paso, texto y, si hay, el error (solo en --auto)
  [ "$AUTO" = 1 ] || return 0
  /system/bin/am broadcast --user 0 -n com.sios/.sistema.Progreso -a com.sios.PROGRESO \
    --es ficha "${SIOS_FICHA:-}" --ei paso "$N" --es texto "$ACTUAL" ${1:+--es error "$1"} >/dev/null 2>&1 || true
}
paso() {  # paso <n> <título>
  N="$1"; ACTUAL="$2"
  printf '\n\033[38;2;200;240;77m●\033[0m \033[1m%s/4 · %s\033[0m\n' "$1" "$2"
  avisar
}
nota() { printf '  %s\n' "$1"; }
# Si algo falla, se dice dónde, en vez de terminar en silencio.
trap 'printf "\n\033[31m✗ Algo falló en «%s».\033[0m Revisa la conexión y vuelve a ejecutar:\n  bash /system/etc/sios/instalar.sh\n" "$ACTUAL"; avisar "Algo falló en «$ACTUAL». Revisa la conexión."; arrancar_anterior' ERR

# Si una actualización falla, el agente que ya había sigue funcionando: se arranca tal cual.
arrancar_anterior() {
  [ "$AUTO" = 1 ] && [ -x ~/sios/iniciar.sh ] && ~/sios/iniciar.sh >/dev/null 2>&1 || true
}

paso 1 "Paquetes de Termux"
# El espejo automático a veces está a medio sincronizar: se fija el oficial.
echo "deb https://packages-cf.termux.dev/apt/termux-main stable main" > "$PREFIX/etc/apt/sources.list"
export DEBIAN_FRONTEND=noninteractive
# Sin «yes |»: con pipefail, la señal que recibe yes al acabar cortaría el instalador.
APT=(-y -q -o Dpkg::Options::=--force-confnew -o Dpkg::Options::=--force-confdef)
# Una instalación cortada a medias (apagar el móvil mientras se instalaba) deja dpkg bloqueado.
dpkg --configure -a >/dev/null 2>&1 || true
apt-get update -q >/dev/null
apt-get "${APT[@]}" full-upgrade >/dev/null
apt-get "${APT[@]}" install nodejs-lts proot-distro termux-api android-tools >/dev/null
# A veces el repositorio publica Node compilado contra otra versión de ICU sin declararlo (pasó
# en nov. 2026: Node pedía libicu 78 y apt instalaba la 79). Si Node no arranca por una librería
# libicu, se instala exactamente la versión que pide.
if ! node --version >/dev/null 2>&1; then
  falta=$(node --version 2>&1 | grep -o 'libicu[a-z0-9]*\.so\.[0-9]*' | head -1 || true)
  mayor=${falta##*.so.}
  if [ -n "$falta" ]; then
    version=$(apt-cache madison libicu | awk -v m="$mayor" '$3 ~ "^" m "\\." { print $3; exit }')
    [ -n "$version" ] && apt-get "${APT[@]}" install --allow-downgrades "libicu=$version" >/dev/null
  fi
  node --version >/dev/null
fi
nota "Node.js $(node --version), proot-distro, Termux:API y ADB."

paso 2 "Servicio de SIOS"
mkdir -p ~/sios/servicio ~/.termux/boot
cp -r "$ORIGEN/servicio/." ~/sios/servicio/
cp "$ORIGEN/termux/iniciar.sh" ~/sios/iniciar.sh
cp "$ORIGEN/termux/android" "$ORIGEN/termux/bt" "$ORIGEN/termux/claude-agente" "$ORIGEN/termux/claude" "$PREFIX/bin/"
cp "$ORIGEN/termux/arranque" ~/.termux/boot/sios
cp "$ORIGEN/termux/navegador" ~/sios/navegador
chmod +x ~/sios/iniciar.sh ~/sios/navegador ~/.termux/boot/sios "$PREFIX/bin/android" "$PREFIX/bin/bt" "$PREFIX/bin/claude-agente" "$PREFIX/bin/claude"
(cd ~/sios/servicio && npm install --no-audit --no-fund >/dev/null 2>&1)
nota "Servicio copiado y listo para arrancar con el móvil."

paso 3 "Conexión con el propio móvil"
nota "SIOS solo deja la depuración dentro del móvil (127.0.0.1)."
nota "Si aparece «¿Permitir depuración?», marca «Permitir siempre» y pulsa Permitir."
adb connect 127.0.0.1:5555 >/dev/null 2>&1 || true
for _ in $(seq 1 60); do
  [ "$(adb -s 127.0.0.1:5555 get-state 2>/dev/null)" = "device" ] && break
  sleep 2
done
if [ "$(adb -s 127.0.0.1:5555 get-state 2>/dev/null)" = "device" ]; then
  nota "Conectado: la IA puede actuar sobre el móvil."
else
  nota "Aún no hay conexión. Acepta «Permitir depuración» y vuelve a ejecutar este instalador."
fi
~/sios/iniciar.sh

paso 4 "Elige tu IA"
# La IA (Claude, ChatGPT, Gemini, Kimi, una clave de API o un modelo en el móvil) se elige en la
# app, que instala lo que necesite cada una (servicio/ia/instalar.sh).
[ "$AUTO" = 1 ] && exit 0
nota "Listo. Abre SIOS y elige tu IA."
