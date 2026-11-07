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
4. When connected, use the [project Gradle MCP](.agents/README.md#gradle-and-kotlin-agent-tools)
   for Gradle project/dependency documentation and source queries. Use a working
   native Kotlin LSP or IDE MCP for semantic navigation, references, and
   refactoring—not regex as a semantic substitute.

## Architecture and implementation rules

- Splash, README, and export branding changes must not alter themed in-app or
  launcher artwork unless explicitly requested. Preserve matte ceramic shading;
  black/white treatment is not a flat silhouette.
- Patterned chat wallpaper artwork must tile seamlessly across both axes and corners.
  Wrap complete motifs and verify repeated proofs; transparent border padding
  or cropping alone does not satisfy this requirement. Preserve the approved
  dense mix of medium doodles, small motifs, and tiny accents when repairing seams.
- Scenic wallpapers use full scenes with matching day/night geometry. Animate
  named light, water, and star elements with authored pixel frames and palette
  states. Do not warp, displace, roll, or crossfade raster patches to fake motion.
  Plan animation surfaces before generating backgrounds; avoid scanlines and
  synthetic overlays. Review triggered events at phone size: nonzero pixel differences
  alone do not demonstrate perceptible or convincing motion.
  Scenic animation should be subtle and very rare, with long still intervals
  and one localized event at a time. Provide an explicitly labeled event-preview
  trigger for review; do not accelerate normal playback to demonstrate motion.
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
- AI writing styles should transform the whole message with style-appropriate
  expressions, vernacular, idioms and grammar. Prefer natural, full rewrites over
  proofreading, appended flourishes or theatrical exaggeration; preserve meaning.
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
  unit tests in each module; Required CI owns full release parity.
- For task validation, run the nearest relevant unit test method in each
  affected module. Use `nix develop -c ./gradlew :app:testDebugUnitTest --tests
  '<fully-qualified-class.method>' --stacktrace`, or `:irc:test` for protocol
  changes. Filters narrow execution, not compilation of test sources and
  dependencies. Robolectric Compose tests are local unit-tier checks.
- Before committing implementation changes, build each changed module and run
  its formatting and lint checks as described in the command matrix. Use root
  `ktlintCheck` for root Gradle/style configuration changes. Do not automatically
  append an unfiltered test suite or a full pre-push gate.
- For database changes, run the nearest database unit regression and review/commit
  generated `app/schemas` changes.
- `./tools/prepush.sh` is optional local gate reproduction only when explicitly
  requested to diagnose broader failures, not a handoff/push prerequisite or a
  substitute for hosted CI. It requires a clean, committed tree; use
  `MOTD_PREFLIGHT_BASE=<ref>` to override its `origin/main` comparison base.
- Require all applicable hosted `Required CI / gate` checks before merge. When
  an individual job fails, inspect its existing diagnostics and begin fixing it
  immediately rather than waiting for aggregate `gate`; remaining coverage
  continues normally.
- For UI changes, if a device is available, install the changed debug build using
  the [serial-targeted debug install workflow](docs/human-developing.md#debug-device-install),
  perform a quick ADB check, and inspect a fresh screencapture. Otherwise, leave
  visual testing to the user and report it as unverified. Do not start an emulator
  solely for routine verification.
- Large new features must add an emulator test for their main user journey.
  Do not run long-running emulator suites for routine task validation; hosted
  CI runs E2E coverage. Update affected `RequiredHeadlessE2eTest` journeys.
- Release-specific visual verification remains in
  [`release preflight`](.agents/releases.md).
- For explicitly requested ZNC-specific E2E diagnosis, use `test/e2e/znc-stack.sh`
  for SASL, two-client, reconnect-gap, and native-playback work. Its TLS endpoint
  is adb-reversed at `127.0.0.1:6698`; exact credentials, commands, and the observed degradation
  contract are in [`test/e2e/README.md`](test/e2e/README.md).

## Changes, commits, and releases

- Do not rewrite, discard, or reformat unrelated changes. Avoid destructive Git
  commands. Device installation for the UI check above is authorized; do not
  commit, push, tag, publish, or cut a release unless the user requests it.
- Do not add "Co-Authored-By:" trailer when LLMs have assisted with commits.
- Keep commits narrowly scoped and report the verification performed. A request
  to release authorizes the documented release workflow, not unrelated cleanup
  or silently moving an existing tag.
- Release procedure and recovery rules live in
  [`.agents/releases.md`](.agents/releases.md). GitHub workflow files remain the
  final authority when documentation and automation disagree.
