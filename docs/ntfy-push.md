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
6. Leave MOTD open until the status card says **UnifiedPush active**. MOTD first obtains an
   endpoint, then waits for soju to confirm that its encrypted test delivery succeeded.
7. Background MOTD and send a message from another IRC client to verify that a notification
   arrives.

MOTD closes an IRC socket only after soju acknowledges the current endpoint for that network.
Networks without Web Push support, or whose registration fails, remain connected through MOTD's
foreground service. The setting remains UnifiedPush and the status card reports this as socket
fallback rather than silently changing modes. Push payloads are encrypted by soju and decrypted on
your device; ntfy cannot read the IRC message.

Soju uses Web Push for direct messages and channel highlights. Ordinary channel traffic is fetched
through CHATHISTORY when MOTD returns to the foreground. Foregrounding always reconnects first, runs
catch-up, and keeps the socket available while you are using the app.

The status card distinguishes endpoint setup, server verification, active delivery, partial socket
fallback, and actionable errors. **Retry** re-runs endpoint registration without discarding your
delivery-mode choice. If more than one distributor is installed, choose one explicitly; **Change
distributor** safely cleans up the previous instances before requesting replacements.

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
- **Messages appear only after opening MOTD:** inspect the status card. Use **Retry** if it reports
  endpoint or verification failure; affected networks remain socket-backed until verification.
- **Multiple distributors are installed:** use **Choose distributor** or **Change distributor** in
  the status card. MOTD never silently picks one when the choice is ambiguous.
- **Self-hosted delivery fails:** verify the ntfy URL, TLS certificate, login, reverse proxy
  WebSocket/streaming support, and that the phone can reach the server outside Wi-Fi.

The public ntfy service has its own usage policy and availability. Self-host ntfy if you need
control over retention, accounts, or service reliability.

## Local physical-device verification

The IRC network and bouncer remain local; only encrypted Web Push delivery uses public `ntfy.sh`.
The destructive setup is hard-gated to the debug package and never clears the release app.

```sh
./test/e2e/local-stack.sh up
nix develop -c ./gradlew :app:assembleFossDebug
SERIAL=<device> \
MOTD_APK=app/build/outputs/apk/foss/debug/app-foss-debug.apk \
MOTD_SOJU_HOST=127.0.0.1 MOTD_SOJU_USER=motd MOTD_SOJU_PASS=motdtest \
E2E_PHASES="a k" nix develop -c ./test/e2e/runbook.sh
```

Phase K verifies server acknowledgement, the 30-second socket hand-off, tagged DM and highlight
notifications, notification navigation, cold-process delivery via `am kill`, and forced-idle
delivery. It uses `am kill`, never force-stop, because Android intentionally disables receivers for
a force-stopped application. Device-idle state is restored even when the run fails.
