# MOTD agent handbook

`AGENTS.md` contains mandatory repository policy. This directory contains the
task-oriented guidance needed to apply that policy without turning the root file
into a long runbook.

## Repository orientation

- `app/` — Android application, Compose UI, Room persistence, preferences,
  uploads, push, connection lifecycle, and Android transport integration.
- `irc/` — pure-JVM IRC parser, serializer, client state machine, extensions,
  and socket transport.
- `test/e2e/` — fast isolated headless, physical-device, and exhaustive emulator
  harnesses plus the local ergo/soju bouncer stack.
- `.github/workflows/` — current CI, smoke, exhaustive E2E, and release behavior.

Read [`../ARCHITECTURE.md`](../ARCHITECTURE.md) before changing data flow,
connection ownership, or module boundaries.

## Working on a feature or fix

1. Inspect `git status`, existing diffs, nearby implementation, tests, and
   callers. Reproduce a bug before changing it when practical.
2. Identify the narrowest authoritative boundary: `:irc` for protocol behavior;
   repositories and `EventProcessor` for IRC-derived persistence;
   `ConnectionManager` for connection actions; ViewModels for screen state.
3. Implement the smallest coherent change. Keep Android types out of `:irc`,
   avoid whole-file buffering for uploads, and preserve cancellation/lifecycle
   behavior in long-running work.
4. Add or update tests at the same boundary. For UI changes, include semantics
   or stable tags when the interaction belongs in the device harness.
5. Follow [`testing.md`](testing.md): while editing, run the nearest relevant
   test method; before handoff/push, run its class once, in each affected module
   for cross-module changes. Use `nix develop -c ./gradlew
   :app:testDebugUnitTest --tests '<fully-qualified-class.method>' --stacktrace`
   (class filter for handoff; `:irc:test` for protocol changes). Filters narrow
   execution, not compilation of test sources and dependencies; humans may
   enter `nix develop` once. Run each changed module's existing
   `:app:ktlintCheck`, `:irc:ktlintCheck`, or `:ai-whisper:ktlintCheck` once before
   handoff; root Gradle/style configuration changes still require root
   `ktlintCheck`. Keep the nearest database regression and review/commit of
   generated `app/schemas`, `:app:compileE2eAndroidTestKotlin` for affected
   instrumentation journeys, and `:app:assembleDebug` when resources, manifest,
   packaging, or an actual APK require it. Do not automatically append an
   unfiltered suite, Android lint, APK assembly, or a full pre-push gate; no
   routine local emulator/device runs. `./tools/prepush.sh` is only an explicitly
   requested diagnostic for broader failures, requires a clean committed tree,
   and accepts `MOTD_PREFLIGHT_BASE=<ref>` to override `origin/main`; it is not a
   handoff/push prerequisite or a substitute for hosted CI. Require all
   applicable hosted `Required CI / gate` checks before merge. Inspect an
   individual failed job's existing diagnostics and start its fix immediately,
   without waiting for aggregate `gate`; remaining coverage continues normally.
   Inspect the diff and report any verification that could not be performed.

## Task guides

- [`testing.md`](testing.md) — targeted local checks and hosted verification gates.
- [`releases.md`](releases.md) — signed tags, release artifacts, and failure
  recovery.
- [`../test/e2e/README.md`](../test/e2e/README.md) — local stack, physical
  device, hermetic emulator, phases, selectors, and diagnostics.
- [`../docs/obfuscation.md`](../docs/obfuscation.md) — SOCKS5, Tor, and VLESS +
  REALITY behavior and validation.
- [`../docs/ntfy-push.md`](../docs/ntfy-push.md) — delivery backends.
