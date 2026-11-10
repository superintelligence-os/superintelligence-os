package main

// Fuera de casa por Nostr (igual que servicio/nostr.mjs en el móvil): una red abierta de servidores
// gratuitos que solo ven sobres cifrados con la clave del emparejamiento. Firma Schnorr (BIP-340)
// sobre secp256k1 escrita aquí, sin dependencias.

import (
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/websocket"
)

var (
	curvaP, _  = new(big.Int).SetString("fffffffffffffffffffffffffffffffffffffffffffffffffffffffefffffc2f", 16)
	curvaN, _  = new(big.Int).SetString("fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141", 16)
	curvaGx, _ = new(big.Int).SetString("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", 16)
	curvaGy, _ = new(big.Int).SetString("483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", 16)
)

type punto struct{ x, y *big.Int } // nil = infinito

func sumarPuntos(a, b *punto) *punto {
	if a == nil {
		return b
	}
	if b == nil {
		return a
	}
	p := curvaP
	var l *big.Int
	if a.x.Cmp(b.x) == 0 {
		if new(big.Int).Mod(new(big.Int).Add(a.y, b.y), p).Sign() == 0 {
			return nil
		}
		// Doblar: l = 3x² / 2y
		num := new(big.Int).Mul(big.NewInt(3), new(big.Int).Mul(a.x, a.x))
		den := new(big.Int).ModInverse(new(big.Int).Mul(big.NewInt(2), a.y), p)
		l = new(big.Int).Mod(num.Mul(num, den), p)
	} else {
		num := new(big.Int).Sub(b.y, a.y)
		den := new(big.Int).ModInverse(new(big.Int).Mod(new(big.Int).Sub(b.x, a.x), p), p)
		l = new(big.Int).Mod(num.Mul(num, den), p)
	}
	x := new(big.Int).Mod(new(big.Int).Sub(new(big.Int).Sub(new(big.Int).Mul(l, l), a.x), b.x), p)
	y := new(big.Int).Mod(new(big.Int).Sub(new(big.Int).Mul(l, new(big.Int).Sub(a.x, x)), a.y), p)
	return &punto{x, y}
}

func porG(k *big.Int) *punto {
	var r *punto
	q := &punto{curvaGx, curvaGy}
	for i := 0; i < k.BitLen(); i++ {
		if k.Bit(i) == 1 {
			r = sumarPuntos(r, q)
		}
		q = sumarPuntos(q, q)
	}
	return r
}

func bytes32(n *big.Int) []byte {
	b := make([]byte, 32)
	return n.FillBytes(b)
}

func hashEtiquetado(tag string, partes ...[]byte) []byte {
	t := sha256.Sum256([]byte(tag))
	h := sha256.New()
	h.Write(t[:])
	h.Write(t[:])
	for _, p := range partes {
		h.Write(p)
	}
	return h.Sum(nil)
}

// clavePublicaNostr: clave pública x-only (hex) de una privada de 32 bytes.
func clavePublicaNostr(sk []byte) string {
	return hex.EncodeToString(bytes32(porG(new(big.Int).SetBytes(sk)).x))
}

// firmarSchnorr: firma BIP-340 de un mensaje de 32 bytes (aux aleatorio salvo en las pruebas).
func firmarSchnorr(msg, sk, aux []byte) (string, error) {
	d0 := new(big.Int).SetBytes(sk)
	if d0.Sign() == 0 || d0.Cmp(curvaN) >= 0 {
		return "", errors.New("clave privada no válida")
	}
	if aux == nil {
		aux = make([]byte, 32)
		rand.Read(aux)
	}
	P := porG(d0)
	d := d0
	if P.y.Bit(0) == 1 {
		d = new(big.Int).Sub(curvaN, d0)
	}
	t := bytes32(d)
	ha := hashEtiquetado("BIP0340/aux", aux)
	for i := range t {
		t[i] ^= ha[i]
	}
	k0 := new(big.Int).Mod(new(big.Int).SetBytes(hashEtiquetado("BIP0340/nonce", t, bytes32(P.x), msg)), curvaN)
	if k0.Sign() == 0 {
		return "", errors.New("nonce nulo")
	}
	R := porG(k0)
	k := k0
	if R.y.Bit(0) == 1 {
		k = new(big.Int).Sub(curvaN, k0)
	}
	e := new(big.Int).Mod(new(big.Int).SetBytes(hashEtiquetado("BIP0340/challenge", bytes32(R.x), bytes32(P.x), msg)), curvaN)
	s := new(big.Int).Mod(new(big.Int).Add(k, new(big.Int).Mul(e, d)), curvaN)
	return hex.EncodeToString(append(bytes32(R.x), bytes32(s)...)), nil
}

