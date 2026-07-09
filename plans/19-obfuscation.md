# plans/19 — Obfuscation / censorship circumvention (opt-in transport)

Status: design + triage. No app code in this round.

## 1. Goal and scope

Let motd users on hostile networks (censoring ISP, restrictive
corporate/campus firewall, DPI-based blocking) reach their own IRC servers and,
primarily, their own soju bouncer. This is a defensive anti-censorship feature
for the app's own users reaching infrastructure they control, comparable to
adding pluggable-transport / proxy support to a chat client (Tor obfs4,
Shadowsocks, Signal's censorship circumvention).

Design bias for v1:

- Client-implementable, plugs into the existing transport seam.
- Prefer options where the user already controls the server (soju), so
  server-side cooperation is available.
- Opt-in only. Off by default. No behavior change for users on open networks.

## 2. Threat model

Adversary: an on-path network operator between the device and the server who can
inspect, fingerprint, delay, and drop traffic, and can mount active probes.
Assumed capabilities, grounded in current censorship research:

- **Port blocking.** 6667 (IRC) and 6697 (IRCS) are IANA-registered and are
  trivial static block targets. Plaintext 6667 additionally exposes the
  registration burst (`NICK`/`USER`/`PASS`, numerics `001`/`375`) as a stable
  content signature that DPI engines (nDPI, l7-filter, SPID) ship signatures
  for, independent of port.[^dpi][^spid]
- **SNI inspection.** TLS 1.2/1.3 ClientHello carries `server_name` in
  cleartext. This is the dominant real-world TLS censorship vector; the GFW
  maintains per-protocol SNI blocklists and blocked ~43.8K FQDNs/week (58,207
  unique over Oct 2024–Jan 2025). SNI is inspected across all ports, not just
  443.[^gfw25][^esni]
- **TLS fingerprinting (JA3/JA4).** The ClientHello (cipher suites, extensions,
  ordering, curves, GREASE) fingerprints the client stack regardless of SNI.
  China has blocked circumvention tools by fingerprint alone while browsers
  reached the same ports. JA4 is explicitly designed to defeat naive extension
  permutation.[^ja3ja4][^tlsblock]
- **Active probing.** Suspicious flows are followed up with active connection
  probes to confirm a hidden proxy before blocking. Any transport that answers
  differently from a real web server under probing is at risk.[^gfw25]
- **Wholesale UDP/QUIC throttling or blocking** on some networks, which
  neutralizes QUIC-based transports.[^hysteria]

Out of scope: a global passive adversary correlating Tor traffic; endpoint
compromise; malware on the device. We defend reachability and metadata
(destination + protocol), not anonymity from a nation-state SIGINT program.

## 3. Technique survey

For each: does it need server-side cooperation, and is there a usable
Android/JVM path.

### 3.1 TLS on port 443 (baseline)

Move the bouncer's TLS listener to 443 so IRCS blends with HTTPS. Defeats naive
port blocking and is free client-side. **Necessary but insufficient**: SNI, the
TLS certificate, the destination IP, and the client TLS fingerprint all remain
visible on 443 exactly as on 6697.[^tlsobscurity][^esni] Server-side: user
points soju at `:443` (or a reverse proxy). Client-side: nothing beyond letting
the user set port 443. Recommended as always-available guidance, not a real
obfuscation layer.

### 3.2 SNI concealment: ECH and domain fronting

- **ECH (Encrypted Client Hello).** Encrypts the ClientHello (incl. SNI/ALPN)
  under a server public key fetched via DNS HTTPS/SVCB records (so DoH/DoT is a
  practical prerequisite). Standardized as **RFC 9849 (Proposed Standard, March
  2026)**, superseding the dead ESNI design (which the GFW blocked outright in
  2020 by dropping any ClientHello carrying `encrypted_server_name`). Chrome,
  Firefox, Safari, and Cloudflare support it.[^rfc9849][^ech-cf][^esni]
  - **Android/JVM gap.** No upstream ECH in Conscrypt (google/conscrypt#730 open)
    or OkHttp (square/okhttp#6539 "sketched out"). Using ECH on Android today
    means **bundling a forked Conscrypt + BoringSSL** (TunnelBear did exactly
    this) plus a DoH bootstrap. High effort; treat as future work, not
    v1.[^conscrypt][^okhttp][^tunnelbear]
  - ECH hides only SNI; IP, cert, TLS fingerprint, and traffic shape remain.
- **Domain fronting: effectively dead.** Google/AWS disabled it in 2018,
  Cloudflare in 2015, Azure Front Door 8 Jan 2024, Fastly 27 Feb 2024. Do not
  design around it.[^df][^fastly][^azure]

### 3.3 IRC-over-WebSocket (WSS on 443)

soju **natively** supports WebSocket listeners via its `listen`
directive.[^soju][^sojuman] Relevant schemes:

- `wss://` — WebSocket over TLS, default port 443.
- `https://` — HTTPS listener serving WebSocket + file upload (default 443).
- `ws+insecure://` / `ws+unix://` — plaintext WS for reverse-proxying behind
  Caddy/nginx on 443.

IRCv3 defines the WebSocket subprotocols `text.ircv3.net` and
`binary.ircv3.net` (each WS message is one IRC line without trailing CRLF; the
510-byte limit still applies).[^ircv3ws] On the wire, `wss://:443` is a normal
TLS handshake (SNI = bouncer host) followed by an HTTP `Upgrade: websocket`
inside the tunnel — indistinguishable from an ordinary HTTPS web app to DPI,
and it clears networks that only permit web ports. This is the best
stealth-per-effort option and needs only server-side config the user already
owns, but the **client must implement a WebSocket IRC transport** (new code, and
the current `OkioLineTransport` speaks raw TCP/TLS lines, not WS frames).
Residual risk: SNI/IP/JA3 are still exposed (mitigated by pairing with a
generic HTTPS front and, later, ECH).

### 3.4 SOCKS5 / HTTP proxy (user-supplied), and Tor

- **User-supplied SOCKS5/HTTP proxy.** Purely client-side; the app dials the
  target through the proxy. On the JVM:
  `Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort)))`,
  connect, then layer TLS with the 4-arg
  `sslSocketFactory.createSocket(proxiedSocket, targetHost, targetPort, true)`
  which sets SNI + hostname verification over the tunneled
  socket.[^javaproxy][^socksex] **Critical caveat:** Java's SOCKS impl only
  performs remote DNS if the destination `InetSocketAddress` is *unresolved*.
  `InetSocketAddress(host, port)` resolves locally in its constructor (DNS leak,
  and `.onion` fails). Use `InetSocketAddress.createUnresolved(host, port)` to
  force remote resolution. `Proxy.Type.SOCKS` (not HTTP CONNECT) is the right
  choice for arbitrary TCP + remote DNS.[^vkuzel]
- **Tor.** Route the same SOCKS5 path through Orbot's local proxy
  (`127.0.0.1:9050`), or embed Tor in-process via Guardian Project's
  `tor-android` / IPtProxy. Tor carries arbitrary TCP, so an IRC/TLS socket
  works.[^orbot][^toran] The strong pattern for a user-controlled bouncer:
  expose soju as a **`.onion` hidden service** and dial it through SOCKS5 — no
  public IP, no valid cert, NAT traversal, end-to-end auth, no clearnet
  exposure. Cost: latency and the Tor binary/bootstrap. Same
  `createUnresolved` rule applies (mandatory for `.onion`).

### 3.5 Pluggable transports (embedded circumvention core)

The dominant Android architecture: compile a Go core with `gomobile bind` into
an `.aar`, run it in-process (goroutines, no root, no VPNService needed for
app-level proxying), have it open a **local SOCKS5 proxy on `127.0.0.1:<port>`**,
and point the app's socket at that port. Google's Outline SDK packages exactly
this model. Per-ABI `.so` adds ~10–30 MB per ABI to the APK. minSdk 26 is well
within range.[^gomobile][^outline] Rust cores (shadowsocks-rust) use the same
"local SOCKS" shape via JNI.

Ranked by fit given the user controls the server:

| Transport | Needs server? | Android path | DPI resistance 2025–26 |
|---|---|---|---|
| **VLESS + XTLS/Reality** (Xray) | Yes (Xray next to soju) | `AndroidLibXrayLite`/`libv2ray` gomobile `.aar`, local SOCKS[^xray][^libxray] | **Top.** Borrows a real third-party site's TLS handshake; defeats SNI blocking + active probing with no cert/domain of your own.[^reality] |
| **Hysteria2** | Yes | sing-box / apernet Android builds, local proxy[^hysteria] | Strong; masquerades as HTTP/3. But dies under wholesale UDP/QUIC blocking — pair with a TCP fallback.[^hysteria] |
| **Shadowsocks-rust + Cloak** | Yes | shadowsocks-android or embedded rust; Cloak SIP003 plugin[^ssrust][^cloak] | Good. Cloak makes it indistinguishable from a real HTTPS server incl. under probing. Vanilla SS (no plugin) is blocked in China — avoid alone.[^cloak] |
| **obfs4 / Lyrebird** | Yes (obfs4 bridge) | **IPtProxy** `.aar` (Maven Central), local SOCKS[^iptproxy] | Baseline. Look-like-nothing scrambler; detectable by entropy + active probing, no cover protocol. |
| **Snowflake** | No (routes into Tor) | IPtProxy, local SOCKS[^iptproxy] | Robust (WebRTC, ephemeral proxies) but tunnels into Tor, not directly to your soju — architectural mismatch unless routing IRC over Tor. |
| **meek** | Yes + CDN | not in mainline IPtProxy | **Dead** (domain fronting). Skip. |

**IPtProxy** is the cleanest ready-made Android on-ramp (Guardian
Project / Orbot / Onion Browser depend on it): one `Controller`, start a
transport, it auto-picks a free port and runs a local SOCKS5 proxy; read the
port and dial through it. Current mainline exposes Lyrebird/obfs4, Snowflake,
and DNSTT.[^iptproxy][^iptguide]

### 3.6 SSH tunnel

Embed a Java SSH lib (ConnectBot `sshlib` or Apache MINA SSHD), open a local or
dynamic (SOCKS) forward `localhost:LPORT -> bouncer:6697`, dial
that.[^sshlib][^mina] Powerful and reuses an SSH box the user may already have,
but **heaviest UX** (host/port/creds/host-key management, keepalive across
doze/network changes) and **weakest camouflage** (the SSH banner is itself a DPI
signature unless further wrapped). Low priority.

## 4. Ranked options for v1

Effort / robustness / UX, best first for a user who controls their soju:

1. **User-supplied SOCKS5 (+ optional Tor) proxy support.**
   - Effort: **low.** ~one transport wrapper + settings.
   - Robustness: depends on the proxy the user provides. With Tor `.onion` to
     soju: high reachability, resistant to SNI/IP blocking.
   - UX: user enters proxy host/port (or toggles "route via Orbot"). Familiar
     model.
   - Fully client-implementable, plugs directly into the existing transport
     seam. **This is the v1 core.**

2. **WSS-on-443 guidance + a WebSocket transport.**
   - Effort: **medium** (new WS transport in `:irc`).
   - Robustness: high stealth-per-effort; looks like HTTPS. Needs only soju
     config the user already controls.
   - UX: user sets a `wss://` URL / toggles WebSocket for a network.
   - Strong second step; native soju support makes it low-risk server-side.

3. **Embedded pluggable transport (VLESS+Reality via AndroidLibXrayLite, or
   IPtProxy/obfs4) exposing a local SOCKS5.**
   - Effort: **high** (bundle a Go `.aar`, per-ABI size, config surface,
     update/maintenance burden).
   - Robustness: **top-tier** (Reality defeats SNI + active probing without a
     cert/domain).
   - UX: more config, but can be templated since the user controls both ends.
   - Because it terminates at a local SOCKS5 proxy, it **reuses option 1's
     integration path** — the app just points the same proxied-socket code at
     `127.0.0.1:<port>`. This is the key architectural leverage: build the SOCKS5
     plumbing once, and every embedded transport plugs into it.

Deliberately deferred: **ECH** (needs a forked TLS stack on Android — high
effort, low marginal value until IP/fingerprint are also handled) and **SSH**
(heavy UX, weak camouflage).

## 5. Recommended approach (v1)

Build the SOCKS5 proxy path first, because it is client-only, plugs cleanly into
the transport, and is the common substrate that every embedded transport
(Reality, obfs4, Snowflake, Shadowsocks+Cloak) terminates in.

**v1 (ship):**

1. Per-network optional **SOCKS5/HTTP proxy** (host, port, optional
   auth). The transport dials the bouncer/server through it, with remote DNS via
   `createUnresolved`.
2. A convenience **"Route via Tor (Orbot)"** toggle that targets
   `127.0.0.1:9050` and detects Orbot; document the `.onion`-bouncer setup.
3. **Docs/guidance** for `wss://:443` and `ircs://:443` on soju as the
   server-side stealth baseline.

**v1.1 (next):** a **WebSocket-over-TLS transport** in `:irc` so the app can
speak `wss://` to soju directly (best stealth without an extra proxy binary).

**v2 (later):** an **embedded pluggable-transport core** (start with
IPtProxy/obfs4 for a turnkey `.aar`; add VLESS+Reality via AndroidLibXrayLite for
top-tier resistance), each surfaced as "runs a local SOCKS5 that v1 already
knows how to use." Optionally ECH once a forked TLS stack is justified.

Everything after v1 reuses the v1 SOCKS5 plumbing, so this ordering front-loads
the reusable substrate and defers the heavy binaries until proven necessary.

## 6. Concrete integration points

The transport seam is small and already abstracted, which makes this clean.

- **`irc/.../transport/Transport.kt`**
  - `interface IrcTransport` and `fun interface TransportFactory` are the seam.
  - `class OkioLineTransport` builds the socket in `connect()`:
    ```kotlin
    val raw = Socket()
    raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
    ```
    This raw-`Socket()` construction is the **single injection point** for a
    proxy: replace with `Socket(proxy)` and connect to an *unresolved* address.
    TLS is already layered afterwards via `ctx.socketFactory.createSocket(raw,
    host, port, true)`, which is exactly the 4-arg proxied-socket TLS pattern —
    no change needed to the TLS layering itself.
  - Add an optional `proxy: java.net.Proxy?` (and `remoteDns: Boolean`) ctor
    param to `OkioLineTransport`. When present:
    - `Socket(proxy)` instead of `Socket()`.
    - Connect to `InetSocketAddress.createUnresolved(host, port)` to force remote
      DNS through the SOCKS5 proxy (leak-free, `.onion`-capable).
  - A `WsLineTransport` (v1.1) implements the same `IrcTransport` interface with
    a WebSocket framing layer; `TransportFactory` chooses it per network.

- **`app/.../service/AppTransportFactory.kt`**
  - `create(host, port, tls)` already composes STS rewrite + pinning +
    client-cert `SSLContext`. Add proxy resolution here: read the effective
    proxy config (per-network override or global) and pass a `Proxy` (or null)
    into `OkioLineTransport`. Pinning/hostname behavior is unchanged — pinning
    still keys on the real `host:port`, and hostname verification still targets
    the real host through the tunnel.
  - For an embedded transport (v2), this is where a `LocalSocksProvider` (owning
    the IPtProxy/Xray controller and its `127.0.0.1:<port>`) is consulted to
    build `Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", localPort))`.
  - Constructor already takes injected stores; add a proxy-config reader
    (Settings + per-network fields).

- **`app/.../service/ConnectionManagerImpl.kt`**
  - `buildClient(row)` constructs the `AppTransportFactory` per network. Thread
    the network's proxy config here. Also extend `fingerprint(row)` (currently
    `host:port:tls:nick:sasl:bouncerNetId:clientCertAlias`) to include the proxy
    config so a proxy change triggers an actor restart, same as any other
    connection-affecting field.
  - The embedded-core lifecycle (start/stop the local SOCKS proxy) fits the
    existing `@Singleton` `ConnectionManagerImpl` lifecycle (`startAll` /
    `stopAll`), or a sibling singleton it depends on.

- **`app/.../di/IrcModule.kt`**
  - The base `TransportFactory` provider stays; per-network factories are still
    built inside `ConnectionManagerImpl`. If a `LocalSocksProvider` singleton is
    added for v2, bind it here.

## 7. Settings surface

Two levels, mirroring existing patterns (`Settings.kt` data class + DataStore;
per-network fields on `NetworkEntity`).

**Per-network (primary), on `NetworkEntity` + Network Settings screen** — a
proxy is often network-specific (bouncer over Tor, Libera direct):

- New nullable columns on `NetworkEntity` (Room migration): `proxyType`
  (`NONE` | `SOCKS5` | `HTTP` | `TOR` | `EMBEDDED`), `proxyHost`, `proxyPort`,
  `proxyUser`, `proxyPassword` (redacted in `toString()` like `saslPassword`),
  and (v1.1) a `transport` enum (`TCP_TLS` | `WSS`) with a `wsUrl`.
- UI in `NetworkSettingsScreen.kt`: a collapsible "Connection / Obfuscation"
  section under `NetworkForm`, off by default. A `Switch` "Use proxy" reveals
  type + host/port/auth fields, matching the existing `AuthForm`/`ServerForm`
  style. A "Route via Tor (Orbot)" shortcut sets `TOR` + `127.0.0.1:9050`.

**Global (optional defaults), on `Settings`** — a default proxy applied to
networks with `proxyType = NONE`:

- Extend the `Settings` data class + `SettingsRepository` with
  `defaultProxy: ProxyConfig?` (new serializable type) following the existing
  field-per-setting pattern (`setThemeMode` etc.).
- Surface in `SettingsScreen.kt` as a new section "Censorship circumvention"
  with the same opt-in `Switch` gating.

Copy should state plainly that this is opt-in, may add latency, and that the
strongest options (WSS-on-443, `.onion` bouncer, Reality) require server-side
setup on the user's own soju.

## 8. Open questions and risks

- **WebSocket transport scope.** A `wss://` transport needs a WS client. Adding
  OkHttp (`WebSocket`) pulls a dependency not currently present (only Okio is);
  a hand-rolled RFC 6455 client over Okio avoids the dep but is more code.
  Decide before v1.1. Also handle IRCv3 subprotocol negotiation and soju's
  Origin/host authorization.
- **DNS leaks.** Must use `createUnresolved` everywhere a proxy is set;
  otherwise the local resolver leaks the destination and `.onion` breaks. Needs
  an explicit test.
- **Cert pinning interaction.** TOFU leaf pinning (plans/12) and hostname
  verification must key on the real target host, not the proxy — verify the
  `AppTransportFactory` pin lookup and `verifyHostname` still use the real
  endpoint when tunneled.
- **APK size vs. capability (v2).** Each embedded Go/Rust core adds several MB
  per ABI. Consider a separate flavor or a download-on-demand module rather than
  bundling all transports.
- **Reachability of the local proxy / Orbot.** Detect Orbot absence and surface
  a clear error instead of a silent connect timeout.
- **Foreground-service / doze.** A tunnel or embedded core must survive doze and
  network changes; the existing foreground service + connectivity callback path
  is the anchor, but embedded cores add reconnect/bootstrap latency to account
  for in the watchdog/backoff.
- **TLS fingerprint remains (non-mimic paths).** SOCKS5/WSS still emit
  Conscrypt's ClientHello. There is no mature JVM uTLS equivalent, so
  fingerprint mimicry realistically only comes from an embedded Go core
  (Reality/uTLS). Document this limit; do not over-claim stealth for v1.
- **Maintenance.** Embedded cores (IPtProxy, Xray) need version tracking and
  security updates. Budget for it or defer.
- **Legal/UX.** Circumvention may violate local law or network policy; keep it
  opt-in, unadvertised by default, and let the user decide.

## Sources

[^dpi]: https://en.wikipedia.org/wiki/Deep_packet_inspection ,
    https://www.techtarget.com/searchnetworking/definition/deep-packet-inspection-DPI
[^spid]: SPID protocol-fingerprint set incl. IRC (DPI literature, per [^dpi]).
[^gfw25]: https://gfw.report/publications/usenixsecurity25/en/ ,
    https://www.usenix.org/system/files/usenixsecurity25-zohaib.pdf
[^esni]: https://gfw.report/blog/gfw_esni_blocking/en/
[^ja3ja4]: https://www.systemshardening.com/articles/network/tls-fingerprinting-ja3-ja4/ ,
    https://corpus.lantern.io/techniques/tls-fingerprint/
[^tlsblock]: https://gfw.report/blog/blocking_of_tls_based_circumvention_tools/en/
[^tlsobscurity]: https://ris.uni-paderborn.de/download/59824/59826/TLS_Obscurity.pdf
[^rfc9849]: https://datatracker.ietf.org/doc/draft-ietf-tls-esni/ (published as
    RFC 9849, Proposed Standard, March 2026)
[^ech-cf]: https://developers.cloudflare.com/ssl/edge-certificates/ech/ ,
    https://blog.cloudflare.com/announcing-encrypted-client-hello/
[^conscrypt]: https://github.com/google/conscrypt/issues/730
[^okhttp]: https://github.com/square/okhttp/issues/6539
[^tunnelbear]: https://www.tunnelbear.com/blog/introducing-encrypted-client-hello-ech/ ,
    https://guardianproject.info/2021/11/30/implementing-tls-encrypted-client-hello/
[^df]: https://en.wikipedia.org/wiki/Domain_fronting
[^fastly]: https://www.risky.biz/fastly-to-block-domain-fronting-in-2024/
[^azure]: https://techcommunity.microsoft.com/blog/azurenetworkingblog/prohibiting-domain-fronting-with-azure-front-door-and-azure-cdn-standard-from-mi/4006619
[^soju]: https://github.com/emersion/soju/blob/master/doc/soju.1.scd
[^sojuman]: https://manpages.ubuntu.com/manpages/noble/man1/soju.1.html
[^ircv3ws]: https://ircv3.net/specs/extensions/websocket
[^javaproxy]: https://docs.oracle.com/javase/8/docs/technotes/guides/net/proxies.html
[^socksex]: https://www.example-code.com/android/socket_socks_proxy.asp
[^vkuzel]: https://vkuzel.com/using-socks-proxy-in-java
[^orbot]: https://github.com/guardianproject/orbot-android ,
    https://blog.torproject.org/tor-android/
[^toran]: https://tor.void.gr/docs/android.html.en
[^gomobile]: https://go.dev/wiki/Mobile ,
    https://pkg.go.dev/golang.org/x/mobile/cmd/gomobile
[^outline]: https://pkg.go.dev/golang.getoutline.org/sdk
[^xray]: https://github.com/XTLS/Xray-core
[^libxray]: https://github.com/2dust/AndroidLibXrayLite ,
    https://pkg.go.dev/github.com/2dust/AndroidLibXrayLite
[^reality]: https://objshadow.pages.dev/en/posts/how-reality-works/ ,
    https://iplogs.com/blog/reality-xray-amnezia-wg-anti-censorship-2026
[^hysteria]: https://v2.hysteria.network/ , https://github.com/apernet/hysteria
[^ssrust]: https://github.com/shadowsocks/shadowsocks-rust ,
    https://github.com/shadowsocks/shadowsocks-android
[^cloak]: https://github.com/cbeuw/Cloak , https://github.com/cbeuw/Cloak-android
[^iptproxy]: https://github.com/tladesignz/IPtProxy
[^iptguide]: https://guide.onionmobile.dev/tor-on-android/iptproxy-pluggable-transports
[^sshlib]: https://github.com/connectbot/connectbot ,
    https://github.com/Anton2319/VPNoverSSH
[^mina]: https://github.com/apache/mina-sshd/blob/master/docs/port-forwarding.md
