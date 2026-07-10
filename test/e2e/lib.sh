# shellcheck shell=bash
# test/e2e/lib.sh — sourced helper library for the MOTD device-driven E2E harness.
#
# Source this from runbook.sh; it provides adb wrappers, uiautomator dump parsing,
# tap/input primitives, assertions, crash detection, and step/pass/fail logging.
# See plans/18-e2e-runbook.md §3 for the design and the helper contract.
#
# Selection is ALWAYS by text / content-desc / testTag (resource-id). Coordinates
# come only from parsing a matched node's bounds, so layout shifts and keyboard
# scroll don't break selectors.
#
# Requires: coreutils, grep, sed, awk, and adb (on PATH under `nix develop`, or
# fell back to `guix shell android-tools -- adb` on a bare Guix host).

# --- runtime state ---------------------------------------------------------

# Directory holding this library (for locating siblings if ever needed).
E2E_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export E2E_LIB_DIR

# Output/artifacts dir for dumps and screenshots. Overridable via env.
: "${E2E_OUT_DIR:=${E2E_LIB_DIR}/artifacts}"

# The device serial to pin to. If unset and exactly one device is attached we
# auto-select it; if multiple are attached the caller MUST set SERIAL.
: "${SERIAL:=}"

# Package under test (mirrors §0). runbook.sh sets this; default here for safety.
: "${MOTD_PKG:=io.github.trevarj.motd.debug}"

# Cached path of the most recent uiautomator XML dump (host side).
_E2E_DUMP=""

# Running counters and step context.
_E2E_CHECKS=0
_E2E_FAILURES=0
_E2E_STEP=0
_E2E_STEP_DESC=""

# ANSI colours (disabled when stdout is not a tty).
if [ -t 1 ]; then
  _C_RED=$'\033[31m'; _C_GRN=$'\033[32m'; _C_YEL=$'\033[33m'
  _C_CYA=$'\033[36m'; _C_RST=$'\033[0m'
else
  _C_RED=""; _C_GRN=""; _C_YEL=""; _C_CYA=""; _C_RST=""
fi

# --- adb wrapper -----------------------------------------------------------

# Resolve how to invoke adb once. Prefer adb on PATH (the case under
# `nix develop`); otherwise fall back to a Guix ephemeral shell.
_E2E_ADB_MODE=""
_e2e_resolve_adb() {
  [ -n "$_E2E_ADB_MODE" ] && return 0
  if command -v adb >/dev/null 2>&1; then
    _E2E_ADB_MODE="path"
  elif command -v guix >/dev/null 2>&1; then
    _E2E_ADB_MODE="guix"
  else
    echo "${_C_RED}FATAL: adb not found on PATH and guix unavailable.${_C_RST}" >&2
    echo "Run inside 'nix develop' or install android-tools." >&2
    exit 127
  fi
}

# adb_ <args...> — run adb pinned to $SERIAL when set. Central choke point so
# every adb call goes through the same resolution/serial logic.
adb_() {
  _e2e_resolve_adb
  if [ "$_E2E_ADB_MODE" = "guix" ]; then
    if [ -n "$SERIAL" ]; then
      guix shell android-tools -- adb -s "$SERIAL" "$@"
    else
      guix shell android-tools -- adb "$@"
    fi
  else
    if [ -n "$SERIAL" ]; then
      adb -s "$SERIAL" "$@"
    else
      adb "$@"
    fi
  fi
}

# adb_shell <cmd...> — convenience for `adb_ shell`.
adb_shell() { adb_ shell "$@"; }

