#!/usr/bin/env python3
"""Pasa a la paleta de Claude los colores propios de las apps del sistema que no siguen la paleta
de Android (Mensajes, Calendario, Archivos, Calculadora, Contactos…).

Lee los colores de cada APK con aapt2 (los de la app, no los de AppCompat/Material, que ya siguen
la paleta del sistema) y escribe una capa de SIOS por app en rom/overlay-<nombre>/:
  - fondos, barras y cabeceras → gris de Claude (#262624 / #30302E en oscuro; #FAF9F5 / #F0EEE6 en claro)
  - acentos (primary, accent, fab, botones, «hoy», enlaces…) → naranja de Claude (#D97757)
  - rojos de error, borrar o llamada perdida → se quedan
  - el resto → misma luminosidad, en gris cálido o en el naranja de Claude

Uso: temas_apps.py <apk> <paquete> <nombre_capa> [<apk> <paquete> <nombre_capa> …]
     temas_apps.py --dinamicos   (pasa las capas ya generadas a colores del sistema)

Los colores se escriben como referencias a la paleta del sistema (@android:color/system_…): así
siguen a SIOS en blanco y negro y, al elegir una IA, a su color.
"""
import math
import os
import re
import subprocess
import sys

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AAPT = sorted(__import__("glob").glob("/opt/homebrew/share/android-commandlinetools/build-tools/*/aapt2"))[-1]
LIBRERIAS = re.compile(r"^(abc_|design_|m3_|mtrl_|material_|androidx_|notification_|tooltip_|switch_thumb|ripple_material|preference_|browser_actions|call_notification|cardview_|dim_foreground|foreground_material|highlighted_text|primary_dark_material|primary_material|primary_text_|secondary_text_|bright_foreground|background_floating_material|background_material|button_material|accent_material|error_color_material|settingslib_)")
FONDO = re.compile(r"(background|_bg\b|bg_|action_?bar|toolbar|status_?bar|navigation_?bar|header|banner|surface|window|card|sheet|dialog|divider|separator|grid_line|future_bg|past_bg|panel)")
ACENTO = re.compile(r"(primary|accent|fab|button|today|selected|highlight|link|tint|indicator|check|progress|switch|active|focus|cursor|equals|pressed|ripple|now_line|time_line)")
def es_fondo(nombre):
    """Fondos y barras: «background_primary» es un fondo aunque diga «primary»."""
    return bool(FONDO.search(nombre)) and (not ACENTO.search(nombre) or nombre.startswith("background"))

CONSERVAR = re.compile(r"(error|warning|delete|destructive|missed|declined|red\b|_red|alert_event)")

# ---------- Color (CIELAB, D65) ----------

def lin(v): return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
def gam(v): return 12.92 * v if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055
def f(t): return t ** (1 / 3) if t > 0.008856 else 7.787 * t + 16 / 116
def fi(t): return t ** 3 if t ** 3 > 0.008856 else (t - 16 / 116) / 7.787

def lab(rgb):
    r, g, b = (lin(c / 255) for c in rgb)
    x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883
    return 116 * f(y) - 16, 500 * (f(x) - f(y)), 200 * (f(y) - f(z))

def rgb_de_lab(l, a, b):
    fy = (l + 16) / 116
    x, y, z = 0.95047 * fi(fy + a / 500), fi(fy), 1.08883 * fi(fy - b / 200)
    canales = (3.2406 * x - 1.5372 * y - 0.4986 * z, -0.9689 * x + 1.8758 * y + 0.0415 * z, 0.0557 * x - 0.2040 * y + 1.0570 * z)
    return tuple(round(max(0, min(1, gam(c))) * 255) for c in canales)

def gris(l): return rgb_de_lab(l, 0.3, 2.0)

def naranja(l):
    if 45 <= l <= 75: return (0xD9, 0x77, 0x57)
    k = max(0.15, min(1.0, 1 - abs(l - 62) / 62 * 0.7))
    return rgb_de_lab(l, 33 * k, 33 * k)

