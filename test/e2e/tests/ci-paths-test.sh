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

none=$'runtime=false\nirc=false\napp=false\ndevice=false\npackage=false\nharness=false\ndebug_lint=false'
irc_test=$'runtime=true\nirc=true\napp=false\ndevice=false\npackage=false\nharness=false\ndebug_lint=false'
app_test=$'runtime=true\nirc=false\napp=true\ndevice=false\npackage=false\nharness=false\ndebug_lint=false'
production_irc=$'runtime=true\nirc=true\napp=true\ndevice=true\npackage=false\nharness=false\ndebug_lint=false'
production_app=$'runtime=true\nirc=false\napp=true\ndevice=true\npackage=false\nharness=false\ndebug_lint=false'
device_only=$'runtime=true\nirc=false\napp=false\ndevice=true\npackage=false\nharness=true\ndebug_lint=false'
packaging=$'runtime=true\nirc=false\napp=true\ndevice=true\npackage=true\nharness=false\ndebug_lint=false'
harness=$'runtime=true\nirc=false\napp=false\ndevice=false\npackage=false\nharness=true\ndebug_lint=false'
all=$'runtime=true\nirc=true\napp=true\ndevice=true\npackage=true\nharness=true\ndebug_lint=true'

assert_paths "$none" README.md docs/testing.md
assert_paths "$irc_test" irc/src/test/kotlin/ParserTest.kt
assert_paths "$app_test" app/src/test/kotlin/RepoTest.kt app/src/testDebug/kotlin/UiTest.kt
assert_paths "$production_irc" irc/src/main/kotlin/IrcClient.kt
assert_paths "$production_app" app/src/main/kotlin/MainActivity.kt
assert_paths "$device_only" app/src/androidTest/kotlin/RequiredHeadlessE2eTest.kt
assert_paths "$packaging" app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml
assert_paths "$harness" .github/workflows/fuzz.yml
assert_paths "$harness" test/e2e/fast-suite.sh
assert_paths "$all" .github/workflows/ci.yml
assert_paths "$all" unexpected/runtime.surface
