# Obfuscation

motd can reach an IRC server or soju bouncer through one of these per-network
options:

- **Off** — direct connection.
- **SOCKS5** — use an existing SOCKS5 proxy; DNS is resolved through the proxy.
- **Tor (Orbot)** — use Orbot's local SOCKS5 proxy, normally with a `.onion`
  bouncer address.
- **VLESS + REALITY** — the arm64 build runs an embedded sing-box client
  and exposes a local SOCKS proxy automatically. You supply one VLESS URI; no
  companion Android proxy app is needed.

VLESS + REALITY is useful where ordinary IRC/TLS is blocked or conspicuous. It
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
that name only to match your Docker/network layout. Validate and start Xray
using your distribution's service or Docker setup:

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

## Configure motd

1. Add or edit the bouncer network. These **Host**, **Port**, and TLS fields
   name the bouncer destination *after* the VLESS tunnel, not the public VLESS
   server. The VLESS URI below contains the public server address.

   With separate Docker containers on a shared network, use the bouncer's Docker
   DNS name (for example, host `soju`, port `6697`, TLS enabled). Do **not** use
   `127.0.0.1`: in that layout it points back to the proxy container. Loopback is
   correct only when the proxy and bouncer share a network namespace.
2. Open **Settings → Networks → _your network_ → Connection / Obfuscation**.
3. Choose **VLESS + REALITY (sing-box)**, paste the URI, and save.
4. Reconnect. On first use of a self-signed or loopback certificate, verify the
   fingerprint and accept motd's certificate-trust prompt. motd pins that leaf
   certificate for later connections.

If your bouncer is elsewhere, keep its normal hostname and port instead, and
adjust the Xray route restriction accordingly.

## SOCKS5 and Tor

For an existing proxy, choose **SOCKS5** and enter its host and port. For Tor,
install [Orbot](https://orbot.app/), start it, then choose **Tor (Orbot)**. A
Tor hidden-service address for soju avoids exposing the bouncer's public IP.

## Troubleshooting

- Confirm the VPS firewall allows the selected TCP port and that Xray validates
  its configuration.
- Ensure the REALITY server name is reachable from the VPS and supports TLS 1.3.
- A changed bouncer certificate requires reviewing the new certificate prompt.
- The embedded option currently requires an arm64-v8a Android device. Use
  SOCKS5 or Tor on other device ABIs.
