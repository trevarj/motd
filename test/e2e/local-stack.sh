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
#   ./test/e2e/local-stack.sh up        # fresh stack (wipes prior state) + adb reverse + seed
#   ./test/e2e/local-stack.sh down      # stop ergo/soju, drop the adb reverse
#   ./test/e2e/local-stack.sh seed      # re-post the seed messages into the active fixture channel
#   MOTD_STACK_PROFILE=showcase ./test/e2e/local-stack.sh up
#                                      # multi-channel screenshot fixture
#   ./test/e2e/local-stack.sh burst     # post a numbered 12-message live burst
#   ./test/e2e/local-stack.sh jpq       # emit JOIN/PART/QUIT-only activity
#   ./test/e2e/local-stack.sh push TOKEN # emit one tagged highlight and direct message
#   ./test/e2e/local-stack.sh canonical TOKEN # repeated text + account-backed PM nick rewrite
#   ./test/e2e/local-stack.sh reconnect-gap TOKEN # persist forty older TOKEN gNN rows
#   ./test/e2e/local-stack.sh reconnect-current TOKEN # send three live TOKEN cNN rows
#   ./test/e2e/local-stack.sh pause-soju  # delay echo/MARKREAD processing via SIGSTOP
#   ./test/e2e/local-stack.sh resume-soju # resume soju via SIGCONT
#   ./test/e2e/local-stack.sh stop-soju   # deterministic EOF while preserving soju DB/config
#   ./test/e2e/local-stack.sh start-soju  # restart the preserved soju instance
#   ./test/e2e/local-stack.sh status    # show pids + soju network status
#   ./test/e2e/local-stack.sh history-check # retained TARGETS + LATEST CHATHISTORY proof
#   ./test/e2e/local-stack.sh control-check # BouncerServ admin/non-admin and mutation proof
#   ./test/e2e/local-stack.sh read-marker-check # two-client marker broadcast/reconnect proof
#   ./test/e2e/local-stack.sh invite-check # direct sender -> soju downstream INVITE proof
#   ./test/e2e/local-stack.sh ready-up   # start scripted IRCv3 Ready fixture + soju network
#   ./test/e2e/local-stack.sh ready-check # direct and soju wire proofs for Ready features
#   ./test/e2e/local-stack.sh ready-down # remove scripted fixture network + process
#   ./test/e2e/local-stack.sh obfs-up   # VLESS+REALITY layer: sing-box server + Xray SOCKS client
#   ./test/e2e/local-stack.sh obfs-down # stop the reality layer + drop its adb reverse
#   ./test/e2e/local-stack.sh obfs-validate  # socket-level proof: IRC/TLS to soju THROUGH reality
#   ./test/e2e/local-stack.sh obfs-xray-up       # compatibility proof: Xray server + sing-box SOCKS client
#   ./test/e2e/local-stack.sh obfs-xray-down     # stop the compatibility proof layer
#   ./test/e2e/local-stack.sh obfs-xray-validate # socket-level proof through that layer
#   ./test/e2e/local-stack.sh obfs-xray-history-check # retained history through that layer
#   ./test/e2e/local-stack.sh obfs-xray-negative # reject a deliberately wrong REALITY public key
#
# All credentials below are ephemeral LOCAL test creds, NOT secrets.
set -euo pipefail

CMD="${1:-up}"
PUSH_TOKEN="${2:-${PUSH_TOKEN:-motd-unifiedpush}}"
export PUSH_TOKEN
RUN="${MOTD_STACK_DIR:-/tmp/motd-stack}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
PROVISION="$REPO/test/e2e/hermetic/ergo/provision.sh"
STACK_PROFILE="${MOTD_STACK_PROFILE:-default}"
case "$STACK_PROFILE" in
  default|showcase) ;;
  *) printf '\033[31m[local-stack] FATAL:\033[0m unknown MOTD_STACK_PROFILE '\''%s'\'' (want default|showcase)\n' "$STACK_PROFILE" >&2; exit 1 ;;
esac
export MOTD_STACK_PROFILE="$STACK_PROFILE"

# Endpoints + creds (mirror the hermetic stack so onboarding is identical).
ERGO_PORT="${MOTD_ERGO_PORT:-6667}"
SOJU_PORT="${MOTD_SOJU_PORT:-6697}"
export ERGO_HOST=127.0.0.1
export ERGO_PORT
export SOJU_PORT
if [ "$STACK_PROFILE" = showcase ]; then
  export MOTD_SHOWCASE_CHANNELS='#guix #debian #emacs #rust'
  export TEST_CHANNEL="${MOTD_STACK_CHANNEL:-#guix}"
else
  export MOTD_SHOWCASE_CHANNELS=''
  export TEST_CHANNEL="${MOTD_STACK_CHANNEL:-##motdtest}"
fi
export APP_NICK=motdadb
export UP_ACCOUNT=motd
export UP_PASS=motdupstream
export SEED_NICK=motdadb2
export SEED_PASS=motdadb2pass
SOJU_USER=motd
SOJU_PASS=motdtest
SOJU_NONADMIN_USER=motduser
SOJU_NONADMIN_PASS=motdusertest
NETWORK_NAME=libera
READY_PORT="${MOTD_READY_PORT:-6671}"
READY_NETWORK=ready-fixture
READY_PID="$RUN/ircv3-ready.pid"
READY_LOG="$RUN/ircv3-ready.log"

