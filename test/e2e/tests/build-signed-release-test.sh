#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
unset MOTD_KEYSTORE_PATH MOTD_KEYSTORE_PASSWORD MOTD_KEY_ALIAS MOTD_KEY_PASSWORD
export TMPDIR="$scratch/keys" ANDROID_SDK_ROOT="$scratch/sdk" STATE="$scratch/state"
mkdir -p "$TMPDIR" "$STATE" "$scratch/repo/tools" "$scratch/bin" "$ANDROID_SDK_ROOT/build-tools/36.0.0/lib"
cp "$ROOT/tools/build-signed-release.sh" "$scratch/repo/tools/"
touch "$ANDROID_SDK_ROOT/build-tools/36.0.0/lib/apksigner.jar"
helper="$scratch/repo/tools/build-signed-release.sh"

# Replace expensive build/verifier boundaries, not the helper's credential or key lifecycle.
cat > "$scratch/repo/gradlew" <<'SH'
set -euo pipefail
printf '%s' "$MOTD_KEYSTORE_PATH" > "$STATE/key-path"
keytool -list -keystore "$MOTD_KEYSTORE_PATH" -storepass:env MOTD_KEYSTORE_PASSWORD >/dev/null
[ "${BUILD_STATUS:-0}" -eq 0 ] || exit "$BUILD_STATUS"
mkdir -p app/build/outputs/apk/release
printf '%s' '{"elements":[{"outputFile":"release candidate.apk"}]}' > app/build/outputs/apk/release/output-metadata.json
printf 'built APK fixture\n' > 'app/build/outputs/apk/release/release candidate.apk'
SH
printf '#!%s\n' "$BASH" > "$scratch/bin/java"
cat >> "$scratch/bin/java" <<'SH'
set -euo pipefail
if [ "$1" = -jar ]; then
  cp "${@: -1}" "$STATE/signature-input"
  exit "${SIGNATURE_STATUS:-0}"
fi
cp "${@: -1}" "$STATE/policy-input"
exit "${POLICY_STATUS:-0}"
SH
chmod +x "$scratch/bin/java"
export PATH="$scratch/bin:$PATH"

expect_status() {
  local expected="$1" actual=0
  shift
  "$@" > "$scratch/output" 2>&1 || actual=$?
  [ "$actual" -eq "$expected" ] || {
    cat "$scratch/output" >&2
    echo "Expected exit $expected, got $actual" >&2
    exit 1
  }
}
assert_keys_cleaned() {
  local leftovers
  shopt -s nullglob dotglob
  leftovers=("$TMPDIR"/*)
  [ "${#leftovers[@]}" -eq 0 ]
  [ ! -f "$STATE/key-path" ] || [ ! -e "$(cat "$STATE/key-path")" ]
}

# Production mode must not synthesize credentials, even for a partially configured caller.
expect_status 1 bash "$helper"
expect_status 1 env MOTD_KEYSTORE_PATH="$scratch/production.jks" MOTD_KEYSTORE_PASSWORD=provided MOTD_KEY_ALIAS=provided bash "$helper"
[ ! -f "$STATE/key-path" ]
assert_keys_cleaned

# Explicit CI mode refuses any existing signing environment, including an empty variable.
for name in MOTD_KEYSTORE_PATH MOTD_KEYSTORE_PASSWORD MOTD_KEY_ALIAS MOTD_KEY_PASSWORD; do
  expect_status 1 env "$name=" bash "$helper" --ci-key
done
[ ! -f "$STATE/key-path" ]
assert_keys_cleaned

# Key generation failures clean up even a partially written keystore.
printf '#!%s\n' "$BASH" > "$scratch/bin/keytool"
cat >> "$scratch/bin/keytool" <<'SH'
printf 'partial key' > "$MOTD_KEYSTORE_PATH"
exit 23
SH
chmod +x "$scratch/bin/keytool"
expect_status 23 bash "$helper" --ci-key
assert_keys_cleaned
rm "$scratch/bin/keytool"

# No verifier may hide a failed build; no policy check may hide a bad signature.
expect_status 31 env BUILD_STATUS=31 bash "$helper" --ci-key
[ -f "$STATE/key-path" ] && [ ! -f "$STATE/signature-input" ] && [ ! -f "$STATE/policy-input" ]
assert_keys_cleaned
expect_status 32 env SIGNATURE_STATUS=32 bash "$helper" --ci-key
[ -f "$STATE/signature-input" ] && [ ! -f "$STATE/policy-input" ]
assert_keys_cleaned
expect_status 33 env POLICY_STATUS=33 bash "$helper" --ci-key
[ -f "$STATE/policy-input" ]
assert_keys_cleaned
expect_status 0 bash "$helper" --ci-key
cmp "$STATE/signature-input" "$scratch/repo/app/build/outputs/apk/release/release candidate.apk"
cmp "$STATE/signature-input" "$STATE/policy-input"
assert_keys_cleaned
