#!/usr/bin/env bash
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/repo/test/e2e" "$work/bin"
cp "$E2E_DIR"/{fast-suite.sh,fast-suite.env,harness.sh,fast-suite-classifier.sh,fast-suite-privacy.sh,ci-emulator-suite.sh} "$work/repo/test/e2e/"
export CALLS="$work/calls" FAIL=none PACKAGES=installed CI=true GITHUB_ACTIONS=true SERIAL=emulator-5554
export FAST_E2E_STACK_KIND=native FAST_E2E_SOJU_TLS_SHA256
FAST_E2E_SOJU_TLS_SHA256="$(printf '%064d' 0)"
export FAST_E2E_OUT_DIR="$work/output"

printf '#!%s\n' "$(command -v bash)" >"$work/repo/gradlew"
cat >>"$work/repo/gradlew" <<'FAKE'
printf 'BUILD\n' >>"$CALLS"
[ "$FAIL" != build ]
FAKE
printf '#!%s\n' "$(command -v bash)" >"$work/bin/adb"
cat >>"$work/bin/adb" <<'FAKE'
set -eu
[ "$1" = -s ] && [ "$2" = emulator-5554 ] || exit 90
shift 2
printf 'ADB %s\n' "$*" >>"$CALLS"
case "$*" in
  'shell getprop ro.kernel.qemu')
    [ "$FAIL" != qemu ] || exit 21
    if [ "$FAIL" = non-emulator ]; then echo 0; else echo 1; fi ;;
  'shell pm list packages')
    [ "$FAIL" != packages ] || exit 22
    echo package:unrelated.app
    if [ "$PACKAGES" = installed ]; then
      printf 'package:io.github.trevarj.motd.debug\npackage:io.github.trevarj.motd.debug.test\n'
    fi ;;
  uninstall*)
    [ "$FAIL" != uninstall ] || exit 23
    if [ "$FAIL" = uninstall-output ]; then echo Failure; else echo Success; fi ;;
  install*)
    if [[ "$*" = *androidTest* ]]; then
      [ "$FAIL" != test-install ] || exit 24
    else
      [ "$FAIL" != app-install ] || exit 25
    fi
    echo Success ;;
  'shell pm list instrumentation')
    echo 'instrumentation:io.github.trevarj.motd.debug.test/Runner (target=io.github.trevarj.motd.debug)'
    [ "$FAIL" != runner ] || exit 26 ;;
  'shell pm clear '*)
    [ "$FAIL" != clear ] || exit 27
    if [ "$FAIL" = clear-output ]; then echo Failed; else echo Success; fi ;;
  'shell am instrument '*'-e log true '*)
    for i in 1 2 3 4; do
      printf 'INSTRUMENTATION_STATUS: class=Test\nINSTRUMENTATION_STATUS: test=case%s\n' "$i"
    done
    [ "$FAIL" != discovery ] || exit 28 ;;
  'shell am instrument '*)
    echo RUN >>"$CALLS"
    printf 'INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_CODE: -1\n' ;;
  exec-out*) exit 1 ;;
  *) echo "unexpected fake adb call: $*" >&2; exit 91 ;;
esac
FAKE
chmod +x "$work/repo/gradlew" "$work/bin/adb"
export PATH="$work/bin:$PATH"
launcher="$work/repo/test/e2e/fast-suite.sh"
cleanup="$work/repo/test/e2e/ci-emulator-suite.sh"

run() {
  local expected="$1" rc=0
  shift
  : >"$CALLS"
  bash "$@" >"$work/stdout" 2>"$work/stderr" || rc=$?
  if { [ "$expected" = pass ] && [ "$rc" -ne 0 ]; } || { [ "$expected" = fail ] && [ "$rc" -eq 0 ]; }; then
    cat "$work/stdout" "$work/stderr" >&2
    echo "unexpected launcher status $rc (FAIL=$FAIL)" >&2
    exit 1
  fi
}
no_run() { ! grep -qx RUN "$CALLS"; }
no_build() { ! grep -qx BUILD "$CALLS"; }

# Exercise the launcher's real run_attempt || rc=$? boundary, not a copied function.
for FAIL in build app-install test-install runner discovery clear clear-output; do
  export FAIL
  run fail "$launcher" direct
  no_run
  case "$FAIL" in
    build|app-install|test-install) ! grep -q 'shell am instrument' "$CALLS" ;;
  esac
done
FAIL=none
run pass "$launcher" direct
[ "$(grep -cx RUN "$CALLS")" = 4 ]

for PACKAGES in installed absent; do
  export PACKAGES
  run pass "$cleanup" emulator-5554
  [ "$(grep -cx RUN "$CALLS")" = 4 ]
  if [ "$PACKAGES" = installed ]; then
    [ "$(grep -c '^ADB uninstall ' "$CALLS")" = 2 ]
    grep -qx 'ADB uninstall io.github.trevarj.motd.debug.test' "$CALLS"
    grep -qx 'ADB uninstall io.github.trevarj.motd.debug' "$CALLS"
  else
    ! grep -q '^ADB uninstall ' "$CALLS"
  fi
done
PACKAGES=installed
for FAIL in qemu non-emulator packages uninstall uninstall-output; do
  export FAIL
  run fail "$cleanup" emulator-5554
  no_build
  no_run
done
FAIL=none
run fail "$cleanup" physical-device
[ ! -s "$CALLS" ]
GITHUB_ACTIONS=false
run fail "$cleanup" emulator-5554
[ ! -s "$CALLS" ]
echo 'fast-suite prerequisite and CI emulator cleanup checks passed'
