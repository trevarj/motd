# MOTD device and end-to-end testing

This is the canonical operator guide for MOTD’s Android device harness. The
scripts in this directory are the behavioral authority; historical design notes
under `plans/` are not required to run it.

The harness drives only `io.github.trevarj.motd.debug` through `adb` and
uiautomator. It refuses any package id without the `.debug` suffix because its
setup and teardown deliberately clear application data.

## Choose a test mode

| Mode | Best for | Entry point |
| --- | --- | --- |
| Native local stack + USB device | Manual feature work, physical-device checks, quick iteration | `./test/e2e/local-stack.sh` |
| Host-driven A–I runbook | Broad UI interaction and crash sweep on a device or emulator | `./test/e2e/runbook.sh` |
| Managed-device smoke | Onboarding, TLS trust, SASL, and soju discovery | `.github/workflows/smoke.yml` |
| Hermetic emulator run | Scheduled/manual exhaustive CI diagnostics | `.github/workflows/e2e.yml` |

The smoke and exhaustive E2E workflows are currently diagnostic and do not gate
tagged releases. Release CI still runs unit tests, lint, and both release builds.

## Prerequisites

- Enter the project environment with `nix develop`; it supplies JDK 17 and
  Android platform tools. Do not install or invoke a separate host SDK.
- Connect and authorize a device. `adb devices` must show it as `device`. Set
  `SERIAL` when more than one device/emulator is attached.
- Build the appropriate APK:

  ```sh
  nix develop -c ./gradlew :app:assembleFossDebug
  ```

  The physical-device APK is
  `app/build/outputs/apk/foss/debug/app-foss-debug.apk`. The x86_64 hermetic
  emulator uses `app/build/outputs/apk/foss/e2e/app-foss-e2e.apk`, which omits
  the arm64-only libbox core.

## Native local bouncer stack

The native stack runs ephemeral ergo and soju instances under
`/tmp/motd-stack`, publishes TLS soju on host port `6697`, and uses `adb reverse`
so a USB device reaches it at `127.0.0.1:6697`.

```sh
./test/e2e/local-stack.sh up
./test/e2e/local-stack.sh status
./test/e2e/local-stack.sh seed
./test/e2e/local-stack.sh down
```

`up` wipes previous stack state, provisions accounts and the `libera` bouncer
network, seeds `##motdtest`, and installs the reverse. It fails if ports `6667`
or `6697` are already occupied. Stop the owner by its exact PID; never use a
broad `pkill -f` pattern for ergo or soju.

Onboard the debug app with **I have a soju bouncer**:

| Field | Value |
| --- | --- |
| Host | `127.0.0.1` |
| Port / TLS | `6697`, TLS enabled |
| Username | `motd` |
| Password | `motdtest` |

Trust the local self-signed certificate, import `libera`, and open
`##motdtest`. These credentials are local test fixtures, not secrets.

The same script exposes `obfs-*` and `obfs-xray-*` commands for VLESS + REALITY
compatibility and negative-path validation. Run the base stack first and see
[`../../docs/obfuscation.md`](../../docs/obfuscation.md) for the product model.

## Run the host-driven UI sweep

Copy the local template and adjust only when needed:

```sh
cp test/e2e/.env.example test/e2e/.env
nix develop -c ./test/e2e/runbook.sh
```

`test/e2e/.env` is ignored and sourced automatically. Environment variables
override its values. Important options are:

- `MOTD_APK`, `MOTD_PKG` — APK and debug package under test.
- `MOTD_SOJU_HOST`, `MOTD_SOJU_PORT`, `MOTD_SOJU_USER`, `MOTD_SOJU_PASS` —
  bouncer connection.
- `MOTD_NICK`, `MOTD_TEST_CHANNEL`, `MOTD_SECOND_NICK` — deterministic fixture
  identities and channel.
- `SERIAL` — explicit device selection.
- `E2E_PHASES` — space-separated phase letters, for example:

  ```sh
  E2E_PHASES="a b c" nix develop -c ./test/e2e/runbook.sh
  ```

The harness selects nodes by exact visible text, content description, or stable
Compose test tag surfaced as a resource id. Coordinates are derived only from a
matched node’s bounds. After every step it checks the crash log and connection
failure log.

### Phase coverage

| Phase | Coverage | Hermetic status |
| --- | --- | --- |
| A | Clean install, onboarding, TLS trust, bouncer import | Expected green |
| B | Chat list, server drawer, connection state and scoping | Expected green |
| C | Join, send, history, autocomplete, actions, reactions, reply, search | Expected green |
| D | Channel info, topic, mute/pin, members, leave dialog | Best effort; live member state matters |
| E | Channel browser and LIST search | Best effort |
| F | Settings, themes, message presentation, delivery, networks, about | Best effort |
| G | Compact and comfortable rendering | Best effort |
| H | Inline image viewer | Conditional on a reachable seeded image |
| I | Delete-chat cancellation, final crash sweep, clean reset | Expected green |

The hermetic default is A–C. The scheduled/manual workflow may widen this to
A–I. DM, mention, typing, member, and moderation checks need a live second
identity; missing optional fixture state is reported as a skip rather than a
false failure.

### Results and diagnostics

Each step logs `ok`, `FAIL`, or a note, followed by a final check/failure count.
The command exits non-zero on failures. XML dumps and diagnostic screenshots go
to `test/e2e/artifacts/`, which is ignored. Screenshots are diagnostic or used
for color-only checks; accessibility/semantic state is the preferred oracle.

Common failure causes:

- `unauthorized`/`offline` device or an unset `SERIAL` with multiple devices;
- a stale process holding `6667` or `6697`;
- a notification or certificate dialog covering onboarding;
- IME movement between dump and tap;
- delayed IRC registration/history; or
- missing second-identity/image fixtures in optional phases.

Use `local-stack.sh status`, inspect the saved artifact and XML dump, and check
the exact failing step before increasing timeouts or changing selectors.

## Hermetic Docker and emulator path

The hermetic stack is ergo → soju with self-signed TLS and deterministic
CHATHISTORY, exposed to Android emulators at `10.0.2.2:6697`.

```sh
docker compose -f test/e2e/hermetic/docker-compose.yml up --build -d --wait
cp test/e2e/.env.ci test/e2e/.env
nix develop -c ./gradlew :app:assembleFossE2e
nix develop -c ./test/e2e/runbook.sh
docker compose -f test/e2e/hermetic/docker-compose.yml down -v
```

Running this manually requires an already-booted x86_64 emulator. CI supplies
the emulator and installs the E2E APK before invoking the same runbook. Stack
topology and intentional image/version pins are documented in
[`hermetic/README.md`](hermetic/README.md).

## Stable selector policy

Prefer a stable per-item test tag for repeatable rows and a content description
for icon-only or color-only state. Existing anchors include onboarding controls,
drawer network rows, message containers, the composer field, channel members,
settings switches, bouncer rows, and search results. If UI copy or hierarchy
changes, update the semantics and the runbook in the same change.
