// Prueba del servidor intermedio contra el simulador: un «móvil» conectado por WebSocket que
// contesta, un «ordenador» que pide, el último estado y las órdenes guardadas.
//   node relay/prueba/simulador.mjs 8787 &  node relay/prueba/prueba.mjs http://127.0.0.1:8787
import { randomBytes } from "node:crypto";
import assert from "node:assert/strict";

const base = process.argv[2] || "http://127.0.0.1:8787";
const id = randomBytes(16).toString("hex");
const llave = randomBytes(32).toString("hex");
const h = { "content-type": "application/json", "x-llave": llave };
const espera = (ms) => new Promise((r) => setTimeout(r, ms));

// Sin móvil conectado: 503 y desconectado.
let r = await fetch(`${base}/b/${id}/peticion`, { method: "POST", headers: h, body: JSON.stringify({ ruta: "/estado", sobre: { n: "x", c: "y" } }) });
assert.equal(r.status, 503); assert.equal((await r.json()).desconectado, true);
console.log("✓ sin móvil: 503 desconectado");

// Llave equivocada: 403.
r = await fetch(`${base}/b/${id}/ultima`, { headers: { "x-llave": "0".repeat(64) } });
assert.equal(r.status, 403);
console.log("✓ llave equivocada: 403");

// Orden dejada antes de que el móvil se conecte.
r = await fetch(`${base}/b/${id}/orden`, { method: "POST", headers: h, body: JSON.stringify({ n: "o", c: "bloquear" }) });
assert.equal(r.status, 200);

// El móvil se conecta, recibe la orden pendiente y contesta peticiones.
const ws = new WebSocket(`${base.replace("http", "ws")}/b/${id}/movil?llave=${llave}`);
const ordenes = [];
ws.onmessage = (e) => {
  const m = JSON.parse(e.data);
  if (m.tipo === "orden") { ordenes.push(m); ws.send(JSON.stringify({ tipo: "hecha", oid: m.oid })); }
  if (m.tipo === "peticion") ws.send(JSON.stringify({ tipo: "respuesta", rid: m.rid, estado: 200, cuerpo: { eco: m.ruta, sobre: m.sobre } }));
};
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
await espera(300);
assert.equal(ordenes.length, 1); assert.equal(ordenes[0].sobre.c, "bloquear");
console.log("✓ el móvil recibe la orden guardada al conectarse");

r = await fetch(`${base}/b/${id}/peticion`, { method: "POST", headers: h, body: JSON.stringify({ ruta: "/sonar", sobre: { n: "1", c: "2" } }) });
assert.equal(r.status, 200);
const c = await r.json(); assert.equal(c.eco, "/sonar"); assert.equal(c.sobre.c, "2");
console.log("✓ petición del ordenador → móvil → respuesta");

// Último estado.
r = await fetch(`${base}/b/${id}/ultima`, { method: "PUT", headers: h, body: JSON.stringify({ n: "u", c: "posicion" }) });
assert.equal(r.status, 200);
r = await fetch(`${base}/b/${id}/ultima`, { headers: h });
const u = await r.json(); assert.equal(u.ultima.sobre.c, "posicion"); assert.equal(u.conectado, true);
console.log("✓ último estado guardado y leído (conectado)");

// La orden ya cumplida no se repite al reconectar.
ws.close(); await espera(300);
const ws2 = new WebSocket(`${base.replace("http", "ws")}/b/${id}/movil?llave=${llave}`);
const repetidas = [];
ws2.onmessage = (e) => { const m = JSON.parse(e.data); if (m.tipo === "orden") repetidas.push(m); };
await new Promise((res) => { ws2.onopen = res; });
await espera(300);
assert.equal(repetidas.length, 0);
r = await fetch(`${base}/b/${id}/ultima`, { headers: h });
assert.equal((await r.json()).conectado, true);
console.log("✓ la orden cumplida no se repite; reconectado");
ws2.close();
console.log("Todo bien.");
process.exit(0);
