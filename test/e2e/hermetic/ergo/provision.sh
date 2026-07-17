#!/bin/sh
# test/e2e/hermetic/ergo/provision.sh — register accounts / seed history on ergo.
#
# Runs as a ONE-SHOT container, in one of the MODEs below (arg 1, default "all"):
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
#   burst     Post a numbered live burst for auto-follow diagnostics.
#
#   jpq       Emit JOIN/PART/QUIT activity without chat messages.
#
#   push      Send one uniquely-tagged channel highlight and one direct message.
#
#   all       register then seed (for standalone/local use without soju ordering).
#
# The optional MOTD_STACK_PROFILE=showcase profile provisions several believable
# project channels (#guix, #debian, #emacs, #rust) with fictional nicks and
# messages. It is used only by the local screenshot workflow; the default
# ##motdtest fixture remains unchanged.
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
SEED_HOLD_SECONDS="${SEED_HOLD_SECONDS:-1}"
PUSH_TOKEN="${PUSH_TOKEN:-motd-unifiedpush}"
STACK_PROFILE="${MOTD_STACK_PROFILE:-default}"
SHOWCASE_CHANNELS="${MOTD_SHOWCASE_CHANNELS:-#guix #debian #emacs #rust}"

UP_ACCOUNT="${UP_ACCOUNT:-motd}"
UP_PASS="${UP_PASS:-motdupstream}"
SEED_NICK="${SEED_NICK:-motdadb2}"
SEED_PASS="${SEED_PASS:-motdadb2pass}"

log() { printf '[ergo-provision:%s] %s\n' "$MODE" "$*" >&2; }

fixture_channels() {
  if [ "$STACK_PROFILE" = showcase ]; then
    printf '%s\n' $SHOWCASE_CHANNELS
  else
    printf '%s\n' "$TEST_CHANNEL"
  fi
}

fixture_topic() {
  case "$1" in
    '#guix') printf '%s' 'Reproducible builds, small systems, kind packaging.' ;;
    '#debian') printf '%s' 'Stable ideas, carefully shipped.' ;;
    '#emacs') printf '%s' 'The editor is the operating system.' ;;
    '#rust') printf '%s' 'Fearless concurrency, one borrow at a time.' ;;
    *) printf '%s' 'motd e2e seed channel — deterministic history' ;;
  esac
}

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

# Keep a long-lived fixture registered with the IRCd instead of merely keeping
# its local pipe open. Ergo disconnects silent clients after its ping timeout.
hold_connection() {
  remaining="$SEED_HOLD_SECONDS"
  while [ "$remaining" -gt 0 ]; do
    delay=20
    [ "$remaining" -lt "$delay" ] && delay="$remaining"
    sleep "$delay"
    printf 'PING :motd-e2e-keepalive\r\n'
    remaining=$((remaining - delay))
  done
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
    for channel in $(fixture_channels); do
      printf 'JOIN %s\r\n' "$channel"
      sleep 1
      printf 'CS REGISTER %s\r\n' "$channel"
      printf 'TOPIC %s :%s\r\n' "$channel" "$(fixture_topic "$channel")"
    done
    printf 'QUIT :channel ready\r\n'
  } | feed_irc
}

do_showcase_seed() {
  # Seed after soju is connected so its message-store observes live traffic.
  log "seeding showcase channels: $SHOWCASE_CHANNELS"
  {
    printf 'NICK %s\r\n' "$SEED_NICK"
    printf 'USER %s 0 * :motd showcase seeder\r\n' "$SEED_NICK"
    printf 'NICKSERV IDENTIFY %s %s\r\n' "$SEED_NICK" "$SEED_PASS"
    sleep 2
    for channel in $(fixture_channels); do
      printf 'JOIN %s\r\n' "$channel"
      sleep 1
    done
    printf 'NICK alice\r\n'
    printf 'PRIVMSG #guix :Welcome to #guix — tonight the package build is green.\r\n'
    printf 'PRIVMSG #guix :Small systems, reproducible builds, and helpful neighbors.\r\n'
    printf 'NICK bob\r\n'
    printf 'PRIVMSG #guix :motdadb: the sprite avatars look great in this channel.\r\n'
    printf 'NICK carol\r\n'
    printf 'PRIVMSG #debian :A calm release day is a good release day.\r\n'
    printf 'PRIVMSG #debian :The handbook notes are ready for a second pair of eyes.\r\n'
    printf 'NICK dylan\r\n'
    printf 'PRIVMSG #emacs :The minibuffer is a state machine with excellent manners.\r\n'
    printf 'PRIVMSG #emacs :One more small refactor, then coffee.\r\n'
    printf 'NICK rustacean\r\n'
    printf 'PRIVMSG #rust :Borrow checker says hello from the afternoon build.\r\n'
    printf 'PRIVMSG #rust :The async tests are green; ship it when the tea is ready.\r\n'
    printf 'NICK motdadb2\r\n'
    printf 'PRIVMSG #guix :See you around the next build.\r\n'
    hold_connection
    printf 'QUIT :showcase seed done\r\n'
  } | feed_irc
}

