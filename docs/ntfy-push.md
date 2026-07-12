# Push notifications with ntfy

MOTD can use [ntfy](https://ntfy.sh/) as its UnifiedPush distributor. This keeps Firebase out of
MOTD and works with the Google-free `foss` APK. Your soju bouncer must support
`soju.im/webpush`.

## Install ntfy

Install the ntfy Android app from [F-Droid](https://f-droid.org/packages/io.heckel.ntfy/). The
F-Droid build contains no Firebase and maintains its own instant-delivery connection. Grant its
notification permission and allow it to run in the background.

You do not need to create an ntfy topic or account for UnifiedPush when using the public
`https://ntfy.sh` server. MOTD creates and manages its private push endpoints automatically.

## Enable UnifiedPush in MOTD

1. Open ntfy once after installing it.
2. Connect MOTD to your soju bouncer. MOTD must connect at least once to detect Web Push support.
3. Open **Settings → Message delivery**.
4. Select **UnifiedPush**.
5. If Android asks which distributor to use, select **ntfy**. When ntfy is the only installed
   distributor, MOTD selects it automatically.
6. Leave MOTD open briefly while it registers one push endpoint for each auto-connect network.
7. Background MOTD and send a message from another IRC client to verify that a notification
   arrives.

MOTD closes its IRC sockets only after every eligible network has a registered endpoint. Push
payloads are encrypted by soju and decrypted on your device; ntfy cannot read the IRC message.

## Use a self-hosted ntfy server

Self-hosting is optional. In the ntfy app, open **Settings**, change the default server to your
HTTPS ntfy URL, and add credentials under **Manage users** if the server requires authentication.
Then return to MOTD and select **UnifiedPush** again so it obtains endpoints from that server.

The phone must be able to maintain a connection to the self-hosted server. Use a valid HTTPS
certificate where possible; current ntfy Android releases also support self-signed and client
certificates.

See the [ntfy phone documentation](https://docs.ntfy.sh/subscribe/phone/) and
[UnifiedPush ntfy guide](https://unifiedpush.org/users/distributors/ntfy/) for server-specific
configuration.

## Battery settings

For reliable background delivery:

- Allow notification permission for both ntfy and MOTD.
- Exempt ntfy from battery optimization on devices that aggressively stop background apps.
- Allow ntfy background data and automatic startup when those controls exist.
- Do not force-stop ntfy; Android prevents a force-stopped app from receiving messages.

The battery exemption shown in MOTD primarily protects persistent IRC connections. In
UnifiedPush mode, ntfy is the application that must remain reachable in the background.

## Troubleshooting

- **UnifiedPush is disabled in MOTD:** connect to soju first. The bouncer must advertise
  `soju.im/webpush`; plain IRC servers cannot provide push delivery.
- **MOTD says no distributor is installed:** open ntfy, confirm UnifiedPush is enabled in its
  settings, then restart MOTD or select UnifiedPush again.
- **No notification arrives:** confirm ntfy reports an active connection and review Android's
  battery, background-data, and notification permissions.
- **It worked until ntfy was force-stopped:** reopen ntfy. Android intentionally blocks delivery
  to force-stopped applications.
- **Messages appear only after opening MOTD:** the push endpoint may not be registered in soju.
  Switch temporarily to **Persistent connection**, reconnect, then select **UnifiedPush** again.
- **Multiple distributors are installed:** Android or the UnifiedPush connector may retain a
  previous distributor. Remove or disable the old distributor, then reselect UnifiedPush.
- **Self-hosted delivery fails:** verify the ntfy URL, TLS certificate, login, reverse proxy
  WebSocket/streaming support, and that the phone can reach the server outside Wi-Fi.

The public ntfy service has its own usage policy and availability. Self-host ntfy if you need
control over retention, accounts, or service reliability.
