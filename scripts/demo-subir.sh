#!/usr/bin/env bash
# Sobe TODA a demonstracao do AlocaCreche com um comando: infraestrutura (Postgres x2, Redis,
# RabbitMQ), core, relatorios e gateway, mais os usuarios ADMIN, GESTOR e ATENDENTE.
#
# Uso: scripts/demo-subir.sh [--manter] [--com-job] [--sem-build]
#   --manter     nao zera os dados: pula "docker compose down -v" e reaproveita banco, usuarios e as
#                credenciais de .demo/credenciais.txt
#   --com-job    liga o job de manutencao de matriculas (intervalo de 20 s); o padrao e desligado
#   --sem-build  nao recompila; usa os jars ja existentes em */target
#
# Portas (variaveis opcionais): POSTGRES_CORE_PORT (5434), DEMO_PORTA_CORE (8083),
# DEMO_PORTA_RELATORIOS (8082), DEMO_PORTA_GATEWAY (8090).
#
# Estado de execucao (PIDs, logs, credenciais) fica em .demo/, ignorado pelo git. O segredo JWT e a
# senha sao gerados a cada execucao e nunca vao para arquivo versionado nem para o .env.

set -Eeuo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ"
source "$RAIZ/scripts/lib/demo-comum.sh"

MANTER=0
COM_JOB=0
SEM_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --manter) MANTER=1 ;;
    --com-job) COM_JOB=1 ;;
    --sem-build) SEM_BUILD=1 ;;
    -h | --help)
      sed -n '2,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      erro "Opcao desconhecida: $arg (use --manter, --com-job, --sem-build)"
      exit 2
      ;;
  esac
done

export POSTGRES_CORE_PORT
INICIO=$SECONDS
MUNICIPIO_ID=1
EMAIL_ADMIN="admin@demo.local"
EMAIL_GESTOR="gestor@demo.local"
EMAIL_ATENDENTE="atendente@demo.local"
URL_GATEWAY="http://localhost:${DEMO_PORTA_GATEWAY}"
DB_URL_CORE="jdbc:postgresql://localhost:${POSTGRES_CORE_PORT}/alocacreche"
JWT_SECRET=""
SENHA_DEMO=""
ARMADO=0

# Em qualquer saida com erro (ou Ctrl+C) derruba o que ja foi subido, sem apagar dados.
limpar_ao_sair() {
  local rc=$?
  trap - EXIT INT TERM
  if [[ $ARMADO -eq 1 && $rc -ne 0 ]]; then
    erro "A subida falhou (codigo ${rc}); derrubando o que foi iniciado."
    parar_servicos_demo
    docker compose down >/dev/null 2>&1 || true
  fi
  exit $rc
}
trap limpar_ao_sair EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

falhar() {
  erro "$*"
  exit 1
}

# Mostra as ultimas linhas do log de um servico e encerra com erro.
falhar_servico() {
  local nome="$1" motivo="$2"
  erro "${motivo} (${nome})"
  if [[ -f "$DEMO_DIR/${nome}.log" ]]; then
    erro "Ultimas linhas de .demo/${nome}.log:"
    tail -n 25 "$DEMO_DIR/${nome}.log" >&2 || true
  fi
  exit 1
}

