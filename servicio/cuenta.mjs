// Cuenta de Claude: comprobar si hay sesión y entrar con un toque desde la app.
//
// `claude auth login` abre el navegador con un enlace que, al terminar, vuelve a localhost (el
// propio Claude Code lo recoge) y no hay que copiar ningún código. Dentro de Debian no hay
// navegador: la variable BROWSER apunta a ~/sios/navegador, que manda el enlace aquí
// (POST /cuenta/enlace) y la app lo abre en el navegador del móvil. Si algo falla, queda el
// enlace manual de Claude Code y la app deja pegar el código (POST /cuenta/codigo).
import { spawn } from "node:child_process";

const HOME = process.env.HOME || "/data/data/com.termux/files/home";
// Solo se abren enlaces de inicio de sesión de Claude, nunca otros (cualquier app del móvil
// puede hablar con 127.0.0.1).
const ENLACE_VALIDO = /^https:\/\/(claude\.ai|claude\.com|platform\.claude\.com|console\.anthropic\.com)\//;

function claude(args, { stdin = "ignore", env = [] } = {}) {
  return spawn(
    "proot-distro",
    ["login", "debian", "--shared-tmp", "--bind", `${HOME}:/termux`, "--", "env", ...env, "/root/.local/bin/claude", ...args],
    { stdio: [stdin, "pipe", "pipe"] },
  );
}

export class Cuenta {
  constructor(emitir) {
    this.emitir = emitir;
    this.estado = { conectada: null, entrando: false }; // conectada: null = aún sin comprobar
    this.proceso = null;
    this.enlaceManual = null;
  }

  publicar(cambios) {
    Object.assign(this.estado, cambios);
    this.emitir({ tipo: "cuenta", cuenta: this.estado }, { guardar: false });
  }

  /** `claude auth status` es local: no gasta suscripción. */
  comprobar() {
    return new Promise((resolver) => {
      const p = claude(["auth", "status"]);
      let salida = "";
      p.stdout.on("data", (d) => (salida += d));
      p.on("error", () => resolver(this.estado));
      p.on("close", () => {
        let datos = {};
        try {
          datos = JSON.parse(salida.slice(salida.indexOf("{")));
        } catch {}
        this.publicar({
          conectada: datos.loggedIn === true,
          email: datos.email || datos.account?.email || null,
          plan: datos.subscriptionType || null,
        });
        resolver(this.estado);
      });
    });
  }

  entrar() {
    if (this.proceso) {
      // Ya hay un intento en marcha: se vuelve a dar el enlace (por si se cerró el navegador).
      if (this.estado.enlace) this.publicar({});
      return;
    }
    this.enlaceManual = null;
    this.publicar({ entrando: true, enlace: null, error: null, pideCodigo: false });
    const p = claude(["auth", "login", "--claudeai"], {
      stdin: "pipe",
      env: ["BROWSER=/termux/sios/navegador"],
    });
    this.proceso = p;
    let salida = "";
    const leer = (d) => {
      salida += d;
      // Enlace manual (el que pide pegar un código): solo se usa si BROWSER no llega a funcionar.
      const m = salida.match(/visit: (https:\/\/\S+)/);
      if (m && !this.enlaceManual) this.enlaceManual = m[1];
    };
    p.stdout.on("data", leer);
    p.stderr.on("data", leer);
    // Si en unos segundos no ha llegado el enlace bueno, se ofrece el manual con código.
    const reserva = setTimeout(() => {
      if (!this.estado.enlace && this.enlaceManual) this.publicar({ enlace: this.enlaceManual, pideCodigo: true });
    }, 8000);
    p.on("error", (e) => this.publicar({ entrando: false, error: String(e.message || e) }));
    p.on("close", async (codigo) => {
      clearTimeout(reserva);
      this.proceso = null;
      await this.comprobar();
      this.publicar({
        entrando: false,
        enlace: null,
        pideCodigo: false,
        error: this.estado.conectada ? null : codigo === 0 ? null : "No se completó el inicio de sesión. Prueba otra vez.",
      });
    });
  }

  /** Lo llama ~/sios/navegador con el enlace que Claude Code quiere abrir. */
  enlace(url) {
    if (!this.proceso || !ENLACE_VALIDO.test(url)) return false;
    this.publicar({ enlace: url, pideCodigo: false });
    return true;
  }

  codigo(texto) {
    if (!this.proceso?.stdin.writable || !texto) return false;
    this.proceso.stdin.write(texto.trim() + "\n");
    return true;
  }

  cancelar() {
    this.proceso?.kill("SIGTERM");
  }

  /** Cerrar la sesión de Claude (lo pide el usuario desde Ajustes › Inteligencia). */
  salir() {
    return new Promise((listo) => {
      const p = claude(["auth", "logout"]);
      p.on("close", () => this.comprobar().then(listo));
      p.on("error", () => listo(this.estado));
    });
  }
}
