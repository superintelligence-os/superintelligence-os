// Recordatorios por contexto: «recuérdame comprar pilas cuando llegue al súper», «…cuando me suba
// al coche», «…al llegar a casa». Son rutinas de una sola vez (una_vez) con un aviso. Para un tipo
// de sitio (supermercado, farmacia…) se ponen como puntos los más cercanos (OpenStreetMap); para una
// dirección, ese sitio. Nada de esto gasta Claude.
import { puente } from "./movil.mjs";

// Tipo de sitio → filtro de Overpass y palabras para Nominatim (si Overpass no responde).
const SUPER = ["supermercado", "Mercadona", "Lidl", "Aldi", "Carrefour", "Dia", "Consum", "Eroski", "Alcampo", "Supeco", "Coviran", "Spar", "Unide", "Masymas", "Hiperber"];
const TIPOS = {
  supermercado: ['["shop"~"supermarket|convenience"]', SUPER], super: ['["shop"~"supermarket|convenience"]', SUPER],
  farmacia: ['["amenity"="pharmacy"]', ["farmacia"]], gasolinera: ['["amenity"="fuel"]', ["gasolinera", "Repsol", "Cepsa", "Moeve", "BP", "Galp", "Ballenoil", "Plenoil"]],
  panaderia: ['["shop"="bakery"]', ["panadería", "horno"]], ferreteria: ['["shop"~"hardware|doityourself"]', ["ferretería", "Leroy Merlin", "Bricomart"]],
  banco: ['["amenity"="bank"]', ["banco", "Santander", "BBVA", "CaixaBank", "Sabadell", "Cajamar", "ING", "Unicaja"]], cajero: ['["amenity"="atm"]', ["cajero"]],
  correos: ['["amenity"="post_office"]', ["Correos"]], estanco: ['["shop"="tobacco"]', ["estanco"]], libreria: ['["shop"~"books|stationery"]', ["librería", "papelería"]],
  gimnasio: ['["leisure"="fitness_centre"]', ["gimnasio"]], hospital: ['["amenity"="hospital"]', ["hospital"]],
  centro_de_salud: ['["amenity"~"clinic|doctors"]', ["centro de salud", "consultorio"]], colegio: ['["amenity"="school"]', ["colegio", "escuela"]],
};
const sinAcentos = (s) => String(s).normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().trim();

async function aqui() {
  const u = await puente("ubicacion").catch(() => null);
  if (!Number.isFinite(u?.lat)) throw new Error("No sé dónde estás ahora (activa la ubicación)");
  return u;
}

const UA = { "user-agent": "SIOS/0.1 (recordatorios; sios)", accept: "application/json" };
const distancia = ({ lat, lon }) => (a) => Math.hypot(a.lat - lat, (a.lon - lon) * Math.cos((lat * Math.PI) / 180));

/** Los sitios de un tipo más cercanos (hasta 12, a menos de 6 km): Overpass y, si no responde, Nominatim. */
async function cercanos([filtro, palabras], yo) {
  const { lat, lon } = yo;
  const q = `[out:json][timeout:8];nwr${filtro}(around:6000,${lat},${lon});out center 40;`;
  const ordenar = (l) => l.sort((a, b) => distancia(yo)(a) - distancia(yo)(b)).slice(0, 12);
  // Los dos servidores de Overpass a la vez; el primero que conteste con algo, vale.
  const overpass = Promise.any(["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"].map(async (url) => {
    const r = await fetch(url, { method: "POST", body: new URLSearchParams({ data: q }), signal: AbortSignal.timeout(9000), headers: UA });
    if (!r.ok) throw new Error(String(r.status));
    const lista = ((await r.json()).elements || []).map((e) => ({ lat: e.lat ?? e.center?.lat, lon: e.lon ?? e.center?.lon, nombre: e.tags?.name })).filter((p) => Number.isFinite(p.lat));
    if (!lista.length) throw new Error("vacío");
    return lista;
  })).catch(() => null);
  // Mientras, Nominatim por palabras en un cuadro de ~6 km (una petición por segundo, como piden).
  const nominatim = (async () => {
    const caja = `${lon - 0.07},${lat + 0.055},${lon + 0.07},${lat - 0.055}`;
    const vistos = new Map();
    for (const palabra of palabras.slice(0, 7)) {
      try {
        const r = await fetch(`https://nominatim.openstreetmap.org/search?format=json&limit=10&bounded=1&viewbox=${caja}&q=${encodeURIComponent(palabra)}`, { signal: AbortSignal.timeout(6000), headers: UA });
        for (const p of r.ok ? await r.json() : []) vistos.set(p.osm_id, { lat: Number(p.lat), lon: Number(p.lon), nombre: p.name || p.display_name?.split(",")[0] });
      } catch {}
      if (vistos.size >= 12) break;
      await new Promise((ok) => setTimeout(ok, 1050));
    }
    return [...vistos.values()];
  })();
  const deOverpass = await overpass;
  return ordenar(deOverpass || (await nominatim));
}

