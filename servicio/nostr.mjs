// Nostr, lo justo para «Encontrar mi móvil» fuera de casa sin servidor propio: una red abierta de
// servidores gratuitos («relays») de gente y proyectos distintos. Nadie es dueño de la red, no hay
// cuentas: la identidad es una pareja de claves y cada mensaje va firmado (Schnorr BIP-340 sobre
// secp256k1, escrito aquí a mano para no depender de nada).
//
// SIOS solo publica sobres cifrados de extremo a extremo con la clave del emparejamiento: los
// servidores ven un identificador anónimo y datos ilegibles. Se usan varios a la vez; si uno se
// cae o borra mensajes, siguen los demás.
import { createHash, hkdfSync, randomBytes } from "node:crypto";

// ---------- secp256k1 y Schnorr (BIP-340) ----------

const P = 0xfffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2fn;
const N = 0xfffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141n;
const G = [
  0x79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798n,
  0x483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8n,
];

const mod = (a, m = P) => ((a % m) + m) % m;
function inv(a, m = P) {
  // Euclides extendido: más rápido que elevar a m-2.
  let [x, y, u, v] = [mod(a, m), m, 1n, 0n];
  while (x > 1n) { const q = y / x; [x, y] = [y % x, x]; [u, v] = [v - q * u, u]; }
  return mod(u, m);
}

// Puntos en coordenadas jacobianas (X, Y, Z): sin inversiones en cada paso.
function doblar([X, Y, Z]) {
  if (Y === 0n) return [0n, 1n, 0n];
  const S = mod(4n * X * Y * Y), M = mod(3n * X * X);
  const X3 = mod(M * M - 2n * S);
  return [X3, mod(M * (S - X3) - 8n * Y ** 4n), mod(2n * Y * Z)];
}
function sumar(a, b) {
  if (a[2] === 0n) return b;
  if (b[2] === 0n) return a;
  const [X1, Y1, Z1] = a, [X2, Y2, Z2] = b;
  const Z1Z1 = mod(Z1 * Z1), Z2Z2 = mod(Z2 * Z2);
  const U1 = mod(X1 * Z2Z2), U2 = mod(X2 * Z1Z1);
  const S1 = mod(Y1 * Z2 * Z2Z2), S2 = mod(Y2 * Z1 * Z1Z1);
  if (U1 === U2) return S1 === S2 ? doblar(a) : [0n, 1n, 0n];
  const H = mod(U2 - U1), R = mod(S2 - S1), H2 = mod(H * H), H3 = mod(H * H2);
  const X3 = mod(R * R - H3 - 2n * U1 * H2);
  return [X3, mod(R * (U1 * H2 - X3) - S1 * H3), mod(H * Z1 * Z2)];
}
function por(k) {
  let r = [0n, 1n, 0n], q = [G[0], G[1], 1n];
  while (k > 0n) { if (k & 1n) r = sumar(r, q); q = doblar(q); k >>= 1n; }
  const zi = inv(r[2]);
  return [mod(r[0] * zi * zi), mod(r[1] * zi * zi * zi)];
}

const aNum = (b) => BigInt("0x" + Buffer.from(b).toString("hex"));
const aBytes = (n) => Buffer.from(n.toString(16).padStart(64, "0"), "hex");
const sha = (...partes) => createHash("sha256").update(Buffer.concat(partes)).digest();
const etiquetado = (tag, ...partes) => { const t = sha(Buffer.from(tag)); return sha(t, t, ...partes); };

function privada(sk) {
  const d = aNum(sk);
  if (d <= 0n || d >= N) throw new Error("Clave privada no válida");
  return d;
}

/** Clave pública x-only (32 bytes, hex) de una privada de 32 bytes. */
export function publica(sk) {
  return aBytes(por(privada(sk))[0]).toString("hex");
}

/** Firma BIP-340 de un mensaje de 32 bytes. `aux` (32 bytes) es aleatorio salvo en las pruebas. */
export function firmar(msg, sk, aux = randomBytes(32)) {
  const d0 = privada(sk);
  const Pt = por(d0);
  const d = Pt[1] % 2n === 0n ? d0 : N - d0;
  const t = aBytes(d).map((b, i) => b ^ etiquetado("BIP0340/aux", aux)[i]);
  const k0 = mod(aNum(etiquetado("BIP0340/nonce", t, aBytes(Pt[0]), msg)), N);
  if (k0 === 0n) throw new Error("Nonce nulo");
  const R = por(k0);
  const k = R[1] % 2n === 0n ? k0 : N - k0;
  const e = mod(aNum(etiquetado("BIP0340/challenge", aBytes(R[0]), aBytes(Pt[0]), msg)), N);
  return Buffer.concat([aBytes(R[0]), aBytes(mod(k + e * d, N))]).toString("hex");
}

// ---------- Eventos ----------

/** Evento firmado (NIP-01). */
export function evento(sk, kind, content, tags = [], created_at = Math.floor(Date.now() / 1000)) {
  const pubkey = publica(sk);
  const id = sha(Buffer.from(JSON.stringify([0, pubkey, created_at, kind, tags, content])));
  return { id: id.toString("hex"), pubkey, created_at, kind, tags, content, sig: firmar(id, sk) };
}