type eventoNostr struct {
	ID        string     `json:"id"`
	Pubkey    string     `json:"pubkey"`
	CreatedAt int64      `json:"created_at"`
	Kind      int        `json:"kind"`
	Tags      [][]string `json:"tags"`
	Content   string     `json:"content"`
	Sig       string     `json:"sig"`
}

// jsonNostr: JSON como lo espera Nostr para el id (sin escapar <, > ni &, igual que JSON.stringify).
func jsonNostr(v any) []byte {
	var b strings.Builder
	enc := json.NewEncoder(&b)
	enc.SetEscapeHTML(false)
	enc.Encode(v)
	return []byte(strings.TrimSuffix(b.String(), "\n"))
}

func nuevoEvento(sk []byte, kind int, content string, tags [][]string) (eventoNostr, error) {
	if tags == nil {
		tags = [][]string{}
	}
	ev := eventoNostr{Pubkey: clavePublicaNostr(sk), CreatedAt: time.Now().Unix(), Kind: kind, Tags: tags, Content: content}
	id := sha256.Sum256(jsonNostr([]any{0, ev.Pubkey, ev.CreatedAt, ev.Kind, ev.Tags, ev.Content}))
	ev.ID = hex.EncodeToString(id[:])
	sig, err := firmarSchnorr(id[:], sk, nil)
	ev.Sig = sig
	return ev, err
}

const (
	nostrGuardado = 30078 // datos de aplicación que se guardan (el último sustituye al anterior)
	nostrEfimero  = 25078 // efímeros: se reparten a quien escucha y no se guardan
)

// RelaysNostr: los mismos servidores que el móvil (se pueden cambiar con SIOS_NOSTR, separados por comas).
var RelaysNostr = func() []string {
	if l := os.Getenv("SIOS_NOSTR"); l != "" {
		return strings.Split(l, ",")
	}
	return []string{"wss://relay.primal.net", "wss://offchain.pub", "wss://relay.snort.social", "wss://relay.nostr.net"}
}()

type identidadesNostr struct {
	movil, ordenador       []byte
	pubMovil, pubOrdenador string
	dUltima, dOrdenes      string
}

// identidades: las mismas que deriva el móvil de la clave del emparejamiento.
func (v *Vinculo) identidades() (*identidadesNostr, error) {
	k, err := b64.DecodeString(v.Clave)
	if err != nil {
		return nil, err
	}
	derivar := func(que string, n int) []byte {
		b, _ := hkdf.Key(sha256.New, k, []byte("sios-nostr"), que, n)
		return b
	}
	clave := func(que string) []byte {
		for i := 0; ; i++ {
			nombre := que
			if i > 0 {
				nombre = fmt.Sprintf("%s-%d", que, i)
			}
			sk := derivar(nombre, 32)
			d := new(big.Int).SetBytes(sk)
			if d.Sign() > 0 && d.Cmp(curvaN) < 0 {
				return sk
			}
		}
	}
	ids := &identidadesNostr{movil: clave("movil"), ordenador: clave("ordenador")}
	ids.pubMovil, ids.pubOrdenador = clavePublicaNostr(ids.movil), clavePublicaNostr(ids.ordenador)
	ids.dUltima, ids.dOrdenes = hex.EncodeToString(derivar("ultima", 16)), hex.EncodeToString(derivar("ordenes", 16))
	return ids, nil
}

