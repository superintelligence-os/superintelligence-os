// Familia: un grupo con tu gente de SIOS para una lista compartida (la compra…) y, quien quiera,
// dónde está ahora («en casa», «en el trabajo»). Todo va cifrado de extremo a extremo (AES-256-GCM
// con la clave del grupo, que solo está en los móviles y viaja dentro del enlace de invitación); el
// servidor (comunidad/, /familia/<grupo>) solo guarda paquetes cerrados.
import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { puente } from "./movil.mjs";

const ARCHIVO = join(homedir(), "sios", "familia.json");
const COMUNIDAD = join(homedir(), "sios", "comunidad.json");
const servidor = () => { try { return JSON.parse(readFileSync(COMUNIDAD, "utf8")).url; } catch { return null; } };
// El mismo servidor que la galería comunitaria (SIOS_COMUNIDAD o comunidad.json); sin él, Familia no sincroniza.
const URL_BASE = () => servidor() || process.env.SIOS_COMUNIDAD || null;

function leer() {
  try { return JSON.parse(readFileSync(ARCHIVO, "utf8")); } catch { return null; }
}
const guardar = (f) => writeFileSync(ARCHIVO, JSON.stringify(f), { mode: 0o600 });

/** Estado público (sin la clave). */
export function estado() {
  const f = leer();
  if (!f) return { grupo: null };
  return {
    grupo: f.nombre, yo: f.yo.nombre, compartir_ubicacion: !!f.compartirUbicacion,
    lista: Object.values(f.lista || {}).filter((x) => !x.quitado).sort((a, b) => Number(a.hecho) - Number(b.hecho) || (a.orden ?? 0) - (b.orden ?? 0)),
    miembros: Object.values(f.miembros || {}).sort((a, b) => b.cuando - a.cuando),
  };
}

export function enlace() {
  const f = leer();
  if (!f) throw new Error("Aún no hay grupo familiar");
  return `sios://familia?g=${f.grupo}&k=${f.clave}&n=${encodeURIComponent(f.nombre)}`;
}

function nuevoYo(nombre) {
  return { id: randomBytes(6).toString("hex"), nombre: String(nombre || "Yo").slice(0, 30) };
}

export async function crear(nombre, miNombre) {
  const f = { grupo: randomBytes(16).toString("hex"), clave: randomBytes(32).toString("base64url"), nombre: String(nombre || "Familia").slice(0, 30), yo: nuevoYo(miNombre), seq: 0, lista: {}, miembros: {}, compartirUbicacion: false };
  guardar(f);
  await enviar({ tipo: "hola", nombre: f.yo.nombre }).catch(() => {});
  return { enlace: enlace(), ...estado() };
}

export async function unirse(enlaceTexto, miNombre) {
  const u = new URL(String(enlaceTexto).replace("sios://", "https://x/"));
  const grupo = u.searchParams.get("g"), clave = u.searchParams.get("k");
  if (!/^[a-f0-9]{32}$/.test(grupo || "") || Buffer.from(clave || "", "base64url").length !== 32) throw new Error("Invitación no válida");
  guardar({ grupo, clave, nombre: (u.searchParams.get("n") || "Familia").slice(0, 30), yo: nuevoYo(miNombre), seq: 0, lista: {}, miembros: {}, compartirUbicacion: false });
  await enviar({ tipo: "hola", nombre: leer().yo.nombre }).catch(() => {});
  await sincronizar();
  return estado();
}

export function salir() {
  writeFileSync(ARCHIVO, "null");
}

// ---------- Cifrado y buzón ----------

function cerrar(f, obj) {
  const iv = randomBytes(12);
  const c = createCipheriv("aes-256-gcm", Buffer.from(f.clave, "base64url"), iv);
  const datos = Buffer.concat([c.update(JSON.stringify(obj)), c.final(), c.getAuthTag()]);
  return { n: iv.toString("base64"), c: datos.toString("base64") };
}

function abrir(f, { n, c }) {
  const todo = Buffer.from(c, "base64");
  const d = createDecipheriv("aes-256-gcm", Buffer.from(f.clave, "base64url"), Buffer.from(n, "base64"));
  d.setAuthTag(todo.subarray(-16));
  return JSON.parse(Buffer.concat([d.update(todo.subarray(0, -16)), d.final()]).toString("utf8"));
}

async function enviar(obj) {
  const f = leer();
  if (!f) throw new Error("Aún no hay grupo familiar");
  if (!URL_BASE()) throw new Error("Familia necesita un servidor configurado (SIOS_COMUNIDAD)");
  const r = await fetch(`${URL_BASE()}/familia/${f.grupo}`, {
    method: "POST", headers: { "content-type": "application/json" }, signal: AbortSignal.timeout(12000),
    body: JSON.stringify({ de: f.yo.id, ...cerrar(f, { ...obj, de: f.yo.id, nombre: f.yo.nombre, cuando: Date.now() }) }),
  }).catch(() => null);
  if (!r?.ok) throw new Error(r?.status === 404 ? "El servicio de Familia aún no está en marcha" : "No se ha podido enviar a tu familia ahora");
  await sincronizar();
}

