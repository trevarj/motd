#!/usr/bin/env bash
# Canonical launcher for exactly three isolated @FastHeadlessE2e methods.
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$E2E_DIR/../.." && pwd)"
MODE="${1:-connected}"
shift || true
OUT_DIR="${FAST_E2E_OUT_DIR:-$E2E_DIR/artifacts/fast-suite}"
SUMMARY="$OUT_DIR/summary.json"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
. "$E2E_DIR/fast-suite.env"
. "$E2E_DIR/harness.sh"
. "$E2E_DIR/fast-suite-classifier.sh"
. "$E2E_DIR/fast-suite-privacy.sh"

mkdir -p "$OUT_DIR"
rm -rf "$OUT_DIR/required-e2e"
rm -f "$SUMMARY" "$OUT_DIR/pretest.json"
printf '{"phase":"launcher_started"}\n' >"$OUT_DIR/fixture.jsonl"
cd "$REPO"

fixture_fingerprint() {
  case "$FAST_E2E_STACK_KIND" in
    hermetic) "$E2E_DIR/hermetic-stack.sh" tls-fingerprint ;;
    *) printf '%s\n' "${FAST_E2E_SOJU_TLS_SHA256:?FAST_E2E_SOJU_TLS_SHA256 is required for non-hermetic stacks}" ;;
  esac
}

TLS_SHA256="$(fixture_fingerprint)"
[[ "$TLS_SHA256" =~ ^[0-9a-f]{64}$ ]] || { echo "fixture certificate fingerprint is invalid" >&2; exit 2; }

instrumentation_args=(
  "-Pandroid.testInstrumentationRunnerArguments.annotation=$FAST_E2E_ANNOTATION"
  "-Pandroid.testInstrumentationRunnerArguments.sojuHost=$FAST_E2E_SOJU_HOST"
  "-Pandroid.testInstrumentationRunnerArguments.sojuPort=$FAST_E2E_SOJU_PORT"
  "-Pandroid.testInstrumentationRunnerArguments.sojuUser=$FAST_E2E_SOJU_USER"
  "-Pandroid.testInstrumentationRunnerArguments.sojuPassword=$FAST_E2E_SOJU_PASSWORD"
  "-Pandroid.testInstrumentationRunnerArguments.nick=$FAST_E2E_NICK"
  "-Pandroid.testInstrumentationRunnerArguments.channel=$FAST_E2E_CHANNEL"
  "-Pandroid.testInstrumentationRunnerArguments.sojuTlsSha256=$TLS_SHA256"
  "-Pandroid.testInstrumentationRunnerArguments.e2eRunId=$FAST_E2E_RUN_ID"
)

write_summary() {
  local result="$1" rc="$2" started="$3" classification="$4" attempts="$5"
  printf '{"suite":"required-headless","mode":"%s","result":"%s","exitCode":%s,"testsStarted":%s,"failureClass":"%s","attempts":%s,"startedAt":"%s","finishedAt":"%s"}\n' \
    "$MODE" "$result" "$rc" "$started" "$classification" "$attempts" "$STARTED_AT" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$SUMMARY"
}

pretest_classification() {
  [ -f "$OUT_DIR/pretest.json" ] || { printf 'UNKNOWN\n'; return; }
  sed -n 's/.*"class":"\([A-Z_]*\)".*/\1/p' "$OUT_DIR/pretest.json" | head -1 | grep . || printf 'UNKNOWN\n'
}

run_gradle_suite() {
  local task="$1"
  shift
  set +e
  "$REPO/gradlew" "$task" "${instrumentation_args[@]}" "$@" --stacktrace
  local rc=$?
  set -e
  return "$rc"
}

assert_three_gradle_results() {
  local count
  count="$(find "$REPO/app/build/outputs/androidTest-results" -type f -name '*.xml' -mmin -15 -print0 2>/dev/null \
    | xargs -0 -r grep -ho '<testcase ' | wc -l | tr -d ' ')"
  [ "$count" = 3 ] || { echo "required fast suite must report exactly 3 cases; got ${count:-0}" >&2; return 1; }
}

