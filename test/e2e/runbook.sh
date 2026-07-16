#!/usr/bin/env bash
# test/e2e/runbook.sh — device-driven E2E acceptance run for MOTD.
#
# Implements the A–I traversal documented in test/e2e/README.md against a
# physical device or emulator and a real soju test bouncer. Drives the app via
# adb + uiautomator using the helpers in lib.sh.
#
# Run inside the project dev shell so adb is on PATH:
#   nix develop -c ./test/e2e/runbook.sh
#
# Config comes from the environment (see .env.example). NEVER hardcode secrets.
# If test/e2e/.env exists (gitignored) it is sourced automatically.
#
# Selectors prefer stable testTags, then content descriptions. Visible text remains only where a
# runtime-id-suffixed tag cannot identify the intended row without knowing its database id.
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- config / secrets ------------------------------------------------------

# Source untracked defaults, then restore values explicitly supplied by the caller. This makes the
# documented precedence real and prevents a stale .env APK from silently replacing the artifact a
# device-validation command intended to test.
_explicit_config=()
for _name in \
  MOTD_PKG MOTD_APK MOTD_SOJU_HOST MOTD_SOJU_PORT MOTD_SOJU_USER MOTD_SOJU_PASS \
  MOTD_NICK MOTD_TEST_CHANNEL MOTD_SECOND_NICK SERIAL E2E_OUT_DIR E2E_PHASES; do
  if [[ -v "$_name" ]]; then
    _explicit_config+=("$(declare -p "$_name")")
  fi
done
if [ -f "${E2E_DIR}/.env" ]; then
  # shellcheck disable=SC1091 # runtime-provided, untracked file
  . "${E2E_DIR}/.env"
fi
for _declaration in "${_explicit_config[@]}"; do
  eval "$_declaration"
done
unset _explicit_config _declaration _name

# Package + APK.
: "${MOTD_PKG:=io.github.trevarj.motd.debug}"
: "${MOTD_APK:=}"                 # path to a .debug APK; required for install
# Safety: this harness runs destructive pm clear / force-stop against MOTD_PKG. Refuse to touch
# the release install (real account) — only ever the .debug variant.
case "$MOTD_PKG" in
  *.debug) ;;
  *) echo "REFUSING: MOTD_PKG='$MOTD_PKG' is not the .debug variant; would touch the release app." >&2; exit 2 ;;
esac
# Component = <applicationId>/<activity-class>. The debug variant's applicationId carries the
# .debug suffix (MOTD_PKG) but the Activity CLASS name is unsuffixed, so build it from MOTD_PKG
# to guarantee we only ever drive the debug app — never the release install (real account).
MOTD_ACTIVITY="${MOTD_PKG}/io.github.trevarj.motd.MainActivity"

# Bouncer / account. No defaults for non-local credentials.
: "${MOTD_SOJU_HOST:=}"
: "${MOTD_SOJU_PORT:=6697}"
: "${MOTD_SOJU_USER:=}"
: "${MOTD_SOJU_PASS:=}"
: "${MOTD_NICK:=motdadb}"
: "${MOTD_TEST_CHANNEL:=##motdtest}"

# Optional second identity (drives DM/mention/typing). When unset, those steps
# are skipped without failing.
: "${MOTD_SECOND_NICK:=}"

export MOTD_PKG

# lib.sh reads MOTD_PKG, SERIAL, E2E_OUT_DIR.
# shellcheck source=test/e2e/lib.sh disable=SC1091
. "${E2E_DIR}/lib.sh"

# --- preconditions ---------------------------------------------------------

require_env() {
  local missing=0 v
  for v in "$@"; do
    if [ -z "${!v:-}" ]; then
      echo "${_C_RED}Missing required env: ${v}${_C_RST}" >&2
      missing=1
    fi
  done
  [ "$missing" -eq 0 ] || {
    echo "Set them in the environment or test/e2e/.env (see .env.example)." >&2
    exit 2
  }
}

require_env MOTD_SOJU_HOST MOTD_SOJU_USER MOTD_SOJU_PASS

# --- teardown / summary trap ----------------------------------------------

_device_idle_forced=false
_animation_scales_captured=false
_window_animation_scale=""
_transition_animation_scale=""
_animator_duration_scale=""

_capture_animation_scales() {
  _window_animation_scale="$(adb_shell settings get global window_animation_scale)" || return 1
  _transition_animation_scale="$(adb_shell settings get global transition_animation_scale)" || return 1
  _animator_duration_scale="$(adb_shell settings get global animator_duration_scale)" || return 1
  _window_animation_scale="${_window_animation_scale//$'\r'/}"
  _transition_animation_scale="${_transition_animation_scale//$'\r'/}"
  _animator_duration_scale="${_animator_duration_scale//$'\r'/}"
  _animation_scales_captured=true
}

_restore_animation_scale() {
  local setting="$1" value="$2"
  if [ -z "$value" ] || [ "$value" = "null" ]; then
    adb_shell settings delete global "$setting" >/dev/null 2>&1 || true
  else
    adb_shell settings put global "$setting" "$value" >/dev/null 2>&1 || true
  fi
}

_restore_animation_scales() {
  [ "$_animation_scales_captured" = true ] || return 0
  _restore_animation_scale window_animation_scale "$_window_animation_scale"
  _restore_animation_scale transition_animation_scale "$_transition_animation_scale"
  _restore_animation_scale animator_duration_scale "$_animator_duration_scale"
  _animation_scales_captured=false
}

_final() {
  local rc=$?
  _restore_animation_scales
  if [ "$_device_idle_forced" = true ]; then
    adb_shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
    adb_shell dumpsys battery reset >/dev/null 2>&1 || true
  fi
  if [ "$rc" -ne 0 ] || [ "$_E2E_FAILURES" -ne 0 ]; then
    mkdir -p "$E2E_OUT_DIR"
    adb_ exec-out screencap -p >"$E2E_OUT_DIR/failure.png" 2>/dev/null || true
    adb_ logcat -d -v threadtime >"$E2E_OUT_DIR/logcat.txt" 2>/dev/null || true
  fi
  # Print the tally; preserve a non-zero rc if the run body already failed.
  if e2e_summary; then
    exit "$rc"
  else
    exit 1
  fi
}
trap _final EXIT

