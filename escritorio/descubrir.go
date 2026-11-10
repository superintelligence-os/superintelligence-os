package main

// Encontrar el móvil en la red local: la app de SIOS se anuncia como _sios._tcp.

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/grandcat/zeroconf"
)

type Movil struct {
	Nombre string `json:"nombre"`
	Host   string `json:"host"`
}

func Descubrir(espera time.Duration) []Movil {
	r, err := zeroconf.NewResolver(nil)
	if err != nil {
		return nil
	}
	entradas := make(chan *zeroconf.ServiceEntry)
	var lista []Movil
	listo := make(chan struct{})
	go func() {
		for e := range entradas {
			if len(e.AddrIPv4) == 0 {
				continue
			}
			nombre := strings.ReplaceAll(e.Instance, `\ `, " ")
			lista = append(lista, Movil{Nombre: nombre, Host: fmt.Sprintf("%s:%d", e.AddrIPv4[0], e.Port)})
		}
		close(listo)
	}()
	ctx, cancelar := context.WithTimeout(context.Background(), espera)
	defer cancelar()
	r.Browse(ctx, "_sios._tcp", "local.", entradas)
	<-ctx.Done()
	<-listo
	return lista
}

func buscarPorNombre(nombre string) string {
	for _, m := range Descubrir(3 * time.Second) {
		if m.Nombre == nombre {
			return m.Host
		}
	}
	return ""
}
