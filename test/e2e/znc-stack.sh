#!/usr/bin/env bash
# Native ZNC fixture backed by the same deterministic Ergo network as local-stack.sh.
set -euo pipefail

CMD="${1:-up}"
RUN="${MOTD_ZNC_STACK_DIR:-/tmp/motd-znc-stack}"
BASE_RUN="${MOTD_STACK_DIR:-/tmp/motd-stack}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
BASE="$REPO/test/e2e/local-stack.sh"
PROBE="$REPO/test/e2e/fixtures/znc-probe.py"
ZNC_PORT="${MOTD_ZNC_PORT:-6698}"
ERGO_PORT=6667
ZNC_USER=motd
ZNC_PASS=motdtest

log() { printf '\033[36m[znc-stack]\033[0m %s\n' "$*" >&2; }
die() { printf '\033[31m[znc-stack] FATAL:\033[0m %s\n' "$*" >&2; exit 1; }

for binary in znc nc openssl python3; do
  if ! command -v "$binary" >/dev/null 2>&1; then
    log "fetching ZNC fixture binaries via nix shell…"
    exec nix shell nixpkgs#znc nixpkgs#netcat-openbsd nixpkgs#openssl nixpkgs#python3 \
      -c "$0" "$CMD"
  fi
done

adb() {
  local binary
  binary="$(type -P adb 2>/dev/null || true)"
  if [ -n "$binary" ]; then "$binary" "$@"; else nix develop "$REPO" -c adb "$@"; fi
}

wait_port() {
  local count=0
  until nc -z 127.0.0.1 "$1" 2>/dev/null; do
    count=$((count + 1))
    [ "$count" -gt 60 ] && die "$2 did not listen on 127.0.0.1:$1"
    sleep 1
  done
}

stop_znc() {
  if [ -f "$RUN/znc.pid" ]; then
    local pid
    pid="$(cat "$RUN/znc.pid")"
    kill "$pid" 2>/dev/null || true
    for _ in 1 2 3 4 5; do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null || true
    rm -f "$RUN/znc.pid"
  fi
}

write_config() {
  mkdir -p "$RUN/configs"
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$RUN/key.pem" -out "$RUN/cert.pem" -days 3650 -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2" >/dev/null 2>&1
  # ZNC's default TLS path is DATADIR/znc.pem and expects key + certificate in one file.
  cat "$RUN/key.pem" "$RUN/cert.pem" >"$RUN/znc.pem"
  cat >"$RUN/configs/znc.conf" <<EOF
Version = 1.10.1
<Listener motd>
    Port = $ZNC_PORT
    IPv4 = true
    IPv6 = false
    SSL = true
</Listener>
LoadModule = corecaps
LoadModule = saslplainauth

<User $ZNC_USER>
    <Pass password>
        Method = SHA256
        Hash = 7390a1e6a5bf234a4b2001f297bda2973cbbeefe68fdd6d95f5e3f2461944738
        Salt = 5)x(LnH6B.luoP.Jo4oT
    </Pass>
    Admin = true
    AutoClearChanBuffer = false
    AutoClearQueryBuffer = false
    ChanBufferSize = 500
    QueryBufferSize = 500
    Nick = motdadb
    AltNick = motdadb_
    Ident = motd
    RealName = motd local ZNC
    LoadModule = chansaver
    LoadModule = controlpanel

    <Network libera>
        Server = 127.0.0.1 $ERGO_PORT
        Perform = PRIVMSG NickServ :IDENTIFY motd motdupstream
        <Chan ##motdtest>
        </Chan>
    </Network>
</User>
EOF
}

up() {
  stop_znc
  if nc -z 127.0.0.1 "$ZNC_PORT" 2>/dev/null; then
    die "port $ZNC_PORT is already occupied; inspect its exact PID before retrying"
  fi
  MOTD_STACK_DIR="$BASE_RUN" "$BASE" up
  mkdir -p "$RUN"
  : >"$RUN/owns-base-stack"
  rm -rf "$RUN/configs" "$RUN/moddata" "$RUN/users" "$RUN/znc.pem" \
    "$RUN/cert.pem" "$RUN/key.pem" "$RUN/znc.log" "$RUN/observed-caps.txt"
  write_config
  log "starting ZNC 1.10.1 with TLS on :$ZNC_PORT"
  setsid znc -f --no-color -d "$RUN" >"$RUN/znc.log" 2>&1 &
  echo $! >"$RUN/znc.pid"
  wait_port "$ZNC_PORT" ZNC
  adb reverse "tcp:$ZNC_PORT" "tcp:$ZNC_PORT" >/dev/null
  python3 "$PROBE" caps --host 127.0.0.1 --port "$ZNC_PORT" \
    --output "$RUN/observed-caps.txt"
  log "ready: TLS 127.0.0.1:$ZNC_PORT, login $ZNC_USER/libera / $ZNC_PASS"
}

status() {
  if [ -f "$RUN/znc.pid" ] && kill -0 "$(cat "$RUN/znc.pid")" 2>/dev/null; then
    echo "znc   running (pid $(cat "$RUN/znc.pid"), tls :$ZNC_PORT)"
  else
    echo "znc   stopped"
  fi
  MOTD_STACK_DIR="$BASE_RUN" "$BASE" status || true
  [ -f "$RUN/observed-caps.txt" ] && sed 's/^/caps  /' "$RUN/observed-caps.txt"
}

down() {
  stop_znc
  adb reverse --remove "tcp:$ZNC_PORT" >/dev/null 2>&1 || true
  if [ -f "$RUN/owns-base-stack" ]; then
    MOTD_STACK_DIR="$BASE_RUN" "$BASE" down
    rm -f "$RUN/owns-base-stack"
  fi
  log "down (state and logs kept at $RUN)"
}

case "$CMD" in
  up) up ;;
  status) status ;;
  seed) MOTD_STACK_DIR="$BASE_RUN" "$BASE" seed ;;
  probe) python3 "$PROBE" smoke --host 127.0.0.1 --port "$ZNC_PORT" --ergo-port "$ERGO_PORT" ;;
  down) down ;;
  *) die "usage: $0 {up|status|seed|probe|down}" ;;
esac
