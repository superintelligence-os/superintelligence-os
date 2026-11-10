// Continuidad con el Mac: el portapapeles compartido, los avisos del móvil en el Mac, y enlaces y
// archivos de uno a otro. Solo en la red local y solo si el usuario la activa en Ajustes.
//
// Vincular: el móvil enseña un código de 6 cifras; el Mac lo escribe. Se acuerda una clave con
// X25519 (nunca viaja) y el código demuestra que el Mac es el del usuario. Después, todo va cifrado
// con AES-256-GCM (cuerpo y respuesta), con la hora dentro para que nada se pueda repetir.
//
// Servidor en el puerto 8766 (toda la red local). Rutas cifradas (POST): /estado, /eventos (espera
// hasta 25 s a que haya algo), /portapapeles, /enlace, /archivo/subir, /archivo/bajar, /desvincular.
//
// Fuera de casa (móvil perdido, otra red): las mismas rutas por Nostr (nostr.mjs), una red abierta
// de servidores gratuitos que solo ven sobres cifrados con la clave del emparejamiento. El móvil se
// queda escuchando y deja ahí su último estado (ubicación, batería, SIM) para «Encontrar mi móvil»
// en SIOS Desktop. Quien prefiera su propio servidor puede poner uno (relay/) en los ajustes.
import { createHash, createHmac, createCipheriv, createDecipheriv, createPrivateKey, createPublicKey, diffieHellman, generateKeyPairSync, hkdfSync, randomBytes, randomInt, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from "node:fs";
import { homedir, networkInterfaces } from "node:os";
import { basename, join } from "node:path";
import { adb, android, consultaContenido, puente, q, sinAcentos, termux } from "./movil.mjs";
import { registrar } from "./actividad.mjs";
import * as nostr from "./nostr.mjs";

export const PUERTO = 8766;
const DIR = join(homedir(), "sios", "continuidad");
const AJUSTES = join(DIR, "ajustes.json");
const SALIDA = join(DIR, "salida"); // archivos del móvil esperando a que el Mac los recoja
const RECIBIDOS = "/sdcard/Download/SIOS"; // lo que manda el Mac
const MAX_ARCHIVO = 25 * 1024 * 1024;
// Lo que se copia al ordenador (si se activa allí): la cámara, las imágenes y los documentos.
const CARPETAS_COPIA = ["/sdcard/DCIM", "/sdcard/Pictures", "/sdcard/Movies", "/sdcard/Documents"];
const VENTANA_MS = 90_000; // margen de reloj entre Mac y móvil

function leer() {
  try {
    const a = JSON.parse(readFileSync(AJUSTES, "utf8"));
    return {
      activo: !!a.activo, macs: a.macs || [], portapapeles: a.portapapeles !== false, avisos: a.avisos !== false,
      // «Encontrar mi móvil» fuera de casa: de serie, con un ordenador vinculado. Va por Nostr salvo
      // que se ponga un servidor propio (relay) o una lista de servidores Nostr propia.
      encontrar: a.encontrar !== false, relay: a.relay || null, nostr: Array.isArray(a.nostr) && a.nostr.length ? a.nostr : null,
    };
  } catch {
    return { activo: false, macs: [], portapapeles: true, avisos: true, encontrar: true, relay: null, nostr: null };
  }
}
let activaAntes = null;
function guardar(a) {
  mkdirSync(DIR, { recursive: true });
  writeFileSync(AJUSTES, JSON.stringify(a, null, 1), { mode: 0o600 });
  // La app lo sigue (manda los avisos y lo copiado, «Enviar al ordenador», se anuncia en la red).
  if (a.activo !== activaAntes) {
    activaAntes = a.activo;
    puente("continuidad_activa", { si: a.activo ? "1" : "0" }).catch(() => {});
  }
}

/** Lo que ve la app en Ajustes (sin claves). */
export function estado() {
  const a = leer();
  return {
    activo: a.activo, portapapeles: a.portapapeles, avisos: a.avisos, puerto: PUERTO, ip: ipLocal(),
    encontrar: a.encontrar, fuera: [...conexiones.values()].some((c) => c.abierta), perdido: leerAntirrobo().perdido,
    macs: a.macs.map(({ id, nombre, vinculado, visto }) => ({ id, nombre, vinculado, visto: visto || null })),
    codigo: codigo && codigo.hasta > Date.now() ? { codigo: codigo.valor, hasta: codigo.hasta } : null,
  };
}

export function ponerAjustes(cambios) {
  const a = leer();
  for (const k of ["activo", "portapapeles", "avisos", "encontrar"]) if (typeof cambios[k] === "boolean") a[k] = cambios[k];
  if (cambios.quitar) a.macs = a.macs.filter((m) => m.id !== cambios.quitar);
  // Avanzado: servidor propio (https://…) o servidores Nostr propios (wss://…). Vacío = de serie.
  if ("relay" in cambios) a.relay = /^https:\/\/\S+$/.test(String(cambios.relay || "")) ? String(cambios.relay) : null;
  if ("nostr" in cambios) {
    const l = (Array.isArray(cambios.nostr) ? cambios.nostr : []).map(String).filter((u) => /^wss:\/\/\S+$/.test(u)).slice(0, 8);
    a.nostr = l.length ? l : null;
  }
  guardar(a);
  a.activo ? arrancar() : parar();
  conectarRelay();
  return { ...estado(), relay: a.relay, nostr: a.nostr || nostr.RELAYS };
}

function ipLocal() {
  for (const [nombre, lista] of Object.entries(networkInterfaces())) {
    // Las VPN (el cortafuegos de SIOS es una: tun0, 10.111.222.1) no son la red de casa.
    if (/^(tun|ppp|ipsec|wg|dummy)/.test(nombre)) continue;
    for (const i of lista || []) {
      if (i.family === "IPv4" && !i.internal && !i.address.startsWith("10.111.222.") && /^(192\.168|10\.|172\.(1[6-9]|2\d|3[01]))/.test(i.address)) return i.address;
    }
  }
  return null;
}

// ---------- Vincular ----------

let codigo = null; // {valor, hasta, fallos}

/** Un código nuevo de 6 cifras, válido 5 minutos (lo enseña la app). */
export function nuevoCodigo() {
  codigo = { valor: String(randomInt(0, 1_000_000)).padStart(6, "0"), hasta: Date.now() + 5 * 60_000, fallos: 0 };
  if (!leer().activo) ponerAjustes({ activo: true });
  return estado();
}

const PREFIJO_X25519 = Buffer.from("302a300506032b656e032100", "hex"); // SPKI de una clave X25519 cruda

function vincular({ nombre, publica, prueba }) {
  if (!codigo || codigo.hasta < Date.now()) throw Object.assign(new Error("No hay ningún código activo: pide uno en el móvil"), { codigoHttp: 403 });
  const pubMac = Buffer.from(String(publica || ""), "base64");
  if (pubMac.length !== 32) throw new Error("Clave pública no válida");
  // El Mac demuestra que conoce el código sin enviarlo: HMAC(código, su clave pública).
  const esperada = createHmac("sha256", codigo.valor).update(pubMac).digest();
  const recibida = Buffer.from(String(prueba || ""), "base64");
  if (recibida.length !== esperada.length || !timingSafeEqual(recibida, esperada)) {
    if (++codigo.fallos >= 5) codigo = null;
    throw Object.assign(new Error("El código no coincide"), { codigoHttp: 403 });
  }
  const { publicKey, privateKey } = generateKeyPairSync("x25519");
  const pubMovil = publicKey.export({ type: "spki", format: "der" }).subarray(-32);
  const compartido = diffieHellman({ privateKey, publicKey: createPublicKey({ key: Buffer.concat([PREFIJO_X25519, pubMac]), format: "der", type: "spki" }) });
  const sal = Buffer.concat([Buffer.from(codigo.valor), pubMac, pubMovil]);
  const clave = Buffer.from(hkdfSync("sha256", compartido, sal, "sios continuidad", 32));
  const id = randomBytes(6).toString("hex");
  const a = leer();
  a.macs = [...a.macs.filter((m) => m.nombre !== nombre), { id, nombre: String(nombre || "Mac").slice(0, 40), clave: clave.toString("base64"), vinculado: Date.now() }];
  a.activo = true;
  guardar(a);
  codigo = null;
  // El móvil también lo demuestra: HMAC(clave, su clave pública).
  const pruebaMovil = createHmac("sha256", clave).update(pubMovil).digest("base64");
  puente("notificar", { id: "continuidad", titulo: "Ordenador vinculado", texto: `${nombre || "Tu ordenador"} ya está conectado con este móvil.` }).catch(() => {});
  return { id, publica: pubMovil.toString("base64"), prueba: pruebaMovil, movil: nombreMovil };
}

let nombreMovil = "SIOS";
android("settings get global device_name").then((s) => { if (s.trim() && s.trim() !== "null") nombreMovil = s.trim(); }).catch(() => {});

// ---------- Cifrado ----------

function cifrar(clave, obj) {
  const iv = randomBytes(12);
  const c = createCipheriv("aes-256-gcm", clave, iv);
  const datos = Buffer.concat([c.update(JSON.stringify(obj)), c.final(), c.getAuthTag()]);
  return { n: iv.toString("base64"), c: datos.toString("base64") };
}

function descifrar(clave, { n, c }) {
  const iv = Buffer.from(n, "base64");
  const todo = Buffer.from(c, "base64");
  const d = createDecipheriv("aes-256-gcm", clave, iv);
  d.setAuthTag(todo.subarray(-16));
  return JSON.parse(Buffer.concat([d.update(todo.subarray(0, -16)), d.final()]).toString("utf8"));
}

// ---------- Eventos para el Mac ----------

let seq = 0;
const eventos = []; // {seq, ...}
const esperando = new Set();

/** Algo para el Mac (lo llaman la app y el servicio). Solo si hay un Mac y está activo. */
export function evento(e) {
  const a = leer();
  if (!a.activo || !a.macs.length) return false;
  if (e.tipo === "portapapeles" && !a.portapapeles) return false;
  if (e.tipo === "aviso" && !a.avisos) return false;
  eventos.push({ ...e, seq: ++seq, cuando: Date.now() });
  while (eventos.length > 100) eventos.shift();
  for (const f of esperando) f();
  return true;
}

/** El móvil manda un archivo al Mac: se guarda hasta que lo recoja (1 h). */
export function archivoParaMac(nombre, datos) {
  if (datos.length > MAX_ARCHIVO) throw new Error("El archivo pasa de 25 MB");
  mkdirSync(SALIDA, { recursive: true });
  const id = randomBytes(8).toString("hex");
  writeFileSync(join(SALIDA, id), datos);
  writeFileSync(join(SALIDA, `${id}.nombre`), basename(String(nombre || "archivo")));
  return evento({ tipo: "archivo", id, nombre: basename(String(nombre || "archivo")), tamano: datos.length });
}

function limpiarSalida() {
  if (!existsSync(SALIDA)) return;
  for (const f of readdirSync(SALIDA)) {
    try { if (Date.now() - statSync(join(SALIDA, f)).mtimeMs > 3600_000) rmSync(join(SALIDA, f), { force: true }); } catch {}
  }
}

// ---------- Rutas del Mac ----------

async function atender(ruta, d, mac) {
  switch (ruta) {
    case "/estado": {
      const b = await android("dumpsys battery | grep -E 'level|AC powered|USB powered'").catch(() => "");
      return { movil: nombreMovil, bateria: Number(b.match(/level: (\d+)/)?.[1]) || null, cargando: /powered: true/.test(b), seq };
    }
    case "/eventos": {
      const desde = Number(d.desde) || 0;
      const pendientes = () => eventos.filter((e) => e.seq > desde);
      if (!pendientes().length && desde <= seq) {
        await new Promise((ok) => {
          const fin = () => { esperando.delete(fin); clearTimeout(t); ok(); };
          const t = setTimeout(fin, 25_000);
          esperando.add(fin);
        });
      }
      return { seq, eventos: pendientes() };
    }
    case "/portapapeles": {
      const texto = String(d.texto || "").slice(0, 100_000);
      if (texto) await puente("portapapeles_poner", { texto });
      return { ok: true };
    }
    case "/enlace": {
      const url = String(d.url || "");
      if (!/^https?:\/\//i.test(url)) throw new Error("Solo enlaces web");
      await android(`am start -a android.intent.action.VIEW -d ${q(url)}`);
      return { ok: true };
    }
    case "/archivo/subir": {
      const nombre = basename(String(d.nombre || "archivo")).replace(/[^\w.\- ()áéíóúñÁÉÍÓÚÑ]/g, "_") || "archivo";
      const datos = Buffer.from(String(d.datos || ""), "base64");
      if (datos.length > MAX_ARCHIVO) throw new Error("El archivo pasa de 25 MB");
      const tmp = join(DIR, `entrada-${randomBytes(4).toString("hex")}`);
      writeFileSync(tmp, datos);
      // La shell no puede leer la carpeta de Termux: adb push la lee desde aquí y la escribe como shell.
      try {
        await android(`mkdir -p ${RECIBIDOS}`);
        await adb(["push", tmp, `${RECIBIDOS}/${nombre}`]);
        await android(`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d ${q(`file://${RECIBIDOS}/${nombre}`)} >/dev/null`);
      } finally {
        rmSync(tmp, { force: true });
      }
      puente("notificar", { id: `mac-${nombre}`, titulo: `${mac.nombre} te ha enviado un archivo`, texto: `${nombre} · en Descargas › Claude` }).catch(() => {});
      return { ok: true, ruta: `${RECIBIDOS}/${nombre}` };
    }
    case "/archivo/bajar": {
      const id = String(d.id || "").replace(/[^a-f0-9]/g, "");
      const f = join(SALIDA, id);
      if (!id || !existsSync(f)) throw new Error("Ese archivo ya no está");
      const datos = readFileSync(f);
      const nombre = readFileSync(`${f}.nombre`, "utf8");
      rmSync(f, { force: true });
      rmSync(`${f}.nombre`, { force: true });
      return { nombre, datos: datos.toString("base64") };
    }
    // ---------- Encontrar mi móvil ----------
    case "/ubicacion":
      return await estadoAntirrobo();
    case "/perdido": {
      // Modo perdido: mensaje y teléfono en la pantalla de bloqueo, ubicación más a menudo.
      const p = { perdido: !!d.si, mensaje: String(d.mensaje || "").slice(0, 200), telefono: String(d.telefono || "").slice(0, 30) };
      guardarAntirrobo(p);
      await puente("perdido", { si: p.perdido ? "1" : "0", mensaje: p.mensaje, telefono: p.telefono }).catch(() => {});
      registrar({ origen: "ordenador", herramienta: "antirrobo", frase: p.perdido ? `${mac.nombre} activó el modo perdido` : `${mac.nombre} quitó el modo perdido` });
      programarUltima();
      enviarUltima().catch(() => {});
      return { ok: true, ...p };
    }
    case "/bloquear": {
      // Bloquea ya (pantalla apagada: hará falta el PIN) y, si llega, enseña el mensaje de perdido.
      if (d.mensaje || d.telefono) {
        guardarAntirrobo({ perdido: true, mensaje: String(d.mensaje || "").slice(0, 200), telefono: String(d.telefono || "").slice(0, 30) });
        await puente("perdido", { si: "1", mensaje: String(d.mensaje || ""), telefono: String(d.telefono || "") }).catch(() => {});
        programarUltima();
      }
      await android("input keyevent KEYCODE_SLEEP").catch(() => {});
      registrar({ origen: "ordenador", herramienta: "antirrobo", frase: `${mac.nombre} bloqueó el móvil a distancia` });
      return { ok: true };
    }
    case "/borrar": {
      // Borrar todo: no tiene vuelta atrás. El ordenador obliga a escribir BORRAR y aquí se exige igual.
      if (d.confirmacion !== "BORRAR") throw Object.assign(new Error("Falta la confirmación"), { codigoHttp: 400 });
      registrar({ origen: "ordenador", herramienta: "antirrobo", frase: `${mac.nombre} borró el móvil a distancia` });
      await puente("borrar_todo", {}).catch(() => {});
      return { ok: true };
    }
    // Buscar el móvil: suena a todo volumen aunque esté en silencio, hasta que se toque.
    case "/sonar":
      await puente("sonar", { de: mac.nombre });
      return { ok: true };
    case "/contactos": {
      const t = sinAcentos(String(d.texto || "")).trim();
      if (!t) return { contactos: [] };
      const filas = await consultaContenido("content://com.android.contacts/data/phones", ["display_name", "data1"]).catch(() => []);
      const vistos = new Set();
      const contactos = filas
        .filter((f) => sinAcentos(f.display_name || "").includes(t) || (f.data1 || "").replace(/\s/g, "").includes(t))
        .map((f) => ({ nombre: f.display_name, numero: f.data1 }))
        .filter((c) => !vistos.has(c.nombre + c.numero) && vistos.add(c.nombre + c.numero))
        .slice(0, 8);
      return { contactos };
    }
    case "/sms": {
      const numero = String(d.numero || "").replace(/[^\d+]/g, "");
      const t = String(d.texto || "").trim().slice(0, 1600);
      if (numero.length < 3 || !t) throw new Error("Falta el número o el texto");
      const r = await termux(`termux-sms-send -n ${q(numero)} ${q(t)}`);
      if (!r.ok) throw new Error("El móvil no ha podido enviar el SMS");
      registrar({ origen: "usuario", herramienta: "sms_enviar", frase: `SMS a ${d.nombre || numero} desde ${mac.nombre}` });
      return { ok: true };
    }
    case "/llamar": {
      const numero = String(d.numero || "").replace(/[^\d+*#]/g, "");
      if (numero.length < 3) throw new Error("Número no válido");
      await android(`am start -a android.intent.action.CALL -d ${q(`tel:${numero}`)}`);
      registrar({ origen: "usuario", herramienta: "llamar", frase: `Llamada a ${d.nombre || numero} desde ${mac.nombre}` });
      return { ok: true };
    }
    // El panel de widgets del móvil, para pintarlo en el ordenador.
    case "/panel": {
      if (!creaciones) return { titulo: null, tarjetas: [] };
      const p = await creaciones.panelAhora();
      const tarjetas = p.orden.map((id) => creaciones.obtener(id)).filter(Boolean).flatMap((c) => {
        const w = c.plantilla === "pila" ? creaciones.obtener(c.pilaActual || c.pila?.[0]) : c;
        if (!w || w.plantilla === "html") return [];
        return [{ id: w.id, nombre: w.nombre, plantilla: w.plantilla, tamano: c.tamano || w.tamano, acento: w.acento, datos: w.datos || {}, actualizado: w.estado?.ultima || null }];
      });
      return { titulo: p.titulo, tarjetas };
    }
    // Contestar un mensaje desde el ordenador (la respuesta rápida de la notificación del móvil).
    case "/responder": {
      const clave = String(d.clave || ""), t = String(d.texto || "").trim().slice(0, 2000);
      if (!clave || !t) throw new Error("Falta el mensaje o la respuesta");
      const r = await puente("responder", { clave, texto: t });
      if (!r.enviado) throw new Error("Ese mensaje ya no se puede contestar desde aquí (ábrelo en el móvil)");
      registrar({ origen: "usuario", herramienta: "responder_mensaje", frase: `Respuesta desde ${mac.nombre}` });
      return { ok: true };
    }
    // Copias al ordenador: qué hay nuevo (fotos, vídeos, documentos) y cada archivo a trozos de 4 MB.
    case "/copia/lista": {
      const desde = Math.floor((Number(d.desde) || 0) / 1000);
      const salida = await android(`find ${CARPETAS_COPIA.join(" ")} -type f ! -name '.*' ! -path '*/.thumbnails/*' ! -path '*/.trashed*' -newermt @${desde} -exec stat -c '%Y|%s|%n' {} + 2>/dev/null | sort -n | head -n 400`).catch(() => "");
      const archivos = salida.split("\n").filter(Boolean).map((l) => { const [t, s, ...r] = l.split("|"); return { ruta: r.join("|"), tamano: Number(s), cuando: Number(t) * 1000 }; })
        .filter((a) => a.ruta && a.tamano > 0 && a.tamano < 4 * 1024 ** 3);
      return { archivos };
    }
    case "/copia/trozo": {
      // «archivo», no «ruta»: «ruta» es la de la petición (va cifrada con ella).
      const ruta = String(d.archivo || "");
      if (!CARPETAS_COPIA.some((c) => ruta.startsWith(c + "/")) || ruta.includes("/../")) throw new Error("Ruta no permitida");
      const n = Math.max(0, Math.floor(Number(d.trozo) || 0));
      const datos = await adb(["exec-out", `dd if=${q(ruta)} bs=4194304 skip=${n} count=1 2>/dev/null`], true);
      return { datos: Buffer.from(datos).toString("base64"), trozo: n };
    }
    case "/ia": case "/ia/elegir": case "/ia/modelo": case "/ia/modelos": case "/ia/clave": case "/ia/entrar":
    case "/ia/codigo": case "/ia/cancelar": case "/ia/salir": case "/ia/instalar": case "/ia/local/descargar": case "/ia/local/borrar": case "/ia/nombre": {
      // Elegir la IA e iniciar sesión desde el ordenador: lo mismo que en Ajustes del móvil. La clave
      // viaja cifrada por este canal y se guarda solo en el móvil.
      const lectura = ruta === "/ia" || ruta === "/ia/modelos";
      const r = await fetch(`http://127.0.0.1:${process.env.PUERTO || 8765}${ruta}${ruta === "/ia/modelos" ? `?id=${encodeURIComponent(d.id || "")}` : ""}`, {
        method: lectura ? "GET" : "POST",
        headers: { "content-type": "application/json" },
        body: lectura ? undefined : JSON.stringify(d),
      });
      const datos = await r.json();
      if (!r.ok) throw new Error(datos.error || `Error ${r.status}`);
      return datos;
    }
    case "/desvincular": {
      const a = leer();
      a.macs = a.macs.filter((m) => m.id !== mac.id);
      guardar(a);
      return { ok: true };
    }
    default:
      throw Object.assign(new Error("Ruta desconocida"), { codigoHttp: 404 });
  }
}

async function cuerpo(req, max) {
  const trozos = [];
  let n = 0;
  for await (const t of req) {
    n += t.length;
    if (n > max) throw Object.assign(new Error("Demasiado grande"), { codigoHttp: 413 });
    trozos.push(t);
  }
  return JSON.parse(Buffer.concat(trozos).toString("utf8") || "{}");
}

let servidor = null;

function arrancar() {
  if (servidor) return;
  servidor = createServer(async (req, res) => {
    const responder = (codigoHttp, obj) => { res.writeHead(codigoHttp, { "content-type": "application/json" }); res.end(JSON.stringify(obj)); };
    try {
      const ruta = new URL(req.url, "http://x").pathname;
      if (req.method !== "POST") return responder(405, { error: "POST" });
      if (ruta === "/vincular") return responder(200, vincular(await cuerpo(req, 4096)));
      const mac = leer().macs.find((m) => m.id === req.headers["x-claude-mac"]);
      if (!mac) return responder(401, { error: "Este ordenador no está vinculado" });
      const clave = Buffer.from(mac.clave, "base64");
      let d;
      try {
        d = descifrar(clave, await cuerpo(req, MAX_ARCHIVO * 1.4 + 4096));
      } catch {
        return responder(401, { error: "No se ha podido descifrar" });
      }
      // La ruta y la hora van dentro de lo cifrado: no se puede reutilizar una petición en otra ruta ni más tarde.
      if (d.ruta !== ruta || Math.abs(Date.now() - Number(d.t)) > VENTANA_MS) return responder(401, { error: "Petición caducada" });
      const a = leer();
      const m = a.macs.find((x) => x.id === mac.id);
      if (m && (!m.visto || Date.now() - m.visto > 60_000)) { m.visto = Date.now(); guardar(a); }
      const r = await atender(ruta, d, mac);
      responder(200, cifrar(clave, { ...r, t: Date.now() }));
    } catch (e) {
      responder(e.codigoHttp || 400, { error: String(e.message || e) });
    }
  });
  servidor.on("error", (e) => { console.error("[continuidad]", e.message); servidor = null; });
  servidor.listen(PUERTO, "0.0.0.0", () => console.log(`Continuidad con el Mac en el puerto ${PUERTO}`));
}

function parar() {
  servidor?.close();
  servidor = null;
  for (const f of esperando) f();
}

// ---------- Fuera de casa: Nostr (de serie) o un servidor propio ----------

// Servidor propio opcional (relay/, Cloudflare Worker). Sin él, Nostr: gratis y sin montar nada.
const RELAY_PROPIO = process.env.SIOS_RELAY || null;
const ANTIRROBO = join(DIR, "antirrobo.json");
const HECHAS = join(DIR, "ordenes-hechas.json");
const conexiones = new Map(); // id del Mac → { abierta, cerrar, cerrarYa(), publicar?(ev), ids? }

/** Buzón y llave del emparejamiento: salen de la clave y el servidor no puede deducirla de ellos. */
export function buzonDe(claveB64) {
  const k = Buffer.from(claveB64, "base64");
  const derivar = (que, n) => Buffer.from(hkdfSync("sha256", k, Buffer.from("sios-relay"), que, n)).toString("hex");
  return { id: derivar("buzon", 16), llave: derivar("llave", 32) };
}

const relayUrl = () => String(leer().relay || RELAY_PROPIO || "").replace(/\/+$/, "");
const relaysNostr = () => leer().nostr || nostr.RELAYS;

function leerAntirrobo() {
  try { return { perdido: false, mensaje: "", telefono: "", ...JSON.parse(readFileSync(ANTIRROBO, "utf8")) }; }
  catch { return { perdido: false, mensaje: "", telefono: "" }; }
}
function guardarAntirrobo(p) {
  mkdirSync(DIR, { recursive: true });
  writeFileSync(ANTIRROBO, JSON.stringify({ ...leerAntirrobo(), ...p }), { mode: 0o600 });
}

/** Lo que ve «Encontrar mi móvil»: dónde está, batería, SIM y si está en modo perdido. */
async function estadoAntirrobo() {
  const [ubicacion, b, sim] = await Promise.all([
    puente("ubicacion").catch(() => null),
    android("dumpsys battery | grep -E 'level|AC powered|USB powered'").catch(() => ""),
    puente("sim").catch(() => null),
  ]);
  const p = leerAntirrobo();
  return {
    movil: nombreMovil, ubicacion, sim,
    bateria: Number(b.match(/level: (\d+)/)?.[1]) || null, cargando: /powered: true/.test(b),
    perdido: p.perdido, mensaje: p.mensaje, telefono: p.telefono, cuando: Date.now(),
    // Intentos de desbloqueo con foto: solo la última foto (cabe en un mensaje de Nostr) y las horas de las demás.
    intrusos: (p.intrusos || []).map((x, i) => (i === 0 ? x : { cuando: x.cuando })),
  };
}

/** Deja el último estado cifrado en el buzón de cada ordenador (para verlo aunque el móvil se apague). */
export async function enviarUltima() {
  const a = leer();
  if (!a.activo || !a.encontrar || !a.macs.length) return;
  const e = await estadoAntirrobo();
  await Promise.all(a.macs.map(async (mac) => {
    const sobre = cifrar(Buffer.from(mac.clave, "base64"), { ...e, hechas: leerHechas().slice(-20), t: Date.now() });
    if (!relayUrl()) {
      // Evento guardado (el último sustituye al anterior) firmado con la identidad del móvil.
      const ids = nostr.identidades(mac.clave);
      const ev = nostr.evento(ids.movil, nostr.GUARDADO, JSON.stringify(sobre), [["d", ids.dUltima]]);
      const c = conexiones.get(mac.id);
      if (!(c?.publicar && c.publicar(ev) > 0)) await nostr.publicarUnaVez(ev, relaysNostr()).catch(() => {});
      return;
    }
    const { id, llave } = buzonDe(mac.clave);
    await fetch(`${relayUrl()}/b/${id}/ultima`, {
      method: "PUT", headers: { "content-type": "application/json", "x-llave": llave }, body: JSON.stringify(sobre),
      signal: AbortSignal.timeout(20_000),
    }).catch(() => {});
  }));
}

// Cada 15 min; en modo perdido, cada 2.
let relojUltima = null;
function programarUltima() {
  clearInterval(relojUltima);
  relojUltima = setInterval(() => enviarUltima().catch(() => {}), leerAntirrobo().perdido ? 2 * 60_000 : 15 * 60_000);
  relojUltima.unref?.();
}

/** Abre (o cierra) la conexión con el buzón de cada ordenador vinculado. */
export function conectarRelay() {
  const a = leer();
  const quiere = a.activo && a.encontrar ? new Map(a.macs.map((m) => [m.id, m])) : new Map();
  // Si se cambia entre Nostr y servidor propio, se reabre todo.
  const modo = relayUrl() || "nostr:" + relaysNostr().join(",");
  for (const [id, c] of conexiones) {
    if (!quiere.has(id) || c.modo !== modo) { c.cerrar = true; try { c.cerrarYa(); } catch {} conexiones.delete(id); }
  }
  for (const [id, mac] of quiere) if (!conexiones.has(id)) (relayUrl() ? abrirRelay(mac) : abrirNostr(mac));
}

// ---------- Nostr ----------

const pedidasNostr = new Map(); // id de petición → cuándo (para no atender dos veces la misma)

/**
 * Escucha en los servidores Nostr lo que manda el ordenador: peticiones al momento (efímeras) y la
 * lista de órdenes pendientes (guardada, llega aunque el móvil estuviera apagado al dejarla).
 */
function abrirNostr(mac) {
  const ids = nostr.identidades(mac.clave);
  const c = { modo: "nostr:" + relaysNostr().join(","), cerrar: false, ids };
  let primera = true;
  const pool = nostr.conectar({
    relays: relaysNostr(),
    filtros: () => [
      { kinds: [nostr.EFIMERO], authors: [ids.pubOrdenador], since: Math.floor(Date.now() / 1000) - 60 },
      { kinds: [nostr.GUARDADO], authors: [ids.pubOrdenador], "#d": [ids.dOrdenes] },
    ],
    alAbrir: () => { if (primera) { primera = false; enviarUltima().catch(() => {}); } },
    alRecibir: (ev) => recibirNostr(mac, ids, ev).catch((err) => console.error("[nostr]", err.message)),
  });
  Object.defineProperty(c, "abierta", { get: () => pool.abiertos() > 0, enumerable: true });
  c.publicar = (ev) => pool.publicar(ev);
  c.cerrarYa = () => pool.cerrar();
  conexiones.set(mac.id, c);
}

async function recibirNostr(mac, ids, ev) {
  if (ev.pubkey !== ids.pubOrdenador) return;
  const clave = Buffer.from(mac.clave, "base64");
  if (ev.kind === nostr.EFIMERO) {
    // Petición al momento: { r: id, s: sobre }. Igual que por la red local: la ruta y la hora van
    // dentro de lo cifrado; además, cada petición se atiende una sola vez.
    const m = JSON.parse(ev.content);
    if (!m.r || pedidasNostr.has(m.r)) return;
    pedidasNostr.set(m.r, Date.now());
    for (const [r, t] of pedidasNostr) if (Date.now() - t > 5 * 60_000) pedidasNostr.delete(r);
    let estado = 200, cuerpo;
    try {
      const d = descifrar(clave, m.s);
      if (!d.ruta || Math.abs(Date.now() - Number(d.t)) > VENTANA_MS) throw Object.assign(new Error("Petición caducada"), { codigoHttp: 401 });
      const r = await atender(d.ruta, d, mac);
      cuerpo = cifrar(clave, { ...r, t: Date.now() });
      // Los servidores Nostr no aceptan mensajes grandes (archivos): eso solo en casa.
      if (JSON.stringify(cuerpo).length > 60_000) { estado = 413; cuerpo = { error: "Demasiado grande para enviarlo fuera de casa" }; }
    } catch (e) {
      estado = e.codigoHttp || 400;
      cuerpo = { error: String(e.message || e) };
    }
    const resp = nostr.evento(ids.movil, nostr.EFIMERO, JSON.stringify({ r: m.r, e: estado, s: cuerpo }));
    if (!(conexiones.get(mac.id)?.publicar?.(resp) > 0)) await nostr.publicarUnaVez(resp, relaysNostr());
  } else if (ev.kind === nostr.GUARDADO) {
    // Lista de órdenes pendientes que deja el ordenador (modo perdido, bloquear, sonar, borrar).
    const d = descifrar(clave, JSON.parse(ev.content));
    let alguna = false;
    for (const o of Array.isArray(d.ordenes) ? d.ordenes : []) alguna = (await cumplirOrden(o, mac)) || alguna;
    if (alguna) enviarUltima().catch(() => {});
  }
}

/** Cumple una orden dejada para más tarde, una sola vez (por su identificador, que va cifrado). */
async function cumplirOrden(d, mac) {
  const hechas = leerHechas();
  if (!d?.id_orden || hechas.includes(d.id_orden) || !["/perdido", "/bloquear", "/sonar", "/borrar"].includes(d.ruta)) return false;
  // Más de una semana: ya no se cumple (el ordenador las quita antes).
  if (Number(d.t) && Date.now() - Number(d.t) > 7 * 86_400_000) return false;
  mkdirSync(DIR, { recursive: true });
  writeFileSync(HECHAS, JSON.stringify([...hechas, d.id_orden].slice(-200)), { mode: 0o600 });
  await atender(d.ruta, d, mac);
  return true;
}

function abrirRelay(mac, intentos = 0) {
  if (typeof WebSocket === "undefined") return; // Node sin WebSocket: solo red local
  const { id, llave } = buzonDe(mac.clave);
  const ws = new WebSocket(`${relayUrl().replace(/^http/, "ws")}/b/${id}/movil?llave=${llave}`);
  const c = { ws, abierta: false, cerrar: false, modo: relayUrl(), cerrarYa: () => ws.close() };
  conexiones.set(mac.id, c);
  let latido = null;
  ws.onopen = () => {
    c.abierta = true;
    intentos = 0;
    // Las redes móviles cortan las conexiones calladas: un latido al minuto la mantiene viva.
    latido = setInterval(() => { try { ws.send(JSON.stringify({ tipo: "latido" })); } catch {} }, 60_000);
    enviarUltima().catch(() => {});
  };
  ws.onmessage = (e) => recibirRelay(mac, ws, e.data).catch((err) => console.error("[relay]", err.message));
  ws.onerror = () => {};
  ws.onclose = () => {
    clearInterval(latido);
    c.abierta = false;
    if (c.cerrar || conexiones.get(mac.id) !== c) return;
    conexiones.delete(mac.id);
    // Reintento progresivo: 5 s, 10 s, 20 s… hasta 5 min.
    const espera = Math.min(300_000, 5_000 * 2 ** Math.min(intentos, 6));
    setTimeout(() => { const m = leer().macs.find((x) => x.id === mac.id); if (m && !conexiones.has(m.id)) abrirRelay(m, intentos + 1); }, espera).unref?.();
  };
}

function leerHechas() { try { return JSON.parse(readFileSync(HECHAS, "utf8")); } catch { return []; } }

async function recibirRelay(mac, ws, crudo) {
  const m = JSON.parse(String(crudo));
  const clave = Buffer.from(mac.clave, "base64");
  if (m.tipo === "peticion") {
    // Igual que por la red local: la ruta y la hora van dentro del sobre (no se puede reutilizar).
    let estado = 200, cuerpo;
    try {
      const d = descifrar(clave, m.sobre);
      if (d.ruta !== m.ruta || Math.abs(Date.now() - Number(d.t)) > VENTANA_MS) throw Object.assign(new Error("Petición caducada"), { codigoHttp: 401 });
      const r = await atender(m.ruta, d, mac);
      cuerpo = cifrar(clave, { ...r, t: Date.now() });
    } catch (e) {
      estado = e.codigoHttp || 400;
      cuerpo = { error: String(e.message || e) };
    }
    ws.send(JSON.stringify({ tipo: "respuesta", rid: m.rid, estado, cuerpo }));
  } else if (m.tipo === "orden") {
    // Órdenes dejadas con el móvil apagado: pueden llegar días después. Cada una lleva un
    // identificador propio dentro del sobre y se cumple una sola vez (el servidor no puede repetirlas).
    try {
      await cumplirOrden(descifrar(clave, m.sobre), mac);
    } catch (e) {
      console.error("[relay] orden no válida:", e.message);
    }
    ws.send(JSON.stringify({ tipo: "hecha", oid: m.oid }));
  }
}

/**
 * La app avisa de algo que el ordenador debe saber ya: «sim» (han cambiado la SIM) o «encontrado»
 * (el dueño ha desbloqueado y quita el modo perdido desde el móvil).
 */
export async function avisoAntirrobo(que, datos = {}) {
  if (que === "intruso") {
    // Foto pequeña (≤ 20 KB) de quien ha fallado al desbloquear: se guardan las 3 últimas.
    const foto = typeof datos.foto === "string" && datos.foto.length < 40_000 ? datos.foto : null;
    if (foto) guardarAntirrobo({ intrusos: [{ cuando: Number(datos.cuando) || Date.now(), foto }, ...(leerAntirrobo().intrusos || [])].slice(0, 3) });
    evento({ tipo: "antirrobo", que, cuando: Number(datos.cuando) || Date.now() });
    await enviarUltima().catch(() => {});
    return;
  }
  if (que === "encontrado") {
    guardarAntirrobo({ perdido: false });
    programarUltima();
  }
  evento({ tipo: "antirrobo", que, ...datos });
  await enviarUltima().catch(() => {});
}

let creaciones = null;

export function arrancarContinuidad(c) {
  creaciones = c;
  if (leer().activo) arrancar();
  setInterval(limpiarSalida, 10 * 60_000).unref?.();
  conectarRelay();
  programarUltima();
  // Si cambian los ordenadores vinculados o los ajustes, se reabren las conexiones.
  setInterval(conectarRelay, 60_000).unref?.();
}

export const huellaClave = (b64) => createHash("sha256").update(b64).digest("hex").slice(0, 8);
