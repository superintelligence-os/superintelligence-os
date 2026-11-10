// «Buenas noches»: de la hora de dormir a tu alarma (o a la hora de levantarte), No molestar con
// prioridad (pasan tus favoritos y quien insiste), lo demás espera en silencio (el Foco de Claude) y
// la pantalla en gris para ayudar a soltar el móvil. Al despertar, el resumen de lo que llegó.
import { readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { android, puente } from "./movil.mjs";

const ARCHIVO = join(homedir(), "sios", "buenas-noches.json");
const HORA = /^([01]\d|2[0-3]):[0-5]\d$/;

export function ajustes() {
  try {
    return { activo: false, desde: "23:30", hasta: "07:30", gris: true, ...JSON.parse(readFileSync(ARCHIVO, "utf8")) };
  } catch {
    return { activo: false, desde: "23:30", hasta: "07:30", gris: true };
  }
}

export function poner(cambios) {
  const a = { ...ajustes() };
  for (const k of ["activo", "gris"]) if (typeof cambios[k] === "boolean") a[k] = cambios[k];
  for (const k of ["desde", "hasta"]) if (HORA.test(cambios[k] || "")) a[k] = cambios[k];
  writeFileSync(ARCHIVO, JSON.stringify(a));
  return a;
}

const minutos = (hhmm) => { const [h, m] = hhmm.split(":").map(Number); return h * 60 + m; };

/** ¿Ahora es de noche según los ajustes? (o hasta la alarma, si suena antes). */
async function toca(a) {
  const f = new Date();
  const ahora = f.getHours() * 60 + f.getMinutes();
  const desde = minutos(a.desde);
  let hasta = minutos(a.hasta);
  const alarma = await puente("proxima_alarma").then((r) => r.cuando).catch(() => null);
  // La alarma de mañana manda si es antes de la hora de levantarse.
  if (Number.isFinite(alarma) && alarma - Date.now() < 14 * 3600_000) {
    const fa = new Date(alarma);
    hasta = Math.min(hasta, fa.getHours() * 60 + fa.getMinutes());
  }
  return desde > hasta ? ahora >= desde || ahora < hasta : ahora >= desde && ahora < hasta;
}

let enCurso = false;
let desdeCuando = 0;
let manual = false; // empezada a mano («buenas noches»): dura hasta la mañana

export async function empezar({ aMano = false } = {}) {
  const a = ajustes();
  if (enCurso) return;
  enCurso = true;
  manual = aMano;
  desdeCuando = Date.now();
  await android("cmd notification set_dnd priority").catch(() => {});
  await puente("foco", { minutos: String(12 * 60) }).catch(() => {});
  if (a.gris) await android("settings put secure accessibility_display_daltonizer 0; settings put secure accessibility_display_daltonizer_enabled 1").catch(() => {});
}

export async function terminar() {
  if (!enCurso) return;
  enCurso = false;
  manual = false;
  await android("cmd notification set_dnd off; settings put secure accessibility_display_daltonizer_enabled 0").catch(() => {});
  await puente("foco", { minutos: "0" }).catch(() => {}); // el Foco, al acabar, cuenta lo que esperó
  // «Mientras dormías»: todo lo que llegó por la noche, en pocas líneas (sin gastar Claude).
  const r = await puente("notificaciones").catch(() => null);
  const llegaron = [...(r?.en_la_barra || []), ...(r?.recientes || [])]
    .filter((n) => Date.now() - (n.hace_min || 0) * 60_000 >= desdeCuando - 60_000)
    .filter((n, i, l) => l.findIndex((x) => x.titulo === n.titulo && x.texto === n.texto) === i);
  if (llegaron.length) {
    const lineas = llegaron.slice(0, 5).map((n) => `${n.titulo || n.app}: ${String(n.texto || "").slice(0, 70)}`).join("\n");
    await puente("notificar", { id: "buenos-dias", titulo: `Buenos días · ${llegaron.length} ${llegaron.length === 1 ? "aviso" : "avisos"} mientras dormías`, texto: lineas + (llegaron.length > 5 ? `\ny ${llegaron.length - 5} más` : "") }).catch(() => {});
  }
}

export const activa = () => enCurso;

async function mirar() {
  const a = ajustes();
  const deNoche = await toca(a);
  if (a.activo && deNoche && !enCurso) await empezar();
  // Se acaba al llegar la hora (o la alarma), también si se empezó a mano.
  if (enCurso && !deNoche && (a.activo || manual) && new Date().getHours() >= 5 && new Date().getHours() < 13) await terminar();
}

export function arrancarBuenasNoches() {
  setTimeout(() => mirar().catch(() => {}), 40_000);
  setInterval(() => mirar().catch(() => {}), 60_000).unref?.();
}
