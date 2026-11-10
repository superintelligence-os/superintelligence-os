# SIOS

**English** · [Español](README.es.md)

SIOS is an Android 16 GSI (a generic system image, based on LineageOS 23.2) where **the AI you
choose is the system's agent**: Claude, ChatGPT, Gemini, Kimi, Mistral, Grok, any OpenAI-compatible
API, or a model that runs on the phone itself. The agent can read the screen, tap, type, open apps,
send messages and run commands, always through the same set of phone tools and asking for your
confirmation for anything sensitive.

Until you pick an AI, the system is neutral: it is just **SIOS**, in black and white. Once you pick
one, the system takes on its name, logo and colour.

> **Status: early (0.1).** Tested end to end in the Android emulator. **Not yet tested on a real
> phone.** See [Status](#status).

## Contents

- [Features](#features)
- [Privacy](#privacy)
- [Editions](#editions)
- [Status](#status)
- [Building](#building)
- [Installing](#installing)
- [Repository layout](#repository-layout)
- [License and trademarks](#license-and-trademarks)

## Features

**The agent**
- Any AI: with your subscription (Claude Code, Codex, Gemini CLI, Kimi Code, Grok, Mistral), with a
  pay-as-you-go API key (DeepSeek, OpenAI, Anthropic, Gemini, Kimi, OpenRouter…), or a local model
  (llama.cpp with Qwen3, recommended according to the phone's RAM). The command-line tools are
  installed the first time you choose them.
- All of them use the same phone tools over MCP. Anything not on the allowed list asks for
  confirmation. Text that comes from third parties (SMS, notifications, web pages) is marked as data,
  and after reading it, actions that send, call, install or run something ask again.
- System-wide: home screen bar, assistant sheet, long-press of the power button, a "Hey …" wake
  word, on-screen search, share sheet, text selection menu (improve, translate, ask), and an
  accessibility service ("Hands") that lets the agent act inside any app, with a visible halo and a
  Stop button.
- Creations: the agent builds home-screen widgets (fixed templates, or free HTML in a sandbox),
  mini-apps, background agents and routines ("when X happens, do Y"), and changes them from a sentence.

**Built-in apps** (Kotlin + Jetpack Compose, one privileged app, `com.sios`)
- Home screen and widget panel, app drawer, settings, keyboard (6 languages, AI bar), phone and
  call screening, professional camera (manual controls, RAW), photo editor with on-device AI
  (subject cut-out, object eraser), document scanner.
- **Vault**: passwords, 2FA codes and documents, encrypted with SQLCipher and a hardware-backed key
  that needs your fingerprint or PIN on every use. Autofill and Credential Manager provider.
- **Emergencies**: SOS (five presses of the power button), medical ID on the lock screen, fall
  detection, "share my trip". Works before the first unlock and never goes through the AI.
- **Firewall and tracker blocking**: per-app internet access (Wi-Fi / mobile data) and DNS blocking
  through a local, DNS-only VPN. Nothing is sent to a VPN server.
- **Screen memory** ("where did I see…?"): off by default, local and encrypted. It never stores
  password fields, banking or payment apps, incognito tabs or the lock screen.
- **Offline voice**: dictation (Whisper or Vosk) and translation (OPUS-MT) on the phone.
- Notification digest, focus mode, scam detector, digital wellbeing, car mode, bedtime, a daily
  journal, context reminders, expenses and subscriptions, warranties, desktop mode on an external
  monitor, encrypted backups (`.sios` file), over-the-air system updates (A/B, with automatic
  rollback).
- Interface in Spanish, English, French, German, Italian and Portuguese.

**SIOS Desktop** (Go, macOS and Windows)
- Install SIOS over a USB cable: try it without erasing anything (DSU) or keep it (fastboot). It
  also moves photos, documents, music, contacts and the app list from your previous phone.
- Continuity: shared clipboard, phone notifications, links and files, SMS and calls from the
  computer, automatic photo backup over Wi-Fi.
- **Find my phone**: ring, lost mode, remote wipe, SIM change alert and an optional photo of whoever
  fails to unlock it. Outside your home network it goes through Nostr, end-to-end encrypted.
- Choose the phone's AI and sign in to it from the computer.

## Privacy

What **stays on the phone**: the vault, screen memory, the phone's search index (text from photos,
documents and notifications), emergency data, backups (unless you save them to a cloud folder),
offline dictation and translation, and everything the local model does.

What **leaves the phone**, and only when the feature is used:
- Messages to the AI you chose, sent to that provider (nothing with the local model).
- Data sources for the widgets you add (weather, news, fuel prices, sports, stocks, maps…), web
  searches you ask for, and app installs (F-Droid / Neo Store; Play Store in the Google edition).
- One-time model downloads from Hugging Face (only when you turn the feature on) and installs of the
  AI command-line tools from GitHub or npm.
- The vault's leaked-password check (off by default): only the first 5 characters of the SHA-1 hash
  go to Have I Been Pwned.
- Continuity with your computer: on the local network; outside it, only end-to-end encrypted
  envelopes through public Nostr relays (or your own relay).
- Optional community services (widget gallery, Family): only if whoever distributes SIOS runs
  `comunidad/`. Family data is end-to-end encrypted.
- Update checks, only if the build was configured with an update channel.

**Settings › What has left your phone** lists every outgoing connection made by the service and the
agent (destination, purpose and size, never the content) and every message sent to a subscription
AI. The Google edition also includes Google Play services, which talk to Google on their own.

## Editions

| Edition | Base | For |
| --- | --- | --- |
| **SIOS** (`rom/construir.sh`) | LineageOS 23.2 GAPPS (MindTheGapps: Play Store, Google services) | Everyday use with WhatsApp, banking apps, Maps… |
| **SIOS Puro** (`rom/construir.sh --puro`) | LineageOS 23.2 VANILLA, without Google | No Google: free apps with Neo Store, Play apps with Aurora Store (no account), CoMaps, Thunderbird |

Google's apps cannot be redistributed: **this repository and any official download contain no Google
apps.** The edition with Google is one you build yourself from a GAPPS base image.

## Status

- Tested in the Android emulator: it boots, goes through the setup wizard, shows the SIOS welcome,
  installs the agent and runs it end to end.
- **Not tested on a real phone yet.** Vendor-dependent parts (camera, fingerprint, VoLTE, NFC
  payments, auto-brightness) may fail on some models, as with any GSI.
- Not deployed: the community gallery / Family server (`comunidad/`) and an update channel.
- SIOS Desktop is not signed with a developer certificate (macOS: right-click › Open; Windows:
  SmartScreen › More info › Run anyway).
- Desktop mode on an external monitor is prepared but untested with a real monitor.
- eSIM: an eSIM activated before installing keeps working; downloading a new one does not (a GSI has
  no eSIM manager).
- On a real phone the agent uses local ADB (127.0.0.1 only): you accept "Allow USB debugging" once.

The full list of limitations is in [`rom/LEEME.md`](rom/LEEME.md) (Spanish).

## Building

Everything is built on **macOS** (Apple Silicon: the scripts use Homebrew paths under
`/opt/homebrew`).

Requirements (taken from the scripts):

| Tool | Used for |
| --- | --- |
| `openjdk@21` (Homebrew) | Gradle, apktool, apksigner |
| Android command-line tools (Homebrew `android-commandlinetools`) with `platforms;android-36`, the Android 37.2 platform (`compileSdk`), `build-tools;36.0.0`, `build-tools;37.0.0`, `platform-tools` and, for testing, `emulator` | App, overlays, signing, emulator |
| `e2fsprogs` (Homebrew) | Editing the ext4 system image (`debugfs`, `resize2fs`, `e2fsck`) |
| `apktool` | Patching Termux (`rom/termux/parchear.sh`) |
| Python 3 with Pillow | Wallpapers, boot animation, image tools |
| Google Chrome (in `/Applications`) | Headless rendering of the brand artwork |
| Go 1.27+ and the Xcode command-line tools | SIOS Desktop (`escritorio/construir.sh`) |
| Node.js | The service (it runs in Termux on the phone), `relay/` and `comunidad/` (Wrangler) |
| `openssl`, `zip`, `curl`; `ffmpeg` (optional) | Keys, packaging, downloads, animation preview |

Steps:

1. **Base images.** Download the unofficial LineageOS 23.2 GSI builds by MisterZtr (arm64, A/B,
   ext4) from SourceForge, check their checksums, extract the `.img` files and place them in
   `rom/base/` with the names `rom/construir.sh` expects (`LineageOS-23.2-20260524-GAPPS-EXT4-GSI.img`
   and `LineageOS-23.2-20260524-VANILLA-EXT4-GSI.img`).
2. **Termux.** Put the arm64 builds of Termux, Termux:API and Termux:Boot from their GitHub releases
   in `descargas/` as `termux-arm64.apk`, `termux-api.apk` and `termux-boot.apk`.
3. **Signing.** The overlays, the app and the patched Termux are signed with
   `~/.android/debug.keystore` (Gradle creates it on the first debug build). Anything you distribute
   should be signed with your own key.
4. **Optional:** `scripts/clave-actualizaciones.sh` creates the update keys (the private keys stay
   in `~/.sios/`; the public ones go into the repository) so the image accepts signed OTA updates.
5. Build:

```bash
rom/construir.sh            # SIOS (Google edition): builds the app and assembles the ROM
rom/construir.sh --puro     # SIOS Puro
rom/construir.sh --sin-app  # reuse the last app build
```

The output goes to `rom/salida/`: the fastboot image (`.img`), the DSU image (`.img.gz`) and their
SHA-256. The apps that ship with SIOS (Neo Store; Aurora Store, CoMaps and Thunderbird in Puro) are
downloaded from F-Droid by `rom/tienda/descargar.sh`, with a pinned version, SHA-256 and signing
certificate.

Testing in the emulator:

```bash
rom/emulador/preparar.sh            # puts the ROM in an emulator called "sios"
rom/emulador/arrancar.sh            # starts it (emulator-5556)
rom/emulador/asistente_lineage.sh   # goes through the LineageOS setup wizard
```

SIOS Desktop: `escritorio/construir.sh` (universal Mac app and Windows x64 `.exe` in
`escritorio/dist/`).

## Installing

The easiest way is **SIOS Desktop › By cable**: it downloads Google's `adb` and `fastboot` if they
are missing, checks that the phone is compatible (arm64, Treble, vendor on Android 13 or later) and
offers two steps:

1. **Try it without erasing anything (DSU).** SIOS boots as a temporary system; restart and your own
   Android is back, untouched.
2. **Keep SIOS (fastboot).** Needs an unlocked bootloader and erases the phone. You type INSTALAR
   to confirm.

The manual commands and a step-by-step guide are in [`rom/LEEME.md`](rom/LEEME.md) and
[`rom/GUIA-INSTALACION.md`](rom/GUIA-INSTALACION.md) (Spanish). Unlocking the bootloader erases the
phone: back up first.

## Repository layout

| Path | What it is |
| --- | --- |
| `app/` | The SIOS app (Kotlin + Compose, package `com.sios`): home, settings, keyboard, phone, camera, vault, emergencies, firewall, screen memory, photo editor, offline voice, desktop mode… |
| `servicio/` | The agent service (Node.js, runs in Termux): AI engines (`ia/`), MCP phone tools, chat, creations, continuity, updates |
| `termux/` | Commands and start scripts installed in Termux (`android`, `bt`, `claude-agente`…) |
| `mcp/` | Standalone MCP server that exposes the phone over ADB |
| `escritorio/` | SIOS Desktop (Go): install by cable, continuity, find my phone |
| `rom/` | Scripts that remaster the GSI on macOS (`construir.sh`), overlays, system layer (`capa/`), brand (`marca/`), OTA, emulator, Termux patch, bundled apps |
| `relay/` | Optional relay (Cloudflare Worker) for those who prefer it to Nostr |
| `comunidad/` | Optional Cloudflare Worker for the community widget gallery and Family |
| `scripts/` | Development helpers: deploy to the emulator, update keys, publish a version |
| `descargas/` | Third-party downloads (the Termux APKs, not in the repository) and the Montserrat font |

Code, identifiers and comments are in Spanish. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License and trademarks

SIOS's own code is licensed under the **GNU Affero General Public License v3.0 or later**
([LICENSE](LICENSE)). Copyright © The SIOS authors. Contributions require signing the
[CLA](CLA.md), which lets the project also offer the code under commercial licenses.

Third-party components keep their own licenses: LineageOS/AOSP, Termux, the bundled F-Droid apps,
AI models downloaded on demand, fonts, icons, block lists, dictionaries and libraries. See
[THIRD_PARTY.md](THIRD_PARTY.md).

Claude, ChatGPT, Gemini, Kimi, Mistral, Grok, DeepSeek, OpenRouter, Qwen, Android and the other names
and logos mentioned are trademarks of their respective owners. **SIOS is not affiliated with,
endorsed by or sponsored by any of them.** Their names and logos are shown only to identify the AI
the user has chosen.

Security issues: please report them privately, see [SECURITY.md](SECURITY.md).
