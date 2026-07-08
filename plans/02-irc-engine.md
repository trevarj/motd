# 02 — `:irc` protocol engine

Pure JVM module. Zero Android imports. All logic testable with `FakeTransport` (a
`TransportFactory` returning scripted line flows). Public API is frozen in `10-contracts.md`.

## Message parsing (`proto/`)

Grammar (modern IRCv3 form):

```
message    =  ['@' tags SPACE] [':' source SPACE] command [params]
tags       =  tag *[';' tag]
tag        =  key ['=' value]        ; key may be prefixed '+' (client tag) and/or vendor 'a.b/'
source     =  nick ['!' user] ['@' host]  |  servername
command    =  1*letter | 3digit
params     =  *(SPACE middle) [SPACE ':' trailing]
```

Rules:
- Input lines arrive without CRLF (transport strips it).
- **Tag value escaping** (both directions, exact table):

  | escaped | value |
  |---|---|
  | `\:` | `;` |
  | `\s` | ` ` (space) |
  | `\\` | `\` |
  | `\r` | CR |
  | `\n` | LF |
  | `\<other>` | `<other>` (drop the backslash) |
  | trailing lone `\` | dropped |

- A tag without `=` has value `""`. `key=` also means `""`.
- Trailing param starts at the first `:` after the command; it may contain spaces and be empty.
  A message can have up to 15 params; don't enforce, just parse greedily.
- Limits (serialize-time asserts, parse-time tolerance): 512 bytes for the non-tag portion
  incl. CRLF; 8191 bytes for the tag section. `serialize()` throws `IllegalArgumentException`
  if exceeded — callers (composer) are responsible for splitting long messages
  (split at ~400 bytes of UTF-8 text on word boundaries; the app layer does this).
- Command is uppercased on parse. Numerics stay 3-digit strings.
- `Isupport.normalize()`: `rfc1459` casemapping lowers `A-Z` plus `[]\~` → `{}|^`;
  `ascii` lowers only `A-Z`. Use for ALL map keys involving nicks/channels.

## Transport (`transport/OkioLineTransport`)

- Plain `java.net.Socket` or `SSLSocket` from `SSLContext` (default context when none injected;
  app injects one with a client cert for SASL EXTERNAL).
- Connect with 15s timeout; enable TCP keepalive; `SO_TIMEOUT = 0` (reads block; watchdog
  handles death).
- okio `BufferedSource.readUtf8LineStrict(limit = 16384)` loop on `Dispatchers.IO`, emitting
  into `incoming` (a `channelFlow`); completes normally on EOF, rethrows socket exceptions.
- `send()` serializes writes with a `Mutex`; writes `line + "\r\n"` and flushes.
- SNI + hostname verification on by default for TLS (use `HttpsURLConnection.getDefaultHostnameVerifier()`
  equivalent via `SSLParameters.endpointIdentificationAlgorithm = "HTTPS"`).

## Registration state machine (`client/RegistrationStateMachine`)

```
Connecting ──transport.connect()──► Registering:
  1. send: CAP LS 302
  2. send: NICK <nick> ; USER <username> 0 * :<realname>
  3. collect CAP LS (multiline: params[2] == "*" means more coming)
  4. compute request set = (tier1 ∪ tier2 ∪ tier3 ∪ extraCaps) ∩ advertised   (tiers: plans/03)
  5. send: CAP REQ :<batch of caps>   (split at 400 bytes; multiple REQs allowed)
  6. on CAP ACK/NAK record acked caps
  7. if sasl acked and config.sasl != NONE: AUTHENTICATE flow (below); await 903/904/905
     - 904/905 → state = Failed(reason, fatal = true)
  8. if config.bouncerNetId != null and "soju.im/bouncer-networks" acked:
       send: BOUNCER BIND <netId>            ; MUST precede CAP END
  9. send: CAP END
 10. await 001 (RPL_WELCOME); accumulate 005 into Isupport until 005s stop (or 376/422)
 11. state = Ready(nick, caps, isupport) ; emit Registered
