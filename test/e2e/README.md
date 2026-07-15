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
| Fast headless emulator | Default local feature validation; onboarding, chat, channel, settings, and bouncer journeys | `./test/e2e/headless.sh fast` |
| Full headless emulator | Local A-I shell-runbook sweep | `./test/e2e/headless.sh full` |
| Public screenshot showcase | Deterministic chat list, conversation, and attachment-sheet captures | `./test/e2e/headless.sh showcase` |
| Native local stack + USB device | Manual feature work, physical-device checks, quick iteration | `./test/e2e/local-stack.sh` |
| Native ZNC + Ergo stack | ZNC playback, reconnect, SASL, and capability degradation | `./test/e2e/znc-stack.sh` |
| Host-driven A–I runbook | Broad UI interaction and crash sweep on a device or emulator | `./test/e2e/runbook.sh` |
| Managed-device fast suite | Alternate/manual CI execution of the fast journeys | `.github/workflows/smoke.yml` |
| Hermetic emulator run | Scheduled/manual exhaustive CI diagnostics | `.github/workflows/e2e.yml` |

The fast headless suite is a required pull-request and main-branch CI gate. The
exhaustive A-I workflow remains scheduled/manual diagnostics. Release CI still
runs its own unit, lint, and FOSS release build checks.

## Prerequisites

- Enter the project environment with `nix develop`; it supplies JDK 17 and
  Android platform tools. Do not install or invoke a separate host SDK.
- The local headless commands require Linux KVM access. Their first run enters
  the opt-in `.#emulator` shell and fetches the pinned API 34 AOSP image; normal
  builds do not carry that large closure.
- For physical-device modes, connect and authorize a device. `adb devices` must
  show it as `device`; set `SERIAL` when more than one device is attached. The
  headless lifecycle wrapper creates and pins its own emulator serial.
- Build the appropriate APK:

  ```sh
  nix develop -c ./gradlew :app:assembleFossDebug
  ```

  The physical-device APK is
  `app/build/outputs/apk/foss/debug/app-foss-debug.apk`. The x86_64 hermetic
  emulator uses `app/build/outputs/apk/foss/e2e/app-foss-e2e.apk`, which omits
  the arm64-only libbox core.

## Fast local headless loop

For ordinary app feature work, run:

```sh
./test/e2e/headless.sh fast
```

The lifecycle wrapper owns a dedicated AVD, emulator serial, native soju/ergo
stack, adb reverse, and temporary state. Every adb command includes that serial,
so an attached phone is never installed to, cleared, reversed, or reconfigured.
Each journey gets a fresh instrumentation process and cleared debug-app data;
the launcher discovers every `@FastHeadlessE2e` class from the installed test
package instead of maintaining a class list. CI and managed-device runs use the
same fixture configuration and enforce isolation with Android Test Orchestrator.
The four journeys cover:

- onboarding, self-signed fixture trust, soju login, and network import;
- channel join, send, emoji and command completion, and message search;
- channel info, member actions, and leave cancellation; and
- settings, themes, chat presentation, and Soju control-center panels.

The production Activity, Room database, services, TLS transport, and bouncer
connection are used. The fixture replaces only the remote IRC network. This is
therefore strong evidence for functional Android behavior, including semantics,
navigation, persistence boundaries, and protocol integration. It is not proof
of physical input latency, GPU-specific rendering, Doze/background delivery,
UnifiedPush, system picker/provider behavior, OEM notification behavior, or
release signing/install upgrades; retain a physical-device check for those.

The emulator and stack remain alive after a passing run to make the next run
fast. Manage their lifecycle explicitly:

```sh
./test/e2e/headless.sh status
./test/e2e/headless.sh full
./test/e2e/headless.sh down
./test/e2e/headless.sh reset
```

`full` runs the existing A-I uiautomator sweep on the same isolated emulator.
`down` preserves the AVD; `reset` deletes only the wrapper's isolated AVD,
stack, and state directories. Failures save a JSON summary, screenshot, logcat,
instrumentation output, stack versions, and local bouncer logs under
`test/e2e/artifacts/headless/`.

## Public screenshot showcase

The showcase command provisions a separate local fixture with believable
`#guix`, `#debian`, `#emacs`, and `#rust` channels, fictional nicks, and seeded
messages. It drives the production Compose screens through the existing
headless emulator, selects the dark Modus Vivendi preset, comfortable bubbles,
and deterministic IRC sprites, then writes these tracked assets at the
repository root:

