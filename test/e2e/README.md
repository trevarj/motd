# MOTD end-to-end device harness

Host-driven UI acceptance run for MOTD against a **physical Android device**
(adb + uiautomator) and a **real soju test bouncer**. This harness mirrors
`plans/18-e2e-runbook.md` step-for-step; that doc is the source of truth for the
coverage matrix and selectors.

## What it is

- `lib.sh` — sourced helper library: adb wrapper, uiautomator dump + XML
  parsing, tap/input primitives, assertions, crash detection, step logging.
- `runbook.sh` — the ordered traversal, Phases A–I (§2 of the runbook).
- `fixtures/seed.sh` — best-effort seeding of `##motdtest` over raw TLS.
- `.env.example` — template for the required config; copy to `.env` (gitignored).

Selection is always by visible text, content-description, or Compose testTag
(surfaced as `resource-id`) — never raw coordinates. Coordinates come only from
a matched node's parsed bounds, so layout shifts and keyboard scroll do not
break selectors.

## Prerequisites

1. **Device.** A physical device connected and authorized (`adb devices` shows
   it as `device`, not `unauthorized`/`offline`). USB debugging on, RSA prompt
   accepted. If multiple devices are attached, set `SERIAL`.
2. **adb on PATH.** Run inside the project dev shell (`nix develop`), which
   provides the Android SDK platform-tools. On a bare Guix host without the dev
   shell, `lib.sh` falls back to `guix shell android-tools -- adb`.
3. **App built.** A debuggable APK at `MOTD_APK` for clean install, or the app
   already installed on the device. Hermetic CI uses `:app:assembleE2e`, whose
   APK keeps the `.debug` id while omitting arm64-only libbox JNI; the E2E flow
   exercises plain IRC through soju, never embedded obfuscation.
4. **Bouncer reachable.** The soju test bouncer (§0) reachable from the device
   for the run, and from the host for seeding.
5. **Config.** Copy `.env.example` to `test/e2e/.env` and fill in the bouncer
   creds. `.env` is gitignored; never commit secrets.

## Run

```sh
# 1. (optional) seed the channel with deterministic history:
nix develop -c ./test/e2e/fixtures/seed.sh

# 2. run the full acceptance sweep:
nix develop -c ./test/e2e/runbook.sh
```

Subset of phases:

```sh
E2E_PHASES="a b c" nix develop -c ./test/e2e/runbook.sh
```

Config is read from the environment or `test/e2e/.env`. Required:
`MOTD_SOJU_HOST`, `MOTD_SOJU_USER`, `MOTD_SOJU_PASS`. Optional: `MOTD_APK`,
`MOTD_NICK`, `MOTD_TEST_CHANNEL`, `MOTD_SECOND_NICK`, `SERIAL`, `E2E_PHASES`.

## What it asserts

Per phase (see §2 of the runbook for the exact steps):

- **A** — `pm clear`, launch into onboarding, fill soju server/auth, **trust the
  TOFU cert**, reach Ready, import `libera`, finish to the chat list.
- **B** — drawer contents, network subtitle, scope/unscope, SERVER buffer.
- **C** — join `##motdtest`, send a message, nick/command autocomplete, `/me`,
  reactions, reply, copy, scroll-to-bottom FAB, in-buffer search.
- **D** — channel info: topic dialog, mute/pin toggles, member nick sheet, add
  friend, leave dialog.
- **E** — channel browser: search, gated states.
- **F** — settings sweep: theme (AMOLED), message style, colored nicknames,
  palette, nick color overrides, friends/fools manage, join/part toggle, push
  availability, battery optimization, networks list, bouncer networks, about.
- **G** — Compact vs Comfortable render.
- **H** — image viewer (conditional on a seeded image message).
- **I** — teardown: delete-chat swipe (cancel), final crash sweep, `pm clear`.

After **every** step it runs `assert_no_crash` (logcat crash buffer + MotdConn
connection-failure grep) in addition to the step's own assertion.

## Reading results

- Each step logs `ok` / `FAIL` / `..` (note) lines with a running check counter.
- The final `E2E summary` prints total checks + failures; the script exits
  non-zero if any check failed.
- Diagnostic screenshots and the latest dump land in `test/e2e/artifacts/`
  (created on demand). Screenshots are used only for color-only oracles (AMOLED
  black background) and on-failure diagnostics, never as the primary oracle.

## Known limitations (from §6/§7 of the runbook)

- **Flaky real network.** Connect/registration/chathistory timing varies; the
  harness polls with generous `wait_for_text` timeouts and dump retries instead
  of fixed sleeps. Some steps still depend on live server behavior.
- **Cert-trust.** Every `pm clear` re-triggers the TOFU dialog (intentional
  coverage). The changed-cert dialog is hard to force and is not automated.
- **State encoded as color** (drawer status dots, badge severity) is unreadable
  via text dump until the §4 content-descriptions land; those assertions fall
  back to indirect signals (subtitle text, nick) or screencap.
- **Second identity.** DM/mention/typing and nick-autocomplete need
  `MOTD_SECOND_NICK` actively present in the channel (seed it). Without it those
  steps are skipped, not failed.
- **IME-shifted layout.** Field bounds move once the keyboard opens; every field
  step re-dumps after input. This is the most likely source of mis-taps.
- **Seeding is best-effort.** `fixtures/seed.sh` needs the bouncer reachable from
  the host and uses only `openssl s_client`.

## Stable selectors

The harness prefers the `testTag`s/content descriptions already exposed by the
Compose UI. Popup windows use their accessibility descriptions because the
Activity root's test-tag export does not propagate into a separate Compose
window. Visible text remains where a runtime database id makes a dynamic tag
unknowable to the host or where the visible copy is the intended oracle:

- `onboarding_forward_button`, `onboarding_choice_soju`,
  `onboarding_bouncer_switch_<id>` (Phase A).
- `drawer_network_row_<id>`, `drawer_status_dot_<id>` (Phase B; status is
  color-only today).
- `chat_message_<msgid>`, `chat_composer_field`, `message_more_reactions`
  (Phase C; long-press/react/reply/compact-vs-bubble targeting).
- `channelinfo_member_<nick>` (Phase D).
- `settings_switch_nick_colors`, `settings_switch_show_jpq` (Phase F; needed to
  read a switch's checked state directly).
- `network_settings_status` / `network_settings_conn_button`,
  `bouncer_row_<id>` / `bouncer_switch_<id>`, `search_result_<msgid>`.
