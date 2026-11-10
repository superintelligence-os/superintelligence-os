package main

// Protocolo con el móvil (servicio/continuidad.mjs): vincular con X25519 + código de 6 cifras y,
// después, cada petición y cada respuesta cifradas con AES-256-GCM, con la ruta y la hora dentro.

import (
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

var b64 = base64.StdEncoding

type Vinculo struct {
	Host        string `json:"host"` // ip:puerto del móvil
	ID          string `json:"id"`
	Clave       string `json:"clave"`
	NombreMovil string `json:"nombre_movil"`
}

func hmacDe(clave, datos []byte) []byte {
	m := hmac.New(sha256.New, clave)
	m.Write(datos)
	return m.Sum(nil)
}

// Vincular con el móvil de `host` usando el código que enseña en Ajustes.
func Vincular(host, codigo, nombre string) (*Vinculo, error) {
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	pub := priv.PublicKey().Bytes()
	cuerpo, _ := json.Marshal(map[string]string{
		"nombre": nombre, "publica": b64.EncodeToString(pub), "prueba": b64.EncodeToString(hmacDe([]byte(codigo), pub)),
	})
	cliente := &http.Client{Timeout: 15 * time.Second}
	r, err := cliente.Post("http://"+host+"/vincular", "application/json", bytes.NewReader(cuerpo))
	if err != nil {
		return nil, fmt.Errorf("no se encuentra el móvil en %s", host)
	}
	defer r.Body.Close()
	var resp struct {
		ID, Publica, Prueba, Movil, Error string
	}
	if err := json.NewDecoder(r.Body).Decode(&resp); err != nil {
		return nil, errors.New("el móvil ha respondido algo raro")
	}
	if resp.Error != "" {
		return nil, errors.New(resp.Error)
	}
	pubMovil, err := b64.DecodeString(resp.Publica)
	if err != nil || len(pubMovil) != 32 {
		return nil, errors.New("clave del móvil no válida")
	}
	par, err := ecdh.X25519().NewPublicKey(pubMovil)
	if err != nil {
		return nil, err
	}
	compartido, err := priv.ECDH(par)
	if err != nil {
		return nil, err
	}
	sal := append(append([]byte(codigo), pub...), pubMovil...)
	clave, err := hkdf.Key(sha256.New, compartido, sal, "sios continuidad", 32)
	if err != nil {
		return nil, err
	}
	// El móvil demuestra que tiene la misma clave.
	prueba, _ := b64.DecodeString(resp.Prueba)
	if !hmac.Equal(prueba, hmacDe(clave, pubMovil)) {
		return nil, errors.New("el móvil no ha podido demostrar quién es")
	}
	return &Vinculo{Host: host, ID: resp.ID, Clave: b64.EncodeToString(clave), NombreMovil: resp.Movil}, nil
}

type sobre struct {
	N string `json:"n"`
	C string `json:"c"`
}

func (v *Vinculo) aead() (cipher.AEAD, error) {
	k, err := b64.DecodeString(v.Clave)
	if err != nil {
		return nil, err
	}
	bloque, err := aes.NewCipher(k)
	if err != nil {
		return nil, err
	}
	return cipher.NewGCM(bloque)
}

// ErrNoVinculado: el móvil ya no reconoce este ordenador (lo quitaron en Ajustes).
var ErrNoVinculado = errors.New("el móvil ya no tiene vinculado este ordenador")

// Pedir una ruta cifrada al móvil: por la red local y, si el móvil no está en ella (otra Wi‑Fi,
// datos, perdido), por Nostr (o por un servidor propio si se ha puesto), con el mismo sobre cifrado.
func (v *Vinculo) Pedir(ruta string, datos map[string]any, espera time.Duration) (map[string]any, error) {
	s, err := v.sellar(ruta, datos)
	if err != nil {
		return nil, err
	}
	if !v.estaFuera() {
		resp, err := v.pedirLocal(ruta, s, espera)
		var red *errorDeRed
		if !errors.As(err, &red) {
			return resp, err
		}
		v.marcarFuera()
	}
	if RelayURL == "" {
		return v.pedirNostr(s, espera)
	}
	return v.pedirRelay(ruta, s, espera)
}

// sellar: la ruta y la hora van dentro de lo cifrado (no se puede reutilizar en otra ruta ni más tarde).
func (v *Vinculo) sellar(ruta string, datos map[string]any) (sobre, error) {
	g, err := v.aead()
	if err != nil {
		return sobre{}, err
	}
	if datos == nil {
		datos = map[string]any{}
	}
	datos["ruta"] = ruta
	datos["t"] = time.Now().UnixMilli()
	claro, _ := json.Marshal(datos)
	iv := make([]byte, 12)
	rand.Read(iv)
	return sobre{N: b64.EncodeToString(iv), C: b64.EncodeToString(g.Seal(nil, iv, claro, nil))}, nil
}

func (v *Vinculo) abrir(crudo []byte) (map[string]any, error) {
	g, err := v.aead()
	if err != nil {
		return nil, err
	}
	var s sobre
	if err := json.Unmarshal(crudo, &s); err != nil {
		return nil, err
	}
	n, _ := b64.DecodeString(s.N)
	c, _ := b64.DecodeString(s.C)
	plano, err := g.Open(nil, n, c, nil)
	if err != nil {
		return nil, errors.New("respuesta del móvil no válida")
	}
	var resp map[string]any
	return resp, json.Unmarshal(plano, &resp)
}

// errorDeRed: no se ha llegado al móvil (no es que haya contestado con un error).
type errorDeRed struct{ error }

// Con el móvil fuera de la red local, un minuto sin volver a intentarlo por ella (no esperar en cada petición).
var fueraHasta = map[string]time.Time{}
var fueraMu sync.Mutex

func (v *Vinculo) estaFuera() bool {
	fueraMu.Lock()
	defer fueraMu.Unlock()
	return time.Now().Before(fueraHasta[v.ID])
}

func (v *Vinculo) marcarFuera() {
	fueraMu.Lock()
	fueraHasta[v.ID] = time.Now().Add(time.Minute)
	fueraMu.Unlock()
}

func (v *Vinculo) pedirLocal(ruta string, s sobre, espera time.Duration) (map[string]any, error) {
	cuerpo, _ := json.Marshal(s)
	pet, _ := http.NewRequest("POST", "http://"+v.Host+ruta, bytes.NewReader(cuerpo))
	pet.Header.Set("content-type", "application/json")
	pet.Header.Set("x-claude-mac", v.ID)
	// Conectar se intenta poco rato: si el móvil no está en esta red, se pasa al servidor intermedio.
	cliente := &http.Client{Timeout: espera, Transport: &http.Transport{DialContext: (&net.Dialer{Timeout: 3 * time.Second}).DialContext}}
	r, err := cliente.Do(pet)
	if err != nil {
		return nil, &errorDeRed{err}
	}
	defer r.Body.Close()
	crudo, _ := io.ReadAll(r.Body)
	if r.StatusCode != 200 {
		var e struct{ Error string }
		json.Unmarshal(crudo, &e)
		if r.StatusCode == 401 && e.Error == "Este ordenador no está vinculado" {
			return nil, ErrNoVinculado
		}
		if e.Error == "" {
			e.Error = r.Status
		}
		return nil, errors.New(e.Error)
	}
	return v.abrir(crudo)
}

// ---------- Servidor propio opcional (relay/) ----------

// RelayURL: servidor intermedio propio (SIOS_RELAY). Vacío, lo normal: se usa Nostr (nostr.go).
var RelayURL = strings.TrimRight(os.Getenv("SIOS_RELAY"), "/")

// ErrDesconectado: el móvil no está conectado ahora mismo a internet (apagado, sin cobertura).
var ErrDesconectado = errors.New("el móvil no está conectado ahora mismo")

// buzon: dónde se encuentran ordenador y móvil en el servidor intermedio y la llave para escribir.
// Salen de la clave del emparejamiento (igual que en el móvil); el servidor no puede deducirla.
func (v *Vinculo) buzon() (string, string, error) {
	k, err := b64.DecodeString(v.Clave)
	if err != nil {
		return "", "", err
	}
	id, err := hkdf.Key(sha256.New, k, []byte("sios-relay"), "buzon", 16)
	if err != nil {
		return "", "", err
	}
	llave, err := hkdf.Key(sha256.New, k, []byte("sios-relay"), "llave", 32)
	if err != nil {
		return "", "", err
	}
	return hex.EncodeToString(id), hex.EncodeToString(llave), nil
}

func (v *Vinculo) relay(metodo, que string, cuerpo any, espera time.Duration) (int, []byte, error) {
	id, llave, err := v.buzon()
	if err != nil {
		return 0, nil, err
	}
	var lector io.Reader
	if cuerpo != nil {
		b, _ := json.Marshal(cuerpo)
		lector = bytes.NewReader(b)
	}
	pet, _ := http.NewRequest(metodo, RelayURL+"/b/"+id+"/"+que, lector)
	pet.Header.Set("content-type", "application/json")
	pet.Header.Set("x-llave", llave)
	r, err := (&http.Client{Timeout: espera + 5*time.Second}).Do(pet)
	if err != nil {
		return 0, nil, fmt.Errorf("no se llega al servidor de SIOS: %w", err)
	}
	defer r.Body.Close()
	crudo, _ := io.ReadAll(r.Body)
	return r.StatusCode, crudo, nil
}

func (v *Vinculo) pedirRelay(ruta string, s sobre, espera time.Duration) (map[string]any, error) {
	if espera > 30*time.Second {
		espera = 30 * time.Second // el servidor intermedio espera como mucho 30 s al móvil
	}
	estado, crudo, err := v.relay("POST", "peticion", map[string]any{"ruta": ruta, "sobre": s}, espera)
	if err != nil {
		return nil, err
	}
	if estado == 503 {
		return nil, ErrDesconectado
	}
	if estado != 200 {
		var e struct{ Error string }
		json.Unmarshal(crudo, &e)
		if estado == 401 && e.Error == "Este ordenador no está vinculado" {
			return nil, ErrNoVinculado
		}
		if e.Error == "" {
			e.Error = fmt.Sprintf("el servidor de SIOS respondió %d", estado)
		}
		return nil, errors.New(e.Error)
	}
	return v.abrir(crudo)
}

// Ultima: el último estado que dejó el móvil en el servidor intermedio (dónde estaba, batería, SIM),
// aunque ahora esté apagado; y si está conectado y cuándo se le vio por última vez.
func (v *Vinculo) Ultima() (map[string]any, error) {
	if RelayURL == "" {
		return v.ultimaNostr()
	}
	estado, crudo, err := v.relay("GET", "ultima", nil, 10*time.Second)
	if err != nil {
		return nil, err
	}
	if estado != 200 {
		return nil, fmt.Errorf("el servidor de SIOS respondió %d", estado)
	}
	var r struct {
		Ultima *struct {
			Sobre  json.RawMessage `json:"sobre"`
			Cuando int64           `json:"cuando"`
		} `json:"ultima"`
		Conectado bool   `json:"conectado"`
		Visto     *int64 `json:"visto"`
	}
	if err := json.Unmarshal(crudo, &r); err != nil {
		return nil, err
	}
	res := map[string]any{"conectado": r.Conectado, "visto": r.Visto}
	if r.Ultima != nil {
		if e, err := v.abrir(r.Ultima.Sobre); err == nil {
			res["estado"] = e
			res["cuando"] = r.Ultima.Cuando
		}
	}
	return res, nil
}

// Orden: algo para cuando el móvil vuelva a conectarse (modo perdido, bloquear, borrar). Lleva un
// identificador propio dentro del sobre para que el móvil la cumpla una sola vez.
func (v *Vinculo) Orden(ruta string, datos map[string]any) error {
	if datos == nil {
		datos = map[string]any{}
	}
	id := make([]byte, 12)
	rand.Read(id)
	datos["id_orden"] = hex.EncodeToString(id)
	if RelayURL == "" {
		datos["ruta"], datos["t"] = ruta, time.Now().UnixMilli()
		return v.ordenNostr(datos)
	}
	s, err := v.sellar(ruta, datos)
	if err != nil {
		return err
	}
	estado, _, err := v.relay("POST", "orden", s, 10*time.Second)
	if err != nil {
		return err
	}
	if estado != 200 {
		return fmt.Errorf("el servidor de SIOS respondió %d", estado)
	}
	return nil
}
