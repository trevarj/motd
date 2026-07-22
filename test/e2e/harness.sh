#!/usr/bin/env bash
# Shared lifecycle and failure-artifact helpers for connected and managed-device E2E runners.

E2E_HARNESS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
E2E_HERMETIC_COMPOSE="$E2E_HARNESS_DIR/hermetic/docker-compose.yml"

e2e_adb() {
  if [ -n "${SERIAL:-}" ]; then adb -s "$SERIAL" "$@"; else adb "$@"; fi
}

e2e_capture_device_artifacts() {
  local output_dir="$1"
  mkdir -p "$output_dir"
  e2e_adb exec-out screencap -p >"$output_dir/screenshot.png" 2>/dev/null || true
  e2e_adb logcat -d -v threadtime >"$output_dir/logcat.txt" 2>/dev/null || true
}

e2e_capture_hermetic_artifacts() {
  local output_dir="$1"
  mkdir -p "$output_dir"
  docker compose -f "$E2E_HERMETIC_COMPOSE" logs --no-color \
    >"$output_dir/stack-services.log" 2>&1 || true
  docker compose -f "$E2E_HERMETIC_COMPOSE" ps --all \
    >"$output_dir/stack-status.txt" 2>&1 || true
  docker compose -f "$E2E_HERMETIC_COMPOSE" config --images \
    >"$output_dir/stack-images.txt" 2>&1 || true
}

e2e_capture_native_stack_artifacts() {
  local output_dir="$1" stack_dir="$2"
  mkdir -p "$output_dir"
  cp "$stack_dir/soju.log" "$output_dir/soju.log" 2>/dev/null || true
  cp "$stack_dir/ergo.log" "$output_dir/ergo.log" 2>/dev/null || true
  {
    if command -v soju >/dev/null 2>&1; then soju -version 2>&1 || true; fi
    if command -v ergo >/dev/null 2>&1; then ergo version 2>&1 || true; fi
  } >"$output_dir/stack-versions.txt"
}

e2e_pull_required_e2e_artifacts() {
  local output_dir="$1"
  mkdir -p "$output_dir"
  # The instrumentation package owns these files. Unlike shared external storage, its internal
  # files directory is always mounted; run-as keeps the pull scoped to the debuggable test APK.
  e2e_adb exec-out run-as "$FAST_E2E_TEST_PACKAGE" tar -C files -cf - required-e2e 2>/dev/null \
    | tar -C "$output_dir" -xf - 2>/dev/null || true
}

e2e_collect_gradle_required_e2e_artifacts() {
  local output_dir="$1" source_file relative
  while IFS= read -r -d '' source_file; do
    relative="${source_file#*required-e2e/}"
    mkdir -p "$output_dir/required-e2e/$(dirname "$relative")"
    cp "$source_file" "$output_dir/required-e2e/$relative"
  done < <(find "$E2E_HARNESS_DIR/../../app/build/outputs" -type f \
    -path '*additional*' -path '*/required-e2e/*' -print0 2>/dev/null)
}
