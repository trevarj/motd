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
   `:app:ktlintCheck`, `:irc:ktlintCheck`, `:ai-whisper:ktlintCheck`,
   or `:ai-text:ktlintCheck` once before handoff; root
   Gradle/style configuration changes still require root `ktlintCheck`.
   Keep the nearest database regression and review/commit of
   generated `app/schemas`, `:app:compileE2eAndroidTestKotlin` for affected
   instrumentation journeys, and `:app:assembleDebug` when resources, manifest,
   packaging, or an actual APK require it. Do not automatically append an
   unfiltered suite, Android lint, APK assembly, or a full pre-push gate.
   Routine feature/fix work does not run local emulator, screenshot, or video
   verification. Only when cutting a release, perform the focused actual-emulator
   checks in [`releases.md`](releases.md), reusing the owned warm session in
   [`../test/e2e/README.md`](../test/e2e/README.md). Full local E2E suites remain
   non-routine, and physical-device validation requires explicit maintainer
   authorization.
   `./tools/prepush.sh` is only an explicitly requested diagnostic for broader
   failures, requires a clean committed tree, and accepts
   `MOTD_PREFLIGHT_BASE=<ref>` to override `origin/main`; it is not a handoff/push
   prerequisite or a substitute for hosted CI. Require all
   applicable hosted `Required CI / gate` checks before merge. Inspect an
   individual failed job's existing diagnostics and start its fix immediately,
   without waiting for aggregate `gate`; remaining coverage continues normally.
   Inspect the diff and report any verification that could not be performed.

## Gradle and Kotlin agent tools

Root [`.mcp.json`](../.mcp.json) exposes the `gradle` stdio server to project-local
clients. From the repository root, start/discover it through the client's MCP
support (OMP reads this file), or launch `nix develop .#mcp -c gradle-mcp stdio`
for a client-managed initialize → `tools/list` exchange. The opt-in shell extends
the ordinary Android/JDK 21 environment; ordinary shells do not fetch the JAR.
It pins [rnett/gradle-mcp 0.0.15](https://github.com/rnett/gradle-mcp/releases/tag/0.0.15),
Apache-2.0, at source revision
[`9ccea8cf2582032e7e72f546bfe4fdfdc733683d`](https://github.com/rnett/gradle-mcp/tree/9ccea8cf2582032e7e72f546bfe4fdfdc733683d),
with the Maven Central JAR's fixed SHA-256 in `flake.nix`. Client
`instructions: false` avoids importing third-party server guidance as policy.

Use the connected server's actual schemas: `gradle_docs` for this project's
Gradle **9.8.0** (set `version: "9.8.0"`), `inspect_dependencies` for a narrow
module/configuration (`checkUpdates: false` unless requested), and
`read_dependency_sources` / `search_dependency_sources` for library APIs.
Supply the absolute repository `projectRoot` explicitly; the launcher also sets
`GRADLE_MCP_PROJECT_ROOT` from its working directory when unset. `gradle` can run
scoped tasks, with `query_build` / `wait_build` for results. Build/model tools
execute Gradle configuration and may download dependencies: they are not
read-only by default. Do not publish public Build Scans, automatically run full
builds/suites, install server skills, or change global client configuration.
[`testing.md`](testing.md) and all existing Required CI rules still govern checks.

Verified with Gradle 9.8.0: version-specific docs, all five project modules,
`:irc` dependency resolution, and resolved Okio source access. This does not
establish IDE-style Kotlin reference/refactoring support.

For Kotlin semantics, prefer a working native LSP or the
[JetBrains IDE MCP](https://www.jetbrains.com/help/idea/mcp-server.html) when a
compatible running IDE has imported/indexed this project and supplies its real
copied local client configuration; never invent an endpoint. Keep access approval
enabled and brave mode off; start with analysis/navigation tools.
Skip [official Kotlin skills](https://kotlinlang.org/docs/ai-for-development.html#kotlin-ai-skills)
for now: their current backend/KMP/migration triggers do not match this task.
Skip the [MCP Kotlin SDK](https://kotlinlang.org/docs/kotlin-ai-apps-development-overview.html#model-context-protocol-mcp-kotlin-sdk):
it implements clients/servers, not Kotlin code intelligence; no app dependency is needed.

## Task guides

- [`testing.md`](testing.md) — targeted local checks and hosted verification gates.
- [`releases.md`](releases.md) — signed tags, release artifacts, and failure
  recovery.
- [`../test/e2e/README.md`](../test/e2e/README.md) — local stack, physical
  device, hermetic emulator, phases, selectors, and diagnostics.
- [`../docs/obfuscation.md`](../docs/obfuscation.md) — SOCKS5, Tor, and embedded
  VLESS (TCP + REALITY or WebSocket + TLS) behavior and validation.
- [`../docs/ntfy-push.md`](../docs/ntfy-push.md) — delivery backends.
