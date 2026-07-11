# plans/20 — Obfuscation v2: VLESS + REALITY via an embedded sing-box core

Status: design, approved direction. Builds on `plans/19` (threat model, technique
survey, transport-seam integration points — not repeated here). This doc pins the
concrete v2 implementation.

## Context

The user's ISP runs active DPI and already fingerprinted-and-killed the shipped
WSS-on-443 transport (`plans/19` §3.3: SNI/IP/JA3 still exposed on our own cert).
Two hard requirements drive the choice:

1. **Easy VPS setup, soju-grade.** A single static Go binary + one JSON config +
   a systemd unit, sitting next to soju.
2. **Minimal client, embedded in the app.** No companion app (no Orbot, no ntfy
   pattern). One pasted share-link to configure a network.

Decision: **VLESS + REALITY, carried by an embedded sing-box core.** REALITY is
the only tier that survives active DPI, because it borrows a *real* third-party
site's TLS handshake and certificate instead of presenting our own (exactly what
got fingerprinted). Using **sing-box for both ends** gives one binary type, one
config dialect, and an actively-maintained gomobile `.aar` (`libbox`). This
supersedes `plans/19` §3.5's AndroidLibXrayLite suggestion (same architecture,
better-maintained core). Hysteria2 is kept as a fallback the same core speaks
(simpler server, but QUIC/UDP — blockable; lead with TCP/443 REALITY).

Current tree state (verified): the WSS transport shipped (`WsLineTransport`,
`wsUrl`, okhttp). **No SOCKS5 / proxy substrate exists** — `plans/19` §4 option 1
is greenfield and is the load-bearing first phase here.

## Architecture

```
motd app
  └─ OkioLineTransport via Proxy(SOCKS5, 127.0.0.1:<localPort>)
       └─ embedded sing-box core (libbox) — VLESS+REALITY outbound
            [ISP DPI sees a real TLS 1.3 handshake to the handshake domain on :443]
VPS :443  sing-box inbound (vless+reality) ── decrypts ──▶ 127.0.0.1:6697 (soju)
soju (unchanged, loopback)
```

One TCP tunnel on `:443` indistinguishable from ordinary HTTPS to the handshake
domain. The core terminates in a **loopback SOCKS5** the app already knows how to
dial — so every phase reuses the Phase 1 substrate.

## Phase 1 — SOCKS5 substrate (client-only, independently useful)

The reusable core from `plans/19` §5–6. Ships value on its own (user-supplied
SOCKS5 / Tor `.onion`) and is the terminus every embedded transport plugs into.

- **`irc/.../transport/Transport.kt` — `OkioLineTransport`**: add ctor params
  `proxy: java.net.Proxy? = null`. In `connect()`:
  - `val raw = if (proxy != null) Socket(proxy) else Socket()`.
  - Dial `InetSocketAddress.createUnresolved(host, port)` when `proxy != null`
    (force **remote DNS** through SOCKS5 — Java resolves locally otherwise, a leak
    that also breaks `.onion`; see `plans/19` §3.4/§8). Unproxied path keeps the
    resolving `InetSocketAddress(host, port)`.
  - TLS layering is unchanged: `ctx.socketFactory.createSocket(raw, host, port,
    true)` already keys SNI + hostname verification on the **real** target through
    the tunnel (`plans/19` §8 cert-pinning note).
- **`TransportFactory.create(...)`**: thread the proxy. Extend the fun-interface
  signature to carry an optional proxy (add a param alongside `wsUrl`, or fold
  both into a small `TransportOpts`); the pure-JVM default ignores it.
- **`app/.../service/AppTransportFactory.kt`**: build
  `Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", port))`
  (or a user SOCKS5 host/port) and pass to `OkioLineTransport`. Pinning/STS/
  hostname logic untouched (still keys on real `host:port`).
- **`app/.../service/ConnectionManagerImpl.kt`**: thread per-network proxy config
  into `buildClient(row)`; add the proxy config to `fingerprint(row)` so a proxy
  change restarts the actor like any connection-affecting field.
- **DB — `NetworkEntity` (`data/db/Entities.kt`) + migration 2→3
  (`MotdDatabase.kt`)**: nullable columns `obfsMode` (`NONE` | `SOCKS5` | `TOR` |
  `EMBEDDED_REALITY`), `proxyHost`, `proxyPort`, and `obfsLink` (the
  pasted `vless://…`). Follow the existing `wsUrl` migration pattern
  (`ALTER TABLE networks ADD COLUMN …`). Redact any secret in `toString()`.
