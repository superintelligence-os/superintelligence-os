// «Tu día» en la pantalla de bloqueo y el médico del móvil.
//
// Tu día: cada mañana (y cuando se pide) Claude junta las citas de hoy y el tiempo en una línea
// corta que la app enseña en la pantalla de bloqueo. Una llamada a Haiku al día.
// Médico: el estado real del sistema (batería, procesos, memoria, almacenamiento, temperatura)
// para que Claude explique por qué va lento o gasta batería y proponga arreglos.
import { android, consultaContenido, puente, termux } from "./movil.mjs";
import { preguntarRapido } from "./creaciones.mjs";

const HORA_DIA = 7; // a partir de las 7:00 se prepara el del día
let hecho = ""; // fecha (AAAA-MM-DD) del último «Tu día»

const hoyISO = () => new Date().toISOString().slice(0, 10);

/** Citas de hoy (desde ahora hasta medianoche) del calendario del móvil. */
export async function citasHoy() {
  const ahora = new Date();
  const fin = new Date(ahora);
  fin.setHours(23, 59, 59, 999);
  const inicio = new Date(ahora);
  inicio.setHours(0, 0, 0, 0);
  const filas = await consultaContenido(
    "content://com.android.calendar/events",
    ["title", "dtstart", "allDay", "eventLocation"],
    { where: `dtstart>=${inicio.getTime()} AND dtstart<=${fin.getTime()} AND deleted=0`, orden: "dtstart ASC" }
  ).catch(() => []);
  return filas.map((f) => {
    const hora = f.allDay === "1" ? "todo el día" : new Date(+f.dtstart).toLocaleTimeString("es-ES", { hour: "2-digit", minute: "2-digit" });
    return `${hora} ${f.title}${f.eventLocation && f.eventLocation !== "NULL" ? ` (${f.eventLocation})` : ""}`;
  });
}

/** El tiempo de hoy en pocas palabras (wttr.in, por la ubicación de la conexión). */
export async function tiempoHoy() {
  const r = await termux("curl -fsS --max-time 8 'https://wttr.in/?format=%l:+%C,+%t,+lluvia+%p&lang=es&m'", { timeout: 12000 });
  return r.ok ? r.salida.split("\n")[0].trim() : "";
}

/** Prepara «Tu día» y lo publica en la pantalla de bloqueo. Devuelve la línea. */
export async function tuDia() {
  const [citas, tiempo] = await Promise.all([citasHoy(), tiempoHoy()]);
  const fecha = new Date().toLocaleDateString("es-ES", { weekday: "long", day: "numeric", month: "long" });
  const linea = await preguntarRapido(
    `Hoy es ${fecha}.\nCitas de hoy: ${citas.length ? citas.join("; ") : "ninguna"}.\nTiempo: ${tiempo || "sin datos"}.`,
    {
      sistema:
        "Escribe UNA línea muy corta (máximo 90 caracteres) para la pantalla de bloqueo del usuario con lo esencial de su día: " +
        "cuántas cosas tiene y la primera con su hora, y el tiempo si es relevante (lluvia, frío, calor). Español, tono cercano, " +
        "sin saludos ni emojis, sin comillas. Si no hay citas, dilo con naturalidad («Día libre») y añade el tiempo.",
    }
  );
  const texto = linea.split("\n")[0].trim().slice(0, 120);
  await puente("dia", { texto, detalle: [citas.length ? citas.join("\n") : "Sin citas hoy", tiempo].filter(Boolean).join("\n") });
  hecho = hoyISO();
  return texto;
}

/** Cada 10 minutos mira si ya toca el de hoy (a partir de las 7:00, una vez al día). */
export function arrancarDia() {
  const mirar = () => {
    if (new Date().getHours() >= HORA_DIA && hecho !== hoyISO()) tuDia().catch((e) => console.error("Tu día:", e.message));
  };
  setTimeout(mirar, 30_000);
  setInterval(mirar, 10 * 60_000).unref?.();
}

/** Médico del móvil: lo que hace falta para saber por qué va lento, se calienta o gasta batería. */
export async function revisarMovil() {
  const partes = await Promise.all([
    android("dumpsys battery | grep -E 'level|temperature|health|AC powered|USB powered'").then((s) => `Batería:\n${s.trim()}`),
    android("dumpsys batterystats --charged 2>/dev/null | grep -A 14 -m 1 'Estimated power use'").then((s) => s.trim() ? `Consumo desde la última carga (mAh):\n${s.trim()}` : ""),
    android("top -b -n 1 -m 10 2>/dev/null | tail -n 12").then((s) => `Procesos que más CPU usan ahora:\n${s.trim()}`),
    android("head -n 3 /proc/meminfo").then((s) => `Memoria:\n${s.trim()}`),
    android("df -h /data | tail -1; du -sh /sdcard/DCIM /sdcard/Pictures /sdcard/Download /sdcard/Movies /sdcard/Android/media 2>/dev/null").then((s) => `Almacenamiento:\n${s.trim()}`),
    android("dumpsys thermalservice 2>/dev/null | grep -m 1 -A 6 'Current temperatures from HAL'").then((s) => s.trim() ? `Temperaturas:\n${s.trim()}` : ""),
    android("uptime").then((s) => `Encendido: ${s.trim()}`),
    android("cmd package list packages -3 | wc -l").then((s) => `Apps instaladas por el usuario: ${s.trim()}`),
  ].map((p) => p.catch((e) => `(no disponible: ${e.message})`)));
  return partes.filter(Boolean).join("\n\n");
}
