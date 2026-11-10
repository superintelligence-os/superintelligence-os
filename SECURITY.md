# Seguridad · Security

[Español](#español) · [English](#english)

---

## Español

### Cómo avisar de un fallo

**No abras una incidencia pública** para un fallo de seguridad. Escribe en privado a
**info@neuroaigency.com** (si se habilitan los avisos privados de seguridad del repositorio, también
valen). Cuenta:

- Qué parte de SIOS está afectada (app, servicio, SIOS Desktop, ROM, relay, comunidad) y su versión
  o el *commit*.
- Cómo reproducirlo, paso a paso, y qué consigue un atacante.
- Si hace falta acceso físico, estar en la misma red, una app instalada o nada.

Contestaremos en cuanto podamos (objetivo: acuse en 7 días), te diremos si lo confirmamos y
acordaremos contigo cuándo publicarlo, normalmente cuando haya arreglo o a los 90 días. Si quieres,
te citaremos en el aviso.

Todavía no hay versiones estables: los arreglos van a la última versión.

### Lo más delicado

Estas partes tienen prioridad y nos interesa especialmente cualquier fallo en ellas:

- **Bóveda** (`app/.../boveda/`): base SQLCipher, clave envuelta con el Android Keystore y
  autenticación en cada uso, autorrelleno y proveedor de credenciales, exportación cifrada,
  códigos TOTP. Cualquier forma de leer el contenido sin la huella o el PIN, de que quede descifrado
  en memoria o en disco, o de que el autorrelleno rellene en la app o web equivocada.
- **Continuidad y Nostr** (`servicio/continuidad.mjs`, `servicio/nostr.mjs`, `escritorio/`,
  `relay/`): emparejamiento con código de 6 cifras (X25519 + HKDF + HMAC), cifrado AES-256-GCM con
  ruta y hora, servidor en el puerto 8766 de la red local, firma Schnorr (BIP-340) escrita a mano,
  ventana local de SIOS Desktop. Cualquier forma de emparejarse sin el código, repetir o falsificar
  órdenes, leer los sobres o usar la ventana local desde una web.
- **Actualizaciones y claves** (`servicio/actualizaciones.mjs`, `app/.../sistema/SistemaOta.kt`,
  `rom/capa/system/etc/sios/sistema-ota.sh`, `scripts/publicar-version.sh`): manifiesto firmado con
  Ed25519, cargas del sistema firmadas para update_engine (`otacerts.zip`), vuelta atrás automática.
  Cualquier forma de instalar una app, un agente o un sistema que no esté firmado por la clave de
  SIOS, o de saltarse la comprobación de SHA-256.
- **Antirrobo / Encontrar mi móvil** (`app/.../sistema/Antirrobo.kt`, `Intruso.kt`, `Sonar.kt`):
  modo perdido, borrado a distancia, aviso de cambio de SIM y foto al intruso. Cualquier forma de que
  alguien ajeno ordene un borrado o localice el móvil, o de que el ladrón lo desactive sin
  desbloquear.
- **El agente y sus permisos** (`servicio/agente.mjs`, `servicio/seguridad.mjs`, `servicio/ia/`,
  `/mcp/vigilado`): inyección de instrucciones desde SMS, avisos, webs o documentos que logre que el
  agente envíe, llame, instale o ejecute algo sin confirmación; ADB local (solo 127.0.0.1); el
  «puente» `content://com.sios.puente`; la orden RUN_COMMAND del Termux parcheado; claves de API
  guardadas en el móvil.
- **Emergencias** (`app/.../emergencias/`): que el SOS falle, o que alguien pueda dispararlo o leer
  la ficha médica más allá de lo previsto.
- **Cortafuegos** (`app/.../cortafuegos/`): fugas de DNS o apps que se salten el bloqueo.

### Fuera de alcance

- Lo que depende de LineageOS, AOSP, el kernel o el firmware del fabricante: avisa a esos proyectos.
- Fallos de las IA de terceros o de sus programas (Claude Code, Codex, Gemini CLI, Kimi Code).
- Ataques que exigen un móvil ya desbloqueado y en manos del atacante, salvo que se salten la
  bóveda o el antirrobo.
- Las imágenes que construye cada uno firmadas con su clave de depuración (`~/.android/debug.keystore`):
  para distribuir hay que firmar con claves propias.

---

## English

### How to report

**Do not open a public issue** for a security problem. Write privately to **info@neuroaigency.com**
(or use the repository's private security advisories, if enabled). Please include:

- Which part of SIOS is affected (app, service, SIOS Desktop, ROM, relay, community) and its version
  or commit.
- Step-by-step reproduction and what an attacker gains.
- Whether it needs physical access, the same network, an installed app, or nothing.

We aim to acknowledge within 7 days, confirm or reject the report, and agree a disclosure date with
you, usually once a fix is available or after 90 days. We will credit you if you wish.

There are no stable releases yet: fixes go to the latest version.

### Especially sensitive areas

- **Vault** (`app/.../boveda/`): SQLCipher database, key wrapped by the Android Keystore with
  per-use authentication, autofill and credential provider, encrypted export, TOTP codes.
- **Continuity and Nostr** (`servicio/continuidad.mjs`, `servicio/nostr.mjs`, `escritorio/`,
  `relay/`): 6-digit pairing (X25519 + HKDF + HMAC), AES-256-GCM with path and timestamp, the LAN
  server on port 8766, the hand-written Schnorr (BIP-340) signatures, the SIOS Desktop local window.
- **Updates and keys** (`servicio/actualizaciones.mjs`, `app/.../sistema/SistemaOta.kt`,
  `rom/capa/system/etc/sios/sistema-ota.sh`, `scripts/publicar-version.sh`): Ed25519-signed
  manifest, signed update_engine payloads (`otacerts.zip`), automatic rollback.
- **Anti-theft / Find my phone** (`app/.../sistema/Antirrobo.kt`, `Intruso.kt`, `Sonar.kt`): lost
  mode, remote wipe, SIM change alert, intruder photo.
- **The agent and its permissions** (`servicio/agente.mjs`, `servicio/seguridad.mjs`,
  `servicio/ia/`, `/mcp/vigilado`): prompt injection from SMS, notifications, web pages or documents
  that makes the agent send, call, install or run something without confirmation; local ADB
  (127.0.0.1 only); the `content://com.sios.puente` bridge; RUN_COMMAND in the patched Termux; API
  keys stored on the phone.
- **Emergencies** (`app/.../emergencias/`) and the **firewall** (`app/.../cortafuegos/`).

### Out of scope

- Issues in LineageOS, AOSP, the kernel or vendor firmware: report them to those projects.
- Issues in third-party AIs or their tools (Claude Code, Codex, Gemini CLI, Kimi Code).
- Attacks that require an already unlocked phone in the attacker's hands, unless they bypass the
  vault or anti-theft.
- Images built by others and signed with their debug key (`~/.android/debug.keystore`): anything
  distributed must be signed with its own keys.