# ==========================================================================
# Phase A — clean install & onboard the soju bouncer
# ==========================================================================
phase_a() {
  echo ""
  echo "${_C_CYA}########## Phase A: clean install & onboarding ##########${_C_RST}"

  # 1. Reset + launch.
  step "Reset app state and launch into onboarding"
  if [ -n "$MOTD_APK" ] && [ -f "$MOTD_APK" ]; then
    note "installing APK: $MOTD_APK"
    adb_ install -r -g "$MOTD_APK" >/dev/null 2>&1 || \
      note "install -r failed (already installed?); continuing"
  else
    note "MOTD_APK unset/absent; assuming app already installed"
  fi
  adb_shell pm clear "$MOTD_PKG" >/dev/null
  adb_shell am force-stop "$MOTD_PKG" >/dev/null 2>&1 || true
  clear_crash
  sleep 1
  # -S forces a stop-then-start so a stale task can't be "brought to front" (the pm clear /
  # am start race that left onboarding hidden). Fall back to a plain start if -S is unsupported.
  adb_shell am start -S -n "$MOTD_ACTIVITY" >/dev/null 2>&1 || adb_shell am start -n "$MOTD_ACTIVITY" >/dev/null
  # The runtime POST_NOTIFICATIONS dialog appears on top a few seconds into the cold start (a
  # separate window) and hides onboarding from uiautomator. Poll for EITHER onboarding (done)
  # or the dialog's exact "Allow" button (tap to dismiss), robust to launch/dialog timing.
  _w=0
  while [ "$_w" -lt 30 ]; do
    dump || true
    [ -n "$(bounds_of_text 'Welcome to motd')" ] && break
    # AOSP images expose this label as uppercase ALLOW while other devices use Allow.
    if [ -n "$(bounds_of_text 'Allow')" ]; then tap_text "Allow"; sleep 1; continue; fi
    if [ -n "$(bounds_of_text 'ALLOW')" ]; then tap_text "ALLOW"; sleep 1; continue; fi
    sleep 1; _w=$(( _w + 1 ))
  done
  assert_text "Welcome to motd"          # redirected into onboarding
  assert_no_crash

  # 2. Welcome -> Choice.
  step "Advance from Welcome to Choice"
  tap_tag onboarding_forward_button
  assert_text "How do you connect?"
  assert_no_crash

  # 3. Choose soju.
  step "Choose the soju bouncer path"
  tap_tag onboarding_choice_bouncer
  tap_tag onboarding_choice_soju
  tap_tag onboarding_forward_button
  assert_text "Host"                     # SERVER page fields present
  assert_no_crash

  # 4. Server fields (re-dump after each IME open).
  step "Fill server host/port/nick"
  input_by_text_label "Host" "$MOTD_SOJU_HOST"
  redump
  input_by_text_label "Port" "$MOTD_SOJU_PORT"
  redump
  input_by_text_label "Nickname" "$MOTD_NICK"
  redump
  assert_text "Use TLS"                  # TLS toggle present (default on)
  assert_no_crash

  # 5. Advance to auth.
  step "Advance to Authentication"
  tap_tag onboarding_forward_button
  assert_text "Authentication"
  assert_text "Bouncer username"
  assert_text "Bouncer password"
  assert_no_crash

  # 6. Auth creds.
  step "Fill soju SASL PLAIN credentials"
  input_by_text_label "Bouncer username" "$MOTD_SOJU_USER"
  redump
  input_by_text_label "Bouncer password" "$MOTD_SOJU_PASS"
  redump
  assert_no_crash

  # 7. Connect.
  step "Start connect test"
  tap_tag onboarding_forward_button
  # "Connecting" is transient and, for a self-signed bouncer, is immediately superseded by the
  # TOFU cert dialog — treat it as a soft signal, not a hard assertion. No assert_no_crash here:
  # the app logs an expected "certificate not trusted" until we accept the cert in step 8.
  if wait_for_text "Connecting" 6; then ok "connect started (Connecting shown)"
  else note "Connecting not caught (superseded by cert prompt); continuing"; fi

  # 8. Trust cert (TOFU). Every pm clear re-triggers this.
  step "Handle TOFU cert-trust prompt"
  if wait_for_text "Trust this certificate?" 25; then
    # AlertDialog is hosted in a separate Compose window, so the Activity root's
    # testTagsAsResourceId setting does not propagate here. Its visible title/fingerprint and
    # button labels are the stable accessibility contract for device automation.
    assert_text "SHA-256 fingerprint"
    tap_text "Trust"                     # buttons carry visible text
    note "trusted cert"
    clear_crash                          # reset the expected pre-trust 'not trusted' baseline
  else
    note "cert dialog did not appear (already trusted or fast connect); continuing"
  fi
  # Wait for either the ready indicator or the bouncer section.
  if wait_for_any_text 30 "Connected as " "Bouncer networks" >/dev/null; then
    ok "connect settled (ready or bouncer section shown)"
  else
    fail "connect did not reach Ready / Bouncer networks within timeout"
  fi
  assert_no_crash

  # 9. Bouncer import.
  step "Import bouncer network 'libera'"
  wait_for_text "Bouncer networks" 20 || true
  assert_text "Bouncer networks"
  # The list populates asynchronously as BOUNCER NETWORK notifications arrive after connect;
  # poll for the libera row rather than asserting immediately (confirmed on the bouncer:
  # BOUNCER NETWORK 2 name=libera state=connected).
  wait_for_text "libera" 25 || true
  assert_text "libera"
  # Select libera (rows default to unselected) so it is imported as a child network and later
  # phases have real buffers/channels to drive. The row is clickable and toggles its switch.
  tap_tag_prefix onboarding_bouncer_switch_
  sleep 1
  note "selected libera for import"
  assert_no_crash

  # 10. Finish.
  step "Finish onboarding -> ChatList"
  # The forward button label varies (Next/Finish) and "Finish" also appears as the FINISH page
  # heading, so drive it by its stable testTag rather than by text.
  tap_tag onboarding_forward_button      # CONNECT -> FINISH
  sleep 1
  tap_tag onboarding_forward_button      # FINISH -> ChatList
  wait_for_desc "New conversation" 15 || true
  assert_desc_present "New conversation"  # stable ChatList action anchor
  assert_no_text "Welcome to motd"
  assert_no_crash
}

