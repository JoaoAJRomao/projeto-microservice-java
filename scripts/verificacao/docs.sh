#!/usr/bin/env bash
# =============================================================================
#  Verificação da documentação da escala horizontal (ADR-0005, ADR-0006).
#
#  Uso:  bash scripts/verificacao/docs.sh <cenario>
#        bash scripts/verificacao/docs.sh --lista
#
#  0 = passou, 1 = falhou, 2 = cenário desconhecido. Requer só bash, grep e awk.
# =============================================================================
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$RAIZ"

passou() { printf '\033[1;32mPASSOU\033[0m %s\n' "$1"; }
falha() { printf '\033[1;31mFALHOU\033[0m %s\n' "$1" >&2; exit 1; }

# secao <arquivo> <início> <fim>: linhas a partir do título que começa com <início>
# até antes do título que começa com <fim>
secao() {
  awk -v ini="$2" -v fim="$3" '
    index($0, ini) == 1 { dentro = 1; next }
    dentro && index($0, fim) == 1 { exit }
    dentro { print }' "$1"
}

contem() { grep -F -c -- "$2" <<< "$1" >/dev/null; }

cenario_adrs() {
  local adr titulo
  for adr in docs/adr/0005-api-gateway-e-service-discovery.md docs/adr/0006-migracoes-de-esquema-com-flyway.md; do
    [ -f "$adr" ] || falha "$adr não existe"
    for titulo in "## Contexto" "## Alternativas consideradas" "## Decisão" "## Consequências" "## Histórico de revisões"; do
      grep -E -c -- "^${titulo}\$" "$adr" >/dev/null || falha "$adr sem a seção '$titulo'"
    done
    grep -F -c -- "($(basename "$adr"))" docs/adr/README.md >/dev/null || falha "docs/adr/README.md não linka $(basename "$adr")"
  done
  passou "ADR-0005 e ADR-0006 têm as cinco seções do template e estão linkados no índice"
}

cenario_arquitetura() {
  local doc=docs/arquitetura.md s31 s35 s8 s9 linha
  s31=$(secao "$doc" "### 3.1" "### 3.2")
  s35=$(secao "$doc" "### 3.5" "## 4.")
  s8=$(secao "$doc" "## 8." "## 9.")
  s9=$(secao "$doc" "## 9." "## 10.")
  [ -n "$s31" ] && [ -n "$s35" ] && [ -n "$s8" ] && [ -n "$s9" ] || falha "não achei as seções 3.1, 3.5, 8 e 9 em $doc"
  contem "$s31" "api-gateway" || falha "§3.1 não cita api-gateway no diagrama"
  contem "$s31" "discovery-server" || falha "§3.1 não cita discovery-server no diagrama"
  contem "$s35" "8080" || falha "§3.5 não cita a porta 8080"
  for linha in '| `api-gateway` |' '| `discovery-server` |'; do
    contem "$s8" "$linha" || falha "a tabela da §8 não tem a linha $linha"
  done
  ! contem "$s9" "ddl-auto: update" || falha "§9 ainda lista ddl-auto: update"
  passou "arquitetura.md: gateway e discovery na §3.1, 8080 na §3.5, containers novos na §8, ddl-auto: update fora da §9"
}

cenario_enderecos() {
  local f
  for f in README.md scripts/demo.sh scripts/demo.http load-test/carga-cadastro.js .env.example; do
    [ -f "$f" ] || falha "$f não existe"
    ! grep -E -n -- 'localhost:808[12]' "$f" >&2 || falha "$f ainda contém localhost:8081 ou localhost:8082 (linhas acima)"
  done
  grep -F -c -- "http://localhost:8080" README.md >/dev/null || falha "README não lista http://localhost:8080"
  grep -F -c -- "http://localhost:8761" README.md >/dev/null || falha "README não lista http://localhost:8761"
  passou "nenhum cliente aponta para localhost:8081/8082; README lista :8080 e :8761"
}

# ================================================================= main ===
CENARIOS=(adrs arquitetura enderecos)

if [ "${1:-}" = "--lista" ]; then printf '%s\n' "${CENARIOS[@]}"; exit 0; fi
alvo="${1:-}"
for c in "${CENARIOS[@]}"; do
  if [ "$c" = "$alvo" ]; then
    printf '\033[1;36m==> %s\033[0m\n' "$alvo"
    "cenario_${alvo}"
    exit 0
  fi
done
echo "cenário desconhecido: '${alvo}'. Use --lista para ver os cenários." >&2
exit 2