// enRelays: se conecta a todos los servidores a la vez; en cada uno se suscribe a `filtro` (si hay),
// publica `ev` (si hay) y pasa lo que llega a `alEvento` hasta que devuelve true, todos acaban
// (EOSE con `hastaEOSE`) o se cumple `espera`. Devuelve en cuántos servidores se aceptó `ev`.
func enRelays(filtro map[string]any, ev *eventoNostr, hastaEOSE bool, espera time.Duration, alEvento func(eventoNostr) bool) (int, int) {
	var mu sync.Mutex
	aceptado, conectados := 0, 0
	listo := make(chan struct{})
	var cerrarListo sync.Once
	var grupo sync.WaitGroup
	for _, url := range RelaysNostr {
		grupo.Add(1)
		go func(url string) {
			defer grupo.Done()
			cfg, err := websocket.NewConfig(url, "https://sios.local")
			if err != nil {
				return
			}
			cfg.Dialer = nil
			ws, err := websocket.DialConfig(cfg)
			if err != nil {
				return
			}
			defer ws.Close()
			mu.Lock()
			conectados++
			mu.Unlock()
			limite := time.Now().Add(espera)
			ws.SetDeadline(limite)
			go func() { <-listo; ws.SetDeadline(time.Now()) }()
			if filtro != nil {
				websocket.Message.Send(ws, string(jsonNostr([]any{"REQ", "s", filtro})))
			}
			if ev != nil {
				websocket.Message.Send(ws, string(jsonNostr([]any{"EVENT", ev})))
			}
			okVisto, eoseVisto := ev == nil, filtro == nil || !hastaEOSE
			for {
				var texto string
				if err := websocket.Message.Receive(ws, &texto); err != nil {
					return
				}
				var m []json.RawMessage
				if json.Unmarshal([]byte(texto), &m) != nil || len(m) < 2 {
					continue
				}
				var tipo string
				json.Unmarshal(m[0], &tipo)
				switch tipo {
				case "OK":
					var id string
					var ok bool
					json.Unmarshal(m[1], &id)
					if len(m) > 2 {
						json.Unmarshal(m[2], &ok)
					}
					if ev != nil && id == ev.ID {
						okVisto = true
						if ok {
							mu.Lock()
							aceptado++
							mu.Unlock()
						}
					}
				case "EOSE":
					eoseVisto = true
				case "EVENT":
					if len(m) < 3 || alEvento == nil {
						continue
					}
					var e eventoNostr
					if json.Unmarshal(m[2], &e) == nil {
						mu.Lock()
						fin := alEvento(e)
						mu.Unlock()
						if fin {
							cerrarListo.Do(func() { close(listo) })
							return
						}
					}
				}
				if okVisto && eoseVisto && (alEvento == nil || hastaEOSE) {
					return
				}
			}
		}(url)
	}
	grupo.Wait()
	cerrarListo.Do(func() { close(listo) })
	return aceptado, conectados
}

// ErrSinRed: no se llega a ningún servidor Nostr (el ordenador no tiene internet).
var ErrSinRed = errors.New("no se llega a internet para buscar el móvil")

// pedirNostr: una petición al momento por Nostr; el móvil, si está conectado, contesta en segundos.
func (v *Vinculo) pedirNostr(s sobre, espera time.Duration) (map[string]any, error) {
	ids, err := v.identidades()
	if err != nil {
		return nil, err
	}
	if espera > 20*time.Second {
		espera = 20 * time.Second
	}
	rid := make([]byte, 12)
	rand.Read(rid)
	r := hex.EncodeToString(rid)
	ev, err := nuevoEvento(ids.ordenador, nostrEfimero, string(jsonNostr(map[string]any{"r": r, "s": s})), nil)
	if err != nil {
		return nil, err
	}
	var resp struct {
		R string          `json:"r"`
		E int             `json:"e"`
		S json.RawMessage `json:"s"`
	}
	llego := false
	filtro := map[string]any{"kinds": []int{nostrEfimero}, "authors": []string{ids.pubMovil}, "since": time.Now().Unix() - 5}
	_, conectados := enRelays(filtro, &ev, false, espera, func(e eventoNostr) bool {
		if llego || e.Pubkey != ids.pubMovil {
			return llego
		}
		var x struct {
			R string          `json:"r"`
			E int             `json:"e"`
			S json.RawMessage `json:"s"`
		}
		if json.Unmarshal([]byte(e.Content), &x) == nil && x.R == r {
			resp, llego = x, true
		}
		return llego
	})
	if !llego {
		if conectados == 0 {
			return nil, ErrSinRed
		}
		return nil, ErrDesconectado
	}
	if resp.E != 200 {
		var e struct{ Error string }
		json.Unmarshal(resp.S, &e)
		if resp.E == 401 && e.Error == "Este ordenador no está vinculado" {
			return nil, ErrNoVinculado
		}
		if e.Error == "" {
			e.Error = fmt.Sprintf("el móvil respondió %d", resp.E)
		}
		return nil, errors.New(e.Error)
	}
	return v.abrir(resp.S)
}

