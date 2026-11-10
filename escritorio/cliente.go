package main

// El cliente: ajustes, la conexión con el móvil (eventos en espera larga), el portapapeles
// compartido y lo que llega del móvil (avisos, enlaces, archivos).

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"golang.design/x/clipboard"
)

type Ajustes struct {
	Vinculo      *Vinculo `json:"vinculo,omitempty"`
	Portapapeles bool     `json:"portapapeles"`
	Avisos       bool     `json:"avisos"`
	AlIniciar    bool     `json:"al_iniciar"`
	Copia        bool     `json:"copia"`         // copiar fotos y documentos del móvil a este ordenador
	CarpetaCopia string   `json:"carpeta_copia"` // dónde (por defecto, Imágenes/SIOS)
	UltimaCopia  float64  `json:"ultima_copia"`  // fecha (ms) del archivo más nuevo ya copiado
}

type Estado struct {
	Vinculado   bool   `json:"vinculado"`
	Conectado   bool   `json:"conectado"`
	NombreMovil string `json:"nombre_movil"`
	Host        string `json:"host"`
	Bateria     int    `json:"bateria"`
	Cargando    bool   `json:"cargando"`
	Error       string `json:"error"`
}

type Cliente struct {
	mu              sync.Mutex
	ajustes         Ajustes
	estado          Estado
	seq             float64
	ultimo          string // lo último que puso el móvil en el portapapeles: no se le devuelve
	cancelar        context.CancelFunc
	cambios         func() // la bandeja se repinta
	hayPortapapeles bool
	mensajes        []Mensaje // los últimos mensajes del móvil, para contestarlos desde aquí
	copia           EstadoCopia
}

type Mensaje struct {
	Clave     string `json:"clave"`
	App       string `json:"app"`
	De        string `json:"de"`
	Texto     string `json:"texto"`
	Cuando    int64  `json:"cuando"`
	Responder bool   `json:"responder"`
}

type EstadoCopia struct {
	Copiando bool   `json:"copiando"`
	Hechos   int    `json:"hechos"` // en la copia de ahora
	Total    int    `json:"total"`
	Ultima   int64  `json:"ultima"` // cuándo terminó la última
	Nuevos   int    `json:"nuevos"` // cuántos trajo la última
	Error    string `json:"error"`
}

func rutaAjustes() string {
	if r := os.Getenv("SIOS_AJUSTES"); r != "" { // pruebas
		return r
	}
	dir, _ := os.UserConfigDir()
	return filepath.Join(dir, "SIOS", "escritorio.json")
}

func NuevoCliente() *Cliente {
	c := &Cliente{ajustes: Ajustes{Portapapeles: true, Avisos: true}}
	if b, err := os.ReadFile(rutaAjustes()); err == nil {
		json.Unmarshal(b, &c.ajustes)
	}
	c.hayPortapapeles = clipboard.Init() == nil
	return c
}

func (c *Cliente) guardar() {
	os.MkdirAll(filepath.Dir(rutaAjustes()), 0o700)
	b, _ := json.MarshalIndent(c.ajustes, "", "  ")
	os.WriteFile(rutaAjustes(), b, 0o600) // la clave va aquí: solo la puede leer el usuario
}

func (c *Cliente) Estado() (Estado, Ajustes) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e := c.estado
	e.Vinculado = c.ajustes.Vinculo != nil
	if c.ajustes.Vinculo != nil {
		e.NombreMovil = c.ajustes.Vinculo.NombreMovil
		e.Host = c.ajustes.Vinculo.Host
	}
	return e, c.ajustes
}

func (c *Cliente) avisarCambio() {
	if c.cambios != nil {
		c.cambios()
	}
}

func (c *Cliente) ponerEstado(f func(e *Estado)) {
	c.mu.Lock()
	f(&c.estado)
	c.mu.Unlock()
	c.avisarCambio()
}

func (c *Cliente) vinculo() *Vinculo {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.ajustes.Vinculo
}

// ---------- Vincular y desvincular ----------

