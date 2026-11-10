# Se carga con «source»: deja en FIRMA los argumentos de apksigner. Con la clave de SIOS
# (~/.sios/firma.properties, o la ruta de SIOS_FIRMA) si está; si no, con la de pruebas de Android
# (quien compila por su cuenta). La contraseña va por una variable de entorno, nunca en la orden.
FP="${SIOS_FIRMA:-$HOME/.sios/firma.properties}"
if [ -f "$FP" ]; then
  export SIOS_KS_PASS="$(grep '^storePassword=' "$FP" | cut -d= -f2-)"
  FIRMA=(--ks "$(grep '^storeFile=' "$FP" | cut -d= -f2-)" --ks-key-alias "$(grep '^keyAlias=' "$FP" | cut -d= -f2-)"
         --ks-pass env:SIOS_KS_PASS --key-pass env:SIOS_KS_PASS)
else
  echo "  (sin clave de SIOS: se firma con la de pruebas de Android)"
  FIRMA=(--ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android)
fi
