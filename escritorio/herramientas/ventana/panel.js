function anillo(valor, color) {
  const v = Math.max(0, Math.min(100, Number(valor) || 0)), r = 30, l = 2 * Math.PI * r;
  const ns = "http://www.w3.org/2000/svg", svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 72 72"); svg.setAttribute("class", "anillo");
  const c = (stroke, dash) => { const x = document.createElementNS(ns, "circle"); x.setAttribute("cx", 36); x.setAttribute("cy", 36); x.setAttribute("r", r); x.setAttribute("fill", "none");
    x.setAttribute("stroke-width", 6); x.setAttribute("stroke", stroke); x.setAttribute("stroke-linecap", "round"); if (dash) { x.setAttribute("stroke-dasharray", l); x.setAttribute("stroke-dashoffset", l * (1 - v / 100)); x.setAttribute("transform", "rotate(-90 36 36)"); } return x; };
  svg.append(c("var(--superficie-2)"), c(color, true));
  const t = document.createElementNS(ns, "text"); t.setAttribute("x", 36); t.setAttribute("y", 41); t.setAttribute("text-anchor", "middle"); t.setAttribute("fill", "currentColor");
  t.setAttribute("font-size", 16); t.setAttribute("font-family", "Montserrat"); t.textContent = Math.round(v); svg.append(t);
  return svg;
}
function grafico(serie, color, tipo) {
  const ys = serie.map((p) => Number(p.y) || 0), n = ys.length;
  const ns = "http://www.w3.org/2000/svg", svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 100 40"); svg.setAttribute("preserveAspectRatio", "none"); svg.setAttribute("class", "grafico");
  if (tipo === "barras") {
    const max = Math.max(...ys, 1);
    ys.forEach((y, i) => { const r = document.createElementNS(ns, "rect"), w = 100 / n, h = Math.max(1.5, (y / max) * 38);
      r.setAttribute("x", i * w + w * 0.2); r.setAttribute("width", w * 0.6); r.setAttribute("y", 40 - h); r.setAttribute("height", h); r.setAttribute("rx", 1.2);
      r.setAttribute("fill", color); r.setAttribute("opacity", i === n - 1 ? 1 : 0.45); svg.append(r); });
    return svg;
  }
  // Línea entre el mínimo y el máximo (como en el móvil), con un velo debajo.
  const min = Math.min(...ys), max = Math.max(...ys), rango = max - min || 1;
  const pts = ys.map((y, i) => [n === 1 ? 50 : (i / (n - 1)) * 100, 36 - ((y - min) / rango) * 32]);
  const d = pts.map((p, i) => (i ? "L" : "M") + p[0].toFixed(2) + " " + p[1].toFixed(2)).join(" ");
  const area = document.createElementNS(ns, "path"); area.setAttribute("d", d + " L100 40 L0 40 Z"); area.setAttribute("fill", color); area.setAttribute("opacity", 0.12);
  const linea = document.createElementNS(ns, "path"); linea.setAttribute("d", d); linea.setAttribute("fill", "none"); linea.setAttribute("stroke", color);
  linea.setAttribute("stroke-width", 2); linea.setAttribute("vector-effect", "non-scaling-stroke"); linea.setAttribute("stroke-linejoin", "round"); linea.setAttribute("stroke-linecap", "round");
  svg.append(area, linea);
  return svg;
}
function tarjeta(w) {
  const d = w.datos || {}, color = COLORES[w.acento] || "var(--acento)";
  const t = el("div", "w " + (w.tamano || "mediano")); t.style.setProperty("--a", color);
  const cab = el("div", "cab"); cab.append(el("i"), el("b", "", d.titulo || w.nombre)); t.append(cab);
  const elems = Array.isArray(d.elementos) ? d.elementos : [];
  const filas = (n) => { const f = el("div", "filas"); elems.slice(0, n).forEach((e) => { const r = el("div", "fila2");
    if (e.hora) r.append(el("span", "h", e.hora)); r.append(el("span", "t", e.texto || e.titulo || "")); if (e.valor != null && e.valor !== "") r.append(el("span", "v", e.valor)); f.append(r); }); return f; };
  const caben = w.tamano === "grande" ? 9 : w.tamano === "pequeno" ? 3 : 4;
  switch (w.plantilla) {
    case "cifra": case "progreso": {
      const c = el("div", "cifra", d.valor ?? "—"); if (d.unidad) c.append(el("small", "", d.unidad)); t.append(c);
      if (d.subtitulo) t.append(el("div", "sub", d.subtitulo));
      if (d.progreso != null) { const b = el("div", "barra"), i = el("i"); i.style.width = Math.round(Math.min(1, d.progreso) * 100) + "%"; b.append(i); t.append(b); }
      break; }
    case "grafico": {
      const c = el("div", "cifra", d.valor ?? ""); if (d.unidad) c.append(el("small", "", d.unidad)); c.style.fontSize = "30px"; t.append(c);
      if (d.subtitulo) t.append(el("div", "sub", d.subtitulo));
      if (Array.isArray(d.serie) && d.serie.length) t.append(grafico(d.serie, color, d.tipo));
      break; }
    case "anillos": { const a = el("div", "anillos"); elems.slice(0, w.tamano === "pequeno" ? 1 : 4).forEach((e) => a.append(anillo(e.valor, color))); t.append(a);
      if (elems[0]) t.append(el("div", "sub", elems.slice(0, w.tamano === "pequeno" ? 1 : 4).map((e) => e.texto).join(" · "))); break; }
    case "marcador": { const m = el("div", "marcador");
      m.append(el("div", "eq", d.local || ""), el("div", "goles", (d.goles_local ?? "–") + " – " + (d.goles_visitante ?? "–")), el("div", "eq", d.visitante || "")); t.append(m);
      if (d.estado) t.append(el("div", "sub", d.estado)); break; }
    case "texto": t.append(el("div", "texto", d.texto || "")); break;
    case "noticias": { const f = el("div", "noticias");
      elems.slice(0, w.tamano === "grande" ? 4 : 2).forEach((e) => { const n = el(e.enlace ? "a" : "div", "noticia");
        if (e.enlace) { n.href = "#"; n.onclick = (ev) => { ev.preventDefault(); post("/api/enlace", { url: e.enlace }).then(() => aviso("Abierto en el móvil")).catch((x) => aviso(x.message)); }; }
        n.append(el("div", "titular", e.texto || ""), el("div", "fuente", [e.fuente, e.hora].filter(Boolean).join(" · "))); f.append(n); });
      t.append(f); break; }
    case "lista": { const f = el("div", "filas"); elems.slice(0, caben).forEach((e) => { const r = el("div", "fila2" + (e.hecho ? " hecho" : ""));
      r.append(el("span", "casilla"), el("span", "t", e.texto || "")); f.append(r); }); t.append(f); break; }
    default: t.append(elems.length ? filas(caben) : el("div", "texto", d.texto || ""));
  }
  return t;
}
