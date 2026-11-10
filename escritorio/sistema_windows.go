package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows/registry"
)

func nombreBonito() string { return os.Getenv("COMPUTERNAME") }

func sinVentana(c *exec.Cmd) *exec.Cmd {
	c.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return c
}

func abrir(url string) {
	sinVentana(exec.Command("rundll32", "url.dll,FileProtocolHandler", url)).Start()
}

// La ventana, nativa (ventana_windows.go); si no se pudiera, Edge en modo app.
func abrirComoApp(url string) {
	if !abrirVentanaNativa(url) {
		abrirConEdge(url)
	}
}

// Edge en modo app (viene con Windows); si no, el navegador.
func abrirConEdge(url string) {
	w, h, x, y := medidaVentana()
	if err := sinVentana(exec.Command("cmd", "/c", "start", "", "msedge", "--app="+url, "--user-data-dir="+filepath.Join(carpetaApp(), "ventana"),
		"--no-first-run", "--no-default-browser-check",
		fmt.Sprintf("--window-size=%d,%d", w, h), fmt.Sprintf("--window-position=%d,%d", x, y))).Start(); err != nil {
		abrir(url)
	}
}

// pantalla: el área de trabajo (sin la barra de tareas), de user32.
func pantalla() (int, int) {
	u := syscall.NewLazyDLL("user32.dll").NewProc("GetSystemMetrics")
	w, _, _ := u.Call(16) // SM_CXFULLSCREEN
	h, _, _ := u.Call(17) // SM_CYFULLSCREEN
	if w == 0 || h == 0 {
		return 1440, 900
	}
	return int(w), int(h)
}

func mostrarEnCarpeta(ruta string) { exec.Command("explorer", "/select,", ruta).Start() }

// El icono para los avisos (Windows lo quiere en un archivo).
func rutaIcono() string {
	dir, _ := os.UserConfigDir()
	ruta := filepath.Join(dir, "SIOS", "icono.png")
	if _, err := os.Stat(ruta); err != nil {
		os.MkdirAll(filepath.Dir(ruta), 0o700)
		os.WriteFile(ruta, iconoAvisos, 0o644)
	}
	return ruta
}

// Abrir al iniciar sesión: HKCU\…\Run.
func ponerAlIniciar(si bool) {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Run`, registry.SET_VALUE)
	if err != nil {
		return
	}
	defer k.Close()
	if !si {
		k.DeleteValue("SIOS Desktop")
		return
	}
	ejecutable, _ := os.Executable()
	k.SetStringValue("SIOS Desktop", `"`+ejecutable+`"`)
}

// En Windows la entrada de inicio ya apunta al programa: no hay nada que rehacer.
func alIniciarPuesto() bool { return false }

// libreEnOrdenador: GB libres donde SIOS Desktop guarda la imagen.
func libreEnOrdenador() float64 {
	var libre uint64
	ruta, err := syscall.UTF16PtrFromString(carpetaApp())
	if err != nil {
		return 0
	}
	p := syscall.NewLazyDLL("kernel32.dll").NewProc("GetDiskFreeSpaceExW")
	if r, _, _ := p.Call(uintptr(unsafe.Pointer(ruta)), uintptr(unsafe.Pointer(&libre)), 0, 0); r == 0 {
		return 0
	}
	return float64(libre) / (1 << 30)
}