# ensure_device — verify exactly one usable device, auto-picking a serial if the
# caller didn't pin one. Fails loudly on zero or ambiguous multiple devices.
ensure_device() {
  _e2e_resolve_adb
  local devices
  # List "serial<TAB>device" lines only (skip header + offline/unauthorized).
  devices="$(adb_ devices | awk 'NR>1 && $2=="device" {print $1}')"
  if [ -z "$devices" ]; then
    echo "${_C_RED}FATAL: no authorized adb device attached.${_C_RST}" >&2
    echo "Connect a device, enable USB debugging, and accept the RSA prompt." >&2
    exit 1
  fi
  if [ -z "$SERIAL" ]; then
    local count
    count="$(printf '%s\n' "$devices" | wc -l | tr -d ' ')"
    if [ "$count" -gt 1 ]; then
      echo "${_C_RED}FATAL: multiple devices attached; set SERIAL.${_C_RST}" >&2
      printf '%s\n' "$devices" >&2
      exit 1
    fi
    SERIAL="$devices"
    export SERIAL
    echo "${_C_CYA}Using device: $SERIAL${_C_RST}" >&2
  fi
  mkdir -p "$E2E_OUT_DIR"
}

# --- uiautomator dump + XML parsing ---------------------------------------

# dump — capture a fresh uiautomator hierarchy to a host XML file, caching the
# path in $_E2E_DUMP. Re-call this after any IME opens (bounds shift).
#
# uiautomator dump writes to a device path; we pull it to the host so the
# grep/sed parsing runs locally with no per-node adb round-trips.
dump() {
  local dev_xml="/sdcard/motd_e2e_dump.xml"
  local host_xml="${E2E_OUT_DIR}/dump.xml"
  mkdir -p "$E2E_OUT_DIR"
  # uiautomator occasionally emits "null root node returned"; retry a couple of
  # times before giving up (transient during transitions/animations).
  local attempt out
  # shellcheck disable=SC2034 # attempt is a loop counter; iteration count is the point
  for attempt in 1 2 3; do
    out="$(adb_shell uiautomator dump "$dev_xml" 2>&1 || true)"
    case "$out" in
      *ERROR*|*null*root*)
        sleep 1
        continue
        ;;
    esac
    if adb_ pull "$dev_xml" "$host_xml" >/dev/null 2>&1; then
      _E2E_DUMP="$host_xml"
      return 0
    fi
    sleep 1
  done
  echo "${_C_RED}dump failed after retries: ${out}${_C_RST}" >&2
  return 1
}

# redump — explicit alias for readability at IME-shift sites.
redump() { dump; }

# _e2e_have_dump — ensure a dump exists; take one if not.
_e2e_have_dump() {
  [ -n "$_E2E_DUMP" ] && [ -f "$_E2E_DUMP" ] && return 0
  dump
}

# _e2e_bounds_from_attr <attr> <value> — internal: given an XML attribute name
# (text | content-desc | resource-id) and its exact value, print the first
# matching node's bounds string "[x1,y1][x2,y2]", or nothing if not found.
#
# uiautomator emits one <node .../> element per line. We isolate the node lines
# carrying the attribute=value pair, then extract that node's bounds attribute.
# Values are matched exactly against the quoted attribute content.
_e2e_bounds_from_attr() {
  local attr="$1" value="$2"
  _e2e_have_dump || return 1
  # Escape regex metacharacters in the value so text like "AMOLED (true black)"
  # or "SHA-256" is matched literally.
  local esc
  # shellcheck disable=SC2016 # single quotes are intentional: literal sed script
  esc="$(printf '%s' "$value" | sed 's/[][\.*^$(){}?+|/]/\\&/g')"
  # Grab node lines where attr="value" appears, then pull bounds from the same
  # node. Because uiautomator prints one node per line, a per-line grep suffices.
  grep -oE "<node[^>]* ${attr}=\"${esc}\"[^>]*>" "$_E2E_DUMP" 2>/dev/null \
    | head -n1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | head -n1 \
    | sed -E 's/^bounds="(.*)"$/\1/'
}

# bounds_of_text "<text>" — bounds of the first node whose text= matches exactly.
bounds_of_text() { _e2e_bounds_from_attr "text" "$1"; }

# bounds_of_desc "<contentDesc>" — bounds by content-desc.
bounds_of_desc() { _e2e_bounds_from_attr "content-desc" "$1"; }

