# Hermetic e2e — phase coverage status

Which `runbook.sh` phases pass against the self-contained bouncer stack
(`test/e2e/hermetic/`, ergo + soju) vs. which need more seeding. The stack targets
phases **A, B, C** green; **D–I** are best-effort.

The default CI phase set is `a b c` (see `.env.ci`). Widen it via the workflow
input `phases` or `E2E_PHASES`.

| Phase | Status | Notes |
| --- | --- | --- |
| **A** onboarding | Expected green | Onboards the soju path, handles the self-signed TOFU cert (`Trust`), reaches Ready, imports the bouncer network **`libera`** (soju advertises it by name), finishes to the chat list. |
| **B** chat list / drawer | Expected green | Drawer lists `libera`; subtitle shows the nick when Ready; scope/unscope and the SERVER buffer work. |
| **C** join + chat + history | Expected green | Joins `##motdtest` (soju already auto-joins it), sends a message and sees the echo, sees the **seeded backfill** (CHATHISTORY from soju's `message-store db`), and in-buffer search hits the seeded lines. Nick-autocomplete lists `motdadb2` because the seeder is a channel member. |
| **D** channel info | Best-effort | Topic/mute/pin toggles and the leave dialog should work. The member nick sheet + "add friend" depend on a member row being present; `motdadb2` is a saved member but is **offline** after seeding, so it may not appear in the live NAMES list. To make this deterministic, keep a second identity connected (see "Open items"). |
| **E** channel browser | Best-effort | LIST against ergo returns `##motdtest`; the runbook only asserts the search field + result-or-empty, so it should pass. Join-from-browser is intentionally skipped by the runbook. |
| **F** settings sweep | Best-effort | Mostly local UI; should pass. Push availability asserts the "install a distributor" hint (no UnifiedPush distributor on the emulator) — expected. |
| **G** render modes | Best-effort | Compact/Comfortable re-render of the seeded message; should pass. |
| **H** image viewer | Needs seeding | Requires a message whose URL the app renders as an inline image. The seed posts `https://example.com/cat.png`, but the emulator has **no outbound network** to fetch it, so the viewer can't load a real image. The runbook skips gracefully. To exercise it, seed a data: URL or serve an image from a stack container the emulator can reach. |
| **I** teardown | Expected green | Delete-chat swipe (cancels), final crash sweep, `pm clear`. |

## Open items to widen D–I coverage

- **Live second member** for typing indicators, DM/mention badges, and the
  member nick sheet (Phase D step 34–35, Phase C step 21/31). Add a long-lived
  `motdadb2` presence: a small container that connects to soju/ergo, joins
  `##motdtest`, and idles. The current seeder disconnects after posting.
- **Moderation surface** (Phase D kick/ban, op-gated actions) needs the app's
  nick (`motdadb`) to hold channel op. Grant `motdadb` founder/op on
  `##motdtest` (register the channel as `motdadb`, or `CS OP`), which requires
  the app account to have joined first — chicken-and-egg; easiest is a provision
  step that ops `motd`/`motdadb` after first connect.
- **Inline image** (Phase H) needs a fetchable image reachable from the emulator
  with no external network — serve one from a container on the compose network
  and reference it via the host-published port (or a `data:` URL if the app
  renders those).
- **DM/mention badges** need `motdadb2` to actually deliver a PRIVMSG to
  `motdadb` while the app is connected (the seed posts a channel mention, but a
  direct DM lands only if the app account is online at seed time).

## Validation performed

The bouncer stack was exercised **end-to-end against real ergo + soju binaries**
(ergo 2.17.0, soju 0.10.1 from nixpkgs) outside Docker, using the actual config
files and provisioning/seed scripts in this tree:

- **ergo config** boots cleanly on the real binary (listens plaintext `:6667`,
  no schema errors).
- **Account registration** (`provision.sh register`) registers `motd` +
  `motdadb2` and creates/registers `##motdtest` with a topic — confirmed in
  ergo's account/services logs.
- **soju provisioning** (`entrypoint.sh` logic): `user create -admin`,
  `user run motd network create … -name libera` with two `-connect-command`s
  (NickServ IDENTIFY + JOIN), all accepted. soju connects upstream, registers
  nick `motdadb`, logs into account `motd`, and joins `##motdtest`.
- **Seeding + CHATHISTORY** (the load-bearing assertion): `provision.sh seed`
  posts the 6 messages while soju is joined; a downstream SASL PLAIN client
  (authcid `motd/libera`) then issues `CHATHISTORY LATEST ##motdtest * 50` and
  **soju returns all 6 seeded messages** in a batch with `server-time` tags.
  This proves the Phase C history-backfill path works hermetically.

Bugs caught + fixed during this validation:
- soju boolean flags need the attached form (`-admin`, `-enabled=true`); the
  manpage's `-admin true` is rejected.
- `channel create` has no `-network` flag under `user run`; replaced with a
  `JOIN` `-connect-command` (channels are joined, not saved).
- Seeding must happen **after** soju is connected/joined (soju only logs live
  traffic) — hence the split `ergo-provision` (register, before soju) vs
  `ergo-seed` (post messages, after soju healthy) services.

Not run in the authoring sandbox:
- `docker compose config` / an actual `docker compose up` (Docker unavailable).
  The compose file parses as valid YAML and its anchor/alias + depends_on graph
  were checked programmatically; validate on a Docker host before first CI use.
  The ergo image is `v2.18.0` (config validated on 2.17.0 — same schema family).

## What still needs a real KVM runner

The emulator boot, APK install, and the full uiautomator-driven traversal can
only be exercised on a KVM-capable runner (GitHub `ubuntu-latest` provides KVM;
the authoring sandbox does not). The bouncer stack itself is validated above;
the app-side traversal is correct by construction but unverified until the
workflow runs.
