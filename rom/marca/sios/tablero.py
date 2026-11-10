#!/usr/bin/env python3
"""Hoja de marca de SIOS (HTML; Chrome sin ventana la pasa a PNG)."""
import sys
from simbolo import ESPECTRO, GRAFITO, HUESO, svg_logotipo, svg_simbolo
COLORES = [("Claude", "#D97757"), ("ChatGPT", "#10A37F"), ("Gemini", "#4C8DF6"), ("Qwen", "#7B6CF6"), ("DeepSeek", "#4D6BFE")]
DEF = '<defs><linearGradient id="esp" x1="0" y1="0" x2="1" y2="1">' + "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in ESPECTRO) + "</linearGradient></defs>"
def svg(c, w, h, vb): return f'<svg width="{w}" height="{h}" viewBox="{vb}">{DEF}{c}</svg>'
def caja(c, fondo, nota, extra=""):
    return f'<div class="c" style="background:{fondo};{extra}">{c}<span>{nota}</span></div>'
h = ['<html><head><style>body{margin:0;background:#0b0b0a;font:13px -apple-system,sans-serif;color:#8a8984;padding:28px}'
     '.f{display:flex;gap:16px;margin-bottom:16px}.c{display:flex;flex-direction:column;align-items:center;justify-content:center;padding:22px;border-radius:20px;gap:10px}</style></head><body>']
h.append('<div class="f">')
h.append(caja(svg(svg_simbolo(punto="url(#esp)"), 210, 210, "0 0 100 100"), GRAFITO, "SIOS · sin IA elegida"))
for n, c in COLORES:
    h.append(caja(svg(svg_simbolo(punto=c), 150, 150, "0 0 100 100"), GRAFITO, n))
h.append('</div><div class="f">')
logo, w = svg_logotipo(punto="url(#esp)")
h.append(caja(svg(logo, 560, 180, f"-6 22 {w+12} 84"), GRAFITO, "logotipo"))
logo, w = svg_logotipo(color=GRAFITO, punto="url(#esp)")
h.append(caja(svg(logo, 380, 122, f"-6 22 {w+12} 84"), HUESO, "sobre claro"))
for t in (48, 24, 16):
    h.append(caja(svg(svg_simbolo(punto="#D97757"), t, t, "0 0 100 100"), GRAFITO, f"{t} px"))
h.append('</div></body></html>')
open(sys.argv[1], "w").write("".join(h))
