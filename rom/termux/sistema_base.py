#!/usr/bin/env python3
"""Sistema base de Termux (bootstrap) listo para la ROM.

Termux trae su sistema base como un zip escondido dentro de libtermux-bootstrap.so y solo lo
instala al abrir su pantalla. SIOS lo deja preparado como .tar.gz en /system/etc/sios:
la app Claude lo despliega por detrás (con el toybox tar de Android) y Termux nunca tiene que
abrirse. Reproduce lo que hace TermuxInstaller: permisos 0700 en ejecutables y los enlaces
de SYMLINKS.txt («destino←ruta»).

Uso: sistema_base.py <termux.apk> <salida.tar.gz>
"""
import io
import sys
import tarfile
import zipfile

apk, salida = sys.argv[1], sys.argv[2]
so = zipfile.ZipFile(apk).read("lib/arm64-v8a/libtermux-bootstrap.so")

# El zip va incrustado tal cual: desde la primera cabecera local hasta el final del directorio central.
ini = so.index(b"PK\x03\x04")
eocd = so.rindex(b"PK\x05\x06")
largo_comentario = int.from_bytes(so[eocd + 20:eocd + 22], "little")
z = zipfile.ZipFile(io.BytesIO(so[ini:eocd + 22 + largo_comentario]))

EJECUTABLES = ("bin/", "libexec", "lib/apt/apt-helper", "lib/apt/methods")
SEGUNDA_FASE = "etc/termux/bootstrap/termux-bootstrap-second-stage.sh"

with tarfile.open(salida, "w:gz", compresslevel=9, format=tarfile.USTAR_FORMAT) as tar:
    vistos = set()

    def carpeta(ruta):
        partes = ruta.split("/")
        for i in range(1, len(partes)):
            d = "/".join(partes[:i])
            if d and d not in vistos:
                vistos.add(d)
                ti = tarfile.TarInfo(d)
                ti.type, ti.mode = tarfile.DIRTYPE, 0o700
                tar.addfile(ti)

    enlaces = []
    for e in z.infolist():
        if e.filename == "SYMLINKS.txt":
            for linea in z.read(e).decode().splitlines():
                if "←" in linea:
                    destino, ruta = linea.split("←", 1)
                    enlaces.append((destino, ruta.removeprefix("./")))
            continue
        if e.is_dir():
            carpeta(e.filename.rstrip("/") + "/x")
            continue
        carpeta(e.filename)
        datos = z.read(e)
        ti = tarfile.TarInfo(e.filename)
        ti.size = len(datos)
        ti.mode = 0o700 if e.filename.startswith(EJECUTABLES) or e.filename == SEGUNDA_FASE else 0o600
        tar.addfile(ti, io.BytesIO(datos))
    for destino, ruta in enlaces:
        carpeta(ruta)
        ti = tarfile.TarInfo(ruta)
        ti.type, ti.linkname, ti.mode = tarfile.SYMTYPE, destino, 0o777
        tar.addfile(ti)
    print(f"  {len(z.infolist())} ficheros, {len(enlaces)} enlaces")