# ==========================================================================
# Phase B — chat list, drawer, connectivity
# ==========================================================================
phase_b() {
  echo ""
  echo "${_C_CYA}########## Phase B: chat list & drawer ##########${_C_RST}"

  # 11. Drawer open.
  step "Open the navigation drawer"
  tap_desc "Open navigation drawer"
  assert_text "NETWORKS"
  assert_text "libera"
  assert_text "Add network"
  assert_text "Settings"
  assert_no_crash

  # 12. Network subtitle (Ready as nick, or a state string).
  step "Check libera network subtitle/state"
  # Subtitle text is the nick when Ready; we assert the nick is somewhere in the
  # drawer. Status dot color is unreadable without a state content description.
  if [ -n "$(bounds_of_text "$MOTD_NICK")" ]; then
    ok "libera subtitle shows nick '$MOTD_NICK' (Ready)"
  else
    note "nick not shown; network may be Connecting/Registering (color-only dot)"
  fi
  assert_no_crash

  # 13. Scoped bulk read action. Seeded history normally makes this available; keep the phase
  # useful when a reused fixture is already fully read.
  step "Mark all current-scope chats read"
  dump
  if [ -n "$(bounds_of_tag drawer_mark_all_read)" ]; then
    tap_tag drawer_mark_all_read
    assert_text "Mark all chats as read?"
    # AlertDialog is a separate Compose window; UIAutomator does not expose its button testTag
    # reliably, so use the exact localized label (same constraint as the reactions sheet).
    assert_text "Mark as read"
    tap_text "Mark as read"
    wait_for_desc "Open navigation drawer" 5 || true
    tap_desc "Open navigation drawer"
    dump
    if [ -z "$(bounds_of_tag drawer_mark_all_read)" ]; then
      ok "bulk read action cleared the current scope"
    else
      fail "bulk read action remained after confirmation"
    fi
  else
    note "current scope already read; bulk read action correctly hidden"
  fi
  assert_no_crash

  # 14. Scope to network.
  step "Scope list to libera"
  tap_tag_prefix_containing_text drawer_network_row_ "libera"
  assert_text "libera"                   # ScopeChip / title
  assert_no_crash

  # 15. Clear scope.
  step "Clear network scope"
  tap_desc "Clear network filter"
  assert_desc_present "New conversation"
  assert_no_crash

  # 16. Server messages buffer.
  step "Open the SERVER buffer via drawer long-press"
  tap_desc "Open navigation drawer"
  long_press_tag_prefix_containing_text drawer_network_row_ "libera"
  if wait_for_text "Server messages" 5; then
    tap_text "Server messages"
    assert_text "Send a command…"        # SERVER composer placeholder
  else
    note "long-press menu did not surface 'Server messages'; skipping"
  fi
  assert_no_crash

  # 17. Back to list.
  step "Back to chat list"
  adb_shell input keyevent 4
  wait_for_desc "New conversation" 8 || true
  assert_desc_present "New conversation"
  assert_no_crash
}

# ==========================================================================
# Phase C — join a channel & chat features
# ==========================================================================
phase_c() {
  echo ""
  echo "${_C_CYA}########## Phase C: join channel & chat ##########${_C_RST}"

  # 17. New conversation sheet.
  step "Open new-conversation sheet"
  tap_desc "New conversation"
  assert_text "Join channel"
  assert_text "Browse channels…"
  assert_no_crash

  # 18. Join seed channel.
  step "Join seed channel ${MOTD_TEST_CHANNEL}"
  tap_tag new_conversation_join_tab
  input_tag new_conversation_input "$MOTD_TEST_CHANNEL"
  tap_tag new_conversation_submit
  wait_for_text "$MOTD_TEST_CHANNEL" 15 || true
  assert_text "$MOTD_TEST_CHANNEL"
  assert_no_crash

  # 19. Open channel.
  step "Open ${MOTD_TEST_CHANNEL} chat"
  tap_tag_prefix_containing_text chatlist_row_ "$MOTD_TEST_CHANNEL"
  assert_text "$MOTD_TEST_CHANNEL"
  assert_text "Message"                  # composer placeholder
  assert_no_crash

  # 20. Send a message. The composer uses sentence capitalization by design (bug #10, Telegram-
  # style UX), so start the text with a capital letter — otherwise the IME uppercases the first
  # char and the sent/echoed text ("Hello…") would not match a lowercase assertion.
  step "Send a message"
  input_tag chat_composer_field "Hello from e2e"
  redump
  tap_desc "Send"
  wait_for_text "Hello from e2e" 10 || true
  assert_text "Hello from e2e"
  assert_no_crash

  # 21. Nick autocomplete (needs a second member).
  step "Nick autocomplete"
  if [ -n "$MOTD_SECOND_NICK" ]; then
    input_tag chat_composer_field "${MOTD_SECOND_NICK:0:2}"
    redump
    # A dropdown row prefix-matching should appear; assert the second nick shows.
    if [ -n "$(bounds_of_text "$MOTD_SECOND_NICK")" ]; then
      ok "autocomplete lists '$MOTD_SECOND_NICK'"
    else
      note "no autocomplete row (channel may lack other members)"
    fi
    # No BACK here: input_tag already closed the keyboard, so a second BACK would pop the chat
    # screen (and then exit the app). The next step's input_tag clears the composer text itself.
  else
    note "MOTD_SECOND_NICK unset; skipping conditional nick autocomplete"
  fi
  assert_no_crash

  # 22. Command autocomplete.
  step "Command autocomplete"
  input_tag chat_composer_field "/"
  redump
  if [ -n "$(bounds_of_text "/join")" ] || [ -n "$(bounds_of_text "/me")" ]; then
    ok "command dropdown present (/me, /join, …)"
  else
    note "command dropdown not detected"
  fi
  # No BACK: input_tag closed the keyboard already; a second BACK would exit the chat/app.
  assert_no_crash

  # 23. /me action.
  step "/me action"
  input_tag chat_composer_field "/me waves"
  redump
  tap_desc "Send"
  # ACTION uses the same sender-prefixed text in every layout density.
  if wait_for_text "* ${MOTD_NICK} waves" 8; then
    ok "/me action rendered"
  else
    fail "/me action did not render with its sender prefix"
  fi
  assert_no_crash

  # 24. Reaction add.
  step "Add a reaction"
  scroll_to_text "Hello from e2e" || true
  long_press_tag_prefix_containing_text chat_message_ "Hello from e2e"
  if wait_for_text "👍" 5; then
    tap_text "👍"
    ok "reaction quick-row present; tapped 👍"
  else
    note "reaction quick-row not detected"
  fi
  assert_no_crash

  # 25. More reactions.
  step "More reactions grid"
  scroll_to_text "Hello from e2e" || true
  long_press_tag_prefix_containing_text chat_message_ "Hello from e2e"
  if wait_for_text "More reactions" 5 || [ -n "$(bounds_of_desc "More reactions")" ]; then
    # ModalBottomSheet is a separate Compose window; its explicit content description is exported
    # reliably to uiautomator even when the parallel testTag is not.
    tap_desc "More reactions"
    note "opened more-reactions grid"
  else
    note "more-reactions control not detected; dismissing"
    adb_shell input keyevent 4
  fi
  assert_no_crash

  # 26. Reply.
  step "Reply bar"
  scroll_to_text "Hello from e2e" || true
  long_press_tag_prefix_containing_text chat_message_ "Hello from e2e"
  if wait_for_text "Reply" 5; then
    tap_text "Reply"
    if [ -n "$(bounds_of_text "Replying to $MOTD_NICK")" ] || wait_for_text "Replying to" 4; then
      ok "reply bar shown"
    fi
    tap_desc "Cancel reply" || true
  else
    note "Reply action not detected"
  fi
  assert_no_crash

  # 27. Copy / Quote.
  step "Copy action (no crash)"
  scroll_to_text "Hello from e2e" || true
  long_press_tag_prefix_containing_text chat_message_ "Hello from e2e"
  if wait_for_text "Copy" 5; then
    tap_text "Copy"                      # clipboard is not asserted through UI
    ok "Copy tapped"
  else
    note "Copy action not detected; dismissing"
    adb_shell input keyevent 4
  fi
  assert_no_crash

  # 28. Scroll-to-bottom FAB.
  step "Scroll-to-bottom FAB"
  # Swipe down (content up) to scroll history; then look for the FAB.
  adb_shell input swipe 540 800 540 1600 200
  redump
  if [ -n "$(bounds_of_desc "Scroll to bottom")" ]; then
    tap_desc "Scroll to bottom"
    redump
    assert_no_text "Scroll to bottom"    # gone once at bottom
  else
    note "scroll-to-bottom FAB not shown (few messages?)"
  fi
  assert_no_crash

  # 29. Search from chat.
  step "Search within the buffer"
  tap_desc "Search"
  assert_text "Search messages"
  input_by_text_label "Search messages" "hello"
  redump
  if wait_for_text "hello" 8; then
    ok "search returned a result"
    tap_text "hello" || true             # jump back to chat (deep-jump pulse)
  else
    note "no search result (FTS may be async)"
  fi
  # Ensure we're back at a chat-ish screen.
  adb_shell input keyevent 4 || true
  assert_no_crash
}

