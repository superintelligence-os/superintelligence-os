#!/usr/bin/env python3
"""Geometría de la marca SIOS (SuperIntelligence OS). Todo sale de aquí: SVG, vectores de
Android, la animación de arranque y los fondos.

Símbolo «io»: un anillo (la o: el sistema) cuyo lado izquierdo sube recto y lleva un punto
encima (la i: la inteligencia). La i y la o comparten trazo: son una sola pieza. Es el «io» del
centro de s·io·s, el I/O de un sistema operativo y, en italiano, «yo»: el sistema es tuyo.
El anillo es siempre igual; el punto toma el color de la IA que esté en uso.

Logotipo «sios»: s + símbolo + s, monolínea, mismo grosor y remates redondos.
Rejilla del símbolo: 100×100.
"""
import math

GRAFITO, HUESO = "#141413", "#F4F1EA"
# El punto sin IA elegida: el espectro de todas.
ESPECTRO = [("0", "#D97757"), (".38", "#E9B44C"), (".68", "#4C8DF6"), ("1", "#7B6CF6")]

def trazo_io(C=(52.0, 56.0), R=29.0, T=12.5, ini=-104.0, fin=180.0, recto=11.0, hueco=6.0, rp=7.6):
    """(d del anillo, (cx, cy, r) del punto). Espejo de la versión con la i a la derecha:
    el anillo empieza arriba a la derecha (ini), da la vuelta por abajo y termina en el lado
    izquierdo (fin), sube recto y deja sitio al punto."""
    a0, a1 = math.radians(ini), math.radians(fin)
    p0 = (C[0] + R * math.cos(a0), C[1] + R * math.sin(a0))
    p1 = (C[0] + R * math.cos(a1), C[1] + R * math.sin(a1))
    ytop = p1[1] - recto
    d = f"M{p0[0]:.3f} {p0[1]:.3f}A{R} {R} 0 1 1 {p1[0]:.3f} {p1[1]:.3f}L{p1[0]:.3f} {ytop:.3f}"
    punto = (p1[0], ytop - T / 2 - hueco - rp, rp)
    return d, punto

def svg_simbolo(anillo=HUESO, punto="#D97757", T=12.5, **k):
    d, (px, py, rp) = trazo_io(T=T, **k)
    return (f'<path d="{d}" fill="none" stroke="{anillo}" stroke-width="{T}" stroke-linecap="round" stroke-linejoin="round"/>'
            f'<circle cx="{px:.3f}" cy="{py:.3f}" r="{rp:.3f}" fill="{punto}"/>')

T_LETRA = 11.0
X = 58.0  # altura de x del logotipo (bordes exteriores)

def svg_ese(x, base, color, T=T_LETRA):
    """«s» geométrica: dos cuencos elípticos apilados (el de abajo algo más ancho, como en la
    buena tipografía), remates cortados con la misma curva."""
    top, bot = base - X + T / 2, base - T / 2
    mid = (top + bot) / 2
    rxu, rxl = 17.0, 18.5
    ry = (bot - top) / 4
    cx = x + rxl + T / 2
    a = math.radians(-32)
    b = math.radians(148)
    s0 = (cx + rxu * math.cos(a), top + ry + ry * math.sin(a))
    s3 = (cx + rxl * math.cos(b), bot - ry + ry * math.sin(b))
    d = (f"M{s0[0]:.3f} {s0[1]:.3f}A{rxu} {ry} 0 0 0 {cx:.3f} {top:.3f}"
         f"A{rxu} {ry} 0 0 0 {cx:.3f} {mid:.3f}"
         f"A{rxl} {ry} 0 0 1 {cx:.3f} {bot:.3f}"
         f"A{rxl} {ry} 0 0 1 {s3[0]:.3f} {s3[1]:.3f}")
    return f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{T}" stroke-linecap="round" stroke-linejoin="round"/>', 2 * rxl + T

def svg_logotipo(color=HUESO, punto="#D97757", x0=0.0, base=100.0, espacio=10.0):
    """s · i · o · s, monolínea. La i va entera (palo y punto en color) para que se lea «sios» y
    nunca «SOS»; la fusión «io» se queda para el símbolo suelto. Devuelve (svg, ancho)."""
    piezas = []
    t = T_LETRA
    top, bot = base - X + t / 2, base - t / 2
    x = x0
    s_, w = svg_ese(x, base, color); piezas.append(s_); x += w + espacio
    # i: palo a la altura de x y punto encima, del mismo tamaño que el del símbolo.
    cx = x + t / 2
    piezas.append(f'<path d="M{cx:.3f} {top:.3f}V{bot:.3f}" stroke="{color}" stroke-width="{t}" stroke-linecap="round"/>')
    rp = 6.6
    piezas.append(f'<circle cx="{cx:.3f}" cy="{top - t / 2 - 5.5 - rp:.3f}" r="{rp:.3f}" fill="{punto}"/>')
    x += t + espacio
    # o: anillo cerrado.
    r = (X - t) / 2
    piezas.append(f'<circle cx="{x + t / 2 + r:.3f}" cy="{base - X / 2:.3f}" r="{r:.3f}" fill="none" stroke="{color}" stroke-width="{t}"/>')
    x += 2 * r + t + espacio
    s_, w = svg_ese(x, base, color); piezas.append(s_); x += w
    return "".join(piezas), x - x0
