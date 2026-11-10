#!/usr/bin/env python3
"""Genera recursos/ventana.html desde plantilla.html: logos de las IA, símbolo de SIOS y el
dibujo de las tarjetas del panel (CSS y JS compartidos)."""
import json, os, re, sys
AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.abspath(os.path.join(AQUI, "../.."))
sys.path.insert(0, os.path.join(RAIZ, "../rom/marca/sios"))
from simbolo import trazo_io
d, (px, py, rp) = trazo_io()
def sios(tam):
    return (f'<svg class="sios" width="{tam}" height="{tam}" viewBox="14 14 76 82"><path class="anillo" d="{d}" fill="none" stroke-width="12.5" stroke-linecap="round" stroke-linejoin="round"/>'
            f'<circle class="punto" cx="{px}" cy="{py}" r="{rp}"/></svg>')
p = open(os.path.join(AQUI, "plantilla.html")).read()
p = p.replace("__LOGOS__", open(os.path.join(AQUI, "logos.json")).read())
p = p.replace("__SIOS_GRANDE__", sios(84)).replace("__SIOS__", sios(28))
p = p.replace("__PANEL_CSS__", open(os.path.join(AQUI, "panel.css")).read())
p = p.replace("__PANEL_JS__", open(os.path.join(AQUI, "panel.js")).read())
open(os.path.join(RAIZ, "recursos/ventana.html"), "w").write(p)
print("ventana.html lista")
