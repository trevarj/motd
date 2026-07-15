#!/usr/bin/env bash
# Canonical launcher for the existing four @FastHeadlessE2e journeys.
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$E2E_DIR/../.." && pwd)"
MODE="${1:-connected}"
shift || true
OUT_DIR="${FAST_E2E_OUT_DIR:-$E2E_DIR/artifacts/fast-suite}"
SUMMARY="$OUT_DIR/summary.json"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# shellcheck source=test/e2e/fast-suite.env
. "$E2E_DIR/fast-suite.env"
# shellcheck source=test/e2e/harness.sh
. "$E2E_DIR/harness.sh"

mkdir -p "$OUT_DIR"
rm -f "$SUMMARY"
cd "$REPO"

write_summary() {
  local result="$1" rc="$2"
  printf '{"suite":"fast-headless","mode":"%s","result":"%s","exitCode":%s,"startedAt":"%s","finishedAt":"%s"}\n' \
    "$MODE" "$result" "$rc" "$STARTED_AT" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$SUMMARY"
}

on_exit() {
  local rc=$?
  if [ "$rc" -eq 0 ]; then
    write_summary pass 0
  else
    write_summary fail "$rc"
    e2e_capture_device_artifacts "$OUT_DIR"
    if docker compose -f "$E2E_HERMETIC_COMPOSE" ps >/dev/null 2>&1; then
      e2e_capture_hermetic_artifacts "$OUT_DIR"
    fi
  fi
}
trap on_exit EXIT

instrumentation_args=(
  "-Pandroid.testInstrumentationRunnerArguments.annotation=$FAST_E2E_ANNOTATION"
  "-Pandroid.testInstrumentationRunnerArguments.sojuHost=$FAST_E2E_SOJU_HOST"
  "-Pandroid.testInstrumentationRunnerArguments.sojuPort=$FAST_E2E_SOJU_PORT"
  "-Pandroid.testInstrumentationRunnerArguments.sojuUser=$FAST_E2E_SOJU_USER"
  "-Pandroid.testInstrumentationRunnerArguments.sojuPassword=$FAST_E2E_SOJU_PASSWORD"
  "-Pandroid.testInstrumentationRunnerArguments.nick=$FAST_E2E_NICK"
  "-Pandroid.testInstrumentationRunnerArguments.channel=$FAST_E2E_CHANNEL"
  "-Pandroid.testInstrumentationRunnerArguments.secondNick=$FAST_E2E_SECOND_NICK"
)

run_gradle_suite() {
  local task="$1"
  shift
  "$REPO/gradlew" "$task" "${instrumentation_args[@]}" "$@" --stacktrace
}

discover_runner() {
  e2e_adb shell pm list instrumentation \
    | sed -n "s#^instrumentation:\([^ ]*\) (target=${FAST_E2E_TARGET_PACKAGE})#\1#p" \
    | head -1 | tr -d '\r'
}

run_direct_suite() {
  local app_apk test_apk runner listing test_class result_file
  local -a test_classes
  app_apk="${FAST_E2E_APP_APK:-$REPO/app/build/outputs/apk/foss/e2e/app-foss-e2e.apk}"
  test_apk="${FAST_E2E_TEST_APK:-$REPO/app/build/outputs/apk/androidTest/foss/e2e/app-foss-e2e-androidTest.apk}"
  "$REPO/gradlew" :app:assembleFossE2e :app:assembleFossE2eAndroidTest \
    --stacktrace --no-daemon --max-workers=1
  e2e_adb install -r -g "$app_apk" >/dev/null
  e2e_adb install -r "$test_apk" >/dev/null
  runner="$(discover_runner)"
  [ -n "$runner" ] || { echo "Unable to discover instrumentation runner for $FAST_E2E_TARGET_PACKAGE" >&2; return 1; }

  listing="$OUT_DIR/discovery.instrumentation.txt"
  e2e_adb shell am instrument -w -r -e log true -e annotation "$FAST_E2E_ANNOTATION" "$runner" \
    | tee "$listing" >/dev/null
  mapfile -t test_classes < <(
    sed -n 's/^INSTRUMENTATION_STATUS: class=//p' "$listing" | tr -d '\r' | sort -u
  )
  [ "${#test_classes[@]}" -gt 0 ] || { echo "No @$FAST_E2E_ANNOTATION tests discovered" >&2; return 1; }

  e2e_adb logcat -c
  for test_class in "${test_classes[@]}"; do
    e2e_adb shell pm clear "$FAST_E2E_TARGET_PACKAGE" >/dev/null
    result_file="$OUT_DIR/${test_class##*.}.instrumentation.txt"
    e2e_adb shell am instrument -w -r \
      -e class "$test_class" \
      -e sojuHost "$FAST_E2E_SOJU_HOST" -e sojuPort "$FAST_E2E_SOJU_PORT" \
      -e sojuUser "$FAST_E2E_SOJU_USER" -e sojuPassword "$FAST_E2E_SOJU_PASSWORD" \
      -e nick "$FAST_E2E_NICK" -e channel "$FAST_E2E_CHANNEL" \
      -e secondNick "$FAST_E2E_SECOND_NICK" "$runner" | tee "$result_file"
    grep -Eq '^OK \([1-9][0-9]* tests?\)$' "$result_file"
  done
}

case "$MODE" in
  connected) run_gradle_suite :app:connectedFossE2eAndroidTest "$@" ;;
  managed)
    run_gradle_suite headlessApi34FossE2eAndroidTest \
      -Pandroid.testoptions.manageddevices.emulator.gpu=swiftshader_indirect "$@"
    ;;
  direct) run_direct_suite ;;
  *) echo "usage: $0 {connected|managed|direct} [Gradle arguments...]" >&2; exit 2 ;;
esac
