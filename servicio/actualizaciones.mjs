// Actualizaciones automáticas de SIOS.
//
// Cada 12 horas (y al arrancar) se descarga el manifiesto del canal de actualizaciones y su firma
// Ed25519; solo si la firma es de la clave de SIOS (clave-actualizaciones.pem) se mira qué hay:
// - app nueva: se descarga, se comprueba su SHA-256 y se instala sin preguntar (adb, como el resto);
// - agente nuevo: igual, se descomprime al lado, se cambia por el actual y el servicio se reinicia;
// - ROM nueva: solo se avisa (se instala a mano, con fastboot o DSU).
// El canal es una carpeta web con manifiesto.json, manifiesto.firma, sios.apk y servicio.tar.gz
// (los prepara scripts/publicar-version.sh).
import { createHash, createPublicKey, verify } from "node:crypto";
import { spawn } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { homedir } from "node:os";
import { fileURLToPath } from "node:url";
import { adb, android, puente, termux, q } from "./movil.mjs";
import { registrar } from "./actividad.mjs";

const AQUI = dirname(fileURLToPath(import.meta.url));
const DATOS = join(homedir(), "sios");
const ESTADO = join(DATOS, "actualizaciones.json");
const CADA = 12 * 3_600_000;

const leerEstado = () => {
  try { return JSON.parse(readFileSync(ESTADO, "utf8")); } catch { return {}; }
};
const guardarEstado = (cambios) => {
  const e = { ...leerEstado(), ...cambios };
  writeFileSync(ESTADO, JSON.stringify(e, null, 1));
  return e;
};

/** Dónde se publican las actualizaciones: el que se haya puesto a mano o el que trae la ROM. */
export async function canal() {
  const e = leerEstado();
  if (e.canal) return e.canal;
  return (await android("getprop ro.sios.actualizaciones").catch(() => "")).trim();
}

/** Lo que hay instalado ahora. */
export async function actual() {
  const dump = await android("dumpsys package com.sios | grep -m1 versionCode=").catch(() => "");
  let servicio = "0";
  try { servicio = JSON.parse(readFileSync(join(AQUI, "version.json"), "utf8")).version; } catch {}
  return {
    app: Number(dump.match(/versionCode=(\d+)/)?.[1] || 0),
    servicio,
    rom: (await android("getprop ro.sios.compilacion").catch(() => "")).trim(),
  };
}

const huella = (buf) => createHash("sha256").update(buf).digest("hex");

/** Descarga y comprueba el manifiesto firmado. Lanza un error si la firma no es buena. */
async function manifiesto(url) {
  const [r, f] = await Promise.all([
    fetch(url, { signal: AbortSignal.timeout(20000), cache: "no-store" }),
    fetch(url.replace(/manifiesto\.json(\?.*)?$/, "manifiesto.firma"), { signal: AbortSignal.timeout(20000), cache: "no-store" }),
  ]);
  if (!r.ok) throw new Error(`El canal respondió ${r.status}`);
  if (!f.ok) throw new Error("El canal no tiene firma");
  const texto = Buffer.from(await r.arrayBuffer());
  const firma = Buffer.from((await f.text()).trim(), "base64");
  const clave = createPublicKey(readFileSync(join(AQUI, "clave-actualizaciones.pem")));
  if (!verify(null, texto, clave, firma)) throw new Error("La firma de la actualización no es válida: no se instala nada");
  return JSON.parse(texto.toString("utf8"));
}

/** Busca actualizaciones. Devuelve qué hay nuevo (sin instalar nada). */
export async function buscar() {
  const url = await canal();
  if (!url) return guardarEstado({ ultima_busqueda: Date.now(), error: null, disponible: null, sin_canal: true });
  try {
    const m = await manifiesto(url);
    const a = await actual();
    const disponible = {
      version: m.version, notas: m.notas, publicado: m.publicado,
      app: m.app && m.app.version_code > a.app ? m.app : null,
      servicio: m.servicio && m.servicio.version > a.servicio ? m.servicio : null,
      rom: m.rom && m.rom.compilacion > a.rom ? m.rom : null,
      base: new URL(".", url).href,
    };
    const hay = disponible.app || disponible.servicio || disponible.rom;
    return guardarEstado({ ultima_busqueda: Date.now(), error: null, sin_canal: false, disponible: hay ? disponible : null });
  } catch (e) {
    return guardarEstado({ ultima_busqueda: Date.now(), error: String(e.message || e) });
  }
}

async function descargar(base, archivo, sha256) {
  const r = await fetch(new URL(archivo, base), { signal: AbortSignal.timeout(300000) });
  if (!r.ok) throw new Error(`No se pudo descargar ${archivo} (${r.status})`);
  const buf = Buffer.from(await r.arrayBuffer());
  if (huella(buf) !== sha256) throw new Error(`${archivo} no coincide con el manifiesto firmado: no se instala`);
  return buf;
}