def convertir(nombre, argb, noche):
    a, r, g, b = argb >> 24, (argb >> 16) & 255, (argb >> 8) & 255, argb & 255
    l, la, lb = lab((r, g, b))
    croma = math.hypot(la, lb)
    tono = math.degrees(math.atan2(lb, la)) % 360
    if CONSERVAR.search(nombre) and croma > 20 and (tono < 40 or tono > 330):
        return None  # rojo con significado: se queda
    if es_fondo(nombre):
        if l < 50:
            nuevo = (0x26, 0x26, 0x24) if l < 22 else (0x30, 0x30, 0x2E)
        else:
            nuevo = (0xFA, 0xF9, 0xF5) if l > 94 else (0xF0, 0xEE, 0xE6)
    elif croma < 12:
        if l < 2 or l > 99:
            return None  # negro y blanco puros se quedan
        nuevo = gris(l)
    else:
        nuevo = naranja(l)
    return (a << 24) | (nuevo[0] << 16) | (nuevo[1] << 8) | nuevo[2]

# Papeles de Material 3 en Claude (los mismos que ColoresSistema.kt pone en la paleta del sistema).
# Sirven para las apps que llevan dentro los colores fijos de referencia de Material 3
# (m3_sys_color_dark_surface = #141218…) en vez de los del sistema.
PAPELES_OSCURO = {
    "background": 0xFF262624,
    "on_background": 0xFFFAF9F5,
    "surface": 0xFF262624,
    "surface_dim": 0xFF1F1E1D,
    "surface_bright": 0xFF3D3D3A,
    "surface_container_lowest": 0xFF1F1E1D,
    "surface_container_low": 0xFF2A2A27,
    "surface_container": 0xFF30302E,
    "surface_container_high": 0xFF353532,
    "surface_container_highest": 0xFF3D3D3A,
    "surface_variant": 0xFF3D3D3A,
    "on_surface": 0xFFFAF9F5,
    "on_surface_variant": 0xFFC2C0B6,
    "outline": 0xFF73726C,
    "outline_variant": 0xFF3E3D3A,
    "inverse_surface": 0xFFFAF9F5,
    "inverse_on_surface": 0xFF262624,
    "inverse_primary": 0xFFB5573A,
    "primary": 0xFFD97757,
    "on_primary": 0xFFFFFFFF,
    "primary_container": 0xFF5C2E20,
    "on_primary_container": 0xFFF9D5C7,
    "primary_dim": 0xFFC6613F,
    "surface_tint": 0xFFD97757,
    "secondary": 0xFFC2C0B6,
    "on_secondary": 0xFF262624,
    "secondary_container": 0xFF3D3D3A,
    "on_secondary_container": 0xFFFAF9F5,
    "secondary_dim": 0xFF9C9A92,
    "tertiary": 0xFFD97757,
    "on_tertiary": 0xFFFFFFFF,
    "tertiary_container": 0xFF5C2E20,
    "on_tertiary_container": 0xFFF9D5C7,
    "tertiary_dim": 0xFFC6613F,
    "primary_fixed": 0xFFF9D5C7,
    "primary_fixed_dim": 0xFFE9A68C,
    "on_primary_fixed": 0xFF33170E,
    "on_primary_fixed_variant": 0xFF622D1C,
    "secondary_fixed": 0xFFE8E6DC,
    "secondary_fixed_dim": 0xFFC2C0B6,
    "on_secondary_fixed": 0xFF141413,
    "on_secondary_fixed_variant": 0xFF3D3D3A,
    "tertiary_fixed": 0xFFF9D5C7,
    "tertiary_fixed_dim": 0xFFE9A68C,
    "on_tertiary_fixed": 0xFF33170E,
    "on_tertiary_fixed_variant": 0xFF622D1C,
    "shadow": 0xFF000000,
    "scrim": 0xFF000000,
}
PAPELES_CLARO = {
    "background": 0xFFFAF9F5,
    "on_background": 0xFF141413,
    "surface": 0xFFFAF9F5,
    "surface_dim": 0xFFE8E6DC,
    "surface_bright": 0xFFFFFFFF,
    "surface_container_lowest": 0xFFFFFFFF,
    "surface_container_low": 0xFFF5F4ED,
    "surface_container": 0xFFF0EEE6,
    "surface_container_high": 0xFFEBE9E0,
    "surface_container_highest": 0xFFE8E6DC,
    "surface_variant": 0xFFE8E6DC,
    "on_surface": 0xFF141413,
    "on_surface_variant": 0xFF3D3D3A,
    "outline": 0xFF73726C,
    "outline_variant": 0xFFD1CFC5,
    "inverse_surface": 0xFF30302E,
    "inverse_on_surface": 0xFFFAF9F5,
    "inverse_primary": 0xFFE9A68C,
    "primary": 0xFFC6613F,
    "on_primary": 0xFFFFFFFF,
    "primary_container": 0xFFF9D5C7,
    "on_primary_container": 0xFF5C2E20,
    "primary_dim": 0xFFB5573A,
    "surface_tint": 0xFFC6613F,
    "secondary": 0xFF5E5D59,
    "on_secondary": 0xFFFFFFFF,
    "secondary_container": 0xFFE8E6DC,
    "on_secondary_container": 0xFF141413,
    "secondary_dim": 0xFF73726C,
    "tertiary": 0xFFC6613F,
    "on_tertiary": 0xFFFFFFFF,
    "tertiary_container": 0xFFF9D5C7,
    "on_tertiary_container": 0xFF5C2E20,
    "tertiary_dim": 0xFFB5573A,
    "primary_fixed": 0xFFF9D5C7,
    "primary_fixed_dim": 0xFFE9A68C,
    "on_primary_fixed": 0xFF33170E,
    "on_primary_fixed_variant": 0xFF622D1C,
    "secondary_fixed": 0xFFE8E6DC,
    "secondary_fixed_dim": 0xFFC2C0B6,
    "on_secondary_fixed": 0xFF141413,
    "on_secondary_fixed_variant": 0xFF3D3D3A,
    "tertiary_fixed": 0xFFF9D5C7,
    "tertiary_fixed_dim": 0xFFE9A68C,
    "on_tertiary_fixed": 0xFF33170E,
    "on_tertiary_fixed_variant": 0xFF622D1C,
    "shadow": 0xFF000000,
    "scrim": 0xFF000000,
}
M3_FIJO = re.compile(r"^m3_sys_color_(dark|light)_(\w+)$")

