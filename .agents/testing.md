# Testing and verification

Run all Gradle commands through the repository Nix shell (`nix develop -c ...`,
or enter `nix develop` once and invoke Gradle there). While editing, run the
nearest relevant test method; before handoff/push, run its class once.
Cross-module changes run the nearest affected tests in each module. Changed
behavior needs a new or updated regression unless an existing named test already
exercises that branch. Do not automatically append an unfiltered suite, Android
lint, APK assembly, or a full pre-push gate.

Before handoff, run each changed module's existing `:app:ktlintCheck`,
`:irc:ktlintCheck`, or `:ai-whisper:ktlintCheck` once through Nix. Root
Gradle/style configuration changes still require root `ktlintCheck`. Use the
corresponding `ktlintFormat` task to apply enforced style.

## Command matrix

| Changed surface | Required local checks |
| --- | --- |
| Documentation only | `git diff --check`; verify links, commands, and referenced paths |
| Shell harness/config | `bash -n test/e2e/*.sh test/e2e/fixtures/*.sh test/e2e/hermetic/*/*.sh` plus the relevant dry run |
| IRC parser/client/transport | Nearest `:irc:test` method while editing; its class once before handoff/push |
| Android repositories, services, preferences, or ViewModels | Nearest `:app` test method while editing; its class once before handoff/push |
| Room entities/schema/migrations | Nearest database regression method, then its class before handoff/push; review and commit generated `app/schemas` changes |
| Compose Kotlin | Nearest Robolectric behavior method in `testDebug`, then its class before handoff/push; its Gradle task already compiles the app |
| Resources/manifest/packaging | Nearest test when behavior changed, then `:app:assembleDebug` |
| Instrumentation source or affected journey | `:app:compileE2eAndroidTestKotlin`; no routine local emulator/device run |
| Ordinary app user journey | Nearest unit/integration method, then its class before handoff/push; assemble only when an APK is needed |
| Cross-module or release-sensitive work | Nearest affected methods in each module while editing; their classes once before handoff/push |

Target one method while editing, then use the class filter once before
handoff/push:

```sh
nix develop -c ./gradlew :app:testDebugUnitTest \
  --tests '<fully-qualified-class.method>' --stacktrace
```

Use `:irc:test` instead for protocol tests. These filters narrow execution, not
compilation of the test source set and its dependencies. Run `:app:assembleDebug`
only when resources, manifest, packaging, or an actual APK require it. No routine
local emulator or physical-device runs.

## Behavior-test arrangements

Arrange the state needed for the behavior under test. Select typed navigation
targets rather than translated display labels. Give generic editor operations
explicit input trees; rely on stock defaults only in default/reset tests. Reuse
existing local helpers rather than adding a second generic fixture framework.
Keep security, persistence, migration, accessibility, and legitimate boundary
assertions; remove coupling to incidental wording, catalog size, or default-menu
layout outside tests of those contracts.

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
immediately instead of waiting for aggregate `gate`; remaining coverage continues
normally.

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

- Do not run the headless emulator suite during routine local development. It
  materially slows the maintainer's workstation. Local verification stops at
  nearest unit/integration tests and assembly only when the matrix requires it.
- `.github/workflows/ci.yml` owns the complete required gate. Its `headless` job runs exactly
  four isolated `@FastHeadlessE2e` methods on API34 Pixel 6 AOSP, while the parallel
  component tier runs fixture-free Compose/UI tests through `:app:testDebugUnitTest` under
  Robolectric. Documentation-only
  changes run the path classifier and stable gate without booting Android jobs.
  Push the candidate commit and require the complete CI gate to pass before
  tagging a release. An `action_required` external-PR run is not evidence:
  approve it and wait for the exact candidate or integration SHA to pass before
  merging.
- Use a physical device for hardware- or OS-integration evidence: input latency,
  scrolling performance, wallpaper/rendering quality, background lifecycle,
  notifications and UnifiedPush, system pickers, certificates outside the
  fixture trust flow, and a real release installation. Only do this when the
  maintainer explicitly asks for device validation.
- Only when lower-level checks cannot validate behavior, reproduce the focused
  CI suite with `./test/e2e/headless.sh fast`.
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

When explicitly debugging CI E2E, follow
[`../test/e2e/README.md`](../test/e2e/README.md) for setup and teardown.
Never point the destructive E2E reset flow at the release application id.
