#!/usr/bin/env node
// Motor de IA para lo que no es Claude Code: ChatGPT (Codex), Gemini (Gemini CLI), cualquier API
// compatible con OpenAI (DeepSeek, Kimi, Mistral, Grok, OpenRouter…) y el modelo local (llama.cpp).
//
// Habla el mismo stream-json que Claude Code (entrada por stdin, eventos por stdout), así el
// agente, el chat, las tarjetas de permiso y el registro funcionan igual sea cual sea la IA.
// Las únicas herramientas son las del móvil, por MCP, en /mcp/vigilado: allí el servicio aplica
// el modo de permisos del usuario y pide confirmación con la tarjeta de siempre. Codex y Gemini
// conservan sus protecciones por defecto (Codex en sandbox de solo lectura; Gemini sin modo yolo:
// solo se confía en el servidor del móvil, que ya pregunta al usuario).
//
// Configuración en la variable IA_CONFIG (JSON): { proveedor, modelo, sistema, sesion, web, puerto }.
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { createInterface } from "node:readline";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { PROVEEDORES } from "./catalogo.mjs";
import { clave, rutaDe } from "./claves.mjs";
import { anotar, vigilarFetch } from "../privacidad.mjs";

vigilarFetch(); // las llamadas a la API de la IA también quedan anotadas

const cfg = JSON.parse(process.env.IA_CONFIG || "{}");
const prov = PROVEEDORES[cfg.proveedor];
const PUERTO = cfg.puerto || 8765;
const URL_MCP = `http://127.0.0.1:${PUERTO}/mcp/vigilado`;
const SESIONES = join(homedir(), "sios", "ia", "sesiones");
mkdirSync(SESIONES, { recursive: true });

const decir = (ev) => process.stdout.write(JSON.stringify(ev) + "\n");
const nombreHerramienta = (n) => (n === "web_buscar" ? "WebSearch" : n === "web_leer" ? "WebFetch" : `mcp__movil__${n}`);
const entradaParaResumen = (n, a) => (n === "web_buscar" ? { query: a.consulta } : a);
// «auto»: que elija el propio servicio (Codex y Gemini CLI ya escogen el mejor de tu plan).
const conModelo = () => (cfg.modelo && cfg.modelo !== "auto" ? ["-m", cfg.modelo] : []);
const textoDe = (content) => (content || []).filter((c) => c.type === "text").map((c) => c.text).join("\n");

function resultado(error, inicio, uso = {}) {
  decir({
    type: "result",
    is_error: !!error,
    result: error || "",
    duration_ms: Date.now() - inicio,
    usage: { input_tokens: uso.entrada || 0, output_tokens: uso.salida || 0, cache_read_input_tokens: uso.cache || 0 },
    modelUsage: { [cfg.modelo]: {} },
  });
}

// ---------- Herramientas del móvil (cliente MCP) ----------

let mcp = null;
let herramientas = [];
async function conectarMcp() {
  if (mcp) return;
  const c = new Client({ name: `motor-${cfg.proveedor}`, version: "1.0.0" });
  await c.connect(new StreamableHTTPClientTransport(new URL(URL_MCP)));
  mcp = c;
  herramientas = (await c.listTools()).tools;
}

// Un modelo pequeño se pierde con 50 herramientas: en local, solo las de cada día.
const ESENCIALES = [
  "info_movil", "contactos_buscar", "sms_leer", "sms_enviar", "llamar", "calendario_eventos", "alarma_poner",
  "temporizador_poner", "leer_notificaciones", "responder_mensaje", "abrir_app", "tu_dia", "recordar", "web_buscar",
];

/** En local, el esquema mínimo: sin descripciones de cada parámetro (son cientos de tokens). */
function esquemaCorto(e) {
  if (!e || typeof e !== "object") return e;
  const { description, $schema, additionalProperties, ...resto } = e;
  if (resto.properties) resto.properties = Object.fromEntries(Object.entries(resto.properties).map(([k, v]) => [k, esquemaCorto(v)]));
  if (resto.items) resto.items = esquemaCorto(resto.items);
  return resto;
}

