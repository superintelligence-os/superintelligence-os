#!/usr/bin/env python3
"""Hoja de marca de SIOS (PNG): símbolo, logotipo, el punto con cada IA, versiones y tamaños."""
import os, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from simbolo import ESPECTRO, GRAFITO, HUESO, svg_logotipo, svg_simbolo

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
DEF = '<defs><linearGradient id="esp" x1="0" y1="0" x2="1" y2="1">' + "".join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in ESPECTRO) + "</linearGradient></defs>"
def s(c, w, h, vb): return f'<svg width="{w}" height="{h}" viewBox="{vb}">{DEF}{c}</svg>'
IAS = [("Claude", "#D97757"), ("ChatGPT", "#10A37F"), ("Gemini", "#4C8DF6"), ("Qwen", "#7B6CF6"), ("Kimi", "#3B82F6"), ("Mistral", "#FA520F")]
logo, w = svg_logotipo(punto="url(#esp)")
logo_osc, _ = svg_logotipo(color=GRAFITO, punto="url(#esp)")
firma, w2 = svg_logotipo(punto="url(#esp)", x0=130)
html = f"""<html><head><style>
@font-face {{ font-family: M; src: url(file://{os.path.abspath('../../../escritorio/recursos/montserrat.ttf')}); }}
body {{ margin:0; width:1600px; background:#0b0b0a; font-family:M, sans-serif; color:#8a8984; }}
.hero {{ height:620px; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:34px; background:radial-gradient(ellipse at 50% 30%, #1d1b26 0%, {GRAFITO} 70%); }}
.lema {{ font-size:22px; letter-spacing:.32em; text-transform:uppercase; color:#b9b6ad; }}
.fila {{ display:flex; gap:20px; padding:0 40px 20px; }}
.c {{ flex:1; border-radius:24px; padding:30px; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:14px; font-size:15px; }}
h3 {{ margin:44px 40px 18px; font-size:15px; letter-spacing:.2em; text-transform:uppercase; color:#6f6d66; font-weight:600; }}
.nota {{ margin:0 40px 40px; font-size:17px; line-height:1.6; color:#a3a098; max-width:1300px; }}
</style></head><body>
<div class="hero">{s(svg_simbolo(punto="url(#esp)"), 260, 260, "0 0 100 100")}{s(logo, 520, 160, f"-6 22 {w+12} 84")}<div class="lema">SuperIntelligence OS</div></div>
<h3>El punto es la IA que uses</h3>
<div class="fila">{"".join(f'<div class="c" style="background:{GRAFITO}">{s(svg_simbolo(punto=c), 120, 120, "0 0 100 100")}<span>{n}</span></div>' for n, c in IAS)}</div>
<h3>Versiones</h3>
<div class="fila">
<div class="c" style="background:{GRAFITO}">{s(firma, 520, 130, f"-5 0 {130+w2+10} 125").replace('<svg', '<svg', 1).replace(DEF, DEF + '<g transform="translate(0,10)">' + svg_simbolo(punto="url(#esp)") + '</g>', 1)}<span>Firma</span></div>
<div class="c" style="background:{HUESO}">{s(logo_osc, 380, 120, f"-6 22 {w+12} 84")}<span style="color:#73726c">Sobre claro</span></div>
</div>
<div class="fila">
<div class="c" style="background:#1a1a19">{s('<rect x="11.5" y="11.5" width="107" height="107" rx="24" fill="' + GRAFITO + '"/><g transform="translate(19.8 17.7) scale(0.868)">' + svg_simbolo(punto="url(#esp)") + '</g>', 150, 150, "0 0 130 130")}<span>Icono de la app</span></div>
<div class="c" style="background:{GRAFITO}">{''.join(s(svg_simbolo(punto="#D97757"), t, t, "0 0 100 100") for t in (96, 48, 32, 20))}<span>96 · 48 · 32 · 20 px</span></div>
<div class="c" style="background:{GRAFITO}">{s(svg_simbolo(anillo=HUESO, punto=HUESO), 110, 110, "0 0 100 100")}<span>Un color</span></div>
</div>
<h3>La idea</h3>
<div class="nota">Un anillo —la <b>o</b>, el sistema— cuyo lado izquierdo sube recto y lleva un punto encima —la <b>i</b>, la inteligencia—. La i y la o comparten trazo: son una sola pieza, el «io» del centro de s·io·s. Se lee también como I/O, lo que hace un sistema operativo, y en italiano «yo»: el sistema es tuyo. El anillo nunca cambia; el punto toma el color de la IA que hayas elegido. Sin ninguna, lleva el espectro de todas.</div>
</body></html>"""
with tempfile.TemporaryDirectory() as tmp:
    h = os.path.join(tmp, "m.html"); open(h, "w").write(html)
    salida = os.path.join(os.path.dirname(os.path.abspath(__file__)), "marca-sios.png")
    subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--screenshot={salida}", "--window-size=1600,1780", "file://" + h], check=True, capture_output=True)
    print(salida)
