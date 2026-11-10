// Registro de lo que hace Claude en el móvil: cada acción del chat, de las rutinas, de los agentes
// y de la app (respuestas, bloqueos…), con hora, origen y, si se puede, cómo deshacerla.
// Se guarda en ~/sios/actividad.jsonl (las últimas 3000) y la app lo enseña en Ajustes.
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { homedir } from "node:os";
import { randomUUID } from "node:crypto";

const ARCHIVO = join(homedir(), "sios", "actividad.jsonl");
const MAXIMO = 3000;

/**
 * entrada: { origen: chat|rutina|agente|app, herramienta, frase, detalle?, consulta?, error?, deshacer?: {...} }
 * deshacer: {tipo: "comando", comando} | {tipo: "puente", metodo, extras} | {tipo: "creacion", id}
 */
export function registrar(e) {
  const fila = { id: randomUUID().slice(0, 10), hora: Date.now(), ...e };
  try {
    appendFileSync(ARCHIVO, JSON.stringify(fila) + "\n");
    recortar();
  } catch (err) {
    console.error("Actividad:", err.message);
  }
  emitir?.({ tipo: "actividad" });
  return fila;
}

let emitir = null;
export const alEmitir = (f) => (emitir = f);

let escrituras = 0;
function recortar() {
  if (++escrituras % 100) return;
  const filas = leer();
  if (filas.length > MAXIMO) writeFileSync(ARCHIVO, filas.slice(-MAXIMO).map((f) => JSON.stringify(f)).join("\n") + "\n");
}

function leer() {
  if (!existsSync(ARCHIVO)) return [];
  return readFileSync(ARCHIVO, "utf8").split("\n").filter(Boolean).map((l) => {
    try { return JSON.parse(l); } catch { return null; }
  }).filter(Boolean);
}

/** Las más recientes primero. consultas=false deja fuera lo que solo mira (leer pantalla, buscar…). */
export function listar({ limite = 200, consultas = false } = {}) {
  const filas = leer();
  const deshechas = new Set(filas.filter((f) => f.deshace).map((f) => f.deshace));
  return filas
    .filter((f) => consultas || !f.consulta)
    .reverse()
    .slice(0, limite)
    .map((f) => ({ ...f, deshecha: deshechas.has(f.id), se_puede_deshacer: !!f.deshacer && !deshechas.has(f.id) }));
}

export function obtener(id) {
  return leer().find((f) => f.id === id) || null;
}
