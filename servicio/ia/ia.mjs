// La IA del sistema: cuál está elegida, con qué modelo, si hay sesión o clave, la instalación la
// primera vez, el inicio de sesión (también desde el ordenador) y el modelo local (llama.cpp).
//
// Claude sigue yendo por Claude Code y cuenta.mjs; el resto, por ia/motor.mjs.
import { spawn, spawnSync } from "node:child_process";
import { existsSync, readFileSync, readdirSync, renameSync, rmSync, statSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { MODELOS_LOCALES, PROVEEDORES, modeloLocalPara, publico } from "./catalogo.mjs";
import { clave, ponerClave as guardarClave, resumenClaves, rutaDe } from "./claves.mjs";

export { PROVEEDORES } from "./catalogo.mjs";
export const MOTOR = fileURLToPath(new URL("./motor.mjs", import.meta.url));
const PREFIX = process.env.PREFIX || "/data/data/com.termux/files/usr";
const INSTALAR = fileURLToPath(new URL("./instalar.sh", import.meta.url));
const DIR = join(homedir(), "sios", "ia");
const ARCHIVO = join(DIR, "ajustes.json");
const MODELOS_DIR = join(DIR, "modelos");
const PUERTO_LOCAL = 8181;

/** El nombre del sistema operativo, en un solo sitio (pendiente de decidir el definitivo). */
export const SISTEMA = process.env.NOMBRE_SISTEMA || "SIOS";

const leer = () => {
  try {
    return JSON.parse(readFileSync(ARCHIVO, "utf8"));
  } catch {
    return {};
  }
};
// Sin «proveedor» hasta que el usuario elige su IA (ver elegida()).
let ajustes = { proveedor: null, modelos: {}, ...leer() };
const guardar = () => {
  writeFileSync(ARCHIVO + ".tmp", JSON.stringify(ajustes));
  renameSync(ARCHIVO + ".tmp", ARCHIVO);
};

const oyentes = new Set();
export const alCambiar = (fn) => oyentes.add(fn);
const avisar = () => estado().then((e) => oyentes.forEach((fn) => fn(e))).catch(() => {});

// Lo que pasa ahora mismo (instalando, entrando, descargando): se enseña tal cual en la app.
const tareas = {};
function tarea(id, cambios) {
  if (cambios === null) delete tareas[id];
  else tareas[id] = { ...(tareas[id] || {}), ...cambios };
  avisar();
}

// ---------- Mensajes ----------

/**
 * Error con clave: la app lo enseña en el idioma del móvil (la clave y sus datos); el texto, en
 * español, queda de reserva para quien no conozca la clave (la app del ordenador, el registro).
 */
export class ErrorIa extends Error {
  constructor(clave, texto, datos = {}) {
    super(texto);
    this.clave = clave;
    this.datos = datos;
  }
}

// Pasos del instalador (líneas «@clave» de instalar.sh) y su texto de reserva.
const PASOS = {
  preparando: "Preparando…", debian: "Instalando Debian…", claude: "Instalando Claude Code…", codex: "Descargando Codex de OpenAI…",
  gemini: "Instalando Gemini CLI…", kimi: "Instalando Kimi Code…", llama: "Instalando llama.cpp…", comprobando_codigo: "Comprobando el código…",
};
const paso = (clave) => ({ paso: PASOS[clave] || clave, paso_clave: clave });
const fallo = (clave, texto, datos = {}) => ({ error: texto, error_clave: clave, datos });

// ---------- Elegida ----------

export const activo = () => (PROVEEDORES[ajustes.proveedor] ? ajustes.proveedor : "claude");
/**
 * ¿Ha elegido ya el usuario su IA? Mientras no, el sistema se presenta como SIOS (su símbolo, su
 * nombre y su paleta en blanco y negro), no como ninguna IA. Quien ya había entrado con Claude
 * antes de poder elegir cuenta como elegida.
 */
export const elegida = () => !!PROVEEDORES[ajustes.proveedor] || leerCuentaClaude()?.conectada === true;
/** La identidad que se ve en el sistema: la de la IA elegida o, sin elegir, la de SIOS. */
export const identidad = () =>
  elegida()
    ? (({ id, acento, logo, tipo }) => ({ id, nombre: nombreAsistente(), acento, logo, tipo }))(proveedor())
    : { id: "sios", nombre: SISTEMA, acento: ["#F4F1EA", "#141413"], logo: null, tipo: null };
export const proveedor = () => ({ id: activo(), ...PROVEEDORES[activo()] });
export function modelo(id = activo()) {
  if (id === "local") return ajustes.local || null;
  return ajustes.modelos[id] || "auto";
}

/**
 * Se ha entrado (o salido) en la cuenta de una IA. Si aún no había ninguna elegida, la que acaba de
 * quedar lista se elige sola; en cualquier caso se avisa a la app, que cambia nombre, logo y colores.
 */
export function sesionCambiada(id, conectada) {
  if (conectada && !PROVEEDORES[ajustes.proveedor] && PROVEEDORES[id]) elegir(id);
  else avisar();
}

export function elegir(id) {
  if (!PROVEEDORES[id]) throw new Error("IA desconocida");
  ajustes.proveedor = id;
  guardar();
  avisar();
}

export function ponerModelo(id, m) {
  if (!PROVEEDORES[id]) throw new Error("IA desconocida");
  if (id === "local") {
    const mod = MODELOS_LOCALES.find((x) => x.id === m);
    if (!mod) throw new Error("Modelo local desconocido");
    if (ramGb() < mod.ram) throw new ErrorIa("ram", `Tu móvil no tiene RAM suficiente para ${mod.nombre} (necesita ${mod.ram} GB).`, { modelo: mod.nombre, gb: mod.ram });
    if (!descargado(m)) throw new ErrorIa("descarga_primero", `Descarga ${mod.nombre} primero en Ajustes › Inteligencia.`, { modelo: mod.nombre });
    ajustes.local = m;
    pararLocal(); // con otro modelo hay que volver a cargar
  } else ajustes.modelos[id] = String(m).slice(0, 100);
  guardar();
  avisar();
}

/** Nombre con el que se presenta el asistente (Claude, ChatGPT, Gemini, DeepSeek… o el que el usuario le ponga al local). */
export const nombreLocal = () => ajustes.nombreLocal || "Qwen";
export const nombreAsistente = () => (activo() === "local" ? nombreLocal() : PROVEEDORES[activo()].nombre);

export function ponerNombreLocal(nombre) {
  const n = String(nombre || "").replace(/[^\p{L}\p{N} .'-]/gu, "").trim().slice(0, 24);
  if (n) ajustes.nombreLocal = n;
  else delete ajustes.nombreLocal;
  guardar();
  avisar();
}

// ---------- Instalación bajo demanda ----------

const NECESITA = { claude: "claude", codex: "codex", gemini: "gemini", kimi: "kimi", local: "llama" };
const BINARIO = { claude: "ia-claude", codex: "ia-codex", gemini: "ia-gemini", kimi: "ia-kimi", llama: "llama-server" };
// Claude Code instalado por la preparación de antes (cuando venía con el sistema), dentro de Debian.
const CLAUDE_DEBIAN = join(PREFIX, "var/lib/proot-distro/installed-rootfs/debian/root/.local/bin/claude");
const instalado = (id) => {
  const que = NECESITA[PROVEEDORES[id]?.motor] || NECESITA[id];
  return !que || existsSync(join(PREFIX, "bin", BINARIO[que])) || (que === "claude" && existsSync(CLAUDE_DEBIAN));
};

/** Instala lo que necesite la IA si aún no está (true si queda lista para entrar). */
export const asegurar = async (id) => instalado(id) || (await instalar(id));

export function instalar(id) {
  const que = NECESITA[PROVEEDORES[id]?.motor] || NECESITA[id];
  if (!que || tareas[id]?.instalando) return Promise.resolve(true);
  tarea(id, { instalando: true, ...paso("preparando"), error: null, error_clave: null });
  return new Promise((listo) => {
    const p = spawn("bash", [INSTALAR, que], { stdio: ["ignore", "pipe", "pipe"] });
    let ultimo = "";
    const leerLinea = (d) => {
      const l = String(d).trim().split("\n").filter(Boolean).pop();
      if (l) {
        ultimo = l;
        if (l.startsWith("@")) tarea(id, paso(l.slice(1).trim()));
        else if (!/^listo$/.test(l)) tarea(id, { paso: l.slice(0, 120), paso_clave: null });
      }
    };
    p.stdout.on("data", leerLinea);
    p.stderr.on("data", (d) => (ultimo = String(d).trim().slice(-200) || ultimo));
    p.on("close", (codigo) => {
      if (codigo === 0) {
        tarea(id, null);
        listo(true);
      } else {
        console.error(`No se pudo instalar ${id}: ${ultimo}`); // el detalle técnico, al registro
        tarea(id, { instalando: false, ...fallo("instalar", `No se pudo instalar: ${ultimo}`, { detalle: ultimo }) });
        listo(false);
      }
    });
  });
}

// ---------- Sesión: ChatGPT (Codex) y Google (Gemini) ----------

const CREDENCIALES_GEMINI = () => join(rutaDe("gemini"), ".gemini", "oauth_creds.json");
let cacheCodex = { t: 0, conectada: null };

function codexConectado() {
  if (!instalado("chatgpt")) return Promise.resolve(false);
  if (Date.now() - cacheCodex.t < 60_000) return Promise.resolve(cacheCodex.conectada);
  return new Promise((listo) => {
    const p = spawn("ia-codex", ["login", "status"], { stdio: ["ignore", "pipe", "pipe"] });
    let s = "";
    p.stdout.on("data", (d) => (s += d));
    p.stderr.on("data", (d) => (s += d));
    p.on("close", () => {
      cacheCodex = { t: Date.now(), conectada: /logged in/i.test(s) && !/not logged in/i.test(s) };
      listo(cacheCodex.conectada);
    });
    p.on("error", () => listo(false));
  });
}

/** Kimi Code guarda su sesión en ~/.kimi (dentro de su carpeta): hay credenciales OAuth o modelos de la suscripción. */
function kimiConectado() {
  const dir = join(rutaDe("kimi"), ".kimi");
  try {
    if (readdirSync(dir).some((f) => /oauth|credential|token|auth/i.test(f))) return true;
    return /kimi-for-coding|oauth/i.test(readFileSync(join(dir, "config.toml"), "utf8"));
  } catch {
    return false;
  }
}

function cuentaGemini() {
  try {
    return JSON.parse(readFileSync(join(rutaDe("gemini"), ".gemini", "google_accounts.json"), "utf8")).active || null;
  } catch {
    return null;
  }
}

let entrando = null; // { id, proceso }

/** Empieza el inicio de sesión. Devuelve enseguida; el enlace (y el código) llegan por estado. */
export async function entrar(id) {
  const p = PROVEEDORES[id];
  if (!p || !["codex", "gemini", "kimi"].includes(p.motor)) throw new Error("Esta IA no inicia sesión así");
  if (!instalado(id) && !(await instalar(id))) return;
  cancelar();
  tarea(id, { entrando: true, enlace: null, codigo: null, pideCodigo: false, error: null });
  let salida = "";
  const limpiar = (s) => s.replace(/\x1b\[[0-9;?]*[a-zA-Z]/g, "").replace(/\r/g, "");
  let proc;
  if (p.motor === "kimi") {
    // Kimi Code: código de dispositivo de su cuenta (como ChatGPT).
    proc = spawn("ia-kimi", ["login"], { stdio: ["ignore", "pipe", "pipe"], detached: true, env: { ...process.env, BROWSER: "true" } });
    const leerSalida = (d) => {
      salida += limpiar(String(d));
      const enlace = salida.match(/https:\/\/[^\s"']*(kimi|moonshot)[^\s"']*/)?.[0];
      const codigo = salida.match(/\b([A-Z0-9]{4}-[A-Z0-9]{4})\b/)?.[1];
      if (enlace && !tareas[id]?.enlace) tarea(id, { enlace, ...(codigo ? { codigo } : {}) });
      else if (codigo && !tareas[id]?.codigo) tarea(id, { codigo });
    };
    proc.stdout.on("data", leerSalida);
    proc.stderr.on("data", leerSalida);
  } else if (p.motor === "codex") {
    // Código de dispositivo: se abre auth.openai.com/codex/device (en el móvil o en el ordenador) y se escribe el código.
    proc = spawn("ia-codex", ["login", "--device-auth"], { stdio: ["ignore", "pipe", "pipe"], detached: true });
    const leerSalida = (d) => {
      salida += limpiar(String(d));
      const enlace = salida.match(/https:\/\/auth\.openai\.com\/\S+/)?.[0];
      const codigo = salida.match(/\b([A-Z0-9]{4}-[A-Z0-9]{4,6})\b/)?.[1];
      if (enlace && codigo && !tareas[id]?.codigo) tarea(id, { enlace, codigo });
    };
    proc.stdout.on("data", leerSalida);
    proc.stderr.on("data", leerSalida);
  } else {
    // Gemini CLI solo pregunta el código en una terminal: se le da una con `script`.
    escribirAjustesGemini("");
    proc = spawn("script", ["-q", "-c", "ia-gemini", "/dev/null"], { stdio: ["pipe", "pipe", "pipe"], detached: true });
    const leerSalida = (d) => {
      salida += limpiar(String(d));
      const enlace = salida.match(/https:\/\/accounts\.google\.com\/o\/oauth2\/\S+/)?.[0];
      if (enlace && !tareas[id]?.enlace) tarea(id, { enlace, pideCodigo: true });
      // Con la sesión hecha arranca su interfaz: ya no hace falta.
      if (existsSync(CREDENCIALES_GEMINI())) terminar(true);
    };
    proc.stdout.on("data", leerSalida);
    proc.stderr.on("data", leerSalida);
  }
  entrando = { id, proceso: proc };
  const plazo = setTimeout(() => terminar(false, "tiempo_entrar"), 15 * 60_000);
  let hecho = false;
  function terminar(ok, error) {
    if (hecho) return;
    hecho = true;
    clearTimeout(plazo);
    try {
      process.kill(-proc.pid, "SIGTERM");
    } catch {}
    if (entrando?.proceso === proc) entrando = null;
    cacheCodex.t = 0;
    const TEXTOS = { tiempo_entrar: "Ha pasado el tiempo para entrar. Vuelve a intentarlo.", sesion_incompleta: "No se ha completado el inicio de sesión." };
    tarea(id, ok ? null : { entrando: false, ...(error ? fallo(error, TEXTOS[error] || error) : { error: null, error_clave: null }) });
    if (ok) sesionCambiada(id, true);
  }
  proc.on("close", (codigo) => {
    if (p.motor === "kimi") terminar(codigo === 0 && kimiConectado(), "sesion_incompleta");
    else if (p.motor === "codex") terminar(codigo === 0, codigo === 0 ? null : "sesion_incompleta");
    else terminar(existsSync(CREDENCIALES_GEMINI()), "sesion_incompleta");
  });
}

/** Gemini: el código que da Google al terminar en el navegador. */
export function codigo(id, valor) {
  if (!entrando || entrando.id !== id || !entrando.proceso.stdin) return false;
  entrando.proceso.stdin.write(String(valor).trim() + "\r");
  tarea(id, { pideCodigo: false, ...paso("comprobando_codigo") });
  return true;
}

export function cancelar() {
  if (!entrando) return;
  const { id, proceso } = entrando;
  entrando = null;
  try {
    process.kill(-proceso.pid, "SIGTERM");
  } catch {}
  tarea(id, null);
}

export async function salir(id) {
  const p = PROVEEDORES[id];
  if (p?.motor === "codex") await new Promise((l) => spawn("ia-codex", ["logout"], { stdio: "ignore" }).on("close", l).on("error", l));
  else if (p?.motor === "gemini") rmSync(CREDENCIALES_GEMINI(), { force: true });
  else if (p?.motor === "kimi") await new Promise((l) => spawn("ia-kimi", ["logout"], { stdio: "ignore" }).on("close", l).on("error", l));
  else if (p?.tipo === "clave") guardarClave(id, null);
  cacheCodex.t = 0;
  avisar();
}

// ---------- Claves de API ----------

const NO_CHAT = /embed|whisper|tts|dall-e|moderation|image|audio|transcribe|realtime|search|similarity|rerank|ocr|vision-preview|babbage|davinci/i;

async function pedirModelos(id, key) {
  const r = await fetch(`${PROVEEDORES[id].base}/models`, { headers: { authorization: `Bearer ${key}` }, signal: AbortSignal.timeout(15000) });
  if (r.status === 401 || r.status === 403) throw new ErrorIa("clave_invalida", "La clave no es válida.");
  if (!r.ok) throw new ErrorIa("respondio", `${PROVEEDORES[id].nombre} respondió ${r.status}.`, { nombre: PROVEEDORES[id].nombre, estado: r.status });
  const datos = await r.json();
  return (datos.data || datos.models || [])
    .map((m) => String(m.id || m.name || "").replace(/^models\//, ""))
    .filter((m) => m && !NO_CHAT.test(m))
    .sort();
}

/** Guarda la clave solo si funciona (se comprueba listando sus modelos, que no cuesta nada). */
export async function ponerClave(id, valor) {
  if (PROVEEDORES[id]?.tipo !== "clave") throw new Error("Esta IA no usa clave");
  const v = String(valor || "").trim();
  if (!v) {
    guardarClave(id, null);
    avisar();
    return { ok: true };
  }
  const modelos = await pedirModelos(id, v);
  guardarClave(id, v);
  cacheModelos[id] = { t: Date.now(), lista: modelos };
  if (!ajustes.modelos[id] || ajustes.modelos[id] === "auto") {
    ajustes.modelos[id] = elegirPreferido(id, modelos, "normal") || "auto";
    guardar();
  }
  avisar();
  return { ok: true, modelos: modelos.length };
}

function elegirPreferido(id, lista, tipo) {
  for (const re of PROVEEDORES[id].preferidos?.[tipo] || []) {
    const m = lista.find((x) => re.test(x));
    if (m) return m;
  }
  return lista[0] || null;
}

const cacheModelos = {};
/** Modelos para elegir. «auto»: lo que el propio servicio considere mejor ahora. */
export async function modelos(id) {
  const p = PROVEEDORES[id];
  if (!p) return [];
  // En local, solo los que están descargados y caben en la RAM de este móvil.
  if (id === "local") return MODELOS_LOCALES.filter((m) => descargado(m.id) && ramGb() >= m.ram).map((m) => ({ id: m.id, nombre: m.nombre, nota: m.nota }));
  if (p.motor === "codex") return [{ id: "auto", nombre: "El recomendado por OpenAI" }, { id: "gpt-5.5", nombre: "GPT-5.5" }, { id: "gpt-5.5-mini", nombre: "GPT-5.5 mini (más rápido)" }];
  if (p.motor === "kimi") return [{ id: "auto", nombre: "El de tu plan (Kimi para programar)" }];
  if (p.motor === "gemini") return [{ id: "auto", nombre: "Automático (Pro o Flash según la tarea)" }, { id: "gemini-3-pro-preview", nombre: "Gemini 3 Pro" }, { id: "gemini-2.5-pro", nombre: "Gemini 2.5 Pro" }, { id: "gemini-2.5-flash", nombre: "Gemini 2.5 Flash (más rápido)" }];
  if (p.tipo !== "clave") return [];
  const key = clave(id);
  if (!key) return [];
  if (!cacheModelos[id] || Date.now() - cacheModelos[id].t > 24 * 3600_000) {
    try {
      cacheModelos[id] = { t: Date.now(), lista: await pedirModelos(id, key) };
    } catch {
      return [];
    }
  }
  return cacheModelos[id].lista.map((m) => ({ id: m, nombre: m }));
}

/** Para el motor: el modelo concreto (en las API «auto» se resuelve al preferido). */
export async function modeloParaUsar(id = activo(), tipo = "normal") {
  const p = PROVEEDORES[id];
  if (id === "local") return ajustes.local;
  const elegido = ajustes.modelos[id];
  if (p.tipo === "clave" && (!elegido || elegido === "auto" || tipo === "rapido")) {
    const lista = (await modelos(id)).map((m) => m.id);
    return elegirPreferido(id, lista, tipo) || elegido || "auto";
  }
  return elegido || "auto";
}

// ---------- Modelo local (llama.cpp) ----------

export function ramGb() {
  try {
    const kb = Number(readFileSync("/proc/meminfo", "utf8").match(/MemTotal:\s+(\d+)/)[1]);
    return Math.round(kb / 1024 / 1024 + 0.4); // un «6 GB» de fábrica marca unos 5,7
  } catch {
    return 0;
  }
}

const archivoLocal = (m) => join(MODELOS_DIR, `${m}.gguf`);
const descargado = (m) => existsSync(archivoLocal(m));

export function descargarLocal(m) {
  const mod = MODELOS_LOCALES.find((x) => x.id === m);
  if (!mod) throw new Error("Modelo local desconocido");
  if (descargado(m) || tareas[`local:${m}`]) return;
  if (!instalado("local")) instalar("local");
  const parte = archivoLocal(m) + ".parte";
  rutaDe("modelos");
  tarea(`local:${m}`, { descargando: true, progreso: 0, error: null });
  const p = spawn("curl", ["-fL", "-C", "-", "-s", "-o", parte, mod.url], { stdio: "ignore", detached: true });
  const total = mod.tamano * 1024 ** 3;
  const reloj = setInterval(() => {
    try {
      tarea(`local:${m}`, { progreso: Math.min(0.99, statSync(parte).size / total) });
    } catch {}
  }, 2000);
  p.on("close", (codigo) => {
    clearInterval(reloj);
    if (codigo === 0) {
      renameSync(parte, archivoLocal(m));
      if (!ajustes.local) {
        ajustes.local = m;
        guardar();
      }
      tarea(`local:${m}`, null);
    } else tarea(`local:${m}`, { descargando: false, ...fallo("descarga_cortada", "La descarga se ha cortado. Vuelve a intentarlo: sigue donde se quedó.") });
  });
}

export function borrarLocal(m) {
  if (ajustes.local === m) pararLocal();
  rmSync(archivoLocal(m), { force: true });
  rmSync(archivoLocal(m) + ".parte", { force: true });
  if (ajustes.local === m) {
    ajustes.local = MODELOS_LOCALES.find((x) => descargado(x.id))?.id || null;
    guardar();
  }
  avisar();
}

let servidorLocal = null; // { proceso, modelo }
let relojLocal = null;

const vivo = () => fetch(`http://127.0.0.1:${PUERTO_LOCAL}/health`, { signal: AbortSignal.timeout(1500) }).then((r) => r.ok).catch(() => false);

/** Arranca llama-server con el modelo elegido si no está ya (y lo para tras 15 min sin uso). */
export async function asegurarLocal() {
  const m = ajustes.local;
  if (!m || !descargado(m)) throw new ErrorIa("descarga_modelo", "Descarga primero un modelo local en Ajustes › Inteligencia.");
  clearTimeout(relojLocal);
  relojLocal = setTimeout(pararLocal, 30 * 60_000);
  if (servidorLocal?.modelo === m && (await vivo())) return;
  pararLocal();
  // Uno que quedó de un arranque anterior del servicio (con otro modelo u otros ajustes): fuera.
  if (await vivo()) {
    spawnSync("pkill", ["-f", "llama-server"]);
    for (let i = 0; i < 20 && (await vivo()); i++) await new Promise((r) => setTimeout(r, 250));
  }
  // Todos los núcleos (en un móvil de gama alta, los grandes): leer el mensaje es lo que más tarda.
  // En Android, os.cpus() viene vacío: availableParallelism sí dice cuántos núcleos hay.
  const os = await import("node:os");
  const hilos = Math.max(2, Math.min(8, os.availableParallelism?.() || os.cpus().length || 4));
  const proceso = spawn("llama-server", [
    "-m", archivoLocal(m), "--host", "127.0.0.1", "--port", String(PUERTO_LOCAL),
    "--jinja", "-c", "8192", "-t", String(hilos), "-tb", String(hilos), "--reasoning-budget", "0", "--no-webui", "--cache-reuse", "256",
  ], { stdio: "ignore", detached: true });
  servidorLocal = { proceso, modelo: m };
  proceso.on("close", () => {
    if (servidorLocal?.proceso === proceso) servidorLocal = null;
    avisar();
  });
  for (let i = 0; i < 120; i++) {
    if (await vivo()) {
      avisar();
      return;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new ErrorIa("local_no_arranca", "El modelo local no ha arrancado.");
}

export function pararLocal() {
  clearTimeout(relojLocal);
  if (!servidorLocal) return;
  try {
    process.kill(-servidorLocal.proceso.pid, "SIGTERM");
  } catch {}
  servidorLocal = null;
}

// ---------- Estado para la app y el ordenador (sin claves) ----------

// La cuenta de Claude la lleva cuenta.mjs: el servicio dice cómo leerla (también para los avisos).
let leerCuentaClaude = () => null;
export const usarCuentaClaude = (fn) => (leerCuentaClaude = fn);

export async function estado({ cuentaClaude = leerCuentaClaude() } = {}) {
  const claves = resumenClaves();
  const [codex] = await Promise.all([codexConectado()]);
  const ram = ramGb();
  const lista = Object.keys(PROVEEDORES).map((id) => {
    const p = PROVEEDORES[id];
    let listo = false;
    let cuenta = null;
    if (p.motor === "claude") {
      listo = cuentaClaude?.conectada === true;
      cuenta = cuentaClaude?.email || null;
    } else if (p.motor === "codex") listo = codex;
    else if (p.motor === "kimi") listo = instalado(id) && kimiConectado();
    else if (p.motor === "gemini") {
      listo = existsSync(CREDENCIALES_GEMINI());
      cuenta = listo ? cuentaGemini() : null;
    } else if (p.tipo === "clave") listo = !!claves[id];
    else if (p.tipo === "local") listo = !!ajustes.local && descargado(ajustes.local) && instalado("local");
    return {
      ...publico(id),
      ...(id === "local" ? { nombre: nombreLocal() } : {}),
      listo, cuenta, instalado: instalado(id), modelo: modelo(id),
      ...(p.tipo === "clave" && claves[id] ? { clave: `…${claves[id].final}` } : {}),
      ...(tareas[id] ? { tarea: tareas[id] } : {}),
    };
  });
  const recomendado = modeloLocalPara(ram);
  return {
    activo: activo(),
    elegida: elegida(),
    identidad: identidad(),
    sistema: SISTEMA,
    proveedores: lista,
    local: {
      ram,
      recomendado: recomendado?.id || null,
      enMarcha: !!servidorLocal,
      modelos: MODELOS_LOCALES.map((m) => ({
        ...m, cabe: ram >= m.ram, descargado: descargado(m.id), elegido: ajustes.local === m.id,
        ...(tareas[`local:${m.id}`] ? { tarea: tareas[`local:${m.id}`] } : {}),
      })),
    },
  };
}

// ---------- Kimi Code: su agente ----------

/**
 * Agente de Kimi Code con la lista cerrada de herramientas: solo su buscador web (las del móvil
 * llegan por MCP y piden permiso en el servicio). Sin terminal ni edición de archivos: en modo sin
 * pantalla Kimi aprueba solo sus herramientas, y la terminal se saltaría los permisos del usuario.
 */
export function escribirAgenteKimi(sistema, puerto = 8765) {
  const dir = rutaDe("kimi");
  writeFileSync(join(dir, "sistema.md"), sistema.replace(/\$\{/g, "$ {"));
  writeFileSync(join(dir, "agente.yaml"), [
    "version: 1",
    "agent:",
    "  name: sios",
    "  system_prompt_path: ./sistema.md",
    "  tools:",
    '    - "kimi_cli.tools.web:SearchWeb"',
    '    - "kimi_cli.tools.web:FetchURL"',
    "",
  ].join("\n"));
  writeFileSync(join(dir, "mcp.json"), JSON.stringify({ mcpServers: { movil: { url: `http://127.0.0.1:${puerto}/mcp/vigilado`, transport: "http" } } }));
  return { agente: join(dir, "agente.yaml"), mcp: join(dir, "mcp.json") };
}

// ---------- Gemini: sus ajustes ----------

/** settings.json de Gemini CLI: solo su buscador y el servidor del móvil (que pide permiso él). */
export function escribirAjustesGemini(sistema, puerto = 8765) {
  const base = rutaDe("gemini");
  const dir = join(base, ".gemini");
  if (!existsSync(dir)) rutaDe(join("gemini", ".gemini"));
  writeFileSync(join(dir, "settings.json"), JSON.stringify({
    security: { auth: { selectedType: "oauth-personal" } },
    general: { disableAutoUpdate: true, disableUpdateNag: true },
    privacy: { usageStatisticsEnabled: false },
    ui: { hideTips: true, hideBanner: true },
    tools: { core: ["google_web_search"] },
    mcpServers: { movil: { httpUrl: `http://127.0.0.1:${puerto}/mcp/vigilado`, timeout: 900000, trust: true } },
  }, null, 1));
  if (sistema) writeFileSync(join(base, "sistema.md"), sistema);
}

// ---------- Preguntas rápidas (sin herramientas) con la IA elegida ----------

function ejecutar(cmd, args, entrada, ms = 120_000) {
  return new Promise((resolver, rechazar) => {
    const p = spawn(cmd, args, { stdio: ["pipe", "pipe", "pipe"], detached: true });
    p.stdin.end(entrada);
    let salida = "", errores = "";
    const reloj = setTimeout(() => {
      try {
        process.kill(-p.pid, "SIGTERM");
      } catch {}
    }, ms);
    p.stdout.on("data", (d) => (salida += d));
    p.stderr.on("data", (d) => (errores += d));
    p.on("error", rechazar);
    p.on("close", (codigo) => {
      clearTimeout(reloj);
      codigo === 0 ? resolver(salida) : rechazar(new Error(errores.trim().split("\n").pop()?.slice(0, 200) || `salió con ${codigo}`));
    });
  });
}

export async function rapido(pregunta, sistema) {
  const id = activo();
  const p = PROVEEDORES[id];
  if (p.motor === "claude") {
    const s = await ejecutar("claude-agente", ["-p", "--model", "haiku", "--effort", "low", "--tools", "", "--setting-sources", "", "--output-format", "text", "--system-prompt", sistema], pregunta);
    return s.trim();
  }
  if (p.motor === "codex") {
    const args = ["exec", "--json", "--skip-git-repo-check", "-c", `developer_instructions=${JSON.stringify(sistema)}`, "-c", "tools.web_search=false", "-"];
    const s = await ejecutar("ia-codex", args, pregunta);
    const mensajes = s.split("\n").map((l) => { try { return JSON.parse(l); } catch { return null; } })
      .filter((e) => e?.type === "item.completed" && e.item?.type === "agent_message");
    return mensajes.map((e) => e.item.text).join("\n").trim();
  }
  if (p.motor === "kimi") {
    const { agente } = escribirAgenteKimi(sistema);
    const s = await ejecutar("ia-kimi", ["--print", "--agent-file", agente, "--output-format", "stream-json", "-p", pregunta], "");
    const finales = s.split("\n").map((l) => { try { return JSON.parse(l); } catch { return null; } })
      .filter((e) => e?.role === "assistant" && !e.tool_calls?.length && typeof e.content === "string");
    return (finales.pop()?.content || "").trim();
  }
  if (p.motor === "gemini") {
    const s = await ejecutar("ia-gemini", ["--output-format", "json", "-m", "gemini-2.5-flash", "--allowed-mcp-server-names", "ninguno"], `${sistema}\n\n${pregunta}`);
    return String(JSON.parse(s.slice(s.indexOf("{"))).response || "").trim();
  }
  // API o local: una sola petición, sin herramientas.
  if (p.tipo === "local") await asegurarLocal();
  const key = p.tipo === "local" ? "local" : clave(id);
  if (!key) throw new ErrorIa("falta_clave", "Falta la clave de la API.");
  const r = await fetch(`${p.base}/chat/completions`, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: `Bearer ${key}` },
    body: JSON.stringify({
      model: await modeloParaUsar(id, "rapido"),
      messages: [{ role: "system", content: sistema + (p.tipo === "local" ? "\n/no_think" : "") }, { role: "user", content: pregunta }],
      max_tokens: 800,
    }),
    signal: AbortSignal.timeout(120_000),
  });
  if (!r.ok) throw new ErrorIa("respondio", `${p.nombre} respondió ${r.status}.`, { nombre: p.nombre, estado: r.status });
  const d = await r.json();
  return String(d.choices?.[0]?.message?.content || "").replace(/<think>[\s\S]*?<\/think>/g, "").trim();
}

/** Quita sesiones viejas del motor propio (más de 60 días). */
export function limpiarSesiones() {
  const dir = join(DIR, "sesiones");
  try {
    for (const f of readdirSync(dir)) {
      const ruta = join(dir, f);
      if (Date.now() - statSync(ruta).mtimeMs > 60 * 86400_000) rmSync(ruta, { force: true });
    }
  } catch {}
}
