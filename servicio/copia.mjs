// Copia de seguridad de lo que guarda el agente en ~/sios: conversaciones, creaciones y
// rutinas (con los datos de las mini-apps), ajustes, uso y actividad. La app la junta con lo suyo,
// la cifra con la contraseña del usuario y la guarda donde él elija.
// No entra la sesión de Claude (~/.claude): en un móvil nuevo se vuelve a entrar con un toque.
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { homedir, tmpdir } from "node:os";
import { termux, q } from "./movil.mjs";

const DATOS = join(homedir(), "sios");
const QUE = ["conversaciones", "creaciones", "ajustes.json", "uso.json", "actividad.jsonl"];

/** tar.gz con los datos del agente. */
export async function crear() {
  const dir = mkdtempSync(join(process.env.TMPDIR || tmpdir(), "copia-"));
  const archivo = join(dir, "agente.tar.gz");
  const hay = QUE.filter((x) => existsSync(join(DATOS, x)));
  const r = await termux(`tar -czf ${q(archivo)} -C ${q(DATOS)} ${hay.map(q).join(" ")}`, { timeout: 120000 });
  if (!r.ok) throw new Error(`No se pudo preparar la copia: ${r.salida.slice(0, 200)}`);
  const buf = readFileSync(archivo);
  rmSync(dir, { recursive: true, force: true });
  return buf;
}

/** Restaura los datos del agente desde el tar.gz (sustituye lo que hubiera). */
export async function restaurar(buf) {
  const dir = mkdtempSync(join(process.env.TMPDIR || tmpdir(), "restaurar-"));
  const archivo = join(dir, "agente.tar.gz");
  writeFileSync(archivo, buf);
  // Solo se aceptan las rutas esperadas, sin «..» ni rutas absolutas.
  const lista = await termux(`tar -tzf ${q(archivo)}`);
  if (!lista.ok) throw new Error("La copia no se puede leer");
  const malas = lista.salida.split("\n").filter(Boolean).filter((p) => p.startsWith("/") || p.split("/").includes("..") || !QUE.includes(p.replace(/^\.\//, "").split("/")[0]));
  if (malas.length) throw new Error("La copia trae archivos inesperados: no se restaura");
  for (const x of QUE) rmSync(join(DATOS, x), { recursive: true, force: true });
  const r = await termux(`tar -xzf ${q(archivo)} -C ${q(DATOS)}`, { timeout: 120000 });
  rmSync(dir, { recursive: true, force: true });
  if (!r.ok) throw new Error(`No se pudo restaurar: ${r.salida.slice(0, 200)}`);
}
