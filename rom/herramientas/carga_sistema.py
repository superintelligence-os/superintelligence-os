#!/usr/bin/env python3
"""Genera la carga útil de una actualización del sistema de SIOS para update_engine (A/B virtual).

Uso:
  carga_sistema.py --nueva NUEVA.img --clave CLAVE_PRIVADA.pem --salida carga.bin
                   [--anterior ANTERIOR.img] [--resumen resumen.json] [--hilos N]

Qué hace: una carga «parcial» (partial_update) que solo trae la partición system. SIOS es una GSI:
la imagen de sistema lleva dentro system_ext y product (/system/system_ext, /system/product), así
que es la única partición que cambia. El resto (boot, vendor, vbmeta, odm…) no viaja: update_engine
copia las de la ranura actual a la otra, así la otra ranura queda con el mismo firmware que ya
arranca y solo cambia el sistema.

- Sin --anterior: carga completa (REPLACE_XZ por trozos de 2 MiB, ZERO para los trozos vacíos).
- Con --anterior (la imagen que tienen ahora los móviles, bit a bit): carga diferencial. Los
  bloques que no cambian van como SOURCE_COPY en el mismo sitio (en A/B virtual no gastan espacio:
  update_engine se los salta) y los que cambian, comprimidos. Si el móvil no tiene exactamente esa
  imagen, update_engine lo detecta con la huella de origen de cada operación y no instala nada.

Formato: el de update_engine (cabecera «CrAU» versión 2, manifiesto DeltaArchiveManifest en
protobuf, firma de los metadatos, datos y firma de la carga). Las firmas son RSA PKCS#1 v1.5 sobre
SHA-256, como las de brillo_update_payload; el móvil las comprueba con los certificados de
/system/etc/security/otacerts.zip (rom/construir.sh mete ahí el de SIOS). Se firma con openssl:
la clave privada nunca sale del Mac y no se lee desde Python.

Requisitos: Python 3 (sin paquetes extra), openssl y debugfs (e2fsprogs) para leer build.prop.
"""
import argparse
import base64
import hashlib
import json
import lzma
import os
import struct
import subprocess
import sys
import tempfile
from concurrent.futures import ProcessPoolExecutor

BLOQUE = 4096
TROZO = 2 * 1024 * 1024  # 512 bloques por operación, como delta_generator
MAX_COPIA = 64 * 1024 * 1024  # una SOURCE_COPY no pasa de aquí (su huella de origen se calcula entera)
VERSION_MAYOR = 2  # kBrilloMajorPayloadVersion
VERSION_MENOR_PARCIAL = 7  # kPartialUpdateMinorPayloadVersion
GRUPO = "sios_dynamic_partitions"
DEBUGFS = os.environ.get("DEBUGFS", "/opt/homebrew/opt/e2fsprogs/sbin/debugfs")

# Tipos de InstallOperation (update_metadata.proto).
REPLACE, SOURCE_COPY, ZERO, REPLACE_XZ = 0, 4, 6, 8


# ---------- protobuf mínimo (solo lo que necesita el manifiesto) ----------

def _varint(n):
    s = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            s.append(b | 0x80)
        else:
            s.append(b)
            return bytes(s)


def pv(campo, valor):  # varint (uint32/uint64/bool/enum)
    return _varint(campo << 3) + _varint(int(valor))


def pb(campo, datos):  # bytes, string o mensaje anidado
    if isinstance(datos, str):
        datos = datos.encode()
    return _varint((campo << 3) | 2) + _varint(len(datos)) + datos


def pf32(campo, valor):  # fixed32
    return _varint((campo << 3) | 5) + struct.pack("<I", valor)


def extent(inicio, num):
    return pv(1, inicio) + pv(2, num)


def particion_info(tam, huella):
    return pv(1, tam) + pb(2, huella)


def operacion(op):
    m = pv(1, op["tipo"])  # «required»: siempre, aunque sea REPLACE (0)
    if op.get("datos_long"):
        m += pv(2, op["datos_ofs"]) + pv(3, op["datos_long"])
    for ini, num in op.get("origen", []):
        m += pb(4, extent(ini, num))
    for ini, num in op["destino"]:
        m += pb(6, extent(ini, num))
    if op.get("huella_datos"):
        m += pb(8, op["huella_datos"])
    if op.get("huella_origen"):
        m += pb(9, op["huella_origen"])
    return m