func (c *Cliente) Vincular(host, codigo string) error {
	host = strings.TrimSpace(host)
	if !strings.Contains(host, ":") {
		host += ":8766"
	}
	codigo = regexp.MustCompile(`\D`).ReplaceAllString(codigo, "")
	if len(codigo) != 6 {
		return errors.New("el código tiene 6 cifras")
	}
	v, err := Vincular(host, codigo, nombreEquipo())
	if err != nil {
		return err
	}
	c.mu.Lock()
	c.ajustes.Vinculo = v
	c.estado = Estado{}
	c.mu.Unlock()
	c.guardar()
	c.Conectar()
	return nil
}

func (c *Cliente) Desvincular() {
	if v := c.vinculo(); v != nil {
		v.Pedir("/desvincular", nil, 5*time.Second)
	}
	c.olvidar()
}

func (c *Cliente) olvidar() {
	c.mu.Lock()
	if c.cancelar != nil {
		c.cancelar()
	}
	c.ajustes.Vinculo = nil
	c.estado = Estado{}
	c.mu.Unlock()
	c.guardar()
	c.avisarCambio()
}

func (c *Cliente) PonerAjustes(portapapeles, avisos, alIniciar *bool) {
	c.mu.Lock()
	if portapapeles != nil {
		c.ajustes.Portapapeles = *portapapeles
	}
	if avisos != nil {
		c.ajustes.Avisos = *avisos
	}
	if alIniciar != nil {
		c.ajustes.AlIniciar = *alIniciar
	}
	c.mu.Unlock()
	if alIniciar != nil {
		ponerAlIniciar(*alIniciar)
	}
	c.guardar()
	c.avisarCambio()
}

// ---------- Conexión ----------

// Conectar (o reconectar): estado y, después, eventos sin fin hasta que se cancele.
func (c *Cliente) Conectar() {
	c.mu.Lock()
	if c.cancelar != nil {
		c.cancelar()
	}
	ctx, cancelar := context.WithCancel(context.Background())
	c.cancelar = cancelar
	c.mu.Unlock()
	go c.bucle(ctx)
}

func (c *Cliente) bucle(ctx context.Context) {
	espera := 2 * time.Second
	primera := true
	for ctx.Err() == nil {
		v := c.vinculo()
		if v == nil {
			return
		}
		if primera {
			r, err := v.Pedir("/estado", nil, 8*time.Second)
			if err != nil {
				c.fallo(ctx, v, err)
				dormir(ctx, espera)
				espera = min(espera*2, 30*time.Second)
				continue
			}
			c.mu.Lock()
			c.seq = num(r["seq"]) // lo de antes de conectar no se repite
			c.mu.Unlock()
			c.ponerEstado(func(e *Estado) {
				e.Conectado, e.Error = true, ""
				e.Bateria, e.Cargando = int(num(r["bateria"])), r["cargando"] == true
			})
			primera = false
			espera = 2 * time.Second
		}
		c.mu.Lock()
		desde := c.seq
		c.mu.Unlock()
		r, err := v.Pedir("/eventos", map[string]any{"desde": desde}, 40*time.Second)
		if err != nil {
			c.fallo(ctx, v, err)
			primera = true
			dormir(ctx, espera)
			espera = min(espera*2, 30*time.Second)
			continue
		}
		c.mu.Lock()
		c.seq = num(r["seq"])
		c.mu.Unlock()
		if lista, ok := r["eventos"].([]any); ok {
			for _, x := range lista {
				if e, ok := x.(map[string]any); ok {
					c.atender(v, e)
				}
			}
		}
	}
}

func (c *Cliente) fallo(ctx context.Context, v *Vinculo, err error) {
	if ctx.Err() != nil {
		return
	}
	if errors.Is(err, ErrNoVinculado) {
		notificar("SIOS", "Este ordenador ya no está vinculado con el móvil.")
		c.olvidar()
		return
	}
	// Sin respuesta: quizá el móvil cambió de IP. Se busca en la red por su nombre.
	if nuevo := buscarPorNombre(v.NombreMovil); nuevo != "" && nuevo != v.Host && responde(v, nuevo) {
		c.mu.Lock()
		if c.ajustes.Vinculo != nil {
			c.ajustes.Vinculo.Host = nuevo
		}
		c.mu.Unlock()
		c.guardar()
	}
	c.ponerEstado(func(e *Estado) { e.Conectado, e.Error = false, "No se encuentra el móvil en la red" })
}