# bounds_of_tag "<testTag/resourceId>" — bounds by resource-id. A bare tag (no
# package prefix) is matched as a suffix, because Compose testTags surface as
# resource-id and may or may not carry a package qualifier depending on toolchain.
bounds_of_tag() {
  local tag="$1"
  _e2e_have_dump || return 1
  local esc
  # shellcheck disable=SC2016 # single quotes are intentional: literal sed script
  esc="$(printf '%s' "$tag" | sed 's/[][\.*^$(){}?+|/]/\\&/g')"
  # Match resource-id="<...>tag" allowing an optional package/prefix before it.
  grep -oE "<node[^>]* resource-id=\"[^\"]*${esc}\"[^>]*>" "$_E2E_DUMP" 2>/dev/null \
    | head -n1 \
    | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' \
    | head -n1 \
    | sed -E 's/^bounds="(.*)"$/\1/'
}

# _e2e_center <bounds> — given "[x1,y1][x2,y2]" print "cx cy" (integer centre).
_e2e_center() {
  printf '%s' "$1" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' \
    | awk '{printf "%d %d", int(($1+$3)/2), int(($2+$4)/2)}'
}

# --- tap primitives --------------------------------------------------------

# _e2e_tap_bounds_fn <bounds-fn> <selector> <human-label> — shared tap logic:
# re-dump, resolve bounds via the given lookup function, tap the centre. Fails
# the current check with a clear message if the selector is not found.
_e2e_tap_bounds_fn() {
  local fn="$1" sel="$2" label="$3"
  dump || { fail "dump failed while looking for ${label} '${sel}'"; return 1; }
  local b
  b="$("$fn" "$sel")"
  if [ -z "$b" ]; then
    fail "not found: ${label} '${sel}'"
    return 1
  fi
  local xy
  xy="$(_e2e_center "$b")"
  # shellcheck disable=SC2086 # xy is a deliberate "cx cy" word split.
  adb_shell input tap $xy
  ok "tapped ${label} '${sel}'"
}

# tap_text "<text>" — tap the centre of the node with this exact visible text.
tap_text() { _e2e_tap_bounds_fn bounds_of_text "$1" "text"; }

# tap_desc "<contentDesc>" — tap by content-desc.
tap_desc() { _e2e_tap_bounds_fn bounds_of_desc "$1" "desc"; }

# tap_tag "<testTag>" — tap by resource-id (Compose testTag).
tap_tag() { _e2e_tap_bounds_fn bounds_of_tag "$1" "tag"; }

# _e2e_long_press_fn <bounds-fn> <selector> <label> — long-press (500ms) the
# centre of a matched node via `input swipe x y x y 600` (same start/end point).
_e2e_long_press_fn() {
  local fn="$1" sel="$2" label="$3"
  dump || { fail "dump failed while looking for ${label} '${sel}'"; return 1; }
  local b
  b="$("$fn" "$sel")"
  if [ -z "$b" ]; then
    fail "not found for long-press: ${label} '${sel}'"
    return 1
  fi
  local cx cy
  read -r cx cy <<EOF
$(_e2e_center "$b")
EOF
  adb_shell input swipe "$cx" "$cy" "$cx" "$cy" 600
  ok "long-pressed ${label} '${sel}'"
}

# long_press_text / long_press_tag — long-press by text / testTag.
long_press_text() { _e2e_long_press_fn bounds_of_text "$1" "text"; }
long_press_tag() { _e2e_long_press_fn bounds_of_tag "$1" "tag"; }

# --- text input ------------------------------------------------------------

# _e2e_send_text <text> — type text via adb `input text`, safely. adb `input
# text` treats space as an argument separator and interprets a few characters
# specially; we escape spaces as %s and backslash-escape the shell-significant
# ASCII punctuation so arbitrary values (passwords, URLs) type verbatim.
_e2e_send_text() {
  local text="$1" out=""
  local i ch
  for (( i=0; i<${#text}; i++ )); do
    ch="${text:$i:1}"
    case "$ch" in
      ' ')  out+='%s' ;;                       # adb input's space token
      # Characters the adb `input text` parser / device shell treat specially. '#' starts a
      # comment in the device shell (so an unescaped channel like "##motdtest" becomes empty).
      '('|')'|'<'|'>'|'|'|';'|'&'|'*'|\\|'~'|'"'|"'"|'`'|'$'|'#'|'!'|'{'|'}')
            out+="\\$ch" ;;
      *)    out+="$ch" ;;
    esac
  done
  adb_shell input text "$out"
}