discover_direct_methods() {
  local runner="$1"
  # Keep only fixed test identifiers; raw instrumentation output is neither retained nor uploaded.
  e2e_adb shell am instrument -w -r -e log true -e annotation "$FAST_E2E_ANNOTATION" "$runner" \
    | awk -F= '
        /^INSTRUMENTATION_STATUS: class=/ { klass=$2 }
        /^INSTRUMENTATION_STATUS: test=/ && klass != "" { print klass "#" $2 }
      ' | tr -d '\r' | sort -u
}

run_direct_suite() {
  local app_apk test_apk runner method rc=0
  local -a methods
  app_apk="${FAST_E2E_APP_APK:-$REPO/app/build/outputs/apk/foss/e2e/app-foss-e2e.apk}"
  test_apk="${FAST_E2E_TEST_APK:-$REPO/app/build/outputs/apk/androidTest/foss/e2e/app-foss-e2e-androidTest.apk}"
  "$REPO/gradlew" :app:assembleFossE2e :app:assembleFossE2eAndroidTest --stacktrace --no-daemon --max-workers=1
  e2e_adb install -r -g "$app_apk" >/dev/null
  e2e_adb install -r "$test_apk" >/dev/null
  runner="$(e2e_adb shell pm list instrumentation | sed -n "s#^instrumentation:\([^ ]*\) (target=${FAST_E2E_TARGET_PACKAGE})#\1#p" | head -1 | tr -d '\r')"
  [ -n "$runner" ] || return 1
  mapfile -t methods < <(discover_direct_methods "$runner")
  [ "${#methods[@]}" -eq 3 ] || { echo "required fast suite must discover exactly 3 Class#method cases" >&2; return 1; }
  for method in "${methods[@]}"; do
    e2e_adb shell pm clear "$FAST_E2E_TARGET_PACKAGE" >/dev/null
    e2e_adb shell am instrument -w -r -e class "$method" \
      -e sojuHost "$FAST_E2E_SOJU_HOST" -e sojuPort "$FAST_E2E_SOJU_PORT" \
      -e sojuUser "$FAST_E2E_SOJU_USER" -e sojuPassword "$FAST_E2E_SOJU_PASSWORD" \
      -e nick "$FAST_E2E_NICK" -e channel "$FAST_E2E_CHANNEL" \
      -e sojuTlsSha256 "$TLS_SHA256" -e e2eRunId "$FAST_E2E_RUN_ID" "$runner" >/dev/null || rc=$?
    e2e_pull_required_e2e_artifacts "$OUT_DIR"
    [ "$rc" -eq 0 ] || return "$rc"
  done
}

run_attempt() {
  case "$MODE" in
    connected) run_gradle_suite :app:connectedFossE2eAndroidTest "$@" ;;
    managed) run_gradle_suite headlessApi34FossE2eAndroidTest -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect "$@" ;;
    direct) run_direct_suite ;;
    *) echo "usage: $0 {connected|managed|direct} [Gradle arguments...]" >&2; return 2 ;;
  esac
}

attempts=1
rc=0
run_attempt "$@" || rc=$?
e2e_pull_required_e2e_artifacts "$OUT_DIR"
if [ "$MODE" != direct ] && [ "$rc" -eq 0 ]; then assert_three_gradle_results || rc=1; fi
declared="$(pretest_classification)"
classification="$(e2e_classify_attempt "$OUT_DIR" "$declared")"
started=false
e2e_attempt_started "$OUT_DIR" && started=true
if [ "$rc" -ne 0 ] && [ "$started" = false ] && e2e_retry_allowed "$classification"; then
  attempts=2
  rc=0
  run_attempt "$@" || rc=$?
  e2e_pull_required_e2e_artifacts "$OUT_DIR"
  if [ "$MODE" != direct ] && [ "$rc" -eq 0 ]; then assert_three_gradle_results || rc=1; fi
  classification="$(e2e_classify_attempt "$OUT_DIR" "$(pretest_classification)")"
  e2e_attempt_started "$OUT_DIR" && started=true
fi
e2e_audit_required_artifacts "$OUT_DIR" || { write_summary fail 1 "$started" PRIVACY_AUDIT_FAILED "$attempts"; exit 1; }
if [ "$rc" -ne 0 ]; then
  write_summary fail "$rc" "$started" "$classification" "$attempts"
  exit "$rc"
fi
write_summary pass 0 "$started" NONE "$attempts"
