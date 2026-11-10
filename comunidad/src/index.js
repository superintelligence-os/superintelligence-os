// Galería comunitaria de widgets de SIOS.
//   GET  /widgets?orden=populares|nuevos   lista (sin los ocultos)
//   GET  /widgets/:id                      uno, con su enlace sios://importar?d=…
//   POST /widgets {codigo, descripcion}    publicar (5 al día por conexión)
//   POST /widgets/:id/usado                alguien lo ha añadido
//   POST /widgets/:id/denunciar            con 3 denuncias se oculta
//   DELETE /widgets/:id  (cabecera x-admin) quitarlo
// Solo widgets que traen sus datos de fuentes integradas: nada de comandos, HTML, prompts ni
// datos fijos; las opciones personales (direcciones, nombres de dispositivos, casa) se quitan.

const FUENTES = ["noticias", "mi_dia", "calendario", "cumpleanos", "tiempo", "combustible", "baterias", "deportes", "bolsa", "cripto", "aire", "salud", "uso_movil", "diario", "gastos", "suscripciones"];
const PLANTILLAS = ["cifra", "lista", "texto", "progreso", "noticias", "agenda", "filas", "anillos", "grafico", "marcador", "pila"];
const OPCIONES = { // lo que puede viajar de cada fuente
  noticias: ["intereses", "rss", "cuantas", "indicaciones", "idioma"], mi_dia: ["indicaciones"], calendario: ["dias", "cuantas"], cumpleanos: ["dias"],
  tiempo: [], combustible: ["tipo", "cuantas", "orden", "radio_km"], baterias: ["sin_movil", "todos"], deportes: ["equipo", "liga"],
  bolsa: ["simbolos"], cripto: ["monedas"], aire: [], salud: [], uso_movil: [], diario: [], gastos: [], suscripciones: [],
};
const CABECERAS = { "content-type": "application/json; charset=utf-8", "access-control-allow-origin": "*", "cache-control": "no-store" };
const json = (v, status = 200) => new Response(JSON.stringify(v), { status, headers: CABECERAS });
const error = (m, status = 400) => json({ error: m }, status);

