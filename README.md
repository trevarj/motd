# MOTD

A modern native Android IRCv3 client. Telegram-grade UI, IRC under the hood.

- Connects directly to networks (Libera.Chat) or through a [soju](https://soju.im) bouncer
- Bouncer features light up automatically: infinite scrollback (`draft/chathistory`),
  cross-device read state (`draft/read-marker`), multi-network over one account
  (`soju.im/bouncer-networks`), battery-friendly push (`soju.im/webpush` + UnifiedPush)
- Kotlin, Jetpack Compose, Material 3 with dynamic color

## Features

- **Telegram-like chat UI** — unified chat list with unread and mention badges, message
  bubbles with sender grouping, day separators, system-event pills, inline images, and OG
  link previews.
- **Composer** — nick autocomplete, reply threading (`+draft/reply`), reactions
  (`+draft/react`), typing indicators (`+typing`), and slash commands (`/msg`, `/query`,
  `/join`, `/me`, ...).
- **Full-text search** — FTS4 over message history, global or scoped to one buffer.
- **Infinite scrollback** — Paging 3 backed by a `draft/chathistory` RemoteMediator; plain
  networks fall back to local-only history.
- **Cross-device read state** — `draft/read-marker` (MARKREAD) sync, single mark-read entry
  point through `ConnectionManager`.
- **Multi-network over one account** — `soju.im/bouncer-networks` (BOUNCER BIND) with a root
  connection plus per-network child bindings.
- **Two delivery modes** — a persistent-socket foreground service, or battery-friendly push
  via UnifiedPush + `soju.im/webpush` with RFC 8291 (aes128gcm) decryption on-device.
- **Material You** — dynamic color on Android 12+, plus SYSTEM/LIGHT/DARK/AMOLED themes.
- **TLS** — okio over `SSLSocket`, SASL PLAIN/EXTERNAL, client certificates via the Android
  KeyChain, and IRCv3 STS policy pinning.

## Architecture

```
:app (Android)                          :irc (pure JVM)
  ui/* (Compose, MVVM)                    client/IrcClient (CAP/SASL, labels, batches)
    │ Flow / PagingData   │ actions       ext/ (chathistory, read-marker, bouncer, webpush)
    ▼                     ▼               proto/ (parser/serializer)  transport/ (okio+TLS)
  data/repo/* ◄─ data/db (Room+FTS) ◄─ data/sync/EventProcessor (sole Room writer)
  data/prefs (DataStore)   │ RemoteMediator      ▲ IrcEvent
  push/* (UnifiedPush,     │                     │
   RFC 8291 decrypt) ──────┴──► service/ConnectionManager ── per-network IrcClient ──►
```

- `data/sync/EventProcessor` is the only component that writes IRC-derived state to Room.
- UI reads only repositories (Room Flows / PagingData) and sends actions only through
  `ConnectionManager`.
- `:irc` has zero Android dependencies; TLS policy and client certs are injected via
  `TransportFactory`.

Design blueprints live in [`plans/`](plans/).

## Screenshots

_Placeholder — add device captures here once UI is polished on-device._

| Chat list | Chat screen | Search | Settings |
|-----------|-------------|--------|----------|
| _TODO_    | _TODO_      | _TODO_ | _TODO_   |

## Building

CI is the canonical build environment (see [`.github/workflows/`](.github/workflows/)).
Locally, the Nix flake provides JDK 17 and the Android SDK; direnv loads it via `.envrc`:

```sh
nix develop -c ./gradlew :irc:test           # protocol engine tests (pure JVM)
nix develop -c ./gradlew :app:testDebugUnitTest  # app unit tests (Robolectric)
nix develop -c ./gradlew :app:assembleDebug  # debug APK
nix develop -c ./gradlew build               # everything: tests + lint + both APKs
```

Artifacts land in `app/build/outputs/apk/`.

## Manual smoke checklist

Run against a real network (Libera.Chat) after installing the debug APK:

1. **Install** the debug APK (`adb install app/build/outputs/apk/debug/app-debug.apk`) and
   launch; grant the POST_NOTIFICATIONS prompt.
2. **Onboard** — the empty chat list routes to onboarding. Add a network:
   host `irc.libera.chat`, port `6697`, TLS on, a nick, SASL PLAIN with your NickServ
   credentials (or SASL NONE for an unregistered nick).
3. **Connect** — the connection banner reaches "Ready"; a status notification appears.
4. **Join** `#libera` (`/join #libera` or the join action). The buffer appears in the chat
   list and opens with member list populated.
5. **Send / receive** — post a message; confirm your echo dedups to a single row and other
   members' messages arrive live. Try `/me`, a reply, and a reaction.
6. **Search** — open search, query a word you sent; the hit navigates to its buffer.
7. **Theme switch** — Settings → toggle LIGHT/DARK/AMOLED and dynamic color; the UI
   recolors immediately.
8. **DM** — `/msg <nick> hi` navigates to a new QUERY buffer.

## Releasing

Releases are cut by pushing a `v*` tag; `release.yml` builds and uploads a signed APK.

1. Bump nothing in source — `versionName` comes from the tag, `versionCode` from the CI run
   number.
2. Tag and push:
   ```sh
   git tag -s v0.1.0 -m "v0.1.0"
   git push origin v0.1.0
   ```
3. CI decodes the keystore from `KEYSTORE_BASE64`, runs `:app:assembleRelease` with the
   signing env (`MOTD_KEYSTORE_PATH`, `MOTD_KEYSTORE_PASSWORD`, `MOTD_KEY_ALIAS`,
   `MOTD_KEY_PASSWORD`), renames the artifact to `motd-<tag>.apk`, and attaches it to a
   GitHub release with generated notes.

Required repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
`KEY_PASSWORD` (see [`plans/08-ci-release.md`](plans/08-ci-release.md) for the runbook).

To dry-run locally, build the release variant with the signing env set (or with the debug
signing config) — `nix develop -c ./gradlew :app:assembleRelease`.
