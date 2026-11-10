package main

// La ventana de SIOS Desktop: una página local (solo en 127.0.0.1, con una cookie que pone
// el propio programa) para vincular el móvil, mandarle archivos y enlaces y cambiar los ajustes.
// Igual en Mac y en Windows.

import (
	"crypto/rand"
	_ "embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"
)

//go:embed recursos/ventana.html
var ventanaHTML []byte

//go:embed recursos/montserrat.ttf
var montserrat []byte

const puertoVentana = 47800

type Ventana struct {
	c         *Cliente
	token     string
	direccion string
	mu        sync.Mutex
	moviles   []Movil
	buscado   time.Time
}

func NuevaVentana(c *Cliente) *Ventana {
	b := make([]byte, 16)
	rand.Read(b)
	return &Ventana{c: c, token: hex.EncodeToString(b)}
}

func (w *Ventana) URL() string { return fmt.Sprintf("http://%s/?t=%s", w.direccion, w.token) }

func (w *Ventana) Abrir() { abrirComoApp(w.URL()) }

func (w *Ventana) Servir() error {
	l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", puertoVentana))
	if err != nil {
		l, err = net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return err
		}
	}
	w.direccion = l.Addr().String()
	return http.Serve(l, w)
}

func (w *Ventana) resultado(rw http.ResponseWriter, v any, err error) {
	if err != nil {
		responder(rw, 400, map[string]string{"error": err.Error()})
		return
	}
	if v == nil {
		v = map[string]bool{"ok": true}
	}
	responder(rw, 200, v)
}

// Páginas para iniciar sesión o conseguir una clave en cada IA.
var dominiosEntrada = []string{
	"claude.ai", "claude.com", "console.anthropic.com", "auth.openai.com", "platform.openai.com", "accounts.google.com",
	"aistudio.google.com", "kimi.com", "www.kimi.com", "platform.moonshot.ai", "platform.deepseek.com", "console.mistral.ai",
	"console.x.ai", "openrouter.ai", "code.kimi.com", "auth.kimi.com",
	// Guías oficiales para desbloquear el cargador de arranque, y la búsqueda para las demás marcas.
	"source.android.com", "community.oneplus.com", "nothing.tech", "support.motorola.com", "en.miui.com",
	"developer.sony.com", "www.fairphone.com", "www.samsung.com", "www.google.com",
}

func enlaceDeEntrada(u string) bool {
	p, err := url.Parse(u)
	if err != nil || p.Scheme != "https" {
		return false
	}
	for _, d := range dominiosEntrada {
		if p.Host == d || strings.HasSuffix(p.Host, "."+d) {
			return true
		}
	}
	return false
}

func responder(rw http.ResponseWriter, codigo int, v any) {
	rw.Header().Set("content-type", "application/json")
	rw.WriteHeader(codigo)
	json.NewEncoder(rw).Encode(v)
}