- **Settings UI — `NetworkSettingsScreen.kt` / `NetworkForm.kt`**: a collapsible
  "Connection / Obfuscation" section (off by default), matching `plans/19` §7:
  mode selector; SOCKS5 reveals host/port; a "Route via Tor (Orbot)" shortcut
  sets `127.0.0.1:9050`.
- **Tests**: a fake SOCKS5 server asserting the destination arrives **unresolved**
  (remote-DNS / leak-free), and `AppTransportFactory` builds the right `Proxy`.

## Embedded sing-box core + REALITY

- **Dependency**: vendor `libbox` (sing-box gomobile `.aar`, the core
  `sing-box-for-android` ships). Not on Maven Central → build via `gomobile bind`
  or vendor the prebuilt per-ABI `.aar` from a pinned sing-box release; pin the
  version in `libs.versions.toml`. **~10–20 MB per ABI** — rely on the existing
  per-ABI APK splits (already shipped); consider on-demand delivery later.
- **`LocalSocksProvider` (`@Singleton`, new, in `service/`)**: owns the core
  lifecycle — `start(configJson)` boots the core with a `mixed`/`socks` inbound on
  `127.0.0.1:<ephemeralPort>` and the VLESS+REALITY outbound; `stop()` tears it
  down; exposes the bound port. Lifecycle anchored to the foreground service /
  `ConnectionManagerImpl.startAll/stopAll` (survives doze; add bootstrap latency
  to the watchdog/backoff per `plans/19` §8). Bind in `di/IrcModule.kt`.
- **`AppTransportFactory`**: when `obfsMode == EMBEDDED_REALITY`, consult
  `LocalSocksProvider` for the live loopback port and build the SOCKS `Proxy`.
  Nothing else changes.
- **vless:// parsing (new `obfs/VlessLink.kt`)**: parse
  `vless://<uuid>@host:443?security=reality&sni=<domain>&pbk=<pubkey>&sid=<shortId>&flow=xtls-rprx-vision`
  into the sing-box outbound JSON (uuid, server/port, `tls.reality` with
  public_key/short_id, `utls` fingerprint e.g. `chrome`, `flow`). Pure function +
  unit tests (valid link, missing params, round-trip to JSON).
- **UI**: the per-network "Obfuscation" field accepts a pasted `vless://` link
  (or scanned QR); selecting a link implies `EMBEDDED_REALITY`.

## Phase 3 — Server (soju-grade) + docs

- One-time: `sing-box generate reality-keypair` (x25519) + a short-id; pick a real,
  reachable **handshake domain** to impersonate (e.g. `www.microsoft.com:443`).
- **Inbound** (`vless` + `reality`, `listen :443`): users `[{uuid}]`;
  `tls.reality { enabled, handshake.server = <domain>:443, private_key, short_id }`;
  route/forward the decrypted stream to `127.0.0.1:6697` (soju). That is the whole
  server — no Let's Encrypt, no owned cert.
- Ship a `docs/obfuscation.md` (or extend `test/e2e` tooling): the sing-box
  systemd unit, the config template, the keypair/short-id commands, and how to
  print the client `vless://` link / QR.
- **Local validation**: run sing-box on the host next to the local stack
  (`test/e2e/local-stack.sh`), `adb reverse tcp:443`, and drive the app through
  `EMBEDDED_REALITY` → soju → ergo end-to-end before touching the VPS.

## Risks / notes (delta over plans/19 §8)

- **APK size** is the real cost — per-ABI splits mandatory; watch total per-arch
  size.
- **Doze / foreground**: the core must survive doze + network changes; reuse the
  foreground-service + connectivity-callback anchor; account for core bootstrap
  latency in reconnect/backoff.
- **REALITY correctness**: the handshake domain must be a real, reachable TLS 1.3
  site the server can forward probes to (fail-open is what defeats active probing).
  Document choosing one.
- **Maintenance**: pin and track the sing-box `libbox` version for security fixes.
- **Pinning/DNS** already covered: pin keys on the real host; `createUnresolved`
  everywhere a proxy is set (explicit test).
- **Hysteria2 fallback**: same embedded core can speak it (password-only server);
  add behind the same `obfsMode` if UDP/QUIC turns out viable on the user's path.

## Phasing / effort

- Phase 1 (SOCKS5 substrate): low, client-only, testable against any SOCKS5
  (`ssh -D`, the local sing-box, Orbot). Independently shippable.
- Embedded core + REALITY: high (AAR vendoring, lifecycle, size, vless
  parsing).
- Phase 3 (server + docs): low, mostly config; can be done first to have a live
  endpoint for Phase 1/2 testing.

Recommended order: bring up the server endpoint, validate the SOCKS substrate
against it, then embed the core. Everything after the SOCKS substrate reuses its
plumbing.
