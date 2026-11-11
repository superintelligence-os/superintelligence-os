# Third-party components · Componentes de terceros

SIOS's own code is AGPL-3.0-or-later (see [LICENSE](LICENSE)). Everything listed here belongs to its
respective authors and keeps **its own license**. The tables say whether each component is
**in this repository**, **bundled** into the built images/apps, or **downloaded on demand** on the
phone or the computer.

> El código propio de SIOS es AGPL-3.0-or-later. Todo lo de esta lista es de sus autores y mantiene
> **su propia licencia**. Las tablas dicen si cada cosa está **en el repositorio**, si va **dentro**
> de las imágenes o apps que se construyen, o si se **descarga bajo demanda**.

If you redistribute SIOS images or apps, you must comply with the licenses of everything bundled in
them (for GPL components, this includes offering the corresponding source code).

## 1. Base system

| Component | License | Where |
| --- | --- | --- |
| LineageOS 23.2 / AOSP (Android 16 QPR2), unofficial GSI builds by MisterZtr, with TrebleDroid (phh) patches | Mostly Apache-2.0; Linux kernel GPL-2.0; other components under their own licenses (see *Settings › About phone › Legal information* on the device) | Not in the repository. The base image is downloaded by whoever builds; `rom/construir.sh` adds the SIOS layer on top. |
| MindTheGapps (Google apps in the GAPPS base: Play Store, Google Play services…) | Proprietary (Google) | **Never in the repository or in official downloads.** Only in the "SIOS" edition, which each user builds from a GAPPS base image. |

Some files of the base are modified at build time (for example `phh-on-boot.sh`, `build.prop`,
`fonts_customization.xml`, `otacerts.zip`); the modifications are the scripts in `rom/`.

## 2. Apps bundled in the image

