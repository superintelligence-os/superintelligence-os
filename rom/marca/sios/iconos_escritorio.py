#!/usr/bin/env python3
"""Iconos de SIOS Escritorio a partir del símbolo (Chrome sin ventana dibuja el SVG; Pillow
escala y empaqueta): app del Mac (.iconset), .ico de Windows, barra del Mac (plantilla en negro)
y bandeja de Windows."""
import os, subprocess, sys, tempfile
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from simbolo import ESPECTRO, GRAFITO, HUESO, trazo_io

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DEST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../../escritorio/recursos")

def svg(tam, fondo=True, anillo=HUESO, punto="url(#esp)", margen=0.0):
    d, (px, py, rp) = trazo_io()
    paradas = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in ESPECTRO)
    caja = ""
    # Caja visual del símbolo en su rejilla: centro (52, 54.4), unos 70×74.
    cx, cy = 52.0, 54.4
    if fondo:  # cuadrado redondeado del Mac (824/1024 del lienzo, esquinas del 22,5 %)
        lado = 824 / 1024 * 130
        o = (130 - lado) / 2
        caja = f'<rect x="{o:.2f}" y="{o:.2f}" width="{lado:.2f}" height="{lado:.2f}" rx="{lado*0.225:.2f}" fill="{GRAFITO}"/>'
        k = lado * 0.6 / 74
        vb, t = "0 0 130 130", f"translate({65 - cx*k:.3f} {65 - cy*k:.3f}) scale({k:.4f})"
    else:
        vb, t = f"{cx-40-margen} {cy-40-margen} {80+2*margen} {80+2*margen}", ""
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{tam}" height="{tam}" viewBox="{vb}"><defs><linearGradient id="esp" x1="0" y1="0" x2="1" y2="1">{paradas}</linearGradient></defs>'
            f'{caja}<g transform="{t}"><path d="{d}" fill="none" stroke="{anillo}" stroke-width="12.5" stroke-linecap="round" stroke-linejoin="round"/>'
            f'<circle cx="{px}" cy="{py}" r="{rp}" fill="{punto}"/></g></svg>')

def png(contenido, tam, ruta):
    with tempfile.TemporaryDirectory() as tmp:
        h = os.path.join(tmp, "i.html")
        open(h, "w").write(f'<html><body style="margin:0;background:transparent">{contenido}</body></html>')
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--default-background-color=00000000",
                        f"--screenshot={ruta}", f"--window-size={tam},{tam}", "file://" + h], check=True, capture_output=True)

with tempfile.TemporaryDirectory() as tmp:
    grande = os.path.join(tmp, "app.png"); png(svg(1024), 1024, grande)
    app = Image.open(grande).convert("RGBA")
    iconset = os.path.join(DEST, "SIOS.iconset"); os.makedirs(iconset, exist_ok=True)
    for t in (16, 32, 128, 256, 512):
        app.resize((t, t), Image.LANCZOS).save(os.path.join(iconset, f"icon_{t}x{t}.png"))
        app.resize((t * 2, t * 2), Image.LANCZOS).save(os.path.join(iconset, f"icon_{t}x{t}@2x.png"))
    app.save(os.path.join(DEST, "app.ico"), sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    # Barra del Mac: plantilla negra sobre transparente (macOS la tiñe sola), 18 pt a 2×.
    b = os.path.join(tmp, "barra.png"); png(svg(36, fondo=False, anillo="#000", punto="#000", margen=4), 36, b)
    Image.open(b).convert("RGBA").save(os.path.join(DEST, "barra_mac.png"))
    # Bandeja de Windows: blanco con el punto en color.
    t = os.path.join(tmp, "bandeja.png"); png(svg(256, fondo=False, anillo="#fff", margen=4), 256, t)
    Image.open(t).convert("RGBA").save(os.path.join(DEST, "bandeja.ico"), sizes=[(16, 16), (24, 24), (32, 32), (48, 48)])
    # Avisos: el icono de la app.
    app.resize((256, 256), Image.LANCZOS).save(os.path.join(DEST, "avisos.png"))
print("iconos listos")
