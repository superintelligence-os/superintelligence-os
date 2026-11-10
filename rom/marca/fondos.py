#!/usr/bin/env python3
"""Fondos de pantalla de SIOS: uno por IA y el propio de SIOS (sin IA elegida). 1080×2400.

Una familia, «objeto iluminado»: el logo de cada IA, entero y en el centro de la mitad baja, como
una pieza de cristal (cuerpo tintado, filo de luz, reflejo en diagonal) con la luz de su marca
saliendo por detrás y bañando el suelo. Base casi negra teñida de su color, grano fino contra las
bandas y la parte de arriba limpia para el reloj.

Los dibuja Chrome sin ventana. Salen en WebP para la app (que cambia el fondo al cambiar de IA) y
el de SIOS, también en PNG, como fondo por defecto de la ROM.

Uso: fondos.py <carpeta de salida>
"""
import os
import re
import subprocess
import sys

from PIL import Image

AQUI = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(AQUI, "sios"))
from simbolo import ESPECTRO, GRAFITO, HUESO, trazo_io  # noqa: E402

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
ANCHO, ALTO = 1080, 2400
CENTRO_Y = 1420  # el objeto, bajo el reloj y las notificaciones
TAM = 560

# id → (logo, base, luz de detrás, segunda luz, tinte del cristal)
FONDOS = {
    "claude": ("claude", "#14110E", "#D97757", "#F0B08A", "#F6C7AE"),
    "chatgpt": ("openai", "#0B1110", "#10A37F", "#63D6B4", "#BDF2E1"),
    "gemini": ("gemini", "#0B0D18", "#4C8DF6", "#D96570", "url(#gem)"),
    "deepseek": ("deepseek", "#0B0E1C", "#4D6BFE", "#8EA2FF", "#C3CEFF"),
    "kimi": ("kimi", "#0B0D14", "#2F6BFF", "#8AB4FF", "#D3E1FF"),
    "mistral": ("mistral", "#120D09", "#FA520F", "#FFD800", "url(#mis)"),
    "grok": ("grok", "#090909", "#BDBDBD", "#5C5C5C", "#F2F2F2"),
    "openrouter": ("openrouter", "#0C0C17", "#6467F2", "#A5A7FF", "#CDCEFF"),
    "local": ("qwen-3d", "#0E0C1A", "#6F5BEF", "#A99BFF", "#CFC6FF"),
}

GRANO = """<filter id="grano" x="0" y="0" width="100%" height="100%"><feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="2" stitchTiles="stitch"/>
<feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 .55 0"/></filter>"""


def html_fondo(base, figura, luz, luz2):
    return f"""<html><body style="margin:0;width:{ANCHO}px;height:{ALTO}px;overflow:hidden;background:{base}">
<svg width="{ANCHO}" height="{ALTO}" style="position:absolute;inset:0">
<defs>{GRANO}
<radialGradient id="luz"><stop offset="0" stop-color="{luz}" stop-opacity=".85"/><stop offset=".45" stop-color="{luz}" stop-opacity=".28"/><stop offset="1" stop-color="{luz}" stop-opacity="0"/></radialGradient>
<radialGradient id="luz2"><stop offset="0" stop-color="{luz2}" stop-opacity=".55"/><stop offset="1" stop-color="{luz2}" stop-opacity="0"/></radialGradient>
<linearGradient id="gem" x1="0" y1="1" x2="1" y2="0"><stop offset="0" stop-color="#4C8DF6"/><stop offset=".55" stop-color="#9B72CB"/><stop offset="1" stop-color="#D96570"/></linearGradient>
<linearGradient id="mis" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FFD800"/><stop offset=".5" stop-color="#FA520F"/><stop offset="1" stop-color="#E10500"/></linearGradient>
<linearGradient id="vidrio" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".30"/><stop offset=".6" stop-color="#fff" stop-opacity=".06"/><stop offset="1" stop-color="#fff" stop-opacity=".02"/></linearGradient>
<linearGradient id="filo" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".95"/><stop offset=".5" stop-color="#fff" stop-opacity=".15"/><stop offset="1" stop-color="#fff" stop-opacity=".45"/></linearGradient>
<linearGradient id="reflejo" x1="0" y1="0" x2="1" y2="1"><stop offset=".30" stop-color="#fff" stop-opacity="0"/><stop offset=".42" stop-color="#fff" stop-opacity=".22"/><stop offset=".50" stop-color="#fff" stop-opacity="0"/></linearGradient>
<filter id="desenfoque" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="60"/></filter>
<linearGradient id="arriba" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{base}"/><stop offset=".42" stop-color="{base}" stop-opacity="0"/></linearGradient>
</defs>
<ellipse cx="{ANCHO / 2}" cy="{CENTRO_Y + 40}" rx="620" ry="760" fill="url(#luz)" filter="url(#desenfoque)"/>
<ellipse cx="{ANCHO / 2 + 260}" cy="{CENTRO_Y + 380}" rx="420" ry="480" fill="url(#luz2)" filter="url(#desenfoque)"/>
<ellipse cx="{ANCHO / 2}" cy="{CENTRO_Y + TAM / 2 + 260}" rx="560" ry="150" fill="{luz}" opacity=".20" filter="url(#desenfoque)"/>
{figura}
<rect width="{ANCHO}" height="{ALTO}" fill="url(#arriba)"/>
<rect width="{ANCHO}" height="{ALTO}" filter="url(#grano)" opacity=".06"/>
</svg></body></html>"""