// Solo se cambia de dirección si en la nueva contesta el mismo móvil (con la misma clave).
func responde(v *Vinculo, host string) bool {
	prueba := *v
	prueba.Host = host
	_, err := prueba.Pedir("/estado", nil, 4*time.Second)
	return err == nil
}

func dormir(ctx context.Context, d time.Duration) {
	select {
	case <-ctx.Done():
	case <-time.After(d):
	}
}

func num(v any) float64 {
	f, _ := v.(float64)
	return f
}

func texto(v any) string {
	s, _ := v.(string)
	return s
}

// ---------- Lo que llega del móvil ----------

var urlWeb = regexp.MustCompile(`^https?://\S+$`)

func (c *Cliente) atender(v *Vinculo, e map[string]any) {
	_, aj := c.Estado()
	switch texto(e["tipo"]) {
	case "portapapeles":
		t := texto(e["texto"])
		if t == "" || !c.hayPortapapeles {
			return
		}
		c.mu.Lock()
		c.ultimo = t
		c.mu.Unlock()
		clipboard.Write(context.Background(), clipboard.FmtText, []byte(t))
		if e["avisar"] == true {
			notificar("Copiado desde el móvil", recortar(t, 120))
		}
	case "aviso":
		if e["mensaje"] == true && texto(e["clave"]) != "" {
			m := Mensaje{Clave: texto(e["clave"]), App: texto(e["app"]), De: texto(e["titulo"]), Texto: texto(e["texto"]), Cuando: int64(num(e["cuando"])), Responder: e["responder"] == true}
			c.mu.Lock()
			// Una fila por conversación (la última), las más recientes primero; hasta 30.
			lista := []Mensaje{m}
			for _, x := range c.mensajes {
				if x.De != m.De || x.App != m.App {
					lista = append(lista, x)
				}
			}
			if len(lista) > 30 {
				lista = lista[:30]
			}
			c.mensajes = lista
			c.mu.Unlock()
		}
		if !aj.Avisos {
			return
		}
		titulo := texto(e["titulo"])
		if app := texto(e["app"]); app != "" && app != titulo {
			titulo = fmt.Sprintf("%s · %s", titulo, app)
		}
		notificar(titulo, recortar(texto(e["texto"]), 240))
	case "enlace":
		if u := texto(e["url"]); urlWeb.MatchString(u) {
			abrir(u)
		}
	case "archivo":
		go c.bajar(v, texto(e["id"]))
	}
}

func (c *Cliente) bajar(v *Vinculo, id string) {
	r, err := v.Pedir("/archivo/bajar", map[string]any{"id": id}, 5*time.Minute)
	if err != nil {
		notificar("No ha llegado el archivo", err.Error())
		return
	}
	datos, err := b64.DecodeString(texto(r["datos"]))
	if err != nil {
		return
	}
	nombre := filepath.Base(texto(r["nombre"]))
	ruta := rutaLibre(filepath.Join(carpetaDescargas(), nombre))
	if err := os.WriteFile(ruta, datos, 0o644); err != nil {
		notificar("No se ha podido guardar", err.Error())
		return
	}
	notificar("Del móvil: "+filepath.Base(ruta), "Guardado en Descargas")
	mostrarEnCarpeta(ruta)
}

func rutaLibre(ruta string) string {
	if _, err := os.Stat(ruta); err != nil {
		return ruta
	}
	ext := filepath.Ext(ruta)
	base := strings.TrimSuffix(ruta, ext)
	for i := 2; ; i++ {
		r := fmt.Sprintf("%s (%d)%s", base, i, ext)
		if _, err := os.Stat(r); err != nil {
			return r
		}
	}
}

