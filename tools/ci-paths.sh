#!/usr/bin/env bash
set -euo pipefail

paths=("$@")
[ "${#paths[@]}" -gt 0 ] || mapfile -t paths

runtime=false
irc=false
app=false
device=false
package=false
harness=false
debug_lint=false

all_tiers() {
  runtime=true
  irc=true
  app=true
  device=true
  package=true
  harness=true
  debug_lint=true
}

for path in "${paths[@]}"; do
  [ -n "$path" ] || continue
  case "$path" in
    *.md|LICENSE|docs/*|site/*|screenshots/*|fastlane/metadata/*)
      ;;
    irc/src/test/*)
      runtime=true; irc=true
      ;;
    irc/src/main/*|irc/build.gradle.kts)
      runtime=true; irc=true; app=true; device=true
      ;;
    app/src/test/*|app/src/testDebug/*)
      runtime=true; app=true
      ;;
    app/src/androidTest/*|app/src/e2e/*)
      runtime=true; device=true; harness=true
      ;;
    app/src/main/kotlin/*)
      runtime=true; app=true; device=true
      ;;
    app/src/main/res/*|app/src/main/AndroidManifest.xml)
      runtime=true; app=true; device=true; package=true
      ;;
    app/src/debug/*)
      runtime=true; app=true; package=true; debug_lint=true
      ;;
    app/src/release/*|app/libs/*|third_party/sing-box/source.lock)
      runtime=true; app=true; device=true; package=true
      ;;
    app/schemas/*)
      runtime=true; app=true
      ;;
    app/build.gradle.kts)
      runtime=true; app=true; device=true; package=true; debug_lint=true
      ;;
    test/e2e/*)
      runtime=true; harness=true
      ;;
    .github/workflows/ci.yml)
      all_tiers
      ;;
    .github/actions/setup-native-toolchain/*)
      runtime=true; app=true; package=true; harness=true
      ;;
    .github/workflows/*|.github/actions/*|tools/android-lint.sh|tools/ci-paths.sh|tools/prepush.sh|flake.nix|flake.lock|.envrc)
      runtime=true; harness=true
      ;;
    build.gradle.kts|settings.gradle.kts|gradle.properties|gradlew|gradlew.bat|gradle/*)
      all_tiers
      ;;
    *)
      # Unknown runtime surface: spend resources rather than silently lose coverage.
      all_tiers
      ;;
  esac
done

printf 'runtime=%s\nirc=%s\napp=%s\ndevice=%s\npackage=%s\nharness=%s\ndebug_lint=%s\n' \
  "$runtime" "$irc" "$app" "$device" "$package" "$harness" "$debug_lint"
