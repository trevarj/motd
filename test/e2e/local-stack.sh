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
#   ./test/e2e/local-stack.sh seed      # re-post the seed messages into ##motdtest
#   ./test/e2e/local-stack.sh status    # show pids + soju network status
#   ./test/e2e/local-stack.sh obfs-up   # VLESS+REALITY layer: sing-box server + Xray SOCKS client
#   ./test/e2e/local-stack.sh obfs-down # stop the reality layer + drop its adb reverse
#   ./test/e2e/local-stack.sh obfs-validate  # socket-level proof: IRC/TLS to soju THROUGH reality
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

# Obfuscation layer (plans/20 Phase 1 validation). REALITY server on :8443 (avoid clashing with a
# real 443), a local SOCKS5 the app dials on 127.0.0.1:1080, and a handshake domain to impersonate.
# www.microsoft.com does NOT work as a REALITY steal target from every host / with the uTLS Chrome
# fingerprint; www.cloudflare.com is a reliable default. Override any of these via the environment.
REALITY_PORT="${MOTD_REALITY_PORT:-8443}"
SOCKS_PORT="${MOTD_SOCKS_PORT:-1080}"
HANDSHAKE_DOMAIN="${MOTD_HANDSHAKE_DOMAIN:-www.cloudflare.com}"
OBFS_DIR="$RUN/obfs"

log() { printf '\033[36m[local-stack]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31m[local-stack] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

# The obfs layer needs sing-box (REALITY server), xray (REALITY client exposing SOCKS5) and
# python3 (the socket-level validator); the base layer needs the bouncer binaries. Re-exec inside a
# nix shell providing whatever the command needs but is missing from PATH.
need_reexec=false
case "$CMD" in
  obfs-*)
    for b in soju ergo sing-box xray python3; do
      command -v "$b" >/dev/null 2>&1 || { need_reexec=true; break; }
    done ;;
  *)
    command -v soju >/dev/null 2>&1 && command -v ergo >/dev/null 2>&1 || need_reexec=true ;;
esac
if [ "$need_reexec" = true ]; then
  log "fetching stack binaries via nix shell…"
  exec nix shell nixpkgs#ergochat nixpkgs#soju nixpkgs#netcat-openbsd nixpkgs#openssl \
    nixpkgs#sing-box nixpkgs#xray nixpkgs#python3 \
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

case "$CMD" in
  up) up ;;
  down) down ;;
  seed) sh "$PROVISION" seed ;;
  status) status ;;
  obfs-up) obfs_up ;;
  obfs-down) obfs_down ;;
  obfs-validate) obfs_validate ;;
  *) die "unknown command '$CMD' (want up|down|seed|status|obfs-up|obfs-down|obfs-validate)" ;;
esac