# ==========================================================================
# Phase D — channel info & moderation surface
# ==========================================================================
phase_d() {
  echo ""
  echo "${_C_CYA}########## Phase D: channel info ##########${_C_RST}"

  # 30. Open channel info by tapping the title.
  step "Open channel info"
  # Reopen the channel to guarantee we're in the chat.
  tap_tag_prefix_containing_text chatlist_row_ "$MOTD_TEST_CHANNEL"
  redump
  tap_text "$MOTD_TEST_CHANNEL"          # title area
  if wait_for_text "Channel info" 6; then
    ok "channel info open"
  else
    note "channel info title not detected"
  fi
  assert_no_crash

  # 31. Topic edit dialog.
  step "Topic edit dialog (cancel)"
  if [ -n "$(bounds_of_desc "Edit topic")" ]; then
    tap_desc "Edit topic"
    wait_for_text "Edit topic" 5 || true
    assert_text "Channel topic"
    tap_text "Cancel"
  else
    note "Edit topic control not present"
  fi
  assert_no_crash

  # 32. Mute toggle.
  step "Mute toggle"
  if [ -n "$(bounds_of_text "Mute")" ]; then
    tap_text "Mute"
    wait_for_text "Unmute" 5 || true
    assert_text "Unmute"
    tap_text "Unmute"                    # restore
  else
    note "Mute control not present"
  fi
  assert_no_crash

  # 33. Pin toggle.
  step "Pin toggle"
  if [ -n "$(bounds_of_text "Pin")" ]; then
    tap_text "Pin"
    wait_for_text "Unpin" 5 || true
    assert_text "Unpin"
  else
    note "Pin control not present"
  fi
  assert_no_crash

  # 34. Member -> nick sheet.
  step "Open a member nick sheet"
  # We cannot reliably know a member nick without the seed; use second identity
  # if provided, else best-effort.
  if [ -n "$MOTD_SECOND_NICK" ] && [ -n "$(bounds_of_text "$MOTD_SECOND_NICK")" ]; then
    tap_tag "channelinfo_member_${MOTD_SECOND_NICK}"
    if wait_for_text "Message" 5; then
      assert_text "Add to friends"
      # 35. Add friend.
      step "Add member to friends"
      tap_text "Add to friends"
      ok "added to friends"
    fi
  else
    note "no known member nick (set MOTD_SECOND_NICK + seed); skipping nick sheet"
  fi
  assert_no_crash

  # 36. Leave dialog (cancel).
  step "Leave dialog (cancel)"
  if [ -n "$(bounds_of_text "Leave")" ]; then
    tap_text "Leave"
    wait_for_text "Leave channel?" 5 || true
    assert_text "Leave channel?"
    tap_text "Cancel"
  else
    note "Leave control not present"
  fi
  assert_no_crash

  # 37. Back to chat.
  step "Back out of channel info"
  adb_shell input keyevent 4
  assert_no_crash
}

# ==========================================================================
# Phase E — channel browser
# ==========================================================================
phase_e() {
  echo ""
  echo "${_C_CYA}########## Phase E: channel browser ##########${_C_RST}"

  # 38. Open browser.
  step "Open channel browser"
  # reset_to_chatlist (run before this phase) already put us on the chat list; a BACK here would
  # pop it and exit the app, so just re-anchor instead of pressing BACK.
  reset_to_chatlist >/dev/null 2>&1 || true
  wait_for_text "motd" 6 || true
  tap_desc "New conversation"
  if wait_for_text "Browse channels…" 5; then
    tap_text "Browse channels…"
    wait_for_text "Browse channels" 8 || true
    # The browser can legitimately show a "Connect to browse channels" empty state, so assert the
    # screen title (always present) rather than the conditionally-shown search field.
    assert_text "Browse channels"
  else
    note "Browse channels… entry not found"
  fi
  assert_no_crash

  # 39. Search channels.
  step "Search channels"
  if [ -n "$(bounds_of_text "Search channels")" ]; then
    input_by_text_label "Search channels" "motd"
    redump
    if [ -n "$(bounds_of_text "No channels found")" ]; then
      note "no channels found for mask"
    else
      note "channel list rendered (or loading)"
    fi
  fi
  assert_no_crash

  # 40. Join from browser (if a result exists).
  step "Join from browser (best-effort)"
  note "join-from-browser depends on live LIST results; skipping tap to avoid state churn"

  # 41. Back.
  step "Back from browser"
  adb_shell input keyevent 4
  assert_no_crash
}

