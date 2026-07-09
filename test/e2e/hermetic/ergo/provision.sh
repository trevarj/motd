#!/bin/sh
# test/e2e/hermetic/ergo/provision.sh — register accounts / seed history on ergo.
#
# Runs as a ONE-SHOT container, in one of two MODEs (arg 1, default "all"):
#
#   register  Register the ergo accounts + the seed channel. Runs BEFORE soju
#             connects (so soju can identify to the upstream `motd` account and
#             auto-join ##motdtest):
#               1. account motd/motdupstream    (soju identifies to this)
#               2. account motdadb2/motdadb2pass (the seed identity)
#               3. as motdadb2: identify, join ##motdtest, register the channel,
#                  set a topic.
#
#   seed      Post the deterministic seed messages into ##motdtest. Runs AFTER
#             soju is connected and joined, so soju OBSERVES the messages live
#             and writes them to its message-store. This is what makes the app's
#             CHATHISTORY backfill (Phase C) return content — soju only logs
#             traffic it sees while connected, so seeding must happen post-connect.
#
#   all       register then seed (for standalone/local use without soju ordering).
#
# Talks plaintext IRC to ergo over TCP using busybox `nc`. Idempotent: re-running
# `register` is a no-op (ergo replies "already registered", ignored); re-running
# `seed` appends the messages again.
#
# All credentials are ephemeral LOCAL test creds, not secrets.
set -eu

MODE="${1:-all}"

ERGO_HOST="${ERGO_HOST:-ergo}"
ERGO_PORT="${ERGO_PORT:-6667}"
TEST_CHANNEL="${TEST_CHANNEL:-##motdtest}"
APP_NICK="${APP_NICK:-motdadb}"

UP_ACCOUNT="${UP_ACCOUNT:-motd}"
UP_PASS="${UP_PASS:-motdupstream}"
SEED_NICK="${SEED_NICK:-motdadb2}"
SEED_PASS="${SEED_PASS:-motdadb2pass}"

log() { printf '[ergo-provision:%s] %s\n' "$MODE" "$*" >&2; }

# feed_irc: pipe CRLF IRC lines (generator on fd 0) into ergo over nc, holding
# the connection open with a trailing sleep so ergo drains the burst before EOF.
# Server output is echoed to stderr for logs. Generators pace themselves with
# short sleeps so registration completes before JOIN/PRIVMSG.
feed_irc() {
  # shellcheck disable=SC2094 # generator writes, nc reads; distinct streams.
  { cat; sleep 4; } | nc "$ERGO_HOST" "$ERGO_PORT" 2>/dev/null | sed 's/^/[ergo] /' >&2 || true
}

wait_for_ergo() {
  log "waiting for ergo at ${ERGO_HOST}:${ERGO_PORT}"
  i=0
  while ! nc -z "$ERGO_HOST" "$ERGO_PORT" 2>/dev/null; do
    i=$((i + 1))
    [ "$i" -gt 60 ] && { log "FATAL: ergo not reachable"; exit 1; }
    sleep 1
  done
  log "ergo reachable"
}

# Register an ergo account (allow-before-connect is enabled).
register_account() {
  _nick="$1"; _pass="$2"
  log "registering ergo account $_nick"
  {
    printf 'NICK %s\r\n' "$_nick"
    printf 'USER %s 0 * :motd e2e\r\n' "$_nick"
    sleep 2                                   # let registration reach 001
    printf 'NICKSERV REGISTER %s *\r\n' "$_pass"
    sleep 1
    printf 'QUIT :registered\r\n'
  } | feed_irc
}

do_register() {
  register_account "$UP_ACCOUNT" "$UP_PASS"
  register_account "$SEED_NICK" "$SEED_PASS"
  # As motdadb2: identify, join, register the channel + set a topic so they
  # persist. NICKSERV IDENTIFY is a single unsolicited command (avoids the SASL
  # challenge/response ordering a fire-and-forget stream can't satisfy).
  log "creating $TEST_CHANNEL (topic + registration)"
  {
    printf 'NICK %s\r\n' "$SEED_NICK"
    printf 'USER %s 0 * :motd e2e seeder\r\n' "$SEED_NICK"
    printf 'NICKSERV IDENTIFY %s %s\r\n' "$SEED_NICK" "$SEED_PASS"
    sleep 2
    printf 'JOIN %s\r\n' "$TEST_CHANNEL"
    sleep 1
    printf 'CS REGISTER %s\r\n' "$TEST_CHANNEL"
    printf 'TOPIC %s :motd e2e seed channel — deterministic history\r\n' "$TEST_CHANNEL"
    printf 'QUIT :channel ready\r\n'
  } | feed_irc
}

do_seed() {
  # Post the seed messages while soju is connected + joined, so soju logs them
  # into its message-store for the app's CHATHISTORY backfill.
  log "seeding messages into $TEST_CHANNEL as $SEED_NICK"
  {
    printf 'NICK %s\r\n' "$SEED_NICK"
    printf 'USER %s 0 * :motd e2e seeder\r\n' "$SEED_NICK"
    printf 'NICKSERV IDENTIFY %s %s\r\n' "$SEED_NICK" "$SEED_PASS"
    sleep 2
    printf 'JOIN %s\r\n' "$TEST_CHANNEL"
    sleep 1
    printf 'PRIVMSG %s :hello, this is a seeded plain line\r\n' "$TEST_CHANNEL"
    printf 'PRIVMSG %s :second seeded line for history backfill\r\n' "$TEST_CHANNEL"
    printf 'PRIVMSG %s :\001ACTION waves at the room\001\r\n' "$TEST_CHANNEL"
    printf 'PRIVMSG %s :check this link https://example.com/page\r\n' "$TEST_CHANNEL"
    printf 'PRIVMSG %s :and an image https://example.com/cat.png\r\n' "$TEST_CHANNEL"
    printf 'PRIVMSG %s :%s: welcome to the seed channel\r\n' "$TEST_CHANNEL" "$APP_NICK"
    sleep 1
    printf 'QUIT :seed done\r\n'
  } | feed_irc
}

wait_for_ergo
case "$MODE" in
  register) do_register ;;
  seed)     do_seed ;;
  all)      do_register; do_seed ;;
  *) log "FATAL: unknown mode '$MODE' (want register|seed|all)"; exit 2 ;;
esac
log "done"