# _e2e_input_by_fn <bounds-fn> <selector> <text> <label> — tap a field to focus
# it, type the text, then close the keyboard (BACK). Caller should re-dump after
# because the IME open/close shifts bounds (runbook flags every field step).
_e2e_input_by_fn() {
  local fn="$1" sel="$2" text="$3" label="$4"
  dump || { fail "dump failed locating field ${label} '${sel}'"; return 1; }
  local b
  b="$("$fn" "$sel")"
  if [ -z "$b" ]; then
    fail "field not found: ${label} '${sel}'"
    return 1
  fi
  local xy
  xy="$(_e2e_center "$b")"
  # shellcheck disable=SC2086
  adb_shell input tap $xy
  # Give the IME a beat to attach focus before typing.
  sleep 1
  # Clear any pre-filled/default content (e.g. the Port field defaults to 6697) so typing
  # REPLACES rather than appends. Move caret to end, then delete a generous run backward.
  # 123=MOVE_END, 67=DEL; these fields are short (host/port/nick/user/pass).
  adb_shell input keyevent 123
  adb_shell input keyevent 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67 67
  _e2e_send_text "$text"
  # Close the soft keyboard so subsequent dumps see the settled layout.
  adb_shell input keyevent 4   # KEYCODE_BACK
  ok "input ${label} '${sel}' <= \"${text}\""
}

# input_field <selector-fn> <text> — generic entry point taking a bounds-lookup
# function name plus a value. Example: input_field bounds_of_tag chat_composer_field "hi".
# The selector value is passed via a wrapper so the fn signature stays uniform.
input_field() {
  local selfn="$1" selector="$2" text="$3"
  _e2e_input_by_fn "$selfn" "$selector" "$text" "field"
}

# input_tag <testTag> <text> — type into a field identified by testTag.
input_tag() { _e2e_input_by_fn bounds_of_tag "$1" "$2" "tag"; }

# input_by_text_label <label> <text> — type into a field identified by its own
# visible text (its label/placeholder). Used until a stable testTag exists.
input_by_text_label() { _e2e_input_by_fn bounds_of_text "$1" "$2" "label"; }

# --- assertions ------------------------------------------------------------

# assert_text "<text>" — pass if the current (fresh) dump contains a node whose
# text equals this value. Captures a diagnostic screenshot on miss.
assert_text() {
  local t="$1"
  dump || { fail "dump failed asserting text '${t}'"; return 1; }
  if [ -n "$(bounds_of_text "$t")" ]; then
    ok "present: text '${t}'"
  else
    screencap_step "missing_$(_e2e_slug "$t")"
    fail "expected text absent: '${t}'"
  fi
}

# assert_no_text "<text>" — inverse of assert_text.
assert_no_text() {
  local t="$1"
  dump || { fail "dump failed asserting absence of '${t}'"; return 1; }
  if [ -z "$(bounds_of_text "$t")" ]; then
    ok "absent as expected: text '${t}'"
  else
    fail "unexpected text present: '${t}'"
  fi
}

# assert_tag_present "<testTag>" — pass if a node with this resource-id exists.
assert_tag_present() {
  local tag="$1"
  dump || { fail "dump failed asserting tag '${tag}'"; return 1; }
  if [ -n "$(bounds_of_tag "$tag")" ]; then
    ok "present: tag '${tag}'"
  else
    screencap_step "missing_tag_$(_e2e_slug "$tag")"
    fail "expected tag absent: '${tag}'"
  fi
}

