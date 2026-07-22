#!/usr/bin/env bash
set -euo pipefail

E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mapfile -t scripts < <(find "$E2E_DIR" -type f -name '*.sh' -print | sort)
bash -n "${scripts[@]}"
[ -x "$E2E_DIR/fast-suite.sh" ] || {
  echo "fast-suite.sh must remain executable" >&2
  exit 1
}
"$E2E_DIR/tests/fast-suite-classifier-test.sh"
"$E2E_DIR/tests/fast-suite-privacy-test.sh"
if command -v docker >/dev/null 2>&1; then
  "$E2E_DIR/hermetic-stack.sh" validate
else
  echo "SKIP: docker unavailable; Compose validation remains required in CI" >&2
fi
