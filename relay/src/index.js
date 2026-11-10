// Servidor intermedio de SIOS (Cloudflare Worker + Durable Objects).
//
// Cada emparejamiento móvil ↔ ordenador tiene un «buzón». Su nombre (32 cifras hex) y su llave de
// escritura salen de la clave del emparejamiento (HKDF), que el servidor no conoce: solo quien está
// emparejado sabe dónde está su buzón, y lo que pasa por él va cifrado con AES-256-GCM de extremo a
// extremo (los mismos sobres que en la red local). Aquí no se descifra nada.
//
//   GET  /b/:id/movil?llave=…   el móvil se queda conectado (WebSocket) esperando peticiones
//   POST /b/:id/peticion        el ordenador pide algo al móvil y espera la respuesta (hasta 30 s)
//   PUT  /b/:id/ultima          el móvil deja su último estado (ubicación, batería, SIM), cifrado
//   GET  /b/:id/ultima          el ordenador lo lee aunque el móvil esté apagado o sin cobertura
//   POST /b/:id/orden           el ordenador deja una orden (bloquear, borrar…) para cuando vuelva
//
// La llave va en la cabecera «x-llave» (o «?llave=» al abrir el WebSocket). La primera que llega
// queda fijada (solo se guarda su huella): después, sin ella, no se puede escribir ni leer.

const ID = /^[0-9a-f]{32}$/;
const MAX_MENSAJE = 900 * 1024; // un mensaje de WebSocket no puede pasar de 1 MiB
const ESPERA_MS = 30_000;
const MAX_ORDENES = 20;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const m = url.pathname.match(/^\/b\/([0-9a-f]{32})\/(movil|peticion|ultima|orden)$/);
    if (!m || !ID.test(m[1])) {
      if (url.pathname === "/") return new Response("SIOS: servidor intermedio cifrado.\n", { headers: { "content-type": "text/plain; charset=utf-8" } });
      return json(404, { error: "No existe" });
    }
    const buzon = env.BUZON.get(env.BUZON.idFromName(m[1]));
    return buzon.fetch(req);
  },
};

export class Buzon {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.esperando = new Map(); // rid → resolver de la petición del ordenador (en memoria: dura lo que la petición)
  }

  async fetch(req) {
    const url = new URL(req.url);
    const que = url.pathname.split("/").pop();
    const llave = req.headers.get("x-llave") || url.searchParams.get("llave") || "";
    if (!(await this.llaveValida(llave))) return json(403, { error: "Llave no válida" });

    if (que === "movil") {
      if (req.headers.get("upgrade") !== "websocket") return json(426, { error: "Hace falta WebSocket" });
      const [cliente, servidor] = Object.values(new WebSocketPair());
      // Un solo móvil por buzón: si se reconecta, la conexión vieja se cierra.
      for (const ws of this.ctx.getWebSockets("movil")) try { ws.close(1000, "Otra conexión"); } catch {}
      this.ctx.acceptWebSocket(servidor, ["movil"]);
      await this.ctx.storage.put("visto", Date.now());
      // Lo que se dejó mientras no estaba (bloquear, borrar…).
      const ordenes = (await this.ctx.storage.get("ordenes")) || [];
      for (const o of ordenes) servidor.send(JSON.stringify({ tipo: "orden", ...o }));
      return new Response(null, { status: 101, webSocket: cliente });
    }

    if (que === "peticion" && req.method === "POST") {
      const texto = await req.text();
      if (texto.length > MAX_MENSAJE) return json(413, { error: "Demasiado grande para el servidor intermedio" });
      const movil = this.ctx.getWebSockets("movil")[0];
      if (!movil) return json(503, { desconectado: true, visto: (await this.ctx.storage.get("visto")) || null });
      const rid = crypto.randomUUID();
      const respuesta = new Promise((resolver) => {
        const reloj = setTimeout(() => { this.esperando.delete(rid); resolver(null); }, ESPERA_MS);
        this.esperando.set(rid, (r) => { clearTimeout(reloj); this.esperando.delete(rid); resolver(r); });
      });
      movil.send(JSON.stringify({ tipo: "peticion", rid, ...JSON.parse(texto) }));
      const r = await respuesta;
      if (!r) return json(504, { error: "El móvil no ha contestado a tiempo" });
      return new Response(JSON.stringify(r.cuerpo), { status: r.estado || 200, headers: { "content-type": "application/json" } });
    }

    if (que === "ultima") {
      if (req.method === "PUT") {
        const texto = await req.text();
        if (texto.length > 16 * 1024) return json(413, { error: "Demasiado grande" });
        await this.ctx.storage.put("ultima", { sobre: JSON.parse(texto), cuando: Date.now() });
        return json(200, { ok: true });
      }
      const u = (await this.ctx.storage.get("ultima")) || null;
      return json(200, {
        ultima: u, conectado: this.ctx.getWebSockets("movil").length > 0,
        visto: (await this.ctx.storage.get("visto")) || null,
      });
    }

    if (que === "orden" && req.method === "POST") {
      const texto = await req.text();
      if (texto.length > 16 * 1024) return json(413, { error: "Demasiado grande" });
      const o = { oid: crypto.randomUUID(), sobre: JSON.parse(texto), cuando: Date.now() };
      const ordenes = [...((await this.ctx.storage.get("ordenes")) || []), o].slice(-MAX_ORDENES);
      await this.ctx.storage.put("ordenes", ordenes);
      for (const ws of this.ctx.getWebSockets("movil")) ws.send(JSON.stringify({ tipo: "orden", ...o }));
      return json(200, { ok: true, oid: o.oid });
    }

    return json(405, { error: "Método no permitido" });
  }

  // ---------- WebSocket del móvil (con hibernación: no gasta mientras espera) ----------

  async webSocketMessage(ws, mensaje) {
    let m;
    try { m = JSON.parse(typeof mensaje === "string" ? mensaje : new TextDecoder().decode(mensaje)); } catch { return; }
    if (m.tipo === "respuesta") this.esperando.get(m.rid)?.({ estado: m.estado, cuerpo: m.cuerpo });
    else if (m.tipo === "hecha") {
      // El móvil ya ha cumplido una orden: se quita de la cola.
      const ordenes = ((await this.ctx.storage.get("ordenes")) || []).filter((o) => o.oid !== m.oid);
      await this.ctx.storage.put("ordenes", ordenes);
    } else if (m.tipo === "latido") ws.send(JSON.stringify({ tipo: "latido" }));
    await this.ctx.storage.put("visto", Date.now());
  }

  async webSocketClose() {
    await this.ctx.storage.put("visto", Date.now());
  }

  async webSocketError() {
    await this.ctx.storage.put("visto", Date.now());
  }

  /** La primera llave que llega queda fijada (se guarda su huella); las demás tienen que coincidir. */
  async llaveValida(llave) {
    if (!/^[0-9a-f]{64}$/.test(llave)) return false;
    const huella = await sha256(llave);
    const guardada = await this.ctx.storage.get("llave");
    if (!guardada) {
      await this.ctx.storage.put("llave", huella);
      return true;
    }
    return guardada === huella;
  }
}

async function sha256(texto) {
  const b = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(texto));
  return [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, "0")).join("");
}

function json(estado, obj) {
  return new Response(JSON.stringify(obj), { status: estado, headers: { "content-type": "application/json" } });
}
