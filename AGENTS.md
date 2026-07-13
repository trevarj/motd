# AGENTS.md — MOTD ground rules

This file is the mandatory policy layer for work in this repository. Detailed
workflows live in [`.agents/`](.agents/README.md); operational E2E instructions
live beside the harness in [`test/e2e/`](test/e2e/README.md).

## Start here

1. Read the user request, then inspect `git status`, the relevant diff, and the
   implementation before editing. Existing changes belong to the user unless
   proven otherwise; preserve them and stage only your own work.
2. Treat current source, Gradle configuration, tests, scripts, and GitHub
   workflows as authoritative. `plans/` records the original design process and
   is historical reference material, not a contract.
3. Read [`ARCHITECTURE.md`](ARCHITECTURE.md) and the task-specific guide linked
   from [`.agents/README.md`](.agents/README.md). Prefer `rg`/`rg --files` when
   locating code.

## Architecture and implementation rules

- `:irc` is pure JVM: no Android imports. Keep parsing, protocol state, and
  transport behavior testable with fake transports.
- `EventProcessor` is the sole writer of IRC-derived state to Room. UI reads
  state through repositories and delegates connection/protocol actions to
  `ConnectionManager`; feature-local Android work may use its own repository or
  service boundary.
- Use idiomatic coroutines and `Flow`, sealed state/event hierarchies, and
  constructor injection. Compose screens should be stateless where practical,
  with ViewModels owning state and side effects. Add stable semantics/test tags
  when UI behavior needs automation.
- Keep dependency versions centralized in `gradle/libs.versions.toml`. Do not
  add or change dependencies casually; explain and test any necessary catalog
  change. Hilt and Room use KSP only—never kapt. Release minification remains
  disabled unless the maintainer explicitly scopes a change to it.
- Transport constraints are boundary-specific: IRC TCP/TLS in `:irc` uses
  okio over `Socket`/`SSLSocket`; app-side WSS uses the pinned OkHttp stack;
  existing preview/upload code uses `HttpURLConnection` and streams content.
  Do not introduce a second networking stack without an explicit reason.
- Preserve database migrations and serialized preference compatibility unless
  the user explicitly says migration is unnecessary for that change.

## Build and verification

- The supported local environment is the Nix flake. Run project tooling as
  `nix develop -c ...`; do not recommend Guix packages, apt, Homebrew, a global
  Android SDK, or unpinned replacement tooling.
- Match verification to the affected surface, using the command matrix in
  [`.agents/testing.md`](.agents/testing.md). Tests for changed behavior are
  part of the implementation, not optional follow-up work.
- CI treats both FOSS and Google flavors as supported. Lint warnings are errors.
  When a change crosses modules or release behavior, run the full documented
  build rather than only the nearest unit test.
- Device-sensitive UI, lifecycle, connection, and performance work requires an
  appropriate emulator or physical-device check when one is available. Use the
  local bouncer and E2E runbook rather than a live personal network.
- Use `test/e2e/znc-stack.sh` for ZNC-specific SASL, two-client, reconnect-gap,
  and native-playback work. Its TLS endpoint is adb-reversed at
  `127.0.0.1:6698`; exact credentials, commands, and the observed degradation
  contract are in [`test/e2e/README.md`](test/e2e/README.md).

## Changes, commits, and releases

- Do not rewrite, discard, or reformat unrelated changes. Avoid destructive Git
  commands. Do not commit, push, tag, install on a device, publish, or cut a
  release unless the user requests that action.
- Keep commits narrowly scoped and report the verification performed. A request
  to release authorizes the documented release workflow, not unrelated cleanup
  or silently moving an existing tag.
- Release procedure and recovery rules live in
  [`.agents/releases.md`](.agents/releases.md). GitHub workflow files remain the
  final authority when documentation and automation disagree.
