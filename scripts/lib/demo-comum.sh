#!/usr/bin/env bash
# Funcoes compartilhadas por scripts/demo-subir.sh e scripts/demo-parar.sh.
# Este arquivo so e carregado com "source"; nao e executado diretamente.
#
# Compativel com Git Bash (Windows), Linux e macOS (bash 3.2+).

DEMO_DIR="${RAIZ}/.demo"

# Portas da demonstracao. A do Postgres do core usa o mesmo nome do docker-compose.yml.
POSTGRES_CORE_PORT="${POSTGRES_CORE_PORT:-5434}"
DEMO_PORTA_CORE="${DEMO_PORTA_CORE:-8083}"
DEMO_PORTA_RELATORIOS="${DEMO_PORTA_RELATORIOS:-8082}"
DEMO_PORTA_GATEWAY="${DEMO_PORTA_GATEWAY:-8090}"

# Portas de infraestrutura fixadas pelo docker-compose.yml (nao sao configuraveis).
PORTA_POSTGRES_RELATORIOS=5433
PORTA_REDIS=6379
PORTA_RABBITMQ=5672
PORTA_RABBITMQ_MGMT=15672

SERVICOS_DEMO="gateway relatorios core"

info() { printf '[demo] %s\n' "$*"; }
aviso() { printf '[demo] AVISO: %s\n' "$*" >&2; }
erro() { printf '[demo] ERRO: %s\n' "$*" >&2; }

# Verdadeiro (0) quando algo aceita conexao TCP em 127.0.0.1:<porta>.
porta_ocupada() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

# Melhor esforco para dizer qual processo ocupa a porta (imprime nada se nao descobrir).
quem_usa_porta() {
  local porta="$1" pid=""
  if command -v netstat >/dev/null 2>&1 && [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
    pid=$(netstat -ano 2>/dev/null | tr -d '\r' | awk -v p=":${porta}" '$1=="TCP" && $2 ~ p"$" && $4=="LISTENING" {print $5; exit}')
    if [[ -n "$pid" ]]; then
      local nome
      nome=$(tasklist //FI "PID eq ${pid}" //FO CSV //NH 2>/dev/null | tr -d '\r' | cut -d, -f1 | tr -d '"')
      echo "PID ${pid} ${nome}"
    fi
  elif command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"${porta}" -sTCP:LISTEN 2>/dev/null | awk 'NR==2{print "PID " $2 " " $1}'
  elif command -v ss >/dev/null 2>&1; then
    ss -ltnp "sport = :${porta}" 2>/dev/null | awk 'NR==2{print $NF}'
  fi
}

eh_windows() {
  [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]
}

# Arquivo de PID de um servico: contem "<pid> <winpid>" (winpid so existe no Git Bash).
arquivo_pid() { echo "${DEMO_DIR}/$1.pid"; }

# Verdadeiro quando o servico tem PID registrado e o processo ainda existe.
servico_vivo() {
  local arq pid
  arq=$(arquivo_pid "$1")
  [[ -f "$arq" ]] || return 1
  pid=$(cut -d' ' -f1 "$arq")
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

# Encerra um processo e seus filhos (taskkill /T no Windows, kill nos demais).
matar_arvore() {
  local pid="$1" winpid="$2"
  if eh_windows && command -v taskkill >/dev/null 2>&1 && [[ -n "$winpid" ]]; then
    taskkill //PID "$winpid" //F //T >/dev/null 2>&1 || true
  else
    if command -v pkill >/dev/null 2>&1; then
      pkill -TERM -P "$pid" 2>/dev/null || true
    fi
    kill "$pid" 2>/dev/null || true
  fi
  local i
  for i in 1 2 3 4 5 6 7 8 9 10; do
    kill -0 "$pid" 2>/dev/null || return 0
    sleep 1
  done
  kill -9 "$pid" 2>/dev/null || true
}

# Para um servico da demo pelo PID registrado em .demo/ e remove o arquivo de PID.
parar_servico() {
  local arq pid winpid
  arq=$(arquivo_pid "$1")
  [[ -f "$arq" ]] || return 0
  pid=$(cut -d' ' -f1 "$arq")
  winpid=$(cut -d' ' -f2 -s "$arq")
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    info "Parando $1 (PID ${pid})"
    matar_arvore "$pid" "$winpid"
  fi
  rm -f "$arq"
}

parar_servicos_demo() {
  local s
  for s in $SERVICOS_DEMO; do
    parar_servico "$s"
  done
}

# Espera a porta ficar livre (ate <segundos>); retorna 1 se continuar ocupada.
esperar_porta_livre() {
  local porta="$1" limite="${2:-15}" i
  for ((i = 0; i < limite; i++)); do
    porta_ocupada "$porta" || return 0
    sleep 1
  done
  return 1
}
