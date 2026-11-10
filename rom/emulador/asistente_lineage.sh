#!/bin/bash
# Recorre el asistente de bienvenida (el de LineageOS y, en la edición con Google, también el de
# Google) como un usuario: sin cuenta de Google, sin PIN, sin copiar datos. En español o en inglés.
# Para pruebas en el emulador «sios».
set -uo pipefail
S="${1:-emulator-5556}"

# Botones que avanzan sin configurar nada, en orden de preferencia.
AVANZAR='Omitir|Saltar|Saltar de todos modos|No copiar|Ahora no|No, gracias|Aceptar|Más|Siguiente|Empezar|Comenzar|Hecho|Listo|Skip|Skip anyway|Don.t copy|Not now|No thanks|Accept|More|Next|Start|Done|Got it|I agree'

tocar_primero() {  # toca el primer botón visible cuyo texto encaje con el patrón
  adb -s "$S" shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  local xml b
  xml=$(adb -s "$S" shell cat /sdcard/u.xml)
  for palabra in $(echo "$1" | tr '|' '\n' | tr ' ' '~'); do
    palabra=${palabra//\~/ }
    b=$(echo "$xml" | grep -oE "<node[^>]*(text|content-desc)=\"$palabra\"[^>]*>" | grep -oE 'bounds="[^"]*"' | head -1)
    if [ -n "$b" ]; then
      adb -s "$S" shell input tap $(echo "$b" | python3 -c "import sys,re; a=list(map(int,re.findall(r'\d+',sys.stdin.read()))); print((a[0]+a[2])//2,(a[1]+a[3])//2)")
      echo "  · $palabra"
      return 0
    fi
  done
  return 1
}

foco() { adb -s "$S" shell dumpsys window | grep -o "mCurrentFocus=[^ ]* [^ ]* [^}]*" | sed 's/.*u0 //'; }

adb -s "$S" shell input keyevent KEYCODE_WAKEUP
for _ in $(seq 1 40); do
  f=$(foco)
  case "$f" in
    *sios*) echo "  · ya en Claude"; break ;;
    *ResolverActivity*) echo "  ✗ apareció el selector de inicio"; tocar_primero 'Claude' && tocar_primero 'Siempre|Always' ;;
    *NetworkActivity*) tocar_primero "$AVANZAR" || tocar_primero 'Set up offline|Configurar sin conexión' || sleep 2 ;;  # el emulador va por cable
    *setupwizard*|*SetupChooseLock*|*settings*|*gms*|*restore*) tocar_primero "$AVANZAR" || sleep 2 ;;
    *) sleep 2 ;;
  esac
  sleep 3
done
echo "Pantalla actual: $(foco)"
