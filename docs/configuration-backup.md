# Configuration Backup Format

MOTD configuration backups use a JSON document with the extension `.motdconfig`.
Version 1 is app-owned and portable across devices for configuration only.

## Envelope

Top-level fields:

- `formatVersion`: `1`
- `appVersion`: exporting app version
- `exportedAtEpochMillis`: export timestamp
- `mode`: `CREDENTIALS_EXCLUDED` or `ENCRYPTED_WITH_CREDENTIALS`
- `payload`: present only for credentials-excluded exports
- `encryptedPayload`: present only for credential-bearing exports

Credential-bearing exports encrypt the serialized payload with AES-256-GCM. The
key is derived from the export password with `PBKDF2WithHmacSHA256`, a random
16-byte salt, and 600,000 iterations. The GCM nonce is random and unique per
export. Envelope metadata is authenticated as associated data.

## Included

- Direct networks, bouncer roots, and bouncer children
- Parent/child relationships through export-local network ids
- Network ordering, TLS, WebSocket, identity, SASL metadata, server password
  marker, auto-connect intent, SOCKS/Tor/VLESS settings, and ZNC classification
- User-facing settings for appearance, chat behavior, previews, replies,
  upload backend configuration, avatar display, per-network self-avatar setting,
  and delivery mode

## Excluded

- Room primary keys, messages, buffers, read markers, drafts, cached previews,
  upload history, deletion tokens, generated push endpoints, push keypairs,
  certificate pins, STS policy state, diagnostics, history cursors, and other
  runtime/cache state
- Android KeyChain aliases. A network that used a client certificate is imported
  disconnected until the user selects a certificate on the destination device.
- Custom wallpaper image files and their private local filenames. The retained builtin wallpaper
  and intensity travel instead; importing a backup restores that builtin fallback.

## Import

The importer validates the complete document before mutating local state.
Unsupported versions, malformed hierarchy, invalid ports, invalid WebSocket
URLs, oversized files, truncated encrypted payloads, wrong passwords, and
tampered ciphertext fail without applying the backup.

`MERGE` updates matching networks in place and keeps local-only networks.
`REPLACE` updates matching networks in place, adds missing networks, and deletes
local-only network trees with their local history through existing Room
cascades.

Credentials-excluded imports keep already stored local credentials on matching
networks. New networks that require omitted SASL passwords, server passwords,
VLESS links, or client certificates are imported with `autoConnect = false` and
durable pending credential requirements.
