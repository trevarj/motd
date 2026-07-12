# 08 — CI & release (WP1 authors workflows; runbook for the human)

CI is the canonical build environment: `ubuntu-latest` with its preinstalled Android SDK.
No Nix in CI (the flake is for local dev only; the Nix-caching option was evaluated and
rejected — see plans/17). AGP accepts licenses non-interactively on hosted runners; no extra
license step needed.

The CI workflow is split into two parallel jobs (`irc`, `app`), has per-job `timeout-minutes`
so a hang fails fast, and runs lint isolated (`--no-daemon -Dorg.gradle.workers.max=1`) with a
bounded retry to dodge the flaky `ModifierDeclarationDetector` classloader race. Rationale and
the Nix verdict are in plans/17. The current workflow is `.github/workflows/ci.yml`:

```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  irc:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - name: irc unit tests
        run: ./gradlew :irc:test --stacktrace

  app:
    runs-on: ubuntu-latest
    timeout-minutes: 25
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: gradle/actions/setup-gradle@v4
      - name: App unit tests
        run: ./gradlew :app:testDebugUnitTest --stacktrace
      - name: Android lint (warnings as errors)
        run: |
          for attempt in 1 2; do
            echo "::group::lint attempt $attempt"
            ./gradlew :app:lintDebug --stacktrace --no-daemon \
              -Dorg.gradle.workers.max=1 && { echo "::endgroup::"; exit 0; }
            echo "::endgroup::"
            echo "lint attempt $attempt failed; retrying" >&2
          done
          exit 1
      - name: Assemble debug APK
        run: ./gradlew :app:assembleDebug --stacktrace
      - uses: actions/upload-artifact@v4
        with:
          name: motd-debug
          path: app/build/outputs/apk/debug/app-debug.apk
```

## `.github/workflows/release.yml`

Tagged releases first call `.github/workflows/smoke.yml`. That focused gate starts
the hermetic Docker soju/ergo stack and runs the Kotlin onboarding/connectivity
suite on a Gradle Managed Device. Only after it passes does `release.yml` build,
sign, and publish the APK, licensed libbox source, IBM Plex OFL, and compliance
assets.

The exhaustive A-I device journey in `.github/workflows/e2e.yml` runs nightly or
by manual dispatch. It is diagnostic coverage and intentionally does not gate a
release. Fast protocol, repository, and ViewModel tests remain in `ci.yml` and in
the release build's Gradle `build` task.

Note: `MOTD_VERSION_NAME` receives the raw tag (`v0.1.0`); `app/build.gradle.kts` uses it
as-is — acceptable for v1 (versionName "v0.1.0"). Strip the `v` there later if it bothers
anyone.

## Versioning scheme

- Tags: `v<semver>` (`v0.1.0` first release). `versionCode` = GitHub run number
  (monotonic per workflow). Local builds: `0.0.0-dev` / 1.

## One-time signing setup (human runbook — requires the repo to exist on GitHub)

```sh
# 1. generate a keystore (25+ year validity), store it OUTSIDE the repo
keytool -genkeypair -v -keystore ~/secrets/motd-release.jks -alias motd \
  -keyalg RSA -keysize 4096 -validity 10000

# 2. upload secrets
gh secret set KEYSTORE_BASE64 --repo trevarj/motd < <(base64 -w0 ~/secrets/motd-release.jks)
gh secret set KEYSTORE_PASSWORD --repo trevarj/motd
gh secret set KEY_ALIAS --repo trevarj/motd --body motd
gh secret set KEY_PASSWORD --repo trevarj/motd

# 3. release
git tag v0.1.0 && git push origin v0.1.0
```

Losing the keystore means users must uninstall/reinstall (signature mismatch) — back it up.