# ==========================================================================
# Phase F — settings sweep
# ==========================================================================
phase_f() {
  echo ""
  echo "${_C_CYA}########## Phase F: settings ##########${_C_RST}"

  # 42. Open settings.
  step "Open Settings"
  wait_for_text "motd" 6 || true
  tap_desc "Settings"
  wait_for_text "Settings" 8 || true
  assert_text "Settings"
  assert_tag_present settings_category_networks
  assert_tag_present settings_category_appearance
  assert_no_crash

  # 43. Networks list -> root NetworkSettings. Bouncer roots are rendered before their children,
  # so the first tagged network row is deterministic without knowing its Room id.
  step "Networks list -> NetworkSettings"
  tap_tag settings_category_networks
  wait_for_text "Networks" 6 || true
  assert_tag_present settings_add_network
  tap_tag_prefix settings_network_row_
  wait_for_text "Connect automatically" 6 || true
  assert_text "Connect automatically"
  scroll_forward_to_tag network_settings_bouncer_networks 12 || true
  assert_tag_present network_settings_bouncer_networks
  assert_no_crash

  # 44. Bouncer networks (root).
  step "Bouncer networks (root)"
  tap_tag network_settings_bouncer_networks
  wait_for_text "Soju control center" 10 || true
  assert_text "Soju control center"
  if [ -n "$(bounds_of_tag bouncer_add_network)" ]; then
    tap_tag bouncer_add_network
    wait_for_text "Server address" 5 || true
    assert_text "Server address"
    tap_text "Cancel"
  fi
  tap_tag bouncer_tab_channels
  assert_text "Channel controls"
  tap_tag bouncer_tab_account
  assert_text "Fallback identity"
  if wait_for_text "Admin" 10; then
    tap_tag bouncer_tab_admin
    assert_text "Users"
    assert_text "Server"
  else
    fail "admin BouncerServ commands were not discovered"
  fi
  tap_tag bouncer_tab_console
  assert_text "One BouncerServ command"
  adb_shell input keyevent 4            # NetworkSettings
  adb_shell input keyevent 4            # Networks category
  adb_shell input keyevent 4            # Settings root
  wait_for_text "Settings" 6 || true
  assert_tag_present settings_category_appearance
  assert_no_crash

  # 45. Theme AMOLED (color-only oracle uses a screenshot).
  step "Theme: AMOLED"
  tap_tag settings_category_appearance
  wait_for_text "Appearance" 6 || true
  assert_tag_present settings_theme_picker
  tap_tag settings_theme_picker
  # ModalBottomSheet is a separate Compose window. UIAutomator renders its visible text but does
  # not consistently export testTags as resource ids (the same boundary as AlertDialog buttons),
  # so use the sheet's exact localized labels here rather than leaving it open and cascading every
  # later Settings check onto the wrong window.
  wait_for_text "Search themes" 6 || true
  assert_text "Search themes"
  input_by_text_label "Search themes" "AMOLED"
  wait_for_text "AMOLED (true black)" 6 || true
  tap_text "AMOLED (true black)"
  screencap_step "amoled_background"   # color-only oracle
  ok "selected AMOLED (background asserted via screencap only)"
  assert_no_crash

  # 46. Colored nicknames off/on.
  step "Toggle Colored nicknames"
  tap_tag settings_switch_nick_colors
  tap_tag settings_switch_nick_colors   # toggle back
  ok "toggled Colored nicknames off/on"
  assert_no_crash

  # 47. Palette.
  step "Palette: Vivid"
  scroll_forward_to_tag settings_palette_vivid 4 || true
  tap_tag settings_palette_vivid
  ok "selected Vivid palette"
  assert_no_crash

  # 48. Nick color overrides.
  step "Nick color overrides"
  scroll_forward_to_tag settings_nick_color_overrides 4 || true
  tap_tag settings_nick_color_overrides
  wait_for_text "Nick colors" 6 || true
  assert_text "Nick colors"
  input_by_text_label "Nickname" "foo"
  redump
  tap_text "Add" || true
  if wait_for_text "Auto (no override)" 5; then
    ok "hue picker opened for 'foo'"
    # Dismiss explicitly, then wait for the separate Compose dialog window to disappear. Sending
    # BACK twice without this boundary can race recomposition and consume both events in the dialog,
    # leaving the next density checks stranded on Nick colors.
    tap_text "Cancel"
    local _dialog_wait
    for _dialog_wait in 1 2 3 4 5 6; do
      dump || true
      [ -z "$(bounds_of_text "Auto (no override)")" ] && break
      sleep 1
    done
  fi
  adb_shell input keyevent 4           # back to Appearance
  wait_for_text "Appearance" 6 || true
  assert_text "Appearance"
  assert_no_crash

  # 49. Message style Compact. This control lives below the font-size sliders.
  step "Message style: Compact"
  scroll_forward_to_tag settings_density_compact 8 || true
  tap_tag settings_density_compact
  ok "selected Compact"
  adb_shell input keyevent 4           # back to Settings root
  assert_no_crash

  # 50. Chat category: join/part, friends, and fools.
  step "Show join/part toggle"
  scroll_forward_to_tag settings_category_chat 4 || true
  tap_tag settings_category_chat
  wait_for_text "Chat" 6 || true
  tap_tag settings_switch_show_jpq
  tap_tag settings_switch_show_jpq      # restore
  ok "toggled show join/part"
  assert_no_crash

  step "Friends manage screen"
  scroll_forward_to_tag settings_friends 8 || true
  tap_tag settings_friends
  wait_for_text "Friends" 6 || true
  input_by_text_label "Nickname" "bar"
  redump
  tap_text "Add" || true
  wait_for_text "bar" 5 || true
  if [ -n "$(bounds_of_desc "Remove")" ]; then
    tap_desc "Remove"
    ok "added then removed friend 'bar'"
  fi
  adb_shell input keyevent 4
  assert_no_crash

  step "Fools manage + mode"
  scroll_forward_to_tag settings_fools 4 || true
  tap_tag settings_fools
  wait_for_text "Fools" 6 || true
  adb_shell input keyevent 4
  scroll_forward_to_tag settings_fools_mode_hide 4 || true
  tap_tag settings_fools_mode_hide
  tap_tag settings_fools_mode_collapse   # restore
  ok "toggled fools' messages mode"
  adb_shell input keyevent 4             # back to Settings root
  assert_no_crash

  # 51. Push availability (no distributor on CI -> specific disabled string).
  step "Push delivery availability"
  scroll_forward_to_tag settings_category_delivery 4 || true
  tap_tag settings_category_delivery
  wait_for_text "Message delivery" 6 || true
  assert_tag_present settings_unified_push_row
  assert_text "UnifiedPush"
  if [ -n "$(bounds_of_text "Install a UnifiedPush distributor like ntfy to receive push.")" ]; then
    ok "push disabled with expected 'install distributor' hint"
  else
    note "push hint string differs (bouncer webpush state / distributor present)"
  fi
  assert_no_crash

  # 52. Battery optimization (OS intent -> no crash).
  step "Battery optimization intent"
  scroll_forward_to_tag settings_battery_optimization 4 || true
  tap_tag settings_battery_optimization
  assert_no_crash                       # OS settings intent fired
  # The system battery activity opens asynchronously. Fixed consecutive BACK presses can race it:
  # one is consumed before launch and the next merely returns to Message delivery. Back out one
  # settled screen at a time until a tagged root category proves we actually reached Settings.
  local _back_attempt
  for _back_attempt in 1 2 3; do
    adb_shell input keyevent 4
    sleep 1
    dump || true
    if [ -n "$(bounds_of_tag settings_category_delivery)" ] ||
       [ -n "$(bounds_of_tag settings_category_uploads)" ] ||
       [ -n "$(bounds_of_tag settings_category_about)" ]; then
      break
    fi
  done
  assert_tag_present settings_category_delivery
  assert_no_crash

  # 53. About. Find the category by tag because it is normally below the root viewport.
  step "About screen"
  scroll_forward_to_tag settings_category_about 6 || true
  tap_tag settings_category_about
  wait_for_text "Diagnostic logging" 6 || true
  assert_text "Diagnostic logging"
  assert_tag_present about_diagnostic_logging_switch
  assert_tag_present about_export_diagnostics
  scroll_forward_to_tag about_github 4 || true
  assert_text "License"
  assert_text "GitHub"
  # Back out to chat list.
  adb_shell input keyevent 4            # Settings root
  adb_shell input keyevent 4            # chat list
  wait_for_text "motd" 8 || true
  assert_no_crash
}

