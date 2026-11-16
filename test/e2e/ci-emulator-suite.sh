#!/usr/bin/env bash
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERIAL="${1:?CI emulator serial is required}"
if [ "${CI:-}" != true ] || [ "${GITHUB_ACTIONS:-}" != true ] || [[ ! "$SERIAL" =~ ^emulator-[0-9]+$ ]]; then
  echo "package cleanup requires a GitHub CI-owned emulator" >&2
  exit 1
fi
qemu="$(adb -s "$SERIAL" shell getprop ro.kernel.qemu)"
[ "${qemu//$'\r'/}" = 1 ] || { echo "refusing package cleanup on a non-emulator" >&2; exit 1; }
packages="$(adb -s "$SERIAL" shell pm list packages)"
for package in io.github.trevarj.motd.debug.test io.github.trevarj.motd.debug; do
  if grep -Fxq "package:$package" <<<"${packages//$'\r'/}"; then
    result="$(adb -s "$SERIAL" uninstall "$package")"
    [ "${result//$'\r'/}" = Success ] || { echo "CI emulator package uninstall failed: $package" >&2; exit 1; }
  fi
done
export SERIAL
exec bash "$E2E_DIR/fast-suite.sh" direct
