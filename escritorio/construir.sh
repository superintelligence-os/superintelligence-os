#!/bin/bash
# Construye SIOS Desktop: la app del Mac (universal: Apple Silicon e Intel) y el .exe de
# Windows (x64; en Windows ARM funciona emulado). Salida en dist/.
set -euo pipefail
cd "$(dirname "$0")"
VERSION="${VERSION:-0.1.0}"
APP="dist/SIOS Desktop.app"
rm -rf dist && mkdir -p dist

echo "● Iconos"
# Los iconos salen de rom/marca/sios/iconos_escritorio.py (el símbolo de SIOS).

echo "● Mac"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
CGO_ENABLED=1 GOARCH=arm64 go build -trimpath -ldflags "-s -w" -o dist/mac-arm64 .
CGO_ENABLED=1 GOARCH=amd64 CC="clang -arch x86_64" go build -trimpath -ldflags "-s -w" -o dist/mac-amd64 .
lipo -create dist/mac-arm64 dist/mac-amd64 -output "$APP/Contents/MacOS/sios-escritorio"
rm dist/mac-arm64 dist/mac-amd64
iconutil -c icns recursos/SIOS.iconset -o "$APP/Contents/Resources/AppIcon.icns"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleName</key><string>SIOS Desktop</string>
<key>CFBundleDisplayName</key><string>SIOS Desktop</string>
<key>CFBundleIdentifier</key><string>com.sios.escritorio</string>
<key>CFBundleExecutable</key><string>sios-escritorio</string>
<key>CFBundleIconFile</key><string>AppIcon</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>$VERSION</string>
<key>CFBundleVersion</key><string>$VERSION</string>
<key>LSMinimumSystemVersion</key><string>12.0</string>
<key>LSUIElement</key><true/>
<key>NSHighResolutionCapable</key><true/>
<key>NSLocalNetworkUsageDescription</key><string>Para encontrar tu móvil con SIOS en tu Wi-Fi.</string>
<key>NSBonjourServices</key><array><string>_sios._tcp</string></array>
</dict></plist>
PLIST
codesign --force --deep -s - "$APP" 2>/dev/null
(cd dist && ditto -c -k --keepParent "SIOS Desktop.app" "SIOS-Desktop-Mac.zip")

echo "● Windows"
go run github.com/akavel/rsrc@v0.10.2 -ico recursos/app.ico -arch amd64 -o rsrc_windows_amd64.syso
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags "-H windowsgui -s -w" -o "dist/SIOS Desktop.exe" .
rm -f rsrc_windows_amd64.syso
ls -la dist
