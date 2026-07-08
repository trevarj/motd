# 06 — UnifiedPush + soju webpush (WP9)

Battery-friendly delivery: sockets close, soju pushes encrypted single-line IRC payloads via
Web Push (RFC 8030/8291) to a UnifiedPush endpoint; the app decrypts, stores, notifies.
Entire feature is gated behind `DeliveryMode.UNIFIED_PUSH` and degrades to persistent-socket
mode on any failure — it must never block v1.

## Flow

1. Settings → delivery mode "UnifiedPush": call
   `UnifiedPush.registerApp(context)` (distributor picker via
   `UnifiedPush.getDistributors` / `saveDistributor` — use the connector's built-in dialog
   helper if only one distributor).
2. `MotdPushReceiver : MessagingReceiver` callbacks:
   - `onNewEndpoint(endpoint)`: generate (or load) our keypair (below), persist endpoint;
     for every connected network with cap `soju.im/webpush` (the soju root connection):
     `client.webpushRegister(endpoint, p256dhPublicUncompressed, authSecret)`.
     On confirmation (labeled ACK) → flip ConnectionManager into push mode (plans/05).
   - `onMessage(bytes, instance)`: decrypt (below) → one IRC line → `IrcMessage.parse` →
     hand to `PushEventHandler`: map via the same event mapper rules and feed the resulting
     `IrcEvent` through the `IrcEventSink` contract interface (EventProcessor implements it);
     post MessagingStyle notification (same rules as plans/05).
   - `onUnregistered`: `webpushUnregister`, revert deliveryMode to PERSISTENT_SOCKET,
     restart service.
3. App comes to foreground while in push mode: ConnectionManager quick-connects and runs the
   normal catch-up (`CHATHISTORY AFTER`), then disconnects again when backgrounded >30s
   (push mode keeps sockets only while visible).

## Keys (`WebPushCrypto`, pure JCA — no dependencies)

- Generate P-256 keypair (`KeyPairGenerator.getInstance("EC")`, curve `secp256r1`) + 16-byte
  random `auth` secret; persist via the `PushPrefs` contract interface (WP4 implements it
  over DataStore; see plans/04).
- `p256dh` sent to soju = 65-byte uncompressed point `04 || X || Y`, base64url.

## Decryption — RFC 8291 `aes128gcm` (the hard part; spec steps verbatim)

Input: `body` bytes from `onMessage`. Content-coding header is IN the body (RFC 8188):

```
header = salt(16) || rs(4, big-endian) || idlen(1) || keyid(idlen)
   keyid = app-server (sender) uncompressed P-256 public key (65 bytes) for webpush
ciphertext = rest (single record for webpush; rs >= ciphertext length)
```

Derivation (HMAC-SHA256 HKDF; `ua_priv/ua_pub` = our keypair, `as_pub` = keyid):

```
ecdh_secret = ECDH(ua_priv, as_pub)                                  # 32 bytes
PRK_key  = HKDF-Extract(salt = auth_secret, IKM = ecdh_secret)
key_info = "WebPush: info" || 0x00 || ua_pub(65) || as_pub(65)
IKM      = HKDF-Expand(PRK_key, key_info, 32)
PRK      = HKDF-Extract(salt = salt, IKM = IKM)
CEK      = HKDF-Expand(PRK, "Content-Encoding: aes128gcm" || 0x00, 16)
NONCE    = HKDF-Expand(PRK, "Content-Encoding: nonce"     || 0x00, 12)
plaintext_padded = AES-128-GCM-Decrypt(CEK, NONCE, ciphertext)       # 16-byte tag at end
plaintext = strip padding: remove trailing bytes after last 0x02 delimiter
            (record format: plaintext || 0x02 || zero padding; last record uses 0x02)
```

JCA pieces: `KeyAgreement.getInstance("ECDH")`, `Mac.getInstance("HmacSHA256")` (HKDF is two
short helper functions — implement, don't import), `Cipher.getInstance("AES/GCM/NoPadding")`
with `GCMParameterSpec(128, nonce)`. Reconstruct sender `ECPublicKey` from the uncompressed
point via `ECPointUtil`-style manual affine coords + `KeyFactory.getInstance("EC")` with the
named-curve `ECParameterSpec` obtained from a throwaway generated key.

## Acceptance gate: RFC 8291 test vector

`WebPushCryptoTest` MUST reproduce RFC 8291 Appendix A exactly — decrypting the sample message
with the sample keys yields `"When I grow up, I want to be a watermelon"`. The implementing
agent MUST fetch the vector values (ua private/public key, auth secret, and the full encrypted
message body) from https://www.rfc-editor.org/rfc/rfc8291 (Appendix A) at implementation time
and embed them as base64url constants in the test — do not trust any transcription found
elsewhere, including this document. A second test decrypts a message produced by our own
inverse (implement a package-private `encrypt` used only by tests) to cover the padding path.

## WP9 files

`push/MotdPushReceiver.kt`, `push/WebPushCrypto.kt`, `push/WebPushRegistrar.kt` (endpoint/key
persistence + per-network REGISTER/UNREGISTER orchestration), `push/PushEventHandler.kt`.
WP9 depends only on WP1 contracts: `IrcMessage.parse`, `IrcEventSink`, `PushPrefs`,
`ConnectionManager`. Real wiring to the WP4/WP5 implementations happens in WP10.
