#!/usr/bin/env python3
"""Pantalla final del asistente de bienvenida (FinishActivity de LineageOS): «Ya está todo listo ·
Bienvenido a SuperIntelligenceOS», animado.

El símbolo se dibuja con la misma coreografía que la animación de arranque (el cometa que traza el
anillo y el punto que entra con un muelle) y debajo entra el texto letra a letra; «SuperIntelligenceOS»
lleva el espectro de las IA. Un WebP animado con fondo transparente por idioma, que el overlay pone en
lugar del logo con <animated-image autoStart> (se reproduce una vez y se queda en el último fotograma).

Uso: bienvenida_final.py <carpeta res del overlay>
"""
import io
import math
import os
import subprocess
import sys
import tempfile

from PIL import Image

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "sios"))
from simbolo import ESPECTRO, HUESO, trazo_io  # noqa: E402

ANCHO, ALTO, FPS, DURACION, ESCALA = 1080, 1500, 30, 2.8, 1
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
TEXTOS = {  # carpeta de recursos → (título, frase antes del nombre)
    "": ("You’re all set", "Welcome to"),
    "es": ("Ya está todo listo", "Bienvenido a"),
    "de": ("Alles ist bereit", "Willkommen bei"),
    "fr": ("Tout est prêt", "Bienvenue dans"),
    "it": ("È tutto pronto", "Benvenuto in"),
    "pt": ("Está tudo pronto", "Bem-vindo ao"),
}

C, R, T = (52.0, 56.0), 29.0, 12.5
D, (PX, PY, RP) = trazo_io()
ARCO = R * math.radians(284)
LARGO = ARCO + 11.0


def lim(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def tramo(t, a, b):
    return lim((t - a) / (b - a))


def sale(x, p=3.0):
    return 1 - (1 - lim(x)) ** p


def pluma(x):
    x = lim(x)
    return 4 * x ** 3 if x < 0.5 else 1 - (-2 * x + 2) ** 3 / 2


def muelle(x, k=8.5, w=9.5):
    x = lim(x)
    return 1 - math.exp(-k * x) * math.cos(w * x)


def mezcla(a, b, t):
    a = tuple(int(a[i:i + 2], 16) for i in (1, 3, 5))
    b = tuple(int(b[i:i + 2], 16) for i in (1, 3, 5))
    return "#%02x%02x%02x" % tuple(round(a[i] + (b[i] - a[i]) * lim(t)) for i in range(3))


def espectro(f):
    paradas = [(float(o), c) for o, c in ESPECTRO]
    for (o0, c0), (o1, c1) in zip(paradas, paradas[1:]):
        if f <= o1:
            return mezcla(c0, c1, (f - o0) / (o1 - o0))
    return paradas[-1][1]


def punto_en(f):
    s = lim(f) * LARGO
    if s <= ARCO:
        a = math.radians(-104) + s / R
        return C[0] + R * math.cos(a), C[1] + R * math.sin(a)
    return C[0] - R, C[1] - (s - ARCO)


def letras(texto, aparicion, retraso_max=0.5):
    n = max(1, len(texto))
    return "".join(
        f'<tspan opacity="{sale(tramo(aparicion, i / n * retraso_max, i / n * retraso_max + 0.5), 2.4):.3f}">'
        f'{"&#160;" if ch == " " else ch}</tspan>'
        for i, ch in enumerate(texto))


def cuadro(t, titulo, frase):
    paradas = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in ESPECTRO)
    capas = []
    p = pluma(tramo(t, 0.05, 1.0))
    if p > 0:
        capas.append(f'<path d="{D}" pathLength="1" fill="none" stroke="{HUESO}" stroke-width="{T}" stroke-linecap="round" '
                     f'stroke-dasharray="{p:.5f} 3"/>')
        cabeza = 1 - sale(tramo(t, 0.95, 1.2), 2)
        if cabeza > 0.01:
            x, y = punto_en(p)
            capas.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="8" fill="{espectro(p * 0.92)}" opacity="{0.75 * cabeza:.3f}" filter="url(#b)"/>')
            capas.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="3" fill="#fff" opacity="{0.9 * cabeza:.3f}"/>')
    e = muelle(tramo(t, 0.85, 1.55))
    if e > 0.001:
        r = RP * lim(e, 0, 1.25)
        dy = (C[1] - 11.0 - PY) * (1 - muelle(tramo(t, 0.85, 1.6)))
        manchas = "".join(
            f'<circle cx="{PX + r * 0.62 * math.cos(math.radians(90 * i)):.2f}" cy="{PY + dy + r * 0.62 * math.sin(math.radians(90 * i)):.2f}" r="{r:.2f}" fill="{c}"/>'
            for i, (_, c) in enumerate(ESPECTRO))
        halo = 1.3 * sale(tramo(t, 0.9, 1.1)) - 0.85 * sale(tramo(t, 1.1, 2.0))
        capas.append(f'<g opacity="{0.45 * lim(halo, 0, 2):.3f}" filter="url(#h)">{manchas}</g>')
        capas.append(f'<clipPath id="k"><circle cx="{PX}" cy="{PY + dy:.2f}" r="{r:.2f}"/></clipPath>'
                     f'<g clip-path="url(#k)"><circle cx="{PX}" cy="{PY + dy:.2f}" r="{r:.2f}" fill="{ESPECTRO[2][1]}"/>'
                     f'<g filter="url(#o)">{manchas}</g></g>')
    a1 = tramo(t, 1.15, 2.05)
    a2 = tramo(t, 1.55, 2.5)
    simbolo = f'<g transform="translate({ANCHO / 2 - 52 * 3.4:.1f} {430 - 54 * 3.4:.1f}) scale(3.4)">{"".join(capas)}</g>'
    textos = ""
    if a1 > 0:
        textos += (f'<text x="{ANCHO / 2}" y="{905 + 18 * (1 - sale(a1)):.1f}" text-anchor="middle" font-family="Montserrat" font-weight="600" '
                   f'font-size="66" fill="{HUESO}">{letras(titulo, a1)}</text>')
    if a2 > 0:
        textos += (f'<text x="{ANCHO / 2}" y="{990 + 14 * (1 - sale(a2)):.1f}" text-anchor="middle" font-family="Montserrat" font-weight="500" '
                   f'font-size="38" fill="{HUESO}" opacity="{0.75 * sale(a2):.3f}">{frase}&#160;<tspan fill="url(#esp)" font-weight="600">SuperIntelligenceOS</tspan></text>')
    return (f'<svg width="{ANCHO}" height="{ALTO}" viewBox="0 0 {ANCHO} {ALTO}" xmlns="http://www.w3.org/2000/svg">'
            f'<defs><linearGradient id="esp" x1="0" y1="0" x2="1" y2="0">{paradas}</linearGradient>'
            f'<filter id="b" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="3"/></filter>'
            f'<filter id="h" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="7"/></filter>'
            f'<filter id="o" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="3.4"/></filter></defs>'
            f'{simbolo}{textos}</svg>')


