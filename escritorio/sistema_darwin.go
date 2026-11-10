package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

func nombreBonito() string {
	b, err := exec.Command("scutil", "--get", "ComputerName").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func abrir(url string) { exec.Command("open", url).Start() }

// La ventana, nativa (ventana_darwin.go); si no se pudiera, en el navegador.
func abrirComoApp(url string) {
	if !abrirVentanaNativa(url) {
		abrir(url)
	}
}

// pantalla: el tamaño útil del escritorio (en puntos), lo que dice el Finder.
func pantalla() (int, int) {
	out, err := exec.Command("osascript", "-e", `tell application "Finder" to get bounds of window of desktop`).Output()
	if err == nil {
		var x0, y0, x1, y1 int
		if n, _ := fmt.Sscanf(strings.ReplaceAll(strings.TrimSpace(string(out)), ",", ""), "%d %d %d %d", &x0, &y0, &x1, &y1); n == 4 && x1 > 0 {
			return x1 - x0, y1 - y0
		}
	}
	return 1440, 900
}

func mostrarEnCarpeta(ruta string) { exec.Command("open", "-R", ruta).Start() }

func rutaIcono() string { return "" }

const agente = "com.sios.escritorio"

func alIniciarPuesto() bool {
	casa, _ := os.UserHomeDir()
	_, err := os.Stat(filepath.Join(casa, "Library", "LaunchAgents", agente+".plist"))
	return err == nil
}

// Abrir al iniciar sesión: un LaunchAgent que abre la app.
func ponerAlIniciar(si bool) {
	casa, _ := os.UserHomeDir()
	plist := filepath.Join(casa, "Library", "LaunchAgents", agente+".plist")
	if !si {
		os.Remove(plist)
		return
	}
	ejecutable, _ := os.Executable()
	app := ejecutable
	if i := strings.Index(ejecutable, ".app/"); i > 0 {
		app = ejecutable[:i+4]
	}
	_ = app
	os.MkdirAll(filepath.Dir(plist), 0o755)
	// El propio programa de la app (no «open»: macOS enseñaría «open» en Ítems de inicio) y su
	// identificador, para que en Ítems de inicio y en el aviso salga «SIOS Desktop» con su icono.
	os.WriteFile(plist, []byte(fmt.Sprintf(`<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>%s</string>
<key>ProgramArguments</key><array><string>%s</string></array>
<key>AssociatedBundleIdentifiers</key><array><string>%s</string></array>
<key>RunAtLoad</key><true/>
<key>ProcessType</key><string>Interactive</string>
</dict></plist>
`, agente, ejecutable, agente)), 0o644)
}
