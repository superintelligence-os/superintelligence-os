#!/usr/bin/env python3
"""Vectores de Android del símbolo de SIOS (icono de la app, monocromo y uno suelto)."""
import os
from simbolo import ESPECTRO, HUESO, trazo_io

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../app/app/src/main/res/drawable")

def vector(nombre, anillo, punto_relleno, viewport=108, escala=0.56, comentario=""):
    d, (px, py, rp) = trazo_io()
    k = viewport * escala / 100
    off = (viewport - 100 * k) / 2
    circulo = f"M{px - rp:.3f} {py:.3f}a{rp:.3f} {rp:.3f} 0 1 0 {2 * rp:.3f} 0a{rp:.3f} {rp:.3f} 0 1 0 {-2 * rp:.3f} 0Z"
    if punto_relleno == "espectro":
        paradas = "".join(f'<item android:offset="{o if o != "0" else "0"}" android:color="#FF{c[1:]}"/>' for o, c in ESPECTRO).replace('offset=".', 'offset="0.')
        relleno = (f'><aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="{px - rp}" android:startY="{py - rp}" '
                   f'android:endX="{px + rp}" android:endY="{py + rp}">{paradas}</gradient></aapt:attr></path>')
    else:
        relleno = f' android:fillColor="{punto_relleno}"/>'
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- {comentario} Generado por rom/marca/sios/android.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="{viewport}dp" android:height="{viewport}dp" android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">
    <group android:translateX="{off:.3f}" android:translateY="{off:.3f}" android:scaleX="{k:.4f}" android:scaleY="{k:.4f}">
        <path android:pathData="{d}" android:strokeColor="{anillo}" android:strokeWidth="12.5" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000"/>
        <path android:pathData="{circulo}"{relleno}
    </group>
</vector>
'''
    open(os.path.join(RES, f"{nombre}.xml"), "w").write(xml)

def logo_bienvenida():
    """Símbolo de SIOS en el lienzo del logo de LineageOS (512×260) para su asistente de bienvenida:
    anillo en hueso, punto con el espectro, centrado y a unos 210 de alto."""
    d, (px, py, rp) = trazo_io()
    k = 2.85
    cx, cy = (16.75 + 87.25) / 2, (17.5 + 91.25) / 2  # centro del símbolo con su grosor
    tx, ty = 256 - cx * k, 130 - cy * k
    circulo = f"M{px - rp:.3f} {py:.3f}a{rp:.3f} {rp:.3f} 0 1 0 {2 * rp:.3f} 0a{rp:.3f} {rp:.3f} 0 1 0 {-2 * rp:.3f} 0Z"
    paradas = "".join(f'<item android:offset="{float(o):g}" android:color="#FF{c[1:]}"/>' for o, c in ESPECTRO)
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Símbolo de SIOS en el lienzo del logo de LineageOS (512×260). Generado por rom/marca/sios/android.py. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android" xmlns:aapt="http://schemas.android.com/aapt"
    android:width="512dp" android:height="260dp" android:viewportWidth="512" android:viewportHeight="260">
    <group android:translateX="{tx:.3f}" android:translateY="{ty:.3f}" android:scaleX="{k}" android:scaleY="{k}">
        <path android:pathData="{d}" android:strokeColor="#FF{HUESO[1:]}" android:strokeWidth="12.5" android:strokeLineCap="round" android:strokeLineJoin="round" android:fillColor="#00000000"/>
        <path android:pathData="{circulo}"><aapt:attr name="android:fillColor"><gradient android:type="linear" android:startX="{px - rp}" android:startY="{py - rp}" android:endX="{px + rp}" android:endY="{py + rp}">{paradas}</gradient></aapt:attr></path>
    </group>
</vector>
'''
    destino = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../overlay-setupwizard/res/drawable/logo.xml")
    open(destino, "w").write(xml)

logo_bienvenida()
vector("ic_launcher_frente", "#FF" + HUESO[1:], "espectro", comentario="Primer plano del icono de SIOS: anillo en hueso y el punto con el espectro de las IA, en la zona segura.")
vector("ic_launcher_mono", "#FFFFFFFF", "#FFFFFFFF", comentario="Icono de SIOS en un color (iconos temáticos).")
vector("ic_sios", "#FFFFFFFF", "#FFFFFFFF", viewport=24, escala=1.0, comentario="Símbolo de SIOS en un color (notificaciones del sistema).")
print("ok")