def figura_logo(nombre, color):
    """El logo como una pieza de cristal: cuerpo tintado, filo de luz y un reflejo en diagonal."""
    s = open(os.path.join(AQUI, "sios", "logos", f"{nombre}.svg")).read()
    vb = [float(v) for v in re.search(r'viewBox="([^"]+)"', s).group(1).split()]
    cuerpo = re.sub(r"<title>.*?</title>", "", re.search(r"<svg[^>]*>(.*)</svg>", s, re.S).group(1))
    cuerpo = re.sub(r'\sfill="[^"]*"', "", cuerpo).replace("currentColor", "")
    regla = ' fill-rule="evenodd"' if "evenodd" in s else ""
    k = TAM / max(vb[2], vb[3])
    t = f'transform="translate({ANCHO / 2 - vb[2] * k / 2},{CENTRO_Y - vb[3] * k / 2}) scale({k}) translate({-vb[0]},{-vb[1]})"'
    return (f'<clipPath id="forma"><g {t}{regla}>{cuerpo}</g></clipPath>'
            f'<g {t}{regla} fill="{color}" opacity=".26">{cuerpo}</g>'
            f'<g {t}{regla} fill="url(#vidrio)">{cuerpo}</g>'
            f'<rect width="{ANCHO}" height="{ALTO}" fill="url(#reflejo)" clip-path="url(#forma)" opacity=".9"/>'
            f'<g {t}{regla} fill="none" stroke="url(#filo)" stroke-width="{1.6 / k}" opacity=".55">{cuerpo}</g>')


def figura_sios():
    d, (px, py, rp) = trazo_io()
    k = TAM / 100
    t = f'transform="translate({ANCHO / 2 - 52 * k},{CENTRO_Y - 50 * k}) scale({k})"'
    paradas = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in ESPECTRO)
    return (f'<defs><linearGradient id="esp" x1="0" y1="0" x2="1" y2="1">{paradas}</linearGradient>'
            f'<radialGradient id="halo"><stop offset="0" stop-color="#E9B44C" stop-opacity=".55"/><stop offset=".5" stop-color="#7B6CF6" stop-opacity=".16"/><stop offset="1" stop-color="#7B6CF6" stop-opacity="0"/></radialGradient></defs>'
            f'<g {t}><circle cx="{px}" cy="{py}" r="{rp * 6}" fill="url(#halo)"/>'
            f'<path d="{d}" fill="none" stroke="{HUESO}" stroke-opacity=".10" stroke-width="12.5" stroke-linecap="round" stroke-linejoin="round"/>'
            f'<circle cx="{px}" cy="{py}" r="{rp}" fill="url(#esp)"/></g>')


def dibujar(html, png):
    ruta = png + ".html"
    open(ruta, "w").write(html)
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--screenshot={png}",
                    f"--window-size={ANCHO},{ALTO}", "file://" + ruta], check=True, capture_output=True)
    os.remove(ruta)


def main():
    salida = os.path.abspath(sys.argv[1])
    os.makedirs(salida, exist_ok=True)
    hechos = []
    png = os.path.join(salida, "sios.png")
    dibujar(html_fondo(GRAFITO, figura_sios(), "#5B4BE0", "#D97757"), png)
    hechos.append(("sios", png))
    for id_, (logo, base, luz, luz2, cristal) in FONDOS.items():
        png = os.path.join(salida, f"{id_}.png")
        dibujar(html_fondo(base, figura_logo(logo, cristal), luz, luz2), png)
        hechos.append((id_, png))
    for id_, png in hechos:
        Image.open(png).convert("RGB").save(png[:-4] + ".webp", "WEBP", quality=96, method=6)
    hoja = Image.new("RGB", (216 * len(hechos), 480), "#000")
    for i, (_, png) in enumerate(hechos):
        hoja.paste(Image.open(png).convert("RGB").resize((216, 480)), (i * 216, 0))
    hoja.save(os.path.join(salida, "fondos-muestra.png"))
    print(f"  {len(hechos)} fondos en {salida}")


main()
