#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
. "$ROOT/test/e2e/fast-suite-privacy.sh"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/required-e2e"
printf '{"phase":"launcher_started"}\n' >"$scratch/fixture.jsonl"
printf '{"test":"RequiredHeadlessE2eTest_send"}\n' >"$scratch/required-e2e/started.jsonl"
e2e_audit_required_artifacts "$scratch"
printf '{"message":"sentinel"}\n' >"$scratch/required-e2e/semantics.json"
if e2e_audit_required_artifacts "$scratch" >/dev/null 2>&1; then
  echo "privacy audit accepted message sentinel" >&2
  exit 1
fi