/**
 * Identidades del emparejamiento: una para el móvil y otra para el ordenador, y la etiqueta de sus
 * datos guardados. Salen de la clave compartida: los dos lados las conocen; los servidores, no.
 */
export function identidades(claveB64) {
  const k = Buffer.from(claveB64, "base64");
  const derivar = (que, n = 32) => Buffer.from(hkdfSync("sha256", k, Buffer.from("sios-nostr"), que, n));
  const clave = (que) => {
    // Casi imposible que no valga; si pasa, se vuelve a derivar.
    for (let i = 0; ; i++) {
      const sk = derivar(i ? `${que}-${i}` : que);
      const d = aNum(sk);
      if (d > 0n && d < N) return sk;
    }
  };
  const movil = clave("movil"), ordenador = clave("ordenador");
  return {
    movil, ordenador, pubMovil: publica(movil), pubOrdenador: publica(ordenador),
    dUltima: derivar("ultima", 16).toString("hex"), dOrdenes: derivar("ordenes", 16).toString("hex"),
  };
}

// Tipos de evento: 30078 son datos de una aplicación que se guardan (el último sustituye al
// anterior); 25078, efímeros (se reparten a quien esté escuchando y no se guardan).
export const GUARDADO = 30078;
export const EFIMERO = 25078;

// Servidores públicos y gratuitos. Se usan todos a la vez.
// (Probados: aceptan estos tipos de evento sin registro. Damus y nos.lol no siempre contestan.)
export const RELAYS = [
  "wss://relay.primal.net",
  "wss://offchain.pub",
  "wss://relay.snort.social",
  "wss://relay.nostr.net",
];

// ---------- Conexión con varios servidores a la vez ----------

/**
 * Se conecta a todos los servidores, se suscribe a `filtros` y llama a `alRecibir(evento)` una sola
 * vez por evento (aunque llegue de varios). Reconecta solo. Devuelve { publicar, cerrar, abiertos }.
 */
export function conectar({ relays = RELAYS, filtros, alRecibir, alAbrir }) {
  const vistos = new Set();
  const conexiones = new Map();
  let cerrado = false;

  function abrir(url, intentos = 0) {
    if (cerrado || typeof WebSocket === "undefined") return;
    let ws;
    try { ws = new WebSocket(url); } catch { return; }
    const c = { ws, abierta: false };
    conexiones.set(url, c);
    let latido = null;
    ws.onopen = () => {
      c.abierta = true;
      intentos = 0;
      // Al reconectar, solo lo que no se haya visto (el resto lo filtra `vistos`).
      ws.send(JSON.stringify(["REQ", "sios", ...filtros()]));
      // Las redes móviles cortan las conexiones calladas: se renueva la suscripción cada 4 min.
      latido = setInterval(() => { try { ws.send(JSON.stringify(["REQ", "sios", ...filtros()])); } catch {} }, 240_000);
      alAbrir?.(url);
    };
    ws.onmessage = (e) => {
      let m;
      try { m = JSON.parse(String(e.data)); } catch { return; }
      if (m[0] === "EVENT" && m[2]?.id && !vistos.has(m[2].id)) {
        vistos.add(m[2].id);
        if (vistos.size > 2000) vistos.delete(vistos.values().next().value);
        try { alRecibir(m[2], url); } catch (err) { console.error("[nostr]", err.message); }
      }
    };
    ws.onerror = () => {};
    ws.onclose = () => {
      clearInterval(latido);
      c.abierta = false;
      if (cerrado || conexiones.get(url) !== c) return;
      conexiones.delete(url);
      const espera = Math.min(300_000, 5_000 * 2 ** Math.min(intentos, 6));
      setTimeout(() => { if (!cerrado && !conexiones.has(url)) abrir(url, intentos + 1); }, espera).unref?.();
    };
  }
  for (const url of relays) abrir(url);

  return {
    publicar(ev) {
      const texto = JSON.stringify(["EVENT", ev]);
      let enviado = 0;
      for (const c of conexiones.values()) if (c.abierta) { try { c.ws.send(texto); enviado++; } catch {} }
      return enviado;
    },
    abiertos: () => [...conexiones.values()].filter((c) => c.abierta).length,
    cerrar() {
      cerrado = true;
      for (const c of conexiones.values()) try { c.ws.close(); } catch {}
      conexiones.clear();
    },
  };
}

/** Publica un evento en todos los servidores y espera a que al menos uno lo acepte. */
export function publicarUnaVez(ev, relays = RELAYS, espera = 12_000) {
  return new Promise((resolver) => {
    let pendientes = relays.length, aceptado = false;
    const fin = () => { if (--pendientes <= 0) resolver(aceptado); };
    for (const url of relays) {
      let ws, hecho = false;
      const acabar = (ok) => { if (hecho) return; hecho = true; if (ok) { aceptado = true; resolver(true); } try { ws?.close(); } catch {} fin(); };
      setTimeout(() => acabar(false), espera).unref?.();
      try { ws = new WebSocket(url); } catch { acabar(false); continue; }
      ws.onopen = () => ws.send(JSON.stringify(["EVENT", ev]));
      ws.onmessage = (e) => { try { const m = JSON.parse(String(e.data)); if (m[0] === "OK" && m[1] === ev.id) acabar(!!m[2]); } catch {} };
      ws.onerror = () => acabar(false);
    }
  });
}
