#!/bin/bash
# Crea (una sola vez) las claves con las que se firman las actualizaciones de SIOS.
# - Ed25519 para el manifiesto (app y agente): la privada en ~/.sios/actualizaciones.key; la
#   pública va al servicio del agente y a la imagen, y la comprueban el agente y la app.
# - RSA-4096 para el sistema (la carga de update_engine): la privada en ~/.sios/sistema.key; su
#   certificado va a rom/ota/clave-sistema.x509.pem y rom/construir.sh lo mete en otacerts.zip.
#   Solo las imágenes construidas con este certificado aceptan actualizaciones del sistema sin cable.
# Las privadas solo las puedes leer tú; no se imprimen ni se copian al proyecto.
set -euo pipefail
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$HOME/.sios"
PRIVADA="$DIR/actualizaciones.key"
PUBLICA="$RAIZ/servicio/clave-actualizaciones.pem"

mkdir -p "$DIR"
chmod 700 "$DIR"
if [ ! -f "$PRIVADA" ]; then
  (umask 077 && openssl genpkey -algorithm ed25519 -out "$PRIVADA" 2>/dev/null)
  echo "Clave privada nueva en $PRIVADA (guárdala en un sitio seguro: sin ella no se pueden publicar actualizaciones)."
else
  echo "Ya había clave privada en $PRIVADA."
fi
openssl pkey -in "$PRIVADA" -pubout -out "$PUBLICA" 2>/dev/null
echo "Clave pública en $PUBLICA"

PRIVADA_SISTEMA="$DIR/sistema.key"
CERTIFICADO="$RAIZ/rom/ota/clave-sistema.x509.pem"
if [ ! -f "$PRIVADA_SISTEMA" ]; then
  (umask 077 && openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$PRIVADA_SISTEMA" 2>/dev/null)
  echo "Clave privada del sistema nueva en $PRIVADA_SISTEMA (guárdala junto a la otra: sin ella los móviles no aceptan sistemas nuevos)."
else
  echo "Ya había clave privada del sistema en $PRIVADA_SISTEMA."
fi
mkdir -p "$(dirname "$CERTIFICADO")"
# El certificado solo se rehace si no es de esta clave (lo que cuenta en el móvil es la clave pública).
if [ ! -f "$CERTIFICADO" ] || [ "$(openssl x509 -in "$CERTIFICADO" -noout -pubkey 2>/dev/null)" != "$(openssl pkey -in "$PRIVADA_SISTEMA" -pubout 2>/dev/null)" ]; then
  openssl req -new -x509 -sha256 -days 10950 -key "$PRIVADA_SISTEMA" -out "$CERTIFICADO" \
    -subj "/O=SIOS/CN=SIOS actualizaciones del sistema" 2>/dev/null
fi
echo "Certificado del sistema en $CERTIFICADO"
