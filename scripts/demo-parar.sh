#!/usr/bin/env bash
# Para toda a demonstracao do AlocaCreche iniciada por scripts/demo-subir.sh.
#
# Uso: scripts/demo-parar.sh [--zerar]
#   --zerar   alem de parar os containers, apaga os volumes (docker compose down -v)
#
# Idempotente: rodar sem nada no ar nao e erro.

set -u

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$RAIZ" || exit 1
source "$RAIZ/scripts/lib/demo-comum.sh"

ZERAR=0
for arg in "$@"; do
  case "$arg" in
    --zerar) ZERAR=1 ;;
    -h | --help)
      sed -n '2,8p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      erro "Opcao desconhecida: $arg (use --zerar)"
      exit 2
      ;;
  esac
done

# As portas realmente usadas na ultima subida ficam registradas em .demo/portas.env.
if [[ -f "$DEMO_DIR/portas.env" ]]; then
  DEMO_PORTA_CORE=$(grep '^core=' "$DEMO_DIR/portas.env" | cut -d= -f2)
  DEMO_PORTA_RELATORIOS=$(grep '^relatorios=' "$DEMO_DIR/portas.env" | cut -d= -f2)
  DEMO_PORTA_GATEWAY=$(grep '^gateway=' "$DEMO_DIR/portas.env" | cut -d= -f2)
fi

declare -a tinham_pid=()
for s in $SERVICOS_DEMO; do
  [[ -f "$(arquivo_pid "$s")" ]] && tinham_pid+=("$s")
done

parar_servicos_demo

status=0
for par in "core:${DEMO_PORTA_CORE}" "relatorios:${DEMO_PORTA_RELATORIOS}" "gateway:${DEMO_PORTA_GATEWAY}"; do
  nome="${par%%:*}"
  porta="${par##*:}"
  if esperar_porta_livre "$porta" 15; then
    info "Porta ${porta} (${nome}) livre"
    continue
  fi
  dono=$(quem_usa_porta "$porta")
  mesmo_nosso=0
  for t in "${tinham_pid[@]+"${tinham_pid[@]}"}"; do
    [[ "$t" == "$nome" ]] && mesmo_nosso=1
  done
  if [[ $mesmo_nosso -eq 1 ]]; then
    erro "A porta ${porta} (${nome}) continua ocupada depois de parar a demo ${dono:+(${dono})}"
    status=1
  else
    aviso "A porta ${porta} esta ocupada por um processo que nao e da demo ${dono:+(${dono})}; nada foi encerrado"
  fi
done

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  if [[ $ZERAR -eq 1 ]]; then
    info "Derrubando a infraestrutura e apagando os volumes (down -v)"
    docker compose down -v >/dev/null 2>&1 || { erro "docker compose down -v falhou"; status=1; }
  else
    info "Derrubando a infraestrutura (os dados ficam nos volumes; use --zerar para apagar)"
    docker compose down >/dev/null 2>&1 || { erro "docker compose down falhou"; status=1; }
  fi
else
  aviso "Docker nao esta acessivel; a infraestrutura nao foi derrubada"
fi

if [[ $ZERAR -eq 1 ]]; then
  rm -f "$DEMO_DIR/credenciais.txt"
fi
rm -f "$DEMO_DIR/portas.env"

if [[ $status -eq 0 ]]; then
  info "Demonstracao parada."
fi
exit $status
