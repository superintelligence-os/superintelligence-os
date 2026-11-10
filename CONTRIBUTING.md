# Contribuir a SIOS · Contributing to SIOS

[Español](#español) · [English](#english)

---

## Español

Gracias por querer ayudar. Antes de nada:

- **Fallos de seguridad:** no abras una incidencia pública; sigue [SECURITY.md](SECURITY.md).
- **Cambios grandes:** abre primero una incidencia para hablarlo, así nadie trabaja en balde.
- **No subas nada de Google** (apps, servicios de Play, imágenes GAPPS), ni APK, imágenes de
  sistema, modelos de IA ni claves privadas. El `.gitignore` ya los deja fuera.

### Firmar el CLA

Para que podamos aceptar tu contribución tienes que firmar el [acuerdo de licencia de contribuidor
(CLA)](CLA.md). Tú sigues siendo el autor de tu código; el CLA da al mantenedor del proyecto una
licencia amplia (de derechos de autor y de patentes) para distribuirlo con la AGPL y también con
otras licencias, incluidas las comerciales. Así el proyecto puede ofrecer una licencia doble.

Cómo firmarlo:

1. Lee [CLA.md](CLA.md).
2. En tu primera *pull request*, añade una línea al final de la descripción:

   > He leído el CLA de SIOS (versión 1.0) y lo acepto para esta y mis futuras contribuciones. / I have read the SIOS CLA (version 1.0) and I agree to it for this and my future contributions.

3. Haz los *commits* con `git commit -s` (añade `Signed-off-by: Tu Nombre <tu@correo>`), con el
   mismo nombre y correo que usas en la *pull request*.

Si contribuyes en nombre de una empresa, o tu contrato dice que lo que programas es de tu empleador,
escríbenos antes a info@neuroaigency.com: hace falta un acuerdo firmado por la empresa.

### Estilo del código

**Idioma**
- Identificadores, comentarios, mensajes de *commit*, nombres de archivo y documentación, en
  **español**: `Marca.texto()`, `CortafuegosVpn`, `construir.sh`. Los nombres de las APIs de
  terceros se quedan como son.
- Comentarios que expliquen **por qué**, en frases normales y claras. Si algo depende de una
  peculiaridad de Android o de un fallo encontrado, dilo en el comentario.

**Textos de la app**
- Todo texto que vea el usuario va en recursos (`app/app/src/main/res/values*/strings*.xml`), nunca
  escrito en el código.
- **Seis idiomas**: inglés (`values/`, el de por defecto), español (`values-es`), francés
  (`values-fr`), alemán (`values-de`), italiano (`values-it`) y portugués (`values-pt`). Un texto
  nuevo va en los seis.
- **«Claude» es el comodín de la IA.** En los textos de la app, «Claude» significa «la IA que haya
  elegido el usuario»: `Marca` (`app/app/src/main/java/com/sios/ui/Marca.kt`) lo sustituye al leer
  el texto por el nombre de la IA elegida, o por «SIOS» si todavía no hay ninguna. Así que escribe
  «Pregúntale a Claude», no «Pregúntale a la IA». Excepciones:
  - Si el texto habla de Claude **en concreto** (por ejemplo, en la lista para elegir IA), pon
    `⁠` (`Marca.FIJO`) justo después de «Claude» para que no se cambie.
  - «SIOS» es el nombre del sistema y no se toca nunca.
- Tono cercano, frases cortas, sin jerga técnica cuando no hace falta.

**Interfaz y animaciones**
- Nada de cambios bruscos: todo lo que aparece, desaparece o se mueve lo hace con **muelles**
  (`Muelle.suave()`, `Muelle.vivo()`, `Muelle.cajon()` en `ui/Tema.kt`), no con duraciones fijas.
  Modales, paneles y cambios de estado, con *easing* de muelle.
- Colores siempre de la paleta (`ui/Tema.kt` y la paleta del sistema): nada de colores fijos de
  una IA en concreto. La interfaz tiene que verse bien en SIOS neutro (blanco y negro) y con el
  color de cualquier IA.
- Letra Montserrat; controles de al menos 48 dp; revisa alineación, alturas, recortes y estados
  (cargando, vacío, error) en claro y oscuro antes de enviar.

**Privacidad**
- Nada sale del móvil sin que el usuario lo haya pedido o activado. Si añades una conexión nueva,
  que quede anotada en «Qué ha salido de tu móvil» (`servicio/privacidad.mjs`) con un motivo claro.
- Nada de telemetría ni de librerías que la traigan (ver el comentario de ONNX Runtime en
  `app/app/build.gradle.kts`).
- Lo que venga de terceros (SMS, avisos, webs) llega al agente marcado como datos
  (`servicio/seguridad.mjs`).

**Probar**
- Prueba en el emulador (`rom/emulador/`) o con `scripts/actualizar-sios.sh` sobre un SIOS ya
  instalado. Di en la *pull request* qué has probado y dónde.
- Go: `go test ./...` en `escritorio/`.

### Licencia de las contribuciones

Al contribuir aceptas que tu código se publique con la licencia AGPL-3.0-or-later y que el
mantenedor pueda relicenciarlo según el [CLA](CLA.md). Si añades algo de terceros, indica su origen y
su licencia y añádelo a [THIRD_PARTY.md](THIRD_PARTY.md); solo se aceptan licencias compatibles con
la AGPL.

---

## English

Thanks for wanting to help. First of all:

- **Security issues:** do not open a public issue; follow [SECURITY.md](SECURITY.md).
- **Large changes:** open an issue first to discuss them, so nobody works in vain.
- **Do not commit anything from Google** (apps, Play services, GAPPS images), nor APKs, system
  images, AI models or private keys. The `.gitignore` already excludes them.

### Signing the CLA

Before we can accept your contribution you need to sign the [Contributor License Agreement
(CLA)](CLA.md). You remain the author of your code; the CLA gives the project maintainer a broad
copyright and patent license to distribute it under the AGPL and also under other licenses,
including commercial ones. This is what allows the project to offer dual licensing.

How to sign:

1. Read [CLA.md](CLA.md).
2. In your first pull request, add this line at the end of the description:

   > He leído el CLA de SIOS (versión 1.0) y lo acepto para esta y mis futuras contribuciones. / I have read the SIOS CLA (version 1.0) and I agree to it for this and my future contributions.

3. Commit with `git commit -s` (adds `Signed-off-by: Your Name <you@example.com>`), using the same
   name and email as in the pull request.

If you contribute on behalf of a company, or your employment contract says your code belongs to your
employer, write to info@neuroaigency.com first: a corporate agreement is needed.

### Code style

**Language**
- Identifiers, comments, commit messages, file names and documentation are in **Spanish**:
  `Marca.texto()`, `CortafuegosVpn`, `construir.sh`. Third-party API names stay as they are.
- Comments explain **why**, in plain sentences. If something depends on an Android quirk or a bug you
  found, say so in the comment.

**App texts**
- Every user-visible text goes in resources (`app/app/src/main/res/values*/strings*.xml`), never
  hard-coded.
- **Six languages**: English (`values/`, the default), Spanish (`values-es`), French (`values-fr`),
  German (`values-de`), Italian (`values-it`) and Portuguese (`values-pt`). A new string goes into
  all six.
- **"Claude" is the placeholder for the AI.** In the app's texts, "Claude" means "whichever AI the
  user chose": `Marca` (`app/app/src/main/java/com/sios/ui/Marca.kt`) replaces it, when the text is
  read, with the chosen AI's name, or with "SIOS" if none has been chosen yet. So write "Ask Claude",
  not "Ask the AI". Exceptions:
  - If the text refers to Claude **specifically** (for example, in the list to choose an AI), put
    `⁠` (`Marca.FIJO`) right after "Claude" so it is not replaced.
  - "SIOS" is the system's name and is never replaced.
- Friendly tone, short sentences, no jargon where it is not needed.

**UI and animation**
- No abrupt changes: everything that appears, disappears or moves uses **springs**
  (`Muelle.suave()`, `Muelle.vivo()`, `Muelle.cajon()` in `ui/Tema.kt`), not fixed durations.
  Dialogs, panels and state changes use spring easing.
- Colours always come from the palette (`ui/Tema.kt` and the system palette), never hard-coded to a
  specific AI. The UI must look right both in neutral SIOS (black and white) and in any AI's colour.
- Montserrat font; touch targets of at least 48 dp; check alignment, heights, clipping and states
  (loading, empty, error) in light and dark mode before submitting.

**Privacy**
- Nothing leaves the phone unless the user asked for it or turned it on. If you add a new outgoing
  connection, make sure it is logged in "What has left your phone" (`servicio/privacidad.mjs`) with
  a clear purpose.
- No telemetry, and no libraries that bring it (see the ONNX Runtime comment in
  `app/app/build.gradle.kts`).
- Third-party content (SMS, notifications, web pages) reaches the agent marked as data
  (`servicio/seguridad.mjs`).

**Testing**
- Test in the emulator (`rom/emulador/`) or with `scripts/actualizar-sios.sh` on an installed SIOS.
  Say in the pull request what you tested and where.
- Go: `go test ./...` in `escritorio/`.

### Licensing of contributions

By contributing you agree that your code is published under AGPL-3.0-or-later and that the
maintainer may relicense it under the terms of the [CLA](CLA.md). If you add third-party material,
state its origin and license and add it to [THIRD_PARTY.md](THIRD_PARTY.md); only AGPL-compatible
licenses are accepted.
