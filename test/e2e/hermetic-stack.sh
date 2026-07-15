#!/usr/bin/env bash
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=test/e2e/harness.sh
. "$E2E_DIR/harness.sh"

case "${1:-}" in
  up)
    docker compose -f "$E2E_HERMETIC_COMPOSE" up --build -d --wait
    docker compose -f "$E2E_HERMETIC_COMPOSE" ps
    ;;
  status) docker compose -f "$E2E_HERMETIC_COMPOSE" ps --all ;;
  logs) docker compose -f "$E2E_HERMETIC_COMPOSE" logs --no-color ;;
  capture)
    : "${2:?usage: $0 capture OUTPUT_DIR}"
    e2e_capture_hermetic_artifacts "$2"
    ;;
  down) docker compose -f "$E2E_HERMETIC_COMPOSE" down -v ;;
  validate) docker compose -f "$E2E_HERMETIC_COMPOSE" config --quiet ;;
  *) echo "usage: $0 {up|status|logs|capture OUTPUT_DIR|down|validate}" >&2; exit 2 ;;
esac
