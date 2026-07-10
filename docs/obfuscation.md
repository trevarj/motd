# Obfuscation: reaching soju through censorship

motd can dial your bouncer through a **SOCKS5 proxy** with remote DNS, off by
default and per-network. That one seam is the substrate every stronger transport
terminates in (plans/19 §5, plans/20). The strongest option shipped-adjacent is
**VLESS + REALITY** carried by a sing-box server next to soju: it borrows a real
third-party site's TLS handshake, so on the wire it is indistinguishable from
ordinary HTTPS to that site and survives active DPI probing.

Phase 1 (this release) is the client-side SOCKS5 substrate plus a local REALITY
test harness. The in-app REALITY core (embedded sing-box `libbox`) is Phase 2;
until then, run a small **sing-box/Xray client** next to the app that exposes a
local SOCKS5, and point the per-network Obfuscation field at it. No companion app
is *eventually* needed — Phase 2 folds the client into the APK.

## a. VPS: the sing-box REALITY server (next to soju)

soju stays unchanged on loopback (`listen ircs://:6697`). Put a sing-box VLESS +
REALITY inbound in front of it. This is one static Go binary + one JSON config +
one systemd unit — soju-grade.

### 1. Keys and identifiers (one time)

```sh
sing-box generate reality-keypair   # -> PrivateKey (server) + PublicKey (client)
sing-box generate uuid              # -> the VLESS user UUID
sing-box generate rand 8 --hex      # -> a short-id (hex, even length, <= 16 chars)
```

### 2. Pick a handshake domain

REALITY impersonates a **real, reachable TLS 1.3 site** the server relays probes
to. It must:

- serve TLS 1.3 and complete a handshake the client's uTLS Chrome fingerprint
  accepts, and
- be reachable from the VPS on `:443`.

`www.microsoft.com` is a poor choice in practice (it did not complete the REALITY
steal from our test host). **`www.cloudflare.com`** is a reliable default;
`www.apple.com`, `addons.mozilla.org`, `gateway.icloud.com` also work. Prefer a
site unrelated to you that is unlikely to be blocked.

### 3. `/etc/sing-box/config.json`

```json
{
  "log": { "level": "warn" },
  "inbounds": [
    {
      "type": "vless",
      "tag": "vless-in",
      "listen": "0.0.0.0",
      "listen_port": 443,
      "users": [ { "uuid": "<UUID>" } ],
      "tls": {
        "enabled": true,
        "server_name": "www.cloudflare.com",
        "reality": {
          "enabled": true,
          "handshake": { "server": "www.cloudflare.com", "server_port": 443 },
          "private_key": "<PrivateKey>",
          "short_id": [ "<short-id>" ]
        }
      }
    }
  ],
  "outbounds": [
    { "type": "direct", "tag": "direct" }
  ]
}
```

The decrypted stream is routed to whatever destination the **client** asked for.
With the client pattern below the client dials `127.0.0.1:6697`, and the server's
`direct` outbound reaches soju on loopback — no static forward needed. If you want
the server to force the destination (so the client cannot pick arbitrary hosts),
add a route rule pinning the outbound to `127.0.0.1:6697`.

Validate before starting: `sing-box check -c /etc/sing-box/config.json`.

### 4. systemd unit `/etc/systemd/system/sing-box.service`

```ini
[Unit]
Description=sing-box (VLESS+REALITY in front of soju)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/sing-box run -c /etc/sing-box/config.json
Restart=on-failure
RestartSec=3
LimitNOFILE=65535
# Hardening
DynamicUser=yes
AmbientCapabilities=CAP_NET_BIND_SERVICE
NoNewPrivileges=yes

[Install]
WantedBy=multi-user.target
```

```sh
systemctl daemon-reload
systemctl enable --now sing-box
```

### 5. The client share link

```
vless://<UUID>@<vps-host>:443?encryption=none&security=reality&sni=www.cloudflare.com&fp=chrome&pbk=<PublicKey>&sid=<short-id>&type=tcp#my-bouncer
```

## b. Client setup (until the embedded core lands)

Phase 1 has no embedded REALITY core, so run a **local sing-box/Xray client** on
the device (or the machine the device reaches) that exposes a plain SOCKS5 inbound
and speaks VLESS+REALITY out to your VPS. Then, in motd:

1. Open the network's settings → **Connection / Obfuscation** (collapsed, off by
   default).