def unicos(svg, n):
    return svg.replace('id="', f'id="c{n}').replace("url(#", f"url(#c{n}")


def fotogramas(titulo, frase):
    n = int(DURACION * FPS)
    svgs = [cuadro(i / FPS, titulo, frase) for i in range(n)]
    salida = []
    tanda = 6
    with tempfile.TemporaryDirectory() as tmp:
        for base in range(0, n, tanda):
            grupo = svgs[base:base + tanda]
            html, png = os.path.join(tmp, "t.html"), os.path.join(tmp, "t.png")
            open(html, "w").write('<html><body style="margin:0;line-height:0;background:transparent">'
                                  + "".join(f"<div>{unicos(s, j)}</div>" for j, s in enumerate(grupo)) + "</body></html>")
            subprocess.run([CHROME, "--headless=new", "--hide-scrollbars", "--default-background-color=00000000",
                            f"--screenshot={png}", f"--window-size={ANCHO},{ALTO * len(grupo)}", "--virtual-time-budget=2000",
                            "file://" + html], check=True, capture_output=True)
            im = Image.open(png).convert("RGBA")
            for j in range(len(grupo)):
                salida.append(im.crop((0, j * ALTO, ANCHO, (j + 1) * ALTO)))
    return salida


def main():
    res = sys.argv[1]
    for carpeta, (titulo, frase) in TEXTOS.items():
        cuadros = fotogramas(titulo, frase)
        destino = os.path.join(res, f"drawable{'-' + carpeta if carpeta else ''}-nodpi")
        os.makedirs(destino, exist_ok=True)
        ruta = os.path.join(destino, "bienvenida_final.webp")
        # Una sola vez (loop=1) y el último fotograma se queda; WebP con pérdida y canal alfa.
        cuadros[0].save(ruta, save_all=True, append_images=cuadros[1:], duration=int(1000 / FPS), loop=1,
                        lossless=False, quality=88, method=6, minimize_size=True)
        print(f"  {carpeta or 'en'}: {len(cuadros)} fotogramas · {os.path.getsize(ruta) // 1024} KB")
        if carpeta == "es":
            cuadros[-1].save("/tmp/bienvenida_final_es.png")


main()