function esquemasOpenAI() {
  const local = prov.tipo === "local";
  return herramientas
    .filter((h) => !local || ESENCIALES.includes(h.name))
    .filter((h) => cfg.web !== false || !h.name.startsWith("web_"))
    .map((h) => ({
      type: "function",
      function: {
        name: h.name,
        // Las descripciones largas cuestan contexto en cada mensaje: en local, la primera frase.
        description: local ? String(h.description || "").split(/(?<=\.)\s/)[0].slice(0, 160) : String(h.description || "").slice(0, 1000),
        parameters: local ? esquemaCorto(h.inputSchema || { type: "object", properties: {} }) : h.inputSchema || { type: "object", properties: {} },
      },
    }));
}

async function usarHerramienta(nombre, args) {
  try {
    const r = await mcp.callTool({ name: nombre, arguments: args }, undefined, { timeout: 900_000 });
    const partes = r.content || [];
    return {
      texto: partes.filter((c) => c.type === "text").map((c) => c.text).join("\n") || "(hecho)",
      imagenes: partes.filter((c) => c.type === "image").map((c) => `data:${c.mimeType};base64,${c.data}`),
      error: !!r.isError,
    };
  } catch (e) {
    return { texto: `Error: ${e.message || e}`, imagenes: [], error: true };
  }
}

// ---------- Sesiones guardadas (para seguir una conversación) ----------

const archivoSesion = (id) => join(SESIONES, `${String(id).replace(/[^\w-]/g, "")}.json`);
function cargarSesion(id) {
  try {
    return JSON.parse(readFileSync(archivoSesion(id), "utf8"));
  } catch {
    return null;
  }
}
function guardarSesion(s) {
  // Sin imágenes en disco: pesan mucho y el modelo ya las vio.
  const ligera = {
    ...s,
    mensajes: s.mensajes.map((m) =>
      Array.isArray(m.content) ? { ...m, content: m.content.map((c) => (c.type === "image_url" ? { type: "text", text: "[imagen]" } : c)) } : m
    ),
  };
  const f = archivoSesion(s.id);
  writeFileSync(f + ".tmp", JSON.stringify(ligera));
  renameSync(f + ".tmp", f);
}

// ---------- Bucle de agente con cualquier API compatible con OpenAI ----------

const ve = () => prov.vision !== false && prov.tipo !== "local";
const LIMITE_RESULTADO = () => (prov.tipo === "local" ? 3000 : 12000);
const LIMITE_MENSAJES = () => (prov.tipo === "local" ? 16 : 60);

/** Los mensajes que caben: los últimos, empezando siempre por uno del usuario. */
function recortar(mensajes) {
  let m = mensajes.slice(-LIMITE_MENSAJES());
  while (m.length && m[0].role !== "user") m = m.slice(1);
  return m;
}

async function* sse(r) {
  const lector = r.body.getReader();
  const dec = new TextDecoder();
  let buf = "";
  for (;;) {
    const { value, done } = await lector.read();
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const linea = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!linea.startsWith("data:")) continue;
      const d = linea.slice(5).trim();
      if (d === "[DONE]") return;
      try {
        yield JSON.parse(d);
      } catch {}
    }
  }
}

function motivoError(estado, cuerpo) {
  if (estado === 401 || estado === 403) return "La clave no es válida o no tiene acceso a este modelo. Cámbiala en Ajustes › Inteligencia.";
  if (estado === 402) return "La cuenta no tiene saldo. Recárgala en la web del proveedor.";
  if (estado === 429) return "Has llegado al límite de uso del proveedor. Prueba en un rato.";
  if (estado === 404) return "Ese modelo no existe en este proveedor. Elige otro en Ajustes › Inteligencia.";
  return `El proveedor respondió ${estado}: ${String(cuerpo).slice(0, 200)}`;
}

class Bucle {
  constructor() {
    const previa = cfg.sesion ? cargarSesion(cfg.sesion) : null;
    this.sesion = previa || { id: randomUUID(), proveedor: cfg.proveedor, mensajes: [] };
    this.cola = Promise.resolve();
  }

