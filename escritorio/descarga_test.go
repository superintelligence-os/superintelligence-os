package main

import (
	"os"
	"strings"
	"testing"
)

// Prueba de la descarga contra un servidor local con imagenes.json firmado
// (SIOS_VERSIONES=http://127.0.0.1:PUERTO go test -run Descarga). Sin esa variable no hace nada.
func TestDescarga(t *testing.T) {
	if os.Getenv("SIOS_VERSIONES") == "" {
		t.Skip("sin SIOS_VERSIONES")
	}
	tarea := &Tarea{}
	img, err := imagenLista(tarea, os.Getenv("SIOS_EDICION"), os.Getenv("SIOS_PLANA") == "1")
	t.Log(strings.Join(tarea.Pasos, "\n"))
	if err != nil {
		t.Fatal(err)
	}
	t.Log("imagen:", img)
}