def firmas(firma, tam_relleno):
    """Signatures { Signature { data (rellena hasta el tamaño de la clave), unpadded_signature_size } }."""
    relleno = firma + b"\0" * (tam_relleno - len(firma))
    return pb(1, pb(2, relleno) + pf32(3, len(firma)))


# ---------- lectura de la imagen ----------

def propiedades_imagen(img):
    """build.prop de la imagen (ext4) con debugfs, sin montarla."""
    r = subprocess.run([DEBUGFS, "-R", "cat /system/build.prop", img], capture_output=True)
    props = {}
    for linea in r.stdout.decode("utf8", "replace").splitlines():
        if "=" in linea and not linea.startswith("#"):
            k, v = linea.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def huella_archivo(ruta, tam=None):
    h = hashlib.sha256()
    with open(ruta, "rb") as f:
        restante = tam if tam is not None else os.path.getsize(ruta)
        while restante > 0:
            b = f.read(min(16 << 20, restante))
            if not b:
                break
            h.update(b)
            restante -= len(b)
    return h.digest()


# ---------- trabajo por trozos (en paralelo) ----------

_NUEVA = _ANTERIOR = None
_TAM_ANTERIOR = 0


def _abrir(nueva, anterior):
    global _NUEVA, _ANTERIOR, _TAM_ANTERIOR
    _NUEVA = open(nueva, "rb")
    if anterior:
        _ANTERIOR = open(anterior, "rb")
        _TAM_ANTERIOR = os.path.getsize(anterior)


def _comprimir(datos):
    """REPLACE_XZ (LZMA2 nivel 6, sin filtros BCJ ni suma de comprobación, como update_engine) o REPLACE si no gana."""
    xz = lzma.compress(datos, format=lzma.FORMAT_XZ, check=lzma.CHECK_NONE,
                       filters=[{"id": lzma.FILTER_LZMA2, "preset": 6}])
    if len(xz) < len(datos):
        return REPLACE_XZ, xz
    return REPLACE, datos


