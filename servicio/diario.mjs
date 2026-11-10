// Diario nocturno: cada noche (22:30 por defecto) Claude escribe unas líneas de cómo fue el día con
// lo que sabe el móvil —citas, pasos, fotos, llamadas, el tiempo y cuánto se usó el móvil— y avisa
// para leerlo. Una llamada a Haiku al día. Las entradas se guardan en ~/sios/diario y se
// leen en la mini-app «Diario» (/apps/diario/).
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { consultaContenido, puente } from "./movil.mjs";
import { preguntarRapido } from "./creaciones.mjs";
import { citasHoy, tiempoHoy } from "./dia.mjs";
import { minutosTexto, uso } from "./bienestar.mjs";

const CARPETA = join(homedir(), "sios", "diario");
const AJUSTES = join(CARPETA, "ajustes.json");
const hoyISO = () => new Date().toLocaleDateString("sv-SE");

export function ajustesDiario() {
  try {
    return { activo: true, hora: "22:30", ...JSON.parse(readFileSync(AJUSTES, "utf8")) };
  } catch {
    return { activo: true, hora: "22:30" };
  }
}

export function ponerAjustesDiario(cambios) {
  mkdirSync(CARPETA, { recursive: true });
  const a = { ...ajustesDiario(), ...cambios };
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(a.hora)) a.hora = "22:30";
  writeFileSync(AJUSTES, JSON.stringify(a));
  return a;
}

/** Las entradas, de la más reciente a la más antigua: [{dia, texto, datos}]. */
export function entradas(cuantas = 30) {
  if (!existsSync(CARPETA)) return [];
  return readdirSync(CARPETA)
    .filter((f) => /^\d{4}-\d{2}-\d{2}\.json$/.test(f))
    .sort()
    .reverse()
    .slice(0, cuantas)
    .map((f) => { try { return JSON.parse(readFileSync(join(CARPETA, f), "utf8")); } catch { return null; } })
    .filter(Boolean);
}

/** Lo que dice el móvil del día de hoy, en líneas cortas. */
async function datosDelDia() {
  const inicio = new Date();
  inicio.setHours(0, 0, 0, 0);
  const desde = inicio.getTime();
  const [citas, tiempo, salud, u, fotos, llamadas] = await Promise.all([
    citasHoy().catch(() => []),
    tiempoHoy().catch(() => ""),
    puente("salud").catch(() => null),
    uso(1).catch(() => null),
    consultaContenido("content://media/external/images/media", ["_id"], { where: `date_added>=${Math.floor(desde / 1000)}` }).catch(() => []),
    consultaContenido("content://call_log/calls", ["type"], { where: `date>=${desde}` }).catch(() => []),
  ]);
  const pasos = salud?.pasos?.at(-1)?.y;
  const total = u?.dias?.at(-1)?.minutos;
  return [
    citas.length ? `Citas: ${citas.join("; ")}` : "Sin citas en el calendario",
    tiempo ? `Tiempo: ${tiempo}` : "",
    pasos > 0 ? `Pasos: ${pasos}${salud.sueno_min ? `; durmió ${minutosTexto(salud.sueno_min)}` : ""}` : "",
    fotos.length ? `Fotos hechas: ${fotos.length}` : "",
    llamadas.length ? `Llamadas: ${llamadas.length}` : "",
    Number.isFinite(total) ? `Móvil: ${minutosTexto(total)}${u.hoy?.length ? ` (sobre todo ${u.hoy.slice(0, 2).map((x) => x.app).join(" y ")})` : ""}` : "",
  ].filter(Boolean);
}

/** Escribe la entrada de hoy (o la rehace) y avisa. */
export async function escribir({ avisar = true } = {}) {
  const datos = await datosDelDia();
  const fecha = new Date().toLocaleDateString("es-ES", { weekday: "long", day: "numeric", month: "long" });
  const respuesta = await preguntarRapido(`Hoy es ${fecha}.\n${datos.join("\n")}`, {
    sistema:
      "Escribe la entrada del diario de hoy del usuario a partir de lo que sabe su móvil. 3 o 4 frases, en segunda persona («Hoy has…»), " +
      "tono cálido y tranquilo, sin juicios sobre el uso del móvil, sin inventar nada que no esté en los datos, sin emojis ni títulos. " +
      "Termina con una frase corta que invite a descansar o a algo de mañana si hay datos para ello.",
  });
  const texto = respuesta.trim().slice(0, 900);
  mkdirSync(CARPETA, { recursive: true });
  const entrada = { dia: hoyISO(), texto, datos, cuando: Date.now() };
  writeFileSync(join(CARPETA, `${entrada.dia}.json`), JSON.stringify(entrada));
  if (avisar) await puente("notificar", { id: "diario", titulo: "Tu día, en unas líneas", texto, app: "diario" }).catch(() => {});
  return entrada;
}

/** La mini-app del diario: las entradas como tarjetas (lo pinta el kit de las mini-apps). */
export function htmlDiario() {
  const e = entradas(60);
  const escapar = (s) => String(s).replace(/[&<>"]/g, (ch) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" })[ch]);
  if (!e.length) return `<div class="vacio">Esta noche, a las ${ajustesDiario().hora}, Claude escribirá aquí cómo ha ido tu día.</div>`;
  return e.map((x) => {
    const fecha = new Date(`${x.dia}T12:00:00`).toLocaleDateString("es-ES", { weekday: "long", day: "numeric", month: "long" });
    return `<section class="tarjeta"><span class="etiqueta">${escapar(fecha)}</span><p>${escapar(x.texto)}</p></section>`;
  }).join("");
}

/** Cada 5 minutos mira si ya es la hora (una vez al día). */
export function arrancarDiario() {
  const mirar = () => {
    const a = ajustesDiario();
    if (!a.activo || existsSync(join(CARPETA, `${hoyISO()}.json`))) return;
    const [h, m] = a.hora.split(":").map(Number);
    const ahora = new Date();
    if (ahora.getHours() * 60 + ahora.getMinutes() >= h * 60 + m) escribir().catch((e) => console.error("Diario:", e.message));
  };
  setTimeout(mirar, 45_000);
  setInterval(mirar, 5 * 60_000).unref?.();
}
