package main

import (
	"net/url"
	"os"
	"os/exec"
	"sync"
)

// La ventana de SIOS Desktop es nativa (WKWebView en el Mac, WebView2 en Windows), no un navegador:
// sale en el Dock y en la barra de tareas como SIOS Desktop, con su icono. Va en un proceso aparte
// (el mismo programa con «--ventana <dirección>») porque en el Mac la ventana y el icono de la barra
// de menús quieren cada uno el hilo principal.

var (
	ventanaMu      sync.Mutex
	procesoVentana *os.Process
)

// abrirVentanaNativa: la abre o, si ya está abierta, la trae al frente. false si no se ha podido.
func abrirVentanaNativa(direccion string) bool {
	ventanaMu.Lock()
	defer ventanaMu.Unlock()
	if procesoVentana != nil && traerAlFrente(procesoVentana) {
		return true
	}
	exe, err := os.Executable()
	if err != nil {
		return false
	}
	cmd := exec.Command(exe, "--ventana", direccion)
	if err := cmd.Start(); err != nil {
		return false
	}
	procesoVentana = cmd.Process
	go func() {
		cmd.Wait()
		ventanaMu.Lock()
		if procesoVentana == cmd.Process {
			procesoVentana = nil
		}
		ventanaMu.Unlock()
	}()
	return true
}

// Fuera de la ventana solo se abren páginas web, en el navegador del ordenador.
func abrirFuera(enlace string) {
	if u, err := url.Parse(enlace); err == nil && (u.Scheme == "https" || u.Scheme == "http") && u.Host != "" {
		abrir(u.String())
	}
}

// Dentro de la ventana, los enlaces a otras páginas (window.open, target=_blank) van al navegador.
const scriptVentana = `
window.open = (u) => { window.abrirFuera(String(u)); return null; };
document.addEventListener("click", (e) => {
  const a = e.target.closest && e.target.closest("a[href]");
  if (!a) return;
  const u = new URL(a.href, location.href);
  if (u.origin !== location.origin || a.target === "_blank") { e.preventDefault(); window.abrirFuera(u.href); }
}, true);
`