- `screenshots/chat-list.png` — the multi-channel chat list;
- `screenshots/chat.png` — a seeded `#guix` conversation; and
- `screenshots/file-uploader.png` — the in-app attachment source chooser.

```sh
nix develop -c ./test/e2e/headless.sh showcase
```

The optional `MOTD_SHOWCASE_SCREENSHOT_DIR` environment variable can point at
another output directory. The workflow never touches a physical device or a
release package; it uses only the wrapper's `.debug` APK and isolated emulator.

## Native local bouncer stack

The native stack runs ephemeral ergo and soju instances under
`/tmp/motd-stack`, publishes TLS soju on host port `6697`, and uses `adb reverse`
so a USB device reaches it at `127.0.0.1:6697`.
Missing fixture binaries cause the script to re-enter the lockfile-backed
`nix develop .#e2e-stack` shell; it never resolves an unpinned registry shell.

```sh
./test/e2e/local-stack.sh up
./test/e2e/local-stack.sh status
./test/e2e/local-stack.sh seed
./test/e2e/local-stack.sh history-check
./test/e2e/local-stack.sh control-check
./test/e2e/local-stack.sh read-marker-check
./test/e2e/local-stack.sh down
```

For member-sheet checks, keep the seeder joined long enough to drive the UI in
another terminal: `SEED_HOLD_SECONDS=180 ./test/e2e/local-stack.sh seed`. The
seed also sends a deterministic direct message to `motdadb`.

For the debug-only auto-follow trace, enable the log tag before launching the
chat and capture only its structured records:

```sh
adb shell setprop log.tag.MotdAutoFollow DEBUG
adb logcat -c
adb logcat -v epoch MotdAutoFollow:D '*:S'
```

The trace records row identity/classification, Paging state, follow decisions,
viewport settlement, and read-marker timing; it never records message text,
nicks, addresses, or credentials. It is disabled in release builds and remains
dormant in debug builds until the log tag is enabled. Reset the tag to
its normal threshold after capture:

```sh
adb shell setprop log.tag.MotdAutoFollow INFO
```

The native stack provides deterministic inputs for the baseline matrix:

```sh
./test/e2e/local-stack.sh burst       # 12 numbered PRIVMSGs, then QUIT
./test/e2e/local-stack.sh jpq         # JOIN/PART/JOIN/QUIT, no chat text
./test/e2e/local-stack.sh push TOKEN  # tagged highlight + DM for UnifiedPush checks
./test/e2e/local-stack.sh pause-soju  # delay socket processing without disconnecting
./test/e2e/local-stack.sh resume-soju
./test/e2e/local-stack.sh stop-soju   # force EOF; preserve config/database
./test/e2e/local-stack.sh start-soju  # restart and exercise client reconnect/catch-up
```

`control-check` connects through the public TLS listener as both the admin and
non-admin fixture users. It verifies help-based authorization, channels,
temporary network/user mutations, SASL, CertFP, broadcast notices, and debug
enable/disable cleanup without adding Python dependencies beyond the Nix shell.

`read-marker-check` connects two downstream clients to the same Soju network,
sends a timestamped message, and verifies that `draft/read-marker` broadcasts
between clients, never regresses after an older SET, and remains available after
a downstream reconnect. The live response is required inside MOTD’s bounded
five-second reconnect barrier: MOTD converges its durable local marker with the
server maximum before CHATHISTORY replay can populate unread-count queries.

`history-check` authenticates as the fixture bouncer user, requires
`##motdtest` in a `CHATHISTORY TARGETS` response bounded from
`1970-01-01T00:00:00.000Z`, then requires its seeded message from
`CHATHISTORY LATEST`. It makes the first-sync discovery path and its required
three-digit fractional timestamp visible without needing an Android device.

Always pair `pause-soju` with `resume-soju`; both target the exact PID recorded
by the fixture rather than matching processes by command line.

`up` wipes previous stack state, provisions accounts and the `libera` bouncer
network, seeds `##motdtest`, and installs the reverse. It fails if ports `6667`
or `6697` are already occupied. Stop the owner by its exact PID; never use a
broad `pkill -f` pattern for ergo or soju.

