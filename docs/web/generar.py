# Genera la portada de la web (index.html en español y en.html en inglés) desde un único diseño.
#   python3 docs/web/generar.py
import os, html
AQUI = os.path.dirname(os.path.abspath(__file__)); DOCS = os.path.dirname(AQUI)
REPO = "https://github.com/NeuroAIgency/superintelligence-os"
LOGO = '<svg viewBox="8 12 80 80" aria-hidden="true"><path d="M44.984 27.861A29 29 0 1 1 23 56L23 45" fill="none" stroke="currentColor" stroke-width="12.5" stroke-linecap="round" stroke-linejoin="round"/><path d="M15.4 25.15a7.6 7.6 0 1 0 15.2 0a7.6 7.6 0 1 0-15.2 0Z" fill="currentColor"/></svg>'
IAS = [("claude", "Claude", "#D97757", "#C6613F"), ("chatgpt", "ChatGPT", "#10A37F", "#0D8A6B"), ("gemini", "Gemini", "#4C8DF6", "#2F6FDB"),
       ("kimi", "Kimi", "#3B82F6", "#2563EB"), ("mistral", "Mistral", "#FA520F", "#D9440A"), ("grok", "Grok", "#A3A3A3", "#525252"), ("local", None, "#7B6CF6", "#5B4BE0")]

