// Acceso al propio móvil: ADB contra el mismo dispositivo (permisos de shell) y Termux:API.
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const run = promisify(execFile);
export const DISPOSITIVO = process.env.SIOS_ADB || "127.0.0.1:5555";

// Comillas simples de shell para pasar texto arbitrario.
export const q = (s) => `'${String(s).replace(/'/g, `'\\''`)}'`;

export const espera = (ms) => new Promise((r) => setTimeout(r, ms));

export async function conectar() {
  await run("adb", ["connect", DISPOSITIVO]).catch(() => {});
}

export async function adb(args, binario = false) {
  const opts = { maxBuffer: 64 * 1024 * 1024, encoding: binario ? "buffer" : "utf8" };
  try {
    return (await run("adb", ["-s", DISPOSITIVO, ...args], opts)).stdout;
  } catch {
    // La conexión se cae si Termux se reinicia o cambia la red: reconecta y reintenta una vez.
    await conectar();
    return (await run("adb", ["-s", DISPOSITIVO, ...args], opts)).stdout;
  }
}

// Comando de Android con permisos de shell (uid 2000).
export const android = (cmd) => adb(["shell", cmd]);

// Comando en el bash de Termux (Termux:API, utilidades, `android` como atajo a adb shell).
export async function termux(cmd, { timeout = 60000 } = {}) {
  try {
    const { stdout, stderr } = await run("bash", ["-lc", cmd], { timeout, maxBuffer: 16 * 1024 * 1024 });
    return { ok: true, salida: (stdout + (stderr ? `\n${stderr}` : "")).trim() };
  } catch (e) {
    const salida = `${e.stdout || ""}${e.stderr || ""}`.trim();
    return { ok: false, salida: salida || (e.killed ? "Tiempo agotado" : e.message) };
  }
}

export async function termuxJson(cmd) {
  const r = await termux(cmd, { timeout: 30000 });
  if (!r.ok) throw new Error(r.salida);
  try {
    return JSON.parse(r.salida || "null");
  } catch {
    return r.salida;
  }
}

// ---------- Pantalla ----------

const UI_XML = "/sdcard/claude-ui.xml";

// Elementos de la última lectura, para poder tocar por número.
export let ultimos = [];

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
  // uiautomator falla si la pantalla está animándose; otro intento suele bastar.
  for (let intento = 0; intento < 3; intento++) {
    const salida = await android(`uiautomator dump ${UI_XML} 2>&1; cat ${UI_XML}`);
    const i = salida.indexOf("<?xml");
    if (i >= 0) return salida.slice(i);
    await espera(500);
  }
  throw new Error("No se pudo leer la interfaz (uiautomator no respondió).");
}

export async function appEnPrimerPlano() {
  const s = await android("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -2");
  const m = s.match(/([a-zA-Z][\w.]+)\/([\w.$]+)/);
  return m ? m[1] : "desconocida";
}

export async function leerElementos() {
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

export function describirElemento(e, n) {
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

export function buscarPorTexto(texto) {
  const t = texto.toLowerCase();
  return (
    ultimos.find((e) => e.texto.toLowerCase() === t || e.desc.toLowerCase() === t) ||
    ultimos.find((e) => e.texto.toLowerCase().includes(t) || e.desc.toLowerCase().includes(t))
  );
}

export async function tamanoPantalla() {
  const s = await android("wm size");
  const m = s.match(/(\d+)x(\d+)\s*$/m);
  return m ? { ancho: +m[1], alto: +m[2] } : { ancho: 1080, alto: 2400 };
}

// `input text` solo admite ASCII y se lía con algunos símbolos: el resto se pega desde el portapapeles.
export async function escribirTexto(t) {
  if (/^[A-Za-z0-9 .,@_\-]*$/.test(t)) {
    await android(`input text ${q(t.replace(/ /g, "%s"))}`);
    return;
  }
  const r = await termux(`termux-clipboard-set ${q(t)}`, { timeout: 15000 });
  if (!r.ok) throw new Error(`No se pudo usar el portapapeles: ${r.salida}`);
  await espera(200);
  await android("input keyevent KEYCODE_PASTE");
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

export const sinAcentos = (s) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase();

// ---------- Puente con la app Claude ----------

/**
 * Llama al puente de la app Claude (content://com.sios.puente, sistema/Puente.kt): lo que
 * solo puede hacer una app, como el Bluetooth o los nombres visibles de las apps.
 */
export async function puente(metodo, extras = {}) {
  // Valores codificados como en una URL: `content call` no admite «:» dentro de un valor.
  const args = Object.entries(extras).map(([k, v]) => `--extra ${k}:s:${q(encodeURIComponent(v))}`).join(" ");
  const salida = await android(`content call --uri content://com.sios.puente --method ${metodo} ${args}`);
  const m = salida.match(/json=(.*)\}\]\s*$/s);
  if (!m) throw new Error(salida.trim() || "El puente de Claude no respondió");
  const r = JSON.parse(m[1]);
  if (r.error) throw new Error(r.error);
  return r;
}

