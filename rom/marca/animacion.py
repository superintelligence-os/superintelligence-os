#!/usr/bin/env python3
"""Animación de arranque de SIOS (bootanimation.zip), hecha como una pieza de motion graphics.

Entrada (2,8 s, se ve entera):
  · aparecen unas guías finas de construcción (el círculo del anillo y sus ejes), como en un plano;
  · nace una chispa arriba a la derecha y dibuja el anillo como un cometa: la cabeza va pasando por
    el espectro de las IA (naranja → ámbar → azul → violeta) y la estela se enfría al color hueso;
  · al llegar arriba del palo, el punto sale despedido con un muelle, se enciende con el espectro y
    suelta una onda que se expande y se apaga;
  · debajo entra «SUPERINTELLIGENCE OS» letra a letra, cerrando el espaciado.
Bucle (3 s, mientras arranca): un destello de color recorre el trazo y el degradado del punto gira
despacio; el último fotograma empalma con el primero. Al terminar el arranque se funde (tipo «f»).

Fotogramas de 640×640 a 60 fps, dibujados por Chrome sin ventana al doble de resolución (bordes
finos) y con desenfoque de movimiento real en la entrada (varios instantes por fotograma).
Android guarda en la gráfica cada fotograma de la parte que se repite: el bucle es corto (132
fotogramas, ~215 MB) para no apurar a los móviles con poca memoria.

Uso: animacion.py <carpeta de salida>
"""
import math
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image, ImageChops

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "sios"))
from simbolo import ESPECTRO, GRAFITO, HUESO, trazo_io  # noqa: E402

LADO, FPS, ESCALA = 640, 60, 2
ENTRADA, BUCLE, FUNDIDO = 2.8, 2.2, 24  # segundos, segundos, fotogramas de fundido al acabar
CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
TEXTO = "SUPERINTELLIGENCE OS"

# Geometría (la misma que el símbolo de la marca).
C, R, T = (52.0, 56.0), 29.0, 12.5
INI, FIN, RECTO = -104.0, 180.0, 11.0
D, (PX, PY, RP) = trazo_io()
ARCO = R * math.radians(FIN - INI)
LARGO = ARCO + RECTO
TOPE = (C[0] + R * math.cos(math.radians(FIN)), C[1] + R * math.sin(math.radians(FIN)) - RECTO)
VISTA = (-38, -32, 180, 180)  # el símbolo algo por encima del centro y el texto debajo


# ---------- Curvas ----------

def lim(x, a=0.0, b=1.0):
    return max(a, min(b, x))


def tramo(t, a, b):
    """0→1 entre los instantes a y b."""
    return lim((t - a) / (b - a))


def suave(x):
    x = lim(x)
    return x * x * x * (x * (6 * x - 15) + 10)  # smootherstep


def pluma(x):
    """Trazo con decisión: arranca con suavidad, va rápido por el centro y se posa al final."""
    x = lim(x)
    return 4 * x ** 3 if x < 0.5 else 1 - (-2 * x + 2) ** 3 / 2


def sale(x, p=3.0):
    return 1 - (1 - lim(x)) ** p


def muelle(x, rigidez=8.5, rebote=9.5):
    """Muelle amortiguado: pasa un poco de largo y vuelve (0→1)."""
    x = lim(x)
    return 1 - math.exp(-rigidez * x) * math.cos(rebote * x)


# ---------- Color ----------

def hex_rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def mezcla(a, b, t):
    a, b = hex_rgb(a), hex_rgb(b)
    return "#%02x%02x%02x" % tuple(round(a[i] + (b[i] - a[i]) * lim(t)) for i in range(3))


def espectro(f):
    """Color del espectro de la marca en f (0 naranja … 1 violeta)."""
    f = lim(f)
    paradas = [(float(o), c) for o, c in ESPECTRO]
    for (o0, c0), (o1, c1) in zip(paradas, paradas[1:]):
        if f <= o1:
            return mezcla(c0, c1, (f - o0) / (o1 - o0))
    return paradas[-1][1]


def punto_en(f):
    """Punto del trazo a la fracción f de su largo (arco y luego el palo hacia arriba)."""
    s = lim(f) * LARGO
    if s <= ARCO:
        a = math.radians(INI) + s / R
        return C[0] + R * math.cos(a), C[1] + R * math.sin(a)
    return TOPE[0], TOPE[1] + RECTO - (s - ARCO)


# ---------- Dibujo ----------

