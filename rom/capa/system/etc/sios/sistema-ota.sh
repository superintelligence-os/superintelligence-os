#!/system/bin/sh
# SIOS: ayudante de las actualizaciones del sistema sin cable, con vuelta atrás automática.
#
# Lo arranca init como root, en el dominio permisivo de la GSI (phhsu_daemon, el mismo de phh-ota y
# de los scripts de arranque de TrebleDroid): la app de SIOS es una app privilegiada pero no puede
# hablar con update_engine ni con el gestor de arranque (bootctl) por SELinux, así que se lo pide a
# este ayudante con archivos en su carpeta privada del almacenamiento de antes de desbloquear:
#   /data/user_de/0/com.sios/files/sios-ota/
#     orden    (la escribe la app)  accion=instalar|reiniciar|cancelar|visto y sus datos
#     latido   (la escribe la app)  la app ha arrancado en este arranque
#     estado   (la escribe esto)    fase=… progreso=… mensaje=… (la app solo lo lee)
# Solo la app (y root) pueden escribir en esa carpeta: es su almacenamiento privado.
#
# Instalación: comprueba la huella SHA-256 de la descarga, la copia a /data/ota_package (lo único que
# update_engine puede leer) y se la pasa a update_engine, que la escribe en la otra ranura mientras
# se usa el móvil (comprobando además la firma RSA con /system/etc/security/otacerts.zip).
#
# Vuelta atrás: tras reiniciar en la ranura nueva, el arranque no se da por bueno hasta que SIOS está
# sano: el sistema termina de arrancar, la app de SIOS arranca y, si el agente funcionaba antes de
# actualizar, vuelve a responder. Mientras tanto la ranura sigue «sin confirmar»: si el móvil no llega
# a arrancar, el gestor de arranque vuelve solo a la otra ranura tras sus intentos; si arranca pero
# algo no va, esto vuelve a la versión anterior y reinicia. Con la ranura confirmada empieza la fusión
# de la instantánea (A/B virtual) y ya no hay vuelta atrás.
#
# Quien marcaba el arranque como bueno en esta GSI es phh-on-boot.sh (bootctl mark-boot-successful al
# terminar de arrancar; update_verifier no se lanza): rom/construir.sh lo cambia para que no lo haga
# si hay una actualización pendiente de confirmar. En los móviles con «checkpoint» de /data, vold lo
# marcaría también al terminar de arrancar: se le pide que espere con persist.vold.dont_commit_checkpoint.