func carpetaDescargas() string {
	casa, _ := os.UserHomeDir()
	return filepath.Join(casa, "Downloads")
}

func recortar(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n-1]) + "…"
}

// ---------- Lo que va al móvil ----------

// Vigilar el portapapeles del ordenador: lo copiado va al móvil (si está activado).
func (c *Cliente) VigilarPortapapeles() {
	if !c.hayPortapapeles {
		return
	}
	cambios := clipboard.Watch(context.Background(), clipboard.FmtText)
	for d := range cambios {
		// Lo que marcan como sensible (contraseñas de un gestor) no sale del ordenador.
		if d.Sensitive {
			continue
		}
		t := string(d.Bytes)
		c.mu.Lock()
		repetido := t == c.ultimo
		c.mu.Unlock()
		_, aj := c.Estado()
		if repetido || t == "" || !aj.Portapapeles {
			continue
		}
		if v := c.vinculo(); v != nil {
			go v.Pedir("/portapapeles", map[string]any{"texto": recortar(t, 100_000)}, 10*time.Second)
		}
	}
}

func (c *Cliente) MandarCopiado() error {
	v := c.vinculo()
	if v == nil || !c.hayPortapapeles {
		return errors.New("no hay ningún móvil vinculado")
	}
	b, _ := clipboard.Read(context.Background(), clipboard.FmtText)
	t := string(b)
	if t == "" {
		return errors.New("no hay texto copiado")
	}
	_, err := v.Pedir("/portapapeles", map[string]any{"texto": t}, 10*time.Second)
	return err
}

func (c *Cliente) AbrirEnMovil(url string) error {
	v := c.vinculo()
	if v == nil {
		return errors.New("no hay ningún móvil vinculado")
	}
	if url == "" && c.hayPortapapeles {
		b, _ := clipboard.Read(context.Background(), clipboard.FmtText)
		url = strings.TrimSpace(string(b))
	}
	if !urlWeb.MatchString(url) {
		return errors.New("lo copiado no es un enlace")
	}
	_, err := v.Pedir("/enlace", map[string]any{"url": url}, 10*time.Second)
	return err
}

func (c *Cliente) MandarArchivo(nombre string, datos []byte) error {
	v := c.vinculo()
	if v == nil {
		return errors.New("no hay ningún móvil vinculado")
	}
	if len(datos) > 25<<20 {
		return errors.New("pasa de 25 MB")
	}
	_, err := v.Pedir("/archivo/subir", map[string]any{"nombre": nombre, "datos": b64.EncodeToString(datos)}, 5*time.Minute)
	return err
}

// ---------- Más cosas del móvil desde el ordenador ----------

func (c *Cliente) pedirMovil(ruta string, datos map[string]any, espera time.Duration) (map[string]any, error) {
	v := c.vinculo()
	if v == nil {
		return nil, errors.New("no hay ningún móvil vinculado")
	}
	return v.Pedir(ruta, datos, espera)
}

// ---------- Encontrar mi móvil ----------

// Encontrar: dónde está el móvil. Primero en vivo (por la red local o el servidor intermedio); si no
// contesta (apagado, sin cobertura), lo último que dejó en el servidor intermedio.
func (c *Cliente) Encontrar() (map[string]any, error) {
	v := c.vinculo()
	if v == nil {
		return nil, errors.New("no hay ningún móvil vinculado")
	}
	res := map[string]any{"movil": v.NombreMovil}
	// A la vez: lo último que dejó publicado (llega enseguida) y preguntarle ahora (si está conectado).
	type ultima struct {
		u   map[string]any
		err error
	}
	canal := make(chan ultima, 1)
	go func() { u, err := v.Ultima(); canal <- ultima{u, err} }()
	if vivo, err := v.Pedir("/ubicacion", nil, 15*time.Second); err == nil {
		res["estado"], res["vivo"], res["conectado"] = vivo, true, true
		res["cuando"] = time.Now().UnixMilli()
		return res, nil
	}
	ul := <-canal
	u, err := ul.u, ul.err
	if err != nil {
		return nil, err
	}
	for k, x := range u {
		res[k] = x
	}
	res["vivo"] = false
	return res, nil
}

