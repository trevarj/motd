#!/usr/bin/env bash
# test/e2e/local-stack.sh — bring up a native (non-Docker) ergo + soju bouncer
# stack on this host for playing with / e2e-driving the motd app against a real
# device or emulator. Guix/Docker-free: ergo, soju, sojuctl, nc and openssl come
# from nixpkgs on demand; the script re-execs itself inside a `nix shell` if they
# are not already on PATH.
#
# This is the native equivalent of test/e2e/hermetic/ (which targets Docker + an
# emulator at 10.0.2.2). Here the app reaches soju over `adb reverse` at
# 127.0.0.1:6697, so it works for a USB-attached physical device too.
#
# Usage:
#   ./test/e2e/local-stack.sh up      # fresh stack (wipes prior state) + adb reverse + seed
#   ./test/e2e/local-stack.sh down    # stop ergo/soju, drop the adb reverse
#   ./test/e2e/local-stack.sh seed    # re-post the seed messages into ##motdtest
#   ./test/e2e/local-stack.sh status  # show pids + soju network status
#
# All credentials below are ephemeral LOCAL test creds, NOT secrets.
set -euo pipefail

CMD="${1:-up}"
RUN="${MOTD_STACK_DIR:-/tmp/motd-stack}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
PROVISION="$REPO/test/e2e/hermetic/ergo/provision.sh"

# Endpoints + creds (mirror the hermetic stack so onboarding is identical).
ERGO_PORT=6667
SOJU_PORT=6697
export ERGO_HOST=127.0.0.1
export ERGO_PORT
export TEST_CHANNEL='##motdtest'
export APP_NICK=motdadb
export UP_ACCOUNT=motd
export UP_PASS=motdupstream
export SEED_NICK=motdadb2
export SEED_PASS=motdadb2pass
SOJU_USER=motd
SOJU_PASS=motdtest
NETWORK_NAME=libera

