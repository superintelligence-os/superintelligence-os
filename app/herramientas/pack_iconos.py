#!/usr/bin/env python3
"""Pack de iconos de SIOS → app/src/main/java/com/sios/ui/PackIconos.kt

Cada app conocida lleva un glifo plano: de línea (Lucide, licencia ISC) para lo que es una función
(teléfono, cámara, ajustes…) y de marca (Simple Icons, CC0) para servicios con logo propio
(WhatsApp, YouTube…). El inicio los dibuja sobre una baldosa gris redondeada; las apps que no
están aquí usan su capa monocroma o su icono original con la misma forma.

También genera los iconos que Claude puede elegir para sus mini-apps (la lista sale de
servicio/creaciones.mjs, para que servicio y app no se desincronicen).

Uso: pack_iconos.py <lucide-static/icons> <simple-icons/icons>
     (npm pack lucide-static simple-icons y descomprimir; solo se leen los SVG)
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

LUCIDE, SIMPLE = sys.argv[1], sys.argv[2]
SALIDA = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "java", "com", "sios", "ui", "PackIconos.kt")

# glifo → paquetes. "l:" = Lucide (línea), "s:" = Simple Icons (marca, relleno).
PACK = {
    "l:phone": ["com.google.android.dialer", "com.android.dialer"],
    "l:message-circle": ["com.google.android.apps.messaging", "com.android.messaging"],
    "l:camera": ["org.lineageos.aperture", "com.android.camera2", "com.google.android.GoogleCamera"],
    "l:images": ["org.lineageos.glimpse", "com.android.gallery3d"],
    "l:settings": ["com.android.settings"],
    "l:globe": ["org.lineageos.jelly", "com.android.browser"],
    "l:alarm-clock": ["com.google.android.deskclock", "com.android.deskclock"],
    "l:calendar-days": ["com.google.android.calendar", "org.lineageos.etar", "com.android.calendar"],
    "l:contact": ["com.google.android.contacts", "com.android.contacts"],
    "l:folder": ["com.google.android.documentsui", "com.android.documentsui", "com.google.android.apps.nbu.files"],
    "l:calculator": ["com.google.android.calculator", "com.android.calculator2"],
    "l:mic": ["org.lineageos.recorder", "com.android.soundrecorder", "com.google.android.apps.recorder"],
    "l:music": ["org.lineageos.twelve", "org.lineageos.eleven", "com.android.music"],
    "l:square-terminal": ["com.termux"],
    "l:radio": ["com.android.fmradio"],
    "l:mail": ["com.android.email"],
    "l:wallet": ["com.google.android.apps.walletnfcrel"],
    "l:notebook-pen": ["com.google.android.keep"],
    "l:sliders-vertical": ["org.lineageos.audiofx"],
    "l:card-sim": ["com.android.stk"],
    "s:whatsapp": ["com.whatsapp", "com.whatsapp.w4b"],
    "s:telegram": ["org.telegram.messenger", "org.telegram.messenger.web"],
    "s:instagram": ["com.instagram.android"],
    "s:threads": ["com.instagram.barcelona"],
    "s:facebook": ["com.facebook.katana", "com.facebook.lite"],
    "s:messenger": ["com.facebook.orca"],
    "s:x": ["com.twitter.android"],
    "s:tiktok": ["com.zhiliaoapp.musically", "com.ss.android.ugc.trill"],
    "s:snapchat": ["com.snapchat.android"],
    "s:pinterest": ["com.pinterest"],
    "s:reddit": ["com.reddit.frontpage"],
    "s:discord": ["com.discord"],
    "s:signal": ["org.thoughtcrime.securesms"],
    "s:spotify": ["com.spotify.music"],
    "s:youtube": ["com.google.android.youtube"],
    "s:youtubemusic": ["com.google.android.apps.youtube.music"],
    "s:netflix": ["com.netflix.mediaclient"],
    "s:twitch": ["tv.twitch.android.app"],
    "s:gmail": ["com.google.android.gm"],
    "s:googlemaps": ["com.google.android.apps.maps"],
    "s:googleplay": ["com.android.vending"],
    "s:googlechrome": ["com.android.chrome"],
    "s:google": ["com.google.android.googlequicksearchbox"],
    "s:googledrive": ["com.google.android.apps.docs"],
    "s:googlephotos": ["com.google.android.apps.photos"],
    "s:googlemeet": ["com.google.android.apps.tachyon"],
    "s:paypal": ["com.paypal.android.p2pmobile"],
    "s:uber": ["com.ubercab"],
    "s:duolingo": ["com.duolingo"],
}


def num(v):
    return float(v)


def a_trazado(el):
    """Elemento SVG (path, circle, rect, line, polyline, polygon, ellipse) → datos de trazado."""
    etiqueta = el.tag.split("}")[-1]
    a = el.attrib
    if etiqueta == "path":
        return a["d"]
    if etiqueta in ("circle", "ellipse"):
        cx, cy = num(a["cx"]), num(a["cy"])
        rx = num(a.get("r", a.get("rx", 0)))
        ry = num(a.get("r", a.get("ry", 0)))
        return f"M{cx - rx} {cy}a{rx} {ry} 0 1 0 {2 * rx} 0a{rx} {ry} 0 1 0 {-2 * rx} 0z"
    if etiqueta == "rect":
        x, y, w, h = (num(a.get(k, 0)) for k in ("x", "y", "width", "height"))
        r = num(a.get("rx", a.get("ry", 0)))
        if not r:
            return f"M{x} {y}h{w}v{h}h{-w}z"
        return (f"M{x + r} {y}h{w - 2 * r}a{r} {r} 0 0 1 {r} {r}v{h - 2 * r}a{r} {r} 0 0 1 {-r} {r}"
                f"h{-(w - 2 * r)}a{r} {r} 0 0 1 {-r} {-r}v{-(h - 2 * r)}a{r} {r} 0 0 1 {r} {-r}z")
    if etiqueta == "line":
        return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if etiqueta in ("polyline", "polygon"):
        pts = re.findall(r"-?\d*\.?\d+", a["points"])
        pares = [f"{pts[i]} {pts[i + 1]}" for i in range(0, len(pts), 2)]
        return "M" + "L".join(pares) + ("z" if etiqueta == "polygon" else "")
    return None


def leer(clave):
    tipo, nombre = clave.split(":")
    ruta = os.path.join(LUCIDE if tipo == "l" else SIMPLE, nombre + ".svg")
    raiz = ET.parse(ruta).getroot()
    trazados = [t for el in raiz.iter() if (t := a_trazado(el))]
    return tipo == "s", trazados


def kotlin(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


lineas = []
for clave, paquetes in PACK.items():
    relleno, trazados = leer(clave)
    ident = clave.split(":")[1]
    lineas.append(f"    Glifo({kotlin(ident)}, relleno = {'true' if relleno else 'false'}, trazados = listOf(" +
                  ", ".join(kotlin(t) for t in trazados) + "), paquetes = listOf(" +
                  ", ".join(kotlin(p) for p in paquetes) + ")),")

# Iconos de las creaciones de Claude (mini-apps, widgets): los de ICONOS en creaciones.mjs.
fuente = open(os.path.join(os.path.dirname(__file__), "..", "..", "servicio", "creaciones.mjs")).read()
nombres = re.findall(r'"([a-z0-9-]+)"', fuente[fuente.index("export const ICONOS"):fuente.index("];", fuente.index("export const ICONOS"))])
creaciones = []
for nombre in nombres:
    _, trazados = leer("l:" + nombre)
    creaciones.append(f"    {kotlin(nombre)} to Glifo({kotlin(nombre)}, relleno = false, trazados = listOf(" +
                      ", ".join(kotlin(x) for x in trazados) + "), paquetes = emptyList()),")

codigo = f'''package com.sios.ui

// Generado por app/herramientas/pack_iconos.py: no editar a mano.
// Glifos de línea: Lucide (ISC, lucide.dev). Glifos de marca: Simple Icons (CC0, simpleicons.org);
// las marcas son de sus dueños.

/** Un glifo del pack: trazados en 24×24, de línea o rellenos (marcas), y las apps que lo usan. */
class Glifo(val nombre: String, val relleno: Boolean, val trazados: List<String>, val paquetes: List<String>)

val GLIFOS = listOf(
{chr(10).join(lineas)}
)

/** Iconos que Claude puede elegir para lo que crea (mini-apps, widgets). */
val ICONOS_CREACIONES = mapOf(
{chr(10).join(creaciones)}
)
'''
open(SALIDA, "w").write(codigo)
print(f"{len(PACK)} glifos, {sum(len(v) for v in PACK.values())} apps → {os.path.normpath(SALIDA)}")