Onboard the debug app with **Bouncer → soju**:

| Field | Value |
| --- | --- |
| Host | `127.0.0.1` |
| Port / TLS | `6697`, TLS enabled |
| Username | `motd` |
| Password | `motdtest` |

Trust the local self-signed certificate, import `libera`, and open
`##motdtest`. These credentials are local test fixtures, not secrets.

For BouncerServ authorization checks, the same stack also provisions the
non-admin account `motduser` / `motdusertest`. Use a separate debug app profile
or clear only the debug package before switching accounts. The admin account
must expose `user` and `server` command families; the non-admin account must not.

The same script exposes `obfs-*` and `obfs-xray-*` commands for VLESS + REALITY
compatibility and negative-path validation. After `up`, use
`obfs-xray-up`, `obfs-xray-validate`, and `obfs-xray-history-check` to prove
the embedded-client-compatible sing-box → Xray path carries both the TLS IRC
connection and retained history. `obfs-xray-up` reverses the local VLESS
ingress and prints a local-only URI for an arm64 device's Embedded REALITY
setting; leave the bouncer destination as `127.0.0.1:6697`. Run the base stack
first and see [`../../docs/obfuscation.md`](../../docs/obfuscation.md) for the
product model.

## Native ZNC fixture

The sibling ZNC fixture reuses the deterministic local Ergo network, adds a
self-signed ZNC 1.10.1 TLS listener on `6698`, and installs a separate adb
reverse. It owns the base stack it starts and tears it down by recorded PID.

```sh
./test/e2e/znc-stack.sh up
./test/e2e/znc-stack.sh status
./test/e2e/znc-stack.sh probe
./test/e2e/znc-stack.sh seed
./test/e2e/znc-stack.sh down
```

Connect the debug app with **Bouncer → ZNC** and trust the ephemeral certificate:

| Field | Value |
| --- | --- |
| Host | `127.0.0.1` |
| Port / TLS | `6698`, TLS enabled |
| Nickname | `motdadb` |
| Bouncer username | `motd` |
| ZNC network | `libera` |
| Bouncer password | `motdtest` |

The app combines the separate username and network inputs into ZNC's
`motd/libera` SASL authcid. Runtime state and logs remain under
`/tmp/motd-znc-stack` (override with `MOTD_ZNC_STACK_DIR`). The focused E2E
runner can exercise this branch with instrumentation arguments
`bouncerKind=znc`, `sojuPort=6698`, and `zncNetwork=libera`.

`probe` is a dependency-free socket test covering CAP negotiation, SASL PLAIN,
two attached clients, channel/query routing, self echo, a fully detached gap,
and timestamped native playback. The pinned fixture currently advertises
`batch`, `echo-message`, `message-tags`, `sasl=PLAIN`, `server-time`, and ZNC
extensions, but not `draft/chathistory`. App recovery must therefore accept
ZNC's native playback and must not issue CHATHISTORY merely because the Ergo
upstream supports it.

## Avatar metadata fixture

The dependency-free plaintext IRC fixture advertises a publish-capable
`draft/metadata-2`, creates `#avatars`, and logs every client/server line. Use it
for physical subscription, SYNC, publish/remove, image, and fallback checks:

```sh
nix shell nixpkgs#python3 -c python3 \
  test/e2e/fixtures/avatar-metadata-server.py
adb reverse tcp:6670 tcp:6670
```

Add a custom IRC network in the debug app with host `127.0.0.1`, port `6670`,
TLS off, authentication None, and any valid identity. Confirm the plaintext
warning because the connection exists only over `adb reverse`. The fixture
publishes a remote HTTPS avatar containing `{size}` and writes its transcript
to `/tmp/motd-avatar-metadata.log` by default. Network settings should allow a
self HTTPS URL to be published and cleared. Turning off **Show shared user
avatars** should send `METADATA * UNSUB avatar`, clear the remote image, and
show the monogram fallback; restore it before finishing.

Delete the disposable network, remove the reverse, and stop the fixture with
Ctrl-C:

```sh
adb reverse --remove tcp:6670
```

## IRCv3 Ready fixture