| Component | License | Where |
| --- | --- | --- |
| [Termux](https://github.com/termux/termux-app) | GPL-3.0 | Bundled, **modified**: `rom/termux/parchear.sh` patches the external-apps check, the name and the icon, and re-signs it. Source: upstream + that script. The APK goes in `descargas/` (not in the repository). |
| [Termux:API](https://github.com/termux/termux-api) | GPL-3.0 | Bundled, re-signed (contents unchanged). |
| [Termux:Boot](https://github.com/termux/termux-boot) | GPL-3.0 | Bundled, re-signed (contents unchanged). |
| Termux bootstrap (`sistema-base.tar.gz`, from the Termux APK) and the packages installed later (Node.js, proot-distro, Debian, android-tools, llama.cpp…) | Each package under its own license (see [termux-packages](https://github.com/termux/termux-packages) and Debian) | Bootstrap bundled; packages downloaded on demand. |
| [Neo Store](https://github.com/NeoApplications/Neo-Store) (`com.machiav3lli.fdroid`) | GPL-3.0 | Bundled unmodified, both editions. Downloaded from F-Droid by `rom/tienda/descargar.sh` (pinned version, SHA-256 and signing certificate). |
| [Aurora Store](https://gitlab.com/AuroraOSS/AuroraStore) | GPL-3.0 | Bundled unmodified, SIOS Puro only. Same script. |
| [CoMaps](https://codeberg.org/comaps/comaps) | Apache-2.0 (map data © OpenStreetMap contributors, ODbL) | Bundled unmodified, SIOS Puro only. Same script. |
| [Thunderbird for Android](https://github.com/thunderbird/thunderbird-android) | Apache-2.0 | Bundled unmodified, SIOS Puro only. Same script. |

## 3. Libraries in the SIOS app (`app/`)

Resolved by Gradle at build time (not in the repository), and bundled in the APK.

| Library | License |
| --- | --- |
| AndroidX, Jetpack Compose, CameraX, `androidx.credentials`, `androidx.sqlite`, `androidx.documentfile` | Apache-2.0 |
| Kotlin standard library, kotlinx-coroutines | Apache-2.0 |
| OkHttp, okhttp-sse (Square) | Apache-2.0 |
| ZXing core | Apache-2.0 |
| Vosk Android (Alpha Cephei) | Apache-2.0 |
| JNA | Apache-2.0 or LGPL-2.1 (dual) |
| SQLCipher for Android (Zetetic, community edition) | BSD-style (see its license) |
| ONNX Runtime for Android 1.28.0 (Microsoft) | MIT |
| androidx.fragment 1.3.6 | Apache-2.0 |
| Gradle wrapper (`app/gradle/wrapper/`, `gradlew`) | Apache-2.0 — in the repository |

## 4. Other code dependencies

**Service (`servicio/`, `mcp/`)** — installed with npm on the phone:

| Package | License |
| --- | --- |
| `@modelcontextprotocol/sdk` | MIT |
| `zod` | MIT |

**SIOS Desktop (`escritorio/`)** — Go modules, bundled in the binaries:

| Module | License |
| --- | --- |
| `fyne.io/systray`, `github.com/ebitengine/purego` | Apache-2.0 |
| `github.com/gen2brain/beeep`, `github.com/godbus/dbus/v5`, `github.com/esiqveland/notify`, `github.com/pkg/errors`, `github.com/pmezard/go-difflib` | BSD-2-Clause / BSD-3-Clause |
| `golang.org/x/*`, `github.com/miekg/dns`, `github.com/sergeymakinen/go-bmp`, `github.com/sergeymakinen/go-ico` | BSD-3-Clause |
| `github.com/grandcat/zeroconf`, `golang.design/x/clipboard`, `golang.design/x/x11`, `github.com/go-ole/go-ole`, `github.com/cenkalti/backoff`, `github.com/jackmordaunt/icns/v3`, `github.com/tadvi/systray`, `github.com/stretchr/testify` | MIT |
| `git.sr.ht/~jackmordaunt/go-toast` | MIT or Unlicense |
| `github.com/nfnt/resize`, `github.com/davecgh/go-spew` | ISC |
| `gopkg.in/yaml.v3` | MIT and Apache-2.0 |
| `github.com/akavel/rsrc` (build tool only, Windows icon) | MIT |

**Relay and community (`relay/`, `comunidad/`)**: no runtime dependencies; deployed with Cloudflare
Wrangler (Apache-2.0 / MIT), not bundled.

### Built-in OCR models (inside the APK)

| Model | Used for | License | Source |
| --- | --- | --- | --- |
| PP-OCRv5_mobile_det (PaddlePaddle) | Text detection (OCR of photos, screenshots, documents, screen memory) | Apache-2.0 | Hugging Face `PaddlePaddle/PP-OCRv5_mobile_det_onnx`, pinned revision and SHA-256 in `app/app/build.gradle.kts` (task `modelosOcr`, downloaded at build time, not in the repository) |
| latin_PP-OCRv5_mobile_rec + its dictionary (PaddlePaddle) | Text recognition (Latin script) | Apache-2.0 | `PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx`, same task |
| openWakeWord melspectrogram + embedding models (David Scripka; embedding based on Google's speech_embedding) | «Oye SIOS» acoustic wake-word detector | Apache-2.0 | In the repository: `app/app/src/main/assets/oye/` (v0.5.1, unchanged) |

SIOS contains **no Google ML Kit or Google Play services code**: OCR, the document scanner and
photo labelling are open source and run on the phone.

## 5. AI models (downloaded on demand)

None of these are in the repository or the images: the phone downloads them, from pinned revisions
on Hugging Face or the publisher's site, only when the user turns the feature on.

| Model | Used for | License | Defined in |
| --- | --- | --- | --- |
| CLIP ViT-B/32 (OpenAI), ONNX 8-bit by Xenova | Screen memory: image search | MIT | `app/.../sistema/MemoriaPantallaModelo.kt` |
| clip-ViT-B-32-multilingual-v1 (sentence-transformers) | Screen memory: query text | Apache-2.0 | same |
| Whisper small (OpenAI), ONNX by onnx-community | Offline dictation (accurate) | MIT (model); Apache-2.0 (ONNX export) | `app/.../voz/ModelosVoz.kt` |
| Vosk small models (Alpha Cephei), one per language | Offline dictation (light), "Hey …", call answering | Apache-2.0 | same |
| OPUS-MT (University of Helsinki), ONNX by Xenova | Offline translation | Apache-2.0 / CC-BY-4.0 | same |
| IS-Net "DIS general use" | Photo editor: subject cut-out | Apache-2.0 | `app/.../fotos/ModelosFotos.kt` |
| MI-GAN pipeline v2 (Picsart) | Photo editor: object eraser | MIT | same |
| Qwen3 0.6B / 1.7B / 4B / 8B, GGUF (Qwen; 1.7B conversion by Unsloth) | Local AI model | Apache-2.0 | `servicio/ia/catalogo.mjs` |

## 6. AI tools installed on demand

Installed on the phone the first time the user chooses that AI (`servicio/ia/instalar.sh`). Each
one is used under its own license and terms of service, with the user's own account or key.

| Tool | License |
| --- | --- |
| Claude Code (Anthropic), official installer | Proprietary (Anthropic's terms) |
| Codex CLI (OpenAI) | Apache-2.0 |
| Gemini CLI (Google) | Apache-2.0 |
| Kimi Code (Moonshot AI) | See its package's license |
| llama.cpp (`llama-server`, Termux package) | MIT |

## 7. Fonts, icons and artwork

| Component | License | Where |
| --- | --- | --- |
| [Montserrat](https://github.com/JulietaUla/Montserrat) | SIL Open Font License 1.1 (`descargas/fuentes/OFL.txt`, `app/app/LICENCIA-MONTSERRAT.txt`) | In the repository: `app/app/src/main/res/font/`, `servicio/web/`, `escritorio/recursos/`, `descargas/fuentes/`. Bundled as the system font (`Montserrat-Sios.ttf`). |
| [Lucide](https://lucide.dev) icons | ISC | Converted into `app/.../ui/PackIconos.kt` by `app/herramientas/pack_iconos.py`. |
| [Simple Icons](https://simpleicons.org) | CC0-1.0 (the brand logos themselves are trademarks of their owners) | Same file. |
| AI provider logos (Claude, ChatGPT/OpenAI, Gemini, DeepSeek, Kimi, Mistral, Grok, OpenRouter, Qwen), taken from [svgl](https://svgl.app) and the providers | **Trademarks of their owners**, used only to identify the AI the user chose | `app/.../res/drawable/ic_ia_*.xml`, `ic_claude.xml`, `rom/marca/sios/logos/`, `rom/marca/logo.txt`, `escritorio/herramientas/ventana/logos.json`, and the wallpapers in `app/.../assets/fondos/` |

The SIOS symbol, logotype, wallpapers and boot animation are generated by the scripts in
`rom/marca/` and are part of SIOS (AGPL-3.0-or-later). The SIOS name and logo may be subject to
trademark rules set by the project.

## 8. Data

| Data | License | Where |
| --- | --- | --- |
| AdAway hosts list | CC BY 3.0 | In the repository (`app/.../assets/cortafuegos/lista-compacta.txt`, reformatted to one domain per line). Notice in `LICENCIAS-cortafuegos.txt`. |
| StevenBlack/hosts "unified" | MIT (each source it aggregates keeps its own license) | Downloaded only if the user chooses the full list. |
| Keyboard dictionaries from [FrequencyWords](https://github.com/hermitdave/FrequencyWords) (Hermit Dave, from OpenSubtitles) | **CC BY-SA 4.0** | In the repository (`app/.../assets/diccionario-*.txt`, trimmed). Notice in `LICENCIAS-diccionarios.txt`. Share-alike applies to these files. |
| `lista-sios.txt`, `empresas.tsv` | Part of SIOS (AGPL-3.0-or-later) | In the repository. |

## 9. Online services

SIOS talks to these services only when a feature that uses them is on; their data is subject to
their own terms and attribution requirements:

- **OpenStreetMap** (Nominatim, Overpass, map tiles) — data © OpenStreetMap contributors, ODbL.
- **Open-Meteo** (weather, air quality) — CC BY 4.0.
- OSRM, Frankfurter, CoinGecko, ESPN, Yahoo Finance, Google News, the Spanish Ministry fuel price
  service, wttr.in, DuckDuckGo, Have I Been Pwned (k-anonymity), F-Droid, Hugging Face, GitHub, npm,
  Termux package mirrors, public Nostr relays (`relay.primal.net`, `offchain.pub`,
  `relay.snort.social`, `relay.nostr.net`).
- The AI provider the user chooses (Anthropic, OpenAI, Google, Moonshot, Mistral, xAI, DeepSeek,
  OpenRouter…), under the user's own account and that provider's terms.
- SIOS Desktop downloads Google's Android platform tools (`adb`, `fastboot`) from `dl.google.com`
  if they are missing, under Google's terms.

## 10. Trademarks

Claude and Anthropic; ChatGPT, Codex and OpenAI; Gemini, Google, Android and Google Play; Kimi and
Moonshot AI; Mistral and Le Chat; Grok and xAI; DeepSeek; OpenRouter; Qwen; LineageOS; Termux; and
any other names and logos mentioned are trademarks of their respective owners. SIOS is not
affiliated with, endorsed by or sponsored by any of them.
