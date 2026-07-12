#!/usr/bin/env bash
# test/e2e/fixtures/seed.sh — best-effort seeding of the soju test bouncer.
#
# Connects a second identity to the bouncer over raw TLS, registers (NICK/USER +
# SASL PLAIN, PASS fallback), JOINs $MOTD_TEST_CHANNEL, sets a topic, and posts a
# handful of PRIVMSGs (plain lines, an ACTION, a URL, an image URL) so the chat
# screen has deterministic history to assert against.
#
# Dependency-light: openssl only (`openssl s_client`). This is BEST-EFFORT and
# needs the bouncer reachable FROM THE HOST running this script. It does not
# touch the device/app.
#
# Config from env (or test/e2e/.env). SASL PLAIN is preferred; if the account
# only accepts a server password, set MOTD_SEED_PASS and it is sent via PASS.
#
#   MOTD_SOJU_HOST      bouncer host (required)
#   MOTD_SOJU_PORT      bouncer port (default 6697)
#   MOTD_SEED_USER      SASL username (default: MOTD_SOJU_USER)
#   MOTD_SEED_PASS      SASL/PASS password (default: MOTD_SOJU_PASS)
#   MOTD_SECOND_NICK    seeding nick (default motdadb2)
#   MOTD_TEST_CHANNEL   channel to seed (default ##motdtest)
#   MOTD_TARGET_NICK    nick to DM/mention (default motdadb)
set -euo pipefail

SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Source local overrides (untracked) if present.
if [ -f "${SEED_DIR}/../.env" ]; then
  # shellcheck disable=SC1091
  . "${SEED_DIR}/../.env"
fi

: "${MOTD_SOJU_HOST:=}"
: "${MOTD_SOJU_PORT:=6697}"
: "${MOTD_SEED_USER:=${MOTD_SOJU_USER:-}}"
: "${MOTD_SEED_PASS:=${MOTD_SOJU_PASS:-}}"
: "${MOTD_SECOND_NICK:=motdadb2}"
: "${MOTD_TEST_CHANNEL:=##motdtest}"
: "${MOTD_TARGET_NICK:=motdadb}"

if [ -z "$MOTD_SOJU_HOST" ] || [ -z "$MOTD_SEED_USER" ] || [ -z "$MOTD_SEED_PASS" ]; then
  echo "seed.sh: need MOTD_SOJU_HOST, MOTD_SEED_USER/MOTD_SOJU_USER, MOTD_SEED_PASS/MOTD_SOJU_PASS" >&2
  echo "Set them in the environment or test/e2e/.env (see .env.example)." >&2
  exit 2
fi

if ! command -v openssl >/dev/null 2>&1; then
  echo "seed.sh: openssl not found (required for the raw TLS connection)." >&2
  exit 127
fi

# SASL PLAIN payload: base64("\0user\0pass"). We build the NUL-delimited blob
# with printf and pipe through base64 (coreutils). soju accepts SASL PLAIN over
# TLS for the bouncer account.
sasl_b64="$(printf '\0%s\0%s' "$MOTD_SEED_USER" "$MOTD_SEED_PASS" | base64 | tr -d '\n')"

# Build the IRC line stream. CRLF terminates each line per RFC 1459/IRCv3. We
# request the sasl capability, authenticate, register, join, set topic, and
# post seed messages. A trailing sleep keeps the socket open long enough for the
# server to process the JOIN/PRIVMSG bursts before QUIT.
gen_lines() {
  printf 'CAP REQ :sasl\r\n'
  printf 'AUTHENTICATE PLAIN\r\n'
  printf 'AUTHENTICATE %s\r\n' "$sasl_b64"
  printf 'CAP END\r\n'
  # PASS is harmless/ignored when SASL succeeds; include it as a fallback path
  # for accounts that gate on a server password instead.
  printf 'PASS %s\r\n' "$MOTD_SEED_PASS"
  printf 'NICK %s\r\n' "$MOTD_SECOND_NICK"
  printf 'USER %s 0 * :motd e2e seeder\r\n' "$MOTD_SECOND_NICK"
  # Give registration a moment before joining.
  printf 'JOIN %s\r\n' "$MOTD_TEST_CHANNEL"
  printf 'TOPIC %s :motd e2e seed channel — deterministic history\r\n' "$MOTD_TEST_CHANNEL"
  printf 'PRIVMSG %s :hello, this is a seeded plain line\r\n' "$MOTD_TEST_CHANNEL"
  printf 'PRIVMSG %s :\001ACTION waves at the room\001\r\n' "$MOTD_TEST_CHANNEL"
  printf 'PRIVMSG %s :check this link https://example.com/page\r\n' "$MOTD_TEST_CHANNEL"
  printf 'PRIVMSG %s :and an image https://example.com/cat.png\r\n' "$MOTD_TEST_CHANNEL"
  # Mention + DM to the app's identity (drives mention/unread badges).
  printf 'PRIVMSG %s :%s: welcome\r\n' "$MOTD_TEST_CHANNEL" "$MOTD_TARGET_NICK"
  printf 'PRIVMSG %s :hi from the seeder\r\n' "$MOTD_TARGET_NICK"
}

echo "seed.sh: connecting ${MOTD_SECOND_NICK} to ${MOTD_SOJU_HOST}:${MOTD_SOJU_PORT} (TLS)…" >&2

# Feed the generated lines into openssl s_client. `-quiet` suppresses the
# session banner; `-crlf` is not used because we already emit CRLF ourselves.
# We keep the pipe open with a short sleep so the server drains our writes.
{
  gen_lines
  sleep 5
} | openssl s_client -connect "${MOTD_SOJU_HOST}:${MOTD_SOJU_PORT}" -quiet 2>/dev/null || {
  echo "seed.sh: openssl connection ended (best-effort). Verify bouncer reachability." >&2
}

echo "seed.sh: done (best-effort). Seeded ${MOTD_TEST_CHANNEL} with topic + messages." >&2
