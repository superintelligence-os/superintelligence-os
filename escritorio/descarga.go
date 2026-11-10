// Descargar SIOS: si no hay una imagen en el ordenador, SIOS Desktop baja la de la última versión
// publicada en GitHub, con lo que el usuario solo tiene que descargar esta app.
//
// En cada versión se publican, junto a las imágenes, «imagenes.json» (qué imagen hay de cada
// edición, con su tamaño y su huella SHA-256) y «imagenes.firma» (firma Ed25519 de ese archivo con
// la clave de las actualizaciones, la misma que comprueban los móviles). Se comprueba la firma y,
// al acabar la descarga, la huella: una imagen cambiada por el camino no se instala nunca.
package main

import (
	"compress/gzip"
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/x509"
	_ "embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

// La clave pública de las actualizaciones (scripts/clave-actualizaciones.sh la deja aquí).
//
//go:embed recursos/clave-actualizaciones.pem
var claveActualizaciones []byte

// De dónde se descargan las versiones (SIOS_VERSIONES lo cambia, para probar con otro servidor).
var versionesSIOS = func() string {
	if v := os.Getenv("SIOS_VERSIONES"); v != "" {
		return strings.TrimSuffix(v, "/") + "/"
	}
	return "https://github.com/superintelligence-os/superintelligence-os/releases/latest/download/"
}()

type imagenPublicada struct {
	Archivo string `json:"archivo"`
	Tamano  int64  `json:"tamano"`
	SHA256  string `json:"sha256"`
}

type listaImagenes struct {
	Version  string                     `json:"version"`
	Imagenes map[string]imagenPublicada `json:"imagenes"` // "puro" (sin Google); "google" no se publica
}

var archivoImagenRe = regexp.MustCompile(`^sios[\w.-]*\.img\.gz$`)

func bajar(url string, limite time.Duration) ([]byte, error) {
	c := &http.Client{Timeout: limite}
	r, err := c.Get(url)
	if err != nil {
		return nil, err
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return nil, fmt.Errorf("HTTP %d", r.StatusCode)
	}
	return io.ReadAll(io.LimitReader(r.Body, 1<<20))
}

// imagenesPublicadas: la lista de la última versión, solo si la firma es buena.
func imagenesPublicadas() (*listaImagenes, error) {
	datos, err := bajar(versionesSIOS+"imagenes.json", 30*time.Second)
	if err != nil {
		return nil, errors.New("no se ha podido consultar la última versión de SIOS: mira tu conexión")
	}
	firma64, err := bajar(versionesSIOS+"imagenes.firma", 30*time.Second)
	if err != nil {
		return nil, errors.New("la versión publicada de SIOS no trae firma: no se descarga")
	}
	bloque, _ := pem.Decode(claveActualizaciones)
	if bloque == nil {
		return nil, errors.New("falta la clave de SIOS en esta app")
	}
	pub, err := x509.ParsePKIXPublicKey(bloque.Bytes)
	clave, ok := pub.(ed25519.PublicKey)
	if err != nil || !ok {
		return nil, errors.New("la clave de SIOS de esta app no es válida")
	}
	firma, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(firma64)))
	if err != nil || !ed25519.Verify(clave, datos, firma) {
		return nil, errors.New("la firma de la versión publicada no es la de SIOS: no se descarga")
	}
	var l listaImagenes
	if err := json.Unmarshal(datos, &l); err != nil {
		return nil, errors.New("la lista de versiones de SIOS no se entiende")
	}
	return &l, nil
}

// imagenLista: la imagen con la que instalar. La que haya en el ordenador o, si no hay, la de la
// última versión (de la edición elegida), descargada y comprobada. Con sinComprimir, la .img.
func imagenLista(t *Tarea, edicion string, sinComprimir bool) (string, error) {
	img := imagenSIOS()
	if img == "" {
		var err error
		if img, err = descargarImagen(t, edicion); err != nil {
			return "", err
		}
	}
	if !sinComprimir || !strings.HasSuffix(img, ".gz") {
		return img, nil
	}
	plana := strings.TrimSuffix(img, ".gz")
	if info, err := os.Stat(plana); err == nil && info.Size() > 0 {
		return plana, nil
	}
	t.paso("Descomprimiendo SIOS…")
	if err := descomprimir(img, plana); err != nil {
		return "", fmt.Errorf("no se ha podido descomprimir la imagen: %v", err)
	}
	return plana, nil
}

