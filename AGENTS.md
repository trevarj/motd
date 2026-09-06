# AGENTS.md — motd ground rules

This file is the mandatory policy layer for work in this repository. Detailed
workflows live in [`.agents/`](.agents/README.md); operational E2E instructions
live beside the harness in [`test/e2e/`](test/e2e/README.md).

## Start here

1. Read the user request, then inspect `git status`, the relevant diff, and the
   implementation before editing. Existing changes belong to the user unless
   proven otherwise; preserve them and stage only your own work.
2. Treat current source, Gradle configuration, tests, scripts, and GitHub
   workflows as authoritative.
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
  [`.agents/testing.md`](.agents/testing.md). Changed behavior must add or update
  the nearest regression, or name an existing regression that already exercises
  the changed branch. Compile-only evidence does not replace a behavior test.
- The app is a single Google-free build with no product flavors. Push delivery
  is UnifiedPush only; do not reintroduce Firebase/FCM or a Play Store
  distribution unless the maintainer explicitly asks for it. Lint warnings are
  errors.
  When a change crosses modules or release behavior, run the nearest affected
  tests in each module; Required CI owns full release parity.
- While editing, run the nearest relevant test method; before handoff/push, run
  its class once. Use `nix develop -c ./gradlew :app:testDebugUnitTest --tests
  '<fully-qualified-class.method>' --stacktrace`, replacing the filter with the
  class for handoff and the task with `:irc:test` for protocol changes. Filters
  narrow execution, not compilation of test sources and dependencies. Humans
  may enter `nix develop` once and run Gradle there. Robolectric Compose tests
  are local unit-tier checks, not hosted-emulator follow-up.
- Before handoff, run each changed module's existing `:app:ktlintCheck`,
  `:irc:ktlintCheck`, or `:ai-whisper:ktlintCheck` once. Use root `ktlintCheck`
  only for root Gradle/style configuration changes. Do not automatically append
  an unfiltered suite, Android lint, APK assembly, or a full pre-push gate.
- For database changes, run the nearest database regression and review/commit
  generated `app/schemas` changes. Run `:app:assembleDebug` when resources,
  manifest, packaging, or an actual APK require it.
- `./tools/prepush.sh` is optional local gate reproduction only when explicitly
  requested to diagnose broader failures, not a handoff/push prerequisite or a
  substitute for hosted CI. It requires a clean, committed tree; use
  `MOTD_PREFLIGHT_BASE=<ref>` to override its `origin/main` comparison base.
- Require all applicable hosted `Required CI / gate` checks before merge. When
  an individual job fails, inspect its existing diagnostics and begin fixing it
  immediately rather than waiting for aggregate `gate`; remaining coverage
  continues normally.
- Do not run emulator/device E2E as part of routine local development. Before
  committing a change that affects a journey covered by `RequiredHeadlessE2eTest`,
  inspect and update that journey in the same commit and run
  `:app:compileE2eAndroidTestKotlin`. Reserve
  `nix develop -c ./test/e2e/headless.sh fast` for
  behavior that cannot be validated below E2E; do not defer a known required-gate
  mismatch until after push. Use a physical device only when the maintainer
  explicitly requests hardware/OS validation.
- Use `test/e2e/znc-stack.sh` for ZNC-specific SASL, two-client, reconnect-gap,
  and native-playback work. Its TLS endpoint is adb-reversed at
  `127.0.0.1:6698`; exact credentials, commands, and the observed degradation
  contract are in [`test/e2e/README.md`](test/e2e/README.md).

## Changes, commits, and releases

- Do not rewrite, discard, or reformat unrelated changes. Avoid destructive Git
  commands. Do not commit, push, tag, install on a device, publish, or cut a
  release unless the user requests that action.
- Do not add "Co-Authored-By:" trailer when LLMs have assisted with commits.
- Keep commits narrowly scoped and report the verification performed. A request
  to release authorizes the documented release workflow, not unrelated cleanup
  or silently moving an existing tag.
- Release procedure and recovery rules live in
  [`.agents/releases.md`](.agents/releases.md). GitHub workflow files remain the
  final authority when documentation and automation disagree.