# ---------------------------------------------------------------------------------------------
# 1. Pre-checagens (nada e alterado aqui)
# ---------------------------------------------------------------------------------------------
pre_checagens() {
  info "Pre-checagens"
  command -v docker >/dev/null 2>&1 || falhar "Docker nao encontrado no PATH."
  docker info >/dev/null 2>&1 || falhar "O Docker nao esta rodando. Inicie o Docker Desktop (ou o daemon) e tente de novo."
  docker compose version >/dev/null 2>&1 || falhar "O plugin 'docker compose' nao esta disponivel."
  command -v java >/dev/null 2>&1 || falhar "Java nao encontrado no PATH (o projeto exige o JDK 25)."
  local versao
  versao=$(java -version 2>&1 | head -n 1)
  [[ "$versao" == *'version "25'* ]] || falhar "O projeto exige Java 25; encontrado: ${versao}"
  command -v mvn >/dev/null 2>&1 || falhar "Maven (mvn) nao encontrado no PATH."
  command -v curl >/dev/null 2>&1 || falhar "curl nao encontrado no PATH."

  local erros=0 par nome porta dono dica
  # Portas dos servicos Java: livres, ou ocupadas por um processo da propria demo (sera parado).
  for par in "core:${DEMO_PORTA_CORE}:DEMO_PORTA_CORE" "relatorios:${DEMO_PORTA_RELATORIOS}:DEMO_PORTA_RELATORIOS" \
    "gateway:${DEMO_PORTA_GATEWAY}:DEMO_PORTA_GATEWAY"; do
    nome=$(echo "$par" | cut -d: -f1)
    porta=$(echo "$par" | cut -d: -f2)
    if porta_ocupada "$porta" && ! servico_vivo "$nome"; then
      dono=$(quem_usa_porta "$porta")
      erro "A porta ${porta} (${nome}) esta ocupada por outro processo ${dono:+(${dono})}. Libere-a ou use $(echo "$par" | cut -d: -f3)=<outra porta>."
      erros=1
    fi
  done
  # Portas de infraestrutura: livres, ou ocupadas por um container desta propria composicao.
  for par in "postgres-core:${POSTGRES_CORE_PORT}" "postgres-relatorios:${PORTA_POSTGRES_RELATORIOS}" \
    "redis:${PORTA_REDIS}" "rabbitmq:${PORTA_RABBITMQ}" "rabbitmq:${PORTA_RABBITMQ_MGMT}"; do
    nome="${par%%:*}"
    porta="${par##*:}"
    if porta_ocupada "$porta"; then
      if [[ -n "$(docker compose ps -q --status running "$nome" 2>/dev/null)" ]]; then
        continue
      fi
      dono=$(quem_usa_porta "$porta")
      dica=""
      if [[ "$nome" == "postgres-core" ]]; then
        dica=" Use POSTGRES_CORE_PORT=<outra porta>."
      fi
      erro "A porta ${porta} (${nome}) esta ocupada por outro processo ${dono:+(${dono})}.${dica}"
      erros=1
    fi
  done
  [[ $erros -eq 0 ]] || exit 1
}

