// Contexto del móvil para el panel: qué hora es, dónde está, a qué Wi-Fi y Bluetooth está conectado,
// si carga, si está en modo foco y qué cita viene. Lo usan las pilas inteligentes, los modos del
// panel y las tarjetas automáticas. Se guarda un minuto: leerlo cuesta varias órdenes.
//
// También lleva el presupuesto diario de Claude para el panel (noticias, «mi día», pilas, tarjetas
// automáticas): que el panel nunca se coma el plan del usuario.
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { android, consultaContenido, puente } from "./movil.mjs";

const HOME = process.env.HOME || "/data/data/com.termux/files/home";
const AJUSTES = join(HOME, "sios", "panel-ajustes.json");

let cache = null;
let cuando = 0;

/** {hora "HH:MM", minutos (del día), dia 1–7, wifi, bluetooth [nombres], carga, bateria, lat, lon, foco, cita {titulo, en_min}} */
export async function contexto({ fresco = false } = {}) {
  if (!fresco && cache && Date.now() - cuando < 60_000) return cache;
  const f = new Date();
  const [wifi, bt, bateria, foco, cita, pos] = await Promise.all([
    android("cmd wifi status").then((s) => s.match(/Wifi is connected to "([^"]+)"/)?.[1] || null).catch(() => null),
    puente("bt_baterias").then((r) => (r.dispositivos || []).map((d) => d.nombre).filter(Boolean)).catch(() => []),
    android("dumpsys battery").catch(() => ""),
    android("settings get global zen_mode").then((s) => s.trim() !== "0").catch(() => false),
    proximaCita().catch(() => null),
    puente("ubicacion").catch(() => null),
  ]);
  cache = {
    hora: `${String(f.getHours()).padStart(2, "0")}:${String(f.getMinutes()).padStart(2, "0")}`,
    minutos: f.getHours() * 60 + f.getMinutes(),
    dia: f.getDay() || 7,
    wifi,
    bluetooth: bt,
    carga: /(AC|USB|Wireless) powered: true/.test(bateria),
    bateria: Number(bateria.match(/level: (\d+)/)?.[1]) || null,
    lat: pos?.lat ?? null,
    lon: pos?.lon ?? null,
    foco,
    cita,
  };
  cuando = Date.now();
  return cache;
}

async function proximaCita() {
  const ahora = Date.now();
  const filas = await consultaContenido(`content://com.android.calendar/instances/when/${ahora}/${ahora + 12 * 3600_000}`, ["title", "begin", "allDay"], { orden: "begin ASC" });
  const f = filas.find((x) => x.allDay !== "1");
  return f ? { titulo: f.title, en_min: Math.round((+f.begin - ahora) / 60_000) } : null;
}

function distanciaM(a, b) {
  const r = (g) => (g * Math.PI) / 180;
  const h = Math.sin(r(b.lat - a.lat) / 2) ** 2 + Math.cos(r(a.lat)) * Math.cos(r(b.lat)) * Math.sin(r(b.lon - a.lon) / 2) ** 2;
  return 12742000 * Math.asin(Math.sqrt(h));
}

const aMin = (hhmm) => { const [h, m] = String(hhmm).split(":").map(Number); return h * 60 + (m || 0); };
const contiene = (lista, texto) => (lista || []).some((x) => String(x).toLowerCase().includes(String(texto).toLowerCase()));

/**
 * ¿Se cumple esta condición? cuando: {desde, hasta ("HH:MM", cruza medianoche si desde > hasta),
 * dias [1–7], wifi, bluetooth, lugar {lat, lon, radio}, carga, foco, cita_en_min}. Todo lo que
 * lleve tiene que cumplirse; sin nada, se cumple siempre.
 */
