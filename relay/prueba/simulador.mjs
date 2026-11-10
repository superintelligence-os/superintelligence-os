// Simulador mínimo de Cloudflare Workers + Durable Objects para probar el servidor intermedio en el
// Mac sin instalar nada: ejecuta src/index.js tal cual sobre node:http, con WebSocket de servidor
// (RFC 6455, solo texto) y almacenamiento en memoria.
//   node relay/prueba/simulador.mjs [puerto]
import { createServer } from "node:http";
import { createHash } from "node:crypto";

// Response de Node no admite el 101 con WebSocket que devuelven los Workers.
const ResponseNode = globalThis.Response;
globalThis.Response = class extends ResponseNode {
  constructor(cuerpo, init = {}) {
    if (init.status === 101) { super(null, { status: 200 }); this.ws = init.webSocket; this.es101 = true; }
    else super(cuerpo, init);
  }
};

class LadoWs {
  // Lo que se envía antes de completar el 101 se guarda y sale al conectar (como en Cloudflare).
  constructor() { this.socket = null; this.etiquetas = []; this.cola = []; }
  send(texto) {
    if (!this.socket) { this.cola.push(String(texto)); return; }
    if (!this.socket.destroyed) this.socket.write(trama(Buffer.from(String(texto)), 0x1));
  }
  conectar(socket) { this.socket = socket; for (const t of this.cola.splice(0)) this.send(t); }
  close(codigo = 1000) {
    if (!this.socket || this.socket.destroyed) return;
    const c = Buffer.alloc(2); c.writeUInt16BE(codigo);
    this.socket.write(trama(c, 0x8)); this.socket.end();
  }
}
globalThis.WebSocketPair = class { constructor() { this[0] = new LadoWs(); this[1] = new LadoWs(); } };

function trama(datos, op) {
  const n = datos.length;
  const cab = n < 126 ? Buffer.from([0x80 | op, n]) : n < 65536 ? Buffer.from([0x80 | op, 126, n >> 8, n & 255]) : (() => { const b = Buffer.alloc(10); b[0] = 0x80 | op; b[1] = 127; b.writeBigUInt64BE(BigInt(n), 2); return b; })();
  return Buffer.concat([cab, datos]);
}

const { default: worker, Buzon } = await import("../src/index.js");
const buzones = new Map();

function buzon(nombre) {
  if (!buzones.has(nombre)) {
    const datos = new Map();
    const sockets = new Set();
    const ctx = {
      storage: { get: async (k) => structuredClone(datos.get(k)), put: async (k, v) => { datos.set(k, structuredClone(v)); } },
      acceptWebSocket: (ws, etiquetas) => { ws.etiquetas = etiquetas; sockets.add(ws); },
      getWebSockets: (etiqueta) => [...sockets].filter((w) => (!w.socket || !w.socket.destroyed) && (!etiqueta || w.etiquetas.includes(etiqueta))),
      sockets,
    };
    buzones.set(nombre, new Buzon(ctx, {}));
  }
  return buzones.get(nombre);
}
const env = { BUZON: { idFromName: (n) => n, get: (id) => ({ fetch: (req) => buzon(id).fetch(req) }) } };

const servidor = createServer(async (req, res) => {
  const trozos = [];
  for await (const t of req) trozos.push(t);
  const r = await worker.fetch(new Request(`http://localhost${req.url}`, {
    method: req.method, headers: req.headers, body: ["GET", "HEAD"].includes(req.method) ? undefined : Buffer.concat(trozos),
  }), env);
  res.writeHead(r.status, Object.fromEntries(r.headers));
  res.end(Buffer.from(await r.arrayBuffer()));
});

servidor.on("upgrade", async (req, socket) => {
  const r = await worker.fetch(new Request(`http://localhost${req.url}`, { headers: req.headers }), env);
  if (!r.es101) { socket.end(`HTTP/1.1 ${r.status} No\r\n\r\n`); return; }
  const acepta = createHash("sha1").update(req.headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").digest("base64");
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${acepta}\r\n\r\n`);
  // El lado «servidor» del par es el que guardó el Buzon con acceptWebSocket.
  const nombre = new URL(`http://x${req.url}`).pathname.split("/")[2];
  const lado = [...buzon(nombre).ctx.sockets].find((w) => !w.socket);
  lado.conectar(socket);
  let pendiente = Buffer.alloc(0);
  socket.on("data", async (d) => {
    pendiente = Buffer.concat([pendiente, d]);
    while (pendiente.length >= 2) {
      const op = pendiente[0] & 0x0f;
      let n = pendiente[1] & 0x7f, i = 2;
      if (n === 126) { n = pendiente.readUInt16BE(2); i = 4; } else if (n === 127) { n = Number(pendiente.readBigUInt64BE(2)); i = 10; }
      const mascara = pendiente.subarray(i, i + 4); i += 4;
      if (pendiente.length < i + n) return;
      const datos = Buffer.from(pendiente.subarray(i, i + n)).map((b, k) => b ^ mascara[k % 4]);
      pendiente = pendiente.subarray(i + n);
      if (op === 0x8) { socket.end(); await buzon(nombre).webSocketClose(lado); return; }
      if (op === 0x9) { socket.write(trama(datos, 0xA)); continue; }
      if (op === 0x1) await buzon(nombre).webSocketMessage(lado, datos.toString("utf8"));
    }
  });
  socket.on("close", () => { buzon(nombre).ctx.sockets.delete(lado); });
});

const puerto = Number(process.argv[2] || 8787);
servidor.listen(puerto, "127.0.0.1", () => console.log(`Simulador del servidor intermedio en http://127.0.0.1:${puerto}`));
