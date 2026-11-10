package main

import (
	"os"

	webview2 "github.com/jchv/go-webview2"
)

func ventanaNativa(direccion string) {
	ancho, alto, _, _ := medidaVentana()
	w := webview2.NewWithOptions(webview2.WebViewOptions{
		AutoFocus:     true,
		WindowOptions: webview2.WindowOptions{Title: "SIOS Desktop", Width: uint(ancho), Height: uint(alto), Center: true},
	})
	if w == nil { // sin WebView2 en este Windows: Edge en modo app, como antes
		abrirConEdge(direccion)
		return
	}
	defer w.Destroy()
	w.Bind("abrirFuera", abrirFuera)
	w.Init(scriptVentana)
	w.Navigate(direccion)
	w.Run()
}

// En Windows, si ya está abierta, se deja: está en la barra de tareas.
func traerAlFrente(p *os.Process) bool { return true }