def defs(giro):
    paradas = "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in ESPECTRO)
    return f"""<defs>
<linearGradient id="esp" gradientUnits="userSpaceOnUse" x1="{PX - RP}" y1="{PY - RP}" x2="{PX + RP}" y2="{PY + RP}"
 gradientTransform="rotate({giro:.2f} {PX} {PY})">{paradas}</linearGradient>
<linearGradient id="onda" x1="0" y1="0" x2="1" y2="1">{paradas}</linearGradient>
<radialGradient id="luz"><stop offset="0" stop-color="#fff" stop-opacity="1"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>
<filter id="brillo" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="3.2"/></filter>
<filter id="brillo2" x="-100%" y="-100%" width="300%" height="300%"><feGaussianBlur stdDeviation="7"/></filter>
<filter id="orbe" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="3.4"/></filter>
<filter id="luzsuave" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="2.4"/></filter>
<mask id="trazo" maskUnits="userSpaceOnUse" x="-38" y="-32" width="180" height="180"><path d="{D}" fill="none" stroke="#fff" stroke-width="{T}" stroke-linecap="round" stroke-linejoin="round"/></mask>
<filter id="desenfoque" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="0.9"/></filter>
</defs>"""


def segmento(desde, hasta, color, ancho=T, opacidad=1.0, filtro="", remate="round"):
    """Trozo del trazo entre dos fracciones de su largo (con pathLength=1). Los tramos de una estela
    van con remate recto: con remate redondo se solapan y la estela se ve hecha de cuentas."""
    desde, hasta = lim(desde), lim(hasta)
    if hasta - desde <= 1e-4 or opacidad <= 0.002:
        return ""
    # En las puntas del trazo, que son redondas, el tramo también: si no, se ve cortado en recto.
    if desde <= 0 or hasta >= 1:
        remate = "round"
    f = f' filter="url(#{filtro})"' if filtro else ""
    return (f'<path d="{D}" pathLength="1" fill="none" stroke="{color}" stroke-width="{ancho:.3f}" stroke-linecap="{remate}"'
            f' stroke-linejoin="round" stroke-dasharray="{hasta - desde:.5f} 3" stroke-dashoffset="{-desde:.5f}"'
            f' opacity="{opacidad:.3f}"{f}/>')


def texto(aparicion, opacidad_final=0.62):
    """El nombre entra letra a letra y cierra el espaciado."""
    if aparicion <= 0:
        return ""
    espaciado = 3.9 - 1.6 * sale(aparicion, 3)
    letras = []
    n = len(TEXTO)
    for i, ch in enumerate(TEXTO):
        a = sale(tramo(aparicion, i / n * 0.55, i / n * 0.55 + 0.45), 2.4)
        letras.append(f'<tspan opacity="{a * opacidad_final:.3f}">{ch}</tspan>')
    y = 132 + 3.5 * (1 - sale(aparicion, 3))
    return (f'<text x="52" y="{y:.3f}" text-anchor="middle" font-family="Montserrat" font-weight="500" font-size="6.4"'
            f' letter-spacing="{espaciado:.3f}" fill="{HUESO}">{"".join(letras)}</text>')


def lienzo(cuerpo, giro=0.0):
    x, y, w, h = VISTA
    return (f'<svg width="{LADO}" height="{LADO}" viewBox="{x} {y} {w} {h}" xmlns="http://www.w3.org/2000/svg">'
            f'{defs(giro)}<rect x="{x}" y="{y}" width="{w}" height="{h}" fill="{GRAFITO}"/>{cuerpo}</svg>')


def punto_y_halo(escala, dy, halo, giro=0.0):
    """El punto: un orbe con manchas difuminadas de los colores de las IA que giran despacio
    (recortadas dentro del círculo), y su halo. escala 0→1, dy desplazamiento vertical."""
    if escala <= 0.001:
        return ""
    cx, cy, r = PX, PY + dy, RP * escala
    colores = [c for _, c in ESPECTRO]

    def manchas(distancia, radio):
        return "".join(
            f'<circle cx="{cx + distancia * math.cos(math.radians(giro + 90 * i)):.3f}"'
            f' cy="{cy + distancia * math.sin(math.radians(giro + 90 * i)):.3f}" r="{radio:.3f}" fill="{c}"/>'
            for i, c in enumerate(colores))
    halo_ = lim(halo, 0, 2)
    return (f'<clipPath id="recorte"><circle cx="{cx}" cy="{cy:.3f}" r="{r:.3f}"/></clipPath>'
            f'<g opacity="{0.42 * halo_:.3f}" filter="url(#brillo2)">{manchas(r * 0.9, r * 1.5)}</g>'
            f'<g clip-path="url(#recorte)"><circle cx="{cx}" cy="{cy:.3f}" r="{r:.3f}" fill="{mezcla(colores[0], colores[2], 0.5)}"/>'
            f'<g filter="url(#orbe)">{manchas(r * 0.62, r * 1.0)}</g></g>')