do_showcase_hold() {
  log "holding showcase member $SEED_NICK in fixture channels"
  {
    printf 'NICK %s\r\n' "$SEED_NICK"
    printf 'USER %s 0 * :motd showcase member\r\n' "$SEED_NICK"
    printf 'NICKSERV IDENTIFY %s %s\r\n' "$SEED_NICK" "$SEED_PASS"
    sleep 2
    for channel in $(fixture_channels); do
      printf 'JOIN %s\r\n' "$channel"
      sleep 1
    done
    hold_connection
    printf 'QUIT :showcase hold done\r\n'
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
    printf 'PRIVMSG %s :hi from the seeded member\r\n' "$APP_NICK"
    # Device checks can keep the second identity visible in Channel info without a separate
    # daemon. The ordinary seed remains a short one-shot fixture.
    hold_connection
    printf 'QUIT :seed done\r\n'
  } | feed_irc
}

do_burst() {
  log "posting auto-follow burst into $TEST_CHANNEL as $SEED_NICK"
  {
    printf 'NICK %s\r\n' "$SEED_NICK"
    printf 'USER %s 0 * :motd auto-follow fixture\r\n' "$SEED_NICK"
    printf 'NICKSERV IDENTIFY %s %s\r\n' "$SEED_NICK" "$SEED_PASS"
    sleep 2
    printf 'JOIN %s\r\n' "$TEST_CHANNEL"
    sleep 1
    i=1
    while [ "$i" -le 12 ]; do
      printf 'PRIVMSG %s :auto-follow burst %02d\r\n' "$TEST_CHANNEL" "$i"
      i=$((i + 1))
    done
    sleep 1
    printf 'QUIT :auto-follow burst complete\r\n'
  } | feed_irc
}

do_jpq() {
  log "posting JOIN/PART/QUIT-only activity into $TEST_CHANNEL as $SEED_NICK"
  {
    printf 'NICK %s\r\n' "$SEED_NICK"
    printf 'USER %s 0 * :motd auto-follow fixture\r\n' "$SEED_NICK"
    printf 'NICKSERV IDENTIFY %s %s\r\n' "$SEED_NICK" "$SEED_PASS"
    sleep 2
    printf 'JOIN %s\r\n' "$TEST_CHANNEL"
    sleep 1
    printf 'PART %s :auto-follow part\r\n' "$TEST_CHANNEL"
    sleep 1
    printf 'JOIN %s\r\n' "$TEST_CHANNEL"
    sleep 1
    printf 'QUIT :auto-follow quit\r\n'
  } | feed_irc
}

do_push() {
  case "$PUSH_TOKEN" in
    *[!A-Za-z0-9._-]*) log "FATAL: PUSH_TOKEN contains unsafe IRC characters"; exit 2 ;;
  esac
  log "posting UnifiedPush fixtures tagged $PUSH_TOKEN"
  {
    printf 'NICK %s\r\n' "$SEED_NICK"
    printf 'USER %s 0 * :motd UnifiedPush fixture\r\n' "$SEED_NICK"
    printf 'NICKSERV IDENTIFY %s %s\r\n' "$SEED_NICK" "$SEED_PASS"
    sleep 2
    printf 'JOIN %s\r\n' "$TEST_CHANNEL"
    sleep 1
    printf 'PRIVMSG %s :%s: %s-mention\r\n' "$TEST_CHANNEL" "$APP_NICK" "$PUSH_TOKEN"
    printf 'PRIVMSG %s :%s-dm\r\n' "$APP_NICK" "$PUSH_TOKEN"
    sleep 1
    printf 'QUIT :UnifiedPush fixture complete\r\n'
  } | feed_irc
}

do_canonical() {
  case "$PUSH_TOKEN" in
    *[!A-Za-z0-9._-]*) log "FATAL: PUSH_TOKEN contains unsafe IRC characters"; exit 2 ;;
  esac
  rewritten_nick="${SEED_NICK}r"
  log "posting canonical repeated-text and PM room-rewrite fixtures tagged $PUSH_TOKEN"
  {
    printf 'NICK %s\r\n' "$SEED_NICK"
    printf 'USER %s 0 * :motd canonical timeline fixture\r\n' "$SEED_NICK"
    printf 'NICKSERV IDENTIFY %s %s\r\n' "$SEED_NICK" "$SEED_PASS"
    sleep 2
    printf 'JOIN %s\r\n' "$TEST_CHANNEL"
    sleep 1
    printf 'PRIVMSG %s :%s-repeat\r\n' "$TEST_CHANNEL" "$PUSH_TOKEN"
    printf 'PRIVMSG %s :%s-repeat\r\n' "$TEST_CHANNEL" "$PUSH_TOKEN"
    printf 'PRIVMSG %s :%s-room-before\r\n' "$APP_NICK" "$PUSH_TOKEN"
    printf 'NICK %s\r\n' "$rewritten_nick"
    sleep 1
    printf 'PRIVMSG %s :%s-room-after\r\n' "$APP_NICK" "$PUSH_TOKEN"
    sleep 1
    printf 'QUIT :canonical timeline fixture complete\r\n'
  } | feed_irc
}

wait_for_ergo
case "$MODE" in
  register) do_register ;;
  seed)     do_seed ;;
  showcase) do_showcase_seed ;;
  showcase-hold) do_showcase_hold ;;
  burst)    do_burst ;;
  jpq)      do_jpq ;;
  push)     do_push ;;
  canonical) do_canonical ;;
  all)
    do_register
    if [ "$STACK_PROFILE" = showcase ]; then do_showcase_seed; else do_seed; fi
    ;;
  *) log "FATAL: unknown mode '$MODE' (want register|seed|showcase|showcase-hold|burst|jpq|push|canonical|all)"; exit 2 ;;
esac
log "done"
