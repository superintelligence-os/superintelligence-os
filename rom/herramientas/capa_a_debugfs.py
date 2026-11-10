#!/usr/bin/env python3
"""Genera las órdenes de debugfs que copian una capa (árbol de carpetas) dentro de una imagen ext4.

Uso: capa_a_debugfs.py <carpeta_capa> <archivo_contexto> > ordenes.txt

Cada carpeta y archivo de la capa se crea en la imagen con dueño root:root, permisos 0755
(carpetas y .sh) o 0644 (resto) y la etiqueta SELinux u:object_r:system_file:s0, que es la de
todo /system (salvo los de CONTEXTOS_ORIGINALES, que conservan la suya). Los archivos que ya
existan en la imagen se sustituyen.
"""
import os
import sys

capa, contexto = sys.argv[1], sys.argv[2]
ordenes = []

# Archivos de la imagen base que se sustituyen pero deben conservar sus permisos originales.
MODOS_ORIGINALES = {"/system/build.prop": "0100600", "/system/product/etc/build.prop": "0100600"}
# …y su etiqueta SELinux, si no es la de todo /system. phh-on-boot.sh lo lanza init en el dominio
# de la GSI (phhsu_daemon) por su etiqueta: con la de system_file no arrancaría.
CONTEXTOS_ORIGINALES = {"/system/bin/phh-on-boot.sh": "u:object_r:phhsu_exec:s0"}


def archivo_contexto(ruta):
    """El archivo con la etiqueta (terminada en NUL, como la escribe el sistema) para ea_set -f."""
    etiqueta = CONTEXTOS_ORIGINALES.get(ruta)
    if not etiqueta:
        return contexto
    destino = os.path.join(os.path.dirname(os.path.abspath(contexto)), "contexto-" + etiqueta.split(":")[2])
    with open(destino, "wb") as f:
        f.write(etiqueta.encode() + b"\0")
    return destino


def etiquetar(destino, modo):
    ordenes.append(f'sif "{destino}" mode {modo}')
    ordenes.append(f'sif "{destino}" uid 0')
    ordenes.append(f'sif "{destino}" gid 0')
    ordenes.append(f'ea_set -f "{archivo_contexto(destino)}" "{destino}" security.selinux')


for raiz, carpetas, archivos in os.walk(capa):
    carpetas.sort()
    relativa = os.path.relpath(raiz, capa)
    destino_dir = "/" if relativa == "." else "/" + relativa
    if relativa != ".":
        # mkdir falla si ya existe (p. ej. /system/etc): no pasa nada, se ignora.
        ordenes.append(f'mkdir "{destino_dir}"')
        etiquetar(destino_dir, "040755")
    ordenes.append(f'cd "{destino_dir}"')
    for nombre in sorted(archivos):
        if nombre.startswith("."):
            continue
        origen = os.path.join(raiz, nombre)
        ordenes.append(f'rm "{nombre}"')
        ordenes.append(f'write "{origen}" "{nombre}"')
        ruta = destino_dir.rstrip("/") + "/" + nombre
        modo = MODOS_ORIGINALES.get(ruta) or ("0100755" if nombre.endswith(".sh") or "/termux/" in ruta else "0100644")
        etiquetar(ruta, modo)

print("\n".join(ordenes))