# assert_desc_present "<contentDesc>" — pass if a node with this content-desc exists.
assert_desc_present() {
  local d="$1"
  dump || { fail "dump failed asserting desc '${d}'"; return 1; }
  if [ -n "$(bounds_of_desc "$d")" ]; then
    ok "present: desc '${d}'"
  else
    fail "expected content-desc absent: '${d}'"
  fi
}

# wait_for_text "<text>" <timeout_s> — poll a fresh dump every ~1s until the
# text appears or the timeout elapses. Returns success/failure without counting
# a check (callers usually follow with assert_text); use for connect/registration.
wait_for_text() {
  local t="$1" timeout="${2:-20}" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if dump && [ -n "$(bounds_of_text "$t")" ]; then
      return 0
    fi
    sleep 1
    waited=$(( waited + 1 ))
  done
  return 1
}

# wait_for_any_text <timeout_s> <text...> — poll until ANY of the given texts
# appears. Prints the matched text on stdout. Useful when a step can settle into
# one of several states (e.g. "Connected as …" OR "Bouncer networks").
wait_for_any_text() {
  local timeout="$1"; shift
  local waited=0 t
  while [ "$waited" -lt "$timeout" ]; do
    if dump; then
      for t in "$@"; do
        if [ -n "$(bounds_of_text "$t")" ]; then
          printf '%s' "$t"
          return 0
        fi
      done
    fi
    sleep 1
    waited=$(( waited + 1 ))
  done
  return 1
}

# reset_to_chatlist — normalize the app to the chat-list screen so a phase can start from a known
# anchor (the "New conversation" action). Closes any open sheet/dialog and presses BACK until the
# list shows. Enables a rapid dev cycle: run the expensive phase 'a' once to set up device state
# (install + onboard + connect), then re-run any later phase(s) alone via E2E_PHASES without
# repeating onboarding. Returns non-zero if the chat list can't be reached (app not onboarded).
reset_to_chatlist() {
  # Foreground the app first: a prior phase's BACK presses can walk all the way out to the
  # launcher, and reset can't navigate an app that isn't on screen. am start resumes a running
  # app (possibly on a sub-screen) or cold-starts it; with networks already configured a cold
  # start lands directly on the chat list.
  local activity="${MOTD_ACTIVITY:-${MOTD_PKG}/io.github.trevarj.motd.MainActivity}"
  adb_shell am start -n "$activity" >/dev/null 2>&1 || true
  sleep 1
  local i
  for i in 1 2 3 4 5 6 7 8; do
    dump || true
    [ -n "$(bounds_of_desc 'New conversation')" ] && return 0
    adb_shell input keyevent 4   # BACK: dismiss a sheet/dialog or pop a screen
    sleep 1
  done
  dump || true
  [ -n "$(bounds_of_desc 'New conversation')" ]
}

# scroll_to_text "<text>" [tries] — in a reverse-layout chat list (newest at the bottom), scroll up
# toward older messages until the exact text is on screen. Used before long-pressing an older
# message that auto-scroll pushed off the top. Returns success if the text becomes visible.
scroll_to_text() {
  local t="$1" tries="${2:-6}" i
  for i in $(seq 1 "$tries"); do
    dump || true
    [ -n "$(bounds_of_text "$t")" ] && return 0
    # Swipe finger top->bottom to drag content down, revealing older messages above.
    adb_shell input swipe 540 700 540 1650 300
    sleep 1
  done
  dump || true
  [ -n "$(bounds_of_text "$t")" ]
}

# --- crash detection -------------------------------------------------------

# clear_crash — clear the logcat crash + main buffers to establish a baseline.
# Called at run start (and after intentional error-path steps if desired).
clear_crash() {
  adb_ logcat -c -b crash >/dev/null 2>&1 || true
  adb_ logcat -c >/dev/null 2>&1 || true
  ok "cleared logcat crash baseline"
}

