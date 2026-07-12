# 00 — MOTD overview

> **Historical design record.** This file describes the original implementation
> plan. Current code, tests, workflows, `ARCHITECTURE.md`, and `AGENTS.md` take
> precedence; see [`README.md`](README.md) in this directory.

**MOTD** is a native Android IRCv3 client with Telegram-grade UX. Package
`io.github.trevarj.motd`, repo `trevarj/motd`. One IRCv3 core connects directly to networks
(Libera.Chat) or through a soju bouncer; bouncer capabilities light up via CAP detection and
degrade gracefully on plain networks.

## Product pillars

1. **Telegram-like UI** — unified chat list with unread/mention badges, message bubbles with
   sender grouping, inline images and link previews, reactions, typing indicators, full-text
   search, Material You dynamic color.
2. **Bouncer-optimized sync** — infinite scrollback via `draft/chathistory`, cross-device read
   state via `draft/read-marker`, multi-network over one soju account via
   `soju.im/bouncer-networks`, battery-friendly push via `soju.im/webpush` + UnifiedPush.
3. **Works everywhere** — plain IRCv3 networks get local-only history + a persistent-socket
   foreground service; no feature hard-requires a bouncer except where protocol makes it
   impossible.

## Architecture at a glance

```
┌────────────────────────── :app (Android) ──────────────────────────┐
│  ui/* (Compose, MVVM)                                              │
│    │ reads Flow/PagingData          │ actions                      │
│    ▼                                ▼                              │
│  data/repo/* ◄──── data/db (Room+FTS) ◄── data/sync/EventProcessor │
│                          ▲                        ▲ (sole writer)  │
│  data/prefs (DataStore)  │ RemoteMediator         │ IrcEvent       │
│  push/* (UnifiedPush,    │                        │                │
│   RFC 8291 decrypt) ─────┴──► service/ConnectionManager ───────────┤
└──────────────────────────────────────│─────────────────────────────┘
                                       │ per-network IrcClient
┌────────────────────────── :irc (pure JVM) ─────────────────────────┐
│  client/IrcClient (CAP/SASL state machine, labels, watchdog)       │
│  ext/ (batch, chathistory, read-marker, bouncer-networks, webpush) │
│  proto/ (parser/serializer)   transport/ (okio over SSLSocket)     │
└────────────────────────────────────────────────────────────────────┘
```

Data-flow rules:
- `data/sync/EventProcessor` is the **only** component that writes IRC-derived state to Room.
- UI reads **only** repositories (Room Flows / PagingData) and sends actions **only** through
  `ConnectionManager`.
- `:irc` has zero Android dependencies; TLS policy/client certs are injected via
  `TransportFactory`.

## Plan documents

| Doc | Contents |
|---|---|
| `01-versions-gradle.md` | Pinned version matrix, Gradle files verbatim, flake.nix |
| `02-irc-engine.md` | Parser, transport, registration state machine, labels, batches, tests |
| `03-capabilities.md` | IRCv3 cap tiers, degradation rules, exact soju command syntax |
| `04-data-layer.md` | Room schema semantics, RemoteMediator algorithm, dedup, FTS |
| `05-service-connectivity.md` | Foreground service, ConnectionManager lifecycle, notifications |
| `06-push.md` | UnifiedPush + soju webpush, RFC 8291 decrypt, mode switching |
| `07-ui.md` | Screen specs, composable trees, ViewModel contracts, theme |
| `08-ci-release.md` | GitHub Actions workflows, signing, versioning, secrets runbook |
| `09-work-packages.md` | Work packages: ownership, dependencies, acceptance criteria |
| `10-contracts.md` | **Frozen** cross-package Kotlin signatures — read before coding |

## Work-package dependency graph

```
WP1 skeleton+contracts
 ├──► WP2 :irc proto/transport ──► WP3 :irc client/extensions ─┐
 ├──► WP4 Room data layer ─────────────────────────────────────┼──► WP5 service+sync ─┐
 ├──► WP6 theme/nav/chat-list ──► WP7 chat screen+composer     │                      ├──► WP10 integration
 │                            └─► WP8 onboarding/settings/search                      │
 └──► WP9 push (UnifiedPush + webpush crypto) ─────────────────────────────────────────┘
```

Max parallelism 4. Ownership is directory-exclusive (see `09-work-packages.md`); the only
shared artifacts are WP1's contract files, which nobody edits.

## Ground rules (duplicated in repo AGENTS.md — absolute)

- Pinned versions in `libs.versions.toml` are law; never change or add dependencies.
- Contracts in `10-contracts.md` are frozen.
- No kapt (KSP only). No minification in v1. No OkHttp.
- `:irc` stays pure JVM.
- Local dev env = Nix flake (`nix develop`); CI is the canonical build environment.