// AccionEncontrar: sonar, modo perdido (o quitarlo), bloquear o borrar. Si el móvil no está
// conectado, queda guardada en el servidor intermedio y se hace en cuanto vuelva (salvo sonar,
// que solo tiene sentido al momento). Devuelve si ha quedado pendiente.
func (c *Cliente) AccionEncontrar(accion, mensaje, telefono, confirmacion string) (bool, error) {
	v := c.vinculo()
	if v == nil {
		return false, errors.New("no hay ningún móvil vinculado")
	}
	datos := map[string]any{}
	ruta := ""
	switch accion {
	case "sonar":
		ruta = "/sonar"
	case "perdido", "perdido_fin":
		ruta = "/perdido"
		datos["si"], datos["mensaje"], datos["telefono"] = accion == "perdido", mensaje, telefono
	case "bloquear":
		ruta = "/bloquear"
		datos["mensaje"], datos["telefono"] = mensaje, telefono
	case "borrar":
		// Sin vuelta atrás: se pide escribir BORRAR (y el móvil lo vuelve a comprobar).
		if confirmacion != "BORRAR" {
			return false, errors.New("escribe BORRAR para confirmar")
		}
		ruta = "/borrar"
		datos["confirmacion"] = confirmacion
	default:
		return false, errors.New("acción desconocida")
	}
	_, err := v.Pedir(ruta, datos, 20*time.Second)
	if err == nil {
		return false, nil
	}
	if accion == "sonar" {
		return false, err
	}
	// No contesta: queda guardada para cuando se conecte.
	if errors.Is(err, ErrDesconectado) || errors.Is(err, ErrSinRed) || strings.Contains(err.Error(), "no ha contestado") || strings.Contains(err.Error(), "no se llega") {
		datosOrden := map[string]any{}
		for k, x := range datos {
			datosOrden[k] = x
		}
		return true, v.Orden(ruta, datosOrden)
	}
	return false, err
}

// Hacer sonar el móvil (aunque esté en silencio) para encontrarlo.
func (c *Cliente) Sonar() error {
	_, err := c.pedirMovil("/sonar", nil, 10*time.Second)
	return err
}

func (c *Cliente) Contactos(texto string) (any, error) {
	r, err := c.pedirMovil("/contactos", map[string]any{"texto": texto}, 10*time.Second)
	if err != nil {
		return nil, err
	}
	return r["contactos"], nil
}

func (c *Cliente) SMS(numero, nombre, texto string) error {
	_, err := c.pedirMovil("/sms", map[string]any{"numero": numero, "nombre": nombre, "texto": texto}, 30*time.Second)
	return err
}

func (c *Cliente) Llamar(numero, nombre string) error {
	_, err := c.pedirMovil("/llamar", map[string]any{"numero": numero, "nombre": nombre}, 10*time.Second)
	return err
}

func (c *Cliente) Panel() (map[string]any, error) {
	return c.pedirMovil("/panel", nil, 15*time.Second)
}

// ---------- Contestar desde el ordenador ----------

func (c *Cliente) Mensajes() []Mensaje {
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]Mensaje{}, c.mensajes...)
}

func (c *Cliente) Responder(clave, texto string) error {
	_, err := c.pedirMovil("/responder", map[string]any{"clave": clave, "texto": texto}, 20*time.Second)
	if err == nil {
		c.mu.Lock()
		for i := range c.mensajes {
			if c.mensajes[i].Clave == clave {
				c.mensajes[i].Responder = false // ya contestado
			}
		}
		c.mu.Unlock()
	}
	return err
}

// ---------- Copias al ordenador ----------

func carpetaCopiaPorDefecto() string {
	casa, _ := os.UserHomeDir()
	return filepath.Join(casa, "Pictures", "SIOS")
}

func (c *Cliente) CarpetaCopia() string {
	_, aj := c.Estado()
	if aj.CarpetaCopia != "" {
		return aj.CarpetaCopia
	}
	return carpetaCopiaPorDefecto()
}