CANAL=${SIOS_OTA_CANAL:-/data/user_de/0/com.sios/files/sios-ota}
PRIV=${SIOS_OTA_PRIV:-/data/misc/sios-ota}
PAQ=${SIOS_OTA_PAQ:-/data/ota_package/sios}
UE_LOG=${SIOS_OTA_UE_LOG:-/data/misc/update_engine_log}
UE_PREFS=${SIOS_OTA_UE_PREFS:-/data/misc/update_engine/prefs}
BOOT_ID=$(cat "${SIOS_OTA_BOOT_ID:-/proc/sys/kernel/random/boot_id}" 2>/dev/null)
AGENTE=${SIOS_OTA_AGENTE:-http://127.0.0.1:8765/estado}
PAUSA=${SIOS_OTA_PAUSA:-2}

MAX_ARRANQUES=3      # arranques de la ranura nueva sin confirmar antes de volver atrás
ESPERA_SISTEMA=900   # s desde que arranca el núcleo hasta que el sistema termina de arrancar
ESPERA_APP=600       # s desde que termina de arrancar hasta que la app de SIOS da señales
ESPERA_AGENTE=1800   # s desde el desbloqueo hasta que el agente responde (si respondía antes)

registro() { log -t sios-ota "$*" 2>/dev/null; echo "sios-ota: $*" >&2; }
segundos() { cut -d. -f1 "${SIOS_OTA_UPTIME:-/proc/uptime}" 2>/dev/null; }
valor() { sed -n "s/^$2=//p" "$1" 2>/dev/null | head -n 1; }
# poner <archivo> <clave> <valor>: cambia (o añade) una línea clave=valor.
poner() {
  { grep -v "^$2=" "$1" 2>/dev/null; echo "$2=$3"; } > "$1.tmp" && mv -f "$1.tmp" "$1"
}

# ---------- comunicación con la app ----------

ULTIMO_ESTADO=""
# estado <fase> [clave=valor…]: lo que ve la app. Solo se escribe si cambia (no gastar la memoria).
estado() {
  FASE=$1
  [ -d "$CANAL" ] || return 0
  contenido=$(
    echo "fase=$1"; shift
    for kv in "$@"; do echo "$kv"; done
    echo "arranque=$BOOT_ID"
    echo "ranura=$(getprop ro.boot.slot_suffix | tr -d _)"
    [ -f "$PRIV/resultado" ] && sed 's/^/resultado_/' "$PRIV/resultado"
  )
  [ "$contenido" = "$ULTIMO_ESTADO" ] && return 0
  ULTIMO_ESTADO=$contenido
  tmp="$CANAL/.estado.tmp"
  printf '%s\ncuando=%s\n' "$contenido" "$(date +%s)" > "$tmp" || return 0
  uid=$(stat -c %u "$CANAL")
  chown "$uid:$uid" "$tmp" 2>/dev/null
  chmod 0644 "$tmp"
  chcon "$(stat -c %C "$CANAL")" "$tmp" 2>/dev/null
  mv -f "$tmp" "$CANAL/estado"
}

# Aviso a la app (notificación): solo con el sistema ya arrancado.
avisar() {
  [ "$(getprop sys.boot_completed)" = 1 ] || return 0
  am broadcast --user 0 -n com.sios/.sistema.AvisoSistemaOta -a com.sios.SISTEMA_OTA \
    --es fase "$1" >/dev/null 2>&1 || true
}

# resultado <actualizado|vuelta_atras> [clave=valor…]: se guarda hasta que la app lo ve.
resultado() {
  r=$1; shift
  { echo "fase=$r"; for kv in "$@"; do echo "$kv"; done; echo "cuando=$(date +%s)"; } > "$PRIV/resultado"
  registro "resultado: $r $*"
}

error() {
  registro "error: $*"
  rm -f "$PAQ/carga.bin" "$PAQ/carga.bin.tmp" "$PRIV/instalando"
  estado error "mensaje=$*"
  avisar error
}

agente_responde() {
  [ "$(curl -s -m 4 -o /dev/null -w '%{http_code}' "$AGENTE" 2>/dev/null)" = 200 ]
}

# ---------- vuelta atrás y confirmación (tras reiniciar en la ranura nueva) ----------

VIGILAR=""
arrancar() {
  mkdir -p "$PRIV" && chmod 0700 "$PRIV"
  ACTUAL=$(bootctl get-current-slot 2>/dev/null)
  if [ -f "$PRIV/pendiente" ]; then
    P="$PRIV/pendiente"
    DESTINO=$(valor "$P" destino); ORIGEN=$(valor "$P" origen)
    if [ "$(valor "$P" boot_id_lista)" = "$BOOT_ID" ]; then
      :  # instalada en este mismo arranque: falta reiniciar (el ayudante se ha reiniciado sin más)
    elif [ "$ACTUAL" = "$DESTINO" ]; then
      if bootctl is-slot-marked-successful "$ACTUAL" >/dev/null 2>&1; then
        # Ya estaba confirmada (no debería pasar): no hay nada que vigilar.
        resultado actualizado "compilacion=$(valor "$P" compilacion)" "aviso=confirmada_antes"
        rm -f "$P"
      else
        # Que vold no la dé por buena al terminar de arrancar: la confirmación es nuestra.
        setprop persist.vold.dont_commit_checkpoint 1
        if [ "$(valor "$P" boot_id)" != "$BOOT_ID" ]; then
          poner "$P" arranques $(( $(valor "$P" arranques) + 1 ))
          poner "$P" boot_id "$BOOT_ID"
        fi
        registro "ranura nueva sin confirmar (arranque $(valor "$P" arranques) de $MAX_ARRANQUES)"
        VIGILAR=1
        if [ "$(valor "$P" arranques)" -gt "$MAX_ARRANQUES" ]; then volver arranques; fi
      fi
    elif [ "$(getprop ro.sios.compilacion)" = "$(valor "$P" compilacion_origen)" ]; then
      # Estamos otra vez en la versión de antes: la nueva no llegó a arrancar bien.
      resultado vuelta_atras "compilacion=$(valor "$P" compilacion)" "version=$(valor "$P" version)" \
        "motivo=$(valor "$P" motivo | grep . || echo no_arranca)"
      rm -f "$P"
    else
      rm -f "$P"  # se instaló otra cosa por cable: lo pendiente ya no vale
    fi
  fi
  if [ -z "$VIGILAR" ] && [ "$(getprop persist.vold.dont_commit_checkpoint)" = 1 ]; then
    setprop persist.vold.dont_commit_checkpoint 0
  fi
}

# volver <motivo>: vuelve a la versión anterior y reinicia.
volver() {
  P="$PRIV/pendiente"
  registro "vuelta atrás a la ranura $ORIGEN: $1"
  poner "$P" motivo "$1"
  estado volviendo "motivo=$1"
  # Los datos escritos en este arranque no se confirman (vold los deshace si el móvil usa checkpoint).
  bootctl set-active-boot-slot "$ORIGEN"
  bootctl set-slot-as-unbootable "$DESTINO" 2>/dev/null
  sync
  svc power reboot sios-vuelta-atras 2>/dev/null || setprop sys.powerctl reboot,sios-vuelta-atras
  sleep 120
  setprop sys.powerctl reboot,sios-vuelta-atras
  exit 0
}

T_ARRANCADO=""; T_DESBLOQUEO=""
vigilar() {
  P="$PRIV/pendiente"
  t=$(segundos)
  if [ "$(getprop sys.boot_completed)" != 1 ]; then
    [ "$t" -gt "$ESPERA_SISTEMA" ] && volver sistema
    estado comprobando paso=sistema "compilacion=$(valor "$P" compilacion)"
    return
  fi
  [ -n "$T_ARRANCADO" ] || T_ARRANCADO=$t
  # La app ha arrancado en este arranque: su latido trae el arranque que le dimos en «estado».
  if ! grep -q "^arranque=$BOOT_ID\$" "$CANAL/latido" 2>/dev/null; then
    [ $((t - T_ARRANCADO)) -gt "$ESPERA_APP" ] && volver app
    estado comprobando paso=app "compilacion=$(valor "$P" compilacion)"
    return
  fi
  if [ "$(valor "$P" servicio_antes)" = 1 ]; then
    if [ "$(getprop sys.user.0.ce_available)" != true ]; then
      # Sin desbloquear no puede arrancar el agente: se espera (sin plazo; tampoco se confirma).
      estado comprobando paso=desbloqueo "compilacion=$(valor "$P" compilacion)"
      return
    fi
    [ -n "$T_DESBLOQUEO" ] || T_DESBLOQUEO=$t
    if ! agente_responde; then
      [ $((t - T_DESBLOQUEO)) -gt "$ESPERA_AGENTE" ] && volver agente
      estado comprobando paso=agente "compilacion=$(valor "$P" compilacion)"
      return
    fi
  fi
  confirmar
}

INTENTOS_CONFIRMAR=0
confirmar() {
  P="$PRIV/pendiente"
  setprop persist.vold.dont_commit_checkpoint 0
  vdc checkpoint commitChanges >/dev/null 2>&1  # con checkpoint, esto mismo da el arranque por bueno
  bootctl mark-boot-successful >/dev/null 2>&1
  if bootctl is-slot-marked-successful "$ACTUAL" >/dev/null 2>&1; then
    resultado actualizado "compilacion=$(valor "$P" compilacion)" "version=$(valor "$P" version)"
    rm -f "$P"
    VIGILAR=""
    estado libre
    avisar actualizado
  else
    INTENTOS_CONFIRMAR=$((INTENTOS_CONFIRMAR + 1))
    registro "no se pudo confirmar la ranura $ACTUAL (intento $INTENTOS_CONFIRMAR)"
    # Si el gestor de arranque no deja marcarla, se vuelve a la anterior (que sí funciona).
    [ "$INTENTOS_CONFIRMAR" -ge 5 ] && volver confirmar
  fi
}

# ---------- instalación en la otra ranura ----------

cabeceras() {  # las propiedades de la carga para update_engine, una por línea
  O="$PRIV/instalando"
  printf 'FILE_HASH=%s\nFILE_SIZE=%s\nMETADATA_HASH=%s\nMETADATA_SIZE=%s' \
    "$(valor "$O" FILE_HASH)" "$(valor "$O" FILE_SIZE)" "$(valor "$O" METADATA_HASH)" "$(valor "$O" METADATA_SIZE)"
}

log_actual() { ls -t "$UE_LOG" 2>/dev/null | grep '^update_engine' | head -n 1; }

lanzar_update_engine() {
  O="$PRIV/instalando"
  L=$(log_actual)
  poner "$O" log "$L"
  poner "$O" lineas "$( [ -n "$L" ] && wc -l < "$UE_LOG/$L" | tr -d ' ' || echo 0)"
  poner "$O" boot_id "$BOOT_ID"
  salida=$(update_engine_client --update --payload="file://$PAQ/carga.bin" --offset=0 \
    --size="$(valor "$O" tamano)" --headers="$(cabeceras)" 2>&1)
  if [ $? -ne 0 ]; then
    error "update_engine no la acepta: $(echo "$salida" | grep -v '^$' | tail -n 1 | cut -c1-200)"
    return 1
  fi
  return 0
}

instalar() {
  O="$PRIV/orden"
  case "$FASE" in libre|error|"") ;; *) registro "orden de instalar ignorada (fase $FASE)"; return ;; esac
  archivo=$(valor "$O" archivo)
  # Solo descargas de la propia app, nada de rutas raras.
  case "$archivo" in "$CANAL"/*) ;; *) error "ruta no válida"; return ;; esac
  case "$archivo" in *..*) error "ruta no válida"; return ;; esac
  [ -f "$archivo" ] || { error "no está la descarga"; return; }
  tam=$(valor "$O" tamano)
  [ "$(stat -c %s "$archivo")" = "$tam" ] || { error "la descarga no está completa"; return; }
  estado comprobando_descarga
  if [ "$(sha256sum "$archivo" | cut -d' ' -f1)" != "$(valor "$O" sha256)" ]; then
    rm -f "$archivo"
    error "la descarga no coincide con la huella firmada"
    return
  fi
  libre_kb=$(df -k /data 2>/dev/null | tail -n 1 | awk '{print $4}')
  if [ -n "$libre_kb" ] && [ $((libre_kb / 1024)) -lt $((tam / 1048576 + 300)) ]; then
    error "sin_espacio"; return
  fi
  estado copiando
  mkdir -p "$PAQ"
  chown system:cache "$PAQ" 2>/dev/null; chmod 0770 "$PAQ"
  rm -f "$PAQ/carga.bin"
  # Se copia (no se puede mover: cada carpeta va cifrada con su clave) y se borra la descarga.
  if ! cp "$archivo" "$PAQ/carga.bin.tmp"; then rm -f "$PAQ/carga.bin.tmp"; error "sin_espacio"; return; fi
  mv -f "$PAQ/carga.bin.tmp" "$PAQ/carga.bin"
  rm -f "$archivo"
  chown system:cache "$PAQ/carga.bin" 2>/dev/null; chmod 0660 "$PAQ/carga.bin"
  chcon u:object_r:ota_package_file:s0 "$PAQ" "$PAQ/carga.bin" 2>/dev/null
  {
    for k in id compilacion version tamano FILE_HASH FILE_SIZE METADATA_HASH METADATA_SIZE; do
      echo "$k=$(valor "$O" "$k")"
    done
    echo "servicio_antes=$(agente_responde && echo 1 || echo 0)"
  } > "$PRIV/instalando"
  registro "instalando $(valor "$O" compilacion) en la otra ranura"
  lanzar_update_engine && estado instalando progreso=0
}

# Mira cómo va update_engine: su marca de «terminado» y su registro (no se le puede escuchar: SELinux
# no le deja llamar a este dominio, así que se lee lo que escribe).
seguir() {
  O="$PRIV/instalando"
  if [ "$(cat "$UE_PREFS/update-completed-on-boot-id" 2>/dev/null)" = "$BOOT_ID" ]; then
    lista; return
  fi
  if [ "$(valor "$O" boot_id)" != "$BOOT_ID" ]; then
    # Se reinició a medias: update_engine retoma la misma carga desde donde iba.
    registro "retomando la instalación tras reiniciar"
    lanzar_update_engine || return
  fi
  L=$(valor "$O" log)
  [ -n "$L" ] || { L=$(log_actual); poner "$O" log "$L"; poner "$O" lineas 0; }
  nuevas=$( [ -n "$L" ] && tail -n +"$(( $(valor "$O" lineas) + 1 ))" "$UE_LOG/$L" 2>/dev/null)
  codigo=$(echo "$nuevas" | grep -o 'with code ErrorCode::k[A-Za-z]*' | grep -v 'kSuccess$' | tail -n 1 | sed 's/.*ErrorCode:://')
  if [ -n "$codigo" ]; then
    rm -f "$PAQ/carga.bin" "$PRIV/instalando"
    estado error "codigo=$codigo" "mensaje=$codigo"
    registro "update_engine terminó con $codigo"
    avisar error
    return
  fi
  pct=$(echo "$nuevas" | grep -o 'overall progress [0-9]*%' | tail -n 1 | grep -o '[0-9]*')
  if echo "$nuevas" | grep -q 'ActionProcessor: starting FilesystemVerifierAction'; then
    estado verificando "progreso=${pct:-100}" "compilacion=$(valor "$O" compilacion)"
  else
    estado instalando "progreso=${pct:-0}" "compilacion=$(valor "$O" compilacion)"
  fi
}

lista() {
  O="$PRIV/instalando"
  # update_engine ya ha dejado activa la otra ranura para el próximo arranque.
  destino=$(bootctl get-active-boot-slot 2>/dev/null)
  [ -n "$destino" ] && [ "$destino" != "$ACTUAL" ] || destino=$((1 - ACTUAL))
  {
    echo "destino=$destino"
    echo "origen=$ACTUAL"
    echo "compilacion=$(valor "$O" compilacion)"
    echo "version=$(valor "$O" version)"
    echo "compilacion_origen=$(getprop ro.sios.compilacion)"
    echo "servicio_antes=$(valor "$O" servicio_antes)"
    echo "arranques=0"
    echo "boot_id="
    echo "boot_id_lista=$BOOT_ID"
  } > "$PRIV/pendiente"
  rm -f "$PAQ/carga.bin" "$O"
  registro "lista en la ranura $destino: falta reiniciar"
  estado lista "compilacion=$(valor "$PRIV/pendiente" compilacion)"
  avisar lista
}

reiniciar() {
  [ "$FASE" = lista ] && [ -f "$PRIV/pendiente" ] || { registro "reiniciar sin nada pendiente"; return; }
  estado reiniciando "compilacion=$(valor "$PRIV/pendiente" compilacion)"
  sync
  svc power reboot sios-actualizacion 2>/dev/null || setprop sys.powerctl reboot,sios-actualizacion
}

cancelar() {
  case "$FASE" in
    instalando|verificando|copiando)
      update_engine_client --cancel >/dev/null 2>&1
      rm -f "$PAQ/carga.bin" "$PRIV/instalando" ;;
    lista)
      # Deja la ranura actual como la del próximo arranque y borra la instantánea.
      update_engine_client --reset_status >/dev/null 2>&1
      rm -f "$PRIV/pendiente" ;;
  esac
  registro "cancelada"
  estado libre
}

procesar_orden() {
  [ -f "$CANAL/orden" ] || return 0
  # Solo vale si la ha escrito la app: el dueño es el de su carpeta.
  if [ "$(stat -c %u "$CANAL/orden")" != "$(stat -c %u "$CANAL")" ]; then rm -f "$CANAL/orden"; return 0; fi
  cp "$CANAL/orden" "$PRIV/orden" && rm -f "$CANAL/orden"
  case "$(valor "$PRIV/orden" accion)" in
    instalar) instalar ;;
    reiniciar) reiniciar ;;
    cancelar) cancelar ;;
    visto) rm -f "$PRIV/resultado"; ULTIMO_ESTADO=""; estado "${FASE:-libre}" ;;
  esac
}

# ---------- bucle ----------

arrancar
FASE=libre
if [ -z "$VIGILAR" ] && [ -f "$PRIV/instalando" ]; then FASE=instalando; fi
[ -z "$VIGILAR" ] && [ -f "$PRIV/pendiente" ] && FASE=lista
while true; do
  if [ -n "$VIGILAR" ]; then
    vigilar
  else
    procesar_orden
    case "$FASE" in
      instalando|verificando) seguir ;;
      libre) estado libre ;;
      lista) estado lista "compilacion=$(valor "$PRIV/pendiente" compilacion)" ;;
    esac
  fi
  case "$FASE" in instalando|verificando|copiando|comprobando*) sleep "$PAUSA" ;; *) sleep $((PAUSA * 2)) ;; esac
done