async function abrir(codigo) {
  const d = String(codigo || "").replace(/^sios:\/\/importar\?d=/, "");
  if (!/^[\w-]{10,12000}$/.test(d)) throw new Error("Enlace no válido");
  const bin = Uint8Array.from(atob(d.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
  const flujo = new Blob([bin]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
  const texto = await new Response(flujo).text();
  if (texto.length > 16000) throw new Error("Demasiado grande");
  return JSON.parse(texto);
}

async function cerrar(obj) {
  const flujo = new Blob([JSON.stringify(obj)]).stream().pipeThrough(new CompressionStream("deflate-raw"));
  const bin = new Uint8Array(await new Response(flujo).arrayBuffer());
  let s = "";
  for (const b of bin) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

const corto = (s, n) => String(s || "").replace(/[\u0000-\u001f<>]/g, "").trim().slice(0, n);

/** Deja solo lo seguro de una ficha de widget; lanza si no se puede publicar. */
function limpiar(f, enPila = false) {
  if (f?.tipo !== "widget") throw new Error("Solo se pueden publicar widgets");
  if (f.html || f.prompt || f.comando || f.origen?.comando) throw new Error("Este widget ejecuta cosas o lleva su propia página: no se puede publicar");
  if (!PLANTILLAS.includes(f.plantilla)) throw new Error("Plantilla no válida");
  if (f.plantilla !== "pila") {
    const fuente = f.origen?.fuente;
    if (!FUENTES.includes(fuente)) throw new Error("Solo widgets que traen sus datos de una fuente integrada (noticias, tiempo, fútbol…)");
    const origen = { fuente };
    for (const k of OPCIONES[fuente]) if (f.origen[k] != null) origen[k] = f.origen[k];
    return {
      tipo: "widget", nombre: corto(f.nombre, 40) || "Widget", plantilla: f.plantilla, tamano: ["pequeno", "mediano", "grande"].includes(f.tamano) ? f.tamano : undefined,
      lugar: enPila ? "pila" : f.lugar === "inicio" ? "inicio" : "panel", acento: corto(f.acento, 12) || "naranja", icono: corto(f.icono, 24) || undefined,
      origen, datos: f.datos?.titulo ? { titulo: corto(f.datos.titulo, 40) } : {},
    };
  }
  return { tipo: "widget", nombre: corto(f.nombre, 40) || "Pila", plantilla: "pila", tamano: f.tamano, lugar: "panel", acento: corto(f.acento, 12) || "naranja", pila: (f.pila || []).slice(0, 6), reglas: [] };
}

async function indice(env) {
  return (await env.KV.get("indice", "json")) || [];
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const partes = url.pathname.split("/").filter(Boolean);
    if (req.method === "OPTIONS") return new Response(null, { headers: { ...CABECERAS, "access-control-allow-methods": "GET,POST,DELETE", "access-control-allow-headers": "content-type,x-admin" } });
    // Familia: un buzón cifrado por grupo. El servidor guarda paquetes que no puede leer (AES-GCM con
    // la clave del grupo, que solo tienen los móviles); id del grupo de 128 bits, imposible de adivinar.
    if (partes[0] === "familia") {
      const grupo = partes[1];
      if (!/^[a-f0-9]{32}$/.test(grupo || "")) return error("Grupo no válido", 404);
      const k = `f:${grupo}`;
      if (req.method === "GET") {
        const desde = Number(url.searchParams.get("desde")) || 0;
        const lista = ((await env.KV.get(k, "json")) || []).filter((x) => x.seq > desde);
        return json(lista);
      }
      if (req.method === "POST") {
        const b = await req.json();
        if (typeof b.n !== "string" || typeof b.c !== "string" || b.c.length > 12000 || !/^[\w-]{1,24}$/.test(b.de || "")) return error("Paquete no válido");
        const dia = new Date().toISOString().slice(0, 10);
        const cuenta = `fc:${grupo}:${dia}`;
        const n = Number(await env.KV.get(cuenta)) || 0;
        if (n >= 1000) return error("Demasiados mensajes hoy", 429);
        const lista = (await env.KV.get(k, "json")) || [];
        const seq = (lista.at(-1)?.seq || 0) + 1;
        lista.push({ seq, de: b.de, n: b.n, c: b.c, cuando: Date.now() });
        // Se guarda lo de los últimos 30 días (y como mucho 500 paquetes).
        const vivos = lista.filter((x) => Date.now() - x.cuando < 30 * 86400_000).slice(-500);
        await env.KV.put(k, JSON.stringify(vivos), { expirationTtl: 60 * 86400 });
        await env.KV.put(cuenta, String(n + 1), { expirationTtl: 2 * 86400 });
        return json({ seq }, 201);
      }
      return error("Método no permitido", 405);
    }
    if (partes[0] !== "widgets") return json({ nombre: "Comunidad de SIOS", widgets: "/widgets" });
    try {
      // Lista
      if (req.method === "GET" && partes.length === 1) {
        const orden = url.searchParams.get("orden") === "nuevos" ? "nuevos" : "populares";
        const lista = (await indice(env)).filter((w) => !w.oculto);
        lista.sort((a, b) => (orden === "nuevos" ? b.creado - a.creado : b.usos - a.usos || b.creado - a.creado));
        return json(lista.slice(0, 100).map(({ denuncias, ...w }) => w));
      }
      const id = partes[1];
      if (id && !/^[a-z0-9]{10}$/.test(id)) return error("No existe", 404);
      // Uno
      if (req.method === "GET" && partes.length === 2) {
        const w = await env.KV.get(`w:${id}`, "json");
        if (!w || w.oculto) return error("No existe", 404);
        return json(w);
      }
      // Publicar
      if (req.method === "POST" && partes.length === 1) {
        const ip = req.headers.get("cf-connecting-ip") || "?";
        const dia = new Date().toISOString().slice(0, 10);
        const k = `ip:${ip}:${dia}`;
        const n = Number(await env.KV.get(k)) || 0;
        if (n >= 5) return error("Has publicado mucho hoy: prueba mañana", 429);
        const b = await req.json();
        const p = await abrir(b.codigo);
        if (p?.v !== 1) throw new Error("Versión no válida");
        const hijos = (p.h || []).slice(0, 6).map((h) => ({ ...limpiar(h, true), _id: corto(h._id, 16) }));
        const f = limpiar(p.f);
        if (f.plantilla === "pila") f.pila = f.pila.filter((x) => hijos.some((h) => h._id === x));
        const codigo = await cerrar({ v: 1, f, h: hijos });
        const nuevo = crypto.randomUUID().replace(/-/g, "").slice(0, 10);
        const w = {
          id: nuevo, nombre: f.nombre, descripcion: corto(b.descripcion, 140), plantilla: f.plantilla, tamano: f.tamano || null,
          fuente: f.origen?.fuente || hijos.map((h) => h.origen.fuente).join("+"), acento: f.acento, usos: 0, denuncias: 0, creado: Date.now(),
          enlace: `sios://importar?d=${codigo}`,
        };
        await env.KV.put(`w:${nuevo}`, JSON.stringify(w));
        const i = await indice(env);
        i.push({ id: w.id, nombre: w.nombre, descripcion: w.descripcion, plantilla: w.plantilla, tamano: w.tamano, fuente: w.fuente, acento: w.acento, usos: 0, denuncias: 0, creado: w.creado });
        await env.KV.put("indice", JSON.stringify(i.slice(-2000)));
        await env.KV.put(k, String(n + 1), { expirationTtl: 2 * 86400 });
        return json({ id: nuevo }, 201);
      }
      // Usado / denunciar
      if (req.method === "POST" && partes.length === 3 && ["usado", "denunciar"].includes(partes[2])) {
        const w = await env.KV.get(`w:${id}`, "json");
        if (!w) return error("No existe", 404);
        if (partes[2] === "usado") w.usos++;
        else { w.denuncias++; if (w.denuncias >= 3) w.oculto = true; }
        await env.KV.put(`w:${id}`, JSON.stringify(w));
        const i = await indice(env);
        const e = i.find((x) => x.id === id);
        if (e) { e.usos = w.usos; e.denuncias = w.denuncias; e.oculto = !!w.oculto; await env.KV.put("indice", JSON.stringify(i)); }
        return json({ ok: true });
      }
      // Quitar (administración)
      if (req.method === "DELETE" && partes.length === 2) {
        if (!env.ADMIN || req.headers.get("x-admin") !== env.ADMIN) return error("No", 403);
        await env.KV.delete(`w:${id}`);
        await env.KV.put("indice", JSON.stringify((await indice(env)).filter((x) => x.id !== id)));
        return json({ ok: true });
      }
      return error("Ruta desconocida", 404);
    } catch (e) {
      return error(String(e.message || e));
    }
  },
};
