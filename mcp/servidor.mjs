#!/usr/bin/env node
// Servidor MCP de SIOS: expone el propio móvil a Claude.
// Habla con Android por ADB contra el mismo dispositivo (127.0.0.1:5555), lo que da
// permisos de nivel shell sin root: leer la interfaz, tocar, escribir, abrir apps.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const run = promisify(execFile);
const DISPOSITIVO = process.env.SIOS_ADB || "127.0.0.1:5555";
const UI_XML = "/sdcard/claude-ui.xml";

// ---------- ADB ----------

// Comillas simples de shell para pasar texto arbitrario a `adb shell`.
const q = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;

async function conectar() {
  await run("adb", ["connect", DISPOSITIVO]).catch(() => {});
}

async function adb(args, binario = false) {
  const opts = { maxBuffer: 64 * 1024 * 1024, encoding: binario ? "buffer" : "utf8" };
  try {
    return (await run("adb", ["-s", DISPOSITIVO, ...args], opts)).stdout;
  } catch {
    // La conexión se cae si Termux se reinicia o cambia la red: reconecta y reintenta una vez.
    await conectar();
    return (await run("adb", ["-s", DISPOSITIVO, ...args], opts)).stdout;
  }
}

const shell = (cmd) => adb(["shell", cmd]);

// ---------- Pantalla ----------

// Elementos de la última lectura, para poder tocar por número.
let ultimos = [];

function atributos(nodo) {
  const a = {};
  for (const m of nodo.matchAll(/([\w-]+)="([^"]*)"/g)) a[m[1]] = m[2];
  return a;
}

const desescapar = (s) =>
  s.replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&amp;/g, "&");

function centro(bounds) {
  const m = bounds.match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
  if (!m) return null;
  const [x1, y1, x2, y2] = m.slice(1).map(Number);
  if (x2 <= x1 || y2 <= y1) return null;
  return { x: Math.round((x1 + x2) / 2), y: Math.round((y1 + y2) / 2) };
}

