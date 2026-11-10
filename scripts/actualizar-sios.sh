#!/bin/bash
# Pone al día un SIOS ya configurado (el emulador «sios» o un móvil) sin tocar sus datos:
# instala la app Claude compilada y copia el servicio del agente a Termux, y lo reinicia.
# Uso: scripts/actualizar-sios.sh [--sin-app]     (ANDROID_SERIAL elige el dispositivo; por defecto emulator-5556)
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
export PATH="/opt/homebrew/share/android-commandlinetools/platform-tools:$PATH"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5556}"
T=/data/data/com.termux/files

if [ "${1:-}" != "--sin-app" ]; then
  echo "● App Claude"
  APK="$RAIZ/app/app/build/outputs/apk/release/app-release.apk"
  if ! (cd "$RAIZ/app" && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleRelease -q -PcodigoVersion=$(( ($(date +%s) - 1767225600) / 60 )) > /tmp/gradle-sios.log 2>&1); then
    # Si la compilación falla, no se instala la versión anterior como si nada.
    grep -v '^w:' /tmp/gradle-sios.log | head -20
    echo "✗ La app no compila (registro completo: /tmp/gradle-sios.log)"; exit 1
  fi
  grep -v '^w:' /tmp/gradle-sios.log | head -5 || true
  adb install -r "$APK" | tail -1
fi

echo "● Servicio del agente"
TMP=/data/local/tmp/claude-servicio
adb shell "rm -rf $TMP && mkdir -p $TMP/web $TMP/ia"
for f in "$RAIZ"/servicio/*.mjs "$RAIZ"/servicio/package.json "$RAIZ"/servicio/clave-actualizaciones.pem; do adb push "$f" "$TMP/" >/dev/null 2>&1; done
for f in "$RAIZ"/servicio/web/*; do adb push "$f" "$TMP/web/" >/dev/null 2>&1; done
for f in "$RAIZ"/servicio/ia/*; do adb push "$f" "$TMP/ia/" >/dev/null 2>&1; done
adb shell "chmod -R a+rX $TMP"
adb shell "run-as com.termux sh -c 'cp $TMP/*.mjs $TMP/package.json $TMP/*.pem $T/home/sios/servicio/ && cp $TMP/web/* $T/home/sios/servicio/web/ && mkdir -p $T/home/sios/servicio/ia && cp $TMP/ia/* $T/home/sios/servicio/ia/ && chmod 600 $T/home/sios/servicio/*.mjs $T/home/sios/servicio/ia/*.mjs && chmod 700 $T/home/sios/servicio/ia/instalar.sh'"
adb shell "rm -rf $TMP"

echo "● Reiniciando el agente"
adb shell "run-as com.termux $T/usr/bin/env HOME=$T/home PREFIX=$T/usr PATH=$T/usr/bin TMPDIR=$T/usr/tmp LD_PRELOAD=$T/usr/lib/libtermux-exec.so $T/usr/bin/bash $T/home/sios/iniciar.sh" | tail -1