T = {
 "es": dict(
  lang="es", archivo="index.html", otro="en.html", otro_nombre="English", priv="privacidad.html",
  titulo="SIOS · Tu móvil hace lo que le pides", desc="SIOS (SuperIntelligenceOS): Android con la IA que tú elijas como agente del sistema. Privado y de código abierto.",
  nav_funciones="Qué trae", nav_priv="Privacidad", nav_instalar="Instalar",
  saltar="Saltar", titular=["Tu móvil", "hace lo que", "le pides."],
  entrada="SIOS es Android con una IA dentro que maneja tus apps, tus avisos y tus cosas. La que tú elijas, y sin que lo tuyo salga del móvil.",
  elige="Elige su IA", local="En el móvil", pregunta="Pregúntale a {}", ninguna="SIOS",
  cta1="Ver el código en GitHub", cta2="Cómo se instala",
  pedido="Reserva mesa para dos el viernes en un italiano cerca",
  pasos=["Buscando italianos cerca", "<b>La Piazzetta</b>, a 600 m", "Abriendo la app de reservas", "<b>Viernes, 21:00</b>, 2 personas", "Reservado y apuntado en tu calendario"],
  dia="Viernes, 14 de noviembre",
  s1t="Se lo dices como a una persona.", s1e="Escribe o habla. La IA abre las apps, toca, escribe y te enseña lo que va a hacer antes de mandar nada importante.",
  ejemplos=[("Dile a Marta que llego diez minutos tarde", "Abre tus mensajes, lo escribe y te lo enseña antes de enviarlo."),
            ("¿Dónde vi el sofá azul el martes?", "La memoria de pantalla lo encuentra, aunque la foto no tuviera texto."),
            ("Hazme un widget con el tiempo de la playa este finde", "Aparece en tu inicio y se actualiza solo."),
            ("Apúntame lo de esta factura", "Lee el PDF, anota el gasto y te propone la fecha en el calendario."),
            ("Si llama un número que no conozco, contesta tú", "Atiende, pregunta quién es y te resume la llamada."),
            ("Que nada me moleste hasta las seis", "Silencia lo que no importa y te deja pasar lo urgente.")],
  s2t="Lo tuyo no sale del móvil.", s2e="SIOS no tiene cuentas, ni analítica, ni servidores que guarden nada tuyo. Y cuando algo tiene que salir, sabes a dónde.",
  queda_t="Se queda en el móvil", queda=[("Tus contraseñas, códigos y documentos", "Cifrados, se abren con tu huella"), ("Lo que has visto en pantalla", "Si activas la memoria de pantalla"), ("Tu voz al dictar y lo que traduces", "Los modelos funcionan sin internet"), ("El texto de tus fotos", "Se lee en el propio móvil"), ("Lo que bloquea el cortafuegos", "El registro no sale de ahí")],
  sale_t="Sale, y sabes a dónde", sale=[("Lo que le pides a tu IA", "Al proveedor que elijas, o a nadie con un modelo en el móvil"), ("Dónde está tu móvil", "Cifrado por Nostr: solo lo lee tu ordenador"), ("Los modelos que descargas", "De Hugging Face, al activar una función"), ("El tiempo, las rutas, los envíos", "A servicios públicos, solo cuando lo pides")],
  nota_priv='Todo, con nombres y detalles, en la <a href="privacidad.html">política de privacidad</a>.',
  s3t="Todo lo que trae.", s3e="Un sistema completo, no una app encima de Android.",
  grupos=[("Seguridad", [("Bóveda", "Contraseñas que se rellenan solas, códigos de verificación y documentos, cifrados con tu huella."), ("Cortafuegos", "Bloquea rastreadores y publicidad, y te deja cortar internet a cada app."), ("Encontrar mi móvil", "Desde tu ordenador: ubicación, sonar, modo perdido, bloquear o borrar, y foto a quien falle el PIN."), ("Emergencias", "Cinco pulsaciones del botón de encendido piden ayuda. Ficha médica en el bloqueo y detección de caídas.")]),
          ("Sin conexión", [("Dictado y traducción", "Whisper o Vosk para dictar y traducción en vivo, en el propio móvil."), ("Texto de fotos y escáner", "Lee tickets, facturas y capturas, y escanea documentos con corrección de perspectiva."), ("Una IA en el móvil", "Un modelo que funciona sin internet, elegido según la memoria de tu móvil."), ("Memoria de pantalla", "Apagada de serie. Si la activas, encuentras lo que viste, también en imágenes.")]),
          ("Día a día", [("Inicio y widgets", "Pídele un widget y aparece. Pilas que eligen qué enseñarte en cada momento."), ("Teclado y teléfono", "Teclado moderno con PIN aleatorio, y un teléfono que contesta y resume por ti."), ("Cámara y fotos", "Cámara profesional y editor con borrador mágico, quitar fondo y edición con palabras."), ("Tienda libre y modo escritorio", "Apps de F-Droid sin cuentas, y el móvil como ordenador al conectar un monitor.")]),
          ("Con tu ordenador", [("SIOS Desktop", "Para Mac y Windows: instala SIOS por cable y pasa tus fotos, contactos y apps."), ("Continuidad", "Portapapeles compartido, tus avisos en el ordenador y archivos de uno a otro."), ("Actualizaciones seguras", "El sistema se actualiza en segundo plano y vuelve solo a la versión anterior si algo falla."), ("Hecho para durar", "Basado en LineageOS y Android 16, para móviles con Android 13 o más.")])],
  s4t="Dos ediciones.", s4e="El mismo SIOS, con o sin Google.",
  ed=[("SIOS", "Para quien necesita las apps de Google.", ["Play Store y servicios de Google", "WhatsApp, bancos y Maps sin trucos", "Se construye con los scripts del repositorio"]),
      ("SIOS Puro", "Para quien no quiere nada de Google.", ["Sin servicios de Google", "Neo Store, Aurora Store, CoMaps y Thunderbird", "La edición que se publicará para descargar"])],
  s5t="Instalarlo.", s5e="Con SIOS Desktop y un cable, sin comandos.",
  pasos_inst=[("Conecta tu Android", "Abre SIOS Desktop y conecta el móvil por USB con la depuración activada. Te dice si es compatible."), ("Pruébalo sin borrar nada", "SIOS arranca como sistema temporal. Reinicias y vuelves a tu Android, con todo como estaba."), ("Quédate con SIOS", "Si te convence, lo instalas del todo y pasas tus fotos, contactos y apps del móvil anterior.")],
  estado="<b>SIOS 0.1 está en desarrollo.</b> Funciona en el emulador de Android; todavía no se ha probado en móviles reales y aún no hay descargas oficiales.",
  s6t="Código abierto, de verdad.", s6e="Todo el código está en GitHub con licencia AGPL-3.0. Cualquiera puede comprobar lo que hace SIOS, y mejorarlo.",
  cta_codigo="Ver en GitHub", cta_contribuir="Cómo contribuir",
  pie='Claude, ChatGPT, Gemini y el resto de nombres son marcas de sus dueños; SIOS no está afiliado a ellos.', contacto="Contacto"),
 "en": dict(
  lang="en", archivo="en.html", otro="index.html", otro_nombre="Español", priv="privacy.html",
  titulo="SIOS · Your phone does what you ask", desc="SIOS (SuperIntelligenceOS): Android with the AI you choose as the system agent. Private and open source.",
  nav_funciones="Features", nav_priv="Privacy", nav_instalar="Install",
  saltar="Skip", titular=["Your phone", "does what", "you ask."],
  entrada="SIOS is Android with an AI inside that handles your apps, notifications and stuff. The one you choose, without your data leaving the phone.",
  elige="Choose its AI", local="On the phone", pregunta="Ask {}", ninguna="SIOS",
  cta1="See the code on GitHub", cta2="How to install",
  pedido="Book a table for two on Friday at an Italian place nearby",
  pasos=["Looking for Italian places nearby", "<b>La Piazzetta</b>, 600 m away", "Opening the booking app", "<b>Friday, 9 pm</b>, 2 people", "Booked and added to your calendar"],
  dia="Friday, 14 November",
  s1t="Tell it like you’d tell a person.", s1e="Type or talk. The AI opens apps, taps, types and shows you what it will do before sending anything important.",
  ejemplos=[("Tell Marta I’ll be ten minutes late", "Opens your messages, writes it and shows you before sending."),
            ("Where did I see that blue sofa on Tuesday?", "Screen memory finds it, even if the photo had no text."),
            ("Make me a widget with the beach weather this weekend", "It appears on your home screen and updates itself."),
            ("Log this invoice for me", "Reads the PDF, records the expense and suggests the date for your calendar."),
            ("If an unknown number calls, you answer", "Picks up, asks who it is and gives you a summary."),
            ("Don’t let anything bother me until six", "Silences what doesn’t matter and lets urgent things through.")],
  s2t="Your stuff stays on your phone.", s2e="SIOS has no accounts, no analytics and no servers storing anything of yours. And when something has to leave, you know where it goes.",
  queda_t="Stays on the phone", queda=[("Your passwords, codes and documents", "Encrypted, opened with your fingerprint"), ("What you’ve seen on screen", "If you turn on screen memory"), ("Your voice when dictating, and translations", "The models work offline"), ("The text in your photos", "Read on the phone itself"), ("What the firewall blocks", "The log never leaves it")],
  sale_t="Leaves, and you know where", sale=[("What you ask your AI", "To the provider you choose, or nobody with an on-device model"), ("Where your phone is", "Encrypted over Nostr: only your computer can read it"), ("Models you download", "From Hugging Face, when you turn on a feature"), ("Weather, routes, parcels", "To public services, only when you ask")],
  nota_priv='Everything, with names and details, in the <a href="privacy.html">privacy policy</a>.',
  s3t="Everything it brings.", s3e="A complete system, not an app on top of Android.",
  grupos=[("Security", [("Vault", "Passwords that fill themselves in, verification codes and documents, encrypted with your fingerprint."), ("Firewall", "Blocks trackers and ads, and lets you cut internet access per app."), ("Find my phone", "From your computer: location, ring, lost mode, lock or wipe, and a photo of whoever gets the PIN wrong."), ("Emergencies", "Five presses of the power button call for help. Medical ID on the lock screen and fall detection.")]),
          ("Offline", [("Dictation and translation", "Whisper or Vosk for dictation and live translation, on the phone itself."), ("Photo text and scanner", "Reads receipts, invoices and screenshots, and scans documents with perspective correction."), ("An AI on the phone", "A model that works without internet, chosen for your phone’s memory."), ("Screen memory", "Off by default. Turn it on and find what you saw, images included.")]),
          ("Every day", [("Home and widgets", "Ask for a widget and it appears. Stacks that pick what to show you at each moment."), ("Keyboard and phone", "A modern keyboard with a shuffled PIN pad, and a phone app that answers and summarises for you."), ("Camera and photos", "A pro camera and an editor with magic eraser, background removal and editing with words."), ("Free app store and desktop mode", "F-Droid apps with no accounts, and the phone as a computer when you plug in a monitor.")]),
          ("With your computer", [("SIOS Desktop", "For Mac and Windows: installs SIOS over USB and moves your photos, contacts and apps."), ("Continuity", "Shared clipboard, your notifications on the computer and files both ways."), ("Safe updates", "The system updates in the background and rolls back on its own if something fails."), ("Built to last", "Based on LineageOS and Android 16, for phones running Android 13 or later.")])],
  s4t="Two editions.", s4e="The same SIOS, with or without Google.",
  ed=[("SIOS", "For people who need Google apps.", ["Play Store and Google services", "WhatsApp, banking and Maps without workarounds", "Built with the scripts in the repository"]),
      ("SIOS Pure", "For people who want nothing from Google.", ["No Google services", "Neo Store, Aurora Store, CoMaps and Thunderbird", "The edition that will be published for download"])],
  s5t="Installing it.", s5e="With SIOS Desktop and a cable, no commands.",
  pasos_inst=[("Connect your Android", "Open SIOS Desktop and plug the phone in over USB with debugging on. It tells you if it’s compatible."), ("Try it without erasing anything", "SIOS boots as a temporary system. Restart and you’re back on your Android, exactly as it was."), ("Keep SIOS", "If you like it, install it for good and move your photos, contacts and apps from your old phone.")],
  estado="<b>SIOS 0.1 is in development.</b> It runs on the Android emulator; it hasn’t been tested on real phones yet and there are no official downloads yet.",
  s6t="Truly open source.", s6e="All the code is on GitHub under the AGPL-3.0 licence. Anyone can check what SIOS does, and improve it.",
  cta_codigo="See on GitHub", cta_contribuir="How to contribute",
  pie='Claude, ChatGPT, Gemini and other names are trademarks of their owners; SIOS is not affiliated with them.', contacto="Contact"),
}

