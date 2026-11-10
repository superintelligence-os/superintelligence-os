// Claves de API de cada proveedor: en ~/sios/ia/claves.json con permisos 600 (solo el
// servicio puede leerlas). Ninguna ruta del servicio las devuelve: como mucho, «hay clave» y sus
// cuatro últimos caracteres para reconocerla.
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const DIR = join(homedir(), "sios", "ia");
const ARCHIVO = join(DIR, "claves.json");
mkdirSync(DIR, { recursive: true, mode: 0o700 });

function leer() {
  try {
    return JSON.parse(readFileSync(ARCHIVO, "utf8"));
  } catch {
    return {};
  }
}

export function clave(proveedor) {
  return leer()[proveedor] || null;
}

export function ponerClave(proveedor, valor) {
  const todas = leer();
  if (valor) todas[proveedor] = String(valor).trim();
  else delete todas[proveedor];
  writeFileSync(ARCHIVO + ".tmp", JSON.stringify(todas), { mode: 0o600 });
  renameSync(ARCHIVO + ".tmp", ARCHIVO);
  chmodSync(ARCHIVO, 0o600);
}

/** Lo único que sale de aquí hacia la app: si hay clave y cómo termina. */
export function resumenClaves() {
  return Object.fromEntries(Object.entries(leer()).map(([p, v]) => [p, { tiene: true, final: String(v).slice(-4) }]));
}

/** Carpeta propia de cada motor (su HOME: sesiones, ajustes e inicio de sesión). */
export function rutaDe(motor) {
  const d = join(DIR, motor);
  if (!existsSync(d)) mkdirSync(d, { recursive: true, mode: 0o700 });
  return d;
}