def _trozo(args):
    """Operaciones de un trozo: [(tipo, bloque_inicial, num_bloques, datos | None)]."""
    ofs, largo = args
    _NUEVA.seek(ofs)
    nuevo = _NUEVA.read(largo)
    if len(nuevo) != largo:
        raise RuntimeError(f"Lectura corta en {ofs}")
    primero = ofs // BLOQUE
    nb = largo // BLOQUE
    # Qué bloques son iguales a los de la imagen anterior en el mismo sitio.
    iguales = [False] * nb
    if _ANTERIOR is not None and ofs < _TAM_ANTERIOR:
        _ANTERIOR.seek(ofs)
        viejo = _ANTERIOR.read(min(largo, _TAM_ANTERIOR - ofs))
        for i in range(len(viejo) // BLOQUE):
            a = i * BLOQUE
            iguales[i] = nuevo[a:a + BLOQUE] == viejo[a:a + BLOQUE]
    ops = []
    i = 0
    while i < nb:
        j = i
        while j < nb and iguales[j] == iguales[i]:
            j += 1
        if iguales[i]:
            ops.append((SOURCE_COPY, primero + i, j - i, None))
        else:
            datos = nuevo[i * BLOQUE:j * BLOQUE]
            if datos.count(0) == len(datos):
                ops.append((ZERO, primero + i, j - i, None))
            else:
                tipo, blob = _comprimir(datos)
                ops.append((tipo, primero + i, j - i, blob))
        i = j
    return ops


# ---------- firma con openssl ----------

def firmar(clave, huella):
    """RSA PKCS#1 v1.5 con DigestInfo de SHA-256 (lo mismo que PadRSASHA256Hash + RSA en update_engine)."""
    r = subprocess.run(["openssl", "pkeyutl", "-sign", "-inkey", clave, "-pkeyopt", "digest:sha256"],
                       input=huella, capture_output=True)
    if r.returncode != 0 or not r.stdout:
        raise RuntimeError("openssl no pudo firmar: " + r.stderr.decode(errors="replace").strip())
    return r.stdout


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--nueva", required=True, help="imagen de sistema nueva (ext4, la de rom/salida)")
    ap.add_argument("--anterior", help="imagen que tienen ahora los móviles (para una carga diferencial)")
    ap.add_argument("--clave", required=True, help="clave privada RSA (PEM) de las actualizaciones del sistema")
    ap.add_argument("--salida", required=True, help="carga.bin a crear")
    ap.add_argument("--resumen", help="JSON con la huella, el tamaño y las propiedades para update_engine")
    ap.add_argument("--hilos", type=int, default=os.cpu_count() or 4)
    a = ap.parse_args()

    tam_nueva = os.path.getsize(a.nueva)
    if tam_nueva % BLOQUE:
        sys.exit(f"La imagen nueva mide {tam_nueva} bytes: no es múltiplo de {BLOQUE}")
    props = propiedades_imagen(a.nueva)
    compilacion = props.get("ro.sios.compilacion", "")
    if not compilacion:
        sys.exit("La imagen nueva no es de SIOS (no tiene ro.sios.compilacion en /system/build.prop)")
    # update_engine exige en las cargas parciales la «versión» de cada partición y no deja bajar:
    # se compara con ro.system.build.date.utc del sistema del móvil.
    version_particion = props.get("ro.system.build.date.utc") or props.get("ro.build.date.utc")
    if not version_particion:
        sys.exit("La imagen nueva no tiene ro.system.build.date.utc")
    desde = None
    tam_anterior = 0
    if a.anterior:
        tam_anterior = os.path.getsize(a.anterior)
        if tam_anterior % BLOQUE:
            sys.exit("La imagen anterior no es múltiplo de 4096")
        pa = propiedades_imagen(a.anterior)
        desde = pa.get("ro.sios.compilacion")
        if not desde:
            sys.exit("La imagen anterior no es de SIOS")
        if pa.get("ro.sios.base") != props.get("ro.sios.base"):
            sys.exit(f"Las dos imágenes son de ediciones distintas ({pa.get('ro.sios.base')} y {props.get('ro.sios.base')})")
        if desde == compilacion:
            sys.exit("La imagen anterior es la misma compilación que la nueva")

    # Tamaño de la firma (el de la clave: 512 bytes con RSA-4096).
    tam_firma = len(firmar(a.clave, hashlib.sha256(b"x").digest()))
    blob_firma = len(firmas(b"\0" * tam_firma, tam_firma))

    print(f"● Operaciones ({'diferencial desde ' + desde if desde else 'completa'}, {a.hilos} hilos)", file=sys.stderr)
    trozos = [(o, min(TROZO, tam_nueva - o)) for o in range(0, tam_nueva, TROZO)]
    datos = tempfile.NamedTemporaryFile(prefix="carga-datos-", dir=os.path.dirname(os.path.abspath(a.salida)), delete=False)
    ops = []
    escrito_cow = 0  # bloques que se escriben de verdad (lo que ocupa la instantánea en el móvil)
    try:
        with ProcessPoolExecutor(a.hilos, initializer=_abrir, initargs=(a.nueva, a.anterior)) as pool:
            hecho = 0
            for resultado in pool.map(_trozo, trozos, chunksize=4):
                for tipo, ini, num, blob in resultado:
                    previa = ops[-1] if ops else None
                    if tipo == SOURCE_COPY:
                        # Las copias seguidas se juntan (hasta MAX_COPIA): menos operaciones.
                        if previa and previa["tipo"] == SOURCE_COPY and previa["fin"] == ini \
                                and (previa["fin"] - previa["ini"] + num) * BLOQUE <= MAX_COPIA:
                            previa["fin"] += num
                            continue
                        ops.append({"tipo": SOURCE_COPY, "ini": ini, "fin": ini + num})
                        continue
                    escrito_cow += num
                    if tipo == ZERO:
                        if previa and previa["tipo"] == ZERO and previa["fin"] == ini:
                            previa["fin"] += num
                            continue
                        ops.append({"tipo": ZERO, "ini": ini, "fin": ini + num})
                        continue
                    op = {"tipo": tipo, "ini": ini, "fin": ini + num, "datos_ofs": datos.tell(),
                          "datos_long": len(blob), "huella_datos": hashlib.sha256(blob).digest()}
                    datos.write(blob)
                    ops.append(op)
                hecho += 1
                if hecho % 200 == 0:
                    print(f"  {hecho}/{len(trozos)} trozos", file=sys.stderr)
        datos.flush()
        tam_datos = datos.tell()

        # Huellas de origen de las copias (se leen otra vez de la imagen anterior).
        if a.anterior:
            with open(a.anterior, "rb") as f:
                for op in ops:
                    if op["tipo"] == SOURCE_COPY:
                        f.seek(op["ini"] * BLOQUE)
                        op["huella_origen"] = hashlib.sha256(f.read((op["fin"] - op["ini"]) * BLOQUE)).digest()
        for op in ops:
            op["destino"] = [(op["ini"], op["fin"] - op["ini"])]
            if op["tipo"] == SOURCE_COPY:
                op["origen"] = [(op["ini"], op["fin"] - op["ini"])]

        print("● Huellas de las imágenes", file=sys.stderr)
        particion = pb(1, "system")
        if a.anterior:
            particion += pb(6, particion_info(tam_anterior, huella_archivo(a.anterior)))
        particion += pb(7, particion_info(tam_nueva, huella_archivo(a.nueva)))
        for op in ops:
            particion += pb(8, operacion(op))
        particion += pb(17, version_particion)

        # Grupo de particiones dinámicas propio de SIOS: cada fabricante llama distinto al suyo
        # (qti_dynamic_partitions, main…) y una GSI no puede saberlo. En una carga parcial no se borra
        # nada: system de la otra ranura pasa a este grupo y el resto (vendor, product…) se queda donde está.
        tam_grupo = -(-tam_nueva // (1 << 20)) * (1 << 20)
        grupo = pb(1, GRUPO) + pv(2, tam_grupo) + pb(3, "system")
        dinamicas = pb(1, grupo) + pv(2, True) + pv(3, False)  # instantáneas sí; comprimidas (VABC), no

        manifiesto = (pv(3, BLOQUE) + pv(4, tam_datos) + pv(5, blob_firma) + pv(12, VERSION_MENOR_PARCIAL)
                      + pb(13, particion) + pb(15, dinamicas) + pv(16, True))
        cabecera = b"CrAU" + struct.pack(">QQI", VERSION_MAYOR, len(manifiesto), blob_firma)
        metadatos = cabecera + manifiesto

        print("● Firmando", file=sys.stderr)
        firma_meta = firmas(firmar(a.clave, hashlib.sha256(metadatos).digest()), tam_firma)
        h = hashlib.sha256(metadatos)  # la firma de la carga no cubre la de los metadatos
        datos.seek(0)
        while True:
            b = datos.read(16 << 20)
            if not b:
                break
            h.update(b)
        firma_carga = firmas(firmar(a.clave, h.digest()), tam_firma)
        assert len(firma_meta) == len(firma_carga) == blob_firma

        tmp = a.salida + ".tmp"
        with open(tmp, "wb") as f:
            f.write(metadatos)
            f.write(firma_meta)
            datos.seek(0)
            while True:
                b = datos.read(16 << 20)
                if not b:
                    break
                f.write(b)
            f.write(firma_carga)
        os.replace(tmp, a.salida)
    finally:
        datos.close()
        os.unlink(datos.name)

    huella = huella_archivo(a.salida)
    tam = os.path.getsize(a.salida)
    resumen = {
        "archivo": os.path.basename(a.salida),
        "tamano": tam,
        "sha256": huella.hex(),
        "compilacion": compilacion,
        "desde": desde,
        "base": props.get("ro.sios.base", ""),
        "version": props.get("ro.sios.version", ""),
        "tamano_sistema": tam_nueva,
        # Lo que necesita libre el móvil además de la descarga: la instantánea de los bloques que se
        # escriben (en el hueco de super o en /data) y la copia de la carga para update_engine.
        "espacio": escrito_cow * BLOQUE + tam,
        "operaciones": len(ops),
        # Cabeceras que se pasan a update_engine (applyPayload / update_engine_client --headers).
        "propiedades": {
            "FILE_HASH": base64.b64encode(huella).decode(),
            "FILE_SIZE": str(tam),
            "METADATA_HASH": base64.b64encode(hashlib.sha256(metadatos).digest()).decode(),
            "METADATA_SIZE": str(len(metadatos)),
        },
    }
    if a.resumen:
        with open(a.resumen, "w") as f:
            json.dump(resumen, f, indent=1)
    print(json.dumps(resumen, indent=1))
    print(f"● {a.salida}: {tam / 1048576:.1f} MiB, {len(ops)} operaciones", file=sys.stderr)


if __name__ == "__main__":
    main()