# Obfuscation layer (plans/20 Phase 1 validation). REALITY server on :8443 (avoid clashing with a
# real 443), a local SOCKS5 the app dials on 127.0.0.1:1080, and a handshake domain to impersonate.
# www.microsoft.com does NOT work as a REALITY steal target from every host / with the uTLS Chrome
# fingerprint; www.cloudflare.com is a reliable default. Override any of these via the environment.
REALITY_PORT="${MOTD_REALITY_PORT:-8443}"
SOCKS_PORT="${MOTD_SOCKS_PORT:-1080}"
HANDSHAKE_DOMAIN="${MOTD_HANDSHAKE_DOMAIN:-www.cloudflare.com}"
OBFS_DIR="$RUN/obfs"
# A deliberately separate cross-core compatibility path. Keep its defaults distinct
# from obfs-* so the two proofs can be compared without port collisions.
XRAY_REALITY_PORT="${MOTD_XRAY_REALITY_PORT:-8444}"
SINGBOX_SOCKS_PORT="${MOTD_SINGBOX_SOCKS_PORT:-1081}"
SINGBOX_BAD_SOCKS_PORT="${MOTD_SINGBOX_BAD_SOCKS_PORT:-1082}"
XRAY_OBFS_DIR="$RUN/obfs-xray"

log() { printf '\033[36m[local-stack]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31m[local-stack] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

# The obfs layer needs sing-box (REALITY server), xray (REALITY client exposing SOCKS5) and
# python3 (the socket-level validator); the base layer needs the bouncer binaries. Re-exec inside a
# nix shell providing whatever the command needs but is missing from PATH.
need_reexec=false
case "$CMD" in
  control-check|history-check|read-marker-check|invite-check|ready-up|ready-check|ready-down)
    command -v soju >/dev/null 2>&1 && command -v ergo >/dev/null 2>&1 && \
      command -v python3 >/dev/null 2>&1 || need_reexec=true ;;
  obfs-*)
    for b in soju ergo sing-box xray python3; do
      command -v "$b" >/dev/null 2>&1 || { need_reexec=true; break; }
    done ;;
  *)
    command -v soju >/dev/null 2>&1 && command -v ergo >/dev/null 2>&1 || need_reexec=true ;;
esac
if [ "$need_reexec" = true ]; then
  [ "${MOTD_E2E_STACK_SHELL:-}" != 1 ] || die "required command is missing from the e2e-stack shell"
  log "entering the lockfile-backed e2e-stack shell…"
  exec nix develop "$REPO#e2e-stack" -c "$0" "$@"
fi

CONF_ERGO="$RUN/ircd.yaml"
CONF_SOJU="$RUN/soju.config"
ADMIN_SOCK="$RUN/soju/admin.sock"
ctl() { sojuctl -config "$CONF_SOJU" "$@"; }

# adb: prefer a real executable on PATH (type -P ignores shell funcs/aliases so a
# leaked `adb` alias can't false-positive), else the repo flake devshell.
adb() {
  # Socket-only CI checks have no device to reverse into. Avoid constructing the
  # Android flake environment merely to receive the expected "no devices" error.
  [ "${MOTD_SKIP_ADB_REVERSE:-0}" != "1" ] || return 1
  local bin; bin="$(type -P adb 2>/dev/null || true)"
  if [ -n "$bin" ]; then "$bin" "$@"; else nix develop "$REPO" -c adb "$@"; fi
}