# ==========================================================================
# Phase G — render-mode verification
# ==========================================================================
phase_g() {
  echo ""
  echo "${_C_CYA}########## Phase G: render modes ##########${_C_RST}"

  # 58. Compact render.
  step "Compact render in chat"
  tap_text "$MOTD_TEST_CHANNEL" || true
  # The chat opens scrolled to the newest message, so an older sent line may be off-screen —
  # scroll it back into view before asserting it renders.
  # Compact mode renders "nick: text" as ONE node, so match that combined form — a bare
  # "Hello from e2e" only exists as its own node in the bubble layouts.
  scroll_to_text "${MOTD_NICK}: Hello from e2e" 8 || true
  assert_text "${MOTD_NICK}: Hello from e2e"
  note "compact vs bubble structure needs chat_message_<msgid> tag to assert"
  assert_no_crash

  # 59. Restore Comfortable through the Appearance category.
  step "Restore Comfortable render"
  adb_shell input keyevent 4             # back to list
  tap_desc "Settings"
  wait_for_text "Settings" 6 || true
  tap_tag settings_category_appearance
  wait_for_text "Appearance" 6 || true
  scroll_forward_to_tag settings_density_comfortable 8 || true
  tap_tag settings_density_comfortable
  ok "restored Comfortable message style"
  adb_shell input keyevent 4            # Settings root
  adb_shell input keyevent 4            # chat list
  wait_for_text "motd" 8 || true
  assert_no_crash
}

# ==========================================================================
# Phase H — image viewer (conditional on an image message existing)
# ==========================================================================
phase_h() {
  echo ""
  echo "${_C_CYA}########## Phase H: image viewer ##########${_C_RST}"
  step "Image viewer (best-effort)"
  # Requires a message with an inline image (seeded by fixtures/seed.sh). We
  # cannot reliably locate an image node by text; skip gracefully unless the
  # full-screen image CD is already reachable.
  tap_text "$MOTD_TEST_CHANNEL" || true
  redump
  if [ -n "$(bounds_of_desc "Full-screen image")" ]; then
    assert_desc_present "Full-screen image"
    tap_desc "Back" || true
  else
    skip "phase H requires a reachable seeded inline image"
  fi
  adb_shell input keyevent 4 || true
  assert_no_crash
}

# ==========================================================================
# Phase I — teardown
# ==========================================================================
phase_i() {
  echo ""
  echo "${_C_CYA}########## Phase I: teardown ##########${_C_RST}"

  # 61. Delete a chat (swipe) — cancel to avoid destroying state mid-run.
  step "Delete-chat swipe (cancel)"
  wait_for_text "$MOTD_TEST_CHANNEL" 6 || true
  local b
  b="$(bounds_of_text "$MOTD_TEST_CHANNEL")"
  if [ -n "$b" ]; then
    # Swipe end-to-start (right->left) across the row centre to arm delete.
    # Only the y (row centre) is needed; x endpoints are the screen edges.
    local _cx cy
    read -r _cx cy <<EOF
$(_e2e_center "$b")
EOF
    adb_shell input swipe 980 "$cy" 120 "$cy" 250
    redump
    if [ -n "$(bounds_of_text "Delete chat?")" ]; then
      assert_text "Delete chat?"
      tap_text "Cancel"                  # do NOT delete; preserve for re-runs
    else
      fail "swipe did not arm the delete dialog"
    fi
  else
    fail "seed channel row not present for swipe test"
  fi
  assert_no_crash

  # 62. Final crash sweep.
  step "Final crash sweep"
  assert_no_crash

  # 63. Reset (leave device clean).
  step "Reset app state for next run"
  adb_shell pm clear "$MOTD_PKG" >/dev/null
  ok "pm clear done"
}

# ==========================================================================
# Phase J — focused Soju control center (safe, non-destructive)
# ==========================================================================
phase_j() {
  echo ""
  echo "${_C_CYA}########## Phase J: Soju control center ##########${_C_RST}"

  step "Open root bouncer tools"
  tap_desc "Settings"
  wait_for_text "Networks" 8 || true
  tap_text "Networks"
  wait_for_text "Bouncers" 8 || true
  assert_text "Bouncers"
  tap_text "$MOTD_SOJU_HOST"
  # Opening settings does not imply that the root IRC session is ready. In particular, a prior
  # run can leave this screen backed by cached state while the app is disconnected. Do not enter
  # the control center until the status action proves the root is ready; otherwise every disabled
  # capability assertion below is a misleading probe failure.
  local connection_attempt
  for connection_attempt in 1 2 3; do
    if wait_for_text "Disconnect" 2; then
      break
    fi
    if wait_for_text "Connect" 2; then
      tap_text "Connect"
    elif wait_for_text "Reconnect" 2; then
      tap_text "Reconnect"
    fi
    wait_for_text "Disconnect" 15 && break
  done
  assert_text "Disconnect"
  local attempt
  for attempt in 1 2 3 4; do
    dump || true
    [ -n "$(bounds_of_text "Bouncer networks")" ] && break
    adb_shell input swipe 540 1800 540 600 300
    sleep 1
  done
  assert_text "Bouncer networks"
  tap_text "Bouncer networks"
  wait_for_text "Soju control center" 15 || true
  assert_text "Soju control center"
  assert_no_crash

  step "Wait for server-verified BouncerServ capabilities"
  wait_for_text "BouncerServ commands verified for this connection." 20 || true
  assert_text "BouncerServ commands verified for this connection."

  step "Inspect guided panels and safe create dialog"
  tap_tag bouncer_tab_networks
  assert_tag_present bouncer_networks_panel
  tap_tag bouncer_add_network
  assert_text "Server address"
  tap_text "Cancel"
  tap_tag bouncer_tab_channels
  assert_tag_present bouncer_channels_panel
  tap_tag bouncer_tab_account
  assert_tag_present bouncer_account_panel
  if wait_for_text "Admin" 15; then
    tap_tag bouncer_tab_admin
    assert_tag_present bouncer_admin_panel
  else
    fail "admin BouncerServ commands were not discovered"
  fi
  tap_tag bouncer_tab_console
  assert_tag_present bouncer_console_panel
  assert_tag_present bouncer_console_input
  assert_no_crash

  step "Run safe BouncerServ console command"
  input_tag bouncer_console_input "network status"
  tap_desc "Send"
  for attempt in 1 2 3 4 5 6 7 8 9 10; do
    dump || true
    [ -n "$(bounds_of_tag "bouncer_command_notice")" ] && break
    sleep 1
  done
  assert_tag_present bouncer_command_notice
  assert_no_crash
}