// ---------- Manos de Claude ----------
// Servicio de accesibilidad de la app Claude (sistema/Manos.kt): lee y toca mucho más rápido que
// uiautomator, escribe con tildes y, mientras tanto, ilumina el borde y dibuja cada toque. Si está
// desactivado, las herramientas vuelven a uiautomator y `input`.

let manosEnUso = false;

async function manos(metodo, extras = {}) {
  const r = await puente(`manos_${metodo}`, extras);
  manosEnUso = true;
  return r;
}

/** Fin del turno: se apaga el halo y la hoja del asistente vuelve con la respuesta. */
export async function manosFin() {
  if (!manosEnUso) return;
  manosEnUso = false;
  await puente("manos_fin").catch(() => {});
}

/** Esconde el halo un momento, para que no salga en una captura. */
export async function manosOcultar(ms = 900) {
  if (manosEnUso) await puente("manos_ocultar", { ms }).catch(() => {});
}

/** App en primer plano y elementos visibles (numerables con describirElemento). */
export async function leerPantalla() {
  try {
    const r = await manos("leer");
    ultimos = r.elementos;
    return { app: r.app, elementos: r.elementos };
  } catch {
    const [app, elementos] = await Promise.all([appEnPrimerPlano(), leerElementos()]);
    return { app, elementos };
  }
}

export async function tocarEn(x, y, largo = false) {
  try {
    const r = await manos("tocar", { x, y, largo: largo ? "1" : "0" });
    if (r.hecho) return;
  } catch {}
  await android(largo ? `input swipe ${x} ${y} ${x} ${y} 800` : `input tap ${x} ${y}`);
}

export async function deslizarDe(x1, y1, x2, y2, ms = 320) {
  try {
    const r = await manos("deslizar", { x1, y1, x2, y2, ms });
    if (r.hecho) return;
  } catch {}
  await android(`input swipe ${x1} ${y1} ${x2} ${y2} ${ms}`);
}

/** Escribe donde esté el cursor del campo con el foco; con enviar, pulsa Intro. */
export async function escribirEnCampo(t, enviar = false) {
  try {
    const r = await manos("escribir", { texto: t, enviar: enviar ? "1" : "0" });
    if (enviar && !r.intro) await android("input keyevent KEYCODE_ENTER");
    return;
  } catch (e) {
    if (/tócalo antes|no deja escribir/.test(e.message)) throw e;
  }
  await escribirTexto(t);
  if (enviar) await android("input keyevent KEYCODE_ENTER");
}

// ---------- Apps ----------

/** Apps que se pueden abrir: [{ nombre, paquete }]. Sin la app Claude, solo los paquetes. */
export async function appsConNombre() {
  try {
    return (await puente("apps")).apps;
  } catch {
    const s = await android("cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER");
    return [...new Set([...s.matchAll(/^\s*([\w.]+)\/[\w.$]+/gm)].map((m) => m[1]))].sort().map((p) => ({ nombre: p, paquete: p }));
  }
}

export async function appsInstaladas() {
  return (await appsConNombre()).map((a) => a.paquete);
}