func descargarImagen(t *Tarea, edicion string) (string, error) {
	// Se publica SIOS Puro (sin Google: sus apps no se pueden redistribuir). La edición con Google
	// solo si alguna versión la publicara.
	if edicion != "google" {
		edicion = "puro"
	}
	t.paso("Buscando la última versión de SIOS…")
	l, err := imagenesPublicadas()
	if err != nil {
		return "", err
	}
	im, ok := l.Imagenes[edicion]
	if !ok || !archivoImagenRe.MatchString(im.Archivo) || im.Tamano <= 0 || len(im.SHA256) != 64 {
		return "", errors.New("la versión publicada no trae esa edición de SIOS")
	}
	destino := filepath.Join(carpetaApp(), im.Archivo)
	parte := destino + ".parte"

	// Se sigue donde se quedó si se cortó (el servidor de GitHub admite rangos).
	f, err := os.OpenFile(parte, os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return "", err
	}
	hecho, _ := f.Seek(0, io.SeekEnd)
	if hecho > im.Tamano {
		f.Truncate(0)
		hecho, _ = f.Seek(0, io.SeekStart)
	}
	if hecho < im.Tamano {
		pet, _ := http.NewRequest("GET", versionesSIOS+im.Archivo, nil)
		if hecho > 0 {
			pet.Header.Set("Range", fmt.Sprintf("bytes=%d-", hecho))
		}
		r, err := http.DefaultClient.Do(pet)
		if err != nil {
			f.Close()
			return "", errors.New("se ha cortado la descarga: vuelve a intentarlo y seguirá donde se quedó")
		}
		defer r.Body.Close()
		switch {
		case r.StatusCode == 200 && hecho > 0: // no admite seguir: desde el principio
			f.Truncate(0)
			hecho, _ = f.Seek(0, io.SeekStart)
		case r.StatusCode != 200 && r.StatusCode != 206:
			f.Close()
			return "", fmt.Errorf("no se ha podido descargar SIOS (HTTP %d)", r.StatusCode)
		}
		gb := float64(im.Tamano) / (1 << 30)
		t.paso(fmt.Sprintf("Descargando SIOS (%.1f GB)… %d %%", gb, hecho*100/im.Tamano))
		buf := make([]byte, 1<<20)
		ultimo := hecho * 100 / im.Tamano
		for {
			n, errL := r.Body.Read(buf)
			if n > 0 {
				if _, err := f.Write(buf[:n]); err != nil {
					f.Close()
					return "", err
				}
				hecho += int64(n)
				if p := hecho * 100 / im.Tamano; p != ultimo {
					ultimo = p
					t.cambiarUltimo(fmt.Sprintf("Descargando SIOS (%.1f GB)… %d %%", gb, p))
				}
			}
			if errL == io.EOF {
				break
			}
			if errL != nil {
				f.Close()
				return "", errors.New("se ha cortado la descarga: vuelve a intentarlo y seguirá donde se quedó")
			}
		}
	}
	f.Close()

	t.paso("Comprobando que la imagen es la de SIOS…")
	huella, err := huellaArchivo(parte)
	if err != nil {
		return "", err
	}
	if !strings.EqualFold(huella, im.SHA256) {
		os.Remove(parte)
		return "", errors.New("la imagen descargada no coincide con la publicada: se ha borrado. Vuelve a intentarlo")
	}
	if err := os.Rename(parte, destino); err != nil {
		return "", err
	}
	t.paso("SIOS " + l.Version + " descargado y comprobado.")
	return destino, nil
}

func huellaArchivo(ruta string) (string, error) {
	f, err := os.Open(ruta)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func descomprimir(origen, destino string) error {
	f, err := os.Open(origen)
	if err != nil {
		return err
	}
	defer f.Close()
	z, err := gzip.NewReader(f)
	if err != nil {
		return err
	}
	defer z.Close()
	tmp := destino + ".parte"
	s, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(s, z); err != nil {
		s.Close()
		os.Remove(tmp)
		return err
	}
	if err := s.Close(); err != nil {
		return err
	}
	return os.Rename(tmp, destino)
}