func (c *Cliente) EstadoCopia() EstadoCopia {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.copia
}

func (c *Cliente) PonerCopia(activa bool) {
	c.mu.Lock()
	c.ajustes.Copia = activa
	c.mu.Unlock()
	c.guardar()
	if activa {
		go c.Copiar()
	}
}

// Cada 10 minutos, con el móvil conectado: lo nuevo de la cámara, imágenes y documentos.
func (c *Cliente) VigilarCopias() {
	time.Sleep(30 * time.Second)
	for {
		c.Copiar()
		time.Sleep(10 * time.Minute)
	}
}

func (c *Cliente) Copiar() {
	e, aj := c.Estado()
	if !aj.Copia || !e.Conectado {
		return
	}
	c.mu.Lock()
	if c.copia.Copiando {
		c.mu.Unlock()
		return
	}
	c.copia = EstadoCopia{Copiando: true, Ultima: c.copia.Ultima}
	c.mu.Unlock()
	nuevos, err := c.copiar(aj)
	c.mu.Lock()
	c.copia.Copiando = false
	c.copia.Ultima = time.Now().UnixMilli()
	c.copia.Nuevos = nuevos
	if err != nil {
		c.copia.Error = err.Error()
	}
	c.mu.Unlock()
	c.avisarCambio()
	if nuevos > 0 {
		notificar("Copia del móvil", fmt.Sprintf("%d archivos nuevos en %s", nuevos, c.CarpetaCopia()))
	}
}

func (c *Cliente) copiar(aj Ajustes) (int, error) {
	r, err := c.pedirMovil("/copia/lista", map[string]any{"desde": aj.UltimaCopia}, 60*time.Second)
	if err != nil {
		return 0, err
	}
	lista, _ := r["archivos"].([]any)
	c.mu.Lock()
	c.copia.Total = len(lista)
	c.mu.Unlock()
	base := c.CarpetaCopia()
	nuevos := 0
	for _, x := range lista {
		a, _ := x.(map[string]any)
		ruta, tamano, cuando := texto(a["ruta"]), int64(num(a["tamano"])), num(a["cuando"])
		// /sdcard/DCIM/Camera/IMG.jpg → <carpeta>/DCIM/Camera/IMG.jpg
		rel := strings.TrimPrefix(ruta, "/sdcard/")
		destino := filepath.Join(base, filepath.FromSlash(rel))
		if fi, err := os.Stat(destino); err == nil && fi.Size() == tamano {
			c.avanzar(cuando)
			continue
		}
		if err := c.bajarArchivo(ruta, tamano, destino); err != nil {
			return nuevos, err
		}
		os.Chtimes(destino, time.UnixMilli(int64(cuando)), time.UnixMilli(int64(cuando)))
		nuevos++
		c.avanzar(cuando)
	}
	return nuevos, nil
}

func (c *Cliente) avanzar(cuando float64) {
	c.mu.Lock()
	c.copia.Hechos++
	if cuando > c.ajustes.UltimaCopia {
		c.ajustes.UltimaCopia = cuando
	}
	c.mu.Unlock()
	c.guardar()
	c.avisarCambio()
}

func (c *Cliente) bajarArchivo(ruta string, tamano int64, destino string) error {
	os.MkdirAll(filepath.Dir(destino), 0o755)
	parte := destino + ".parte"
	f, err := os.Create(parte)
	if err != nil {
		return err
	}
	const trozo = 4 << 20
	for n := int64(0); n*trozo < tamano; n++ {
		r, err := c.pedirMovil("/copia/trozo", map[string]any{"archivo": ruta, "trozo": n}, 2*time.Minute)
		if err != nil {
			f.Close()
			os.Remove(parte)
			return err
		}
		datos, err := b64.DecodeString(texto(r["datos"]))
		if err != nil || len(datos) == 0 {
			f.Close()
			os.Remove(parte)
			return errors.New("trozo vacío de " + filepath.Base(ruta))
		}
		f.Write(datos)
	}
	f.Close()
	return os.Rename(parte, destino)
}
