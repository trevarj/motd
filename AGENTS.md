# AGENTS.md — MOTD ground rules

Implementation agents: read `plans/00-overview.md` first, then the plan doc(s) for your work
package (`plans/09-work-packages.md`). These rules are absolute:

1. **Pinned versions are law.** Use `gradle/libs.versions.toml` exactly as specified in
   `plans/01-versions-gradle.md`. Never bump, downgrade, or add a dependency outside it.
2. **Contracts are frozen.** Cross-package types/interfaces are defined verbatim in
   `plans/10-contracts.md`. Implement against them; never edit them. If a contract seems wrong,
   stop and report instead of changing it.
3. **No kapt** — Hilt and Room via KSP only. **No minification** — `isMinifyEnabled = false`.
   **No OkHttp** — okio over `Socket`/`SSLSocket` for IRC, `HttpURLConnection` for previews.
4. **Directory ownership.** Each work package owns an exclusive set of directories
   (`plans/09-work-packages.md`). Never create or edit files outside your ownership.
5. `:irc` is pure JVM — zero Android imports. All protocol logic must be unit-testable with a
   fake transport.
6. Kotlin style: idiomatic coroutines/Flow, sealed hierarchies for events/state, brief intent
   comments. Compose: stateless composables + ViewModel state holders.
7. Verification before done: your WP's acceptance criteria checklist, plus
   `./gradlew build` compiles green.
8. **Local environment is the Nix flake** (host is Guix, but dev tooling comes from Nix):
   `nix develop -c ./gradlew ...`. Never suggest guix install/apt/brew or a global SDK.

## Local bouncer stack (play / manual + e2e)

Native ergo + soju (no Docker) for exercising the app against a real bouncer on a
USB device or emulator. Binaries come from nixpkgs on demand; the app reaches soju
over `adb reverse` at `127.0.0.1:6697`.

```sh
./test/e2e/local-stack.sh up      # fresh stack + provision + seed + adb reverse tcp:6697
./test/e2e/local-stack.sh status  # pids + soju upstream status
./test/e2e/local-stack.sh seed    # re-post the seed history into ##motdtest
./test/e2e/local-stack.sh down    # stop ergo/soju, drop the reverse
```

`up` registers upstream account `motd`, creates `##motdtest`, starts soju with a
self-signed TLS cert, provisions soju user **`motd` / `motdtest`** and bouncer
network **`libera`** → ergo (auto-joins `##motdtest`), then seeds deterministic
history. Onboard the **motd debug** app via "I have a soju bouncer":

| Field | Value |
| --- | --- |
| Host | `127.0.0.1` (via adb reverse) |
| Port | `6697` — TLS on; **Trust** the self-signed cert prompt |
| Username / Password | `motd` / `motdtest` |

Import network `libera`, open `##motdtest` (seeded backfill/search).

Notes:
- State + logs live under `/tmp/motd-stack` (override with `MOTD_STACK_DIR`). All
  creds are ephemeral local test creds, never secrets.
- `up` fails loudly if `6667`/`6697` are already held (a stale stack). Kill it or
  run `down` first — don't let a foreign daemon hijack provisioning.
- Kill stray daemons by **PID** (`ss -ltnp | grep :669`), never
  `pkill -f "ergo run"` (the pattern matches the calling shell — it self-kills).
- Docker + emulator (`10.0.2.2`) variant: `test/e2e/hermetic/`. Full UI runbook:
  `cp test/e2e/.env.ci test/e2e/.env && nix develop -c ./test/e2e/runbook.sh`
  (for a physical device set `MOTD_SOJU_HOST=127.0.0.1` to use the reverse).
