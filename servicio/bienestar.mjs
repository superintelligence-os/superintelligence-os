// Bienestar digital: cuánto se usa el móvil (estadísticas de uso de Android, por el puente), los
// límites por app que se piden hablando con Claude («máximo 30 minutos de Instagram al día») y un
// resumen el domingo por la tarde. Todo en el móvil y sin llamar a Claude: no gasta suscripción.
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { android, puente, sinAcentos } from "./movil.mjs";

const ARCHIVO = join(homedir(), "sios", "limites.json");
const PRORROGA_MIN = 5;
const hoyISO = () => new Date().toLocaleDateString("sv-SE"); // AAAA-MM-DD en la hora local

/** {limites: [{paquete, app, minutos}], prorrogas: {dia, por: {paquete: minutos}}, resumen: dia} */
function leer() {
  try {
    const d = JSON.parse(readFileSync(ARCHIVO, "utf8"));
    return { limites: d.limites || [], prorrogas: d.prorrogas || { dia: "", por: {} }, resumen: d.resumen || "" };
  } catch {
    return { limites: [], prorrogas: { dia: "", por: {} }, resumen: "" };
  }
}
const guardar = (d) => writeFileSync(ARCHIVO, JSON.stringify(d, null, 1));

export const minutosTexto = (min) => (min >= 60 ? `${Math.floor(min / 60)} h${min % 60 ? ` ${min % 60} min` : ""}` : `${min} min`);

/** Uso de los últimos `dias` días: {dias: [{dia, minutos}], hoy: [{paquete, app, minutos}], semana, primer_plano}. */
export async function uso(dias = 7) {
  return puente("uso_apps", { dias: String(dias) });
}

/** Android solo da las estadísticas de uso con este permiso especial: se concede una vez. */
export async function permitir() {
  const marca = join(homedir(), "sios", ".permisos-bienestar");
  if (existsSync(marca)) return;
  try {
    await android("appops set com.sios GET_USAGE_STATS allow");
    writeFileSync(marca, new Date().toISOString());
  } catch (e) {
    console.error("No se pudo conceder el uso de las apps:", e.message);
  }
}

export function limites() {
  return leer().limites;
}

/** Pone (o cambia) el límite diario de una app, por su nombre visible o su paquete. */
export async function ponerLimite({ app, minutos }) {
  const { apps } = await puente("apps");
  const n = sinAcentos(app);
  const a = apps.find((x) => x.paquete === app) || apps.find((x) => sinAcentos(x.nombre) === n) || apps.find((x) => sinAcentos(x.nombre).includes(n));
  if (!a) throw new Error(`No encuentro la app «${app}»`);
  const d = leer();
  d.limites = d.limites.filter((l) => l.paquete !== a.paquete);
  if (minutos > 0) d.limites.push({ paquete: a.paquete, app: a.nombre, minutos: Math.round(minutos) });
  guardar(d);
  return { paquete: a.paquete, app: a.nombre, minutos };
}

/** «Cinco minutos más» en la pantalla del límite: solo para hoy. */
export function prorrogar(paquete) {
  const d = leer();
  if (d.prorrogas.dia !== hoyISO()) d.prorrogas = { dia: hoyISO(), por: {} };
  d.prorrogas.por[paquete] = (d.prorrogas.por[paquete] || 0) + PRORROGA_MIN;
  guardar(d);
}

let mostrado = { paquete: "", cuando: 0 };

/** Cada minuto: si la app que está delante ya pasó su límite de hoy, la pantalla del límite. */
async function vigilar() {
  const d = leer();
  if (!d.limites.length) return;
  const u = await uso(1);
  const delante = u.primer_plano;
  const l = d.limites.find((x) => x.paquete === delante);
  if (!l) return;
  const usados = u.hoy.find((x) => x.paquete === delante)?.minutos ?? 0;
  const extra = d.prorrogas.dia === hoyISO() ? d.prorrogas.por[delante] || 0 : 0;
  if (usados < l.minutos + extra) return;
  if (mostrado.paquete === delante && Date.now() - mostrado.cuando < 50_000) return;
  mostrado = { paquete: delante, cuando: Date.now() };
  await puente("limite", { paquete: delante, app: l.app, minutos: String(l.minutos) });
}

/** El domingo a partir de las 20:00, un resumen de la semana (una vez). */
async function resumenSemanal() {
  const ahora = new Date();
  if (ahora.getDay() !== 0 || ahora.getHours() < 20) return;
  const d = leer();
  if (d.resumen === hoyISO()) return;
  const u = await uso(14);
  const serie = u.dias.map((x) => x.minutos);
  const esta = serie.slice(-7).reduce((a, b) => a + b, 0);
  const antes = serie.slice(0, 7).reduce((a, b) => a + b, 0);
  const media = Math.round(esta / 7);
  const cambio = antes > 0 ? Math.round(((esta - antes) / antes) * 100) : null;
  const top = (u.semana || []).slice(0, 3).map((x) => `${x.app} ${minutosTexto(x.minutos)}`).join(", ");
  const texto = [
    `${minutosTexto(media)} al día de media${cambio == null ? "" : cambio === 0 ? ", igual que la semana pasada" : `, un ${Math.abs(cambio)} % ${cambio < 0 ? "menos" : "más"} que la semana pasada`}.`,
    top ? `Lo que más: ${top}.` : "",
  ].filter(Boolean).join(" ");
  await puente("notificar", { id: "bienestar-semana", titulo: "Tu semana con el móvil", texto });
  d.resumen = hoyISO();
  guardar(d);
}

export function arrancarBienestar() {
  permitir().catch(() => {});
  const tic = () => {
    vigilar().catch(() => {});
    resumenSemanal().catch(() => {});
  };
  setTimeout(tic, 20_000);
  setInterval(tic, 60_000).unref?.();
}