async function geocodificar(direccion, cerca) {
  const vb = cerca ? `&viewbox=${cerca.lon - 0.5},${cerca.lat + 0.5},${cerca.lon + 0.5},${cerca.lat - 0.5}` : "";
  const r = await fetch(`https://nominatim.openstreetmap.org/search?format=json&limit=1&q=${encodeURIComponent(direccion)}${vb}`, { signal: AbortSignal.timeout(15000), headers: { "user-agent": "SIOS/0.1 (recordatorios)" } });
  const [p] = r.ok ? await r.json() : [];
  if (!p) throw new Error(`No encuentro «${direccion}»`);
  return { lat: Number(p.lat), lon: Number(p.lon), nombre: p.display_name?.split(",")[0] };
}

/**
 * cuando: {lugar: tipo o dirección, al: entrar|salir} | {bluetooth: nombre, conectado} | {wifi: red, conectado}
 * | {carga: true}. Devuelve la rutina creada.
 */
export async function recordar(creaciones, { texto, cuando = {} }) {
  if (!String(texto || "").trim()) throw new Error("Falta qué recordar");
  let disparador, donde;
  if (cuando.lugar) {
    const tipo = TIPOS[sinAcentos(cuando.lugar).replace(/^(el|la|un|una)\s+/, "").replace(/\s+/g, "_")];
    const yo = await aqui().catch(() => null);
    if (tipo) {
      if (!yo) throw new Error("Para «cualquier " + cuando.lugar + "» necesito saber dónde estás: activa la ubicación");
      const puntos = await cercanos(tipo, yo);
      if (!puntos.length) throw new Error(`No hay ningún sitio de tipo «${cuando.lugar}» a menos de 6 km`);
      disparador = { tipo: "lugar", lat: puntos[0].lat, lon: puntos[0].lon, puntos: puntos.map(({ lat, lon }) => ({ lat, lon })), radio: 120, al: cuando.al || "entrar", nombre_lugar: cuando.lugar };
      donde = `al ${cuando.al === "salir" ? "salir de" : "llegar a"} ${puntos.length === 1 ? (puntos[0].nombre || cuando.lugar) : `cualquier ${cuando.lugar} (${puntos.length} sitios cerca)`}`;
    } else {
      const p = await geocodificar(cuando.lugar, yo);
      disparador = { tipo: "lugar", lat: p.lat, lon: p.lon, radio: 150, al: cuando.al || "entrar", nombre_lugar: p.nombre || cuando.lugar };
      donde = `al ${cuando.al === "salir" ? "salir de" : "llegar a"} ${p.nombre || cuando.lugar}`;
    }
  } else if (cuando.bluetooth) {
    disparador = { tipo: "bluetooth", dispositivo: cuando.bluetooth, conectado: cuando.conectado !== false };
    donde = `al ${cuando.conectado === false ? "desconectar" : "conectar"} ${cuando.bluetooth}`;
  } else if (cuando.wifi) {
    disparador = { tipo: "wifi", red: cuando.wifi, conectado: cuando.conectado !== false };
    donde = `al ${cuando.conectado === false ? "salir de" : "conectarte a"} la Wi-Fi ${cuando.wifi}`;
  } else if (cuando.carga) {
    disparador = { tipo: "carga", conectado: true };
    donde = "al poner el móvil a cargar";
  } else throw new Error("Dime cuándo: lugar, bluetooth, wifi o carga (para una hora, usa una alarma o el calendario)");
  const c = creaciones.crear({ tipo: "rutina", nombre: `Recuerda: ${String(texto).slice(0, 30)}`, disparador, acciones: [{ aviso: String(texto).slice(0, 300) }], una_vez: true });
  return { id: c.id, donde };
}