# ==========================================================================
# Phase K — opt-in physical-device UnifiedPush verification
# ==========================================================================
phase_k() {
  echo ""
  echo "${_C_CYA}########## Phase K: UnifiedPush + ntfy ##########${_C_RST}"

  if ! adb_shell pm path io.heckel.ntfy >/dev/null 2>&1; then
    skip "phase K requires the F-Droid ntfy UnifiedPush distributor"
    return 0
  fi

  step "Enable UnifiedPush and wait for verified soju registration"
  _settings_open=false
  for _attempt in 1 2 3; do
    tap_desc "Settings"
    if wait_for_text "Settings" 6; then
      _settings_open=true
      break
    fi
  done
  if [ "$_settings_open" != true ]; then
    fail "Settings did not open after three taps"
    return 1
  fi
  tap_text "Message delivery"
  wait_for_text "Delivery method" 8 || true
  assert_tag_present settings_unified_push_row
  tap_tag settings_unified_push_row
  sleep 2
  dump || true
  if [ -n "$(bounds_of_tag 'settings_push_distributor_io.heckel.ntfy')" ]; then
    tap_tag settings_push_distributor_io.heckel.ntfy
  fi
  _push_ready=false
  for _attempt in $(seq 1 60); do
    dump || true
    if [ -n "$(bounds_of_text 'UnifiedPush active')" ]; then
      _push_ready=true
      break
    fi
    sleep 1
  done
  if [ "$_push_ready" = true ]; then
    ok "soju acknowledged the ntfy subscription"
  else
    screencap_step "unifiedpush_not_active"
    fail "UnifiedPush did not reach Active within 60 seconds"
  fi
  assert_tag_present settings_push_status_card
  assert_no_crash

  step "Background past grace period and verify fully protected sockets sleep"
  adb_shell input keyevent 3
  sleep 35
  if adb_shell dumpsys activity services "$MOTD_PKG" 2>/dev/null | grep -q 'IrcForegroundService'; then
    fail "foreground socket service still active after fully verified push hand-off"
  else
    ok "foreground socket service stopped after push hand-off"
  fi

  step "Deliver a channel highlight and DM through public ntfy"
  _token="motd-up-$(date +%s)"
  _push_sender="${MOTD_SECOND_NICK:-motdadb2}"
  "$E2E_DIR/local-stack.sh" push "$_token"
  _found=false
  for _attempt in $(seq 1 45); do
    if adb_shell dumpsys notification --noredact 2>/dev/null | grep -Fq "$_token"; then
      _found=true
      break
    fi
    sleep 1
  done
  if [ "$_found" = true ]; then
    ok "tagged UnifiedPush notification arrived"
  else
    fail "no tagged UnifiedPush notification arrived within 45 seconds"
  fi
  assert_no_crash

  step "Open the pushed conversation and verify reconnect history exactly once"
  adb_shell cmd statusbar expand-notifications >/dev/null 2>&1 || true
  sleep 2
  dump || true
  if [ -n "$(bounds_of_text "${_token}-dm")" ]; then
    tap_text "${_token}-dm"
  else
    note "notification shade did not expose exact text; opening the push-created DM from chat list"
    adb_shell input keyevent 4 >/dev/null 2>&1 || true
    reset_to_chatlist >/dev/null 2>&1 || true
    wait_for_text "$_push_sender" 15 || true
    tap_text "$_push_sender"
  fi
  wait_for_text "${_token}-dm" 30 || true
  assert_text_exactly_once "${_token}-dm"

  step "Verify foreground reconnect, channel history catch-up, and live send"
  input_tag chat_composer_field "Foreground ${_token}"
  redump
  tap_desc "Send"
  wait_for_text "Foreground ${_token}" 10 || true
  assert_text "Foreground ${_token}"
  reset_to_chatlist >/dev/null 2>&1 || true
  wait_for_text "$MOTD_TEST_CHANNEL" 10 || true
  tap_text "$MOTD_TEST_CHANNEL"
  wait_for_text "${MOTD_NICK}: ${_token}-mention" 30 || true
  assert_text_exactly_once "${MOTD_NICK}: ${_token}-mention"
  assert_no_crash

  step "Verify cold receiver delivery without force-stop"
  adb_shell input keyevent 3
  adb_shell am kill "$MOTD_PKG" >/dev/null 2>&1 || true
  _cold="${_token}-cold"
  "$E2E_DIR/local-stack.sh" push "$_cold"
  _found=false
  for _attempt in $(seq 1 45); do
    if adb_shell dumpsys notification --noredact 2>/dev/null | grep -Fq "$_cold"; then
      _found=true
      break
    fi
    sleep 1
  done
  [ "$_found" = true ] && ok "cold UnifiedPush receiver delivered" || fail "cold receiver delivery timed out"

  step "Verify delivery while the device is in forced idle"
  if adb_shell dumpsys deviceidle force-idle >/dev/null 2>&1; then
    _device_idle_forced=true
  else
    note "device does not permit forced idle"
  fi
  _doze="${_token}-doze"
  "$E2E_DIR/local-stack.sh" push "$_doze"
  _found=false
  for _attempt in $(seq 1 60); do
    if adb_shell dumpsys notification --noredact 2>/dev/null | grep -Fq "$_doze"; then
      _found=true
      break
    fi
    sleep 1
  done
  adb_shell dumpsys deviceidle unforce >/dev/null 2>&1 || true
  adb_shell dumpsys battery reset >/dev/null 2>&1 || true
  _device_idle_forced=false
  [ "$_found" = true ] && ok "forced-idle UnifiedPush delivery arrived" || fail "forced-idle delivery timed out"

  step "Verify each cold-path DM appears exactly once"
  _notification_dump="$(adb_shell dumpsys notification --noredact 2>/dev/null || true)"
  for _payload in "${_cold}-dm" "${_doze}-dm"; do
    _message_count="$(printf '%s\n' "$_notification_dump" \
      | grep 'sender_person=' \
      | grep -Fc "text=${_payload}," || true)"
    if [ "$_message_count" -eq 1 ]; then
      ok "notification contains ${_payload} exactly once"
    else
      fail "notification contains ${_payload} ${_message_count} times (expected exactly once)"
    fi
  done
  assert_no_crash
}

