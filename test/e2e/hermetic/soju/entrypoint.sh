#!/bin/sh
# test/e2e/hermetic/soju/entrypoint.sh — boot soju for the hermetic e2e stack.
#
# Steps:
#   1. Generate a self-signed TLS cert/key for the app-facing ircs:// listener
#      (the app's TOFU dialog trusts it on first connect; regenerated each run).
#   2. Start soju in the background against /etc/soju/soju.config.
#   3. Wait for the admin socket, then provision (idempotently):
#        - user `motd` / `motdtest` (the SASL account the app logs in with),
#        - network `libera` pointing at the ergo upstream, auto-identifying to
#          the ergo account `motd` and auto-joining `##motdtest`.
#   4. Hand the foreground back to soju (exec via `wait`).
#
# All credentials here are ephemeral LOCAL test creds, not secrets.
set -eu

CONFIG=/etc/soju/soju.config
TLS_DIR=/etc/soju/tls
ADMIN_SOCK=/var/lib/soju/admin.sock

# Upstream ergo (internal compose hostname:port) and the accounts/channel.
ERGO_ADDR="${ERGO_ADDR:-ergo:6667}"
ERGO_ACCOUNT="${ERGO_ACCOUNT:-motd}"
ERGO_PASSWORD="${ERGO_PASSWORD:-motdupstream}"
SOJU_USER="${SOJU_USER:-motd}"
SOJU_PASS="${SOJU_PASS:-motdtest}"
NETWORK_NAME="${NETWORK_NAME:-libera}"
TEST_CHANNEL="${TEST_CHANNEL:-##motdtest}"
NICK="${NICK:-motdadb}"

log() { printf '[soju-entrypoint] %s\n' "$*" >&2; }

# 1. Self-signed cert for the app-facing TLS listener.
if [ ! -f "$TLS_DIR/cert.pem" ]; then
  mkdir -p "$TLS_DIR"
  log "generating self-signed TLS cert (CN=localhost)"
  openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$TLS_DIR/key.pem" -out "$TLS_DIR/cert.pem" \
    -days 3650 -subj "/CN=localhost" \
    -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:10.0.2.2" >/dev/null 2>&1
fi

# 2. Start soju in the background.
log "starting soju"
soju -config "$CONFIG" &
SOJU_PID=$!

# 3. Wait for the admin socket to appear.
i=0
while [ ! -S "$ADMIN_SOCK" ]; do
  i=$((i + 1))
  if [ "$i" -gt 60 ]; then
    log "FATAL: admin socket did not appear within 60s"
    kill "$SOJU_PID" 2>/dev/null || true
    exit 1
  fi
  # Bail out early if soju already died.
  if ! kill -0 "$SOJU_PID" 2>/dev/null; then
    log "FATAL: soju exited during startup"
    exit 1
  fi
  sleep 1
done
log "admin socket up"

# sojuctl helper: sends a BouncerServ command over the admin socket (as admin).
ctl() { sojuctl -config "$CONFIG" "$@"; }

# Provision the app's soju user (idempotent: ignore "already exists"). The admin
# socket runs as an implicit admin, so `user create` is permitted. NOTE: soju's
# boolean flags take the ATTACHED form (`-admin`, `-enabled=true`); the detached
# `-admin true` is rejected as an unexpected argument (validated against 0.10.1).
log "provisioning user $SOJU_USER"
ctl user create -username "$SOJU_USER" -password "$SOJU_PASS" -realname "motd e2e" -admin 2>/dev/null \
  || log "user $SOJU_USER already exists (ok)"

# Provision the upstream network as "$NETWORK_NAME" for that user, via
# `user run <user> <command>` (the admin-only way to act as another user).
#   -addr irc+insecure://ergo:6667  → plaintext upstream inside the compose net
#   -connect-command (x2)           → identify to the ergo `motd` account, then
#                                     JOIN the seed channel. soju runs these raw
#                                     right after connecting; being joined is
#                                     what makes soju log ##motdtest into its
#                                     message-store (so CHATHISTORY has content).
# There is no `channel create -network` in `user run` (it needs an interactive
# "current network"), so we JOIN via a connect-command instead of saving it.
log "provisioning network $NETWORK_NAME -> $ERGO_ADDR (auto-join $TEST_CHANNEL)"
ctl user run "$SOJU_USER" network create \
  -addr "irc+insecure://${ERGO_ADDR}" \
  -name "$NETWORK_NAME" \
  -nick "$NICK" \
  -username "$ERGO_ACCOUNT" \
  -connect-command "PRIVMSG NickServ :IDENTIFY ${ERGO_ACCOUNT} ${ERGO_PASSWORD}" \
  -connect-command "JOIN ${TEST_CHANNEL}" \
  -enabled=true 2>/dev/null \
  || log "network $NETWORK_NAME already exists (ok)"

log "provisioning complete; soju is serving on ircs://:6697"

# 4. Foreground: track soju.
wait "$SOJU_PID"