# ---------------------------------------------------------------------------------------------
# 2. Credenciais (segredo JWT e senha)
# ---------------------------------------------------------------------------------------------
# Gera <n> caracteres alfanumericos aleatorios (openssl se existir, senao /dev/urandom).
gerar_aleatorio() {
  local n="$1" saida=""
  if command -v openssl >/dev/null 2>&1; then
    saida=$(openssl rand -hex "$n" | cut -c1-"$n")
  else
    saida=$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c "$n" || true)
  fi
  [[ ${#saida} -eq $n ]] || falhar "Nao foi possivel gerar valores aleatorios."
  printf '%s' "$saida"
}

ler_credencial() {
  grep "^$1=" "$DEMO_DIR/credenciais.txt" | head -n 1 | cut -d= -f2-
}

preparar_credenciais() {
  local arq="$DEMO_DIR/credenciais.txt"
  chmod 700 "$DEMO_DIR" 2>/dev/null || true
  if [[ $MANTER -eq 1 && -f "$arq" ]]; then
    JWT_SECRET=$(ler_credencial JWT_SECRET)
    SENHA_DEMO=$(ler_credencial SENHA_DEMO)
    if [[ ${#JWT_SECRET} -ge 32 && -n "$SENHA_DEMO" ]]; then
      info "Reaproveitando as credenciais de .demo/credenciais.txt"
      return
    fi
    aviso ".demo/credenciais.txt esta incompleto; gerando credenciais novas."
  fi
  JWT_SECRET=$(gerar_aleatorio 48)
  SENHA_DEMO=$(gerar_aleatorio 20)
  # Permissao restrita antes de gravar qualquer valor.
  : >"$arq"
  chmod 600 "$arq" 2>/dev/null || true
  {
    echo "JWT_SECRET=${JWT_SECRET}"
    echo "SENHA_DEMO=${SENHA_DEMO}"
  } >"$arq"
}

# ---------------------------------------------------------------------------------------------
# 3. Infraestrutura
# ---------------------------------------------------------------------------------------------
subir_infra() {
  : >"$DEMO_DIR/docker.log"
  if [[ $MANTER -eq 0 ]]; then
    info "Zerando a infraestrutura (docker compose down -v)"
    docker compose down -v >>"$DEMO_DIR/docker.log" 2>&1 || falhar "docker compose down -v falhou (veja .demo/docker.log)"
  fi
  info "Subindo Postgres (core e relatorios), Redis e RabbitMQ"
  docker compose up -d postgres-core postgres-relatorios redis rabbitmq >>"$DEMO_DIR/docker.log" 2>&1 \
    || { tail -n 15 "$DEMO_DIR/docker.log" >&2; falhar "docker compose up falhou (veja .demo/docker.log)"; }
}

esperar_infra_saudavel() {
  info "Esperando a infraestrutura ficar saudavel"
  local svc id estado i par db
  for svc in postgres-core postgres-relatorios redis rabbitmq; do
    estado=""
    for ((i = 0; i < 90; i++)); do
      id=$(docker compose ps -q "$svc" 2>/dev/null || true)
      if [[ -n "$id" ]]; then
        estado=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id" 2>/dev/null || true)
      fi
      [[ "$estado" == "healthy" ]] && break
      sleep 2
    done
    [[ "$estado" == "healthy" ]] || falhar "O servico ${svc} nao ficou saudavel em 180 s (estado: ${estado:-desconhecido}). Veja: docker compose logs ${svc}"
  done
  # O healthcheck do Postgres pode passar no servidor temporario da inicializacao (so socket local);
  # a conexao TCP confirma que os scripts de init (role alocacreche_app) ja terminaram.
  for par in "postgres-core:alocacreche" "postgres-relatorios:alocacreche_relatorios"; do
    svc="${par%%:*}"
    db="${par##*:}"
    for ((i = 0; i < 60; i++)); do
      if docker compose exec -T "$svc" psql -h 127.0.0.1 -U alocacreche -d "$db" -tAc \
        "SELECT 1 FROM pg_roles WHERE rolname='alocacreche_app'" 2>/dev/null | grep -q 1; then
        break
      fi
      sleep 1
    done
    [[ $i -lt 60 ]] || falhar "O Postgres ${svc} nao terminou a inicializacao (role alocacreche_app ausente)."
  done
}

# Executa SQL no Postgres do core como usuario bootstrap (ignora RLS, como no roteiro).
psql_core() {
  docker compose exec -T -e PGOPTIONS="-c client_min_messages=warning" postgres-core psql -U alocacreche -d alocacreche -v ON_ERROR_STOP=1 "$@"
}

# ---------------------------------------------------------------------------------------------
# 4. Build
# ---------------------------------------------------------------------------------------------
compilar() {
  info "Compilando os tres modulos (uma vez)"
  mvn -q -DskipTests package -pl alocacreche-core,alocacreche-relatorios,alocacreche-gateway -am \
    -Dmaven.compiler.proc=full >"$DEMO_DIR/build.log" 2>&1 \
    || { tail -n 30 "$DEMO_DIR/build.log" >&2; falhar "mvn package falhou (veja .demo/build.log)"; }
}

jar_do_modulo() {
  local j
  for j in "$RAIZ/alocacreche-$1/target/"alocacreche-"$1"-*.jar; do
    if [[ -f "$j" && "$j" != *-sources.jar && "$j" != *.original ]]; then
      echo "$j"
      return 0
    fi
  done
  return 1
}

# ---------------------------------------------------------------------------------------------
# 5. Servicos Java, cada um com ambiente isolado
# ---------------------------------------------------------------------------------------------
# Remove do ambiente do subshell toda variavel que possa vir do shell do usuario ou de outro
# servico (SERVER_PORT, SPRING_*, SA_TOKEN_*, ALOCACRECHE_*), para nada vazar entre os servicos.
limpar_ambiente_servico() {
  local v
  for v in $(compgen -e | grep -E '^(SERVER_PORT|SPRING_|SA_TOKEN_|ALOCACRECHE_|POSTGRES_|JAVA_TOOL_OPTIONS)' || true); do
    unset "$v"
  done
}

# Uso: iniciar_servico <modulo> <VAR=valor>...  (exporta so o que foi passado)
iniciar_servico() {
  local modulo="$1" jar="" log
  shift
  log="$DEMO_DIR/${modulo}.log"
  jar=$(jar_do_modulo "$modulo" || true)
  if [[ -n "$jar" ]] && grep -aq 'BOOT-INF' "$jar"; then
    (
      limpar_ambiente_servico
      export "$@"
      exec java -jar "$jar"
    ) >"$log" 2>&1 &
  else
    aviso "Jar executavel de ${modulo} nao encontrado; usando mvn spring-boot:run"
    (
      limpar_ambiente_servico
      export "$@"
      cd "$RAIZ/alocacreche-${modulo}"
      exec mvn -q spring-boot:run
    ) >"$log" 2>&1 &
  fi
  local pid=$! winpid=""
  if eh_windows; then
    winpid=$(ps -p "$pid" 2>/dev/null | awk 'NR==2{print $4}' || true)
  fi
  echo "${pid} ${winpid}" >"$(arquivo_pid "$modulo")"
}

# Espera o log registrar "Started" e o health responder 200; falha se o processo morrer ou estourar o tempo.
esperar_servico() {
  local nome="$1" porta="$2" limite="${3:-150}" i code
  for ((i = 0; i < limite; i++)); do
    if ! servico_vivo "$nome"; then
      falhar_servico "$nome" "O processo terminou antes de subir"
    fi
    if grep -q 'Started Aloca' "$DEMO_DIR/${nome}.log" 2>/dev/null; then
      code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://localhost:${porta}/actuator/health" || true)
      [[ "$code" == "200" ]] && return 0
    fi
    sleep 1
  done
  falhar_servico "$nome" "Nao respondeu em ${limite} s na porta ${porta}"
}

# Uso: iniciar_core [ddl-auto]  (sem argumento, mantem o padrao "update" do application.yml)
iniciar_core() {
  local ddl="${1:-}" extras=()
  if [[ -n "$ddl" ]]; then
    extras+=("SPRING_JPA_HIBERNATE_DDL_AUTO=${ddl}")
  fi
  if [[ $COM_JOB -eq 1 ]]; then
    extras+=("ALOCACRECHE_MATRICULA_MANUTENCAO_HABILITADO=true" "ALOCACRECHE_MATRICULA_MANUTENCAO_INTERVALO_MS=20000")
  else
    extras+=("ALOCACRECHE_MATRICULA_MANUTENCAO_HABILITADO=false")
  fi
  iniciar_servico core \
    "SERVER_PORT=${DEMO_PORTA_CORE}" \
    "SPRING_DATASOURCE_URL=${DB_URL_CORE}" \
    "SA_TOKEN_JWT_SECRET=${JWT_SECRET}" \
    "ALOCACRECHE_SEGURANCA_USUARIOINICIAL_EMAIL=${EMAIL_ADMIN}" \
    "ALOCACRECHE_SEGURANCA_USUARIOINICIAL_SENHA=${SENHA_DEMO}" \
    "ALOCACRECHE_SEGURANCA_USUARIOINICIAL_MUNICIPIOID=${MUNICIPIO_ID}" \
    "ALOCACRECHE_SWAGGER_HABILITADO=true" \
    "${extras[@]}"
}

# Sequencia do core: ver docs/arquitetura/roteiro-demo-relatorios.md (secoes 4 a 6). A tabela municipio
# so nasce na primeira subida (ddl-auto=update) e, depois que o schema.sql aplica RLS, reiniciar com
# "update" falha na verificacao das FKs; por isso a segunda subida usa ddl-auto=none.
subir_core() {
  local existe
  existe=$(psql_core -tAc "SELECT to_regclass('public.municipio') IS NOT NULL" | tr -d '[:space:]')
  if [[ "$existe" != "t" ]]; then
    info "Core: primeira subida (cria as tabelas)"
    iniciar_core
    esperar_servico core "$DEMO_PORTA_CORE"
    parar_servico core
    esperar_porta_livre "$DEMO_PORTA_CORE" 20 || falhar "A porta ${DEMO_PORTA_CORE} nao foi liberada apos a primeira subida do core."
  else
    info "Core: banco ja criado, pulando a primeira subida"
  fi
  info "Criando o municipio ${MUNICIPIO_ID} (idempotente)"
  psql_core -c "INSERT INTO municipio (id, nome, uf, prazo_confirmacao_dias, peso_renda, peso_vulnerabilidade, peso_distancia, peso_irmao_matriculado) VALUES (${MUNICIPIO_ID}, 'Municipio Demo', 'SP', 5, 0.4, 0.3, 0.2, 0.1) ON CONFLICT (id) DO NOTHING" >/dev/null
  info "Core: subida definitiva (ddl-auto=none) na porta ${DEMO_PORTA_CORE}"
  iniciar_core none
}

iniciar_relatorios() {
  info "Relatorios: subindo na porta ${DEMO_PORTA_RELATORIOS}"
  iniciar_servico relatorios \
    "SERVER_PORT=${DEMO_PORTA_RELATORIOS}" \
    "SA_TOKEN_JWT_SECRET=${JWT_SECRET}" \
    "ALOCACRECHE_SWAGGER_HABILITADO=true"
}

iniciar_gateway() {
  info "Gateway: subindo na porta ${DEMO_PORTA_GATEWAY}"
  iniciar_servico gateway \
    "SERVER_PORT=${DEMO_PORTA_GATEWAY}" \
    "ALOCACRECHE_CORE_URL=http://localhost:${DEMO_PORTA_CORE}" \
    "ALOCACRECHE_RELATORIOS_URL=http://localhost:${DEMO_PORTA_RELATORIOS}"
}

# ---------------------------------------------------------------------------------------------
# 6. Usuarios e validacao por login real via Gateway
# ---------------------------------------------------------------------------------------------
# Cria GESTOR, ATENDENTE e (upsert) o ADMIN com a senha desta execucao. O upsert do ADMIN garante
# que o login funcione mesmo com --manter sem credenciais salvas (admin ja existente no banco).
criar_usuarios() {
  info "Criando/atualizando os usuarios ADMIN, GESTOR e ATENDENTE"
  psql_core \
    -v senha="$SENHA_DEMO" \
    -v email_admin="$EMAIL_ADMIN" \
    -v email_gestor="$EMAIL_GESTOR" -v email_atendente="$EMAIL_ATENDENTE" \
    -v municipio_id="$MUNICIPIO_ID" -f - <"$RAIZ/docs/mock-data/usuarios-demo.sql" >/dev/null \
    || falhar "docs/mock-data/usuarios-demo.sql falhou"
}

# Faz login pelo Gateway e confere que veio um token.
validar_login() {
  local perfil="$1" email="$2" resp
  resp=$(curl -s --max-time 10 -X POST "${URL_GATEWAY}/api/v1/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"${email}\",\"senha\":\"${SENHA_DEMO}\",\"municipioId\":${MUNICIPIO_ID}}" || true)
  [[ "$resp" == *'"token":"'* ]] || falhar "Login de ${perfil} (${email}) pelo Gateway falhou. Resposta: ${resp:-<vazia>}"
  info "Login de ${perfil} pelo Gateway: OK"
}

imprimir_quadro() {
  local tempo=$((SECONDS - INICIO)) job="desligado (use --com-job para ligar)"
  if [[ $COM_JOB -eq 1 ]]; then
    job="LIGADO (rodada a cada 20 s)"
  fi
  cat <<QUADRO

================================================================================
 DEMONSTRACAO NO AR  (subida em ${tempo} s)
================================================================================
 Interface web : ${URL_GATEWAY}/login.html   (municipio ${MUNICIPIO_ID})

 $(printf '%-10s  %-24s  %s' Perfil E-mail Senha)
 $(printf '%-10s  %-24s  %s' ADMIN "$EMAIL_ADMIN" "$SENHA_DEMO")
 $(printf '%-10s  %-24s  %s' GESTOR "$EMAIL_GESTOR" "$SENHA_DEMO")
 $(printf '%-10s  %-24s  %s' ATENDENTE "$EMAIL_ATENDENTE" "$SENHA_DEMO")

 Servicos      core ${DEMO_PORTA_CORE} | relatorios ${DEMO_PORTA_RELATORIOS} | gateway ${DEMO_PORTA_GATEWAY}
 Swagger core  http://localhost:${DEMO_PORTA_CORE}/swagger-ui.html
 Swagger relat http://localhost:${DEMO_PORTA_RELATORIOS}/swagger-ui.html
   1) POST /api/v1/auth/login (Try it out) com e-mail, senha e municipio acima
   2) copie o campo "token" da resposta e clique em Authorize (cole so o token)
   3) as demais operacoes passam a valer conforme o perfil; sem token, 401
 RabbitMQ      http://localhost:15672  (guest / guest)
 Job manutencao: ${job}

 Logs          .demo/core.log  .demo/relatorios.log  .demo/gateway.log
 Credenciais   .demo/credenciais.txt  (ignorado pelo git; reaproveitado com --manter)

 Para parar    scripts/demo-parar.sh          (mantem os dados)
               scripts/demo-parar.sh --zerar  (apaga os volumes)
================================================================================
QUADRO
}

# ---------------------------------------------------------------------------------------------
main() {
  pre_checagens
  mkdir -p "$DEMO_DIR"
  ARMADO=1
  info "Parando eventuais processos anteriores da demo"
  parar_servicos_demo
  preparar_credenciais

  subir_infra
  if [[ $SEM_BUILD -eq 0 ]]; then
    compilar
  fi
  esperar_infra_saudavel

  {
    echo "core=${DEMO_PORTA_CORE}"
    echo "relatorios=${DEMO_PORTA_RELATORIOS}"
    echo "gateway=${DEMO_PORTA_GATEWAY}"
  } >"$DEMO_DIR/portas.env"

  iniciar_relatorios
  subir_core
  iniciar_gateway
  esperar_servico relatorios "$DEMO_PORTA_RELATORIOS"
  esperar_servico core "$DEMO_PORTA_CORE"
  esperar_servico gateway "$DEMO_PORTA_GATEWAY"

  criar_usuarios
  validar_login ADMIN "$EMAIL_ADMIN"
  validar_login GESTOR "$EMAIL_GESTOR"
  validar_login ATENDENTE "$EMAIL_ATENDENTE"

  ARMADO=0
  imprimir_quadro
}

main
