// SIOS Desktop: la continuidad del móvil con SIOS en el ordenador (Mac y Windows).
// Un icono en la barra (Mac) o en la bandeja (Windows) y una ventana para vincular y mandar cosas.
package main

import (
	_ "embed"
	"log"
	"os"
	"runtime"
	"time"

	"fyne.io/systray"
)

//go:embed recursos/barra_mac.png
var iconoBarraMac []byte

//go:embed recursos/bandeja.ico
var iconoBandeja []byte

//go:embed recursos/avisos.png
var iconoAvisos []byte

func main() {
	c := NuevoCliente()
	w := NuevaVentana(c)
	go func() { log.Fatal(w.Servir()) }()
	if os.Getenv("SIOS_SIN_ABRIR") != "" { // pruebas: la dirección de la ventana, aquí
		go func() {
			for w.direccion == "" {
				time.Sleep(50 * time.Millisecond)
			}
			log.Println("ventana:", w.URL())
		}()
	}
	go c.VigilarPortapapeles()
	go c.VigilarCopias()
	if e, _ := c.Estado(); e.Vinculado {
		c.Conectar()
	}
	systray.Run(func() { bandeja(c, w) }, func() {})
}

func bandeja(c *Cliente, w *Ventana) {
	if runtime.GOOS == "darwin" {
		systray.SetTemplateIcon(iconoBarraMac, iconoBarraMac)
	} else {
		systray.SetIcon(iconoBandeja)
	}
	systray.SetTooltip("SIOS Desktop")
	estado := systray.AddMenuItem("Sin vincular", "")
	estado.Disable()
	systray.AddSeparator()
	abrirVentana := systray.AddMenuItem("Abrir SIOS Desktop…", "")
	copiado := systray.AddMenuItem("Mandar lo copiado al móvil", "")
	enlace := systray.AddMenuItem("Abrir el enlace copiado en el móvil", "")
	sonar := systray.AddMenuItem("Hacer sonar el móvil", "")
	systray.AddSeparator()
	salir := systray.AddMenuItem("Salir", "")

	pintar := func() {
		e, _ := c.Estado()
		switch {
		case !e.Vinculado:
			estado.SetTitle("Sin vincular")
		case e.Conectado:
			t := "Conectado a " + e.NombreMovil
			if e.Bateria > 0 {
				t += " · " + itoa(e.Bateria) + " %"
			}
			estado.SetTitle(t)
		default:
			estado.SetTitle("Buscando " + e.NombreMovil + "…")
		}
		if e.Vinculado {
			copiado.Enable()
			enlace.Enable()
			sonar.Enable()
		} else {
			copiado.Disable()
			enlace.Disable()
			sonar.Disable()
		}
	}
	c.cambios = pintar
	pintar()
	// Primera vez: la ventana, para vincular.
	if e, _ := c.Estado(); !e.Vinculado && os.Getenv("SIOS_SIN_ABRIR") == "" {
		go func() {
			for w.direccion == "" {
				time.Sleep(50 * time.Millisecond)
			}
			w.Abrir()
		}()
	}
	go func() {
		for {
			select {
			case <-abrirVentana.ClickedCh:
				w.Abrir()
			case <-copiado.ClickedCh:
				if err := c.MandarCopiado(); err != nil {
					notificar("No se ha podido mandar", err.Error())
				}
			case <-enlace.ClickedCh:
				if err := c.AbrirEnMovil(""); err != nil {
					notificar("No se ha podido abrir", err.Error())
				}
			case <-sonar.ClickedCh:
				if err := c.Sonar(); err != nil {
					notificar("No se ha podido hacer sonar", err.Error())
				}
			case <-salir.ClickedCh:
				systray.Quit()
				return
			}
		}
	}()
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	s := ""
	for n > 0 {
		s = string(rune('0'+n%10)) + s
		n /= 10
	}
	return s
}