def chispa(x, y, color, fuerza):
    return (f'<circle cx="{x:.3f}" cy="{y:.3f}" r="{9 * fuerza:.3f}" fill="{color}" opacity="{0.75 * fuerza:.3f}" filter="url(#brillo)"/>'
            f'<circle cx="{x:.3f}" cy="{y:.3f}" r="{3.2 * fuerza:.3f}" fill="#fff" opacity="{0.9 * fuerza:.3f}" filter="url(#desenfoque)"/>')


def cuadro_entrada(t):
    """Un instante de la entrada (t en segundos)."""
    capas = []
    # Resplandor de fondo: sube con el punto y se queda muy suave.
    resplandor = 0.10 * sale(tramo(t, 1.1, 1.7)) - 0.045 * suave(tramo(t, 1.7, 2.6))
    if resplandor > 0:
        capas.append(f'<circle cx="{C[0]}" cy="{C[1]}" r="78" fill="url(#luz)" opacity="{resplandor:.3f}"/>')
    # Guías de construcción: el círculo del anillo y sus ejes, finos, que se dibujan y se apagan.
    guia = sale(tramo(t, 0.0, 0.7)) * (1 - suave(tramo(t, 1.15, 1.75)))
    if guia > 0.002:
        capas.append(f'<circle cx="{C[0]}" cy="{C[1]}" r="{R}" fill="none" stroke="{HUESO}" stroke-width="0.35" pathLength="1"'
                     f' stroke-dasharray="{sale(tramo(t, 0, 0.7)):.4f} 2" transform="rotate(-104 {C[0]} {C[1]})" opacity="{0.22 * guia:.3f}"/>')
        capas.append(f'<line x1="{C[0] - 44}" y1="{C[1]}" x2="{C[0] + 44}" y2="{C[1]}" stroke="{HUESO}" stroke-width="0.25"'
                     f' opacity="{0.12 * guia * sale(tramo(t, 0.15, 0.6)):.3f}"/>')
        capas.append(f'<line x1="{C[0]}" y1="{C[1] - 44}" x2="{C[0]}" y2="{C[1] + 44}" stroke="{HUESO}" stroke-width="0.25"'
                     f' opacity="{0.12 * guia * sale(tramo(t, 0.2, 0.65)):.3f}"/>')
    # El trazo: un cometa que dibuja el anillo y sube por el palo.
    p = pluma(tramo(t, 0.22, 1.28))
    enfriado = suave(tramo(t, 1.25, 1.75))  # la estela pasa a hueso
    if p > 0:
        capas.append(segmento(0, p, HUESO))
        estela = 0.22 * (1 - enfriado)
        n = 28
        color = espectro(p * 0.92)
        for k in range(n):
            a, b = p - estela * (k + 1) / n, p - estela * k / n
            if b <= 0:
                continue
            calor = 1 - k / n
            capas.append(segmento(max(0, a), b, color, opacidad=calor ** 1.6 * (1 - enfriado), remate="butt"))
        if estela > 0.002:  # remate redondo solo en la punta
            capas.append(segmento(max(0, p - 0.004), p, color, opacidad=1 - enfriado))
        cabeza = 1 - suave(tramo(t, 1.22, 1.45))
        if cabeza > 0.002:
            capas.append(chispa(*punto_en(p), espectro(p * 0.92), cabeza))
    elif t < 0.3:
        # La chispa nace antes de empezar a dibujar.
        capas.append(chispa(*punto_en(0), espectro(0), sale(tramo(t, 0.02, 0.22))))
    # El punto sale despedido desde lo alto del palo y se asienta con un muelle.
    x = tramo(t, 1.2, 2.0)
    if x > 0:
        dy = (TOPE[1] - PY) * (1 - muelle(x))
        escala = lim(muelle(tramo(t, 1.2, 1.85), 9, 11), 0, 1.25)
        halo = 1.6 * sale(tramo(t, 1.25, 1.45)) - 1.15 * suave(tramo(t, 1.45, 2.4))  # destello y reposo en 0,45
        capas.append(punto_y_halo(escala, dy, halo))
    # Onda expansiva desde el centro del símbolo (dos, la segunda más débil).
    for retraso, fuerza in ((0.0, 1.0), (0.14, 0.55)):
        w = tramo(t, 1.42 + retraso, 2.35 + retraso)
        if 0 < w < 1:
            r = 36 + 52 * sale(w, 2.6)
            capas.append(f'<circle cx="{C[0]}" cy="{C[1]}" r="{r:.3f}" fill="none" stroke="url(#onda)" stroke-width="{1.4 * (1 - w) + 0.15:.3f}"'
                         f' opacity="{0.55 * fuerza * (1 - w) ** 1.5:.3f}"/>')
    capas.append(texto(tramo(t, 1.7, 2.65)))
    return lienzo("".join(capas))