  async iniciar() {
    // El modelo local tarda en cargar: se espera a que llama-server responda (lo arranca el agente).
    if (prov.tipo === "local") {
      for (let i = 0; i < 180; i++) {
        const ok = await fetch(prov.base.replace(/\/v1$/, "/health"), { signal: AbortSignal.timeout(1500) }).then((r) => r.ok).catch(() => false);
        if (ok) break;
        await new Promise((r) => setTimeout(r, 1000));
      }
    }
    await conectarMcp();
    decir({ type: "system", subtype: "init", session_id: this.sesion.id, model: cfg.modelo });
    // Precalentado: el modelo lee ya las instrucciones y las herramientas (lo más largo) y las deja
    // en caché; el primer mensaje del usuario solo tiene que leer lo suyo.
    if (prov.tipo === "local") {
      this.cola = this.cola.then(() => fetch(`${prov.base}/chat/completions`, {
        method: "POST",
        headers: { "content-type": "application/json", authorization: "Bearer local" },
        body: JSON.stringify({ model: cfg.modelo, max_tokens: 1, messages: [{ role: "system", content: cfg.sistema + "\n/no_think" }, { role: "user", content: "." }], tools: esquemasOpenAI() }),
        signal: AbortSignal.timeout(600_000),
      }).then((r) => r.text()).catch(() => {}));
    }
  }

  recibir(msg) {
    const inicio = Date.now();
    this.cola = this.cola.then(() => this.turno(msg)).catch((e) => resultado(e.message || String(e), inicio));
  }

  async turno(msg) {
    const inicio = Date.now();
    const uso = { entrada: 0, salida: 0, cache: 0 };
    const partes = [];
    for (const c of msg.message?.content || []) {
      if (c.type === "text") partes.push({ type: "text", text: c.text });
      else if (c.type === "image") {
        if (ve()) partes.push({ type: "image_url", image_url: { url: `data:${c.source.media_type};base64,${c.source.data}` } });
        else partes.push({ type: "text", text: "(El usuario ha adjuntado una imagen, pero este modelo no puede verla: díselo si hace falta.)" });
      }
    }
    this.sesion.mensajes.push({ role: "user", content: partes.length === 1 && partes[0].type === "text" ? partes[0].text : partes });
    const key = prov.tipo === "local" ? "local" : clave(cfg.proveedor);
    if (!key) return resultado("Falta la clave de la API. Añádela en Ajustes › Inteligencia.", inicio, uso);

    for (let paso = 0; paso < 40; paso++) {
      const cuerpo = {
        model: cfg.modelo,
        stream: true,
        messages: [{ role: "system", content: cfg.sistema + (prov.tipo === "local" ? "\n/no_think" : "") }, ...recortar(this.sesion.mensajes)],
        tools: esquemasOpenAI(),
        // Qwen3 sin pensar: los valores que recomienda su ficha para respuestas estables.
        ...(prov.tipo === "local" ? { temperature: 0.7, top_p: 0.8, top_k: 20, min_p: 0 } : {}),
      };
      if (!["mistral", "gemini_api"].includes(cfg.proveedor)) cuerpo.stream_options = { include_usage: true };
      const cabeceras = { "content-type": "application/json", authorization: `Bearer ${key}` };
      if (cfg.proveedor === "openrouter") Object.assign(cabeceras, { "x-title": "SIOS" });
      let r;
      try {
        r = await fetch(`${prov.base}/chat/completions`, { method: "POST", headers: cabeceras, body: JSON.stringify(cuerpo), signal: AbortSignal.timeout(600_000) });
      } catch (e) {
        return resultado(prov.tipo === "local" ? "El modelo local no está en marcha. Ábrelo en Ajustes › Inteligencia." : `Sin conexión con ${prov.nombre}: ${e.message}`, inicio, uso);
      }
      if (!r.ok) return resultado(motivoError(r.status, await r.text()), inicio, uso);

      let texto = "";
      let abierto = false;
      let pensando = false;
      const llamadas = [];
      for await (const d of sse(r)) {
        if (d.usage) {
          uso.entrada += d.usage.prompt_tokens || 0;
          uso.salida += d.usage.completion_tokens || 0;
          uso.cache += d.usage.prompt_tokens_details?.cached_tokens || d.usage.prompt_cache_hit_tokens || 0;
        }
        const delta = d.choices?.[0]?.delta;
        if (!delta) continue;
        if (delta.content) {
          // Algunos modelos piensan en voz alta entre <think>…</think>: eso no se enseña.
          // A veces llega solo el cierre «</think>» (pensamiento vacío): todo lo anterior se descarta.
          let t = delta.content;
          if (t.includes("<think>")) pensando = true;
          if (t.includes("</think>")) {
            t = t.split("</think>").pop();
            pensando = false;
            if (!texto.trim()) texto = "";
          } else if (pensando) continue;
          if (!texto && !t.trim()) continue;
          if (!abierto) {
            decir({ type: "stream_event", event: { type: "content_block_start", content_block: { type: "text" } } });
            abierto = true;
          }
          texto += t;
          decir({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: t } } });
        }
        for (const tc of delta.tool_calls || []) {
          const i = tc.index ?? llamadas.length;
          llamadas[i] ||= { id: tc.id || `llamada_${randomUUID().slice(0, 8)}`, nombre: "", args: "" };
          if (tc.id) llamadas[i].id = tc.id;
          if (tc.function?.name) llamadas[i].nombre += tc.function.name;
          if (tc.function?.arguments) llamadas[i].args += tc.function.arguments;
        }
      }
      const validas = llamadas.filter((l) => l?.nombre);
      const contenido = [];
      if (texto.trim()) contenido.push({ type: "text", text: texto.trim() });
      for (const l of validas) {
        try {
          l.entrada = l.args ? JSON.parse(l.args) : {};
        } catch {
          l.entrada = {};
        }
        contenido.push({ type: "tool_use", id: l.id, name: nombreHerramienta(l.nombre), input: entradaParaResumen(l.nombre, l.entrada) });
      }
      if (contenido.length) decir({ type: "assistant", message: { role: "assistant", content: contenido } });
      this.sesion.mensajes.push({
        role: "assistant",
        content: texto.trim() || (validas.length ? null : ""),
        ...(validas.length ? { tool_calls: validas.map((l) => ({ id: l.id, type: "function", function: { name: l.nombre, arguments: l.args || "{}" } })) } : {}),
      });
      // Un modelo pequeño a veces se queda en blanco: se le pide otra vez, una sola.
      if (!validas.length && !texto.trim() && !this.reintentado) {
        this.reintentado = true;
        this.sesion.mensajes.pop();
        continue;
      }
      this.reintentado = false;
      if (!validas.length) break;