// guardadoNostr: el último evento guardado de `autor` con la etiqueta `d` (el más reciente de todos los servidores).
func guardadoNostr(autor, d string) (*eventoNostr, int) {
	var ultimo *eventoNostr
	filtro := map[string]any{"kinds": []int{nostrGuardado}, "authors": []string{autor}, "#d": []string{d}}
	_, conectados := enRelays(filtro, nil, true, 8*time.Second, func(e eventoNostr) bool {
		if e.Pubkey == autor && (ultimo == nil || e.CreatedAt > ultimo.CreatedAt) {
			c := e
			ultimo = &c
		}
		return false
	})
	return ultimo, conectados
}

// ultimaNostr: el último estado que publicó el móvil (aunque ahora esté apagado).
func (v *Vinculo) ultimaNostr() (map[string]any, error) {
	ids, err := v.identidades()
	if err != nil {
		return nil, err
	}
	ev, conectados := guardadoNostr(ids.pubMovil, ids.dUltima)
	if conectados == 0 {
		return nil, ErrSinRed
	}
	res := map[string]any{"conectado": false, "visto": nil}
	if ev != nil {
		if e, err := v.abrir([]byte(ev.Content)); err == nil {
			cuando := ev.CreatedAt * 1000
			if c, ok := e["cuando"].(float64); ok {
				cuando = int64(c)
			}
			res["estado"], res["cuando"], res["visto"] = e, cuando, ev.CreatedAt*1000
		}
	}
	return res, nil
}

// ordenNostr: añade una orden a la lista de pendientes (un evento guardado y cifrado). El móvil la
// recibe al momento si escucha, o al volver a conectarse; cada orden se cumple una sola vez.
func (v *Vinculo) ordenNostr(datos map[string]any) error {
	ids, err := v.identidades()
	if err != nil {
		return err
	}
	var ordenes []map[string]any
	if ev, _ := guardadoNostr(ids.pubOrdenador, ids.dOrdenes); ev != nil {
		if d, err := v.abrir([]byte(ev.Content)); err == nil {
			if l, ok := d["ordenes"].([]any); ok {
				for _, x := range l {
					if o, ok := x.(map[string]any); ok {
						ordenes = append(ordenes, o)
					}
				}
			}
		}
	}
	// Las que el móvil ya ha cumplido (lo dice en su último estado) o de hace más de una semana, fuera.
	hechas := map[string]bool{}
	if u, err := v.ultimaNostr(); err == nil {
		if e, ok := u["estado"].(map[string]any); ok {
			if l, ok := e["hechas"].([]any); ok {
				for _, h := range l {
					if s, ok := h.(string); ok {
						hechas[s] = true
					}
				}
			}
		}
	}
	vigentes := []map[string]any{}
	for _, o := range ordenes {
		id, _ := o["id_orden"].(string)
		t, _ := o["t"].(float64)
		if !hechas[id] && time.Since(time.UnixMilli(int64(t))) < 7*24*time.Hour {
			vigentes = append(vigentes, o)
		}
	}
	vigentes = append(vigentes, datos)
	if len(vigentes) > 20 {
		vigentes = vigentes[len(vigentes)-20:]
	}
	s, err := v.sellar("/ordenes", map[string]any{"ordenes": vigentes})
	if err != nil {
		return err
	}
	ev, err := nuevoEvento(ids.ordenador, nostrGuardado, string(jsonNostr(s)), [][]string{{"d", ids.dOrdenes}})
	if err != nil {
		return err
	}
	aceptado, conectados := enRelays(nil, &ev, false, 10*time.Second, nil)
	if conectados == 0 {
		return ErrSinRed
	}
	if aceptado == 0 {
		return errors.New("ningún servidor ha aceptado la orden")
	}
	return nil
}
