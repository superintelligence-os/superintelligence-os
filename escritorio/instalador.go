// Por cable: instalar SIOS en un móvil y pasar los datos del móvil anterior.
//
// Instalar tiene dos pasos y vuelta atrás:
//  1. Probar sin borrar nada: DSU (Dynamic System Updates, Android 10+). SIOS arranca como sistema
//     temporal; al reiniciar vuelves a tu Android de siempre, con todo como estaba.
//  2. Quedarse con SIOS: se graba en la partición de sistema con fastboot (pide el cargador de
//     arranque desbloqueado y, si se elige, borra los datos).
//
// Las herramientas (adb y fastboot, de Google) se usan si ya están en el ordenador; si no, se
// descargan de dl.google.com a la carpeta de la app cuando el usuario lo pide.
package main

import (
	"archive/zip"
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

func carpetaApp() string {
	dir, _ := os.UserConfigDir()
	d := filepath.Join(dir, "SIOS Desktop")
	os.MkdirAll(d, 0o755)
	return d
}

func exe(nombre string) string {
	if runtime.GOOS == "windows" {
		return nombre + ".exe"
	}
	return nombre
}

// herramienta: ruta de adb o fastboot (la de la app primero, luego la del sistema).
func herramienta(nombre string) string {
	propia := filepath.Join(carpetaApp(), "platform-tools", exe(nombre))
	if _, err := os.Stat(propia); err == nil {
		return propia
	}
	if r, err := exec.LookPath(nombre); err == nil {
		return r
	}
	// Abierta desde el Dock o Finder, la app no ve el PATH de la terminal: se buscan también las
	// del SDK de Android (Android Studio, Homebrew…) para no pedir descargar lo que ya está.
	casa, _ := os.UserHomeDir()
	for _, d := range []string{
		os.Getenv("ANDROID_SDK_ROOT"), os.Getenv("ANDROID_HOME"),
		filepath.Join(casa, "Library/Android/sdk"), filepath.Join(casa, "AppData/Local/Android/Sdk"),
		filepath.Join(casa, "Android/Sdk"), "/opt/homebrew/share/android-commandlinetools",
	} {
		if d == "" {
			continue
		}
		if r := filepath.Join(d, "platform-tools", exe(nombre)); existe(r) {
			return r
		}
	}
	for _, d := range []string{"/opt/homebrew/bin", "/usr/local/bin"} {
		if r := filepath.Join(d, nombre); existe(r) {
			return r
		}
	}
	return ""
}

func existe(ruta string) bool {
	_, err := os.Stat(ruta)
	return err == nil
}

// descargarHerramientas: platform-tools oficiales de Google para este sistema.
func descargarHerramientas() error {
	sistema := map[string]string{"darwin": "darwin", "windows": "windows", "linux": "linux"}[runtime.GOOS]
	url := fmt.Sprintf("https://dl.google.com/android/repository/platform-tools-latest-%s.zip", sistema)
	r, err := http.Get(url)
	if err != nil {
		return err
	}
	defer r.Body.Close()
	if r.StatusCode != 200 {
		return fmt.Errorf("Google respondió %d", r.StatusCode)
	}
	datos, err := io.ReadAll(r.Body)
	if err != nil {
		return err
	}
	z, err := zip.NewReader(bytes.NewReader(datos), int64(len(datos)))
	if err != nil {
		return err
	}
	destino := carpetaApp()
	for _, f := range z.File {
		ruta := filepath.Join(destino, f.Name)
		if !strings.HasPrefix(ruta, destino+string(os.PathSeparator)) {
			continue
		}
		if f.FileInfo().IsDir() {
			os.MkdirAll(ruta, 0o755)
			continue
		}
		os.MkdirAll(filepath.Dir(ruta), 0o755)
		ent, err := f.Open()
		if err != nil {
			return err
		}
		sal, err := os.OpenFile(ruta, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, f.Mode())
		if err != nil {
			ent.Close()
			return err
		}
		io.Copy(sal, ent)
		sal.Close()
		ent.Close()
	}
	return nil
}

func adb(serie string, args ...string) (string, error) {
	a := herramienta("adb")
	if a == "" {
		return "", errors.New("faltan las herramientas de Android")
	}
	if serie != "" {
		args = append([]string{"-s", serie}, args...)
	}
	out, err := exec.Command(a, args...).CombinedOutput()
	return strings.TrimSpace(string(out)), err
}

// ---------- Móviles conectados ----------

type Dispositivo struct {
	Serie        string  `json:"serie"`
	Estado       string  `json:"estado"` // device, unauthorized, fastboot…
	Marca        string  `json:"marca"`
	Modelo       string  `json:"modelo"`
	Android      string  `json:"android"`
	SDK          int     `json:"sdk"`
	Vendor       int     `json:"vendor"`
	Treble       bool    `json:"treble"`
	Arm64        bool    `json:"arm64"`
	Desbloqueado bool    `json:"desbloqueado"`
	DSU          bool    `json:"dsu"`
	EsSIOS       bool    `json:"es_sios"`
	Compatible   bool    `json:"compatible"`
	Emulador     bool    `json:"emulador"`
	OemPermitido bool    `json:"oem_permitido"` // «Desbloqueo OEM» activado en opciones de desarrollador
	LibreGB      float64 `json:"libre_gb"`      // espacio libre en el móvil (datos), en GB
	Motivo       string  `json:"motivo,omitempty"`
}

var propRe = regexp.MustCompile(`^\[([^\]]+)\]: \[([^\]]*)\]$`)

func dispositivos() ([]Dispositivo, error) {
	salida, err := adb("", "devices", "-l")
	if err != nil {
		return nil, err
	}
	var lista []Dispositivo
	sc := bufio.NewScanner(strings.NewReader(salida))
	for sc.Scan() {
		campos := strings.Fields(sc.Text())
		if len(campos) < 2 || campos[0] == "List" {
			continue
		}
		d := Dispositivo{Serie: campos[0], Estado: campos[1]}
		if d.Estado == "device" {
			props := map[string]string{}
			if p, err := adb(d.Serie, "shell", "getprop"); err == nil {
				for _, l := range strings.Split(p, "\n") {
					if m := propRe.FindStringSubmatch(strings.TrimSpace(l)); m != nil {
						props[m[1]] = m[2]
					}
				}
			}
			d.Marca = props["ro.product.manufacturer"]
			d.Modelo = props["ro.product.model"]
			d.Android = props["ro.build.version.release"]
			d.SDK, _ = strconv.Atoi(props["ro.build.version.sdk"])
			d.Vendor, _ = strconv.Atoi(props["ro.vendor.build.version.sdk"])
			d.Treble = props["ro.treble.enabled"] == "true"
			d.Arm64 = strings.HasPrefix(props["ro.product.cpu.abi"], "arm64")
			d.Desbloqueado = props["ro.boot.flash.locked"] == "0" || props["ro.boot.verifiedbootstate"] == "orange"
			d.EsSIOS = props["ro.sios.version"] != ""
			d.Emulador = props["ro.kernel.qemu"] == "1" || props["ro.boot.qemu"] == "1" || strings.HasPrefix(d.Serie, "emulator-")
			d.OemPermitido = props["sys.oem_unlock_allowed"] == "1"
			if p, _ := adb(d.Serie, "shell", "pm", "path", "com.android.dynsystem"); strings.Contains(p, "package:") {
				d.DSU = true
			}
			d.LibreGB = libreEnMovil(d.Serie)
			switch {
			case d.EsSIOS:
				d.Motivo = "Ya tiene SIOS"
			case !d.Arm64:
				d.Motivo = "SIOS es para móviles ARM de 64 bits"
			case !d.Treble:
				d.Motivo = "Este móvil no admite sistemas genéricos (Treble)"
			case d.Vendor > 0 && d.Vendor < 33:
				d.Motivo = "La base del fabricante es demasiado antigua (necesita Android 13 o más)"
			default:
				d.Compatible = true
			}
		}
		lista = append(lista, d)
	}
	// También los que están en modo fastboot (para el último paso de la instalación).
	if f := herramienta("fastboot"); f != "" {
		if out, err := exec.Command(f, "devices").Output(); err == nil {
			for _, l := range strings.Split(strings.TrimSpace(string(out)), "\n") {
				c := strings.Fields(l)
				if len(c) >= 2 {
					lista = append(lista, Dispositivo{Serie: c[0], Estado: "fastboot"})
				}
			}
		}
	}
	return lista, nil
}

// Probar SIOS sin borrar nada (DSU) ocupa en el móvil: la imagen copiada (~1,5 GB, se borra al
// acabar), el sistema instalado (~3,6 GB) y 2 GB para sus datos, más margen. Al volver a tu Android
// y descartar la prueba, se libera. Instalarlo del todo no ocupa espacio libre (borra el móvil).
const espacioPruebaGB = 8.0

// Para descargar SIOS y descomprimirlo en el ordenador (instalar del todo usa la imagen sin comprimir).
const espacioOrdenadorGB = 6.0

// libreEnMovil: GB libres en /data, de «df -k» (columna «Available»).
func libreEnMovil(serie string) float64 {
	out, err := adb(serie, "shell", "df", "-k", "/data")
	if err != nil {
		return 0
	}
	lineas := strings.Split(strings.TrimSpace(out), "\n")
	campos := strings.Fields(lineas[len(lineas)-1])
	if len(campos) < 4 {
		return 0
	}
	kb, _ := strconv.ParseFloat(campos[3], 64)
	return kb / (1 << 20)
}

// ---------- Imagen de SIOS ----------

// imagenSIOS: la imagen que se va a instalar. La que haya dejado el usuario en la app o la más
// reciente de Descargas.
func imagenSIOS() string {
	candidatos := []string{}
	for _, dir := range []string{carpetaApp(), carpetaDescargas()} {
		m, _ := filepath.Glob(filepath.Join(dir, "sios*.img*"))
		candidatos = append(candidatos, m...)
		m, _ = filepath.Glob(filepath.Join(dir, "SIOS*.img*"))
		candidatos = append(candidatos, m...)
	}
	sort.Slice(candidatos, func(i, j int) bool {
		a, _ := os.Stat(candidatos[i])
		b, _ := os.Stat(candidatos[j])
		return a.ModTime().After(b.ModTime())
	})
	// DSU prefiere la comprimida (.img.gz); fastboot, la .img.
	for _, c := range candidatos {
		if strings.HasSuffix(c, ".img.gz") || strings.HasSuffix(c, ".img") {
			return c
		}
	}
	return ""
}

// ---------- Tareas largas con progreso ----------

type Tarea struct {
	mu      sync.Mutex
	Nombre  string   `json:"nombre"`
	Pasos   []string `json:"pasos"`
	Error   string   `json:"error,omitempty"`
	Hecho   bool     `json:"hecho"`
	Empieza int64    `json:"empieza"`
}

var tareaActual *Tarea
var tareaMu sync.Mutex

func (t *Tarea) paso(s string) {
	t.mu.Lock()
	t.Pasos = append(t.Pasos, s)
	t.mu.Unlock()
}

// cambiarUltimo: para el progreso, que se actualiza en la misma línea.
func (t *Tarea) cambiarUltimo(s string) {
	t.mu.Lock()
	if n := len(t.Pasos); n > 0 {
		t.Pasos[n-1] = s
	} else {
		t.Pasos = append(t.Pasos, s)
	}
	t.mu.Unlock()
}

func empezarTarea(nombre string, fn func(t *Tarea) error) error {
	tareaMu.Lock()
	defer tareaMu.Unlock()
	if tareaActual != nil && !tareaActual.Hecho {
		return errors.New("ya hay una tarea en marcha")
	}
	t := &Tarea{Nombre: nombre, Empieza: time.Now().Unix()}
	tareaActual = t
	go func() {
		err := fn(t)
		t.mu.Lock()
		if err != nil {
			t.Error = err.Error()
		}
		t.Hecho = true
		t.mu.Unlock()
	}()
	return nil
}

func estadoTarea() *Tarea {
	tareaMu.Lock()
	defer tareaMu.Unlock()
	if tareaActual == nil {
		return nil
	}
	t := tareaActual
	t.mu.Lock()
	defer t.mu.Unlock()
	return &Tarea{Nombre: t.Nombre, Pasos: append([]string{}, t.Pasos...), Error: t.Error, Hecho: t.Hecho, Empieza: t.Empieza}
}

// ---------- Instalar ----------

// probarSIOS: DSU. La imagen va al móvil y Android la instala como sistema temporal; al terminar,
// una notificación del propio móvil pide reiniciar en SIOS. Reiniciar otra vez vuelve al de siempre.
func probarSIOS(serie, edicion string) error {
	return empezarTarea("Probar SIOS sin borrar nada", func(t *Tarea) error {
		if libre := libreEnMovil(serie); libre > 0 && libre < espacioPruebaGB {
			return fmt.Errorf("el móvil tiene %.1f GB libres y la prueba necesita unos %.0f GB: libera espacio (fotos, vídeos, apps) y vuelve a intentarlo", libre, espacioPruebaGB)
		}
		img, err := imagenLista(t, edicion, false)
		if err != nil {
			return err
		}
		remoto := "/sdcard/Download/" + filepath.Base(img)
		info, _ := os.Stat(img)
		if tam, _ := adb(serie, "shell", "stat", "-c", "%s", remoto); info != nil && strings.TrimSpace(tam) == strconv.FormatInt(info.Size(), 10) {
			t.paso("SIOS ya estaba copiado en el móvil.")
		} else {
			t.paso("Copiando SIOS al móvil (unos minutos)…")
			if out, err := adb(serie, "push", img, remoto); err != nil {
				return fmt.Errorf("no se pudo copiar: %s", out)
			}
		}
		t.paso("Pidiendo a Android que lo prepare como sistema temporal…")
		adb(serie, "logcat", "-c") // para leer solo lo de esta instalación
		adb(serie, "shell", "setprop", "persist.sys.fflag.override.settings_dynamic_system", "true")
		out, err := adb(serie, "shell", "am", "start-activity",
			"-n", "com.android.dynsystem/com.android.dynsystem.VerificationActivity",
			"-a", "android.os.image.action.START_INSTALL",
			"-d", "file://"+strings.Replace(remoto, "/sdcard", "/storage/emulated/0", 1),
			"--el", "KEY_USERDATA_SIZE", "2147483648") // 2 GB para datos: basta para probar
		if err != nil || strings.Contains(out, "Error") {
			return fmt.Errorf("Android no ha aceptado la instalación temporal: %s", out)
		}
		t.paso("Si el móvil lo pide, confirma la instalación en su pantalla.")
		if err := seguirDSU(serie, t); err != nil {
			return err
		}
		adb(serie, "shell", "rm", "-f", remoto) // ya está instalada: la copia solo ocupaba sitio
		return nil
	})
}

var estadoDSU = regexp.MustCompile(`status: (\w+), cause: (\w+).*?total_progress: (\d+)%`)
var estadoDSUSimple = regexp.MustCompile(`status: (\w+), cause: (\w+)`)

// seguirDSU: lee lo que cuenta el instalador de Android (DynamicSystemInstallationService) hasta
// que termina, para no dar por buena una instalación que ha fallado.
func seguirDSU(serie string, t *Tarea) error {
	ultimo := -1
	for i := 0; i < 900; i++ { // hasta 30 minutos
		time.Sleep(2 * time.Second)
		out, _ := adb(serie, "logcat", "-d", "-s", "DynamicSystemInstallationService:D")
		lineas := strings.Split(out, "\n")
		for j := len(lineas) - 1; j >= 0; j-- {
			l := lineas[j]
			m := estadoDSUSimple.FindStringSubmatch(l)
			if m == nil {
				continue
			}
			estado, causa := m[1], m[2]
			switch {
			case strings.HasPrefix(causa, "ERROR") && strings.HasPrefix(serie, "emulator-"):
				return errors.New("Es normal: en un emulador la prueba temporal no puede arrancar. Tu Android sigue como estaba. Para tener SIOS, usa «Instalar SIOS en el emulador».")
			case strings.HasPrefix(causa, "ERROR"):
				return errors.New("Este móvil no ha podido preparar la prueba temporal. Puedes instalar SIOS del todo o probarlo en otro móvil.")
			case estado == "READY":
				t.paso("Preparado. En el móvil, toca «Reiniciar» en la notificación para arrancar SIOS. Para volver a tu Android, reinicia otra vez.")
				return nil
			case estado == "IN_PROGRESS":
				if p := estadoDSU.FindStringSubmatch(l); p != nil {
					n, _ := strconv.Atoi(p[3])
					if n/10 != ultimo/10 {
						ultimo = n
						t.paso(fmt.Sprintf("Instalando en el móvil… %d %%", n))
					}
				}
			case estado == "NOT_STARTED" && causa == "INSTALL_CANCELLED":
				return errors.New("La instalación se ha cancelado en el móvil.")
			}
			break
		}
	}
	return errors.New("La instalación tarda demasiado: mira la pantalla del móvil.")
}

// instalarSIOS: para siempre, con fastboot (fastbootd para las particiones dinámicas).
func instalarSIOS(serie string, borrar bool, edicion string) error {
	fb := herramienta("fastboot")
	if fb == "" {
		return errors.New("faltan las herramientas de Android")
	}
	return empezarTarea("Instalar SIOS", func(t *Tarea) error {
		// fastboot graba la imagen sin comprimir.
		img, err := imagenLista(t, edicion, true)
		if err != nil {
			return err
		}
		run := func(desc string, args ...string) error {
			t.paso(desc)
			out, err := exec.Command(fb, append([]string{"-s", serie}, args...)...).CombinedOutput()
			if err != nil {
				return fmt.Errorf("%s: %s", desc, strings.TrimSpace(string(out)))
			}
			return nil
		}
		t.paso("Reiniciando el móvil en modo instalación…")
		adb(serie, "reboot", "fastboot")
		time.Sleep(20 * time.Second)
		if err := run("Grabando SIOS (varios minutos)…", "flash", "system", img); err != nil {
			return err
		}
		if borrar {
			if err := run("Borrando los datos del sistema anterior…", "-w"); err != nil {
				return err
			}
		}
		if err := run("Reiniciando en SIOS…", "reboot"); err != nil {
			return err
		}
		t.paso("Hecho. El primer arranque tarda un poco más de lo normal.")
		return nil
	})
}

// ---------- Pasar los datos del móvil anterior ----------

var carpetasMigrar = map[string][]string{
	"fotos":      {"DCIM", "Pictures"},
	"documentos": {"Documents", "Download"},
	"musica":     {"Music"},
}

func migrar(origen, destino string, que []string) error {
	if origen == destino {
		return errors.New("elige dos móviles distintos")
	}
	return empezarTarea("Pasar tus datos", func(t *Tarea) error {
		tmp, err := os.MkdirTemp("", "sios-migrar")
		if err != nil {
			return err
		}
		defer os.RemoveAll(tmp)
		for _, q := range que {
			switch q {
			case "fotos", "documentos", "musica":
				for _, c := range carpetasMigrar[q] {
					t.paso(fmt.Sprintf("Copiando %s…", c))
					local := filepath.Join(tmp, c)
					if out, err := adb(origen, "pull", "/sdcard/"+c, tmp); err != nil {
						if strings.Contains(out, "does not exist") {
							continue
						}
						return fmt.Errorf("no se pudo leer %s: %s", c, out)
					}
					if out, err := adb(destino, "push", local, "/sdcard/"); err != nil {
						return fmt.Errorf("no se pudo copiar %s: %s", c, out)
					}
					os.RemoveAll(local)
				}
			case "contactos":
				t.paso("Copiando los contactos…")
				vcf, n, err := exportarContactos(origen)
				if err != nil {
					return err
				}
				f := filepath.Join(tmp, "contactos.vcf")
				os.WriteFile(f, []byte(vcf), 0o600)
				adb(destino, "push", f, "/sdcard/Download/contactos-anteriores.vcf")
				adb(destino, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-t", "text/x-vcard",
					"-d", "file:///storage/emulated/0/Download/contactos-anteriores.vcf")
				t.paso(fmt.Sprintf("%d contactos listos: confirma la importación en el móvil nuevo.", n))
			case "apps":
				t.paso("Mirando qué apps tenías…")
				out, _ := adb(origen, "shell", "pm", "list", "packages", "-3")
				var apps []string
				for _, l := range strings.Split(out, "\n") {
					if p := strings.TrimPrefix(strings.TrimSpace(l), "package:"); p != "" && p != l {
						apps = append(apps, p)
					}
				}
				sort.Strings(apps)
				os.WriteFile(filepath.Join(carpetaApp(), "apps-anteriores.txt"), []byte(strings.Join(apps, "\n")), 0o644)
				if len(apps) == 0 {
					t.paso("No tenías apps instaladas aparte de las del sistema.")
				} else {
					t.paso(fmt.Sprintf("%d apps apuntadas: instálalas desde la lista de abajo.", len(apps)))
				}
			}
		}
		// Que la galería y el resto vean lo copiado sin esperar.
		adb(destino, "shell", "content", "call", "--uri", "content://media", "--method", "scan_volume", "--arg", "external_primary")
		t.paso("Hecho.")
		return nil
	})
}

// exportarContactos: los contactos del móvil anterior en un vCard (nombre, teléfonos y correos).
func exportarContactos(serie string) (string, int, error) {
	out, err := adb(serie, "shell", "content", "query", "--uri", "content://com.android.contacts/data",
		"--projection", "contact_id:mimetype:data1")
	if err != nil {
		return "", 0, fmt.Errorf("no se pudieron leer los contactos: %s", out)
	}
	type contacto struct {
		nombre             string
		telefonos, correos []string
	}
	porID := map[string]*contacto{}
	orden := []string{}
	re := regexp.MustCompile(`contact_id=(\d+), mimetype=([^,]+), data1=(.*)$`)
	for _, l := range strings.Split(out, "\n") {
		m := re.FindStringSubmatch(strings.TrimSpace(l))
		if m == nil || m[3] == "NULL" {
			continue
		}
		c := porID[m[1]]
		if c == nil {
			c = &contacto{}
			porID[m[1]] = c
			orden = append(orden, m[1])
		}
		switch m[2] {
		case "vnd.android.cursor.item/name":
			c.nombre = m[3]
		case "vnd.android.cursor.item/phone_v2":
			c.telefonos = append(c.telefonos, m[3])
		case "vnd.android.cursor.item/email_v2":
			c.correos = append(c.correos, m[3])
		}
	}
	var b strings.Builder
	n := 0
	for _, id := range orden {
		c := porID[id]
		if c.nombre == "" && len(c.telefonos) == 0 {
			continue
		}
		n++
		b.WriteString("BEGIN:VCARD\r\nVERSION:3.0\r\n")
		fmt.Fprintf(&b, "FN:%s\r\n", c.nombre)
		for _, t := range c.telefonos {
			fmt.Fprintf(&b, "TEL:%s\r\n", t)
		}
		for _, e := range c.correos {
			fmt.Fprintf(&b, "EMAIL:%s\r\n", e)
		}
		b.WriteString("END:VCARD\r\n")
	}
	return b.String(), n, nil
}

func appsAnteriores() []string {
	d, err := os.ReadFile(filepath.Join(carpetaApp(), "apps-anteriores.txt"))
	if err != nil || len(d) == 0 {
		return []string{}
	}
	return strings.Split(string(d), "\n")
}

// ---------- Desbloquear el cargador de arranque ----------

// esperarFastboot: hasta que el móvil aparece en modo fastboot (o se acaba el tiempo).
func esperarFastboot(fb string, segundos int) string {
	for i := 0; i < segundos; i++ {
		out, _ := exec.Command(fb, "devices").Output()
		if c := strings.Fields(strings.TrimSpace(string(out))); len(c) >= 2 {
			return c[0]
		}
		time.Sleep(time.Second)
	}
	return ""
}

// desbloquear: para las marcas que lo permiten con fastboot (Pixel, OnePlus, Nothing…). El móvil
// pide confirmarlo con sus botones y, al aceptar, se borra entero.
func desbloquear(serie string) error {
	fb := herramienta("fastboot")
	if fb == "" {
		return errors.New("faltan las herramientas de Android")
	}
	return empezarTarea("Desbloquear el cargador de arranque", func(t *Tarea) error {
		t.paso("Reiniciando el móvil en modo fastboot…")
		adb(serie, "reboot", "bootloader")
		f := esperarFastboot(fb, 60)
		if f == "" {
			return errors.New("El móvil no ha entrado en modo fastboot. Desconéctalo, vuelve a conectarlo e inténtalo otra vez.")
		}
		t.paso("En el móvil: elige «Unlock the bootloader» con los botones de volumen y confírmalo con el de encendido.")
		out, err := exec.Command(fb, "-s", f, "flashing", "unlock").CombinedOutput()
		if err != nil || strings.Contains(strings.ToLower(string(out)), "unknown command") {
			out, err = exec.Command(fb, "-s", f, "oem", "unlock").CombinedOutput()
		}
		if err != nil || strings.Contains(strings.ToLower(string(out)), "fail") {
			return fmt.Errorf("El móvil no ha aceptado el desbloqueo: %s", strings.TrimSpace(string(out)))
		}
		t.paso("Desbloqueado. El móvil se borra y se reinicia: cuando arranque, vuelve a activar la depuración USB.")
		return nil
	})
}

// ---------- Instalar en un emulador (para probar el recorrido entero) ----------

func sdkAndroid() string {
	for _, d := range []string{os.Getenv("ANDROID_SDK_ROOT"), os.Getenv("ANDROID_HOME"), "/opt/homebrew/share/android-commandlinetools", filepath.Join(os.Getenv("HOME"), "Library/Android/sdk")} {
		if d != "" {
			if _, err := os.Stat(filepath.Join(d, "emulator")); err == nil {
				return d
			}
		}
	}
	return ""
}

const sistemaSIOSEmulador = "system-images/android-36.1/sios/arm64-v8a/"

// instalarEnEmulador: un emulador no tiene fastboot; instalar SIOS en él es cambiarle la imagen
// de sistema por la de SIOS y empezar de cero (como «Quedarme con SIOS» borrando los datos).
func instalarEnEmulador(serie string) error {
	sdk := sdkAndroid()
	if sdk == "" {
		return errors.New("no encuentro el SDK de Android en este ordenador")
	}
	if _, err := os.Stat(filepath.Join(sdk, sistemaSIOSEmulador, "system.img")); err != nil {
		return errors.New("falta la imagen de SIOS para emulador (rom/emulador/preparar.sh)")
	}
	nombre, err := adb(serie, "emu", "avd", "name")
	nombre = strings.TrimSpace(strings.Split(nombre, "\n")[0])
	if err != nil || nombre == "" {
		return errors.New("no se pudo saber qué emulador es")
	}
	puerto := strings.TrimPrefix(serie, "emulator-")
	return empezarTarea("Instalar SIOS", func(t *Tarea) error {
		casa, _ := os.UserHomeDir()
		avd := filepath.Join(casa, ".android", "avd", nombre+".avd")
		t.paso("Apagando el móvil…")
		adb(serie, "emu", "kill")
		time.Sleep(6 * time.Second)
		t.paso("Grabando SIOS y borrando el sistema anterior…")
		cfg, err := os.ReadFile(filepath.Join(avd, "config.ini"))
		if err != nil {
			return err
		}
		lineas := []string{}
		for _, l := range strings.Split(string(cfg), "\n") {
			switch {
			case strings.HasPrefix(l, "image.sysdir.1="):
				l = "image.sysdir.1=" + sistemaSIOSEmulador
			case strings.HasPrefix(l, "tag.id="), strings.HasPrefix(l, "tag.ids="):
				l = strings.SplitN(l, "=", 2)[0] + "=sios"
			case strings.HasPrefix(l, "tag.display="), strings.HasPrefix(l, "tag.displaynames="):
				l = strings.SplitN(l, "=", 2)[0] + "=SIOS"
			case strings.HasPrefix(l, "hw.ramSize="):
				if n, _ := strconv.Atoi(strings.TrimPrefix(l, "hw.ramSize=")); n < 3072 {
					l = "hw.ramSize=3072" // SIOS y su agente necesitan algo más de memoria
				}
			}
			lineas = append(lineas, l)
		}
		os.WriteFile(filepath.Join(avd, "config.ini"), []byte(strings.Join(lineas, "\n")), 0o644)
		for _, patron := range []string{"userdata-qemu.img*", "cache.img*", "*.lock", "snapshots"} {
			m, _ := filepath.Glob(filepath.Join(avd, patron))
			for _, f := range m {
				os.RemoveAll(f)
			}
		}
		t.paso("Arrancando SIOS por primera vez…")
		cmd := exec.Command(filepath.Join(sdk, "emulator", "emulator"), "-avd", nombre, "-port", puerto, "-no-snapshot", "-gpu", "host", "-memory", "3072")
		cmd.Env = append(os.Environ(), "ANDROID_SDK_ROOT="+sdk)
		if err := cmd.Start(); err != nil {
			return err
		}
		go cmd.Wait()
		for i := 0; i < 240; i++ {
			time.Sleep(2 * time.Second)
			if out, _ := adb(serie, "shell", "getprop", "sys.boot_completed"); strings.TrimSpace(out) == "1" {
				t.paso("SIOS está en marcha. Sigue la bienvenida en el móvil.")
				return nil
			}
		}
		return errors.New("SIOS tarda en arrancar: mira la ventana del emulador")
	})
}

// puenteEmulador: el emulador de Android vive en su propia red (10.0.2.x) y el ordenador no la ve.
// Si el móvil dice estar ahí y hay un emulador conectado por adb, se le abre el puerto en este
// ordenador y se vincula por 127.0.0.1. Con un móvil de verdad en la Wi‑Fi no hace nada.
func puenteEmulador(host string) string {
	ip, puerto, err := net.SplitHostPort(host)
	if err != nil || !strings.HasPrefix(ip, "10.0.2.") {
		return host
	}
	lista, _ := dispositivos()
	for _, d := range lista {
		if d.Emulador && d.Estado == "device" {
			if _, err := adb(d.Serie, "forward", "tcp:"+puerto, "tcp:"+puerto); err == nil {
				return net.JoinHostPort("127.0.0.1", puerto)
			}
		}
	}
	return host
}