write_configs() {
  mkdir -p "$RUN/ergo" "$RUN/soju/tls"
  cat >"$CONF_ERGO" <<YAML
network:
    name: MotdLocal
server:
    name: ergo.local
    listeners:
        ":$ERGO_PORT":
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
    # A reaction removal must survive replay with the reaction it cancels.
    tagmsg-storage:
        default: false
        whitelist: ["+draft/react", "+draft/unreact", "+react"]
datastore:
    path: $RUN/ergo/ircd.db
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

recorded_soju_running() {
  local pid
  [ -f "$RUN/soju.pid" ] || return 1
  pid="$(cat "$RUN/soju.pid")"
  kill -0 "$pid" 2>/dev/null || return 1
  ps -p "$pid" -o comm= 2>/dev/null | grep -Eq '(^|/)soju$'
}

wait_for_soju_listener() {
  local i=0
  until [ -S "$ADMIN_SOCK" ] && nc -z 127.0.0.1 "$SOJU_PORT" 2>/dev/null; do
    i=$((i + 1)); [ "$i" -gt 60 ] && die "soju listener did not become ready (see $RUN/soju.log)"; sleep 1
  done
}

wait_for_soju_ready() {
  wait_for_soju_listener
  local i=0
  i=0
  until ctl user run "$SOJU_USER" network status 2>/dev/null | grep -q '\[connected\]'; do
    i=$((i + 1)); [ "$i" -gt 40 ] && die "soju upstream did not reconnect (see $RUN/soju.log)"; sleep 1
  done
}

start_recorded_soju() {
  if recorded_soju_running; then
    wait_for_soju_listener
    return
  fi
  if nc -z 127.0.0.1 "$SOJU_PORT" 2>/dev/null; then
    die "port $SOJU_PORT has a foreign listener; refusing to replace it"
  fi
  rm -f "$ADMIN_SOCK"
  setsid soju -config "$CONF_SOJU" >>"$RUN/soju.log" 2>&1 &
  echo $! >"$RUN/soju.pid"
  wait_for_soju_listener
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

  log "ergo: register accounts + create fixture channels (profile=$STACK_PROFILE)"
  sh "$PROVISION" register

  log "soju: self-signed TLS cert (SAN localhost,127.0.0.1,10.0.2.2)"
  [ -f "$RUN/soju/tls/cert.pem" ] || openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$RUN/soju/tls/key.pem" -out "$RUN/soju/tls/cert.pem" \
    -days 3650 -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2" >/dev/null 2>&1

  log "soju: start (ircs://:$SOJU_PORT)"
  start_recorded_soju

  log "soju: provision user '$SOJU_USER' + network '$NETWORK_NAME' -> ergo"
  ctl user create -username "$SOJU_USER" -password "$SOJU_PASS" -realname "motd local" -admin 2>/dev/null \
    || log "user $SOJU_USER already exists (ok)"
  ctl user create -username "$SOJU_NONADMIN_USER" -password "$SOJU_NONADMIN_PASS" \
    -realname "motd local non-admin" 2>/dev/null \
    || log "user $SOJU_NONADMIN_USER already exists (ok)"
  local -a network_args=(
    -addr "irc+insecure://127.0.0.1:$ERGO_PORT"
    -name "$NETWORK_NAME"
    -nick "$APP_NICK"
    -username "$UP_ACCOUNT"
    -connect-command "PRIVMSG NickServ :IDENTIFY ${UP_ACCOUNT} ${UP_PASS}"
    -enabled=true
  )
  if [ "$STACK_PROFILE" = showcase ]; then
    for channel in $MOTD_SHOWCASE_CHANNELS; do
      network_args+=(-connect-command "JOIN ${channel}")
    done
  else
    network_args+=(-connect-command "JOIN ${TEST_CHANNEL}")
  fi
  ctl user run "$SOJU_USER" network create "${network_args[@]}" 2>/dev/null \
    || log "network $NETWORK_NAME already exists (ok)"

  log "soju: waiting for upstream network to connect + join fixture channels"
  wait_for_soju_ready

  log "seeding history for profile $STACK_PROFILE"
  if [ "$STACK_PROFILE" = showcase ]; then
    sh "$PROVISION" showcase
  else
    sh "$PROVISION" seed
  fi

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

BouncerServ non-admin capability check (separate app profile/device):
  Username: $SOJU_NONADMIN_USER
  Password: $SOJU_NONADMIN_PASS

  Re-seed:  ./test/e2e/local-stack.sh seed
Tear down: ./test/e2e/local-stack.sh down
EOF
}

showcase_seed() {
  [ "$STACK_PROFILE" = showcase ] || die "showcase seed requires MOTD_STACK_PROFILE=showcase"
  sh "$PROVISION" showcase
}

showcase_hold() {
  [ "$STACK_PROFILE" = showcase ] || die "showcase hold requires MOTD_STACK_PROFILE=showcase"
  sh "$PROVISION" showcase-hold
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

control_check() {
  [ -S "$ADMIN_SOCK" ] || die "soju admin socket not found; run '$0 up' first"
  nc -z 127.0.0.1 "$SOJU_PORT" 2>/dev/null || die "soju is not listening on 127.0.0.1:$SOJU_PORT"
  log "running BouncerServ admin/non-admin capability and mutation proof"
  python3 "$REPO/test/e2e/fixtures/bouncerserv-probe.py" --port "$SOJU_PORT"
}

history_check() {
  [ -S "$ADMIN_SOCK" ] || die "soju admin socket not found; run '$0 up' first"
  nc -z 127.0.0.1 "$SOJU_PORT" 2>/dev/null || die "soju is not listening on 127.0.0.1:$SOJU_PORT"
  log "proving retained TARGETS discovery and LATEST playback through Soju"
  local exact_text="${1:-hello, this is a seeded plain line}"
  python3 "$REPO/test/e2e/fixtures/chathistory-probe.py" --port "$SOJU_PORT" --seed-text "$exact_text"
}

read_marker_check() {
  [ -f "$RUN/soju.pid" ] && kill -0 "$(cat "$RUN/soju.pid")" 2>/dev/null \
    || die "soju is not running — run '$0 up' first"
  python3 "$REPO/test/e2e/fixtures/read-marker-probe.py" \
    --host 127.0.0.1 \
    --port "$SOJU_PORT" \
    --username "$SOJU_USER" \
    --password "$SOJU_PASS" \
    --network "$NETWORK_NAME" \
    --channel "$TEST_CHANNEL"
}

invite_check() {
  [ -S "$ADMIN_SOCK" ] || die "soju admin socket not found; run '$0 up' first"
  log "running direct Ergo sender -> soju downstream invitation proof"
  python3 "$REPO/test/e2e/fixtures/invite-delivery-probe.py" \
    --ergo-port "$ERGO_PORT" --soju-port "$SOJU_PORT" \
    --username "$SOJU_USER/$NETWORK_NAME" --password "$SOJU_PASS" --target "$APP_NICK"
}

ready_up() {
  [ -S "$ADMIN_SOCK" ] || die "soju admin socket not found; run '$0 up' first"
  if [ -f "$READY_PID" ] && kill -0 "$(cat "$READY_PID")" 2>/dev/null; then
    die "IRCv3 Ready fixture is already running; use '$0 ready-down' first"
  fi
  nc -z 127.0.0.1 "$READY_PORT" 2>/dev/null && die "port $READY_PORT is already in use"
  log "starting deterministic IRCv3 Ready fixture on :$READY_PORT"
  setsid python3 "$REPO/test/e2e/fixtures/ircv3-ready-server.py" \
    --port "$READY_PORT" --log "$READY_LOG" >"$RUN/ircv3-ready.stdout" 2>&1 &
  echo $! >"$READY_PID"
  wait_port 127.0.0.1 "$READY_PORT" "IRCv3 Ready fixture"
  ctl user run "$SOJU_USER" network create \
    -addr "irc+insecure://127.0.0.1:$READY_PORT" \
    -name "$READY_NETWORK" -nick motdready -username motdready -enabled=true 2>/dev/null \
    || log "network $READY_NETWORK already exists (ok)"
  local i=0
  until ctl user run "$SOJU_USER" network status 2>/dev/null \
      | grep "$READY_NETWORK" | grep -q '\[connected\]'; do
    i=$((i + 1)); [ "$i" -gt 30 ] && die "soju Ready fixture network did not connect"; sleep 1
  done
  adb reverse "tcp:$READY_PORT" "tcp:$READY_PORT" \
    || log "adb reverse failed (no device?) — set it up manually"
  log "Ready fixture available direct on :$READY_PORT and via soju network '$READY_NETWORK'"
}

ready_check() {
  [ -f "$READY_PID" ] && kill -0 "$(cat "$READY_PID")" 2>/dev/null \
    || die "IRCv3 Ready fixture is not running — run '$0 ready-up' first"
  log "checking direct Solanum-shaped wire contract"
  python3 "$REPO/test/e2e/fixtures/ircv3-ready-probe.py" --port "$READY_PORT"
  log "checking soju forwarding and capability contract"
  python3 "$REPO/test/e2e/fixtures/ircv3-ready-probe.py" \
    --port "$SOJU_PORT" --tls \
    --username "$SOJU_USER/$READY_NETWORK" --password "$SOJU_PASS" --skip-invite
}

ready_down() {
  if [ -S "$ADMIN_SOCK" ]; then
    ctl user run "$SOJU_USER" network delete "$READY_NETWORK" 2>/dev/null || true
  fi
  if [ -f "$READY_PID" ]; then
    kill "$(cat "$READY_PID")" 2>/dev/null || true
    rm -f "$READY_PID"
  fi
  adb reverse --remove "tcp:$READY_PORT" 2>/dev/null || true
  log "IRCv3 Ready fixture stopped"
}

signal_soju() { # signal description
  local signal="$1" description="$2" pid_file="$RUN/soju.pid"
  [ -f "$pid_file" ] || die "soju pid file missing; run '$0 up' first"
  local pid
  pid="$(cat "$pid_file")"
  kill -0 "$pid" 2>/dev/null || die "soju pid $pid is not running"
  kill "-$signal" "$pid"
  log "soju $description (pid $pid)"
}

stop_soju_for_reconnect() {
  local pid_file="$RUN/soju.pid"
  [ -f "$pid_file" ] || die "soju pid file missing; run '$0 up' first"
  local pid i
  pid="$(cat "$pid_file")"
  recorded_soju_running || die "recorded soju pid $pid is not a live soju process"
  kill "$pid"
  for i in $(seq 1 10); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    recorded_soju_running || die "recorded pid $pid changed before forced stop"
    kill -9 "$pid"
  fi
  for i in $(seq 1 20); do
    ! kill -0 "$pid" 2>/dev/null && ! nc -z 127.0.0.1 "$SOJU_PORT" 2>/dev/null && break
    sleep 1
  done
  ! kill -0 "$pid" 2>/dev/null || die "recorded soju pid $pid did not stop"
  ! nc -z 127.0.0.1 "$SOJU_PORT" 2>/dev/null || die "soju listener $SOJU_PORT remained up after stop"
  rm -f "$pid_file" "$ADMIN_SOCK"
  log "soju stopped for reconnect test; DB/config preserved"
}

start_soju_for_reconnect() {
  [ -f "$CONF_SOJU" ] || die "soju config missing; run '$0 up' first"
  start_recorded_soju
  wait_for_soju_ready
  log "soju restarted from preserved state (pid $(cat "$RUN/soju.pid"))"
}

# ---- Obfuscation layer: VLESS + REALITY (plans/20) -----------------------------------------
#
# Proves the SOCKS5 substrate carries IRC end-to-end through REALITY against the LOCAL stack, before
# any VPS. Topology:
#
#   app / validator ── SOCKS5 127.0.0.1:$SOCKS_PORT ─▶ Xray client ─ vless+reality ─▶
#     sing-box server :$REALITY_PORT ── decrypts, forwards ──▶ 127.0.0.1:$SOJU_PORT (soju)
#
# The REALITY *server* is sing-box (matches the VPS/docs and the eventual embedded libbox core). The
# *client* is Xray, because sing-box's own REALITY client cannot authenticate against a sing-box
# REALITY server (upstream bug SagerNet/sing-box#4023, closed "not planned"); Xray's client is the
# battle-tested REALITY peer. To the app this is invisible — it only ever dials a plain SOCKS5 proxy.

obfs_stop_pids() {
  for p in singbox xray; do
    local f="$OBFS_DIR/$p.pid"
    if [ -f "$f" ]; then kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; fi
  done
}

obfs_share_link() { # uuid pubkey shortid
  # Standard vless://<uuid>@host:port?... share link (Xray/sing-box/v2rayNG import format).
  printf 'vless://%s@127.0.0.1:%s?encryption=none&security=reality&sni=%s&fp=chrome&pbk=%s&sid=%s&type=tcp#motd-local-reality\n' \
    "$1" "$REALITY_PORT" "$HANDSHAKE_DOMAIN" "$2" "$3"
}

obfs_up() {
  obfs_stop_pids
  # Refuse to clash with an already-bound SOCKS/REALITY port (e.g. a leftover run, or a foreign
  # SOCKS proxy on 1080). Fail loudly so the validator can't talk to the wrong daemon.
  for pp in "$REALITY_PORT" "$SOCKS_PORT"; do
    if nc -z 127.0.0.1 "$pp" 2>/dev/null; then
      die "port $pp already in use. Inspect: ss -ltnp | grep :$pp — kill it, run '$0 obfs-down', or set MOTD_SOCKS_PORT/MOTD_REALITY_PORT"
    fi
  done
  [ -S "$ADMIN_SOCK" ] || log "note: soju admin socket not found — is the base stack up? ('$0 up')"
  mkdir -p "$OBFS_DIR"

  log "reality: generate x25519 keypair + short-id + uuid"
  local kp priv pub uuid sid
  kp="$(sing-box generate reality-keypair)"
  priv="$(printf '%s\n' "$kp" | awk '/PrivateKey/{print $2}')"
  pub="$(printf '%s\n' "$kp" | awk '/PublicKey/{print $2}')"
  uuid="$(sing-box generate uuid)"
  sid="$(sing-box generate rand 8 --hex)"
  # Persist so obfs-validate / re-print can reuse them without regenerating.
  printf 'UUID=%s\nPRIV=%s\nPUB=%s\nSID=%s\n' "$uuid" "$priv" "$pub" "$sid" >"$OBFS_DIR/reality.env"

  log "sing-box: REALITY server on 127.0.0.1:$REALITY_PORT (handshake $HANDSHAKE_DOMAIN) -> soju :$SOJU_PORT"
  cat >"$OBFS_DIR/singbox-server.json" <<EOF
{
  "log": { "level": "info" },
  "inbounds": [ {
    "type": "vless", "tag": "vless-in", "listen": "127.0.0.1", "listen_port": $REALITY_PORT,
    "users": [ { "uuid": "$uuid" } ],
    "tls": { "enabled": true, "server_name": "$HANDSHAKE_DOMAIN",
      "reality": { "enabled": true,
        "handshake": { "server": "$HANDSHAKE_DOMAIN", "server_port": 443 },
        "private_key": "$priv", "short_id": [ "$sid" ] } } } ],
  "outbounds": [ { "type": "direct", "tag": "direct" } ]
}
EOF

  # The decrypted stream is routed by the CLIENT's requested destination; the validator/app dials the
  # SOCKS5 proxy with 127.0.0.1:$SOJU_PORT, so no server-side static forward is needed — the server's
  # direct outbound reaches soju on loopback. (For a VPS you'd instead pin the destination; see docs.)

  log "xray: REALITY client with a SOCKS5 inbound on 127.0.0.1:$SOCKS_PORT"
  cat >"$OBFS_DIR/xray-client.json" <<EOF
{
  "log": { "loglevel": "warning" },
  "inbounds": [ { "tag": "socks-in", "listen": "127.0.0.1", "port": $SOCKS_PORT,
    "protocol": "socks", "settings": { "udp": false }, "sniffing": { "enabled": false } } ],
  "outbounds": [ { "tag": "vless-out", "protocol": "vless",
    "settings": { "vnext": [ { "address": "127.0.0.1", "port": $REALITY_PORT,
      "users": [ { "id": "$uuid", "encryption": "none" } ] } ] },
    "streamSettings": { "network": "tcp", "security": "reality",
      "realitySettings": { "serverName": "$HANDSHAKE_DOMAIN", "fingerprint": "chrome",
        "publicKey": "$pub", "shortId": "$sid" } } } ]
}
EOF

  sing-box check -c "$OBFS_DIR/singbox-server.json" || die "sing-box server config invalid"
  xray run -test -c "$OBFS_DIR/xray-client.json" >/dev/null 2>&1 || die "xray client config invalid"

  setsid sing-box run -c "$OBFS_DIR/singbox-server.json" >"$OBFS_DIR/singbox.log" 2>&1 &
  echo $! >"$OBFS_DIR/singbox.pid"
  setsid xray run -c "$OBFS_DIR/xray-client.json" >"$OBFS_DIR/xray.log" 2>&1 &
  echo $! >"$OBFS_DIR/xray.pid"
  wait_port 127.0.0.1 "$REALITY_PORT" "sing-box REALITY server"
  wait_port 127.0.0.1 "$SOCKS_PORT" "Xray SOCKS5 client"

  log "adb reverse tcp:$SOCKS_PORT (device 127.0.0.1:$SOCKS_PORT -> host Xray SOCKS)"
  adb reverse "tcp:$SOCKS_PORT" "tcp:$SOCKS_PORT" || log "adb reverse failed (no device?) — set it up manually"

  cat >&2 <<EOF

\033[32m[local-stack] OBFS UP\033[0m  logs: $OBFS_DIR/{singbox,xray}.log

Point the app's per-network Obfuscation section at a SOCKS5 proxy:
  Mode: SOCKS5   Host: 127.0.0.1   Port: $SOCKS_PORT
(then keep the soju endpoint 127.0.0.1:$SOJU_PORT as usual — traffic tunnels through REALITY.)

Client share link (import into any vless client):
  $(obfs_share_link "$uuid" "$pub" "$sid")

Prove it at the socket level (no app needed):
  $0 obfs-validate
Tear down: $0 obfs-down
EOF
}

obfs_down() {
  log "stopping sing-box/xray reality layer"
  obfs_stop_pids
  adb reverse --remove "tcp:$SOCKS_PORT" 2>/dev/null || true
  log "obfs-down (configs kept at $OBFS_DIR)"
}

obfs_validate() {
  [ -f "$OBFS_DIR/reality.env" ] || die "reality layer not up — run '$0 obfs-up' first"
  nc -z 127.0.0.1 "$SOCKS_PORT" 2>/dev/null || die "SOCKS5 proxy not listening on 127.0.0.1:$SOCKS_PORT (run '$0 obfs-up')"
  log "validating IRC/TLS to soju THROUGH the REALITY SOCKS5 tunnel (127.0.0.1:$SOCKS_PORT)…"
  SOCKS_PORT="$SOCKS_PORT" SOJU_PORT="$SOJU_PORT" python3 - <<'PY'
import os, socket, ssl, struct, sys

SOCKS_PORT = int(os.environ["SOCKS_PORT"])
DEST_HOST, DEST_PORT = "127.0.0.1", int(os.environ["SOJU_PORT"])

# --- SOCKS5 (no auth), CONNECT to soju as a DOMAIN NAME (remote DNS, leak-free) ---
s = socket.create_connection(("127.0.0.1", SOCKS_PORT), timeout=10)
s.sendall(b"\x05\x01\x00")
if s.recv(2) != b"\x05\x00":
    sys.exit("FAIL: SOCKS5 method negotiation rejected")
host = DEST_HOST.encode()
s.sendall(b"\x05\x01\x00\x03" + bytes([len(host)]) + host + struct.pack(">H", DEST_PORT))
rep = s.recv(4)
if rep[1] != 0x00:
    sys.exit(f"FAIL: SOCKS5 CONNECT failed (rep={rep[1]}) — REALITY tunnel not established")
s.recv(6)  # BND.ADDR/PORT
print(f"  SOCKS5 CONNECT to soju :{DEST_PORT} ok (through the REALITY tunnel)")

# --- TLS over the tunneled socket; soju's cert is self-signed, so don't verify the chain ---
ctx = ssl._create_unverified_context()
tls = ctx.wrap_socket(s, server_hostname=DEST_HOST)
print(f"  TLS 1.3 handshake to soju ok (cipher {tls.cipher()[0]})")

# --- IRC: CAP LS must return soju's capability banner ---
tls.sendall(b"CAP LS 302\r\n")
tls.settimeout(8)
data = b""
while b"\r\n" not in data:
    chunk = tls.recv(4096)
    if not chunk:
        break
    data += chunk
banner = data.decode(errors="replace").splitlines()[0] if data else ""
tls.close()
if "CAP" not in banner:
    sys.exit(f"FAIL: no IRC CAP response through the tunnel (got: {banner!r})")
print(f"  IRC banner: {banner[:120]}")
print("PASS: IRC/TLS reached soju END-TO-END through VLESS+REALITY")
PY
}

# ---- Cross-core REALITY compatibility proof -----------------------------------------------
#
# This is intentionally the inverse of obfs-*: Xray terminates the REALITY inbound and
# sing-box is the VLESS+REALITY SOCKS client. It is the gate for an embedded libbox
# client: do not infer client compatibility from the existing sing-box-server/Xray-client
# test. Both hops remain on loopback; only the final direct outbound reaches local soju.
#
#   validator -> sing-box SOCKS -> VLESS+REALITY -> Xray -> 127.0.0.1:6697 (soju)

xray_obfs_share_link() { # uuid pubkey shortid
  printf 'vless://%s@127.0.0.1:%s?encryption=none&security=reality&sni=%s&fp=chrome&pbk=%s&sid=%s&type=tcp#motd-local-xray-reality\n' \
    "$1" "$XRAY_REALITY_PORT" "$HANDSHAKE_DOMAIN" "$2" "$3"
}

xray_obfs_stop_pids() {
  for p in xray singbox; do
    local f="$XRAY_OBFS_DIR/$p.pid"
    if [ -f "$f" ]; then kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; fi
  done
  local negative="$XRAY_OBFS_DIR/singbox-negative.pid"
  if [ -f "$negative" ]; then kill "$(cat "$negative")" 2>/dev/null || true; rm -f "$negative"; fi
}

xray_obfs_up() {
  xray_obfs_stop_pids
  for pp in "$XRAY_REALITY_PORT" "$SINGBOX_SOCKS_PORT"; do
    if nc -z 127.0.0.1 "$pp" 2>/dev/null; then
      die "port $pp already in use. Inspect: ss -ltnp | grep :$pp — kill it, run '$0 obfs-xray-down', or set MOTD_XRAY_REALITY_PORT/MOTD_SINGBOX_SOCKS_PORT"
    fi
  done
  [ -S "$ADMIN_SOCK" ] || die "base stack is not up — run '$0 up' before '$0 obfs-xray-up'"
  mkdir -p "$XRAY_OBFS_DIR"

  log "reality compatibility: generate x25519 keypair + short-id + uuid"
  local kp priv pub uuid sid
  kp="$(sing-box generate reality-keypair)"
  priv="$(printf '%s\n' "$kp" | awk '/PrivateKey/{print $2}')"
  pub="$(printf '%s\n' "$kp" | awk '/PublicKey/{print $2}')"
  uuid="$(sing-box generate uuid)"
  sid="$(sing-box generate rand 8 --hex)"
  printf 'UUID=%s\nPRIV=%s\nPUB=%s\nSID=%s\n' "$uuid" "$priv" "$pub" "$sid" >"$XRAY_OBFS_DIR/reality.env"

  log "xray: REALITY server on 127.0.0.1:$XRAY_REALITY_PORT (handshake $HANDSHAKE_DOMAIN) -> soju :$SOJU_PORT"
  cat >"$XRAY_OBFS_DIR/xray-server.json" <<EOF
{
  "log": { "loglevel": "warning" },
  "inbounds": [ { "tag": "vless-in", "listen": "127.0.0.1", "port": $XRAY_REALITY_PORT,
    "protocol": "vless", "settings": { "clients": [ { "id": "$uuid" } ], "decryption": "none" },
    "streamSettings": { "network": "tcp", "security": "reality",
      "realitySettings": { "show": false, "dest": "$HANDSHAKE_DOMAIN:443", "xver": 0,
        "serverNames": [ "$HANDSHAKE_DOMAIN" ], "privateKey": "$priv", "shortIds": [ "$sid" ] } } } ],
  "outbounds": [ { "tag": "direct", "protocol": "freedom" } ]
}
EOF

  log "sing-box: REALITY client with a SOCKS5 inbound on 127.0.0.1:$SINGBOX_SOCKS_PORT"
  cat >"$XRAY_OBFS_DIR/singbox-client.json" <<EOF
{
  "log": { "level": "info" },
  "inbounds": [ { "type": "socks", "tag": "socks-in", "listen": "127.0.0.1", "listen_port": $SINGBOX_SOCKS_PORT } ],
  "outbounds": [ { "type": "vless", "tag": "vless-out", "server": "127.0.0.1", "server_port": $XRAY_REALITY_PORT,
    "uuid": "$uuid", "tls": { "enabled": true, "server_name": "$HANDSHAKE_DOMAIN",
      "utls": { "enabled": true, "fingerprint": "chrome" },
      "reality": { "enabled": true, "public_key": "$pub", "short_id": "$sid" } } } ],
  "route": { "final": "vless-out" }
}
EOF

  xray run -test -c "$XRAY_OBFS_DIR/xray-server.json" >/dev/null 2>&1 || die "Xray REALITY server config invalid"
  sing-box check -c "$XRAY_OBFS_DIR/singbox-client.json" || die "sing-box REALITY client config invalid"

  setsid xray run -c "$XRAY_OBFS_DIR/xray-server.json" >"$XRAY_OBFS_DIR/xray.log" 2>&1 &
  echo $! >"$XRAY_OBFS_DIR/xray.pid"
  setsid sing-box run -c "$XRAY_OBFS_DIR/singbox-client.json" >"$XRAY_OBFS_DIR/singbox.log" 2>&1 &
  echo $! >"$XRAY_OBFS_DIR/singbox.pid"
  wait_port 127.0.0.1 "$XRAY_REALITY_PORT" "Xray REALITY server"
  wait_port 127.0.0.1 "$SINGBOX_SOCKS_PORT" "sing-box REALITY client"
  log "adb reverse tcp:$XRAY_REALITY_PORT (device loopback -> host Xray REALITY ingress)"
  adb reverse "tcp:$XRAY_REALITY_PORT" "tcp:$XRAY_REALITY_PORT" || log "adb reverse failed (no device?) — set it up manually"
  log "adb reverse tcp:$SINGBOX_SOCKS_PORT (device loopback -> host sing-box SOCKS)"
  adb reverse "tcp:$SINGBOX_SOCKS_PORT" "tcp:$SINGBOX_SOCKS_PORT" || log "adb reverse failed (no device?) — set it up manually"
  cat >&2 <<EOF

\033[32m[local-stack] OBFS XRAY UP\033[0m  logs: $XRAY_OBFS_DIR/{xray,singbox}.log

For the arm64 app's Embedded REALITY mode, keep the bouncer endpoint as
127.0.0.1:$SOJU_PORT and paste this local-only VLESS URI:
  $(xray_obfs_share_link "$uuid" "$pub" "$sid")

Socket checks:
  $0 obfs-xray-validate
  $0 obfs-xray-history-check
EOF
}

xray_obfs_down() {
  log "stopping Xray-server/sing-box-client compatibility layer"
  xray_obfs_stop_pids
  adb reverse --remove "tcp:$XRAY_REALITY_PORT" 2>/dev/null || true
  adb reverse --remove "tcp:$SINGBOX_SOCKS_PORT" 2>/dev/null || true
  log "obfs-xray-down (configs kept at $XRAY_OBFS_DIR)"
}

xray_obfs_validate() {
  [ -f "$XRAY_OBFS_DIR/reality.env" ] || die "compatibility layer not up — run '$0 obfs-xray-up' first"
  nc -z 127.0.0.1 "$SINGBOX_SOCKS_PORT" 2>/dev/null || die "sing-box SOCKS proxy not listening on 127.0.0.1:$SINGBOX_SOCKS_PORT (run '$0 obfs-xray-up')"
  log "validating IRC/TLS through sing-box-client -> Xray-server REALITY tunnel…"
  SOCKS_PORT="$SINGBOX_SOCKS_PORT" SOJU_PORT="$SOJU_PORT" python3 - <<'PY'
import os, socket, ssl, struct, sys

s = socket.create_connection(("127.0.0.1", int(os.environ["SOCKS_PORT"])), timeout=10)
s.sendall(b"\x05\x01\x00")
if s.recv(2) != b"\x05\x00": sys.exit("FAIL: SOCKS5 method negotiation rejected")
host, port = b"127.0.0.1", int(os.environ["SOJU_PORT"])
s.sendall(b"\x05\x01\x00\x03" + bytes([len(host)]) + host + struct.pack(">H", port))
rep = s.recv(4)
if len(rep) != 4 or rep[1] != 0: sys.exit(f"FAIL: SOCKS5 CONNECT failed ({rep!r})")
atyp = rep[3]
if atyp == 1: s.recv(6)       # IPv4 address + port
elif atyp == 4: s.recv(18)    # IPv6 address + port
elif atyp == 3:
    length = s.recv(1)
    if not length: sys.exit("FAIL: truncated SOCKS5 domain reply")
    s.recv(length[0] + 2)
else: sys.exit(f"FAIL: unknown SOCKS5 reply address type {atyp}")
print(f"  SOCKS5 CONNECT to soju :{port} ok (through sing-box -> Xray REALITY)")
tls = ssl._create_unverified_context().wrap_socket(s, server_hostname="127.0.0.1")
print(f"  TLS handshake to soju ok (cipher {tls.cipher()[0]})")
tls.sendall(b"CAP LS 302\r\n")
tls.settimeout(8); data = tls.recv(4096); tls.close()
banner = data.decode(errors="replace").splitlines()[0] if data else ""
if "CAP" not in banner: sys.exit(f"FAIL: no IRC CAP response through the tunnel (got {banner!r})")
print(f"  IRC banner: {banner[:120]}")
print("PASS: IRC/TLS reached soju through sing-box client + Xray REALITY server")
PY
}

xray_obfs_history_check() {
  [ -f "$XRAY_OBFS_DIR/reality.env" ] || die "compatibility layer not up — run '$0 obfs-xray-up' first"
  nc -z 127.0.0.1 "$SINGBOX_SOCKS_PORT" 2>/dev/null || die "sing-box SOCKS proxy not listening on 127.0.0.1:$SINGBOX_SOCKS_PORT (run '$0 obfs-xray-up')"
  log "proving retained CHATHISTORY through sing-box-client -> Xray REALITY tunnel"
  python3 "$REPO/test/e2e/fixtures/chathistory-probe.py" \
    --port "$SOJU_PORT" \
    --socks-host 127.0.0.1 \
    --socks-port "$SINGBOX_SOCKS_PORT"
}

# A positive cross-core tunnel is insufficient if a client accepts any REALITY key. Run a second,
# isolated sing-box SOCKS client with one character of the server public key changed and assert that
# SOCKS CONNECT is rejected. The normal proof layer remains running throughout.
xray_obfs_negative() {
  [ -f "$XRAY_OBFS_DIR/reality.env" ] || die "compatibility layer not up — run '$0 obfs-xray-up' first"
  if nc -z 127.0.0.1 "$SINGBOX_BAD_SOCKS_PORT" 2>/dev/null; then
    die "port $SINGBOX_BAD_SOCKS_PORT already in use; run '$0 obfs-xray-down' or set MOTD_SINGBOX_BAD_SOCKS_PORT"
  fi
  # shellcheck disable=SC1090
  . "$XRAY_OBFS_DIR/reality.env"
  local bad_pub
  bad_pub="A${PUB:1}"
  [ "$bad_pub" = "$PUB" ] && bad_pub="B${PUB:1}"
  cat >"$XRAY_OBFS_DIR/singbox-invalid-key.json" <<EOF
{
  "log": { "level": "info" },
  "inbounds": [ { "type": "socks", "tag": "socks-in", "listen": "127.0.0.1", "listen_port": $SINGBOX_BAD_SOCKS_PORT } ],
  "outbounds": [ { "type": "vless", "tag": "vless-out", "server": "127.0.0.1", "server_port": $XRAY_REALITY_PORT,
    "uuid": "$UUID", "tls": { "enabled": true, "server_name": "$HANDSHAKE_DOMAIN",
      "utls": { "enabled": true, "fingerprint": "chrome" },
      "reality": { "enabled": true, "public_key": "$bad_pub", "short_id": "$SID" } } } ],
  "route": { "final": "vless-out" }
}
EOF
  sing-box check -c "$XRAY_OBFS_DIR/singbox-invalid-key.json" || die "invalid-key sing-box config unexpectedly invalid"
  setsid sing-box run -c "$XRAY_OBFS_DIR/singbox-invalid-key.json" >"$XRAY_OBFS_DIR/singbox-invalid-key.log" 2>&1 &
  echo $! >"$XRAY_OBFS_DIR/singbox-negative.pid"
  wait_port 127.0.0.1 "$SINGBOX_BAD_SOCKS_PORT" "invalid-key sing-box client"
  log "asserting the wrong REALITY public key cannot CONNECT to soju"
  SOCKS_PORT="$SINGBOX_BAD_SOCKS_PORT" SOJU_PORT="$SOJU_PORT" python3 - <<'PY'
import os, socket, struct, sys

s = socket.create_connection(("127.0.0.1", int(os.environ["SOCKS_PORT"])), timeout=10)
s.sendall(b"\x05\x01\x00")
if s.recv(2) != b"\x05\x00":
    sys.exit("FAIL: invalid-key client did not provide SOCKS5")
host, port = b"127.0.0.1", int(os.environ["SOJU_PORT"])
s.sendall(b"\x05\x01\x00\x03" + bytes([len(host)]) + host + struct.pack(">H", port))
try:
    reply = s.recv(4)
except socket.timeout:
    reply = b""
finally:
    s.close()
if len(reply) == 4 and reply[1] == 0:
    sys.exit("FAIL: wrong REALITY public key unexpectedly established a SOCKS tunnel")
print("PASS: wrong REALITY public key was rejected")
PY
  local negative="$XRAY_OBFS_DIR/singbox-negative.pid"
  kill "$(cat "$negative")" 2>/dev/null || true
  rm -f "$negative"
}

case "$CMD" in
  up) up ;;
  down) down ;;
  seed)
    if [ "$STACK_PROFILE" = showcase ]; then showcase_seed; else sh "$PROVISION" seed; fi
    ;;
  showcase) showcase_seed ;;
  showcase-hold) showcase_hold ;;
  burst) sh "$PROVISION" burst ;;
  jpq) sh "$PROVISION" jpq ;;
  push) sh "$PROVISION" push ;;
  canonical) sh "$PROVISION" canonical ;;
  reconnect-gap) sh "$PROVISION" reconnect-gap "${2:-}" ;;
  reconnect-current) sh "$PROVISION" reconnect-current "${2:-}" ;;
  pause-soju) signal_soju STOP paused ;;
  resume-soju) signal_soju CONT resumed ;;
  stop-soju) stop_soju_for_reconnect ;;
  start-soju) start_soju_for_reconnect ;;
  status) status ;;
  control-check) control_check ;;
  history-check) history_check "${2:-}" ;;
  read-marker-check) read_marker_check ;;
  invite-check) invite_check ;;
  ready-up) ready_up ;;
  ready-check) ready_check ;;
  ready-down) ready_down ;;
  obfs-up) obfs_up ;;
  obfs-down) obfs_down ;;
  obfs-validate) obfs_validate ;;
  obfs-xray-up) xray_obfs_up ;;
  obfs-xray-down) xray_obfs_down ;;
  obfs-xray-validate) xray_obfs_validate ;;
  obfs-xray-history-check) xray_obfs_history_check ;;
  obfs-xray-negative) xray_obfs_negative ;;
  *) die "unknown command '$CMD' (want up|down|seed|showcase|showcase-hold|burst|jpq|push|canonical|reconnect-gap|reconnect-current|pause-soju|resume-soju|stop-soju|start-soju|status|history-check|control-check|read-marker-check|invite-check|ready-up|ready-check|ready-down|obfs-up|obfs-down|obfs-validate|obfs-xray-up|obfs-xray-down|obfs-xray-validate|obfs-xray-history-check|obfs-xray-negative)" ;;
esac
