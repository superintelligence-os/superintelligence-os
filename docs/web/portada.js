// Portada de SIOS: la animación de arranque que vuela hasta el móvil, la IA que eliges y el agente
// haciendo un encargo. Sin librerías.
(() => {
  const raiz = document.documentElement;
  const quieto = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const anillo = document.getElementById("anillo");
  const charla = document.getElementById("charla");
  const pregunta = document.getElementById("pregunta");
  const espera = (ms) => new Promise((r) => setTimeout(r, ms));

  // ---------- El agente haciendo el encargo ----------
  let vuelta = 0;
  async function demo() {
    const mia = ++vuelta;
    const burbuja = charla.querySelector(".burbuja");
    const pasos = [...charla.querySelectorAll(".paso")];
    [burbuja, ...pasos].forEach((e) => e.classList.remove("visto", "hecho"));
    anillo.classList.remove("apartado");
    if (quieto) { burbuja.classList.add("visto"); pasos.forEach((p) => p.classList.add("visto", "hecho")); anillo.classList.add("apartado"); return; }
    await espera(500); if (mia !== vuelta) return;
    anillo.classList.add("apartado");
    burbuja.classList.add("visto");
    await espera(700);
    for (const p of pasos) {
      if (mia !== vuelta) return;
      p.classList.add("visto");
      await espera(820);
      if (mia !== vuelta) return;
      p.classList.add("hecho");
      await espera(160);
    }
  }

  // ---------- La IA que eliges ----------
  const botones = [...document.querySelectorAll(".ias button")];
  botones.forEach((b) => b.addEventListener("click", () => {
    const yaEstaba = b.getAttribute("aria-pressed") === "true";
    botones.forEach((x) => x.setAttribute("aria-pressed", "false"));
    if (yaEstaba) {
      raiz.style.removeProperty("--ia"); raiz.style.removeProperty("--ia-2");
      pregunta.textContent = pregunta.dataset.plantilla.replace("{}", "SIOS");
    } else {
      b.setAttribute("aria-pressed", "true");
      raiz.style.setProperty("--ia", b.dataset.c); raiz.style.setProperty("--ia-2", "#141413");
      pregunta.textContent = pregunta.dataset.plantilla.replace("{}", b.dataset.nombre);
    }
    demo();
  }));

  // ---------- Aparecer al bajar ----------
  const revelar = [...document.querySelectorAll(".revela")];
  if (quieto || !("IntersectionObserver" in window)) revelar.forEach((e) => e.classList.add("visto"));
  else {
    const io = new IntersectionObserver((entradas) => entradas.forEach((e) => {
      if (!e.isIntersecting) return;
      // Los de una misma fila, uno detrás de otro.
      const hermanos = [...e.target.parentElement.children].filter((x) => x.classList.contains("revela"));
      e.target.style.transitionDelay = `${Math.max(0, hermanos.indexOf(e.target)) * 90}ms`;
      e.target.classList.add("visto");
      io.unobserve(e.target);
    }), { rootMargin: "0px 0px -12% 0px" });
    revelar.forEach((e) => io.observe(e));
  }

  // ---------- Arranque: el logo se dibuja y vuela hasta el móvil ----------
  const capa = document.getElementById("arranque");
  const video = document.getElementById("video-arranque");
  let hecho = false;
  function dibujarAnillo(al = true) { anillo.classList.add("dibujado"); if (!al) anillo.classList.add("sin-transicion"); }

  async function terminar(volar) {
    if (hecho) return; hecho = true;
    document.body.classList.remove("arrancando");
    if (!volar || quieto) {
      capa.remove(); dibujarAnillo(false); demo(); return;
    }
    // FLIP: del logo del vídeo al anillo del móvil.
    const v = video.getBoundingClientRect(), a = anillo.getBoundingClientRect();
    const logoV = { x: v.left + v.width * 0.5, y: v.top + v.height * (308 / 640), w: v.width * 250 / 640 }; // medido en el último fotograma
    // El logo del móvil tal como está en pantalla (el trazo, más medio grosor de línea, y el punto).
    const t = anillo.querySelector(".trazo").getBoundingClientRect(), p = anillo.querySelector(".punto").getBoundingClientRect();
    const medio = (12.5 / 2) * (a.width / 80);
    const caja = { l: Math.min(t.left - medio, p.left), r: Math.max(t.right + medio, p.right), t: Math.min(t.top - medio, p.top), b: Math.max(t.bottom + medio, p.bottom) };
    const logoA = { x: (caja.l + caja.r) / 2, y: (caja.t + caja.b) / 2, w: caja.r - caja.l };
    const s = logoA.w / logoV.w;
    const destino = `translate(${logoA.x - logoV.x}px, ${logoA.y - logoV.y}px) scale(${s})`;
    const movimiento = video.animate(
      [{ transform: "translate(0,0) scale(1)" }, { transform: destino }],
      { duration: 1050, easing: "cubic-bezier(.65,0,.2,1)", fill: "forwards" });
    // Fundido cruzado: a mitad del vuelo, debajo ya está el anillo del móvil y el vídeo se desvanece
    // (así el rótulo del vídeo no llega a entrar en la pantalla).
    // El difuminado del borde se cierra sobre el logo (el rótulo se va) sin dejar un disco marcado.
    video.animate([{ "--r": "100%" }, { "--r": "66%", offset: .4 }, { "--r": "66%" }],
      { duration: 1050, easing: "cubic-bezier(.4,0,.2,1)", fill: "forwards" });
    video.animate([{ opacity: 1 }, { opacity: 1, offset: .88 }, { opacity: 0 }], { duration: 1050, easing: "linear", fill: "forwards" });
    setTimeout(() => dibujarAnillo(false), 920);
    capa.animate([{ backgroundColor: "rgba(20,20,19,1)" }, { backgroundColor: "rgba(20,20,19,0)" }], { duration: 900, delay: 150, easing: "ease-out", fill: "forwards" });
    document.querySelector(".texto-portada").animate(
      [{ opacity: 0, transform: "translateY(28px)" }, { opacity: 1, transform: "none" }],
      { duration: 1000, delay: 380, easing: "cubic-bezier(.34,1.56,.64,1)", fill: "backwards" });
    await movimiento.finished;
    capa.animate([{ opacity: 1 }, { opacity: 0 }], { duration: 260, fill: "forwards" }).finished.then(() => capa.remove());
    demo();
  }

  if (quieto || sessionStorage.getItem("sios-arranque")) {
    terminar(false);
  } else {
    document.body.classList.add("arrancando");
    sessionStorage.setItem("sios-arranque", "1");
    // La parte de entrada dura 2,8 s; al terminar, vuela (y si el vídeo no puede, a los 4 s igual).
    video.addEventListener("timeupdate", () => { if (video.currentTime >= 3.05) terminar(true); });
    video.addEventListener("ended", () => terminar(true));
    video.play().catch(() => terminar(false));
    setTimeout(() => terminar(true), 4200);
    document.getElementById("saltar").addEventListener("click", () => terminar(true));
    addEventListener("keydown", (e) => { if (e.key === "Escape") terminar(true); }, { once: true });
  }
})();