      const verAhora = [];
      for (const l of validas) {
        const res = await usarHerramienta(l.nombre, l.entrada);
        decir({ type: "user", message: { role: "user", content: [{ type: "tool_result", tool_use_id: l.id, is_error: res.error }] } });
        this.sesion.mensajes.push({ role: "tool", tool_call_id: l.id, content: res.texto.slice(0, LIMITE_RESULTADO()) });
        if (res.imagenes.length && ve()) verAhora.push(...res.imagenes);
      }
      // Las capturas de pantalla no caben en un resultado de herramienta: van como mensaje aparte.
      if (verAhora.length) {
        this.sesion.mensajes.push({ role: "user", content: [{ type: "text", text: "(Imágenes que ha devuelto la herramienta.)" }, ...verAhora.map((url) => ({ type: "image_url", image_url: { url } }))] });
      }
      guardarSesion(this.sesion);
    }
    guardarSesion(this.sesion);
    resultado(null, inicio, uso);
  }
}

// ---------- ChatGPT con la suscripción (Codex CLI) ----------
// Un `codex exec` por mensaje (sandbox de solo lectura, por defecto); la conversación sigue con
// `codex exec resume <hilo>`. Sus herramientas: las del móvil por MCP y su buscador web.

class Codex {
  constructor() {
    this.hilo = cfg.sesion || null;
    this.cola = Promise.resolve();
  }

  async iniciar() {
    decir({ type: "system", subtype: "init", session_id: this.hilo || `codex-${randomUUID()}`, model: cfg.modelo });
  }

  recibir(msg) {
    this.cola = this.cola.then(() => this.turno(msg));
  }

