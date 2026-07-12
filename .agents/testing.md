# Testing and verification

Run all Gradle commands through the repository Nix shell. Start with the
narrowest useful check and expand when a change crosses boundaries.

## Command matrix

| Changed surface | Required checks |
| --- | --- |
| Documentation only | `git diff --check`; verify links, commands, and referenced paths |
| Shell harness/config | `bash -n test/e2e/*.sh test/e2e/fixtures/*.sh test/e2e/hermetic/*/*.sh` plus the relevant dry run |
| IRC parser/client/transport | `nix develop -c ./gradlew :irc:test --stacktrace` |
| Android repositories, services, preferences, or ViewModels | `nix develop -c ./gradlew :app:testFossDebugUnitTest :app:testGoogleDebugUnitTest --stacktrace` |
| Firebase relay | `nix develop -c npm ci --prefix firebase/functions --ignore-scripts`, then `npm test` and `npm audit --omit=dev` with the same prefix |
| Compose/resources/manifest | App unit tests, both-flavor lint, and both debug assemblies |
| Cross-module or release-sensitive work | The full release-parity Gradle command below |

Both-flavor lint and debug assembly:

```sh
nix develop -c ./gradlew \
  :app:lintFossDebug :app:lintGoogleDebug \
  :app:assembleFossDebug :app:assembleGoogleDebug \
  --stacktrace --no-daemon --max-workers=1
```

Full release-parity Gradle verification:

```sh
nix develop -c ./gradlew build \
  :app:lintFossDebug :app:lintGoogleDebug \
  --stacktrace --no-daemon --max-workers=1
```

Lint warnings are errors. Keep the single-worker/no-daemon form for final lint
and release checks because it avoids a known Android lint worker race.

## Device and E2E selection

- Use a physical device for input latency, scrolling, wallpaper/rendering,
  lifecycle, notification, certificate, picker, and real installation checks.
- Use the focused managed-device smoke for onboarding, TLS trust, SASL, and
  bouncer discovery. It is manually runnable through `.github/workflows/smoke.yml`.
- Use `test/e2e/runbook.sh` for multi-screen interaction and crash sweeps. The
  local native stack is the fastest physical-device setup; the hermetic Docker
  stack is used by the scheduled/manual emulator workflow.
- Use `:app:assembleFossE2e` only for x86_64 emulator testing. It deliberately
  excludes the arm64-only embedded libbox core and is not representative of
  obfuscation support.

Follow [`../test/e2e/README.md`](../test/e2e/README.md) for setup and teardown.
Never point the destructive E2E reset flow at the release application id.
