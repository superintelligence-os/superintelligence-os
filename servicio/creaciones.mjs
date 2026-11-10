// Creaciones de Claude: widgets del inicio, mini-apps, agentes en segundo plano y rutinas
// («cuando llegue a casa…», «cuando me conecte al coche…»).
//
// Cada creación es una ficha JSON en ~/sios/creaciones/<id>.json. El diseño está
// predefinido (plantillas, acentos e iconos cerrados): Claude solo rellena contenido y elige
// opciones, así todo se ve como SIOS y se puede cambiar con una frase.
//
// Rendimiento: un único temporizador por minuto; los comandos van con prioridad baja (nice) y un
// tiempo máximo, y Claude (Haiku, sin herramientas) solo se llama si la salida del comando cambia.
import { rapido as rapidoIa, SISTEMA } from "./ia/ia.mjs";
import { mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { createHash, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { android, puente, resolverApp, termux, q } from "./movil.mjs";
import { registrar } from "./actividad.mjs";
import { FUENTES, leerFuente, casaAccion } from "./fuentes.mjs";
import { contexto, contextoTexto, cumple, gastarIa, ajustesPanel } from "./contexto.mjs";

const DIR = join(process.env.HOME || "/data/data/com.termux/files/home", "sios", "creaciones");
// Panel (a la izquierda del inicio): título y orden de sus tarjetas, que son widgets con lugar «panel».
const PANEL = join(DIR, "..", "panel.json");
const HISTORIAL = join(DIR, "..", "panel-historial");

export const TIPOS = ["widget", "app", "agente", "rutina"];
// Rutinas: qué las dispara y qué pueden hacer.
export const DISPARADORES = ["hora", "lugar", "wifi", "bluetooth", "bateria", "carga", "notificacion"];
export const ACCIONES = ["comando", "aviso", "abrir_app", "claude"];
export const PLANTILLAS = ["cifra", "lista", "texto", "progreso", "noticias", "agenda", "filas", "anillos", "grafico", "marcador", "pila", "html"];
// Acciones de los botones y las filas de un widget (las ejecuta la app o el servicio).
export const ACCIONES_WIDGET = ["enlace", "app", "llamar", "navegar", "multimedia", "tachar", "claude", "casa", "comando", "refrescar"];
// Tamaños en la rejilla de 2 columnas del panel: pequeño 1×1, mediano 2×1, grande 2×2.
export const TAMANOS = ["pequeno", "mediano", "grande"];
const TAMANO_PLANTILLA = { cifra: "pequeno", progreso: "pequeno", anillos: "pequeno", texto: "mediano", lista: "mediano", agenda: "mediano", html: "mediano", noticias: "grande", filas: "grande", grafico: "mediano", marcador: "mediano", pila: "mediano" };

/** Comprueba una acción de widget (botón o fila). */
function validarAccion(a, donde) {
  if (!a) return;
  if (!ACCIONES_WIDGET.includes(a.tipo)) throw new Error(`${donde}.accion.tipo: ${ACCIONES_WIDGET.join(", ")}`);
  const falta = { enlace: "url", app: "app", llamar: "numero", claude: "prompt", casa: "entidad", comando: "comando", multimedia: "orden" }[a.tipo];
  if (falta && !a[falta]) throw new Error(`${donde}.accion de tipo ${a.tipo} necesita ${falta}`);
  if (a.tipo === "navegar" && !a.destino && !Number.isFinite(a.lat)) throw new Error(`${donde}.accion navegar necesita destino o lat/lon`);
  if (a.tipo === "multimedia" && !["play_pause", "siguiente", "anterior"].includes(a.orden)) throw new Error("multimedia.orden: play_pause, siguiente, anterior");
}
// Dónde va un widget: la pantalla de inicio o el panel que sale al deslizar a la derecha.
export const LUGARES = ["inicio", "panel", "pila"]; // pila: dentro de una pila (no se ve suelto)
export { FUENTES };
export const ACENTOS = ["naranja", "verde", "azul", "amarillo", "rojo", "gris"];
// Iconos de línea disponibles para mini-apps y widgets (los dibuja la app con el pack de iconos).
export const ICONOS = [
  "sparkles", "shopping-cart", "list-checks", "wallet", "piggy-bank", "dumbbell", "book-open", "timer",
  "cloud-sun", "battery-medium", "bell", "heart", "star", "music", "map", "coffee", "utensils", "plane",
  "car", "house", "calendar-check", "clipboard-list", "gamepad-2", "film", "sprout", "pill",
  "graduation-cap", "briefcase", "chart-line", "zap", "newspaper", "smile",
];
// Mínimos para no gastar batería ni suscripción: comandos cada 5 min, Claude cada 15.
const MIN_COMANDO = 5;
const MIN_CLAUDE = 15;

const ahora = () => Date.now();
const huella = (s) => createHash("sha1").update(String(s)).digest("hex").slice(0, 12);

export class Creaciones {
  /** emitir: avisa a la app (SSE). */
  constructor(emitir) {
    this.emitir = emitir;
    this.ocupado = false;
    mkdirSync(DIR, { recursive: true });
  }

  // ---------- Fichas ----------

  listar() {
    return readdirSync(DIR)
      .filter((f) => f.endsWith(".json") && !f.endsWith(".datos.json")) // los .datos.json son de las mini-apps
      .map((f) => {
        try {
          return JSON.parse(readFileSync(join(DIR, f), "utf8"));
        } catch {
          return null;
        }
      })
      .filter(Boolean)
      .sort((a, b) => a.creado - b.creado);
  }

  obtener(id) {
    try {
      return JSON.parse(readFileSync(join(DIR, `${id.replace(/[^\w-]/g, "")}.json`), "utf8"));
    } catch {
      return null;
    }
  }

  guardar(c) {
    c.actualizado = ahora();
    writeFileSync(join(DIR, `${c.id}.json`), JSON.stringify(c, null, 1));
    this.emitir({ tipo: "creaciones" });
    return c;
  }

  /** Comprueba y normaliza una ficha; lanza un error legible si algo no encaja con el diseño. */
  validar(c) {
    if (!TIPOS.includes(c.tipo)) throw new Error(`tipo debe ser ${TIPOS.join(", ")}`);
    if (!c.nombre?.trim()) throw new Error("Falta el nombre");
    c.nombre = c.nombre.trim().slice(0, 40);
    if (c.acento && !ACENTOS.includes(c.acento)) throw new Error(`acento: ${ACENTOS.join(", ")}`);
    if (c.icono && !ICONOS.includes(c.icono)) throw new Error(`icono: ${ICONOS.join(", ")}`);
    c.acento ||= "naranja";
    if (c.tipo === "widget") {
      const fuente = c.origen?.fuente;
      if (fuente && !FUENTES[fuente]) throw new Error(`origen.fuente: ${Object.keys(FUENTES).join(", ")}`);
      c.plantilla ||= fuente ? FUENTES[fuente].plantilla : "texto";
      if (!PLANTILLAS.includes(c.plantilla)) throw new Error(`plantilla: ${PLANTILLAS.join(", ")}`);
      c.lugar ||= "inicio";
      // La lista de la familia siempre con casillas (tocar tacha para todos).
      if (fuente === "familia_lista") c.marcable = true;
      c.tamano ||= (fuente && FUENTES[fuente].tamano) || TAMANO_PLANTILLA[c.plantilla] || "mediano";
      if (!TAMANOS.includes(c.tamano)) throw new Error(`tamano: ${TAMANOS.join(", ")}`);
      if (!LUGARES.includes(c.lugar)) throw new Error(`lugar: ${LUGARES.join(", ")}`);
      c.datos ||= {};
      if (c.plantilla === "html" && !c.html?.trim()) throw new Error("Un widget con plantilla html necesita html");
      if (c.plantilla === "html") c.alto = Math.min(640, Math.max(60, Number(c.alto) || 0)) || undefined;
      // Botones (hasta 3) y acciones en las filas: widgets que se tocan sin abrir nada.
      if (c.botones) {
        if (!Array.isArray(c.botones) || c.botones.length > 3) throw new Error("botones: hasta 3 [{texto, icono, accion}]");
        c.botones.forEach((b, i) => { if (!b.texto && !b.icono) throw new Error(`botones[${i}] necesita texto o icono`); validarAccion(b.accion, `botones[${i}]`); });
      }
      // Las filas que pone una fuente integrada traen sus propias acciones (internas): solo se
      // comprueban las que escribe Claude en datos fijos.
      if (!fuente) (c.datos.elementos || []).forEach((e, i) => typeof e === "object" && validarAccion(e.accion, `elementos[${i}]`));
      // Pila: varios widgets en el mismo hueco; se ve el que toque (reglas o Claude).
      if (c.plantilla === "pila") {
        if (!Array.isArray(c.pila) || c.pila.length < 2) throw new Error("Una pila necesita pila: [ids de 2 o más widgets]");
        for (const id of c.pila) if (this.obtener(id)?.tipo !== "widget") throw new Error(`pila: no existe el widget ${id}`);
        (c.reglas || []).forEach((r, i) => { if (!c.pila.includes(r.mostrar)) throw new Error(`reglas[${i}].mostrar debe ser uno de la pila`); });
      }
      if (c.modos && !Array.isArray(c.modos)) throw new Error("modos: lista de ids de modos del panel");
      if (fuente) {
        c.datos.titulo ||= FUENTES[fuente].titulo;
        c.origen.cada = Math.max(FUENTES[fuente].min, Number(c.origen.cada) || FUENTES[fuente].min);
      } else if (c.origen?.comando) c.origen.cada = Math.max(MIN_COMANDO, Number(c.origen.cada) || 30);
    }
    if (c.tipo === "app") {
      c.icono ||= "sparkles";
      if (!c.html?.trim()) throw new Error("Falta el html de la mini-app");
    }
    if (c.tipo === "agente") {
      if (!c.comando?.trim() && !c.prompt?.trim()) throw new Error("Un agente necesita comando, prompt o los dos");
      c.cada = Math.max(c.prompt ? MIN_CLAUDE : MIN_COMANDO, Number(c.cada) || 60);
      c.notificar = c.notificar !== false;
      if (c.widget && this.obtener(c.widget)?.tipo !== "widget") throw new Error(`No existe el widget ${c.widget}`);
    }
    if (c.tipo === "rutina") {
      const d = c.disparador;
      if (!d || !DISPARADORES.includes(d.tipo)) throw new Error(`disparador.tipo: ${DISPARADORES.join(", ")}`);
      if (d.tipo === "hora" && !/^\d{1,2}:\d{2}$/.test(d.hora || "")) throw new Error("disparador.hora en formato HH:MM");
      if (d.tipo === "lugar" && !(Number.isFinite(d.lat) && Number.isFinite(d.lon))) throw new Error("disparador.lat y disparador.lon (usa la herramienta ubicacion o busca la dirección)");
      if (d.tipo === "lugar") { d.radio = Math.max(100, Number(d.radio) || 150); d.al ||= "entrar"; }
      // Varios sitios a la vez (todos los supermercados de alrededor…): cualquiera vale.
      if (d.puntos != null && (!Array.isArray(d.puntos) || d.puntos.some((p) => !Number.isFinite(p.lat) || !Number.isFinite(p.lon)))) throw new Error("disparador.puntos: [{lat, lon}]");
      if (d.puntos) d.puntos = d.puntos.slice(0, 20);
      if (d.tipo === "wifi" && !d.red) throw new Error("disparador.red: nombre de la red Wi-Fi");
      if (d.tipo === "bluetooth" && !d.dispositivo) throw new Error("disparador.dispositivo: nombre (o parte) del dispositivo");
      if (d.tipo === "bateria") d.por_debajo = Math.min(95, Math.max(5, Number(d.por_debajo) || 15));
      if (["wifi", "bluetooth", "carga"].includes(d.tipo)) d.conectado = d.conectado !== false;
      if (!Array.isArray(c.acciones) || !c.acciones.length) throw new Error("Una rutina necesita al menos una acción");
      for (const a of c.acciones) if (!ACCIONES.some((k) => a[k])) throw new Error(`Cada acción es uno de: ${ACCIONES.join(", ")}`);
    }
    c.estado ||= {};
    return c;
  }

  /** sinPrimera: quien la crea se encarga de la primera ejecución (las herramientas de Claude). */
  crear(ficha, { sinPrimera = false } = {}) {
    const c = this.validar({ ...ficha, id: randomUUID().slice(0, 8), creado: ahora() });
    if (c.tipo === "widget" && (c.lugar === "panel" || c.lugar === "pila")) this.fotoPanel(`antes de añadir «${c.nombre}»`);
    this.guardar(c);
    if (!sinPrimera && c.tipo !== "app" && c.tipo !== "rutina") setTimeout(() => this.ejecutar(c.id).catch(() => {}), 500); // primer dato ya
    return c;
  }

  /** Cambios parciales: los objetos (datos, origen) se mezclan; el resto se sustituye. */
  modificar(id, cambios, { sinPrimera = false } = {}) {
    const c = this.obtener(id);
    if (!c) throw new Error(`No existe la creación ${id}`);
    if (c.tipo === "widget" && (c.lugar === "panel" || c.lugar === "pila" || cambios.lugar === "panel")) this.fotoPanel(`antes de cambiar «${c.nombre}»`);
    for (const [k, v] of Object.entries(cambios)) {
      if (["id", "tipo", "creado", "estado"].includes(k)) continue;
      c[k] = v && typeof v === "object" && !Array.isArray(v) && c[k] && typeof c[k] === "object" ? { ...c[k], ...v } : v;
    }
    this.guardar(this.validar(c));
    if (!sinPrimera && ("comando" in cambios || "origen" in cambios || "prompt" in cambios)) setTimeout(() => this.ejecutar(id).catch(() => {}), 500);
    return c;
  }

  borrar(id) {
    const c = this.obtener(id);
    if (c?.tipo === "widget" && (c.lugar === "panel" || c.lugar === "pila")) this.fotoPanel(`antes de quitar «${c.nombre}»`);
    if (c?.estado?.en_directo) puente("en_directo_fin", { id: c.id }).catch(() => {});
    // Una pila se lleva sus widgets (solo existen dentro de ella).
    for (const hijo of c?.plantilla === "pila" ? c.pila || [] : []) if (this.obtener(hijo)?.lugar === "pila") rmSync(join(DIR, `${hijo}.json`), { force: true });
    rmSync(join(DIR, `${id.replace(/[^\w-]/g, "")}.json`), { force: true });
    rmSync(datosApp(id), { force: true });
    this.emitir({ tipo: "creaciones" });
  }

  /** Medida real de un widget html en pantalla (sin tocar `actualizado`: no es un cambio de la ficha). */
  anotarMedida(id, m) {
    const c = this.obtener(id);
    if (!c || c.plantilla !== "html") return false;
    c.estado = { ...c.estado, medida: { ...m, cuando: ahora() } };
    writeFileSync(join(DIR, `${c.id}.json`), JSON.stringify(c, null, 1));
    return true;
  }

  // ---------- Panel ----------

  /** {titulo, orden}: orden con los ids de las tarjetas (las nuevas, al final). */
  leerPanel() {
    try { return JSON.parse(readFileSync(PANEL, "utf8")); } catch { return {}; }
  }

  /**
   * {titulo, orden, modos, modo, modo_manual}. orden: las tarjetas del modo activo (las que no dicen
   * modos están en todos), las nuevas al final. modo: el activo (null sin modos).
   */
  panel({ modo } = {}) {
    const p = this.leerPanel();
    const delPanel = this.listar().filter((c) => c.tipo === "widget" && c.lugar === "panel" && (!c.auto || c.auto.visible));
    const visibles = delPanel.filter((c) => !c.modos?.length || !modo || c.modos.includes(modo)).map((c) => c.id);
    // Las automáticas que acaban de aparecer van arriba (salvo que el usuario ya las haya movido).
    const autos = delPanel.filter((c) => c.auto && !(p.orden || []).includes(c.id)).map((c) => c.id);
    const orden = (p.orden || []).filter((id) => visibles.includes(id));
    return {
      titulo: p.titulo || null,
      orden: [...autos.filter((id) => visibles.includes(id)), ...orden, ...visibles.filter((id) => !orden.includes(id) && !autos.includes(id))],
      modos: (p.modos || []).map(({ id, nombre, cuando }) => ({ id, nombre, cuando })),
      modo: modo || null,
      modo_manual: p.modo_manual || null,
    };
  }

  /** Igual que panel(), con el modo activo calculado. */
  async panelAhora() {
    return this.panel({ modo: await this.modoActivo() });
  }

  /**
   * Cambia el título, el orden (los ids que falten se quedan detrás, en su orden), los modos o el
   * modo elegido a mano (null: automático).
   */
  ponerPanel({ titulo, orden, modos, modo_manual } = {}) {
    this.fotoPanel("antes de cambiar el panel");
    const p = this.leerPanel();
    const todos = this.listar().filter((c) => c.tipo === "widget" && c.lugar === "panel").map((c) => c.id);
    const actual = [...(p.orden || []).filter((id) => todos.includes(id)), ...todos.filter((id) => !(p.orden || []).includes(id))];
    if (titulo !== undefined) p.titulo = String(titulo || "").trim().slice(0, 40) || null;
    if (Array.isArray(orden)) p.orden = [...orden.filter((id) => actual.includes(id)), ...actual.filter((id) => !orden.includes(id))];
    if (Array.isArray(modos)) {
      p.modos = modos.map((m, i) => ({ id: String(m.id || m.nombre || `modo${i}`).toLowerCase().replace(/[^\w-]/g, "").slice(0, 20) || `modo${i}`, nombre: String(m.nombre || m.id).slice(0, 24), cuando: m.cuando || {} }));
    }
    if (modo_manual !== undefined) p.modo_manual = modo_manual || null;
    writeFileSync(PANEL, JSON.stringify(p));
    this.emitir({ tipo: "creaciones" });
    return this.panel();
  }

  /**
   * Al abrir el panel: refresca en segundo plano las tarjetas que lo necesitan. Las que no gastan
   * suscripción, si llevan más de 10 min (o siempre, si se fuerza); las de Claude, solo a su hora.
   */
  refrescarPanel(forzar = false) {
    this.panelVisto = ahora();
    this.elegirPilas().catch(() => {});
    if (this.refrescando) return;
    const ids = this.panel().orden.flatMap((id) => { const c = this.obtener(id); return c?.plantilla === "pila" ? c.pila : [id]; });
    const toca = ids.map((id) => this.obtener(id)).filter((c) => {
      if (!c || c.pausado || !(c.origen?.fuente || c.origen?.comando)) return false;
      const hace = ahora() - (c.estado?.ultima || 0);
      const deIa = FUENTES[c.origen?.fuente]?.claude;
      return deIa ? hace >= c.origen.cada * 60_000 : forzar || hace >= 10 * 60_000;
    });
    if (!toca.length) return;
    this.refrescando = (async () => {
      for (const c of toca) await this.ejecutar(c.id).catch(() => {});
    })().finally(() => { this.refrescando = null; });
  }

  // ---------- Vistazo en la pantalla de bloqueo ----------

  /** Hasta 4 cifras del panel (las que elija Claude o las primeras que tengan un valor). */
  piezasVistazo(modo) {
    const aj = ajustesPanel().vistazo;
    const corto = (s, n) => { const t = String(s ?? "").trim(); return t.length > n ? `${t.slice(0, n - 1)}…` : t; };
    const panel = this.panel({ modo }).orden.flatMap((id) => { const c = this.obtener(id); return c?.plantilla === "pila" ? [c.pilaActual || c.pila?.[0]] : [id]; });
    const inicio = this.listar().filter((c) => c.tipo === "widget" && (c.lugar || "inicio") === "inicio").map((c) => c.id);
    const ids = aj.ids?.length ? aj.ids : [...panel, ...inicio];
    const piezas = [];
    for (const id of ids) {
      const c = this.obtener(id);
      const d = c?.datos;
      if (!d || c.pausado) continue;
      // Una cifra corta que se lea de un vistazo; si no cabe entera, esa tarjeta no sale (nunca se corta).
      let pieza = null;
      if (d.valor != null && String(d.valor).length && ["cifra", "progreso", "grafico"].includes(c.plantilla)) {
        const unidad = String(d.unidad || "");
        const valor = unidad === "°C" ? `${d.valor}°` : unidad === "%" ? `${d.valor} %` : unidad.length <= 3 && unidad ? `${d.valor} ${unidad}` : String(d.valor);
        pieza = { valor, etiqueta: corto(d.titulo || c.nombre, 12) };
      } else if (c.plantilla === "anillos") {
        // La del propio móvil ya está en la barra de estado: los cascos, el reloj…
        const e = (d.elementos || []).find((x) => x.valor != null && x.icono !== "movil");
        if (e) pieza = { valor: `${e.valor} %`, etiqueta: corto(e.texto, 12) };
      } else if (c.plantilla === "agenda" && /\b\d{1,2}:\d{2}$/.test(String(d.elementos?.[0]?.hora || ""))) {
        pieza = { valor: String(d.elementos[0].hora).match(/\d{1,2}:\d{2}$/)[0], etiqueta: corto(d.elementos[0].texto, 12) };
      }
      if (pieza && pieza.valor.length > 7) pieza = null;
      if (pieza?.valor && !piezas.some((p) => p.valor === pieza.valor && p.etiqueta === pieza.etiqueta)) piezas.push({ ...pieza, id });
      if (piezas.length >= 4) break;
    }
    return piezas;
  }

  /** Publica (o quita) el vistazo; solo si ha cambiado o cada media hora. */
  async vistazo() {
    const aj = ajustesPanel().vistazo;
    if (!aj.activo) {
      if (this.vistazoHuella !== "fuera") { await puente("vistazo_fin").catch(() => {}); this.vistazoHuella = "fuera"; }
      this.enVistazo = new Set();
      return;
    }
    const piezas = this.piezasVistazo(await this.modoActivo().catch(() => null));
    this.enVistazo = new Set(piezas.map((p) => p.id));
    const huella = JSON.stringify(piezas.map(({ valor, etiqueta }) => [valor, etiqueta]));
    if (huella === this.vistazoHuella && ahora() - (this.vistazoHace || 0) < 30 * 60_000) return;
    if (!piezas.length) await puente("vistazo_fin").catch(() => {});
    else await puente("vistazo", { piezas: JSON.stringify(piezas.map(({ valor, etiqueta }) => ({ valor, etiqueta }))) });
    this.vistazoHuella = huella;
    this.vistazoHace = ahora();
  }

  // ---------- Segundo plano ----------

  arrancar() {
    setTimeout(() => this.tick(), 10_000);
    setInterval(() => this.tick(), 60_000).unref?.();
  }

  async tick() {
    if (this.ocupado) return;
    this.ocupado = true;
    try {
      // Rutinas a una hora: «HH:MM» en los días indicados (1 lunes … 7 domingo; todos si no hay).
      const f = new Date();
      const hm = `${f.getHours()}:${String(f.getMinutes()).padStart(2, "0")}`;
      const dia = f.getDay() || 7;
      for (const c of this.listar()) {
        const d = c.disparador;
        if (c.tipo !== "rutina" || c.pausado || d?.tipo !== "hora") continue;
        const [h, m] = d.hora.split(":").map(Number);
        if (`${h}:${String(m).padStart(2, "0")}` === hm && (!d.dias?.length || d.dias.includes(dia)) && ahora() - (c.estado?.ultima || 0) > 90_000) {
          await this.ejecutarRutina(c, `Son las ${d.hora}.`).catch(() => {});
        }
      }
      // Ahorro: lo del panel solo se refresca si se ha visto hace poco (al abrirlo se pone al día),
      // salvo lo que puede ponerse «en directo» (partidos, paquetes, vuelos) y las tarjetas
      // automáticas. Con la batería baja y sin cargar, todo va tres veces más espaciado.
      const bat = await this.bateria();
      const factor = bat.nivel != null && bat.nivel < 20 && !bat.carga ? 3 : 1;
      const panelVisible = ahora() - (this.panelVisto || 0) < 5 * 60_000;
      for (const c of this.listar()) {
        const cada = c.tipo === "agente" ? c.cada : c.origen?.comando || c.origen?.fuente ? c.origen.cada : null;
        if (!cada || c.pausado) continue;
        const enPanel = c.tipo === "widget" && (c.lugar === "panel" || c.lugar === "pila");
        const vigilado = c.auto || ["deportes", "paquetes", "vuelos"].includes(c.origen?.fuente) || c.estado?.en_directo || this.enVistazo?.has(c.id);
        if (enPanel && !panelVisible && !vigilado) continue;
        if (ahora() - (c.estado?.ultima || 0) >= cada * factor * 60_000) await this.ejecutar(c.id).catch(() => {});
      }
      if (panelVisible && ahora() - (this.pilasHace || 0) > 5 * 60_000) { this.pilasHace = ahora(); await this.elegirPilas().catch(() => {}); }
      if (ahora() - (this.vigiaHace || 0) > 15 * 60_000) { this.vigiaHace = ahora(); await this.vigia().catch((e) => console.error("[vigía]", e.message)); }
      await this.vistazo().catch((e) => console.error("[vistazo]", e.message));
    } finally {
      this.ocupado = false;
    }
  }

  // ---------- Rutinas ----------

  /**
   * Algo ha pasado en el móvil (lo cuenta la app): se ejecutan las rutinas a las que les toca.
   * evento: {tipo: wifi|bluetooth|carga, conectado, red|dispositivo} | {tipo: bateria, nivel}
   *       | {tipo: lugar, id, al} | {tipo: notificacion, app, paquete, titulo, texto}
   */
  async disparo(e) {
    const contiene = (a, b) => String(a || "").toLowerCase().includes(String(b || "").toLowerCase());
    const hechas = [];
    for (const c of this.listar()) {
      const d = c.disparador;
      if (c.tipo !== "rutina" || c.pausado || d?.tipo !== e.tipo) continue;
      let toca = false;
      let contexto = "";
      if (e.tipo === "wifi") { toca = d.conectado === !!e.conectado && contiene(e.red, d.red); contexto = `${e.conectado ? "Conectado a" : "Desconectado de"} la Wi-Fi «${e.red}».`; }
      if (e.tipo === "bluetooth") { toca = d.conectado === !!e.conectado && contiene(e.dispositivo, d.dispositivo); contexto = `${e.conectado ? "Conectado" : "Desconectado"} por Bluetooth: «${e.dispositivo}».`; }
      if (e.tipo === "carga") { toca = d.conectado === !!e.conectado; contexto = e.conectado ? "El móvil se ha puesto a cargar." : "El móvil ha dejado de cargar."; }
      // Android avisa de «entrar» nada más poner la alerta si ya estás dentro: un recordatorio
      // recién creado no salta por eso (el usuario lo pidió para la próxima vez que llegue).
      if (e.tipo === "lugar" && c.una_vez && e.al === "entrar" && ahora() - c.creado < 120_000) continue;
      if (e.tipo === "lugar") { toca = e.id === c.id && (d.al || "entrar") === e.al; contexto = `${e.al === "salir" ? "Saliendo de" : "Llegando a"} ${d.nombre_lugar || "el lugar de la rutina"}.`; }
      if (e.tipo === "notificacion") {
        toca = (!d.app || contiene(e.app, d.app) || contiene(e.paquete, d.app)) && (!d.contiene || contiene(`${e.titulo} ${e.texto}`, d.contiene));
        contexto = `Notificación de ${e.app}: ${e.titulo}: ${e.texto}`;
      }
      if (e.tipo === "bateria") {
        // Solo al bajar del umbral (una vez); se rearma al volver a subir por encima.
        const bajo = Number(e.nivel) <= d.por_debajo;
        const antes = c.estado?.bajo;
        c.estado = { ...c.estado, bajo };
        this.guardar(c);
        toca = bajo && !antes;
        contexto = `Batería al ${e.nivel} %.`;
      }
      if (toca && ahora() - (c.estado?.ultima || 0) > 60_000) {
        await this.ejecutarRutina(c, contexto).catch(() => {});
        hechas.push(c.nombre);
        // Recordatorios: una sola vez y fuera (se avisó; la rutina ya no hace falta).
        if (c.una_vez) this.borrar(c.id);
      }
    }
    return hechas;
  }

  async ejecutarRutina(c, contexto = "") {
    const estado = { ...c.estado, ultima: ahora(), error: null, ejecuciones: (c.estado?.ejecuciones || 0) + 1 };
    const hecho = [];
    try {
      for (const a of c.acciones) {
        if (a.comando) hecho.push((await this.comando(a.comando)).trim().slice(0, 200));
        if (a.abrir_app) {
          const paquete = await resolverApp(a.abrir_app);
          if (!paquete) throw new Error(`No encuentro la app «${a.abrir_app}»`);
          await android(`monkey -p ${q(paquete)} -c android.intent.category.LAUNCHER 1`);
          hecho.push(`abierta ${a.abrir_app}`);
        }
        if (a.aviso) await notificar(c, a.aviso);
        if (a.claude) {
          const r = await preguntarRapido(`${a.claude}\n\nQué ha pasado: ${contexto}${hecho.length ? `\nYa hecho: ${hecho.join("; ")}` : ""}`);
          if (r && !/^\s*(nada|ok)\.?\s*$/i.test(r)) await notificar(c, r);
          hecho.push(r);
        }
      }
      estado.resultado = hecho.filter(Boolean).join(" · ").slice(0, 300) || "Hecho";
    } catch (e) {
      estado.error = String(e.message || e).slice(0, 300);
    }
    registrar({
      origen: "rutina", herramienta: "rutina", frase: `Rutina «${c.nombre}»`,
      detalle: [contexto, estado.resultado].filter(Boolean).join(" → ").slice(0, 300), error: estado.error || undefined,
    });
    c.estado = estado;
    return this.guardar(c);
  }

  /** Comando con prioridad baja y 30 s como mucho. */
  async comando(cmd) {
    const r = await termux(`nice -n 10 bash -c ${q(cmd)}`, { timeout: 30000 });
    if (!r.ok) throw new Error(r.salida.slice(0, 300));
    return r.salida.slice(0, 4000);
  }

  async ejecutar(id) {
    const c = this.obtener(id);
    if (!c) throw new Error(`No existe la creación ${id}`);
    if (c.tipo === "rutina") return this.ejecutarRutina(c, "La ha ejecutado el usuario a mano.");
    const estado = { ...c.estado, ultima: ahora(), error: null };
    try {
      if (c.tipo === "widget" && c.origen?.fuente) {
        const nuevos = await leerFuente(c); // puede guardar cosas suyas en c.estado (huella, historial…)
        const { ultima, error, ...propio } = c.estado || {};
        Object.assign(estado, propio, { ultima: estado.ultima, error: null });
        if (nuevos) {
          c.datos = { ...c.datos, ...Object.fromEntries(Object.entries(nuevos).filter(([, v]) => v !== undefined)) };
          if ("texto" in nuevos && nuevos.texto === undefined) delete c.datos.texto;
          if (!("texto" in nuevos) && Array.isArray(nuevos.elementos) && nuevos.elementos.length) delete c.datos.texto;
        }
      } else if (c.tipo === "widget" && c.origen?.comando) {
        c.datos = { ...c.datos, ...aDatos(await this.comando(c.origen.comando)) };
      } else if (c.tipo === "agente") {
        const salida = c.comando ? await this.comando(c.comando) : "";
        const h = huella(salida);
        // Sin cambios en lo que mira, no se despierta a Claude (ni se gasta suscripción).
        let resultado = c.prompt ? estado.resultado : salida.trim();
        if (c.prompt && (h !== estado.huella || !estado.resultado)) {
          resultado = await preguntarRapido(`${c.prompt}${salida ? `\n\nDatos (salida de \`${c.comando}\`):\n${salida}` : ""}`);
          if (c.notificar && resultado && !/^\s*(nada|sin novedad|ok)\.?\s*$/i.test(resultado)) await notificar(c, resultado);
        } else if (!c.prompt && c.notificar && h !== estado.huella && salida.trim()) {
          await notificar(c, salida.trim().slice(0, 300));
        }
        estado.huella = h;
        estado.resultado = resultado;
        estado.ejecuciones = (estado.ejecuciones || 0) + 1;
        if (c.widget && resultado) {
          const w = this.obtener(c.widget);
          if (w) this.guardar({ ...w, datos: { ...w.datos, ...aDatos(resultado) }, estado: { ...w.estado, ultima: ahora() } });
        }
      }
    } catch (e) {
      estado.error = String(e.message || e).slice(0, 300);
    }
    c.estado = estado;
    if (c.auto) {
      c.auto = { ...c.auto, visible: this.relevante(c) };
      // Las automáticas de lista crecen y menguan con lo que traen: con una o dos filas, mediana
      // (una tarjeta 2×2 casi vacía no se ve bien); con más, grande.
      if ((c.plantilla === "agenda" || c.plantilla === "filas") && !c.auto.tamanoFijo) c.tamano = (c.datos?.elementos?.length || 0) > (c.plantilla === "filas" ? 3 : 2) ? "grande" : "mediano";
    }
    const hecha = this.guardar(c);
    if (c.tipo === "widget") this.enDirecto(hecha).catch(() => {});
    return hecha;
  }

  /** Batería (ligera: una orden cada 5 min como mucho). */
  async bateria() {
    if (this.bat && ahora() - this.bat.cuando < 5 * 60_000) return this.bat;
    const s = await android("dumpsys battery").catch(() => "");
    this.bat = { cuando: ahora(), nivel: Number(s.match(/level: (\d+)/)?.[1]) || null, carga: /(AC|USB|Wireless) powered: true/.test(s) };
    return this.bat;
  }

  // ---------- Tarjetas automáticas ----------

  /**
   * Tarjetas que aparecen solas cuando hacen falta y se van después: paquetes en camino, vuelos
   * cercanos, partidos de tus equipos, facturas que vencen. Son widgets del panel marcados `auto`
   * que existen siempre pero solo se enseñan cuando son relevantes. Si el usuario ya tiene una
   * tarjeta de lo mismo, no se duplica.
   */
  async vigia() {
    const aj = ajustesPanel().automaticas;
    const todas = this.listar().filter((c) => c.tipo === "widget");
    const autos = todas.filter((c) => c.auto);
    const deseadas = [];
    if (aj.activo) {
      for (const tipo of ["paquetes", "vuelos", "facturas", "viaje"]) if (aj.tipos.includes(tipo)) deseadas.push({ clave: tipo, fuente: tipo });
      if (aj.tipos.includes("deportes")) for (const e of aj.equipos || []) deseadas.push({ clave: `deportes:${String(e.equipo).toLowerCase()}`, fuente: "deportes", opciones: { equipo: e.equipo, liga: e.liga || "esp.1" } });
    }
    // Las que sobran (desactivadas o equipo quitado), fuera.
    for (const c of autos) if (!deseadas.some((d) => d.clave === c.auto.clave)) this.borrar(c.id);
    for (const d of deseadas) {
      const propia = todas.some((c) => !c.auto && c.lugar === "panel" && c.origen?.fuente === d.fuente && (d.fuente !== "deportes" || String(c.origen.equipo || "").toLowerCase() === String(d.opciones.equipo).toLowerCase()));
      const existente = autos.find((c) => c.auto.clave === d.clave);
      if (propia) { if (existente) this.borrar(existente.id); continue; }
      if (!existente) {
        const c = this.validar({
          tipo: "widget", nombre: FUENTES[d.fuente].titulo + (d.opciones?.equipo ? ` · ${d.opciones.equipo}` : ""), lugar: "panel",
          origen: { fuente: d.fuente, ...(d.opciones || {}) }, auto: { clave: d.clave, visible: false },
          id: randomUUID().slice(0, 8), creado: ahora(),
        });
        this.guardar(c);
        await this.ejecutar(c.id).catch(() => {});
      }
    }
  }

  /** ¿Merece salir ahora esta tarjeta automática? */
  relevante(c) {
    const d = c.datos || {};
    switch (c.origen?.fuente) {
      case "paquetes": return (d.pendientes || 0) > 0;
      case "facturas": return (d.pendientes || 0) > 0;
      case "vuelos": return !!d.vivo || (d.elementos || []).some((e) => /Hoy|Mañana/.test(e.hora || ""));
      case "viaje": return d.en_dias != null && d.en_dias <= 3;
      // Partido: en juego, hoy o mañana, o acabado hace menos de 5 horas.
      case "deportes": return !!d.en_juego || /^(Hoy|Mañana)/.test(d.estado || "") || (d.estado === "Final" && d.inicio && ahora() - d.inicio < 5 * 3600_000);
      default: return false;
    }
  }

  // ---------- Actividades en directo ----------

  /**
   * Si los datos de un widget traen `vivo` (partido en juego, paquete en reparto, vuelo de hoy…),
   * se publica como actividad en directo (Android 16: chip en la barra de estado y arriba en el
   * bloqueo); cuando deja de traerlo, se quita.
   */
  async enDirecto(c) {
    const v = c.datos?.vivo;
    const antes = c.estado?.en_directo;
    if (v && (v.titulo || v.chip)) {
      const firma = JSON.stringify(v);
      if (firma === antes) return;
      await puente("en_directo", {
        id: c.id, titulo: v.titulo || c.nombre, texto: v.texto || "", chip: v.chip || "",
        progreso: v.progreso != null ? String(v.progreso) : "", acento: c.acento || "naranja",
      });
      this.anotarEstado(c.id, { en_directo: firma });
    } else if (antes) {
      await puente("en_directo_fin", { id: c.id }).catch(() => {});
      this.anotarEstado(c.id, { en_directo: null });
    }
  }

  /** Cambia campos del estado sin tocar `actualizado` ni avisar a la app. */
  anotarEstado(id, campos) {
    const c = this.obtener(id);
    if (!c) return;
    c.estado = { ...c.estado, ...campos };
    writeFileSync(join(DIR, `${c.id}.json`), JSON.stringify(c, null, 1));
  }

  // ---------- Acciones de los widgets ----------

  /**
   * Un botón o una fila de un widget se ha tocado. Solo se ejecutan acciones que estén en la ficha
   * (las puso Claude al crearlo): la app dice cuál, no qué hacer. Devuelve {ok, aviso?}.
   */
  async accion(id, { boton, elemento } = {}) {
    const c = this.obtener(id);
    if (!c) throw new Error("No existe");
    const a = boton != null ? c.botones?.[boton]?.accion ?? c.datos?.botones?.[boton]?.accion : c.datos?.elementos?.[elemento]?.accion;
    const fila = elemento != null ? c.datos?.elementos?.[elemento] : null;
    if (!a && !(fila && c.marcable)) throw new Error("Esa acción no existe");
    const tipo = a?.tipo || "tachar";
    let aviso = null;
    if (tipo === "tachar") {
      fila.hecho = !fila.hecho;
      this.guardar(c);
    } else if (tipo === "multimedia") {
      const orden = { play_pause: "play-pause", siguiente: "next", anterior: "previous" }[a.orden];
      await android(`cmd media_session dispatch ${orden}`);
    } else if (tipo === "casa") {
      await casaAccion(a.entidad, a.servicio || "toggle");
      setTimeout(() => this.ejecutar(id).catch(() => {}), 1200);
    } else if (tipo === "familia_tachar") {
      // La lista compartida de la familia: se tacha para todos (cifrado) y se vuelve a pintar.
      const { tachar } = await import("./familia.mjs");
      await tachar(a.item, !fila?.hecho);
      await this.ejecutar(id);
    } else if (tipo === "comando") {
      aviso = (await this.comando(a.comando)).trim().split("\n")[0].slice(0, 140) || "Hecho";
    } else if (tipo === "claude") {
      if (!gastarIa("botón de widget")) throw new Error("Hoy ya se ha usado todo el presupuesto de la IA del panel");
      aviso = await preguntarRapido(`${a.prompt}\n\nDatos del widget «${c.nombre}»: ${JSON.stringify(c.datos).slice(0, 2000)}`, {
        sistema: "Respondes desde un botón de un widget del móvil: una o dos frases cortas, en el idioma del usuario, sin formato.",
      });
    } else if (tipo === "refrescar") {
      await this.ejecutar(id);
    } else if (tipo === "app") {
      const paquete = await resolverApp(a.app);
      if (!paquete) throw new Error(`No encuentro la app «${a.app}»`);
      await android(`monkey -p ${q(paquete)} -c android.intent.category.LAUNCHER 1`);
    }
    registrar({ origen: "widget", herramienta: "accion", frase: `«${c.nombre}»: ${tipo}${a?.texto ? ` (${a.texto})` : ""}`, detalle: aviso || undefined });
    return { ok: true, aviso };
  }

  // ---------- Pilas inteligentes ----------

  /**
   * Qué widget enseña cada pila ahora: la primera regla que se cumpla; si ninguna y hay criterio,
   * Claude (Haiku) elige con el contexto del móvil (como mucho cada 30 min y con presupuesto).
   */
  async elegirPilas({ forzar = false } = {}) {
    const pilas = this.listar().filter((c) => c.tipo === "widget" && c.plantilla === "pila");
    if (!pilas.length) return;
    const ctx = await contexto();
    for (const p of pilas) {
      let elegido = (p.reglas || []).find((r) => cumple(r.cuando, ctx))?.mostrar;
      let porque = elegido ? "regla" : null;
      if (!elegido && p.criterio) {
        const reciente = ahora() - (p.estado?.eleccion_claude || 0) < 30 * 60_000;
        if (reciente && !forzar) continue;
        if (!gastarIa("pila")) continue;
        const hijos = p.pila.map((id) => this.obtener(id)).filter(Boolean);
        const r = await preguntarRapido(
          `Ahora: ${contextoTexto(ctx)}.\nCriterio del usuario: ${p.criterio}\nWidgets:\n${hijos.map((h) => `- ${h.id}: ${h.nombre} (${JSON.stringify(h.datos).slice(0, 160)})`).join("\n")}`,
          { sistema: "Eliges qué widget de la pila conviene enseñar ahora mismo. Responde SOLO con el id." }
        ).catch(() => "");
        elegido = hijos.find((h) => r.includes(h.id))?.id;
        porque = "claude";
        this.anotarEstado(p.id, { eleccion_claude: ahora() });
      }
      elegido ||= p.estado?.actual && p.pila.includes(p.estado.actual) ? p.estado.actual : p.pila[0];
      if (elegido !== p.estado?.actual) {
        const f = this.obtener(p.id);
        f.estado = { ...f.estado, actual: elegido, porque };
        this.guardar(f);
      }
    }
  }

  // ---------- Modos del panel ----------

  /** El modo del panel que toca ahora (el elegido a mano manda; si no, el primero cuyas condiciones se cumplan). */
  async modoActivo() {
    const p = this.leerPanel();
    if (!p.modos?.length) return null;
    if (p.modo_manual && p.modos.some((m) => m.id === p.modo_manual)) return p.modo_manual;
    const ctx = await contexto();
    return p.modos.find((m) => m.cuando && Object.keys(m.cuando).length && cumple(m.cuando, ctx))?.id || null;
  }

  // ---------- Historial del panel ----------

  /** Foto del panel (sus tarjetas, pilas y ajustes) antes de cambiarlo: para «vuelve a como estaba». */
  fotoPanel(motivo) {
    // La foto es del estado de ANTES del cambio; varios cambios seguidos (Claude rehaciendo el
    // panel) cuentan como uno solo: se guarda la primera foto de la ráfaga.
    if (this.fotoPendiente) return;
    const fichas = this.listar().filter((c) => c.tipo === "widget" && (c.lugar === "panel" || c.lugar === "pila") && !c.auto);
    this.fotoPendiente = { cuando: ahora(), motivo, panel: this.leerPanel(), fichas: fichas.map(({ estado, ...f }) => f) };
    setTimeout(() => {
      const foto = this.fotoPendiente;
      this.fotoPendiente = null;
      mkdirSync(HISTORIAL, { recursive: true });
      const ultima = readdirSync(HISTORIAL).sort().at(-1);
      const firma = JSON.stringify([foto.panel, foto.fichas]);
      try { if (ultima && JSON.stringify((({ panel, fichas }) => [panel, fichas])(JSON.parse(readFileSync(join(HISTORIAL, ultima), "utf8")))) === firma) return; } catch {}
      writeFileSync(join(HISTORIAL, `${foto.cuando}.json`), JSON.stringify(foto));
      for (const viejo of readdirSync(HISTORIAL).sort().slice(0, -60)) rmSync(join(HISTORIAL, viejo), { force: true });
    }, 8000);
  }

  historialPanel() {
    try {
      return readdirSync(HISTORIAL).sort().reverse().map((f) => {
        const h = JSON.parse(readFileSync(join(HISTORIAL, f), "utf8"));
        return { id: f.replace(".json", ""), cuando: h.cuando, motivo: h.motivo, tarjetas: h.fichas.filter((x) => x.lugar === "panel").map((x) => x.nombre) };
      });
    } catch {
      return [];
    }
  }

  /** Deja el panel como en la foto `id` (o la más cercana anterior a `hasta`, en ms). */
  restaurarPanel({ id, hasta } = {}) {
    const lista = this.historialPanel();
    const elegida = id ? lista.find((h) => h.id === String(id)) : lista.find((h) => h.cuando <= hasta);
    if (!elegida) throw new Error("No hay una foto del panel de entonces");
    this.fotoPanel("antes de restaurar");
    const foto = JSON.parse(readFileSync(join(HISTORIAL, `${elegida.id}.json`), "utf8"));
    for (const c of this.listar()) if (c.tipo === "widget" && (c.lugar === "panel" || c.lugar === "pila")) rmSync(join(DIR, `${c.id}.json`), { force: true });
    for (const f of foto.fichas) writeFileSync(join(DIR, `${f.id}.json`), JSON.stringify({ ...f, estado: {}, actualizado: ahora() }, null, 1));
    writeFileSync(PANEL, JSON.stringify(foto.panel));
    this.emitir({ tipo: "creaciones" });
    setTimeout(() => this.refrescarPanel(true), 500);
    return elegida;
  }
}

/** La salida de un comando o de Claude: JSON con campos de `datos` o texto suelto. */
function aDatos(s) {
  const t = String(s).trim();
  try {
    const v = JSON.parse(t);
    if (v && typeof v === "object" && !Array.isArray(v)) return v;
  } catch {}
  return { texto: t.slice(0, 600) };
}

/** Aviso del agente: lo publica la app Claude (con su icono), no Termux. */
async function notificar(c, texto) {
  if (c.tipo === "agente") registrar({ origen: "agente", herramienta: "agente", frase: `Agente «${c.nombre}» te avisó`, detalle: texto.slice(0, 300) });
  await puente("notificar", { id: `creacion-${c.id}`, titulo: c.nombre, texto: texto.slice(0, 500) });
}

/**
 * Pregunta corta a Claude para agentes y mini-apps: Haiku, esfuerzo bajo, sin herramientas ni
 * historial. Unos cientos de tokens por llamada.
 */
export function preguntarRapido(prompt, { sistema } = {}) {
  // Con la IA que esté elegida (Claude con Haiku, ChatGPT, Gemini, una API o el modelo local).
  return rapidoIa(prompt, sistema || `Eres un agente de ${SISTEMA} que trabaja en segundo plano en el móvil del usuario. Responde en una o dos frases, en el idioma del usuario. Si no hay nada que contar, responde solo «nada».`);
}

// ---------- Mini-apps ----------

const datosApp = (id) => join(DIR, `${String(id).replace(/[^\w-]/g, "")}.datos.json`);

/** Datos guardados de una mini-app (lo que guarda con sios.guardar). */
export function leerDatosApp(id) {
  try {
    return JSON.parse(readFileSync(datosApp(id), "utf8"));
  } catch {
    return {};
  }
}

export function guardarDatosApp(id, datos) {
  const s = JSON.stringify(datos ?? {});
  if (s.length > 512 * 1024) throw new Error("Demasiados datos (máx. 512 KB)");
  writeFileSync(datosApp(id), s);
}

/**
 * Quita lo que podría romper el diseño común: hojas y atributos de estilo, recursos externos y
 * títulos propios (la cabecera con el nombre la pone el kit).
 */
export function limpiarHtml(html) {
  return String(html)
    .replace(/<style[\s\S]*?<\/style>/gi, "")
    .replace(/<link\b[^>]*>/gi, "")
    .replace(/<script\b[^>]*\bsrc=[^>]*>\s*<\/script>/gi, "")
    .replace(/\sstyle\s*=\s*("[^"]*"|'[^']*')/gi, "")
    .replace(/<h1\b[^>]*>[\s\S]*?<\/h1>/gi, "");
}

// Widgets de diseño libre (plantilla html): a diferencia de las mini-apps, Claude pone su propio
// <style> y <script>. Parten del kit (variables, Montserrat, componentes) con fondo transparente
// y sin márgenes: la tarjeta la pone la app. Nada de scripts ni conexiones de fuera (CSP).
// Marco que el diseño de Claude no puede saltarse: márgenes interiores, letra de SIOS, nada se
// sale de la tarjeta y, si algo no cabe, se funde abajo en vez de cortarse.
const WIDGET_CSS = `
html, body { background: transparent !important; margin: 0 !important; padding: 0 !important; }
/* El marco del sistema: en la rejilla, fijo al tamaño de la celda; en el inicio, crece con el contenido. */
#sios-marco { box-sizing: border-box !important; padding: 16px !important; overflow: hidden !important;
  font-size: 14px; line-height: 1.35; color: var(--texto); }
body.fijo { overflow: hidden !important; }
body.fijo #sios-marco { position: fixed !important; inset: 0 !important; width: auto !important; height: auto !important; }
body.tamano-pequeno #sios-marco { padding: 14px !important; font-size: 13px; }
#sios-marco, #sios-marco * { font-family: "Montserrat", system-ui, sans-serif !important; max-width: 100%; overflow-wrap: anywhere; }
#sios-marco img, #sios-marco svg, #sios-marco canvas, #sios-marco video { max-width: 100%; }
/* Si algo no cabe, se funde abajo en vez de cortarse (y se le avisa a Claude). */
#sios-marco.desborda { -webkit-mask-image: linear-gradient(#000 calc(100% - 28px), transparent); mask-image: linear-gradient(#000 calc(100% - 28px), transparent); }
.tarjeta { animation: none; }
`;

const WIDGET_JS = `
(() => {
  const oyentes = [];
  Object.assign(window.sios, {
    datos: window.__datosWidget || {},
    /** pequeno (1×1), mediano (2×1) o grande (2×2): también en la clase del body (tamano-…). */
    tamano: window.__tamano,
    /** fn(datos) cada vez que llegan datos nuevos de su origen (fuente, comando o agente). */
    alCambiar(fn) { oyentes.push(fn); },
    _nuevos(d) { sios.datos = d || {}; oyentes.forEach((f) => { try { f(sios.datos); } catch (e) { console.error(e); } }); },
    /** Abre un enlace en el navegador (o una app con un enlace suyo). */
    abrir(url) { location.href = "sios-abrir:" + encodeURIComponent(url); },
    /** Vuelve a pedir los datos a su origen ya. */
    refrescar() { return fetch("/creaciones/__ID__/ejecutar", { method: "POST" }).catch(() => {}); },
  });
  // La app ajusta la tarjeta al alto del contenido (en el inicio) y comprueba que el diseño cabe:
  // si se sale, se funde abajo y se le avisa a Claude para que lo arregle.
  let informado = "";
  const medir = () => {
    const m = document.getElementById("sios-marco");
    if (!m) return;
    const fijo = document.body.classList.contains("fijo");
    m.classList.remove("desborda");
    const desborda = fijo && (m.scrollHeight > m.clientHeight + 2 || m.scrollWidth > m.clientWidth + 2);
    m.classList.toggle("desborda", desborda);
    if (!fijo) window.SiosWidget?.alto(Math.ceil(m.getBoundingClientRect().height));
    const info = JSON.stringify({ desborda, contenido_alto: m.scrollHeight, contenido_ancho: m.scrollWidth, alto: m.clientHeight, ancho: m.clientWidth, tamano: sios.tamano });
    if (info !== informado) { informado = info; window.SiosWidget?.medida(info); }
  };
  addEventListener("load", () => {
    const m = document.getElementById("sios-marco");
    new ResizeObserver(medir).observe(m);
    new MutationObserver(() => requestAnimationFrame(medir)).observe(m, { childList: true, subtree: true, characterData: true });
    (document.fonts?.ready || Promise.resolve()).then(() => setTimeout(medir, 250));
  });
})();
`;

/** tamano: el de la celda de la rejilla (marco fijo); sin él, en el inicio, crece con el contenido. */
export function paginaWidget(c, tamanoCelda, tema = {}) {
  const tamano = TAMANOS.includes(tamanoCelda) ? tamanoCelda : c.tamano || "mediano";
  const html = String(c.html || "").replace(/<link\b[^>]*>/gi, "").replace(/<script\b[^>]*\bsrc=[^>]*>\s*<\/script>/gi, "");
  return `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<meta http-equiv="Content-Security-Policy" content="default-src 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'; connect-src 'self'; font-src 'self'">
<style>${estiloTema(c.acento, tema)}${WIDGET_CSS}</style>
<script>window.__datos = ${JSON.stringify(leerDatosApp(c.id)).replace(/</g, "\\u003c")};window.__datosWidget = ${JSON.stringify(c.datos || {}).replace(/</g, "\\u003c")};window.__tamano = ${JSON.stringify(TAMANOS.includes(tamano) ? tamano : "mediano")};${KIT_JS.replaceAll("__ID__", c.id)}${WIDGET_JS.replaceAll("__ID__", c.id)}</script></head>
<body class="tamano-${tamano}${TAMANOS.includes(tamanoCelda) ? " fijo" : ""}"><div id="sios-marco">${html}</div><div id="claude-aviso" class="aviso" role="status"></div></body></html>`;
}

/** Página de una mini-app: cabecera común, su contenido y el kit de diseño de SIOS. */
export function paginaApp(c, tema = {}) {
  return `<!doctype html><html lang="es"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
<title>${escapar(c.nombre)}</title><style>${estiloTema(c.acento, tema)}</style>
<script>window.__datos = ${JSON.stringify(leerDatosApp(c.id)).replace(/</g, "\\u003c")};${KIT_JS.replaceAll("__ID__", c.id)}</script></head>
<body><header class="cabecera"><h1>${escapar(c.nombre)}</h1></header><main class="pila">${limpiarHtml(c.html)}</main>
<div id="claude-aviso" class="aviso" role="status"></div></body></html>`;
}

const escapar = (s) => String(s).replace(/[&<>"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[ch]);

const COLOR = { naranja: "#D97757", verde: "#7CBF8E", azul: "#7AA7E0", amarillo: "#E8C46A", rojo: "#E0816F", gris: "#C2C0B6", lila: "#A88FDB" };
// En claro, los acentos algo más oscuros para que contrasten igual sobre el crema.
const COLOR_CLARO = { naranja: "#C6613F", verde: "#4E8B62", azul: "#3F6FB5", amarillo: "#A8801F", rojo: "#C64545", gris: "#73726C", lila: "#7656B8" };

/**
 * Tema de una página (mini-app o widget libre): claro u oscuro y el acento, como la app. El acento
 * «naranja» de una creación sigue al acento del sistema que haya elegido el usuario.
 */
function estiloTema(acentoCreacion, { tema, acento } = {}) {
  const claro = tema === "claro";
  const nombre = acentoCreacion === "naranja" && COLOR[acento] ? acento : acentoCreacion;
  const color = (claro ? COLOR_CLARO : COLOR)[nombre] || (claro ? COLOR_CLARO : COLOR).naranja;
  const claroCss = claro
    ? `:root { --fondo: #FAF9F5; --superficie: #FFFFFF; --superficie-2: #F0EEE6; --borde: #E3E1D7; --texto: #141413; --texto-2: #3D3D3A; --texto-3: #73726C; --rojo: #C64545; color-scheme: light; }`
    : "";
  return KIT_CSS.replace("--acento: #D97757", `--acento: ${color}`) + claroCss;
}

// Kit de diseño de las mini-apps: paleta y fuente de SIOS, un solo tamaño de control (48 px)
// y componentes cerrados. Claude no puede añadir estilos: así todas las apps se ven igual de cuidadas.
export const KIT_CSS = `
@font-face { font-family: "Montserrat"; src: url(/montserrat.ttf); font-weight: 100 900; }
:root { --fondo: #262624; --superficie: #30302E; --superficie-2: #3D3D3A; --borde: #3E3D3A;
  --texto: #FAF9F5; --texto-2: #C2C0B6; --texto-3: #9C9A92; --rojo: #E0816F; --acento: #D97757;
  --radio: 16px; --radio-control: 12px; --alto: 48px;
  --muelle: cubic-bezier(0.34, 1.56, 0.64, 1); --suave: cubic-bezier(0.2, 0.8, 0.2, 1); color-scheme: dark; }
* { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
html, body { margin: 0; background: var(--fondo); color: var(--texto); font: 400 15px/1.5 "Montserrat", system-ui, sans-serif; }
body { padding: 8px 20px 32px; -webkit-user-select: none; user-select: none; }
.cabecera h1 { font-size: 26px; font-weight: 600; letter-spacing: -0.4px; margin: 12px 0 20px; }
.pila { display: flex; flex-direction: column; gap: 12px; }
h2 { font-size: 12px; font-weight: 600; letter-spacing: .8px; text-transform: uppercase; color: var(--texto-3); margin: 12px 0 0; }
h3 { font-size: 16px; font-weight: 600; margin: 0; }
p { color: var(--texto-2); margin: 0; }
.suave { color: var(--texto-3); font-size: 13px; }
.etiqueta { font-size: 12px; font-weight: 600; letter-spacing: .8px; text-transform: uppercase; color: var(--texto-3); }
.grande { font-size: 44px; font-weight: 300; letter-spacing: -1.5px; line-height: 1.1; }
.acento { color: var(--acento); }
.tarjeta { background: var(--superficie); border: 1px solid var(--borde); border-radius: var(--radio); padding: 16px;
  display: flex; flex-direction: column; gap: 10px; animation: entrar .45s var(--muelle) both; }
/* Filas: los controles se reparten el ancho por igual; lo que lleva .crece ocupa el resto. */
.fila, .acciones { display: flex; gap: 8px; align-items: center; }
.fila > *, .acciones > * { flex: 1 1 0; min-width: 0; }
.fila > .crece { flex: 1 1 auto; }
.fila > .crece ~ .boton, .fila > .boton:has(~ .crece) { flex: 0 0 auto; width: auto; min-width: 104px; }
/* Botón: un único tamaño en todas las apps. */
.boton { appearance: none; border: 0; height: var(--alto); padding: 0 18px; border-radius: var(--radio-control);
  font-family: inherit; font-size: 15px; font-weight: 600; line-height: 1; background: var(--acento); color: #fff; width: 100%;
  display: inline-flex; align-items: center; justify-content: center; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  transition: transform .3s var(--muelle), opacity .2s; }
.boton:active { transform: scale(.96); }
.boton:disabled { opacity: .45; }
.boton.secundario { background: var(--superficie-2); color: var(--texto); }
.boton.peligro { background: var(--superficie-2); color: var(--rojo); }
.campo { width: 100%; height: var(--alto); background: var(--superficie); color: var(--texto); border: 1px solid var(--borde);
  border-radius: var(--radio-control); padding: 0 14px; font: inherit; outline: none; transition: border-color .2s; -webkit-user-select: text; user-select: text; }
textarea.campo { height: auto; min-height: 112px; padding: 12px 14px; resize: none; }
.campo:focus { border-color: var(--acento); }
/* Lista: filas de 52 px dentro de una tarjeta; .hecho las tacha. */
.lista { background: var(--superficie); border: 1px solid var(--borde); border-radius: var(--radio); overflow: hidden; }
.lista:empty { display: none; }
.item { min-height: 52px; padding: 12px 16px; display: flex; align-items: center; gap: 12px; border-bottom: 1px solid var(--borde);
  transition: background .2s, color .2s; animation: entrar .4s var(--muelle) both; }
.item:last-child { border-bottom: 0; }
.item:active { background: var(--superficie-2); }
.item > .crece, .item > span:first-child { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.item .detalle { color: var(--texto-3); font-size: 13px; }
.item.hecho { color: var(--texto-3); text-decoration: line-through; }
/* Tarjeta solo con texto (p. ej. una respuesta de Claude): respeta los saltos de línea. */
.tarjeta:not(:has(*)) { white-space: pre-line; }
.vacio { color: var(--texto-3); text-align: center; padding: 28px 16px; }
.chip { display: inline-flex; align-items: center; height: 28px; padding: 0 12px; border-radius: 999px; background: var(--superficie-2); color: var(--texto-2); font-size: 13px; }
.barra { height: 6px; background: var(--superficie-2); border-radius: 99px; overflow: hidden; }
.barra > i { display: block; height: 100%; background: var(--acento); border-radius: 99px; transition: width .6s var(--muelle); }
.oculto { display: none !important; }
.cargando { color: var(--texto-3); }
.cargando::before { content: ""; display: inline-block; width: 14px; height: 14px; margin-right: 10px; vertical-align: -2px;
  border: 2px solid var(--superficie-2); border-top-color: var(--acento); border-radius: 50%; animation: girar .8s linear infinite; }
input[type=checkbox] { accent-color: var(--acento); width: 20px; height: 20px; margin: 0; flex: none; }
.aviso { position: fixed; left: 20px; right: 20px; bottom: 24px; background: var(--superficie-2); color: var(--texto); border-radius: var(--radio-control);
  padding: 14px 16px; opacity: 0; transform: translateY(16px); transition: opacity .25s var(--suave), transform .45s var(--muelle); pointer-events: none; }
.aviso.visible { opacity: 1; transform: none; }
@keyframes entrar { from { opacity: 0; transform: translateY(8px) scale(.98); } }
@keyframes girar { to { transform: rotate(360deg); } }
`;

// sios.cargar/guardar: datos de la mini-app (guardados en el servicio: sobreviven a todo y Claude
// los puede leer). sios.preguntar: pregunta corta a Claude. sios.aviso: mensaje breve abajo.
const KIT_JS = `
(() => {
  const datos = window.__datos || {};
  let pendiente;
  const subir = () => fetch("/creaciones/__ID__/datos", { method: "POST", keepalive: true, headers: { "content-type": "application/json" }, body: JSON.stringify(datos) })
    .catch((e) => console.error("sios.guardar", e));
  let reloj;
  window.sios = {
    cargar(clave, defecto) { return clave in datos ? structuredClone(datos[clave]) : defecto; },
    guardar(clave, valor) { datos[clave] = valor; clearTimeout(pendiente); pendiente = setTimeout(subir, 250); },
    async preguntar(texto) {
      const r = await fetch("/creaciones/__ID__/preguntar", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ texto }) });
      let j = {}; try { j = await r.json(); } catch {}
      if (!r.ok) { console.error("sios.preguntar", r.status, j.error); throw new Error(j.error || "Claude no respondió"); }
      return j.respuesta;
    },
    aviso(texto) {
      const a = document.getElementById("claude-aviso"); a.textContent = texto; a.classList.add("visible");
      clearTimeout(reloj); reloj = setTimeout(() => a.classList.remove("visible"), 2600);
    },
  };
  addEventListener("pagehide", () => { if (pendiente) { clearTimeout(pendiente); subir(); } });
})();
`;
