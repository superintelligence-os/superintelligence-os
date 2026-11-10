// Gastos y suscripciones: los cobros que avisa el banco (notificaciones y SMS), los tickets y
// facturas escaneados y lo que se anote a mano. Todo en ~/sios/gastos.json, en el móvil.
//
// Suscripciones: un comercio que cobra lo mismo (±15 %) cada ~semana, ~mes o ~año, o uno conocido
// (Netflix, Spotify…). Dos días antes de la renovación prevista, un aviso. Sin gastar Claude: el
// importe y el comercio se sacan con reglas.
import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { puente } from "./movil.mjs";

const ARCHIVO = join(homedir(), "sios", "gastos.json");
const DIA = 86_400_000;

function leer() {
  try {
    const d = JSON.parse(readFileSync(ARCHIVO, "utf8"));
    return { movimientos: d.movimientos || [], avisados: d.avisados || {}, ignorados: d.ignorados || [] };
  } catch {
    return { movimientos: [], avisados: {}, ignorados: [] };
  }
}
const guardar = (d) => writeFileSync(ARCHIVO, JSON.stringify(d));

// ---------- Leer un aviso del banco ----------

const IMPORTE = /(?:(?:€|EUR)\s?(\d{1,3}(?:[.\s]\d{3})*(?:,\d{1,2})?|\d+(?:[.,]\d{1,2})?))|(?:(\d{1,3}(?:[.\s]\d{3})*(?:,\d{1,2})|\d+(?:[.,]\d{1,2})?)\s?(?:€|EUR|euros?))/i;
const ES_PAGO = /(compra|pago|cargo|cobro|has pagado|pagado|adeudo|recibo|domiciliaci|suscripci|renovaci|cuota|retirada|bizum enviado|has enviado|transferencia emitida|tarjeta)/i;
const ES_INGRESO = /(ingreso|abono|has recibido|bizum recibido|te han enviado|devoluci|reembolso|nómina|nomina|transferencia recibida)/i;
const NO_ES = /(código|codigo|clave|contraseña|verifica|oferta|descuento|promoci|gana|sorteo|préstamo preconcedido)/i;
// Lo que pide pagar (o huele a estafa) no es un gasto hecho: «pague», «pendiente», «aduana», enlaces…
const PIDE = /(pague|abone|paga ahora|pendiente|retenid|aduana|reclam|deuda|haga clic|pulsa|https?:\/\/|www\.|bit\.ly)/i;

const SUSCRIPCIONES = /(netflix|spotify|disney|hbo|max\b|prime video|amazon prime|apple\.com|icloud|google one|google storage|youtube premium|dazn|movistar|vodafone|orange|digi|o2|masmovil|jazztel|filmin|audible|kindle unlimited|chatgpt|openai|anthropic|claude\.ai|microsoft 365|xbox|playstation|nintendo|adobe|canva|notion|dropbox|basic-fit|gimnasio|gym|patreon|twitch)/i;

const numero = (s) => Number(String(s).replace(/[.\s](?=\d{3}\b)/g, "").replace(",", "."));

/** Comercio a partir del texto: «Compra … en MERCADONA», «Pago a Netflix», «recibo ENDESA», «de Ana». */
function comercio(texto) {
  const t = String(texto).replace(/\s+/g, " ");
  const conocida = t.match(SUSCRIPCIONES)?.[0];
  if (conocida) return conocida.charAt(0).toUpperCase() + conocida.slice(1).toLowerCase().replace(/\.(com|es)$/, "");
  const NOMBRE = "([A-Za-zÁÉÍÓÚÑáéíóúñ][\\wÁÉÍÓÚÑáéíóúñ.&'*-]*(?:\\s+(?!con\\b|de\\b|por\\b|el\\b|tu\\b)[A-Za-zÁÉÍÓÚÑáéíóúñ0-9][\\wÁÉÍÓÚÑáéíóúñ.&'*-]*){0,2})";
  for (const prefijo of ["\\ben", "\\b(?:recibo|domiciliaci[oó]n|adeudo)(?:\\s+de)?", "\\ba", "\\bcomercio:?", "\\bde"]) {
    const m = t.match(new RegExp(`${prefijo}\\s+${NOMBRE}`, "i"));
    if (!m) continue;
    let nombre = m[1].replace(/[.*]+$/, "").replace(/\b(S\.?L\.?|S\.?A\.?|SL|SA)\b\.?/g, "").trim();
    if (/^(tu|su|la|el|los|las|cuenta|tarjeta|euros?|eur)$/i.test(nombre)) continue;
    if (nombre.length > 2 && nombre === nombre.toUpperCase()) nombre = nombre.toLowerCase().replace(/(^|\s)\S/g, (x) => x.toUpperCase());
    return nombre;
  }
  return "Sin comercio";
}

