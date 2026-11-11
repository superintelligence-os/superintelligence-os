#!/usr/bin/env python3
"""Animaciones Lottie de SIOS para el asistente de bienvenida de Google (edición con Google), en
lugar de las suyas: «Preparando tu dispositivo», «Copiando…» y las esperas.

  · entrada: como la animación de arranque. El anillo se dibuja (de arriba a la derecha, por abajo,
    hasta el palo), el punto sale con un muelle y se enciende con el espectro de las IA.
  · bucle: el símbolo quieto, el punto respira y su degradado gira despacio.

Lienzo 332×396 (el de Google), 60 fps. Geometría de sios/simbolo.py.
Uso: lottie_sios.py <carpeta>  → entrada.json, bucle.json
"""
import json
import math
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "sios"))
from simbolo import ESPECTRO, HUESO  # noqa: E402

W, H, FPS = 332, 396, 60
C, R, T = (52.0, 56.0), 29.0, 12.5
INI, FIN, RECTO, HUECO, RP = -104.0, 180.0, 11.0, 6.0, 7.6
K = 1.9                      # el símbolo (100×100) a 190 px
OX, OY = W / 2 - 52 * K, H / 2 - 54 * K


def rgb(h):
    h = h.lstrip("#")
    return [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]


def p(x, y):
    return [OX + x * K, OY + y * K]


def fijo(v):
    return {"a": 0, "k": v}


def anim(claves):
    """claves: [(fotograma, valor, curva)] con curva 'suave' o 'muelle' hacia la siguiente."""
    out = []
    for i, (t, v, _) in enumerate(claves):
        c = {"t": t, "s": v if isinstance(v, list) else [v]}
        if i < len(claves) - 1:
            c["o"] = {"x": [0.33], "y": [0]}
            c["i"] = {"x": [0.2], "y": [1]}
        out.append(c)
    return {"a": 1, "k": out}


def transform(**k):
    return {"o": k.get("o", fijo(100)), "r": fijo(0), "p": k.get("p", fijo([0, 0, 0])), "a": fijo([0, 0, 0]), "s": k.get("s", fijo([100, 100, 100]))}


def capa(nombre, formas, op, ks=None):
    return {"ty": 4, "nm": nombre, "ip": 0, "op": op, "st": 0, "sr": 1, "ao": 0, "ks": ks or transform(), "shapes": formas, "bm": 0}


def trazo(color, ancho):
    return {"ty": "st", "c": fijo(rgb(color) + [1]), "o": fijo(100), "w": fijo(ancho), "lc": 2, "lj": 2}


def grupo(items):
    return {"ty": "gr", "it": items + [{"ty": "tr", "p": fijo([0, 0]), "a": fijo([0, 0]), "s": fijo([100, 100]), "r": fijo(0), "o": fijo(100)}]}


def anillo_y_palo(op, dibujo=None):
    """El trazo «io»: un arco de 284° (Lottie empieza las elipses arriba, en sentido horario) y el palo."""
    cx, cy = p(*C)
    arco = {"ty": "el", "p": fijo([cx, cy]), "s": fijo([2 * R * K, 2 * R * K]), "d": 1}
    fin_arco = (FIN - INI) / 360 * 100
    if dibujo:  # (desde, hasta) en fotogramas
        a, b = dibujo
        corte = a + (b - a) * 0.86
        tm_arco = {"ty": "tm", "s": fijo(0), "e": anim([(a, 0, ""), (corte, fin_arco, "")]), "o": fijo(INI + 90), "m": 1}
        tm_palo = {"ty": "tm", "s": fijo(0), "e": anim([(corte, 0, ""), (b, 100, "")]), "o": fijo(0), "m": 1}
    else:
        tm_arco = {"ty": "tm", "s": fijo(0), "e": fijo(fin_arco), "o": fijo(INI + 90), "m": 1}
        tm_palo = {"ty": "tm", "s": fijo(0), "e": fijo(100), "o": fijo(0), "m": 1}
    x1, y1 = p(C[0] + R * math.cos(math.radians(FIN)), C[1] + R * math.sin(math.radians(FIN)))
    palo = {"ty": "sh", "ks": fijo({"c": False, "v": [[x1, y1], [x1, y1 - RECTO * K]], "i": [[0, 0], [0, 0]], "o": [[0, 0], [0, 0]]})}
    return capa("anillo", [grupo([arco, tm_arco, trazo(HUESO, T * K)]), grupo([palo, tm_palo, trazo(HUESO, T * K)])], op)


