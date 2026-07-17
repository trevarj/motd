# Testing and verification

Run all Gradle commands through the repository Nix shell. Start with the
narrowest useful check and expand when a change crosses boundaries.

## Command matrix

| Changed surface | Required checks |
| --- | --- |
| Documentation only | `git diff --check`; verify links, commands, and referenced paths |
| Shell harness/config | `bash -n test/e2e/*.sh test/e2e/fixtures/*.sh test/e2e/hermetic/*/*.sh` plus the relevant dry run |
| IRC parser/client/transport | `nix develop -c ./gradlew :irc:test --stacktrace` |
| Android repositories, services, preferences, or ViewModels | `nix develop -c ./gradlew :app:testFossDebugUnitTest --stacktrace` |
| Firebase relay | `nix develop -c npm ci --prefix firebase/functions --ignore-scripts`, then `npm test` and `npm audit --omit=dev` with the same prefix |
| Compose/resources/manifest | App unit tests, FOSS lint, and the FOSS debug assembly |
| Ordinary app user journey | Relevant unit/integration tests plus FOSS lint/build; rely on required CI for E2E |
| Cross-module or release-sensitive work | The full release-parity Gradle command below |

FOSS lint and debug assembly:

```sh
nix develop -c ./gradlew \
  :app:lintFossDebug :app:assembleFossDebug \
  --stacktrace --no-daemon --max-workers=1
```

Full release-parity Gradle verification:

```sh
nix develop -c ./gradlew \
  :irc:build \
  :app:testFossDebugUnitTest :app:testFossReleaseUnitTest \
  :app:lintFossDebug :app:assembleFossRelease \
  --stacktrace --no-daemon --max-workers=1
```

The Google/FCM flavor is dormant. Do not run Google Gradle tasks or build a
Google APK unless the maintainer explicitly reactivates that distribution.

Lint warnings are errors. Keep the single-worker/no-daemon form for final lint
and release checks because it avoids a known Android lint worker race.

## Deterministic generated tests

The ordinary `:irc:test` and `:app:testFossDebugUnitTest` tasks include bounded,
seeded generated tests. Their defaults are stable; CI replaces the seed with the
pull-request commit and `.github/workflows/fuzz.yml` runs the larger nightly
profile.

The nightly workflow runs three disjoint case-index shards for each module. An
IRC shard covers 200,000 parser cases and 75,000 mapper cases. An app shard
covers 75,000 presentation cases, 1,500 canonical-timeline cases with 128
operations each, and 500 EventProcessor cases. Job summaries report the
effective counts, index ranges, and any manual overrides.

- `MOTD_FUZZ_SEED=<text>` selects an exact seed.
- `MOTD_FUZZ_CASE=<index>` replays one independently seeded case.
- `MOTD_FUZZ_PROFILE=pr|nightly` selects the bounded workload.
- `MOTD_FUZZ_CASES=<count>` and `MOTD_FUZZ_STEPS=<count>` override campaign size.
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
  the relevant unit/integration tests, lint, and builds in the matrix above.
- `.github/workflows/ci.yml` runs the fast headless journeys as a required gate.
  Push the candidate commit and require the complete CI gate to pass before
  tagging a release.
- Use a physical device for hardware- or OS-integration evidence: input latency,
  scrolling performance, wallpaper/rendering quality, background lifecycle,
  notifications and UnifiedPush, system pickers, certificates outside the
  fixture trust flow, and a real release installation. Only do this when the
  maintainer explicitly asks for device validation.
- The manually runnable `.github/workflows/smoke.yml` exercises the same suite
  with Gradle's managed-device path.
- `test/e2e/fast-suite.sh` is the canonical fast-suite launcher and fixture
  argument source for local direct instrumentation, connected CI, and the
  managed-device smoke workflow. Do not duplicate its annotation or fixture
  arguments in workflow YAML.
- Use `test/e2e/runbook.sh` for multi-screen interaction and crash sweeps. The
  local headless `full` command runs its A-I phases on the isolated emulator;
  the hermetic Docker stack is used by the scheduled/manual CI workflow.
- Use `:app:assembleFossE2e` only for x86_64 emulator testing. It deliberately
  excludes the arm64-only embedded libbox core and is not representative of
  obfuscation support.

When explicitly debugging CI E2E, follow
[`../test/e2e/README.md`](../test/e2e/README.md) for setup and teardown.
Never point the destructive E2E reset flow at the release application id.