const clave = (c) => String(c).toLowerCase().normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[^a-z0-9]+/g, " ").trim().split(" ").slice(0, 2).join(" ");

/** ¿Es un cobro? → {importe, comercio, ingreso}. null si no lo parece. */
export function leerAviso(texto) {
  const t = String(texto || "");
  if ((NO_ES.test(t) && !ES_PAGO.test(t)) || PIDE.test(t)) return null;
  const m = t.match(IMPORTE);
  if (!m) return null;
  const importe = numero(m[1] || m[2]);
  if (!Number.isFinite(importe) || importe <= 0 || importe > 50_000) return null;
  const ingreso = ES_INGRESO.test(t);
  if (!ingreso && !ES_PAGO.test(t)) return null;
  return { importe, comercio: comercio(t), ingreso };
}

/** Un movimiento nuevo (sin repetir el mismo aviso). Devuelve el movimiento o null. */
export function anotar({ importe, comercio: c, cuando = Date.now(), origen = "manual", texto = "", ingreso = false, categoria }) {
  const d = leer();
  const huella = createHash("sha1").update(`${Math.round(importe * 100)}|${clave(c)}|${Math.floor(cuando / (10 * 60_000))}`).digest("hex").slice(0, 12);
  if (d.movimientos.some((x) => x.id === huella)) return null;
  c = String(c || "").replace(/[\s,]*\b(S\.?A\.?U\.?|S\.?L\.?U?\.?|S\.?A\.?)$/i, "").trim();
  const mov = { id: huella, cuando, importe: Math.round(importe * 100) / 100, comercio: c || "Sin comercio", ingreso: !!ingreso, origen, ...(categoria ? { categoria } : {}), texto: String(texto).slice(0, 200) };
  d.movimientos.push(mov);
  d.movimientos = d.movimientos.filter((x) => Date.now() - x.cuando < 400 * DIA).sort((a, b) => a.cuando - b.cuando);
  guardar(d);
  return mov;
}

export function borrar(id) {
  const d = leer();
  d.movimientos = d.movimientos.filter((x) => x.id !== id);
  guardar(d);
}

/** Desde la app: un aviso nuevo de una app de banco o de pagos. */
export function aviso({ app = "", titulo = "", texto = "", cuando = Date.now() }) {
  const l = leerAviso(`${titulo} ${texto}`);
  if (!l) return null;
  return anotar({ ...l, cuando, origen: app || "aviso", texto: `${titulo}: ${texto}` });
}

// ---------- Resumen y suscripciones ----------

const inicioMes = (f = new Date()) => new Date(f.getFullYear(), f.getMonth(), 1).getTime();

export function movimientos(dias = 31) {
  return leer().movimientos.filter((x) => Date.now() - x.cuando < dias * DIA);
}

/** Suscripciones detectadas: {comercio, importe, periodo (días), ultimo, proximo}. */
export function suscripciones() {
  const d = leer();
  const grupos = new Map();
  for (const m of d.movimientos.filter((x) => !x.ingreso)) {
    const k = clave(m.comercio);
    if (!grupos.has(k)) grupos.set(k, []);
    grupos.get(k).push(m);
  }
  const lista = [];
  for (const [k, ms] of grupos) {
    if (d.ignorados.includes(k)) continue;
    const u = ms.at(-1);
    const parecidos = ms.filter((m) => Math.abs(m.importe - u.importe) <= u.importe * 0.15);
    let periodo = null;
    if (parecidos.length >= 2) {
      const huecos = parecidos.slice(1).map((m, i) => (m.cuando - parecidos[i].cuando) / DIA);
      const medio = huecos.reduce((a, b) => a + b, 0) / huecos.length;
      periodo = [7, 14, 30, 91, 365].find((p) => Math.abs(medio - p) <= p * 0.2) || null;
      if (periodo === 30) periodo = 30.4;
    } else if (SUSCRIPCIONES.test(u.comercio)) periodo = 30.4; // conocida: mensual mientras no se vea otra cosa
    if (!periodo) continue;
    const proximo = u.cuando + periodo * DIA;
    if (Date.now() - u.cuando > periodo * DIA * 1.6) continue; // ya no se cobra
    lista.push({ clave: k, comercio: u.comercio, importe: u.importe, periodo: Math.round(periodo), ultimo: u.cuando, proximo });
  }
  return lista.sort((a, b) => a.proximo - b.proximo);
}

