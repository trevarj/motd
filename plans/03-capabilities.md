# 03 — IRCv3 capabilities

Request policy: during registration, REQ every advertised cap from all tiers. Tier defines
what happens when a cap is missing, not whether we request it.

## Tier 1 — required core (Libera and soju both provide all of these)

| Cap | Behavior | Missing ⇒ |
|---|---|---|
| `sasl` | PLAIN + EXTERNAL per plans/02 | If config.sasl != NONE and cap absent → Failed(fatal) — never send passwords in the clear via NickServ fallback |
| `cap-notify` | handle CAP NEW/DEL | implied by CAP LS 302; no degradation path needed |
| `message-tags` | enables all tag features + TAGMSG | typing/react features silently disabled |
| `server-time` | serverTime from `time` tag | local receive time used; history ordering still works (dedupKey hash path) |
| `batch` | batch reassembly | live-only; chathistory unusable (it requires batch anyway) |
| `labeled-response` | request/response correlation | `sendLabeled` degrades: complete immediately with empty list; echo dedup falls back to heuristic (below) |
| `echo-message` | own PRIVMSGs come back with msgid/time | self-insert locally at send time with local clock; dedupKey stays the pending hash |

Echo dedup heuristic without labels: match incoming self-message to oldest pending row with
same target+text within 30s; if none, insert normally.

## Tier 2 — standard UX (request always, degrade silently)

`multi-prefix`, `away-notify`, `account-notify`, `account-tag`, `extended-join`, `chghost`,
`setname`, `userhost-in-names`, `invite-notify`.

`sts` (subset): on `sts` cap value `port=<p>,duration=<d>` over TLS, persist
(host → port, expiry) in DataStore; on future connects to that host force TLS on that port
until expiry. Over plaintext, immediately reconnect with TLS to the given port. (Implemented
app-side in ConnectionManager; `:irc` just surfaces the cap value in `Ready.caps` — cap values
are part of the `caps` set encoded as `name=value` when the LS carried a value.)

## Tier 3 — history & bouncer (light up on soju; plain networks degrade to local-only)

### `draft/chathistory` (+ `draft/event-playback`)

- ISUPPORT `CHATHISTORY=<n>` caps page size; clamp all requests to it (default 100 if absent).
- Request `draft/event-playback` only if `draft/chathistory` was ACKed (gives JOIN/PART/etc.
  in playback).
- Subcommands used (all via `sendLabeled`, response = `chathistory` batch):
  - `CHATHISTORY LATEST <target> * <limit>` — initial fill of an empty buffer
  - `CHATHISTORY BEFORE <target> timestamp=<ISO> <limit>` — infinite scroll (RemoteMediator)
  - `CHATHISTORY AFTER <target> timestamp=<ISO> <limit>` — reconnect catch-up (loop until page < limit)
  - `CHATHISTORY AROUND <target> msgid=<id> <limit>` — jump-to-search-hit
  - `CHATHISTORY TARGETS timestamp=<from> timestamp=<to> <limit>` — discover buffers with
    activity since last sync (response batch `draft/chathistory-targets`, lines
    `CHATHISTORY TARGETS <target> <ISO timestamp>`)
- Missing ⇒ `historyComplete` stays effectively true at the local-data boundary; UI shows
  "history unavailable on this network" footer instead of a spinner.

### `soju.im/bouncer-networks` (+ `soju.im/bouncer-networks-notify`)

- Detection also via ISUPPORT `BOUNCER_NETID=<id>` on a bound connection.
- Registration ordering is strict: CAP REQ/ACK → SASL → `BOUNCER BIND <netid>` → `CAP END`.
- Root (unbound) connection commands, all labeled:
  - `BOUNCER LISTNETWORKS` → batch `soju.im/bouncer-networks` of
    `BOUNCER NETWORK <netid> <attrs>` lines
  - `BOUNCER ADDNETWORK <attrs>` → `BOUNCER ADDNETWORK <netid>` reply (attrs:
    `name=...;host=...;port=...;tls=1;nickname=...` — values tag-escaped)
  - `BOUNCER DELNETWORK <netid>`
- With `-notify`, unsolicited `BOUNCER NETWORK` lines stream network add/update/delete and a
  `state=connected|connecting|disconnected` attr → EventProcessor mirrors into `networks` rows.

### `draft/read-marker`

- `MARKREAD <target> timestamp=<ISO>` sets; bare `MARKREAD <target>` gets; server replies/echoes
  `MARKREAD <target> timestamp=<ISO|*>` to ALL clients → cross-device read sync.
- Rule: only advance (server keeps the max; client mirrors that rule locally via
  `advanceReadMarker`). Missing ⇒ read state is local-only.

### `soju.im/webpush`

- ISUPPORT `VAPID=<base64url uncompressed P-256 public key>` — the server's VAPID key.
- `WEBPUSH REGISTER <endpoint> <keys>` where `<keys>` is tag-escaped
  `p256dh=<b64url>;auth=<b64url>` (our ECDH public key + 16-byte auth secret).
- `WEBPUSH UNREGISTER <endpoint>`.
- Server pushes an RFC 8030/8291 encrypted payload to the endpoint; decrypted plaintext is
  **exactly one IRC line** (no CRLF); tags may be stripped except `msgid`/`time`.
- Missing ⇒ UnifiedPush delivery mode unavailable; Settings shows why (persistent socket only).

## Client-only tags (need only `message-tags`)

- `+typing` — TAGMSG with values `active|paused|done`; send throttle 3s (plans/02); render
  "X is typing…" for 6s after last `active`, clear on `done`/message arrival.
- `+draft/react` + `+draft/reply` — react = TAGMSG carrying `+draft/react=<emoji>` and
  `+draft/reply=<target msgid>`. Reply-to on a PRIVMSG = `+draft/reply=<msgid>`.

## Explicitly out of v1

`draft/multiline`, `draft/message-redaction`, `monitor`, `draft/metadata`, WHOX.
