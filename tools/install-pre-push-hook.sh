#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
if hooks_path="$(git config --get core.hooksPath)"; then
  echo "Refusing custom core.hooksPath=$hooks_path; no hooks changed." >&2
  exit 1
else
  status=$?
  [ "$status" -eq 1 ] || exit "$status"
fi
hook="$(git rev-parse --path-format=absolute --git-path hooks/pre-push)"
pinned_bash="$(nix develop -c bash -c 'printf "%s\n" "$BASH"')"
[[ "$pinned_bash" = /* && "$pinned_bash" != *$'\n'* && "$pinned_bash" != *' '* && -x "$pinned_bash" ]] || {
  echo "Could not resolve an executable absolute project Bash path" >&2
  exit 1
}
printf -v wrapper '#!%s\n# motd repository-local pre-push wrapper\nset -euo pipefail\nrepo="$(git rev-parse --show-toplevel)"\nexec %q "$repo/tools/pre-push-hook.sh" "$@"\n' \
  "$pinned_bash" "$pinned_bash"
if [ -e "$hook" ] || [ -L "$hook" ]; then
  if [ -L "$hook" ] || [ ! -f "$hook" ] || [ "$(cat "$hook")" != "${wrapper%$'\n'}" ]; then
    echo "Refusing existing unrecognized hook: $hook" >&2
    exit 1
  fi
  chmod +x "$hook"
  echo "Pre-push hook already installed: $hook"
  exit 0
fi
mkdir -p "$(dirname "$hook")"
# Noclobber also refuses a hook created after the existence check.
(set -o noclobber; printf '%s' "$wrapper" > "$hook")
chmod +x "$hook"
echo "Installed repository-local pre-push hook: $hook"
