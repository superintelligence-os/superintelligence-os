//go:build movil

package main

// Prueba de punta a punta con un móvil (o el emulador con `adb forward tcp:8766 tcp:8766`):
// SIOS_MOVIL=127.0.0.1:8766 SIOS_CODIGO=123456 go test -tags movil -run TestMovil -v

import (
	"bytes"
	"encoding/json"
	"net/http"
	"os"
	"testing"
	"time"
)

func local(t *testing.T, ruta string, cuerpo any) map[string]any {
	b, _ := json.Marshal(cuerpo)
	r, err := http.Post("http://127.0.0.1:18765"+ruta, "application/json", bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	defer r.Body.Close()
	var m map[string]any
	json.NewDecoder(r.Body).Decode(&m)
	return m
}

func TestMovil(t *testing.T) {
	v, err := Vincular(os.Getenv("SIOS_MOVIL"), os.Getenv("SIOS_CODIGO"), "Prueba")
	if err != nil {
		t.Fatal("vincular:", err)
	}
	t.Logf("vinculado con %s (id %s)", v.NombreMovil, v.ID)
	e, err := v.Pedir("/estado", nil, 10*time.Second)
	if err != nil {
		t.Fatal("estado:", err)
	}
	t.Logf("estado: %v", e)
	seq := e["seq"].(float64)

	// Del móvil al ordenador: un aviso y un archivo.
	local(t, "/continuidad/evento", map[string]any{"tipo": "aviso", "app": "Mensajes", "titulo": "Ana", "texto": "¿Comemos?"})
	local(t, "/continuidad/archivo", map[string]any{"nombre": "hola.txt", "datos": b64.EncodeToString([]byte("hola desde el móvil"))})
	ev, err := v.Pedir("/eventos", map[string]any{"desde": seq}, 30*time.Second)
	if err != nil {
		t.Fatal("eventos:", err)
	}
	lista := ev["eventos"].([]any)
	if len(lista) != 2 {
		t.Fatalf("esperaba 2 eventos, hay %d: %v", len(lista), ev)
	}
	archivo := lista[1].(map[string]any)
	a, err := v.Pedir("/archivo/bajar", map[string]any{"id": archivo["id"]}, 30*time.Second)
	if err != nil {
		t.Fatal("bajar:", err)
	}
	if d, _ := b64.DecodeString(a["datos"].(string)); string(d) != "hola desde el móvil" {
		t.Fatalf("archivo distinto: %q", d)
	}
	t.Logf("eventos y archivo: bien (%v)", lista[0])

	// Del ordenador al móvil.
	if _, err := v.Pedir("/portapapeles", map[string]any{"texto": "copiado en el ordenador"}, 10*time.Second); err != nil {
		t.Fatal("portapapeles:", err)
	}
	if _, err := v.Pedir("/archivo/subir", map[string]any{"nombre": "desde-el-ordenador.txt", "datos": b64.EncodeToString([]byte("hola, móvil"))}, 30*time.Second); err != nil {
		t.Fatal("subir:", err)
	}
	// Una petición vieja (repetida) no vale.
	viejo := *v
	if _, err := viejo.Pedir("/nada", nil, 5*time.Second); err == nil {
		t.Fatal("una ruta desconocida debería fallar")
	}
	// Clave equivocada: rechazada.
	malo := *v
	malo.Clave = b64.EncodeToString(make([]byte, 32))
	if _, err := malo.Pedir("/estado", nil, 5*time.Second); err == nil {
		t.Fatal("con otra clave no debería entrar")
	}
	if _, err := v.Pedir("/desvincular", nil, 5*time.Second); err != nil {
		t.Fatal("desvincular:", err)
	}
	if _, err := v.Pedir("/estado", nil, 5*time.Second); err != ErrNoVinculado {
		t.Fatal("tras desvincular debería decir que no está vinculado, dice:", err)
	}
	t.Log("todo bien")
}