def punto(op, aparece=None, respira=None):
    x1, y1 = p(C[0] - R, C[1])
    py = y1 - RECTO * K - T * K / 2 - HUECO * K - RP * K
    paradas = []
    for o, c in ESPECTRO:
        paradas += [float(o)] + rgb(c)
    relleno = {"ty": "gf", "o": fijo(100), "r": 1, "t": 1, "nm": "espectro",
               "s": fijo([-RP * K, -RP * K]), "e": fijo([RP * K, RP * K]),
               "g": {"p": len(ESPECTRO), "k": fijo(paradas)}}
    forma = {"ty": "el", "p": fijo([0, 0]), "s": fijo([2 * RP * K, 2 * RP * K]), "d": 1}
    ks = transform(p=fijo([x1, py, 0]))
    if aparece:  # muelle: 0 → 118 % → 94 % → 100 %
        a = aparece
        ks["s"] = anim([(a, [0, 0, 100], ""), (a + 14, [118, 118, 100], ""), (a + 24, [94, 94, 100], ""), (a + 34, [100, 100, 100], "")])
    if respira:
        n = respira
        ks["s"] = anim([(0, [100, 100, 100], ""), (n / 2, [108, 108, 100], ""), (n, [100, 100, 100], "")])
        ks["r"] = anim([(0, 0, ""), (n, 360, "")])
    return capa("punto", [grupo([forma, relleno])], op, ks)


def halo(op, desde=0, ciclo=None):
    """Luz suave detrás del punto."""
    x1, y1 = p(C[0] - R, C[1])
    py = y1 - RECTO * K - T * K / 2 - HUECO * K - RP * K
    r = RP * K * 5
    forma = {"ty": "el", "p": fijo([0, 0]), "s": fijo([2 * r, 2 * r]), "d": 1}
    relleno = {"ty": "gf", "o": fijo(100), "r": 1, "t": 2, "s": fijo([0, 0]), "e": fijo([r, 0]),
               "g": {"p": 2, "k": fijo([0] + rgb("#E9B44C") + [1] + rgb("#7B6CF6") + [0, 0.45, 1, 0])}}
    ks = transform(p=fijo([x1, py, 0]))
    if ciclo:
        ks["o"] = anim([(0, 60, ""), (ciclo / 2, 100, ""), (ciclo, 60, "")])
    else:
        ks["o"] = anim([(desde, 0, ""), (desde + 30, 100, "")])
    return capa("halo", [grupo([forma, relleno])], op, ks)


def lottie(nombre, op, capas):
    return {"v": "5.7.4", "fr": FPS, "ip": 0, "op": op, "w": W, "h": H, "nm": nombre, "ddd": 0, "assets": [], "layers": capas}


def main():
    salida = sys.argv[1]
    os.makedirs(salida, exist_ok=True)
    op_e = 150  # 2,5 s
    entrada = lottie("SIOS entrada", op_e, [punto(op_e, aparece=84), anillo_y_palo(op_e, dibujo=(6, 84)), halo(op_e, desde=84)])
    op_b = 240  # 4 s
    bucle = lottie("SIOS bucle", op_b, [punto(op_b, respira=op_b), anillo_y_palo(op_b), halo(op_b, ciclo=op_b)])
    for n, d in (("entrada", entrada), ("bucle", bucle)):
        with open(os.path.join(salida, f"{n}.json"), "w") as f:
            json.dump(d, f, separators=(",", ":"))
    print("  lottie de SIOS en", salida)


main()
