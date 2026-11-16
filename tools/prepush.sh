#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
bases=()
all=false
candidate="$(git -C "$REPO" rev-parse --verify 'HEAD^{commit}')"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --all) all=true ;;
    --head)
      shift
      [ "$#" -gt 0 ] && [ "$1" = "$candidate" ] || {
        echo "Push candidate changed; check out the pushed commit before checking." >&2
        exit 1
      }
      ;;
    -*) echo "Usage: bash tools/prepush.sh [--all] [--head COMMIT] [BASE ...]" >&2; exit 1 ;;
    *) bases+=("$1") ;;
  esac
  shift
done
if ! "$all" && [ "${#bases[@]}" -eq 0 ]; then
  bases+=("${MOTD_PREFLIGHT_BASE:-origin/main}")
fi
cd "$REPO"
[ -z "$(git status --porcelain)" ] || {
  echo "pre-push checks require a clean committed tree" >&2
  exit 1
}
paths_file="$(mktemp)"
trap 'rm -f "$paths_file"' EXIT
for base in "${bases[@]}"; do
  base="$(git rev-parse --verify --end-of-options "$base^{commit}")"
  git diff --check "$base" "$candidate"
  git diff --name-only -z "$base" "$candidate" >> "$paths_file"
done
mapfile -d '' -t changed < "$paths_file"
if "$all"; then
  changed+=(unknown/runtime.surface)
fi
runtime=false irc=false app=false device=false harness=false debug_lint=false
classification="$(bash ./tools/ci-paths.sh "${changed[@]}" </dev/null)"
while IFS='=' read -r tier selected; do
  case "$tier:$selected" in
    runtime:true|irc:true|app:true|device:true|harness:true|debug_lint:true) declare "$tier=true" ;;
    runtime:false|irc:false|app:false|device:false|harness:false|debug_lint:false) ;;
    *) echo "Invalid CI path classification: $tier=$selected" >&2; exit 1 ;;
  esac
done <<< "$classification"

gradle() { nix develop -c bash ./gradlew "$@" --stacktrace; }
export MOTD_FUZZ_PROFILE=pr MOTD_FUZZ_SEED="$candidate"
changed_shell=()
for path in "${changed[@]}"; do
  [[ "$path" == *.sh && -f "$path" ]] && changed_shell+=("$path")
done
[ "${#changed_shell[@]}" -eq 0 ] || bash -n "${changed_shell[@]}"
catalog_args=()
for base in "${bases[@]}"; do
  catalog_args+=(--base "$base")
done
nix develop -c python3 tools/palette-catalog.py --check "${catalog_args[@]}"
nix develop -c python3 -m unittest discover -s tools -p test_palette_catalog.py
if "$harness"; then
  nix develop -c actionlint
  nix develop -c bash ./test/e2e/validate.sh
fi
if "$irc"; then
  gradle :irc:ktlintCheck :irc:test
fi
if "$app"; then
  gradle ktlintCheck
  gradle :app:testDebugUnitTest
  git diff --exit-code -- app/schemas
  test -z "$(git ls-files --others --exclude-standard -- app/schemas)"
  # Keep producer failure distinct from grep's ordinary no-match status.
  dependencies="$(nix develop -c bash ./gradlew :app:dependencies --configuration releaseRuntimeClasspath)"
  if grep -Ei 'firebase|play-services' <<< "$dependencies"; then
    echo "Google-only dependencies reached the FOSS runtime classpath" >&2
    exit 1
  fi
  nix develop -c bash ./tools/build-signed-release.sh --ci-key
  MOTD_LINT_DEBUG="$debug_lint" MOTD_LINT_LOG=app/build/reports/android-lint.log \
    nix develop -c bash ./tools/android-lint.sh
  gradle --no-daemon --no-parallel --max-workers=2 :app:verifyAiNativeArtifacts
fi
if "$device"; then
  gradle :app:assembleE2e :app:assembleE2eAndroidTest
  echo "Emulator journey runtime remains required in hosted CI; no local device is started or installed."
fi
[ "$(git rev-parse --verify 'HEAD^{commit}')" = "$candidate" ] || {
  echo "HEAD changed during pre-push checks" >&2
  exit 1
}
[ -z "$(git status --porcelain)" ] || {
  echo "pre-push checks changed the working tree" >&2
  exit 1
}
echo "Pre-push checks passed for $candidate"