def pagina(t):
    ias = "".join(f'<button type="button" data-ia="{i}" data-c="{c}" data-c2="{c2}" data-nombre="{n or t["local"]}" aria-pressed="false" style="--c:{c}"><i></i>{n or t["local"]}</button>' for i, n, c, c2 in IAS)
    pasos = "".join(f'<div class="paso"><span class="marca-paso"></span><span>{p}</span></div>' for p in t["pasos"])
    ejemplos = "".join(f'<div class="ejemplo revela"><q>{html.escape(a)}</q><p>{html.escape(b)}</p></div>' for a, b in t["ejemplos"])
    lista = lambda l: "".join(f'<li>{html.escape(a)}<span>{html.escape(b)}</span></li>' for a, b in l)
    grupos = "".join(f'<div class="grupo revela"><h3>{g}</h3><dl>' + "".join(f'<div><dt>{a}</dt><dd>{b}</dd></div>' for a, b in fs) + '</dl></div>' for g, fs in t["grupos"])
    eds = "".join(f'<div class="edicion revela"><h3>{n}</h3><p class="para">{p}</p><ul>' + "".join(f"<li>{x}</li>" for x in l) + '</ul></div>' for n, p, l in t["ed"])
    pasos_inst = "".join(f'<li class="revela"><h3>{a}</h3><p>{b}</p></li>' for a, b in t["pasos_inst"])
    titular = "".join(f"<span>{l}</span>" for l in t["titular"])
    return f'''<!doctype html>
<html lang="{t["lang"]}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{t["titulo"]}</title>
<meta name="description" content="{t["desc"]}">
<meta name="theme-color" content="#141413">
<link rel="alternate" hreflang="es" href="index.html"><link rel="alternate" hreflang="en" href="en.html">
<link rel="stylesheet" href="web/portada.css">
</head>
<body>
<div class="arranque" id="arranque" aria-hidden="true">
  <video id="video-arranque" muted playsinline preload="auto" poster="web/arranque-final.png"><source src="web/arranque.webm" type="video/webm"><source src="web/arranque.mp4" type="video/mp4"></video>
  <button type="button" class="saltar" id="saltar">{t["saltar"]}</button>
</div>
<div class="ancho">
<header class="cabecera"><a class="marca" href="{t["archivo"]}">{LOGO}<span>SIOS</span></a>
<nav><a class="opcional" href="#funciones">{t["nav_funciones"]}</a><a class="opcional" href="#privacidad">{t["nav_priv"]}</a><a href="#instalar">{t["nav_instalar"]}</a><a href="{t["otro"]}" hreflang="{"en" if t["lang"]=="es" else "es"}">{t["otro_nombre"]}</a></nav></header>
<div class="portada">
  <div class="texto-portada">
    <h1 class="titular">{titular}</h1>
    <p class="entrada">{t["entrada"]}</p>
    <p class="elige" id="elige">{t["elige"]}</p>
    <div class="ias" role="group" aria-labelledby="elige">{ias}</div>
    <div class="acciones"><a class="boton lleno" href="{REPO}">{t["cta1"]}</a><a class="boton borde" href="#instalar">{t["cta2"]}</a></div>
  </div>
  <div class="escena" aria-hidden="true">
    <div class="halo"></div>
    <div class="movil"><div class="pantalla">
      <div class="isla"></div>
      <div class="estado"><span>9:41</span><span>5G</span></div>
      <div class="hora">9:41</div><div class="fecha">{t["dia"]}</div>
      <svg class="anillo" id="anillo" viewBox="8 12 80 80"><path class="trazo" d="M44.984 27.861A29 29 0 1 1 23 56L23 45"/><circle class="punto" cx="23" cy="25.15" r="7.6"/></svg>
      <div class="charla" id="charla"><div class="burbuja">{t["pedido"]}</div>{pasos}</div>
      <div class="barra"><span id="pregunta" data-plantilla="{t["pregunta"]}">{t["pregunta"].format(t["ninguna"])}</span><span class="bola"></span></div>
    </div></div>
  </div>
</div>
</div>
<section><div class="ancho">
  <h2 class="seccion-titulo revela">{t["s1t"]}</h2><p class="seccion-entrada revela">{t["s1e"]}</p>
  <div class="ejemplos">{ejemplos}</div>
</div></section>
<section id="privacidad"><div class="ancho">
  <h2 class="seccion-titulo revela">{t["s2t"]}</h2><p class="seccion-entrada revela">{t["s2e"]}</p>
  <div class="privacidad"><div class="caja queda revela"><h3>{t["queda_t"]}</h3><ul>{lista(t["queda"])}</ul></div><div class="caja sale revela"><h3>{t["sale_t"]}</h3><ul>{lista(t["sale"])}</ul></div></div>
  <p class="nota revela">{t["nota_priv"]}</p>
</div></section>
<section id="funciones"><div class="ancho">
  <h2 class="seccion-titulo revela">{t["s3t"]}</h2><p class="seccion-entrada revela">{t["s3e"]}</p>
  {grupos}
</div></section>
<section><div class="ancho">
  <h2 class="seccion-titulo revela">{t["s4t"]}</h2><p class="seccion-entrada revela">{t["s4e"]}</p>
  <div class="ediciones">{eds}</div>
</div></section>
<section id="instalar"><div class="ancho">
  <h2 class="seccion-titulo revela">{t["s5t"]}</h2><p class="seccion-entrada revela">{t["s5e"]}</p>
  <ol class="pasos">{pasos_inst}</ol>
  <p class="estado-version revela">{t["estado"]}</p>
</div></section>
<section><div class="ancho abierto">
  <div><h2 class="seccion-titulo revela">{t["s6t"]}</h2><p class="seccion-entrada revela">{t["s6e"]}</p></div>
  <div class="acciones revela"><a class="boton lleno" href="{REPO}">{t["cta_codigo"]}</a><a class="boton borde" href="{REPO}/blob/main/CONTRIBUTING.md">{t["cta_contribuir"]}</a></div>
</div></section>
<div class="ancho"><footer class="pie"><nav class="enlaces-pie"><a href="{REPO}/blob/main/LICENSE">AGPL-3.0</a><a href="{t["priv"]}">{t["nav_priv"]}</a><a href="mailto:info@neuroaigency.com">info@neuroaigency.com</a></nav><p>{t["pie"]}</p></footer></div>
<script src="web/portada.js"></script>
</body>
</html>
'''

for k, t in T.items():
    open(os.path.join(DOCS, t["archivo"]), "w", encoding="utf-8").write(pagina(t))
print("portada lista")
