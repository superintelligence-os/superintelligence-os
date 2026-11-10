//go:build movil

package main

import "testing"

// SIOS_SERIE=emulator-5560 go test -tags movil -run TestExportarContactos -v
func TestExportarContactos(t *testing.T) {
	vcf, n, err := exportarContactos("emulator-5560")
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("%d contactos\n%s", n, vcf)
}
