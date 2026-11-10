// Compartir widgets, mini-apps, agentes y rutinas: la ficha (sin datos personales del estado) va
// comprimida dentro de un enlace sios://importar?d=… que también sale como QR. Al abrirlo en
// otro SIOS se enseña qué es y qué hace —y si ejecuta comandos, gasta Claude o lleva una
// ubicación— y solo se añade si el usuario lo confirma.
import { deflateRawSync, inflateRawSync } from "node:zlib";

const VERSION = 1;
const MAX = 64 * 1024; // ficha descomprimida
// Lo que es de este móvil y no viaja: estado, ids, fechas, medidas, tarjetas automáticas.
const FUERA = ["id", "creado", "actualizado", "estado", "auto", "pilaActual", "htmlHuella", "pausado", "resultado"];

function limpia(c) {
  const f = Object.fromEntries(Object.entries(c).filter(([k]) => !FUERA.includes(k)));
  // Con origen, los datos los trae la fuente: solo viaja el título.
  if (f.origen && f.datos) f.datos = f.datos.titulo ? { titulo: f.datos.titulo } : {};
  return f;
}

/** Enlace para compartir la creación `id` (con sus widgets, si es una pila). */
export function exportar(creaciones, id) {
  const c = creaciones.obtener(id);
  if (!c) throw new Error(`No existe la creación ${id}`);
  const hijos = c.plantilla === "pila" ? (c.pila || []).map((h) => creaciones.obtener(h)).filter(Boolean).map((h) => ({ ...limpia(h), _id: h.id })) : [];
  const paquete = { v: VERSION, f: limpia(c), h: hijos };
  const codigo = deflateRawSync(Buffer.from(JSON.stringify(paquete)), { level: 9 }).toString("base64url");
  return { enlace: `sios://importar?d=${codigo}`, nombre: c.nombre, tipo: c.tipo, largo: codigo.length };
}

function abrir(codigo) {
  const d = String(codigo || "").replace(/^sios:\/\/importar\?d=/, "").trim();
  if (!/^[\w-]+$/.test(d)) throw new Error("El enlace no es de SIOS o está incompleto");
  let p;
  try {
    const crudo = inflateRawSync(Buffer.from(d, "base64url"), { maxOutputLength: MAX });
    p = JSON.parse(crudo.toString("utf8"));
  } catch {
    throw new Error("El enlace está dañado o incompleto");
  }
  if (p?.v !== VERSION || !p.f?.tipo) throw new Error("Este enlace es de otra versión de SIOS");
  return p;
}

/** Lo que conviene saber antes de añadirlo (en frases para el usuario). */
function avisos(p) {
  const todas = [p.f, ...(p.h || [])];
  const a = [];
  const comandos = todas.flatMap((f) => [f.origen?.comando, f.comando, ...(f.acciones || []).map((x) => x.comando)]).filter(Boolean);
  if (comandos.length) a.push({ tipo: "comando", texto: "Ejecuta órdenes en tu móvil", detalle: comandos.map((x) => String(x).slice(0, 300)) });
  if (todas.some((f) => f.prompt || (f.acciones || []).some((x) => x.claude))) a.push({ tipo: "claude", texto: "Le pide cosas a Claude (gasta de tu plan)" });
  if (todas.some((f) => f.html)) a.push({ tipo: "html", texto: "Lleva su propia página (sin acceso a internet ni a tus datos)" });
  if (p.f.disparador?.tipo === "lugar") a.push({ tipo: "lugar", texto: "Se activa en un sitio concreto que eligió quien lo comparte" });
  if (todas.some((f) => f.origen?.fuente === "casa")) a.push({ tipo: "casa", texto: "Necesita tu Home Assistant conectado" });
  return a;
}

const NOMBRES = { widget: "Widget", app: "Mini-app", agente: "Agente", rutina: "Rutina" };

/** Vista previa para la pantalla de importar. */
export function ver(codigo) {
  const p = abrir(codigo);
  const f = p.f;
  const partes = [NOMBRES[f.tipo] || f.tipo];
  if (f.tipo === "widget") partes.push(f.plantilla === "pila" ? `pila de ${p.h.length}` : f.origen?.fuente ? `datos de «${f.origen.fuente}»` : f.plantilla, f.lugar === "panel" ? "para el panel" : "para el inicio");
  if (f.tipo === "rutina") partes.push(`cuando: ${f.disparador?.tipo}${f.disparador?.hora ? ` ${f.disparador.hora}` : ""}`);
  if (f.tipo === "agente" && f.cada) partes.push(`cada ${f.cada} min`);
  return { tipo: f.tipo, nombre: f.nombre, descripcion: partes.filter(Boolean).join(" · "), acento: f.acento || "naranja", tamano: f.tamano || null, avisos: avisos(p) };
}

/** Lo añade (primero los widgets de la pila, con ids nuevos). */
export function importar(creaciones, codigo) {
  const p = abrir(codigo);
  const ids = {};
  for (const h of p.h || []) {
    const { _id, ...ficha } = h;
    ids[_id] = creaciones.crear({ ...ficha, lugar: "pila" }).id;
  }
  const f = { ...p.f };
  if (f.plantilla === "pila") {
    f.pila = (f.pila || []).map((x) => ids[x]).filter(Boolean);
    f.reglas = (f.reglas || []).map((r) => ({ ...r, mostrar: ids[r.mostrar] })).filter((r) => r.mostrar);
  }
  if (f.tipo === "agente") delete f.widget; // su widget era de otro móvil
  return creaciones.crear(f);
}