async function volcarInterfaz() {
  // uiautomator falla si la pantalla está animándose; un segundo intento suele bastar.
  for (let intento = 0; intento < 3; intento++) {
    const salida = await shell(`uiautomator dump ${UI_XML} 2>&1; cat ${UI_XML}`);
    const i = salida.indexOf("<?xml");
    if (i >= 0) return salida.slice(i);
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("No se pudo leer la interfaz (uiautomator no respondió).");
}

async function appEnPrimerPlano() {
  const s = await shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2");
  const m = s.match(/([a-zA-Z][\w.]+)\/([\w.$]+)/);
  return m ? m[1] : "desconocida";
}

async function leerElementos() {
  const xml = await volcarInterfaz();
  const elementos = [];
  for (const nodo of xml.match(/<node [^>]*>/g) || []) {
    const a = atributos(nodo);
    const texto = desescapar(a.text || "");
    const desc = desescapar(a["content-desc"] || "");
    const pulsable = a.clickable === "true" || a["long-clickable"] === "true";
    const desplazable = a.scrollable === "true";
    const editable = a.class?.includes("EditText");
    if (!texto && !desc && !pulsable && !desplazable && !editable) continue;
    const c = centro(a.bounds || "");
    if (!c) continue;
    elementos.push({
      texto,
      desc,
      id: (a["resource-id"] || "").split("/").pop(),
      tipo: (a.class || "").split(".").pop(),
      pulsable,
      desplazable,
      editable,
      marcado: a.checked === "true",
      activo: a.enabled !== "false",
      ...c,
    });
  }
  ultimos = elementos;
  return elementos;
}

function describir(e, n) {
  const partes = [`[${n}] ${e.tipo}`];
  if (e.texto) partes.push(`"${e.texto}"`);
  if (e.desc && e.desc !== e.texto) partes.push(`(${e.desc})`);
  if (e.id) partes.push(`#${e.id}`);
  const marcas = [
    e.pulsable && "pulsable",
    e.editable && "editable",
    e.desplazable && "desplazable",
    e.marcado && "marcado",
    !e.activo && "desactivado",
  ].filter(Boolean);
  if (marcas.length) partes.push(`{${marcas.join(", ")}}`);
  partes.push(`@${e.x},${e.y}`);
  return partes.join(" ");
}

function buscarPorTexto(texto) {
  const t = texto.toLowerCase();
  return (
    ultimos.find((e) => e.texto.toLowerCase() === t || e.desc.toLowerCase() === t) ||
    ultimos.find((e) => e.texto.toLowerCase().includes(t) || e.desc.toLowerCase().includes(t))
  );
}

// ---------- Apps ----------

const ALIAS = {
  ajustes: "com.android.settings",
  configuracion: "com.android.settings",
  chrome: "com.android.chrome",
  navegador: "com.android.chrome",
  camara: "com.android.camera2",
  contactos: "com.google.android.contacts",
  telefono: "com.google.android.dialer",
  mensajes: "com.google.android.apps.messaging",
  calendario: "com.google.android.calendar",
  reloj: "com.google.android.deskclock",
  archivos: "com.google.android.documentsui",
  fotos: "com.google.android.apps.photos",
  termux: "com.termux",
};

const sinAcentos = (s) => s.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase();

async function appsInstaladas() {
  const s = await shell(
    "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
  );
  return [...new Set([...s.matchAll(/^\s*([\w.]+)\/[\w.$]+/gm)].map((m) => m[1]))].sort();
}

async function resolverApp(nombre) {
  const n = sinAcentos(nombre.trim());
  const apps = await appsInstaladas();
  if (apps.includes(nombre)) return nombre;
  if (ALIAS[n] && apps.includes(ALIAS[n])) return ALIAS[n];
  return apps.find((p) => p.toLowerCase().includes(n.replace(/\s+/g, ""))) || null;
}

// ---------- Notificaciones ----------

async function notificaciones() {
  const s = await shell("dumpsys notification --noredact");
  const lista = [];
  for (const bloque of s.split(/NotificationRecord\(/).slice(1)) {
    const pkg = bloque.match(/pkg=([\w.]+)/)?.[1];
    const titulo = bloque.match(/android\.title=\w+ \((.*)\)/)?.[1];
    const texto = bloque.match(/android\.text=\w+ \((.*)\)/)?.[1];
    if (!pkg || (!titulo && !texto)) continue;
    lista.push({ app: pkg, titulo: titulo || "", texto: texto || "" });
  }
  return lista;
}

// ---------- Utilidades ----------

const texto = (t) => ({ content: [{ type: "text", text: t }] });
const espera = (ms) => new Promise((r) => setTimeout(r, ms));

async function tamanoPantalla() {
  const s = await shell("wm size");
  const m = s.match(/(\d+)x(\d+)\s*$/m);
  return m ? { ancho: +m[1], alto: +m[2] } : { ancho: 1080, alto: 2400 };
}

// ---------- Servidor ----------

const servidor = new McpServer({ name: "movil", version: "0.1.0" });

servidor.registerTool(
  "leer_pantalla",
  {
    title: "Leer la pantalla",
    description:
      "Devuelve la app en primer plano y una lista numerada de los elementos visibles (texto, tipo, si se puede pulsar o escribir, y coordenadas). Úsala antes de tocar o escribir. Gasta muchos menos tokens que una captura.",
    inputSchema: {},
    annotations: { readOnlyHint: true },
  },
  async () => {
    const [app, elementos] = await Promise.all([appEnPrimerPlano(), leerElementos()]);
    const lineas = elementos.map((e, i) => describir(e, i + 1));
    return texto(`App en primer plano: ${app}\n${lineas.length} elementos:\n${lineas.join("\n")}`);
  }
);

servidor.registerTool(
  "captura_pantalla",
  {
    title: "Captura de pantalla",
    description:
      "Devuelve una imagen de la pantalla. Úsala solo cuando la lista de elementos no basta (imágenes, mapas, juegos, contenido dibujado).",
    inputSchema: {},
    annotations: { readOnlyHint: true },
  },
  async () => {
    const png = await adb(["exec-out", "screencap -p"], true);
    return { content: [{ type: "image", data: png.toString("base64"), mimeType: "image/png" }] };
  }
);

servidor.registerTool(
  "tocar",
  {
    title: "Tocar",
    description:
      "Toca un elemento de la pantalla. Indica 'elemento' (número de la última lectura), o 'texto' (busca ese texto en la última lectura), o 'x' e 'y'. Con 'largo' mantiene pulsado.",
    inputSchema: {
      elemento: z.number().int().positive().optional(),
      texto: z.string().optional(),
      x: z.number().int().nonnegative().optional(),
      y: z.number().int().nonnegative().optional(),
      largo: z.boolean().optional(),
    },
  },
  async ({ elemento, texto: buscado, x, y, largo }) => {
    let destino;
    let nombre;
    if (elemento) {
      const e = ultimos[elemento - 1];
      if (!e) return texto(`No hay elemento ${elemento}. Vuelve a leer la pantalla.`);
      destino = e;
      nombre = e.texto || e.desc || e.id || e.tipo;
    } else if (buscado) {
      if (!ultimos.length) await leerElementos();
      const e = buscarPorTexto(buscado);
      if (!e) return texto(`No encuentro "${buscado}" en la pantalla. Vuelve a leerla.`);
      destino = e;
      nombre = e.texto || e.desc;
    } else if (x !== undefined && y !== undefined) {
      destino = { x, y };
      nombre = `${x},${y}`;
    } else {
      return texto("Indica 'elemento', 'texto' o 'x' e 'y'.");
    }
    await shell(
      largo
        ? `input swipe ${destino.x} ${destino.y} ${destino.x} ${destino.y} 800`
        : `input tap ${destino.x} ${destino.y}`
    );
    await espera(600);
    return texto(`${largo ? "Mantenido pulsado" : "Tocado"}: ${nombre}`);
  }
);

servidor.registerTool(
  "escribir",
  {
    title: "Escribir texto",
    description:
      "Escribe texto en el campo que tenga el foco (tócalo antes). Con 'enviar' pulsa Intro al final.",
    inputSchema: { texto: z.string(), enviar: z.boolean().optional() },
  },
  async ({ texto: t, enviar }) => {
    // `input text` no admite espacios literales: se codifican como %s.
    await shell(`input text ${q(t.replace(/%/g, "\\%").replace(/ /g, "%s"))}`);
    if (enviar) await shell("input keyevent KEYCODE_ENTER");
    await espera(400);
    return texto(`Escrito: ${t}${enviar ? " (y enviado)" : ""}`);
  }
);

const TECLAS = {
  atras: "KEYCODE_BACK",
  inicio: "KEYCODE_HOME",
  recientes: "KEYCODE_APP_SWITCH",
  intro: "KEYCODE_ENTER",
  borrar: "KEYCODE_DEL",
  tabulador: "KEYCODE_TAB",
  subir_volumen: "KEYCODE_VOLUME_UP",
  bajar_volumen: "KEYCODE_VOLUME_DOWN",
  bloquear: "KEYCODE_SLEEP",
  despertar: "KEYCODE_WAKEUP",
};

servidor.registerTool(
  "tecla",
  {
    title: "Pulsar tecla",
    description: "Pulsa una tecla del sistema: atrás, inicio, recientes, intro, borrar, volumen, bloquear o despertar.",
    inputSchema: { tecla: z.enum(Object.keys(TECLAS)) },
  },
  async ({ tecla }) => {
    await shell(`input keyevent ${TECLAS[tecla]}`);
    await espera(500);
    return texto(`Tecla pulsada: ${tecla}`);
  }
);

servidor.registerTool(
  "deslizar",
  {
    title: "Deslizar",
    description:
      "Desliza el dedo por la pantalla. 'abajo' muestra contenido que está más abajo (el dedo sube), 'arriba' lo contrario.",
    inputSchema: { direccion: z.enum(["arriba", "abajo", "izquierda", "derecha"]) },
  },
  async ({ direccion }) => {
    const { ancho, alto } = await tamanoPantalla();
    const cx = Math.round(ancho / 2), cy = Math.round(alto / 2);
    const dx = Math.round(ancho * 0.35), dy = Math.round(alto * 0.3);
    const gestos = {
      abajo: [cx, cy + dy, cx, cy - dy],
      arriba: [cx, cy - dy, cx, cy + dy],
      izquierda: [cx - dx, cy, cx + dx, cy],
      derecha: [cx + dx, cy, cx - dx, cy],
    };
    await shell(`input swipe ${gestos[direccion].join(" ")} 300`);
    await espera(600);
    return texto(`Deslizado hacia ${direccion}`);
  }
);

servidor.registerTool(
  "listar_apps",
  {
    title: "Listar apps",
    description: "Devuelve los paquetes de las apps que se pueden abrir.",
    inputSchema: {},
    annotations: { readOnlyHint: true },
  },
  async () => texto((await appsInstaladas()).join("\n"))
);

servidor.registerTool(
  "abrir_app",
  {
    title: "Abrir app",
    description:
      "Abre una app por nombre común (ajustes, chrome, cámara, contactos, mensajes, calendario, reloj, archivos…) o por su paquete.",
    inputSchema: { app: z.string() },
  },
  async ({ app }) => {
    const pkg = await resolverApp(app);
    if (!pkg) return texto(`No encuentro la app "${app}". Usa listar_apps para ver los paquetes.`);
    const actividad = (
      await shell(`cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${q(pkg)} | tail -1`)
    ).trim();
    await shell(actividad.includes("/") ? `am start -n ${q(actividad)}` : `monkey -p ${q(pkg)} 1`);
    await espera(1500);
    return texto(`Abierta: ${pkg}`);
  }
);

servidor.registerTool(
  "abrir_enlace",
  {
    title: "Abrir enlace",
    description: "Abre una URL (web, tel:, mailto:, geo:…) con la app que corresponda.",
    inputSchema: { url: z.string() },
  },
  async ({ url }) => {
    await shell(`am start -a android.intent.action.VIEW -d ${q(url)}`);
    await espera(1500);
    return texto(`Abierto: ${url}`);
  }
);

servidor.registerTool(
  "leer_notificaciones",
  {
    title: "Leer notificaciones",
    description: "Devuelve las notificaciones activas: app, título y texto.",
    inputSchema: {},
    annotations: { readOnlyHint: true },
  },
  async () => {
    const lista = await notificaciones();
    if (!lista.length) return texto("No hay notificaciones.");
    return texto(lista.map((n, i) => `[${i + 1}] ${n.app}: ${n.titulo}${n.texto ? " — " + n.texto : ""}`).join("\n"));
  }
);

await conectar();
await servidor.connect(new StdioServerTransport());
