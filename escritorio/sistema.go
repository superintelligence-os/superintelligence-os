package main

import (
	"os"
	"strings"

	"github.com/gen2brain/beeep"
)

func init() { beeep.AppName = "SIOS" }

func notificar(titulo, texto string) {
	beeep.Notify(titulo, texto, rutaIcono())
}

func nombreEquipo() string {
	if n := nombreBonito(); n != "" {
		return n
	}
	n, _ := os.Hostname()
	return strings.TrimSuffix(n, ".local")
}

// medidaVentana: casi toda la pantalla, centrada y sin pantalla completa (con un máximo para que
// en monitores grandes no quede desproporcionada). Ancho, alto y posición.
func medidaVentana() (w, h, x, y int) {
	pw, ph := pantalla()
	w = min(pw-80, 1480)
	h = min(ph-60, 960)
	w, h = max(w, 900), max(h, 640)
	return w, h, max((pw-w)/2, 0), max((ph-h)/2, 0) + 24
}