func (w *Ventana) ServeHTTP(rw http.ResponseWriter, r *http.Request) {
	// Solo esta página: otras webs no pueden usarla (host exacto, cookie propia, mismo origen).
	if r.Host != w.direccion {
		http.Error(rw, "", http.StatusForbidden)
		return
	}
	if r.URL.Path == "/" && r.URL.Query().Get("t") == w.token {
		http.SetCookie(rw, &http.Cookie{Name: "sios", Value: w.token, Path: "/", HttpOnly: true, SameSite: http.SameSiteStrictMode})
		http.Redirect(rw, r, "/", http.StatusFound)
		return
	}
	if k, err := r.Cookie("sios"); err != nil || k.Value != w.token {
		rw.Header().Set("content-type", "text/html; charset=utf-8")
		rw.WriteHeader(http.StatusForbidden)
		rw.Write([]byte(`<meta charset="utf-8"><body style="font-family:system-ui;background:#262624;color:#C2C0B6;display:grid;place-items:center;height:90vh">Ábrela desde el icono de SIOS.</body>`))
		return
	}
	if r.Method == "POST" && r.Header.Get("origin") != "http://"+w.direccion {
		http.Error(rw, "", http.StatusForbidden)
		return
	}
	switch r.URL.Path {
	case "/":
		rw.Header().Set("content-type", "text/html; charset=utf-8")
		rw.Header().Set("cache-control", "no-store")
		rw.Header().Set("content-security-policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data: https://tile.openstreetmap.org")
		rw.Write(ventanaHTML)
	case "/montserrat.ttf":
		rw.Header().Set("content-type", "font/ttf")
		rw.Header().Set("cache-control", "max-age=86400")
		rw.Write(montserrat)
	case "/api/estado":
		e, aj := w.c.Estado()
		responder(rw, 200, map[string]any{"estado": e, "ajustes": map[string]any{"portapapeles": aj.Portapapeles, "avisos": aj.Avisos, "al_iniciar": aj.AlIniciar}, "equipo": nombreEquipo()})
	case "/api/buscar":
		w.mu.Lock()
		if time.Since(w.buscado) > 3*time.Second {
			w.buscado = time.Now()
			w.mu.Unlock()
			lista := Descubrir(2500 * time.Millisecond)
			w.mu.Lock()
			w.moviles = lista
		}
		lista := w.moviles
		w.mu.Unlock()
		if lista == nil {
			lista = []Movil{}
		}
		responder(rw, 200, lista)
	case "/api/vincular":
		var d struct{ Host, Codigo string }
		json.NewDecoder(r.Body).Decode(&d)
		if err := w.c.Vincular(d.Host, d.Codigo); err != nil {
			responder(rw, 400, map[string]string{"error": err.Error()})
			return
		}
		responder(rw, 200, map[string]bool{"ok": true})
	case "/api/desvincular":
		w.c.Desvincular()
		responder(rw, 200, map[string]bool{"ok": true})
	case "/api/ajustes":
		var d struct {
			Portapapeles, Avisos *bool
			AlIniciar            *bool `json:"al_iniciar"`
		}
		json.NewDecoder(r.Body).Decode(&d)
		w.c.PonerAjustes(d.Portapapeles, d.Avisos, d.AlIniciar)
		responder(rw, 200, map[string]bool{"ok": true})
	case "/api/enlace":
		var d struct{ URL string }
		json.NewDecoder(r.Body).Decode(&d)
		if err := w.c.AbrirEnMovil(d.URL); err != nil {
			responder(rw, 400, map[string]string{"error": err.Error()})
			return
		}
		responder(rw, 200, map[string]bool{"ok": true})
	case "/api/sonar":
		w.resultado(rw, nil, w.c.Sonar())
	case "/api/contactos":
		var d struct{ Texto string }
		json.NewDecoder(r.Body).Decode(&d)
		lista, err := w.c.Contactos(d.Texto)
		w.resultado(rw, map[string]any{"contactos": lista}, err)
	case "/api/sms":
		var d struct{ Numero, Nombre, Texto string }
		json.NewDecoder(r.Body).Decode(&d)
		w.resultado(rw, nil, w.c.SMS(d.Numero, d.Nombre, d.Texto))
	case "/api/llamar":
		var d struct{ Numero, Nombre string }
		json.NewDecoder(r.Body).Decode(&d)
		w.resultado(rw, nil, w.c.Llamar(d.Numero, d.Nombre))
	case "/api/ia":
		// La IA del móvil (elegir, entrar, clave, modelo local): se pasa tal cual por el canal cifrado.
		var d struct {
			Ruta  string
			Datos map[string]any
		}
		json.NewDecoder(r.Body).Decode(&d)
		if !strings.HasPrefix(d.Ruta, "/ia") || strings.Contains(d.Ruta, "..") {
			responder(rw, 400, map[string]string{"error": "ruta no válida"})
			return
		}
		res, err := w.c.pedirMovil(d.Ruta, d.Datos, 90*time.Second)
		w.resultado(rw, res, err)
	// ---------- Encontrar mi móvil ----------
	case "/api/encontrar":
		r, err := w.c.Encontrar()
		w.resultado(rw, r, err)
	case "/api/encontrar/accion":
		var d struct{ Accion, Mensaje, Telefono, Confirmacion string }
		json.NewDecoder(r.Body).Decode(&d)
		pendiente, err := w.c.AccionEncontrar(d.Accion, d.Mensaje, d.Telefono, d.Confirmacion)
		w.resultado(rw, map[string]bool{"ok": err == nil, "pendiente": pendiente}, err)
	case "/api/abrir":
		// Entrar con la cuenta en el navegador del ordenador: solo páginas de inicio de sesión conocidas.
		var d struct{ URL string }
		json.NewDecoder(r.Body).Decode(&d)
		if !enlaceDeEntrada(d.URL) {
			responder(rw, 400, map[string]string{"error": "enlace no permitido"})
			return
		}
		abrir(d.URL)
		responder(rw, 200, map[string]bool{"ok": true})
	// ---------- Por cable: instalar SIOS y pasar los datos ----------
	case "/api/cable":
		lista, err := dispositivos()
		if lista == nil {
			lista = []Dispositivo{}
		}
		img := imagenSIOS()
		res := map[string]any{"herramientas": herramienta("adb") != "", "dispositivos": lista, "imagen": filepath.Base(img), "tarea": estadoTarea(), "apps": appsAnteriores()}
		if img == "" {
			res["imagen"] = ""
		}
		if err != nil && herramienta("adb") != "" {
			res["error"] = err.Error()
		}
		responder(rw, 200, res)
	case "/api/cable/herramientas":
		w.resultado(rw, nil, descargarHerramientas())
	case "/api/cable/probar":
		var d struct{ Serie string }
		json.NewDecoder(r.Body).Decode(&d)
		w.resultado(rw, nil, probarSIOS(d.Serie))
	case "/api/cable/instalar":
		var d struct {
			Serie        string
			Borrar       bool
			Confirmacion string
		}
		json.NewDecoder(r.Body).Decode(&d)
		// Grabar el sistema no tiene vuelta atrás sencilla: se pide escribirlo.
		if d.Confirmacion != "INSTALAR" {
			responder(rw, 400, map[string]string{"error": "escribe INSTALAR para confirmar"})
			return
		}
		w.resultado(rw, nil, instalarSIOS(d.Serie, d.Borrar))
	case "/api/cable/desbloquear":
		var d struct{ Serie, Confirmacion string }
		json.NewDecoder(r.Body).Decode(&d)
		if d.Confirmacion != "DESBLOQUEAR" {
			responder(rw, 400, map[string]string{"error": "escribe DESBLOQUEAR para confirmar"})
			return
		}
		w.resultado(rw, nil, desbloquear(d.Serie))
	case "/api/cable/emulador":
		var d struct{ Serie, Confirmacion string }
		json.NewDecoder(r.Body).Decode(&d)
		if d.Confirmacion != "INSTALAR" {
			responder(rw, 400, map[string]string{"error": "escribe INSTALAR para confirmar"})
			return
		}
		w.resultado(rw, nil, instalarEnEmulador(d.Serie))
	case "/api/cable/migrar":
		var d struct {
			Origen, Destino string
			Que             []string
		}
		json.NewDecoder(r.Body).Decode(&d)
		w.resultado(rw, nil, migrar(d.Origen, d.Destino, d.Que))
	case "/api/cable/app":
		var d struct{ Serie, Paquete string }
		json.NewDecoder(r.Body).Decode(&d)
		if !regexp.MustCompile(`^[A-Za-z0-9_.]+$`).MatchString(d.Paquete) {
			responder(rw, 400, map[string]string{"error": "paquete no válido"})
			return
		}
		_, err := adb(d.Serie, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "market://details?id="+d.Paquete)
		w.resultado(rw, nil, err)
	case "/api/cable/imagen":
		// La imagen de SIOS arrastrada a la ventana: se guarda en la carpeta de la app.
		nombre := filepath.Base(r.Header.Get("x-nombre"))
		if !regexp.MustCompile(`(?i)^sios[\w.-]*\.img(\.gz)?$`).MatchString(nombre) {
			responder(rw, 400, map[string]string{"error": "el archivo debe ser una imagen de SIOS (sios…img o .img.gz)"})
			return
		}
		f, err := os.Create(filepath.Join(carpetaApp(), nombre))
		if err == nil {
			_, err = io.Copy(f, r.Body)
			f.Close()
		}
		w.resultado(rw, nil, err)
	case "/api/panel":
		p, err := w.c.Panel()
		w.resultado(rw, p, err)
	case "/api/mensajes":
		responder(rw, 200, map[string]any{"mensajes": w.c.Mensajes()})
	case "/api/responder":
		var d struct{ Clave, Texto string }
		json.NewDecoder(r.Body).Decode(&d)
		w.resultado(rw, nil, w.c.Responder(d.Clave, d.Texto))
	case "/api/copia":
		if r.Method == "POST" {
			var d struct {
				Activa *bool
				Abrir  bool
				Ahora  bool
			}
			json.NewDecoder(r.Body).Decode(&d)
			if d.Activa != nil {
				w.c.PonerCopia(*d.Activa)
			}
			if d.Abrir {
				os.MkdirAll(w.c.CarpetaCopia(), 0o755)
				abrir(w.c.CarpetaCopia())
			}
			if d.Ahora {
				go w.c.Copiar()
			}
		}
		_, aj := w.c.Estado()
		responder(rw, 200, map[string]any{"activa": aj.Copia, "carpeta": w.c.CarpetaCopia(), "estado": w.c.EstadoCopia()})
	case "/api/archivo":
		datos, err := io.ReadAll(io.LimitReader(r.Body, 25<<20+1))
		if err == nil {
			err = w.c.MandarArchivo(r.URL.Query().Get("nombre"), datos)
		}
		if err != nil {
			responder(rw, 400, map[string]string{"error": err.Error()})
			return
		}
		responder(rw, 200, map[string]bool{"ok": true})
	default:
		http.NotFound(rw, r)
	}
}
