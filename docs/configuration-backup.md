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
- Global message background opacity and blur, saved independently of each other and message layout
- The effects master and independent header/input/chat-list/menu/sheet/drawer/mini-player/floating-button opacity and blur pairs
- Independent global opacity/blur link flags, including mixed values saved before the first linked edit
- Fool membership, default visibility, and sparse per-fool visibility/notification preferences

## Excluded

- Room primary keys, messages, buffers, read markers, drafts, cached previews,
  upload history, deletion tokens, generated push endpoints, push keypairs,
  certificate pins, STS policy state, diagnostics, history cursors, and other
  runtime/cache state
- Per-chat message layout, background-opacity and blur overrides; these remain local to each durable chat
- Android KeyChain aliases. A network that used a client certificate is imported
  disconnected until the user selects a certificate on the destination device.
- Custom wallpaper image files and their private local filenames. The retained builtin wallpaper
  and intensity travel instead; importing a backup restores that builtin fallback.

## Import

The importer validates the complete document before mutating local state.
Unsupported versions, malformed hierarchy, invalid ports, invalid WebSocket
URLs, oversized files, truncated encrypted payloads, wrong passwords, and
tampered ciphertext fail without applying the backup.

Global blur and surface effects are additive appearance data. An older present appearance restores
message blur Off (0), enables the effects master, and uses each surface's original defaults (chat-list
60%/100 blur; new surfaces 100%/0 blur). Values are clamped to 0–100 on restore. A backup with no
appearance section leaves all local appearance untouched; no backup-envelope version change is needed.
Both link flags default to false in older appearance objects. Restore writes message scalars,
interface pairs, master and link flags atomically without triggering interactive linking, even when
the destination has links enabled. An absent appearance object also preserves local link flags.

`MERGE` updates matching networks in place and keeps local-only networks.
`REPLACE` updates matching networks in place, adds missing networks, and deletes
local-only network trees with their local history through existing Room
cascades.

Importing general settings replaces per-fool overrides, including for retained fools. Older backups
without per-fool preferences reset them to inherited visibility with both notification permissions
off; saved `DIMMED`, `BLUR`, `COLLAPSE`, and `HIDE` names retain their meaning.
`BLUR` is available globally or per fool and conceals body text, including translations and
transcripts, until its own reveal tap. Authors, avatars, timestamps, replies, media, audio controls
and reactions remain sharp and usable. Show fools and exact-message jumps remain temporary
chat-visit reveals; Restore conceals immediately. With effects enabled on Android 12+, body text
uses thick native blur; Effects Off and older Android use an opaque text-range cover.
Concealed text and body links remain inaccessible to selection, accessibility and activation until
reveal. Search previews and opted-in Mentions rows use the same choice; notification permissions
remain independent. Server search applies account identity tags as well as nicknames; untagged
results retain nickname matching.

Appearance includes independent input, avatar, chat-card, and media corner presets.
Older appearance objects without these fields restore all four to `DEFAULT`;
backups without an appearance object leave local appearance settings untouched.
The appearance `uiIconSet` enum (`MATERIAL` or `LUCIDE`) is exported and explicitly
restored too. Older appearance objects without it restore `MATERIAL`; missing
appearance objects still leave the local icon set untouched. No format-version
change is required for these defaulted additive fields.

Legacy `splashScreenAnimation` values (`EDGE_TRACE`, `SOFT_WASH`, `TRAVELING_PULSE`,
`STATIC`) still round-trip and restore for compatibility, although startup now
uses a single non-configurable spark cut. Missing fields default to `EDGE_TRACE`;
an absent appearance object leaves the stored legacy choice untouched.

Credentials-excluded imports keep already stored local credentials on matching
networks. New networks that require omitted SASL passwords, server passwords,
VLESS links, or client certificates are imported with `autoConnect = false` and
durable pending credential requirements.