# assert_no_crash — fail if any FATAL exception appeared in the crash buffer
# since the baseline, or if MotdConn reports a connection failure. Surfaces the
# failing lines so the report is actionable.
assert_no_crash() {
  local crash fatal_count
  crash="$(adb_ logcat -d -b crash 2>/dev/null || true)"
  # Count FATAL lines mentioning our package OR any FATAL EXCEPTION (a crash in
  # our process may not always name the package on the FATAL line).
  fatal_count="$(printf '%s\n' "$crash" | grep -c 'FATAL' || true)"
  if [ "${fatal_count:-0}" -gt 0 ]; then
    # Surface the salient lines on stderr (grep -> head, then redirect the whole
    # pipeline; redirecting mid-pipe would starve head).
    printf '%s\n' "$crash" \
      | grep -E "FATAL|Process:|Caused by:|${MOTD_PKG}" \
      | head -n 40 >&2 || true
    fail "crash detected: ${fatal_count} FATAL line(s) in crash buffer"
    return 1
  fi
  # Connection failures: MotdConn logs "... Failed" with a reason.
  local conn_fail
  conn_fail="$(adb_ logcat -d 2>/dev/null | grep -E 'MotdConn.*Failed' | tail -n 5 || true)"
  if [ -n "$conn_fail" ]; then
    printf '%s\n' "$conn_fail" >&2
    fail "connection failure reported by MotdConn (see reason above)"
    return 1
  fi
  ok "no crash / connection failure"
}

# --- screenshots (diagnostic only) ----------------------------------------

# _e2e_slug <str> — filesystem-safe slug for artifact names.
_e2e_slug() {
  printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_' | cut -c1-60
}

# screencap_step <name> — save a PNG to the artifacts dir. Per §6/§7 these are
# for color-only oracles (AMOLED black, status-dot color) and failure
# diagnostics only, never the primary assertion. Keep usage minimal.
screencap_step() {
  local name="$1"
  mkdir -p "$E2E_OUT_DIR"
  local f
  f="${E2E_OUT_DIR}/$(printf '%03d' "$_E2E_STEP")-$(_e2e_slug "$name").png"
  # exec-out streams raw PNG bytes without the historical CRLF mangling.
  adb_ exec-out screencap -p >"$f" 2>/dev/null || true
  echo "${_C_CYA}  screencap -> $f${_C_RST}" >&2
}

# --- logging / step accounting --------------------------------------------

# step "<desc>" — begin a numbered runbook step; all subsequent ok/fail lines
# are attributed to it in the log.
step() {
  _E2E_STEP=$(( _E2E_STEP + 1 ))
  _E2E_STEP_DESC="$1"
  echo ""
  echo "${_C_CYA}== Step ${_E2E_STEP}: ${_E2E_STEP_DESC} ==${_C_RST}"
}

# ok "<msg>" — record a passing check.
ok() {
  _E2E_CHECKS=$(( _E2E_CHECKS + 1 ))
  echo "  ${_C_GRN}ok${_C_RST}  $1"
}

# pass "<msg>" — alias of ok for readability at assertion sites.
pass() { ok "$1"; }

# fail "<msg>" — record a failing check. Does NOT exit (the run continues so the
# summary reflects total damage); the final summary sets a non-zero exit code.
fail() {
  _E2E_CHECKS=$(( _E2E_CHECKS + 1 ))
  _E2E_FAILURES=$(( _E2E_FAILURES + 1 ))
  echo "  ${_C_RED}FAIL${_C_RST} [step ${_E2E_STEP}: ${_E2E_STEP_DESC}] $1" >&2
}

# note "<msg>" — informational log line, not a check.
note() { echo "  ${_C_YEL}..${_C_RST}  $1"; }

# e2e_summary — print the final tally and return non-zero if anything failed.
# Call once at the end of the run (runbook.sh traps EXIT to invoke it).
e2e_summary() {
  echo ""
  echo "${_C_CYA}=================== E2E summary ===================${_C_RST}"
  echo "  checks:   ${_E2E_CHECKS}"
  echo "  failures: ${_E2E_FAILURES}"
  if [ "$_E2E_FAILURES" -eq 0 ]; then
    echo "  ${_C_GRN}RESULT: PASS${_C_RST}"
    return 0
  fi
  echo "  ${_C_RED}RESULT: FAIL${_C_RST}"
  return 1
}