log() { printf '\033[36m[local-stack]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31m[local-stack] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

# Re-exec inside a nix shell that provides the bouncer binaries if missing.
if ! command -v soju >/dev/null 2>&1 || ! command -v ergo >/dev/null 2>&1; then
  log "fetching ergo/soju/nc/openssl via nix shell…"
  exec nix shell nixpkgs#ergochat nixpkgs#soju nixpkgs#netcat-openbsd nixpkgs#openssl \
    -c "$0" "$CMD"
fi

CONF_ERGO="$RUN/ircd.yaml"
CONF_SOJU="$RUN/soju.config"
ADMIN_SOCK="$RUN/soju/admin.sock"
ctl() { sojuctl -config "$CONF_SOJU" "$@"; }

# adb: prefer a real executable on PATH (type -P ignores shell funcs/aliases so a
# leaked `adb` alias can't false-positive), else the repo flake devshell.
adb() {
  local bin; bin="$(type -P adb 2>/dev/null || true)"
  if [ -n "$bin" ]; then "$bin" "$@"; else nix develop "$REPO" -c adb "$@"; fi
}

write_configs() {
  mkdir -p "$RUN/ergo" "$RUN/soju/tls"
  cat >"$CONF_ERGO" <<'YAML'
network:
    name: MotdLocal
server:
    name: ergo.local
    listeners:
        ":6667":
    sts: { enabled: false }
    casemapping: precis
    enforce-utf8: true
    lookup-hostnames: false
    ip-cloaking: { enabled: false }
    ip-limits: { count: false, throttle: false }
    max-sendq: 96k
    compatibility: { force-trailing: true, send-unprefixed-sasl: true }
    ident-timeout: 100ms
accounts:
    authentication-enabled: true
    registration:
        enabled: true
        allow-before-connect: true
        email-verification: { enabled: false }
        bcrypt-cost: 4
        throttling: { enabled: false }
    login-throttling: { enabled: false }
    nick-reservation: { enabled: false }
    multiclient: { enabled: true, allowed-by-default: true, always-on: "opt-in" }
channels:
    default-modes: +nt
    registration: { enabled: true }
history:
    enabled: true
    channel-length: 2048
    client-length: 256
    autoresize-window: 0
    autoreplay-on-join: 0
    chathistory-maxmessages: 1000
    znc-maxmessages: 100
    restrictions: { expire-time: 0 }
    persistent: { enabled: false }
datastore:
    path: /tmp/motd-stack/ergo/ircd.db
    autoupgrade: true
languages: { enabled: false }
limits:
    nicklen: 32
    identlen: 20
    channellen: 64
    awaylen: 390
    kicklen: 390
    topiclen: 390
    monitor-entries: 100
    whowas-entries: 100
    chan-list-modes: 60
    registration-messages: 1024
logging:
    - { method: stderr, type: "* -userinput -useroutput", level: info }
debug: { recover-from-errors: true }
YAML
  # ergo datastore path is fixed above; keep RUN aligned with the default.
  sed -i "s#/tmp/motd-stack/ergo/ircd.db#$RUN/ergo/ircd.db#" "$CONF_ERGO"
  cat >"$CONF_SOJU" <<EOF
hostname localhost
db sqlite3 $RUN/soju/soju.db
message-store db
listen ircs://:$SOJU_PORT
tls $RUN/soju/tls/cert.pem $RUN/soju/tls/key.pem
listen unix+admin://$ADMIN_SOCK
EOF
}

wait_port() { # host port label
  local i=0
  until nc -z "$1" "$2" 2>/dev/null; do
    i=$((i + 1)); [ "$i" -gt 60 ] && die "$3 not reachable on $1:$2"; sleep 1
  done
}

stop_pids() {
  for p in soju ergo; do
    local f="$RUN/$p.pid"
    if [ -f "$f" ]; then kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; fi
  done
}

up() {
  stop_pids
  # Guard: a stale/foreign ergo/soju on these ports would silently hijack the run
  # (provisioning would talk to the wrong daemon). Fail loudly instead.
  for pp in "$ERGO_PORT" "$SOJU_PORT"; do
    if nc -z 127.0.0.1 "$pp" 2>/dev/null; then
      die "port $pp already in use (another stack?). Inspect: ss -ltnp | grep :$pp — then kill it or run '$0 down'"
    fi
  done
  log "wiping prior state at $RUN"
  rm -rf "$RUN/ergo" "$RUN/soju"
  write_configs

  log "ergo: initdb + start (:$ERGO_PORT)"
  ergo initdb --conf "$CONF_ERGO" >/dev/null 2>&1 || true
  setsid ergo run --conf "$CONF_ERGO" >"$RUN/ergo.log" 2>&1 &
  echo $! >"$RUN/ergo.pid"
  wait_port 127.0.0.1 "$ERGO_PORT" ergo

  log "ergo: register accounts + create $TEST_CHANNEL"
  sh "$PROVISION" register

  log "soju: self-signed TLS cert (SAN localhost,127.0.0.1,10.0.2.2)"
  [ -f "$RUN/soju/tls/cert.pem" ] || openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$RUN/soju/tls/key.pem" -out "$RUN/soju/tls/cert.pem" \
    -days 3650 -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2" >/dev/null 2>&1

  log "soju: start (ircs://:$SOJU_PORT)"
  setsid soju -config "$CONF_SOJU" >"$RUN/soju.log" 2>&1 &
  echo $! >"$RUN/soju.pid"
  local i=0
  until [ -S "$ADMIN_SOCK" ]; do
    i=$((i + 1)); [ "$i" -gt 60 ] && die "soju admin socket never appeared (see $RUN/soju.log)"; sleep 1
  done

  log "soju: provision user '$SOJU_USER' + network '$NETWORK_NAME' -> ergo"
  ctl user create -username "$SOJU_USER" -password "$SOJU_PASS" -realname "motd local" -admin 2>/dev/null \
    || log "user $SOJU_USER already exists (ok)"
  ctl user run "$SOJU_USER" network create \
    -addr "irc+insecure://127.0.0.1:$ERGO_PORT" \
    -name "$NETWORK_NAME" \
    -nick "$APP_NICK" \
    -username "$UP_ACCOUNT" \
    -connect-command "PRIVMSG NickServ :IDENTIFY ${UP_ACCOUNT} ${UP_PASS}" \
    -connect-command "JOIN ${TEST_CHANNEL}" \
    -enabled=true 2>/dev/null \
    || log "network $NETWORK_NAME already exists (ok)"

  log "soju: waiting for upstream network to connect + join $TEST_CHANNEL"
  i=0
  until ctl user run "$SOJU_USER" network status 2>/dev/null | grep -q '\[connected\]'; do
    i=$((i + 1)); [ "$i" -gt 40 ] && die "soju upstream never connected (see $RUN/soju.log)"; sleep 1
  done

  log "seeding history into $TEST_CHANNEL"
  sh "$PROVISION" seed

  log "adb reverse tcp:$SOJU_PORT (device 127.0.0.1:$SOJU_PORT -> host soju)"
  adb reverse "tcp:$SOJU_PORT" "tcp:$SOJU_PORT" || log "adb reverse failed (no device?) — set it up manually"

  cat >&2 <<EOF

\033[32m[local-stack] UP\033[0m  logs: $RUN/{ergo,soju}.log

Onboard the app (motd debug) → "I have a soju bouncer":
  Host:     127.0.0.1
  Port:     $SOJU_PORT     (TLS on; tap Trust on the self-signed cert prompt)
  Username: $SOJU_USER
  Password: $SOJU_PASS
Import the bouncer network "$NETWORK_NAME", then open $TEST_CHANNEL (seeded history).

Re-seed:  ./test/e2e/local-stack.sh seed
Tear down: ./test/e2e/local-stack.sh down
EOF
}

down() {
  log "stopping ergo/soju"
  stop_pids
  adb reverse --remove "tcp:$SOJU_PORT" 2>/dev/null || true
  log "down (state kept at $RUN; rm -rf it to wipe)"
}

status() {
  for p in ergo soju; do
    local f="$RUN/$p.pid"
    if [ -f "$f" ] && kill -0 "$(cat "$f")" 2>/dev/null; then
      printf '%-5s running (pid %s)\n' "$p" "$(cat "$f")"
    else
      printf '%-5s stopped\n' "$p"
    fi
  done
  [ -S "$ADMIN_SOCK" ] && ctl user run "$SOJU_USER" network status 2>/dev/null || true
}

case "$CMD" in
  up) up ;;
  down) down ;;
  seed) sh "$PROVISION" seed ;;
  status) status ;;
  *) die "unknown command '$CMD' (want up|down|seed|status)" ;;
esac
