#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
. "$ROOT/test/e2e/fast-suite-classifier.sh"

scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
mkdir -p "$scratch/required-e2e"

[ "$(e2e_classify_attempt "$scratch" EMULATOR_DEVICE_OFFLINE_BEFORE_TEST)" = EMULATOR_DEVICE_OFFLINE_BEFORE_TEST ]
[ "$(e2e_classify_attempt "$scratch" FIXTURE_HEALTHCHECK_TIMEOUT)" = FIXTURE_HEALTHCHECK_TIMEOUT ]
[ "$(e2e_classify_attempt "$scratch" ASSERTION_FAILURE)" = UNKNOWN ]
[ "$(e2e_classify_attempt "$scratch" UNKNOWN)" = UNKNOWN ]
printf '{"test":"RequiredHeadlessE2eTest_send"}\n' >"$scratch/required-e2e/started.jsonl"
[ "$(e2e_classify_attempt "$scratch" EMULATOR_DEVICE_OFFLINE_BEFORE_TEST)" = POST_TEST_FAILURE ]
