# Testing and verification

Run all Gradle commands through the repository Nix shell (`nix develop -c ...`,
or enter `nix develop` once and invoke Gradle there). For task validation, run
the nearest relevant unit test method in each affected module. Changed behavior
needs a new or updated regression unless an existing named test already exercises
that branch. Do not automatically run a full test class, unfiltered suite, emulator
suite, or full pre-push gate.

For UI changes, if a device is available, install the changed debug build using
the [serial-targeted debug install workflow](../docs/human-developing.md#debug-device-install),
use ADB for a quick check, and inspect a fresh screencapture. Otherwise, report
visual testing as unverified and leave it to the user. Do not boot an emulator
solely for a routine check.
Add an emulator test covering the main journey of a large new feature; hosted CI
runs it.

Before committing implementation changes, build each changed module with
`:app:assembleDebug`, `:irc:assemble`, `:ai-whisper:assembleDebug`, or
`:ai-text:assembleDebug`, as applicable. Run each changed module's existing
`ktlintCheck` and each changed Android module's `lintDebug`. Root Gradle/style
configuration changes require root `ktlintCheck`. Use the corresponding
`ktlintFormat` task to apply enforced style.

The local Nix development shells limit Gradle to two workers without cross-project
parallelism, JVM processor-sized pools to two CPUs, and each native Ninja build to
a shared two-job compile/link pool. Gradle and Kotlin daemon heaps remain capped
at 4 GiB each. These limits leave desktop headroom, not an aggregate OS resource
quota; hosted builds without the shell environment retain their existing settings.

## Command matrix

| Changed surface | Task validation (in addition to pre-commit build and lint above) |
| --- | --- |
| Documentation only | `git diff --check`; verify links, commands, and referenced paths |
| Shell harness/config | `bash -n test/e2e/*.sh test/e2e/fixtures/*.sh test/e2e/hermetic/*/*.sh` plus the relevant dry run |
| IRC parser/client/transport | Nearest `:irc:test` unit test method |
| Android repositories, services, preferences, or ViewModels | Nearest `:app` unit test method |
| Room entities/schema/migrations | Nearest database unit regression; review and commit generated `app/schemas` changes |
| Compose Kotlin | Nearest Robolectric behavior method in `testDebug` |
| Resources/manifest/packaging | Nearest unit test when behavior changed |
| Instrumentation source, affected journey, or large new feature | Update the affected journey or add an emulator test for the feature's main journey; hosted CI runs E2E coverage |
| Ordinary app user journey | Nearest unit test method |
| Cross-module or release-sensitive work | Nearest affected unit test methods in each module |
| Cutting a release | Nearest local checks plus focused actual-emulator visual verification below and [`releases.md`](releases.md) preflight |

To target one unit test method:

```sh
nix develop -c ./gradlew :app:testDebugUnitTest \
  --tests '<fully-qualified-class.method>' --stacktrace
```

Use `:irc:test` instead for protocol tests. These filters narrow execution, not
compilation of the test source set and its dependencies. Release visual checks
use the x86_64 `:app:assembleE2e` APK instead.

App test JVMs export `java.base/jdk.internal.access` to unnamed modules in
`app/build.gradle.kts` so Robolectric 4.17's FileDescriptor interceptor can use
`SharedSecrets` on JDK 21. No broader module opens are needed for this access.

## Behavior-test arrangements

Arrange the state needed for the behavior under test. Select typed navigation
targets rather than translated display labels. Give generic editor operations
explicit input trees; rely on stock defaults only in default/reset tests. Reuse
existing local helpers rather than adding a second generic fixture framework.
Keep security, persistence, migration, accessibility, and legitimate boundary
assertions; remove coupling to incidental wording, catalog size, or default-menu
layout outside tests of those contracts.
For failure mappings, assert category-keyed expectations rather than enum order;
shared runtime categories must each retain an explicit expected result.

## Optional local gate reproduction

`./tools/prepush.sh` is only an explicitly requested diagnostic for broader
failures, not a normal handoff/push prerequisite and not a substitute for hosted
CI. It requires the candidate tree to be committed and clean:

```sh
./tools/prepush.sh
```

The script compares `HEAD` with `origin/main`, uses the exact commit as the PR
fuzz seed, and runs only applicable deterministic non-emulator checks. Override
the comparison base with `MOTD_PREFLIGHT_BASE=<ref>` when needed. For production
Android changes it runs the debug unit/Robolectric suite once plus release lint;
release unit tests duplicate shared coverage and are not part of the gate.
A failing check must be fixed and committed before rerunning; do not stack more
pushes on red CI.

Require all applicable hosted `Required CI / gate` checks before merge. If an
individual job fails, inspect that job's existing diagnostics and begin the fix
immediately instead of waiting for aggregate `gate`; remaining coverage continues normally.

## Signed release packaging

For release-build changes or packaging-failure diagnosis, exercise the same
bounded signed build that Required CI and publication use:

```sh
nix develop -c bash ./tools/build-signed-release.sh --ci-key
```

The explicit CI mode creates and removes a disposable keystore and refuses an
existing signing environment. Its APK is test-signed, not for distribution.
Without `--ci-key`, all four production signing variables are required. The helper
builds only the release variant, verifies its native contents and cryptographic
signature, and rejects forbidden signing-block metadata. It is not a routine UI
handoff prerequisite or an invitation to run the full pre-push gate.

The hosted Android tier has a 45-minute wall-clock budget for sequential cold
release packaging, lint, debug/E2E artifact builds, and cleanup; all checks remain
required.

Keep the existing debug/E2E artifact coverage in a separate invocation:

```sh
nix develop -c bash ./gradlew --no-daemon --no-parallel --max-workers=2 \
  :app:verifyAiNativeArtifacts --stacktrace
```

## Deterministic generated tests

Generated tests default locally to checked-in regressions plus eight generated
cases per target. Required CI explicitly selects the PR workload and replaces the
seed with the candidate commit; `.github/workflows/fuzz.yml` selects the larger
nightly profile.

The nightly workflow runs one fresh-seed shard for each module. The IRC shard
covers 200,000 parser cases and 75,000 mapper cases. The app shard covers 75,000
presentation cases, 1,500 canonical-timeline cases with 128 operations each,
and 500 EventProcessor cases. Job summaries report effective counts, index
ranges, and any manual overrides.

- `MOTD_FUZZ_SEED=<text>` selects an exact seed.
- `MOTD_FUZZ_CASE=<index>` replays one independently seeded case.
- `MOTD_FUZZ_PROFILE=pr|nightly` selects a hosted workload; unset uses the local workload.
- `MOTD_FUZZ_CASES=<count>` and `MOTD_FUZZ_STEPS=<count>` override campaign size.
  Only positive values apply (`0` falls back to the selected profile).
- `MOTD_FUZZ_SHARD=<zero-based index>` offsets generated case indices by one
  configured case-count, allowing parallel jobs to cover disjoint cases under
  the same reproducible seed. Exact `MOTD_FUZZ_CASE` replay ignores the shard.

Failures print an exact Nix/Gradle replay command and write the generated
operation trace below the module's `build/fuzz-failures/` directory. Minimize a
real failure into a named JUnit regression and retain its target, generator
version, seed, case, and fixture in that module's
`src/test/resources/fuzz/regressions.tsv` file.

## Device and E2E selection

- Only when cutting a release, focused actual-emulator visual verification is
  required alongside the nearest tests: inspect fresh current-surface PNGs,
  exercise changed UI interactions, and capture/inspect a short MP4 for
  interaction/navigation transitions. Check the actual rendered result, not
  merely that capture files exist. Reuse the persistent owned emulator and app
  data via the warm loop in [`../test/e2e/README.md`](../test/e2e/README.md);
  keep captures private and outside tracked screenshots. Follow
  [`releases.md`](releases.md) preflight and report concrete blockers and
  unverified behavior.
- Routine feature/fix work uses the quick ADB screencapture check above when a
  suitable device is available. Full local `fast`/`full` E2E suites remain
  non-routine; release visual checks do not run those suites or replace hosted CI.
- `.github/workflows/ci.yml` owns the complete required gate. Its `headless` job runs exactly
  four isolated `@FastHeadlessE2e` methods on API34 Pixel 6 AOSP, while the parallel
  component tier runs fixture-free Compose/UI tests through `:app:testDebugUnitTest` under
  Robolectric. Documentation-only
  changes run the path classifier and stable gate without booting Android jobs.
  Push the candidate commit and require the complete CI gate to pass before
  tagging a release. An `action_required` external-PR run is not evidence:
  approve it and wait for the exact candidate or integration SHA to pass before
  merging.
- Hardware- or OS-integration evidence may require physical-device validation
  beyond the quick ADB check; obtain explicit maintainer authorization first.
- Reproduce the focused CI suite with `./test/e2e/headless.sh fast` only for
  explicitly requested E2E diagnosis, not routine task validation.
- Fixture-free Compose/component tests live in `app/src/testDebug` and run with
  `:app:testDebugUnitTest`; only real-stack journeys and their support remain in `androidTest`.
- `test/e2e/fast-suite.sh` is the canonical fast-suite launcher and fixture
  argument source for local direct instrumentation and connected CI. Do not duplicate its
  annotation or fixture arguments in workflow YAML.
- Use `test/e2e/runbook.sh` for multi-screen interaction and crash sweeps. The
  local headless `full` command runs A-H/J/V/R before teardown phase I on the isolated emulator;
  the exhaustive hosted runbook is manual-only, while scheduled proxy and ZNC probes stay enabled.
- Use `:app:assembleE2e` only for x86_64 emulator testing. It deliberately
  excludes the arm64-only embedded libbox core and is not representative of
  obfuscation support.

For focused release visual checks or explicitly debugging CI E2E, follow
[`../test/e2e/README.md`](../test/e2e/README.md) for setup and owned-session teardown.
Never point the destructive E2E reset flow at the release application id.

### Surface-effects graphics coverage

`SettingsHomeAndPrimitivesUiTest` owns fixture-free native-graphics regressions:
`admissionGateIsCapturedBeforeTranslucentConsumersCanReplayMessages` checks the
root capture's admission gate; `productionFabPreservesShapeAndContentAtZeroFullAndMasterOffOpacity`
checks FAB opacity branches, shape, foreground color, and clicks.
`productionFabUsesRawPageWithoutCapturingItselfAndMasterRestoresPixels` and
`rootOverlayFabBlursBothPanesWithoutCapturingItself` retain raw-source/root-overlay
coverage. `nativeBackdrop_composerCompletion_parentReplay_motion_and_sharp_rows`
checks the real nick, command and emoji completion popup against wallpaper and
timeline sources, including movement, blur, clipping, sharp text and row clicks.
The `nativeBackdrop_dropdown_crossWindow_static_motion_and_sharp_foreground`,
`nativeBackdrop_modalSheet_crossWindow_static_and_native_motion`, and
`nativeBackdrop_menuInsideSheet_parentFallback_and_content_updates` regressions
retain popup/sheet alignment, sharp foreground, and parent-source fallback.

Robolectric native captures do not prove HWUI shadow compositing, autonomous
producer/consumer window-frame delivery, or physical-display presentation.
Use focused on-device or release-emulator checks for those hardware-specific
behaviors; ordinary fixture-free instrumentation tests are not part of this tier.