The deterministic plaintext fixture on port `6671` is shaped like a capable
Solanum connection. It negotiates ratified `no-implicit-names`,
`userhost-in-names`, and `extended-monitor`; advertises `MONITOR` and `WHOX`;
forwards a two-client invite; and emits nested netsplit/netjoin batches inside
CHATHISTORY. The probe asserts that no implicit NAMES arrives, then checks the
explicit NAMES/WHOX snapshot, MONITOR online/offline/list numerics, invite
delivery and ordered batch traffic directly. The soju leg proves alias
selection and clean degradation: the pinned bouncer omits upstream
`userhost-in-names` and strips netsplit batch wrappers while preserving the
WHOX identity and individual JOIN/QUIT membership mutations.
`invite-check` separately holds a soju downstream client open while a direct
Ergo client invites the upstream app nick, proving the two-client bouncer path.

```sh
./test/e2e/local-stack.sh up
./test/e2e/local-stack.sh ready-up
./test/e2e/local-stack.sh ready-check
./test/e2e/local-stack.sh invite-check
```

For a physical-device check, add a custom plaintext network at
`127.0.0.1:6671` with no authentication, or import the `ready-fixture` network
from the local soju account. Join `#ready`; the server must not send a roster
until Channel info, completion, or moderation requests it. Send
`/msg solanum.ready.test READY-BATCHES` to insert the deterministic split/join
pills. The transcript is `/tmp/motd-stack/ircv3-ready.log`.

```sh
./test/e2e/local-stack.sh ready-down
./test/e2e/local-stack.sh down
```

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
| A | Clean install, onboarding, TLS trust, bouncer import | Required |
| B | Chat list, server drawer, connection state and scoping | Required |
| C | Join, send, history, autocomplete, actions, reactions, reply, search | Required |
| D | Channel info, topic, mute/pin, members, leave dialog | Diagnostic; live member state varies |
| E | Channel browser and LIST search | Diagnostic |
| F | Settings, themes, message presentation, delivery, networks, about | Diagnostic |
| G | Compact and comfortable rendering | Diagnostic |
| H | Inline image viewer | Conditional; skipped without a reachable seeded image |
| I | Delete-chat cancellation, final crash sweep, clean reset | Required |
| J | Soju control-center panels, admin discovery, safe console command | Required with the local admin fixture |
| K | ntfy discovery, soju WebPush ACK, background/cold/Doze delivery, exactly-once notifications | Conditional; skipped without F-Droid ntfy |
| S | Deterministic public screenshot showcase | Required when selected by `headless.sh showcase` |

Phase K is intentionally excluded from the default A–I sweep because it needs an installed
UnifiedPush distributor and network access to its HTTPS relay. With the native stack already up,
run a clean debug-only proof using:

```sh
SERIAL=<device> \
MOTD_APK=app/build/outputs/apk/foss/debug/app-foss-debug.apk \
MOTD_SOJU_HOST=127.0.0.1 MOTD_SOJU_USER=motd MOTD_SOJU_PASS=motdtest \
E2E_PHASES="a k" nix develop -c ./test/e2e/runbook.sh
```

The test uses public ntfy only for encrypted WebPush bodies. It never prints endpoint URLs or key
material, never touches `io.github.trevarj.motd`, and always exits forced-idle mode during cleanup.
The runbook also snapshots all three global Android animation scales before disabling animations
and restores the exact original values on every normal exit, failure, or interruption. If the
snapshot cannot be completed, it leaves the device's animation settings unchanged.

The hermetic default is A–C. The scheduled/manual workflow may widen this to
A–I. DM, mention, typing, member, and moderation checks need a live second
identity; missing optional fixture state is reported as a skip rather than a
false failure.

### Results and diagnostics

Each step logs `ok`, `FAIL`, `SKIP`, or a diagnostic note, followed by a final
check/failure/skip count. The command writes `summary.json` and exits non-zero
on failures. XML dumps, failure logcat, and diagnostic screenshots go
to `test/e2e/artifacts/`, which is ignored. The showcase phase is the explicit
exception: its three named PNGs are public outputs, while ordinary runbook
screenshots remain diagnostic or color-only checks. Accessibility/semantic
state is the preferred oracle for ordinary tests.

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
./test/e2e/hermetic-stack.sh up
cp test/e2e/.env.ci test/e2e/.env
nix develop -c ./gradlew :app:assembleFossE2e
nix develop -c ./test/e2e/runbook.sh
./test/e2e/hermetic-stack.sh down
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
