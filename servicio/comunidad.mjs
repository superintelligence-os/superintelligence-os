// Galería comunitaria de widgets (servidor en comunidad/, Cloudflare Worker). Desde aquí: ver la
// lista, publicar un widget propio (solo los de fuentes integradas, sin datos personales: el
// servidor lo vuelve a comprobar) y contar cuándo alguien añade uno. Al añadir, pasa por la misma
// pantalla de importar que un enlace compartido (se enseña qué es y se confirma).
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import * as compartir from "./compartir.mjs";

// Servidor de la galería (comunidad/, un Cloudflare Worker que monta quien distribuya SIOS). Se pone
// con SIOS_COMUNIDAD al compilar/arrancar o en ~/sios/comunidad.json; sin él, la galería no sale.
const POR_DEFECTO = process.env.SIOS_COMUNIDAD || null;

function url() {
  try {
    return JSON.parse(readFileSync(join(homedir(), "sios", "comunidad.json"), "utf8")).url || POR_DEFECTO;
  } catch {
    return POR_DEFECTO;
  }
}

async function pedir(ruta, opciones = {}) {
  if (!url()) throw new Error("La comunidad no está configurada en este móvil");
  let r;
  try {
    r = await fetch(url() + ruta, { ...opciones, signal: AbortSignal.timeout(12000), headers: { "content-type": "application/json", "user-agent": "SIOS/0.1" } });
  } catch {
    throw new Error("La comunidad no responde ahora mismo");
  }
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(d.error || (r.status === 404 ? "La comunidad aún no está en marcha" : `La comunidad respondió ${r.status}`));
  return d;
}

export const lista = (orden = "populares") => pedir(`/widgets?orden=${orden === "nuevos" ? "nuevos" : "populares"}`);
export const uno = (id) => pedir(`/widgets/${encodeURIComponent(id)}`);
export const usado = (id) => pedir(`/widgets/${encodeURIComponent(id)}/usado`, { method: "POST", body: "{}" }).catch(() => null);
export const denunciar = (id) => pedir(`/widgets/${encodeURIComponent(id)}/denunciar`, { method: "POST", body: "{}" });

/** Publica un widget propio (por su id en este móvil). */
export async function publicar(creaciones, id, descripcion = "") {
  const c = creaciones.obtener(id);
  if (c?.tipo !== "widget") throw new Error("Solo se pueden publicar widgets");
  if (c.plantilla === "html" || c.origen?.comando) throw new Error("Este widget lleva su propia página o un comando: no se puede publicar");
  if (c.plantilla !== "pila" && !c.origen?.fuente) throw new Error("Este widget tiene datos tuyos escritos a mano: no se puede publicar");
  const { enlace } = compartir.exportar(creaciones, id);
  return pedir("/widgets", { method: "POST", body: JSON.stringify({ codigo: enlace, descripcion: String(descripcion).slice(0, 140) }) });
}