```

Runtime `CAP NEW` → REQ any tier cap newly advertised; `CAP DEL` → emit `CapsChanged`.
Handle 433 (nick in use) during registration by appending `_` (max 3 tries, then Failed).

### SASL (`client/SaslAuthenticator`)

- `AUTHENTICATE PLAIN` → server `AUTHENTICATE +` → send
  `base64("<authzid>\0<authcid>\0<password>")` using saslUser for both zid/cid; chunk at 400
  bytes: send 400-byte chunks, then `AUTHENTICATE +` if the payload was a multiple of 400.
- `AUTHENTICATE EXTERNAL` → server `+` → send `AUTHENTICATE +` (empty response; identity from
  the client TLS cert).
- Success: 903. Failure: 904/905/906/907 → fatal Failed (never retry-loop bad credentials).

## Labeled response (`client/LabelCorrelator`)

- Monotonic counter label `motd-<n>` per connection.
- `sendLabeled(msg)`: attach `label` tag, register a `CompletableDeferred`, send.
- Response completion rules:
  - a message carrying the label and NOT opening a batch → single-message response, complete.
  - `BATCH +ref type ...` carrying the label → collect every message with `batch=ref`
    (including nested batches, reassembled recursively) until `BATCH -ref`, complete with list.
  - `ACK` with the label → complete with empty list.
  - `FAIL`/`ERR_*` with the label → complete exceptionally (`IrcCommandException(command, code, text)`).
- 30s timeout → complete exceptionally with `IrcTimeoutException`.

## Batch assembly (`ext/BatchAssembler`)

- Track open batches `ref → (type, params, buffered messages, parent)`; messages with a `batch`
  tag are routed to their batch, not emitted live.
- On close: `chathistory` batch → emit `IrcEvent.HistoryBatch(target, events)` (events mapped
  through the normal event mapper, each with `ctx.batchId` set); unknown batch types → flatten:
  emit contents as if live (still tagged with batchId).
- Nested batches attach to their parent and flatten into it on close.

## Event mapping (`client/` internal)

Map inbound `IrcMessage` → `IrcEvent` per `10-contracts.md`. Notables:
- `serverTime`: parse `time` tag (ISO 8601 UTC, e.g. `2026-07-08T12:34:56.789Z`) via
  `java.time.Instant`; fall back to `System.currentTimeMillis()` when absent.
- `isSelf`: source nick == current nick (track own nick through NICK changes).
- CTCP ACTION: PRIVMSG text `\x01ACTION <t>\x01` → `ChatKind.ACTION` with text `<t>`.
  Other CTCP: respond to `\x01VERSION\x01` with NOTICE `\x01VERSION MOTD\x01` (hardcoded
  reply string); ignore the rest.
- NAMES: accumulate 353 params (with `multi-prefix` + `userhost-in-names` parsing) until 366,
  then emit `Names`.
- `TAGMSG`: extract `+typing`, `+draft/react`, `+draft/reply` client tags → `TagMessage`.
- `MARKREAD <target> timestamp=<ts>` (or `*`) → `ReadMarker`.
- `BOUNCER NETWORK <netid> <attrs|*>` → `BouncerNetworkState` (attr format: `k=v;k2=v2`,
  values tag-escaped; `*` means deleted).
- Everything unmapped → `IrcEvent.Raw`.

## Ping watchdog (`client/PingWatchdog`)

- On 90s of inbound silence send `PING motd-<epoch>`; if no line at all arrives within a
  further 30s, `stop()` the transport → state `Disconnected` (reconnect is the app's job).
- Always answer server `PING x` with `PONG x` immediately (before event mapping).

## Typing outbox (`ext/TypingOutbox`)

- `sendTyping(target, "active")` throttled: at most one TAGMSG per (target, state) per 3s;
  "done" always sent immediately and clears the throttle window.

## Unit tests (WP2 + WP3 acceptance; all with FakeTransport / plain functions)

parser: tag escape round-trips (every table row), missing source, numeric commands, trailing
edge cases (`:` inside trailing, empty trailing), vendored+client tag keys, oversize serialize
throws. isupport: PREFIX/CHATHISTORY/CASEMAPPING parsing, normalize() both mappings.
client (scripted conversations): full registration happy path (CAP 302 multiline LS + SASL
PLAIN incl. exact base64 + 900/903 + 001/005 → Ready); SASL failure 904 → fatal Failed;
BOUNCER BIND ordering (BIND before CAP END, after ACK); labeled PRIVMSG echo correlation;
labeled chathistory batch reassembly incl. nested batch; CAP NEW mid-session; 433 nick retry;
watchdog timeout → Disconnected; TypingOutbox throttling (virtual time via coroutines-test).
