// Carga inteligente: cargar siempre al 100 % desgasta la batería antes. Una ROM genérica no puede
// cortar la carga (eso depende del fabricante), así que Claude avisa al llegar al 80 % —sin sonido
// de noche— y aprende a qué hora sueles desenchufar (y mira tu alarma) para no molestar si lo vas a
// necesitar lleno pronto. Sin gastar Claude.
import { readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { android, puente } from "./movil.mjs";

const ARCHIVO = join(homedir(), "sios", "carga.json");
const UMBRAL = 80;

function leer() {
  try {
    return { activo: true, desenchufes: [], ...JSON.parse(readFileSync(ARCHIVO, "utf8")) };
  } catch {
    return { activo: true, desenchufes: [] };
  }
}
const guardar = (d) => writeFileSync(ARCHIVO, JSON.stringify(d));

export const ajustesCarga = () => ({ activo: leer().activo, desenchufe_habitual: horaHabitual(leer().desenchufes) });
export function ponerCarga(activo) {
  const d = leer();
  d.activo = !!activo;
  guardar(d);
}

/** Hora (minutos desde medianoche) a la que sueles desenchufar por la mañana: la mediana de 14 días. */
function horaHabitual(desenchufes) {
  const manana = desenchufes.filter((t) => Date.now() - t < 14 * 86_400_000).map((t) => { const f = new Date(t); return f.getHours() * 60 + f.getMinutes(); }).filter((m) => m >= 4 * 60 && m <= 12 * 60).sort((a, b) => a - b);
  if (manana.length < 3) return null;
  return manana[Math.floor(manana.length / 2)];
}

let antes = null; // {cargando, nivel}
let avisadaEsta = false;

async function mirar() {
  const d = leer();
  const b = await android("dumpsys battery").catch(() => "");
  const nivel = Number(b.match(/level: (\d+)/)?.[1]);
  const cargando = /(AC|USB|Wireless) powered: true/.test(b);
  if (!Number.isFinite(nivel)) return;
  if (antes && antes.cargando && !cargando) {
    d.desenchufes = [...d.desenchufes.filter((t) => Date.now() - t < 30 * 86_400_000), Date.now()];
    guardar(d);
  }
  if (!cargando) avisadaEsta = false;
  if (d.activo && cargando && nivel >= UMBRAL && !avisadaEsta && (antes == null || antes.nivel < UMBRAL || !antes.cargando)) {
    avisadaEsta = true;
    const ahora = new Date();
    const deNoche = ahora.getHours() >= 23 || ahora.getHours() < 7;
    const alarma = await puente("proxima_alarma").then((r) => r.cuando).catch(() => null);
    const habitual = horaHabitual(d.desenchufes);
    // Cuándo lo vas a necesitar: tu alarma o, si no hay, tu hora de siempre.
    let necesitas = Number.isFinite(alarma) ? alarma : null;
    if (!necesitas && habitual != null) {
      const f = new Date(); f.setHours(Math.floor(habitual / 60), habitual % 60, 0, 0);
      if (f < ahora) f.setDate(f.getDate() + 1);
      necesitas = f.getTime();
    }
    const hora = necesitas ? new Date(necesitas).toLocaleTimeString("es-ES", { hour: "2-digit", minute: "2-digit" }) : null;
    // Si lo vas a usar en menos de una hora, mejor que siga: no se molesta.
    if (necesitas && necesitas - Date.now() < 60 * 60_000) return;
    await puente("notificar", {
      id: "carga", titulo: `Batería al ${nivel} %`,
      texto: deNoche && hora
        ? `Tienes de sobra hasta las ${hora}. Si lo desenchufas ahora, la batería te durará más años.`
        : "Para cuidar la batería, desenchúfalo cuando puedas: cargarla siempre al 100 % la desgasta antes.",
      silencioso: deNoche ? "1" : "0",
    }).catch(() => {});
  }
  antes = { cargando, nivel };
}

export function arrancarCarga() {
  setTimeout(() => mirar().catch(() => {}), 30_000);
  setInterval(() => mirar().catch(() => {}), 2 * 60_000).unref?.();
}