  turno(msg) {
    return new Promise((listo) => {
      const inicio = Date.now();
      const imagenes = (msg.message?.content || []).filter((c) => c.type === "image");
      const tmp = join(rutaDe("codex"), "adjuntos");
      mkdirSync(tmp, { recursive: true });
      const rutas = imagenes.map((c, i) => {
        const f = join(tmp, `${Date.now()}-${i}.jpg`);
        writeFileSync(f, Buffer.from(c.source.data, "base64"));
        return f;
      });
      const ajustes = [
        `mcp_servers.movil.url="${URL_MCP}"`,
        "mcp_servers.movil.tool_timeout_sec=900",
        `developer_instructions=${JSON.stringify(cfg.sistema)}`,
        `tools.web_search=${cfg.web !== false}`,
      ].flatMap((c) => ["-c", c]);
      const comunes = ["--json", "--skip-git-repo-check", ...conModelo(), ...ajustes, ...rutas.flatMap((r) => ["-i", r]), "-"];
      const args = this.hilo && !this.hilo.startsWith("codex-") ? ["exec", "resume", this.hilo, ...comunes] : ["exec", ...comunes];
      const p = spawn("ia-codex", args, { stdio: ["pipe", "pipe", "pipe"] });
      p.stdin.end(textoDe(msg.message?.content));
      const uso = { entrada: 0, salida: 0, cache: 0 };
      let error = null;
      let errores = "";
      p.stderr.on("data", (d) => (errores = (errores + d).slice(-2000)));
      createInterface({ input: p.stdout }).on("line", (l) => {
        let ev;
        try {
          ev = JSON.parse(l);
        } catch {
          return;
        }
        const it = ev.item || {};
        if (ev.type === "thread.started" && ev.thread_id) {
          this.hilo = ev.thread_id;
          decir({ type: "system", subtype: "init", session_id: ev.thread_id, model: cfg.modelo });
        } else if (ev.type === "item.started" && it.type === "mcp_tool_call") {
          decir({ type: "assistant", message: { content: [{ type: "tool_use", id: it.id, name: nombreHerramienta(it.tool), input: entradaParaResumen(it.tool, it.arguments || {}) }] } });
        } else if (ev.type === "item.started" && it.type === "web_search") {
          decir({ type: "assistant", message: { content: [{ type: "tool_use", id: it.id, name: "WebSearch", input: { query: it.query || "" } }] } });
        } else if (ev.type === "item.completed" && ["mcp_tool_call", "web_search"].includes(it.type)) {
          decir({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: it.id, is_error: it.status === "failed" || !!it.error }] } });
        } else if (ev.type === "item.completed" && it.type === "agent_message" && it.text?.trim()) {
          decir({ type: "stream_event", event: { type: "content_block_start", content_block: { type: "text" } } });
          decir({ type: "assistant", message: { content: [{ type: "text", text: it.text }] } });
        } else if (ev.type === "turn.completed") {
          uso.entrada += ev.usage?.input_tokens || 0;
          uso.cache += ev.usage?.cached_input_tokens || 0;
          uso.salida += ev.usage?.output_tokens || 0;
        } else if (ev.type === "turn.failed" || ev.type === "error") {
          error = ev.error?.message || ev.message || "ChatGPT no ha podido terminar.";
        }
      });
      p.on("close", (codigo) => {
        if (codigo && !error) error = /login|auth|401/i.test(errores) ? "No has iniciado sesión con ChatGPT. Entra en Ajustes › Inteligencia." : errores.trim().split("\n").pop() || `Codex salió con ${codigo}`;
        if (error && /usage limit|rate limit/i.test(error)) error = "Has llegado al límite de tu plan de ChatGPT. Prueba más tarde o cambia de IA.";
        resultado(error, inicio, uso);
        listo();
      });
    });
  }
}

// ---------- Gemini con la cuenta de Google (Gemini CLI) ----------
// Sus ajustes (ia/gemini/.gemini/settings.json) solo dejan sus herramientas de internet y el
// servidor del móvil, marcado como de confianza porque ya pide permiso al usuario él mismo.

class Gemini {
  constructor() {
    this.sesion = cfg.sesion || null;
    this.cola = Promise.resolve();
  }

  async iniciar() {
    decir({ type: "system", subtype: "init", session_id: this.sesion || `gemini-${randomUUID()}`, model: cfg.modelo });
  }

  recibir(msg) {
    this.cola = this.cola.then(() => this.turno(msg));
  }