def cuadro_bucle(f):
    """Un instante del bucle (f de 0 a 1; f=0 coincide con el final de la entrada)."""
    capas = [f'<circle cx="{C[0]}" cy="{C[1]}" r="78" fill="url(#luz)" opacity="0.055"/>', segmento(0, 1, HUESO)]
    # Destello de color que recorre el trazo durante el 60 % del ciclo; el resto, reposo. Es una sola
    # luz: un tramo difuminado y recortado con la forma del trazo (máscara), así entra y sale por
    # las puntas redondas sin bordes rectos y se enciende y apaga poco a poco.
    x = tramo(f, 0.08, 0.68)
    if 0 < x < 1:
        largo = 0.2
        cab = -largo + (1 + 2 * largo) * suave(x)
        fuerza = 0.9 * math.sin(math.pi * x) ** 0.6
        luz = segmento(cab - largo, cab, espectro(lim(cab)), opacidad=fuerza)
        if luz:
            capas.append(f'<g mask="url(#trazo)"><g filter="url(#luzsuave)">{luz}</g></g>')
        # El brillo de la cabeza se enciende al entrar y se apaga al salir por la punta (sin cortes).
        fuera = abs(cab - lim(cab)) / largo
        brillo = 0.35 * (1 - suave(lim(fuera))) * fuerza
        if brillo > 0.002:
            hx, hy = punto_en(lim(cab))
            capas.append(f'<circle cx="{hx:.3f}" cy="{hy:.3f}" r="8" fill="{espectro(lim(cab))}" opacity="{brillo:.3f}" filter="url(#brillo)"/>')
    halo = 0.45 + 0.12 * (1 - math.cos(2 * math.pi * f)) / 2
    capas.append(punto_y_halo(1, 0, halo, giro=360 * f))
    capas.append(texto(1))
    return lienzo("".join(capas))


# ---------- Fotogramas ----------

def fotogramas():
    entrada = []
    sub = 4  # instantes por fotograma para el desenfoque de movimiento
    for i in range(int(round(FPS * ENTRADA))):
        t = i / FPS
        n = sub if 0.15 < t < 2.1 else 1
        entrada.append([cuadro_entrada(t + j / (FPS * n)) for j in range(n)])
    bucle = [[cuadro_bucle(i / (FPS * BUCLE))] for i in range(int(round(FPS * BUCLE)))]
    return entrada, bucle


def unicos(svg, n):
    """Cada SVG de la tanda con sus propios ids: si no, url(#…) apunta al primero del documento."""
    return svg.replace('id="', f'id="c{n}').replace("url(#", f"url(#c{n}")


