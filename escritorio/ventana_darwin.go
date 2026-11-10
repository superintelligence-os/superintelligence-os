package main

/*
#cgo CFLAGS: -x objective-c -fobjc-arc
#cgo LDFLAGS: -framework Cocoa
#import <Cocoa/Cocoa.h>

// Una app normal (Dock y Cmd+Tab) con su icono, aunque el resto de SIOS Desktop viva en la barra de menús.
static void siosPrepararVentana(void *ventana, int claro) {
	[NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
	NSWindow *v = (__bridge NSWindow *)ventana;
	v.titlebarAppearsTransparent = YES;
	v.appearance = [NSAppearance appearanceNamed:(claro ? NSAppearanceNameAqua : NSAppearanceNameDarkAqua)];
	v.backgroundColor = claro ? [NSColor colorWithRed:0.937 green:0.929 blue:0.902 alpha:1]
	                          : [NSColor colorWithRed:0.090 green:0.090 blue:0.086 alpha:1];
	v.minSize = NSMakeSize(900, 600);
	v.tabbingMode = NSWindowTabbingModeDisallowed;
	[v center];
	[NSApp activateIgnoringOtherApps:YES];
}

static void siosAlFrente(void) {
	[NSApp activateIgnoringOtherApps:YES];
	for (NSWindow *v in NSApp.windows) [v makeKeyAndOrderFront:nil];
}
*/
import "C"

import (
	"os"
	"os/exec"
	"os/signal"
	"strings"
	"syscall"

	webview "github.com/webview/webview_go"
)

func ventanaNativa(direccion string) {
	w := webview.New(false)
	defer w.Destroy()
	w.SetTitle("SIOS Desktop")
	ancho, alto, _, _ := medidaVentana()
	w.SetSize(ancho, alto, webview.HintNone)
	C.siosPrepararVentana(w.Window(), C.int(boolInt(modoClaro())))
	w.Bind("abrirFuera", abrirFuera)
	w.Init(scriptVentana)
	// La barra de menús pide «trae la ventana al frente» con una señal.
	senal := make(chan os.Signal, 1)
	signal.Notify(senal, syscall.SIGUSR1)
	go func() {
		for range senal {
			w.Dispatch(func() { C.siosAlFrente() })
		}
	}()
	w.Navigate(direccion)
	w.Run()
}

func traerAlFrente(p *os.Process) bool { return p.Signal(syscall.SIGUSR1) == nil }

func modoClaro() bool {
	out, err := exec.Command("defaults", "read", "-g", "AppleInterfaceStyle").Output()
	return err != nil || !strings.Contains(string(out), "Dark")
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
