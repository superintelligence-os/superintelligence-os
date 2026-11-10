// Genera los iconos de SIOS Desktop a partir del logo (el mismo trazo que ic_claude.xml):
// el de la barra del Mac (plantilla negra), el de la bandeja de Windows (.ico), el de los avisos y
// el de la app (cuadrado oscuro con el logo naranja, como en el móvil). go run ./herramientas/iconos
package main

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"os"
	"regexp"
	"strconv"

	"golang.org/x/image/vector"
)

func trazo() string {
	b, _ := os.ReadFile("../app/app/src/main/res/drawable/ic_claude.xml")
	return regexp.MustCompile(`pathData="([^"]+)"`).FindStringSubmatch(string(b))[1]
}

// Dibuja el logo (caja 248×248) en un lienzo de lado n, ocupando `escala` del lado, centrado.
func logo(n int, escala float32, c color.Color, fondo *image.RGBA) *image.RGBA {
	r := vector.NewRasterizer(n, n)
	k := float32(n) * escala / 248
	off := (float32(n) - 248*k) / 2
	p := func(x, y float32) (float32, float32) { return off + x*k, off + y*k }
	nums := regexp.MustCompile(`[A-Za-z]|-?\d*\.?\d+(?:e-?\d+)?`).FindAllString(trazo(), -1)
	var cx, cy, sx, sy float32
	i := 0
	f := func() float32 { v, _ := strconv.ParseFloat(nums[i], 32); i++; return float32(v) }
	cmd := ""
	for i < len(nums) {
		if _, err := strconv.ParseFloat(nums[i], 32); err != nil {
			cmd = nums[i]
			i++
			if cmd == "Z" || cmd == "z" {
				r.ClosePath()
				cx, cy = sx, sy
				continue
			}
		}
		switch cmd {
		case "M":
			cx, cy = f(), f()
			sx, sy = cx, cy
			r.MoveTo(p(cx, cy))
			cmd = "L"
		case "L":
			cx, cy = f(), f()
			r.LineTo(p(cx, cy))
		case "H":
			cx = f()
			r.LineTo(p(cx, cy))
		case "V":
			cy = f()
			r.LineTo(p(cx, cy))
		case "C":
			x1, y1, x2, y2, x, y := f(), f(), f(), f(), f(), f()
			ax, ay := p(x1, y1)
			bx, by := p(x2, y2)
			ex, ey := p(x, y)
			r.CubeTo(ax, ay, bx, by, ex, ey)
			cx, cy = x, y
		default:
			panic("orden no soportada: " + cmd)
		}
	}
	lienzo := fondo
	if lienzo == nil {
		lienzo = image.NewRGBA(image.Rect(0, 0, n, n))
	}
	r.Draw(lienzo, lienzo.Bounds(), image.NewUniform(c), image.Point{})
	return lienzo
}

// Cuadrado redondeado (icono de app del Mac: cuerpo de 824/1024 con esquinas de ~185).
func cuadrado(n int, c color.Color) *image.RGBA {
	img := image.NewRGBA(image.Rect(0, 0, n, n))
	r := vector.NewRasterizer(n, n)
	m := float32(n) * 100 / 1024
	l := float32(n) - 2*m
	rad := l * 0.225
	const k = 0.5523
	x0, y0, x1, y1 := m, m, m+l, m+l
	r.MoveTo(x0+rad, y0)
	r.LineTo(x1-rad, y0)
	r.CubeTo(x1-rad+rad*k, y0, x1, y0+rad-rad*k, x1, y0+rad)
	r.LineTo(x1, y1-rad)
	r.CubeTo(x1, y1-rad+rad*k, x1-rad+rad*k, y1, x1-rad, y1)
	r.LineTo(x0+rad, y1)
	r.CubeTo(x0+rad-rad*k, y1, x0, y1-rad+rad*k, x0, y1-rad)
	r.LineTo(x0, y0+rad)
	r.CubeTo(x0, y0+rad-rad*k, x0+rad-rad*k, y0, x0+rad, y0)
	r.ClosePath()
	r.Draw(img, img.Bounds(), image.NewUniform(c), image.Point{})
	return img
}

func guardarPNG(ruta string, img image.Image) {
	var b bytes.Buffer
	png.Encode(&b, img)
	os.WriteFile(ruta, b.Bytes(), 0o644)
}

// .ico con PNG dentro (Windows Vista en adelante).
func guardarICO(ruta string, tamanos []int, hacer func(int) image.Image) {
	var imgs [][]byte
	for _, t := range tamanos {
		var b bytes.Buffer
		png.Encode(&b, hacer(t))
		imgs = append(imgs, b.Bytes())
	}
	var out bytes.Buffer
	binary.Write(&out, binary.LittleEndian, []uint16{0, 1, uint16(len(imgs))})
	desp := 6 + 16*len(imgs)
	for i, t := range tamanos {
		lado := byte(t)
		if t >= 256 {
			lado = 0
		}
		out.Write([]byte{lado, lado, 0, 0})
		binary.Write(&out, binary.LittleEndian, []uint16{1, 32})
		binary.Write(&out, binary.LittleEndian, []uint32{uint32(len(imgs[i])), uint32(desp)})
		desp += len(imgs[i])
	}
	for _, b := range imgs {
		out.Write(b)
	}
	os.WriteFile(ruta, out.Bytes(), 0o644)
}

func main() {
	naranja := color.RGBA{0xD9, 0x77, 0x57, 0xFF}
	oscuro := color.RGBA{0x26, 0x26, 0x24, 0xFF}
	// Barra del Mac: plantilla (negro sobre transparente; macOS la tiñe), 18 pt a 2×.
	guardarPNG("recursos/barra_mac.png", logo(36, 0.94, color.Black, nil))
	// Bandeja de Windows: naranja, varios tamaños.
	guardarICO("recursos/bandeja.ico", []int{16, 20, 24, 32, 48}, func(n int) image.Image { return logo(n, 0.96, naranja, nil) })
	// Avisos.
	guardarPNG("recursos/avisos.png", logo(256, 0.9, naranja, nil))
	// App: cuadrado oscuro con el logo naranja (como el icono del móvil).
	os.MkdirAll("recursos/Claude.iconset", 0o755)
	for _, t := range []struct {
		n    int
		name string
	}{{16, "16x16"}, {32, "16x16@2x"}, {32, "32x32"}, {64, "32x32@2x"}, {128, "128x128"}, {256, "128x128@2x"}, {256, "256x256"}, {512, "256x256@2x"}, {512, "512x512"}, {1024, "512x512@2x"}} {
		base := cuadrado(t.n, oscuro)
		guardarPNG("recursos/Claude.iconset/icon_"+t.name+".png", logo(t.n, 0.46, naranja, base))
	}
	// Windows: el mismo icono para el .exe.
	guardarICO("recursos/app.ico", []int{16, 24, 32, 48, 64, 128, 256}, func(n int) image.Image { return logo(n, 0.46, naranja, cuadrado(n, oscuro)) })
	_ = draw.Draw
}
