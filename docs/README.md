# motd docs

Human-oriented runbooks for working in this repository. Each one lists the
exact `nix develop` commands for one activity.

## Human runbooks

- [`human-developing.md`](human-developing.md) — building, linting, and testing
  motd locally.
- [`human-releasing.md`](human-releasing.md) — cutting a signed release tag and
  the workflow that publishes it.
- [`human-fdroid-update.md`](human-fdroid-update.md) — how a release reaches
  F-Droid, and when the fdroiddata recipe needs a hand-written change.

## Packaging reference

- [`fdroid.md`](fdroid.md) — the merged fdroiddata recipe, native libbox source
  build, reproducible signing, and the FOSS boundary. The per-release update
  steps live in `human-fdroid-update.md`.

## Feature and setup docs

- [`cloak.md`](cloak.md) — CLoak bouncer connection guide.
- [`obfuscation.md`](obfuscation.md) — SOCKS5, Tor, and VLESS + REALITY
  transport behavior and validation.
- [`ntfy-push.md`](ntfy-push.md) — ntfy and UnifiedPush setup for Google-free
  push.
- [`xmpp-companion.md`](xmpp-companion.md) — build, install, pair, and configure
  the XMPP companion prototype.
- [`theme-sources.md`](theme-sources.md) — editor, terminal, and wallpaper
  palette sources.
- [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) — third-party
  licensing and libbox source provenance.

## Note on agent docs

[`../AGENTS.md`](../AGENTS.md) and [`../.agents/`](../.agents) are mandatory
policy and task guides for AI agents working in this repository. They are not
human runbooks; the files above are.