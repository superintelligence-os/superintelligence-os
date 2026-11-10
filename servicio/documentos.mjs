// Documentos escaneados: lo que Claude sacó de cada uno (tipo, comercio, importe, fechas, productos)
// en ~/sios/documentos/<id>.json. Con eso: avisos de devolución («te quedan 3 días para
// devolver las zapatillas») y de garantía, y las facturas para que Claude busque algo más barato.
import { randomUUID } from "node:crypto";
import { mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { puente } from "./movil.mjs";

const DIR = join(homedir(), "sios", "documentos");
const DIA = 86_400_000;
const iso = (ms) => new Date(ms).toISOString().slice(0, 10);
const fecha = (s) => (typeof s === "string" && /^\d{4}-\d{2}-\d{2}$/.test(s) && !Number.isNaN(Date.parse(s)) ? Date.parse(s) + 12 * 3600_000 : null);

/**
 * Guarda lo extraído y calcula los plazos: devolución (la que diga el ticket; 14 días si es una
 * compra por internet) y garantía legal de 3 años para productos (España, desde 2022).
 */
export function guardar(d, texto = "") {
  mkdirSync(DIR, { recursive: true });
  const id = randomUUID().slice(0, 8);
  const compra = fecha(d.fecha) || Date.now();
  const conProductos = ["ticket", "factura"].includes(d.tipo) && Array.isArray(d.productos) && d.productos.length > 0;
  const diasDevolucion = Number(d.devolucion_dias) > 0 ? Number(d.devolucion_dias) : d.online && conProductos ? 14 : null;
  const doc = {
    id, guardado: Date.now(), tipo: d.tipo || "otro", titulo: d.titulo || "Documento", comercio: d.comercio || null,
    importe: Number(d.importe) > 0 ? Number(d.importe) : null, fecha: iso(compra), vence: d.vence || null, caduca: d.caduca || null,
    productos: (d.productos || []).slice(0, 12).map(String), categoria: d.categoria || null, resumen: d.resumen || "",
    devolucion_hasta: diasDevolucion ? iso(compra + diasDevolucion * DIA) : null,
    garantia_hasta: conProductos && d.garantia !== false ? iso(compra + (Number(d.garantia_anos) || 3) * 365 * DIA) : null,
    texto: String(texto).slice(0, 4000), pdf: null, avisos: {},
  };
  writeFileSync(join(DIR, `${id}.json`), JSON.stringify(doc));
  return doc;
}

export function ponerPdf(id, pdf) {
  const d = uno(id);
  if (!d) return;
  d.pdf = String(pdf || "").slice(0, 500);
  writeFileSync(join(DIR, `${d.id}.json`), JSON.stringify(d));
}

export function uno(id) {
  try { return JSON.parse(readFileSync(join(DIR, `${String(id).replace(/[^\w-]/g, "")}.json`), "utf8")); } catch { return null; }
}

export function todos() {
  try {
    return readdirSync(DIR).filter((f) => f.endsWith(".json")).map((f) => { try { return JSON.parse(readFileSync(join(DIR, f), "utf8")); } catch { return null; } })
      .filter(Boolean).sort((a, b) => b.guardado - a.guardado);
  } catch { return []; }
}

/** Facturas recurrentes (luz, gas, móvil, internet, seguros…) para comparar con el mercado. */
export function facturas() {
  return todos().filter((d) => d.tipo === "factura" || d.tipo === "recibo");
}

/** Lo que está en garantía (y hasta cuándo), lo más reciente primero. */
export function garantias() {
  const hoy = Date.now();
  return todos().filter((d) => d.garantia_hasta && Date.parse(d.garantia_hasta) > hoy);
}

/** Una vez al día: devoluciones que se acaban en 3 días (y el último día) y garantías a 30 días de acabar. */
async function vigilar() {
  const hoy = Date.now();
  for (const d of todos()) {
    const avisar = async (clave, titulo, texto) => {
      if (d.avisos?.[clave]) return;
      await puente("notificar", { id: `doc-${d.id}-${clave}`, titulo, texto }).catch(() => {});
      d.avisos = { ...d.avisos, [clave]: hoy };
      writeFileSync(join(DIR, `${d.id}.json`), JSON.stringify(d));
    };
    const que = d.productos?.length ? d.productos.slice(0, 2).join(" y ").toLowerCase() : d.titulo;
    if (d.devolucion_hasta) {
      const faltan = Math.ceil((Date.parse(d.devolucion_hasta) + DIA - hoy) / DIA);
      if (faltan === 3) await avisar("dev3", `Te quedan 3 días para devolver ${que}`, `${d.comercio || d.titulo}: hasta el ${new Date(d.devolucion_hasta).toLocaleDateString("es-ES", { day: "numeric", month: "long" })}. El ticket está en Documentos › Claude.`);
      if (faltan === 1) await avisar("dev1", `Último día para devolver ${que}`, `${d.comercio || d.titulo}. El ticket está en Documentos › Claude.`);
    }
    if (d.garantia_hasta) {
      const faltan = Math.ceil((Date.parse(d.garantia_hasta) - hoy) / DIA);
      if (faltan === 30) await avisar("gar30", `La garantía de ${que} acaba en un mes`, `Si le pasa algo, aún puedes reclamar a ${d.comercio || "la tienda"} hasta el ${new Date(d.garantia_hasta).toLocaleDateString("es-ES")}.`);
    }
  }
}

export function arrancarDocumentos() {
  setTimeout(() => vigilar().catch(() => {}), 90_000);
  setInterval(() => vigilar().catch(() => {}), 6 * 3600_000).unref?.();
}