export function cumple(cuando = {}, c) {
  if (cuando.desde && cuando.hasta) {
    const d = aMin(cuando.desde), h = aMin(cuando.hasta);
    const dentro = d <= h ? c.minutos >= d && c.minutos < h : c.minutos >= d || c.minutos < h;
    if (!dentro) return false;
  }
  if (cuando.dias?.length && !cuando.dias.includes(c.dia)) return false;
  if (cuando.wifi && !(c.wifi || "").toLowerCase().includes(String(cuando.wifi).toLowerCase())) return false;
  if (cuando.bluetooth && !contiene(c.bluetooth, cuando.bluetooth)) return false;
  if (cuando.lugar && Number.isFinite(cuando.lugar.lat)) {
    if (c.lat == null || distanciaM(cuando.lugar, c) > (cuando.lugar.radio || 300)) return false;
  }
  if (cuando.carga != null && !!cuando.carga !== c.carga) return false;
  if (cuando.foco != null && !!cuando.foco !== c.foco) return false;
  if (cuando.cita_en_min != null && !(c.cita && c.cita.en_min <= cuando.cita_en_min)) return false;
  return true;
}

/** Contexto en una línea, para que Claude elija (pilas) sin datos de más. */
export function contextoTexto(c) {
  const dias = ["", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"];
  return [
    `${dias[c.dia]} ${c.hora}`,
    c.wifi ? `Wi-Fi «${c.wifi}»` : "sin Wi-Fi",
    c.bluetooth.length ? `Bluetooth: ${c.bluetooth.join(", ")}` : null,
    c.carga ? "cargando" : c.bateria != null ? `batería ${c.bateria} %` : null,
    c.foco ? "modo foco" : null,
    c.cita ? `próxima cita «${c.cita.titulo}» en ${c.cita.en_min} min` : null,
  ].filter(Boolean).join(" · ");
}

// ---------- Ajustes del panel y presupuesto de Claude ----------

const AJUSTES_DE_SERIE = {
  presupuesto: 30, // llamadas a Claude al día para el panel
  automaticas: { activo: true, tipos: ["paquetes", "deportes", "vuelos", "facturas", "viaje"], equipos: [] },
  vistazo: { activo: true, ids: [] }, // cifras del panel en la pantalla de bloqueo
};

export function ajustesPanel() {
  try {
    const a = JSON.parse(readFileSync(AJUSTES, "utf8"));
    // Tipos nuevos de tarjetas automáticas: activados también para quien ya tenía ajustes guardados.
    if (a.automaticas?.tipos && !a.automaticas.vistos?.includes("viaje")) {
      a.automaticas.tipos = [...new Set([...a.automaticas.tipos, "viaje"])];
      a.automaticas.vistos = [...(a.automaticas.vistos || []), "viaje"];
    }
    return { ...AJUSTES_DE_SERIE, ...a, automaticas: { ...AJUSTES_DE_SERIE.automaticas, ...a.automaticas }, vistazo: { ...AJUSTES_DE_SERIE.vistazo, ...a.vistazo } };
  } catch {
    return structuredClone(AJUSTES_DE_SERIE);
  }
}

export function ponerAjustesPanel(cambios) {
  const a = ajustesPanel();
  const nuevo = { ...a, ...cambios, automaticas: { ...a.automaticas, ...(cambios.automaticas || {}) }, vistazo: { ...a.vistazo, ...(cambios.vistazo || {}) } };
  mkdirSync(join(HOME, "sios"), { recursive: true });
  writeFileSync(AJUSTES, JSON.stringify(nuevo, null, 1));
  return nuevo;
}

// El gasto del día se guarda en disco: reiniciar el servicio no lo pone a cero.
const GASTO = join(HOME, "sios", "panel-gasto.json");
const hoy = () => new Date().toISOString().slice(0, 10);
function leerGasto() {
  try {
    const g = JSON.parse(readFileSync(GASTO, "utf8"));
    if (g.dia === hoy()) return g;
  } catch {}
  return { dia: hoy(), usadas: 0 };
}

/** ¿Puede el panel llamar a Claude ahora? Lo apunta si sí. */
export function gastarIa(motivo = "") {
  const g = leerGasto();
  const limite = ajustesPanel().presupuesto;
  if (g.usadas >= limite) {
    console.log(`[panel] sin presupuesto de Claude hoy (${limite}); ${motivo} espera a mañana`);
    return false;
  }
  g.usadas++;
  writeFileSync(GASTO, JSON.stringify(g));
  return true;
}

export const presupuestoHoy = () => ({ ...leerGasto(), limite: ajustesPanel().presupuesto });