# ==========================================================================
# Phase S — deterministic public showcase screenshots
# ==========================================================================
phase_s() {
  echo ""
  echo "${_C_CYA}########## Phase S: showcase screenshots ##########${_C_RST}"

  step "Pin the showcase appearance"
  # Select a named app preset instead of relying on the emulator's system mode.
  # This keeps the public frames dark and stable across host images.
  tap_desc "Settings"
  wait_for_text "Settings" 8 || true
  tap_tag settings_category_appearance
  wait_for_text "Appearance" 8 || true
  tap_tag settings_theme_picker
  wait_for_text "Search themes" 6 || true
  # Keep the query shorter than the result label so tap_text cannot match the
  # focused search field itself when UIAutomator returns exact text matches.
  input_by_text_label "Search themes" "Modus"
  wait_for_text "Modus Vivendi" 6 || true
  tap_text "Modus Vivendi"
  # The sheet is a separate Compose window. Do not start scrolling the
  # underlying Appearance page until its close affordance has disappeared.
  local _theme_wait
  for _theme_wait in 1 2 3 4 5 6; do
    dump || true
    [ -z "$(bounds_of_desc "Close sheet")" ] && break
    sleep 1
  done
  wait_for_text "Appearance" 6 || true
  scroll_forward_to_tag settings_density_comfortable 10 || true
  tap_tag settings_density_comfortable
  scroll_forward_to_tag settings_avatar_style_irc_sprite 10 || true
  tap_tag settings_avatar_style_irc_sprite
  ok "selected Modus Vivendi, comfortable bubbles, and IRC sprite avatars"
  adb_shell input keyevent 4             # Settings root
  adb_shell input keyevent 4             # chat list
  wait_for_desc "New conversation" 8 || true
  assert_no_crash

  step "Capture the multi-channel chat list"
  for _showcase_channel in '#guix' '#debian' '#emacs' '#rust'; do
    wait_for_text "$_showcase_channel" 20 || true
    assert_text "$_showcase_channel"
  done
  capture_named_screenshot "chat-list"
  assert_no_crash

  step "Capture a seeded conversation"
  tap_text '#guix'
  wait_for_text "See you around the next build." 15 || true
  # Chat bubbles expose the full message as one semantics node, while the
  # sender remains its own node. Assert exact nodes that survive line wrapping.
  assert_text "alice"
  assert_text "See you around the next build."
  capture_named_screenshot "chat"
  assert_no_crash

  step "Capture the attachment source chooser"
  input_tag chat_composer_field "A small note for the next build"
  tap_tag chat_composer_attachment
  wait_for_text "Share something" 8 || true
  assert_text "Share something"
  assert_text "Photo"
  assert_text "File"
  assert_text "Current draft"
  assert_text "Text paste"
  capture_named_screenshot "file-uploader"
  assert_no_crash
}

# ==========================================================================
# Driver — phases toggled by env (default: all). E2E_PHASES="a c f" runs a subset;
# the public capture workflow uses E2E_PHASES="a s".
# ==========================================================================
main() {
  ensure_device
  echo "${_C_CYA}MOTD E2E run — pkg=${MOTD_PKG} device=${SERIAL}${_C_RST}"
  echo "${_C_CYA}bouncer=${MOTD_SOJU_HOST}:${MOTD_SOJU_PORT} nick=${MOTD_NICK} channel=${MOTD_TEST_CHANNEL}${_C_RST}"

  # Freeze animations so uiautomator can reach an idle state. Compose's blinking text cursor and
  # ripple/transition animations otherwise keep the window perpetually non-idle, and `uiautomator
  # dump` fails with "could not get idle state" (cascades through later phases). Only mutate the
  # device after all three original values are captured; the EXIT trap restores exact custom
  # values (or deletes an originally absent override) after success, failure, or interruption.
  if _capture_animation_scales; then
    adb_shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
    adb_shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
    adb_shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
  else
    note "could not capture animation scales; leaving device animation settings unchanged"
  fi

  # Phases run in order. Phase 'a' owns the expensive setup (install + onboard + connect) and
  # leaves durable device state (networks, joined channel). Every later phase begins from the
  # chat-list anchor via reset_to_chatlist, so a subset run — e.g. E2E_PHASES="c" — picks up
  # where a prior full run left off without repeating onboarding (rapid dev cycle).
  local phases="${E2E_PHASES:-a b c d e f g h i}"
  local p phase_class phase_rc failures_before findings
  for p in $phases; do
    case "$p" in
      a|b|c|i|j|s) phase_class=required ;;
      d|e|f|g) phase_class=diagnostic ;;
      h|k) phase_class=conditional ;;
      *) fail "unknown phase '$p'"; continue ;;
    esac
    note "phase $p classification: $phase_class"
    if [ "$p" != "a" ]; then
      if ! reset_to_chatlist; then
        if [ "$phase_class" = diagnostic ]; then
          _E2E_DIAGNOSTIC_FAILURES=$(( _E2E_DIAGNOSTIC_FAILURES + 1 ))
          note "phase '$p' unavailable: app is not at the chat list"
        elif [ "$phase_class" = conditional ]; then
          skip "phase '$p' requires chat state from phase A"
        else
          fail "phase '$p': app is not at the chat list (run E2E_PHASES=\"a\" first to set up state)"
        fi
        continue
      fi
    fi
    # Contain a hard early-abort to the current phase so later coverage still runs. Diagnostic
    # findings are reported separately and cannot erase or add to an earlier required failure.
    failures_before="$_E2E_FAILURES"
    phase_rc=0
    case "$p" in
      a) phase_a ;;
      b) phase_b ;;
      c) phase_c ;;
      d) phase_d ;;
      e) phase_e ;;
      f) phase_f ;;
      g) phase_g ;;
      h) phase_h ;;
      i) phase_i ;;
      j) phase_j ;;
      k) phase_k ;;
      s) phase_s ;;
      *) return 2 ;;
    esac || phase_rc=$?
    if [ "$phase_class" = diagnostic ]; then
      findings=$(( _E2E_FAILURES - failures_before ))
      [ "$phase_rc" -eq 0 ] || findings=$(( findings + 1 ))
      _E2E_DIAGNOSTIC_FAILURES=$(( _E2E_DIAGNOSTIC_FAILURES + findings ))
      _E2E_FAILURES="$failures_before"
      [ "$findings" -eq 0 ] || note "phase '$p' recorded $findings diagnostic finding(s)"
    elif [ "$phase_rc" -ne 0 ]; then
      fail "phase '$p' aborted early (see output above)"
    fi
  done
}

main "$@"
