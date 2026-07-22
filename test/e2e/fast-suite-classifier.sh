#!/usr/bin/env bash
# Shared, side-effect-free retry classifier for the required fast suite.

FAST_E2E_RETRY_CLASSES=(
  EMULATOR_ACTION_BEFORE_SCRIPT
  EMULATOR_DEVICE_OFFLINE_BEFORE_TEST
  FIXTURE_HEALTHCHECK_TIMEOUT
  FIXTURE_PROVISIONING_STARTUP_FAILURE
  ORCHESTRATOR_INSTALL_TRANSPORT_FAILURE
)

e2e_retry_allowed() {
  local candidate="$1"
  local allowed
  for allowed in "${FAST_E2E_RETRY_CLASSES[@]}"; do
    [ "$candidate" = "$allowed" ] && return 0
  done
  return 1
}

e2e_attempt_started() {
  [ -s "$1/required-e2e/started.jsonl" ]
}

# [declared] may only come from a harness-written pretest.json structural phase record. Unknown
# strings are deliberately demoted, so a new failure mode cannot silently become retriable.
e2e_classify_attempt() {
  local artifacts="$1" declared="${2:-UNKNOWN}"
  if e2e_attempt_started "$artifacts"; then
    printf 'POST_TEST_FAILURE\n'
  elif e2e_retry_allowed "$declared"; then
    printf '%s\n' "$declared"
  else
    printf 'UNKNOWN\n'
  fi
}