def rasterizar(cuadros, carpeta):
    """Chrome dibuja tandas de instantes apilados al doble de tamaño; Pillow los separa, mezcla los
    instantes de cada fotograma (desenfoque de movimiento) y los reduce."""
    os.makedirs(carpeta)
    planos = [(k, s) for k, inst in enumerate(cuadros) for s in inst]
    acumulado = {}
    tanda = 9
    L = LADO * ESCALA
    for base in range(0, len(planos), tanda):
        grupo = planos[base:base + tanda]
        html, png = os.path.join(carpeta, "tanda.html"), os.path.join(carpeta, "tanda.png")
        open(html, "w").write('<html><body style="margin:0;line-height:0;background:#141413">'
                              + "".join(f"<div>{unicos(s, j)}</div>" for j, (_, s) in enumerate(grupo)) + "</body></html>")
        subprocess.run([CHROME, "--headless=new", "--hide-scrollbars", f"--force-device-scale-factor={ESCALA}",
                        f"--screenshot={png}", f"--window-size={LADO},{LADO * len(grupo)}", "--virtual-time-budget=2000",
                        "file://" + html], check=True, capture_output=True)
        im = Image.open(png).convert("RGB")
        for j, (k, _) in enumerate(grupo):
            acumulado.setdefault(k, []).append(im.crop((0, j * L, L, (j + 1) * L)))
        os.remove(html)
        os.remove(png)
        # Fotogramas completos: se mezclan sus instantes y se guardan.
        for k in [k for k in acumulado if len(acumulado[k]) == len(cuadros[k])]:
            capas = acumulado.pop(k)
            final = capas[0]
            for idx, c in enumerate(capas[1:], start=2):
                final = Image.blend(final, c, 1 / idx)
            final = final.resize((LADO, LADO), Image.LANCZOS)
            # Color completo y sin pérdida: con 256 colores los degradados del punto y los brillos
            # salen a escalones (o con puntitos si se trama), y apenas se ahorra.
            final.save(os.path.join(carpeta, f"{k:03d}.png"), optimize=True)


def main():
    salida = sys.argv[1]
    trabajo = tempfile.mkdtemp()
    raiz = os.path.join(trabajo, "zip")
    entrada, bucle = fotogramas()
    rasterizar(entrada, os.path.join(raiz, "part0"))
    rasterizar(bucle, os.path.join(raiz, "part1"))
    print(f"  part0: {len(entrada)} fotogramas · part1: {len(bucle)} · {FPS} fps")
    # Empalmes: final de la entrada → bucle, y final del bucle → su principio.
    def dif(a, b):
        return max(x for _, x in ImageChops.difference(Image.open(a).convert("RGB"), Image.open(b).convert("RGB")).getextrema())
    p0, p1 = os.path.join(raiz, "part0"), os.path.join(raiz, "part1")
    print(f"  empalmes (diferencia máxima /255): entrada→bucle {dif(f'{p0}/{len(entrada) - 1:03d}.png', f'{p1}/000.png')}"
          f" · bucle→bucle {dif(f'{p1}/{len(bucle) - 1:03d}.png', f'{p1}/000.png')}")
    # c = la entrada se ve entera; f = el bucle se repite y se funde al terminar el arranque.
    open(os.path.join(raiz, "desc.txt"), "w").write(
        f"{LADO} {LADO} {FPS}\nc 1 0 part0 {GRAFITO}\nf 0 0 part1 {FUNDIDO} {GRAFITO}\n")
    destino = os.path.abspath(os.path.join(salida, "bootanimation.zip"))
    if os.path.exists(destino):
        os.remove(destino)
    subprocess.run(["zip", "-0", "-q", "-r", destino, "desc.txt", "part0", "part1"], cwd=raiz, check=True)
    # Hoja de contactos y vídeo de muestra para revisarla a ojo.
    muestras = [("part0", int(s * FPS)) for s in (0.15, 0.45, 0.75, 1.05, 1.3, 1.5, 1.8, 2.2, 2.79)] + \
               [("part1", int(f * FPS * BUCLE)) for f in (0.25, 0.45, 0.6)]
    lado = 240
    hoja = Image.new("RGB", (lado * 6, lado * 2), GRAFITO)
    for idx, (parte, num) in enumerate(muestras):
        hoja.paste(Image.open(os.path.join(raiz, parte, f"{num:03d}.png")).resize((lado, lado), Image.LANCZOS),
                   ((idx % 6) * lado, (idx // 6) * lado))
    hoja.save(os.path.join(salida, "animacion-muestra.png"))
    if shutil.which("ffmpeg"):
        lista = os.path.join(trabajo, "lista.txt")
        with open(lista, "w") as l:
            for parte, n in (("part0", len(entrada)), ("part1", len(bucle)), ("part1", len(bucle))):
                for i in range(n):
                    l.write(f"file '{os.path.join(raiz, parte, f'{i:03d}.png')}'\nduration {1 / FPS:.6f}\n")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", lista, "-r", str(FPS),
                        "-pix_fmt", "yuv420p", "-crf", "16", os.path.join(salida, "animacion-muestra.mp4")], check=True)
    shutil.rmtree(trabajo)
    print(f"  {destino} ({os.path.getsize(destino) // 1024} KB)")


main()
