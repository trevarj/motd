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
| Ordinary app user journey | Relevant unit/build checks, then `./test/e2e/headless.sh fast` |
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

## Device and E2E selection

- Use `./test/e2e/headless.sh fast` as the default product-level check for
  onboarding, navigation, composer, channel, settings, TLS, SASL, and bouncer
  behavior. It boots a persistent isolated API 34 AOSP emulator, runs four
  process-isolated Compose/JUnit journeys, and leaves the emulator and local
  stack alive for quick reruns. Use `status`, `down`, or `reset` to manage it.
- Use a physical device for hardware- or OS-integration evidence: input latency,
  scrolling performance, wallpaper/rendering quality, background lifecycle,
  notifications and UnifiedPush, system pickers, certificates outside the
  fixture trust flow, and a real release installation. A green headless run is
  strong functional evidence, but does not replace those checks.
- `.github/workflows/ci.yml` runs the fast journeys as a required gate. The
  manually runnable `.github/workflows/smoke.yml` exercises the same suite with
  Gradle's managed-device path.
- Use `test/e2e/runbook.sh` for multi-screen interaction and crash sweeps. The
  local headless `full` command runs its A-I phases on the isolated emulator;
  the hermetic Docker stack is used by the scheduled/manual CI workflow.
- Use `:app:assembleFossE2e` only for x86_64 emulator testing. It deliberately
  excludes the arm64-only embedded libbox core and is not representative of
  obfuscation support.

Follow [`../test/e2e/README.md`](../test/e2e/README.md) for setup and teardown.
Never point the destructive E2E reset flow at the release application id.