# ---------- Colores fijos → colores del sistema ----------

# Tonos de la paleta de Android (sombra 0 = blanco … 1000 = negro) y su luminosidad L* (= tono HCT).
SOMBRAS = [(0, 100), (10, 99), (50, 95), (100, 90), (200, 80), (300, 70), (400, 60), (500, 50), (600, 40), (700, 30), (800, 20), (900, 10), (1000, 0)]
# Los papeles fijos de Claude que ya tienen nombre en Material: así siguen a la paleta del sistema.
# Solo colores públicos del SDK con el que se enlazan las capas (android-36): los «_dim» no lo son.
PAPEL_DE = {
    (0xFF262624, True): "system_background_dark", (0xFF30302E, True): "system_surface_container_dark",
    (0xFF1F1E1D, True): "system_surface_container_lowest_dark", (0xFF3D3D3A, True): "system_surface_container_highest_dark",
    (0xFFFAF9F5, True): "system_on_surface_dark", (0xFFC2C0B6, True): "system_on_surface_variant_dark",
    (0xFFD97757, True): "system_primary_dark",
    (0xFFFAF9F5, False): "system_background_light", (0xFFF0EEE6, False): "system_surface_container_light",
    (0xFFFFFFFF, False): "system_surface_container_lowest_light", (0xFFE8E6DC, False): "system_surface_container_highest_light",
    (0xFF141413, False): "system_on_surface_light", (0xFF3D3D3A, False): "system_on_surface_variant_light",
    (0xFFC6613F, False): "system_primary_light",
    (0xFFD97757, False): "system_primary_light",
}

def dinamico(argb, noche):
    """Valor para la capa: una referencia a la paleta del sistema (sigue a SIOS en blanco y negro y,
    al elegir una IA, a su color) o el color tal cual si lleva transparencia o no tiene equivalente."""
    if argb >> 24 != 0xFF:
        return f"#{argb:08X}"
    if (argb, noche) in PAPEL_DE:
        return f"@android:color/{PAPEL_DE[(argb, noche)]}"
    r, g, b = (argb >> 16) & 255, (argb >> 8) & 255, argb & 255
    l, la, lb = lab((r, g, b))
    if (r, g, b) in ((0, 0, 0), (255, 255, 255)):
        return f"#{argb:08X}"
    sombra = min(SOMBRAS, key=lambda s: abs(s[1] - l))[0]
    paleta = "accent1" if math.hypot(la, lb) >= 12 else "neutral1"
    return f"@android:color/system_{paleta}_{sombra}"