2. Mode **SOCKS5**, Host `127.0.0.1`, Port `1080` (or wherever the client's SOCKS
   inbound listens).
3. Leave the soju endpoint (host/port/TLS) as-is: the app resolves and reaches it
   **remotely through the proxy** (DNS is not leaked locally; `.onion` works).

Notes:

- **Route via Tor (Orbot)** is a shortcut that pins SOCKS5 at `127.0.0.1:9050`;
  install Orbot and, ideally, expose soju as a `.onion` hidden service.
- The strongest options (a `.onion` bouncer, or VLESS+REALITY) require setup on
  **your own** soju/VPS — this is opt-in and may add latency.
- Client uTLS caveat: sing-box's own REALITY *client* currently cannot
  authenticate against a sing-box REALITY *server*
  ([SagerNet/sing-box#4023](https://github.com/SagerNet/sing-box/issues/4023),
  closed "not planned"). Use an **Xray** client (v2rayNG on Android, or the `xray`
  binary) against the sing-box server. The app never sees this — it only dials the
  SOCKS5 the client exposes.

An example Xray client (SOCKS5 on `127.0.0.1:1080` → VLESS+REALITY out):

```json
{
  "inbounds": [ { "listen": "127.0.0.1", "port": 1080, "protocol": "socks",
                  "settings": { "udp": false } } ],
  "outbounds": [ { "protocol": "vless",
    "settings": { "vnext": [ { "address": "<vps-host>", "port": 443,
      "users": [ { "id": "<UUID>", "encryption": "none" } ] } ] },
    "streamSettings": { "network": "tcp", "security": "reality",
      "realitySettings": { "serverName": "www.cloudflare.com", "fingerprint": "chrome",
        "publicKey": "<PublicKey>", "shortId": "<short-id>" } } } ]
}
```

## c. Local test flow (`test/e2e/local-stack.sh`)

The native ergo + soju stack has a REALITY layer for proving the substrate
end-to-end before touching a VPS.

```sh
./test/e2e/local-stack.sh up            # ergo + soju (as usual)
./test/e2e/local-stack.sh obfs-up       # sing-box REALITY server :8443 + Xray SOCKS5 client :1080
./test/e2e/local-stack.sh obfs-validate # socket-level proof: IRC/TLS to soju THROUGH reality
./test/e2e/local-stack.sh obfs-down     # stop the reality layer
```

`obfs-up` generates a fresh keypair/short-id/uuid, writes both configs under
`/tmp/motd-stack/obfs/`, runs the **sing-box server** (REALITY inbound on `:8443`,
handshake `www.cloudflare.com`) and the **Xray client** (SOCKS5 inbound on
`127.0.0.1:1080`, VLESS+REALITY outbound to the server), does `adb reverse
tcp:1080`, and prints the `vless://` share link. It refuses to start if `:8443` or
`:1080` is already held.

`obfs-validate` opens a SOCKS5 CONNECT to `127.0.0.1:6697` (soju) **through** the
proxy, completes soju's TLS 1.3 handshake, and reads soju's `CAP LS` banner —
proving the server + client REALITY configs carry IRC end-to-end.

Overrides: `MOTD_SOCKS_PORT`, `MOTD_REALITY_PORT`, `MOTD_HANDSHAKE_DOMAIN` (e.g.
if `1080` is taken locally, `MOTD_SOCKS_PORT=11080 ./test/e2e/local-stack.sh
obfs-up`).

### Drive the app against it (manual)

The socket-level `obfs-validate` is the authoritative proof. To also exercise the
app UI against the tunnel:

1. `./test/e2e/local-stack.sh up && ./test/e2e/local-stack.sh obfs-up`
   (if `1080` is free; else set `MOTD_SOCKS_PORT` and use that port below).
2. `nix develop -c ./gradlew :app:installDebug`.
3. Onboard the soju bouncer as usual (Host `127.0.0.1`, Port `6697`, TLS on,
   Username/Password `motd`/`motdtest`; Trust the self-signed cert).
4. Open the network's settings → **Connection / Obfuscation** → **SOCKS5**,
   Host `127.0.0.1`, Port `1080` → Save (check FAB). Editing the proxy restarts
   the connection (it is part of the connection fingerprint).
5. The network reconnects; open `##motdtest` and confirm seeded history — the
   traffic is now flowing through VLESS+REALITY to soju.
