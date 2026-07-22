#!/usr/bin/env bash
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=test/e2e/harness.sh
. "$E2E_DIR/harness.sh"

compose() { docker compose -f "$E2E_HERMETIC_COMPOSE" "$@"; }

wait_for_soju_ready() {
  local container i status
  container="$(compose ps -q soju)"
  [ -n "$container" ] || { echo "soju container is absent" >&2; return 1; }
  for i in $(seq 1 60); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$container")"
    if [ "$status" = healthy ] && compose exec -T soju sh -ec \
      "sojuctl -config /etc/soju/soju.config user run motd network status | grep -q '\\[connected\\]'"; then
      return 0
    fi
    sleep 1
  done
  compose logs --no-color soju >&2 || true
  return 1
}

seed_reconnect() {
  local mode="$1" token="$2"
  case "$token" in ''|*[!A-Za-z0-9]*) echo "token must be nonempty ASCII alphanumeric" >&2; return 2 ;; esac
  compose run --rm --no-deps ergo-provision "$mode" "$token"
}

history_check() {
  local exact_text="${1:-hello, this is a seeded plain line}"
  wait_for_soju_ready
  python3 "$E2E_DIR/fixtures/chathistory-probe.py" --port 6697 --seed-text "$exact_text"
}

stop_soju() {
  if [ -z "$(compose ps -q soju)" ]; then
    return 0
  fi
  compose stop soju
  [ -z "$(compose ps -q --status running soju)" ] || { echo "soju stayed running after stop" >&2; return 1; }
}

start_soju() {
  if [ -n "$(compose ps -q --status running soju)" ]; then
    wait_for_soju_ready
    return
  fi
  compose start soju
  wait_for_soju_ready
}

tls_fingerprint() {
  # The generated leaf is intentionally passed as a lowercase opaque test argument; never print
  # the certificate, endpoint, or fingerprint in harness diagnostics.
  compose exec -T soju openssl x509 -in /etc/soju/tls/cert.pem -outform der \
    | sha256sum | awk '{print tolower($1)}'
}

case "${1:-}" in
  up)
    compose up --build -d --wait
    compose ps
    ;;
  status) compose ps --all ;;
  logs) compose logs --no-color ;;
  capture)
    : "${2:?usage: $0 capture OUTPUT_DIR}"
    e2e_capture_hermetic_artifacts "$2"
    ;;
  reconnect-gap) : "${2:?usage: $0 reconnect-gap TOKEN}"; seed_reconnect reconnect-gap "$2" ;;
  reconnect-current) : "${2:?usage: $0 reconnect-current TOKEN}"; seed_reconnect reconnect-current "$2" ;;
  history-check) history_check "${2:-}" ;;
  stop-soju) stop_soju ;;
  start-soju) start_soju ;;
  tls-fingerprint) tls_fingerprint ;;
  down) compose down -v ;;
  validate) compose config --quiet ;;
  *) echo "usage: $0 {up|status|logs|capture OUTPUT_DIR|reconnect-gap TOKEN|reconnect-current TOKEN|history-check [EXACT_TEXT]|stop-soju|start-soju|tls-fingerprint|down|validate}" >&2; exit 2 ;;
esac
