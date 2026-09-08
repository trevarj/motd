#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
signing_vars=(MOTD_KEYSTORE_PATH MOTD_KEYSTORE_PASSWORD MOTD_KEY_ALIAS MOTD_KEY_PASSWORD)
case "${1:-}" in
  --ci-key)
    [ "$#" -eq 1 ] || { echo "Usage: $0 [--ci-key]" >&2; exit 1; }
    for name in "${signing_vars[@]}"; do
      [ ! -v "$name" ] || { echo "--ci-key refuses existing $name" >&2; exit 1; }
    done
    key_dir="$(mktemp -d)"
    trap 'rm -rf "$key_dir"' EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    export MOTD_KEYSTORE_PATH="$key_dir/ci.jks"
    export MOTD_KEYSTORE_PASSWORD=motd-ci-only MOTD_KEY_ALIAS=motd-ci MOTD_KEY_PASSWORD=motd-ci-only
    keytool -genkeypair -noprompt -keystore "$MOTD_KEYSTORE_PATH" -storetype JKS \
      -storepass:env MOTD_KEYSTORE_PASSWORD -keypass:env MOTD_KEY_PASSWORD \
      -alias "$MOTD_KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 2 -dname 'CN=MOTD CI'
    ;;
  '')
    [ "$#" -eq 0 ] || { echo "Usage: $0 [--ci-key]" >&2; exit 1; }
    ;;
  *) echo "Usage: $0 [--ci-key]" >&2; exit 1 ;;
esac
for name in "${signing_vars[@]}"; do
  [ -n "${!name:-}" ] || { echo "Required signing variable missing: $name" >&2; exit 1; }
done
[ -s "$MOTD_KEYSTORE_PATH" ] || { echo "Signing keystore is missing or empty" >&2; exit 1; }
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:?Pinned Android SDK environment required; use nix develop}}"
apksigner="$sdk/build-tools/36.0.0/lib/apksigner.jar"
[ -f "$apksigner" ] || { echo "Missing pinned APK signature verifier: $apksigner" >&2; exit 1; }

# Keep release packaging out of the debug/E2E graph and its retained daemon heap.
bash ./gradlew --no-daemon --no-parallel --max-workers=2 :app:verifyReleaseAiNativeArtifacts --stacktrace
apk="$(node <<'JS'
const fs = require('node:fs');
const path = require('node:path');
const output = 'app/build/outputs/apk/release';
const { elements } = JSON.parse(fs.readFileSync(path.join(output, 'output-metadata.json'), 'utf8'));
if (elements.length !== 1) throw new Error('Expected exactly one release APK');
console.log(path.resolve(output, elements[0].outputFile));
JS
)"
java -jar "$apksigner" verify --verbose "$apk"
java .github/scripts/CheckApkSigningBlocks.java "$apk"
