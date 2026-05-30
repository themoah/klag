#!/usr/bin/env bash
#
# Runs the Strimzi compatibility e2e (scripts/e2e-strimzi-test.sh) across
# multiple Kafka versions, sequentially (each run uses its own disposable
# cluster and cleans up after itself). Verifies the Klag chart works against
# every Kafka version the installed Strimzi operator supports.
#
# Usage:
#   scripts/e2e-strimzi-matrix.sh [version ...]
#
# Defaults to the versions supported by recent Strimzi releases. Override by
# passing versions, e.g.:
#   scripts/e2e-strimzi-matrix.sh 4.1.0 4.2.0
#
set -uo pipefail
cd "$(dirname "$0")/.."

VERSIONS=("$@")
if [[ ${#VERSIONS[@]} -eq 0 ]]; then
  VERSIONS=(4.1.0 4.2.0)
fi

GREEN='\033[0;32m'; RED='\033[0;31m'; BLUE='\033[0;34m'; NC='\033[0m'
declare -a RESULTS
fail=0

for v in "${VERSIONS[@]}"; do
  echo ""
  echo -e "${BLUE}######## Strimzi e2e — Kafka ${v} ########${NC}"
  if KAFKA_VERSION="$v" ./scripts/e2e-strimzi-test.sh; then
    RESULTS+=("${v}: PASS"); echo -e "${GREEN}Kafka ${v}: PASS${NC}"
  else
    RESULTS+=("${v}: FAIL"); echo -e "${RED}Kafka ${v}: FAIL${NC}"; fail=1
  fi
done

echo ""
echo "==== Strimzi compatibility matrix ===="
for r in "${RESULTS[@]}"; do echo "  $r"; done
[[ $fail -eq 0 ]] && echo -e "${GREEN}All Kafka versions passed${NC}" || echo -e "${RED}Some versions failed${NC}"
exit $fail