/** Instala lo que haya (app y agente) y avisa de la ROM. reiniciar: el servicio se reinicia si cambió. */
export async function instalar() {
  const e = leerEstado();
  const d = e.disponible;
  if (!d) return { ok: true, nada: true };
  const hecho = [];
  try {
    if (d.app) {
      const apk = join(process.env.TMPDIR || "/data/data/com.termux/files/usr/tmp", "claude-actualizacion.apk");
      writeFileSync(apk, await descargar(d.base, d.app.archivo, d.app.sha256));
      const salida = await adb(["install", "-r", apk]);
      rmSync(apk, { force: true });
      if (!/Success/.test(salida)) throw new Error(`La app no se instaló: ${salida.trim().slice(0, 200)}`);
      hecho.push("la app");
    }
    if (d.rom && e.rom_avisada !== d.rom.compilacion) {
      await puente("notificar", { id: "rom-nueva", titulo: "Hay una versión nueva de SIOS", texto: `${d.notas || ""}\nSe instala desde el ordenador: ${d.rom.descarga}`.trim() }).catch(() => {});
      guardarEstado({ rom_avisada: d.rom.compilacion });
    }
    let reiniciar = false;
    if (d.servicio) {
      await cambiarServicio(await descargar(d.base, d.servicio.archivo, d.servicio.sha256));
      hecho.push("el agente");
      reiniciar = true;
    }
    guardarEstado({ disponible: d.rom ? { ...d, app: null, servicio: null } : null, ultima_instalacion: Date.now(), ultima_version: d.version, error: null });
    if (hecho.length) {
      registrar({ origen: "app", herramienta: "actualizar", frase: `SIOS se ha actualizado (${hecho.join(" y ")})`, detalle: d.notas });
      await puente("notificar", { id: "actualizado", titulo: "SIOS se ha actualizado", texto: d.notas || "Ya tienes la última versión." }).catch(() => {});
    }
    if (reiniciar) setTimeout(reiniciarServicio, 1500);
    return { ok: true, hecho };
  } catch (err) {
    guardarEstado({ error: String(err.message || err) });
    return { ok: false, error: String(err.message || err) };
  }
}

/** Cambia los archivos del agente por los nuevos sin tocar node_modules (salvo que cambien las dependencias). */
async function cambiarServicio(tar) {
  const nuevo = `${AQUI}.nuevo`;
  const viejo = `${AQUI}.anterior`;
  rmSync(nuevo, { recursive: true, force: true });
  mkdirSync(nuevo, { recursive: true });
  const archivo = join(nuevo, "servicio.tar.gz");
  writeFileSync(archivo, tar);
  const r = await termux(`tar -xzf ${q(archivo)} -C ${q(nuevo)} && rm ${q(archivo)}`);
  if (!r.ok) throw new Error(`No se pudo descomprimir el agente: ${r.salida.slice(0, 200)}`);
  const deps = (p) => { try { return JSON.stringify(JSON.parse(readFileSync(p, "utf8")).dependencies || {}); } catch { return ""; } };
  if (deps(join(nuevo, "package.json")) === deps(join(AQUI, "package.json")) && existsSync(join(AQUI, "node_modules"))) {
    renameSync(join(AQUI, "node_modules"), join(nuevo, "node_modules"));
  } else {
    const i = await termux(`cd ${q(nuevo)} && npm install --omit=dev --no-audit --no-fund`, { timeout: 600000 });
    if (!i.ok) throw new Error(`No se pudieron instalar las dependencias del agente: ${i.salida.slice(-200)}`);
  }
  rmSync(viejo, { recursive: true, force: true });
  renameSync(AQUI, viejo);
  renameSync(nuevo, AQUI);
}

/** El agente nuevo arranca solo: iniciar.sh para este proceso y lanza el nuevo. */
function reiniciarServicio() {
  spawn("bash", [join(DATOS, "iniciar.sh")], { detached: true, stdio: "ignore" }).unref();
}

export function estado() {
  const e = leerEstado();
  return { ...e, automaticas: e.automaticas !== false };
}

export function ajustes({ automaticas, canal: c }) {
  const cambios = {};
  if (typeof automaticas === "boolean") cambios.automaticas = automaticas;
  if (typeof c === "string") cambios.canal = c.trim();
  return guardarEstado(cambios);
}

/** Cada 12 horas: buscar y, si están activadas, instalar solas. */
export function arrancarActualizaciones() {
  const pasar = async () => {
    const e = await buscar();
    if (e.disponible && estado().automaticas) await instalar();
  };
  setTimeout(() => pasar().catch(() => {}), 2 * 60_000);
  setInterval(() => pasar().catch(() => {}), CADA).unref?.();
}
