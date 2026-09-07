# Obfuscation

motd can reach an IRC server or soju bouncer through one of these per-network
options:

- **Off** — direct connection.
- **SOCKS5** — use an existing SOCKS5 proxy; DNS is resolved through the proxy.
- **Tor (Orbot)** — use Orbot's local SOCKS5 proxy, normally with a `.onion`
  bouncer address.
- **Embedded VLESS (sing-box)** — TCP + REALITY or WebSocket + TLS, without
  VLESS flow. The arm64 build runs an embedded sing-box client and exposes a
  local SOCKS proxy automatically. Supply one VLESS URI; no companion Android
  proxy app is needed.

Embedded VLESS is useful where ordinary IRC/TLS is blocked or conspicuous. It
is not a guarantee of anonymity, and operating it may have legal or policy
implications where you live.

## VLESS + REALITY on a VPS

You need a VPS with a public TCP port (use `443` for the best chance of
surviving ISP filtering), an IRC server or
[soju](https://soju.im/) bouncer, and an [Xray](https://github.com/XTLS/Xray-core)
REALITY server. The app embeds sing-box as its client; use Xray on the server
because this pairing is the tested compatible path. Allow the chosen TCP port in
both your host and provider firewall. Keep soju private where possible.
For Docker, use Xray's [official container image](https://github.com/XTLS/Xray-core/)
and join it to the same Docker network as soju.

Generate the server credentials once:

```sh
sing-box generate reality-keypair
sing-box generate uuid
sing-box generate rand 8 --hex
```

Save the private key on the VPS. The public key, UUID, and short ID go in the
client URI. Choose a real TLS 1.3 hostname that is permitted and reliably
reachable in your jurisdiction; do not use a site your ISP or local policy
blocks. The `dest`, `serverNames`, and URI `sni` must all match. See the
[Xray documentation](https://xtls.github.io/en/) for the full option set.

Create `/etc/xray/config.json` (use `443`; keep a second listener on another
port only as an optional fallback):

```json
{
  "log": { "loglevel": "warning" },
  "inbounds": [
    {
      "tag": "motd-vless",
      "listen": "0.0.0.0",
      "port": 443,
      "protocol": "vless",
      "settings": {
        "clients": [{ "id": "<UUID>" }],
        "decryption": "none"
      },
      "streamSettings": {
        "network": "tcp",
        "security": "reality",
        "realitySettings": {
          "dest": "<HANDSHAKE_HOST>:443",
          "serverNames": ["<HANDSHAKE_HOST>"],
          "privateKey": "<PRIVATE_KEY>",
          "shortIds": ["<SHORT_ID>"]
        }
      }
    }
  ],
  "outbounds": [
    { "tag": "direct", "protocol": "freedom" },
    { "tag": "block", "protocol": "blackhole" }
  ],
  "routing": {
    "rules": [
      { "type": "field", "domain": ["full:soju"], "port": "6697", "outboundTag": "direct" },
      { "type": "field", "network": "tcp,udp", "outboundTag": "block" }
    ]
  }
}
```

The route rules above restrict the service to the bouncer named `soju`; change
that name only to match your Docker/network layout. If soju advertises a file
host outside that name, allow only its exact host and port before the blocking
rule too:

```json
{ "type": "field", "domain": ["full:<FILEHOST_HOST>"], "port": "<FILEHOST_PORT>", "outboundTag": "direct" }
```

Validate and start Xray using your distribution's service or Docker setup:

```sh
xray run -test -c /etc/xray/config.json
systemctl enable --now xray
```

Your client URI is:

```text
vless://<UUID>@<VPS_HOST>:443?encryption=none&security=reality&sni=<HANDSHAKE_HOST>&fp=chrome&pbk=<PUBLIC_KEY>&sid=<SHORT_ID>&type=tcp#motd
```

Treat this URI like a password: it grants access to your VPS proxy. Use a unique
UUID per device and remove it from the server config when a device is lost.

## VLESS + WebSocket + TLS through a CDN

A WebSocket-capable HTTPS CDN such as Cloudflare needs a separate VLESS
WebSocket origin, not the TCP + REALITY listener above. Direct REALITY cannot
simply be orange-clouded: an ordinary CDN terminates TLS and forwards HTTP
WebSockets, not the REALITY handshake.

Example client link (replace the example UUID with a device-specific credential):

```text
vless://00000000-0000-4000-8000-000000000001@relay.trevs.site:443?encryption=none&type=ws&security=tls&sni=relay.trevs.site&host=relay.trevs.site&path=%2Firc-vless#motd-cdn
```

The URI address is the public ingress. `sni` must match its valid TLS certificate;
the WebSocket `host` must select the configured HTTP virtual host, and `path`
must match the origin's WebSocket route (`/irc-vless` here, URL-encoded in the
URI). Omitted `path` defaults to `/`; `host` is optional when no override is
needed. Do not set `flow`. Use verified TLS from Cloudflare to the origin too
(Full (strict)), and retain exact destination host/port allowlists followed by
a catch-all reject rule.

The ingress address and TLS identity may differ. If the CDN's assigned addresses
stall on the affected network, an operator can use a separate DNS-only connection
name (for example, `relay-entry.trevs.site`) pointing to a tested Cloudflare edge.
Use that name only as the URI ingress; keep `sni` and `host` as the proxied
`relay.trevs.site`. DNS-only must point to the CDN edge, not the VPS. This is
path-specific address selection, not guaranteed unblockability: keep the edge
choice in DNS so it can be changed without editing every client, and retain TLS
verification.

Cloudflare terminates the outer TLS, so keep **verified inner IRC TLS** enabled
to protect IRC credentials and messages from the CDN. This CDN transport does
not make arbitrary SSH work; it serves the configured VLESS WebSocket endpoint
and only the destinations its routing rules permit.

The pinned sing-box 1.13.12 supports WebSocket; Xray 26.6.1 emits a WebSocket
deprecation warning. Check client/server transport compatibility before future
upgrades.

## Configure motd

1. Add or edit the bouncer network. These **Host**, **Port**, and TLS fields
   name the bouncer destination *after* the VLESS tunnel, not the public VLESS
   server. The VLESS URI below contains the public server address. For example,
   keep the ordinary inner IRC hostname and port `6697`, with TLS enabled;
   leave the native IRC **WebSocket URL** blank. The VLESS WebSocket is an outer
   tunnel, not the IRC server's own WebSocket transport.

   With separate Docker containers on a shared network, use the bouncer's Docker
   DNS name (for example, host `soju`, port `6697`, TLS enabled). Do **not** use
   `127.0.0.1`: in that layout it points back to the proxy container. Loopback is
   correct only when the proxy and bouncer share a network namespace.
2. Open **Settings → Networks → _your network_ → Connection / Obfuscation**.
3. Choose **Embedded VLESS (sing-box)**, paste either supported URI, and save.
4. Reconnect. On first use of a self-signed or loopback certificate, verify the
   fingerprint and accept motd's certificate-trust prompt. motd pins that leaf
   certificate for later connections.

If your bouncer is elsewhere, keep its normal hostname and port instead, and
adjust the Xray route restriction accordingly. Invalid embedded configuration
or a failed proxy does not fall back to a direct IRC connection.

Upload credential scope is unchanged for both VLESS transports. `DIRECT` and
child endpoints accept `FILEHOST` only at the IRC hostname or its subdomains,
or at the exact URI ingress hostname; unrelated hosts are rejected before
sending credentials. An ingress of `relay.trevs.site` alone does **not**
authorize `irc.trevs.site` as a `FILEHOST` (it must independently match the IRC
host namespace). The existing embedded Soju root exception still accepts its
advertised HTTPS file host, since that Soju already holds the same credential.
HTTPS certificate validation remains required, and server routing must
separately allow the exact file-host destination and port.

## SOCKS5 and Tor

For an existing proxy, choose **SOCKS5** and enter its host and port. For Tor,
install [Orbot](https://orbot.app/), start it, then choose **Tor (Orbot)**. A
Tor hidden-service address for soju avoids exposing the bouncer's public IP.

## Media previews

Chat images, link thumbnails, video posters and playback, fullscreen images,
image **Save**, avatars, network icons, link metadata, and extensionless-audio
HEAD discovery use the owning IRC network's route, including embedded VLESS,
SOCKS5, or Tor. Missing networks and broken routes fail closed; they never fall
back to a direct connection. Media GET/HEAD requests are anonymous and do not
send SASL credentials.

The restrictive Xray example above blocks arbitrary web destinations: a
working IRC connection does not imply a media host is reachable. Allow the
URL's exact host and port, including redirect destinations, before the
blocking rule. Use the public URL host and port (for example,
`irc.trevs.site:9443`), not an internal reverse-proxy backend port.
Ergo filehost GET/HEAD viewing is public:
it needs no direct bypass and grants no permission to POST uploads or send
credentials to an unrelated host. Upload credential scope remains unchanged.

The **Load previews over direct connection** setting has been retired.
Existing preferences and imported backups cannot re-enable a direct bypass.
Global avatar views use a shared avatar only when its source network is
unambiguous; otherwise they retain the generated avatar rather than choosing
an arbitrary route. Imported local avatar files remain local.

Image/link-preview visibility and automatic-loading settings for metered and
unmetered connections still apply. When automatic loading is disabled, the
download icon requests that preview; it does not grant permission to bypass a
proxy. Failed link previews can be retried after the route is repaired.
Explicitly opening a link in a browser or another app does not give that app
motd's embedded proxy. Audio **Save** uses Android's DownloadManager, not the
routed image-save path.

## Troubleshooting

- Confirm the VPS firewall allows the selected TCP port and that Xray validates
  its configuration.
- Ensure the REALITY server name is reachable from the VPS and supports TLS 1.3.
- Embedded VLESS resolves ingress hostnames through Android's active network
  resolver, including its configured DNS policy. Keep the CDN hostname in the
  URI; a fixed edge IP is only a diagnostic and may stop working as routing changes.
- A changed bouncer certificate requires reviewing the new certificate prompt.
- The embedded option currently requires an arm64-v8a Android device. Use
  SOCKS5 or Tor on other device ABIs.
