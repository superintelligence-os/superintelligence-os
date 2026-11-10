#!/usr/bin/env python3
"""Fondos de pantalla de SIOS, 1080×2400. Todos de la marca SIOS (no de las IA: el agente es SIOS,
la IA es su cerebro), con su símbolo «io», el grafito y el hueso, y el espectro de las IA solo en
el punto y en la luz:
  · sios (Origen): el anillo apenas marcado y el punto encendido con su halo. El de serie.
  · orbita: el trazo del anillo enorme, saliendo de la pantalla, con una luz que lo recorre.
  · espectro: una aurora suave con los colores del espectro subiendo desde abajo.
  · guias: las guías de construcción del símbolo (como en el arranque), finas, y el punto.
  · hueso: claro, el símbolo en grafito sobre hueso.
Grano fino contra las bandas y la parte de arriba limpia para el reloj.

Los dibuja Chrome sin ventana. Salen en WebP para la app y el de serie, también en PNG, como
fondo por defecto de la ROM.

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



PARADAS = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in ESPECTRO)


def lienzo(base, cuerpo, arriba=True):
    return f"""<html><body style="margin:0;width:{ANCHO}px;height:{ALTO}px;overflow:hidden;background:{base}">
<svg width="{ANCHO}" height="{ALTO}" style="position:absolute;inset:0"><defs>{GRANO}
<linearGradient id="esp" x1="0" y1="0" x2="1" y2="1">{PARADAS}</linearGradient>
<filter id="desenfoque" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="70"/></filter>
<linearGradient id="arriba" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{base}"/><stop offset=".40" stop-color="{base}" stop-opacity="0"/></linearGradient>
</defs>{cuerpo}{f'<rect width="{ANCHO}" height="{ALTO}" fill="url(#arriba)"/>' if arriba else ""}
<rect width="{ANCHO}" height="{ALTO}" filter="url(#grano)" opacity=".06"/></svg></body></html>"""


def fondo_orbita():
    """El anillo de SIOS enorme, saliendo por la derecha: un filo fino de hueso con una luz del espectro
    que lo recorre y su sombra ancha, muy suave. Sin el punto: el anillo solo, como una órbita."""
    cx, cy, r = ANCHO * 1.02, ALTO * 0.70, 760
    return lienzo(GRAFITO, f"""<defs><linearGradient id="barrido" x1="0" y1="1" x2=".6" y2="0">
<stop offset=".05" stop-color="{HUESO}" stop-opacity=".06"/><stop offset=".30" stop-color="#D97757" stop-opacity=".75"/>
<stop offset=".42" stop-color="#E9B44C" stop-opacity=".85"/><stop offset=".55" stop-color="#4C8DF6" stop-opacity=".7"/>
<stop offset=".68" stop-color="#7B6CF6" stop-opacity=".35"/><stop offset=".9" stop-color="{HUESO}" stop-opacity=".06"/></linearGradient>
<filter id="suave" x="-30%" y="-30%" width="160%" height="160%"><feGaussianBlur stdDeviation="14"/></filter></defs>
<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="{HUESO}" stroke-opacity=".035" stroke-width="300"/>
<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="url(#barrido)" stroke-width="26" opacity=".35" filter="url(#suave)"/>
<circle cx="{cx}" cy="{cy}" r="{r}" fill="none" stroke="url(#barrido)" stroke-width="2.5"/>""")


def fondo_espectro():
    """Una aurora: los colores del espectro, muy desenfocados, subiendo desde abajo; el punto, pequeño."""
    manchas = "".join(
        f'<ellipse cx="{x}" cy="{y}" rx="{rx}" ry="{ry}" fill="{c}" opacity="{o}" filter="url(#desenfoque)"/>'
        for x, y, rx, ry, c, o in [
            (60, 2480, 620, 330, "#D97757", .42), (430, 2440, 520, 300, "#E9B44C", .26),
            (800, 2470, 560, 320, "#4C8DF6", .36), (1120, 2300, 420, 360, "#7B6CF6", .34), (560, 2200, 700, 200, "#7B6CF6", .12),
        ])
    return lienzo(GRAFITO, manchas + f'<circle cx="{ANCHO / 2}" cy="1520" r="11" fill="{HUESO}" opacity=".9"/>')


def fondo_guias():
    """Las guías de construcción del símbolo, finas, como en la animación de arranque, y el punto encendido."""
    d, (px, py, rp) = trazo_io()
    k = 7.2
    cx, cy = ANCHO / 2, 1460
    t = f'transform="translate({cx - 52 * k},{cy - 56 * k}) scale({k})"'
    finas = "".join(f'<circle cx="52" cy="56" r="{r}" fill="none" stroke="{HUESO}" stroke-opacity="{o}" stroke-width="{w}"/>'
                    for r, o, w in [(29, .16, .14), (42, .08, .12), (58, .05, .1), (80, .035, .1)])
    ejes = (f'<line x1="-200" y1="56" x2="300" y2="56" stroke="{HUESO}" stroke-opacity=".07" stroke-width=".1"/>'
            f'<line x1="52" y1="-200" x2="52" y2="300" stroke="{HUESO}" stroke-opacity=".07" stroke-width=".1"/>'
            f'<line x1="{px}" y1="-200" x2="{px}" y2="300" stroke="{HUESO}" stroke-opacity=".05" stroke-width=".1"/>')
    return lienzo(GRAFITO, f"""<defs><radialGradient id="halo"><stop offset="0" stop-color="#E9B44C" stop-opacity=".5"/>