  turno(msg) {
    return new Promise((listo) => {
      const inicio = Date.now();
      let texto = textoDe(msg.message?.content);
      const imagenes = (msg.message?.content || []).filter((c) => c.type === "image");
      if (imagenes.length) {
        const dir = join(rutaDe("gemini"), "adjuntos");
        mkdirSync(dir, { recursive: true });
        const rutas = imagenes.map((c, i) => {
          const f = join(dir, `${Date.now()}-${i}.jpg`);
          writeFileSync(f, Buffer.from(c.source.data, "base64"));
          return f;
        });
        texto = `${rutas.map((r) => `@${r}`).join(" ")}\n${texto}`;
      }
      const args = ["--output-format", "stream-json", ...conModelo()];
      if (this.sesion && !this.sesion.startsWith("gemini-")) args.push("--resume", this.sesion);
      const p = spawn("ia-gemini", args, { stdio: ["pipe", "pipe", "pipe"] });
      p.stdin.end(texto);
      const uso = { entrada: 0, salida: 0, cache: 0 };
      let error = null;
      let errores = "";
      let abierto = false;
      let acumulado = "";
      const cerrarTexto = () => {
        if (acumulado.trim()) decir({ type: "assistant", message: { content: [{ type: "text", text: acumulado.trim() }] } });
        acumulado = "";
        abierto = false;
      };
      p.stderr.on("data", (d) => (errores = (errores + d).slice(-2000)));
      createInterface({ input: p.stdout }).on("line", (l) => {
        let ev;
        try {
          ev = JSON.parse(l);
        } catch {
          return;
        }
        if (ev.type === "init" && ev.session_id) {
          this.sesion = ev.session_id;
          decir({ type: "system", subtype: "init", session_id: ev.session_id, model: ev.model || cfg.modelo });
        } else if (ev.type === "message" && ev.role === "assistant" && ev.content) {
          if (!abierto) {
            decir({ type: "stream_event", event: { type: "content_block_start", content_block: { type: "text" } } });
            abierto = true;
          }
          acumulado += ev.content;
          decir({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: ev.content } } });
        } else if (ev.type === "tool_use") {
          cerrarTexto();
          const n = String(ev.tool_name || "").replace(/^mcp_movil_|^movil__/, "");
          const nombre = n === "google_web_search" ? "WebSearch" : n === "web_fetch" ? "WebFetch" : nombreHerramienta(n);
          decir({ type: "assistant", message: { content: [{ type: "tool_use", id: ev.tool_id, name: nombre, input: n === "google_web_search" ? { query: ev.parameters?.query } : ev.parameters || {} }] } });
        } else if (ev.type === "tool_result") {
          decir({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: ev.tool_id, is_error: ev.status === "error" }] } });
        } else if (ev.type === "error") {
          error = ev.message || "Gemini ha fallado.";
        } else if (ev.type === "result") {
          const s = ev.stats || {};
          uso.entrada = s.input_tokens || 0;
          uso.salida = s.output_tokens || 0;
          uso.cache = s.cached || 0;
          if (ev.status === "error") error ||= ev.error?.message || "Gemini no ha podido terminar.";
        }
      });
      p.on("close", (codigo) => {
        cerrarTexto();
        if (codigo && !error) error = /auth|login|credentials/i.test(errores) ? "No has iniciado sesión con Google. Entra en Ajustes › Inteligencia." : errores.trim().split("\n").pop() || `Gemini salió con ${codigo}`;
        if (error && /quota|429|exhausted/i.test(error)) error = "Has llegado al límite de uso de Gemini. Prueba más tarde o cambia de IA.";
        resultado(error, inicio, uso);
        listo();
      });
    });
  }
}

// ---------- Kimi con la suscripción (Kimi Code) ----------
// Un proceso que lee mensajes por stdin (stream-json) y contesta con mensajes estilo OpenAI:
// {role:"assistant", content, tool_calls?} y {role:"tool", tool_call_id, content}. El agente
// (ia/kimi/agente.yaml) solo tiene su buscador web: el resto de herramientas son las del móvil.