/** Paquete de una app por su nombre visible («WhatsApp»), un alias («cámara») o el propio paquete. */
export async function resolverApp(nombre) {
  const n = sinAcentos(nombre.trim());
  const apps = await appsConNombre();
  const paquetes = apps.map((a) => a.paquete);
  if (paquetes.includes(nombre.trim())) return nombre.trim();
  if (ALIAS[n] && paquetes.includes(ALIAS[n])) return ALIAS[n];
  const porNombre = (f) => apps.find((a) => f(sinAcentos(a.nombre)))?.paquete;
  return (
    porNombre((x) => x === n) ||
    porNombre((x) => x.startsWith(n)) ||
    porNombre((x) => x.includes(n)) ||
    paquetes.find((p) => p.toLowerCase().includes(n.replace(/\s+/g, ""))) ||
    null
  );
}

/** Busca apps libres en F-Droid: [{ nombre, paquete, resumen }]. */
export async function buscarEnFdroid(consulta) {
  const r = await fetch(`https://search.f-droid.org/api/search_apps?q=${encodeURIComponent(consulta)}`, { signal: AbortSignal.timeout(15000) });
  if (!r.ok) throw new Error(`F-Droid respondió ${r.status}`);
  const { apps = [] } = await r.json();
  return apps.slice(0, 8).map((a) => ({ nombre: a.name, paquete: a.url.split("/packages/")[1]?.replace(/\/$/, ""), resumen: a.summary }));
}

/** Descarga de F-Droid la versión recomendada de un paquete y la instala. Devuelve la versión. */
export async function instalarDeFdroid(paquete) {
  const info = await fetch(`https://f-droid.org/api/v1/packages/${encodeURIComponent(paquete)}`, { signal: AbortSignal.timeout(15000) });
  if (!info.ok) throw new Error(`${paquete} no está en F-Droid`);
  const { suggestedVersionCode: codigo, packages = [] } = await info.json();
  const version = packages.find((p) => p.versionCode === codigo)?.versionName || String(codigo);
  const apk = `${process.env.TMPDIR || "/data/data/com.termux/files/usr/tmp"}/${paquete}.apk`;
  const descarga = await termux(`curl -fsSL --retry 2 -o ${q(apk)} ${q(`https://f-droid.org/repo/${paquete}_${codigo}.apk`)}`, { timeout: 300000 });
  if (!descarga.ok) throw new Error(`No se pudo descargar: ${descarga.salida}`);
  try {
    // adb install lee el APK desde Termux; la shell de Android no podría leerlo de la carpeta de Termux.
    const salida = await adb(["install", "-r", apk]);
    if (!/Success/.test(salida)) throw new Error(salida.trim());
  } finally {
    await termux(`rm -f ${q(apk)}`);
  }
  return version;
}

// ---------- Datos del sistema ----------

export async function notificaciones() {
  const s = await android("dumpsys notification --noredact");
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

// `content query` devuelve líneas "Row: N clave=valor, clave=valor"; las claves vienen de la proyección.
export async function consultaContenido(uri, columnas, { where, orden } = {}) {
  let cmd = `content query --uri ${uri} --projection ${columnas.join(":")}`;
  if (where) cmd += ` --where ${q(where)}`;
  if (orden) cmd += ` --sort ${q(orden)}`;
  const s = await android(cmd);
  if (/No result found/.test(s)) return [];
  const filas = [];
  for (const linea of s.split("\n")) {
    if (!linea.startsWith("Row:")) continue;
    const fila = {};
    // Corta por ", clave=" sabiendo qué claves esperar, para no romper valores con comas.
    const patron = new RegExp(`(?:^Row: \\d+ |, )(${columnas.join("|")})=`, "g");
    const marcas = [...linea.matchAll(patron)];
    marcas.forEach((m, i) => {
      const inicio = m.index + m[0].length;
      const fin = i + 1 < marcas.length ? marcas[i + 1].index : linea.length;
      fila[m[1]] = linea.slice(inicio, fin);
    });
    filas.push(fila);
  }
  return filas;
}
