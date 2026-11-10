// «Qué ha salido de tu móvil»: cada conexión hacia fuera del servicio y del motor de IA queda
// anotada (a qué servidor, para qué y cuántos datos), y cada mensaje que se manda a una IA. Nada
// del contenido: solo destino, motivo y tamaño. Se enseña en Ajustes para que se pueda comprobar.
import { appendFileSync, existsSync, readFileSync, renameSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

const ARCHIVO = join(homedir(), "sios", "privacidad.jsonl");

// Para qué se usa cada servidor, dicho como lo entendería cualquiera.
const MOTIVOS = [
  [/open-meteo\.com|wttr\.in/, "El tiempo"],
  [/coingecko|binance|kraken/, "Precios de criptomonedas (widget)"],
  [/gasolin|minetur|geoportal/i, "Precios de gasolina (widget)"],
  [/nominatim\.openstreetmap\.org/, "Nombres de lugares (mapa)"],
  [/overpass/, "Sitios cercanos (mapa)"],
  [/frankfurter/, "Cambio de moneda"],
  [/duckduckgo\.com/, "Búsqueda en internet que pediste"],
  [/huggingface\.co|hf\.co|cdn-lfs/, "Descarga del modelo local"],
  [/workers\.dev/, "Galería de widgets o familia"],
  [/api\.anthropic\.com|claude\.ai/, "IA: Claude"],
  [/api\.openai\.com|chatgpt\.com/, "IA: ChatGPT"],
  [/generativelanguage\.googleapis\.com|googleapis\.com/, "IA: Gemini"],
  [/api\.deepseek\.com/, "IA: DeepSeek"],
  [/moonshot|kimi/, "IA: Kimi"],
  [/api\.mistral\.ai/, "IA: Mistral"],
  [/api\.x\.ai/, "IA: Grok"],
  [/openrouter\.ai/, "IA: OpenRouter"],
  [/github\.com|githubusercontent|npmjs|registry\.npmjs/, "Instalar o actualizar programas"],
  [/rss|feed|news|noticias/i, "Noticias de tu panel"],
];

export function motivo(host) {
  return MOTIVOS.find(([re]) => re.test(host))?.[1] || "Web que pediste o de un widget";
}

export function anotar({ destino, bytes = 0, para }) {
  try {
    if (existsSync(ARCHIVO) && statSync(ARCHIVO).size > 2_000_000) renameSync(ARCHIVO, ARCHIVO + ".1");
    appendFileSync(ARCHIVO, JSON.stringify({ t: Date.now(), destino, bytes, para: para || motivo(destino) }) + "\n");
  } catch {}
}

/** Envuelve fetch de este proceso: cada petición a internet queda anotada (127.0.0.1 no cuenta). */
export function vigilarFetch() {
  if (globalThis.fetch.__vigilado) return;
  const original = globalThis.fetch;
  const vigilado = async (entrada, opciones = {}) => {
    let host = "";
    try {
      host = new URL(typeof entrada === "string" ? entrada : entrada.url).host;
    } catch {}
    const r = await original(entrada, opciones);
    if (host && !/^(127\.0\.0\.1|localhost|\[::1\])(:\d+)?$/.test(host)) {
      const cuerpo = opciones.body;
      const enviado = typeof cuerpo === "string" ? Buffer.byteLength(cuerpo) : cuerpo?.byteLength || cuerpo?.length || 0;
      anotar({ destino: host.replace(/:\d+$/, ""), bytes: enviado });
    }
    return r;
  };
  vigilado.__vigilado = true;
  globalThis.fetch = vigilado;
}

/** Resumen de los últimos días por destino, de lo más reciente a lo más antiguo. */
export function resumen(dias = 7) {
  const desde = Date.now() - dias * 86400_000;
  const porDestino = new Map();
  let total = 0;
  for (const f of [ARCHIVO + ".1", ARCHIVO]) {
    let texto = "";
    try {
      texto = readFileSync(f, "utf8");
    } catch {
      continue;
    }
    for (const l of texto.split("\n")) {
      if (!l) continue;
      let e;
      try {
        e = JSON.parse(l);
      } catch {
        continue;
      }
      if (e.t < desde) continue;
      total++;
      const k = `${e.para}|${e.destino}`;
      const a = porDestino.get(k) || { destino: e.destino, para: e.para, veces: 0, bytes: 0, ultimo: 0 };
      a.veces++;
      a.bytes += e.bytes || 0;
      a.ultimo = Math.max(a.ultimo, e.t);
      porDestino.set(k, a);
    }
  }
  return { dias, total, destinos: [...porDestino.values()].sort((a, b) => b.ultimo - a.ultimo) };
}
