#!/usr/bin/env bash
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mapfile -t git_env < <(git rev-parse --local-env-vars)
unset "${git_env[@]}"
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
gradle_tasks=()
if "$runtime"; then
  gradle_tasks+=(ktlintCheck)
fi
if "$irc"; then
  gradle_tasks+=(:irc:compileKotlin)
fi
if "$app" || "$device"; then
  gradle_tasks+=(:app:compileDebugKotlin)
fi
if [ "${#gradle_tasks[@]}" -gt 0 ]; then
  nix develop -c env JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=1 bash ./gradlew \
    --no-daemon --no-parallel --max-workers=1 \
    "-Dorg.gradle.jvmargs=-Xmx2g -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8" \
    -Pkotlin.compiler.execution.strategy=in-process \
    "${gradle_tasks[@]}" --stacktrace
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
