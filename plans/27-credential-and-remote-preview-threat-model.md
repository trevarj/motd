# Credential storage and remote preview threat model

Date: 2026-07-15
Status: investigation complete; behavior changes require the product decisions
listed below

## Scope and current boundaries

The application sandbox currently holds:

- Room `networks.saslPassword` and `networks.obfsLink` values in plaintext. A
  VLESS + REALITY link includes the connection UUID and complete transport
  configuration.
- Preferences DataStore WebPush private/auth key material and per-network
  capability endpoints.
- Dormant FCM subscription endpoint, token, and management secret values.
- Attachment upload deletion tokens in a separate Preferences DataStore.
- Chat content, remote avatar URLs, certificate pins, and STS policies. The
  latter two are integrity policy rather than authentication secrets.

`android:allowBackup="false"` is reinforced by legacy and Android 12+
exclusions for the database and DataStore domains, including device transfer.
That prevents supported backup/export paths but does not encrypt the files at
rest. Android's per-app sandbox and platform full-disk/file encryption remain
the primary current controls.

Automatic remote content has three paths:

1. Link metadata uses `HttpURLConnection`, follows redirects automatically,
   accepts only a final `text/html` response, applies five-second timeouts, and
   caps the body at 512 KiB. It does not validate every redirect target.
2. Message image URLs, OG image URLs, and shared avatar URLs load through
   Coil. The URL source or OG document can be controlled by a remote IRC user.
3. Both link and image preview preferences default on. Rendering a received
   message can therefore disclose the device IP address, fetch timing, and
   reachability of local services without an explicit tap.

Attachment uploads and an explicit user tap on a link are separate user-driven
network actions. They should validate their own endpoints but are not automatic
preview requests.

## Assets and attacker capabilities

Protected assets are IRC/bouncer passwords, VLESS credentials, WebPush
decryption/auth material, capability endpoints, dormant relay management
credentials, upload deletion tokens, chat content, and the user's network
location/topology.

The model includes:

- an attacker who copies app-private files from an unlocked, rooted, debugged,
  or forensically acquired device;
- a malicious IRC participant who controls message URLs, remote avatar
  metadata, HTML/OG fields, DNS, and redirect responses;
- a hostile cleartext network path; and
- accidental logs, crash material, or backup/device-transfer behavior.

The model does not claim confidentiality from an attacker who can execute code
inside the live MOTD process or fully control the running OS. Such an attacker
can ask the application to decrypt a secret or observe it when MOTD connects.
Android Keystore still improves resistance to offline extraction because the
wrapping key is non-exportable, but it is not a process-compromise boundary.

## Proposed credential envelope

Use a small injected `SecretCipher` boundary implemented directly with Android
Keystore; do not add SQLCipher or deprecated convenience security libraries.

- Generate one non-user-authenticated AES-256-GCM key under a versioned app
  alias. Background IRC reconnect and push registration must work after normal
  boot without opening an activity or showing an authentication prompt.
- Generate a fresh 96-bit IV per value. Bind ciphertext with AAD containing the
  storage domain, record identity, field name, and envelope version so values
  cannot be swapped between networks or purposes.
- Persist a self-describing `enc:v1:` base64url envelope. Encryption/decryption
  runs on the injected IO dispatcher. Unit tests use a deterministic fake
  cipher; Android tests cover Keystore creation and round trips.
- Encrypt SASL passwords, VLESS links, WebPush private/auth keys and capability
  endpoints, FCM management/token material if that flavor is reactivated, and
  attachment deletion tokens. Public keys, ordinary endpoint hosts, cert pins,
  and display metadata remain plaintext.

### Compatible migration

Readers must accept legacy plaintext and `enc:v1` during migration. A
single-owner startup migration should encrypt each store transactionally,
verify the round trip, then remove the plaintext representation. Writes switch
to ciphertext immediately. Room schema history and DataStore key names remain
compatible; a schema change is only needed if a separate network-secret table
is chosen during implementation.

If the Keystore key is missing, permanently invalidated, or ciphertext fails
authentication, preserve networks, buffers, messages, and non-secret settings.
Mark only the affected connection/delivery credential as requiring re-entry;
do not silently regenerate a VLESS or SASL secret. WebPush may generate a new
keypair and subscription only after a live authenticated connection can
unregister/re-register it. Upload records remain visible but deletion is
disabled when their token cannot be recovered.

## Proposed automatic-preview policy

One policy must be shared by link metadata, inline/OG images, shared avatars,
and every redirect:

- parse structurally and permit only absolute `http` or `https` URLs with a
  non-empty host;
- reject user-info/embedded credentials, fragments for request identity, and
  malformed or ambiguous authorities;
- follow at most five redirects manually and apply the complete policy to each
  target before connecting;
- send no cookies, authorization, or referrer headers and do not accept
  server-set cookies into a shared jar;
- resolve every target and reject unspecified, loopback, link-local,
  multicast, carrier-grade NAT, private IPv4, unique-local IPv6, IPv4-mapped
  equivalents, and metadata-service addresses when public-only mode applies;
- retain existing time, response-type, and body/bitmap bounds, and cache only
  by the normalized validated URL.

DNS validation reduces accidental SSRF but cannot make a hostile network safe
from rebinding unless the actual connection is pinned to the validated address
while preserving TLS hostname verification. The implementation design must
test that boundary rather than presenting a preflight DNS check as complete
protection.

Android recommends parsing a URI and validating both scheme and authority, and
Android Keystore provides a non-exportable application key boundary:

- <https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading>
- <https://developer.android.com/privacy-and-security/keystore>
- <https://developer.android.com/privacy-and-security/cryptography>

## Product decisions required

The following choices affect compatibility and cannot be inferred from a
security-only refactor:

1. **Credential recovery.** Recommended: adopt the Keystore envelope above,
   do not require interactive user authentication, and preserve history while
   asking for only unrecoverable secrets again. Alternative: retain plaintext
   for maximum forensic/recovery simplicity.
2. **Intranet previews.** Recommended: automatic previews are public-network
   only; loopback/private/intranet URLs remain tappable and are fetched only
   after explicit user action. Alternative: retain automatic intranet previews
   and accept the SSRF/topology exposure.
3. **Default preview consent.** Recommended: default automatic link and image
   previews off for fresh installs while preserving the explicit/current value
   for existing installs. A migration marker plus existing-network check avoids
   treating an old absent preference as fresh-install consent. Alternative:
   keep defaults on and document the IP/timing disclosure.
4. **Cleartext previews.** Recommended: automatic preview fetches require
   HTTPS; an HTTP link remains tappable. Alternative: permit HTTP automatic
   previews and accept content manipulation and observer disclosure.

No storage or preview behavior should change until these four decisions are
accepted or replaced. Afterward, implement credential wrapping and preview
policy as separate, migration-tested changes so either can be reviewed and
recovered independently.
