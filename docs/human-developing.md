# Building, linting, and testing motd

Enter the repository Nix shell first so the JDK and Android SDK match CI:

```sh
nix develop
```

direnv loads the same shell automatically via `.envrc` if you use it. All
Gradle commands below assume you are already inside this shell.

## Prerequisites

- Before rebuilding the bundled libbox AAR, initialize submodules recursively:

```sh
git submodule update --init --recursive
```

## Build

```sh
./gradlew :irc:test                   # protocol tests (pure JVM)
./gradlew :app:testDebugUnitTest  # app unit tests (Robolectric)
./gradlew :app:assembleDebug      # Google-free arm64 debug APK
```

The debug APK lands under `app/build/outputs/apk/debug/`. Install it with
`adb install`. The debug build carries the `.debug` application-id suffix, so
it can coexist with a release install.

The embedded VLESS + REALITY transport uses bundled libbox, which is
arm64-v8a-only. APKs built from this source tree must not be installed on
32-bit ARM or x86 devices. Other ABI support needs a separately pinned and
verified libbox artifact.

## Verification

Use the authoritative local command matrix in
[`.agents/testing.md`](../.agents/testing.md). While editing, run the nearest
relevant test method; before handoff/push, run its class once. For cross-module
changes, do this for the nearest affected tests in each module:

```sh
./gradlew :app:testDebugUnitTest \
  --tests '<fully-qualified-class.method>' --stacktrace
```

Use the class filter for handoff and `:irc:test` for protocol changes. Filters
narrow execution, not compilation of test sources and dependencies. The command
above assumes the Nix shell; alternatively use
`nix develop -c ./gradlew :app:testDebugUnitTest --tests '<fully-qualified-class.method>' --stacktrace`.

Run each changed module's existing `:app:ktlintCheck`, `:irc:ktlintCheck`, or
`:ai-whisper:ktlintCheck` once before handoff. Root Gradle/style configuration
changes still require root `ktlintCheck`. Do not automatically append an
unfiltered suite, Android lint, APK assembly, or a full pre-push gate.

For database changes, run the nearest database regression and review/commit
generated `app/schemas` changes. Compile affected instrumentation journeys with
`:app:compileE2eAndroidTestKotlin`. Run `:app:assembleDebug` when resources,
manifest, packaging, or an actual APK require it. No routine local emulator or
physical-device runs.

`./tools/prepush.sh` is only an explicitly requested diagnostic for broader
failures, not a handoff/push prerequisite or a substitute for hosted CI. It
requires a clean committed tree; `MOTD_PREFLIGHT_BASE=<ref>` overrides its
`origin/main` comparison base.

Require all applicable hosted `Required CI / gate` checks before merge. Inspect
an individual failed job's existing diagnostics and begin fixing it immediately
rather than waiting for aggregate `gate`; remaining coverage continues normally.

## Device and E2E testing

Do not run the headless emulator suite during routine local development; it
materially slows the maintainer's workstation. Local verification stops at the
nearest unit/integration tests and assembly only when needed.

For the local stack, physical-device, and emulator harnesses, follow
[`../test/e2e/README.md`](../test/e2e/README.md). The agent-facing selection
matrix in [`../.agents/testing.md`](../.agents/testing.md) describes which
suite fits which task. Those harnesses have their own shell requirements
documented alongside them.

## Architecture

For data flow, connection ownership, and module boundaries, see
[`../ARCHITECTURE.md`](../ARCHITECTURE.md).
