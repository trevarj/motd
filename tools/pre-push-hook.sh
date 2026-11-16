#!/usr/bin/env bash
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"
head="$(git rev-parse --verify 'HEAD^{commit}')"
declare -A bases=()
all=false
updates=false
while read -r local_ref local_oid remote_ref remote_oid extra || [ -n "${local_ref:-}" ]; do
  if [ -n "${extra:-}" ] || [[ ! "$local_oid" =~ ^([0-9a-fA-F]{40}|[0-9a-fA-F]{64})$ ]] ||
    [[ ! "$remote_oid" =~ ^([0-9a-fA-F]{40}|[0-9a-fA-F]{64})$ ]]; then
    echo "Invalid pre-push ref update" >&2
    exit 1
  fi
  git check-ref-format "$remote_ref"
  if [[ "$local_oid" =~ ^0+$ ]]; then
    [ "$local_ref" = '(delete)' ] || git check-ref-format "$local_ref"
    continue
  fi
  [ "$local_ref" = HEAD ] || git check-ref-format "$local_ref"
  candidate="$(git rev-parse --verify "$local_oid^{commit}")"
  if [ "$candidate" != "$head" ]; then
    echo "Cannot check $local_ref: check out pushed commit $candidate before pushing (current HEAD is $head)." >&2
    exit 1
  fi
  updates=true
  if [[ "$remote_oid" =~ ^0+$ ]]; then
    all=true
  elif base="$(git rev-parse --verify "$remote_oid^{commit}" 2>/dev/null)"; then
    bases["$base"]=1
  else
    all=true
  fi
done
if "$updates"; then
  args=(--head "$head")
  "$all" && args+=(--all)
  for base in "${!bases[@]}"; do args+=("$base"); done
  exec "$BASH" "$REPO/tools/prepush.sh" "${args[@]}"
fi