def reescribir_dinamicos():
    """Pasa las capas ya generadas (rom/overlay-*) de colores fijos a colores del sistema."""
    for carpeta in sorted(__import__("glob").glob(os.path.join(RAIZ, "overlay-*", "res", "values*"))):
        noche = carpeta.endswith("-night")
        for archivo in ("colors.xml", "valores.xml"):
            ruta = os.path.join(carpeta, archivo)
            if not os.path.exists(ruta) or "overlay-framework" in ruta:
                continue
            texto = open(ruta).read()
            nuevo = re.sub(r'(<color name="[^"]+">)#([0-9A-Fa-f]{8})(</color>)',
                           lambda m: m.group(1) + dinamico(int(m.group(2), 16), noche) + m.group(3), texto)
            nuevo = nuevo.replace("pasados a la paleta de Claude", "pasados a la paleta del sistema (la de SIOS o la de la IA elegida)")
            if nuevo != texto:
                open(ruta, "w").write(nuevo)
                print("  ", os.path.relpath(ruta, RAIZ))

# ---------- APK → capa ----------

# Ajustes de medidas por app (además de los colores).
MEDIDAS = {
    # Los botones del teclado de la Calculadora llevan 16 dp de relleno por lado: con 4 columnas
    # «AC» no cabe y se parte en dos líneas. Con 4 dp cabe (también en Montserrat).
    "com.android.calculator2": {"m3_comp_button_small_leading_space": "4dp", "m3_comp_button_small_trailing_space": "4dp"},
}

# Valores sueltos por app (bool, string, color fijo) que no salen de convertir colores.
# Archivos (DocumentsUI) solo deja cambiar su lista «overlayable»: con force_material3 usa el tema
# Material 3, que sigue la paleta del sistema (la de Claude), y la letra de SIOS.
VALORES = {
    # Contactos pinta la barra superior con colorPrimary (= primary_color) y la barra de estado con
    # primary_color_dark: el acento, naranja de Claude; las barras, del gris del fondo.
    "com.android.contacts": {
        "": [("color", "primary_color", "#FFC6613F"), ("color", "primary_color_dark", "#FFFAF9F5")],
        "night": [("color", "primary_color", "#FFD97757"), ("color", "primary_color_dark", "#FF262624")],
    },
    "com.android.documentsui": {
        "": [("bool", "force_material3", "true"), ("color", "primary", "#FFC6613F"), ("color", "color_surface_header", "#FFFAF9F5"),
             ("string", "config_fontFamily", "sios-sans"), ("string", "config_fontFamilyMedium", "sios-sans-medium")],
        "night": [("color", "primary", "#FFD97757"), ("color", "color_surface_header", "#FF262624")],
    },
}

def overlayable(apk):
    """(nombre, {recursos}) si la app limita qué se puede cambiar con <overlayable>; si no, (None, None)."""
    salida = subprocess.run([AAPT, "dump", "overlayable", apk], capture_output=True, text=True).stdout
    m = re.search(r'name="([^"]+)"', salida)
    if not m:
        return None, None
    return m.group(1), {l.strip() for l in salida.splitlines() if re.match(r"^\s+\w+/\S+$", l)}

FORZADOS = set()  # (nombre, config) que se escriben aunque ya tengan el color de Claude (venían de la paleta)

def colores(apk):
    """{(nombre, config): argb} de los colores literales de la app (sin los de librerías)."""
    salida = subprocess.run([AAPT, "dump", "resources", apk], capture_output=True, text=True).stdout
    res, actual = {}, None
    for linea in salida.splitlines():
        m = re.match(r"^\s+resource 0x[0-9a-f]+ color/(\S+)", linea)
        if m:
            actual = None if LIBRERIAS.match(m.group(1)) and not M3_FIJO.match(m.group(1)) else m.group(1)
            continue
        if re.match(r"^\s*(resource 0x|type )", linea):
            actual = None  # otro tipo de recurso: sus valores no son colores
            continue
        if actual is None:
            continue
        if M3_FIJO.match(actual):
            m = re.match(r"^\s+\(([^)]*)\) [@#]", linea)
            if m:
                res[(actual, m.group(1))] = 0  # papel fijo de Material 3: se pone el de Claude
            continue
        m = re.match(r"^\s+\(([^)]*)\) #([0-9a-fA-F]{8})\s*$", linea)
        if m:
            res[(actual, m.group(1))] = int(m.group(2), 16)
            continue
        # Fondos y barras que apuntan a un tono de la paleta del sistema (@0x0106…): con la paleta
        # de Claude saldrían naranjas; en Claude son grises (oscuro y claro).
        m = re.match(r"^\s+\(([^)]*)\) @0x0106[0-9a-f]{4}\s*$", linea)
        if m and es_fondo(actual):
            res[(actual, "night")] = 0xFF262624
            res[(actual, "")] = 0xFFF0EEE6
            FORZADOS.update({(actual, "night"), (actual, "")})
    return res

