#!/bin/bash
# Integra la app de SIOS en el sistema (sin root, con permisos de ADB):
#   - pantalla de inicio de SIOS (sustituye al inicio de Google)
#   - SIOS como asistente digital
#   - recuadro de uso el primero de los ajustes rápidos
#   - tema oscuro de SIOS como color del sistema
# El tamaño ancho del recuadro se elige a mano: lápiz del panel → tocar «SIOS» → arrastrar el borde.
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-emulator-5554}"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
APK="$RAIZ/app/app/build/outputs/apk/debug/app-debug.apk"
s() { adb -s "$SERIAL" shell "$@"; }

if [ "${1:-}" != "--sin-instalar" ]; then
  (cd "$RAIZ/app" && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug -q -PcodigoVersion=$(( ($(date +%s) - 1767225600) / 60 )))
  adb -s "$SERIAL" install -r "$APK" >/dev/null
  echo "App instalada."
fi

s cmd package set-home-activity com.sios/.inicio.InicioActivity >/dev/null
s cmd role add-role-holder android.app.role.ASSISTANT com.sios 0
echo "Inicio y asistente: SIOS."

# `add-tile` lo pone el primero. Si ya está no se toca: quitarlo y añadirlo pierde el tamaño ancho.
if s settings get secure sysui_qs_tiles | grep -q "com.sios/.sistema.UsoTile"; then
  echo "Recuadro de uso: ya estaba en el panel."
else
  s cmd statusbar add-tile com.sios/.sistema.UsoTile
  echo "Recuadro de uso: el primero del panel (ensánchalo a mano para ver el porcentaje)."
fi

s cmd uimode night yes >/dev/null
# Como en SIOS: tema oscuro del sistema.
s "settings put secure theme_customization_overlay_packages '{\"android.theme.customization.color_source\":\"preset\",\"android.theme.customization.system_palette\":\"D97757\",\"android.theme.customization.accent_color\":\"D97757\",\"android.theme.customization.theme_style\":\"TONAL_SPOT\",\"_applied_timestamp\":$(date +%s)000}'"
echo "Tema: oscuro de SIOS."

s input keyevent KEYCODE_HOME