/** Trae lo nuevo y lo aplica: lista (poner, tachar, quitar) y dónde está cada uno. */
export async function sincronizar() {
  const f = leer();
  if (!f) return;
  if (!URL_BASE()) return null;
  const r = await fetch(`${URL_BASE()}/familia/${f.grupo}?desde=${f.seq || 0}`, { signal: AbortSignal.timeout(12000) }).catch(() => null);
  if (!r?.ok) return;
  const paquetes = await r.json();
  const nuevos = [];
  for (const p of paquetes) {
    let m;
    try { m = abrir(f, p); } catch { continue; } // no es de este grupo (o está dañado)
    f.seq = Math.max(f.seq || 0, p.seq);
    if (m.de !== p.de) continue;
    f.miembros = f.miembros || {};
    f.miembros[m.de] = { ...(f.miembros[m.de] || {}), id: m.de, nombre: m.nombre, visto: m.cuando, cuando: f.miembros[m.de]?.cuando || m.cuando, yo: m.de === f.yo.id };
    if (m.tipo === "lista") {
      f.lista = f.lista || {};
      const id = m.item;
      if (m.accion === "poner") f.lista[id] = { id, texto: m.texto, hecho: false, cuando: m.cuando, orden: p.seq, quien: m.nombre };
      else if (f.lista[id] && m.accion === "tachar") f.lista[id].hecho = !!m.hecho;
      else if (f.lista[id] && m.accion === "quitar") f.lista[id].quitado = true;
      if (m.de !== f.yo.id && m.accion === "poner") nuevos.push(`${m.nombre}: ${m.texto}`);
    }
    if (m.tipo === "ubicacion") f.miembros[m.de] = { ...f.miembros[m.de], lugar: m.lugar, cuando: m.cuando };
    if (m.tipo === "deja_ubicacion") f.miembros[m.de] = { ...f.miembros[m.de], lugar: null };
  }
  guardar(f);
  if (nuevos.length) await puente("notificar", { id: "familia-lista", titulo: `${f.nombre}: a la lista`, texto: nuevos.slice(-4).join("\n") }).catch(() => {});
}

// ---------- Lista ----------

export const anadir = (texto) => enviar({ tipo: "lista", accion: "poner", item: randomBytes(5).toString("hex"), texto: String(texto).slice(0, 80) });
function buscarItem(texto) {
  const t = String(texto).toLowerCase();
  const l = estado().lista || [];
  return l.find((x) => x.id === texto) || l.find((x) => x.texto.toLowerCase() === t) || l.find((x) => x.texto.toLowerCase().includes(t));
}
export async function tachar(texto, hecho = true) {
  const x = buscarItem(texto);
  if (!x) throw new Error(`«${texto}» no está en la lista`);
  await enviar({ tipo: "lista", accion: "tachar", item: x.id, hecho });
}
export async function quitar(texto) {
  const x = buscarItem(texto);
  if (!x) throw new Error(`«${texto}» no está en la lista`);
  await enviar({ tipo: "lista", accion: "quitar", item: x.id });
}

// ---------- Ubicación (solo si la activas) ----------

export async function compartirUbicacion(si) {
  const f = leer();
  if (!f) throw new Error("Aún no hay grupo familiar");
  f.compartirUbicacion = !!si;
  guardar(f);
  if (si) await enviarUbicacion(true);
  else await enviar({ tipo: "deja_ubicacion" }).catch(() => {});
}

let ultimaUbicacion = 0;
let ultimoLugar = "";
/** El sitio con nombre, no las coordenadas: «Calle Mayor, Fortuna» (o «en casa» si coincide). */
async function enviarUbicacion(forzar = false) {
  const f = leer();
  if (!f?.compartirUbicacion) return;
  if (!forzar && Date.now() - ultimaUbicacion < 15 * 60_000) return;
  const u = await puente("ubicacion").catch(() => null);
  if (!Number.isFinite(u?.lat)) return;
  const r = await fetch(`https://nominatim.openstreetmap.org/reverse?format=json&zoom=17&accept-language=es&lat=${u.lat}&lon=${u.lon}`, { headers: { "user-agent": "SIOS/0.1 (familia)" }, signal: AbortSignal.timeout(10000) }).then((x) => x.json()).catch(() => null);
  const a = r?.address || {};
  const lugar = [a.road || a.pedestrian || a.suburb, a.city || a.town || a.village].filter(Boolean).join(", ") || "Ubicación compartida";
  ultimaUbicacion = Date.now();
  if (!forzar && lugar === ultimoLugar) return; // no ha cambiado: no se manda
  ultimoLugar = lugar;
  await enviar({ tipo: "ubicacion", lugar }).catch(() => {});
}

export function arrancarFamilia() {
  const tic = () => { if (leer()) { sincronizar().catch(() => {}); enviarUbicacion().catch(() => {}); } };
  setTimeout(tic, 50_000);
  setInterval(tic, 3 * 60_000).unref?.();
}