def escribir(paquete, capa, cols, apk):
    base = os.path.join(RAIZ, f"overlay-{capa}")
    nombre_ov, permitidos = overlayable(apk)
    if os.path.isdir(os.path.join(base, "res")):
        __import__("shutil").rmtree(os.path.join(base, "res"))
    por_config = {}
    for (nombre, config), argb in cols.items():
        if config not in ("", "night"):
            continue
        if permitidos is not None and f"color/{nombre}" not in permitidos:
            continue  # la app no deja cambiarlo: Android rechazaría la capa entera
        papel = M3_FIJO.match(nombre)
        if papel:
            tabla = PAPELES_OSCURO if papel.group(1) == "dark" else PAPELES_CLARO
            if papel.group(2) not in tabla:
                continue
            nuevo = tabla[papel.group(2)]
            if nuevo != argb:
                por_config.setdefault(config, {})[nombre] = nuevo
            continue
        nuevo = convertir(nombre, argb, config == "night")
        if (nombre, config) in FORZADOS:
            nuevo = argb if nuevo is None else nuevo
        if nuevo is not None and (nuevo != argb or (nombre, config) in FORZADOS):
            por_config.setdefault(config, {})[nombre] = nuevo
    medidas = MEDIDAS.get(paquete, {})
    valores = VALORES.get(paquete, {})
    for config, lista in valores.items():  # los valores fijos mandan sobre los convertidos
        for t, n, _ in lista:
            por_config.get(config, {}).pop(n, None)
    por_config = {c: v for c, v in por_config.items() if v}
    if not por_config and not medidas and not valores:
        print(f"  {capa}: nada que cambiar ({paquete} ya usa la paleta del sistema)")
        return
    if medidas:
        carpeta = os.path.join(base, "res", "values")
        os.makedirs(carpeta, exist_ok=True)
        lineas = ['<?xml version="1.0" encoding="utf-8"?>', f"<!-- Medidas de {paquete} ajustadas por SIOS (marca/temas_apps.py). -->", "<resources>"]
        lineas += [f'    <dimen name="{n}">{v}</dimen>' for n, v in sorted(medidas.items())]
        open(os.path.join(carpeta, "dimens.xml"), "w").write("\n".join(lineas + ["</resources>", ""]))
    for config, cambios in por_config.items():
        carpeta = os.path.join(base, "res", "values" + ("-night" if config == "night" else ""))
        os.makedirs(carpeta, exist_ok=True)
        lineas = ['<?xml version="1.0" encoding="utf-8"?>', f"<!-- Colores de {paquete} pasados a la paleta del sistema (la de SIOS o la de la IA elegida) (marca/temas_apps.py). -->", "<resources>"]
        lineas += [f'    <color name="{n}">{dinamico(v, config == "night")}</color>' for n, v in sorted(cambios.items())]
        open(os.path.join(carpeta, "colors.xml"), "w").write("\n".join(lineas + ["</resources>", ""]))
    for config, lista in valores.items():
        carpeta = os.path.join(base, "res", "values" + ("-night" if config == "night" else ""))
        os.makedirs(carpeta, exist_ok=True)
        lineas = ['<?xml version="1.0" encoding="utf-8"?>', f"<!-- Valores de {paquete} para SIOS (marca/temas_apps.py). -->", "<resources>"]
        lineas += [f'    <{t} name="{n}">{dinamico(int(v[1:], 16), config == "night") if t == "color" else v}</{t}>' for t, n, v in lista]
        open(os.path.join(carpeta, "valores.xml"), "w").write("\n".join(lineas + ["</resources>", ""]))
    open(os.path.join(base, "AndroidManifest.xml"), "w").write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- SIOS: {paquete} con los colores de Claude (generado por marca/temas_apps.py). -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.sios.overlay.{capa}">
    <overlay android:targetPackage="{paquete}"{f' android:targetName="{nombre_ov}"' if nombre_ov else ""} android:isStatic="true" android:priority="900" />
    <application android:hasCode="false" />
</manifest>
''')
    print(f"  {capa}: {sum(len(v) for v in por_config.values())} colores" + (f" y {len(medidas)} medidas" if medidas else "") + (f" y {sum(len(v) for v in valores.values())} valores" if valores else "") + (f" [overlayable {nombre_ov}]" if nombre_ov else "") + f" ({paquete})")

if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--dinamicos"]:
        reescribir_dinamicos()
        sys.exit(0)
    for i in range(0, len(args), 3):
        escribir(args[i + 1], args[i + 2], colores(args[i]), args[i])