<stop offset=".5" stop-color="#7B6CF6" stop-opacity=".14"/><stop offset="1" stop-color="#7B6CF6" stop-opacity="0"/></radialGradient></defs>
<g {t}>{finas}{ejes}<path d="{d}" fill="none" stroke="{HUESO}" stroke-opacity=".22" stroke-width=".45" stroke-linecap="round"/>
<circle cx="{px}" cy="{py}" r="{rp * 5}" fill="url(#halo)"/><circle cx="{px}" cy="{py}" r="{rp}" fill="url(#esp)"/></g>""")


def fondo_hueso():
    """Claro: el símbolo en grafito sobre hueso, con el punto del espectro."""
    d, (px, py, rp) = trazo_io()
    k = TAM / 100
    t = f'transform="translate({ANCHO / 2 - 52 * k},{CENTRO_Y - 50 * k}) scale({k})"'
    return lienzo(HUESO, f"""<defs><radialGradient id="sombra"><stop offset="0" stop-color="#141413" stop-opacity=".10"/><stop offset="1" stop-color="#141413" stop-opacity="0"/></radialGradient></defs>
<ellipse cx="{ANCHO / 2}" cy="{CENTRO_Y + TAM * .62}" rx="420" ry="70" fill="url(#sombra)"/>
<g {t}><path d="{d}" fill="none" stroke="{GRAFITO}" stroke-width="12.5" stroke-linecap="round" stroke-linejoin="round"/>
<circle cx="{px}" cy="{py}" r="{rp}" fill="url(#esp)"/></g>""")


def dibujar(html, png):
    ruta = png + ".html"
    open(ruta, "w").write(html)
    # Chrome sin ventana a veces sale con error al cerrarse aunque haya hecho la captura: se mira el PNG.
    for _ in range(3):
        if os.path.exists(png):
            os.remove(png)
        subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--screenshot={png}",
                        f"--window-size={ANCHO},{ALTO}", "file://" + ruta], capture_output=True)
        if os.path.exists(png) and os.path.getsize(png) > 0:
            break
    else:
        raise RuntimeError(f"Chrome no ha dibujado {png}")
    os.remove(ruta)


def main():
    salida = os.path.abspath(sys.argv[1])
    os.makedirs(salida, exist_ok=True)
    hechos = []
    png = os.path.join(salida, "sios.png")
    dibujar(html_fondo(GRAFITO, figura_sios(), "#5B4BE0", "#D97757"), png)
    hechos.append(("sios", png))
    for id_, html in [("orbita", fondo_orbita()), ("espectro", fondo_espectro()), ("guias", fondo_guias()), ("hueso", fondo_hueso())]:
        png = os.path.join(salida, f"{id_}.png")
        dibujar(html, png)
        hechos.append((id_, png))
    for id_, png in hechos:
        Image.open(png).convert("RGB").save(png[:-4] + ".webp", "WEBP", quality=96, method=6)
    hoja = Image.new("RGB", (216 * len(hechos), 480), "#000")
    for i, (_, png) in enumerate(hechos):
        hoja.paste(Image.open(png).convert("RGB").resize((216, 480)), (i * 216, 0))
    hoja.save(os.path.join(salida, "fondos-muestra.png"))
    print(f"  {len(hechos)} fondos en {salida}")


main()