class Kimi {
  constructor() {
    // Kimi crea la sesión con el id que se le dé (y la retoma si ya existe).
    this.sesion = cfg.sesion || randomUUID();
    this.cola = Promise.resolve();
    this.inicio = Date.now();
  }

  async iniciar() {
    const dir = rutaDe("kimi");
    const args = ["--print", "--input-format", "stream-json", "--output-format", "stream-json",
      "--agent-file", join(dir, "agente.yaml"), "--mcp-config-file", join(dir, "mcp.json"), "--session", this.sesion, ...conModelo()];
    const p = spawn("ia-kimi", args, { stdio: ["pipe", "pipe", "pipe"] });
    this.p = p;
    let errores = "";
    p.stderr.on("data", (d) => (errores = (errores + d).slice(-2000)));
    p.on("close", (codigo) => {
      if (this.pendiente) {
        const e = /login|auth|401|unauthor/i.test(errores) ? "No has iniciado sesión con Kimi. Entra en Ajustes › Inteligencia." : errores.trim().split("\n").pop() || `Kimi salió con ${codigo}`;
        resultado(e, this.inicio);
      }
      process.exit(0);
    });
    createInterface({ input: p.stdout }).on("line", (l) => this.linea(l));
    decir({ type: "system", subtype: "init", session_id: this.sesion, model: cfg.modelo });
  }

  linea(l) {
    let m;
    try {
      m = JSON.parse(l);
    } catch {
      return;
    }
    if (m.role === "assistant") {
      const texto = typeof m.content === "string" ? m.content : textoDe(m.content);
      const contenido = [];
      if (texto?.trim()) {
        decir({ type: "stream_event", event: { type: "content_block_start", content_block: { type: "text" } } });
        contenido.push({ type: "text", text: texto.trim() });
      }
      for (const tc of m.tool_calls || []) {
        let entrada = {};
        try {
          entrada = JSON.parse(tc.function?.arguments || "{}");
        } catch {}
        const n = String(tc.function?.name || "").replace(/^mcp__movil__|^movil[_:.]+/, "");
        const nombre = n === "SearchWeb" ? "WebSearch" : n === "FetchURL" ? "WebFetch" : nombreHerramienta(n);
        contenido.push({ type: "tool_use", id: tc.id, name: nombre, input: n === "SearchWeb" ? { query: entrada.query } : entrada });
      }
      if (contenido.length) decir({ type: "assistant", message: { content: contenido } });
      // Un mensaje sin llamadas a herramientas cierra el turno.
      if (!m.tool_calls?.length && this.pendiente) {
        this.pendiente = false;
        resultado(null, this.inicio);
        this.listo?.();
      }
    } else if (m.role === "tool") {
      const error = /^(error|failed)/i.test(String(m.content || ""));
      decir({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: m.tool_call_id, is_error: error }] } });
    }
  }

  recibir(msg) {
    this.cola = this.cola.then(() => new Promise((listo) => {
      this.inicio = Date.now();
      this.pendiente = true;
      this.listo = listo;
      const partes = [];
      for (const c of msg.message?.content || []) {
        if (c.type === "text") partes.push({ type: "text", text: c.text });
        else if (c.type === "image") partes.push({ type: "text", text: "(El usuario ha adjuntado una imagen, pero aquí no se puede ver: díselo si hace falta.)" });
      }
      this.p.stdin.write(JSON.stringify({ role: "user", content: partes }) + "\n");
    }));
  }
}

// ---------- Arranque ----------

if (!prov) {
  resultado(`IA desconocida: ${cfg.proveedor}`, Date.now());
  process.exit(1);
}
const motor = prov.motor === "codex" ? new Codex() : prov.motor === "gemini" ? new Gemini() : prov.motor === "kimi" ? new Kimi() : new Bucle();
try {
  await motor.iniciar();
} catch (e) {
  resultado(`No se pudo preparar ${prov.nombre}: ${e.message || e}`, Date.now());
}
createInterface({ input: process.stdin }).on("line", (l) => {
  try {
    const m = JSON.parse(l);
    if (m.type === "user") motor.recibir(m);
  } catch {}
});
process.stdin.on("end", () => motor.cola.finally(() => {
  motor.p?.stdin.end();
  if (!motor.p) process.exit(0);
}));
process.on("SIGTERM", () => process.exit(0));
