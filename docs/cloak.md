# Connecting through CLoak

motd interoperates with [CLoak](https://github.com/parenworks/cloak) as a
standard IRC network. This was verified against CLoak
[v0.4.0](https://github.com/parenworks/cloak/releases/tag/v0.4.0) on
2026-07-16, including live messages in both directions and buffered-message
playback after reconnecting.

## Before connecting

Configure CLoak with a user and at least one network. Current CLoak sources use
`$XDG_CONFIG_HOME/cloak/config.lisp`, or `~/.config/cloak/config.lisp` when
`XDG_CONFIG_HOME` is unset. They retain `~/.cloak/config.lisp` as a legacy
fallback. CLoak's own [README](https://github.com/parenworks/cloak#configuration)
documents the available listener, user, and network settings.

Expose CLoak's IRC listener to the phone and use TLS for any connection that
leaves the bouncer host. The listener's certificate must be valid for the host
name entered in motd.

## Add the connection in motd

Choose **IRC network**, not the guided bouncer option, and enter:

- **Host and port:** CLoak's IRC listener address and port.
- **TLS:** On for a normal remote connection.
- **Nick and username:** The identity motd should present to CLoak. CLoak uses
  the server password, rather than these fields, to select the account and
  upstream network.
- **Server password:** `username/network:password`. For example, user `alice`,
  network `libera`, and password `correct-horse` become
  `alice/libera:correct-horse`.
- **SASL:** None. CLoak v0.4.0 authenticates downstream clients with IRC
  `PASS`, not downstream SASL.

Create one motd network entry for each CLoak upstream network, changing the
network segment of the server password for each entry. Network names must match
CLoak's configuration exactly.

## What works

CLoak v0.4.0 and motd negotiate `batch`, `message-tags`, and `server-time`.
CLoak keeps the upstream IRC connection alive and replays buffered messages
when motd reconnects. No manual history request is needed.

CLoak v0.4.0 does not advertise downstream SASL, `draft/chathistory`,
`draft/read-marker`, `soju.im/bouncer-networks`, or `soju.im/webpush`. As a
result:

- reconnect playback comes from CLoak automatically rather than motd's
  infinite-scrollback protocol;
- each CLoak network needs its own motd entry;
- cross-device read-marker sync and bouncer Web Push are unavailable.

## Troubleshooting

- **Password rejected / numeric 464:** Verify the complete
  `username/network:password` value and make sure it is in **Server password**,
  not the SASL password field.
- **One network works but another does not:** Check the network name and case in
  CLoak's configuration, then create a separate motd entry for it.
- **TLS connection fails:** Use the certificate's host name, confirm the phone
  trusts its issuer, and verify that the configured port is CLoak's IRC
  listener rather than its web interface.
- **Old messages are not replayed:** Enable and configure CLoak's buffering
  modules; playback is provided by CLoak rather than requested with
  `CHATHISTORY`.

The compatibility check is an opt-in pure-JVM integration test at
`IrcClientCloakIntegrationTest`. It connects a real motd IRC client through a
CLoak fixture backed by the repository's local Ergo stack, verifies two-way
messaging, disconnects the client, sends a message during the gap, and asserts
that CLoak replays it exactly once with server time preserved.