export function ignorarSuscripcion(comercioOclave) {
  const d = leer();
  const k = clave(comercioOclave);
  if (!d.ignorados.includes(k)) d.ignorados.push(k);
  guardar(d);
}

const euros = (n) => `${n.toLocaleString("es-ES", { minimumFractionDigits: n % 1 ? 2 : 0, maximumFractionDigits: 2 })} €`;
const fecha = (ms) => new Date(ms).toLocaleDateString("es-ES", { day: "numeric", month: "short" });

/** Para el widget y para Claude. */
export function resumen() {
  const todos = leer().movimientos.filter((x) => !x.ingreso);
  const desde = inicioMes();
  const ahora = new Date();
  const desdeAntes = inicioMes(new Date(ahora.getFullYear(), ahora.getMonth() - 1, 1));
  const esteMes = todos.filter((x) => x.cuando >= desde);
  // El mes pasado, hasta el mismo día (para comparar igual con igual).
  const mismoDia = new Date(ahora.getFullYear(), ahora.getMonth() - 1, ahora.getDate(), 23, 59).getTime();
  const mesPasado = todos.filter((x) => x.cuando >= desdeAntes && x.cuando < desde && x.cuando <= mismoDia);
  const suma = (l) => l.reduce((a, x) => a + x.importe, 0);
  const porComercio = new Map();
  for (const x of esteMes) porComercio.set(x.comercio, (porComercio.get(x.comercio) || 0) + x.importe);
  const semanas = [3, 2, 1, 0].map((s) => {
    const fin = Date.now() - s * 7 * DIA;
    return { x: s === 0 ? "esta" : `-${s}`, y: Math.round(suma(todos.filter((x) => x.cuando > fin - 7 * DIA && x.cuando <= fin))) };
  });
  return {
    mes: Math.round(suma(esteMes) * 100) / 100,
    mes_pasado: Math.round(suma(mesPasado) * 100) / 100,
    movimientos: esteMes.length,
    top: [...porComercio].sort((a, b) => b[1] - a[1]).slice(0, 6).map(([c, i]) => ({ comercio: c, importe: Math.round(i * 100) / 100 })),
    semanas,
    suscripciones: suscripciones(),
    euros, fecha,
  };
}

// ---------- Avisos de renovación ----------

async function vigilarRenovaciones() {
  const d = leer();
  for (const s of suscripciones()) {
    const faltan = (s.proximo - Date.now()) / DIA;
    const id = `${s.clave}:${new Date(s.proximo).toISOString().slice(0, 10)}`;
    if (faltan > 0 && faltan <= 2.2 && !d.avisados[id]) {
      await puente("notificar", {
        id: `renovacion-${s.clave}`, titulo: `${s.comercio} se renueva ${faltan < 1.2 ? "mañana" : "pasado mañana"}`,
        texto: `${euros(s.importe)}${s.periodo >= 360 ? " al año" : s.periodo <= 8 ? " a la semana" : " al mes"}. Si ya no lo usas, es buen momento para darlo de baja.`,
      }).catch(() => {});
      d.avisados[id] = Date.now();
      guardar(d);
    }
  }
}

/** Al arrancar: los SMS del banco de los últimos 60 días (una vez) y, cada hora, las renovaciones. */
export function arrancarGastos(textosSms) {
  const marca = join(homedir(), "sios", ".gastos-sms");
  (async () => {
    try {
      readFileSync(marca);
    } catch {
      for (const s of (await textosSms(60).catch(() => [])) || []) {
        const l = leerAviso(s.texto);
        if (l) anotar({ ...l, cuando: s.cuando || Date.now(), origen: "sms", texto: s.texto });
      }
      writeFileSync(marca, new Date().toISOString());
    }
  })();
  setTimeout(() => vigilarRenovaciones().catch(() => {}), 60_000);
  setInterval(() => vigilarRenovaciones().catch(() => {}), 3600_000).unref?.();
}
