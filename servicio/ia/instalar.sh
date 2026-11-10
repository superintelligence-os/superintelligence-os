#!/data/data/com.termux/files/usr/bin/bash
# Instala lo que hace falta para una IA la primera vez que se elige (así la ROM no carga con todo):
#   claude  Claude con la suscripción: Claude Code dentro del Debian de proot (no tiene versión
#           para Android). El agente lo llama con «claude» (termux/claude); ia-claude solo marca
#           que ya está instalado.
#   codex   ChatGPT con la suscripción: Codex CLI (binario estático) dentro del Debian de proot.
#   gemini  Gemini con la cuenta de Google: Gemini CLI (Node) en Termux.
#   llama   Modelo local: llama.cpp (llama-server) de los paquetes de Termux.
# Escribe el progreso en una línea por paso, como clave («@debian», «@claude»…) que la app traduce
# al idioma del móvil; termina con «listo» o con «error: …».
set -e
B=$PREFIX/bin
# Claude Code y Codex viven en un Debian de proot: se instala la primera vez que hace falta.
debian() {
  proot-distro login debian -- true >/dev/null 2>&1 && return 0
  echo "@debian"
  proot-distro install debian >/dev/null
}
case "$1" in
  claude)
    debian
    echo "@claude"
    proot-distro login debian -- bash -c '
      set -e
      export DEBIAN_FRONTEND=noninteractive
      command -v curl >/dev/null || { apt-get update -qq >/dev/null && apt-get install -y -qq curl ca-certificates >/dev/null; }
      if [ ! -x /root/.local/bin/claude ]; then
        curl -fsSL https://claude.ai/install.sh -o /tmp/instalar-claude.sh
        bash /tmp/instalar-claude.sh >/dev/null
      fi
      mkdir -p /root/.claude
      [ -f /root/.claude/settings.json ] || echo "{\"env\":{\"MCP_TOOL_TIMEOUT\":\"900000\"}}" > /root/.claude/settings.json
      /root/.local/bin/claude --version'
    cat > $B/ia-claude <<'W'
#!/data/data/com.termux/files/usr/bin/bash
# Claude Code ya está instalado en el Debian de proot: el agente lo usa con «claude».
exec claude "$@"
W
    chmod +x $B/ia-claude
    ;;
  codex)
    debian
    echo "@codex"
    proot-distro login debian -- bash -c '
      set -e; mkdir -p /root/.local/bin; cd /tmp
      curl -fsSL -o codex.tar.gz https://github.com/openai/codex/releases/latest/download/codex-aarch64-unknown-linux-musl.tar.gz
      tar xzf codex.tar.gz; mv -f codex-aarch64-unknown-linux-musl /root/.local/bin/codex; chmod +x /root/.local/bin/codex; rm -f codex.tar.gz
      /root/.local/bin/codex --version'
    cat > $B/ia-codex <<'W'
#!/data/data/com.termux/files/usr/bin/bash
# Codex CLI (Debian de proot) con su propia carpeta de sesión dentro de ~/sios/ia/codex.
H=/data/data/com.termux/files/home
exec proot-distro login debian --shared-tmp -- \
  env CODEX_HOME=$H/sios/ia/codex/home bash -c 'mkdir -p "$CODEX_HOME" /root/agente && cd /root/agente && exec /root/.local/bin/codex "$@"' codex "$@"
W
    chmod +x $B/ia-codex
    ;;
  gemini)
    echo "@gemini"
    npm install -g --no-fund --no-audit @google/gemini-cli >/dev/null 2>&1
    cat > $B/ia-gemini <<'W'
#!/data/data/com.termux/files/usr/bin/bash
# Gemini CLI con su propia carpeta (ajustes, sesiones e inicio de sesión) en ~/sios/ia/gemini.
export HOME_REAL=$HOME HOME=$HOME/sios/ia/gemini
export TERMUX_VERSION=${TERMUX_VERSION:-0.118} GEMINI_SYSTEM_MD=$HOME/sistema.md NO_BROWSER=true GEMINI_CLI_NO_RELAUNCH=true
mkdir -p $HOME/trabajo && cd $HOME/trabajo
exec node $PREFIX/bin/gemini "$@"
W
    chmod +x $B/ia-gemini
    TERMUX_VERSION=0.118 node $B/gemini --version
    ;;
  kimi)
    echo "@kimi"
    npm install -g --no-fund --no-audit @moonshot-ai/kimi-code >/dev/null 2>&1
    cat > $B/ia-kimi <<'W'
#!/data/data/com.termux/files/usr/bin/bash
# Kimi Code con su propia carpeta (sesión, ajustes y agente) en ~/sios/ia/kimi.
export HOME=$HOME/sios/ia/kimi TERMUX_VERSION=${TERMUX_VERSION:-0.118}
mkdir -p $HOME/trabajo && cd $HOME/trabajo
exec node $PREFIX/bin/kimi "$@"
W
    chmod +x $B/ia-kimi
    ;;
  llama)
    echo "@llama"
    pkg install -y llama-cpp >/dev/null 2>&1 || apt-get install -y llama-cpp >/dev/null 2>&1
    command -v llama-server >/dev/null
    ;;
  *) echo "error: ¿qué instalo? (codex, gemini, kimi, llama)"; exit 2 ;;
esac
echo listo
