#!/usr/bin/env python3
"""Mete SIOS (GSI, ext4) en una imagen de disco del emulador de Android para probarlo.

El emulador arranca desde system.img: un disco GPT con «vbmeta» y «super». Dentro de super hay
particiones lógicas (formato LP de AOSP). Este script crea un system.img nuevo cuya super contiene:
  system       → la GSI de SIOS
  vendor       → el vendor del emulador, con su fstab sin product ni system_ext (la GSI los lleva dentro)
  system_dlkm  → el del emulador, tal cual
vbmeta no se toca: el fstab del emulador no usa verificación (sin «avb»), así que no la comprueba.

Uso: imagen_emulador.py <system.img del emulador> <gsi.img> <vendor modificado> <salida system.img>
"""
import hashlib
import os
import shutil
import struct
import subprocess
import sys

orig, gsi, vendor_mod, salida = sys.argv[1:5]

SECTOR = 512
SUPER = 4096 * SECTOR  # la partición super empieza en el LBA 4096 del disco
ALINEACION = 2048  # sectores (1 MiB)

with open(orig, "rb") as f:
    f.seek(SUPER + 4096)
    geometria = f.read(4096)
    max_meta, slots = struct.unpack("<II", geometria[40:48])
    f.seek(SUPER + 4096 + 8192)
    cab = f.read(256)
    tam_cab = struct.unpack("<I", cab[8:12])[0]
    tam_tablas = struct.unpack("<I", cab[44:48])[0]
    f.seek(SUPER + 4096 + 8192 + tam_cab)
    tablas = f.read(tam_tablas)


def descriptor(off):
    return struct.unpack("<III", cab[off:off + 12])


def filas(d):
    o, n, s = d
    return [tablas[o + i * s:o + (i + 1) * s] for i in range(n)]


extensiones = [struct.unpack("<QIQI", e) for e in filas(descriptor(92))]
particiones = {}
for p in filas(descriptor(80)):
    nombre = p[:36].rstrip(b"\0").decode()
    _, primera, num, _ = struct.unpack("<IIII", p[36:52])
    particiones[nombre] = extensiones[primera:primera + num]
grupos = filas(descriptor(104))
discos = filas(descriptor(116))
tam_super = struct.unpack("<Q", discos[0][16:24])[0]


def leer_particion(nombre):
    """Devuelve el contenido de una partición lógica del super original."""
    datos = bytearray()
    with open(orig, "rb") as f:
        for sectores, _, destino, _ in particiones[nombre]:
            f.seek(SUPER + destino * SECTOR)
            datos += f.read(sectores * SECTOR)
    return bytes(datos)


# Contenido nuevo de cada partición: (nombre, ruta o bytes)
nuevas = [
    ("system", gsi),
    ("vendor", vendor_mod),
    ("system_dlkm", leer_particion("system_dlkm")),
]

# Copia del disco (clon instantáneo en APFS) sobre la que se escribe la nueva super.
subprocess.run(["cp", "-c", orig, salida], check=True)

tabla_part, tabla_ext = b"", b""
siguiente = ALINEACION  # los primeros 1 MiB de super son de metadatos
with open(salida, "r+b") as f:
    for i, (nombre, origen) in enumerate(nuevas):
        if isinstance(origen, str):
            tam = os.path.getsize(origen)
        else:
            tam = len(origen)
        sectores = (tam + SECTOR - 1) // SECTOR
        if (siguiente + sectores) * SECTOR > tam_super:
            sys.exit(f"No cabe {nombre} en super ({tam_super // 2**20} MiB)")
        f.seek(SUPER + siguiente * SECTOR)
        if isinstance(origen, str):
            with open(origen, "rb") as o:
                shutil.copyfileobj(o, f, 16 << 20)
        else:
            f.write(origen)
        relleno = sectores * SECTOR - tam
        if relleno:
            f.write(b"\0" * relleno)
        # Atributo 1 = solo lectura; grupo 1 = emulator_dynamic_partitions (como el original).
        tabla_part += nombre.encode().ljust(36, b"\0") + struct.pack("<IIII", 1, i, 1, 1)
        tabla_ext += struct.pack("<QIQI", sectores, 0, siguiente, 0)
        print(f"  {nombre:12} {tam / 2**20:8.1f} MiB en el sector {siguiente}")
        siguiente += (sectores + ALINEACION - 1) // ALINEACION * ALINEACION

    tabla_grupos = b"".join(grupos)
    tabla_discos = b"".join(discos)
    t = tabla_part + tabla_ext + tabla_grupos + tabla_discos
    nueva_cab = bytearray(cab[:tam_cab])
    struct.pack_into("<I", nueva_cab, 44, len(t))
    nueva_cab[48:80] = hashlib.sha256(t).digest()
    o = 0
    for off, filas_, tam_fila in [(80, len(nuevas), 52), (92, len(nuevas), 24), (104, len(grupos), 48), (116, len(discos), 64)]:
        struct.pack_into("<III", nueva_cab, off, o, filas_, tam_fila)
        o += filas_ * tam_fila
    nueva_cab[12:44] = b"\0" * 32
    nueva_cab[12:44] = hashlib.sha256(bytes(nueva_cab)).digest()
    metadatos = bytes(nueva_cab) + t
    assert len(metadatos) <= max_meta
    # Dos copias principales y dos de seguridad, una por ranura.
    for copia in range(2 * slots):
        f.seek(SUPER + 4096 + 8192 + copia * max_meta)
        f.write(metadatos.ljust(max_meta, b"\0"))

print(f"Imagen lista: {salida}")
