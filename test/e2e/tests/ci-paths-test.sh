#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CLASSIFIER="$ROOT/tools/ci-paths.sh"

assert_paths() {
  local expected="$1"
  shift
  local actual
  actual="$(bash "$CLASSIFIER" "$@")"
  [ "$actual" = "$expected" ] || {
    printf 'classification mismatch for %s\nexpected:\n%s\nactual:\n%s\n' "$*" "$expected" "$actual" >&2
    exit 1
  }
}

none=$'runtime=false\nirc=false\napp=false\ndevice=false\nharness=false\ndebug_lint=false'
irc_test=$'runtime=true\nirc=true\napp=false\ndevice=false\nharness=false\ndebug_lint=false'
app_test=$'runtime=true\nirc=false\napp=true\ndevice=false\nharness=false\ndebug_lint=false'
production_irc=$'runtime=true\nirc=true\napp=true\ndevice=true\nharness=false\ndebug_lint=false'
production_app=$'runtime=true\nirc=false\napp=true\ndevice=true\nharness=false\ndebug_lint=false'
device_only=$'runtime=true\nirc=false\napp=false\ndevice=true\nharness=true\ndebug_lint=false'
harness=$'runtime=true\nirc=false\napp=false\ndevice=false\nharness=true\ndebug_lint=false'
signed_release=$'runtime=true\nirc=false\napp=true\ndevice=false\nharness=true\ndebug_lint=false'
debug=$'runtime=true\nirc=false\napp=true\ndevice=false\nharness=false\ndebug_lint=true'
app_build=$'runtime=true\nirc=false\napp=true\ndevice=true\nharness=false\ndebug_lint=true'
all=$'runtime=true\nirc=true\napp=true\ndevice=true\nharness=true\ndebug_lint=true'

assert_paths "$none" README.md docs/testing.md
assert_paths "$irc_test" irc/src/test/kotlin/ParserTest.kt
assert_paths "$app_test" app/src/test/kotlin/RepoTest.kt app/src/testDebug/kotlin/UiTest.kt
assert_paths "$production_irc" irc/src/main/kotlin/IrcClient.kt
assert_paths "$production_app" app/src/main/kotlin/MainActivity.kt
assert_paths "$device_only" app/src/androidTest/kotlin/RequiredHeadlessE2eTest.kt
assert_paths "$production_app" app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
assert_paths "$harness" .github/workflows/fuzz.yml
assert_paths "$harness" test/e2e/fast-suite.sh
assert_paths "$signed_release" .github/workflows/release.yml
assert_paths "$signed_release" tools/build-signed-release.sh
assert_paths "$signed_release" .github/scripts/CheckApkSigningBlocks.java
assert_paths "$signed_release" .github/actions/setup-native-toolchain/action.yml
assert_paths "$debug" app/src/debug/AndroidManifest.xml
assert_paths "$app_build" app/build.gradle.kts
assert_paths "$production_app" app/src/release/AndroidManifest.xml app/libs/libbox.aar third_party/sing-box/source.lock
assert_paths "$production_irc" irc/src/test/kotlin/ParserTest.kt app/src/main/kotlin/MainActivity.kt
assert_paths "$all" .github/workflows/ci.yml
assert_paths "$all" unexpected/runtime.surface
